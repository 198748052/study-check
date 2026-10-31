package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// 更新器：拉 manifest → 下载二进制 → sha256 校验 → 原子替换 → 重启 → 健康校验 → 失败自动回滚
//
// 更新源默认是 GitHub Release（SC_UPDATE_REPO 指向仓库，默认本项目的仓库）：
//   https://github.com/<repo>/releases/latest/download/latest-<arch>.json
// 也可以直接用 SC_UPDATE_MANIFEST_URL 指定任意清单地址。
//
// 重启方式按运行环境自动选：systemd → supervisor → 直接给自己发 TERM（交给外部守护重启）。

const defaultUpdateRepo = "198748052/study-check"

type updateManifest struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	SHA256  string `json:"sha256"`
	Notes   string `json:"notes"`
	Size    int64  `json:"size"`
}

type UpdateStatus struct {
	Enabled      bool    `json:"enabled"`
	CurrentVer   string  `json:"current_version"`
	LatestVer    string  `json:"latest_version"`
	HasUpdate    bool    `json:"has_update"`
	Running      bool    `json:"running"`
	LastRun      string  `json:"last_run"`
	TargetVer    string  `json:"target_version,omitempty"`
	RunningSince *string `json:"running_since,omitempty"`
	Error        string  `json:"error,omitempty"`
	CheckedAt    *string `json:"checked_at,omitempty"`
	ManifestURL  string  `json:"manifest_url,omitempty"`
	Notes        string  `json:"notes,omitempty"`
	ServiceName  string  `json:"service_name,omitempty"`
	AutoUpdate   bool    `json:"auto_update"`
	Manager      string  `json:"manager,omitempty"`
	Arch         string  `json:"arch,omitempty"`
}

var updateMu sync.Mutex

func (s *Server) dataPath(name string) string {
	return filepath.Join(filepath.Dir(s.cfg.DBPath), name)
}

func (s *Server) updateStatusPath() string { return s.dataPath("update.status") }
func (s *Server) updateLogPath() string    { return s.dataPath("update.log") }

// updateMarker 是 update.status 的内容：
//
//	running <目标版本> <更新脚本 PID>
//	ok <目标版本>
//	failed <目标版本>
//
// 老格式只有状态本身（没有版本和 PID），这里一并兼容。
type updateMarker struct {
	State  string // ok / failed / running / none
	Target string // 目标版本
	PID    int    // 更新脚本自己的 PID（0 表示未知）
}

func readMarkerFull(path string) updateMarker {
	b, err := os.ReadFile(path)
	if err != nil {
		return updateMarker{State: "none"}
	}
	fields := strings.Fields(strings.TrimSpace(string(b)))
	if len(fields) == 0 {
		return updateMarker{State: "none"}
	}
	m := updateMarker{State: "none"}
	switch fields[0] {
	case "ok", "failed", "running":
		m.State = fields[0]
	}
	if len(fields) > 1 {
		m.Target = fields[1]
	}
	if len(fields) > 2 {
		if n, err := strconv.Atoi(fields[2]); err == nil {
			m.PID = n
		}
	}
	return m
}

// readMarker 只要状态本身（日志接口、自动更新循环用）。
func readMarker(path string) string { return readMarkerFull(path).State }

func writeMarker(path, state, target string) error {
	body := state
	if target != "" {
		body += " " + target
	}
	return os.WriteFile(path, []byte(body), 0o644)
}

// pidAlive 判断进程是否还在（EPERM 说明存在但没权限，也算活着）。
func pidAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	err := syscall.Kill(pid, 0)
	return err == nil || errors.Is(err, syscall.EPERM)
}

// updateRunningMaxAge 兜底过期时间：脚本 PID 未知时（老格式 / 刚启动），
// 超过这个时长就不再认为「正在更新」，免得界面永远卡在「更新中」。
const updateRunningMaxAge = 10 * time.Minute

// updateRunning 是否真的还有更新在跑。
// 有 PID 就以进程是否还活着为准——更新脚本被 systemd 的 cgroup 连带杀掉时能立刻发现；
// 没有 PID 才退回「文件是否够新」。
func (s *Server) updateRunning() bool {
	path := s.updateStatusPath()
	m := readMarkerFull(path)
	if m.State != "running" {
		return false
	}
	st, err := os.Stat(path)
	if err != nil {
		return false
	}
	if time.Since(st.ModTime()) > updateRunningMaxAge {
		return false
	}
	if m.PID > 0 && !pidAlive(m.PID) {
		return false
	}
	return true
}

