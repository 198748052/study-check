package main

import (
	"archive/zip"
	"database/sql"
	"encoding/csv"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 手动修正打卡时间
// ---------------------------------------------------------------

type patchCheckinReq struct {
	InAt     *string `json:"in_at"`     // RFC3339；null 表示不改
	OutAt    *string `json:"out_at"`    // RFC3339；显式 null 表示清空出馆（还没走）
	InNote   *string `json:"in_note"`   // 可选
	OutNote  *string `json:"out_note"`  // 可选
	InPlace  *string `json:"in_place"`  // 可选
	OutPlace *string `json:"out_place"` // 可选
}

// PATCH /api/v1/checkins/{id} —— 兼容入口：只有一天的记录只有一段时可直接改，
// 多段的日子请用 PATCH /api/v1/sessions/{id} 逐段修正。
func (s *Server) handlePatchCheckin(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	id := qIntPath(r, "id")
	if id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_id", "id 无效")
		return
	}

	var day string
	if err := s.db.QueryRow(`SELECT day FROM checkins WHERE id = ? AND user_id = ?`, id, uid).Scan(&day); err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "记录不存在")
		return
	}

	sessions, err := s.sessionsForDay(uid, day)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	if len(sessions) == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "记录不存在")
		return
	}
	if len(sessions) > 1 {
		writeErr(w, http.StatusConflict, "multiple_sessions",
			"这一天有 "+strconv.Itoa(len(sessions))+" 段自习，请逐段修正")
		return
	}
	s.applySessionPatch(w, r, uid, sessions[0].ID)
}

func parseStored(v sql.NullString, loc *time.Location) *time.Time {
	if !v.Valid || v.String == "" {
		return nil
	}
	if t, err := time.Parse(time.RFC3339, v.String); err == nil {
		tt := t.In(loc)
		return &tt
	}
	return nil
}

