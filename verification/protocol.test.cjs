const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { spawn } = require('node:child_process');
const readline = require('node:readline');
const crypto = require('node:crypto');
const http = require('node:http');
const net = require('node:net');
const desktopSource = process.env.LANDROP_DESKTOP_SOURCE || path.resolve(__dirname, '../../PCdeliver/server/app.js');
const { createApp } = require(desktopSource);
const runRoot = path.resolve(__dirname, 'runs', String(Date.now()));
const java = process.env.LANDROP_JAVA || 'java';
const classpath = path.join(__dirname, 'classes') + path.delimiter + path.join(__dirname, 'json.jar');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const result = await fn(); if (result) return result; await wait(60); }
  throw new Error('Timed out waiting for transfer state');
}
async function android(folder, port = 0) {
  const child = spawn(java, ['-cp', classpath, 'ProtocolHarness', folder, String(port)], { windowsHide: true });
  const pending = new Map(); let sequence = 0; let stderr = '';
  child.stderr.on('data', data => stderr += data);
  const info = await new Promise((resolve, reject) => {
    const lines = readline.createInterface({ input: child.stdout });
    let first = true;
    lines.on('line', line => {
      const data = JSON.parse(line);
      if (first) { first = false; resolve(data); }
      else { const callback = pending.get(data.requestId); if (callback) { pending.delete(data.requestId); data.error ? callback.reject(new Error(data.error)) : callback.resolve(data.result); } }
    });
    child.on('exit', code => {
      if (first) reject(new Error('Harness failed: ' + code + ' ' + stderr));
      for (const callback of pending.values()) callback.reject(new Error('Harness exited: ' + stderr));
    });
  });
  return { info, call: (action, data = {}) => new Promise((resolve, reject) => { const requestId = String(++sequence); pending.set(requestId, { resolve, reject }); child.stdin.write(JSON.stringify({ requestId, action, ...data }) + '\n'); }), close: async () => { child.stdin.end(); await new Promise(resolve => child.once('exit', resolve)); } };
}
async function local(app, route, body, method = 'POST') {
  return wire(app.port, route, body, method, { 'X-App-Token': app.state().uiToken });
}
async function wire(port, route, body, method = 'POST', headers = {}) {
  const bytes = body === undefined ? null : Buffer.from(JSON.stringify(body));
  return new Promise((resolve, reject) => {
    const request = http.request({ host: '127.0.0.1', port, path: route, method, headers: { ...headers, ...(bytes ? { 'Content-Type': 'application/json', 'Content-Length': bytes.length } : {}) } }, response => {
      let text = ''; response.on('data', data => text += data); response.on('end', () => resolve({ status: response.statusCode, body: JSON.parse(text) }));
    }); request.on('error', reject); request.end(bytes);
  });
}
async function finished(peer, id) { return until(async () => { const state = await peer.call('state'); const job = state.transfers.find(job => job.id === id); return job && ['completed', 'failed', 'cancelled'].includes(job.status) ? job : null; }); }
test('Android core interoperates with desktop and another Android core', { timeout: 90000 }, async t => {
  await fs.mkdir(runRoot, { recursive: true });
  const desktop = await createApp({ port: 0, dataDir: path.join(runRoot, 'desktop'), name: 'Windows interoperability test' });
  let a = await android(path.join(runRoot, 'android-a'));
  const b = await android(path.join(runRoot, 'android-b'));
  const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
  try {
    await t.test('unconfigured sender is rejected and credentials are checked', async () => {
      const token = a.info.token;
      const meta = { sender: desktop.info(), kind: 'text', name: '文字消息', size: 3, text: 'abc' };
      assert.equal((await wire(a.info.port, '/peer/request', meta, 'POST', { 'X-Lan-Token': token })).status, 403);
      assert.equal((await wire(a.info.port, '/peer/request', meta, 'POST', { 'X-Lan-Token': 'bad' })).status, 403);
      assert.equal((await wire(a.info.port, '/api/state', undefined, 'GET')).status, 404);
    });
    await a.call('peer', { address: '127.0.0.1:' + desktop.port });
    assert.equal((await local(desktop, '/api/peers', { address: '127.0.0.1:' + a.info.port })).status, 200);
    await t.test('desktop sends Unicode text to Android', async () => {
      const text = '电脑 → 安卓 🌿\n中文 English <script> stays text';
      const result = await desktop.sendText(a.info.id, text); assert.equal(result.status, 'completed');
      const state = await a.call('state'); assert.equal(state.transfers.at(-1).text, text); assert.equal(state.transfers.at(-1).status, 'completed');
    });
    await t.test('Android sends Unicode text to desktop', async () => {
      const text = '安卓 → 电脑 ✨\n中英文保持原样';
      const id = await a.call('text', { peerId: desktop.info().id, text }); assert.equal((await finished(a, id)).status, 'completed');
      assert.equal(desktop.state().transfers.at(-1).text, text);
    });
    const bytes = crypto.randomBytes(2 * 1024 * 1024 + 319);
    const filename = path.join(runRoot, '照片 sample.bin'); await fs.writeFile(filename, bytes);
    await t.test('desktop sends binary files with SHA-256 and numbered duplicates', async () => {
      for (let i = 0; i < 2; i++) assert.equal((await desktop.sendLocalFile(a.info.id, filename)).status, 'completed');
      const jobs = (await a.call('state')).transfers.filter(job => job.direction === 'incoming' && job.kind === 'file');
      assert.equal(jobs.length, 2); assert.notEqual(jobs[0].path, jobs[1].path);
      for (const job of jobs) { assert.equal(job.sha256, digest(bytes)); assert.deepEqual(await fs.readFile(job.path), bytes); }
    });
    await t.test('Android sends binary file to desktop', async () => {
      const id = await a.call('file', { peerId: desktop.info().id, path: filename }); assert.equal((await finished(a, id)).status, 'completed');
      const received = desktop.state().transfers.at(-1); assert.deepEqual(await fs.readFile(received.path), bytes); assert.equal(received.sha256, digest(bytes));
    });
    await t.test('zero-byte files are valid in both directions', async () => {
      const empty = path.join(runRoot, 'empty.bin'); await fs.writeFile(empty, Buffer.alloc(0));
      assert.equal((await desktop.sendLocalFile(a.info.id, empty)).status, 'completed');
      const id = await a.call('file', { peerId: desktop.info().id, path: empty }); assert.equal((await finished(a, id)).status, 'completed');
    });
    await t.test('Android to Android file and text transfers', async () => {
      await a.call('peer', { address: '127.0.0.1:' + b.info.port }); await b.call('peer', { address: '127.0.0.1:' + a.info.port });
      const id = await a.call('file', { peerId: b.info.id, path: filename }); assert.equal((await finished(a, id)).status, 'completed');
      const received = (await b.call('state')).transfers.at(-1); assert.deepEqual(await fs.readFile(received.path), bytes);
      const textId = await b.call('text', { peerId: a.info.id, text: '手机 ↔ 手机，局域网直连' }); assert.equal((await finished(b, textId)).status, 'completed');
    });
    await t.test('disabled receiving rejects incoming transfer', async () => {
      await a.call('command', { route: '/api/settings', body: { enabled: false } });
      const result = await desktop.sendText(a.info.id, 'must fail'); assert.notEqual(result.status, 'completed');
      await a.call('command', { route: '/api/settings', body: { enabled: true } });
    });
    await t.test('truncated upload cleans incomplete files', async () => {
      const info = (await wire(a.info.port, '/peer/info', undefined, 'GET')).body;
      const prepared = await wire(a.info.port, '/peer/request', { sender: desktop.info(), kind: 'file', name: 'broken.bin', size: 1000000 }, 'POST', { 'X-Lan-Token': info.token });
      assert.equal(prepared.status, 200);
      const socket = net.connect(a.info.port, '127.0.0.1');
      await new Promise(resolve => socket.once('connect', resolve));
      socket.write(`POST /peer/upload/${prepared.body.id} HTTP/1.1\r\nHost: 127.0.0.1:${a.info.port}\r\nX-Lan-Token: ${info.token}\r\nContent-Length: 1000000\r\n\r\n`);
      socket.write(crypto.randomBytes(10000)); socket.end();
      assert.equal((await finished(a, prepared.body.id)).status, 'failed');
      await until(async () => !(await fs.readdir(path.join(runRoot, 'android-a', 'received'))).some(name => name === 'broken.bin' || name.endsWith('.part')));
    });
    await t.test('receiver cancellation closes stream and cleans incomplete files', async () => {
      const info = (await wire(a.info.port, '/peer/info', undefined, 'GET')).body;
      const prepared = await wire(a.info.port, '/peer/request', { sender: desktop.info(), kind: 'file', name: 'cancelled.bin', size: 1000000 }, 'POST', { 'X-Lan-Token': info.token });
      const socket = net.connect(a.info.port, '127.0.0.1'); socket.on('error', () => {}); await new Promise(resolve => socket.once('connect', resolve));
      socket.write(`POST /peer/upload/${prepared.body.id} HTTP/1.1\r\nHost: 127.0.0.1:${a.info.port}\r\nX-Lan-Token: ${info.token}\r\nContent-Length: 1000000\r\n\r\n`); socket.write(Buffer.alloc(10000));
      await until(async () => (await a.call('state')).transfers.find(job => job.id === prepared.body.id)?.status === 'transferring');
      await a.call('command', { route: '/api/cancel', body: { id: prepared.body.id } });
      assert.equal((await finished(a, prepared.body.id)).status, 'cancelled'); socket.destroy();
      await until(async () => !(await fs.readdir(path.join(runRoot, 'android-a', 'received'))).some(name => name === 'cancelled.bin' || name.endsWith('.part')));
    });
    await t.test('deleted text and language settings survive restart; identity remains compatible', async () => {
      const state = await a.call('state'), job = state.transfers.find(job => job.kind === 'text');
      await a.call('command', { route: '/api/history/' + job.id, method: 'DELETE' });
      await a.call('command', { route: '/api/settings', body: { language: 'en', name: 'Android saved name' } });
      const oldInfo = a.info; await a.call('close');
      a = await android(path.join(runRoot, 'android-a'), oldInfo.port);
      assert.equal(a.info.id, oldInfo.id); assert.notEqual(a.info.token, oldInfo.token);
      const restored = await a.call('state'); assert.equal(restored.settings.language, 'en'); assert.equal(restored.self.name, 'Android saved name'); assert.equal(restored.transfers.some(item => item.id === job.id), false);
      assert.equal((await desktop.sendText(a.info.id, 'restart works')).status, 'completed');
    });
    await fs.writeFile(path.join(runRoot, 'result.json'), JSON.stringify({ passed: true, desktopAndroid: true, androidAndroid: true }, null, 2));
  } finally { await desktop.close(); await a.call('close').catch(() => {}); await b.call('close').catch(() => {}); }
});