// healUpdateMarker 收尾卡住的 running 标记：
// 更新脚本可能已经没了（被 systemd 连带杀、机器重启、脚本自己崩），
// 那就按「当前版本是否已经是目标版本」给出最终结论，避免界面一直显示「更新中」。
func (s *Server) healUpdateMarker() {
	path := s.updateStatusPath()
	m := readMarkerFull(path)
	if m.State != "running" || s.updateRunning() || m.Target == "" {
		return
	}
	if compareSemver(m.Target, version) <= 0 {
		if err := writeMarker(path, "ok", m.Target); err == nil {
			log.Printf("更新收尾：当前已是 %s（目标 %s），标记为成功", version, m.Target)
		}
		return
	}
	if err := writeMarker(path, "failed", m.Target); err == nil {
		log.Printf("更新收尾：仍是 %s，目标 %s 没换上，标记为失败", version, m.Target)
	}
}

// markerIsFreshRunning 兼容旧调用点。
func markerIsFreshRunning(path string) bool {
	if readMarker(path) != "running" {
		return false
	}
	st, err := os.Stat(path)
	if err != nil {
		return false
	}
	return time.Since(st.ModTime()) < updateRunningMaxAge
}

// compareSemver 只比较 x.y.z 数字段，返回 1/0/-1；非法版本视为相等。
func compareSemver(a, b string) int {
	pa := strings.Split(strings.TrimPrefix(strings.TrimPrefix(a, "v"), "V"), ".")
	pb := strings.Split(strings.TrimPrefix(strings.TrimPrefix(b, "v"), "V"), ".")
	for i := 0; i < 3; i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(strings.SplitN(pa[i], "-", 2)[0])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(strings.SplitN(pb[i], "-", 2)[0])
		}
		if x > y {
			return 1
		}
		if x < y {
			return -1
		}
	}
	return 0
}

// repoName 返回配置的仓库，未配置时用项目默认仓库。
func (s *Server) repoName() string {
	if r := strings.TrimSpace(s.cfg.UpdateRepo); r != "" {
		return strings.Trim(r, "/")
	}
	return defaultUpdateRepo
}

// manifestCandidates 返回本次该尝试的清单地址（按优先级）。
// 优先按架构的清单（arm64 机器不会下到 amd64 的包），再退回通用清单。
func (s *Server) manifestCandidates() []string {
	if u := strings.TrimSpace(s.cfg.UpdateManifestURL); u != "" {
		return []string{u}
	}
	if s.cfg.UpdateRepo == "-" {
		return nil
	}
	base := "https://github.com/" + s.repoName() + "/releases/latest/download"
	return []string{
		base + "/latest-" + runtime.GOARCH + ".json",
		base + "/latest.json",
	}
}

func (s *Server) effectiveManifestURL() string {
	if c := s.manifestCandidates(); len(c) > 0 {
		return c[0]
	}
	return ""
}

