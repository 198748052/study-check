/* 研读 · Web 端
 * 纯原生实现，无构建步骤，随 Go 二进制一起嵌入发布。
 */
'use strict';

const LS = { token: 'sc_token', refresh: 'sc_refresh', user: 'sc_user' };

const state = {
  token: localStorage.getItem(LS.token) || '',
  refresh: localStorage.getItem(LS.refresh) || '',
  user: JSON.parse(localStorage.getItem(LS.user) || 'null'),
  today: null,
  stats: null,
  history: { items: [], offset: 0, done: false, loading: false },
  entries: { day: todayStr(), items: [] },
  report: { period: 'week', anchor: todayStr(), data: null },
  achievements: null,
  search: { q: '', items: [] },
  timer: null,
};

const $ = (id) => document.getElementById(id);
const el = (tag, cls, html) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (html !== undefined) n.innerHTML = html;
  return n;
};

/* ---------------- 工具 ---------------- */

function todayStr() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function fmtTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d)) return '—';
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function fmtDur(sec) {
  if (!sec || sec < 0) return '0 分钟';
  const h = Math.floor(sec / 3600);
  const m = Math.round((sec % 3600) / 60);
  if (h > 0) return m > 0 ? `${h} 小时 ${m} 分` : `${h} 小时`;
  return `${m} 分钟`;
}

function fmtMinutes(min) {
  if (!min) return '0';
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  if (h > 0) return `${h}<small>时</small>${m > 0 ? m + '<small>分</small>' : ''}`;
  return `${m}<small>分</small>`;
}

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function fmtDay(day) {
  const [y, m, d] = day.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  return { md: `${m}月${d}日`, week: WEEK[dt.getDay()] };
}

// 取图鉴权走服务端下发的 HttpOnly cookie（sc_media），
// 不再把 token 拼进 URL，避免泄漏到访问日志与 Referer。
// fullFor 把 /media/xxx_t.jpg 反推成原图 /media/xxx.jpg；原图 URL 原样返回。
function fullFor(thumbUrl) {
  if (!thumbUrl) return thumbUrl;
  const m = thumbUrl.match(/^(.*\/)([^/]+)_t\.(jpg|jpeg|png|webp)$/i);
  if (!m) return thumbUrl;
  return m[1] + m[2] + '.' + m[3].toLowerCase();
}

function media(url) {
  return url || '';
}

let toastTimer = null;
function toast(msg, ms = 2200) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

