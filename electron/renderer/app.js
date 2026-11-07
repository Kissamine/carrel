import { prefs } from './prefs.js';
import { createFastEditor } from './fasteditor.js';
import { cardPage } from './cardpage.js';
import { openSettings, loadSnippets, AI_KIND_INFO } from './settings.js';
import { visibleModels } from './models.js';
import { createRichEditor } from './rich.js';

// 画面の組み立て。データはすべて window.anki.call() 経由で Python の anki から取る。

let reopening = null;
async function reopenCollection() {
  if (reopening) return reopening;
  reopening = (async () => {
    const path = await window.anki.collectionPath();
    let lastErr = null;
    for (let k = 0; k < 8; k++) {
      try { await window.anki.call('open', { path }); return true; }
      catch (e) { lastErr = e; await new Promise((r) => setTimeout(r, 1500)); }
    }
    window.anki.mark('コレクションを開き直せない: ' + (lastErr && lastErr.message));
    return false;
  })();
  try { return await reopening; } finally { reopening = null; }
}
const call = async (method, params) => {
  try {
    return await window.anki.call(method, params);
  } catch (e) {
    const msg = e.message || '';
    // Anki 本体が動いている間はコレクションに触れない。終了を待ってからやり直す
    if (/ANKI_RUNNING/.test(msg)) {
      await waitForAnkiToQuit();
      return window.anki.call(method, params);
    }
    if (/NO_PROFILE/.test(msg)) {
      showAnkiBanner('noProfile');
      throw new Error('Anki のプロフィールが見つかりません');
    }
    // 控え (バックアップ) などで閉じたまま残ったときは、開き直してもう一度
    if (method !== 'open' && method !== 'close' && /コレクションが開かれていません/.test(e.message || '')) {
      window.anki.mark(`閉じたままの呼び出し: ${method} → 開き直す`);
      if (await reopenCollection()) return window.anki.call(method, params);
    }
    throw e;
  }
};

/* ---------------- Anki 本体との住み分け ---------------- */

const ankiState = { profile: null, waiting: null };

function showAnkiBanner(kind) {
  const banner = el('ankiBanner');
  const text = el('ankiBannerText');
  const button = el('ankiBannerButton');
  if (!kind) { banner.hidden = true; return; }
  banner.hidden = false;
  if (kind === 'running') {
    text.textContent = 'Anki が起動中のため待機しています。Anki で同期や学習を終えて Anki を終了すると、自動で再開します。';
    button.hidden = true;
  } else {
    text.textContent = 'Anki のプロフィールが見つかりません。Anki をインストールして一度起動してから、設定で選んでください。';
    button.hidden = false;
    button.textContent = '設定を開く';
    button.onclick = () => el('settings').click();
  }
}

/** Anki が終了するまで待つ (何度呼ばれても待ち合わせは 1 つ) */
function waitForAnkiToQuit() {
  if (ankiState.waiting) return ankiState.waiting;
  showAnkiBanner('running');
  ankiState.waiting = (async () => {
    for (;;) {
      await new Promise((r) => setTimeout(r, 1500));
      try {
        const st = await window.anki.ankiStatus();
        if (!st.running) break;
      } catch { break; }
    }
    showAnkiBanner(null);
    ankiState.waiting = null;
    toast('Anki が終了したので再開します');
  })();
  return ankiState.waiting;
}

/** プロフィールの有無を調べて画面の状態を合わせる */
async function loadAnkiStatus() {
  try {
    const st = await window.anki.ankiStatus();
    ankiState.profile = st.profile;
    if (!st.profile) showAnkiBanner('noProfile');
    else if (st.running) { waitForAnkiToQuit(); }
    else showAnkiBanner(null);
    return st;
  } catch (e) {
    ankiState.profile = null;
    return null;
  }
}

/** メディア同期が裏で動いている間は、コレクションを閉じてはいけない (開き直せなくなる) */
async function waitMediaIdle(maxMs = 90000) {
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    try {
      const st = await window.anki.call('mediaStatus', {});
      if (!st.active) return true;
    } catch { return true; }
    await new Promise((r) => setTimeout(r, 700));
  }
  return false;
}

/** コレクションを閉じた状態で fn を走らせ、必ず開き直す */
async function withCollectionClosed(fn) {
  const path = await window.anki.collectionPath();
  if (!(await waitMediaIdle())) throw new Error('メディア同期が終わらないため、いまは行えません');
  await window.anki.call('close', {});
  try {
    return await fn();
  } finally {
    let ok = false;
    for (let k = 0; k < 8 && !ok; k++) {
      try { await window.anki.call('open', { path }); ok = true; }
      catch { await new Promise((r) => setTimeout(r, 1500)); }
    }
    if (!ok) {
      window.anki.mark('控えのあと開き直せない');
      toast('コレクションを開き直せません。アプリを再起動してください');
    }
  }
}

const state = {
  notes: [],
  selected: null,
  tags: [],
  tagCounts: {},
  selectedTags: new Set(),
  keyword: '',
  editing: false,
  mode: 'rich',   // 設定を読み込んだあと boot() で入れ直す
  focusedField: 0,
  editor: null,
  draft: null,          // 編集中の内容 { fields, tags }
  fieldNames: [],
  dirty: false,
  unsynced: false,
  history: { past: [], future: [], current: null },
  manualPreview: false,
  previewStale: false,    // プレビューに反映していない変更があるか
  previewAttached: false, // プレビューの入れ物が画面に貼り付いたか
  previewPending: null,   // 貼り付く前に頼まれた読み込み
  previewOf: null,        // いまプレビューに読み込んであるノート
  previewReady: false,
  previewSeq: 0,
  previewCss: null,
  autoCollapsed: false,   // 幅が足りなくて自動でしまったか
  listOverlay: false,     // エディタの上にかぶせて出しているか
};

const el = (id) => document.getElementById(id);

/* ---------------- タイトル ---------------- */

const titleCache = new Map();

/** HTML を落として 1 行の見出しにする (Compose 版の noteTitle と同じ考え方) */
function noteTitle(note, maxBreaks = Infinity) {
  // 同じノートの見出しは何度も使うので、変わるまで覚えておく
  const key = `${note.id}:${note.mtimeSecs || 0}`;
  let lines = titleCache.get(key);
  if (!lines) {
    lines = titleLines(note);
    if (titleCache.size > 2000) titleCache.clear();
    titleCache.set(key, lines);
  }
  return maxBreaks === Infinity ? lines : lines.slice(0, maxBreaks);
}

function titleLines(note) {
  const raw = (note.fields || []).find((f) => stripHtml(f).trim().length) || '';
  const lines = raw
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(div|p|li|h[1-6])>/gi, '\n')
    .split('\n')
    .map(stripHtml)
    .map((s) => s.trim())
    .filter((s) => s.length);
  return lines;
}

function stripHtml(html) {
  return html
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

/* ---------------- 一覧 ---------------- */

let renderedIds = '';

/** 0 件のときは、なぜ空なのか・どう戻すのかを出す */
function showEmptyHint() {
  const hint = el('emptyList');
  if (!hint) return;
  const filtered = state.keyword.trim() || state.selectedTags.size ||
    (prefs.getItem('defaultDeck') && prefs.getItem('defaultDeck') !== 'すべてのデッキ');
  hint.hidden = !(loggedIn() && state.notes.length === 0);
  if (hint.hidden) return;
  hint.querySelector('.why').textContent = filtered
    ? '検索語やタグ・デッキで絞り込んでいます'
    : 'カードが見つかりません';
  hint.querySelector('button').hidden = !filtered;
}

/** 選んでいる行の印だけ付け替える (300 行を作り直さない) */
/** 名前ふだを付け直す (キーの割り当てを変えたときなど) */
export function refreshTitlebarTips() {
  window.dispatchEvent(new Event('anki-keys-changed'));
}

function updateSelectedRow() {
  const id = state.selected && state.selected.id;
  for (const row of el('notes').children) {
    row.classList.toggle('on', Number(row.dataset.id) === id);
  }
}

function renderNotes() {
  const box = el('notes');
  el('count').textContent = `${state.notes.length} 件`;
  showEmptyHint();
  // 並びが同じなら、印の付け替えだけで済ませる
  const ids = state.notes.map((n) => n.id).join(',');
  if (ids === renderedIds && box.children.length === state.notes.length) {
    updateSelectedRow();
    return;
  }
  renderedIds = ids;
  box.innerHTML = '';
  for (const note of state.notes) {
    const row = document.createElement('div');
    row.dataset.id = String(note.id);
    row.className = 'note' + (state.selected && state.selected.id === note.id ? ' on' : '');
    const title = document.createElement('div');
    title.className = 'title';
    title.textContent = noteTitle(note).slice(0, 3).join('\n');
    title.style.whiteSpace = 'pre-line';
    row.appendChild(title);
    const tags = (note.tags || '').trim().split(/\s+/).filter(Boolean);
    if (tags.length) {
      const tagrow = document.createElement('div');
      tagrow.className = 'tagrow';
      for (const t of tags.slice(0, 4)) {
        const s = document.createElement('span');
        s.textContent = t;
        tagrow.appendChild(s);
      }
      row.appendChild(tagrow);
    }
    row.onclick = () => select(note);
    box.appendChild(row);
  }
}

/** 設定と件数をふまえて、実際に出すタグを順番どおりに並べる */
export function visibleTags(all, counts, selected) {
  const hidden = new Set(JSON.parse(prefs.getItem('hiddenTags') || '[]'));
  const order = JSON.parse(prefs.getItem('tagOrder') || '[]');
  const pinned = new Set(JSON.parse(prefs.getItem('pinnedTags') || '[]'));
  const usable = all.filter((t) => !hidden.has(t) && (counts[t] === undefined || counts[t] > 0));
  const place = (t) => {
    const at = order.indexOf(t);
    return at >= 0 ? at : order.length + 1;
  };
  // 絞り込み中 → よく使う → 設定した並び → 五十音
  const rank = (t) => (selected.has(t) ? 0 : pinned.has(t) ? 1 : 2);
  return usable.sort((a, b) =>
    rank(a) - rank(b) || place(a) - place(b) || a.localeCompare(b, 'ja'));
}

function renderTags() {
  const box = el('tags');
  box.innerHTML = '';
  const ordered = visibleTags(state.tags, state.tagCounts, state.selectedTags);
  for (const tag of ordered.slice(0, 60)) {
    const b = document.createElement('button');
    b.className = 'chip' + (state.selectedTags.has(tag) ? ' on' : '');
    b.textContent = tag;
    b.onclick = () => {
      if (state.selectedTags.has(tag)) state.selectedTags.delete(tag);
      else state.selectedTags.add(tag);
      renderTags();
      search();
      rememberSession();
    };
    box.appendChild(b);
  }
}

function query() {
  const parts = [];
  const deck = prefs.getItem('defaultDeck');
  if (deck && deck !== 'すべてのデッキ') parts.push(`deck:"${deck}"`);
  for (const tag of state.selectedTags) parts.push(`tag:"${tag}"`);
  const words = state.keyword.trim();
  if (words) parts.push(words);
  return parts.join(' ');
}

const bodyCache = new Map();

function score(note, keyword) {
  if (!keyword) return 0;
  const needle = keyword.toLowerCase();
  const title = noteTitle(note).join(' ').toLowerCase();
  const bodyKey = `${note.id}:${note.mtimeSecs || 0}`;
  let body = bodyCache.get(bodyKey);
  if (body === undefined) {
    body = (note.fields || []).map(stripHtml).join(' ').toLowerCase();
    if (bodyCache.size > 2000) bodyCache.clear();
    bodyCache.set(bodyKey, body);
  }
  let point = 0;
  if (title.startsWith(needle)) point += 100;
  if (title.includes(needle)) point += 50;
  if (body.includes(needle)) point += 10;
  return point;
}

/** 並べ替え用の文字列: 丸数字・全角数字・上付き数字を 3 桁の数字に直す */
function sortKey(text) {
  return text.replace(/[①-⑳㉑-㉟㊱-㊿⓪⓫-⓴❶-❿⓿０-９⁰¹²³⁴-⁹]/g, (ch) => {
    const c = ch.codePointAt(0);
    let n = null;
    if (c >= 0x2460 && c <= 0x2473) n = c - 0x2460 + 1;       // ①〜⑳
    else if (c >= 0x3251 && c <= 0x325F) n = c - 0x3251 + 21; // ㉑〜㉟
    else if (c >= 0x32B1 && c <= 0x32BF) n = c - 0x32B1 + 36; // ㊱〜㊿
    else if (c === 0x24EA) n = 0;                              // ⓪
    else if (c >= 0x24EB && c <= 0x24F4) n = c - 0x24EB + 11;  // ⓫〜⓴
    else if (c >= 0x2776 && c <= 0x277F) n = c - 0x2776 + 1;   // ❶〜❿
    else if (c === 0x24FF) n = 0;                              // ⓿
    else if (c >= 0xFF10 && c <= 0xFF19) return String(c - 0xFF10);   // 全角数字
    else if (c === 0x2070) return '0';
    else if (c === 0xB9) return '1';
    else if (c === 0xB2) return '2';
    else if (c === 0xB3) return '3';
    else if (c >= 0x2074 && c <= 0x2079) return String(c - 0x2074 + 4);
    return n === null ? ch : String(n).padStart(3, '0');
  });
}

function sortNotes(notes) {
  const keyword = state.keyword.trim();
  // 比較のたびに見出しを組み立てると重いので、先に 1 回だけ作る
  const keyOf = new Map(notes.map((n) => [n.id, noteTitle(n).join(' ')]));
  const mode = keyword
    ? (prefs.getItem('sortMode') === 'RELEVANCE' ? 'RELEVANCE'
        : prefs.getItem('sortMode') || 'RELEVANCE')
    : (prefs.getItem('sortMode') || 'NAME_ASC');
  if (mode === 'RELEVANCE') {
    // 点数も 1 回だけ計算して覚えておく
    const points = new Map(notes.map((n) => [n.id, score(n, keyword)]));
    return notes.slice().sort((a, b) => points.get(b.id) - points.get(a.id));
  }
  // ⑬ のような丸数字は、並べ替えのときだけ「013」のような数字に直す。
  // そのまま比べると「1,3」と「2」の文字比べになり、⑬ が ② より前に来てしまう
  const key = (n) => sortKey(keyOf.get(n.id) || '');
  const sorted = notes.slice();
  if (mode === 'NAME_ASC') sorted.sort((a, b) => key(a).localeCompare(key(b), 'ja', { numeric: true }));
  if (mode === 'NAME_DESC') sorted.sort((a, b) => key(b).localeCompare(key(a), 'ja', { numeric: true }));
  if (mode === 'MTIME_DESC') sorted.sort((a, b) => (b.mtimeSecs || 0) - (a.mtimeSecs || 0));
  if (mode === 'MTIME_ASC') sorted.sort((a, b) => (a.mtimeSecs || 0) - (b.mtimeSecs || 0));
  return sorted;
}

async function search() {
  if (!loggedIn()) { state.notes = []; renderNotes(); return; }
  const limit = Number(prefs.getItem('searchLimit') || 300);
  // 一覧では見出しとタグしか使わないので、本文は切り詰めて受け取る
  state.notes = sortNotes(await call('searchNotes', { query: query(), limit, brief: 1 }));
  renderNotes();
}

/* ---------------- プレビュー ---------------- */

let selectSeq = 0;
async function select(note) {
  const my = ++selectSeq;
  if (state.editor && state.editor.isReviewing && state.editor.isReviewing()) finishReview(false);
  if (state.editing && state.dirty) await save({ sync: false });
  if (my !== selectSeq) return;               // 待っている間に別のノートが選ばれた
  state.selected = note;
  rememberSession();
  updateSelectedRow();
  el('cardTitle').textContent = noteTitle(note, 3).join(' ');
  updateTitlebar();
  const rendered = await call('renderNote', { id: note.id });
  if (my !== selectSeq) return;
  showCard(rendered);
  if (state.editing) await reloadEditor(note);
}

/* ---------------- 取り消す / やり直す ---------------- */

/** いまの内容を、履歴の起点として置き直す */
function resetHistory() {
  state.history = { past: [], future: [], current: state.draft.fields.slice() };
  updateHistoryButtons();
}

let historyTimer = null;
/** 打鍵が落ち着いたところで、ひと区切りとして覚える */
function pushHistory() {
  clearTimeout(historyTimer);
  historyTimer = setTimeout(() => {
    const h = state.history;
    const now = state.draft.fields.slice();
    if (h.current && now.join('\u0000') === h.current.join('\u0000')) return;
    if (h.current) h.past.push(h.current);
    // 長い本文だと 1 段ごとに数万文字を抱えるので、控えめに持つ
    if (h.past.length > 30) h.past.shift();
    h.current = now;
    h.future = [];
    updateHistoryButtons();
  }, 400);
}

function applyHistory(fields) {
  state.draft.fields = fields.slice();
  // 作り直すと見ている位置が飛ぶので、中身だけ入れ替える
  if (state.editor && state.editor.setValues) state.editor.setValues(state.draft.fields);
  else buildEditor();
  state.dirty = true;
  updateSaveButton();
  livePreview();
  updateHistoryButtons();
}

function undo() {
  // CodeMirror を使っているときは、そちらの履歴に任せる (控えを二重に持たない)
  if (state.editor && state.editor.undo && state.editor.undo()) return;
  clearTimeout(historyTimer);
  const h = state.history;
  if (!state.editing || !h.past.length) return;
  h.future.push(h.current);
  h.current = h.past.pop();
  applyHistory(h.current);
}

function redo() {
  if (state.editor && state.editor.redo && state.editor.redo()) return;
  clearTimeout(historyTimer);
  const h = state.history;
  if (!state.editing || !h.future.length) return;
  h.past.push(h.current);
  h.current = h.future.pop();
  applyHistory(h.current);
}

function updateHistoryButtons() {
  const h = state.history;
  el('undo').hidden = !state.editing;
  el('redo').hidden = !state.editing;
  el('undo').disabled = !h.past.length;
  el('redo').disabled = !h.future.length;
}

/** 開いたまま、別のノートの内容に入れ替える */
async function reloadEditor(forNote) {
  const note = forNote || state.selected;
  if (!note) return;
  try {
    const names = await call('fieldNames', { notetypeId: note.modelId });
    const content = await call('noteContent', { id: note.id });
    if (!content) throw new Error('ノートを読めません');
    // 読み込んでいる間に別のノートへ移っていたら、この結果は使わない
    if (!state.selected || state.selected.id !== note.id) return;
    state.fieldNames = names;
    state.draft = { noteId: note.id, fields: content.fields.slice(), tags: content.tags || note.tags || '' };
    state.dirty = false;
    updateSaveButton();
    buildEditor();
    renderEditorTags();
    resetHistory();
    state.previewStale = false;
    updatePreviewMode();
  } catch (e) {
    toast('編集を開けません: ' + e.message);
  }
}

/* ---------------- 編集 ---------------- */

async function openEditor() {
  const note = state.selected;
  if (!note) { toast('先に一覧からノートを選んでください'); return; }
  try {
    const names = await call('fieldNames', { notetypeId: note.modelId });
    const content = await call('noteContent', { id: note.id });
    if (!content) throw new Error('ノートを読めません');
    if (!state.selected || state.selected.id !== note.id) return;   // 待つ間に移っていた
    state.fieldNames = names;
    state.draft = { noteId: note.id, fields: content.fields.slice(), tags: content.tags || note.tags || '' };
    state.dirty = false;
    updateSaveButton();
    state.editing = true;
    const pane = el('editorPane');
    pane.hidden = false;
    el('split2').hidden = false;
    buildEditor();
    layout();
    pane.style.width = '0px';
    pane.classList.remove('open');
    renderEditorTags();
    requestAnimationFrame(() => {
      if (state.editor && state.editor.refit) setTimeout(() => state.editor.refit(), 320);
      pane.classList.add('open');
      pane.style.width = Math.round(widths().editor) + 'px';
      layout();
    });
    el('edit').classList.add('active');
    resetHistory();
    state.previewStale = false;
    updatePreviewMode();
    setTimeout(fitEditorBar, 50);
    setTimeout(fitEditorBar, 350);
    rememberSession();
  } catch (e) {
    toast('編集を開けません: ' + e.message);
    console.log('[editor] ' + e.message);
  }
}

/** いまのモードで編集欄を作り直す (中身は draft を引き継ぐ) */
function buildEditor() {
  // 作り直すと先頭へ戻ってしまうので、見ていた位置と入力位置を控える
  const box = el('fields');
  const keepScroll = box.scrollTop;
  const active = document.activeElement;
  const keepField = active && active.dataset && active.dataset.index !== undefined
    ? { index: Number(active.dataset.index), at: active.selectionStart }
    : null;
  // このエディタがどのノートのものかを覚えておく。
  // 別のノートへ移ったあとに古いエディタから中身が届いても、受け取らない
  // (受け取ると、別のカードの本文で下書きを上書きしてしまう)
  const boundId = state.draft && state.draft.noteId;
  const stale = () => !state.draft || state.draft.noteId !== boundId ||
    !state.selected || state.selected.id !== boundId;
  const hooks = {
    onChange: (i, value) => {
      if (stale()) { console.warn('[guard] 古いエディタからの内容を捨てました', boundId); return; }
      state.draft.fields[i] = value;
      state.dirty = true;
      updateSaveButton();
      // HTML 編集では CodeMirror が履歴を持つので、こちらでは控えない
      if (state.mode !== 'html') pushHistory();
      livePreview();

    },
    onSave: () => save(),
    onFind: () => el('editorFindInput').focus(),
    onAi: () => openAi(),
    // 入力欄に触れたら、検索欄の表示も元に戻す
    onFindCleared: () => {
      el('editorFindInput').value = '';
      el('editorFindCount').textContent = '';
    },
    // 欄の下に出す画像の置き場所
    mediaUrl: (src) => (/^(https?:|data:|media:)/.test(src)
      ? src
      : 'media://media/' + encodeURIComponent(src)),
    onOpenImage: (src, name) => openLightbox(src, name),
    // クリップボードの画像を、コレクションに入れてから差し込む
    onPasteImage: (i, file) => pasteImage(i, file),
    onFocus: (i) => { state.focusedField = i; },
    // 「変わった」ことだけを先に受け取る (中身は少し遅れて届く)
    onDirty: () => {
      if (stale()) return;
      if (!state.previewStale) {
        state.previewStale = true;
        updatePreviewMode();
      }
      if (state.dirty) return;
      state.dirty = true;
      updateSaveButton();
    },
  };
  // 前の編集欄を片付けてから作り直す。
  // 放っておくと、古い編集欄が裏で動き続けて、だんだん重くなる
  if (state.editor && state.editor.destroy) state.editor.destroy();
  const rich = state.mode === 'rich';
  state.editor = rich
    ? createRichEditor(el('fields'), state.fieldNames, state.draft.fields, hooks)
    : createFastEditor(el('fields'), state.fieldNames, state.draft.fields, hooks);
  el('modeRich').classList.toggle('on', rich);
  el('modeHtml').classList.toggle('on', !rich);
  el('formatBar').hidden = !rich;
  renderSnippetBar();
  applyEditorWrap();
  el('editorFindInput').disabled = rich;

  // 控えておいた位置に戻す
  const settle = () => { box.scrollTop = keepScroll; };
  setTimeout(settle, 80);
  setTimeout(settle, 180);
  requestAnimationFrame(() => {
    box.scrollTop = keepScroll;
    if (keepField && !rich) {
      const area = box.querySelector('#ta' + keepField.index);
      if (area) {
        area.focus({ preventScroll: true });
        const at = Math.min(keepField.at ?? 0, area.value.length);
        area.setSelectionRange(at, at);
        box.scrollTop = keepScroll;
      }
    }
  });
}

function setMode(mode) {
  if (state.mode === mode) return;
  if (state.editor) state.editor.flush();
  state.mode = mode;
  prefs.setItem('editorMode', mode);
  if (state.editing) buildEditor();
}

/* ---------------- 組み込み AI ---------------- */

const AI_SYSTEM = [
  'あなたは Anki の医学カード (HTML) を直す編集者です。',
  '渡された「対象」の部分だけを、指示どおりに書き直して返してください。',
  '守ること:',
  '- 返すのは差し替え後のテキストだけ。説明・前置き・コードフェンスは付けない',
  '- 対象が HTML なら HTML のまま返す。タグ・class・style・改行やインデントの流儀はそのまま踏襲する',
  '- 指示されていない箇所は変えない。文体・記号 (「,」「.」の使い方など) は周りにそろえる',
  '- 対象が空で「加えて」と言われたら、周りの流儀に合わせて新しく書く',
  '- 数式は <anki-mathjax>…</anki-mathjax> のまま残す。\\(…\\) や $…$ に書き換えない。新しく数式を書くときも <anki-mathjax> を使う',
  '- 確かめたいことがあれば、自分の判断で Web 検索を使ってよい (数値・用語・最新の指針など)。検索して取り入れた内容は、返事にひとこと添える',
  '- いちばん最後に、改行してから「<<<返事>>>」と書き、続けて何をどう変えたかを簡潔に書く (本文には含めない)',
].join('\n');

// 欄全体・エディタ全体は、全文を書き直させない。
// 長い本文だと出し切れずに後半が消えるため、「どこをどう変えるか」の一覧だけを返させる
const AI_SYSTEM_PATCH = [
  'あなたは Anki の医学カード (HTML) を直す編集者です。',
  '本文は長いことがあるので、全文は返さず、変える箇所だけを「編集」の一覧で返してください。',
  '形式 (この形だけを返す。説明やコードフェンスは付けない):',
  '',
  '<<<欄: 欄の名前>>>',
  '<<<探す>>>',
  '本文に実際にある文字列 (改行も含めてそのまま。短くてよいが、本文の中で一意になる長さ)',
  '<<<置換>>>',
  '置き換え後の文字列 (削除なら空)',
  '<<<終>>>',
  '',
  '守ること:',
  '- 「探す」は本文からの正確な引用。1 文字でも違うと当たらないので、書き換えずに写す',
  '- 追記したいときは、その直前か直後にある文字列を「探す」に入れ、「置換」にその文字列＋追記分を書く',
  '- 編集は必要なぶんだけ、いくつでも並べてよい。変える箇所が無ければ「<<<変更なし>>>」とだけ返す',
  '- HTML はそのまま。タグ・class・style・改行やインデントの流儀は周りにそろえる',
  '- 指示されていない箇所は変えない。文体・記号 (「,」「.」の使い方など) も周りにそろえる',
  '- 数式は <anki-mathjax>…</anki-mathjax> のまま残す。\\(…\\) や $…$ に書き換えない。新しく数式を書くときも <anki-mathjax> を使う',
  '- 確かめたいことがあれば、自分の判断で Web 検索を使ってよい (数値・用語・最新の指針など)。検索して取り入れた内容は、返事にひとこと添える',
  '- いちばん最後に「<<<返事>>>」と書き、続けて何をどう変えたかを簡潔に書く',
].join('\n');

/** 「編集」の一覧を読み取る。[{ field, find, replace }] */
function parsePatches(text, names) {
  const out = [];
  const re = /<<<欄: ?(.+?)>>>\s*<<<探す>>>\n?([\s\S]*?)\n?<<<置換>>>\n?([\s\S]*?)\n?<<<終>>>/g;
  let m;
  while ((m = re.exec(text))) {
    const name = m[1].trim();
    let field = names.indexOf(name);
    if (field < 0) field = names.findIndex((n) => n.toLowerCase() === name.toLowerCase());
    if (field < 0) continue;
    out.push({ field, find: m[2], replace: m[3] });
  }
  return out;
}

/** 編集の一覧を本文に当てて、欄ごとの新しい中身を作る。当たらなかったものは数える */
function applyPatches(fields, patches) {
  const next = fields.slice();
  let hit = 0;
  let miss = 0;
  for (const p of patches) {
    const body = next[p.field] || '';
    let at = body.indexOf(p.find);
    if (at < 0 && p.find.trim()) {
      // 行頭・行末の空白の違いくらいは許す
      const loose = p.find.trim();
      at = body.indexOf(loose);
      if (at >= 0) { next[p.field] = body.slice(0, at) + p.replace.trim() + body.slice(at + loose.length); hit++; continue; }
    }
    if (at < 0) { miss++; continue; }
    next[p.field] = body.slice(0, at) + p.replace + body.slice(at + p.find.length);
    hit++;
  }
  return { next, hit, miss };
}

let aiAttachments = [];    // 資料 { kind: 'file'|'url'|'note', name, mime?, dataBase64?, text? }

/**
 * ① 他のカードから「このデッキの書き方」を抜き出す。
 * よく使う class とその例、見出し (tips) の並び、表の骨組み、上位の並び、関係する CSS。
 * ノートタイプごとに 1 日キャッシュする
 */
async function styleGuide(force = false, noteIds = null) {
  const note = state.selected;
  const modelId = note ? note.modelId : (state.notes[0] && state.notes[0].modelId);
  if (!modelId && !(noteIds && noteIds.length)) return '';
  const key = 'aiStyleGuide:' + modelId;
  if (!noteIds) {
    try {
      const cached = JSON.parse(prefs.getItem(key) || 'null');
      if (!force && cached && Date.now() - cached.builtAt < 86400000) return cached.text;
    } catch { /* 作り直す */ }
  }
  const bodies = [];
  if (noteIds && noteIds.length) {
    // 指定されたカードだけから学ぶ
    for (const id of noteIds) {
      try { const c = await call('noteContent', { id }); if (c) bodies.push(c.fields); } catch { /* 飛ばす */ }
    }
  } else {
    // 同じノートタイプのカードを、いまの一覧から (足りなければ全体から) 拾う
    let pool = state.notes.filter((n) => n.modelId === modelId && (!note || n.id !== note.id));
    if (pool.length < 6) {
      try {
        const more = await call('searchNotes', { query: `mid:${modelId}`, limit: 60, brief: 1 });
        for (const n of more) if ((!note || n.id !== note.id) && !pool.some((p) => p.id === n.id)) pool.push(n);
      } catch { /* 手元のぶんで作る */ }
    }
    pool = pool.slice(0, 40);
    // 本文が長い順に 8 枚読む (書き方がよく出ているものを優先)
    for (const n of pool.slice(0, 24)) {
      try {
        const c = await call('noteContent', { id: n.id });
        if (c) bodies.push(c.fields);
      } catch { /* 飛ばす */ }
      if (bodies.length >= 12) break;
    }
  }
  bodies.sort((a, b) => b.join('').length - a.join('').length);
  const picked = noteIds ? bodies : bodies.slice(0, 8);
  if (!picked.length) return '';
  const html = picked.map((f) => f.join('\n')).join('\n');

  // class の頻度と例
  const classCount = new Map();
  const example = new Map();
  const reClass = /<([a-zA-Z][\w-]*)([^>]*?)class="([^"]+)"[^>]*>/g;
  let m;
  while ((m = reClass.exec(html))) {
    for (const c of m[3].split(/\s+/).filter(Boolean)) {
      classCount.set(c, (classCount.get(c) || 0) + 1);
      if (!example.has(c)) {
        const from = m.index;
        example.set(c, html.slice(from, from + 220).replace(/\s+/g, ' '));
      }
    }
  }
  const topClasses = [...classCount.entries()].sort((a, b) => b[1] - a[1]).slice(0, 24);
  // 見出し (tips) の並び
  const tips = [];
  const reTips = /<span class="tips"[^>]*>([^<]{1,30})<\/span>/g;
  for (const f of picked) {
    const seq = [];
    let t;
    while ((t = reTips.exec(f.join('\n')))) seq.push(t[1].trim());
    if (seq.length) tips.push(seq.join(' → '));
  }
  // 表の骨組み
  const table = (/<table[\s\S]{0,900}?<\/tr>/i.exec(html) || [''])[0].replace(/\s+/g, ' ');
  // 上位の並び (代表 1 枚)
  const top = [];
  const reTop = /^<([a-zA-Z][\w-]*)([^>]*)>/gm;
  const rep = picked[0].join('\n');
  while ((m = reTop.exec(rep)) && top.length < 40) {
    const cls = (/class="([^"]+)"/.exec(m[2]) || [])[1];
    top.push(cls ? `${m[1]}.${cls.split(/\s+/)[0]}` : m[1]);
  }
  // 関係する CSS
  let css = '';
  try {
    const tpl = await call('templateOf', { notetypeId: note.modelId });
    const names = new Set(topClasses.map(([c]) => c));
    const rules = (tpl && tpl.css ? tpl.css : '').match(/[^{}]+\{[^}]*\}/g) || [];
    css = rules.filter((r) => [...names].some((c) => r.includes('.' + c))).join('\n').replace(/\s+/g, ' ');
    if (css.length > 3000) css = css.slice(0, 3000) + ' …';
  } catch { /* 無くてもよい */ }

  const text = [
    `よく使う class (回数) と、その使い方の例:`,
    ...topClasses.map(([c, n]) => `- .${c} (${n}) 例: ${example.get(c)}`),
    tips.length ? `\n見出し (span.tips) の並びの例:\n${tips.slice(0, 5).map((x) => '- ' + x).join('\n')}` : '',
    table ? `\n表の骨組みの例:\n${table}` : '',
    top.length ? `\n本文の上位の並び (代表例): ${top.join(' > ')}` : '',
    css ? `\n関係する CSS:\n${css}` : '',
    `\n約束: 記号は「,」「.」を半角で使い、後ろに空白。改行は <br> と改行文字を組み合わせ、インデントはタブ。`,
  ].filter(Boolean).join('\n');
  if (!noteIds) prefs.setItem(key, JSON.stringify({ builtAt: Date.now(), text }));
  return text;
}

