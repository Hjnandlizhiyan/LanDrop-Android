const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9223');
  const page = browser.contexts()[0].pages()[0];
  try {
    await page.waitForFunction(() => typeof state !== 'undefined' && state);
    await page.evaluate(() => {
      window.selectMutations = 0;
      window.selectObserver = new MutationObserver(records => { selectMutations += records.length; });
      selectObserver.observe(document.getElementById('target-device'), { childList: true, subtree: true, attributes: true, characterData: true });
    });
    await page.waitForTimeout(3300);
    const idleMutations = await page.evaluate(() => { selectObserver.disconnect(); return selectMutations; });
    if (process.argv.includes('--baseline')) {
      assert.ok(idleMutations > 0, 'Original APK reproduces repeated select redraws');
      console.log(JSON.stringify({ originalApkIdleMutations: idleMutations }));
      return;
    }
    assert.equal(idleMutations, 0, 'Unchanged real APK polling must not rewrite select options');
    const result = await page.evaluate(async () => {
      const originalNativeCall = nativeCall;
      const originalPage = currentPage;
      const actual = await originalNativeCall('state');
      let fixture = structuredClone(actual);
      nativeCall = (action, data) => action === 'state' ? Promise.resolve(structuredClone(fixture)) : originalNativeCall(action, data);
      const select = document.getElementById('target-device');
      const check = (condition, description) => { if (!condition) throw new Error(description); };
      try {
        fixture.settings.language = 'zh-CN';
        fixture.peers = [{ id: 'qa-phone', name: '测试手机 <A&B>', address: '192.168.1.20', port: 45878, platform: 'android', online: true, enabled: true }];
        fixture.transfers = [];
        applyState(structuredClone(fixture)); choosePeer('qa-phone'); showPage('home');
        const placeholder = select.options[0], phone = select.options[1];
        select.focus();
        window.selectMutations = 0; selectObserver.observe(select, { childList: true, subtree: true, attributes: true, characterData: true });
        for (let i = 0; i < 60; i++) {
          fixture.peers[0].lastSeen = Date.now() + i;
          fixture.transfers = [{ id: 'qa-progress', direction: 'outgoing', kind: 'file', name: 'sample.bin', peerName: '测试手机', size: 1000, status: 'transferring', progress: i, speed: 100, createdAt: Date.now() }];
          applyState(structuredClone(fixture));
        }
        await new Promise(resolve => setTimeout(resolve, 3300));
        selectObserver.disconnect();
        check(selectMutations === 0, 'Progress and heartbeat updates rewrote select options');
        check(select.value === 'qa-phone' && select.options[1] === phone && select.options[0] === placeholder, 'Selection and option nodes must remain intact');
        check(document.activeElement === select, 'Status polling must preserve focus');
        check(phone.textContent === '测试手机 <A&B>', 'Device names must render as plain text');
        fixture.peers[0].name = '重命名的手机'; applyState(structuredClone(fixture));
        check(select.options[1] === phone && phone.textContent === '重命名的手机', 'Rename must update the existing option');
        fixture.peers.unshift({ ...fixture.peers[0], id: 'qa-pc', name: 'Computer' }); applyState(structuredClone(fixture));
        check(select.options[2] === phone && select.value === 'qa-phone', 'Adding/reordering peers must preserve selection');
        fixture.settings.language = 'en'; applyState(structuredClone(fixture));
        check(select.options[0] === placeholder && placeholder.textContent === 'Choose a device', 'Language switching must translate the existing placeholder');
        fixture.peers.find(peer => peer.id === 'qa-phone').online = false; applyState(structuredClone(fixture));
        check(select.value === '' && selectedPeer === '' && document.getElementById('send-button').disabled, 'Offline selection must return to the placeholder and prevent sending');
        fixture.peers = []; applyState(structuredClone(fixture));
        check(select.options.length === 1 && select.options[0] === placeholder, 'An empty peer list must retain the placeholder');
        return { progressPollMutations: selectMutations, selectionAndFocusPreserved: true, renameAddReorderLanguageOfflinePassed: true };
      } finally { nativeCall = originalNativeCall; applyState(actual); showPage(originalPage); }
    });
    const report = { idlePollMutations: idleMutations, ...result };
    const output = process.argv.find(arg => arg.endsWith('.json'));
    if (output) await fs.writeFile(output, JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
