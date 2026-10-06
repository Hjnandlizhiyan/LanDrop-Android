document.querySelector('.community-panel').addEventListener('click', async event => {
  const link = event.target.closest('a[data-community-link]');
  const group = event.target.closest('#copy-qq-group');
  if (link && window.desktop?.openExternal) {
    event.preventDefault();
    try {
      const url = LanDropCommunityLinks[link.dataset.communityLink];
      if (!url || link.getAttribute('href') !== url) throw new Error(t('无法打开此链接'));
      await window.desktop.openExternal(url);
    } catch (error) { toast(error.message, true); }
  }
  if (group) {
    try {
      if (window.desktop) await window.desktop.copyText('305402575');
      else await navigator.clipboard.writeText('305402575');
      toast(t('群号已复制'));
    } catch { toast(t('复制失败，请手动复制群号：305402575'), true); }
  }
});
