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
  await Promise.all([loadToday(), loadTodayEntries(), loadStats(), loadHistory(true)]);
  loadVersion();
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
    note.textContent = '进馆拍一张，开始计时。';
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
    note.textContent = c && c.in_note ? c.in_note : `进馆于 ${fmtTime(c && c.in_at)}`;
    tickTimer();
  } else {
    pill.textContent = '已完成';
    pill.className = 'pill ok';
    $('today-timer').textContent = fmtDur(c ? c.duration_sec : 0);
    btnIn.disabled = true;
    btnIn.textContent = '今日已进馆';
    btnOut.disabled = true;
    btnOut.textContent = '今日已出馆';
    const parts = [];
    if (c && c.in_at) parts.push(`进馆 ${fmtTime(c.in_at)}`);
    if (c && c.out_at) parts.push(`出馆 ${fmtTime(c.out_at)}`);
    const notes = [c && c.in_note, c && c.out_note].filter(Boolean).join(' · ');
    note.textContent = parts.join('  ·  ') + (notes ? `  ·  ${notes}` : '');
  }

  if (c) {
    [['进馆', c.in_thumb_url || c.in_photo_url], ['出馆', c.out_thumb_url || c.out_photo_url]].forEach(([label, url]) => {
      if (!url) return;
      const fig = el('figure');
      const img = el('img');
      img.src = media(url);
      img.loading = 'lazy';
      img.alt = label;
      img.addEventListener('click', () => openLightbox(fullFor(url), url));
      fig.appendChild(img);
      fig.appendChild(el('figcaption', null, `${label} ${fmtTime(label === '进馆' ? c.in_at : c.out_at)}`));
      photos.appendChild(fig);
    });
  }
}

function tickTimer() {
  clearInterval(state.timer);
  const update = () => {
    const c = state.today && state.today.checkin;
    if (!c || !c.in_at) return;
    const sec = Math.max(0, Math.round((Date.now() - new Date(c.in_at).getTime()) / 1000));
    $('today-timer').textContent = fmtDur(sec);
  };
  update();
  state.timer = setInterval(update, 30000);
}

async function loadStats() {
  const s = await api('/api/v1/stats');
  state.stats = s;
  renderStats();
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
  if (tab === 'stats') loadStats().catch((err) => toast(err.message));
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
