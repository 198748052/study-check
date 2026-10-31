package main

import (
	"net/http"
	"sort"
	"strings"
	"time"
)

// ---------------------------------------------------------------
// 全局搜索：打卡备注 / 地点 / 过程笔记
// GET /api/v1/search?q=关键词&limit=50
// ---------------------------------------------------------------

type searchItem struct {
	Kind        string  `json:"kind"` // session | entry
	ID          int64   `json:"id"`
	Day         string  `json:"day"`
	At          string  `json:"at"`
	InAt        *string `json:"in_at,omitempty"`
	OutAt       *string `json:"out_at,omitempty"`
	Note        string  `json:"note"`
	Place       string  `json:"place"`
	Subject     string  `json:"subject,omitempty"`
	PhotoURL    *string `json:"photo_url,omitempty"`
	ThumbURL    *string `json:"thumb_url,omitempty"`
	DurationSec int64   `json:"duration_sec,omitempty"`
	Match       string  `json:"match,omitempty"`
}

// likePattern 转义 LIKE 的通配符，避免用户输入的 % / _ 变成通配。
func likePattern(q string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return "%" + r.Replace(q) + "%"
}

func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	uid := userID(r)
	loc := s.clientLoc(r)
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		writeErr(w, http.StatusBadRequest, "q_required", "请提供搜索关键词 q")
		return
	}
	limit := qInt(r, "limit", 50)
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	pattern := likePattern(q)
	// 关键词本身是日期时，顺便把这一天的记录也带出来
	dayExact := ""
	if t, err := time.ParseInLocation("2006-01-02", q, loc); err == nil {
		dayExact = t.Format("2006-01-02")
	}

	items := make([]searchItem, 0, limit)

	// —— 打卡分段 ——
	rows, err := s.db.Query(`SELECT id, day, COALESCE(in_at,''), COALESCE(out_at,''),
			COALESCE(in_photo,''), COALESCE(out_photo,''),
			in_note, out_note, in_place, out_place, COALESCE(subject,''), duration_sec
		FROM sessions
		WHERE user_id = ?
		  AND (in_note LIKE ? ESCAPE '\' OR out_note LIKE ? ESCAPE '\'
		       OR in_place LIKE ? ESCAPE '\' OR out_place LIKE ? ESCAPE '\'
		       OR subject LIKE ? ESCAPE '\' OR day = ?)
		ORDER BY day DESC, in_at DESC, id DESC
		LIMIT ?`,
		uid, pattern, pattern, pattern, pattern, pattern, dayExact, limit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "查询失败")
		return
	}
	for rows.Next() {
		var (
			id                int64
			day, inAt, outAt  string
			inPhoto, outPh    string
			inNote, outNote   string
			inPlace, outPlace string
			subject           string
			dur               int64
		)
		if err := rows.Scan(&id, &day, &inAt, &outAt, &inPhoto, &outPh,
			&inNote, &outNote, &inPlace, &outPlace, &subject, &dur); err != nil {
			rows.Close()
			writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
			return
		}
		photo := inPhoto
		if photo == "" {
			photo = outPh
		}
		it := searchItem{
			Kind:        "session",
			ID:          id,
			Day:         day,
			At:          inAt,
			InAt:        strPtr(inAt),
			OutAt:       strPtr(outAt),
			Note:        joinNotes(inNote, outNote),
			Place:       firstNonEmpty(inPlace, outPlace),
			Subject:     subject,
			DurationSec: dur,
			Match:       matchField(q, inNote, outNote, inPlace, outPlace, subject),
		}
		it.PhotoURL, it.ThumbURL = mediaURL(strPtr(photo)), thumbURL(strPtr(photo))
		items = append(items, it)
	}
	rows.Close()

	// —— 过程笔记 ——
	if len(items) < limit {
		erows, err := s.db.Query(`SELECT id, day, COALESCE(created_at,''), COALESCE(photo,''), note
			FROM entries
			WHERE user_id = ? AND (note LIKE ? ESCAPE '\' OR day = ?)
			ORDER BY day DESC, id DESC
			LIMIT ?`, uid, pattern, dayExact, limit-len(items))
		if err == nil {
			for erows.Next() {
				var (
					id        int64
					day, at   string
					photo, no string
				)
				if err := erows.Scan(&id, &day, &at, &photo, &no); err != nil {
					break
				}
				it := searchItem{
					Kind:  "entry",
					ID:    id,
					Day:   day,
					At:    at,
					Note:  no,
					Match: matchField(q, no),
				}
				it.PhotoURL, it.ThumbURL = mediaURL(strPtr(photo)), thumbURL(strPtr(photo))
				items = append(items, it)
			}
			erows.Close()
		}
	}

	// 统一按日期倒序，同一天里按时间倒序
	sort.SliceStable(items, func(i, j int) bool {
		if items[i].Day != items[j].Day {
			return items[i].Day > items[j].Day
		}
		return items[i].At > items[j].At
	})
	if len(items) > limit {
		items = items[:limit]
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"query":       q,
		"items":       items,
		"count":       len(items),
		"server_time": time.Now().In(loc).Format(time.RFC3339),
	})
}

func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func joinNotes(vals ...string) string {
	out := make([]string, 0, len(vals))
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			out = append(out, v)
		}
	}
	return strings.Join(out, " · ")
}

// matchField 返回命中的字段名，客户端可以高亮「备注」还是「地点」。
func matchField(q string, values ...string) string {
	names := []string{"note", "note", "place", "place", "subject"}
	lower := strings.ToLower(q)
	for i, v := range values {
		if strings.Contains(strings.ToLower(v), lower) {
			if i < len(names) {
				return names[i]
			}
			return "note"
		}
	}
	return ""
}
