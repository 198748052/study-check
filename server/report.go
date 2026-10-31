package main

import (
	"errors"
	"net/http"
	"sort"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 手动补卡：漏打卡时按日期 + 时间补一条记录（不需要照片）
// ---------------------------------------------------------------

type manualCheckinReq struct {
	Day      string `json:"day"`
	InAt     string `json:"in_at"`
	OutAt    string `json:"out_at"`
	InNote   string `json:"in_note"`
	OutNote  string `json:"out_note"`
	InPlace  string `json:"in_place"`
	OutPlace string `json:"out_place"`
}

// combineDayTime 把 "HH:MM"（或 RFC3339）与日期拼成带时区的时间点。
func combineDayTime(day, raw string, loc *time.Location) (time.Time, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return time.Time{}, errors.New("时间不能为空")
	}
	base, err := time.ParseInLocation("2006-01-02", day, loc)
	if err != nil {
		return time.Time{}, errors.New("日期格式应为 YYYY-MM-DD")
	}
	for _, layout := range []string{"15:04", "15:04:05"} {
		if t, err := time.ParseInLocation(layout, raw, loc); err == nil {
			return time.Date(base.Year(), base.Month(), base.Day(), t.Hour(), t.Minute(), t.Second(), 0, loc), nil
		}
	}
	if t, err := time.Parse(time.RFC3339, raw); err == nil {
		return t.In(loc), nil
	}
	return time.Time{}, errors.New("时间格式应为 HH:MM")
}

// POST /api/v1/checkins —— 手动补卡（无需照片）
func (s *Server) handleManualCheckin(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	if s.idemLookup(w, r, uid, "manual") {
		return
	}

	var req manualCheckinReq
	if err := decodeJSON(w, r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}

	loc := s.clientLoc(r)
	now := time.Now().In(loc)
	day := strings.TrimSpace(req.Day)
	if day == "" {
		writeErr(w, http.StatusBadRequest, "day_required", "请选择补卡日期")
		return
	}
	base, err := time.ParseInLocation("2006-01-02", day, loc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_day", "日期格式应为 YYYY-MM-DD")
		return
	}
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, loc)
	if base.After(today) {
		writeErr(w, http.StatusBadRequest, "future_day", "不能给未来日期补卡")
		return
	}
	if base.Before(today.AddDate(0, 0, -3650)) {
		writeErr(w, http.StatusBadRequest, "day_too_old", "只支持补最近 10 年的记录")
		return
	}

	inAt, err := combineDayTime(day, req.InAt, loc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_in_at", "进馆时间不正确："+err.Error())
		return
	}

	var outAt *time.Time
	if strings.TrimSpace(req.OutAt) != "" {
		t, oerr := combineDayTime(day, req.OutAt, loc)
		if oerr != nil {
			writeErr(w, http.StatusBadRequest, "bad_out_at", "出馆时间不正确："+oerr.Error())
			return
		}
		if t.Before(inAt) {
			writeErr(w, http.StatusBadRequest, "out_before_in", "出馆时间不能早于进馆时间")
			return
		}
		outAt = &t
	}

	// 同一天允许多段补卡；这里只挡「同一分钟重复提交」
	var dup int
	_ = s.db.QueryRow(`SELECT COUNT(1) FROM sessions WHERE user_id = ? AND day = ? AND in_at = ?`,
		uid, day, inAt.Format(time.RFC3339)).Scan(&dup)
	if dup > 0 {
		writeErr(w, http.StatusConflict, "duplicate_session", "这一段时间已经存在了，可直接修改")
		return
	}

	var dur int64
	var outStr any
	if outAt != nil {
		dur = int64(outAt.Sub(inAt).Seconds())
		if dur > 86400 {
			dur = 86400
		}
		outStr = outAt.Format(time.RFC3339)
	}
	noteIn := truncateStr(req.InNote, 200)
	noteOut := truncateStr(req.OutNote, 200)
	placeIn := truncateStr(req.InPlace, 40)
	placeOut := truncateStr(req.OutPlace, 40)

	stamp := now.Format(time.RFC3339)
	if _, err = s.db.Exec(`INSERT INTO sessions(user_id, day, in_at, out_at,
			in_note, out_note, in_place, out_place, duration_sec, created_at, updated_at)
		VALUES(?,?,?,?,?,?,?,?,?,?,?)`,
		uid, day, inAt.Format(time.RFC3339), outStr,
		noteIn, noteOut, placeIn, placeOut, dur, stamp, stamp); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "补卡失败")
		return
	}
	if err := s.recomputeDay(uid, day); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "汇总失败")
		return
	}

	c, err := s.checkinByDay(uid, day)
	if err != nil || c == nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取记录失败")
		return
	}
	s.idemStore(r, uid, "manual", http.StatusCreated, c)
	writeJSON(w, http.StatusCreated, c)
}

