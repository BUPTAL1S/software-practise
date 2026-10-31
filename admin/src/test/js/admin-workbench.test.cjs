/* Isolated browser regression: mock HTTP API only; never runs Spring Boot, SIP or Docker. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const staticDir = path.join(root, 'main/resources/static');
const target = path.resolve(root, '../target');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

class Browser {
  constructor() {
    const executable = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
    fs.mkdirSync(target, { recursive: true });
    const profile = fs.mkdtempSync(path.join(target, 'workbench-browser-'));
    this.child = spawn(executable, ['--headless=new', '--remote-debugging-pipe', '--no-first-run',
      '--disable-background-networking', '--disable-component-update', '--disable-sync', '--disable-gpu',
      '--window-size=1360,1000', '--user-data-dir=' + profile], { stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] });
    this.nextId = 0; this.pending = new Map(); this.buffer = ''; this.exceptions = [];
    this.child.stderr.on('data', () => {});
    const decoder = new (require('node:string_decoder').StringDecoder)('utf8');
    this.child.stdio[4].on('data', chunk => {
      this.buffer += decoder.write(chunk);
      let end;
      while ((end = this.buffer.indexOf('\0')) >= 0) {
        const value = JSON.parse(this.buffer.slice(0, end)); this.buffer = this.buffer.slice(end + 1);
        if (value.id) {
          const job = this.pending.get(value.id);
          if (job) { clearTimeout(job.timer); this.pending.delete(value.id); value.error ? job.reject(Error(JSON.stringify(value.error))) : job.resolve(value.result); }
        } else if (value.method === 'Runtime.exceptionThrown') this.exceptions.push(value.params.exceptionDetails);
      }
    });
  }
  send(method, params = {}, sessionId) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(Error('CDP timeout: ' + method)); }, 10000);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }) + '\0');
    });
  }
  async open(url) {
    const { targetId } = await this.send('Target.createTarget', { url: 'about:blank' });
    this.session = (await this.send('Target.attachToTarget', { targetId, flatten: true })).sessionId;
    await this.send('Runtime.enable', {}, this.session);
    await this.send('Page.enable', {}, this.session);
    await this.send('Page.navigate', { url }, this.session);
    await this.wait('document.getElementById("loginForm") !== null');
  }
  async evaluate(expression) {
    const output = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, this.session);
    if (output.exceptionDetails) throw Error(output.exceptionDetails.exception?.description || output.exceptionDetails.text);
    return output.result.value;
  }
  async wait(expression, timeout = 6000) {
    const until = Date.now() + timeout;
    do {
      if (await this.evaluate(expression)) return;
      await sleep(30);
    } while (Date.now() < until);
    throw Error('Browser condition timed out: ' + expression);
  }
  async screenshot(name) {
    const data = await this.send('Page.captureScreenshot', { captureBeyondViewport: true }, this.session);
    fs.writeFileSync(path.join(target, name), Buffer.from(data.data, 'base64'));
  }
  async close() {
    try { await this.send('Browser.close'); } catch (_) { }
    this.child.kill();
    for (const job of this.pending.values()) clearTimeout(job.timer);
  }
}

test('management workbench browser contract', { timeout: 60000 }, async t => {
  const state = { requests: [], health: 'ok', online: true, expired: false, cdrDelay: 0,
    activeCdr: 0, maxCdr: 0, roomPosts: 0, userPosts: 0, roomsFail: false,
    userDeleted: false, userDeleteFail: false, userDeletes: 0 };
  const hostile = `<img src=x onerror="window.injected=true"> O'Reilly & <svg onload="window.injected=true">`;
  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost');
    if (!url.pathname.startsWith('/api/')) {
      const name = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
      if (!['index.html', 'admin.html', 'admin.js'].includes(name)) { response.writeHead(404).end(); return; }
      response.setHeader('Content-Type', name.endsWith('.js') ? 'text/javascript' : 'text/html; charset=utf-8');
      response.end(fs.readFileSync(path.join(staticDir, name))); return;
    }
    let body = ''; for await (const chunk of request) body += chunk;
    state.requests.push({ path: url.pathname, query: url.searchParams.toString(), method: request.method, auth: request.headers.authorization, body });
    const send = (data, status = 200) => {
      if (response.destroyed) return;
      response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(data));
    };
    if (url.pathname === '/api/admin/login') {
      const user = JSON.parse(body);
      if (user.username !== '1001') send({ error: '仅管理员账号 1001 可以登录工作台' }, 403);
      else if (user.password !== '1001') send({ error: '账号或密码错误' }, 401);
      else { state.expired = false; send({ username: '1001', role: 'ADMIN', token: 'browser-token' }); }
      return;
    }
    if (request.headers.authorization !== 'Bearer browser-token' || state.expired) { send({ error: 'unauthorized' }, 401); return; }
    if (url.pathname === '/api/admin/session') { send({ username: '1001', role: 'ADMIN' }); return; }
    if (url.pathname === '/api/admin/logout') { send({ success: true }); return; }
    if (url.pathname.endsWith('/health')) { send(state.health === 'ok' ? { status: 'ok', esl: { enabled: true, connected: true } } : { error: 'backend unavailable' }, state.health === 'ok' ? 200 : 503); return; }
    if (url.pathname.endsWith('/stats')) { send({ totalCalls: 5, totalDurationSec: 90, averageDurationSec: 18, peakRoomMembers: 2 }); return; }
    if (url.pathname.endsWith('/users/online')) { send(state.online ? { count: 1, users: ['1001'] } : { error: 'online unavailable' }, state.online ? 200 : 503); return; }
    if (url.pathname.endsWith('/cdr')) {
      state.activeCdr++; state.maxCdr = Math.max(state.maxCdr, state.activeCdr);
      response.on('close', () => { state.activeCdr--; });
      const caller = url.searchParams.get('caller') || hostile;
      await sleep(caller === 'slow' ? 400 : state.cdrDelay);
      send([{ id: 1, caller, callee_or_room: hostile, call_id: hostile, duration_sec: 15, started_at: 'invalid-date' }]); return;
    }
    if (url.pathname.endsWith('/rooms')) {
      if (state.roomsFail) { send({ error: 'rooms unavailable' }, 503); return; }
      if (request.method === 'POST') { state.roomPosts++; assert.equal(JSON.parse(body).max_members, 8); await sleep(250); send({ id: 2, name: JSON.parse(body).name }); }
      else send([{ id: 1, name: hostile, sip_uri: 'room-101', max_members: 8, member_count: 0 }]);
      return;
    }
    if (url.pathname.endsWith('/users')) {
      if (request.method === 'POST') { state.userPosts++; await sleep(250); send({ username: JSON.parse(body).username }); }
      else send([{ id: 1, username: '1001', sipId: hostile },
        ...state.userDeleted ? [] : [{ id: 2, username: hostile, sipId: '1002' }]]);
      return;
    }
    if (url.pathname.endsWith('/users/2') && request.method === 'DELETE') {
      state.userDeletes++; await sleep(250);
      if (state.userDeleteFail) send({ error: 'delete unavailable' }, 503);
      else { state.userDeleted = true; send({ success: true, username: hostile }); }
      return;
    }
    if (url.pathname.endsWith('/files')) { send([{ id: 1, name: hostile, size: 1024, from: hostile, to: '1001', created_at: 'invalid-date', url: 'javascript:window.injected=true' }]); return; }
    if (url.pathname.endsWith('/files/1')) { response.writeHead(200, { 'Content-Type': 'application/octet-stream' }); response.end('file'); return; }
    if (url.pathname === '/api/admin/messages') { send([{ id: 1, sender: hostile, receiver: '1002', content: hostile, status: 'PENDING', created_at: 'bad' }]); return; }
    if (url.pathname === '/api/admin/room-messages') { send([{ id: 1, room_uri: hostile, sender: hostile, content: hostile, created_at: 'bad' }]); return; }
    send({ error: 'unknown test endpoint' }, 404);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = new Browser();
  const origin = 'http://127.0.0.1:' + server.address().port;
  const clickTab = async tab => {
    await browser.evaluate(`document.querySelector('[data-tab="tab-${tab}"]').click()`);
    const body = { rooms: 'roomsTableBody', users: 'usersTableBody', files: 'filesTableBody', chat: 'chatTableBody', cdr: 'cdrTableBody' }[tab];
    await browser.wait(`document.getElementById('${body}').querySelector('td') !== null`);
  };
  const refresh = async () => {
    await browser.wait('!document.getElementById("refreshAll").disabled');
    await browser.evaluate('document.getElementById("refreshAll").click()');
    await browser.wait('!document.getElementById("refreshAll").disabled');
  };
  try {
    await browser.open(origin + '/admin.html');
    await t.test('login gate fetches no management data; ordinary user is rejected', async () => {
      assert.equal(state.requests.length, 0);
      assert.equal(await browser.evaluate('document.getElementById("loginForm").method'), 'post');
      await browser.evaluate(`document.getElementById('adminUser').value='1002'; document.getElementById('adminPassword').value='1002'; document.getElementById('loginForm').requestSubmit()`);
      await browser.wait('document.getElementById("loginError").textContent.includes("1001")');
      assert.equal(await browser.evaluate('document.getElementById("workbench").hidden'), true);
      await browser.screenshot('workbench-login.png');
    });
    await t.test('admin login refreshes only active panel and safely renders hostile text', async () => {
      await browser.evaluate(`document.getElementById('adminUser').value='1001'; document.getElementById('adminPassword').value='1001'; document.getElementById('loginForm').requestSubmit()`);
      await browser.wait('!document.getElementById("workbench").hidden && document.querySelector("#cdrTableBody button") !== null');
      await browser.wait('!document.getElementById("refreshAll").disabled');
      await browser.evaluate('document.getElementById("autoRefreshToggle").click()');
      assert.equal(state.requests.some(item => /\/(rooms|files|users)$/.test(item.path)), false);
      assert.equal(await browser.evaluate('document.querySelectorAll("tbody img,tbody svg,tbody script").length'), 0);
      assert.equal(await browser.evaluate('window.injected === undefined'), true);
      assert.equal(await browser.evaluate('document.getElementById("cdrTableBody").textContent.includes("Invalid Date")'), false);
      await browser.screenshot('workbench-cdr.png');
    });
    await t.test('unchanged rows and filters survive refresh; older query cannot overwrite newer query', async () => {
      await browser.evaluate('window.originalRow=document.querySelector("#cdrTableBody tr")');
      await refresh();
      assert.equal(await browser.evaluate('window.originalRow === document.querySelector("#cdrTableBody tr")'), true);
      await browser.evaluate(`document.getElementById('filterCaller').value='slow'; document.getElementById('queryCdr').click()`);
      await sleep(40);
      await browser.evaluate(`document.getElementById('filterCaller').value='fast'; document.getElementById('queryCdr').click()`);
      await browser.wait('document.querySelector("#cdrTableBody button").textContent === "fast"');
      await sleep(500);
      assert.equal(await browser.evaluate('document.querySelector("#cdrTableBody button").textContent'), 'fast');
      await refresh();
      assert.equal(await browser.evaluate('document.getElementById("filterCaller").value'), 'fast');
    });
    await t.test('room capacity is fixed; clipboard failure is truthful; double submit creates once', async () => {
      await clickTab('rooms');
      assert.equal(await browser.evaluate('document.getElementById("roomMaxMembersInput").readOnly'), true);
      await browser.evaluate(`Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async()=>{throw Error('denied')}}}); document.querySelector('#roomsTableBody button').click()`);
      await browser.wait('document.getElementById("toast").textContent.includes("复制失败")');
      assert.equal(await browser.evaluate('document.querySelectorAll("#roomsTableBody img").length'), 0);
      await browser.evaluate(`document.getElementById('roomNameInput').value='test room'; var form=document.getElementById('roomForm'); form.dispatchEvent(new Event('submit',{cancelable:true})); form.dispatchEvent(new Event('submit',{cancelable:true}))`);
      await browser.wait('document.getElementById("toast").textContent.includes("房间创建成功")');
      assert.equal(state.roomPosts, 1);
      state.roomsFail = true;
      await refresh();
      assert.match(await browser.evaluate('document.getElementById("dataStatus-rooms").textContent'), /刷新失败/);
      assert.match(await browser.evaluate('document.getElementById("roomsTableBody").textContent'), /room-101/);
      state.roomsFail = false;
      await refresh();
    });
    await t.test('online state failure is unknown, health failure clears green status, recovery works', async () => {
      await clickTab('users');
      await browser.wait('document.getElementById("usersTableBody").textContent.includes("后台已登录")');
      state.health = 'failed'; state.online = false;
      await refresh();
      assert.match(await browser.evaluate('document.getElementById("syncStatus").textContent'), /连接异常/);
      assert.match(await browser.evaluate('document.getElementById("eslStatus").textContent'), /未知/);
      assert.match(await browser.evaluate('document.getElementById("usersTableBody").textContent'), /状态未知/);
      assert.equal(await browser.evaluate('document.getElementById("statOnlineUsers").textContent'), '--');
      state.health = 'ok'; state.online = true;
      await refresh();
      assert.match(await browser.evaluate('document.getElementById("syncStatus").textContent'), /已连接/);
      assert.match(await browser.evaluate('document.getElementById("usersTableBody").textContent'), /后台已登录/);
      await browser.evaluate(`document.getElementById('regUsername').value='test-user'; document.getElementById('regPassword').value='secret'; var form=document.getElementById('registerForm'); form.dispatchEvent(new Event('submit',{cancelable:true})); form.dispatchEvent(new Event('submit',{cancelable:true}))`);
      await browser.wait('document.getElementById("toast").textContent.includes("用户注册成功")');
      assert.equal(state.userPosts, 1);
    });
    await t.test('files use authenticated generated download URL; all chat content is plain text', async () => {
      await clickTab('files');
      await browser.evaluate(`HTMLAnchorElement.prototype.click=function(){window.downloadLink={href:this.href,name:this.download}}; document.querySelector('#filesTableBody button').click()`);
      await browser.wait('window.downloadLink !== undefined');
      assert.match(await browser.evaluate('window.downloadLink.href'), /^blob:/);
      assert.equal(await browser.evaluate('window.downloadLink.name'), hostile);
      assert.equal(state.requests.find(item => item.path.endsWith('/files/1')).auth, 'Bearer browser-token');
      await clickTab('chat');
      await browser.wait('document.querySelector("#roomChatTableBody td") !== null');
      assert.equal(await browser.evaluate('document.querySelectorAll("tbody img,tbody svg,tbody script").length'), 0);
      assert.equal(await browser.evaluate('window.injected === undefined'), true);
    });
    await t.test('protected admin, delete cancellation, failure retry and duplicate suppression', async () => {
      await clickTab('users');
      assert.equal(await browser.evaluate(`document.querySelector('[data-delete-user-id="1"]').disabled`), true);
      await browser.evaluate(`window.confirm=()=>false; document.querySelector('[data-delete-user-id="2"]').click()`);
      assert.equal(state.userDeletes, 0);
      state.userDeleteFail = true;
      await browser.evaluate(`window.confirm=()=>true; document.querySelector('[data-delete-user-id="2"]').click()`);
      await browser.wait('document.getElementById("toast").textContent.includes("delete unavailable")');
      await browser.wait(`!document.querySelector('[data-delete-user-id="2"]').disabled`);
      state.userDeleteFail = false;
      await browser.evaluate(`var remove=document.querySelector('[data-delete-user-id="2"]'); remove.click(); remove.click()`);
      await browser.wait(`document.querySelector('[data-delete-user-id="2"]') === null`);
      assert.equal(state.userDeletes, 2, 'one failed request and one successful request');
      assert.equal(await browser.evaluate('document.getElementById("userCountBadge").textContent'), '1 人');
      assert.equal(await browser.evaluate('document.querySelectorAll("tbody img,tbody svg,tbody script").length'), 0);
      await browser.screenshot('workbench-user-deletion.png');
    });
    await t.test('hidden page pauses polling and resumes when visible; nested tab click works', async () => {
      await browser.wait('!document.getElementById("refreshAll").disabled');
      await browser.evaluate(`window.testHidden=true; Object.defineProperty(document,'hidden',{configurable:true,get:()=>window.testHidden}); document.dispatchEvent(new Event('visibilitychange')); document.getElementById('autoRefreshToggle').click()`);
      const count = state.requests.length;
      await sleep(5200);
      assert.equal(state.requests.length, count);
      await browser.evaluate(`window.testHidden=false; document.dispatchEvent(new Event('visibilitychange'))`);
      await browser.wait('!document.getElementById("refreshAll").disabled');
      assert.ok(state.requests.length > count);
      await browser.evaluate(`document.getElementById('autoRefreshToggle').click(); const child=document.createElement('span'); document.querySelector('[data-tab="tab-rooms"]').append(child); child.click()`);
      await browser.wait('document.getElementById("tab-rooms").classList.contains("active")');
    });
    await t.test('slow polling never overlaps requests; logout and expiration clear private rows', async () => {
      const previous = state.requests.filter(item => item.path.endsWith('/cdr')).length;
      await clickTab('cdr');
      // Cached rows and the global refresh button do not describe the tab's separate load.
      // Settle that load before measuring the polling cycle, including its server-side close.
      for (let attempt = 0; attempt < 200 && (state.activeCdr !== 0 ||
          state.requests.filter(item => item.path.endsWith('/cdr')).length === previous); attempt++) await sleep(10);
      assert.equal(state.activeCdr, 0);
      assert.ok(state.requests.filter(item => item.path.endsWith('/cdr')).length > previous);
      await browser.wait('!document.getElementById("refreshAll").disabled');
      state.cdrDelay = 5800; state.maxCdr = 0;
      await browser.evaluate('document.getElementById("refreshAll").click(); document.getElementById("autoRefreshToggle").click()');
      await sleep(5400);
      assert.equal(state.maxCdr, 1);
      await browser.wait('!document.getElementById("refreshAll").disabled');
      state.cdrDelay = 0;
      state.expired = true;
      await refresh();
      await browser.wait('document.getElementById("workbench").hidden && document.getElementById("loginError").textContent.includes("过期")');
      assert.equal(await browser.evaluate('document.querySelectorAll("tbody td").length'), 0);
      assert.equal(await browser.evaluate('sessionStorage.getItem("adminWorkbenchToken")'), null);
    });
    await t.test('session restores on reload and explicit logout clears only this browser session', async () => {
      await browser.evaluate(`document.getElementById('adminUser').value='1001'; document.getElementById('adminPassword').value='1001'; document.getElementById('loginForm').requestSubmit()`);
      await browser.wait('!document.getElementById("workbench").hidden && !document.getElementById("refreshAll").disabled');
      await browser.send('Page.reload', {}, browser.session);
      await browser.wait('document.getElementById("workbench") !== null && !document.getElementById("workbench").hidden');
      assert.ok(state.requests.some(item => item.path === '/api/admin/session'));
      await browser.evaluate('document.getElementById("logoutButton").click()');
      await browser.wait('document.getElementById("workbench").hidden');
      await sleep(80);
      assert.equal(await browser.evaluate('sessionStorage.getItem("adminWorkbenchToken")'), null);
      assert.equal(await browser.evaluate('document.querySelectorAll("tbody td").length'), 0);
      assert.equal(state.requests.filter(item => item.path === '/api/admin/logout').length, 1);
      assert.equal(browser.exceptions.length, 0, JSON.stringify(browser.exceptions));
    });
  } finally {
    await browser.close();
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
});
