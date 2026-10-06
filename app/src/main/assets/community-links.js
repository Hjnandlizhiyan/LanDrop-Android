(function (root) {
  const links = Object.freeze({
    bilibili: 'https://space.bilibili.com/521952225',
    github: 'https://github.com/Hjnandlizhiyan',
    website: 'https://www.deepseeklover.com'
  });
  if (typeof module === 'object' && module.exports) module.exports = links;
  else root.LanDropCommunityLinks = links;
})(typeof globalThis !== 'undefined' ? globalThis : this);