function setBusy(btn, busy, text) {
  if (!btn) return;
  if (busy) {
    btn.dataset.label = btn.textContent;
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span>${text || '处理中'}`;
  } else {
    btn.disabled = false;
    btn.textContent = btn.dataset.label || text || btn.textContent;
  }
}

/* ---------------- 网络 ---------------- */

class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function refreshToken() {
  if (!state.refresh) return false;
  const res = await fetch('/api/v1/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refresh_token: state.refresh }),
  });
  if (!res.ok) return false;
  const data = await res.json();
  state.token = data.token;
  state.refresh = data.refresh_token || state.refresh;
  localStorage.setItem(LS.token, state.token);
  localStorage.setItem(LS.refresh, state.refresh);
  return true;
}

async function api(path, opts = {}) {
  const { method = 'GET', json, form, retry = true } = opts;
  const headers = {};
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (form) {
    body = form;
  }
  // 让服务端按浏览器本地时区判定"今天"
  try { headers['X-Timezone'] = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (_) {}

  const res = await fetch(path, { method, headers, body });

  if (res.status === 401 && retry && state.refresh) {
    if (await refreshToken()) return api(path, { ...opts, retry: false });
    logout(true);
    throw new ApiError('登录已过期，请重新登录', 401, 'unauthorized');
  }

  if (res.status === 204) return null;

  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = null; }

  if (!res.ok) {
    const msg = (data && data.error && data.error.message) || `请求失败 (${res.status})`;
    throw new ApiError(msg, res.status, data && data.error && data.error.code);
  }
  return data;
}

/* ---------------- 登录 ---------------- */

let authMode = 'login';

function showAuthError(msg) {
  const p = $('auth-error');
  p.textContent = msg;
  p.hidden = !msg;
}

function switchAuthMode(mode) {
  authMode = mode;
  showAuthError('');
  $('login-form').classList.toggle('hidden', mode !== 'login');
  $('register-form').classList.toggle('hidden', mode !== 'register');
  $('auth-switch-text').textContent = mode === 'login' ? '还没有账号?' : '已经有账号?';
  $('auth-switch-btn').textContent = mode === 'login' ? '注册一个' : '去登录';
}

function enterApp(user) {
  state.user = user;
  localStorage.setItem(LS.user, JSON.stringify(user));
  $('auth-view').classList.add('hidden');
  $('app-view').classList.remove('hidden');
  $('me-nick').textContent = user.nickname || user.username;
  $('me-username').textContent = '@' + user.username;
  $('me-avatar').textContent = (user.nickname || user.username || '研').slice(0, 1);
  $('me-nickname').value = user.nickname || '';
  $('me-goal').value = user.daily_goal_min || 0;
  $('entry-day').value = state.entries.day;
  loadAll();
}

function logout(silent) {
  const token = state.token;
  const refresh = state.refresh;
  if (token && refresh) {
    fetch('/api/v1/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ refresh_token: refresh }),
    }).catch(() => {});
  }
  state.token = '';
  state.refresh = '';
  state.user = null;
  state.today = null;
  state.stats = null;
  state.history = { items: [], offset: 0, done: false, loading: false };
  Object.values(LS).forEach((k) => localStorage.removeItem(k));
  clearInterval(state.timer);
  $('app-view').classList.add('hidden');
  $('auth-view').classList.remove('hidden');
  switchAuthMode('login');
  if (!silent) toast('已退出登录');
}

$('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = e.target.querySelector('button[type=submit]');
  showAuthError('');
  setBusy(btn, true, '登录中');
  try {
    const data = await api('/api/v1/auth/login', {
      method: 'POST',
      json: { username: $('login-user').value.trim(), password: $('login-pass').value },
      retry: false,
    });
    state.token = data.token;
    state.refresh = data.refresh_token || '';
    localStorage.setItem(LS.token, state.token);
    localStorage.setItem(LS.refresh, state.refresh);
    enterApp(data.user);
  } catch (err) {
    showAuthError(err.message || '登录失败');
  } finally {
    setBusy(btn, false);
  }
});

$('register-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = e.target.querySelector('button[type=submit]');
  showAuthError('');
  setBusy(btn, true, '注册中');
  try {
    const data = await api('/api/v1/auth/register', {
      method: 'POST',
      json: {
        username: $('reg-user').value.trim(),
        password: $('reg-pass').value,
        nickname: $('reg-nick').value.trim(),
      },
      retry: false,
    });
    state.token = data.token;
    state.refresh = data.refresh_token || '';
    localStorage.setItem(LS.token, state.token);
    localStorage.setItem(LS.refresh, state.refresh);
    enterApp(data.user);
  } catch (err) {
    showAuthError(err.message || '注册失败');
  } finally {
    setBusy(btn, false);
  }
});

$('auth-switch-btn').addEventListener('click', () => switchAuthMode(authMode === 'login' ? 'register' : 'login'));

/* ---------------- 数据加载 ---------------- */

async function loadAll() {
  await Promise.all([
    loadToday(), loadTodayEntries(), loadStats(), loadHistory(true),
    loadReport(), loadAchievements(),
  ]);
  loadVersion();
  loadUsers();
  loadUpdateStatus();
  loadSystemStatus();
  loadBackups();
}

/* ---------------- 系统更新（仅管理员） ---------------- */

const updateState = { status: null, timer: null };

function renderUpdateCard(st) {
  const card = $('update-card');
  if (!card) return;
  const isAdmin = !!(state.user && state.user.is_admin);
  card.classList.toggle('hidden', !isAdmin);
  if (!isAdmin) return;

  updateState.status = st;
  const ver = st.current_version ? `v${st.current_version}` : '—';
  $('update-version').textContent = ver;

  const apply = $('btn-update-apply');
  const check = $('btn-update-check');
  if (st.running) {
    $('update-hint').textContent = '正在更新，页面会自动刷新…';
    apply.disabled = true;
    check.disabled = true;
    return;
  }
  check.disabled = false;

  if (!st.enabled) {
    $('update-hint').textContent = '在线更新未启用（服务端 SC_UPDATE_ENABLED=false）';
    apply.disabled = true;
    return;
  }
  if (st.error) {
    $('update-hint').textContent = st.error;
    apply.disabled = true;
    return;
  }
  if (st.has_update) {
    $('update-hint').textContent = `发现新版本 v${st.latest_version}（当前 v${st.current_version}，${st.arch || ''}）`;
    apply.disabled = false;
    apply.textContent = `更新到 v${st.latest_version}`;
  } else if (st.latest_version) {
    $('update-hint').textContent = `已是最新版本 v${st.current_version}`;
    apply.disabled = true;
    apply.textContent = '立即更新';
  } else {
    $('update-hint').textContent = '更新源：GitHub Release，点「检查更新」看看有没有新版';
    apply.disabled = true;
    apply.textContent = '立即更新';
  }
}

async function loadUpdateStatus() {
  if (!(state.user && state.user.is_admin)) {
    $('update-card') && $('update-card').classList.add('hidden');
    return;
  }
  try {
    const st = await api('/api/v1/system/update');
    renderUpdateCard(st);
    // 缓存超过 6 小时就悄悄查一次，省得用户每次都要手动点
    const stale = !st.checked_at || Date.now() - Date.parse(st.checked_at) > 6 * 3600 * 1000;
    if (st.enabled && !st.running && stale) {
      try {
        renderUpdateCard(await api('/api/v1/system/update/check', { method: 'POST' }));
      } catch (_) { /* 静默失败，等用户手动点 */ }
    }
  } catch (err) {
    if (err.status !== 403) toast(err.message || '读取更新状态失败');
  }
}

async function checkUpdate() {
  const btn = $('btn-update-check');
  setBusy(btn, true, '检查中');
  try {
    const st = await api('/api/v1/system/update/check', { method: 'POST' });
    renderUpdateCard(st);
    toast(st.error ? st.error : (st.has_update ? `发现新版本 v${st.latest_version}` : '已是最新版本'));
  } catch (err) {
    toast(err.message || '检查失败');
  } finally {
    setBusy(btn, false, '检查更新');
  }
}

async function applyUpdate() {
  const st = updateState.status;
  if (!st) return;
  if (!confirm(`更新到 v${st.latest_version}？服务会重启十几秒，数据不受影响。`)) return;
  const btn = $('btn-update-apply');
  setBusy(btn, true, '更新中');
  $('update-log').classList.remove('hidden');
  try {
    await api('/api/v1/system/update', { method: 'POST' });
    toast('已开始更新，服务重启后自动刷新');
    pollUpdate(0);
  } catch (err) {
    toast(err.message || '更新失败');
    setBusy(btn, false, '立即更新');
    $('update-log').textContent = err.message || '';
  }
}

// 更新过程中服务会短暂失联，这里轮询到新版本上线为止
function pollUpdate(n) {
  clearTimeout(updateState.timer);
  if (n > 90) {
    $('update-hint').textContent = '更新超时，请手动刷新页面检查版本';
    $('btn-update-check').disabled = false;
    return;
  }
  updateState.timer = setTimeout(async () => {
    try {
      const st = await api('/api/v1/system/update');
      renderUpdateCard(st);
      loadLog();
      if (st.current_version && st.latest_version && st.current_version === st.latest_version) {
        toast('更新完成，正在刷新');
        setTimeout(() => location.reload(), 800);
        return;
      }
      if (!st.running && st.last_run === 'failed') {
        $('update-hint').textContent = '更新失败，已回滚到之前版本，点「更新日志」看原因';
        $('btn-update-check').disabled = false;
        setBusy($('btn-update-apply'), false, '立即更新');
        loadLog();
        return;
      }
    } catch (_) { /* 服务重启中，继续等 */ }
    pollUpdate(n + 1);
  }, 2500);
}

async function loadLog() {
  try {
    const d = await api('/api/v1/system/update/log');
    if (d.log) {
      const box = $('update-log');
      box.classList.remove('hidden');
      box.textContent = d.log;
      box.scrollTop = box.scrollHeight;
    }
  } catch (_) { /* 忽略 */ }
}

/* ---------------- 系统状态与备份（仅管理员） ---------------- */

function humanBytes(n) {
  if (!n || n < 0) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  return (n / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + ' ' + u[i];
}

function shortTime(s) {
  if (!s) return '—';
  const d = new Date(s);
  if (isNaN(d)) return s;
  const p = (x) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

async function loadSystemStatus() {
  const card = $('status-card');
  if (!card) return;
  if (!(state.user && state.user.is_admin)) {
    card.classList.add('hidden');
    return;
  }
  card.classList.remove('hidden');
  let st;
  try {
    st = await api('/api/v1/system/status');
  } catch (err) {
    if (err.status !== 403) toast(err.message || '读取系统状态失败');
    card.classList.add('hidden');
    return;
  }
  const s = st.storage || {};
  const c = st.counts || {};
  const disk = s.disk || {};
  $('status-uptime').textContent = `运行 ${st.uptime || '—'}`;
  const rows = [
    ['版本', `v${st.version || '—'}`],
    ['用户 / 打卡 / 读物', `${c.users || 0} / ${c.checkins || 0} / ${c.entries || 0}`],
    ['照片', `${s.photos_files || 0} 张 · ${humanBytes(s.photos_bytes)}`],
    ['数据库', humanBytes(s.db_bytes)],
    ['备份', `${s.backups_files || 0} 份 · ${humanBytes(s.backups_bytes)}`],
    ['磁盘可用', disk.free ? `${humanBytes(disk.free)} / ${humanBytes(disk.total)}` : '—'],
    ['最近备份', s.last_backup ? shortTime(s.last_backup_at) : '还没有备份'],
  ];
  $('status-list').innerHTML = rows
    .map(([k, v]) => `<li><span class="muted">${k}</span><b>${v}</b></li>`)
    .join('');
}

async function loadBackups() {
  const card = $('backup-card');
  if (!card) return;
  if (!(state.user && state.user.is_admin)) {
    card.classList.add('hidden');
    return;
  }
  card.classList.remove('hidden');
  let d;
  try {
    d = await api('/api/v1/system/backups');
  } catch (err) {
    if (err.status !== 403) toast(err.message || '读取备份列表失败');
    card.classList.add('hidden');
    return;
  }
  const list = d.backups || [];
  $('backup-meta').textContent = list.length ? `共 ${list.length} 份` : '';
  $('backup-hint').textContent = d.enabled
    ? `每天 ${d.at || '03:30'} 自动备份，保留最近 ${d.keep || 14} 份（${d.dir || ''}）`
    : '自动备份已关闭（SC_BACKUP_ENABLED=false），可手动备份';
  const box = $('backup-list');
  if (!list.length) {
    box.innerHTML = '<li class="backup-empty">还没有备份，点「立即备份」生成第一份</li>';
    return;
  }
  box.innerHTML = list.map((b) => `
    <li>
      <span class="bk-name">${shortTime(b.created_at)} · ${humanBytes(b.size)}</span>
      <span class="bk-ops">
        <button data-bk-act="download" data-bk="${b.name}" title="下载到本地">下载</button>
        <button data-bk-act="restore" data-bk="${b.name}" title="用这份备份覆盖当前数据">恢复</button>
        <button data-bk-act="delete" data-bk="${b.name}" title="删除这份备份">删除</button>
      </span>
    </li>`).join('');
}

$('backup-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-bk-act]');
  if (!btn) return;
  const name = btn.dataset.bk;
  const act = btn.dataset.bkAct;
  if (act === 'download') {
    // 直接走浏览器下载：cookie 已带鉴权，不再拼 token
    const a = document.createElement('a');
    a.href = `/api/v1/system/backups/${encodeURIComponent(name)}`;
    a.download = name;
    a.click();
    return;
  }
  if (act === 'delete') {
    if (!confirm(`删除备份 ${name}？`)) return;
    try {
      await api(`/api/v1/system/backups/${encodeURIComponent(name)}`, { method: 'DELETE' });
      toast('已删除');
      loadBackups();
      loadSystemStatus();
    } catch (err) {
      toast(err.message || '删除失败');
    }
    return;
  }
  if (act === 'restore') {
    if (!confirm(`用备份 ${name} 覆盖当前数据？\n恢复前会自动备份当前库，所有人需要重新登录。`)) return;
    setBusy(btn, true, '恢复中');
    try {
      const r = await api(`/api/v1/system/backups/${encodeURIComponent(name)}/restore`, { method: 'POST' });
      toast(r.message || '已恢复');
      setTimeout(() => location.reload(), 1200);
    } catch (err) {
      toast(err.message || '恢复失败');
      setBusy(btn, false, '恢复');
    }
  }
});

$('btn-backup-now').addEventListener('click', async (e) => {
  const btn = e.currentTarget;
  setBusy(btn, true, '备份中');
  try {
    const info = await api('/api/v1/system/backups', { method: 'POST' });
    toast(`已备份 ${info.name}（${humanBytes(info.size)}）`);
    loadBackups();
    loadSystemStatus();
  } catch (err) {
    toast(err.message || '备份失败');
  } finally {
    setBusy(btn, false, '立即备份');
  }
});

$('btn-cleanup').addEventListener('click', async (e) => {
  const btn = e.currentTarget;
  if (!confirm('清理数据库里已经没有引用的照片文件？只删 10 分钟前的文件，正在上传的不受影响。')) return;
  setBusy(btn, true, '清理中');
  try {
    const r = await api('/api/v1/system/cleanup', { method: 'POST' });
    toast(r.removed ? `清理 ${r.removed} 个文件，释放 ${humanBytes(r.freed_bytes)}` : '没有无引用照片');
    loadSystemStatus();
  } catch (err) {
    toast(err.message || '清理失败');
  } finally {
    setBusy(btn, false, '清理无引用照片');
  }
});

async function loadVersion() {
  try {
    const h = await api('/api/v1/health', { retry: false });
    $('version-line').textContent = `server ${h.version} · ${h.status}`;
  } catch (_) { /* 忽略 */ }
}

async function loadToday() {
  const data = await api('/api/v1/checkins/today');
  state.today = data;
  renderToday();
}

function renderToday() {
  const data = state.today;
  if (!data) return;
  const c = data.checkin;
  const sessions = data.sessions || [];
  const d = fmtDay(data.day);
  $('today-label').textContent = `${d.md} ${d.week}`;

  const pill = $('today-state');
  const btnIn = $('btn-checkin');
  const btnOut = $('btn-checkout');
  const note = $('today-note');
  const photos = $('today-photos');
  photos.innerHTML = '';

  if (data.state === 'not_started') {
    pill.textContent = '未开始';
    pill.className = 'pill idle';
    $('today-timer').textContent = '—';
    note.textContent = '进馆拍一张，开始计时。中间可以出馆再进馆，分段计时。';
    btnIn.disabled = false;
    btnIn.textContent = '进馆打卡';
    btnOut.disabled = true;
    btnOut.textContent = '出馆打卡';
  } else if (data.state === 'in_library') {
    pill.textContent = '在馆中';
    pill.className = 'pill';
    btnIn.disabled = true;
    btnIn.textContent = '已进馆';
    btnOut.disabled = false;
    btnOut.textContent = '出馆打卡';
    const open = sessions.filter((x) => !x.out_at).pop();
    note.textContent = open && open.in_note
      ? `${open.in_note} · 进馆于 ${fmtTime(open.in_at)}`
      : `进馆于 ${fmtTime(open && open.in_at)}`;
    tickTimer();
  } else {
    pill.textContent = '已完成';
    pill.className = 'pill ok';
    $('today-timer').textContent = fmtDur(data.total_sec || (c ? c.duration_sec : 0));
    // 一天多段时，出馆后还能再开一段
    btnIn.disabled = false;
    btnIn.textContent = '再进馆';
    btnOut.disabled = true;
    btnOut.textContent = '今日已出馆';
    const parts = [];
    if (sessions.length > 1) parts.push(`${sessions.length} 段`);
    if (c && c.in_at) parts.push(`第一次 ${fmtTime(c.in_at)}`);
    if (c && c.out_at) parts.push(`最后一次 ${fmtTime(c.out_at)}`);
    const notes = sessions.map((x) => x.out_note || x.in_note).filter(Boolean).slice(-1).join('');
    note.textContent = parts.join('  ·  ') + (notes ? `  ·  ${notes}` : '');
  }

  // 照片墙：优先展示最后一段的进出馆照片
  const last = sessions[sessions.length - 1] || c;
  if (last) {
    [['进馆', last.in_thumb_url || last.in_photo_url, last.in_at], ['出馆', last.out_thumb_url || last.out_photo_url, last.out_at]].forEach(([label, url, at]) => {
      if (!url) return;
      const fig = el('figure');
      const img = el('img');
      img.src = media(url);
      img.loading = 'lazy';
      img.alt = label;
      img.addEventListener('click', () => openLightbox(fullFor(url), url));
      fig.appendChild(img);
      fig.appendChild(el('figcaption', null, `${label} ${fmtTime(at)}`));
      photos.appendChild(fig);
    });
  }

  renderTodaySessions(sessions);
}

// 今日分段列表：每段都能改时间/删掉
function renderTodaySessions(sessions) {
  const box = $('today-sessions');
  if (!box) return;
  box.innerHTML = '';
  $('today-sessions-meta').textContent = sessions.length
    ? `${sessions.length} 段 · 共 ${fmtDur(state.today.total_sec || 0)}`
    : '';
  if (!sessions.length) {
    box.appendChild(el('div', 'empty', '今天还没有进馆，点上面的「进馆打卡」开始'));
    return;
  }
  sessions.forEach((ss, i) => {
    box.appendChild(segRow(ss, i + 1, {
      onEdit: () => openSegModal(ss),
      onDelete: () => deleteSession(ss),
    }));
  });
}

// 一段的展示行：序号 / 时间区间 / 时长 / 备注 / 操作
function segRow(ss, idx, { onEdit, onDelete, onOpen } = {}) {
  const row = el('div', 'seg-row');

  const head = el('div', 'seg-head');
  const badge = el('span', 'seg-idx', idx ? String(idx) : '·');
  head.appendChild(badge);
  const range = ss.out_at
    ? `${fmtTime(ss.in_at)} — ${fmtTime(ss.out_at)}`
    : `${fmtTime(ss.in_at)} — 进行中`;
  head.appendChild(el('b', 'seg-range', range));
  head.appendChild(el('span', 'seg-dur', ss.out_at ? fmtDur(ss.duration_sec) : fmtDur(liveSecOf(ss))));
  row.appendChild(head);

  const notes = [ss.in_note, ss.out_note].filter(Boolean).join(' · ');
  const line = [notes, ss.in_place || ss.out_place].filter(Boolean).join(' · ');
  if (line) row.appendChild(el('p', 'seg-note', escapeHtml(line)));

  const ops = el('div', 'seg-ops');
  if (onOpen) {
    const b = el('button', 'seg-op', '查看当天');
    b.type = 'button';
    b.addEventListener('click', (e) => { e.stopPropagation(); onOpen(); });
    ops.appendChild(b);
  }
  if (onEdit) {
    const b = el('button', 'seg-op', '修正');
    b.type = 'button';
    b.addEventListener('click', (e) => { e.stopPropagation(); onEdit(); });
    ops.appendChild(b);
  }
  if (onDelete) {
    const b = el('button', 'seg-op danger', '删除');
    b.type = 'button';
    b.addEventListener('click', (e) => { e.stopPropagation(); onDelete(); });
    ops.appendChild(b);
  }
  if (ops.children.length) row.appendChild(ops);

  const thumbs = el('div', 'day-thumbs');
  [[ss.in_thumb_url, ss.in_photo_url], [ss.out_thumb_url, ss.out_photo_url]].forEach(([t, f]) => {
    if (!t && !f) return;
    const img = el('img');
    img.src = media(t || f);
    img.loading = 'lazy';
    img.alt = '打卡照片';
    img.addEventListener('click', (e) => { e.stopPropagation(); openLightbox(fullFor(f || t), t || f); });
    thumbs.appendChild(img);
  });
  if (thumbs.children.length) row.appendChild(thumbs);
  return row;
}

// 未结束的段：从进馆时刻算到此刻
function liveSecOf(ss) {
  if (!ss || !ss.in_at) return 0;
  return Math.max(0, Math.round((Date.now() - new Date(ss.in_at).getTime()) / 1000));
}

function todayLiveSeconds() {
  const d = state.today;
  if (!d) return 0;
  let total = 0;
  (d.sessions || []).forEach((ss) => {
    total += ss.out_at ? (ss.duration_sec || 0) : liveSecOf(ss);
  });
  return total;
}

function tickTimer() {
  clearInterval(state.timer);
  const update = () => {
    const d = state.today;
    if (!d || d.state !== 'in_library') return;
    $('today-timer').textContent = fmtDur(todayLiveSeconds());
  };
  update();
  state.timer = setInterval(update, 1000);
}

async function loadStats() {
  const s = await api('/api/v1/stats');
  state.stats = s;
  renderStats();
  maybeCelebrateGoal();
}

// 当天首次达到目标时弹一次提示，之后同一天不再打扰
function maybeCelebrateGoal() {
  const s = state.stats;
  if (!s || !s.daily_goal_min) return;
  const today = Number(s.today_minutes || 0);
  if (today < s.daily_goal_min) return;
  const key = 'sc_goal_celebrated';
  if (localStorage.getItem(key) === s.today) return;
  localStorage.setItem(key, s.today);
  toast(`今日目标达成：${today} 分钟`, 3200);
}

function renderStats() {
  const s = state.stats;
  if (!s) return;
  $('streak-label').textContent = s.current_streak
    ? `连续 ${s.current_streak} 天 · 目标 ${s.daily_goal_min} 分钟`
    : `目标 ${s.daily_goal_min} 分钟`;

  const grid = $('stat-grid');
  grid.innerHTML = '';
  const items = [
    ['连续天数', `${s.current_streak}<small>天</small>`],
    ['最长连续', `${s.longest_streak}<small>天</small>`],
    ['本月', `${s.month_days}<small>天</small> · ${fmtMinutes(s.month_minutes)}`],
    ['本周', `${s.week_days}<small>天</small> · ${fmtMinutes(s.week_minutes)}`],
    ['累计时长', fmtMinutes(s.total_minutes)],
    ['日均', fmtMinutes(s.avg_minutes)],
  ];
  items.forEach(([k, v]) => {
    const box = el('div', 'stat');
    box.appendChild(el('div', 'k', k));
    box.appendChild(el('div', 'v', v));
    grid.appendChild(box);
  });

  const map = {};
  (s.heatmap || []).forEach((c) => { map[c.day] = c.minutes || 0; });

  const end = new Date(`${s.today}T00:00:00`);
  const start = new Date(end);
  start.setDate(start.getDate() - 181);

  const heat = $('heatmap');
  heat.innerHTML = '';
  const offset = (start.getDay() + 6) % 7; // 周一 = 0
  const p = (n) => String(n).padStart(2, '0');
  for (let i = 0; i < 182; i++) {
    const d = new Date(start);
    d.setDate(d.getDate() + i);
    const key = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
    const min = map[key] || 0;
    let lv = 0;
    if (min > 0) {
      if (min < 30) lv = 1;
      else if (min < 60) lv = 2;
      else if (min < 120) lv = 3;
      else if (min < 180) lv = 4;
      else lv = 5;
    }
    const cell = el('i');
    cell.dataset.lv = String(lv);
    cell.title = `${key} · ${min} 分钟`;
    if (i === 0 && offset > 0) cell.style.gridRowStart = String(offset + 1);
    heat.appendChild(cell);
  }
  const wrap = heat.parentElement;
  if (wrap) wrap.scrollLeft = wrap.scrollWidth;
  $('heat-legend').textContent = '颜色越深，当天在馆越久';
  renderGoal(s);
}

function renderGoal(s) {
  const fill = $('goal-fill');
  if (!fill) return;
  const today = Number(s.today_minutes || 0);
  const goal = Number(s.daily_goal_min || 0);
  const ratio = goal > 0 ? Math.min(1, today / goal) : 0;
  fill.style.width = `${Math.round(ratio * 100)}%`;
  fill.classList.toggle('done', goal > 0 && today >= goal);
  $('goal-text').textContent = goal > 0 ? `${today} / ${goal} 分钟` : `${today} 分钟`;
  $('goal-hint').textContent = goal <= 0
    ? '在「我的」里设置每日目标'
    : today >= goal
      ? '今日目标已达成，保持住'
      : (s.total_days ? `还差 ${goal - today} 分钟，继续加油` : '完成一次打卡开始累计');
}

/* ---------------- 周报 / 月报 ---------------- */

function shiftAnchor(anchor, period, delta) {
  const [y, m, d] = anchor.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  if (period === 'month') dt.setMonth(dt.getMonth() + delta);
  else dt.setDate(dt.getDate() + delta * 7);
  const p = (n) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`;
}

async function loadReport() {
  const rp = state.report;
  try {
    const data = await api(`/api/v1/report?period=${rp.period}&anchor=${rp.anchor}`);
    rp.data = data;
    renderReport();
  } catch (err) {
    console.warn('report', err);
  }
}

function renderReport() {
  const rp = state.report;
  const d = rp.data;
  document.querySelectorAll('#report-seg .seg-btn').forEach((b) => {
    b.classList.toggle('active', b.dataset.period === rp.period);
  });
  if (!d) return;
  $('report-label').textContent = d.label || `${d.start} ~ ${d.end}`;
  const nextDisabled = d.end >= todayStr();
  $('report-next').disabled = nextDisabled;
  $('report-next').style.opacity = nextDisabled ? '0.4' : '1';

  const box = $('report-body');
  box.innerHTML = '';

  const grid = el('div', 'report-grid');
  const tiles = [
    ['打卡', `${d.days}<small>天</small>`],
    ['时长', fmtMinutes(d.minutes)],
    ['日均', fmtMinutes(d.avg_minutes)],
    ['达标', d.goal_minutes > 0 ? `${d.goal_days}<small>天</small> · ${d.goal_rate}%` : '未设目标'],
  ];
  tiles.forEach(([k, v]) => {
    const t = el('div', 'report-tile');
    t.appendChild(el('div', 'k', k));
    t.appendChild(el('div', 'v', v));
    grid.appendChild(t);
  });
  box.appendChild(grid);

  const delta = Number(d.delta_minutes || 0);
  const cmp = el('p', 'report-cmp');
  const cls = delta > 0 ? 'up' : delta < 0 ? 'down' : '';
  const sign = delta > 0 ? '+' : '';
  cmp.innerHTML = `上一期 ${d.prev_days} 天 · ${fmtMinutes(d.prev_minutes)}，本期 <b class="${cls}">${sign}${delta} 分钟</b>`;
  box.appendChild(cmp);

  if (d.best_day && d.best_day.minutes > 0) {
    box.appendChild(el('p', 'report-best',
      `最佳一天：${fmtDay(d.best_day.day).md} · ${fmtMinutes(d.best_day.minutes)}`));
  }

  const maxMin = Math.max(1, ...d.by_weekday.map((x) => x.minutes));
  const bars = el('div', 'week-bars');
  d.by_weekday.forEach((x) => {
    const col = el('div', 'week-bar');
    const track = el('div', 'week-track');
    const fill = el('i');
    fill.style.height = `${Math.max(3, Math.round((x.minutes / maxMin) * 100))}%`;
    if (x.minutes <= 0) fill.classList.add('zero');
    track.appendChild(fill);
    col.appendChild(track);
    col.appendChild(el('span', null, x.label));
    col.title = `周${x.label} · ${x.days} 天 · ${x.minutes} 分钟`;
    bars.appendChild(col);
  });
  box.appendChild(bars);

  if (d.places && d.places.length) {
    const tags = el('div', 'place-tags');
    d.places.forEach((p) => {
      tags.appendChild(el('span', 'place-tag', `${escapeHtml(p.name)} · ${p.days} 天`));
    });
    box.appendChild(tags);
  }
}

document.querySelectorAll('#report-seg .seg-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    if (state.report.period === btn.dataset.period) return;
    state.report.period = btn.dataset.period;
    state.report.anchor = todayStr();
    loadReport();
  });
});