func truncateStr(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// ---------------------------------------------------------------
// 周报 / 月报
// ---------------------------------------------------------------

type reportRow struct {
	day      string
	dur      int64
	hasIn    bool
	hasOut   bool
	inPlace  string
	outPlace string
}

func (s *Server) checkinsBetween(uid int64, start, end string) ([]reportRow, error) {
	rows, err := s.db.Query(`SELECT day, COALESCE(in_at,''), COALESCE(out_at,''), duration_sec,
		COALESCE(in_place,''), COALESCE(out_place,'')
		FROM checkins WHERE user_id = ? AND day >= ? AND day <= ? ORDER BY day ASC`,
		uid, start, end)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]reportRow, 0, 40)
	for rows.Next() {
		var rr reportRow
		var inAt, outAt, inPlace, outPlace string
		if err := rows.Scan(&rr.day, &inAt, &outAt, &rr.dur, &inPlace, &outPlace); err != nil {
			return nil, err
		}
		rr.hasIn = inAt != ""
		rr.hasOut = outAt != ""
		rr.inPlace = inPlace
		rr.outPlace = outPlace
		out = append(out, rr)
	}
	return out, rows.Err()
}

func periodRange(anchor time.Time, period string, loc *time.Location) (start, end time.Time) {
	y, m, d := anchor.Date()
	if period == "month" {
		start = time.Date(y, m, 1, 0, 0, 0, 0, loc)
		end = start.AddDate(0, 1, -1)
		return start, end
	}
	// 周一为一周起点
	offset := (int(anchor.In(loc).Weekday()) + 6) % 7
	start = time.Date(y, m, d, 0, 0, 0, 0, loc).AddDate(0, 0, -offset)
	end = start.AddDate(0, 0, 6)
	return start, end
}

type weekdayStat struct {
	Weekday int    `json:"weekday"`
	Label   string `json:"label"`
	Days    int    `json:"days"`
	Minutes int64  `json:"minutes"`
}

type placeStat struct {
	Name  string `json:"name"`
	Days  int    `json:"days"`
	Times int    `json:"times"`
}

type reportBestDay struct {
	Day     string `json:"day"`
	Minutes int64  `json:"minutes"`
}

