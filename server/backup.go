package main

import (
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

// 备份文件命名：studycheck-2026-10-30.db，按字典序即按时间序。
const backupPrefix = "studycheck-"

// orphanGrace 是清理无引用照片时的宽限期：只清早于该时长的文件，
// 避免把正在上传、还没写进数据库的照片误删。
const orphanGrace = 10 * time.Minute

// dataDir 返回数据库所在目录，备份就放在它的 backups 子目录里。
func (s *Server) dataDir() string {
	return filepath.Dir(s.cfg.DBPath)
}

func (s *Server) backupDir() string {
	return filepath.Join(s.dataDir(), "backups")
}

// backupPath 把用户传来的文件名收敛成 backups 目录内的绝对路径，
// 任何越界尝试（../、绝对路径）都直接拒绝。
func (s *Server) backupPath(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", errors.New("缺少备份文件名")
	}
	if strings.ContainsAny(name, `/\`) || strings.Contains(name, "..") {
		return "", errors.New("备份文件名不合法")
	}
	if !strings.HasPrefix(name, backupPrefix) || !strings.HasSuffix(name, ".db") {
		return "", errors.New("备份文件名不合法")
	}
	root, err := filepath.Abs(s.backupDir())
	if err != nil {
		return "", err
	}
	full := filepath.Join(root, name)
	if !strings.HasPrefix(full, root+string(os.PathSeparator)) {
		return "", errors.New("备份文件名不合法")
	}
	return full, nil
}

type backupInfo struct {
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	CreatedAt string `json:"created_at"`
}

// listBackups 返回备份列表，新的在前。
func (s *Server) listBackups() ([]backupInfo, error) {
	ents, err := os.ReadDir(s.backupDir())
	if errors.Is(err, os.ErrNotExist) {
		return []backupInfo{}, nil
	}
	if err != nil {
		return nil, err
	}
	out := make([]backupInfo, 0, len(ents))
	for _, e := range ents {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".db") {
			continue
		}
		st, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, backupInfo{
			Name:      e.Name(),
			Size:      st.Size(),
			CreatedAt: st.ModTime().Format(time.RFC3339),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name > out[j].Name })
	return out, nil
}

// createBackup 用 VACUUM INTO 生成一份一致快照，然后按保留份数删最老的。
// VACUUM INTO 不依赖外部命令，产出的就是完整数据库文件（含 WAL 内容）。
func (s *Server) createBackup() (backupInfo, error) {
	if err := os.MkdirAll(s.backupDir(), 0o755); err != nil {
		return backupInfo{}, err
	}
	day := time.Now().Format("2006-01-02")
	name := backupPrefix + day + ".db"
	// 同一天重复备份时累加序号：-02、-03…
	if _, err := os.Stat(filepath.Join(s.backupDir(), name)); err == nil {
		for i := 2; i < 100; i++ {
			cand := fmt.Sprintf("%s%s-%02d.db", backupPrefix, day, i)
			if _, err := os.Stat(filepath.Join(s.backupDir(), cand)); err != nil {
				name = cand
				break
			}
		}
	}
	full, err := s.backupPath(name)
	if err != nil {
		return backupInfo{}, err
	}
	tmp := full + ".tmp"
	if _, err := s.db.Exec(`VACUUM INTO ?`, tmp); err != nil {
		os.Remove(tmp)
		return backupInfo{}, err
	}
	if err := os.Rename(tmp, full); err != nil {
		os.Remove(tmp)
		return backupInfo{}, err
	}
	st, err := os.Stat(full)
	if err != nil {
		return backupInfo{}, err
	}
	info := backupInfo{Name: name, Size: st.Size(), CreatedAt: st.ModTime().Format(time.RFC3339)}
	s.pruneBackups()
	return info, nil
}

// pruneBackups 只保留最近 keep 份。
func (s *Server) pruneBackups() {
	keep := s.cfg.BackupKeep
	if keep <= 0 {
		return
	}
	list, err := s.listBackups()
	if err != nil || len(list) <= keep {
		return
	}
	for _, b := range list[keep:] {
		_ = os.Remove(filepath.Join(s.backupDir(), b.Name))
	}
}

// backupLoop 每晚定点备份一次；启动时若一份备份都没有，立即补一份，
// 保证"装了就用、睡着也有人在备份"。
func (s *Server) backupLoop() {
	hour, min := 3, 30
	if hm := strings.TrimSpace(s.cfg.BackupAt); hm != "" {
		var h, m int
		if n, err := fmt.Sscanf(hm, "%d:%d", &h, &m); err == nil && n == 2 && h >= 0 && h < 24 && m >= 0 && m < 60 {
			hour, min = h, m
		}
	}
	for {
		list, _ := s.listBackups()
		if len(list) == 0 {
			s.runBackupJob()
		}
		now := time.Now()
		next := time.Date(now.Year(), now.Month(), now.Day(), hour, min, 0, 0, now.Location())
		if !next.After(now) {
			next = next.AddDate(0, 0, 1)
		}
		timer := time.NewTimer(time.Until(next))
		<-timer.C
		s.runBackupJob()
	}
}

func (s *Server) runBackupJob() {
	info, err := s.createBackup()
	if err != nil {
		log.Printf("定时备份失败: %v", err)
		return
	}
	log.Printf("定时备份完成: %s（%.1f KB）", info.Name, float64(info.Size)/1024)
}

// ---------------- 管理端接口 ----------------

// GET /api/v1/system/backups —— 备份列表 + 配置
func (s *Server) handleListBackups(w http.ResponseWriter, r *http.Request) {
	list, err := s.listBackups()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取备份失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"enabled":  s.cfg.BackupEnabled,
		"keep":     s.cfg.BackupKeep,
		"at":       s.cfg.BackupAt,
		"dir":      s.backupDir(),
		"backups":  list,
		"last":     lastBackupName(list),
		"last_run": s.backupMarker(),
	})
}

func lastBackupName(list []backupInfo) string {
	if len(list) == 0 {
		return ""
	}
	return list[0].Name
}

// POST /api/v1/system/backups —— 立即备份
func (s *Server) handleCreateBackup(w http.ResponseWriter, r *http.Request) {
	info, err := s.createBackup()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "backup_failed", err.Error())
		return
	}
	s.markBackupRun()
	writeJSON(w, http.StatusCreated, info)
}

// GET /api/v1/system/backups/{name} —— 下载备份
func (s *Server) handleDownloadBackup(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	full, err := s.backupPath(name)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_name", err.Error())
		return
	}
	st, err := os.Stat(full)
	if err != nil || st.IsDir() {
		writeErr(w, http.StatusNotFound, "not_found", "备份不存在")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="`+filepath.Base(full)+`"`)
	http.ServeFile(w, r, full)
}

