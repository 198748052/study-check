package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

func withUser(r *http.Request, uid int64) context.Context {
	return context.WithValue(r.Context(), ctxUserID, uid)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v == nil {
		return
	}
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("writeJSON: %v", err)
	}
}

type errBody struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// writeErr 统一错误结构，方便安卓端按 code 分支处理。
func writeErr(w http.ResponseWriter, status int, code, msg string) {
	var b errBody
	b.Error.Code = code
	b.Error.Message = msg
	writeJSON(w, status, b)
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return errors.New("请求体不是合法 JSON: " + err.Error())
	}
	return nil
}

func qInt(r *http.Request, key string, def int) int {
	if v := r.URL.Query().Get(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func parseTimePtr(s string, loc *time.Location) *time.Time {
	if s == "" {
		return nil
	}
	for _, layout := range []string{time.RFC3339, "2006-01-02 15:04:05", "2006-01-02T15:04"} {
		if t, err := time.ParseInLocation(layout, s, loc); err == nil {
			return &t
		}
	}
	return nil
}

// limitUploadBody 给上传请求加上硬性体积上限。
// Content-Length 已知时先直判，避免收完整个 body 才发现超限。
func (s *Server) limitUploadBody(w http.ResponseWriter, r *http.Request) bool {
	return s.limitUploadBodyN(w, r, s.cfg.MaxPhotoMB<<20)
}

// limitUploadBodyN 允许调用方指定单个请求的「照片总量」额度（连拍场景更大）。
func (s *Server) limitUploadBodyN(w http.ResponseWriter, r *http.Request, photoBudget int64) bool {
	const hardCap = 96 << 20 // 超过这个值不再读，直接切断（防滥用）
	limit := photoBudget + (2 << 20)

	if r.ContentLength > 0 && r.ContentLength > limit {
		// 体量还在可接受范围内时，先把 body 读掉再返回，
		// 这样客户端能收到干净的 413，而不是连接被重置。
		if r.ContentLength <= hardCap {
			_, _ = io.CopyN(io.Discard, r.Body, hardCap)
		}
		writeErr(w, http.StatusRequestEntityTooLarge, "photo_too_large",
			"照片超过 "+strconv.FormatInt(s.cfg.MaxPhotoMB, 10)+"MB 限制，请压缩后再上传")
		return false
	}
	r.Body = newPeekBody(http.MaxBytesReader(w, r.Body, limit))
	return true
}

// peekBody 在标准 body 外面套一层 bufio，让处理器能在不消费数据的前提下
// 偷看开头若干 KB（用于 multipart 里的 day 字段，实现「先判断再收图」）。
type peekBody struct {
	*bufio.Reader
}

func newPeekBody(rc io.ReadCloser) *peekBody {
	return &peekBody{Reader: bufio.NewReaderSize(rc, peekLimit)}
}

func (p *peekBody) Close() error { return nil }

const peekLimit = 16 << 10

// peekField 从 multipart 前缀里取出某个文本字段的值；没找到返回空串。
func (p *peekBody) peekField(name string) string {
	head, _ := p.Peek(peekLimit)
	if len(head) == 0 {
		return ""
	}
	needle := []byte(`name="` + name + `"`)
	i := bytes.Index(head, needle)
	if i < 0 {
		return ""
	}
	rest := head[i+len(needle):]
	j := bytes.Index(rest, []byte("\r\n\r\n"))
	if j < 0 {
		return ""
	}
	val := rest[j+4:]
	if k := bytes.Index(val, []byte("\r\n")); k >= 0 {
		val = val[:k]
	}
	return strings.TrimSpace(string(val))
}

// dayHint 返回请求里声明的目标日期（优先 query，其次 multipart 字段）。
func dayHint(r *http.Request) string {
	if d := strings.TrimSpace(r.URL.Query().Get("day")); d != "" {
		return d
	}
	if pb, ok := r.Body.(*peekBody); ok {
		return pb.peekField("day")
	}
	return ""
}

// multipartErrStatus 把「body 超限」映射成 413，其余仍是 400。
func multipartErrStatus(err error) int {
	if err != nil && strings.Contains(err.Error(), "request body too large") {
		return http.StatusRequestEntityTooLarge
	}
	return http.StatusBadRequest
}

// drainBody 在提前返回错误前把请求体读掉（有上限），
// 否则客户端仍在写、服务端已关闭连接，只能看到 connection reset 而不是 4xx。
func drainBody(r *http.Request, max int64) {
	if r.Body == nil {
		return
	}
	_, _ = io.CopyN(io.Discard, r.Body, max)
	_ = r.Body.Close()
}