$('report-prev').addEventListener('click', () => {
  state.report.anchor = shiftAnchor(state.report.anchor, state.report.period, -1);
  loadReport();
});

$('report-next').addEventListener('click', () => {
  const anchor = shiftAnchor(state.report.anchor, state.report.period, 1);
  if (anchor > todayStr()) return;
  state.report.anchor = anchor;
  loadReport();
});

/* ---------------- 成就 ---------------- */

async function loadAchievements() {
  try {
    state.achievements = await api('/api/v1/achievements');
    renderAchievements();
  } catch (err) {
    console.warn('achievements', err);
  }
}

function renderAchievements() {
  const a = state.achievements;
  const box = $('ach-grid');
  if (!a || !box) return;
  box.innerHTML = '';
  const items = a.achievements || [];
  const unlocked = items.filter((x) => x.unlocked).length;
  $('ach-summary').textContent = `已解锁 ${unlocked} / ${items.length}`;

  items.forEach((x) => {
    const card = el('div', 'ach' + (x.unlocked ? ' on' : ''));
    const badge = el('div', 'ach-badge', x.unlocked ? '★' : '☆');
    card.appendChild(badge);
    const info = el('div', 'ach-info');
    info.appendChild(el('b', null, x.title));
    info.appendChild(el('span', null, x.desc));
    const prog = el('div', 'ach-prog');
    const ratio = x.target > 0 ? Math.min(1, x.progress / x.target) : 0;
    const bar = el('i');
    bar.style.width = `${Math.round(ratio * 100)}%`;
    prog.appendChild(bar);
    info.appendChild(prog);
    info.appendChild(el('em', null, x.unlocked
      ? `已达成${x.achieved_on ? ' · ' + x.achieved_on : ''}`
      : `${x.progress} / ${x.target}`));
    card.appendChild(info);
    box.appendChild(card);
  });
}