/**
 * 「記憶」: 他のカードから抜き出した書き方を、AI に短いメモへまとめさせて保存しておく。
 * 毎回作り直さず、いつでも呼び出す (設定 › AI で見る・直す・学び直す)
 */
async function memoryGet() {
  const all = await window.anki.settingsAll();
  return all.aiMemory || null;            // { text, builtAt, notetype }
}
/** 記憶の控え。書き換える前に必ず取っておく (最新 10 世代) */
async function memoryBackup(reason) {
  const m = await memoryGet();
  if (!m || !m.text) return;
  const all = await window.anki.settingsAll();
  const list = Array.isArray(all.aiMemoryBackups) ? all.aiMemoryBackups : [];
  if (list.length && list[0].text === m.text) return;      // 同じものは重ねない
  list.unshift({ text: m.text, savedAt: Date.now(), reason: reason || '' });
  await window.anki.settingsSet('aiMemoryBackups', list.slice(0, 10));
}
async function memoryBackups() {
  const all = await window.anki.settingsAll();
  return Array.isArray(all.aiMemoryBackups) ? all.aiMemoryBackups : [];
}
async function memoryRestore(index) {
  const list = await memoryBackups();
  const b = list[index];
  if (!b) throw new Error('その控えはありません');
  await memoryBackup('復元の前');
  const m = (await memoryGet()) || {};
  await window.anki.settingsSet('aiMemory', { ...m, text: b.text, builtAt: Date.now() });
  refreshMemoryChip();
}

/**
 * 学ぶ。mode: 'merge' (いまのメモを土台に足す・消す・簡潔に) / 'rebuild' (根本から作り直す) / 'append' (末尾に追記するだけ)
 * noteIds を渡すと、そのカードだけから学ぶ
 */
