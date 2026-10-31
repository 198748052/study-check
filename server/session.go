package main

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 一天多段自习
//
// 每一次「进馆 → 出馆」是一段（sessions 表的一行）。
// checkins 仍是「按天一行」的聚合缓存：最早进馆、最晚出馆、总时长，
// 由 recomputeDay 在每次变更后重算，统计 / 报表 / 导出等读 checkins 的代码不用改。
// ---------------------------------------------------------------

const sessionCols = checkinCols // 列结构与 checkins 完全一致，直接复用 scanCheckin

// sessionsForDay 按进馆时间正序返回某天的所有分段。
func (s *Server) sessionsForDay(uid int64, day string) ([]Checkin, error) {
	rows, err := s.db.Query(`SELECT `+sessionCols+` FROM sessions
		WHERE user_id = ? AND day = ? AND in_at IS NOT NULL
		ORDER BY in_at ASC, id ASC`, uid, day)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Checkin, 0, 4)
	for rows.Next() {
		c, err := scanCheckin(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// openSession 返回某天还没出馆的那一段（没有则 nil）。
func (s *Server) openSession(uid int64, day string) (*Checkin, error) {
	row := s.db.QueryRow(`SELECT `+sessionCols+` FROM sessions
		WHERE user_id = ? AND day = ? AND in_at IS NOT NULL AND out_at IS NULL
		ORDER BY in_at DESC, id DESC LIMIT 1`, uid, day)
	c, err := scanCheckin(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// sessionOutTarget 为出馆挑一段：优先挑「进馆时间不晚于本次拍照时间」的未结束分段，
// 这样离线补传的旧照片不会把今天刚开的一段关掉。
func (s *Server) sessionOutTarget(uid int64, day string, takenAt *time.Time) (*Checkin, error) {
	if takenAt != nil {
		row := s.db.QueryRow(`SELECT `+sessionCols+` FROM sessions
			WHERE user_id = ? AND day = ? AND in_at IS NOT NULL AND out_at IS NULL AND in_at <= ?
			ORDER BY in_at DESC, id DESC LIMIT 1`, uid, day, takenAt.Format(time.RFC3339))
		c, err := scanCheckin(row)
		if err == nil {
			return &c, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
	}
	return s.openSession(uid, day)
}

// sessionRaw 是聚合计算需要的原始列（照片要存库，不能要 URL）。
type sessionRaw struct {
	inAt    sql.NullString
	outAt   sql.NullString
	inPhoto sql.NullString
	outPh   sql.NullString
	inNote  string
	outNote string
	inPlace string
	outPl   string
	dur     int64
	updated string
}

func (s *Server) daySessionsRaw(uid int64, day string) ([]sessionRaw, error) {
	rows, err := s.db.Query(`SELECT in_at, out_at, in_photo, out_photo,
		in_note, out_note, in_place, out_place, duration_sec, updated_at
		FROM sessions WHERE user_id = ? AND day = ? AND in_at IS NOT NULL
		ORDER BY in_at ASC, id ASC`, uid, day)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]sessionRaw, 0, 4)
	for rows.Next() {
		var x sessionRaw
		if err := rows.Scan(&x.inAt, &x.outAt, &x.inPhoto, &x.outPh,
			&x.inNote, &x.outNote, &x.inPlace, &x.outPl, &x.dur, &x.updated); err != nil {
			return nil, err
		}
		out = append(out, x)
	}
	return out, rows.Err()
}

// recomputeDay 把某天的所有分段重新聚合成 checkins 的一行。
// 没有任何分段时删掉这一天的缓存行。
func (s *Server) recomputeDay(uid int64, day string) error {
	ss, err := s.daySessionsRaw(uid, day)
	if err != nil {
		return err
	}
	if len(ss) == 0 {
		_, err = s.db.Exec(`DELETE FROM checkins WHERE user_id = ? AND day = ?`, uid, day)
		return err
	}

	first := ss[0]
	last := ss[len(ss)-1]
	hasOpen := false
	var total int64
	maxUpdated := ""
	var inPhoto, outPhoto, outAt any
	if first.inPhoto.Valid && first.inPhoto.String != "" {
		inPhoto = first.inPhoto.String
	}
	for _, x := range ss {
		total += x.dur
		if !x.outAt.Valid {
			hasOpen = true
		}
		if x.updated > maxUpdated {
			maxUpdated = x.updated
		}
	}
	// 只要还有一段没出馆，这一天就算「进行中」，聚合行的 out_at 保持为空
	if !hasOpen && last.outAt.Valid {
		outAt = last.outAt.String
		if last.outPh.Valid && last.outPh.String != "" {
			outPhoto = last.outPh.String
		}
	}

	updated := maxUpdated
	if updated == "" {
		updated = time.Now().Format(time.RFC3339)
	}

	_, err = s.db.Exec(`INSERT INTO checkins(user_id, day, in_at, out_at, in_photo, out_photo,
			in_note, out_note, in_place, out_place, duration_sec, created_at, updated_at)
		VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
		ON CONFLICT(user_id, day) DO UPDATE SET
			in_at = excluded.in_at,
			out_at = excluded.out_at,
			in_photo = excluded.in_photo,
			out_photo = excluded.out_photo,
			in_note = excluded.in_note,
			out_note = excluded.out_note,
			in_place = excluded.in_place,
			out_place = excluded.out_place,
			duration_sec = excluded.duration_sec,
			updated_at = excluded.updated_at`,
		uid, day, first.inAt.String, outAt, inPhoto, outPhoto,
		first.inNote, last.outNote, first.inPlace, last.outPl,
		total, time.Now().Format(time.RFC3339), updated)
	return err
}

// dayState 由分段推导出今天的状态。
func dayState(sessions []Checkin) string {
	if len(sessions) == 0 {
		return "not_started"
	}
	for _, x := range sessions {
		if x.OutAt == nil {
			return "in_library"
		}
	}
	return "finished"
}

// ---------------------------------------------------------------
// 分段接口
// ---------------------------------------------------------------

// GET /api/v1/sessions?day=YYYY-MM-DD
func (s *Server) handleListSessions(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)
	day := strings.TrimSpace(r.URL.Query().Get("day"))
	if day == "" {
		day = time.Now().In(loc).Format("2006-01-02")
	}
	if _, err := time.ParseInLocation("2006-01-02", day, loc); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", "day 格式应为 YYYY-MM-DD")
		return
	}
	items, err := s.sessionsForDay(uid, day)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"day":   day,
		"items": items,
		"count": len(items),
	})
}

