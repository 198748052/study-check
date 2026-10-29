package main

import (
	"embed"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

// Web 前端静态资源，编译时嵌入二进制，部署仍然只需一个文件。
//
//go:embed web
var webAssets embed.FS

// webHandler 托管内嵌的 Web 界面；未知路径回落到 index.html（单页应用）。
// /api/ 与 /media/ 前缀不在此处理，未命中时返回 JSON 404，避免把接口路径返回成 HTML。
func (s *Server) webHandler() http.Handler {
	sub, err := fs.Sub(webAssets, "web")
	if err != nil {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			writeErr(w, http.StatusInternalServerError, "internal", "前端资源不可用")
		})
	}
	files := http.FileServer(http.FS(sub))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") || strings.HasPrefix(r.URL.Path, "/media/") {
			writeErr(w, http.StatusNotFound, "not_found", "接口不存在")
			return
		}

		name := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if name == "" || name == "." {
			name = "index.html"
		}

		if f, err := sub.Open(name); err == nil {
			_ = f.Close()
			if strings.HasSuffix(name, ".html") {
				w.Header().Set("Cache-Control", "no-cache")
			} else {
				w.Header().Set("Cache-Control", "public, max-age=300")
			}
			files.ServeHTTP(w, r)
			return
		}

		// 单页应用回落：其余路径一律返回 index.html
		w.Header().Set("Cache-Control", "no-cache")
		r.URL.Path = "/"
		files.ServeHTTP(w, r)
	})
}