// DELETE /api/v1/system/backups/{name} —— 删除单个备份
func (s *Server) handleDeleteBackup(w http.ResponseWriter, r *http.Request) {
	full, err := s.backupPath(r.PathValue("name"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_name", err.Error())
		return
	}
	if err := os.Remove(full); err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "备份不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

var restoreMu sync.Mutex

// POST /api/v1/system/backups/{name}/restore —— 用备份覆盖当前数据库
// 覆盖前先把当前库存一份 safety 备份，恢复失败可以退回去。
func (s *Server) handleRestoreBackup(w http.ResponseWriter, r *http.Request) {
	if !restoreMu.TryLock() {
		writeErr(w, http.StatusConflict, "busy", "正在恢复中，请稍候")
		return
	}
	defer restoreMu.Unlock()

	full, err := s.backupPath(r.PathValue("name"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_name", err.Error())
		return
	}
	if _, err := os.Stat(full); err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "备份不存在")
		return
	}

	// 1. 先给当前库留后路
	if safety, err := s.createBackup(); err == nil {
		log.Printf("恢复前已自动备份当前库: %s", safety.Name)
	} else {
		writeErr(w, http.StatusInternalServerError, "backup_failed", "恢复前自动备份失败: "+err.Error())
		return
	}

	// 2. 关连接 → 换文件 → 重开（单连接串行化，窗口很小）
	if err := s.db.Close(); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "关闭数据库失败: "+err.Error())
		if err := s.reopenDB(); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "恢复后重开数据库失败: "+err.Error())
			return
		}
		return
	}
	src, err := os.Open(full)
	if err != nil {
		if err := s.reopenDB(); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "恢复后重开数据库失败: "+err.Error())
			return
		}
		writeErr(w, http.StatusInternalServerError, "internal", "读取备份失败: "+err.Error())
		return
	}
	defer src.Close()
	dst, err := os.OpenFile(s.cfg.DBPath, os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		src.Close()
		if err := s.reopenDB(); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "恢复后重开数据库失败: "+err.Error())
			return
		}
		writeErr(w, http.StatusInternalServerError, "internal", "写入数据库失败: "+err.Error())
		return
	}
	if _, err := io.Copy(dst, src); err != nil {
		dst.Close()
		if err := s.reopenDB(); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "恢复后重开数据库失败: "+err.Error())
			return
		}
		writeErr(w, http.StatusInternalServerError, "internal", "写入数据库失败: "+err.Error())
		return
	}
	if err := dst.Sync(); err != nil {
		dst.Close()
		if err := s.reopenDB(); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "恢复后重开数据库失败: "+err.Error())
			return
		}
		writeErr(w, http.StatusInternalServerError, "internal", "写入数据库失败: "+err.Error())
		return
	}
	dst.Close()
	// WAL 边车文件属于旧库，必须清掉，否则会把旧数据又叠回来
	os.Remove(s.cfg.DBPath + "-wal")
	os.Remove(s.cfg.DBPath + "-shm")

	if err := s.reopenDB(); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "恢复后重开数据库失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"restored": filepath.Base(full),
		"message":  "已恢复，所有在线用户需要重新登录",
	})
}