type sessionCreateReq struct {
	Day   string `json:"day"`
	InAt  string `json:"in_at"`
	OutAt string `json:"out_at"`
	// 简写：一个 note/place 同时用于进馆侧
	Note  string `json:"note"`
	Place string `json:"place"`
	// 显式区分进/出馆两侧（与 checkins 的字段名保持一致）
	InNote   string `json:"in_note"`
	InPlace  string `json:"in_place"`
	OutNote  string `json:"out_note"`
	OutPlace string `json:"out_place"`
}

// POST /api/v1/sessions —— 手动补一段（不需要照片），或给某天再补一段
func (s *Server) handleCreateSession(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	var req sessionCreateReq
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}

	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	day := strings.TrimSpace(req.Day)
	if day == "" {
		writeErr(w, http.StatusBadRequest, "day_required", "请选择日期")
		return
	}
	base, err := time.ParseInLocation("2006-01-02", day, loc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", "日期格式应为 YYYY-MM-DD")
		return
	}
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, loc)
	if base.After(today) {
		writeErr(w, http.StatusBadRequest, "future_day", "不能给未来日期补卡")
		return
	}
	if base.Before(today.AddDate(0, 0, -3650)) {
		writeErr(w, http.StatusBadRequest, "day_too_old", "只支持补最近 10 年的记录")
		return
	}

	inAt, err := combineDayTime(day, req.InAt, loc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_in_at", "进馆时间不正确："+err.Error())
		return
	}

	var outAt *time.Time
	if strings.TrimSpace(req.OutAt) != "" {
		t, oerr := combineDayTime(day, req.OutAt, loc)
		if oerr != nil {
			writeErr(w, http.StatusBadRequest, "bad_out_at", "出馆时间不正确："+oerr.Error())
			return
		}
		if t.Before(inAt) {
			writeErr(w, http.StatusBadRequest, "out_before_in", "出馆时间不能早于进馆时间")
			return
		}
		outAt = &t
	}

	// 同一分钟重复提交视为重复（Web 端没有幂等键，这里兜一层）
	var dup int
	_ = s.db.QueryRow(`SELECT COUNT(1) FROM sessions WHERE user_id = ? AND day = ? AND in_at = ?`,
		uid, day, inAt.Format(time.RFC3339)).Scan(&dup)
	if dup > 0 {
		writeErr(w, http.StatusConflict, "duplicate_session", "这一段时间已经存在了")
		return
	}

	var dur int64
	var outStr any
	if outAt != nil {
		dur = int64(outAt.Sub(inAt).Seconds())
		if dur > 86400 {
			dur = 86400
		}
		outStr = outAt.Format(time.RFC3339)
	}
	inNote := truncateStr(firstNonEmpty(req.InNote, req.Note), 200)
	outNote := truncateStr(firstNonEmpty(req.OutNote, req.Note), 200)
	inPlace := truncateStr(firstNonEmpty(req.InPlace, req.Place), 40)
	outPlace := truncateStr(firstNonEmpty(req.OutPlace, req.Place), 40)
	stamp := now.Format(time.RFC3339)

	res, err := s.db.Exec(`INSERT INTO sessions(user_id, day, in_at, out_at,
			in_note, out_note, in_place, out_place, duration_sec, created_at, updated_at)
		VALUES(?,?,?,?,?,?,?,?,?,?,?)`,
		uid, day, inAt.Format(time.RFC3339), outStr, inNote, outNote, inPlace, outPlace, dur, stamp, stamp)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "补卡失败")
		return
	}
	id, _ := res.LastInsertId()
	if err := s.recomputeDay(uid, day); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "汇总失败")
		return
	}
	session, _ := s.sessionByID(uid, id)
	c, _ := s.checkinByDay(uid, day)
	writeJSON(w, http.StatusCreated, map[string]any{"session": session, "checkin": c})
}

