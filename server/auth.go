package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// —— 极简 HS256 JWT（不引第三方库，减少依赖与升级成本）——

type tokenClaims struct {
	Sub int64 `json:"sub"`
	Iat int64 `json:"iat"`
	Exp int64 `json:"exp"`
}

var errInvalidToken = errors.New("token 无效或已过期")

func issueToken(secret []byte, uid int64, ttl time.Duration) (string, time.Time, error) {
	now := time.Now()
	exp := now.Add(ttl)
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payload, err := json.Marshal(tokenClaims{Sub: uid, Iat: now.Unix(), Exp: exp.Unix()})
	if err != nil {
		return "", time.Time{}, err
	}
	body := header + "." + base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(body))
	return body + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), exp, nil
}

func parseToken(secret []byte, tok string) (int64, error) {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return 0, errInvalidToken
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(parts[0] + "." + parts[1]))
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || !hmac.Equal(sig, mac.Sum(nil)) {
		return 0, errInvalidToken
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return 0, errInvalidToken
	}
	var c tokenClaims
	if err := json.Unmarshal(raw, &c); err != nil {
		return 0, errInvalidToken
	}
	if time.Now().Unix() > c.Exp {
		return 0, errInvalidToken
	}
	return c.Sub, nil
}

func hashPassword(pw string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(pw), bcrypt.DefaultCost)
	return string(b), err
}

func checkPassword(hash, pw string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(pw)) == nil
}

// —— 中间件 ——

type ctxKey string

const ctxUserID ctxKey = "uid"

// auth 校验 Authorization: Bearer <token>，把 uid 注入 context。
// 若开启 SC_DEV_NO_AUTH（联调用），未带 token 或 token 无效时回落到开发账号。
func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if uid, ok := s.tryToken(r); ok {
			next(w, r.WithContext(withUser(r, uid)))
			return
		}
		if s.cfg.DevNoAuth {
			uid, err := s.devUserID()
			if err != nil {
				writeErr(w, http.StatusInternalServerError, "internal", "开发账号不可用: "+err.Error())
				return
			}
			next(w, r.WithContext(withUser(r, uid)))
			return
		}
		h := r.Header.Get("Authorization")
		if h == "" {
			writeErr(w, http.StatusUnauthorized, "missing_token", "缺少 Authorization 头")
			return
		}
		writeErr(w, http.StatusUnauthorized, "bad_token", "Authorization 格式应为 Bearer <token>，或 token 已过期")
	}
}

// tryToken 解析请求里的 Bearer token，成功返回 uid。
func (s *Server) tryToken(r *http.Request) (int64, bool) {
	h := r.Header.Get("Authorization")
	if h == "" {
		return 0, false
	}
	const prefix = "Bearer "
	if len(h) <= len(prefix) || !strings.EqualFold(h[:len(prefix)], prefix) {
		return 0, false
	}
	uid, err := parseToken(s.cfg.JWTSecret, strings.TrimSpace(h[len(prefix):]))
	if err != nil {
		return 0, false
	}
	return uid, true
}

// devUserID 返回联调账号的 id；不存在则自动创建（仅 SC_DEV_NO_AUTH 时使用）。
func (s *Server) devUserID() (int64, error) {
	s.devOnce.Do(func() {
		name := s.cfg.DevUser
		if name == "" {
			name = "demo"
		}
		var id int64
		err := s.db.QueryRow(`SELECT id FROM users WHERE username = ?`, name).Scan(&id)
		if err == nil {
			s.devID = id
			return
		}
		hash, herr := hashPassword("dev-" + name + "-" + strconv.FormatInt(time.Now().UnixNano(), 36))
		if herr != nil {
			s.devErr = herr
			return
		}
		res, ierr := s.db.Exec(
			`INSERT INTO users(username, nickname, password_hash, daily_goal_min, is_admin, created_at)
			 VALUES(?, ?, ?, 120, 1, ?)`,
			name, "开发者", hash, time.Now().In(s.cfg.Loc).Format(time.RFC3339))
		if ierr != nil {
			s.devErr = ierr
			return
		}
		s.devID, _ = res.LastInsertId()
		log.Printf("⚠️  免登录模式：已自动创建开发账号 %q（id=%d，管理员）", name, s.devID)
	})
	return s.devID, s.devErr
}

// admin 在 auth 之上再校验管理员身份，用于系统更新等危险操作。
func (s *Server) admin(next http.HandlerFunc) http.HandlerFunc {
	return s.auth(func(w http.ResponseWriter, r *http.Request) {
		var isAdmin int
		if err := s.db.QueryRow(`SELECT is_admin FROM users WHERE id = ?`, userID(r)).Scan(&isAdmin); err != nil || isAdmin == 0 {
			writeErr(w, http.StatusForbidden, "forbidden", "需要管理员权限")
			return
		}
		next(w, r)
	})
}

func userID(r *http.Request) int64 {
	v, _ := r.Context().Value(ctxUserID).(int64)
	return v
}

func uidString(r *http.Request) string { return strconv.FormatInt(userID(r), 10) }
