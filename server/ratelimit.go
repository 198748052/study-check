package main

import (
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	loginFailLimit  = 5               // 窗口内允许的失败次数
	loginFailWindow = 5 * time.Minute // 计数窗口
	loginLockTime   = 5 * time.Minute // 超限后的锁定时长
	limiterSweepAt  = 512             // 条目超过该数量时清理过期数据
)

type rateEntry struct {
	fails     int
	start     time.Time
	lockUntil time.Time
}

// loginLimiter 是按 key 计数的内存令牌桶：窗口内失败满额即锁定一段时间。
// 单进程内存实现，重启即清空，够用且零依赖。
type loginLimiter struct {
	mu sync.Mutex
	m  map[string]*rateEntry
}

func newLoginLimiter() *loginLimiter {
	return &loginLimiter{m: make(map[string]*rateEntry)}
}

// wait 返回该 key 还需等待多久才能重试，0 表示放行。
func (l *loginLimiter) wait(key string, now time.Time) time.Duration {
	l.mu.Lock()
	defer l.mu.Unlock()
	e := l.m[key]
	if e == nil {
		return 0
	}
	if now.Before(e.lockUntil) {
		return e.lockUntil.Sub(now)
	}
	return 0
}

func (l *loginLimiter) fail(key string, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	e := l.m[key]
	if e == nil || now.Sub(e.start) > loginFailWindow {
		l.m[key] = &rateEntry{fails: 1, start: now}
	} else {
		e.fails++
		if e.fails >= loginFailLimit {
			e.fails = 0
			e.start = now
			e.lockUntil = now.Add(loginLockTime)
		}
	}
	if len(l.m) > limiterSweepAt {
		l.sweepLocked(now)
	}
}

func (l *loginLimiter) reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.m, key)
}

func (l *loginLimiter) sweepLocked(now time.Time) {
	for k, e := range l.m {
		if now.Sub(e.start) > loginFailWindow && !now.Before(e.lockUntil) {
			delete(l.m, k)
		}
	}
}

// clientIP 取客户端地址。部署在 Nginx 之后时优先用转发头。
// X-Forwarded-For 可被伪造，所以 IP 只是辅助维度，用户名维度同样计数。
func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if i := strings.IndexByte(xff, ','); i >= 0 {
			return strings.TrimSpace(xff[:i])
		}
		return strings.TrimSpace(xff)
	}
	if ip := r.Header.Get("X-Real-IP"); ip != "" {
		return strings.TrimSpace(ip)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// writeTooMany 返回 429，并告知客户端多少秒后可重试。
func writeTooMany(w http.ResponseWriter, d time.Duration) {
	secs := int(d.Seconds())
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	writeErr(w, http.StatusTooManyRequests, "too_many_attempts",
		fmt.Sprintf("尝试过于频繁，请 %d 分钟后再试", (secs+59)/60))
}