async function loadHistory(reset) {
  const h = state.history;
  if (h.loading || (h.done && !reset)) return;
  h.loading = true;
  if (reset) {
    h.items = [];
    h.offset = 0;
    h.done = false;
  }
  try {
    const data = await api(`/api/v1/checkins?limit=20&offset=${h.offset}`);
    const items = data.items || [];
    h.items = h.items.concat(items);
    h.offset += items.length;
    if (items.length < 20) h.done = true;
    renderHistory();
  } finally {
    h.loading = false;
  }
}

function renderHistory() {
  const box = $('history-list');
  const items = state.history.items;
  box.innerHTML = '';
  if (!items.length) {
    box.appendChild(el('div', 'empty', '还没有打卡记录'));
    return;
  }
  items.forEach((c) => {
    const d = fmtDay(c.day);
    const row = el('div', 'day-row');
    row.dataset.day = c.day;

    const dateBox = el('div', 'day-date');
    dateBox.appendChild(el('b', null, d.md));
    dateBox.appendChild(el('span', null, d.week));
    row.appendChild(dateBox);

    const body = el('div', 'day-body');
    const dur = c.duration_sec ? `<span class="day-dur">${fmtDur(c.duration_sec)}</span>` : '<span class="day-dur">进行中</span>';
    body.appendChild(el('div', 'day-times',
      `${fmtTime(c.in_at)} — ${c.out_at ? fmtTime(c.out_at) : '未出馆'}　${dur}`));
    const note = [c.in_note, c.out_note].filter(Boolean).join(' · ');
    if (note) body.appendChild(el('p', 'day-note', escapeHtml(note)));

    // 点时间行展开当天分段：一天多段时能逐段修正
    const times = body.firstChild;
    times.classList.add('day-times-clickable');
    times.title = '点开看看这一天的分段';
    const segBox = el('div', 'day-sessions hidden');
    times.addEventListener('click', async () => {
      if (!segBox.classList.contains('hidden')) {
        segBox.classList.add('hidden');
        return;
      }
      segBox.classList.remove('hidden');
      if (segBox.dataset.loaded === '1') return;
      segBox.innerHTML = '<p class="muted small">加载中…</p>';
      try {
        const data = await api(`/api/v1/sessions?day=${c.day}`);
        segBox.innerHTML = '';
        (data.items || []).forEach((ss, i) => {
          segBox.appendChild(segRow(ss, i + 1, {
            onEdit: () => openSegModal(ss),
            onDelete: () => deleteSession(ss),
          }));
        });
        segBox.dataset.loaded = '1';
      } catch (err) {
        segBox.innerHTML = `<p class="muted small">${escapeHtml(err.message || '加载失败')}</p>`;
      }
    });
    body.appendChild(segBox);

    const thumbs = el('div', 'day-thumbs');
    [c.in_thumb_url, c.out_thumb_url].filter(Boolean).forEach((u) => {
      const img = el('img');
      img.src = media(u);
      img.loading = 'lazy';
      img.alt = '打卡照片';
      img.addEventListener('click', () => openLightbox(fullFor(u), u));
      thumbs.appendChild(img);
    });
    if (thumbs.children.length) body.appendChild(thumbs);
    row.appendChild(body);

    const del = el('button', 'day-del', '&times;');
    del.type = 'button';
    del.title = c.out_at ? '删除这条打卡' : '放弃这次进馆记录';
    del.setAttribute('aria-label', '删除记录');
    del.addEventListener('click', async (e) => {
      e.stopPropagation();
      await deleteCheckin(c.id, c.day);
    });
    row.appendChild(del);

    box.appendChild(row);
  });
}

