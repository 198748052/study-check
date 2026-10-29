package main

import (
	"bytes"
	"compress/gzip"
	"net/http"
	"strconv"
	"strings"
)

// bufWriter 先缓冲响应体，便于决定是否 gzip。
// 只用于 /api/* 的 JSON 响应（都很小），对照片流不做缓冲。
const gzipBufLimit = 512 << 10

type bufWriter struct {
	header http.Header
	status int
	buf    bytes.Buffer
	over   bool
	real   http.ResponseWriter
}

func (b *bufWriter) Header() http.Header { return b.header }

func (b *bufWriter) WriteHeader(code int) {
	if b.status == 0 {
		b.status = code
	}
}

func (b *bufWriter) Write(p []byte) (int, error) {
	if b.status == 0 {
		b.status = http.StatusOK
	}
	// 超过上限就放弃缓冲，直接透传（几乎不会发生）
	if b.over {
		return b.real.Write(p)
	}
	if b.buf.Len()+len(p) > gzipBufLimit {
		b.over = true
		copyHeader(b.real.Header(), b.header)
		b.real.WriteHeader(b.status)
		if _, err := b.real.Write(b.buf.Bytes()); err != nil {
			return 0, err
		}
		b.buf.Reset()
		return b.real.Write(p)
	}
	return b.buf.Write(p)
}

func copyHeader(dst, src http.Header) {
	for k, vs := range src {
		for _, v := range vs {
			dst.Add(k, v)
		}
	}
}

// withGzip 对 /api/* 的 JSON 响应做 gzip（移动端流量友好），
// 小于 512 字节或客户端不支持时保持原样。
func (s *Server) withGzip(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/api/") ||
			strings.HasPrefix(r.URL.Path, "/api/v1/export/") || // CSV/ZIP 已经是流式下载
			r.Method == http.MethodHead ||
			!strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
			next.ServeHTTP(w, r)
			return
		}

		bw := &bufWriter{header: http.Header{}, real: w}
		next.ServeHTTP(bw, r)

		body := bw.buf.Bytes()
		h := w.Header()
		copyHeader(h, bw.header)
		h.Add("Vary", "Accept-Encoding")

		if bw.over || bw.status == http.StatusNoContent || len(body) < 512 {
			h.Del("Content-Encoding")
			h.Set("Content-Length", strconv.Itoa(len(body)))
			w.WriteHeader(bw.status)
			if len(body) > 0 {
				_, _ = w.Write(body)
			}
			return
		}

		var out bytes.Buffer
		gz, err := gzip.NewWriterLevel(&out, gzip.BestSpeed)
		if err != nil {
			h.Set("Content-Length", strconv.Itoa(len(body)))
			w.WriteHeader(bw.status)
			_, _ = w.Write(body)
			return
		}
		if _, err := gz.Write(body); err != nil {
			_ = gz.Close()
			h.Set("Content-Length", strconv.Itoa(len(body)))
			w.WriteHeader(bw.status)
			_, _ = w.Write(body)
			return
		}
		_ = gz.Close()

		h.Set("Content-Encoding", "gzip")
		h.Set("Content-Length", strconv.Itoa(out.Len()))
		w.WriteHeader(bw.status)
		_, _ = w.Write(out.Bytes())
	})
}
