package main

import (
	"database/sql"
	"errors"
	"net/http"
	"path/filepath"
	"strings"
	"time"
)

// —— 通用结构 ——

type Checkin struct {
	ID          int64   `json:"id"`
	Day         string  `json:"day"`
	InAt        *string `json:"in_at"`
	OutAt       *string `json:"out_at"`
	InPhotoURL  *string `json:"in_photo_url"`
	OutPhotoURL *string `json:"out_photo_url"`
	InThumbURL  *string `json:"in_thumb_url"`
	OutThumbURL *string `json:"out_thumb_url"`
	InNote      string  `json:"in_note"`
	OutNote     string  `json:"out_note"`
	InPlace     string  `json:"in_place"`
	OutPlace    string  `json:"out_place"`
	DurationSec int64   `json:"duration_sec"`
	UpdatedAt   string  `json:"updated_at"`
}

func mediaURL(rel *string) *string {
	if rel == nil || *rel == "" {
		return nil
	}
	u := "/media/" + *rel
	return &u
}

func thumbURL(rel *string) *string {
	if rel == nil || *rel == "" {
		return nil
	}
	ext := filepath.Ext(*rel)
	u := "/media/" + strings.TrimSuffix(*rel, ext) + "_t.jpg"
	return &u
}

func tsPtr(v sql.NullString) *string {
	if !v.Valid || v.String == "" {
		return nil
	}
	s := v.String
	return &s
}

const checkinCols = `id, day, in_at, out_at, in_photo, out_photo, in_note, out_note, in_place, out_place, duration_sec, updated_at`

func scanCheckin(sc interface{ Scan(...any) error }) (Checkin, error) {
	var c Checkin
	var inAt, outAt, inPhoto, outPhoto sql.NullString
	err := sc.Scan(&c.ID, &c.Day, &inAt, &outAt, &inPhoto, &outPhoto,
		&c.InNote, &c.OutNote, &c.InPlace, &c.OutPlace, &c.DurationSec, &c.UpdatedAt)
	if err != nil {
		return c, err
	}
	c.InAt, c.OutAt = tsPtr(inAt), tsPtr(outAt)
	ip, op := tsPtr(inPhoto), tsPtr(outPhoto)
	c.InPhotoURL, c.OutPhotoURL = mediaURL(ip), mediaURL(op)
	c.InThumbURL, c.OutThumbURL = thumbURL(ip), thumbURL(op)
	return c, nil
}

// —— 用户与认证 ——

