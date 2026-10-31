package main

import (
	"encoding/csv"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 数据导入：把导出的 CSV（或等价的 JSON 数组）重新写回账号
//
// 用途：换机 / 恢复 / 从别的工具迁移。导入是「补一段」的语义：
//   - 日期 + 进馆时间完全一样的行视为重复，跳过（不会覆盖已有记录）
//   - 与已有分段时间重叠的行会被拒掉（否则当天时长会被算两遍）
//   - 一次最多 5000 行，逐行独立事务，失败不影响其它行
// ---------------------------------------------------------------

const importMaxRows = 5000
const importMaxBytes = 5 << 20

type importReqItem struct {
	Day     string `json:"day"`
	InAt    string `json:"in_at"`
	OutAt   string `json:"out_at"`
	Subject string `json:"subject"`
	InNote  string `json:"in_note"`
	OutNote string `json:"out_note"`
	InPlace string `json:"in_place"`
	OutPl   string `json:"out_place"`
}

type importReq struct {
	Items []importReqItem `json:"items"`
}

type importRowError struct {
	Line int    `json:"line"`
	Msg  string `json:"msg"`
	Day  string `json:"day,omitempty"`
}

type importResult struct {
	Imported int `json:"imported"`
	Skipped  int `json:"skipped"`
	Failed   int `json:"failed"`
	/** 今天还能导入多少行（服务端有每日配额） */
	Left   int              `json:"left"`
	Errors []importRowError `json:"errors"`
}

// POST /api/v1/import —— multipart 表单字段 file（CSV），或 JSON {items:[...]}
func (s *Server) handleImport(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)

	// 频率闸：同一用户 1 分钟内最多几次，防脚本狂刷
	if !s.checkImportRate(w, uid) {
		return
	}

	loc := s.clientLoc(r)
	day := time.Now().In(loc).Format("2006-01-02")
	left, qerr := s.importQuotaLeft(uid, day)
	if qerr != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询配额失败")
		return
	}
	if left <= 0 {
		writeErr(w, http.StatusTooManyRequests, "import_quota_exceeded",
			"今天导入的行数到顶了（每天最多 "+strconv.Itoa(importRowsPerDay)+" 行），明天再试")
		return
	}

	var items []importReqItem
	var isCSV bool
	ct := r.Header.Get("Content-Type")

	if strings.HasPrefix(ct, "application/json") {
		var req importReq
		if err := decodeJSON(w, r, &req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", err.Error())
			return
		}
		items = req.Items
	} else {
		parsed, err := s.readImportCSV(r)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "bad_csv", err.Error())
			return
		}
		items, isCSV = parsed, true
	}

	if len(items) == 0 {
		writeErr(w, http.StatusBadRequest, "empty", "没有可导入的记录")
		return
	}
	if len(items) > importMaxRows {
		writeErr(w, http.StatusBadRequest, "too_many",
			"一次最多导入 "+strconv.Itoa(importMaxRows)+" 行，当前 "+strconv.Itoa(len(items))+" 行")
		return
	}

	res := importResult{Errors: []importRowError{}}
	// 导入时用固定快照的「今天」，避免跨过午夜后同一次导入的规则变化
	today := time.Now().In(loc)

	for i, it := range items {
		line := i + 1
		if isCSV {
			line = i + 2 // CSV 第一行是表头
		}
		msg := s.importOne(uid, it, loc, today)
		switch {
		case msg == "":
			res.Imported++
		case msg == "skipped":
			res.Skipped++
		default:
			res.Failed++
			if len(res.Errors) < 50 {
				res.Errors = append(res.Errors, importRowError{Line: line, Day: it.Day, Msg: msg})
			}
		}
	}

	// 只扣真正写进去的行（跳过的重复行不占配额）
	if err := s.importQuotaAdd(uid, day, res.Imported); err != nil {
		log.Printf("导入配额累加失败 user=%d: %v", uid, err)
	}
	res.Left = left - res.Imported
	if res.Left < 0 {
		res.Left = 0
	}

	writeJSON(w, http.StatusOK, res)
}

