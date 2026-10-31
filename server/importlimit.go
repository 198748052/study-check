package main

import (
	"net/http"
	"strconv"
	"sync"
	"time"
)

// ---------------------------------------------------------------
// 导入限流 / 配额
//
// 导入是「一次写很多行」的接口，比普通打卡接口更容易被滥用（拿它当批量写库、
// 或者脚本狂刷）。这里加两道闸：
//
//  1. 频率：同一用户 1 分钟内最多 importRatePerMinute 次请求（防脚本狂刷；
//     换机导入一般一次就成功，真失败了重试几次也在额度内）
//  2. 配额：同一用户每天最多 importRowsPerDay 行（防一次灌几万行）
//
// 行数配额写进数据库（import_quota 表），重启后依然有效；
// 频率计数是内存滑动计数，重启清零，够用且零依赖。
// ---------------------------------------------------------------

const (
	importRatePerMinute = 5
	importRowsPerDay    = 20000
)

// importLimiter 每个用户一个滑动的分钟窗口计数。
type importLimiter struct {
	mu     sync.Mutex
	counts map[int64]*importWindow
}

type importWindow struct {
	start   time.Time
	count   int
	rowsDay int // 当天的行数快照，仅用于日志排错
}

func newImportLimiter() *importLimiter {
	return &importLimiter{counts: make(map[int64]*importWindow)}
}

// allow 记录一次请求，返回 (是否放行, 需等待多久)。
func (l *importLimiter) allow(uid int64, now time.Time) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	w := l.counts[uid]
	if w == nil || now.Sub(w.start) >= time.Minute {
		l.counts[uid] = &importWindow{start: now, count: 1}
		return true, 0
	}
	if w.count >= importRatePerMinute {
		return false, w.start.Add(time.Minute).Sub(now)
	}
	w.count++
	return true, 0
}

func (l *importLimiter) reset(uid int64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.counts, uid)
}

// importQuotaLeft 返回该用户今天还能导入多少行（配额表没有记录时是满额）。
func (s *Server) importQuotaLeft(uid int64, day string) (int, error) {
	var done int
	err := s.db.QueryRow(`SELECT rows_done FROM import_quota WHERE user_id = ? AND day = ?`,
		uid, day).Scan(&done)
	if err != nil {
		// 没记录就是今天还没导过
		return importRowsPerDay, nil
	}
	left := importRowsPerDay - done
	if left < 0 {
		left = 0
	}
	return left, nil
}

// importQuotaAdd 把本次实际导入的行数累加到配额表。
func (s *Server) importQuotaAdd(uid int64, day string, rows int) error {
	if rows <= 0 {
		return nil
	}
	_, err := s.db.Exec(`INSERT INTO import_quota(user_id, day, rows_done, updated_at)
		VALUES(?,?,?,?)
		ON CONFLICT(user_id, day) DO UPDATE SET
			rows_done = rows_done + excluded.rows_done,
			updated_at = excluded.updated_at`,
		uid, day, rows, time.Now().Format(time.RFC3339))
	return err
}

// importQuotaReset 手动清掉某用户当天的配额（管理端排错用）。
func (s *Server) importQuotaReset(uid int64, day string) error {
	_, err := s.db.Exec(`DELETE FROM import_quota WHERE user_id = ? AND day = ?`, uid, day)
	return err
}

// checkImportRate 频率闸：超了返回 429 并告诉客户端等多久。
func (s *Server) checkImportRate(w http.ResponseWriter, uid int64) bool {
	ok, wait := s.importLimit.allow(uid, time.Now())
	if !ok {
		secs := int(wait.Seconds())
		if secs < 1 {
			secs = 1
		}
		w.Header().Set("Retry-After", strconv.Itoa(secs))
		writeErr(w, http.StatusTooManyRequests, "too_many_imports",
			"导入太频繁了，请 "+strconv.Itoa(secs)+" 秒后再试")
		return false
	}
	return true
}
