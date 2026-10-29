package main

import (
	"archive/zip"
	"database/sql"
	"encoding/csv"
	"encoding/json"
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
	InAt     *string `json:"in_at"`      // RFC3339；null 表示不改
	OutAt    *string `json:"out_at"`     // RFC3339；显式 null 表示清空出馆（还没走）
	InNote   *string `json:"in_note"`    // 可选
	OutNote  *string `json:"out_note"`   // 可选
	InPlace  *string `json:"in_place"`   // 可选
	OutPlace *string `json:"out_place"`  // 可选
}

// PATCH /api/v1/checkins/{id} —— 忘了打卡 / 时间点错了时手动修正
func (s *Server) handlePatchCheckin(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	id := qIntPath(r, "id")
	if id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_id", "id 无效")
		return
	}

	var req patchCheckinReq
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10))
	if err := dec.Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "请求体不是合法 JSON")
		return
	}

	var (
		day      string
		inAtRaw  sql.NullString
		outAtRaw sql.NullString
	)
	err := s.db.QueryRow(
		`SELECT day, in_at, out_at FROM checkins WHERE id = ? AND user_id = ?`, id, uid,
	).Scan(&day, &inAtRaw, &outAtRaw)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "记录不存在")
		return
	}

	loc := s.clientLoc(r)

	inAt := parseStored(inAtRaw, loc)
	outAt := parseStored(outAtRaw, loc)

	if req.InAt != nil {
		t, perr := parseClientTime(*req.InAt, loc)
		if perr != nil {
			writeErr(w, http.StatusBadRequest, "bad_time", "进馆时间格式不正确")
			return
		}
		inAt = &t
	}
	if req.OutAt != nil {
		if strings.TrimSpace(*req.OutAt) == "" {
			outAt = nil // 显式清空：还没出馆
		} else {
			t, perr := parseClientTime(*req.OutAt, loc)
			if perr != nil {
				writeErr(w, http.StatusBadRequest, "bad_time", "出馆时间格式不正确")
				return
			}
			outAt = &t
		}
	}

	if inAt == nil {
		writeErr(w, http.StatusBadRequest, "in_at_required", "进馆时间不能为空")
		return
	}
	// 时间必须落在这条记录的当天（允许跨时区偏移，但本地日期要一致）
	if inAt.In(loc).Format("2006-01-02") != day {
		writeErr(w, http.StatusBadRequest, "time_out_of_day",
			"时间必须落在 "+day+" 当天")
		return
	}

	dur := int64(0)
	if outAt != nil {
		if outAt.In(loc).Format("2006-01-02") != day {
			writeErr(w, http.StatusBadRequest, "time_out_of_day",
				"出馆时间必须落在 "+day+" 当天")
			return
		}
		if outAt.Before(*inAt) {
			writeErr(w, http.StatusBadRequest, "out_before_in", "出馆时间不能早于进馆时间")
			return
		}
		dur = int64(outAt.Sub(*inAt).Seconds())
		if dur > 86400 {
			dur = 86400
		}
	}

	sets := []string{"in_at = ?", "out_at = ?", "duration_sec = ?", "updated_at = ?"}
	args := []any{inAt.Format(time.RFC3339), nil, dur, time.Now().Format(time.RFC3339)}
	if outAt != nil {
		args[1] = outAt.Format(time.RFC3339)
	}
	if req.InNote != nil {
		sets = append(sets, "in_note = ?")
		args = append(args, strings.TrimSpace(*req.InNote))
	}
	if req.OutNote != nil {
		sets = append(sets, "out_note = ?")
		args = append(args, strings.TrimSpace(*req.OutNote))
	}
	if req.InPlace != nil {
		sets = append(sets, "in_place = ?")
		args = append(args, strings.TrimSpace(*req.InPlace))
	}
	if req.OutPlace != nil {
		sets = append(sets, "out_place = ?")
		args = append(args, strings.TrimSpace(*req.OutPlace))
	}
	args = append(args, id, uid)

	if _, err := s.db.Exec(`UPDATE checkins SET `+strings.Join(sets, ", ")+` WHERE id = ? AND user_id = ?`, args...); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "保存失败")
		return
	}

	c, err := s.checkinByDay(uid, day)
	if err != nil || c == nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
		return
	}
	writeJSON(w, http.StatusOK, c)
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
	for _, layout := range []string{"2006-01-02 15:04:05", "2006-01-02T15:04:05", "2006-01-02 15:04"} {
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
		`SELECT COALESCE(in_photo,''), COALESCE(out_photo,''), day FROM checkins WHERE user_id = ? ORDER BY day ASC`, uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	for rows.Next() {
		var inPhoto, outPhoto, day string
		if err := rows.Scan(&inPhoto, &outPhoto, &day); err == nil {
			if inPhoto != "" {
				items = append(items, item{inPhoto, "打卡照片/" + day + "_进馆_" + filepath.Base(inPhoto)})
			}
			if outPhoto != "" {
				items = append(items, item{outPhoto, "打卡照片/" + day + "_出馆_" + filepath.Base(outPhoto)})
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
