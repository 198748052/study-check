package main

import (
	"database/sql"
	"errors"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// 每日详情条目：一张随手拍照片 + 可选文字笔记（照片可为空，纯笔记也算一条）。
type entryDTO struct {
	ID        int64  `json:"id"`
	Day       string `json:"day"`
	PhotoURL  string `json:"photo_url,omitempty"`
	ThumbURL  string `json:"thumb_url,omitempty"`
	Note      string `json:"note"`
	CreatedAt string `json:"created_at"`
}

const maxEntriesPerRequest = 12

func entryDTOFromRow(id int64, day string, photo, note, created string) entryDTO {
	e := entryDTO{ID: id, Day: day, Note: note, CreatedAt: created}
	if photo != "" {
		e.PhotoURL = "/media/" + photo
		ext := filepath.Ext(photo)
		e.ThumbURL = "/media/" + strings.TrimSuffix(photo, ext) + "_t.jpg"
	}
	return e
}

// POST /api/v1/entries —— 当天的「过程留存」：可一次传多张（连拍）或只写笔记
func (s *Server) handleCreateEntries(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	if s.idemLookup(w, r, uid, "entry") {
		return
	}
	// 连拍可能一次带多张，总量放宽到 12 张的份额
	if !s.limitUploadBodyN(w, r, s.cfg.MaxPhotoMB<<20*maxEntriesPerRequest) {
		return
	}
	if err := r.ParseMultipartForm(16 << 20); err != nil {
		writeErr(w, multipartErrStatus(err), "bad_multipart", "请使用 multipart/form-data 上传")
		return
	}

	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	day, err := resolveDay(r, now, loc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", err.Error())
		return
	}

	note := strings.TrimSpace(r.FormValue("note"))
	var files []*multipart.FileHeader
	if r.MultipartForm != nil {
		files = r.MultipartForm.File["photo"]
	}
	if len(files) == 0 && note == "" {
		writeErr(w, http.StatusBadRequest, "empty_entry", "请至少提供一张照片或一段笔记")
		return
	}
	if len(files) > maxEntriesPerRequest {
		writeErr(w, http.StatusBadRequest, "too_many_photos",
			"一次最多上传 "+strconv.Itoa(maxEntriesPerRequest)+" 张照片")
		return
	}

	stamp := time.Now().Format(time.RFC3339)
	created := make([]int64, 0, len(files))
	items := make([]entryDTO, 0, len(files)+1)

	rollback := func() {
		for _, id := range created {
			_, _ = s.db.Exec(`DELETE FROM entries WHERE id = ? AND user_id = ?`, id, uid)
		}
	}

	for _, fh := range files {
		f, oerr := fh.Open()
		if oerr != nil {
			rollback()
			writeErr(w, http.StatusBadRequest, "bad_photo", "读取照片失败")
			return
		}
		rel, _, _, serr := s.savePhoto(f, fh, now)
		_ = f.Close()
		if serr != nil {
			rollback()
			if errors.Is(serr, errPhotoTooBig) {
				writeErr(w, http.StatusRequestEntityTooLarge, "photo_too_large", serr.Error())
				return
			}
			writeErr(w, http.StatusBadRequest, "bad_photo", serr.Error())
			return
		}
		res, ierr := s.db.Exec(
			`INSERT INTO entries(user_id, day, photo, note, created_at, updated_at) VALUES(?,?,?,?,?,?)`,
			uid, day, rel, note, stamp, stamp)
		if ierr != nil {
			rollback()
			writeErr(w, http.StatusInternalServerError, "internal", "保存记录失败")
			return
		}
		id, _ := res.LastInsertId()
		created = append(created, id)
		items = append(items, entryDTOFromRow(id, day, rel, note, stamp))
	}

	if len(files) == 0 {
		res, ierr := s.db.Exec(
			`INSERT INTO entries(user_id, day, photo, note, created_at, updated_at) VALUES(?,?,NULL,?,?,?)`,
			uid, day, note, stamp, stamp)
		if ierr != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "保存笔记失败")
			return
		}
		id, _ := res.LastInsertId()
		items = append(items, entryDTOFromRow(id, day, "", note, stamp))
	}

	resp := map[string]any{"items": items, "count": len(items), "day": day}
	s.idemStore(r, uid, "entry", http.StatusCreated, resp)
	writeJSON(w, http.StatusCreated, resp)
}

// GET /api/v1/entries?day=YYYY-MM-DD&limit=&offset=&since=
func (s *Server) handleListEntries(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)
	day := strings.TrimSpace(r.URL.Query().Get("day"))
	if day == "" {
		day = time.Now().In(loc).Format("2006-01-02")
	}
	limit := qInt(r, "limit", 60)
	if limit <= 0 || limit > 200 {
		limit = 60
	}
	offset := qInt(r, "offset", 0)
	if offset < 0 {
		offset = 0
	}
	since := strings.TrimSpace(r.URL.Query().Get("since"))

	var (
		rows *sql.Rows
		err  error
	)
	if since != "" {
		rows, err = s.db.Query(
			`SELECT id, day, COALESCE(photo,''), note, created_at FROM entries
			 WHERE user_id = ? AND updated_at >= ? ORDER BY id DESC LIMIT ?`,
			uid, since, limit)
	} else {
		rows, err = s.db.Query(
			`SELECT id, day, COALESCE(photo,''), note, created_at FROM entries
			 WHERE user_id = ? AND day = ? ORDER BY id DESC LIMIT ? OFFSET ?`,
			uid, day, limit, offset)
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	items := make([]entryDTO, 0, 16)
	for rows.Next() {
		var (
			id           int64
			d, photo, no string
			created      string
		)
		if err := rows.Scan(&id, &d, &photo, &no, &created); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
			return
		}
		items = append(items, entryDTOFromRow(id, d, photo, no, created))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items":       items,
		"count":       len(items),
		"day":         day,
		"server_time": time.Now().In(loc).Format(time.RFC3339),
	})
}

// DELETE /api/v1/entries/{id}
func (s *Server) handleDeleteEntry(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "id 不合法")
		return
	}
	var photo sql.NullString
	err = s.db.QueryRow(`SELECT photo FROM entries WHERE id = ? AND user_id = ?`, id, uid).Scan(&photo)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "记录不存在")
		return
	}
	if _, err := s.db.Exec(`DELETE FROM entries WHERE id = ? AND user_id = ?`, id, uid); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "删除失败")
		return
	}
	// 顺手清理照片文件（失败不影响业务）
	if photo.Valid && photo.String != "" {
		for _, rel := range []string{photo.String, strings.TrimSuffix(photo.String, filepath.Ext(photo.String)) + "_t.jpg"} {
			_ = os.Remove(filepath.Join(s.cfg.UploadDir, filepath.FromSlash(rel)))
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
