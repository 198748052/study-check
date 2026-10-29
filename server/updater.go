package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// 更新器：拉 manifest → 下载二进制 → sha256 校验 → 原子替换 → 重启 → 健康校验 → 失败自动回滚
//
// 设计参考 file-transit 的做法（detached 脚本 + status marker），并补上它缺的两点：
//   1. 强制 sha256 校验   2. 健康校验失败自动回滚到 .prev

type updateManifest struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	SHA256  string `json:"sha256"`
	Notes   string `json:"notes"`
	Size    int64  `json:"size"`
}

type UpdateStatus struct {
	Enabled       bool    `json:"enabled"`
	CurrentVer    string  `json:"current_version"`
	LatestVer     string  `json:"latest_version"`
	HasUpdate     bool    `json:"has_update"`
	Running       bool    `json:"running"`
	LastRun       string  `json:"last_run"`
	Error         string  `json:"error,omitempty"`
	CheckedAt     *string `json:"checked_at,omitempty"`
	ManifestURL   string  `json:"manifest_url,omitempty"`
	Notes         string  `json:"notes,omitempty"`
	ServiceName   string  `json:"service_name,omitempty"`
	AutoUpdate    bool    `json:"auto_update"`
}

var updateMu sync.Mutex

func (s *Server) dataPath(name string) string {
	return filepath.Join(filepath.Dir(s.cfg.DBPath), name)
}

func (s *Server) updateStatusPath() string { return s.dataPath("update.status") }
func (s *Server) updateLogPath() string    { return s.dataPath("update.log") }

func readMarker(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return "none"
	}
	v := strings.TrimSpace(string(b))
	switch v {
	case "ok", "failed", "running":
		return v
	}
	return "none"
}

func markerIsFreshRunning(path string) bool {
	if readMarker(path) != "running" {
		return false
	}
	st, err := os.Stat(path)
	if err != nil {
		return false
	}
	return time.Since(st.ModTime()) < 30*time.Minute
}

// compareSemver 只比较 x.y.z 数字段，返回 1/0/-1；非法版本视为相等。
func compareSemver(a, b string) int {
	pa := strings.Split(strings.TrimPrefix(strings.TrimPrefix(a, "v"), "V"), ".")
	pb := strings.Split(strings.TrimPrefix(strings.TrimPrefix(b, "v"), "V"), ".")
	for i := 0; i < 3; i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(strings.SplitN(pa[i], "-", 2)[0])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(strings.SplitN(pb[i], "-", 2)[0])
		}
		if x > y {
			return 1
		}
		if x < y {
			return -1
		}
	}
	return 0
}

func (s *Server) fetchManifest() (*updateManifest, error) {
	if s.cfg.UpdateManifestURL == "" {
		return nil, errors.New("未配置更新源（SC_UPDATE_MANIFEST_URL）")
	}
	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest(http.MethodGet, s.cfg.UpdateManifestURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "studycheck-updater/"+version)
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("获取更新清单失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("更新清单返回 HTTP %d", resp.StatusCode)
	}
	var m updateManifest
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&m); err != nil {
		return nil, fmt.Errorf("更新清单格式错误: %w", err)
	}
	if m.Version == "" || m.URL == "" || m.SHA256 == "" {
		return nil, errors.New("更新清单缺少 version/url/sha256")
	}
	return &m, nil
}

func (s *Server) buildStatus(errMsg string) UpdateStatus {
	st := UpdateStatus{
		Enabled:     s.cfg.UpdateEnabled,
		CurrentVer:  version,
		Running:     markerIsFreshRunning(s.updateStatusPath()),
		LastRun:     readMarker(s.updateStatusPath()),
		ManifestURL: s.cfg.UpdateManifestURL,
		ServiceName: s.cfg.ServiceName,
		AutoUpdate:  s.cfg.AutoUpdate,
		Error:       errMsg,
	}
	if raw, err := os.ReadFile(s.dataPath("update.cache")); err == nil {
		var cached struct {
			Latest    string `json:"latest"`
			Notes     string `json:"notes"`
			CheckedAt string `json:"checked_at"`
		}
		if json.Unmarshal(raw, &cached) == nil {
			st.LatestVer = cached.Latest
			st.Notes = cached.Notes
			if cached.CheckedAt != "" {
				st.CheckedAt = &cached.CheckedAt
			}
			st.HasUpdate = cached.Latest != "" && compareSemver(cached.Latest, version) > 0
		}
	}
	return st
}

func (s *Server) checkUpdate() (UpdateStatus, error) {
	m, err := s.fetchManifest()
	if err != nil {
		return s.buildStatus(err.Error()), err
	}
	now := time.Now().In(s.cfg.Loc).Format(time.RFC3339)
	cache, _ := json.Marshal(map[string]string{"latest": m.Version, "notes": m.Notes, "checked_at": now})
	_ = os.WriteFile(s.dataPath("update.cache"), cache, 0o644)
	st := s.buildStatus("")
	st.LatestVer = m.Version
	st.Notes = m.Notes
	st.CheckedAt = &now
	st.HasUpdate = compareSemver(m.Version, version) > 0
	return st, nil
}