type userDTO struct {
	ID           int64  `json:"id"`
	Username     string `json:"username"`
	Nickname     string `json:"nickname"`
	DailyGoalMin int    `json:"daily_goal_min"`
	IsAdmin      bool   `json:"is_admin"`
	CreatedAt    string `json:"created_at"`
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Nickname string `json:"nickname"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	if len(req.Username) < 3 || len(req.Username) > 32 {
		writeErr(w, http.StatusBadRequest, "bad_username", "用户名需 3-32 个字符")
		return
	}
	if len(req.Password) < 6 {
		writeErr(w, http.StatusBadRequest, "weak_password", "密码至少 6 位")
		return
	}
	if req.Nickname == "" {
		req.Nickname = req.Username
	}

	var userCount int
	if err := s.db.QueryRow(`SELECT COUNT(1) FROM users`).Scan(&userCount); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询用户失败")
		return
	}
	if userCount > 0 && !s.cfg.AllowRegister {
		writeErr(w, http.StatusForbidden, "register_disabled", "本服务已关闭注册")
		return
	}
	isAdmin := 0
	if userCount == 0 {
		isAdmin = 1 // 首个账号即管理员
	}

	hash, err := hashPassword(req.Password)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "密码处理失败")
		return
	}
	now := time.Now().In(s.cfg.Loc).Format(time.RFC3339)
	res, err := s.db.Exec(`INSERT INTO users(username, nickname, password_hash, is_admin, created_at) VALUES(?,?,?,?,?)`,
		req.Username, req.Nickname, hash, isAdmin, now)
	if err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			writeErr(w, http.StatusConflict, "user_exists", "用户名已被占用")
			return
		}
		writeErr(w, http.StatusInternalServerError, "internal", "注册失败")
		return
	}
	uid, _ := res.LastInsertId()
	s.respondToken(w, r, uid, http.StatusCreated)
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	var uid int64
	var hash string
	err := s.db.QueryRow(`SELECT id, password_hash FROM users WHERE username = ?`, strings.TrimSpace(req.Username)).Scan(&uid, &hash)
	// 用户不存在与密码错误返回同一提示，避免枚举账号
	if err != nil || !checkPassword(hash, req.Password) {
		writeErr(w, http.StatusUnauthorized, "bad_credentials", "用户名或密码错误")
		return
	}
	s.respondToken(w, r, uid, http.StatusOK)
}

// handleRefresh 用 refresh token 换新的 access token（并轮换 refresh token）。
func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if strings.TrimSpace(req.RefreshToken) == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "缺少 refresh_token")
		return
	}
	uid, err := s.rotateRefreshToken(req.RefreshToken, deviceOf(r))
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "refresh_invalid", err.Error())
		return
	}
	s.respondToken(w, r, uid, http.StatusOK)
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := decodeJSON(w, r, &req); err == nil && req.RefreshToken != "" {
		s.revokeRefreshToken(req.RefreshToken)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) respondToken(w http.ResponseWriter, r *http.Request, uid int64, status int) {
	access, exp, err := issueToken(s.cfg.JWTSecret, uid, s.cfg.AccessTokenTTL)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "签发 token 失败")
		return
	}
	refresh, refreshExp, err := s.createRefreshToken(uid, deviceOf(r))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "创建 refresh token 失败")
		return
	}
	u, err := s.loadUser(uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取用户失败")
		return
	}
	writeJSON(w, status, map[string]any{
		"token":              access,
		"expires_at":         exp.In(s.cfg.Loc).Format(time.RFC3339),
		"refresh_token":      refresh,
		"refresh_expires_at": refreshExp.In(s.cfg.Loc).Format(time.RFC3339),
		"user":               u,
	})
}

func (s *Server) loadUser(uid int64) (userDTO, error) {
	var u userDTO
	var isAdmin int
	err := s.db.QueryRow(`SELECT id, username, nickname, daily_goal_min, is_admin, created_at FROM users WHERE id = ?`, uid).
		Scan(&u.ID, &u.Username, &u.Nickname, &u.DailyGoalMin, &isAdmin, &u.CreatedAt)
	u.IsAdmin = isAdmin == 1
	return u, err
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	u, err := s.loadUser(userID(r))
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "用户不存在")
		return
	}
	writeJSON(w, http.StatusOK, u)
}

func (s *Server) handleUpdateMe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Nickname     *string `json:"nickname"`
		DailyGoalMin *int    `json:"daily_goal_min"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if req.Nickname != nil {
		if _, err := s.db.Exec(`UPDATE users SET nickname = ? WHERE id = ?`, *req.Nickname, userID(r)); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "更新失败")
			return
		}
	}
	if req.DailyGoalMin != nil {
		if *req.DailyGoalMin < 0 || *req.DailyGoalMin > 1440 {
			writeErr(w, http.StatusBadRequest, "bad_goal", "每日目标需在 0-1440 分钟之间")
			return
		}
		if _, err := s.db.Exec(`UPDATE users SET daily_goal_min = ? WHERE id = ?`, *req.DailyGoalMin, userID(r)); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "更新失败")
			return
		}
	}
	s.handleMe(w, r)
}

// —— 打卡 ——

func (s *Server) checkinByDay(uid int64, day string) (*Checkin, error) {
	row := s.db.QueryRow(`SELECT `+checkinCols+` FROM checkins WHERE user_id = ? AND day = ?`, uid, day)
	c, err := scanCheckin(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// readPhoto 从 multipart 取照片（字段名固定 photo）。
func (s *Server) readPhoto(w http.ResponseWriter, r *http.Request, now time.Time) (string, bool) {
	file, hdr, err := r.FormFile("photo")
	if err != nil {
		writeErr(w, http.StatusBadRequest, "photo_required", "打卡必须上传照片（字段名 photo）")
		return "", false
	}
	defer file.Close()
	rel, _, err := s.savePhoto(file, hdr, now)
	if err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, errPhotoTooBig) {
			status = http.StatusRequestEntityTooLarge
		}
		writeErr(w, status, "bad_photo", err.Error())
		return "", false
	}
	return rel, true
}