async function deleteCheckin(id, day) {
  const ok = confirm('删除这条打卡记录？照片和时长会一起删掉，不能恢复。');
  if (!ok) return;
  try {
    await api(`/api/v1/checkins/${id}`, { method: 'DELETE' });
    state.history.items = state.history.items.filter((x) => x.id !== id);
    renderHistory();
    toast('已删除');
    if (day === todayStr()) loadToday().catch(() => {});
    loadStats().catch(() => {});
  } catch (err) {
    toast(err.message || '删除失败');
  }
}

/* ---------------- 今日随手记 / 读物 ---------------- */

async function loadTodayEntries() {
  const data = await api(`/api/v1/entries?day=${state.entries.day || todayStr()}&limit=60`);
  state.entries.items = data.items || [];
  renderEntries(true);
}

async function loadEntries(day) {
  state.entries.day = day;
  const data = await api(`/api/v1/entries?day=${day}&limit=60`);
  state.entries.items = data.items || [];
  renderEntries(false);
}

function renderEntries(todayOnly) {
  const items = state.entries.items;
  const grids = [];
  if (todayOnly) grids.push($('today-entries'));
  grids.push($('entry-grid'));

  grids.forEach((grid) => {
    grid.innerHTML = '';
    items.forEach((it) => {
      if (it.photo_url) {
        const box = el('div', 'thumb');
        const img = el('img');
        img.src = media(it.thumb_url || it.photo_url);
        img.loading = 'lazy';
        img.alt = it.note || '照片';
        img.addEventListener('click', () => openLightbox(it.photo_url, it.thumb_url));
        box.appendChild(img);
        if (!todayOnly) {
          const del = el('button', 'del', '&times;');
          del.title = '删除';
          del.addEventListener('click', (e) => {
            e.stopPropagation();
            deleteEntry(it.id);
          });
          box.appendChild(del);
        }
        grid.appendChild(box);
      } else if (it.note) {
        grid.appendChild(el('div', 'thumb note-only', escapeHtml(it.note)));
      }
    });
  });

  if (todayOnly && items.length) {
    $('btn-add-entry').textContent = '继续添加';
  }
}