async function memoryLearn({ mode = 'merge', noteIds = null } = {}) {
  const raw = await styleGuide(true, noteIds);
  if (!raw) throw new Error('学べるカードがありません');
  let text = raw;
  const current = (await memoryGet()) || {};
  const prevText = mode === 'rebuild' ? '' : (current.text || '').trim();
  await memoryBackup(mode === 'rebuild' ? '作り直しの前' : (mode === 'append' ? '追記の前' : '学び直しの前'));
  if (aiActive) {
    const r = await window.anki.aiAsk({
      system: [
        'あなたは編集の手引きを書く人です。渡された「デッキの書き方の生データ」を、他の編集者が読んで同じ書き方を再現できるメモにまとめてください。',
        prevText && mode === 'merge'
          ? '「いまのメモ」も渡します。それを土台にして、生データに新しくある部品や決まりは足し、もう使われていないものは消し、重なりや冗長な説明は簡潔にまとめ直してください。' +
            '「## 編集の型」「## 指示からの記憶」の見出しの下にある項目は利用者が自分で加えたものなので、内容を変えずにそのまま末尾に残すこと。'
          : '',
        mode === 'append'
          ? '「いまのメモ」も渡しますが、書き換えてはいけません。生データから読み取れる、いまのメモに無い部品・決まりだけを「## 追記」の見出しの下に簡潔にまとめて返してください (無ければ「<<<追記なし>>>」とだけ返す)。'
          : '',
        '必ず次の形で書くこと:',
        '1. 「部品」の一覧: よく使う HTML のセット (class 名・タグの組み合わせ) ごとに「どんな場面で使うか」と「HTML の雛形」をセットで書く。雛形はデータにある断片を正確に写し、中身だけ … に置き換える',
        '2. 「並び」: 本文の見出し (span.tips など) の典型的な順番と、各見出しの下に来る部品',
        '3. 「表」: 表の骨組みと、表を使う場面',
        '4. 「約束」: 記号・改行・インデント・強調色などの決まり',
        '推測で足さない。3500 字以内。返すのはメモだけ (前置きやコードフェンスは付けない)。',
      ].join('\n'),
      prompt: (prevText ? `## いまのメモ\n${prevText}\n\n## 生データ\n` : '') + raw,
      maxTokens: 6000,
      effort: 'low',
    });
    if (mode === 'append') {
      const add = (r.text || '').trim();
      if (!add || /<<<追記なし>>>/.test(add)) throw new Error('いまのメモに無い内容は見つかりませんでした');
      text = (prevText ? prevText + '\n\n' : '') + add.replace(/^## 追記\s*/, `## 追記 (${new Date().toLocaleDateString('ja-JP')})\n`);
    } else {
      if (r.text && r.text.trim().length > 200) text = r.text.trim();
      // 利用者が足した項目が落ちていたら、こちらで末尾に戻す
      const kept = (current.text || '').split(/\n(?=## )/).filter((sec) => /^## (編集の型|指示からの記憶)/.test(sec));
      for (const sec of kept) {
        const head = sec.split('\n')[0];
        if (!text.includes(head)) text = text.replace(/\s*$/, '') + '\n\n' + sec.trim();
      }
    }
  } else if (mode === 'append') {
    text = (prevText ? prevText + '\n\n' : '') + `## 追記 (${new Date().toLocaleDateString('ja-JP')})\n` + raw;
  }
  const memo = { text, builtAt: Date.now(), notetype: state.selected ? state.selected.modelId : null };
  await window.anki.settingsSet('aiMemory', memo);
  refreshMemoryChip(memo);
  const chip = el('aiMemoryChip');
  if (chip) { chip.classList.remove('learned'); void chip.offsetWidth; chip.classList.add('learned'); }
  return memo;
}
async function refreshMemoryChip(memo) {
  const chip = el('aiMemoryChip');
  if (!chip) return;
  const m = memo || await memoryGet();
  chip.classList.toggle('on', !!(m && m.text));
  chip.textContent = m && m.text ? `記憶 ${new Date(m.builtAt).toLocaleDateString('ja-JP')}` : '記憶なし';
  chip.title = m && m.text
    ? '他のカードから学んだ書き方。クリックで確認・編集・学び直し'
    : 'まだ学んでいません。クリックで他のカードから学びます';
}

/** ③ 指示に「他のカード」の含みがあれば、関係しそうなカードを自動で拾う */
async function autoNoteRefs(ask) {
  if (!/(他|ほか|別)のカード|参考|参照|同じ(よう|形|書き方)|似た|にならって|に倣って|真似/.test(ask)) return [];
  const out = [];
  // 「」や "" で囲まれた語で探す
  const quoted = [...ask.matchAll(/「([^」]{1,30})」|"([^"]{1,30})"/g)].map((q) => q[1] || q[2]);
  const seen = new Set();
  for (const q of quoted) {
    try {
      const found = await call('searchNotes', { query: q, limit: 3, brief: 1 });
      for (const n of found) {
        if (!state.selected || n.id === state.selected.id || seen.has(n.id)) continue;
        seen.add(n.id);
        out.push(n);
        if (out.length >= 3) break;
      }
    } catch { /* 次へ */ }
    if (out.length >= 3) break;
  }
  // 見つからなければ、いまの一覧の近くのカード
  if (!out.length) {
    for (const n of state.notes) {
      if (!state.selected || n.id === state.selected.id || n.modelId !== state.selected.modelId) continue;
      out.push(n);
      if (out.length >= 2) break;
    }
  }
  const refs = [];
  for (const n of out) {
    try {
      const c = await call('noteContent', { id: n.id });
      if (c) refs.push({ kind: 'note', name: noteTitle(n, 2).join(' '), text: c.fields.join('\n').slice(0, 20000), auto: true });
    } catch { /* 飛ばす */ }
  }
  return refs;
}

function renderAttachments() {
  const box = el('aiAttachList');
  box.innerHTML = '';
  box.hidden = !aiAttachments.length;
  aiAttachments.forEach((a, k) => {
    const chip = document.createElement('span');
    chip.className = 'chip-file' + (a.kind === 'note' ? ' note' : '');
    const kind = document.createElement('b');
    kind.textContent = a.loading ? '…' : (a.kind === 'file' ? (a.mime === 'application/pdf' ? 'PDF' : '画像') : (a.kind === 'url' ? 'URL' : 'カード'));
    if (a.failed) chip.classList.add('failed');
    const name = document.createElement('span');
    name.textContent = a.name;
    name.title = a.name;
    const x = document.createElement('button');
    x.textContent = '✕';
    x.onclick = () => {
      aiAttachments.splice(k, 1);
      // 参照カードを外したら、指示欄の @「…」も消す
      if (a.kind === 'note') {
        el('aiPrompt').querySelectorAll('.mention').forEach((m) => { if (m.dataset.label === a.label) m.remove(); });
        syncPromptState();
      }
      renderAttachments();
    };
    if (a.kind === 'file' && /^image\//.test(a.mime || '') && a.dataBase64) {
      chip.classList.add('image');
      const img = document.createElement('img');
      img.src = `data:${a.mime};base64,${a.dataBase64}`;
      img.alt = a.name;
      img.onclick = (e) => { e.stopPropagation(); openLightbox(img.src, a.name); };
      chip.append(img, x);
    } else if (a.kind === 'note' || a.kind === 'snippet') {
      return;                                       // 参照カード・ひな形は指示欄の中に印として出す
    } else {
      if (a.kind === 'url') chip.classList.add('url');
      chip.append(kind, name, x);
    }
    box.appendChild(chip);
  });
  box.hidden = !box.childNodes.length;
}

/* ---- 指示欄 (contenteditable)。@ の参照は本物の印として中に置く ---- */

/** 指示欄の中身を文字にする。印は @「名前」、改行は \n */
function promptText() {
  const box = el('aiPrompt');
  let out = '';
  const walk = (node) => {
    for (const child of node.childNodes) {
      if (child.nodeType === 3) { out += child.nodeValue; continue; }
      if (child.nodeType !== 1) continue;
      if (child.classList.contains('mention')) {
        out += child.dataset.snippet ? `/「${child.dataset.label}」` : `@「${child.dataset.label}」`;
        continue;
      }
      if (child.tagName === 'BR') { out += '\n'; continue; }
      const block = /^(DIV|P)$/.test(child.tagName);
      if (block && out && !out.endsWith('\n')) out += '\n';
      walk(child);
      if (block && !out.endsWith('\n')) out += '\n';
    }
  };
  walk(box);
  return out.replace(/\u00a0/g, ' ').replace(/\n$/, '');
}
/** 指示欄を書き換える (空にする・定型文を入れる) */
function setPromptText(text) {
  const box = el('aiPrompt');
  box.textContent = text;
  placeCaretEnd(box);
  syncPromptState();
}
function placeCaretEnd(box) {
  const range = document.createRange();
  range.selectNodeContents(box);
  range.collapse(false);
  const sel = window.getSelection();
  sel.removeAllRanges();
  sel.addRange(range);
}
/** 印が消えていたら、その参照も外す。空なら placeholder を出す */
function syncPromptState() {
  const box = el('aiPrompt');
  const present = new Set([...box.querySelectorAll('.mention')].map((m) => m.dataset.label));
  const before = aiAttachments.length;
  aiAttachments = aiAttachments.filter((a) => !((a.kind === 'note' || a.kind === 'snippet') && a.label && !present.has(a.label)));
  if (aiAttachments.length !== before) renderAttachments();
  box.classList.toggle('empty', !promptText().trim() && !box.querySelector('.mention'));
}

/** 指示欄に書かれた URL を資料として取り込む (同じものは 1 回だけ) */
async function attachUrlsInPrompt() {
  const urls = [...promptText().matchAll(/https?:\/\/[^\s「」()<>"']+/g)].map((u) => u[0]);
  for (const url of urls) {
    if (aiAttachments.some((a) => a.url === url)) continue;
    const pending = { kind: 'url', name: url, url, text: '', loading: true };
    aiAttachments.push(pending);
    renderAttachments();
    try {
      const r = await window.anki.aiFetchUrl(url);
      Object.assign(pending, r.dataBase64
        ? { kind: 'file', name: r.name, mime: r.mime, dataBase64: r.dataBase64 }
        : { name: r.name, text: r.text });
    } catch (err) {
      pending.name = `${url} (読み込めません: ${err.message || err})`;
      pending.failed = true;
    }
    delete pending.loading;
    renderAttachments();
  }
}

/** @ でカードを参照する: 候補を出し、選んだカードを資料に加える */
let mentionItems = [];
let mentionAt = 0;
function mentionQuery() {
  const sel = window.getSelection();
  if (!sel || !sel.rangeCount || !sel.isCollapsed) return null;
  const r = sel.getRangeAt(0);
  const node = r.startContainer;
  if (node.nodeType !== 3 || !el('aiPrompt').contains(node)) return null;
  const head = node.nodeValue.slice(0, r.startOffset);
  const m = /(?:^|\s)([@/])([^\s@/]{0,40})$/.exec(head);
  if (!m) return null;
  const start = r.startOffset - m[0].length + (/^[@/]/.test(m[0]) ? 0 : 1);
  return { kind: m[1] === '/' ? 'snippet' : 'note', q: m[2], node, start, end: r.startOffset };
}
let mentionTimer = null;
function mentionUpdate() {
  const list = el('aiMention');
  const mq = mentionQuery();
  if (!mq) { list.hidden = true; return; }
  clearTimeout(mentionTimer);
  if (mq.kind === 'snippet') {
    const q = mq.q.toLowerCase();
    mentionItems = loadSnippets()
      .filter((x) => x.name && x.html)
      .map((x) => ({ snippet: true, name: x.name, html: x.html }))
      .filter((x) => !q || x.name.toLowerCase().includes(q))
      .sort((a, b) => (q ? a.name.toLowerCase().indexOf(q) - b.name.toLowerCase().indexOf(q) : 0));
    mentionAt = 0;
    list.innerHTML = '';
    if (!mentionItems.length) {
      list.innerHTML = `<div class="hint">${q ? '見つかりません' : 'スニペットがありません (設定 › スニペット で登録)'}</div>`;
    } else {
      const head = document.createElement('div');
      head.className = 'hint';
      head.textContent = 'ひな形として渡すスニペット';
      list.appendChild(head);
    }
    mentionItems.forEach((x, k) => {
      const b = document.createElement('button');
      b.textContent = '/ ' + x.name;
      b.title = x.html.slice(0, 200);
      if (k === 0) b.classList.add('on');
      b.onmousedown = (e) => { e.preventDefault(); mentionPick(k); };
      list.appendChild(b);
    });
    list.hidden = false;
    return;
  }
  mentionTimer = setTimeout(async () => {
    let found = [];
    try {
      if (mq.q) {
        found = await call('searchNotes', { query: mq.q, limit: 40, brief: 1 });
        // 語の一部でも当たるように、2 文字ずつの断片でも探して足す
        if (found.length < 10 && mq.q.length >= 2) {
          const parts = [...new Set([mq.q.slice(0, 2), mq.q.slice(-2)])];
          for (const part of parts) {
            const more = await call('searchNotes', { query: part, limit: 30, brief: 1 });
            for (const n of more) if (!found.some((x) => x.id === n.id)) found.push(n);
          }
        }
      } else {
        found = state.notes.slice(0, 30);
      }
    } catch { found = []; }
    const q = mq.q.toLowerCase();
    const scoreOf = (n) => {
      const title = noteTitle(n, 3).join(' ').toLowerCase();
      if (!q) return 0;
      if (title.includes(q)) return 300 - title.indexOf(q);                  // 見出しに語そのもの
      let frag = 0;
      for (let k = 0; k + 2 <= q.length; k++) if (title.includes(q.slice(k, k + 2))) frag++;
      if (frag) return 100 + frag * 10;                                       // 見出しに語の一部
      return 10;                                                              // 本文だけ
    };
    mentionItems = found
      .filter((n) => !state.selected || n.id !== state.selected.id)
      .map((n) => [scoreOf(n), n])
      .sort((a, b) => b[0] - a[0])
      .slice(0, 30)
      .map(([, n]) => n);
    mentionAt = 0;
    list.innerHTML = '';
    if (!mentionItems.length) {
      list.innerHTML = `<div class="hint">${mq.q ? '見つかりません' : 'カード名を続けて打つと絞り込めます'}</div>`;
    }
    mentionItems.forEach((n, k) => {
      const b = document.createElement('button');
      b.textContent = noteTitle(n, 2).join(' ');
      if (k === 0) b.classList.add('on');
      b.onmousedown = (e) => { e.preventDefault(); mentionPick(k); };
      list.appendChild(b);
    });
    list.hidden = false;
  }, 120);
}
async function mentionPick(k) {
  const n = mentionItems[k];
  const mq = mentionQuery();
  if (!n || !mq) return;
  const box = el('aiPrompt');
  if (n.snippet) {
    // 「/名前」をひな形の印に置き換える
    const text = mq.node;
    const chip = document.createElement('span');
    chip.className = 'mention snip';
    chip.contentEditable = 'false';
    chip.dataset.label = n.name;
    chip.dataset.snippet = '1';
    chip.textContent = '/' + n.name;
    chip.title = 'このスニペットをひな形にする';
    const tail = document.createTextNode(' ' + text.nodeValue.slice(mq.end).replace(/^ /, ''));
    text.nodeValue = text.nodeValue.slice(0, mq.start);
    text.after(chip, tail);
    const range = document.createRange();
    range.setStart(tail, 1);
    range.collapse(true);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    el('aiMention').hidden = true;
    if (!aiAttachments.some((a) => a.kind === 'snippet' && a.label === n.name)) {
      aiAttachments.push({ kind: 'snippet', label: n.name, name: n.name, text: n.html });
      renderAttachments();
    }
    syncPromptState();
    box.focus();
    return;
  }
  const label = noteTitle(n, 1).join(' ').slice(0, 30);
  // 「@語」の部分を印に置き換える
  const text = mq.node;
  const beforeText = text.nodeValue.slice(0, mq.start);
  const afterText = text.nodeValue.slice(mq.end);
  const chip = document.createElement('span');
  chip.className = 'mention';
  chip.contentEditable = 'false';
  chip.dataset.label = label;
  chip.dataset.note = String(n.id);
  chip.textContent = '@' + label;
  chip.title = noteTitle(n, 2).join(' ');
  const tail = document.createTextNode(' ' + afterText.replace(/^ /, ''));
  text.nodeValue = beforeText;
  text.after(chip, tail);
  const range = document.createRange();
  range.setStart(tail, 1);
  range.collapse(true);
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
  el('aiMention').hidden = true;
  syncPromptState();
  if (!aiAttachments.some((a) => a.noteId === n.id)) {
    try {
      const c = await call('noteContent', { id: n.id });
      aiAttachments.push({ kind: 'note', noteId: n.id, label, name: noteTitle(n, 2).join(' '), text: c.fields.join('\n') });
      renderAttachments();
    } catch { /* 拾えなければ無し */ }
  }
  box.focus();
}
function mentionKey(e) {
  const list = el('aiMention');
  if (list.hidden) return;
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    mentionAt = (mentionAt + (e.key === 'ArrowDown' ? 1 : -1) + mentionItems.length) % Math.max(1, mentionItems.length);
    list.querySelectorAll('button').forEach((b, k) => {
      b.classList.toggle('on', k === mentionAt);
      if (k === mentionAt) b.scrollIntoView({ block: 'nearest' });
    });
  } else if (e.key === 'Enter' || e.key === 'Tab') {
    if (!mentionItems.length) return;
    e.preventDefault();
    e.stopPropagation();
    mentionPick(mentionAt);
  } else if (e.key === 'Escape') {
    e.stopPropagation();
    list.hidden = true;
  }
}

let aiJob = null;          // いま扱っている対象
let aiBusy = false;        // 問い合わせ中 (停止ボタンを出す)
let aiChat = [];           // やりとりの記録 { role: 'me'|'ai', text, ts, err? }
let aiReviewSnap = null;   // 提案を入れる前の欄の中身 { 欄番号: 文字列 } (適用したら履歴へ)
const aiEdits = { past: [], future: [] };   // AI の編集の履歴 (適用した欄の前後)

function aiLogPush(role, text, extra = {}) {
  const item = { role, text, ts: Date.now(), ...extra };
  aiChat.push(item);
  if (aiChat.length > 60) aiChat.shift();
  renderAiLog();
  return item;
}
function renderAiLog() {
  const box = el('aiLog');
  if (!box) return;
  box.hidden = !aiChat.length;
  box.innerHTML = '';
  for (const m of aiChat) {
    const row = document.createElement('div');
    row.className = 'ai-msg ' + m.role;
    if (m.images && m.images.length) {
      const strip = document.createElement('div');
      strip.className = 'ai-msg-images';
      for (const im of m.images) {
        const img = document.createElement('img');
        img.src = im.src;
        img.alt = im.name || '';
        img.onclick = () => openLightbox(im.src, im.name);
        strip.appendChild(img);
      }
      row.appendChild(strip);
    }
    if (m.files && m.files.length) {
      const strip = document.createElement('div');
      strip.className = 'ai-msg-files';
      strip.textContent = m.files.join(' · ');
      row.appendChild(strip);
    }
    const bubble = document.createElement('div');
    bubble.className = 'bubble' + (m.err ? ' err' : '') + (m.pending ? ' pending' : '');
    bubble.textContent = m.text;
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = (m.role === 'me' ? 'あなた' : (m.who || 'AI')) + ' · ' +
      new Date(m.ts).toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit' });
    row.append(bubble, meta);
    box.appendChild(row);
  }
  // 最後の AI の返事の下に、元に戻す／やり直す／記憶に追加
  const rows = box.querySelectorAll('.ai-msg.ai');
  const last = rows[rows.length - 1];
  if (last && (aiEdits.past.length || aiEdits.future.length)) {
    const tools = document.createElement('div');
    tools.className = 'ai-msg-tools';
    const undoB = document.createElement('button');
    undoB.textContent = '↶ 元に戻す';
    undoB.disabled = !aiEdits.past.length;
    undoB.onclick = () => aiEditStep(-1);
    const redoB = document.createElement('button');
    redoB.textContent = '↷ やり直す';
    redoB.disabled = !aiEdits.future.length;
    redoB.onclick = () => aiEditStep(1);
    tools.append(undoB, redoB);
    if (aiLast) {
      const memB = document.createElement('button');
      memB.textContent = '記憶に追加';
      memB.title = 'この直し方を今後も使うルールとして記憶に加える';
      memB.onclick = async () => { memB.disabled = true; memB.textContent = '追加中…'; await rememberEditPattern(memB); };
      tools.append(memB);
    }
    last.appendChild(tools);
  }
  box.scrollTop = box.scrollHeight;
}
/** チャットを最初から: 記録・資料・作り直しの引き継ぎを消す (確認中の提案は破棄) */
function resetAiChat() {
  if (aiBusy) cancelAi();
  if (state.editor && state.editor.isReviewing && state.editor.isReviewing()) finishReview(false);
  aiChat = [];
  aiLast = null;
  aiEdits.past.length = 0;
  aiEdits.future.length = 0;
  aiAttachments = [];
  renderAttachments();
  setPromptText('');
  el('aiHint').textContent = '';
  renderAiLog();
}

/** AI の編集を 1 つ戻す / やり直す (欄ごと入れ替えるので、その後の手入力も一緒に戻る) */
function aiEditStep(dir, { silent = false } = {}) {
  const from = dir < 0 ? aiEdits.past : aiEdits.future;
  const to = dir < 0 ? aiEdits.future : aiEdits.past;
  const step = from.pop();
  if (!step || !state.draft) return;
  const target = dir < 0 ? step.before : step.after;
  to.push(step);
  const next = state.draft.fields.slice();
  for (const [k, v] of Object.entries(target)) next[Number(k)] = v;
  state.draft.fields = next;
  if (state.editor && state.editor.setValues) state.editor.setValues(next);
  state.dirty = true;
  updateSaveButton();
  livePreview();
  if (!silent) aiLogPush('ai', dir < 0 ? '編集を元に戻しました' : '編集をやり直しました', { who: 'メモ' });
  renderAiLog();
}
let aiLast = null;         // 直前の提案 { ask, reply, scope } (作り直しのときに渡す)
let aiActive = null;       // 使える AI (無ければ null)
let aiReady = [];          // 確認済みの AI すべて (窓の中で切り替えられる)
let aiScopeNow = 'field';
let aiEffort = prefs.getItem('aiEffort') || 'medium';

/** 使える AI があるかを調べ、ボタンの表示を切り替える */
async function refreshAi() {
  try {
    const r = await window.anki.aiList();
    aiActive = r.active;
    aiReady = r.providers.filter((x) => x.ok);
    // モデルの一覧は 1 日に 1 回、裏で取り直す (手で「取得」しなくてよいように)
    for (const pv of aiReady) {
      if (Date.now() - (pv.modelsFetchedAt || 0) < 86400000) continue;
      window.anki.aiModels(pv.id)
        .then((ids) => window.anki.aiSave({ id: pv.id, modelsOnly: true, models: ids }))
        .then(() => { if (!refreshAi.again) { refreshAi.again = true; refreshAi().finally(() => { refreshAi.again = false; }); } })
        .catch(() => {});
    }
  } catch { aiActive = null; aiReady = []; }
  const b = el('aiOpen');
  if (b) b.hidden = !aiActive;
  // 窓のプルダウンは「AI × モデル」の組で並べる (確認済みの AI だけ)
  const pick = el('aiModel');
  if (pick) {
    pick.innerHTML = aiReady.map((x) => {
      // 主要なモデル + 設定で足したもの + いま選んでいるもの
      const models = visibleModels(x.kind, x.models, x.extraModels, x.model);
      return `<optgroup label="${x.label}">` +
        models.map(([id, label]) => `<option value="${x.id}|${id}">${x.label} · ${label}</option>`).join('') +
        '</optgroup>';
    }).join('');
    if (aiActive) pick.value = `${aiActive.id}|${aiActive.model}`;
  }
  if (!aiActive) closeAi();
}

function aiScope() { return aiScopeNow; }

function setAiScope(scope) {
  aiScopeNow = scope;
  document.querySelectorAll('#aiScopeSeg button').forEach((b) => {
    b.classList.toggle('on', b.dataset.scope === scope);
  });
  if (state.draft) aiPickTarget();
}

/** 対象を決め直す (範囲の切り替えのたびに) */
function aiPickTarget() {
  const i = state.focusedField || 0;
  const scope = aiScope();
  if (scope === 'all') {
    aiJob = { scope, fields: state.draft.fields.slice() };
    el('aiTarget').innerHTML = `<b>エディタ全体</b> (${state.fieldNames.length} 欄)`;
    return;
  }
  const name = state.fieldNames[i] || `欄 ${i + 1}`;
  if (scope === 'selection' && state.mode === 'html' && state.editor && state.editor.selectionOf) {
    const sel = state.editor.selectionOf(i);
    if (!sel.whole) {
      aiJob = { scope, field: sel.field, start: sel.start, end: sel.end, text: sel.text };
      el('aiTarget').innerHTML = `<b>${name}</b> の選択範囲 (${sel.end - sel.start} 文字)`;
      return;
    }
    el('aiTarget').innerHTML = `<b>${name}</b> — 選択範囲が無いので欄全体を対象にします`;
  } else {
    el('aiTarget').innerHTML = `<b>${name}</b> の全体`;
  }
  const text = state.draft.fields[i] || '';
  aiJob = { scope: 'field', field: i, start: 0, end: text.length, text };
}

function openAi() {
  if (!state.editing || !state.draft || !aiActive) return;
  const pop = el('aiPop');
  // 選択範囲があればそれを、無ければ欄を既定にする。選択が無いときは選択範囲を選べなくする
  const i = state.focusedField || 0;
  const hasSel = state.mode === 'html' && state.editor && state.editor.selectionOf &&
    !state.editor.selectionOf(i).whole;
  const selBtn = document.querySelector('#aiScopeSeg button[data-scope=selection]');
  if (selBtn) { selBtn.disabled = !hasSel; selBtn.title = hasSel ? '' : '本文で範囲を選ぶと使えます'; }
  // 既定の範囲は設定から。「選択範囲」を選んでいても、選択が無いときは「この欄」にする
  const wanted = prefs.getItem('aiScopeDefault') || 'auto';
  const scope = wanted === 'auto'
    ? (hasSel ? 'selection' : 'field')
    : (wanted === 'selection' && !hasSel ? 'field' : wanted);
  setAiScope(scope);
  el('aiHint').textContent = '';
  const reviewing = state.editor && state.editor.isReviewing && state.editor.isReviewing();
  el('aiPrompt').dataset.placeholder = reviewing
    ? '修正の指示 (例: 表は残してもっと短く／見出しは変えないで)。前の提案は破棄して作り直します'
    : '例：文体をですます調にして／@ でカードを参照／/ でスニペットをひな形に／URL はそのまま資料に';
  syncPromptState();
  el('aiOpen').classList.remove('pending');
  pop.hidden = false;
  requestAnimationFrame(() => {
    pop.classList.add('open');
    el('aiOpen').classList.add('on');
    el('aiPrompt').focus();
  });
}

function closeAi() {
  const pop = el('aiPop');
  if (!pop || pop.hidden) return;
  pop.classList.remove('open');
  el('aiOpen').classList.remove('on');
  el('aiMention').hidden = true;
  setTimeout(() => { pop.hidden = true; }, 160);
}

function stripFence(text) {
  return text.trim().replace(/^```[a-z]*\n?/i, '').replace(/\n?```$/, '');
}

async function runAi() {
  // URL は資料として別に渡すので、指示文からは取り除く (残すと AI が文として読んでしまう)
  const askRaw = promptText().trim();
  const ask = askRaw.replace(/https?:\/\/[^\s「」()<>"']+/g, '').replace(/[ \t]{2,}/g, ' ').trim() || (askRaw ? '添付の資料を踏まえて直して' : '');
  if (!ask || !aiJob) return;
  const pop = el('aiPop');
  // 提案を確認中に指示を出したら「作り直し」。前の提案は破棄して、対象を取り直す
  let redo = null;
  // 直前の AI 編集に続けて指示したら、その編集は残したまま重ねて直す (前回の指示と提案は参考として渡す)
  if (aiLast && aiEdits.past.length && aiEdits.past[aiEdits.past.length - 1].ai === aiLast) {
    redo = aiLast;
    aiPickTarget();                    // 対象はいまの範囲 (前回の編集で位置が変わっているため取り直す)
  }
  pop.classList.add('busy');
  aiBusy = true;
  el('aiRun').innerHTML = '停止 <kbd>Esc</kbd>';
  el('aiRun').classList.add('stop');
  el('aiHint').textContent = '';
  aiLogPush('me', ask, {
    images: aiAttachments.filter((a) => a.kind === 'file' && /^image\//.test(a.mime || '') && a.dataBase64)
      .map((a) => ({ src: `data:${a.mime};base64,${a.dataBase64}`, name: a.name })),
    files: aiAttachments.filter((a) => !(a.kind === 'file' && /^image\//.test(a.mime || '')))
      .map((a) => (a.kind === 'note' ? '@' + (a.label || a.name) : a.kind === 'snippet' ? '/' + a.name : (a.kind === 'url' ? a.url || a.name : a.name))),
  });
  // 「覚えて: …」の行、または「この指示を記憶」が入っていれば、記憶にも足す
  {
    const lines = ask.split('\n');
    const memoLines = lines.filter((l) => /^(覚えて|記憶)[:：]/.test(l.trim())).map((l) => l.replace(/^(覚えて|記憶)[:：]\s*/, '').trim());
    const wantAll = el('aiRememberAsk').classList.contains('on');
    const add = wantAll ? [ask] : memoLines;
    if (add.length) {
      try {
        await memoryBackup('指示からの追記の前');
        const m = (await memoryGet()) || {};
        const stamp = new Date().toLocaleDateString('ja-JP');
        const text = (m.text ? m.text.replace(/\s*$/, '') + '\n\n' : '') + `## 指示からの記憶 (${stamp})\n` + add.map((x) => '- ' + x).join('\n');
        await window.anki.settingsSet('aiMemory', { ...m, text, builtAt: Date.now() });
        refreshMemoryChip();
        aiLogPush('ai', '記憶に加えました: ' + add.join(' / ').slice(0, 80), { who: 'メモ' });
      } catch { /* 記憶できなくても続ける */ }
      if (wantAll) el('aiRememberAsk').classList.remove('on');
    }
  }
  const pendingMsg = aiLogPush('ai', '考えています', { pending: true, who: aiActive ? aiActive.label : 'AI' });
  el('previewPane').classList.add('ai-updating');
  setPromptText('');
  const names = state.fieldNames;
  let system = AI_SYSTEM;
  let prompt;
  // 空に近い欄 (資料から本文を作るときなど) は、編集の一覧ではなく直接書かせる
  const targetLen = aiJob.scope === 'all'
    ? aiJob.fields.join('').length
    : (state.draft.fields[aiJob.field] || '').length;
  const patchMode = aiJob.scope !== 'selection' && targetLen > 400;
  // ① 書き方 / ③ 参照カード・スニペット
  let extras = '';
  const memo = await memoryGet();
  if (memo && memo.text) {
    extras += `\n\n## このデッキの書き方 (記憶。特に指示が無ければこれに合わせ、class や表、見出しの並びを引き継いで他のカードと統一する)\n${memo.text}`;
  }
  // 指示文の中の URL は、そのまま資料として読み込む
  await attachUrlsInPrompt();
  const urls = [];
  for (const u of urls) {
    if (aiAttachments.some((a) => a.url === u || a.name === u)) continue;
    el('aiHint').textContent = 'URL を読み込んでいます…';
    try {
      const r = await window.anki.aiFetchUrl(u);
      if (r.dataBase64) aiAttachments.push({ kind: 'file', name: r.name, mime: r.mime, dataBase64: r.dataBase64, url: u });
      else aiAttachments.push({ kind: 'url', name: r.name, url: r.url, text: r.text });
    } catch (e) { el('aiHint').textContent = `URL を読めません: ${e.message || e}`; }
  }
  renderAttachments();
  const snippets = loadSnippets().filter((x) => x.name && x.html);
  if (snippets.length) {
    extras += `\n\n## 登録済みスニペット (指示で名前を挙げられたら、この HTML を土台にして中身を埋める)\n` +
      snippets.map((x) => `### ${x.name}\n${x.html.slice(0, 600)}`).join('\n');
  }
  const refs = [...aiAttachments.filter((a) => a.kind !== 'file' && !a.failed && !a.loading)];
  if (!refs.some((a) => a.kind === 'note')) refs.push(...(await autoNoteRefs(ask)));
  let materials = '';
  for (const a of refs) {
    if (a.kind === 'snippet') {
      materials += `\n\n## ひな形「${a.name}」(指示文で /「${a.name}」と示したスニペット。この HTML を土台にして、指示に従って中身を埋める・書き足す。構造や class はそのまま使う)\n${a.text}`;
    } else if (a.kind === 'url') {
      materials += `\n\n## 資料 (URL: ${a.url})\n${a.text}`;
    } else {
      materials += `\n\n## 参考カード「${a.name}」の HTML (書き方やデザインの手本。必要なら部品を借りる)\n${a.text}`;
    }
  }
  const files = aiAttachments.filter((a) => a.kind === 'file');
  // 添付の画像には、本文に入れるときのファイル名を決めておく (使われたものだけコレクションへ保存する)
  const stampBase = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
  const plannedImages = files
    .filter((f) => /^image\//.test(f.mime || ''))
    .map((f, k) => ({ ...f, planned: `ai_${stampBase}_${k + 1}.${(f.mime.split('/')[1] || 'png').replace('jpeg', 'jpg')}` }));
  if (plannedImages.length) {
    materials += `\n\n## 添付画像を本文に入れる場合\n指示があれば (または必要と判断したら)、添付画像は次のファイル名で <img src="ファイル名"> と書いて入れる。切り抜きはできないので、必要なら「この画像は切り抜きが要る」と返事に添える:\n` +
      plannedImages.map((f, k) => `- ${k + 1} 枚目 (${f.name}): ${f.planned}`).join('\n');
  }
  // 送ったら入力欄の下からは片づける (記録の側に残る)
  const sentAttachments = aiAttachments;
  aiAttachments = [];
  renderAttachments();
  if (files.length) materials += `\n\n## 添付の資料\n${files.map((f) => '- ' + f.name).join('\n')} (画像・PDF は添付で渡す)`;
  if (patchMode) {
    system = AI_SYSTEM_PATCH + extras;
    const targets = aiJob.scope === 'all'
      ? names.map((n, k) => [n, aiJob.fields[k] || ''])
      : [[names[aiJob.field] || `欄 ${aiJob.field + 1}`, state.draft.fields[aiJob.field] || '']];
    const others = aiJob.scope === 'all' ? '' : names.map((n, k) => {
      if (k === aiJob.field) return null;
      const v = (state.draft.fields[k] || '').trim();
      return v ? `### ${n}\n${v.slice(0, 1500)}` : null;
    }).filter(Boolean).join('\n\n');
    prompt = `## 指示\n${ask}\n\n## 本文 (変える箇所を「編集」の一覧で返す)\n` +
      targets.map(([n, v]) => `===== 欄: ${n} =====\n${v}`).join('\n') +
      (others ? `\n\n## 同じカードの他の欄 (参考。編集しない)\n${others}` : '');
  } else if (aiJob.scope !== 'selection') {
    // 欄がほぼ空: 直接書かせる (資料から本文を作る場合など)
    system = AI_SYSTEM + extras;
    const name = names[aiJob.field] || `欄 ${aiJob.field + 1}`;
    const around = names.map((n, k) => {
      if (k === aiJob.field) return null;
      const v = (state.draft.fields[k] || '').trim();
      return v ? `### ${n}\n${v.slice(0, 1500)}` : null;
    }).filter(Boolean).join('\n\n');
    prompt = `## 指示\n${ask}\n\n## 対象 (${name} の全体。いまの中身は次のとおり。空なら新しく書く)\n${state.draft.fields[aiJob.field] || ''}\n\n` +
      (around ? `## 同じカードの他の欄 (参考。返さないこと)\n${around}` : '');
  } else {
    system = AI_SYSTEM + extras;
    const name = names[aiJob.field] || `欄 ${aiJob.field + 1}`;
    const full = state.draft.fields[aiJob.field] || '';
    const around = names.map((n, k) => {
      if (k === aiJob.field) return null;
      const v = (state.draft.fields[k] || '').trim();
      return v ? `### ${n}\n${v.slice(0, 1500)}` : null;
    }).filter(Boolean).join('\n\n');
    prompt = `## 指示\n${ask}\n\n## 対象 (${name} の選択範囲)\n${aiJob.text}\n\n` +
      `## 対象の前後 (参考。返さないこと)\n…${full.slice(Math.max(0, aiJob.start - 400), aiJob.start)}【対象】${full.slice(aiJob.end, aiJob.end + 400)}…\n\n` +
      (around ? `## 同じカードの他の欄 (参考。返さないこと)\n${around}` : '');
  }
  if (aiJob.scope === 'all' && !patchMode) {
    // 全体がほぼ空のときは、欄ごとの区切りで全文を書かせる
    system = AI_SYSTEM + extras + '\n\n複数の欄を渡します。欄ごとに「===== 欄: 名前 =====」で区切り、すべての欄を同じ順で返してください。';
    prompt = `## 指示\n${ask}\n\n## 対象 (すべての欄)\n` +
      names.map((n, k) => `===== 欄: ${n} =====\n${aiJob.fields[k] || ''}`).join('\n');
  }
  prompt += materials;
  if (redo) {
    prompt += `\n\n## 前回の指示\n${redo.ask}\n\n## 前回の提案 (すでに本文に入っている。今回の指示はその上に重ねて直す)\n${redo.reply.slice(0, 6000)}`;
  }
  try {
    const r = await window.anki.aiAsk({
      system, prompt, maxTokens: 64000, effort: aiEffort,
      attachments: files.map((f) => ({ name: f.name, mime: f.mime, dataBase64: f.dataBase64 })),
      webSearch: prefs.getItem('aiWebSearch') !== 'off',
    });
    let text = stripFence(r.text);
    // 本文に使われた添付画像だけ、コレクションに保存する
    for (const f of plannedImages) {
      if (!text.includes(f.planned)) continue;
      try {
        const saved = await call('addMedia', { name: f.planned, dataBase64: f.dataBase64 });
        if (saved && saved !== f.planned) text = text.split(f.planned).join(saved);
      } catch (err) { aiLogPush('ai', `画像を保存できませんでした: ${f.planned} (${err.message || err})`, { who: 'メモ', err: true }); }
    }
    let reply = '';
    const cut = text.lastIndexOf('<<<返事>>>');
    if (cut >= 0) {
      reply = text.slice(cut + '<<<返事>>>'.length).trim();
      text = text.slice(0, cut).replace(/\s+$/, '');
    }
    // 数式の書き方を元に合わせる: このカードが <anki-mathjax> を使っているなら、\(…\) は戻す
    const usesTag = (aiJob.scope === 'all' ? aiJob.fields : [state.draft.fields[aiJob.field] || '']).some((f) => /<anki-mathjax/.test(f));
    const usesBs = (aiJob.scope === 'all' ? aiJob.fields : [state.draft.fields[aiJob.field] || '']).some((f) => /\\\(/.test(f));
    if (usesTag || !usesBs) {
      text = text
        .replace(/\\\[([\s\S]*?)\\\]/g, '<anki-mathjax block="true">$1</anki-mathjax>')
        .replace(/\\\(([\s\S]*?)\\\)/g, '<anki-mathjax>$1</anki-mathjax>');
    }
    if (r.sources && r.sources.length) {
      reply += (reply ? ' ' : '') + '参照: ' + r.sources.map((u) => u.replace(/^https?:\/\//, '').split('/')[0]).join(', ');
    }
    aiLast = {
      ask: redo ? `${redo.ask}\n(修正) ${ask}` : ask, reply: text,
      job: { scope: aiJob.scope, field: aiJob.field, start: aiJob.start, end: aiJob.end },
    };
    let next;
    let missed = 0;
    if (aiJob.scope === 'all' && !patchMode) {
      const parts = {};
      const re = /===== 欄: (.+?) =====\n?([\s\S]*?)(?=\n===== 欄: |$)/g;
      let m;
      while ((m = re.exec(text))) parts[m[1].trim()] = m[2].replace(/\n$/, '');
      if (!Object.keys(parts).length) throw new Error('返ってきた形が読めませんでした');
      next = names.map((n, k) => (n in parts ? parts[n] : aiJob.fields[k]));
    } else if (aiJob.scope === 'field' && !patchMode) {
      next = state.draft.fields.slice();
      next[aiJob.field] = text;
    }
    if (patchMode) {
      if (/<<<変更なし>>>/.test(text)) { el('aiHint').textContent = '変更はありませんでした'; pop.classList.remove('busy'); return; }
      const patches = parsePatches(text, names);
      if (!patches.length) throw new Error('返ってきた形が読めませんでした');
      const applied = applyPatches(state.draft.fields, patches);
      next = applied.next;
      missed = applied.miss;
      if (!applied.hit) throw new Error('提案の箇所が本文に見つかりませんでした (もう一度試してください)');
    }
    // テキストモードでは違いを色で見せられないので、HTML モードに切り替えて見せる
    if (state.mode !== 'html') setMode('html');
    let changed = 0;
    aiReviewSnap = {};
    if (aiJob.scope !== 'selection') {
      next.forEach((v, k) => { if (v !== state.draft.fields[k]) aiReviewSnap[k] = state.draft.fields[k] || ''; });
    } else {
      aiReviewSnap[aiJob.field] = state.draft.fields[aiJob.field] || '';
    }
    if (aiJob.scope !== 'selection') {
      next.forEach((v, k) => {
        if (v !== state.draft.fields[k] &&
            state.editor.review(k, 0, (state.draft.fields[k] || '').length, v)) changed++;
      });
    } else if (state.editor.review(aiJob.field, aiJob.start, aiJob.end, text)) {
      changed = 1;
    }
    pendingMsg.pending = false;
    pendingMsg.who = r.label;
    pendingMsg.text = reply || (changed ? `${changed} 欄を直しました` : '変更はありませんでした');
    renderAiLog();
    if (!changed) { el('aiHint').textContent = '変更はありませんでした'; pop.classList.remove('busy'); el('previewPane').classList.remove('ai-updating'); return; }
    el('aiHint').textContent = '';
    // 提案はそのまま適用する (戻したければ「元に戻す」か ⌘Z)。入った瞬間、プレビューがふわっと光る
    const pane = el('previewPane');
    pane.classList.remove('ai-updating');
    pane.classList.add('ai-landed');
    setTimeout(() => pane.classList.remove('ai-landed'), 1000);
    if (missed) aiLogPush('ai', `当たらなかった編集 ${missed} 件は見送りました`, { who: 'メモ' });
    finishReview(true, { silent: true });
  } catch (e) {
    const reviewing = state.editor && state.editor.isReviewing && state.editor.isReviewing();
    if (/中断しました/.test(e.message || '')) {
      pendingMsg.pending = false; pendingMsg.text = '中断しました'; pendingMsg.who = 'メモ';
      renderAiLog();
      el('previewPane').classList.remove('ai-updating');
      pop.classList.remove('busy'); aiBusy = false;
      el('aiRun').innerHTML = '実行 <kbd>⌘↩</kbd>'; el('aiRun').classList.remove('stop');
      return;
    }
    try { window.anki.mark('AI の処理で失敗: ' + (e.stack || e.message || e)); } catch { /* 記録できなくても進む */ }
    if (reviewing) {
      // 提案そのものは本文に入っている。画面の後処理だけが失敗したので、返事はそのまま見せる
      pendingMsg.pending = false;
      if (!pendingMsg.text || pendingMsg.text === '考えています') pendingMsg.text = '提案を本文に入れました';
      renderAiLog();
      el('aiHint').textContent = '';
    } else {
      pendingMsg.pending = false;
      pendingMsg.err = true;
      pendingMsg.text = `できませんでした: ${e.message || e}`;
      renderAiLog();
      el('aiHint').textContent = `できませんでした: ${e.message || e}`;
    }
    el('previewPane').classList.remove('ai-updating');
  }
  pop.classList.remove('busy');
  aiBusy = false;
  el('aiRun').innerHTML = '実行 <kbd>⌘↩</kbd>';
  el('aiRun').classList.remove('stop');
}

/** 問い合わせを止める。途中の編集は何も入れない */
function cancelAi() {
  if (!aiBusy) return;
  window.anki.aiCancel();
  const last = aiChat[aiChat.length - 1];
  if (last && last.pending) { last.pending = false; last.text = '中断しました'; last.who = 'メモ'; }
  renderAiLog();
  el('previewPane').classList.remove('ai-updating');
}

/** いまの提案の「直し方」を、今後も使えるルールにして記憶へ足す */
async function rememberEditPattern(button) {
  if (!aiLast) { toast('記憶に加える提案がありません'); return; }
  button = button || el('reviewRemember');
  button.disabled = true;
  button.textContent = '記憶に追加中…';
  try {
    let rule = '';
    if (aiActive) {
      const r = await window.anki.aiAsk({
        system: '次の「指示」と「提案」から、同じ種類の指示を今後受けたときに同じ直し方ができる短いルールを 1〜3 行で書いてください。' +
          '具体的な class 名・タグ・書式はそのまま残す。返すのはルールだけ (前置きやコードフェンスは付けない)。',
        prompt: `## 指示\n${aiLast.ask}\n\n## 提案\n${aiLast.reply.slice(0, 4000)}`,
        maxTokens: 600,
        effort: 'low',
      });
      rule = (r.text || '').trim();
    }
    if (!rule) rule = `指示「${aiLast.ask.slice(0, 80)}」→ ${aiLast.reply.slice(0, 200).replace(/\s+/g, ' ')}`;
    await memoryBackup('編集の型の追加の前');
    const m = (await memoryGet()) || {};
    const stamp = new Date().toLocaleDateString('ja-JP');
    const text = (m.text ? m.text.replace(/\s*$/, '') + '\n\n' : '') + `## 編集の型 (${stamp})\n${rule}`;
    await window.anki.settingsSet('aiMemory', { ...m, text, builtAt: Date.now() });
    refreshMemoryChip();
    toast('記憶に加えました');
    button.textContent = '記憶に追加済み';
  } catch (e) {
    toast('記憶に加えられませんでした: ' + (e.message || e));
    button.textContent = '記憶に追加';
    button.disabled = false;
  }
}

/** 提案の確認バーを出す。適用か破棄を選ぶまで、その欄は触れない */
function startReview(label, reply) {
  const bar = el('reviewBar');
  // AI 窓が開いていれば窓の中で、閉じていれば上のバーで確認する
  bar.hidden = true;
  el('aiReview').hidden = true;
  el('aiPrompt').dataset.placeholder = '修正の指示 (例: 表は残してもっと短く)。前の提案は破棄して作り直します';
  renderAiLog();
  el('reviewRemember').disabled = false;
  el('reviewRemember').textContent = '記憶に追加';
  const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  bar.querySelector('.review-text').innerHTML =
    (reply ? `<span class="review-reply">${esc(reply)}</span><br>` : '') +
    `<span class="review-sub">${esc(label)} — <mark class="diff-add">追加</mark> <mark class="diff-del">削除</mark>。プレビューも提案の内容です</span>`;
  state.dirty = true;
  updateSaveButton();
  livePreview();
  const first = document.querySelector('.field.fast.reviewing');
  if (first) first.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

/** 確認を終える。keep=true なら提案を残す (⌘Z で戻せる)、false なら元に戻す */
function finishReview(keep, { silent = false } = {}) {
  if (!state.editor || !state.editor.isReviewing || !state.editor.isReviewing()) return;
  if (keep && aiReviewSnap) {
    const after = {};
    for (const k of Object.keys(aiReviewSnap)) after[k] = state.editor.values()[Number(k)];
    aiEdits.past.push({ before: aiReviewSnap, after, ai: aiLast });
    if (aiEdits.past.length > 20) aiEdits.past.shift();
    aiEdits.future.length = 0;
    renderAiLog();
  }
  aiReviewSnap = null;
  el('previewPane').classList.remove('ai-reviewing', 'ai-updating');
  state.editor.endReview(keep);
  if (keep) { aiAttachments = []; renderAttachments(); }     // 使い終わった資料は片づける
  el('reviewBar').hidden = true;
  el('aiReview').hidden = true;
  el('aiOpen').classList.remove('pending');
  if (!silent) aiLogPush('ai', keep ? '適用しました' : '提案を破棄しました', { who: 'メモ' });
  renderAiLog();
  livePreview();
  updateSaveButton();
  if (!silent) toast(keep ? '適用しました' : '提案を破棄しました');
}

/** 長い行を折り返すかどうかを、編集欄に反映する */
function applyEditorWrap() {
  const off = prefs.getItem('editorWrap') === 'off';
  el('fields').classList.toggle('nowrap', off);
  if (state.editor && state.editor.refit) state.editor.refit();
}

/** よく使う HTML のボタン列 (HTML エディタのときだけ) */
/** 列が幅を超えているとき、はみ出している側だけをフェードさせる */
function updateSnippetFade() {
  const bar = el('snippetBar');
  if (!bar || bar.hidden) return;
  const over = bar.scrollWidth - bar.clientWidth;
  const left = over > 2 && bar.scrollLeft > 2;
  const right = over > 2 && bar.scrollLeft < over - 2;
  bar.style.setProperty('--fade-l', left ? '28px' : '0px');
  bar.style.setProperty('--fade-r', right ? '28px' : '0px');
}

function renderSnippetBar() {
  const bar = el('snippetBar');
  if (!bar) return;
  if (!bar.dataset.fadeWired) {
    bar.dataset.fadeWired = '1';
    bar.addEventListener('scroll', updateSnippetFade, { passive: true });
    window.addEventListener('resize', updateSnippetFade);
    new ResizeObserver(updateSnippetFade).observe(bar);
  }
  const list = loadSnippets().filter((x) => x.name || x.html);
  const rich = state.mode === 'rich';
  bar.hidden = rich || !state.editing || !list.length;
  bar.innerHTML = '';
  for (const item of list) {
    const b = document.createElement('button');
    b.textContent = item.name || '(無題)';
    b.title = (item.html || '').slice(0, 200);
    b.onclick = () => {
      if (!state.editor || !state.editor.insertAt) return;
      const raw = item.html || '';
      const mark = raw.indexOf('{cursor}');
      const html = mark >= 0 ? raw.replace('{cursor}', '') : raw;
      state.editor.insertAt(state.focusedField || 0, html, mark >= 0 ? mark : undefined);
      livePreview();
    };
    bar.appendChild(b);
  }
  requestAnimationFrame(updateSnippetFade);
}

/** 画像を画面いっぱいに見せる (クリックか Esc で閉じる) */
function openLightbox(src, name) {
  if (!src) return;
  const box = el('lightbox');
  const img = el('lightboxImg');
  img.src = src;
  box.hidden = false;
  const view = document.getElementById('preview');
  if (view) { view.style.pointerEvents = 'none'; try { view.blur(); } catch { /* 無くてもよい */ } }
  const close = () => {
    box.hidden = true; img.src = '';
    if (view) view.style.pointerEvents = '';
    document.removeEventListener('keydown', onKey, true);
  };
  const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); close(); } };
  box.onclick = close;
  img.onclick = (e) => e.stopPropagation();          // 画像の上は閉じない (右クリックで保存できるように)
  img.oncontextmenu = (e) => {
    e.stopPropagation();
    // 念のため、後ろのプレビューに選択が出ていたら消す
    if (view && view.executeJavaScript) {
      setTimeout(() => view.executeJavaScript('getSelection().removeAllRanges()').catch(() => {}), 60);
    }
  };
  box.oncontextmenu = (e) => { e.preventDefault(); e.stopPropagation(); };
  document.addEventListener('keydown', onKey, true);
}

/** 差し込む <img> の形。設定が「角丸」なら class="round" を付ける (幅の指定は付けない) */
function imageTag(name) {
  const round = prefs.getItem('imageCorner') === 'ROUNDED';
  return `<img src="${name}"${round ? ' class="round"' : ''}>`;
}

/** 貼り付けた画像をコレクションに入れて、その場に差し込む */
async function pasteImage(i, file) {
  if (Array.isArray(file)) {                       // 複数まとめて (順に入れる)
    for (const f of file) await pasteImage(i, f);  // eslint-disable-line no-await-in-loop
    return;
  }
  if (file && file.fromFinder) {                   // Finder で写したファイル (中身は base64)
    try {
      const name = await call('addMedia', { name: file.name, dataBase64: file.dataBase64 });
      if (state.editor && state.editor.insertAt) state.editor.insertAt(i, imageTag(name));
      livePreview();
    } catch (e) { toast(`画像を入れられませんでした: ${e.message || e}`); }
    return;
  }
  try {
    const buf = await file.arrayBuffer();
    let binary = '';
    const bytes = new Uint8Array(buf);
    for (let k = 0; k < bytes.length; k += 8192) {
      binary += String.fromCharCode.apply(null, bytes.subarray(k, k + 8192));
    }
    const ext = (file.type.split('/')[1] || 'png').replace('jpeg', 'jpg');
    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
    const name = await call('addMedia', {
      name: `paste_${stamp}.${ext}`,
      dataBase64: btoa(binary),
    });
    const html = imageTag(name);
    if (state.editor && state.editor.insertAt) state.editor.insertAt(i, html);
    livePreview();
    toast('画像を入れました');
  } catch (e) {
    toast(`画像を入れられませんでした: ${e.message || e}`);
  }
}

/** 画像や音声を選んで、コレクションに入れてから差し込む */
async function insertMedia() {
  const picked = await window.anki.pickMedia();
  if (!picked) return;
  const name = await call('addMedia', picked);
  const isAudio = /\.(mp3|m4a|wav|ogg)$/i.test(name);
  const isVideo = /\.(mp4|mov|webm)$/i.test(name);
  const html = isAudio
    ? `[sound:${name}]`
    : isVideo
      ? `<video src="${name}" controls></video>`
      : imageTag(name);
  if (state.mode === 'rich' && state.editor.insert) {
    state.editor.insert(html);
  } else {
    const i = state.focusedField || 0;
    state.draft.fields[i] = (state.draft.fields[i] || '') + html;
    if (state.editor.setValues) state.editor.setValues(state.draft.fields);
    else buildEditor();
    state.dirty = true;
    livePreview();
  }
  toast('入れました');
}

function tagList() {
  return (state.draft.tags || '').trim().split(/\s+/).filter(Boolean);
}

function setTags(tags) {
  state.draft.tags = [...new Set(tags)].join(' ');
  state.dirty = true;
  updateSaveButton();
  renderEditorTags();
  livePreview();
}

function renderEditorTags() {
  const box = el('editorTags');
  box.innerHTML = '';
  for (const tag of tagList()) {
    const chip = document.createElement('span');
    chip.textContent = tag;
    const drop = document.createElement('button');
    drop.className = 'drop';
    drop.textContent = '✕';
    drop.title = 'このタグを外す';
    drop.onclick = () => setTags(tagList().filter((t) => t !== tag));
    chip.appendChild(drop);
    box.appendChild(chip);
  }
}

/** タグを選ぶ・作る小窓 */
function openTagPicker() {
  const picker = el('tagPicker');
  const input = el('tagPickerInput');
  const list = el('tagPickerList');
  picker.hidden = false;
  input.value = '';
  input.focus();

  const draw = () => {
    const word = input.value.trim();
    const already = new Set(tagList());
    const found = state.tags
      .filter((t) => !already.has(t) && (!word || t.toLowerCase().includes(word.toLowerCase())));
    list.innerHTML = '';
    if (word && !state.tags.includes(word) && !already.has(word)) {
      const make = document.createElement('button');
      make.className = 'make';
      make.textContent = `「${word}」を作って付ける`;
      make.onclick = () => { setTags([...tagList(), word]); close(); };
      list.appendChild(make);
    }
    for (const tag of found.slice(0, 60)) {
      const pick = document.createElement('button');
      pick.textContent = tag;
      pick.onclick = () => { setTags([...tagList(), tag]); close(); };
      list.appendChild(pick);
    }
    if (!list.children.length) {
      const none = document.createElement('div');
      none.style.cssText = 'padding:8px;font-size:12px;color:var(--on-surface-variant)';
      none.textContent = '候補がありません';
      list.appendChild(none);
    }
  };

  const away = (e) => {
    if (picker.contains(e.target) || e.target.closest('#addTag')) return;
    close();
  };
  function close() {
    picker.hidden = true;
    document.removeEventListener('pointerdown', away, true);
  }

  input.oninput = draw;
  input.onkeydown = (e) => {
    e.stopPropagation();
    if (e.key === 'Escape') { close(); return; }
    if (e.key !== 'Enter') return;
    const word = input.value.trim();
    if (word) { setTags([...tagList(), word]); close(); }
  };
  setTimeout(() => document.addEventListener('pointerdown', away, true), 0);
  draw();
}

function closeEditor() {
  const pane = el('editorPane');
  state.editing = false;
  if (state.editor && state.editor.destroy) state.editor.destroy();
  state.editor = null;
  el('edit').classList.remove('active');
  el('refreshPreview').hidden = true;
  rememberSession();
  pane.classList.remove('open');
  pane.style.width = '0px';
  // 閉じきってから消す (AWT 版と同じ 260ms)
  setTimeout(() => {
    if (!state.editing) {
      pane.hidden = true;
      el('split2').hidden = true;
      layout();
    }
  }, 300);
}

/** 打鍵ごとの更新をやめる目安 (Android 版と同じ考え方) */

/** いまの内容が重いかどうかを見て、更新の仕方を切り替える */
function updatePreviewMode() {
  state.manualPreview = state.testManual || prefs.getItem('livePreview') === 'off';
  const button = el('refreshPreview');
  // 打つたびに幅を測ると画面全体の再計算が起きるので、形をそろえるのは別のところで行う
  if (button) {
    // リアルタイムで反映されている間は出さない
    button.hidden = !state.editing || !state.manualPreview;
    // 反映していない変更があるときだけ、色を付ける
    button.classList.toggle('ghost', !state.previewStale);
    button.disabled = !state.previewStale;
  }
  return state.manualPreview;
}

let livePreviewTimer = null;
let previewBusy = false;
function livePreview() {
  if (updatePreviewMode()) return;
  clearTimeout(livePreviewTimer);
  livePreviewTimer = setTimeout(async () => {
    if (!state.selected || !state.draft) return;
    // 前の更新がまだ終わっていないなら、少し待って出直す
    if (previewBusy) { livePreview(); return; }
    previewBusy = true;
    try {
      const tp = window.__aniProfile ? performance.now() : 0;
      const rendered = await call('renderDraft', {
        id: state.selected.id,
        modelId: state.selected.modelId,
        fields: state.draft.fields,
        tags: state.draft.tags,
      });
      const tq = tp ? performance.now() : 0;
      showCard(rendered, { fade: false });
      if (tp) console.log(`[内訳] preview render ${Math.round(tq - tp)} show ${Math.round(performance.now() - tq)}`);
      state.previewStale = false;
      updatePreviewMode();
    } catch (e) { console.error(e); } finally { previewBusy = false; }
  }, 700);   // 打鍵が止まってから更新する (打ちながら更新すると引っかかる)
}


// 変更を保存したあと、少し待ってから iCloud Drive へ書き出す (続けて保存しても 1 回にまとめる)
let mirrorTimer = null;
function scheduleMirror() {
  clearTimeout(mirrorTimer);
  mirrorTimer = setTimeout(() => { window.anki.mirrorNow(false).catch((e) => window.anki.mark('iCloud 書き出し失敗: ' + e.message)); }, 3000);
}

async function save({ sync: alsoSync = null } = {}) {
  // 一覧用に切り詰めた内容を、そのまま保存させない (取り違えると本文が消える)
  if (state.draft && state.draft.brief) {
    toast('内容を読み直しています。もう一度お試しください');
    await reloadEditor();
    return;
  }
  alsoSync = false;   // 同期は Anki 本体が行う
  if (!state.selected || !state.draft) { toast('編集中のノートがありません'); return; }
  if (state.editor && state.editor.isReviewing && state.editor.isReviewing()) finishReview(true);
  // 編集中の内容が、いま選んでいるノートのものか確かめる。
  // 取り違えると、別のカードの中身で上書きしてしまう
  if (state.draft.noteId && state.draft.noteId !== state.selected.id) {
    toast('別のカードの内容のため、保存を取りやめました');
    await reloadEditor();
    return;
  }
  try {
    if (state.editor) state.editor.flush();
    await call('saveNote', {
      id: state.selected.id,
      fields: state.draft.fields,
      tags: state.draft.tags,
    });
    state.dirty = false;
    updateSaveButton();
    scheduleMirror();
    const rendered = await call('renderNote', { id: state.selected.id });
    showCard(rendered);
    await search();
    state.unsynced = true;
    prefs.setItem('unsynced', '1');
    await recordEdit(state.selected, state.draft.fields, state.draft.tags);
    toast('保存しました');
  } catch (e) {
    toast('保存できません: ' + e.message);
    console.log('[save] 失敗: ' + e.message);
  }
}

let mediaDir = '';

function showCard(rendered, { fade = true } = {}) {
  // 同じカードの中身だけが変わったときは、読み込み直さず本文を差し替える。
  // srcdoc を入れ直すと、その一瞬だけ何も無い画面が見えてしまう
  // 同じカードの中身が変わっただけなら、本文を差し替える。
  // 別のカードに移るときは読み込み直す (中身だけ入れ替えると、
  // そのカード全体を組版し直すことになり、かえって時間がかかる)
  const same = state.previewOf === (state.selected && state.selected.id) &&
    state.previewCss === (rendered && rendered.css);
  if (same && rendered && rendered.body && state.previewReady) {
    // 同じページのまま、本文だけ差し替える (別プロセスへ渡すだけ)
    // 本文の差し替えは直接送り込む (受け口の合図待ちだと届かないことがある)
    const html = JSON.stringify(rendered.body);
    previewView()
      .executeJavaScript(`window.__ankiSetBody && window.__ankiSetBody(${html})`)
      .catch(() => { try { previewView().send('body', rendered.body); } catch { /* 次回に回す */ } });
    return;
  }
  if (!rendered || !rendered.body) return;
  const view = previewView();
  view.classList.add('ready');
  el('empty').style.display = 'none';
  // カードを選び直したときだけ、ふわりと切り替える。
  // 打鍵に追従するリアルタイムプレビューでは、ちらつくので何もしない
  if (fade) {
    view.classList.add('swapping');
    view.addEventListener('load', () => view.classList.remove('swapping'), { once: true });
    setTimeout(() => view.classList.remove('swapping'), 400);   // 念のための保険
  } else {
    view.classList.remove('swapping');
  }
  const zoom = Number(prefs.getItem('textZoom') || 100);
  const page = cardPage(rendered, zoom !== 100 ? `body { zoom: ${zoom / 100}; }` : '');
  state.previewReady = false;
  el('previewLoading').hidden = false;
  el('previewLoading').classList.remove('away');
  previewView().classList.add('swapping');
  // ページ本体は親プロセスに預け、app:// で読ませる。
  // 画面と同じ出どころなので、あとから中身を直接差し替えられる
  state.previewSeq = (state.previewSeq || 0) + 1;
  const seq = state.previewSeq;
  window.anki.previewPage(page).then(() => {
    if (seq !== state.previewSeq) return;
    loadPreview(`app://ui/card?v=${seq}`);
  }).catch(() => {});
  const ready = () => {
    if (state.previewReady) return;
    state.previewReady = true;
    // 読み込みが済んだら、ふわりと切り替える
    view.classList.remove('swapping');
    const spinner = el('previewLoading');
    if (spinner) {
      spinner.classList.add('away');
      setTimeout(() => { spinner.hidden = true; }, 220);
    }
    // カードの地色を聞く (返事は ipc-message で受け取る)
    view.send('ask-background');
  };
  view.addEventListener('dom-ready', ready, { once: true });
  view.addEventListener('did-finish-load', ready, { once: true });
  setTimeout(ready, 1200);
  state.previewOf = state.selected && state.selected.id;
  state.previewCss = rendered.css;
}

/** プレビューの入れ物を用意する (最初に必要になったときだけ) */
function previewView() {
  let view = document.getElementById('preview');
  if (view) return view;
  view = document.createElement('webview');
  view.id = 'preview';
  view.setAttribute('src', 'about:blank');
  view.setAttribute('partition', 'persist:preview');
  view.setAttribute('webpreferences', 'contextIsolation=no');
  if (state.previewPreload) view.setAttribute('preload', state.previewPreload);
  view.addEventListener('ipc-message', (e) => {
    if (e.channel === 'body-set') return;
    if (e.channel === 'open-image') { openLightbox(e.args && e.args[0], ''); return; }
    if (e.channel !== 'background') return;
    const color = e.args && e.args[0];
    if (color && color !== 'rgba(0, 0, 0, 0)') {
      el('previewPane').style.background = color;
      document.documentElement.style.setProperty('--card-bg', color);
    }
  });
  // 貼り付いた直後は読み込みを受け付けないので、順番を守る
  view.addEventListener('did-attach', () => {
    state.previewAttached = true;
    if (state.previewPending) {
      const url = state.previewPending;
      state.previewPending = null;
      view.src = url;
    }
  });
  el('previewPane').prepend(view);
  return view;
}

/** プレビューに読み込ませる (まだ貼り付いていなければ、あとで) */
function loadPreview(url) {
  const view = previewView();
  if (!state.previewAttached) { state.previewPending = url; return; }
  // 前の読み込みが途中なら、そちらを止めてから始める
  try { if (view.isLoading && view.isLoading()) view.stop(); } catch { /* まだ使えない */ }
  view.src = url;
}

/** カード内検索を開く */
function openCardFind() {
  if (!state.previewReady) return;
  // 受け口 (preload) からはページの関数が見えないので、直接呼ぶ
  previewView().executeJavaScript('window.__ankiOpenFind && window.__ankiOpenFind()').catch(() => {});
}

/* ---------------- 知らせ ---------------- */

let toastTimer = null;
function toast(text) {
  const box = el('toast');
  box.textContent = text;
  box.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.hidden = true; }, 2600);
}

/* ---------------- 同期していない編集の控え ---------------- */

/**
 * 保存するたびに「この端末で書いた内容」を控えておく。
 * 同期のあと、同じカードが AnkiWeb 側でも変わっていた場合
 * (Anki の同期は後勝ちなので、こちらの内容が消える) の突き合わせに使う。
 */
async function recordEdit(note, fields, tags) {
  if (state.draft && state.draft.noteId && state.draft.noteId !== note.id) return;
  const all = await window.anki.editsAll();
  all[note.id] = {
    fields: fields.slice(),
    tags,
    title: noteTitle(note, 2).join(' ').slice(0, 60),
    savedAt: Math.round(Date.now() / 1000),
    fieldNames: state.fieldNames.slice(),
  };
  await window.anki.editsSave(all);
}

async function clearEdits(ids) {
  const all = await window.anki.editsAll();
  for (const id of ids) delete all[id];
  await window.anki.editsSave(all);
}

/**
 * 同期のあと、控えと実際の内容を突き合わせる。
 * 違っていれば「AnkiWeb 側の変更が採用された」＝両方で編集していたということ。
 */
async function findConflicts() {
  const all = await window.anki.editsAll();
  const out = [];
  for (const [id, mine] of Object.entries(all)) {
    let now;
    try { now = await call('noteContent', { id: Number(id) }); } catch { now = null; }
    if (!now) continue;                       // 消えていたら対象外
    if (now.fields.join('\u0000') === mine.fields.join('\u0000')) continue;  // こちらが通った
    out.push({ id: Number(id), mine, theirs: now });
  }
  return out;
}

/** 食い違っている場所だけを、目で見て分かる形にする */
function diffParts(mine, theirs) {
  const a = stripHtml(mine || '').replace(/\s+/g, ' ').trim();
  const b = stripHtml(theirs || '').replace(/\s+/g, ' ').trim();
  // 前と後ろの共通部分を除いて、違うところだけを取り出す
  let head = 0;
  while (head < a.length && head < b.length && a[head] === b[head]) head++;
  let tail = 0;
  while (tail < a.length - head && tail < b.length - head &&
         a[a.length - 1 - tail] === b[b.length - 1 - tail]) tail++;
  const cut = (text) => {
    const before = text.slice(Math.max(0, head - 24), head);
    const middle = text.slice(head, text.length - tail);
    const after = text.slice(text.length - tail, text.length - tail + 24);
    return { before, middle, after };
  };
  return { mine: cut(a), theirs: cut(b) };
}

/** その欄に入っている画像を並べる */
function imagesIn(html) {
  const out = [];
  const re = /<img[^>]*src="([^"]+)"/g;
  let m;
  while ((m = re.exec(html || ''))) out.push(m[1]);
  return out;
}

/** 片側の内容を組み立てる */
function conflictSide(label, when, fields, other, names) {
  const box = document.createElement('div');
  box.className = 'conflict-body';
  for (let i = 0; i < Math.max(fields.length, other.length); i++) {
    if ((fields[i] || '') === (other[i] || '')) continue;
    const name = (names || [])[i] || `欄 ${i + 1}`;
    const row = document.createElement('div');
    row.className = 'diff-row';

    const title = document.createElement('div');
    title.className = 'diff-field';
    title.textContent = name;
    row.appendChild(title);

    const text = document.createElement('div');
    text.className = 'diff-text';
    const part = diffParts(fields[i], other[i]).mine;
    const partOther = diffParts(other[i], fields[i]).mine;   // 見出し用に未使用
    text.append(
      document.createTextNode(part.before ? '…' + part.before : ''),
    );
    const mark = document.createElement('mark');
    mark.textContent = part.middle || '（この部分なし）';
    text.appendChild(mark);
    text.append(document.createTextNode(part.after ? part.after + '…' : ''));
    row.appendChild(text);

    // 画像の食い違いは絵で見せる
    const mineImages = imagesIn(fields[i]);
    const theirImages = imagesIn(other[i]);
    const onlyHere = mineImages.filter((src) => !theirImages.includes(src));
    if (onlyHere.length) {
      const shelf = document.createElement('div');
      shelf.className = 'diff-images';
      for (const src of onlyHere.slice(0, 4)) {
        const img = document.createElement('img');
        img.src = 'media://media/' + encodeURIComponent(src);
        img.onerror = () => img.remove();
        shelf.appendChild(img);
      }
      row.appendChild(shelf);
    }
    box.appendChild(row);
  }
  if (!box.children.length) box.textContent = '（本文の違いはありません）';
  return box;
}

/** 競合したカードを並べて、どちらを残すか選んでもらう */
function askConflicts(list) {
  const box = el('conflictList');
  box.innerHTML = '';
  const choice = new Map();
  const when = (secs) => (secs
    ? new Date(secs * 1000).toLocaleString('ja-JP', { dateStyle: 'short', timeStyle: 'short' })
    : '不明');

  for (const item of list) {
    choice.set(item.id, 'theirs');
    const names = item.mine.fieldNames || [];

    const card = document.createElement('div');
    card.className = 'conflict-note';
    const title = document.createElement('h3');
    title.textContent = item.mine.title || `ノート ${item.id}`;
    card.appendChild(title);

    for (const side of ['mine', 'theirs']) {
      const wrap = document.createElement('label');
      wrap.className = 'conflict-side';
      const radio = document.createElement('input');
      radio.type = 'radio';
      radio.name = 'conflict-' + item.id;
      radio.checked = side === 'theirs';
      radio.onchange = () => choice.set(item.id, side);

      const meta = document.createElement('span');
      meta.className = 'meta';
      meta.textContent = side === 'mine'
        ? `この Mac（Carrel）\n${when(item.mine.savedAt)}`
        : `ほかの Anki アプリ\n${when(item.theirs.mtimeSecs)}`;

      const body = side === 'mine'
        ? conflictSide('mine', null, item.mine.fields, item.theirs.fields, names)
        : conflictSide('theirs', null, item.theirs.fields, item.mine.fields, names);

      wrap.append(radio, meta, body);
      card.appendChild(wrap);
    }
    box.appendChild(card);
  }

  const dialog = el('conflictDialog');
  el('conflictCancel').onclick = () => dialog.close();
  el('conflictApply').onclick = async () => {
    dialog.close();
    const mineIds = [];
    for (const item of list) {
      if (choice.get(item.id) !== 'mine') continue;
      await call('saveNote', { id: item.id, fields: item.mine.fields, tags: item.mine.tags });
      mineIds.push(item.id);
    }
    await clearEdits(list.map((i) => i.id));

    // いま開いているカードが対象なら、選ばれた内容で作り直す
    const open = state.selected && list.find((i) => i.id === state.selected.id);
    if (open) {
      const rendered = await call('renderNote', { id: state.selected.id });
      showCard(rendered);
      if (state.editing) await reloadEditor();
    }

    if (mineIds.length) {
      toast(`${mineIds.length} 枚をこの端末の内容にしました。送ります…`);
      await sync({ resolving: true });
    } else {
      toast('AnkiWeb の内容を残しました');
      await search();
    }
  };
  dialog.showModal();
}

/* ---------------- 同期 ---------------- */

function auth() {
  const raw = prefs.getItem('auth');
  return raw ? JSON.parse(raw) : null;
}

/** 「同期」= 編集を保存してコレクションを手放し、Anki 本体を開く。同期そのものは Anki が行う */
async function sync() {
  if (state.editing && state.dirty) await save();
  const button = el('sync');
  button.classList.add('busy');
  try {
    await window.anki.ankiLaunch();
    state.unsynced = false;
    prefs.setItem('unsynced', '0');
    toast('Anki を開きました。Anki の同期ボタンで AnkiWeb と同期してください');
  } catch (e) {
    toast('Anki を開けません: ' + e.message);
  } finally {
    button.classList.remove('busy');
  }
}

/** (旧) Carrel 自身の同期。AnkiWeb の規約で第三者クライアントは認められていないため使わない */
async function syncLegacy({ resolving = false } = {}) {
  const credentials = auth();
  if (!credentials) { toast('設定から AnkiWeb にログインしてください'); return; }
  const button = el('sync');
  button.classList.add('busy');
  try {
    const result = await call('syncCollection', { auth: credentials });
    if (result.auth) prefs.setItem('auth', JSON.stringify(result.auth));
    if (result.required && result.required !== 'NONE') {
      askFullSync(result.required, result.auth || credentials);
    } else {
      await call('syncMedia', { auth: result.auth || credentials });
      state.unsynced = false;
      prefs.setItem('unsynced', '0');
      prefs.setItem('lastSyncSecs', String(Math.round(Date.now() / 1000)));
      // 同じカードを両側で編集していた場合は、ここで選んでもらう
      const pending = Object.keys(await window.anki.editsAll()).length;
      const conflicts = resolving ? [] : await findConflicts();
      console.log(`[同期] 控え ${pending} 件 / 食い違い ${conflicts.length} 件`);
      if (conflicts.length) {
        await refreshAll();
        askConflicts(conflicts);
        return;
      }
      await clearEdits(Object.keys(await window.anki.editsAll()));
      toast('同期しました');
    }
    await refreshAll();
  } catch (e) {
    toast('同期に失敗しました: ' + e.message);
  } finally {
    button.classList.remove('busy');
  }
}

/** どちらの内容を残すか決めてもらってから、全体の同期を始める */
function askFullSync(kind, credentials) {
  const why = {
    DOWNLOAD: 'AnkiWeb 側の内容が大きく変わっています。',
    UPLOAD: 'この端末の内容が大きく変わっています。',
  }[kind] || 'この端末と AnkiWeb の内容が食い違っています。';
  el('fullSyncWhy').textContent = why;
  const stamp = (secs) => (secs
    ? new Date(secs * 1000).toLocaleString('ja-JP', { dateStyle: 'short', timeStyle: 'short' })
    : '—');
  // この端末側は、いま開いているノートの更新時刻を目安に出す
  el('localTime').textContent = stamp(state.selected && state.selected.mtimeSecs);
  el('remoteTime').textContent = stamp(Number(prefs.getItem('lastSyncSecs') || 0)) + ' 以降';
  listLocalEdits();
  const dialog = el('fullSyncDialog');
  const run = async (upload) => {
    dialog.close();
    prefs.setItem('lastSyncSecs', String(Math.round(Date.now() / 1000)));
    await call('fullSync', { auth: credentials, upload });
    watchJob();
  };
  el('fullSyncUpload').onclick = () => run(true);
  el('fullSyncDownload').onclick = () => run(false);
  el('fullSyncCancel').onclick = () => dialog.close();
  dialog.showModal();
}

/** この端末で最近編集したカードを並べる (どちらを残すか決める材料) */
async function listLocalEdits() {
  const box = el('localEdits');
  box.innerHTML = '読み込み中…';
  try {
    const notes = await call('searchNotes', { query: 'edited:7', limit: 40, brief: 1 });
    const since = Number(prefs.getItem('lastSyncSecs') || 0);
    const changed = notes.filter((n) => !since || (n.mtimeSecs || 0) >= since);
    if (!changed.length) { box.textContent = 'この端末での変更は見つかりませんでした'; return; }
    box.innerHTML = '';
    const head = document.createElement('div');
    head.className = 'hint';
    head.textContent = `この端末で変更したカード ${changed.length} 枚`;
    box.appendChild(head);
    for (const note of changed.slice(0, 20)) {
      const line = document.createElement('div');
      line.className = 'edit-line';
      const when = note.mtimeSecs
        ? new Date(note.mtimeSecs * 1000).toLocaleString('ja-JP', { dateStyle: 'short', timeStyle: 'short' })
        : '';
      const whenSpan = document.createElement('span');
      whenSpan.className = 'when';
      whenSpan.textContent = when;
      const whatSpan = document.createElement('span');
      whatSpan.className = 'what';
      whatSpan.textContent = noteTitle(note, 2).join(' ').slice(0, 60);
      line.append(whenSpan, whatSpan);
      box.appendChild(line);
    }
  } catch {
    box.textContent = '';
  }
}

/* ---------------- ノートの追加・削除 ---------------- */

async function openAddDialog() {
  const [decks, models, defaults] = await Promise.all([
    call('decks', {}), call('notetypes', {}), call('addingDefaults', {}),
  ]);
  const deckSelect = el('addDeck');
  const modelSelect = el('addModel');
  deckSelect.innerHTML = decks.map((d) => `<option>${d}</option>`).join('');
  modelSelect.innerHTML = models
    .map((m) => `<option value="${m.id}">${m.name}</option>`).join('');
  modelSelect.value = String(defaults.notetypeId);
  el('addTags').value = Array.from(state.selectedTags).join(' ');
  el('addDialog').showModal();
}

async function addNote() {
  const deckId = await call('deckId', { name: el('addDeck').value });
  const notetypeId = Number(el('addModel').value);
  const names = await call('fieldNames', { notetypeId });
  scheduleMirror();
  const created = await call('addNote', {
    notetypeId,
    deckId,
    fields: names.map(() => ''),
    tags: el('addTags').value,
  });
  el('addDialog').close();
  await search();
  const note = state.notes.find((n) => n.id === created.id);
  if (note) { await select(note); await openEditor(); }
  toast('追加しました');
}

async function deleteNote() {
  const note = state.selected;
  if (!note) return;
  // 消す前に控えを取る (設定から戻せるように)
  try {
    const content = await call('noteContent', { id: note.id });
    const names = await call('fieldNames', { notetypeId: note.modelId });
    const trash = await window.anki.trashAll();
    trash.unshift({
      id: note.id,
      modelId: note.modelId,
      fields: content ? content.fields : note.fields,
      tags: content ? content.tags : note.tags,
      fieldNames: names,
      title: noteTitle(note, 2).join(' ').slice(0, 60),
      deletedAt: Math.round(Date.now() / 1000),
    });
    await window.anki.trashSave(trash.slice(0, 200));   // 直近 200 枚まで
  } catch (e) {
    console.log('[trash] 控えを取れません: ' + e.message);
  }
  await call('deleteNotes', { ids: [note.id] });
  scheduleMirror();
  el('deleteDialog').close();
  state.selected = null;
  closeEditor();
  const showing = document.getElementById('preview');
  if (showing) showing.classList.remove('ready');
  el('empty').style.display = 'grid';
  el('cardTitle').textContent = '';
  updateTitlebar();
  await search();
  toast('削除しました');
}

/**
 * サイドバーの開け閉め。
 * 閉じるとウインドウの左端がプレビュー (編集中は編集欄) の左端まで寄る。
 * プレビューの幅はそのまま保つ。
 */
const EASE_MS = 260;

/**
 * サイドバーの開け閉め。
 * パネルの幅を 260ms で伸縮させるだけにする。
 * 空いた場所はプレビューが受け取り、最大幅を越える分はウインドウ側が縮む
 * (ウインドウを自分で動かすと、途中の状態がちらついて崩れやすいため)。
 */
function toggleList() {
  const list = el('list');
  const preview = document.getElementById('preview');
  const closing = !list.hidden;
  prefs.setItem('listVisible', closing ? '0' : '1');
  state.autoCollapsed = false;
  el('toggleList').dataset.name = closing ? '一覧を開く' : '一覧を閉じる';
  refreshTitlebarTips();

  if (closing) return closeList();

  // 開く前に、押し出す余地があるか調べる。
  // 足りなければエディタの上にかぶせて出す
  const w = widths();
  const forEditor = window.innerWidth - GAP * 2 - w.preview - w.list;
  const overlay = state.editing && forEditor < EDITOR_KEEP;

  list.hidden = false;
  list.style.width = w.list + 'px';
  el('split').hidden = overlay;
  list.classList.add(overlay ? 'overlay' : 'sliding', 'out');
  list.dataset.folded = overlay ? '1' : '0';
  state.listOverlay = overlay;
  if (!overlay) preview.classList.add('settling');
  if (!overlay && state.editing) el('editorPane').classList.add('settling');
  layout();

  requestAnimationFrame(() => list.classList.remove('out'));
  setTimeout(() => {
    if (!overlay) {
      list.classList.remove('sliding');
      delete list.dataset.folded;
    }
    if (preview) preview.classList.remove('settling');
    el('editorPane').classList.remove('settling');
    layout();
  }, 320);

  // かぶせているときは、外側を触ると引っ込む
  if (overlay) {
    setTimeout(() => {
      const away = (e) => {
        if (list.contains(e.target) || e.target.closest('#toggleList')) return;
        document.removeEventListener('pointerdown', away, true);
        if (state.listOverlay) toggleList();
      };
      document.addEventListener('pointerdown', away, true);
    }, 0);
  }
}

/** サイドバーをしまう */
function closeList() {
  const list = el('list');
  const preview = document.getElementById('preview');
  const overlay = state.listOverlay;
  state.listOverlay = false;

  list.classList.add(overlay ? 'overlay' : 'sliding');
  if (!overlay) {
    el('split').hidden = true;
    list.dataset.folded = '1';
    preview.classList.add('settling');
    if (state.editing) el('editorPane').classList.add('settling');
    layout();
  }
  requestAnimationFrame(() => list.classList.add('out'));

  setTimeout(() => {
    list.classList.remove('sliding', 'overlay', 'out');
    list.hidden = true;
    delete list.dataset.folded;
    if (preview) preview.classList.remove('settling');
    el('editorPane').classList.remove('settling');
    layout();
  }, 320);
}

/** 設定した間隔で、コレクションの控えを取る */
async function autoBackup() {
  const dir = prefs.getItem('backupDir');
  if (!dir || prefs.getItem('backupAuto') !== '1') return;
  const every = prefs.getItem('backupEvery') || '1d';
  const spans = {
    '1h': 3600, '3h': 10800, '6h': 21600, '12h': 43200,
    '1d': 86400, '3d': 259200, '1w': 604800,
  };
  const last = Number(prefs.getItem('lastBackupSecs') || 0);
  const now = Math.round(Date.now() / 1000);
  if (now - last < (spans[every] || 86400)) return;
  try {
    // 書きかけが混ざらないよう、いったん閉じてから写す (必ず開き直す)
    const done = await withCollectionClosed(() =>
      window.anki.backupNow(dir, Number(prefs.getItem('backupKeep') || 1)));
    prefs.setItem('lastBackupSecs', String(now));
    console.log('[backup] ' + done.file);
  } catch (e) {
    console.log('[backup] 失敗: ' + e.message);
    window.anki.mark('自動バックアップ失敗: ' + e.message);
  }
}

/** 起動時は、まず AnkiWeb の内容を取り込む */
async function startupSync() {
  const credentials = auth();
  if (!credentials) return;
  el('sync').classList.add('busy');
  try {
    const result = await call('syncCollection', { auth: credentials });
    if (result.auth) prefs.setItem('auth', JSON.stringify(result.auth));
    if (result.required && result.required !== 'NONE') {
      askFullSync(result.required, result.auth || credentials);
    } else {
      await call('syncMedia', { auth: result.auth || credentials });
      prefs.setItem('lastSyncSecs', String(Math.round(Date.now() / 1000)));
      state.unsynced = false;
      prefs.setItem('unsynced', '0');
      // 起動時の同期でも、両側で編集していたカードは選んでもらう
      const conflicts = await findConflicts();
      console.log(`[起動時同期] 食い違い ${conflicts.length} 件`);
      if (conflicts.length) {
        await refreshAll();
        askConflicts(conflicts);
      } else {
        await clearEdits(Object.keys(await window.anki.editsAll()));
      }
    }
  } catch (e) {
    toast('起動時の同期に失敗しました: ' + e.message);
    window.anki.mark('起動時同期の失敗: ' + e.message);
  } finally {
    el('sync').classList.remove('busy');
  }
}

/** 設定した ★ のタグを、そのまま一覧の絞り込みに反映する */
function applyTagSettings() {
  const pinned = JSON.parse(prefs.getItem('pinnedTags') || '[]');
  const hidden = new Set(JSON.parse(prefs.getItem('hiddenTags') || '[]'));
  state.selectedTags = new Set(pinned.filter((t) => !hidden.has(t)));
  renderTags();
  search();
}

/* ---------------- 前回の状態を覚えておく ---------------- */

let sessionTimer = null;
function rememberSession() {
  if (location.search.includes('selftest')) return;   // 調べもののときは残さない
  clearTimeout(sessionTimer);
  sessionTimer = setTimeout(() => {
    prefs.setItem('lastKeyword', state.keyword);
    prefs.setItem('lastTags', JSON.stringify([...state.selectedTags]));
    prefs.setItem('lastNoteId', state.selected ? String(state.selected.id) : '');
    prefs.setItem('listScroll', String(Math.round(el('notes').scrollTop)));
    prefs.setItem('editorOpen', state.editing ? '1' : '0');
  }, 200);
}

/** 前回の続きから始める */
async function restoreSession() {
  const keyword = prefs.getItem('lastKeyword') || '';
  const tags = JSON.parse(prefs.getItem('lastTags') || 'null');
  if (keyword) {
    state.keyword = keyword;
    el('keyword').value = keyword;
    el('clearKeyword').hidden = false;
  }
  if (Array.isArray(tags)) state.selectedTags = new Set(tags);
  renderTags();
  await search();

  const id = Number(prefs.getItem('lastNoteId') || 0);
  const note = id && state.notes.find((n) => n.id === id);
  if (note) {
    await select(note);
    if (prefs.getItem('editorOpen') === '1') await openEditor();
  }
  // 一覧の見えていた位置に戻す (行が描かれてから)
  requestAnimationFrame(() => {
    el('notes').scrollTop = Number(prefs.getItem('listScroll') || 0);
    console.log(`[restore] カード=${note ? note.id : 'なし'} 位置=${el('notes').scrollTop}` +
      ` 検索語=「${keyword}」 タグ=${[...state.selectedTags].join(',') || 'なし'}`);
  });
}

/** カードを読み込める状態か (Anki のプロフィールがある) */
function loggedIn() { return !!ankiState.profile; }

let countedTags = false;

/** デッキの絞り込み (Android 版と同じで、タグの並びの先頭に置く) */
async function renderDecks() {
  const chip = el('deckChip');
  const select = el('deckSelect');
  const name = el('deckName');
  const current = prefs.getItem('defaultDeck') || 'すべてのデッキ';
  let decks = [];
  try { decks = await call('decks', {}); } catch { decks = []; }

  // 先頭は「すべてのデッキ」。選ばなければデッキをまたいで検索する
  const options = ['すべてのデッキ', ...decks];
  select.innerHTML = options.map(() => '<option></option>').join('');
  [...select.options].forEach((opt, i) => { opt.textContent = options[i]; });
  select.value = options.includes(current) ? current : 'すべてのデッキ';

  const show = () => {
    name.textContent = select.value;
    chip.classList.toggle('on', select.value !== 'すべてのデッキ');
  };
  show();

  select.onchange = () => {
    prefs.setItem('defaultDeck', select.value);
    show();
    search();
    rememberSession();
  };
}

async function refreshAll() {
  try {
    await loadAll();
  } catch (e) {
    // 黙って空のまま置かない。理由を出す
    el('count').textContent = '読み込めませんでした';
    toast('読み込めません: ' + e.message);
    console.log('[起動] 読み込み失敗: ' + e.message);
  }
}

async function loadAll() {
  // 未ログインのあいだはカードを出さない
  if (!loggedIn()) {
    state.notes = [];
    state.tags = [];
    state.tagCounts = {};
    state.selected = null;
    renderTags();
    renderNotes();
    el('cardTitle').textContent = '';
    const shown = document.getElementById('preview');
    if (shown) shown.classList.remove('ready');
    el('previewLoading').hidden = true;
    el('empty').style.display = 'grid';
    updateTitlebar();
    return;
  }
  state.tags = await call('allTags', {});
  renderTags();
  await search();
  renderDecks();          // デッキの一覧は、最初の表示を待たせない
  // 件数の集計はタグの数だけ問い合わせるので、最初の表示を待たせない
  if (!countedTags) {
    countedTags = true;
    countTags().then(() => renderTags());
  }
}

/** タグごとのカード数。0 件のタグは一覧に出さない */
async function countTags() {
  const counts = {};
  await Promise.all(state.tags.map(async (tag) => {
    try {
      const found = await call('searchNotes', { query: `tag:"${tag}"`, limit: 1, brief: 1 });
      counts[tag] = found.length;
    } catch { counts[tag] = 1; }
  }));
  state.tagCounts = counts;
}

/* ---------------- 列の幅 ---------------- */

/**
 * 列の幅を決める。
 * ウインドウの幅が変わったときは 編集欄 → 一覧 の順で伸び縮みし、
 * プレビューは境界を直接ドラッグしたときだけ変える (それ以外は固定)。
 */
const LIST_MIN = 200;
const LIST_MAX = 350;
const EDITOR_MIN = 320;
const GAP = 10;

function widths() {
  const min = Number(prefs.getItem('previewMinWidth') || 300);
  // 上限は設けない (数値で縛ると、余りの行き場が無くなって表示が崩れるため)
  const max = Number.MAX_SAFE_INTEGER;
  return {
    min,
    max,
    list: Math.min(LIST_MAX, Math.max(LIST_MIN, Number(prefs.getItem('listWidth') || 320))),
    editor: Number(prefs.getItem('editorWidth') || 460),
    preview: Math.min(max, Math.max(min, Number(prefs.getItem('previewWidth') || 600))),
  };
}

/**
 * いまの設定どおりに並べ直す。
 * それぞれの列は「覚えている幅」をそのまま使い、
 * 収まらないときは 編集欄 → 一覧 → プレビュー の順に譲る。
 * 余ったときは 編集欄 → プレビュー → 一覧 の順に受け取る。
 */
const EDITOR_KEEP = 350;   // 編集欄がこれより狭くなるなら、一覧を引っ込める

function layout(again = false) {
  const w = widths();
  const list0 = el('list');
  let listOpen = !list0.hidden && list0.dataset.folded !== '1' && !state.listOverlay;

  // 幅が足りなくなったら、一覧をその場で (動きなしで) しまう。
  // 余裕が戻ったら、自分でしまったぶんだけ戻す
  if (!again && state.editing && !list0.classList.contains('sliding')) {
    const gapsNow = (listOpen ? GAP : 0) + GAP;
    const forEditor = window.innerWidth - gapsNow - w.preview - (listOpen ? w.list : 0);
    if (listOpen && forEditor < EDITOR_KEEP) {
      list0.hidden = true;
      el('split').hidden = true;
      state.autoCollapsed = true;
      return layout(true);
    }
    if (!listOpen && state.autoCollapsed &&
        window.innerWidth - GAP * 2 - w.preview - w.list >= EDITOR_KEEP) {
      list0.hidden = false;
      el('split').hidden = false;
      state.autoCollapsed = false;
      return layout(true);
    }
  }
  listOpen = !list0.hidden && list0.dataset.folded !== '1' && !state.listOverlay;
  const editorOpen = state.editing;
  const gaps = (listOpen ? GAP : 0) + (editorOpen ? GAP : 0);
  const room = window.innerWidth - gaps;

  let list = listOpen ? w.list : 0;
  let editor = editorOpen ? Math.max(EDITOR_MIN, w.editor) : 0;
  let preview = w.preview;

  let over = list + editor + preview - room;
  if (over > 0 && editorOpen) {                 // まず編集欄が譲る
    const cut = Math.min(over, editor - EDITOR_MIN);
    editor -= cut; over -= cut;
  }
  if (over > 0 && listOpen) {                   // 次に一覧
    const cut = Math.min(over, list - LIST_MIN);
    list -= cut; over -= cut;
  }
  if (over > 0) {                               // それでも足りなければプレビュー
    preview = Math.max(w.min, preview - over);
  }

  let slack = room - list - editor - preview;
  if (slack > 0) {
    if (editorOpen) { editor += slack; slack = 0; }          // 編集欄が受け取る
    else {
      const add = Math.min(slack, w.max - preview);          // 次にプレビュー (最大幅まで)
      preview += add; slack -= add;
    }
    if (slack > 0 && listOpen) {                             // 残りは一覧 (最大幅まで)
      list = Math.min(LIST_MAX, list + slack);
    }
  }

  // 余りを引き取る列を決める。
  // 編集中は編集欄が伸び縮みし、プレビューは動かさない。
  // 編集していないときだけプレビューが余りを引き取る (右に隙間を作らないため)
  el('editorPane').style.flex = editorOpen ? '1 1 auto' : '0 0 auto';
  el('previewPane').style.flex = editorOpen ? '0 0 auto' : '1 1 auto';

  if (listOpen) el('list').style.width = Math.round(list) + 'px';
  else if (el('list').classList.contains('sliding')) { /* すべっている間は幅を触らない */ }
  if (editorOpen) el('editorPane').style.width = Math.round(editor) + 'px';
  el('previewPane').style.width = Math.round(preview) + 'px';
  el('previewPane').style.minWidth = w.min + 'px';
  el('previewPane').style.maxWidth = 'none';

  // 一覧の幅は、境目を掴んで変えたときだけ書き換える (表示の都合で縮んだ値は覚えない)。
  // 編集欄の幅はウインドウに追従するので、そのつど覚えておく
  if (!el('list').classList.contains('sliding')) {
    rememberWidths(null, editorOpen ? Math.round(editor) : null);
  }
  limitWindow(list, editor);
}

let rememberTimer = null;
function rememberWidths(list, editor) {
  clearTimeout(rememberTimer);
  rememberTimer = setTimeout(() => {
    if (list !== null) prefs.setItem('listWidth', String(list));
    if (editor !== null) prefs.setItem('editorWidth', String(editor));
  }, 250);
}

/** ウインドウの幅に上限は設けない */
function limitWindow() {
  window.anki.setWindowMaxWidth(0);
}

/** 設定から呼ばれる。既定幅を入れ直して並べ直す */
function applyWidths() { layout(); }

function setupSplit() {
  // 一覧とその右の境界 → 一覧の幅
  dragHandle(el('split'), (delta) => {
    const w = widths();
    const next = Math.min(LIST_MAX, Math.max(LIST_MIN, w.list + delta));
    prefs.setItem('listWidth', String(next));
    layout();
  });
  // 編集欄とプレビューの境界 → プレビューの幅 (左へ動かすほど広い)
  dragHandle(el('split2'), (delta) => {
    const w = widths();
    const next = Math.min(w.max, Math.max(w.min, w.preview - delta));
    prefs.setItem('previewWidth', String(Math.round(next)));
    layout();
  });
}

function dragHandle(handle, onDelta) {
  handle.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    handle.setPointerCapture(e.pointerId);   // 離すまでこの部品が受け取る
    let last = e.screenX;

    const move = (ev) => {
      // ボタンを押していない動きは無視する (掴んでいる間だけ効かせる)
      if (!(ev.buttons & 1)) { finish(ev); return; }
      onDelta(ev.screenX - last);
      last = ev.screenX;
    };
    const finish = (ev) => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', finish);
      handle.removeEventListener('pointercancel', finish);
      if (handle.hasPointerCapture(ev.pointerId)) handle.releasePointerCapture(ev.pointerId);
      layout();
    };

    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', finish);
    handle.addEventListener('pointercancel', finish);
  });
}

/* ---------------- 起動 ---------------- */

async function boot() {
  const t0 = performance.now();
  const lap = (name) => { console.log(`[速さ] ${name}`); window.anki.mark(name); };
  await prefs.load();
  lap('設定を読む');
  // 設定はここで初めて読めるので、既定のモードもこの時点で反映する
  state.mode = prefs.getItem('editorMode') === 'html' ? 'html' : 'rich';
  state.unsynced = prefs.getItem('unsynced') === '1';
  mediaDir = await window.anki.mediaDir();
  // プレビュー (別プロセス) に仕込む受け口。窓を作る前に場所を知っておく
  try { state.previewPreload = await window.anki.previewPreload(); } catch { state.previewPreload = ''; }
  lap('ブリッジの用意');
  setupSplit();
  if (prefs.getItem('listVisible') === '0') {
    el('list').hidden = true;
    el('split').hidden = true;
  }
  layout();
  let layoutFrame = null;
  window.addEventListener('resize', () => {
    if (layoutFrame) return;
    layoutFrame = requestAnimationFrame(() => { layoutFrame = null; layout(); });
  });
  el('keyword').addEventListener('keydown', (e) => e.stopPropagation());
  const clearKey = el('clearKeyword');
  const showClear = () => { clearKey.hidden = !el('keyword').value; };
  clearKey.onclick = () => {
    el('keyword').value = '';
    state.keyword = '';
    showClear();
    search();
    rememberSession();
    el('keyword').focus();
  };
  let searchTimer = null;
  el('keyword').addEventListener('input', (e) => {
    showClear();
    state.keyword = e.target.value;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => search(), 160);
    rememberSession();
  });
  el('notes').addEventListener('scroll', () => rememberSession());


  el('clearFilters').onclick = () => {
    state.keyword = '';
    el('keyword').value = '';
    state.selectedTags.clear();
    prefs.setItem('defaultDeck', 'すべてのデッキ');
    renderDecks();
    renderTags();
    search();
    rememberSession();
  };
  // 同期していない変更があるまま閉じようとしたときの確認
  window.anki.onConfirmClose(async () => {
    const dialog = el('quitDialog');
    const unsaved = state.editing && state.dirty;

    // 1. 何も編集していない → そのまま閉じる
    if (!unsaved && !state.unsynced) { window.anki.closeNow(); return; }
    // 同期は Anki 本体が行うので、ここでは保存の有無だけ聞く
    const canSync = false;

    el('quitDetail').textContent = unsaved
      ? '保存していない編集があります。'
      : '保存した変更が、まだ AnkiWeb と同期されていません。';

    const keep = el('quitSync');
    const drop = el('quitAnyway');
    keep.textContent = unsaved
      ? (canSync ? '保存して同期して終了' : '保存して終了')
      : '同期して終了';
    drop.textContent = unsaved
      ? '編集を破棄して終了'
      : (canSync ? '変更を破棄して終了（AnkiWeb の内容に戻す）' : '同期せずに終了');

    el('quitCancel').onclick = () => dialog.close();

    keep.onclick = async () => {
      dialog.close();
      if (unsaved) await save({ sync: canSync });
      else if (canSync) await sync();
      window.anki.closeNow();
    };

    drop.onclick = async () => {
      dialog.close();
      if (unsaved) {
        // 2. 保存していない編集は、書かずに閉じるだけで消える
        state.dirty = false;
        window.anki.closeNow();
        return;
      }
      if (!canSync) { window.anki.closeNow(); return; }
      // 3. すでに保存済みの変更を捨てるには、AnkiWeb の内容で入れ直す
      toast('AnkiWeb の内容に戻しています…');
      try {
        await call('fullSync', { auth: auth(), upload: false });
        await waitForJob();
        state.unsynced = false;
        prefs.setItem('unsynced', '0');
      } catch (e) {
        toast('戻せませんでした: ' + e.message);
      }
      window.anki.closeNow();
    };

    dialog.showModal();
  });

  // 閉じる直前にも取りこぼさないよう書き出す
  window.addEventListener('beforeunload', () => {
    prefs.setItem('lastNoteId', state.selected ? String(state.selected.id) : '');
    prefs.setItem('listScroll', String(Math.round(el('notes').scrollTop)));
    prefs.setItem('editorOpen', state.editing ? '1' : '0');
  });
  el('toggleList').onclick = () => toggleList();

  // 名前ふだに、割り当てているキーも並べて出す
  const tipFor = {
    toggleList: 'toggleList', undo: 'undo', redo: 'redo',
    edit: 'edit', find: 'find', sync: 'sync', settings: 'settings',
  };
  function refreshTips() {
    const keys = currentKeys();
    const pretty = (combo) => combo
      .replace('Mod', '⌘').replace('Shift', '⇧').replace('Alt', '⌥').replace(/\+/g, ' ');
    for (const [id, action] of Object.entries(tipFor)) {
      const button = el(id);
      if (!button) continue;
      if (!button.dataset.name) button.dataset.name = button.title;
      const combo = keys[action];
      button.title = combo ? `${button.dataset.name}  ${pretty(combo)}` : button.dataset.name;
      button.dataset.tip = button.title;
    }
  }
  refreshTips();
  window.addEventListener('anki-keys-changed', refreshTips);

  el('edit').onclick = () => (state.editing ? closeEditor() : openEditor());
  el('undo').onclick = () => undo();
  el('redo').onclick = () => redo();
  el('save').onclick = () => save();
  el('refreshPreview').onclick = async () => {
    if (!state.selected || !state.draft) return;
    if (state.editor && state.editor.flush) state.editor.flush();
    const rendered = await call('renderDraft', {
      id: state.selected.id,
      modelId: state.selected.modelId,
      fields: state.draft.fields,
      tags: state.draft.tags,
    });
    showCard(rendered, { fade: false });
    state.previewStale = false;
    updatePreviewMode();
  };
  el('closeEditor').onclick = () => closeEditor();
  el('modeRich').onclick = () => setMode('rich');
  el('modeHtml').onclick = () => setMode('html');
  el('insertMedia').onclick = () => insertMedia();
  el('addTag').onclick = () => openTagPicker();
  el('formatBar').addEventListener('click', (e) => {
    const cmd = e.target.closest('button')?.dataset.cmd;
    if (cmd && state.editor && state.editor.format) state.editor.format(cmd);
  });

  // 並び順
  const sortSelect = el('sortMode');
  sortSelect.value = prefs.getItem('sortMode') || 'NAME_ASC';
  sortSelect.onchange = () => { prefs.setItem('sortMode', sortSelect.value); search(); };

  // ノート内検索: Enter で確定し、← → で行き来する
  const findInput = el('editorFindInput');
  let findTotal = 0;
  const showCount = (at) => {
    el('editorFindCount').textContent = findTotal ? `${at}/${findTotal}` : '';
  };
  findInput.addEventListener('focus', () => findInput.parentNode.classList.add('active'));
  findInput.addEventListener('blur', () => findInput.parentNode.classList.remove('active'));
  findInput.addEventListener('input', () => {
    if (!state.editor) return;
    findTotal = state.editor.search(findInput.value);
    showCount(state.editor.index());
  });
  findInput.addEventListener('keydown', (e) => {
    if (!state.editor) return;
    if (e.key === 'Enter') { e.preventDefault(); showCount(state.editor.move(e.shiftKey ? -1 : 1)); }
    if (e.key === 'Escape') { state.editor.clear(); findInput.value = ''; findTotal = 0; showCount(0); }
  });
  // 置き換え。検索バーの端のアイコンから、ポップアップで開く
  const pop = el('replacePop');
  const fromInput = el('replaceFrom');
  const toInput = el('replaceTo');
  let replaceScope = 'note';
  const scopeOf = () => replaceScope;
  document.querySelectorAll('#replaceScopeSeg button').forEach((b) => {
    b.onclick = () => {
      replaceScope = b.dataset.scope;
      document.querySelectorAll('#replaceScopeSeg button').forEach((x) => x.classList.toggle('on', x === b));
      el('replaceOne').disabled = replaceScope === 'all';     // 1 件ずつはこのノートだけ
      hint(replaceScope === 'all' ? '元に戻せないので、実行前に確認します' : '');
    };
  });
  const hint = (text) => { el('replaceHint').textContent = text; };

  function closeReplace() {
    if (pop.hidden) return;
    pop.classList.remove('open');
    el('replaceOpen').classList.remove('on');
    setTimeout(() => { pop.hidden = true; }, 160);
  }
  function openReplace() {
    if (!pop.hidden) { closeReplace(); return; }
    pop.hidden = false;
    hint('');
    // 検索バーに打ってある語をそのまま引き継ぐ
    if (findInput.value) fromInput.value = findInput.value;
    requestAnimationFrame(() => {
      pop.classList.add('open');
      el('replaceOpen').classList.add('on');
      (fromInput.value ? toInput : fromInput).focus();
    });
  }
  el('replaceOpen').onclick = openReplace;
  el('aiOpen').onclick = () => (el('aiPop').hidden ? openAi() : closeAi());
  el('aiPlus').onclick = () => addAttachmentFiles();
  // 指示欄に貼った画像はそのまま資料に
  el('aiPrompt').addEventListener('paste', async (e) => {
    const items = [...(e.clipboardData ? e.clipboardData.items : [])];
    const shots = items.filter((it) => it.kind === 'file' && /^image\//.test(it.type));
    if (!shots.length) {
      // Finder で ⌘C したファイル (複数可) は file:// の一覧で来る。中身は本体に読んでもらう
      const uris = (e.clipboardData.getData('text/uri-list') || '').split(/\r?\n/).filter((u) => /^file:\/\//.test(u));
      if (uris.length) e.preventDefault();
      const fromFinder = uris.length ? await window.anki.readFileUris(uris).catch(() => []) : [];
      if (uris.length) {
        if (!fromFinder.length) { toast('画像や PDF ではないファイルです'); return; }
        let added = 0;
        for (const f of fromFinder) {
          if (f.size > 25 * 1048576) { toast(`${f.name} は大きすぎます (25MB まで)`); continue; }
          aiAttachments.push({ kind: 'file', name: f.name, mime: f.mime, dataBase64: f.dataBase64 });
          added++;
        }
        renderAttachments();
        if (added > 1) toast(`${added} 件を添付しました`);
        return;
      }
      // 飾りは持ち込まず、文字だけ入れる
      e.preventDefault();
      const plain = e.clipboardData.getData('text/plain');
      document.execCommand('insertText', false, plain);
      return;
    }
    e.preventDefault();
    for (const it of shots) {
      const f = it.getAsFile();
      if (!f) continue;
      const buf = new Uint8Array(await f.arrayBuffer());
      let bin = '';
      for (let k = 0; k < buf.length; k += 8192) bin += String.fromCharCode.apply(null, buf.subarray(k, k + 8192));
      aiAttachments.push({ kind: 'file', name: f.name || `貼り付け画像 ${aiAttachments.length + 1}`, mime: f.type, dataBase64: btoa(bin) });
    }
    renderAttachments();
  });
  // 指示欄の URL は、書いた時点で資料として読み込む (チップで見える)
  let urlTimer = null;
  el('aiPrompt').addEventListener('input', () => {
    clearTimeout(urlTimer);
    urlTimer = setTimeout(attachUrlsInPrompt, 700);
    mentionUpdate();
    syncPromptState();
  });
  el('aiPrompt').addEventListener('keydown', mentionKey, true);
  // 印の直後で Backspace を押したら、印ごと消す
  el('aiPrompt').addEventListener('keydown', (e) => {
    if (e.key !== 'Backspace') return;
    const sel = window.getSelection();
    if (!sel || !sel.rangeCount || !sel.isCollapsed) return;
    const r = sel.getRangeAt(0);
    let prev = null;
    if (r.startContainer.nodeType === 3) {
      if (r.startOffset > 0) return;
      prev = r.startContainer.previousSibling;
    } else {
      prev = r.startContainer.childNodes[r.startOffset - 1] || null;
    }
    if (prev && prev.nodeType === 1 && prev.classList.contains('mention')) {
      e.preventDefault();
      prev.remove();
      syncPromptState();
    }
  });
  syncPromptState();
  // 窓へのドラッグ＆ドロップ
  const dropZone = el('aiPop');
  dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('drop'); });
  dropZone.addEventListener('dragleave', () => dropZone.classList.remove('drop'));
  dropZone.addEventListener('drop', async (e) => {
    e.preventDefault();
    dropZone.classList.remove('drop');
    for (const f of [...(e.dataTransfer.files || [])]) {
      if (!/^(image\/|application\/pdf)/.test(f.type)) { toast(`${f.name} は画像か PDF ではありません`); continue; }
      if (f.size > 25 * 1048576) { toast(`${f.name} は大きすぎます (25MB まで)`); continue; }
      const buf = new Uint8Array(await f.arrayBuffer());
      let bin = '';
      for (let k = 0; k < buf.length; k += 8192) bin += String.fromCharCode.apply(null, buf.subarray(k, k + 8192));
      aiAttachments.push({ kind: 'file', name: f.name, mime: f.type, dataBase64: btoa(bin) });
    }
    renderAttachments();
  });
  // 記憶チップ: 設定の AI へ (無ければその場で学ぶ)
  el('aiMemoryChip').onclick = async () => {
    const m = await memoryGet();
    if (m && m.text) { closeAi(); openSettings(settingsCtx('ai')); return; }
    el('aiHint').textContent = '他のカードから書き方を学んでいます…';
    try { await memoryLearn(); el('aiHint').textContent = '学びました'; }
    catch (err) { el('aiHint').textContent = `学べません: ${err.message || err}`; }
  };
  refreshMemoryChip();
  document.querySelectorAll('#aiScopeSeg button').forEach((b) => {
    b.onclick = () => setAiScope(b.dataset.scope);
  });
  // 考える深さ。選んだものは覚えておく
  const paintEffort = () => document.querySelectorAll('#aiEffortSeg button').forEach((b) => {
    b.classList.toggle('on', b.dataset.effort === aiEffort);
  });
  paintEffort();
  document.querySelectorAll('#aiEffortSeg button').forEach((b) => {
    b.onclick = () => { aiEffort = b.dataset.effort; prefs.setItem('aiEffort', aiEffort); paintEffort(); };
  });
  // 窓の中で AI を切り替える
  el('aiModel').onchange = async () => {
    const [id, model] = el('aiModel').value.split('|');
    await window.anki.aiUse(id, model);
    await refreshAi();
  };
  // 定型の指示は、押すと入力欄に入る (続けて書き足せる)
  document.querySelectorAll('#aiQuick button').forEach((b) => {
    b.onclick = () => {
      const box = el('aiPrompt');
      const cur = promptText().trim();
      setPromptText((cur ? cur + '。' : '') + b.textContent);
      box.focus();
    };
  });
  refreshAi();
  el('aiCancel').onclick = closeAi;
  el('aiRun').onclick = () => (aiBusy ? cancelAi() : runAi());
  el('reviewApply').onclick = () => finishReview(true);
  el('reviewRemember').onclick = () => rememberEditPattern();
  el('aiRememberAsk').onclick = () => el('aiRememberAsk').classList.toggle('on');
  el('reviewDiscard').onclick = () => finishReview(false);
  el('reviewRedo').onclick = () => {
    openAi();
    setPromptText('');
    el('aiPrompt').dataset.placeholder = '修正の指示 (例: 表は残してもっと短く／見出しは変えないで)。前の提案は破棄して作り直します';
    el('aiHint').textContent = '前回の提案を踏まえて作り直します';
  };
  el('aiPop').addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { if (!el('aiMention').hidden) return; e.preventDefault(); if (aiBusy) cancelAi(); else closeAi(); }
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); runAi(); }
  });
  document.addEventListener('mousedown', (e) => {
    const pop = el('aiPop');
    if (pop.hidden || pop.contains(e.target) || el('aiOpen').contains(e.target)) return;
    closeAi();
  });
  document.addEventListener('mousedown', (e) => {
    if (pop.hidden) return;
    if (pop.contains(e.target) || el('replaceOpen').contains(e.target)) return;
    closeReplace();
  });
  pop.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { e.preventDefault(); closeReplace(); }
    if (e.key === 'Enter') { e.preventDefault(); el('replaceAll').click(); }
  });

  // 探す語は、ノート内検索と同じ印を使う
  // 「\t」と書けばタブ文字として探せる (Tab キーは欄の移動に使う)
  const unescapeTab = (v) => v.replace(/\\t/g, '\t');
  fromInput.addEventListener('input', () => {
    if (!state.editor) return;
    findInput.value = unescapeTab(fromInput.value);
    findTotal = state.editor.search(unescapeTab(fromInput.value));
    showCount(state.editor.index());
    el('replaceCount').textContent = fromInput.value ? `${findTotal} 件` : '';
  });

  el('replaceOne').onclick = () => {
    if (!state.editor || !fromInput.value) return;
    if (scopeOf() === 'all') { hint('1 件ずつはこのノートのみです'); return; }
    findTotal = state.editor.replaceOne(unescapeTab(toInput.value));
    showCount(state.editor.index());
    el('replaceCount').textContent = `${findTotal} 件`;
    hint(findTotal ? '' : 'すべて置き換えました');
  };

  el('replaceAll').onclick = async () => {
    const from = unescapeTab(fromInput.value);
    if (!from) return;
    const to = unescapeTab(toInput.value);
    if (scopeOf() === 'note') {
      if (!state.editor) return;
      const done = state.editor.replaceAllHere(to);
      findTotal = 0; showCount(0);
      hint(done ? `${done} 件を置き換えました` : '見つかりませんでした');
      return;
    }
    // すべてのノートは元に戻せないので、必ず確かめる
    const ok = window.confirm(
      `すべてのノートの「${from}」を「${to}」に置き換えます。元には戻せません。`
    );
    if (!ok) return;
    hint('置き換え中…');
    try {
      const res = await window.anki.call('replaceInNotes', {
        query: '', find: from, repl: to, regex: 0,
        matchCase: el('replaceCase').checked ? 1 : 0,
      });
      const count = res && res.notes ? res.notes : 0;
      hint(count ? `${count} 件のノートを置き換えました` : '見つかりませんでした');
      if (count) {
        await search();
        if (state.selected) await select(state.selected);
      }
    } catch (err) {
      hint(`失敗しました: ${err.message || err}`);
    }
  };

  el('editorFindPrev').onclick = () => state.editor && showCount(state.editor.move(-1));
  el('editorFindNext').onclick = () => state.editor && showCount(state.editor.move(1));

  // プレビュー (別ページ) からの合図
  window.addEventListener('message', (e) => {
    if (e.data === 'save') { save(); return; }
    // カードの地色を、プレビューの下地にも敷いておく。
    // 幅が変わる一瞬に見える色が、カードと同じになる
    if (e.data && e.data.type === 'cardBackground' && e.data.color) {
      const color = e.data.color;
      if (color !== 'rgba(0, 0, 0, 0)') {
        el('previewPane').style.background = color;
        document.documentElement.style.setProperty('--card-bg', color);
        console.log('[preview] カードの地色 = ' + color);
      }
    }
  });

  // ショートカット (設定から変えられる)
  const actions = {
    toggleList: () => toggleList(),
    undo: () => undo(),
    redo: () => redo(),
    edit: () => (state.editing ? closeEditor() : openEditor()),
    find: () => openCardFind(),
    sync: () => sync(),
    settings: () => el('settings').click(),
    save: () => save(),
  };
  // メニューバーからの操作
  window.anki.onMenu(async (name) => {
    if (actions[name]) { actions[name](); return; }
    if (name === 'addNote') openAddDialog();
    else if (name === 'backup') openSettings(settingsCtx('backup'));
    else if (name === 'keys') openSettings(settingsCtx('keys'));
    else if (name === 'replace') { if (!state.editing) await openEditor(); const b = el('replaceOpen'); if (b) b.click(); }
    else if (name === 'reload') { await refreshAll(); if (state.selected) await select(state.selected); }
  });
  // メニューに出すショートカットは、設定の割り当てに合わせる
  const pushMenuKeys = () => window.anki.menuKeys(currentKeys());
  pushMenuKeys();
  window.addEventListener('anki-keys-changed', pushMenuKeys);
  window.addEventListener('keydown', (e) => {
    if (window.__noShortcuts) return;      // 調べもの用
    // 設定を開いている間は、カード向けのショートカット (保存など) を効かせない
    const dialog = el('settingsDialog');
    if (dialog && dialog.open && !((e.metaKey || e.ctrlKey) && e.altKey)) return;
    // ⌥⌘I で、要素を調べる道具を開く (Chrome と同じ操作)
    if ((e.metaKey || e.ctrlKey) && e.altKey && (e.key === 'i' || e.key === 'I' || e.code === 'KeyI')) {
      e.preventDefault();
      window.anki.openDevTools();
      return;
    }
    const pressed = keyOf(e);
    if (!pressed) return;
    // エディタの中の取り消し・やり直しは、入力欄の標準の動きに任せる
    const inEditor = e.target && e.target.closest && e.target.closest('.field');
    if (inEditor && (pressed === 'Mod+Z' || pressed === 'Mod+Shift+Z')) return;
    const keys = currentKeys();
    for (const [name, combo] of Object.entries(keys)) {
      if (combo !== pressed) continue;
      e.preventDefault();
      actions[name]();
      return;
    }
  });

  el('sync').onclick = () => sync();
  el('find').onclick = () => openCardFind();
  el('addNote').onclick = () => openAddDialog();
  el('addCancel').onclick = () => el('addDialog').close();
  el('addOk').onclick = () => addNote();
  el('deleteCancel').onclick = () => el('deleteDialog').close();
  el('deleteOk').onclick = () => deleteNote();
  const settingsCtx = (section) => ({
    section,
    call, toast, tags: state.tags,
    redrawCard: () => state.selected && select(state.selected),
    refreshSnippets: () => renderSnippetBar(),
    applyEditorWrap: () => applyEditorWrap(),
    refreshAi: () => refreshAi(),
    research: () => search(),
    titleOf: (note) => noteTitle(note, 2).join(' ').slice(0, 60) || `ノート ${note.id}`,
    sync: () => sync(),
    refreshLogin: async () => { await loadAnkiStatus(); showLoginCall(); await refreshAll(); showLoginCall(); },
    ankiStatus: () => window.anki.ankiStatus(),
    ankiSetProfile: (name) => window.anki.ankiSetProfile(name),
    retag: () => applyTagSettings(),
    tagCounts: state.tagCounts,
    applyWidths: () => layout(),
    backupNow: async (dir) => {
      try {
        return await withCollectionClosed(() =>
          window.anki.backupNow(dir, Number(prefs.getItem('backupKeep') || 1)));
      } finally {
        prefs.setItem('lastBackupSecs', String(Math.round(Date.now() / 1000)));
      }
    },
    watchJob,
    memoryGet, memoryLearn, memoryBackup, memoryBackups, memoryRestore,
    titleOfId: (id) => { const n = state.notes.find((x) => x.id === id); return n ? noteTitle(n, 2).join(' ') : String(id); },
    refreshMemory: () => refreshMemoryChip(),
  });
  el('settings').onclick = () => openSettings(settingsCtx());

  // 一覧の右クリックで削除
  el('notes').addEventListener('contextmenu', (e) => {
    const row = e.target.closest('.note');
    if (!row || !state.selected) return;
    e.preventDefault();
    el('deleteTitle').textContent = noteTitle(state.selected, 2).join(' ');
    el('deleteDialog').showModal();
  });

  // 起動時の絞り込みは、設定で選んだよく使うタグを引き継ぐ
  const hiddenAtBoot = new Set(JSON.parse(prefs.getItem('hiddenTags') || '[]'));
  for (const tag of JSON.parse(prefs.getItem('pinnedTags') || '[]')) {
    if (!hiddenAtBoot.has(tag)) state.selectedTags.add(tag);
  }

  await loadAnkiStatus();
  showLoginCall();
  updateTitlebar();
  watchTyping();
  // 窓が裏に回ったら、保存してコレクションを手放す (Anki を開けるように)。戻ったら必要なときに開き直る
  let releaseTimer = null;
  window.anki.onWindowBlur(() => {
    clearTimeout(releaseTimer);
    releaseTimer = setTimeout(async () => {
      try {
        if (state.editing && state.dirty) await save();
        await window.anki.call('close', {});
        window.anki.mark('窓が裏に回ったのでコレクションを閉じた');
      } catch (e) { window.anki.mark('裏に回ったときの close 失敗: ' + e.message); }
    }, 1500);
  });
  window.anki.onWindowFocus(async () => {
    clearTimeout(releaseTimer);
    const st = await loadAnkiStatus();
    // 裏に回っている間に Anki が編集・同期したかもしれないので、読み直す
    if (st && st.profile && !st.open && !st.running) {
      try {
        await refreshAll();
        if (state.selected) {
          const again = state.notes.find((n) => n.id === state.selected.id);
          if (again) await select(again);
        }
      } catch (e) { window.anki.mark('戻ったときの読み直し失敗: ' + e.message); }
    }
  });
  if (loggedIn()) el('count').textContent = '読み込んでいます…';
  // いつまでも読み込みが終わらないとき (裏側が落ちている等) は、黙って待たせない
  const stuck = setTimeout(() => {
    if (!state.notes.length) {
      el('count').innerHTML =
        '読み込めませんでした。<a href="#" id="retryLoad">もう一度試す</a>';
      const again = el('retryLoad');
      if (again) again.onclick = (e) => { e.preventDefault(); location.reload(); };
    }
  }, 20000);
  await refreshAll();
  clearTimeout(stuck);
  lap('一覧の読み込み');
  if (loggedIn()) await restoreSession();
  lap('前回の状態を戻す');
  // ここから先は、画面が出たあとに裏で進める
  setTimeout(async () => {
    await autoBackup();
    // 何かの拍子に一覧が空のままなら、もう一度だけ読み直す
    if (!state.notes.length) {
      try { await refreshAll(); } catch (e) { window.anki.mark('読み直し失敗: ' + e.message); }
    }
  }, 50);
  console.log(`[boot] ノート ${state.notes.length} 件 / タグ ${state.tags.length} 件` +
    ` / タグ高さ ${el('tags').getBoundingClientRect().height}`);
  console.log('[persist] 前回の記録 = ' + prefs.getItem('persistProbe'));
  prefs.setItem('persistProbe', new Date().toISOString());
  if (location.search.includes('selftest')) setTimeout(selfTest, 1500);   // 復元が終わってから
}

