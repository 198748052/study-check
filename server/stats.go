package main

import (
	"net/http"
	"strconv"
	"time"
)

type dayStat struct {
	Day      string `json:"day"`
	Minutes  int64  `json:"minutes"`
	HasPhoto bool   `json:"has_photo"`
}

// GET /api/v1/stats —— 首页/统计页数据，一次请求全给，减少移动端往返。
func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	rows, err := s.db.Query(`SELECT day, in_at, out_at, in_photo, out_photo, duration_sec
		FROM checkins WHERE user_id = ? ORDER BY day ASC`, uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	type rec struct {
		day     string
		inAt    *string
		outAt   *string
		dur     int64
		hasPh   bool
	}
	var recs []rec
	for rows.Next() {
		var rr rec
		var inAt, outAt, inPhoto, outPhoto *string
		if err := rows.Scan(&rr.day, &inAt, &outAt, &inPhoto, &outPhoto, &rr.dur); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
			return
		}
		rr.inAt, rr.outAt = inAt, outAt
		rr.hasPh = (inPhoto != nil && *inPhoto != "") && (outPhoto != nil && *outPhoto != "")
		recs = append(recs, rr)
	}

	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	today := now.Format("2006-01-02")

	// 按天聚合（一天一条，天然唯一）
	minutesByDay := make(map[string]int64, len(recs))
	hasIn := make(map[string]bool, len(recs))
	var totalSec, completed int
	for _, rr := range recs {
		if rr.inAt != nil {
			hasIn[rr.day] = true
		}
		if rr.outAt != nil {
			completed++
		}
		minutesByDay[rr.day] += rr.dur / 60
		totalSec += int(rr.dur)
	}

	// 连续天数：今天有记录就从今天算；今天还没打卡则从昨天算（不断 streak）
	streak := 0
	cursor := now
	if !hasIn[today] {
		cursor = now.AddDate(0, 0, -1)
	}
	for {
		d := cursor.Format("2006-01-02")
		if hasIn[d] {
			streak++
			cursor = cursor.AddDate(0, 0, -1)
			continue
		}
		break
	}

	// 最长连续
	longest, run := 0, 0
	var prev time.Time
	for _, rr := range recs {
		if rr.inAt == nil {
			continue
		}
		d, err := time.ParseInLocation("2006-01-02", rr.day, loc)
		if err != nil {
			continue
		}
		if run > 0 && d.Sub(prev) == 24*time.Hour {
			run++
		} else {
			run = 1
		}
		if run > longest {
			longest = run
		}
		prev = d
	}
	if streak > longest {
		longest = streak
	}

	// 热力图：最近 26 周（182 天）
	heat := make([]dayStat, 0, 182)
	for i := 181; i >= 0; i-- {
		d := now.AddDate(0, 0, -i).Format("2006-01-02")
		m, ok := minutesByDay[d]
		if !ok && !hasIn[d] {
			continue
		}
		heat = append(heat, dayStat{Day: d, Minutes: m, HasPhoto: true})
	}

	// 本月 / 本周
	monthPrefix := now.Format("2006-01")
	monthDays, monthMin := 0, int64(0)
	weekStart := now.AddDate(0, 0, -int((now.Weekday()+6)%7)) // 周一为一周起点
	weekDays, weekMin := 0, int64(0)
	for d, m := range minutesByDay {
		if len(d) >= 7 && d[:7] == monthPrefix {
			monthDays++
			monthMin += m
		}
		if t, err := time.ParseInLocation("2006-01-02", d, loc); err == nil {
			if !t.Before(time.Date(weekStart.Year(), weekStart.Month(), weekStart.Day(), 0, 0, 0, 0, loc)) {
				weekDays++
				weekMin += m
			}
		}
	}

	goal := 0
	_ = s.db.QueryRow(`SELECT daily_goal_min FROM users WHERE id = ?`, uid).Scan(&goal)

	avg := int64(0)
	if completed > 0 {
		avg = int64(totalSec/60) / int64(completed)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"today":            today,
		"total_days":       len(hasIn),
		"completed_days":   completed,
		"current_streak":   streak,
		"longest_streak":   longest,
		"total_minutes":    int64(totalSec / 60),
		"avg_minutes":      avg,
		"month_days":       monthDays,
		"month_minutes":    monthMin,
		"week_days":        weekDays,
		"week_minutes":     weekMin,
		"daily_goal_min":   goal,
		"heatmap":          heat,
		"server_time":      now.Format(time.RFC3339),
	})
}

func qIntPath(r *http.Request, key string) int {
	v := r.PathValue(key)
	n, err := strconv.Atoi(v)
	if err != nil {
		return -1
	}
	return n
}
