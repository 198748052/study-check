package main

import (
	"net/http"
	"strconv"
	"time"
)

// ---------------------------------------------------------------
// 管理端统计图表数据
//
// 给「系统状态」页加几张图：近两周活跃趋势、时段分布、新增用户、
// 人均表现、时长 Top 用户。全部是聚合查询，只读不写。
// ---------------------------------------------------------------

type analyticsPoint struct {
	Day        string `json:"day"`
	Label      string `json:"label"`
	Users      int    `json:"users"`       // 当天有打卡的用户数
	Sessions   int    `json:"sessions"`    // 当天分段数
	Minutes    int64  `json:"minutes"`     // 当天总时长（分钟）
	NewUsers   int    `json:"new_users"`   // 当天新注册用户数
	ActiveRate int    `json:"active_rate"` // 活跃用户 / 总用户（百分数）
}

type analyticsHour struct {
	Hour     int   `json:"hour"`
	Sessions int   `json:"sessions"`
	Minutes  int64 `json:"minutes"`
}

type analyticsTopUser struct {
	UserID    int64  `json:"user_id"`
	Name      string `json:"name"`
	Username  string `json:"username"`
	Days      int    `json:"days"`
	Minutes   int64  `json:"minutes"`
	Sessions  int    `json:"sessions"`
	AvgPerDay int64  `json:"avg_minutes_per_day"`
}

type analyticsResp struct {
	From     string             `json:"from"`
	To       string             `json:"to"`
	Days     []analyticsPoint   `json:"days"`
	Hours    []analyticsHour    `json:"hours"`
	TopUsers []analyticsTopUser `json:"top_users"`
	Totals   map[string]any     `json:"totals"`
}

// GET /api/v1/system/analytics?days=14
func (s *Server) handleAnalytics(w http.ResponseWriter, r *http.Request) {
	loc := s.clientLoc(r)
	days := qIntDefault(r.URL.Query().Get("days"), 14)
	if days < 1 {
		days = 1
	}
	if days > 90 {
		days = 90
	}

	now := time.Now().In(loc)
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, loc)
	from := today.AddDate(0, 0, -(days - 1))

	totalUsers := int(s.countRows(`SELECT COUNT(1) FROM users`))

	out := analyticsResp{
		From:   from.Format("2006-01-02"),
		To:     today.Format("2006-01-02"),
		Totals: map[string]any{},
	}

	// 近 N 天逐日：活跃用户 / 分段数 / 时长 / 新增用户
	points := make([]analyticsPoint, 0, days)
	var sumSessions int64
	var sumMinutes int64
	for i := 0; i < days; i++ {
		d := from.AddDate(0, 0, i)
		ds := d.Format("2006-01-02")
		var users, segs int
		var secs int64
		_ = s.db.QueryRow(`SELECT COUNT(DISTINCT user_id), COUNT(1), COALESCE(SUM(duration_sec),0)
			FROM sessions WHERE day = ? AND in_at IS NOT NULL`, ds).Scan(&users, &segs, &secs)
		var newUsers int
		_ = s.db.QueryRow(`SELECT COUNT(1) FROM users WHERE substr(created_at,1,10) = ?`, ds).Scan(&newUsers)

		rate := 0
		if totalUsers > 0 {
			rate = users * 100 / totalUsers
		}
		points = append(points, analyticsPoint{
			Day: ds, Label: ds[5:], Users: users, Sessions: segs,
			Minutes: secs / 60, NewUsers: newUsers, ActiveRate: rate,
		})
		sumSessions += int64(segs)
		sumMinutes += secs / 60
	}
	out.Days = points

	// 时段分布：按「本地小时」聚合分段开始时间
	hours := make([]analyticsHour, 24)
	for i := range hours {
		hours[i].Hour = i
	}
	rows, err := s.db.Query(`SELECT COALESCE(in_at,''), COALESCE(duration_sec,0) FROM sessions WHERE in_at IS NOT NULL`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var inAt string
			var secs int64
			if err := rows.Scan(&inAt, &secs); err != nil {
				continue
			}
			t, perr := time.Parse(time.RFC3339, inAt)
			if perr != nil {
				continue
			}
			h := t.In(loc).Hour()
			if h < 0 || h > 23 {
				continue
			}
			hours[h].Sessions++
			hours[h].Minutes += secs / 60
		}
	}
	out.Hours = hours

	// 本月时长 Top 用户
	month := now.Format("2006-01")
	trows, terr := s.db.Query(`SELECT s.user_id,
			COALESCE(u.nickname,''), u.username,
			COUNT(DISTINCT s.day), COALESCE(SUM(s.duration_sec),0), COUNT(1)
		FROM sessions s JOIN users u ON u.id = s.user_id
		WHERE s.day LIKE ? || '%' AND s.in_at IS NOT NULL
		GROUP BY s.user_id ORDER BY SUM(s.duration_sec) DESC LIMIT 5`, month)
	if terr == nil {
		defer trows.Close()
		for trows.Next() {
			var t analyticsTopUser
			if err := trows.Scan(&t.UserID, &t.Name, &t.Username, &t.Days, &t.Minutes, &t.Sessions); err != nil {
				continue
			}
			t.Minutes = t.Minutes / 60
			if t.Days > 0 {
				t.AvgPerDay = t.Minutes / int64(t.Days)
			}
			if t.Name == "" {
				t.Name = t.Username
			}
			out.TopUsers = append(out.TopUsers, t)
		}
	}
	if out.TopUsers == nil {
		out.TopUsers = []analyticsTopUser{}
	}

	// 汇总
	var activeDays int
	for _, p := range points {
		if p.Users > 0 {
			activeDays++
		}
	}
	avgActive := 0
	if days > 0 {
		avgActive = activeDays * 100 / days
	}
	out.Totals = map[string]any{
		"days":              days,
		"total_users":       totalUsers,
		"sessions_in_range": strconv.FormatInt(sumSessions, 10),
		"minutes_in_range":  strconv.FormatInt(sumMinutes, 10),
		"active_days":       activeDays,
		"active_day_rate":   avgActive,
	}

	writeJSON(w, http.StatusOK, out)
}