/** 未ログインなら、最初の画面でログインへ誘導する */
/**
 * 実際の打鍵の重さを控える。
 * キーを押してから、次に画面が描かれるまでの時間を測って書き残す
 */
function watchTyping() {
  const samples = [];
  let waiting = false;
  document.addEventListener('keydown', (e) => {
    if (!e.target.closest || !e.target.closest('.field')) return;
    if (waiting) return;
    waiting = true;
    const at = performance.now();
    requestAnimationFrame(() => {
      waiting = false;
      samples.push(performance.now() - at);
      if (samples.length < 20) return;
      samples.sort((a, b) => a - b);
      const line = `打鍵 ${samples.length} 回: 中央 ${samples[10].toFixed(0)}ms ` +
        `最良 ${samples[0].toFixed(0)}ms 最悪 ${samples[samples.length - 1].toFixed(0)}ms ` +
        `(${samples.map((x) => x.toFixed(0)).join(',')})`;
      window.anki.typingLog(line);
      samples.length = 0;
    });
  }, true);
}

/** 自分で書いた CSS を読み込む (保存するたびに入れ直す) */
async function loadCustomCss() {
  try {
    const css = await window.anki.cssRead();
    let tag = document.getElementById('customCss');
    if (!tag) {
      tag = document.createElement('style');
      tag.id = 'customCss';
      document.head.appendChild(tag);       // 最後に置くので、こちらの指定が勝つ
    }
    tag.textContent = css || '';
  } catch { /* 無くても動く */ }
}

