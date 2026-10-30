package main

import (
	"context"
	"database/sql"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata" // 内嵌时区库，客户端按 IANA 时区名传入也能解析
)

// version 由构建脚本注入：-ldflags "-X main.version=1.2.3"
var version = "1.6.4"

type Server struct {
	db  *sql.DB
	cfg Config

	startedAt time.Time // 供系统状态页展示运行时长

	// 登录失败限流（IP + 用户名维度，内存计数）
	loginLimit *loginLimiter

	// 免登录联调账号（SC_DEV_NO_AUTH）懒加载缓存
	devOnce sync.Once
	devID   int64
	devErr  error
}

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	cfg := loadConfig()

	db, err := openDB(cfg.DBPath)
	if err != nil {
		log.Fatalf("打开数据库失败: %v", err)
	}
	defer db.Close()

	s := &Server{db: db, cfg: cfg, loginLimit: newLoginLimiter(), startedAt: time.Now()}
	if cfg.SeedDemo {
		s.seedDemo()
	}
	if cfg.AutoUpdate {
		go s.autoUpdateLoop()
	}
	if cfg.BackupEnabled {
		go s.backupLoop()
	}
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           s.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		log.Printf("studycheck %s 启动于 %s（照片目录 %s）", version, cfg.Addr, cfg.UploadDir)
		if cfg.DevNoAuth {
			log.Printf("⚠️  SC_DEV_NO_AUTH 已开启：所有接口跳过登录校验，一律当作账号 %q 操作。仅限本地联调/预览，切勿用于生产。", cfg.DevUser)
		}
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("监听失败: %v", err)
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()

	log.Println("收到退出信号，正在优雅关闭…")
	shutCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutCtx); err != nil {
		log.Printf("关闭异常: %v", err)
	}
}

func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /api/v1/health", s.handleHealth)
	mux.HandleFunc("POST /api/v1/auth/register", s.handleRegister)
	mux.HandleFunc("POST /api/v1/auth/login", s.handleLogin)
	mux.HandleFunc("POST /api/v1/auth/refresh", s.handleRefresh)
	mux.HandleFunc("POST /api/v1/auth/logout", s.handleLogout)

	mux.HandleFunc("GET /api/v1/me", s.auth(s.handleMe))
	mux.HandleFunc("PATCH /api/v1/me", s.auth(s.handleUpdateMe))

	mux.HandleFunc("GET /api/v1/checkins/today", s.auth(s.handleToday))
	mux.HandleFunc("GET /api/v1/checkins", s.auth(s.handleListCheckins))
	mux.HandleFunc("POST /api/v1/checkins/in", s.auth(s.handleCheckIn))
	mux.HandleFunc("POST /api/v1/checkins/out", s.auth(s.handleCheckOut))
	mux.HandleFunc("PATCH /api/v1/checkins/{id}", s.auth(s.handlePatchCheckin))
	mux.HandleFunc("DELETE /api/v1/checkins/{id}", s.auth(s.handleDeleteCheckin))

	// 数据导出（个人数据带走）
	mux.HandleFunc("GET /api/v1/export/checkins.csv", s.auth(s.handleExportCheckinsCSV))
	mux.HandleFunc("GET /api/v1/export/entries.csv", s.auth(s.handleExportEntriesCSV))
	mux.HandleFunc("GET /api/v1/export/photos.zip", s.auth(s.handleExportPhotosZip))

	// 每日详情（当天的过程记录：多张照片 + 笔记）
	mux.HandleFunc("POST /api/v1/entries", s.auth(s.handleCreateEntries))
	mux.HandleFunc("GET /api/v1/entries", s.auth(s.handleListEntries))
	mux.HandleFunc("DELETE /api/v1/entries/{id}", s.auth(s.handleDeleteEntry))

	mux.HandleFunc("GET /api/v1/stats", s.auth(s.handleStats))

	// 数据库备份与系统状态（管理员）
	mux.HandleFunc("GET /api/v1/system/backups", s.admin(s.handleListBackups))
	mux.HandleFunc("POST /api/v1/system/backups", s.admin(s.handleCreateBackup))
	mux.HandleFunc("GET /api/v1/system/backups/{name}", s.admin(s.handleDownloadBackup))
	mux.HandleFunc("DELETE /api/v1/system/backups/{name}", s.admin(s.handleDeleteBackup))
	mux.HandleFunc("POST /api/v1/system/backups/{name}/restore", s.admin(s.handleRestoreBackup))
	mux.HandleFunc("GET /api/v1/system/status", s.admin(s.handleSystemStatus))
	mux.HandleFunc("POST /api/v1/system/cleanup", s.admin(s.handleCleanupPhotos))

	// 系统更新（管理员）
	mux.HandleFunc("GET /api/v1/system/update", s.admin(s.handleUpdateStatus))
	mux.HandleFunc("POST /api/v1/system/update", s.admin(s.handleUpdateApply))
	mux.HandleFunc("POST /api/v1/system/update/check", s.admin(s.handleUpdateCheck))
	mux.HandleFunc("GET /api/v1/system/update/log", s.admin(s.handleUpdateLog))

	// 照片需鉴权：App 用 Authorization 头（Coil 拦截器），浏览器用 HttpOnly cookie sc_media
	mux.Handle("GET /media/", s.mediaAuth(s.mediaHandler()))

	// 服务信息（供客户端/运维探测）
	mux.HandleFunc("GET /api/v1/info", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"service": "studycheck",
			"version": version,
			"mode":    "single-binary（Web 界面 + Android API）",
			"ui":      "/",
		})
	})

	// Web 界面：静态资源已嵌入二进制，未命中的路径回落到 index.html
	mux.Handle("GET /", s.webHandler())

	return s.withCORS(s.withLogging(s.withGzip(mux)))
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":                "ok",
		"version":               version,
		"server_time":           time.Now().In(s.cfg.Loc).Format(time.RFC3339),
		"min_supported_version": s.cfg.MinVersion,
	})
}