// parseClientTime 接受 RFC3339（App 传本地时间带偏移），也兼容 "YYYY-MM-DD HH:MM"
func parseClientTime(raw string, loc *time.Location) (time.Time, error) {
	raw = strings.TrimSpace(raw)
	if t, err := time.Parse(time.RFC3339, raw); err == nil {
		return t.In(loc), nil
	}
	for _, layout := range []string{
		"2006-01-02 15:04:05", "2006-01-02T15:04:05",
		"2006-01-02 15:04", "2006-01-02T15:04", // datetime-local 的常见写法
	} {
		if t, err := time.ParseInLocation(layout, raw, loc); err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("无法解析时间 %q", raw)
}

// ---------------------------------------------------------------
// 数据导出：CSV（打卡 / 笔记）+ 照片 ZIP
// ---------------------------------------------------------------

const csvBOM = "\ufeff"

func writeCSVHeaders(w http.ResponseWriter, filename string) {
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="`+filename+`"`)
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, csvBOM)
}

// GET /api/v1/export/checkins.csv
func (s *Server) handleExportCheckinsCSV(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)

	rows, err := s.db.Query(`SELECT `+checkinCols+` FROM checkins WHERE user_id = ? ORDER BY day ASC`, uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	writeCSVHeaders(w, "studycheck-checkins.csv")
	cw := csv.NewWriter(w)
	_ = cw.Write([]string{"日期", "进馆时间", "出馆时间", "时长(分钟)", "进馆地点", "出馆地点", "进馆备注", "出馆备注"})

	for rows.Next() {
		c, err := scanCheckin(rows)
		if err != nil {
			return
		}
		_ = cw.Write([]string{
			c.Day,
			fmtCSVTime(c.InAt, loc),
			fmtCSVTime(c.OutAt, loc),
			strconv.FormatInt(c.DurationSec/60, 10),
			c.InPlace,
			c.OutPlace,
			c.InNote,
			c.OutNote,
		})
	}
	cw.Flush()
}

// GET /api/v1/export/sessions.csv —— 每一段自习一行（多段的日子能看清具体时段）
func (s *Server) handleExportSessionsCSV(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)

	rows, err := s.db.Query(`SELECT `+sessionCols+` FROM sessions
		WHERE user_id = ? AND in_at IS NOT NULL ORDER BY day ASC, in_at ASC`, uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	writeCSVHeaders(w, "studycheck-sessions.csv")
	cw := csv.NewWriter(w)
	_ = cw.Write([]string{"日期", "进馆时间", "出馆时间", "时长(分钟)", "进馆地点", "出馆地点", "进馆备注", "出馆备注"})

	for rows.Next() {
		c, err := scanCheckin(rows)
		if err != nil {
			return
		}
		_ = cw.Write([]string{
			c.Day,
			fmtCSVTime(c.InAt, loc),
			fmtCSVTime(c.OutAt, loc),
			strconv.FormatInt(c.DurationSec/60, 10),
			c.InPlace,
			c.OutPlace,
			c.InNote,
			c.OutNote,
		})
	}
	cw.Flush()
}

// GET /api/v1/export/entries.csv
func (s *Server) handleExportEntriesCSV(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)

	rows, err := s.db.Query(
		`SELECT day, created_at, COALESCE(photo,''), note FROM entries WHERE user_id = ? ORDER BY day ASC, id ASC`, uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	writeCSVHeaders(w, "studycheck-notes.csv")
	cw := csv.NewWriter(w)
	_ = cw.Write([]string{"日期", "时间", "笔记", "照片文件名"})

	for rows.Next() {
		var day, created, photo, note string
		if err := rows.Scan(&day, &created, &photo, &note); err != nil {
			return
		}
		_ = cw.Write([]string{day, fmtCSVTime(&created, loc), note, filepath.Base(photo)})
	}
	cw.Flush()
}

// GET /api/v1/export/photos.zip —— 所有照片打包（流式，不占内存）
func (s *Server) handleExportPhotosZip(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)

	type item struct{ rel, name string }
	var items []item

	rows, err := s.db.Query(
		`SELECT COALESCE(in_photo,''), COALESCE(out_photo,''), day, COALESCE(in_at,'')
		 FROM sessions WHERE user_id = ? ORDER BY day ASC, in_at ASC`, uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	for rows.Next() {
		var inPhoto, outPhoto, day, inAt string
		if err := rows.Scan(&inPhoto, &outPhoto, &day, &inAt); err == nil {
			prefix := day
			if len(inAt) >= 16 {
				// 同一天可能有多段，文件名带上时分，避免互相覆盖
				prefix = day + "_" + inAt[11:16]
			}
			prefix = strings.ReplaceAll(prefix, ":", "")
			if inPhoto != "" {
				items = append(items, item{inPhoto, "打卡照片/" + prefix + "_进馆_" + filepath.Base(inPhoto)})
			}
			if outPhoto != "" {
				items = append(items, item{outPhoto, "打卡照片/" + prefix + "_出馆_" + filepath.Base(outPhoto)})
			}
		}
	}
	rows.Close()

	erows, err := s.db.Query(
		`SELECT COALESCE(photo,''), day FROM entries WHERE user_id = ? ORDER BY day ASC, id ASC`, uid)
	if err == nil {
		for erows.Next() {
			var photo, day string
			if err := erows.Scan(&photo, &day); err == nil && photo != "" {
				items = append(items, item{photo, "过程留存/" + day + "_" + filepath.Base(photo)})
			}
		}
		erows.Close()
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf(`attachment; filename="studycheck-photos-%s.zip"`, time.Now().Format("20060102")))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)

	zw := zip.NewWriter(w)
	defer zw.Close()

	seen := make(map[string]bool, len(items))
	for _, it := range items {
		if seen[it.rel] {
			continue
		}
		seen[it.rel] = true
		abs := filepath.Join(s.cfg.UploadDir, filepath.FromSlash(it.rel))
		f, err := os.Open(abs)
		if err != nil {
			continue // 文件缺失就跳过，不让整包失败
		}
		hdr := &zip.FileHeader{Name: it.name, Method: zip.Store} // 照片已压缩过，Store 更快
		hdr.Modified = time.Now()
		zf, err := zw.CreateHeader(hdr)
		if err != nil {
			_ = f.Close()
			return
		}
		_, _ = io.Copy(zf, f)
		_ = f.Close()
	}
}

func fmtCSVTime(iso *string, loc *time.Location) string {
	if iso == nil || *iso == "" {
		return ""
	}
	t, err := time.Parse(time.RFC3339, *iso)
	if err != nil {
		return *iso
	}
	return t.In(loc).Format("2006-01-02 15:04")
}
