package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/disintegration/imaging"
	_ "golang.org/x/image/webp" // 注册 webp 解码器（前端压缩后就是 webp）
)

const (
	thumbEdge  = 480  // 列表缩略图长边
	maxEdge    = 1600 // 原图长边上限，超过则压缩
	thumbQual  = 78
	originalQa = 85
)

var allowedImageExt = map[string]string{
	"image/jpeg": ".jpg",
	"image/png":  ".png",
	"image/webp": ".webp",
}

var errPhotoTooBig = errors.New("照片超过大小限制")

// removePhoto 删除照片原图与自动生成的缩略图。
// 路径异常或文件不存在都静默跳过——删记录不能因为清文件失败而失败。
func (s *Server) removePhoto(rel string) {
	rel = strings.TrimSpace(rel)
	if rel == "" {
		return
	}
	root, err := filepath.Abs(s.cfg.UploadDir)
	if err != nil {
		return
	}
	thumb := strings.TrimSuffix(rel, filepath.Ext(rel)) + "_t.jpg"
	for _, p := range []string{rel, thumb} {
		full, err := filepath.Abs(filepath.Join(root, filepath.FromSlash(p)))
		if err != nil || !strings.HasPrefix(full, root+string(os.PathSeparator)) {
			continue
		}
		_ = os.Remove(full)
	}
}

// savePhoto 校验图片、按日期分目录落盘，并生成列表用缩略图。
// 返回相对路径（存库）与可直接访问的 URL。
// savePhoto 保存原图 + 缩略图，并顺手把照片里的 EXIF 拍摄时间读出来（没有就 nil）。
// 调用方在客户端没传 taken_at 时用它兜底，避免「晚上才传成功」被记成晚上。
func (s *Server) savePhoto(f multipart.File, hdr *multipart.FileHeader, now time.Time) (rel, relThumb string, exifAt *time.Time, err error) {
	limit := s.cfg.MaxPhotoMB << 20
	buf, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil {
		return "", "", nil, err
	}
	if int64(len(buf)) > limit {
		return "", "", nil, errPhotoTooBig
	}
	if len(buf) < 512 {
		return "", "", nil, errors.New("图片内容为空")
	}

	ct := http.DetectContentType(buf[:512])
	ext, ok := allowedImageExt[ct]
	if !ok {
		return "", "", nil, fmt.Errorf("不支持的图片格式 %s（仅支持 jpg/png/webp）", ct)
	}

	img, err := imaging.Decode(bytes.NewReader(buf), imaging.AutoOrientation(true))
	if err != nil {
		return "", "", nil, fmt.Errorf("图片解码失败: %w", err)
	}

	dir := filepath.Join(now.Format("2006"), now.Format("01"), now.Format("02"))
	absDir := filepath.Join(s.cfg.UploadDir, dir)
	if err := os.MkdirAll(absDir, 0o755); err != nil {
		return "", "", nil, err
	}

	id := randHex()
	// 原图：仅在过大时重编码，省磁盘和带宽
	name := id + ext
	bounds := img.Bounds()
	needShrink := bounds.Dx() > maxEdge || bounds.Dy() > maxEdge || int64(len(buf)) > 900<<10
	if needShrink {
		out := imaging.Fit(img, maxEdge, maxEdge, imaging.Lanczos)
		f, cerr := os.Create(filepath.Join(absDir, name))
		if cerr != nil {
			return "", "", nil, cerr
		}
		defer f.Close()
		if err := imaging.Encode(f, out, imaging.JPEG, imaging.JPEGQuality(originalQa)); err != nil {
			return "", "", nil, err
		}
	} else if err := os.WriteFile(filepath.Join(absDir, name), buf, 0o644); err != nil {
		return "", "", nil, err
	}

	// 缩略图（统一 jpg，列表页只加载这张）
	thumbName := id + "_t.jpg"
	th := imaging.Fit(img, thumbEdge, thumbEdge, imaging.Lanczos)
	tf, err := os.Create(filepath.Join(absDir, thumbName))
	if err != nil {
		return "", "", nil, err
	}
	defer tf.Close()
	if err := imaging.Encode(tf, th, imaging.JPEG, imaging.JPEGQuality(thumbQual)); err != nil {
		return "", "", nil, err
	}

	toSlash := func(p string) string { return strings.ReplaceAll(p, string(os.PathSeparator), "/") }
	// EXIF 拍摄时间（读不到不影响保存）
	if t, eerr := exifTakenAt(buf); eerr == nil {
		exifAt = &t
	}
	return toSlash(filepath.Join(dir, name)), toSlash(filepath.Join(dir, thumbName)), exifAt, nil
}

// decodeDimensions 只读头部拿尺寸（用于返回给前端，无额外开销）。
func decodeDimensions(b []byte) (int, int) {
	cfg, _, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil {
		return 0, 0
	}
	return cfg.Width, cfg.Height
}

func randHex() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}
