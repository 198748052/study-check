package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

// —— 移动端专用能力：refresh token / 幂等 / 客户端时区 / 版本门禁 ——

func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func randomToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return ""
	}
	return hex.EncodeToString(b)
}

// createRefreshToken 生成 refresh token（库里只存 hash），返回明文给客户端。
func (s *Server) createRefreshToken(uid int64, device string) (string, time.Time, error) {
	raw := randomToken()
	if raw == "" {
		return "", time.Time{}, errors.New("生成随机串失败")
	}
	exp := time.Now().Add(s.cfg.RefreshTokenTTL)
	_, err := s.db.Exec(`INSERT INTO refresh_tokens(user_id, token_hash, device, expires_at, created_at)
		VALUES(?,?,?,?,?)`,
		uid, sha256Hex(raw), device, exp.Format(time.RFC3339), time.Now().Format(time.RFC3339))
	if err != nil {
		return "", time.Time{}, err
	}
	return raw, exp, nil
}

// rotateRefreshToken 校验并吊销旧 token，返回新的 uid。
func (s *Server) rotateRefreshToken(raw, device string) (int64, error) {
	var (
		id      int64
		uid     int64
		expStr  string
		revoked sql.NullString
	)
	err := s.db.QueryRow(`SELECT id, user_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ?`,
		sha256Hex(raw)).Scan(&id, &uid, &expStr, &revoked)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, errors.New("refresh token 无效")
	}
	if err != nil {
		return 0, err
	}
	if revoked.Valid && revoked.String != "" {
		// 已吊销的 token 被再次使用，视为泄露：吊销该用户全部 refresh token
		_, _ = s.db.Exec(`UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`,
			time.Now().Format(time.RFC3339), uid)
		return 0, errors.New("refresh token 已失效，请重新登录")
	}
	exp, err := time.Parse(time.RFC3339, expStr)
	if err != nil || time.Now().After(exp) {
		return 0, errors.New("refresh token 已过期，请重新登录")
	}
	if _, err := s.db.Exec(`UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?`,
		time.Now().Format(time.RFC3339), id); err != nil {
		return 0, err
	}
	return uid, nil
}

func (s *Server) revokeRefreshToken(raw string) {
	_, _ = s.db.Exec(`UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL`,
		time.Now().Format(time.RFC3339), sha256Hex(raw))
}

func deviceOf(r *http.Request) string {
	if d := r.Header.Get("X-Device-Name"); d != "" {
		return truncate(d, 64)
	}
	return truncate(r.UserAgent(), 64)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// clientLoc 支持移动端按手机本地时区界定"哪一天"，
// 避免深夜在馆跨零点时进馆算昨天、出馆算今天。
func (s *Server) clientLoc(r *http.Request) *time.Location {
	if name := r.Header.Get("X-Timezone"); name != "" {
		if loc, err := time.LoadLocation(name); err == nil {
			return loc
		}
	}
	return s.cfg.Loc
}

// —— 幂等键 ——

// idemLookup 命中则直接回放首次响应。
func (s *Server) idemLookup(w http.ResponseWriter, r *http.Request, uid int64, endpoint string) bool {
	key := r.Header.Get("Idempotency-Key")
	if key == "" {
		return false
	}
	var status int
	var body string
	err := s.db.QueryRow(`SELECT status, body FROM idempotency WHERE key = ? AND user_id = ? AND endpoint = ?`,
		key, uid, endpoint).Scan(&status, &body)
	if err != nil {
		return false
	}
	// 重放是「秒回」路径，但客户端可能还在传照片；
	// 先把 body 读掉再回响应，避免客户端看到 connection reset。
	drainBody(r, 32<<20)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Idempotent-Replayed", "true")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
	return true
}

func (s *Server) idemStore(r *http.Request, uid int64, endpoint string, status int, v any) {
	key := r.Header.Get("Idempotency-Key")
	if key == "" {
		return
	}
	body, err := json.Marshal(v)
	if err != nil {
		return
	}
	// 顺手清理 7 天前的旧键，避免表无限增长
	_, _ = s.db.Exec(`DELETE FROM idempotency WHERE created_at < ?`,
		time.Now().AddDate(0, 0, -7).Format(time.RFC3339))
	_, _ = s.db.Exec(`INSERT OR REPLACE INTO idempotency(key, user_id, endpoint, status, body, created_at)
		VALUES(?,?,?,?,?,?)`,
		key, uid, endpoint, status, string(body), time.Now().Format(time.RFC3339))
}

// mediaCookieName 是网页端取图用的 HttpOnly 会话 cookie，
// 避免把 token 拼进图片 URL（会漏进 Nginx 日志和 Referer）。
const mediaCookieName = "sc_media"

// mediaAuthorized 供 /media/* 鉴权，依次尝试：
// Authorization 头（App/Coil）、sc_media cookie（浏览器）、?token=（旧客户端兜底）。
func (s *Server) mediaAuthorized(r *http.Request) bool {
	if s.cfg.DevNoAuth {
		return true
	}
	tok := ""
	if h := r.Header.Get("Authorization"); h != "" {
		if len(h) > 7 && strings.EqualFold(h[:7], "Bearer ") {
			tok = strings.TrimSpace(h[7:])
		}
	}
	if tok == "" {
		if c, err := r.Cookie(mediaCookieName); err == nil {
			tok = strings.TrimSpace(c.Value)
		}
	}
	if tok == "" {
		tok = r.URL.Query().Get("token")
	}
	if tok == "" {
		return false
	}
	_, err := parseToken(s.cfg.JWTSecret, tok)
	return err == nil
}

// setMediaCookie 在下发 token 时同步种下取图 cookie。App 不发 cookie，互不影响。
func (s *Server) setMediaCookie(w http.ResponseWriter, r *http.Request, tok string) {
	http.SetCookie(w, &http.Cookie{
		Name:     mediaCookieName,
		Value:    tok,
		Path:     "/",
		HttpOnly: true,
		Secure:   r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https"),
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(s.cfg.AccessTokenTTL.Seconds()),
	})
}

// clearMediaCookie 退出登录时清掉取图 cookie。
func (s *Server) clearMediaCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     mediaCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
}