// importOne 导入一行：返回 "" 表示成功，"skipped" 表示重复跳过，其它是失败原因。
func (s *Server) importOne(uid int64, it importReqItem, loc *time.Location, today time.Time) string {
	day := strings.TrimSpace(it.Day)
	if day == "" {
		return "缺少日期"
	}
	base, err := time.ParseInLocation("2006-01-02", day, loc)
	if err != nil {
		return "日期格式应为 YYYY-MM-DD"
	}
	todayStart := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, loc)
	if base.After(todayStart) {
		return "不能导入未来的日期"
	}
	if base.Before(todayStart.AddDate(0, 0, -3650)) {
		return "只支持最近 10 年的记录"
	}

	inAt, err := combineImportTime(day, it.InAt, loc)
	if err != nil {
		return "进馆时间：" + err.Error()
	}

	var outAt *time.Time
	if strings.TrimSpace(it.OutAt) != "" {
		t, oerr := combineImportTime(day, it.OutAt, loc)
		if oerr != nil {
			return "出馆时间：" + oerr.Error()
		}
		if t.Before(inAt) {
			return "出馆时间早于进馆时间"
		}
		outAt = &t
	}

	// 完全重复：同一天同一分钟进馆，直接跳过（重跑导入不会产生脏数据）
	var dup int
	if err := s.db.QueryRow(`SELECT COUNT(1) FROM sessions WHERE user_id = ? AND day = ? AND in_at = ?`,
		uid, day, inAt.Format(time.RFC3339)).Scan(&dup); err != nil {
		return "查询失败"
	}
	if dup > 0 {
		return "skipped"
	}
	if hit, herr := s.sessionOverlap(uid, day, inAt, outAt, 0); herr != nil {
		return "查询失败"
	} else if hit != nil {
		return "和已有分段重叠"
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
	inNote := truncateStr(it.InNote, 200)
	outNote := truncateStr(it.OutNote, 200)
	inPlace := truncateStr(it.InPlace, 40)
	outPlace := truncateStr(it.OutPl, 40)
	subject := truncateStr(strings.TrimSpace(it.Subject), 20)
	stamp := today.Format(time.RFC3339)

	if err := s.withTx(func(q querier) error {
		if _, err := q.Exec(`INSERT INTO sessions(user_id, day, in_at, out_at,
				in_note, out_note, in_place, out_place, subject, duration_sec, created_at, updated_at)
			VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`,
			uid, day, inAt.Format(time.RFC3339), outStr, inNote, outNote, inPlace, outPlace,
			subject, dur, stamp, stamp); err != nil {
			return err
		}
		return s.recomputeDayOn(q, uid, day)
	}); err != nil {
		return "写入失败"
	}
	return ""
}

