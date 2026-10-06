'use strict';
const nativePending = new Map();
let nativeSequence = 0;
function nativeCall(action, data = {}) {
  return new Promise((resolve, reject) => {
    const id = String(++nativeSequence);
    const timeout = setTimeout(() => { nativePending.delete(id); reject(new Error('操作等待超时，请重试')); }, ['selectFiles', 'chooseFolder'].includes(action) ? 30 * 60 * 1000 : action === 'api' ? 180000 : 30000);
    nativePending.set(id, { action, resolve, reject, timeout });
    try { LanDropAndroid.postMessage(JSON.stringify({ id, action, data })); }
    catch (error) { clearTimeout(timeout); nativePending.delete(id); reject(error); }
  });
}
window.nativeResponse = message => {
  const payload = JSON.parse(message), pending = nativePending.get(payload.id);
  if (!pending) return;
  clearTimeout(pending.timeout); nativePending.delete(payload.id);
  payload.error ? pending.reject(new Error(payload.error)) : pending.resolve(payload.result);
};
window.desktop = {
  openExternal: url => nativeCall('openExternal', { url }),
  selectFiles: () => nativeCall('selectFiles'),
  sendFiles: (peerId, files) => nativeCall('sendFiles', { peerId, files }),
  chooseFolder: () => nativeCall('chooseFolder'),
  showFile: id => nativeCall('showFile', { id }),
  copyText: text => nativeCall('copyText', { text }),
  openFolder: async () => { showPage('history'); toast('点击接收记录，可以打开、分享或另存文件'); }
};
const androidEnglish = {
  '只连接你手动添加的设备，配置自动保存': 'Connect only to devices you add. Configuration is saved automatically.',
  '在另一台电脑或安卓手机上安装并打开 LanDrop。': 'Install and open LanDrop on your other computer or Android phone.',
  '1. 在另一台电脑或安卓手机上安装并打开 LanDrop。': '1. Install and open LanDrop on your other computer or Android phone.',
  '选择要发送的文件': 'Choose files to send',
  'LanDrop · 局域传送': 'LanDrop · Share nearby',
  '这台手机': 'This phone', '手机': 'Phone', '设备': 'Device',
  '两台设备都运行软件，手动配置后直接传输。': 'Open LanDrop on both devices and add each other to transfer.',
  '连接另一台设备': 'Connect your devices',
  '开启局域网传输，并设置这台手机的接收目录。': 'Manage LAN transfers and where this phone saves files.',
  '先配置你的另一台设备': 'Add your other device',
  '两台设备都运行局域传送，在“配置设备”中互相添加 IP 和端口。': 'Open LanDrop on both devices and add each other’s IP and port in Devices.',
  '配置已保存；请在另一台软件里也添加这台手机': 'Device saved. Add this phone on the other device too.',
  '点击接收记录，可以打开、分享或另存文件': 'Tap a received file to open, share or save a copy.',
  '点击选择要分享的文件': 'Choose something to share',
  '可从系统分享菜单发送文件；长按输入框粘贴文字。': 'Share files to LanDrop from other apps, or long-press to paste text.',
  '请长按输入框粘贴文字': 'Long-press the text field to paste.',
  '点击粘贴文字；图片可通过系统分享菜单发送': 'Paste text here, or share an image to LanDrop from another app.',
  '接收位置': 'Receive location',
  '选择文件夹': 'Choose folder',
  '选择本机文件夹后，接收文件会直接保存到那里。默认应用文件夹会随卸载删除，请先另存重要文件。': 'Choose a local folder to receive files there. The default app folder is deleted when uninstalling; save important files elsewhere first.',
  '让另一台设备也打开局域传送': 'Open LanDrop on the other device too',
  '单个文件上限 20 GB · 双方互相配置后直接接收 · 无需登录': 'Up to 20 GB per file · Add each other to receive directly · No sign-in',
  '手机与电脑、手机与手机，在同一局域网直接互传。后台接收时显示通知；从通知可停止传输。当前内容未端到端加密，请在可信网络中使用。': 'Transfer between phones and computers on the same LAN. A notification shows background receiving and lets you stop it. Transfers are not end-to-end encrypted; use a trusted network.',
  '停止传输并退出': 'Stop transfers and quit',
  '退出后将停止接收，并取消正在进行的传输。继续？': 'Quitting stops receiving and cancels active transfers. Continue?',
  '退出': 'Quit', '安卓版本': 'Android version',
  '请先完成或取消传输，再更改接收文件夹': 'Finish or cancel transfers before changing the receive folder.',
  '操作等待超时，请重试': 'The operation timed out. Please try again.',
  '传输服务已停止，请重新打开应用': 'The transfer service stopped. Reopen the app.',
  '无法确定文件大小，请将文件下载到本机后重试': 'Download the file to this device before sending it.',
  '文件选择已失效，请重新选择': 'Choose the file again before sending.',
  '应用已停止，传输未完成': 'The app stopped before the transfer finished.',
  '接收文件夹权限已失效，暂存到应用文件夹；请重新选择接收位置': 'Receive folder permission was lost. Using the app folder temporarily; choose your receive location again.',
  '对方端口拒绝连接，请打开对方软件并核对地址': 'Connection refused. Open LanDrop on the other device and check its address.',
  '连接或传输超时，请检查地址、网络和防火墙': 'Connection or transfer timed out. Check addresses, the network and firewall.',
  '请在两台软件里互相添加 IP 和端口': 'Add each other’s IP and port on both devices.',
  '这是当前设备，请添加另一台设备': 'This is your current device. Add the other one.',
  '设备身份已改变，请重新添加设备': 'The device identity changed. Add it again.',
  '无法读取文件': 'Unable to read the file.',
  '无法创建接收文件': 'Unable to create the received file.',
  '接收文件夹不可写，请重新选择': 'The receive folder is not writable. Choose another folder.',
  '接收文件夹不支持完成文件保存，请选择本机文件夹': 'Choose a local folder that supports saving completed files.',
  '文件大小已改变，请重新选择文件': 'The file size changed. Choose the file again.',
  '无法创建接收文件夹': 'Unable to create the receive folder.',
  '同名文件过多': 'Too many files with the same name.',
  '传输队列已满': 'The transfer queue is full.',
  '文件选择器已打开': 'The file picker is already open.',
  '文件夹选择器已打开': 'The folder picker is already open.'
};
for (const [key, value] of Object.entries(LanDropI18n.en)) {
  const phoneKey = key.replaceAll('两台电脑', '两台设备').replaceAll('另一台电脑', '另一台设备').replaceAll('这台电脑', '这台手机');
  if (phoneKey !== key) LanDropI18n.en[phoneKey] = value.replace(/computers/g, 'devices').replace(/computer/g, phoneKey.includes('这台手机') ? 'phone' : 'device');
}
Object.assign(LanDropI18n.en, androidEnglish);