// mediaAuth 给照片加鉴权中间件。移动端带 header，浏览器带 sc_media cookie。
func (s *Server) mediaAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.mediaAuthorized(r) {
			writeErr(w, http.StatusUnauthorized, "missing_token", "照片需要登录后访问")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// mediaHandler 托管打卡照片。文件名是随机 ID，不可枚举；如需更严格可加鉴权。
func (s *Server) mediaHandler() http.Handler {
	root, err := filepath.Abs(s.cfg.UploadDir)
	if err != nil {
		log.Fatalf("照片目录无效: %v", err)
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rel := pathClean(strings.TrimPrefix(r.URL.Path, "/media/"))
		if rel == "" || strings.HasPrefix(rel, "..") {
			http.NotFound(w, r)
			return
		}
		full := filepath.Join(root, filepath.FromSlash(rel))
		if !strings.HasPrefix(full, root+string(os.PathSeparator)) {
			http.NotFound(w, r)
			return
		}
		st, err := os.Stat(full)
		if err != nil || st.IsDir() {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
		http.ServeFile(w, r, full)
	})
}

func pathClean(p string) string {
	p = strings.ReplaceAll(p, "\\", "/")
	parts := make([]string, 0, 8)
	for _, seg := range strings.Split(p, "/") {
		switch seg {
		case "", ".":
			continue
		case "..":
			if len(parts) > 0 {
				parts = parts[:len(parts)-1]
			}
		default:
			parts = append(parts, seg)
		}
	}
	return strings.Join(parts, "/")
}

func (s *Server) withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if o := s.cfg.CORSOrigin; o != "" {
			w.Header().Set("Access-Control-Allow-Origin", o)
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Max-Age", "86400")
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(c int) {
	r.status = c
	r.ResponseWriter.WriteHeader(c)
}

func (s *Server) withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/api/") {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: 200}
		next.ServeHTTP(rec, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, rec.status, time.Since(start).Round(time.Millisecond))
	})
}
