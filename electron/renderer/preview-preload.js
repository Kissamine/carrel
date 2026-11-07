// プレビュー (別プロセス) 側の受け口。
// 本文の差し替えと、カード内検索を開く合図だけを受け取る。
const { ipcRenderer } = require('electron');

ipcRenderer.on('body', (_event, html) => {
  if (window.__ankiSetBody) {
    window.__ankiSetBody(html);
    ipcRenderer.sendToHost('body-set', html.length);
  } else {
    ipcRenderer.sendToHost('body-set', -1);   // 受け口がまだ無い
  }
});

ipcRenderer.on('find', () => {
  if (window.__ankiOpenFind) window.__ankiOpenFind();
});

ipcRenderer.on('ask-background', () => {
  const color = getComputedStyle(document.body).backgroundColor;
  ipcRenderer.sendToHost('background', color);
});

// ページ側から親へ知らせる窓口 (画像の拡大など)。
// ページと preload は別の世界なので、DOM のメッセージで受け渡す
window.addEventListener('message', (e) => {
  const d = e.data;
  if (!d || d.type !== 'anki-host' || typeof d.channel !== 'string') return;
  ipcRenderer.sendToHost(d.channel, d.payload);
});
