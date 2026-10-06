const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../app/src/main/assets');
const source = process.argv[2] || process.env.LANDROP_DESKTOP_PUBLIC || path.resolve(__dirname, '../../PCdeliver/public');
let html = fs.readFileSync(path.join(source, 'index.html'), 'utf8').replace(/\r\n/g, '\n');
html = html.replace('</head>', '<link rel="stylesheet" href="/mobile.css"></head>')
  .replace('<body>', '<body><header class="mobile-brand"><img src="/logo.png" alt="局域传送少女形象"><div><strong>LanDrop</strong><small>CONNECT. SHARE. SIMPLIFY.</small></div><span>安卓版本</span></header>')
  .replace('<script src="/app.js">', '<script src="/native.js"></script>\n<script src="/app.js">')
  .replaceAll('两台电脑', '两台设备').replaceAll('另一台电脑', '另一台设备').replaceAll('这台电脑', '这台手机')
  .replace('把文件拖到这里', '点击选择要分享的文件')
  .replace('可粘贴文字或截图；截图会作为图片文件发送。', '可从系统分享菜单发送文件；长按输入框粘贴文字。')
  .replace('接收文件夹<svg class="small"', '接收位置<svg class="small"')
  .replace('Windows 设备', 'Android 设备')
  .replace('只连接你手动添加的电脑，配置自动保存', '只连接你手动添加的设备，配置自动保存')
  .replace('在另一台 Windows 电脑上安装并打开同一份软件。', '在另一台电脑或安卓手机上安装并打开 LanDrop。')
  .replace('选择或拖入文件', '选择要发送的文件')
  .replaceAll('v0.4.3', 'v0.1.2').replace('0.4.3 · 中英文切换版', '0.1.2 · Android');
html = html.replace(/(<[^>]*id="choose-folder"[^>]*>)[\s\S]*?(<\/button>)/, '$1选择文件夹$2')
  .replace(/(<[^>]*id="folder-help"[^>]*>)[\s\S]*?(<\/p>)/, '$1选择本机文件夹后，接收文件会直接保存到那里。默认应用文件夹会随卸载删除，请先另存重要文件。$2');
html = html.replace(/单个文件上限 20 GB[^<]*无需登录/, '单个文件上限 20 GB · 双方互相配置后直接接收 · 无需登录');
html = html.replace(/右下角托盘图标[^<]+/, '手机与电脑、手机与手机，在同一局域网直接互传。后台接收时显示通知；从通知可停止传输。当前内容未端到端加密，请在可信网络中使用。');
html = html.replace('设备之间直接通信。手机与电脑', '手机与电脑');
html = html.replace('</form></section>\n      <div class="panel about-panel"', '</form><div class="quit-row"><span>停止传输并退出</span><button id="quit-app" class="button secondary">退出</button></div></section>\n      <div class="panel about-panel"');
if (!html.includes('quit-app')) {
  html = html.replace('<div class="panel about-panel"', '<div class="quit-row"><span>停止传输并退出</span><button id="quit-app" class="button secondary">退出</button></div><div class="panel about-panel"');
}
fs.writeFileSync(path.join(root, 'index.html'), html);
let app = fs.readFileSync(path.join(source, 'app.js'), 'utf8').replace(/\r\n/g, '\n');
const replace = (before, after) => {
  before = before.replace(/\r\n/g, '\n');
  if (!app.includes(before)) throw new Error('Expected UI source was not found: ' + before.substring(0, 100));
  app = app.replace(before, after);
};
replace("  const response = await fetch(route, { method, headers: { 'X-App-Token': state.uiToken, 'Content-Type': 'application/json' }, ...(data !== undefined ? { body: JSON.stringify(data) } : {}) });\n  const result = await response.json(); if (!response.ok) throw new Error(result.error || t('操作失败')); return result;".replaceAll('\n', '\r\n'),
  "  const result = await nativeCall('api', { route, method, body: data });\n  applyState(await nativeCall('state')); return result;");
