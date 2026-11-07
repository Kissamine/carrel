// HTML ソース編集 (CodeMirror 版)。
// 長い本文でも打鍵が重くならないよう、見えている範囲だけを描く仕組みに乗せている。
// キー操作は VS Code に合わせたものを、そのまま持ち込んでいる。

import { highlight as plainHighlight } from './editor.js';
import {
  EditorState, EditorView, keymap, history, historyKeymap, defaultKeymap,
  indentMore, indentLess, insertNewlineAndIndent,
  moveLineUp, moveLineDown, copyLineUp, copyLineDown,
  deleteLine, selectLine, deleteToLineStart, toggleComment,
  undo as undoCmd, redo as redoCmd,
  html, syntaxHighlighting, HighlightStyle, tags, indentUnit,
  drawSelection,
} from './vendor/cm.bundle.js';
import { EditorState as State } from './vendor/cm.bundle.js';

/** VS Code (Dark+) に近い色づかい */
const paint = HighlightStyle.define([
  { tag: tags.tagName, color: '#569CD6' },
  { tag: tags.angleBracket, color: '#808080' },
  { tag: tags.attributeName, color: '#9CDCFE' },
  { tag: tags.attributeValue, color: '#CE9178' },
  // style="" の中の CSS も、Anki 本体と同じように色を分ける
  { tag: tags.propertyName, color: '#9CDCFE' },
  { tag: tags.unit, color: '#B5CEA8' },
  { tag: tags.number, color: '#B5CEA8' },
  { tag: tags.keyword, color: '#569CD6' },
  { tag: tags.definitionOperator, color: '#D4D4D4' },
  { tag: tags.punctuation, color: '#D4D4D4' },
  { tag: tags.className, color: '#4EC9B0' },
  { tag: tags.color, color: '#CE9178' },
  { tag: tags.string, color: '#CE9178' },
  { tag: tags.comment, color: '#6A9955', fontStyle: 'italic' },
  { tag: tags.character, color: '#D7BA7D' },
  { tag: tags.content, color: '#F0F0F0' },
]);

const look = EditorView.theme({
  '&': { backgroundColor: 'transparent', color: '#F0F0F0', fontSize: '13px' },
  '.cm-content': {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    lineHeight: '1.55', padding: '10px 12px', caretColor: '#9FCBFF',
  },
  '.cm-line': { padding: '0' },
  '&.cm-focused': { outline: 'none' },
  '.cm-selectionBackground, ::selection': { backgroundColor: 'rgba(120,170,255,.35)' },
  '&.cm-focused .cm-selectionBackground': { backgroundColor: 'rgba(120,170,255,.35)' },
  '.cm-cursor': { borderLeftColor: '#9FCBFF' },
  '.cm-scroller': { overflow: 'auto' },
  '.cm-searchMatch': { backgroundColor: 'rgba(120,170,255,.45)' },
  '.cm-searchMatch-selected': { backgroundColor: '#f2b705', color: '#111' },
}, { dark: true });

/** 見つかった場所に印を付けるための下ごしらえ */
function markStyle() {
  return EditorView.baseTheme({
    '.anki-hit': { backgroundColor: 'rgba(120,170,255,.45)' },
    '.anki-hit-current': { backgroundColor: '#f2b705', color: '#111' },
  });
}