// readImportCSV 解析上传的 CSV。兼容两种导出格式：
//   - 按段导出：日期,科目,进馆时间,出馆时间,时长(分钟),进馆地点,出馆地点,进馆备注,出馆备注
//   - 按天导出：日期,进馆时间,出馆时间,时长(分钟),进馆地点,出馆地点,进馆备注,出馆备注
//
// 时间列同时支持「HH:MM」和「YYYY-MM-DD HH:MM」。
func (s *Server) readImportCSV(r *http.Request) ([]importReqItem, error) {
	mr, err := r.MultipartReader()
	if err != nil {
		return nil, errors.New("请以 multipart 表单上传 CSV 文件")
	}
	part, err := mr.NextPart()
	if err != nil {
		return nil, errors.New("缺少文件字段 file")
	}
	defer part.Close()

	rd := csv.NewReader(io.LimitReader(part, importMaxBytes))
	rd.FieldsPerRecord = -1
	rd.TrimLeadingSpace = true
	rows, err := rd.ReadAll()
	if err != nil {
		return nil, errors.New("CSV 解析失败：" + err.Error())
	}
	// Excel 导出的 CSV 常带 UTF-8 BOM，先去表头，不然第一列名匹配不上
	if len(rows[0]) > 0 {
		rows[0][0] = strings.TrimPrefix(rows[0][0], "\ufeff")
	}

	head := rows[0]
	col := func(names ...string) int {
		for i, h := range head {
			hh := strings.TrimSpace(h)
			for _, n := range names {
				if hh == n {
					return i
				}
			}
		}
		return -1
	}
	// 表头可能是导出的中文列名，也可能是 day/in_at 这类英文字段名
	cDay := col("日期", "day")
	cIn := col("进馆时间", "in_at")
	cOut := col("出馆时间", "out_at")
	cSub := col("科目", "subject")
	cInPlace := col("进馆地点", "in_place")
	cOutPlace := col("出馆地点", "out_place")
	cInNote := col("进馆备注", "in_note")
	cOutNote := col("出馆备注", "out_note")

	data := rows[1:]
	if cDay < 0 || cIn < 0 {
		// 没有表头：按导出的列序认——「按天导出」是 日期,进馆,出馆,…，「按段导出」是 日期,科目,进馆,出馆,…
		if d, i, o, sub, ok := detectNoHeader(rows[0]); ok {
			cDay, cIn, cOut, cSub = d, i, o, sub
			if len(rows[0]) >= 7 {
				cInPlace, cOutPlace = 5, 6
			}
			if len(rows[0]) >= 9 {
				cInNote, cOutNote = 7, 8
			}
			data = rows
		} else {
			return nil, errors.New("CSV 需要「日期」和「进馆时间」两列")
		}
	}

	cell := func(row []string, idx int) string {
		if idx < 0 || idx >= len(row) {
			return ""
		}
		return strings.TrimSpace(row[idx])
	}

	out := make([]importReqItem, 0, len(data))
	for _, row := range data {
		if len(row) == 0 || (len(row) == 1 && strings.TrimSpace(row[0]) == "") {
			continue
		}
		day := cell(row, cDay)
		in := cell(row, cIn)
		if day == "" && in == "" {
			continue
		}
		out = append(out, importReqItem{
			Day:     day,
			InAt:    in,
			OutAt:   cell(row, cOut),
			Subject: cell(row, cSub),
			InNote:  cell(row, cInNote),
			OutNote: cell(row, cOutNote),
			InPlace: cell(row, cInPlace),
			OutPl:   cell(row, cOutPlace),
		})
	}
	if len(out) == 0 {
		return nil, errors.New("CSV 里没有有效数据行")
	}
	return out, nil
}

// detectNoHeader 判断这一行是不是无表头的数据行，并认出列序：
//   - 按天导出：日期,进馆时间,出馆时间,…（第 2 列就是时间）
//   - 按段导出：日期,科目,进馆时间,出馆时间,…（第 3 列才是时间）
//
// 认不出来就返回 ok=false。
func detectNoHeader(row []string) (cDay, cIn, cOut, cSub int, ok bool) {
	if len(row) < 2 {
		return 0, 0, 0, 0, false
	}
	if _, err := time.Parse("2006-01-02", strings.TrimSpace(row[0])); err != nil {
		return 0, 0, 0, 0, false
	}
	if looksLikeClock(row[1]) {
		cIn = 1
	} else if len(row) > 2 && looksLikeClock(row[2]) {
		cIn, cSub = 2, 1
	} else {
		return 0, 0, 0, 0, false
	}
	cOut = cIn + 1
	return 0, cIn, cOut, cSub, true
}

// looksLikeClock 粗略判断一个单元格是不是「HH:MM」这样的时间。
func looksLikeClock(v string) bool {
	v = strings.TrimSpace(v)
	if i := strings.IndexByte(v, ':'); i > 0 {
		v = v[:i]
	}
	n, err := strconv.Atoi(v)
	return err == nil && n >= 0 && n < 24
}

// combineImportTime 兼容「HH:MM」与「YYYY-MM-DD HH:MM」两种时间写法。
func combineImportTime(day, raw string, loc *time.Location) (time.Time, error) {
	raw = strings.TrimSpace(raw)
	if strings.Contains(raw, " ") || strings.Contains(raw, "T") {
		// 带日期的写法直接解析，忽略 day 列
		for _, layout := range []string{"2006-01-02 15:04:05", "2006-01-02 15:04", time.RFC3339} {
			if t, err := time.ParseInLocation(layout, raw, loc); err == nil {
				return t, nil
			}
		}
	}
	return combineDayTime(day, raw, loc)
}