func (s *Server) fetchManifest() (*updateManifest, error) {
	candidates := s.manifestCandidates()
	if len(candidates) == 0 {
		return nil, errors.New("未配置更新源（SC_UPDATE_MANIFEST_URL 或 SC_UPDATE_REPO）")
	}
	client := &http.Client{Timeout: 20 * time.Second}
	var lastErr error
	for _, u := range candidates {
		m, err := fetchOneManifest(client, u)
		if err == nil {
			return m, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func fetchOneManifest(client *http.Client, url string) (*updateManifest, error) {
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "studycheck-updater/"+version)
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("获取更新清单失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("更新清单返回 HTTP %d", resp.StatusCode)
	}
	var m updateManifest
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&m); err != nil {
		return nil, fmt.Errorf("更新清单格式错误: %w", err)
	}
	if m.Version == "" || m.URL == "" || m.SHA256 == "" {
		return nil, errors.New("更新清单缺少 version/url/sha256")
	}
	// 通用清单里写死的是 amd64 包，arm64 机器上换成对应架构的下载地址
	if runtime.GOARCH != "amd64" && strings.Contains(m.URL, "studycheck-linux-amd64") {
		m.URL = strings.Replace(m.URL, "studycheck-linux-amd64", "studycheck-linux-"+runtime.GOARCH, 1)
		m.SHA256 = "" // 哈希必须重新取，交给下载后的 SHA256SUMS 校验
	}
	return &m, nil
}

func (s *Server) buildStatus(errMsg string) UpdateStatus {
	// 先把卡住的 running 标记收尾（脚本没了就按版本判定成败），界面才不会一直显示「更新中」
	s.healUpdateMarker()
	marker := readMarkerFull(s.updateStatusPath())
	st := UpdateStatus{
		Enabled:     s.cfg.UpdateEnabled,
		CurrentVer:  version,
		Running:     s.updateRunning(),
		LastRun:     marker.State,
		TargetVer:   marker.Target,
		ManifestURL: s.effectiveManifestURL(),
		ServiceName: s.cfg.ServiceName,
		AutoUpdate:  s.cfg.AutoUpdate,
		Error:       errMsg,
		Manager:     s.detectManager(),
		Arch:        runtime.GOARCH,
	}
	// 更新开始时间（状态文件的修改时间），页面刷新后也能算出等了多久
	if st.Running {
		if fi, err := os.Stat(s.updateStatusPath()); err == nil {
			ts := fi.ModTime().In(s.cfg.Loc).Format(time.RFC3339)
			st.RunningSince = &ts
		}
	}
	if raw, err := os.ReadFile(s.dataPath("update.cache")); err == nil {
		var cached struct {
			Latest    string `json:"latest"`
			Notes     string `json:"notes"`
			CheckedAt string `json:"checked_at"`
		}
		if json.Unmarshal(raw, &cached) == nil {
			st.LatestVer = cached.Latest
			st.Notes = cached.Notes
			if cached.CheckedAt != "" {
				st.CheckedAt = &cached.CheckedAt
			}
			st.HasUpdate = cached.Latest != "" && compareSemver(cached.Latest, version) > 0
		}
	}
	return st
}

func (s *Server) checkUpdate() (UpdateStatus, error) {
	m, err := s.fetchManifest()
	if err != nil {
		return s.buildStatus(err.Error()), err
	}
	now := time.Now().In(s.cfg.Loc).Format(time.RFC3339)
	cache, _ := json.Marshal(map[string]string{"latest": m.Version, "notes": m.Notes, "checked_at": now})
	_ = os.WriteFile(s.dataPath("update.cache"), cache, 0o644)
	st := s.buildStatus("")
	st.LatestVer = m.Version
	st.Notes = m.Notes
	st.CheckedAt = &now
	st.HasUpdate = compareSemver(m.Version, version) > 0
	return st, nil
}

// applyUpdate 生成并脱离父进程执行更新脚本（脚本会重启本进程，必须 detached）。
func (s *Server) applyUpdate() error {
	updateMu.Lock()
	defer updateMu.Unlock()

	if markerIsFreshRunning(s.updateStatusPath()) {
		return errors.New("已有更新正在进行")
	}
	m, err := s.fetchManifest()
	if err != nil {
		return err
	}
	if compareSemver(m.Version, version) <= 0 {
		return fmt.Errorf("当前已是最新版本（%s）", version)
	}
	if !strings.HasPrefix(m.URL, "https://") {
		return errors.New("拒绝非 https 的更新包地址")
	}

	binPath, err := os.Executable()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.updateLogPath()), 0o755); err != nil {
		return err
	}

	script := s.buildUpdateScript(m, binPath)
	scriptPath := s.dataPath("update.sh")
	if err := os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		return err
	}
	// 先落一个 running 标记（目标版本要记下来，脚本被系统连带杀掉后靠它判定成败）
	_ = writeMarker(s.updateStatusPath(), "running", m.Version)

	lf, err := os.OpenFile(s.updateLogPath(), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	defer lf.Close()

	cmd := exec.Command("/bin/sh", scriptPath)
	cmd.Dir = filepath.Dir(binPath)
	cmd.Stdout, cmd.Stderr = lf, lf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true} // 脱离进程组，父进程重启不影响它
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}

// detectManager 判断当前用什么方式重启服务。
// 注意：有 systemd 不代表当前用户有权调 systemctl（服务以非 root 跑时经常这样），
// 所以这里只做"存在性"判断，真正的可用性由 canRestartVia 探测。
func (s *Server) detectManager() string {
	if fi, err := os.Stat("/run/systemd/system"); err == nil && fi.IsDir() {
		if _, err := exec.LookPath("systemctl"); err == nil {
			return "systemd"
		}
	}
	if _, err := exec.LookPath("supervisorctl"); err == nil {
		return "supervisor"
	}
	return "self"
}