export function createCmEditor(root, fieldNames, values, hooks) {
  root.innerHTML = '';
  // 欄ごとの中身。編集していない欄は本物の編集欄を作らず、文字を並べるだけにする。
  // 12 個の編集欄を常に生かしておくと、1 打鍵ごとに全部が測り直され、
  // 大きなカードでは数秒かかってしまう
  const text = fieldNames.map((_, i) => values[i] || '');
  const views = new Array(fieldNames.length).fill(null);
  const boxes = [];

  fieldNames.forEach((name, i) => {
    const label = document.createElement('div');
    label.className = 'field-label';
    label.textContent = name;
    const box = document.createElement('div');
    box.className = 'field cm';
    box.dataset.index = String(i);
    root.append(label, box);
    boxes.push(box);
    showPlain(i);
  });

  /** まだ触っていない欄。中身をそのまま出しておく */
  function showPlain(i) {
    const box = boxes[i];
    box.innerHTML = '';
    const view = document.createElement('pre');
    view.className = 'cm-plain';
    // 触るまでは編集欄を作らない。色分けは付けるが、
    // 長い欄は先頭だけにする (全部並べると、他の欄を打つたびに再計算されて重い)
    const body = text[i] || '';
    const shown = body.length > 3000 ? body.slice(0, 3000) : body;
    view.innerHTML = plainHighlight(shown) +
      (body.length > 3000 ? '<span class="more">…（触ると全部出ます）</span>' : '');
    box.appendChild(view);
    const start = () => { mount(i, true); };
    box.addEventListener('mousedown', start, { once: true });
    box.addEventListener('focusin', start, { once: true });
  }

  /** 本物の編集欄に切り替える */
  function mount(i, focus) {
    if (views[i]) return views[i];
    const box = boxes[i];
    box.innerHTML = '';
    const view = makeView(box, i);
    views[i] = view;
    if (focus) {
      view.focus();
      view.dispatch({ selection: { anchor: Math.min(view.state.doc.length, 0) } });
    }
    return view;
  }

  function makeView(box, i) {
    const longest = text[i].split('\n').reduce((n, line) => Math.max(n, line.length), 0);
    // 折り返しと色分けは常に入れる (Anki 本体の HTML 編集と同じ見え方にする)
    const wrap = [EditorView.lineWrapping];
    const paintExt = [html(), syntaxHighlighting(paint)];

    const keys = keymap.of([
      { key: 'Tab', run: indentMore, shift: indentLess },
      { key: 'Mod-]', run: indentMore },
      { key: 'Mod-[', run: indentLess },
      { key: 'Mod-Enter', run: (view) => insertLine(view, false) },
      { key: 'Shift-Mod-Enter', run: (view) => insertLine(view, true) },
      { key: 'Alt-ArrowUp', run: moveLineUp },
      { key: 'Alt-ArrowDown', run: moveLineDown },
      { key: 'Shift-Alt-ArrowUp', run: copyLineUp },
      { key: 'Shift-Alt-ArrowDown', run: copyLineDown },
      { key: 'Shift-Mod-k', run: deleteLine },
      { key: 'Mod-/', run: toggleComment },
      { key: 'Mod-l', run: selectLine },
      { key: 'Mod-Backspace', run: deleteToLineStart },
      // Enter は「いまの行の字下げ」だけを引き継ぐ
      { key: 'Enter', run: (view) => {
        const { state } = view;
        const line = state.doc.lineAt(state.selection.main.head);
        const indent = /^[\t ]*/.exec(line.text)[0];
        view.dispatch(state.replaceSelection('\n' + indent), { scrollIntoView: true });
        return true;
      } },
      { key: 'Mod-s', run: () => { flushOne(i); hooks.onSave(); return true; } },
      { key: 'Mod-f', run: () => { hooks.onFind(); return true; } },
    ]);

    return new EditorView({
      parent: box,
      state: EditorState.create({
        doc: text[i],
        extensions: [
          keys,
          keymap.of(historyKeymap),
          keymap.of(defaultKeymap),
          history(),
          drawSelection(),
          ...paintExt,
          indentUnit.of('\t'),
          State.tabSize.of(4),
          look,
          markStyle(),
          ...wrap,
          // 綴り確認や自動修正は、長い本文だと打鍵のたびに走って重い
          EditorView.contentAttributes.of({
            spellcheck: 'false', autocorrect: 'off', autocapitalize: 'off',
            translate: 'no',
          }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              if (hooks.onDirty) hooks.onDirty(i);
              schedule(i);
            }
            if (update.focusChanged && update.view.hasFocus && hooks.onFocus) hooks.onFocus(i);
          }),
        ],
      }),
    });
  }

  /** いまの中身 (編集欄があればそこから、無ければ控えから) */
  function valueOf(i) {
    if (views[i]) return views[i].state.doc.toString();
    return text[i] || '';
  }

  // 中身を外へ伝えるのは、打鍵が落ち着いてから
  const timers = {};
  function schedule(i) {
    clearTimeout(timers[i]);
    timers[i] = setTimeout(() => {
      text[i] = views[i] ? views[i].state.doc.toString() : text[i];
      hooks.onChange(i, text[i]);
    }, 300);   // 打鍵が続いている間は、本文を取り出さない
  }
  function flushOne(i) {
    clearTimeout(timers[i]);
    text[i] = valueOf(i);
    hooks.onChange(i, text[i]);
  }

  /** ⌘Enter: 下に行を足す / ⇧⌘Enter: 上に行を足す */
  function insertLine(view, above) {
    const { state } = view;
    const line = state.doc.lineAt(state.selection.main.head);
    const indent = /^[\t ]*/.exec(line.text)[0];
    const at = above ? line.from : line.to;
    const text = above ? indent + '\n' : '\n' + indent;
    view.dispatch({
      changes: { from: at, insert: text },
      selection: { anchor: at + text.length },
      scrollIntoView: true,
    });
    return true;
  }

  // ---- ノート内検索 ----
  const find = { query: '', hits: [], current: -1 };

  function collect() {
    find.hits = [];
    if (!find.query) return 0;
    const needle = find.query.toLowerCase();
    fieldNames.forEach((_, i) => {
      const body = valueOf(i).toLowerCase();
      let at = body.indexOf(needle);
      while (at >= 0) {
        find.hits.push({ field: i, at });
        at = body.indexOf(needle, at + needle.length);
      }
    });
    return find.hits.length;
  }

  function show(step) {
    if (!find.hits.length) return 0;
    find.current = (find.current + step + find.hits.length) % find.hits.length;
    const hit = find.hits[find.current];
    const view = mount(hit.field, false);   // 必要になった欄だけ本物にする
    view.dispatch({
      selection: { anchor: hit.at, head: hit.at + find.query.length },
      scrollIntoView: true,
    });
    boxes[hit.field].scrollIntoView({ block: 'center' });
    return find.current + 1;
  }

  return {
    /** 片付け。これを呼ばないと、古い編集欄が裏で生き続けて重くなる */
    destroy() {
      for (const key of Object.keys(timers)) clearTimeout(timers[key]);
      views.forEach((view) => { if (view) view.destroy(); });
      views.fill(null);
      root.innerHTML = '';
    },
    values: () => fieldNames.map((_, i) => valueOf(i)),
    flush: () => fieldNames.forEach((_, i) => { if (views[i]) flushOne(i); }),
    setValues(fields) {
      const outer = root.scrollTop;
      fieldNames.forEach((_, i) => {
        const next = fields[i] || '';
        if (valueOf(i) === next) return;
        text[i] = next;
        if (views[i]) {
          views[i].dispatch({ changes: { from: 0, to: views[i].state.doc.length, insert: next } });
        } else {
          showPlain(i);
        }
      });
      root.scrollTop = outer;
    },
    insert(html2) {
      const at = views.findIndex((v) => v && v.hasFocus);
      const view = at >= 0 ? views[at] : mount(0, true);
      view.dispatch(view.state.replaceSelection(html2));
    },
    search(query) {
      find.query = query;
      find.current = -1;
      const total = collect();
      if (total) show(1);
      return total;
    },
    /** 取り消し・やり直しは CodeMirror の履歴を使う (別に控えを持たない) */
    undo() {
      const view = views.find((v) => v && v.hasFocus) || views.find((v) => v);
      return view ? undoCmd(view) : false;
    },
    redo() {
      const view = views.find((v) => v && v.hasFocus) || views.find((v) => v);
      return view ? redoCmd(view) : false;
    },
    move: (step) => show(step),
    index: () => find.current + 1,
    clear() {
      const stay = find.hits[find.current];
      find.query = ''; find.hits = []; find.current = -1;
      if (stay && views[stay.field]) {
        views[stay.field].focus();
        views[stay.field].dispatch({ selection: { anchor: stay.at } });
      }
    },
  };
}

export function createBareEditor(parent, doc, which = '', hooks = null) {
  const extras = [];
  if (which.includes('hooks') && hooks) {
    extras.push(EditorView.updateListener.of((update) => {
      if (update.docChanged) {
        if (hooks.onDirty) hooks.onDirty(0);
        hooks.onChange(0, update.state.doc.toString());
      }
    }));
  }
  if (which.includes('selection')) extras.push(drawSelection());
  if (which.includes('history')) extras.push(history(), keymap.of(historyKeymap));
  if (which.includes('theme')) extras.push(look, markStyle());
  if (which.includes('keys')) extras.push(keymap.of(defaultKeymap));
  return new EditorView({ parent, state: EditorState.create({ doc, extensions: extras }) });
}