// resolveDay 决定这次打卡归属哪一天：
//   - 显式传 day=YYYY-MM-DD（离线补传场景）优先
//   - 否则按客户端时区的"今天"
func resolveDay(r *http.Request, now time.Time, loc *time.Location) (string, error) {
	if d := strings.TrimSpace(r.FormValue("day")); d != "" {
		t, err := time.ParseInLocation("2006-01-02", d, loc)
		if err != nil {
			return "", errors.New("day 格式应为 YYYY-MM-DD")
		}
		// 只允许补最近 30 天，防止乱写
		today := time.Now().In(loc).Truncate(24 * time.Hour)
		if t.After(today.AddDate(0, 0, 1)) || t.Before(today.AddDate(0, 0, -30)) {
			return "", errors.New("day 超出允许范围（最近 30 天）")
		}
		return t.Format("2006-01-02"), nil
	}
	return now.Format("2006-01-02"), nil
}

// POST /api/v1/checkins/in —— 进馆打卡
func (s *Server) handleCheckIn(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	// 幂等重放：只依赖请求头，不必读照片
	if s.idemLookup(w, r, uid, "in") {
		return
	}
	// 体积上限：超限直接 413，避免把十几 MB 白传一遍
	if !s.limitUploadBody(w, r) {
		return
	}
	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	// 快速失败：目标日期是「今天」时（绝大多数情况），先判状态再收照片
	if q := dayHint(r); q == "" || q == now.Format("2006-01-02") {
		if ex, err := s.checkinByDay(uid, now.Format("2006-01-02")); err == nil && ex != nil && ex.InAt != nil {
			drainBody(r, 32<<20)
			writeErr(w, http.StatusConflict, "already_checked_in", "这一天已经进馆打卡了")
			return
		}
	}
	if err := r.ParseMultipartForm(s.cfg.MaxPhotoMB << 20); err != nil {
		writeErr(w, multipartErrStatus(err), "bad_multipart", "请使用 multipart/form-data 上传，且照片不要超过上限")
		return
	}
	day, err := resolveDay(r, now, loc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", err.Error())
		return
	}

	existing, err := s.checkinByDay(uid, day)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	if existing != nil && existing.InAt != nil {
		writeErr(w, http.StatusConflict, "already_checked_in", "这一天已经进馆打卡了")
		return
	}

	rel, ok := s.readPhoto(w, r, now)
	if !ok {
		return
	}

	ts := now.Format(time.RFC3339)
	if t := parseTimePtr(r.FormValue("taken_at"), loc); t != nil {
		ts = t.In(loc).Format(time.RFC3339)
	}
	note := strings.TrimSpace(r.FormValue("note"))
	place := strings.TrimSpace(r.FormValue("place"))
	stamp := time.Now().Format(time.RFC3339)

	if existing != nil {
		_, err = s.db.Exec(`UPDATE checkins SET in_at=?, in_photo=?, in_note=?, in_place=?, updated_at=? WHERE id=?`,
			ts, rel, note, place, stamp, existing.ID)
	} else {
		_, err = s.db.Exec(`INSERT INTO checkins(user_id, day, in_at, in_photo, in_note, in_place, created_at, updated_at)
			VALUES(?,?,?,?,?,?,?,?)`,
			uid, day, ts, rel, note, place, stamp, stamp)
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "保存打卡失败")
		return
	}
	c, _ := s.checkinByDay(uid, day)
	if c == nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取打卡失败")
		return
	}
	s.idemStore(r, uid, "in", http.StatusCreated, c)
	writeJSON(w, http.StatusCreated, c)
}