func (s *Server) sessionByID(uid, id int64) (*Checkin, error) {
	row := s.db.QueryRow(`SELECT `+sessionCols+` FROM sessions WHERE id = ? AND user_id = ?`, id, uid)
	c, err := scanCheckin(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// PATCH /api/v1/sessions/{id} —— 修正某一段的进/出馆时间与备注
func (s *Server) handlePatchSession(w http.ResponseWriter, r *http.Request) {
	id := int64(qIntPath(r, "id"))
	if id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_id", "id 无效")
		return
	}
	s.applySessionPatch(w, r, userID(r), id)
}

func (s *Server) applySessionPatch(w http.ResponseWriter, r *http.Request, uid, id int64) {
	var req patchCheckinReq
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}

	var (
		day      string
		inAtRaw  sql.NullString
		outAtRaw sql.NullString
	)
	err := s.db.QueryRow(`SELECT day, in_at, out_at FROM sessions WHERE id = ? AND user_id = ?`, id, uid).
		Scan(&day, &inAtRaw, &outAtRaw)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "not_found", "这一段记录不存在")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
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
			outAt = nil
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
	if inAt.In(loc).Format("2006-01-02") != day {
		writeErr(w, http.StatusBadRequest, "time_out_of_day", "时间必须落在 "+day+" 当天")
		return
	}

	dur := int64(0)
	if outAt != nil {
		if outAt.In(loc).Format("2006-01-02") != day {
			writeErr(w, http.StatusBadRequest, "time_out_of_day", "出馆时间必须落在 "+day+" 当天")
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

	if _, err := s.db.Exec(`UPDATE sessions SET `+strings.Join(sets, ", ")+` WHERE id = ? AND user_id = ?`, args...); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "保存失败")
		return
	}
	if err := s.recomputeDay(uid, day); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "汇总失败")
		return
	}

	session, _ := s.sessionByID(uid, id)
	c, _ := s.checkinByDay(uid, day)
	writeJSON(w, http.StatusOK, map[string]any{"session": session, "checkin": c})
}

// DELETE /api/v1/sessions/{id} —— 删掉某一段（当天其它段保留）
func (s *Server) handleDeleteSession(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	id := int64(qIntPath(r, "id"))
	if id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_id", "id 无效")
		return
	}

	var day string
	var inPhoto, outPhoto sql.NullString
	err := s.db.QueryRow(`SELECT day, in_photo, out_photo FROM sessions WHERE id = ? AND user_id = ?`, id, uid).
		Scan(&day, &inPhoto, &outPhoto)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "not_found", "这一段记录不存在")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "删除失败")
		return
	}

	if _, err := s.db.Exec(`DELETE FROM sessions WHERE id = ? AND user_id = ?`, id, uid); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "删除失败")
		return
	}
	if err := s.recomputeDay(uid, day); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "汇总失败")
		return
	}
	s.removePhoto(inPhoto.String)
	s.removePhoto(outPhoto.String)
	w.WriteHeader(http.StatusNoContent)
}