// canRestartVia 探测当前进程是否真的能用该方式重启服务。
// 服务以普通用户运行、又没配免密 sudo / polkit 时，systemctl 会直接
// "Access denied"——更新脚本跑到这一步就彻底卡死，所以必须提前探测。
func (s *Server) canRestartVia(mgr string) bool {
	switch mgr {
	case "systemd":
		// 只查询不动作：is-active 是最轻的调用，能通就说明有权限
		out, err := exec.Command("systemctl", "is-active", "--quiet", s.cfg.ServiceName).CombinedOutput()
		if err == nil {
			return true
		}
		msg := strings.ToLower(string(out))
		// 明确是无权访问时才判不可用；服务没装/状态异常仍可尝试 restart
		return !strings.Contains(msg, "access denied") &&
			!strings.Contains(msg, "interactive authentication") &&
			!strings.Contains(msg, "not authorized")
	case "supervisor":
		out, err := exec.Command("supervisorctl", "status", s.cfg.ServiceName).CombinedOutput()
		if err == nil {
			return true
		}
		msg := strings.ToLower(string(out))
		return !strings.Contains(msg, "access denied") && !strings.Contains(msg, "refused")
	default:
		return true
	}
}

// restartCommand 返回注入到更新脚本里的重启命令。
// 优先用进程管理器；当前用户没权限时退回"给自己发 TERM"，
// 靠 systemd 的 Restart=always 或 supervisor 的 autorestart 把服务拉活。
func (s *Server) restartCommand() string {
	svc := s.cfg.ServiceName
	if mgr := s.detectManager(); mgr != "self" && s.canRestartVia(mgr) {
		cmd := "systemctl restart " + svc
		label := "systemctl restart " + svc
		if mgr == "supervisor" {
			cmd = "supervisorctl restart " + svc
			label = cmd
		}
		return fmt.Sprintf(`restart_service() {
  echo "[update] %s"
  if %s; then return 0; fi
  echo "[update] %s 失败，退回 TERM 让守护程序拉活"
  kill -TERM "$PID" 2>/dev/null || return 1
}`, label, cmd, label)
	}
	return `restart_service() {
  echo "[update] 无进程管理器权限，发送 TERM 让守护程序重启"
  kill -TERM "$PID" 2>/dev/null || return 1
}`
}

func absPath(p string) string {
	if a, err := filepath.Abs(p); err == nil {
		return a
	}
	return p
}

