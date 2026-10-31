package main

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// sessions 是唯一真相，checkins 只是「按天聚合」的缓存。
// 万一进程在「写分段」和「重算聚合」之间被 kill，缓存就会和真相不一致；
// 这里提供两件事：
//   - startupSelfCheck：启动时对一遍账，不一致就打日志提示
//   - POST /api/v1/system/recompute：管理员手动重算（某天 / 某个用户 / 全量）

type integrityReport struct {
	Mismatch int `json:"mismatch"` // 聚合总时长和分段之和对不上
	Missing  int `json:"missing"`  // 有分段但没有聚合行
	Orphan   int `json:"orphan"`   // 有聚合行但一段都没有
}

// integrityCheck 只读地对账，不修数据。
func (s *Server) integrityCheck() (integrityReport, error) {
	var rep integrityReport
	if err := s.db.QueryRow(`
		SELECT COUNT(1) FROM (
			SELECT user_id, day, SUM(duration_sec) AS dur FROM sessions
			WHERE in_at IS NOT NULL GROUP BY user_id, day
		) g JOIN checkins c ON c.user_id = g.user_id AND c.day = g.day
		WHERE c.duration_sec <> g.dur`).Scan(&rep.Mismatch); err != nil {
		return rep, err
	}
	if err := s.db.QueryRow(`
		SELECT COUNT(1) FROM (
			SELECT user_id, day FROM sessions WHERE in_at IS NOT NULL GROUP BY user_id, day
		) g LEFT JOIN checkins c ON c.user_id = g.user_id AND c.day = g.day
		WHERE c.id IS NULL`).Scan(&rep.Missing); err != nil {
		return rep, err
	}
	if err := s.db.QueryRow(`
		SELECT COUNT(1) FROM checkins c
		WHERE NOT EXISTS (SELECT 1 FROM sessions g WHERE g.user_id = c.user_id AND g.day = c.day)`).
		Scan(&rep.Orphan); err != nil {
		return rep, err
	}
	return rep, nil
}

// startupSelfCheck 启动时对账，只在有问题时打日志（不自动改数据）。
func (s *Server) startupSelfCheck() {
	rep, err := s.integrityCheck()
	if err != nil {
		log.Printf("启动自检失败: %v", err)
		return
	}
	if rep.Mismatch+rep.Missing+rep.Orphan == 0 {
		log.Printf("启动自检通过：分段落和按天聚合一致")
		return
	}
	log.Printf("⚠️ 启动自检发现聚合缓存不一致（时长不符 %d 天 / 缺聚合 %d 天 / 多余聚合 %d 天），"+
		"可调用 POST /api/v1/system/recompute 重算", rep.Mismatch, rep.Missing, rep.Orphan)
}

// dayPairs 列出需要重算的 (user_id, day)；day / uid 为空表示不限。
func (s *Server) dayPairs(day string, uid int64) ([][2]any, error) {
	where := []string{"1 = 1"}
	args := []any{}
	if day != "" {
		where = append(where, "day = ?")
		args = append(args, day)
	}
	if uid > 0 {
		where = append(where, "user_id = ?")
		args = append(args, uid)
	}
	cond := strings.Join(where, " AND ")
	q := `SELECT user_id, day FROM sessions WHERE ` + cond +
		` UNION SELECT user_id, day FROM checkins WHERE ` + cond

	rows, err := s.db.Query(q, append(args, args...)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out [][2]any
	for rows.Next() {
		var u any
		var d any
		if err := rows.Scan(&u, &d); err != nil {
			return nil, err
		}
		out = append(out, [2]any{u, d})
	}
	return out, rows.Err()
}

// POST /api/v1/system/recompute
// body 可选：{"day":"2026-10-30","user_id":3}；都不给就是全量重算。
func (s *Server) handleRecompute(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Day    string `json:"day"`
		UserID int64  `json:"user_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil &&
		!errors.Is(err, io.EOF) {
		writeErr(w, http.StatusBadRequest, "bad_json", "请求体不是合法 JSON")
		return
	}
	req.Day = strings.TrimSpace(req.Day)
	if req.Day != "" {
		if _, err := time.ParseInLocation("2006-01-02", req.Day, time.UTC); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_day", "day 格式应为 YYYY-MM-DD")
			return
		}
	}

	pairs, err := s.dayPairs(req.Day, req.UserID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}

	fixed := 0
	for _, p := range pairs {
		uid, _ := p[0].(int64)
		day, _ := p[1].(string)
		if err := s.recomputeDay(uid, day); err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "重算失败（"+day+"）")
			return
		}
		fixed++
	}

	rep, err := s.integrityCheck()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "自检失败")
		return
	}
	log.Printf("已重算 %d 天的聚合缓存（day=%q uid=%d）", fixed, req.Day, req.UserID)
	writeJSON(w, http.StatusOK, map[string]any{
		"recomputed": fixed,
		"integrity":  rep,
	})
}