/* ---------------- キーボードショートカット ---------------- */

/** 決まりの初期値。設定から変えられる */
export const DEFAULT_KEYS = {
  toggleList: 'Mod+B',
  undo: 'Mod+Z',
  redo: 'Mod+Shift+Z',
  edit: 'Mod+E',
  find: 'Mod+F',
  sync: 'Mod+R',
  settings: 'Mod+,',
  save: 'Mod+S',
};

export const KEY_LABELS = {
  toggleList: '一覧の開閉',
  undo: '取り消す',
  redo: 'やり直す',
  edit: '編集を開く・閉じる',
  find: 'カード内を検索',
  sync: '同期',
  settings: '設定',
  save: '保存',
};

/** 設定に入っているものを足した、いまの割り当て */
export function currentKeys() {
  let saved = {};
  try { saved = JSON.parse(prefs.getItem('shortcuts') || '{}'); } catch { saved = {}; }
  return { ...DEFAULT_KEYS, ...saved };
}

/** 押されたキーを「Mod+Shift+F」の形にする */
export function keyOf(e) {
  const parts = [];
  if (e.metaKey || e.ctrlKey) parts.push('Mod');
  if (e.altKey) parts.push('Alt');
  if (e.shiftKey) parts.push('Shift');
  const key = e.key.length === 1 ? e.key.toUpperCase() : e.key;
  if (['Meta', 'Control', 'Alt', 'Shift'].includes(e.key)) return null;
  parts.push(key);
  return parts.join('+');
}