// reopenDB 重新打开数据库连接。调用方（恢复流程）需要知道是否失败，
// 其他调用点忽略返回值即可。
func (s *Server) reopenDB() error {
	db, err := openDB(s.cfg.DBPath)
	if err != nil {
		log.Printf("重开数据库失败: %v", err)
		return err
	}
	s.db = db
	return nil
}

// ---------------- 运行状态 ----------------

type diskStat struct {
	Total uint64 `json:"total"`
	Free  uint64 `json:"free"`
}

// dirSize 统计目录体积与文件数。
func dirSize(root string) (bytes int64, files int64) {
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if st, err := d.Info(); err == nil {
			bytes += st.Size()
			files++
		}
		return nil
	})
	return
}

func fileSize(p string) int64 {
	if st, err := os.Stat(p); err == nil {
		return st.Size()
	}
	return 0
}

func diskUsage(p string) diskStat {
	var st syscall.Statfs_t
	if err := syscall.Statfs(p, &st); err != nil {
		return diskStat{}
	}
	return diskStat{
		Total: uint64(st.Blocks) * uint64(st.Bsize),
		Free:  uint64(st.Bavail) * uint64(st.Bsize),
	}
}

func (s *Server) countRows(q string) int64 {
	var n int64
	_ = s.db.QueryRow(q).Scan(&n)
	return n
}