// applyUpdate 生成并脱离父进程执行更新脚本（脚本会重启本进程，必须 detached）。
func (s *Server) applyUpdate() error {
	updateMu.Lock()
	defer updateMu.Unlock()

	if markerIsFreshRunning(s.updateStatusPath()) {
		return errors.New("已有更新正在进行")
	}
	m, err := s.fetchManifest()
	if err != nil {
		return err
	}
	if compareSemver(m.Version, version) <= 0 {
		return fmt.Errorf("当前已是最新版本（%s）", m.Version)
	}
	if !strings.HasPrefix(m.URL, "https://") {
		return errors.New("拒绝非 https 的更新包地址")
	}

	binPath, err := os.Executable()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.updateLogPath()), 0o755); err != nil {
		return err
	}

	script := s.buildUpdateScript(m, binPath)
	scriptPath := s.dataPath("update.sh")
	if err := os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		return err
	}
	_ = os.WriteFile(s.updateStatusPath(), []byte("running"), 0o644)

	lf, err := os.OpenFile(s.updateLogPath(), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	defer lf.Close()

	cmd := exec.Command("/bin/sh", scriptPath)
	cmd.Dir = filepath.Dir(binPath)
	cmd.Stdout, cmd.Stderr = lf, lf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true} // 脱离进程组，父进程重启不影响它
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}

func (s *Server) buildUpdateScript(m *updateManifest, binPath string) string {
	statusPath := s.updateStatusPath()
	newBin := binPath + ".new"
	return fmt.Sprintf(`#!/bin/sh
# studycheck 自动更新脚本（由程序生成，请勿手改）
STATUS=%q
BIN=%q
NEW=%q
PREV=%q
HEALTH="http://127.0.0.1%s/api/v1/health"
TARGET=%q
URL=%q
SHA=%q

set -u
fail() {
  echo "[update] FAILED: $*"
  printf "failed" > "$STATUS"
  # 回滚
  if [ -f "$PREV" ]; then
    echo "[update] rolling back to previous binary"
    mv -f "$PREV" "$BIN" || true
    systemctl restart %s || true
  fi
  exit 1
}

echo "[update] $(date '+%%Y-%%m-%%d %%H:%%M:%%S') start, target $TARGET"

# 并发锁（没有 flock 就靠 status 标记兜底）
if command -v flock >/dev/null 2>&1; then
  exec 9>"%s"
  flock -n 9 || fail "another update is running"
fi

curl -fsSL --retry 3 --retry-delay 2 -o "$NEW" "$URL" || fail "download error"

ACTUAL=$(sha256sum "$NEW" | awk '{print $1}')
[ "$ACTUAL" = "$SHA" ] || fail "sha256 mismatch: got $ACTUAL"

chmod +x "$NEW" || fail "chmod error"
cp -f "$BIN" "$PREV" || fail "backup error"
mv -f "$NEW" "$BIN" || fail "replace error"

systemctl restart %s || fail "systemctl restart error"

i=0
while [ $i -lt 20 ]; do
  if curl -fsS "$HEALTH" 2>/dev/null | grep -q "\"version\":\"$TARGET\""; then
    echo "[update] OK, running $TARGET"
    printf "ok" > "$STATUS"
    exit 0
  fi
  i=$((i+1))
  sleep 1
done

fail "health check timed out"
`,
		statusPath, binPath, newBin, binPath+".prev",
		s.listenPort(),
		m.Version, m.URL, m.SHA256,
		s.cfg.ServiceName, s.dataPath("update.lock"), s.cfg.ServiceName,
	)
}

// listenPort 从监听地址里取出端口，健康校验用 127.0.0.1 回环访问。
func (s *Server) listenPort() string {
	_, port, err := net.SplitHostPort(s.cfg.Addr)
	if err != nil {
		return "8080"
	}
	return port
}

// autoUpdateLoop 可选的"全自动更新"：定时检查，有新版直接部署。
// 默认关闭（SC_AUTO_UPDATE=false），建议先手动跑通再打开。
func (s *Server) autoUpdateLoop() {
	ticker := time.NewTicker(6 * time.Hour)
	defer ticker.Stop()
	time.Sleep(2 * time.Minute) // 启动后先跑稳，别一上来就折腾
	for {
		if !markerIsFreshRunning(s.updateStatusPath()) {
			if st, err := s.checkUpdate(); err == nil && st.HasUpdate {
				log.Printf("自动更新：发现 %s（当前 %s），开始部署", st.LatestVer, version)
				if err := s.applyUpdate(); err != nil {
					log.Printf("自动更新失败: %v", err)
				}
			}
		}
		<-ticker.C
	}
}

func (s *Server) updateLogTail(n int) string {
	b, err := os.ReadFile(s.updateLogPath())
	if err != nil {
		return ""
	}
	lines := strings.Split(strings.TrimRight(string(b), "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

// —— HTTP handlers ——

func (s *Server) handleUpdateStatus(w http.ResponseWriter, r *http.Request) {
	st := s.buildStatus("")
	writeJSON(w, http.StatusOK, st)
}

func (s *Server) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	st, err := s.checkUpdate()
	if err != nil {
		writeJSON(w, http.StatusOK, st) // 状态里带 error，前端好展示
		return
	}
	writeJSON(w, http.StatusOK, st)
}

func (s *Server) handleUpdateApply(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.UpdateEnabled {
		writeErr(w, http.StatusForbidden, "update_disabled", "更新功能未启用")
		return
	}
	if err := s.applyUpdate(); err != nil {
		writeErr(w, http.StatusConflict, "update_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"started": true})
}

func (s *Server) handleUpdateLog(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"log":      s.updateLogTail(200),
		"last_run": readMarker(s.updateStatusPath()),
		"running":  markerIsFreshRunning(s.updateStatusPath()),
	})
}