/** ウインドウタブのボタンの出し入れ */
/**
 * 上のバーが編集欄の幅に収まるように、更新ボタンの文字を切り替える。
 * 収まらないときは「更新」と短くする
 */
function fitEditorBar() {
  const pane = el('editorPane');
  const button = el('refreshPreview');
  if (!button || pane.hidden || button.hidden) return;
  const bar = button.closest('.editor-bar');
  if (!bar) return;

  button.classList.remove('icon-only');
  button.title = 'プレビューを更新';

  // まず長い方で置いてみて、はみ出すようなら短くする
  button.textContent = 'プレビューを更新';
  const room = bar.clientWidth - 24;          // 左右の余白ぶん
  const used = () => [...bar.children]
    .reduce((n, child) => n + child.getBoundingClientRect().width, 0) + 8 * (bar.children.length - 1);
  if (used() > room) button.textContent = '更新';
}

/** 保存ボタンは、未保存の変更があるときだけ色を付ける */
function updateSaveButton() {
  const button = el('save');
  if (!button) return;
  button.classList.toggle('ghost', !state.dirty);
  button.disabled = !state.dirty;
}

function updateTitlebar() {
  const hasCard = !!state.selected;
  el('edit').hidden = !hasCard;
  el('find').hidden = !hasCard;
  updateHistoryButtons();
}