// GET /api/v1/system/status —— 管理端「系统状态」一页数据
func (s *Server) handleSystemStatus(w http.ResponseWriter, r *http.Request) {
	photoBytes, photoFiles := dirSize(s.cfg.UploadDir)
	dbBytes := fileSize(s.cfg.DBPath)
	list, _ := s.listBackups()
	backupBytes := int64(0)
	for _, b := range list {
		backupBytes += b.Size
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"version": version,
		"uptime":  time.Since(s.startedAt).Round(time.Second).String(),
		"counts": map[string]any{
			"users":    s.countRows(`SELECT COUNT(1) FROM users`),
			"checkins": s.countRows(`SELECT COUNT(1) FROM checkins`),
			"entries":  s.countRows(`SELECT COUNT(1) FROM entries`),
		},
		"storage": map[string]any{
			"photos_bytes":   photoBytes,
			"photos_files":   photoFiles,
			"db_bytes":       dbBytes,
			"backups_bytes":  backupBytes,
			"backups_files":  len(list),
			"uploads_dir":    s.cfg.UploadDir,
			"backups_dir":    s.backupDir(),
			"last_backup":    lastBackupName(list),
			"last_backup_at": lastBackupTime(list),
			"disk":           diskUsage(s.dataDir()),
		},
		"db_path": s.cfg.DBPath,
	})
}

func lastBackupTime(list []backupInfo) string {
	if len(list) == 0 {
		return ""
	}
	return list[0].CreatedAt
}

// POST /api/v1/system/cleanup —— 清理数据库里已经没有引用的照片文件
func (s *Server) handleCleanupPhotos(w http.ResponseWriter, r *http.Request) {
	referenced := map[string]bool{}
	collect := func(q string) {
		rows, err := s.db.Query(q)
		if err != nil {
			return
		}
		defer rows.Close()
		for rows.Next() {
			var rel sql.NullString
			if err := rows.Scan(&rel); err == nil && strings.TrimSpace(rel.String) != "" {
				referenced[strings.TrimSpace(rel.String)] = true
			}
		}
	}
	collect(`SELECT in_photo FROM checkins UNION ALL SELECT out_photo FROM checkins UNION ALL SELECT photo FROM entries`)

	// 缩略图与原图同生同死，一起算引用
	for _, rel := range keysOf(referenced) {
		if ext := filepath.Ext(rel); ext != "" {
			referenced[strings.TrimSuffix(rel, ext)+"_t.jpg"] = true
		}
	}

	root, err := filepath.Abs(s.cfg.UploadDir)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "照片目录无效")
		return
	}
	_, scanned := dirSize(root) // 只展示文件数
	cutoff := time.Now().Add(-orphanGrace)
	removed, freed := 0, int64(0)
	var removedDirs []string

	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			return nil
		}
		st, err := d.Info()
		if err != nil || st.ModTime().After(cutoff) {
			return nil // 太新的文件可能正在上传，跳过
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if referenced[rel] {
			return nil
		}
		if os.Remove(p) == nil {
			removed++
			freed += st.Size()
			removedDirs = append(removedDirs, filepath.Dir(p))
		}
		return nil
	})

	// 顺手清掉空目录（按路径长度倒序，先删深的）
	sort.Slice(removedDirs, func(i, j int) bool { return len(removedDirs[i]) > len(removedDirs[j]) })
	seen := map[string]bool{}
	for _, dir := range removedDirs {
		if seen[dir] || dir == root {
			continue
		}
		seen[dir] = true
		if ents, err := os.ReadDir(dir); err == nil && len(ents) == 0 {
			_ = os.Remove(dir)
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"removed":       removed,
		"freed_bytes":   freed,
		"scanned_files": scanned,
	})
}

func keysOf(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

// ---------------- 备份时间标记（供状态页展示） ----------------

func (s *Server) backupMarkerPath() string {
	return filepath.Join(s.backupDir(), "last-run")
}

func (s *Server) markBackupRun() {
	_ = os.WriteFile(s.backupMarkerPath(), []byte(time.Now().Format(time.RFC3339)), 0o644)
}

func (s *Server) backupMarker() string {
	b, err := os.ReadFile(s.backupMarkerPath())
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}
