package main

import (
	"crypto/rand"
	"encoding/hex"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// Config 全部来自环境变量，方便 systemd / docker 注入，无需配置文件。
type Config struct {
	Addr       string
	DBPath     string
	UploadDir  string
	JWTSecret  []byte
	Loc        *time.Location
	CORSOrigin string
	MaxPhotoMB int64

	AccessTokenTTL  time.Duration // 短效 access token
	RefreshTokenTTL time.Duration // 长效 refresh token（可吊销）
	MinVersion      string        // 低于此版本的 App 强制升级

	AllowRegister bool   // 个人使用建议 false
	ServiceName   string // systemd 单元名，更新脚本用它重启

	UpdateEnabled     bool
	UpdateManifestURL string
	AutoUpdate        bool

	SeedDemo bool // 仅演示环境用

	// 仅供本地联调/预览：开启后所有接口不再校验登录态，
	// 未带 token 的请求一律当作 DevUser 操作。生产环境必须保持 false。
	DevNoAuth bool
	DevUser   string
}

func loadConfig() Config {
	cfg := Config{
		Addr:              env("SC_ADDR", ":8080"),
		DBPath:            env("SC_DB", "./data/studycheck.db"),
		UploadDir:         env("SC_UPLOADS", "./data/uploads"),
		AccessTokenTTL:    time.Duration(envInt("SC_ACCESS_TOKEN_HOURS", 2)) * time.Hour,
		RefreshTokenTTL:   time.Duration(envInt("SC_REFRESH_TOKEN_DAYS", 90)) * 24 * time.Hour,
		MinVersion:        env("SC_MIN_VERSION", ""),
		CORSOrigin:        env("SC_CORS_ORIGIN", ""),
		MaxPhotoMB:        int64(envInt("SC_MAX_PHOTO_MB", 12)),
		AllowRegister:     envBool("SC_ALLOW_REGISTER", true),
		ServiceName:       env("SC_SERVICE_NAME", "studycheck"),
		UpdateEnabled:     envBool("SC_UPDATE_ENABLED", true),
		UpdateManifestURL: env("SC_UPDATE_MANIFEST_URL", ""),
		AutoUpdate:        envBool("SC_AUTO_UPDATE", false),
		SeedDemo:          envBool("SC_SEED_DEMO", false),
		DevNoAuth:         envBool("SC_DEV_NO_AUTH", false),
		DevUser:           env("SC_DEV_USER", "demo"),
	}

	loc, err := time.LoadLocation(env("SC_TZ", "Asia/Shanghai"))
	if err != nil {
		log.Printf("warning: 时区 %q 无效，回落到 UTC", env("SC_TZ", "Asia/Shanghai"))
		loc = time.UTC
	}
	cfg.Loc = loc

	if err := os.MkdirAll(filepath.Dir(cfg.DBPath), 0o755); err != nil {
		log.Fatalf("创建数据库目录失败: %v", err)
	}
	if err := os.MkdirAll(cfg.UploadDir, 0o755); err != nil {
		log.Fatalf("创建图片目录失败: %v", err)
	}

	cfg.JWTSecret = loadSecret(cfg.DBPath)
	return cfg
}

// loadSecret 未显式配置密钥时自动生成并持久化，避免重启后所有 token 失效。
func loadSecret(dbPath string) []byte {
	if s := os.Getenv("SC_JWT_SECRET"); s != "" {
		return []byte(s)
	}
	p := filepath.Join(filepath.Dir(dbPath), "jwt.secret")
	if b, err := os.ReadFile(p); err == nil && len(b) >= 32 {
		return b
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		log.Fatalf("生成密钥失败: %v", err)
	}
	secret := []byte(hex.EncodeToString(buf))
	if err := os.WriteFile(p, secret, 0o600); err != nil {
		log.Printf("warning: 密钥写入失败（重启后 token 会失效）: %v", err)
	}
	return secret
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func envBool(k string, def bool) bool {
	v := os.Getenv(k)
	if v == "" {
		return def
	}
	switch v {
	case "1", "true", "TRUE", "yes", "on":
		return true
	case "0", "false", "FALSE", "no", "off":
		return false
	}
	return def
}