func (s *Server) buildUpdateScript(m *updateManifest, binPath string) string {
	statusPath := absPath(s.updateStatusPath())
	newBin := binPath + ".new"
	// manifest 里没有 sha 时（架构回退场景），下载完用 release 的 SHA256SUMS 兜底校验
	return fmt.Sprintf(`#!/bin/sh
# studycheck 自动更新脚本（由程序生成，请勿手改）
STATUS=%q
BIN=%q
NEW=%q
PREV=%q
PID=%d
HEALTH="http://127.0.0.1:%s/api/v1/health"
TARGET=%q
URL=%q
SHA=%q
SUMS=%q

set -u

# 把「正在更新 + 目标版本 + 自己的 PID」写进状态文件：
# 进程不在了，服务端就能立刻判定这次更新已经结束（而不是傻等）
mark_running() {
  printf "running %%s %%s" "$TARGET" "$$" > "$STATUS" 2>/dev/null || true
}

fail() {
  echo "[update] FAILED: $*"
  printf "failed %%s" "$TARGET" > "$STATUS"
  if [ -f "$PREV" ]; then
    echo "[update] rolling back to previous binary"
    mv -f "$PREV" "$BIN" || true
    restart_service >/dev/null 2>&1 || true
  fi
  exit 1
}

%s

health_ok() {
  curl -fsS --max-time 3 "$HEALTH" 2>/dev/null | grep -q "\"version\":\"$TARGET\""
}

wait_health() {
  i=0
  while [ $i -lt 40 ]; do
    health_ok && return 0
    i=$((i+1))
    sleep 1
  done
  return 1
}

echo "[update] $(date '+%%Y-%%m-%%d %%H:%%M:%%S') start, target $TARGET"

if command -v flock >/dev/null 2>&1; then
  exec 9>"%s"
  flock -n 9 || fail "another update is running"
fi

# 拿到锁之后才写 running，免得并发时把正在跑的那次标记覆盖掉
mark_running

curl -fsSL --retry 3 --retry-delay 2 -o "$NEW" "$URL" || fail "download error"

ACTUAL=$(sha256sum "$NEW" | awk '{print $1}')
if [ -z "$SHA" ]; then
  echo "[update] 清单里没有 sha256，改用 SHA256SUMS 校验"
  SUMS_FILE="$NEW.sums"
  if curl -fsSL --retry 2 -o "$SUMS_FILE" "$SUMS"; then
    WANT=$(awk '/studycheck-linux-/ {print $1; exit}' "$SUMS_FILE")
    [ -n "$WANT" ] || fail "SHA256SUMS 里没有可用条目"
    SHA="$WANT"
  else
    fail "拿不到 SHA256SUMS，拒绝跳过校验"
  fi
fi
[ "$ACTUAL" = "$SHA" ] || fail "sha256 mismatch: got $ACTUAL want $SHA"

chmod +x "$NEW" || fail "chmod error"
cp -f "$BIN" "$PREV" || fail "backup error"
mv -f "$NEW" "$BIN" || fail "replace error"

restart_service || echo "[update] 重启命令返回非 0，继续等待新版本起来"

if wait_health; then
  echo "[update] OK, running $TARGET"
  printf "ok %%s" "$TARGET" > "$STATUS"
  exit 0
fi

echo "[update] 新版本没有按时起来，再试一次重启"
restart_service >/dev/null 2>&1 || true
if wait_health; then
  echo "[update] OK (second try), running $TARGET"
  printf "ok %%s" "$TARGET" > "$STATUS"
  exit 0
fi

fail "health check timed out"
`,
		statusPath, binPath, newBin, binPath+".prev",
		os.Getpid(),
		s.listenPort(),
		m.Version, m.URL, m.SHA256,
		sumsURLFor(m.URL),
		s.restartCommand(),
		absPath(s.dataPath("update.lock")),
	)
}

// listenPort 从监听地址里取出端口，健康校验用 127.0.0.1 回环访问。
func (s *Server) listenPort() string {
	_, port, err := net.SplitHostPort(s.cfg.Addr)
	if err != nil {
		return "8080"
	}
	return port
}

// SHA256SUMS 地址：与二进制同目录
func sumsURLFor(binURL string) string {
	if i := strings.LastIndex(binURL, "/"); i > 0 {
		return binURL[:i] + "/SHA256SUMS"
	}
	return binURL
}

// autoUpdateLoop 可选的"全自动更新"：定时检查，有新版直接部署。
// 默认关闭（SC_AUTO_UPDATE=false），建议先手动跑通再打开。
func (s *Server) autoUpdateLoop() {
	ticker := time.NewTicker(6 * time.Hour)
	defer ticker.Stop()
	time.Sleep(2 * time.Minute) // 启动后先跑稳，别一上来就折腾
	for {
		if !markerIsFreshRunning(s.updateStatusPath()) {
			if st, err := s.checkUpdate(); err == nil && st.HasUpdate {
				log.Printf("自动更新：发现 %s（当前 %s），开始部署", st.LatestVer, version)
				if err := s.applyUpdate(); err != nil {
					log.Printf("自动更新失败: %v", err)
				}
			}
		}
		<-ticker.C
	}
}

func (s *Server) updateLogTail(n int) string {
	b, err := os.ReadFile(s.updateLogPath())
	if err != nil {
		return ""
	}
	lines := strings.Split(strings.TrimRight(string(b), "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

// —— HTTP handlers ——

func (s *Server) handleUpdateStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.buildStatus(""))
}

func (s *Server) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	st, _ := s.checkUpdate()
	writeJSON(w, http.StatusOK, st) // 失败时状态里带 error，前端直接展示
}

func (s *Server) handleUpdateApply(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.UpdateEnabled {
		writeErr(w, http.StatusForbidden, "update_disabled", "更新功能未启用")
		return
	}
	if err := s.applyUpdate(); err != nil {
		writeErr(w, http.StatusConflict, "update_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"started": true})
}

func (s *Server) handleUpdateLog(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"log":      s.updateLogTail(200),
		"last_run": readMarker(s.updateStatusPath()),
		"running":  markerIsFreshRunning(s.updateStatusPath()),
	})
}
