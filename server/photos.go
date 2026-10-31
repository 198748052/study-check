package main

import (
	"net/http"
	"path/filepath"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 照片时间轴：按天分组把所有照片摊平成一条时间线
// （打卡照片 + 每日详情的过程留存照片），方便「翻照片」而不是翻记录。
// ---------------------------------------------------------------

// photoItem 的排序键：同一天内按时间摊平。
// 过程留存照片有时是「今天补传某一天的」，它的 at 是真实上传时间，
// 会跑到当天所有打卡照片后面；时间轴里把它按当天中午算，顺序更自然。
type photoItem struct {
	key      string `json:"-"`
	ID       int64  `json:"id"`
	Kind     string `json:"kind"` // in / out / entry
	PhotoURL string `json:"photo_url"`
	ThumbURL string `json:"thumb_url,omitempty"`
	At       string `json:"at"`
	Day      string `json:"day"`
	Subject  string `json:"subject,omitempty"`
	Note     string `json:"note,omitempty"`
	InPlace  string `json:"in_place,omitempty"`
	OutPlace string `json:"out_place,omitempty"`
}

type photoDay struct {
	Day   string      `json:"day"`
	Items []photoItem `json:"items"`
	Count int         `json:"count"`
}

// GET /api/v1/photos?month=YYYY-MM（默认当前月）&from=&to= 也可以给日期区间
func (s *Server) handlePhotos(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)
	q := r.URL.Query()

	from := strings.TrimSpace(q.Get("from"))
	to := strings.TrimSpace(q.Get("to"))
	month := strings.TrimSpace(q.Get("month"))

	if from == "" && to == "" && month == "" {
		month = time.Now().In(loc).Format("2006-01")
	}
	if month != "" {
		b, err := time.ParseInLocation("2006-01", month, loc)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "bad_month", "month 格式应为 YYYY-MM")
			return
		}
		from = b.Format("2006-01-02")
		to = b.AddDate(0, 1, -1).Format("2006-01-02")
	}
	if from != "" {
		if _, err := time.ParseInLocation("2006-01-02", from, loc); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_from", "from 格式应为 YYYY-MM-DD")
			return
		}
	}
	if to != "" {
		if _, err := time.ParseInLocation("2006-01-02", to, loc); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_to", "to 格式应为 YYYY-MM-DD")
			return
		}
	}
	if from == "" {
		from = "0000-01-01"
	}
	if to == "" {
		to = "9999-12-31"
	}

	// 一天里照片的顺序：进馆 → 过程留存 → 出馆，把同一时刻的照片放一起
	rows, err := s.db.Query(`SELECT id, day, in_photo, out_photo, COALESCE(in_at,''), COALESCE(out_at,''),
			COALESCE(subject,''), COALESCE(in_place,''), COALESCE(out_place,'')
		FROM sessions WHERE user_id = ? AND day BETWEEN ? AND ? AND in_at IS NOT NULL
		ORDER BY day ASC, in_at ASC, id ASC`, uid, from, to)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	days := map[string]*photoDay{}
	order := []string{}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var day, inPhoto, outPhoto, inAt, outAt, subject, inPlace, outPlace string
		if err := rows.Scan(&id, &day, &inPhoto, &outPhoto, &inAt, &outAt,
			&subject, &inPlace, &outPlace); err != nil {
			continue
		}
		d := dayBucket(days, &order, day)
		if inPhoto != "" {
			d.Items = append(d.Items, photoItem{
				key: sortKey(day, inAt, loc), ID: id, Kind: "in", PhotoURL: "/media/" + inPhoto, ThumbURL: photoThumb(inPhoto),
				At: inAt, Day: day, Subject: subject, InPlace: inPlace,
			})
		}
		if outPhoto != "" {
			d.Items = append(d.Items, photoItem{
				key: sortKey(day, outAt, loc), ID: id, Kind: "out", PhotoURL: "/media/" + outPhoto, ThumbURL: photoThumb(outPhoto),
				At: outAt, Day: day, Subject: subject, OutPlace: outPlace,
			})
		}
	}

	// 过程留存照片
	erows, err := s.db.Query(`SELECT id, day, COALESCE(photo,''), COALESCE(created_at,''), COALESCE(note,'')
		FROM entries WHERE user_id = ? AND day BETWEEN ? AND ? AND photo <> ''
		ORDER BY day ASC, created_at ASC, id ASC`, uid, from, to)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer erows.Close()
	for erows.Next() {
		var id int64
		var day, photo, created, note string
		if err := erows.Scan(&id, &day, &photo, &created, &note); err != nil {
			continue
		}
		d := dayBucket(days, &order, day)
		key := sortKey(day, created, loc)
		d.Items = append(d.Items, photoItem{
			key: key, ID: id, Kind: "entry", PhotoURL: "/media/" + photo, ThumbURL: photoThumb(photo),
			At: created, Day: day, Note: note,
		})
	}

	// 同一天内按时间排序，让「进馆 → 随手拍 → 出馆」的顺序自然成立
	out := make([]photoDay, 0, len(order))
	total := 0
	for _, day := range order {
		d := days[day]
		sortPhotosByAt(d.Items)
		d.Count = len(d.Items)
		total += d.Count
		out = append(out, *d)
	}

	// 前后月，方便翻页
	var prev, next string
	if month != "" {
		b, _ := time.ParseInLocation("2006-01", month, loc)
		prev = b.AddDate(0, -1, 0).Format("2006-01")
		next = b.AddDate(0, 1, 0).Format("2006-01")
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"month":      month,
		"from":       from,
		"to":         to,
		"count":      total,
		"days":       out,
		"prev_month": prev,
		"next_month": next,
	})
}

func dayBucket(days map[string]*photoDay, order *[]string, day string) *photoDay {
	if d, ok := days[day]; ok {
		return d
	}
	d := &photoDay{Day: day, Items: []photoItem{}}
	days[day] = d
	*order = append(*order, day)
	return d
}

// sortKey 把一条照片的时间归一到「当天 + 时:分:秒」，同一天内按它排序。
// 补传的老照片（created_at 是上传那一刻）也会落到当天对应时刻，顺序稳定。
func sortKey(day, at string, loc *time.Location) string {
	t, err := time.Parse(time.RFC3339, strings.TrimSpace(at))
	if err != nil {
		return day + "T12:00:00"
	}
	return day + "T" + t.In(loc).Format("15:04:05")
}

// photoThumb 缩略图路径：上传时服务端会同步生成 <name>_t.jpg
func photoThumb(path string) string {
	ext := filepath.Ext(path)
	return "/media/" + strings.TrimSuffix(path, ext) + "_t.jpg"
}

func sortPhotosByAt(items []photoItem) {
	for i := 1; i < len(items); i++ {
		for j := i; j > 0 && items[j].key < items[j-1].key; j-- {
			items[j], items[j-1] = items[j-1], items[j]
		}
	}
}