async function deleteEntry(id) {
  if (!confirm('删除这条记录？')) return;
  try {
    await api(`/api/v1/entries/${id}`, { method: 'DELETE' });
    state.entries.items = state.entries.items.filter((x) => x.id !== id);
    renderEntries(state.entries.day === todayStr());
    toast('已删除');
  } catch (err) {
    toast(err.message || '删除失败');
  }
}

/* ---------------- 弹层 ---------------- */

const modal = {
  files: [],
  onSubmit: null,
};

function openModal({ title, submitText, multiple, withPlace, onSubmit }) {
  $('modal-title').textContent = title;
  $('modal-submit').textContent = submitText;
  $('modal-submit').dataset.label = submitText;
  $('modal-submit').disabled = false;
  $('modal-note').value = '';
  $('modal-place').value = '';
  $('modal-error').hidden = true;
  $('photo-preview').innerHTML = '';
  $('photo-input').value = '';
  $('photo-input').multiple = !!multiple;
  $('place-wrap').classList.toggle('hidden', !withPlace);
  $('photo-picker').classList.toggle('hidden', false);
  modal.files = [];
  modal.onSubmit = onSubmit;
  $('modal').classList.remove('hidden');
}

function closeModal() {
  $('modal').classList.add('hidden');
  modal.files = [];
  modal.onSubmit = null;
}

$('modal-close').addEventListener('click', closeModal);
$('modal').addEventListener('click', (e) => {
  if (e.target === $('modal')) closeModal();
});

$('photo-trigger').addEventListener('click', () => $('photo-input').click());

$('photo-input').addEventListener('change', (e) => {
  const files = Array.from(e.target.files || []).slice(0, 12);
  modal.files = files;
  const preview = $('photo-preview');
  preview.innerHTML = '';
  files.forEach((f) => {
    const img = el('img');
    img.src = URL.createObjectURL(f);
    img.alt = f.name;
    preview.appendChild(img);
  });
});

$('modal-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  if (!modal.onSubmit) return;
  const btn = $('modal-submit');
  $('modal-error').hidden = true;
  setBusy(btn, true, '上传中');
  try {
    await modal.onSubmit({
      files: modal.files,
      note: $('modal-note').value.trim(),
      place: $('modal-place').value.trim(),
    });
    closeModal();
  } catch (err) {
    const p = $('modal-error');
    p.textContent = err.message || '提交失败';
    p.hidden = false;
  } finally {
    setBusy(btn, false);
  }
});

/* ---------------- 手动补卡 ---------------- */

function openManual() {
  $('manual-day').value = todayStr();
  $('manual-day').max = todayStr();
  $('manual-in').value = '09:00';
  $('manual-out').value = '12:00';
  $('manual-place').value = '';
  $('manual-note').value = '';
  $('manual-error').hidden = true;
  $('manual-submit').disabled = false;
  $('manual-modal').classList.remove('hidden');
}

function closeManual() {
  $('manual-modal').classList.add('hidden');
}

$('btn-manual').addEventListener('click', openManual);
$('manual-close').addEventListener('click', closeManual);
$('manual-modal').addEventListener('click', (e) => {
  if (e.target === $('manual-modal')) closeManual();
});

$('manual-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = $('manual-submit');
  const errBox = $('manual-error');
  errBox.hidden = true;

  const day = $('manual-day').value;
  const inAt = $('manual-in').value;
  const outAt = $('manual-out').value;
  if (!day || !inAt) {
    errBox.textContent = '请填写日期和进馆时间';
    errBox.hidden = false;
    return;
  }
  if (outAt && outAt <= inAt) {
    errBox.textContent = '出馆时间需要晚于进馆时间';
    errBox.hidden = false;
    return;
  }

  setBusy(btn, true, '保存中');
  try {
    await api('/api/v1/sessions', {
      method: 'POST',
      json: {
        day,
        in_at: inAt,
        out_at: outAt || '',
        note: $('manual-note').value.trim(),
        place: $('manual-place').value.trim(),
      },
    });
    closeManual();
    await Promise.all([loadToday(), loadStats(), loadHistory(true), loadReport(), loadAchievements()]);
    toast('已补上这一段');
  } catch (err) {
    errBox.textContent = err.message || '补卡失败';
    errBox.hidden = false;
  } finally {
    setBusy(btn, false, '补上这一段');
  }
});

// 大图渐进加载：先把缩略图（已在列表里缓存好）怼上去占位，
// 原图在后台下载，到位后再淡入替换，弱网下不再干等白屏。
let lightboxSeq = 0;

function openLightbox(fullUrl, thumbUrl) {
  const box = $('lightbox');
  const img = $('lightbox-img');
  const my = ++lightboxSeq;
  box.classList.remove('hidden');

  const quick = thumbUrl || fullUrl;
  if (quick) {
    img.classList.remove('fade');
    img.src = media(quick);
  }
  if (!fullUrl || fullUrl === quick) return;

  const pre = new Image();
  pre.onload = () => {
    if (my !== lightboxSeq) return; // 已经划到别的图了
    img.src = media(fullUrl);
    img.classList.add('fade');
  };
  pre.onerror = () => {
    // 原图挂了就留着缩略图，至少看得见
    if (my === lightboxSeq) toast('原图加载失败');
  };
  pre.src = media(fullUrl);
}
$('lightbox').addEventListener('click', () => {
  $('lightbox').classList.add('hidden');
  lightboxSeq++;
  $('lightbox-img').classList.remove('fade');
});

/* ---------------- 打卡动作 ---------------- */

