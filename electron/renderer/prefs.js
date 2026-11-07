// 設定の入れ物。
// file:// のページでは localStorage が残らないため、親プロセス側のファイルに保存する。
// 使い勝手は localStorage と同じにしてある。

let cache = {};

export const prefs = {
  async load() { cache = await window.anki.settingsAll(); },
  getItem(key) { return key in cache ? cache[key] : null; },
  setItem(key, value) {
    cache[key] = String(value);
    window.anki.settingsSet(key, String(value));
  },
  removeItem(key) {
    delete cache[key];
    window.anki.settingsSet(key, null);
  },
};