function showLoginCall() {
  const need = !loggedIn();
  // プロフィールが無いあいだは検索や追加も伏せておく
  el('keyword').disabled = need;
  el('addNote').disabled = need;
  el('count').textContent = need ? 'Anki のプロフィールが必要です' : `${state.notes.length} 件`;
  el('emptyText').textContent = need
    ? 'Anki をインストールして一度起動すると、そのカードをここで編集できます'
    : 'ノートを選ぶとここに実際のカードが表示されます';
}

/** 時間のかかる処理が終わるまで待つ */
function waitForJob() {
  return new Promise((resolve) => {
    const tick = async () => {
      const job = await call('jobStatus', {});
      if (job.running) { setTimeout(tick, 500); return; }
      resolve(job);
    };
    tick();
  });
}

/** 時間のかかる処理 (コレクション全体の同期) の様子を見張る */
function watchJob() {
  const tick = async () => {
    const job = await call('jobStatus', {});
    if (job.running) {
      toast(job.total ? `同期中 ${job.transferred}/${job.total}` : '同期中…');
      setTimeout(tick, 700);
    } else if (job.error) {
      toast('同期に失敗しました: ' + job.error);
    } else {
      toast('同期しました');
      refreshAll();
    }
  };
  tick();
}

boot().catch((e) => {
  document.body.innerHTML =
    `<pre style="padding:24px;color:#ffb4ab">起動に失敗しました\n${e.message}</pre>`;
});