function buildForm({ files, note, place }, single) {
  const fd = new FormData();
  if (files.length) {
    if (single) fd.append('photo', files[0]);
    else files.forEach((f) => fd.append('photo', f));
  }
  if (note) fd.append('note', note);
  if (place) fd.append('place', place);
  return fd;
}

$('btn-checkin').addEventListener('click', () => {
  openModal({
    title: '进馆打卡',
    submitText: '确认进馆',
    multiple: false,
    withPlace: true,
    onSubmit: async (v) => {
      if (!v.files.length) throw new Error('请先拍一张照片');
      await api('/api/v1/checkins/in', { method: 'POST', form: buildForm(v, true) });
      await Promise.all([loadToday(), loadStats(), loadHistory(true)]);
      toast('进馆成功，开始计时');
    },
  });
});

$('btn-checkout').addEventListener('click', () => {
  openModal({
    title: '出馆打卡',
    submitText: '确认出馆',
    multiple: false,
    withPlace: true,
    onSubmit: async (v) => {
      if (!v.files.length) throw new Error('请先拍一张照片');
      await api('/api/v1/checkins/out', { method: 'POST', form: buildForm(v, true) });
      await Promise.all([loadToday(), loadStats(), loadHistory(true)]);
      toast('出馆成功，时长已结算');
    },
  });
});

$('btn-add-entry').addEventListener('click', () => {
  openModal({
    title: '今日随手记',
    submitText: '保存',
    multiple: true,
    withPlace: false,
    onSubmit: async (v) => {
      if (!v.files.length && !v.note) throw new Error('请拍照或写点文字');
      await api('/api/v1/entries', { method: 'POST', form: buildForm(v, false) });
      await loadTodayEntries();
      toast('已保存');
    },
  });
});

$('btn-upload-entry').addEventListener('click', () => {
  const day = $('entry-day').value || todayStr();
  openModal({
    title: `上传读物 · ${day}`,
    submitText: '上传',
    multiple: true,
    withPlace: false,
    onSubmit: async (v) => {
      if (!v.files.length) throw new Error('请选择照片');
      const fd = buildForm(v, false);
      fd.append('day', day);
      await api('/api/v1/entries', { method: 'POST', form: fd });
      await loadEntries(day);
      toast('上传成功');
    },
  });
});

$('entry-day').addEventListener('change', (e) => {
  loadEntries(e.target.value || todayStr()).catch((err) => toast(err.message));
});

/* ---------------- 分段修正 / 删除 ---------------- */

const segModal = { session: null };

function openSegModal(ss) {
  if (!ss) return;
  segModal.session = ss;
  const day = ss.day;
  $('seg-title').textContent = `${fmtDay(day).md} 这一段`;
  $('seg-in').value = fmtTime(ss.in_at);
  $('seg-out').value = ss.out_at ? fmtTime(ss.out_at) : '';
  $('seg-in-note').value = ss.in_note || '';
  $('seg-out-note').value = ss.out_note || '';
  $('seg-place').value = ss.in_place || ss.out_place || '';
  $('seg-error').hidden = true;
  $('seg-submit').disabled = false;
  $('seg-modal').classList.remove('hidden');
}

function closeSegModal() {
  $('seg-modal').classList.add('hidden');
  segModal.session = null;
}

$('seg-close').addEventListener('click', closeSegModal);
$('seg-modal').addEventListener('click', (e) => {
  if (e.target === $('seg-modal')) closeSegModal();
});

$('seg-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const ss = segModal.session;
  if (!ss) return;
  const btn = $('seg-submit');
  const errBox = $('seg-error');
  errBox.hidden = true;

  const inTime = $('seg-in').value;
  const outTime = $('seg-out').value;
  if (!inTime) {
    errBox.textContent = '请填写进馆时间';
    errBox.hidden = false;
    return;
  }
  if (outTime && outTime <= inTime) {
    errBox.textContent = '出馆时间需要晚于进馆时间';
    errBox.hidden = false;
    return;
  }
  const place = $('seg-place').value.trim();
  const payload = {
    in_at: `${ss.day}T${inTime}:00`,
    out_at: outTime ? `${ss.day}T${outTime}:00` : '',
    in_note: $('seg-in-note').value.trim(),
    out_note: $('seg-out-note').value.trim(),
    in_place: place,
    out_place: place,
  };

  setBusy(btn, true, '保存中');
  try {
    await api(`/api/v1/sessions/${ss.id}`, { method: 'PATCH', json: payload });
    closeSegModal();
    await refreshAfterChange();
    toast('已更新这一段');
  } catch (err) {
    errBox.textContent = err.message || '保存失败';
    errBox.hidden = false;
  } finally {
    setBusy(btn, false, '保存');
  }
});

async function deleteSession(ss) {
  const ok = confirm(`删除 ${fmtTime(ss.in_at)} 这一段？时长和照片会一起删掉。`);
  if (!ok) return;
  try {
    await api(`/api/v1/sessions/${ss.id}`, { method: 'DELETE' });
    await refreshAfterChange();
    toast('已删除这一段');
  } catch (err) {
    toast(err.message || '删除失败');
  }
}

// 任何增删改之后，把首页/统计/记录一起刷新
async function refreshAfterChange() {
  await Promise.all([
    loadToday().catch(() => {}),
    loadStats().catch(() => {}),
    loadHistory(true).catch(() => {}),
  ]);
  if (state.search.q) runSearch().catch(() => {});
}

/* ---------------- 全局搜索 ---------------- */

async function runSearch() {
  const q = $('search-input').value.trim();
  const box = $('search-results');
  state.search.q = q;
  if (!q) {
    state.search.items = [];
    box.innerHTML = '';
    $('search-meta').textContent = '';
    return;
  }
  $('search-meta').textContent = '搜索中…';
  const d = await api(`/api/v1/search?q=${encodeURIComponent(q)}&limit=50`);
  state.search.items = d.items || [];
  renderSearch();
}

function renderSearch() {
  const box = $('search-results');
  const items = state.search.items;
  box.innerHTML = '';
  $('search-meta').textContent = items.length ? `${items.length} 条结果` : '没找到';
  if (!items.length) {
    box.appendChild(el('div', 'empty', '换个关键词试试：备注、地点、笔记内容，或者直接输日期'));
    return;
  }
  items.forEach((it) => {
    if (it.kind === 'entry') {
      const row = el('div', 'seg-row');
      const head = el('div', 'seg-head');
      head.appendChild(el('span', 'seg-idx', '记'));
      head.appendChild(el('b', 'seg-range', `${fmtDay(it.day).md} ${fmtDay(it.day).week}`));
      head.appendChild(el('span', 'seg-dur', it.place || '随手记'));
      row.appendChild(head);
      if (it.note) row.appendChild(el('p', 'seg-note', escapeHtml(it.note)));
      box.appendChild(row);
      return;
    }
    const d = fmtDay(it.day);
    box.appendChild(segRow(
      {
        id: it.id,
        day: it.day,
        in_at: it.in_at,
        out_at: it.out_at,
        duration_sec: it.duration_sec,
        in_note: it.note,
        in_place: it.place,
        in_thumb_url: it.thumb_url,
        in_photo_url: it.photo_url,
      },
      0,
      {
        onOpen: () => openDay(it.day),
        onEdit: () => openSegModal({ ...it, in_note: it.note, in_place: it.place }),
      },
    ));
  });
}

$('btn-search').addEventListener('click', () => runSearch().catch((err) => toast(err.message)));
$('search-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    runSearch().catch((err) => toast(err.message));
  }
});

