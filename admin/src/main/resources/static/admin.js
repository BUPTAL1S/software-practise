(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const storageKey = 'adminWorkbenchToken';
  let token = '', epoch = 0, activeTab = 'cdr', refreshTimer, toastTimer;
  let refreshing = false, refreshQueued = false;
  let sessionAbort = new AbortController();
  const resources = new Map(), tables = new Map(), failures = new Map();
  const base = '/api/admin/workbench';

  function text(id, value) {
    const target = $(id), content = String(value ?? '');
    if (target && target.textContent !== content) target.textContent = content;
  }
  function element(tag, value, className) {
    const item = document.createElement(tag);
    if (value != null) item.textContent = String(value);
    if (className) item.className = className;
    return item;
  }
  function badge(value, kind = 'gray') { return element('span', value, 'badge badge-' + kind); }
  function button(label, action) {
    const item = element('button', label, 'btn btn-outline btn-sm');
    item.type = 'button';
    item.addEventListener('click', action);
    return item;
  }
  function showToast(message, error = false) {
    clearTimeout(toastTimer);
    text('toast', message);
    $('toast').style.borderColor = error ? '#ef4444' : '#10b981';
    $('toast').style.display = 'block';
    toastTimer = setTimeout(() => { $('toast').style.display = 'none'; }, 4000);
  }
  function store(value) {
    try {
      sessionStorage.removeItem('chatAdminToken');
      if (value) sessionStorage.setItem(storageKey, value);
      else sessionStorage.removeItem(storageKey);
    } catch (_) { /* This tab can still use its in-memory session. */ }
  }
  function abortResource(key) {
    const old = resources.get(key);
    if (old) { old.generation++; old.controller?.abort(); }
  }
  function lockSession(message = '') {
    epoch++;
    token = '';
    store('');
    sessionAbort.abort();
    sessionAbort = new AbortController();
    resources.forEach((_, key) => abortResource(key));
    resources.clear(); tables.clear(); failures.clear();
    onlineNames = null; userRows = null;
    clearTimeout(refreshTimer);
    clearTimeout(toastTimer);
    text('toast', ''); $('toast').style.display = 'none';
    $('registerForm').reset(); $('roomForm').reset();
    ['filterCaller', 'filterCallee', 'chatUserFilter', 'chatPeerFilter', 'chatRoomFilter', 'chatRoomSenderFilter']
      .forEach(id => { $(id).value = ''; });
    ['cdrCountBadge', 'roomCountBadge', 'userCountBadge', 'fileCountBadge', 'roomChatBadge']
      .forEach(id => text(id, '--'));
    text('syncDetails', '未登录');
    document.querySelectorAll('.data-status').forEach(item => { item.textContent = '等待登录'; });
    $('workbench').hidden = true;
    $('loginPanel').hidden = false;
    $('adminPassword').value = '';
    text('loginError', message);
    ['statOnlineUsers', 'statTotalCalls', 'statTotalDuration', 'statAvgDuration', 'statPeakRoomMembers']
      .forEach(id => text(id, '--'));
    document.querySelectorAll('tbody').forEach(body => body.replaceChildren());
    text('adminIdentity', '');
    text('chatLoginBadge', '未登录');
    text('syncStatus', '● 尚未连接');
    $('syncStatus').className = 'badge badge-gray';
    text('eslStatus', '● ESL 状态未知');
    $('eslStatus').className = 'badge badge-gray';
  }

  async function api(path, options = {}) {
    const { auth = true, signal, blob = false, ...init } = options;
    const requestEpoch = epoch, controller = new AbortController();
    const sessionSignal = sessionAbort.signal;
    const cancel = () => controller.abort();
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 10000);
    signal?.addEventListener('abort', cancel, { once: true });
    if (auth) sessionSignal.addEventListener('abort', cancel, { once: true });
    if (signal?.aborted || auth && sessionSignal.aborted) controller.abort();
    try {
      const headers = { ...init.headers };
      if (auth) headers.Authorization = 'Bearer ' + token;
      const response = await fetch(path, { ...init, headers, signal: controller.signal, cache: 'no-store' });
      if (auth && requestEpoch === epoch && (response.status === 401 || response.status === 403)) {
        lockSession(response.status === 401 ? '登录已过期，请重新登录' : '仅管理员账号 1001 可以访问工作台');
      }
      if (!response.ok) {
        let message = '请求失败（HTTP ' + response.status + '）';
        try { message = (await response.json()).error || message; } catch (_) { }
        throw new Error(message);
      }
      return blob ? await response.blob() : await response.json();
    } catch (error) {
      if (timedOut) throw new Error('请求超时，请重试');
      throw error;
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener('abort', cancel);
      sessionSignal.removeEventListener('abort', cancel);
    }
  }

  const duration = value => {
    const seconds = Math.max(0, Number(value) || 0);
    return seconds >= 60 ? Math.floor(seconds / 60) + '分 ' + seconds % 60 + '秒' : seconds + '秒';
  };
  const time = value => {
    const date = new Date(value);
    return value && Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', { hour12: false }) : '-';
  };
  const size = value => {
    const bytes = Math.max(0, Number(value) || 0);
    if (!bytes) return '0 B';
    const unit = Math.min(3, Math.floor(Math.log(bytes) / Math.log(1024)));
    return Number((bytes / 1024 ** unit).toFixed(1)) + ' ' + ['B', 'KB', 'MB', 'GB'][unit];
  };

  // Preserve existing row nodes when data is unchanged, including text selection and scroll position.
  function renderTable(id, rows, columns, createRow) {
    if (!Array.isArray(rows)) throw new Error('服务端返回的列表格式不正确');
    const body = $(id), previous = tables.get(id) || new Map(), next = new Map();
    const wrapper = body.parentElement.parentElement;
    const left = wrapper.scrollLeft, top = wrapper.scrollTop;
    if (!rows.length) {
      if (!previous.has('empty')) {
        const row = element('tr'), cell = element('td', '暂无记录', 'empty-state');
        cell.colSpan = columns; row.append(cell); body.replaceChildren(row);
      }
      next.set('empty', {});
    } else {
      rows.forEach((data, index) => {
        const key = String(data.id ?? index), signature = JSON.stringify(data), old = previous.get(key);
        let row = old?.signature === signature ? old.row : createRow(data);
        next.set(key, { signature, row });
        const current = body.children[index];
        if (current !== row) body.insertBefore(row, current || null);
      });
      while (body.children.length > rows.length) body.lastElementChild.remove();
      // Drop replaced or removed nodes, which may still occur between reused rows.
      const keep = new Set([...next.values()].map(item => item.row));
      [...body.children].forEach(row => { if (!keep.has(row)) row.remove(); });
    }
    tables.set(id, next);
    wrapper.scrollLeft = left; wrapper.scrollTop = top;
  }
  function row(...values) {
    const result = element('tr');
    values.forEach(value => {
      const cell = element('td');
      cell.append(value instanceof Node ? value : document.createTextNode(String(value ?? '-')));
      result.append(cell);
    });
    return result;
  }
  function statusFor(key) {
    const tab = key === 'roomChat' || key === 'chat' ? 'chat' : key;
    const keys = tab === 'chat' ? ['chat', 'roomChat'] : [key];
    const failed = keys.map(k => failures.get(k)).filter(Boolean);
    const stamp = resources.get(key)?.updated;
    text('dataStatus-' + tab, failed.length ? '刷新失败：' + failed.join('；') + '。已保留上次数据'
      : stamp ? '最近成功更新：' + time(stamp) : '正在更新…');
    $('dataStatus-' + tab)?.classList.toggle('error-text', failed.length > 0);
  }

  function load(key, path, render, force = false) {
    const old = resources.get(key);
    if (old?.promise && !force) return old.promise;
    if (old) abortResource(key);
    const item = { generation: (old?.generation || 0) + 1, controller: new AbortController(), updated: old?.updated };
    const currentEpoch = epoch, generation = item.generation;
    resources.set(key, item);
    const current = () => currentEpoch === epoch && token && item.generation === generation;
    item.promise = api(path, { signal: item.controller.signal }).then(data => {
      if (!current()) return;
      render(data);
      item.updated = new Date().toISOString(); failures.delete(key);
      if (['cdr', 'rooms', 'users', 'files', 'chat', 'roomChat'].includes(key)) statusFor(key);
    }).catch(error => {
      if (!current() || error.name === 'AbortError') return;
      failures.set(key, error.message);
      if (key === 'health') {
        text('syncStatus', '● 后台连接异常'); $('syncStatus').className = 'badge badge-gray';
        text('eslStatus', '● ESL 状态未知'); $('eslStatus').className = 'badge badge-gray';
      }
      if (key === 'online') {
        onlineNames = null;
        text('statOnlineUsers', '--'); text('statOnlineUsersDesc', '会话状态未知');
        renderUsers();
      }
      if (['cdr', 'rooms', 'users', 'files', 'chat', 'roomChat'].includes(key)) statusFor(key);
    }).finally(() => {
      if (resources.get(key) === item) item.promise = null;
      if (current()) syncSummary();
    });
    return item.promise;
  }
  function syncSummary() {
    const updated = resources.get('health')?.updated;
    const errors = [...failures.values()];
    text('syncDetails', (updated ? '后台最近成功连接：' + time(updated) : '尚无成功连接记录')
      + (errors.length ? ' · 部分数据刷新失败：' + [...new Set(errors)].join('；') : ''));
  }
  let onlineNames = null, userRows = null;
  function renderUsers() {
    if (!userRows || !token) return;
    const rows = userRows.map(user => ({ ...user, sessionStatus: onlineNames === null ? '状态未知'
      : onlineNames.has(user.username) || onlineNames.has(user.sipId) ? '后台已登录' : '未登录后台' }));
    renderTable('usersTableBody', rows, 4, user => row(badge('#' + user.id), user.username,
      badge(user.sipId || user.sip_id || user.username, 'primary'),
      badge(user.sessionStatus, user.sessionStatus === '后台已登录' ? 'success' : 'gray')));
  }
  function baseLoads(force) {
    return [
      load('health', base + '/health', data => {
        if (data.status !== 'ok') throw new Error('后台健康状态异常');
        text('syncStatus', '● 后台已连接'); $('syncStatus').className = 'badge badge-success';
        const esl = data.esl;
        const label = !esl ? '● ESL 状态未知' : !esl.enabled ? '● ESL 已停用'
          : esl.connected ? '● ESL 已连接' : '● ESL 未连接';
        text('eslStatus', label);
        $('eslStatus').className = 'badge badge-' + (esl?.enabled && esl?.connected ? 'success' : 'gray');
      }, force),
      load('stats', base + '/stats', data => {
        text('statTotalCalls', data.totalCalls ?? '--');
        text('statTotalDuration', duration(data.totalDurationSec));
        text('statTotalDurationDesc', '累计已上报通话时长');
        text('statAvgDuration', duration(data.averageDurationSec));
        text('statPeakRoomMembers', data.peakRoomMembers ?? '--');
      }, force),
      load('online', base + '/users/online', data => {
        if (!Array.isArray(data.users)) throw new Error('会话列表格式不正确');
        onlineNames = new Set(data.users);
        text('statOnlineUsers', data.count ?? data.users.length);
        text('statOnlineUsersDesc', '有效 HTTP 会话，不代表 SIP 注册或正在通话');
        renderUsers();
      }, force)
    ];
  }

  function panelLoads(force) {
    if (activeTab === 'cdr') {
      const params = new URLSearchParams({ limit: $('filterLimit').value });
      if ($('filterCaller').value.trim()) params.set('caller', $('filterCaller').value.trim());
      if ($('filterCallee').value.trim()) params.set('callee', $('filterCallee').value.trim());
      return [load('cdr', base + '/cdr?' + params, data => {
        renderTable('cdrTableBody', data, 6, call => row(badge('#' + call.id), call.call_id || call.callId || '-',
          button(call.caller, () => { $('filterCaller').value = call.caller; panelLoads(true); }),
          button(call.callee_or_room || call.calleeOrRoom, () => {
            $('filterCallee').value = call.callee_or_room || call.calleeOrRoom; panelLoads(true);
          }), time(call.started_at || call.startedAt), badge(duration(call.duration_sec ?? call.durationSec), 'success')));
        text('cdrCountBadge', data.length + ' 条 · 累计 ' + duration(data.reduce((sum, c) => sum + Number(c.duration_sec ?? c.durationSec ?? 0), 0)));
      }, force)];
    }
    if (activeTab === 'rooms') return [load('rooms', base + '/rooms', data => {
      renderTable('roomsTableBody', data, 6, room => {
        const uri = room.sip_uri || room.sipUri;
        return row(badge('#' + room.id), element('strong', room.name), badge(uri, 'primary'),
          (room.max_members ?? room.maxMembers) + ' 人', badge((room.member_count ?? room.memberCount ?? 0) + ' 人'),
          button('复制 URI', () => copyText(uri)));
      });
      text('roomCountBadge', data.length + ' 间');
    }, force)];
    if (activeTab === 'users') return [load('users', base + '/users', data => {
      if (!Array.isArray(data)) throw new Error('用户列表格式不正确');
      userRows = data; renderUsers(); text('userCountBadge', data.length + ' 人');
    }, force)];
    if (activeTab === 'files') return [load('files', base + '/files', data => {
      renderTable('filesTableBody', data, 7, file => row(badge('#' + file.id), element('strong', file.name), size(file.size),
        badge(file.from), badge(file.to, 'primary'), time(file.created_at || file.createdAt),
        button('下载', event => download(file, event.currentTarget))));
      text('fileCountBadge', data.length + ' 个');
    }, force)];
    const params = new URLSearchParams({ limit: $('chatLimit').value });
    if ($('chatUserFilter').value.trim()) params.set('user', $('chatUserFilter').value.trim());
    if ($('chatPeerFilter').value.trim()) params.set('peer', $('chatPeerFilter').value.trim());
    const roomParams = new URLSearchParams({ limit: $('chatLimit').value });
    if ($('chatRoomFilter').value.trim()) roomParams.set('room', $('chatRoomFilter').value.trim());
    if ($('chatRoomSenderFilter').value.trim()) roomParams.set('sender', $('chatRoomSenderFilter').value.trim());
    return [load('chat', '/api/admin/messages?' + params, data => {
      renderTable('chatTableBody', data, 7, message => row(badge('#' + message.id), badge(message.sender),
        badge(message.receiver, 'primary'), message.content, message.is_file || message.isFile ? '文件' : '文字',
        badge(message.status, message.status === 'DELIVERED' ? 'success' : 'gray'), time(message.created_at || message.createdAt)));
    }, force), load('roomChat', '/api/admin/room-messages?' + roomParams, data => {
      renderTable('roomChatTableBody', data, 6, message => row(badge('#' + message.id), badge(message.room_uri || message.roomUri, 'primary'),
        badge(message.sender), message.content, message.is_file || message.isFile ? '文件' : '文字', time(message.created_at || message.createdAt)));
      text('roomChatBadge', data.length + ' 条');
    }, force)];
  }

  function scheduleRefresh() {
    clearTimeout(refreshTimer);
    if (token && $('autoRefreshToggle').checked && !document.hidden)
      refreshTimer = setTimeout(() => refresh(), 5000);
  }
  async function refresh(force = false) {
    clearTimeout(refreshTimer);
    if (!token) return;
    if (refreshing) { if (force) { refreshQueued = true; panelLoads(true); } return; }
    refreshing = true;
    $('refreshAll').disabled = true;
    try { await Promise.all([...baseLoads(force), ...panelLoads(force)]); }
    finally {
      refreshing = false; $('refreshAll').disabled = false;
      if (refreshQueued && token) { refreshQueued = false; refresh(true); }
      else scheduleRefresh();
    }
  }
  function activateTab(id) {
    ['cdr', 'rooms', 'users', 'files', 'chat', 'roomChat'].forEach(abortResource);
    activeTab = id.replace('tab-', '');
    document.querySelectorAll('[data-tab]').forEach(item => {
      const selected = item.dataset.tab === id;
      item.classList.toggle('active', selected); item.setAttribute('aria-selected', String(selected));
    });
    document.querySelectorAll('.tab-content').forEach(item => item.classList.toggle('active', item.id === id));
    if (token) panelLoads(true);
  }
  async function copyText(value) {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('剪贴板不可用');
      await navigator.clipboard.writeText(String(value)); showToast('已复制: ' + value);
    } catch (_) { showToast('复制失败，请手动选择并复制房间 URI', true); }
  }
  async function download(file, control) {
    if (!/^\d+$/.test(String(file.id))) { showToast('文件编号不正确', true); return; }
    control.disabled = true;
    const currentEpoch = epoch;
    try {
      const data = await api(base + '/files/' + file.id, { blob: true });
      if (currentEpoch !== epoch || !token) return;
      const url = URL.createObjectURL(data), link = element('a');
      link.href = url; link.download = String(file.name || 'download');
      document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { if (currentEpoch === epoch) showToast('下载失败: ' + error.message, true); }
    finally { control.disabled = false; }
  }
  async function submit(form, path, payload, done) {
    if (form.dataset.pending === 'true' || !token) return;
    form.dataset.pending = 'true';
    const control = form.querySelector('[type=submit]'), currentEpoch = epoch;
    control.disabled = true;
    try {
      const data = await api(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
      if (currentEpoch === epoch && token) { done(data); panelLoads(true); }
    } catch (error) { if (currentEpoch === epoch) showToast(error.message, true); }
    finally { control.disabled = false; delete form.dataset.pending; }
  }
  function unlockSession(user) {
    $('loginPanel').hidden = true; $('workbench').hidden = false;
    $('adminPassword').value = ''; text('loginError', '');
    text('adminIdentity', '管理员 ' + user.username); text('chatLoginBadge', '管理员 ' + user.username);
    refreshQueued = true;
    if (!refreshing) { refreshQueued = false; refresh(true); }
  }
  function install() {
    document.querySelectorAll('[data-tab]').forEach(item => item.addEventListener('click', event => activateTab(event.currentTarget.dataset.tab)));
    $('refreshAll').addEventListener('click', () => refresh(true));
    $('autoRefreshToggle').addEventListener('change', scheduleRefresh);
    document.addEventListener('visibilitychange', () => {
      clearTimeout(refreshTimer);
      if (!document.hidden && token && $('autoRefreshToggle').checked) refresh();
    });
    ['queryCdr', 'queryChat', 'queryRoomChat'].forEach(id => $(id).addEventListener('click', () => panelLoads(true)));
    $('resetCdr').addEventListener('click', () => {
      $('filterCaller').value = ''; $('filterCallee').value = ''; $('filterLimit').value = '100'; panelLoads(true);
    });
    $('roomForm').addEventListener('submit', event => {
      event.preventDefault();
      const payload = { name: $('roomNameInput').value.trim(), max_members: 8 };
      if ($('roomSipUriInput').value.trim()) payload.sip_uri = $('roomSipUriInput').value.trim();
      submit(event.currentTarget, '/rooms', payload, data => {
        $('roomNameInput').value = ''; $('roomSipUriInput').value = ''; showToast('房间创建成功: ' + data.name);
      });
    });
    $('registerForm').addEventListener('submit', event => {
      event.preventDefault();
      const payload = { username: $('regUsername').value.trim(), password: $('regPassword').value.trim() };
      if ($('regSipId').value.trim()) payload.sip_id = $('regSipId').value.trim();
      submit(event.currentTarget, '/users', payload, data => {
        $('registerForm').reset(); showToast('用户注册成功: ' + data.username);
      });
    });
    $('loginForm').addEventListener('submit', async event => {
      event.preventDefault();
      const form = event.currentTarget;
      if (form.dataset.pending === 'true') return;
      const username = $('adminUser').value.trim(), password = $('adminPassword').value;
      if (!username || !password) { text('loginError', '请输入管理员账号和密码'); return; }
      form.dataset.pending = 'true'; form.querySelector('button').disabled = true;
      lockSession(); const attempt = epoch;
      try {
        const data = await api('/api/admin/login', { auth: false, method: 'POST',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) });
        if (attempt !== epoch) return;
        if (data.username !== '1001' || data.role !== 'ADMIN' || typeof data.token !== 'string' || !data.token)
          throw new Error('管理员会话无效');
        token = data.token; store(token); unlockSession(data);
      } catch (error) { if (attempt === epoch) text('loginError', error.message); }
      finally { delete form.dataset.pending; form.querySelector('button').disabled = false; }
    });
    $('logoutButton').addEventListener('click', async () => {
      const oldToken = token;
      const logout = api('/api/admin/logout', { auth: false, method: 'POST', headers: { Authorization: 'Bearer ' + oldToken } });
      lockSession('已退出登录'); onlineNames = null; userRows = null;
      try { await logout; } catch (_) { showToast('已退出本地会话，服务端注销未确认，请检查连接', true); }
    });
  }
  if (location.protocol === 'file:') {
    const warning = element('section', null, 'card auth-card');
    warning.append(element('h1', '请通过后台地址打开工作台'), element('p', '直接双击文件无法连接后台。'));
    const link = element('a', '打开本机后台'); link.href = 'http://127.0.0.1:8080/admin.html'; warning.append(link);
    document.body.replaceChildren(warning);
    return;
  }
  install();
  try { token = sessionStorage.getItem(storageKey) || ''; } catch (_) { }
  if (token) {
    text('loginError', '正在验证登录…');
    const attempt = epoch;
    api('/api/admin/session').then(user => {
      if (attempt === epoch && token && user.username === '1001' && user.role === 'ADMIN') unlockSession(user);
      else if (attempt === epoch) lockSession('请使用管理员账号 1001 登录');
    }).catch(error => { if (attempt === epoch) lockSession('登录验证失败：' + error.message); });
  }
})();