// POST /api/v1/checkins/out —— 出馆打卡
func (s *Server) handleCheckOut(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	if s.idemLookup(w, r, uid, "out") {
		return
	}
	if !s.limitUploadBody(w, r) {
		return
	}
	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	// 快速失败：今天还没进馆 / 已经出馆，直接回 409，不读照片
	if q := dayHint(r); q == "" || q == now.Format("2006-01-02") {
		ex, err := s.checkinByDay(uid, now.Format("2006-01-02"))
		if err == nil {
			if ex == nil || ex.InAt == nil {
				drainBody(r, 32<<20)
				writeErr(w, http.StatusConflict, "not_checked_in", "这一天还没有进馆打卡")
				return
			}
			if ex.OutAt != nil {
				drainBody(r, 32<<20)
				writeErr(w, http.StatusConflict, "already_checked_out", "这一天已经出馆打卡了")
				return
			}
		}
	}
	if err := r.ParseMultipartForm(s.cfg.MaxPhotoMB << 20); err != nil {
		writeErr(w, multipartErrStatus(err), "bad_multipart", "请使用 multipart/form-data 上传，且照片不要超过上限")
		return
	}
	day, err := resolveDay(r, now, loc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", err.Error())
		return
	}

	existing, err := s.checkinByDay(uid, day)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	if existing == nil || existing.InAt == nil {
		writeErr(w, http.StatusConflict, "not_checked_in", "这一天还没有进馆打卡")
		return
	}
	if existing.OutAt != nil {
		writeErr(w, http.StatusConflict, "already_checked_out", "这一天已经出馆打卡了")
		return
	}

	rel, ok := s.readPhoto(w, r, now)
	if !ok {
		return
	}

	outAt := now
	if t := parseTimePtr(r.FormValue("taken_at"), loc); t != nil {
		outAt = *t
	}
	inAt, err := time.Parse(time.RFC3339, *existing.InAt)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "历史数据异常")
		return
	}
	dur := int64(outAt.Sub(inAt).Seconds())
	if dur < 0 {
		dur = 0
	}
	// 单日自习不可能超过 24 小时；超出一般是补传时设备时间异常，钳一下避免脏数据
	if dur > 86400 {
		dur = 86400
	}

	_, err = s.db.Exec(`UPDATE checkins SET out_at=?, out_photo=?, out_note=?, out_place=?, duration_sec=?, updated_at=? WHERE id=?`,
		outAt.Format(time.RFC3339), rel, strings.TrimSpace(r.FormValue("note")), strings.TrimSpace(r.FormValue("place")),
		dur, time.Now().Format(time.RFC3339), existing.ID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "保存打卡失败")
		return
	}
	c, _ := s.checkinByDay(uid, day)
	if c == nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取打卡失败")
		return
	}
	s.idemStore(r, uid, "out", http.StatusOK, c)
	writeJSON(w, http.StatusOK, c)
}

// GET /api/v1/checkins/today
func (s *Server) handleToday(w http.ResponseWriter, r *http.Request) {
	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	day := now.Format("2006-01-02")

	c, err := s.checkinByDay(userID(r), day)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	var state string
	switch {
	case c == nil || c.InAt == nil:
		state = "not_started"
	case c.OutAt == nil:
		state = "in_library"
	default:
		state = "finished"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"day":     day,
		"state":   state,
		"checkin": c,
		"now":     now.Format(time.RFC3339),
	})
}

// GET /api/v1/checkins?month=YYYY-MM | ?since=RFC3339 | ?limit=&offset=
func (s *Server) handleListCheckins(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	limit := qInt(r, "limit", 30)
	if limit <= 0 || limit > 200 {
		limit = 30
	}
	offset := qInt(r, "offset", 0)
	if offset < 0 {
		offset = 0
	}

	var (
		rows *sql.Rows
		err  error
	)
	switch {
	case r.URL.Query().Get("since") != "":
		// 增量同步：客户端离线补传后拉取变更，避免整表重传
		since := r.URL.Query().Get("since")
		rows, err = s.db.Query(`SELECT `+checkinCols+` FROM checkins
			WHERE user_id = ? AND updated_at > ? ORDER BY updated_at ASC LIMIT ?`,
			uid, since, limit)
	case r.URL.Query().Get("month") != "":
		rows, err = s.db.Query(`SELECT `+checkinCols+` FROM checkins
			WHERE user_id = ? AND day LIKE ? ORDER BY day DESC LIMIT ? OFFSET ?`,
			uid, r.URL.Query().Get("month")+"-%", limit, offset)
	default:
		rows, err = s.db.Query(`SELECT `+checkinCols+` FROM checkins
			WHERE user_id = ? ORDER BY day DESC LIMIT ? OFFSET ?`, uid, limit, offset)
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	items := make([]Checkin, 0, limit)
	for rows.Next() {
		c, err := scanCheckin(rows)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
			return
		}
		items = append(items, c)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items": items, "limit": limit, "offset": offset, "count": len(items),
		"server_time": time.Now().In(s.cfg.Loc).Format(time.RFC3339),
	})
}

// DELETE /api/v1/checkins/{id}
func (s *Server) handleDeleteCheckin(w http.ResponseWriter, r *http.Request) {
	id := qIntPath(r, "id")
	if id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_id", "id 无效")
		return
	}
	res, err := s.db.Exec(`DELETE FROM checkins WHERE id = ? AND user_id = ?`, id, userID(r))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "删除失败")
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "记录不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