/** 動作確認用。?selftest 付きで開いたときだけ走る */
function process_env_target() {
  return location.search.includes('small') ? 'テスト' : '屈折検査';
}

function hooksOnChangeForTest(i) {
  state.dirty = true;
  livePreview();
}

window.addEventListener('error', (e) => {
  // どこで起きたかまで残す (次に起きたとき追えるように)
  const where = e.error && e.error.stack ? ' @ ' + String(e.error.stack).split('\n').slice(1, 3).join(' ').replace(/\s+/g, ' ') : ` @ ${e.filename}:${e.lineno}`;
  try { window.anki.mark('画面の例外: ' + (e.message || e.error) + where); } catch { /* 記録できなくても進む */ }
});
window.addEventListener('unhandledrejection', (e) => {
  const why = e.reason && (e.reason.message || String(e.reason));
  try { window.anki.mark('未処理の失敗: ' + why); } catch { /* 記録できなくても進む */ }
});

async function selfTest() {
  try {
    // 屈折検査のカードを開き、本物のキー入力を流して、引っかかりを数える
    state.keyword = process_env_target();
    el('keyword').value = state.keyword;
    await search();
    // 数式を含むカードがあれば、それを優先して調べる
    let note = state.notes[0];
    for (const candidate of state.notes) {
      const full = await call('noteContent', { id: candidate.id });
      if (full && full.fields.some((f) => /anki-mathjax/.test(f))) { note = candidate; break; }
    }
    if (!note) { console.log('[test] 対象のカードが見つかりません'); return; }
    console.log('[test] 調べるカード ' + note.id);
    await select(note);
    if (!state.editing) await openEditor();
    setMode('html');
    await new Promise((r) => setTimeout(r, 400));

    const at = state.draft.fields.map((f, i) => [i, (f || '').length])
      .sort((x, y) => y[1] - x[1])[0][0];
    const area = document.querySelector(`.fast-area[data-index="${at}"]`);
    if (!area) { console.log('[test] 編集欄が見つかりません'); return; }
    const filled = [...document.querySelectorAll('.fast-area')].map((a) => a.value.length);
    const colored = [...document.querySelectorAll('.fast-layer')]
      .map((l) => l.querySelectorAll('span').length);
    const knobCount = document.querySelectorAll('.fold-knob').length;
    console.log(`[色] 色分けの数 ${colored.join(',')} / つまみ ${knobCount}`);
    const shownText = [...document.querySelectorAll('.fast-layer')]
      .map((l) => l.textContent.trim().length);
    console.log(`[test] 編集欄 ${filled.length} 個 / 中身 ${filled.join(',')} / 表示 ${shownText.join(',')}`);
    area.focus();

    // ここから先は中身を書き換えないこと (カードが壊れるため)

    console.log('[開く] (別のカードは開かない: 取り違えを防ぐため)');

    // AI: 使える AI が無いときはボタンが出ないこと、窓の部品がそろっていること
    {
      const r = await window.anki.aiList();
      console.log(`[AI] 登録 ${r.providers.length} 件 / 使用中 ${r.active ? r.active.label : 'なし'} / ボタン ${el('aiOpen').hidden ? '非表示' : '表示'}` +
        ` / 範囲の選択肢 ${document.querySelectorAll('#aiScopeSeg button').length}`);
    }

    // 提案の確認モード (使い捨てエディタで)
    {
      const box = document.createElement('div');
      box.style.cssText = 'position:absolute;left:-9999px;width:600px';
      document.body.appendChild(box);
      let got = '';
      const ed = createFastEditor(box, ['t'], ['<ul>\n\t<li>古い文</li>\n</ul>'],
        { onChange: (_, v) => { got = v; }, onSave() {}, onFind() {} });
      const ta = box.querySelector('textarea');
      const started = ed.review(0, 0, ta.value.length, '<ul>\n\t<li>新しい文</li>\n\t<li>追加</li>\n</ul>');
      const adds = box.querySelectorAll('mark.diff-add').length;
      const dels = box.querySelectorAll('mark.diff-del').length;
      const ro = ta.readOnly;
      const afterValue = got;
      ed.endReview(false);
      const restored = ta.value === '<ul>\n\t<li>古い文</li>\n</ul>' && !ta.readOnly;
      console.log(`[提案] 開始 ${started} / 緑 ${adds} 赤 ${dels} / 読取専用 ${ro} / 反映 ${afterValue.includes('新しい文')} / 破棄で戻る ${restored}`);
      ed.destroy(); box.remove();
    }

    // 編集の一覧 (差し替える箇所だけ) の読み取りと当てはめ
    {
      const names = ['Front', 'Back'];
      const fields = ['表', '<ul>\n\t<li>古い文</li>\n\t<li>残す文</li>\n</ul>'];
      const reply = '<<<欄: Back>>>\n<<<探す>>>\n<li>古い文</li>\n<<<置換>>>\n<li>新しい文</li>\n\t<li>足した文</li>\n<<<終>>>\n' +
        '<<<欄: Back>>>\n<<<探す>>>\n<li>無い文</li>\n<<<置換>>>\n<li>x</li>\n<<<終>>>';
      const patches = parsePatches(reply, names);
      const r = applyPatches(fields, patches);
      console.log(`[編集一覧] 読めた ${patches.length} 件 / 当たった ${r.hit} 外れ ${r.miss} / 後半が残る ${r.next[1].includes('残す文')} / 追記 ${r.next[1].includes('足した文')}`);
    }

    // ① 書き方の抽出 (読み取りだけ)
    {
      const t0 = performance.now();
      const guide = await styleGuide(true);
      const lines = guide.split('\n').length;
      console.log(`[書き方] ${guide.length} 文字 / ${lines} 行 / ${Math.round(performance.now() - t0)}ms / class例 ${(guide.match(/^- \./gm) || []).length} 個 / CSS ${guide.includes('関係する CSS') ? 'あり' : 'なし'}`);
      const refs = await autoNoteRefs('他のカードを参考にして');
      console.log(`[参照] 自動で拾ったカード ${refs.length} 枚`);
    }

    // スニペットの差し込み位置と、打鍵中の重さ (書き換えたぶんは最後に必ず戻す)
    {
      const keep = area.value;
      area.focus();
      area.setSelectionRange(1000, 1000);
      await new Promise((r) => setTimeout(r, 50));
      const focusedOk = state.focusedField === at;
      if (!focusedOk) console.log(`[スニペット] 焦点の欄 ${state.focusedField} / 実際の焦点 ${document.activeElement && document.activeElement.className} / 読取専用 ${area.readOnly}`);
      // スニペットのボタンと同じ経路
      state.editor.insertAt(state.focusedField || 0, '<!--SNIP-->', 4);
      const where = area.value.indexOf('<!--SNIP-->');
      const caretOk = area.selectionStart === 1000 + 4;
      console.log(`[スニペット] 欄の把握 ${focusedOk} / 入った位置 ${where} (期待 1000) / カーソル ${caretOk}`);
      // 打鍵の重さ: 20 回の入力イベントで 50ms 超の詰まりを数える
      const blocks = [];
      let watch = null;
      try {
        watch = new PerformanceObserver((list) => { for (const e of list.getEntries()) blocks.push(Math.round(e.duration)); });
        watch.observe({ entryTypes: ['longtask'] });
      } catch { watch = null; }
      window.__aniProfile = true;
      const t0 = performance.now();
      for (let k = 0; k < 20; k++) {
        area.setRangeText('あ', 1200, 1200, 'end');
        area.dispatchEvent(new Event('input', { bubbles: true }));
        await new Promise((r) => setTimeout(r, 60));
      }
      await new Promise((r) => setTimeout(r, 900));
      if (watch) watch.disconnect();
      window.__aniProfile = false;
      console.log(`[重さ] 20 打鍵 ${Math.round(performance.now() - t0)}ms / 詰まり ${blocks.length} 回 (${blocks.join(',')})`);
      {
        const layer = document.querySelector(`.field[data-index="${at}"] .fast-layer`);
        const shown = layer.textContent;
        let k = 0;
        while (k < shown.length && shown[k] === area.value[k]) k++;
        console.log(`[整合] 表示と中身 ${shown === area.value + '\n' ? '一致' : '不一致 ' + k} / 高さ 入力欄 ${area.scrollHeight} 表示 ${layer.scrollHeight} / 行の箱 ${layer.querySelectorAll(':scope > .ln').length}`);
      }
      // 元に戻す
      state.editor.setValues(state.draft.fields.map((f, k) => (k === at ? keep : f)));
      area.value = keep;
      state.draft.fields[at] = keep;
      state.dirty = false;
      console.log(`[復元] 元どおり ${state.editor.values()[at] === keep}`);
    }

    // リアルタイムプレビューが実際に届くか (本文を入れ替えて読み返す)
    {
      const mark = 'ライブ確認' + Date.now();
      let found = -1;
      let probe = '';
      try {
        probe = await previewView().executeJavaScript('typeof window.__ankiSetBody');
        await previewView().executeJavaScript(
          `window.__ankiSetBody(${JSON.stringify('<b>' + mark + '</b>')})`);
        await new Promise((r) => setTimeout(r, 300));
        found = await previewView().executeJavaScript(
          `document.getElementById('ankiContent').innerHTML.indexOf(${JSON.stringify(mark)})`);
      } catch (e) { probe = '確認できず ' + e.message; }
      console.log(`[live] 受け口 ${probe} / 差し替え ${found >= 0 ? 'OK' : 'NG'}`);
    }

    // 折りたたみの中も探せるか / 拡大表示と記録の部品
    {
      const box = document.createElement('div');
      box.style.cssText = 'position:absolute;left:-9999px;width:600px';
      document.body.appendChild(box);
      const ed = createFastEditor(box, ['t'], ['<div>\n\t<p>隠れた語</p>\n</div>\n<p>外の語</p>'],
        { onChange() {}, onSave() {}, onFind() {} });
      await new Promise((r) => setTimeout(r, 120));
      const ta = box.querySelector('textarea');
      const knob = box.querySelector('.fold-knob');
      if (knob) knob.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      const foldedLen = ta.value.length;
      const hits = ed.search('隠れた');
      const shown = ta.value.includes('隠れた語');
      console.log(`[折り検索] 畳んだ長さ ${foldedLen} / 一致 ${hits} 件 / 開いた ${shown}`);
      ed.destroy(); box.remove();
      console.log(`[部品] 拡大 ${el('lightbox') ? 'あり' : 'なし'} / 記録 ${el('aiLog') ? 'あり' : 'なし'} / 記憶に追加 ${el('reviewRemember') ? 'あり' : 'なし'}`);
    }

    // プレビューの画像クリックで拡大表示が開くか / 窓の部品
    {
      const view = previewView();
      await view.executeJavaScript('window.__ankiSetBody(\'<img id="pic" src="media://media/x.png" width="20" height="20">\')');
      await new Promise((r) => setTimeout(r, 200));
      const why = await view.executeJavaScript('(function(){try{var p=document.getElementById("pic");if(!p)return "img なし";p.dispatchEvent(new MouseEvent("click",{bubbles:true}));return "clicked";}catch(e){return "例外: "+e.message}})()');
      await new Promise((r) => setTimeout(r, 300));
      console.log('[拡大] ページ側: ' + why);
      const shown = !el('lightbox').hidden;
      if (shown) { el('lightbox').hidden = true; el('lightboxImg').src = ''; }
      console.log(`[拡大] プレビューの画像クリックで開いた ${shown} / 窓の確認列 ${el('aiReview') ? 'あり' : 'なし'} / @候補 ${el('aiMention') ? 'あり' : 'なし'} / 記憶の切替 ${el('aiRememberAsk') ? 'あり' : 'なし'}`);
    }

    // 指示欄の印 (本物の要素) と、@ の候補の並び
    {
      const box = el('aiPrompt');
      box.innerHTML = '<span class="mention" contenteditable="false" data-label="屈折検査">@屈折検査</span> と同じ体裁で';
      const text = promptText();
      const chips = box.querySelectorAll('.mention').length;
      setPromptText('');
      const found = await call('searchNotes', { query: '眼圧', limit: 40, brief: 1 });
      const titled = found.filter((n) => noteTitle(n, 3).join(' ').includes('眼圧')).length;
      console.log(`[＠] 印 ${chips} 個 / 文字化 ${JSON.stringify(text)} / 候補 ${found.length} 件 (見出しに語あり ${titled})`);
    }

    // プレビューからのコピー と カード内検索
    {
      const view = previewView();
      const stampText = 'コピー確認 ' + Date.now();
      await view.executeJavaScript(`window.__ankiSetBody(${JSON.stringify('<p id="cp">' + stampText + '</p>')})`);
      await new Promise((r) => setTimeout(r, 200));
      await view.executeJavaScript('(function(){var p=document.getElementById("cp");var r=document.createRange();r.selectNodeContents(p);var s=getSelection();s.removeAllRanges();s.addRange(r);})()');
      view.focus();
      // 本物の ⌘C で写るか
      view.sendInputEvent({ type: 'keyDown', keyCode: 'c', modifiers: ['cmd'] });
      view.sendInputEvent({ type: 'char', keyCode: 'c', modifiers: ['cmd'] });
      view.sendInputEvent({ type: 'keyUp', keyCode: 'c', modifiers: ['cmd'] });
      await new Promise((r) => setTimeout(r, 400));
      const clip = await window.anki.clipboardText();
      openCardFind();
      await new Promise((r) => setTimeout(r, 300));
      const findOpen = await view.executeJavaScript('document.getElementById("find").classList.contains("open")');
      console.log(`[プレビュー] ⌘C で写った ${clip === stampText} (${JSON.stringify(clip).slice(0, 40)}) / カード内検索が開いた ${findOpen}`);
    }

    // 折りたたみ: 折り返す長い行があっても、見えている範囲のつまみが出るか
    {
      const box = document.createElement('div');
      box.style.cssText = 'position:absolute;left:-9999px;width:400px';
      document.body.appendChild(box);
      const long = 'あ'.repeat(600);
      const text = Array.from({ length: 30 }, (_, k) => `<div>\n\t<p>${long}</p>\n</div>`).join('\n');
      const ed = createFastEditor(box, ['t'], [text], { onChange() {}, onSave() {}, onFind() {} });
      await new Promise((r) => setTimeout(r, 250));
      const knobs = box.querySelectorAll('.fold-knob').length;
      const ta = box.querySelector('textarea');
      ta.scrollTop = ta.scrollHeight;
      ta.dispatchEvent(new Event('scroll'));
      await new Promise((r) => setTimeout(r, 350));
      const knobsEnd = box.querySelectorAll('.fold-knob').length;
      ed.destroy(); box.remove();
      console.log(`[折り畳み] 長い行 30 組 / 先頭で ${knobs} 個 / 末尾まで巻いて ${knobsEnd} 個`);
    }

    // Finder で写した複数ファイルの貼り付け (text/uri-list) が AI チャットに届くか
    {
      const dt = new DataTransfer();
      dt.setData('text/uri-list', 'file:///tmp/clipimg/a.png\r\nfile:///tmp/clipimg/b.png');
      dt.setData('text/plain', 'a.png\rb.png');
      const before = aiAttachments.length;
      el('aiPrompt').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
      await new Promise((r) => setTimeout(r, 600));
      const added = aiAttachments.length - before;
      const chips = document.querySelectorAll('#aiAttachList .chip-file.image').length;
      console.log(`[複数貼付] 添付 ${added} 件 / 表示 ${chips} 枚 / 指示欄 ${JSON.stringify(promptText())}`);
      aiAttachments = []; renderAttachments(); setPromptText('');
    }

    // 打ち込みの測定はしない (下書きを汚し、カードの取り違えの元になったため)
    state.dirty = false;
    updateSaveButton();
    console.log('[test] 完了 (下書きは破棄)');
  } catch (e) {
    console.log('[test] 失敗: ' + (e.message || e));
  }
}
