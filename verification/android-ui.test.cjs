const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { createApp } = require(process.env.LANDROP_DESKTOP_SOURCE || path.resolve(__dirname, '../../PCdeliver/server/app.js'));
const adb = process.env.LANDROP_ADB || 'adb';
const root = path.resolve(__dirname, 'android-ui-' + Date.now());
const run = args => execFileSync(adb, args, { encoding: 'utf8', windowsHide: true });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function until(fn, timeout = 20000) {
  const deadline = Date.now() + timeout; while (Date.now() < deadline) { const result = await fn(); if (result) return result; await delay(100); } throw new Error('Timed out waiting for Android');
}
function nativeNodes() {
  run(['shell', 'uiautomator', 'dump', '/sdcard/landrop-qa.xml']);
  return [...run(['shell', 'cat', '/sdcard/landrop-qa.xml']).matchAll(/<node\b([^>]+)>/g)].map(match => Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(item => [item[1], item[2]])));
}
function tapNative(label) {
  const nodes = nativeNodes();
  const target = nodes.filter(node => (node.text || '').toLowerCase() === label.toLowerCase() || (node['content-desc'] || '').toLowerCase() === label.toLowerCase()).at(-1);
  if (!target) throw new Error('Native control not found: ' + label + '. Visible: ' + nodes.map(node => node.text || node['content-desc']).filter(Boolean).join(' | '));
  const [left, top, right, bottom] = target.bounds.match(/\d+/g).map(Number);
  run(['shell', 'input', 'tap', String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2))]);
}
async function screenshot(name) { run(['shell', 'screencap', '-p', '/sdcard/landrop-qa.png']); run(['pull', '/sdcard/landrop-qa.png', path.join(root, name + '.png')]); }
(async () => {
  await fs.mkdir(root, { recursive: true });
  const desktop = await createApp({ port: 45890, name: 'Windows QA', dataDir: path.join(root, 'desktop') });
  let browser;
  try {
    browser = await chromium.connectOverCDP('http://127.0.0.1:9223');
    const page = browser.contexts()[0].pages()[0];
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    const state = () => page.evaluate(() => nativeCall('state'));
    await page.waitForFunction(() => typeof state !== 'undefined' && state);
    await page.locator('.nav-item[data-page=devices]').click();
    await page.locator('#peer-address').fill('127.0.0.1:45890'); await page.locator('#connect-button').click();
    console.log('Desktop port:', desktop.port);
    await until(async () => (await state()).peers.some(peer => peer.id === desktop.info().id));
    const added = await fetch('http://127.0.0.1:45890/api/peers', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-App-Token': desktop.state().uiToken }, body: JSON.stringify({ address: '127.0.0.1:45878' }) }); assert.equal(added.status, 200);
    const androidId = (await state()).self.id;
    await page.locator('.nav-item[data-page=home]').click();
    await page.locator('#target-device').selectOption(desktop.info().id);
    await page.locator('.tab[data-tab=text]').click(); await page.locator('#text-content').fill('真实 APK → 电脑 🌿\n中文 English'); await page.locator('#send-button').click();
    await until(() => desktop.state().transfers.some(job => job.status === 'completed' && job.text === '真实 APK → 电脑 🌿\n中文 English'));
    assert.equal((await desktop.sendText(androidId, '电脑 → 真实 APK ✨')).status, 'completed');
    await until(async () => (await state()).transfers.some(job => job.status === 'completed' && job.text === '电脑 → 真实 APK ✨'));
    console.log('PASS: actual APK and desktop exchange Unicode text.');
    const bytes = crypto.randomBytes(512 * 1024 + 25), source = path.join(root, 'LanDrop QA sample.bin'); await fs.writeFile(source, bytes);
    assert.equal((await desktop.sendLocalFile(androidId, source)).status, 'completed');
    const received = (await state()).transfers.filter(job => job.direction === 'incoming' && job.kind === 'file').at(-1);
    const receivePath = received.path.startsWith('content://') ? '/sdcard/' + decodeURIComponent(received.path.split('/document/').at(-1)).replace(/^primary:/, '') : received.path;
    run(['pull', receivePath, path.join(root, 'received-default.bin')]); assert.equal(digest(await fs.readFile(path.join(root, 'received-default.bin'))), digest(bytes));
    console.log('PASS: actual APK receives a file with matching bytes.');
    run(['push', source, '/sdcard/Download/LanDrop QA sample.bin']);
    await page.locator('.tab[data-tab=files]').click(); await page.locator('#choose-files').click();
    await delay(500);
    tapNative('Show roots'); await delay(300); tapNative('Downloads'); await delay(300);
    let nodes = nativeNodes();
    tapNative('LanDrop QA sample.bin');
    await page.waitForFunction(() => selectedFiles.some(file => file.name === 'LanDrop QA sample.bin'));
    await page.locator('#send-button').click();
    const outgoing = await until(() => desktop.state().transfers.find(job => job.kind === 'file' && job.direction === 'incoming' && job.status === 'completed'));
    assert.equal(digest(await fs.readFile(outgoing.path)), digest(bytes));
    console.log('PASS: system document picker selects and sends an original file.');
    await page.locator('.nav-item[data-page=settings]').click();
    await page.locator('#settings-language').selectOption('en'); await page.waitForFunction(() => document.documentElement.lang === 'en');
    for (const view of ['home', 'devices', 'history', 'settings']) {
      await page.locator(`.nav-item[data-page=${view}]`).click();
      const layout = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth })); assert.equal(layout.width, layout.scroll, view + ' must not overflow horizontally');
      const chinese = await page.evaluate(() => {
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); const texts = [];
        while (walker.nextNode()) { const element = walker.currentNode.parentElement; if (element.closest('script,style,pre,#receive-directory,.local-interface,.peer-info,.transfer-error')) continue; if (element.getClientRects().length && /[\u4e00-\u9fff]/.test(walker.currentNode.textContent)) texts.push(walker.currentNode.textContent.trim()); } return texts;
      });
      assert.deepEqual(chinese, [], 'All interface text should translate on ' + view);
      await screenshot(view + '-en');
    }
    assert.deepEqual(errors, []); console.log('PASS: language switching, four page layouts and JavaScript execution.');
    // Choosing a subfolder tests persisted write access rather than Android's restricted Download root.
    run(['shell', 'mkdir', '-p', '/sdcard/Download/LanDropQA']);
    await page.locator('#choose-folder').click(); await delay(500);
    nodes = nativeNodes();
    if (!nodes.some(node => node.text === 'LanDropQA')) {
      if (nodes.some(node => node.text === 'Download')) tapNative('Download');
      else { tapNative('Show roots'); await delay(300); tapNative((await state()).self.name); await delay(300); tapNative('Download'); }
      await delay(300);
    }
    tapNative('LanDropQA'); await delay(300); tapNative('Use this folder'); await delay(300); tapNative('Allow');
    await page.waitForFunction(() => ![...nativePending.values()].some(pending => pending.action === 'chooseFolder'));
    await until(async () => (await state()).settings.receiveDir.includes('LanDropQA'));
    const savedTransfer = await desktop.sendLocalFile(androidId, source);
    assert.equal(savedTransfer.status, 'completed', savedTransfer.error);
    const safJob = (await state()).transfers.filter(job => job.direction === 'incoming' && job.kind === 'file').at(-1);
    const safPath = '/sdcard/' + decodeURIComponent(safJob.path.split('/document/').at(-1)).replace(/^primary:/, '');
    run(['pull', safPath, path.join(root, 'received-saf.bin')]); assert.equal(digest(await fs.readFile(path.join(root, 'received-saf.bin'))), digest(bytes));
    console.log('PASS: selected directory receives matching file bytes through SAF.');
    await page.locator('#settings-language').selectOption('zh-CN'); await page.waitForFunction(() => document.documentElement.lang === 'zh-CN');
    await page.locator('.nav-item[data-page=home]').click(); await page.locator('.tab[data-tab=files]').click(); await screenshot('home-zh');
    // Home keeps the service active while the activity moves into the background.
    run(['shell', 'input', 'keyevent', 'KEYCODE_HOME']);
    assert.equal((await desktop.sendText(androidId, '后台接收验证')).status, 'completed');
    run(['shell', 'am', 'start', '-n', 'com.deepseeklover.landrop/.MainActivity']);
    await until(async () => (await state()).transfers.some(job => job.text === '后台接收验证' && job.status === 'completed'));
    console.log('PASS: background receiving works with the activity hidden.');
    await fs.writeFile(path.join(root, 'result.json'), JSON.stringify({ passed: true, tests: ['apkTextBothWays', 'apkFilesBothWays', 'systemFilePicker', 'language', 'fourPageLayouts', 'safDirectory', 'backgroundReceiving'], errors }, null, 2));
    console.log('Screenshots and results:', root);
  } finally { if (browser) await browser.close(); await desktop.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