// GET /api/v1/report?period=week|month&anchor=YYYY-MM-DD
func (s *Server) handleReport(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)

	period := strings.TrimSpace(r.URL.Query().Get("period"))
	if period != "month" {
		period = "week"
	}
	anchor := time.Now().In(loc)
	if a := strings.TrimSpace(r.URL.Query().Get("anchor")); a != "" {
		if t, err := time.ParseInLocation("2006-01-02", a, loc); err == nil {
			anchor = t
		}
	}

	start, end := periodRange(anchor, period, loc)
	cur, err := s.checkinsBetween(uid, start.Format("2006-01-02"), end.Format("2006-01-02"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}

	var prevStart, prevEnd time.Time
	if period == "month" {
		prevStart = start.AddDate(0, -1, 0)
		prevEnd = start.AddDate(0, 0, -1)
	} else {
		prevStart = start.AddDate(0, 0, -7)
		prevEnd = end.AddDate(0, 0, -7)
	}
	prev, err := s.checkinsBetween(uid, prevStart.Format("2006-01-02"), prevEnd.Format("2006-01-02"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}

	minutesByDay := make(map[string]int64, len(cur))
	var days, completed int
	var totalSec int64
	weekdayMin := [7]int64{}
	weekdayDays := [7]int{}
	placeDays := map[string]map[string]bool{}
	placeTimes := map[string]int{}

	for _, rr := range cur {
		if !rr.hasIn {
			continue
		}
		days++
		if rr.hasOut {
			completed++
		}
		minutesByDay[rr.day] += rr.dur / 60
		totalSec += rr.dur

		if t, perr := time.ParseInLocation("2006-01-02", rr.day, loc); perr == nil {
			idx := (int(t.Weekday()) + 6) % 7
			weekdayMin[idx] += rr.dur / 60
			weekdayDays[idx]++
		}

		place := strings.TrimSpace(rr.inPlace)
		if place == "" {
			place = strings.TrimSpace(rr.outPlace)
		}
		if place != "" {
			if placeDays[place] == nil {
				placeDays[place] = map[string]bool{}
			}
			placeDays[place][rr.day] = true
			placeTimes[place]++
		}
	}

	minutes := totalSec / 60
	avg := int64(0)
	if days > 0 {
		avg = minutes / int64(days)
	}

	var goal int
	_ = s.db.QueryRow(`SELECT daily_goal_min FROM users WHERE id = ?`, uid).Scan(&goal)
	goalDays := 0
	if goal > 0 {
		for _, m := range minutesByDay {
			if m >= int64(goal) {
				goalDays++
			}
		}
	}
	goalRate := 0
	if days > 0 {
		goalRate = goalDays * 100 / days
	}

	var best reportBestDay
	for d, m := range minutesByDay {
		if m > best.Minutes {
			best = reportBestDay{Day: d, Minutes: m}
		}
	}

	labels := [7]string{"一", "二", "三", "四", "五", "六", "日"}
	byWeekday := make([]weekdayStat, 0, 7)
	for i := 0; i < 7; i++ {
		byWeekday = append(byWeekday, weekdayStat{
			Weekday: i + 1, Label: labels[i], Days: weekdayDays[i], Minutes: weekdayMin[i],
		})
	}

	places := make([]placeStat, 0, len(placeDays))
	for name, set := range placeDays {
		places = append(places, placeStat{Name: name, Days: len(set), Times: placeTimes[name]})
	}
	sort.Slice(places, func(i, j int) bool {
		if places[i].Days != places[j].Days {
			return places[i].Days > places[j].Days
		}
		return places[i].Times > places[j].Times
	})
	if len(places) > 5 {
		places = places[:5]
	}

	var prevDays int
	var prevSec int64
	for _, rr := range prev {
		if rr.hasIn {
			prevDays++
			prevSec += rr.dur
		}
	}

	label := ""
	if period == "month" {
		label = start.Format("2006 年 1 月")
	} else {
		label = start.Format("01-02") + " ~ " + end.Format("01-02")
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"period":         period,
		"start":          start.Format("2006-01-02"),
		"end":            end.Format("2006-01-02"),
		"label":          label,
		"days":           days,
		"completed_days": completed,
		"minutes":        minutes,
		"avg_minutes":    avg,
		"goal_minutes":   goal,
		"goal_days":      goalDays,
		"goal_rate":      goalRate,
		"best_day":       best,
		"by_weekday":     byWeekday,
		"places":         places,
		"prev_days":      prevDays,
		"prev_minutes":   prevSec / 60,
		"delta_minutes":  minutes - prevSec/60,
	})
}

// ---------------------------------------------------------------
// 连续打卡成就
// ---------------------------------------------------------------

type achievementDef struct {
	id       string
	category string
	title    string
	desc     string
	target   int64
}

type achievementItem struct {
	ID         string `json:"id"`
	Category   string `json:"category"`
	Title      string `json:"title"`
	Desc       string `json:"desc"`
	Target     int64  `json:"target"`
	Progress   int64  `json:"progress"`
	Unlocked   bool   `json:"unlocked"`
	AchievedOn string `json:"achieved_on,omitempty"`
}

var streakAchievements = []achievementDef{
	{"streak_3", "streak", "初入佳境", "连续打卡 3 天", 3},
	{"streak_7", "streak", "一周不辍", "连续打卡 7 天", 7},
	{"streak_14", "streak", "半月坚持", "连续打卡 14 天", 14},
	{"streak_30", "streak", "月度自律", "连续打卡 30 天", 30},
	{"streak_60", "streak", "两月恒心", "连续打卡 60 天", 60},
	{"streak_100", "streak", "百日筑基", "连续打卡 100 天", 100},
	{"streak_180", "streak", "半年如一", "连续打卡 180 天", 180},
	{"streak_365", "streak", "全年不辍", "连续打卡 365 天", 365},
}

var dayAchievements = []achievementDef{
	{"days_7", "days", "七日之约", "累计打卡 7 天", 7},
	{"days_30", "days", "三十而立", "累计打卡 30 天", 30},
	{"days_100", "days", "百日记", "累计打卡 100 天", 100},
	{"days_365", "days", "岁岁勤学", "累计打卡 365 天", 365},
	{"days_1000", "days", "千日之功", "累计打卡 1000 天", 1000},
}