app = app.replaceAll('两台电脑', '两台设备').replaceAll('另一台电脑', '另一台设备').replaceAll('这台电脑', '这台手机');
replace("{ win32: 'Windows', darwin: 'macOS', linux: 'Linux' }", "{ win32: 'Windows', darwin: 'macOS', linux: 'Linux', android: 'Android' }");
replace("|| t('电脑')", "|| t('设备')");
replace(`function renderPeers() {
  const online = state.peers.filter(p => p.online);
  $('peer-count').textContent = state.peers.length; $('nearby-count').textContent = state.peers.length;
  $('nearby-devices').innerHTML = peerCards(state.peers); $('all-devices').innerHTML = peerCards(state.peers);
  $('target-device').innerHTML = \`<option value="">\${t('请选择一台设备')}</option>\` + online.map(p => \`<option value="\${escapeHtml(p.id)}">\${escapeHtml(p.name)}</option>\`).join('');
  $('target-device').value = selectedPeer;
}`, `let renderedPeerCards;
function renderPeers() {
  const online = state.peers.filter(p => p.online);
  if (selectedPeer && !online.some(peer => peer.id === selectedPeer)) selectedPeer = '';
  for (const id of ['peer-count', 'nearby-count']) {
    if ($(id).textContent !== String(state.peers.length)) $(id).textContent = state.peers.length;
  }
  const cards = peerCards(state.peers);
  if (cards !== renderedPeerCards) {
    $('nearby-devices').innerHTML = cards; $('all-devices').innerHTML = cards;
    renderedPeerCards = cards;
  }
  // Keep native select options intact across status polls: WebView redraws
  // the control when its children are replaced, even with identical markup.
  const target = $('target-device');
  const desired = [['', t('请选择一台设备')], ...online.map(peer => [peer.id, peer.name])];
  const existing = new Map([...target.options].map(option => [option.value, option]));
  const ids = new Set(desired.map(([id]) => id));
  for (const option of [...target.options]) if (!ids.has(option.value)) option.remove();
  desired.forEach(([id, name], index) => {
    let option = existing.get(id);
    if (!option) { option = document.createElement('option'); option.value = id; }
    if (option.textContent !== name) option.textContent = name;
    if (target.options[index] !== option) target.insertBefore(option, target.options[index] || null);
  });
  if (target.value !== selectedPeer) target.value = selectedPeer;
}`);
replace("t('连接另一台设备')", "t('连接另一台设备')");
replace("async function paste() { try { const text = await navigator.clipboard.readText(); $('text-content').value = text.slice(0, 50000); $('text-content').dispatchEvent(new Event('input')); } catch { toast('请在输入框中按 Ctrl + V 粘贴', true); $('text-content').focus(); } }",
  "async function paste() { try { const { text } = await nativeCall('pasteText'); $('text-content').value = text.slice(0, 50000); $('text-content').dispatchEvent(new Event('input')); } catch { toast('请长按输入框粘贴文字', true); $('text-content').focus(); } }");
replace("toast('按 Ctrl + V 粘贴文字或截图')", "toast('点击粘贴文字；图片可通过系统分享菜单发送')");
replace("language = nextLanguage; translateStatic(); showPage", "language = nextLanguage; translateStatic(); if (!$('connect-button').disabled) $('connect-button').innerHTML = icon('plus') + t('保存设备'); showPage");
replace("  currentPage = page;", "  const pageChanged = currentPage !== page; currentPage = page;");
replace("  if (page === 'settings' && syncForm)", "  if (pageChanged) window.scrollTo(0, 0);\n  if (page === 'settings' && syncForm)");
replace("window.desktop ? t('打开位置') : t('下载文件')", "t('查看接收文件')");
replace("    const response = await fetch('/api/state');\r\n    if (!response.ok) throw new Error(t('无法连接本机服务'));\r\n    applyState(await response.json()); toast(t('语言已切换'));",
  "    applyState(await nativeCall('state')); toast(t('语言已切换'));");
const initStart = app.indexOf('async function init() {');
app = app.slice(0, initStart) + `let polling = false;
async function pollState() {
  if (polling) return;
  polling = true;
  try { applyState(await nativeCall('state')); }
  catch (error) { $('network-pill').classList.add('disconnected'); $('network-pill').querySelector('span').textContent = t('服务连接失败'); }
  finally { polling = false; }
}
async function init() {
  try {
    applyState(await nativeCall('state'));
    $('settings-name').value = state.self.name; $('lan-enabled').checked = state.settings.enabled;
    setInterval(pollState, 600);
  } catch (error) { toast(error.message, true); }
}
window.receiveShared = payload => {
  const shared = JSON.parse(payload); showPage('home');
  if (shared.files.length) { selectedFiles.push(...shared.files); changeTab('files'); renderFiles(); }
  else if (shared.text) { changeTab('text'); $('text-content').value = shared.text.slice(0, 50000); $('text-content').dispatchEvent(new Event('input')); }
};
window.androidBack = () => {
  const dialog = document.querySelector('dialog[open]');
  if (dialog) { dialog.close(); return true; }
  if (currentPage !== 'home') { showPage('home'); return true; }
  return false;
};
$('quit-app').addEventListener('click', () => { if (confirm(t('退出后将停止接收，并取消正在进行的传输。继续？'))) nativeCall('stop').catch(error => toast(error.message, true)); });
init();
`;
fs.writeFileSync(path.join(root, 'app.js'), app);
for (const filename of ['styles.css', 'i18n.js', 'community-links.js', 'community.js']) fs.copyFileSync(path.join(source, filename), path.join(root, filename));
fs.copyFileSync(path.join(root, 'logo.png'), path.resolve(__dirname, '../app/src/main/res/drawable/landrop_icon.png'));
console.log('Prepared phone UI and shared assets.');
