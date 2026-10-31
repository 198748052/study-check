package main

import (
	"database/sql"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 账号安全：改密码 / 注销账号 / 管理员重置密码
// ---------------------------------------------------------------

// revokeAllRefreshTokens 让某个用户的所有设备失效（改密、重置、注销时用）。
func (s *Server) revokeAllRefreshTokens(uid int64) {
	_, _ = s.db.Exec(`UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`,
		time.Now().Format(time.RFC3339), uid)
}

// POST /api/v1/me/password —— 改密码（需要原密码），成功后当前设备拿到新 token
func (s *Server) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	var req struct {
		OldPassword string `json:"old_password"`
		NewPassword string `json:"new_password"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if len(req.NewPassword) < 6 {
		writeErr(w, http.StatusBadRequest, "weak_password", "新密码至少 6 位")
		return
	}
	// 原密码错误次数限流：token 被偷时不能无限猜密码
	now := time.Now()
	pwKey := fmt.Sprintf("pw:%d", uid)
	if d := s.loginLimit.wait(pwKey, now); d > 0 {
		writeTooMany(w, d)
		return
	}
	var hash string
	if err := s.db.QueryRow(`SELECT password_hash FROM users WHERE id = ?`, uid).Scan(&hash); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取用户失败")
		return
	}
	if !checkPassword(hash, req.OldPassword) {
		s.loginLimit.fail(pwKey, now)
		writeErr(w, http.StatusBadRequest, "bad_old_password", "原密码不正确")
		return
	}
	s.loginLimit.reset(pwKey)
	if req.NewPassword == req.OldPassword {
		writeErr(w, http.StatusBadRequest, "same_password", "新密码不能和原密码相同")
		return
	}
	newHash, err := hashPassword(req.NewPassword)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "密码处理失败")
		return
	}
	if _, err := s.db.Exec(`UPDATE users SET password_hash = ? WHERE id = ?`, newHash, uid); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "保存失败")
		return
	}
	// 其它设备全部下线，当前设备重新发一对 token，避免自己被踢出去
	s.revokeAllRefreshTokens(uid)
	s.respondToken(w, r, uid, http.StatusOK)
}

// DELETE /api/v1/me —— 注销账号：验证密码后删除全部数据与照片
func (s *Server) handleDeleteAccount(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	var req struct {
		Password string `json:"password"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	now := time.Now()
	pwKey := fmt.Sprintf("pw:%d", uid)
	if d := s.loginLimit.wait(pwKey, now); d > 0 {
		writeTooMany(w, d)
		return
	}
	var hash string
	if err := s.db.QueryRow(`SELECT password_hash FROM users WHERE id = ?`, uid).Scan(&hash); err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "用户不存在")
		return
	}
	if !checkPassword(hash, req.Password) {
		s.loginLimit.fail(pwKey, now)
		writeErr(w, http.StatusBadRequest, "bad_credentials", "密码不正确")
		return
	}
	s.loginLimit.reset(pwKey)

	// 先把照片路径收集起来，用户行删掉之后就查不到了
	var photos []string
	collect := func(q string) {
		rows, err := s.db.Query(q, uid)
		if err != nil {
			return
		}
		defer rows.Close()
		for rows.Next() {
			var a, b sql.NullString
			if err := rows.Scan(&a, &b); err == nil {
				photos = append(photos, a.String, b.String)
			}
		}
	}
	collect(`SELECT in_photo, out_photo FROM sessions WHERE user_id = ?`)
	collect(`SELECT photo, NULL FROM entries WHERE user_id = ?`)

	// users 的外键都是 ON DELETE CASCADE（checkins / sessions / entries / refresh_tokens）
	if _, err := s.db.Exec(`DELETE FROM users WHERE id = ?`, uid); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "注销失败")
		return
	}
	_, _ = s.db.Exec(`DELETE FROM idempotency WHERE user_id = ?`, uid)
	for _, p := range photos {
		s.removePhoto(p)
	}
	s.clearMediaCookie(w)
	w.WriteHeader(http.StatusNoContent)
}

// ---------------------------------------------------------------
// 管理员：用户列表 + 重置密码（忘记密码时的唯一自助出口）
// ---------------------------------------------------------------

type adminUserDTO struct {
	ID           int64  `json:"id"`
	Username     string `json:"username"`
	Nickname     string `json:"nickname"`
	DailyGoalMin int    `json:"daily_goal_min"`
	IsAdmin      bool   `json:"is_admin"`
	CreatedAt    string `json:"created_at"`
	Days         int    `json:"days"`
}

// GET /api/v1/system/users
func (s *Server) handleListUsers(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(`SELECT u.id, u.username, u.nickname, u.daily_goal_min, u.is_admin, u.created_at,
			(SELECT COUNT(1) FROM checkins c WHERE c.user_id = u.id)
		FROM users u ORDER BY u.id ASC`)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	items := make([]adminUserDTO, 0, 8)
	for rows.Next() {
		var u adminUserDTO
		var isAdmin int
		if err := rows.Scan(&u.ID, &u.Username, &u.Nickname, &u.DailyGoalMin, &isAdmin, &u.CreatedAt, &u.Days); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
			return
		}
		u.IsAdmin = isAdmin == 1
		items = append(items, u)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

// POST /api/v1/system/users/{id}/password —— 管理员重置某个账号的密码
func (s *Server) handleAdminResetPassword(w http.ResponseWriter, r *http.Request) {
	id := int64(qIntPath(r, "id"))
	if id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_id", "id 无效")
		return
	}
	var req struct {
		NewPassword string `json:"new_password"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if len(req.NewPassword) < 6 {
		writeErr(w, http.StatusBadRequest, "weak_password", "新密码至少 6 位")
		return
	}
	var exists int
	if err := s.db.QueryRow(`SELECT COUNT(1) FROM users WHERE id = ?`, id).Scan(&exists); err != nil || exists == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "用户不存在")
		return
	}
	hash, err := hashPassword(req.NewPassword)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "密码处理失败")
		return
	}
	if _, err := s.db.Exec(`UPDATE users SET password_hash = ? WHERE id = ?`, hash, id); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "保存失败")
		return
	}
	s.revokeAllRefreshTokens(id)
	if id == userID(r) {
		// 重置的是自己：顺手给当前设备发一对新 token，别把自己踢下线
		s.respondToken(w, r, id, http.StatusOK)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"reset": true, "user_id": id})
}

// ---------------------------------------------------------------
// 忘记密码兜底：命令行重置（不进服务器改库的唯一替代方案）
//
//	studycheck reset-password <用户名> [新密码]
//	也可以用 SC_NEW_PASSWORD 环境变量传密码，避免留在 shell history 里
//
// ---------------------------------------------------------------
func (s *Server) cliResetPassword(username, password string) error {
	username = strings.TrimSpace(username)
	if username == "" {
		return fmt.Errorf("请提供用户名：studycheck reset-password <用户名>")
	}
	if len(password) < 6 {
		return fmt.Errorf("新密码至少 6 位")
	}
	var uid int64
	err := s.db.QueryRow(`SELECT id FROM users WHERE username = ?`, username).Scan(&uid)
	if err == sql.ErrNoRows {
		return fmt.Errorf("用户 %q 不存在", username)
	}
	if err != nil {
		return err
	}
	hash, err := hashPassword(password)
	if err != nil {
		return err
	}
	if _, err := s.db.Exec(`UPDATE users SET password_hash = ? WHERE id = ?`, hash, uid); err != nil {
		return err
	}
	s.revokeAllRefreshTokens(uid)
	return nil
}