// 从搜索结果跳到那一天的详情（滚动到记录列表里对应的日子）
function openDay(day) {
  const box = $('history-list');
  const hit = box && box.querySelector(`[data-day="${day}"]`);
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === 'history'));
  document.querySelectorAll('.panel').forEach((p) => p.classList.add('hidden'));
  $('panel-history').classList.remove('hidden');
  if (hit) {
    hit.scrollIntoView({ block: 'center', behavior: 'smooth' });
    hit.classList.add('flash');
    setTimeout(() => hit.classList.remove('flash'), 1200);
  }
}

/* ---------------- 我的 ---------------- */

$('me-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = e.target.querySelector('button[type=submit]');
  setBusy(btn, true, '保存中');
  try {
    const user = await api('/api/v1/me', {
      method: 'PATCH',
      json: {
        nickname: $('me-nickname').value.trim(),
        daily_goal_min: Number($('me-goal').value) || 0,
      },
    });
    state.user = user;
    localStorage.setItem(LS.user, JSON.stringify(user));
    $('me-nick').textContent = user.nickname || user.username;
    $('me-avatar').textContent = (user.nickname || user.username).slice(0, 1);
    await loadStats();
    toast('已保存');
  } catch (err) {
    toast(err.message || '保存失败');
  } finally {
    setBusy(btn, false);
  }
});

/* ---------------- 账号安全：改密码 / 注销 / 管理员重置 ---------------- */

$('password-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = e.target.querySelector('button[type=submit]');
  const errBox = $('password-error');
  errBox.hidden = true;

  const oldPw = $('pw-old').value;
  const newPw = $('pw-new').value;
  const newPw2 = $('pw-new2').value;
  if (newPw.length < 6) {
    errBox.textContent = '新密码至少 6 位';
    errBox.hidden = false;
    return;
  }
  if (newPw !== newPw2) {
    errBox.textContent = '两次输入的新密码不一致';
    errBox.hidden = false;
    return;
  }
  setBusy(btn, true, '更新中');
  try {
    const data = await api('/api/v1/me/password', {
      method: 'POST',
      json: { old_password: oldPw, new_password: newPw },
    });
    if (data && data.token) {
      state.token = data.token;
      state.refresh = data.refresh_token || state.refresh;
      localStorage.setItem(LS.token, state.token);
      localStorage.setItem(LS.refresh, state.refresh);
    }
    $('pw-old').value = '';
    $('pw-new').value = '';
    $('pw-new2').value = '';
    toast('密码已更新，其它设备需要重新登录');
  } catch (err) {
    errBox.textContent = err.message || '更新失败';
    errBox.hidden = false;
  } finally {
    setBusy(btn, false);
  }
});

$('btn-delete-account').addEventListener('click', async () => {
  const pw = prompt('注销账号会删掉所有打卡、笔记和照片，且无法恢复。\n请再输入一次密码确认：');
  if (pw === null) return;
  if (!pw) {
    toast('需要输入密码才能注销');
    return;
  }
  if (!confirm('最后确认：真的要注销这个账号吗？')) return;
  try {
    await api('/api/v1/me', { method: 'DELETE', json: { password: pw } });
    toast('账号已注销');
    setTimeout(() => logout(true), 600);
  } catch (err) {
    toast(err.message || '注销失败');
  }
});

async function loadUsers() {
  const card = $('users-card');
  if (!card) return;
  if (!(state.user && state.user.is_admin)) {
    card.classList.add('hidden');
    return;
  }
  card.classList.remove('hidden');
  let d;
  try {
    d = await api('/api/v1/system/users');
  } catch (err) {
    if (err.status !== 403) toast(err.message || '读取用户列表失败');
    card.classList.add('hidden');
    return;
  }
  const list = d.items || [];
  $('users-meta').textContent = `${list.length} 个账号`;
  $('users-list').innerHTML = list.map((u) => `
    <li>
      <span class="bk-name">${escapeHtml(u.nickname || u.username)}${u.is_admin ? ' · 管理员' : ''} · ${u.days || 0} 天</span>
      <span class="bk-ops">
        <button data-user-act="reset" data-user="${u.id}" data-user-name="${escapeHtml(u.username)}">重置密码</button>
      </span>
    </li>`).join('');
}

$('users-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-user-act]');
  if (!btn) return;
  if (btn.dataset.userAct !== 'reset') return;
  const name = btn.dataset.userName;
  const pw = prompt(`给「${name}」设置一个新密码（至少 6 位）：`);
  if (pw === null) return;
  if (pw.length < 6) {
    toast('密码至少 6 位');
    return;
  }
  setBusy(btn, true, '重置中');
  try {
    await api(`/api/v1/system/users/${btn.dataset.user}/password`, {
      method: 'POST',
      json: { new_password: pw },
    });
    toast(`已重置 ${name} 的密码，对方需要用新密码重新登录`);
  } catch (err) {
    toast(err.message || '重置失败');
  } finally {
    setBusy(btn, false, '重置密码');
  }
});

async function downloadFile(path, filename) {
  try {
    const res = await fetch(path, { headers: { Authorization: `Bearer ${state.token}` } });
    if (!res.ok) throw new Error(`导出失败 (${res.status})`);
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    toast('已开始下载');
  } catch (err) {
    toast(err.message || '导出失败');
  }
}

$('btn-update-check').addEventListener('click', checkUpdate);
$('btn-update-apply').addEventListener('click', applyUpdate);
$('update-version').addEventListener('click', () => {
  const box = $('update-log');
  box.classList.toggle('hidden');
  if (!box.classList.contains('hidden')) loadLog();
});

$('btn-export-checkins').addEventListener('click', () => downloadFile('/api/v1/export/checkins.csv', 'checkins.csv'));
$('btn-export-sessions').addEventListener('click', () => downloadFile('/api/v1/export/sessions.csv', 'sessions.csv'));
$('btn-export-entries').addEventListener('click', () => downloadFile('/api/v1/export/entries.csv', 'entries.csv'));
$('btn-export-photos').addEventListener('click', () => downloadFile('/api/v1/export/photos.zip', 'photos.zip'));
$('btn-logout').addEventListener('click', () => {
  if (confirm('确定退出登录？')) logout();
});

/* ---------------- 导航 ---------------- */

$('tabbar').addEventListener('click', (e) => {
  const btn = e.target.closest('.tab');
  if (!btn) return;
  const tab = btn.dataset.tab;
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === btn));
  document.querySelectorAll('.panel').forEach((p) => p.classList.add('hidden'));
  $(`panel-${tab}`).classList.remove('hidden');
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (tab === 'stats') {
    loadStats().catch((err) => toast(err.message));
    loadReport();
    loadAchievements();
  }
  if (tab === 'history') loadHistory(state.history.items.length === 0).catch((err) => toast(err.message));
  if (tab === 'entries') loadEntries($('entry-day').value || todayStr()).catch((err) => toast(err.message));
});

$('btn-more-history').addEventListener('click', () => loadHistory(false).catch((err) => toast(err.message)));

$('btn-refresh').addEventListener('click', async (e) => {
  const btn = e.currentTarget;
  btn.disabled = true;
  try {
    await loadAll();
    toast('已刷新');
  } catch (err) {
    toast(err.message || '刷新失败');
  } finally {
    btn.disabled = false;
  }
});

/* ---------------- 启动 ---------------- */

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

(async function boot() {
  if (!state.token) {
    switchAuthMode('login');
    return;
  }
  try {
    const user = await api('/api/v1/me');
    enterApp(user);
  } catch (_) {
    logout(true);
  }
})();

/* ---------------- PWA：注册 Service Worker（弱网可打开界面） ---------------- */

if ('serviceWorker' in navigator && location.protocol.startsWith('http')) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js').catch(() => { /* 不支持就退回普通网页 */ });
  });
}