var minuteAchievements = []achievementDef{
	{"minutes_600", "minutes", "十小时", "累计自习 10 小时", 600},
	{"minutes_3000", "minutes", "五十小时", "累计自习 50 小时", 3000},
	{"minutes_10000", "minutes", "百六十六小时", "累计自习 10000 分钟", 10000},
	{"minutes_30000", "minutes", "五百小时", "累计自习 500 小时", 30000},
}

// GET /api/v1/achievements
func (s *Server) handleAchievements(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)

	rows, err := s.db.Query(`SELECT day, COALESCE(duration_sec,0) FROM checkins
		WHERE user_id = ? AND in_at IS NOT NULL ORDER BY day ASC`, uid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	defer rows.Close()

	var days []string
	durByDay := map[string]int64{}
	for rows.Next() {
		var d string
		var dur int64
		if err := rows.Scan(&d, &dur); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
			return
		}
		if _, ok := durByDay[d]; !ok {
			days = append(days, d)
		}
		durByDay[d] += dur
	}
	if err := rows.Err(); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
		return
	}

	hasIn := make(map[string]bool, len(days))
	for _, d := range days {
		hasIn[d] = true
	}

	now := time.Now().In(loc)
	today := now.Format("2006-01-02")
	cursor := now
	if !hasIn[today] {
		cursor = now.AddDate(0, 0, -1)
	}
	currentStreak := 0
	for {
		if !hasIn[cursor.Format("2006-01-02")] {
			break
		}
		currentStreak++
		cursor = cursor.AddDate(0, 0, -1)
	}

	longest := 0
	run := 0
	var prev string
	streakDay := map[int64]string{}
	for _, d := range days {
		if prev != "" && nextDay(prev) == d {
			run++
		} else {
			run = 1
		}
		if run > longest {
			longest = run
		}
		for _, a := range streakAchievements {
			if int64(run) >= a.target && streakDay[a.target] == "" {
				streakDay[a.target] = d
			}
		}
		prev = d
	}
	if currentStreak > longest {
		longest = currentStreak
	}

	totalMinutes := int64(0)
	for _, dur := range durByDay {
		totalMinutes += dur / 60
	}
	totalDays := int64(len(days))

	dayUnlock := map[int64]string{}
	minUnlock := map[int64]string{}
	var cumDays, cumMin int64
	for _, d := range days {
		cumDays++
		cumMin += durByDay[d] / 60
		for _, a := range dayAchievements {
			if cumDays >= a.target && dayUnlock[a.target] == "" {
				dayUnlock[a.target] = d
			}
		}
		for _, a := range minuteAchievements {
			if cumMin >= a.target && minUnlock[a.target] == "" {
				minUnlock[a.target] = d
			}
		}
	}

	items := make([]achievementItem, 0, len(streakAchievements)+len(dayAchievements)+len(minuteAchievements))
	appendItems := func(defs []achievementDef, value int64, achieved map[int64]string) {
		for _, a := range defs {
			prog := value
			if prog > a.target {
				prog = a.target
			}
			items = append(items, achievementItem{
				ID: a.id, Category: a.category, Title: a.title, Desc: a.desc,
				Target: a.target, Progress: prog, Unlocked: value >= a.target,
				AchievedOn: achieved[a.target],
			})
		}
	}
	appendItems(streakAchievements, int64(longest), streakDay)
	appendItems(dayAchievements, totalDays, dayUnlock)
	appendItems(minuteAchievements, totalMinutes, minUnlock)

	var next *achievementItem
	for i := range items {
		if !items[i].Unlocked {
			if next == nil || items[i].Target-items[i].Progress < next.Target-next.Progress {
				next = &items[i]
			}
		}
	}

	resp := map[string]any{
		"current_streak": currentStreak,
		"longest_streak": longest,
		"total_days":     totalDays,
		"total_minutes":  totalMinutes,
		"achievements":   items,
	}
	if next != nil {
		resp["next"] = next
	}
	writeJSON(w, http.StatusOK, resp)
}

func nextDay(day string) string {
	t, err := time.Parse("2006-01-02", day)
	if err != nil {
		return ""
	}
	return t.AddDate(0, 0, 1).Format("2006-01-02")
}
