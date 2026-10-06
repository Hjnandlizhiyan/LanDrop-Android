const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const adb = process.env.LANDROP_ADB || 'adb';
const run = args => execFileSync(adb, args, { encoding: 'utf8', windowsHide: true });
(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9223');
  const page = browser.contexts()[0].pages()[0];
  const output = path.resolve(__dirname, 'community-verification');
  await fs.mkdir(output, { recursive: true });
  try {
    await page.waitForFunction(() => typeof state !== 'undefined' && state);
    run(['shell', 'am', 'start', '-n', 'com.deepseeklover.landrop/.MainActivity']);
    await page.locator('.nav-item[data-page=settings]').click();
    const origin = page.url();
    const links = await page.evaluate(() => LanDropCommunityLinks);
    for (const [key, url] of Object.entries(links)) {
      assert.equal(await page.locator(`[data-community-link=${key}]`).getAttribute('href'), url);
      await page.locator(`[data-community-link=${key}]`).click();
      await page.waitForTimeout(600);
      run(['shell', 'uiautomator', 'dump', '/sdcard/landrop-community-browser.xml']);
      const nodes = run(['shell', 'cat', '/sdcard/landrop-community-browser.xml']);
      const addressBar = [...nodes.matchAll(/<node\b([^>]+)>/g)].map(match => match[1]).find(node => node.includes('com.android.chrome:id/url_bar'));
      const destination = key === 'bilibili' ? 'bilibili.com' : new URL(url).host.replace(/^www\./, '');
      assert.ok(addressBar?.includes(destination), 'External browser address bar must show the destination host');
      if (key === 'bilibili') assert.ok(addressBar.includes('521952225'), 'Bilibili mobile redirect must retain the profile ID');
      assert.equal(page.url(), origin);
      run(['shell', 'am', 'start', '-n', 'com.deepseeklover.landrop/.MainActivity']);
      await page.waitForTimeout(300);
    }
    assert.equal(await page.evaluate(() => nativeCall('openExternal', { url: 'https://example.com' }).then(() => false, () => true)), true);
    await page.locator('#copy-qq-group').click();
    await page.waitForFunction(() => document.getElementById('toast').textContent === t('群号已复制'));
    assert.equal((await page.evaluate(() => nativeCall('pasteText'))).text, '305402575');
    for (const language of ['en', 'zh-CN']) {
      await page.locator('#settings-language').selectOption(language);
      await page.waitForFunction(language => document.documentElement.lang === language, language);
      const labels = await page.locator('.community-panel').innerText();
      assert.match(labels, language === 'en' ? /Creator & community/ : /作者与社区/);
      if (language === 'en') assert.doesNotMatch(labels, /[\u4e00-\u9fff]/);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    }
    await page.locator('.community-panel').scrollIntoViewIfNeeded();
    run(['shell', 'screencap', '-p', '/sdcard/landrop-community.png']);
    run(['pull', '/sdcard/landrop-community.png', path.join(output, 'android-community.png')]);
    const report = { allThreeLinksPassed: true, externalBrowserIntentsPassed: true, blockedOtherUrls: true, qqClipboardPassed: true, bilingualMobileLayoutPassed: true };
    await fs.writeFile(path.join(output, 'android.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
