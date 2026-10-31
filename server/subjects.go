package main

import (
	"net/http"
	"sort"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 科目 / 标签统计
//
// 每一段自习可以带一个 subject（科目、章节、项目都行），这里按科目
// 汇总时长，回答「这周数学多少小时、英语多少小时」。
// ---------------------------------------------------------------

type subjectStat struct {
	Subject  string `json:"subject"`
	Minute   int64  `json:"minutes"`
	Seconds  int64  `json:"seconds"`
	Days     int    `json:"days"`
	Sessions int    `json:"sessions"`
}

// subjectStats 统计 [from, to] 之间（按分段所属日期）各科目的时长。
// 没填科目的段汇总成 subject = ""，由客户端显示为「未分类」。
func (s *Server) subjectStats(uid int64, from, to string) ([]subjectStat, int64, int64, error) {
	rows, err := s.db.Query(`SELECT COALESCE(subject,''), COUNT(DISTINCT day), COUNT(1), COALESCE(SUM(duration_sec),0)
		FROM sessions
		WHERE user_id = ? AND in_at IS NOT NULL AND day >= ? AND day <= ?
		GROUP BY COALESCE(subject,'')`, uid, from, to)
	if err != nil {
		return nil, 0, 0, err
	}
	defer rows.Close()

	items := make([]subjectStat, 0, 8)
	var total, untagged int64
	for rows.Next() {
		var it subjectStat
		var sec int64
		if err := rows.Scan(&it.Subject, &it.Days, &it.Sessions, &sec); err != nil {
			return nil, 0, 0, err
		}
		it.Subject = strings.TrimSpace(it.Subject)
		it.Seconds = sec
		it.Minute = sec / 60
		total += sec
		if it.Subject == "" {
			untagged += sec
		}
		items = append(items, it)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, 0, err
	}
	// 时长多的排前面，没填科目的永远排最后
	sort.SliceStable(items, func(i, j int) bool {
		if (items[i].Subject == "") != (items[j].Subject == "") {
			return items[j].Subject == ""
		}
		if items[i].Seconds != items[j].Seconds {
			return items[i].Seconds > items[j].Seconds
		}
		return items[i].Subject < items[j].Subject
	})
	return items, total, untagged, nil
}

// GET /api/v1/stats/subjects?from=YYYY-MM-DD&to=YYYY-MM-DD&days=30
// 只给 days 不给 from/to 时，统计「最近 days 天（含今天）」。
func (s *Server) handleSubjectStats(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	today := now.Format("2006-01-02")

	q := r.URL.Query()
	to := strings.TrimSpace(q.Get("to"))
	from := strings.TrimSpace(q.Get("from"))
	if to == "" {
		to = today
	}
	if from == "" {
		days := qIntDefault(q.Get("days"), 30)
		if days < 1 {
			days = 1
		}
		if days > 3650 {
			days = 3650
		}
		base, err := time.ParseInLocation("2006-01-02", to, loc)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "bad_day", "to 格式应为 YYYY-MM-DD")
			return
		}
		from = base.AddDate(0, 0, -(days - 1)).Format("2006-01-02")
	}
	if _, err := time.ParseInLocation("2006-01-02", from, loc); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", "from 格式应为 YYYY-MM-DD")
		return
	}
	if _, err := time.ParseInLocation("2006-01-02", to, loc); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", "to 格式应为 YYYY-MM-DD")
		return
	}
	if from > to {
		writeErr(w, http.StatusBadRequest, "bad_range", "开始日期不能晚于结束日期")
		return
	}

	items, total, untagged, err := s.subjectStats(uid, from, to)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"from":         from,
		"to":           to,
		"items":        items,
		"count":        len(items),
		"total_sec":    total,
		"untagged_sec": untagged,
	})
}

func qIntDefault(raw string, def int) int {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return def
	}
	n := 0
	for _, c := range raw {
		if c < '0' || c > '9' {
			return def
		}
		n = n*10 + int(c-'0')
		if n > 100000 {
			return def
		}
	}
	return n
}
