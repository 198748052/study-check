package main

import (
	"image"
	"image/color"
	"log"
	"math"
	"math/rand"
	"os"
	"path/filepath"
	"time"

	"github.com/disintegration/imaging"
)

// 演示数据：生成一个 demo 账号 + 若干天带照片的打卡记录，
// 让预览时统计页/热力图/历史列表都有内容。正式环境用 SC_SEED_DEMO=false 关闭。

func (s *Server) seedDemo() {
	var count int
	if err := s.db.QueryRow(`SELECT COUNT(1) FROM users`).Scan(&count); err != nil {
		log.Printf("seed: 查询用户失败: %v", err)
		return
	}
	if count > 0 {
		return
	}
	hash, err := hashPassword("demo1234")
	if err != nil {
		log.Printf("seed: 生成密码失败: %v", err)
		return
	}
	now := time.Now().In(s.cfg.Loc)
	res, err := s.db.Exec(`INSERT INTO users(username, nickname, password_hash, daily_goal_min, is_admin, created_at)
		VALUES(?,?,?,?,1,?)`, "demo", "夜读的猫", hash, 180, now.Format(time.RFC3339))
	if err != nil {
		log.Printf("seed: 创建演示账号失败: %v", err)
		return
	}
	uid, _ := res.LastInsertId()

	rng := rand.New(rand.NewSource(20251026))
	days := 0
	for i := 74; i >= 0; i-- {
		day := now.AddDate(0, 0, -i)
		// 约 25% 的日子休息，制造真实的连续/断档
		if i != 0 && rng.Float64() < 0.25 {
			continue
		}
		inHour := 8 + rng.Intn(3)
		inMin := rng.Intn(60)
		inAt := time.Date(day.Year(), day.Month(), day.Day(), inHour, inMin, 0, 0, s.cfg.Loc)
		stay := time.Duration(110+rng.Intn(190)) * time.Minute

		var outAt *time.Time
		if i > 0 {
			t := inAt.Add(stay)
			outAt = &t
		}

		inPhoto, err := s.generateDemoPhoto(uid, day, inAt, rng)
		if err != nil {
			log.Printf("seed: 生成照片失败: %v", err)
			continue
		}
		var outPhoto *string
		var dur int64
		if outAt != nil {
			p, err := s.generateDemoPhoto(uid, day, *outAt, rng)
			if err == nil {
				outPhoto = &p
				dur = int64(outAt.Sub(inAt).Seconds())
			}
		}

		_, err = s.db.Exec(`INSERT OR IGNORE INTO checkins
			(user_id, day, in_at, out_at, in_photo, out_photo, in_note, out_note, in_place, out_place, duration_sec, created_at, updated_at)
			VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`,
			uid, day.Format("2006-01-02"),
			inAt.Format(time.RFC3339), nullableTime(outAt), inPhoto, outPhoto,
			"今天从第三章开始", "完成两节 + 一组错题", "图书馆 3 楼", "图书馆 3 楼",
			dur, inAt.Format(time.RFC3339), time.Now().Format(time.RFC3339))
		if err != nil {
			log.Printf("seed: 插入打卡失败: %v", err)
			continue
		}
		days++
	}
	log.Printf("seed: 演示账号 demo/demo1234 已创建，%d 天打卡记录", days)
}

func nullableTime(t *time.Time) any {
	if t == nil {
		return nil
	}
	return t.Format(time.RFC3339)
}

// generateDemoPhoto 生成一张"书架氛围"的渐变占位图，避免演示时出现破图。
func (s *Server) generateDemoPhoto(uid int64, day, ts time.Time, rng *rand.Rand) (string, error) {
	const w, h = 1200, 900
	img := image.NewRGBA(image.Rect(0, 0, w, h))

	// 每个时段一个色系：早上偏暖、晚些偏冷
	base := [][3]float64{
		{246, 214, 160}, {232, 196, 158}, {198, 190, 176}, {150, 160, 180}, {120, 130, 165},
	}
	pick := base[rng.Intn(len(base))]
	hue := rng.Float64() * 0.25

	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			fy := float64(y) / h
			fx := float64(x) / w
			// 斜向渐变 + 轻微木纹条纹
			stripe := 0.06 * math.Sin(fx*26+hue*10)
			v := 1.15 - fy*0.65 + stripe
			r := clampV(pick[0] + 255*(v-1))
			g := clampV(pick[1] + 255*(v-1))
			b := clampV(pick[2] + 255*(v-1))
			// 桌面上的暖色光斑
			dx, dy := fx-0.68, fy-0.72
			glow := math.Exp(-(dx*dx+dy*dy)*32) * 0.55
			r = clampV(r + 255*glow)
			g = clampV(g + 235*glow)
			b = clampV(b + 190*glow)
			img.Set(x, y, color.RGBA{uint8(r), uint8(g), uint8(b), 255})
		}
	}

	dir := filepath.Join(ts.Format("2006"), ts.Format("01"), ts.Format("02"))
	absDir := filepath.Join(s.cfg.UploadDir, dir)
	if err := os.MkdirAll(absDir, 0o755); err != nil {
		return "", err
	}
	name := randHex()
	orig := filepath.Join(absDir, name+".jpg")
	f, err := os.Create(orig)
	if err != nil {
		return "", err
	}
	if err := imaging.Encode(f, img, imaging.JPEG, imaging.JPEGQuality(82)); err != nil {
		f.Close()
		return "", err
	}
	f.Close()

	th := imaging.Fit(img, thumbEdge, thumbEdge, imaging.Lanczos)
	tf, err := os.Create(filepath.Join(absDir, name+"_t.jpg"))
	if err != nil {
		return "", err
	}
	defer tf.Close()
	if err := imaging.Encode(tf, th, imaging.JPEG, imaging.JPEGQuality(78)); err != nil {
		return "", err
	}
	_ = uid
	return filepath.ToSlash(filepath.Join(dir, name+".jpg")), nil
}

func clampV(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 255 {
		return 255
	}
	return v
}
