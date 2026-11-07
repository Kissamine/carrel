// HTML ソース編集。
//
// 作り直しの方針:
//   打っている間は、素の <textarea> をそのまま使う。
//   1 打鍵ごとに走る自前の処理を無くすことで、入力の重さを断ち切る。
//   色分けは「その欄から離れているとき」に、静止画のように重ねて見せる。
//
// これにより
//   ・クリックした瞬間から打てる (編集欄を作り直さない)
//   ・打鍵のたびの色分け・高さ計算・受け渡しが無い
//   ・読むときは色が付いている
// という形になる。

import { highlight as highlightHtml, highlightCss, escapeHtml } from './editor.js';
import { diffText } from './diff.js';

// 閉じタグを持たないもの
const VOID = new Set(['br', 'img', 'hr', 'input', 'meta', 'link', 'source',
  'area', 'base', 'col', 'embed', 'param', 'track', 'wbr']);
// 折りたたんだ中身の目印は、見えない文字で作る
const FOLD_MARK = /\u22ef\u200b+/g;

const IDLE_MS = 250;        // 打鍵が止まったとみなすまで
const MAX_HEIGHT = 0.9;     // 高さの上限 (ウインドウ比)
const LINE_PX = 20;         // 1 行のおおよその高さ (13px × 1.55)

export function createFastEditor(root, fieldNames, values, hooks) {
  root.innerHTML = '';
  // 欄ごとの色分け (通常は HTML、テンプレートのスタイル欄だけ CSS)
  const langOf = (i) => (hooks.langOf ? hooks.langOf(i) : 'html');
  let paintingField = 0;
  const highlight = (text) => (langOf(paintingField) === 'css' ? highlightCss(text) : highlightHtml(text));
  const areas = [];
  const layers = [];
  const shotBoxes = [];
  const timers = {};
  const foldSig = {};           // 欄ごとに、前回描いたつまみの並び (同じなら描き直さない)
  const lineHtml = [];          // 欄ごとに、いま出している行の HTML
  const lineText = [];          // 欄ごとに、前回塗ったときの行の文字
  const folded = new Map();     // 折りたたんで預かっている中身
  const reviewing = new Map();  // 確認中の欄 i -> { before, after }
  const past = [];              // 取り消し用の控え { i, value, at }
  const future = [];
  let burst = null;             // 打鍵のまとまりを 1 回分として数えるための時計
  let lastField = 0;
  let foldSeq = 0;

  fieldNames.forEach((name, i) => {
    const label = document.createElement('div');
    label.className = 'field-label';
    label.textContent = name;

    const wrap = document.createElement('div');
    wrap.className = 'field fast';
    wrap.dataset.index = String(i);

    const layer = document.createElement('pre');
    layer.className = 'fast-layer';
    // 中身は後回し。まず箱だけ並べて、すぐ打てる状態にする

    const area = document.createElement('textarea');
    area.className = 'fast-area';
    area.dataset.index = String(i);
    area.spellcheck = false;
    area.value = values[i] || '';

    const shots = document.createElement('div');
    shots.className = 'field-shots';
    shots.hidden = true;

    wrap.append(layer, area);
    root.append(label, wrap, shots);
    shotBoxes.push(shots);
    areas.push(area);
    layers.push(layer);

    // 表示されてから測る (隠れている間は正しい高さが出ない)
    requestAnimationFrame(() => { fit(i); drawFolds(i); drawShots(i); });

    // 触っている間は色分けを外す (打鍵のたびに塗り直さないため)
    area.addEventListener('focus', () => {
      if (hooks.onFocus) hooks.onFocus(i);
    });
    area.addEventListener('blur', () => {
      repaint(i);
      fit(i);
      flush(i);
    });
    // 入力欄を動かしたら、色の層も同じだけ動かす
    let scrollTimer = null;
    area.addEventListener('scroll', () => {
      layers[i].scrollTop = area.scrollTop;
      layers[i].scrollLeft = area.scrollLeft;        // 折り返さない設定では横にも動く
      // 見えている範囲が変わったら、つまみを描き足す
      clearTimeout(scrollTimer);
      scrollTimer = setTimeout(() => { try { drawFolds(i); } catch { /* 次で直る */ } }, 160);
    });

    // カーソルが止まったら、対応するタグを光らせる。
    // 文字を打っている間は出さない (タグの中を直すたびに点滅するため)
    let matchTimer = null;
    const laterMatch = () => {
      clearTimeout(matchTimer);
      matchTimer = setTimeout(() => showMatch(i), 140);
    };
    const MOVE_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown',
      'Home', 'End', 'PageUp', 'PageDown']);
    area.addEventListener('keyup', (e) => {
      if (!MOVE_KEYS.has(e.key)) return;
      laterMatch();
    });
    area.addEventListener('click', () => {
      // 入力欄に触れたら、検索の印は消す (打ち始める場所を見失わないように)
      if (find.query && hooks.onFindCleared) {
        find.query = ''; find.hits = []; find.current = -1;
        areas.forEach((_, k) => repaint(k));
        hooks.onFindCleared();
      }
      laterMatch();
    });

    // 打つ直前の姿を控えておく (取り消しのため)
    area.addEventListener('beforeinput', (e) => {
      const heavy = e.inputType === 'insertFromPaste' || e.inputType === 'insertFromDrop' ||
        e.inputType === 'deleteByCut' || e.inputType.startsWith('deleteHard');
      remember(i, heavy);
    });

    area.addEventListener('input', () => {
      // 色分けは次の描画のタイミングでまとめて 1 回
      matchOf[i] = '';
      schedulePaint(i);
      // 中身の受け渡しと高さの計算は、打鍵が落ち着いてから
      clearTimeout(timers[i]);
      timers[i] = setTimeout(() => {
        const t0 = window.__aniProfile ? performance.now() : 0;
        repaint(i);                                // 手が止まったので、全体を正確に塗り直す
        flush(i);
        const t1 = t0 ? performance.now() : 0;
        fit(i);
        const t2 = t0 ? performance.now() : 0;
        matchOf[i] = null;
        drawFolds(i);
        const t3 = t0 ? performance.now() : 0;
        drawShots(i);
        if (t0) {
          const t4 = performance.now();
          if (t4 - t0 > 8) console.log(`[内訳] idle flush ${Math.round(t1 - t0)} fit ${Math.round(t2 - t1)} folds ${Math.round(t3 - t2)} shots ${Math.round(t4 - t3)}`);
        }
      }, IDLE_MS);
      if (hooks.onDirty) hooks.onDirty(i);
    });

    // 折りたたんだところを写すときは、中身を戻してから渡す
    const putPlain = (e) => {
      const picked = area.value.slice(area.selectionStart, area.selectionEnd);
      if (!picked || !FOLD_MARK.test(picked)) { FOLD_MARK.lastIndex = 0; return; }
      FOLD_MARK.lastIndex = 0;
      e.clipboardData.setData('text/plain', expand(picked));
      e.preventDefault();
      if (e.type === 'cut') {
        remember(i, true);
        setRange(area, area.value.slice(0, area.selectionStart) + area.value.slice(area.selectionEnd),
          area.selectionStart);
        flush(i);
      }
    };
    area.addEventListener('copy', putPlain);
    area.addEventListener('cut', putPlain);

    // クリップボードの画像を貼り付ける (⌘V)
    area.addEventListener('paste', (e) => {
      if (!hooks.onPasteImage) return;
      const items = [...(e.clipboardData ? e.clipboardData.items : [])];
      const shots = items.filter((it) => it.kind === 'file' && it.type.startsWith('image/'));
      if (!shots.length) {
        // Finder で ⌘C した画像ファイル (複数可) は file:// の一覧で来る
        const uris = ((e.clipboardData && e.clipboardData.getData('text/uri-list')) || '').split(/\r?\n/).filter((u) => /^file:\/\//.test(u));
        if (!uris.length || !window.anki || !window.anki.readFileUris) return;    // 普通の文字の貼り付け
        e.preventDefault();
        window.anki.readFileUris(uris).then((files) => {
          const pics = files.filter((f) => /^image\//.test(f.mime)).map((f) => ({ ...f, fromFinder: true }));
          if (pics.length) hooks.onPasteImage(i, pics.length === 1 ? pics[0] : pics);
        }).catch(() => {});
        return;
      }
      e.preventDefault();
      const files = shots.map((it) => it.getAsFile()).filter(Boolean);
      if (files.length) hooks.onPasteImage(i, files.length === 1 ? files[0] : files);
    });

    area.addEventListener('keydown', (e) => {
      if (e.isComposing || e.keyCode === 229) return;   // 変換中は触らない
      // <ul> と打ち終えた瞬間に </ul> を続けて置く (カーソルは間に残す)
      if (e.key === '>' && !e.metaKey && !e.ctrlKey && !e.altKey &&
          area.selectionStart === area.selectionEnd) {
        const at = area.selectionStart;
        const head = area.value.slice(0, at);
        const m = /<([a-zA-Z][\w-]*)(\s[^<>]*)?$/.exec(head);
        if (m && !/\/$/.test(head) && !VOID.has(m[1].toLowerCase()) &&
            !area.value.slice(at).startsWith('</' + m[1] + '>')) {
          e.preventDefault();
          remember(i, false);
          setRange(area, head + '>' + '</' + m[1] + '>' + area.value.slice(at), at + 1);
          area.dispatchEvent(new Event('input', { bubbles: true }));
          return;
        }
      }
      const cmd = e.metaKey || e.ctrlKey;
      if (cmd && (e.key === 's' || e.key === 'S')) {
        e.preventDefault(); e.stopPropagation(); flush(i); hooks.onSave(); return;
      }
      if (cmd && (e.key === 'z' || e.key === 'Z')) {
        e.preventDefault();
        if (e.shiftKey) apply(future, past); else apply(past, future);
        return;
      }
      if (cmd && (e.key === 'f' || e.key === 'F')) { e.preventDefault(); e.stopPropagation(); hooks.onFind(); return; }
      if (cmd && !e.shiftKey && (e.key === 'i' || e.key === 'I') && hooks.onAi) {
        e.preventDefault(); hooks.onAi(i); return;
      }
      if (cmd && 'zZyY'.includes(e.key)) return;        // 取り消しは標準の動きに任せる
      if (e.key === 'Enter' && !cmd && !e.altKey) {
        e.preventDefault();
        const at = area.selectionStart;
        const [from] = lineAt(area.value, at);
        const indent = e.shiftKey ? '' : (/^[\t ]*/.exec(area.value.slice(from))[0] || '');
        // <div>|</div> の間なら、中に一段下げた行を作って閉じタグを次の行へ送る
        const head = area.value.slice(from, at);
        const tail = area.value.slice(at);
        const opened = /<([a-zA-Z][\w-]*)\b[^<>]*>$/.exec(head);
        if (opened && !e.shiftKey && tail.startsWith('</' + opened[1] + '>')) {
          setRange(area,
            area.value.slice(0, at) + '\n' + indent + '\t' + '\n' + indent + tail,
            at + 1 + indent.length + 1);
          return;
        }
        insert(area, '\n' + indent);
        return;
      }
      if (cmd || e.altKey) { shortcut(area, e); return; }
      if (e.key === 'Tab') {
        e.preventDefault();
        const picked = area.value.slice(area.selectionStart, area.selectionEnd);
        if (picked.includes('\n')) {                      // 複数行なら行ごとに字下げ
          const [s0] = lineAt(area.value, area.selectionStart);
          const [, e1] = lineAt(area.value, area.selectionEnd - 1);
          const lines = area.value.slice(s0, e1).split('\n');
          const moved = lines.map((ln) => (e.shiftKey
            ? ln.replace(/^\t/, '').replace(/^ {1,4}/, '')
            : (ln.length ? '\t' + ln : ln)));
          const body = moved.join('\n');
          remember(i, true);
          setRange(area, area.value.slice(0, s0) + body + area.value.slice(e1), s0);
          area.setSelectionRange(s0, s0 + body.length);
          flush(i);
          return;
        }
        if (e.shiftKey) {
          const start = area.selectionStart;
          const before = area.value.slice(0, start);
          const cut = before.endsWith('\t') ? 1 : (before.endsWith('    ') ? 4 : 0);
          if (!cut) return;
          setRange(area, area.value.slice(0, start - cut) + area.value.slice(start), start - cut);
        } else {
          insert(area, '\t');
        }
      }
    });
  });

  // 作った時点で中身 (色分け) を必ず入れる。
  // 一つ失敗しても、ほかの欄が空にならないようにする
  areas.forEach((_, i) => {
    try { repaint(i); } catch { layers[i].textContent = areas[i].value; }
    try { drawFolds(i); } catch { /* 位置が測れるようになってから描き直す */ }
  });

  /** 変える直前の姿を控える (打鍵が続く間は 1 回分にまとめる) */
  function remember(i, force) {
    lastField = i;
    if (!force && burst && burst.i === i && Date.now() - burst.at < 600) {
      burst.at = Date.now();
      return;
    }
    past.push({ i, value: areas[i].value, at: areas[i].selectionStart });
    if (past.length > 200) past.shift();
    future.length = 0;
    burst = force ? null : { i, at: Date.now() };
  }

  /** 控えた姿に戻す (取り消し・やり直しの共通処理) */
  function apply(step, into) {
    const snap = step.pop();
    if (!snap) return false;
    const area = areas[snap.i];
    if (!area) return false;
    into.push({ i: snap.i, value: area.value, at: area.selectionStart });
    burst = null;
    const keep = area.scrollTop;
    area.value = snap.value;
    area.focus({ preventScroll: true });
    area.setSelectionRange(snap.at, snap.at);
    matchOf[snap.i] = null;
    repaint(snap.i);
    fit(snap.i);
    area.scrollTop = keep;
    layers[snap.i].scrollTop = keep;
    flush(snap.i);
    drawFolds(snap.i);
    drawShots(snap.i);
    return true;
  }

  const pending = new Set();
  let frame = null;
  function schedulePaint(i) {
    pending.add(i);
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      const list = [...pending];
      pending.clear();
      for (const index of list) {
        repaint(index, null, true);
        layers[index].scrollTop = areas[index].scrollTop;
      }
    });
  }

  /**
   * 中身に合わせて高さを決める (打鍵中は呼ばない)。
   * 空の欄は小さく、長い欄は中身のぶんだけ伸ばし、上限はウインドウの 9 割。
   */
  function fit(i) {
    if (reviewing.has(i)) return;               // 確認中は差分の高さのまま
    const area = areas[i];
    const limit = Math.round(window.innerHeight * MAX_HEIGHT);
    // 行数から見て上限を大きく超えている欄は、高さが変わらないので測らない
    const lines = lineHtml[i] ? lineHtml[i].length : 0;
    if (lines * LINE_PX > limit * 1.5 && parseInt(area.style.height, 10) === limit) return;
    const empty = area.value.trim().length === 0;
    const least = empty ? 38 : 60;   // 空の欄は小さく、中身があれば少しだけ確保
    area.style.height = 'auto';
    const height = Math.min(Math.max(area.scrollHeight + 2, least), limit);
    area.style.height = height + 'px';
    layers[i].style.height = height + 'px';
    area.style.overflowY = area.scrollHeight > height + 2 ? 'auto' : 'hidden';
  }

  /** すべての欄の高さを整え直す */
  function fitAll() {
    areas.forEach((_, i) => { fit(i); drawFolds(i); });
  }

  // 画面の大きさが変わったら、上限も変わるので測り直す
  const onResize = () => fitAll();
  window.addEventListener('resize', onResize);

  /** 色分けを描き直す (欄全体) */
  /** 提案の確認中は、色分けの代わりに「消えた所 (赤)・増えた所 (緑)」を描く */
  function paintDiff(i) {
    const r = reviewing.get(i);
    const parts = diffText(r.before, r.after);
    let out = '';
    for (const [kind, text] of parts) {
      if (kind === '=') out += escapeHtml(text);
      else if (kind === '+') out += `<mark class="diff-add">${escapeHtml(text)}</mark>`;
      else out += `<mark class="diff-del">${escapeHtml(text)}</mark>`;
    }
    layers[i].innerHTML = out + '\n';
    // 消えた文も見せるぶん背が伸びるので、欄の高さは表示側に合わせる
    const need = Math.min(layers[i].scrollHeight + 2, Math.round(window.innerHeight * MAX_HEIGHT));
    areas[i].style.height = need + 'px';
    layers[i].style.height = need + 'px';
    layers[i].scrollTop = 0;
    areas[i].scrollTop = 0;
  }

  /**
   * 色分けの HTML を、行ごとに閉じた断片に分ける。
   * 行をまたぐ <span> や <mark> は、行末で閉じて次の行頭で開き直す
   */
  function splitLines(html) {
    const lines = [];
    const open = [];          // いま開いているタグ (そのままの文字列)
    let cur = '';
    let k = 0;
    const n = html.length;
    const jump = /[<\n]/g;
    while (k < n) {
      jump.lastIndex = k;
      const m = jump.exec(html);
      if (!m) { cur += html.slice(k); break; }
      if (m.index > k) cur += html.slice(k, m.index);
      k = m.index;
      if (html[k] === '<') {
        const end = html.indexOf('>', k);
        const tag = html.slice(k, end + 1);
        if (tag[1] === '/') open.pop(); else open.push(tag);
        cur += tag;
        k = end + 1;
        continue;
      }
      cur += '\n';
      for (let q = open.length - 1; q >= 0; q--) cur += '</' + /^<([a-z]+)/i.exec(open[q])[1] + '>';
      lines.push(cur);
      cur = open.join('');
      k++;
    }
    for (let q = open.length - 1; q >= 0; q--) cur += '</' + /^<([a-z]+)/i.exec(open[q])[1] + '>';
    lines.push(cur);
    return lines;
  }

  function repaint(i, marks, quick = false) {
    foldSig[i] = null;                          // 中身を入れ替えたので、つまみも置き直す
    if (reviewing.has(i)) { lineHtml[i] = null; lineText[i] = null; paintDiff(i); return; }
    paintingField = i;
    const text = areas[i].value;
    const layer = layers[i];
    const tick = window.__aniProfile ? performance.now() : 0;
    // 末尾に 1 行ぶん足しておく。<pre> は最後の改行で行を作らないため、
    // 足さないと文末まで巻いたときに表示だけが 1 行ぶん上にずれる
    const wrap = (h) => `<span class="ln">${h}</span>`;
    const prev = lineHtml[i];
    const prevText = lineText[i];
    const marksBox = layer.querySelector(':scope > .fold-marks');
    const lineCount = layer.childElementCount - (marksBox ? 1 : 0);
    const same = prev && prev.length && lineCount === prev.length;
    let lines;
    let tickA = 0;
    let tickB = 0;
    const rawLines = text.split('\n');
    if (quick && same && !marks && prevText && rawLines.length === prevText.length) {
      // 変わった行の前後だけ塗り直す (行をまたぐ印は次の全体塗りで直る)
      let head = 0;
      const total = rawLines.length;
      while (head < total && rawLines[head] === prevText[head]) head++;
      let tail = 0;
      while (tail < total - head && rawLines[total - 1 - tail] === prevText[total - 1 - tail]) tail++;
      if (head === total) { lineText[i] = rawLines; return; }   // 何も変わっていない
      const from = Math.max(0, head - 2);
      const to = Math.min(total, total - tail + 2);
      tickA = tick ? performance.now() : 0;
      // 各行は末尾の改行ごと箱に入れる。途中までの範囲では、余った空行を落とす
      const part = splitLines(highlight(rawLines.slice(from, to).join('\n')) + '\n');
      if (to < total) part.pop();
      tickB = tick ? performance.now() : 0;
      lines = prev.slice();
      lines.splice(from, to - from, ...part);
    } else if (quick && same && !marks && prevText) {
      // 行数が変わった: 変わった範囲の前後だけ塗り直す
      let head = 0;
      const maxHead = Math.min(rawLines.length, prevText.length);
      while (head < maxHead && rawLines[head] === prevText[head]) head++;
      let tail = 0;
      while (tail < maxHead - head && rawLines[rawLines.length - 1 - tail] === prevText[prevText.length - 1 - tail]) tail++;
      const from = Math.max(0, head - 2);
      const toNew = Math.min(rawLines.length, rawLines.length - tail + 2);
      const toOld = Math.min(prevText.length, prevText.length - tail + 2);
      tickA = tick ? performance.now() : 0;
      const part = splitLines(highlight(rawLines.slice(from, toNew).join('\n')) + '\n');
      if (toNew < rawLines.length) part.pop();
      tickB = tick ? performance.now() : 0;
      lines = prev.slice(0, from).concat(part, prev.slice(toOld));
    } else {
      const html = (marks ? withMarks(text, marks) : highlight(text)) + '\n';
      tickA = tick ? performance.now() : 0;
      lines = splitLines(html);
      tickB = tick ? performance.now() : 0;
    }
    lineText[i] = rawLines;
    if (!same || marks) {
      layer.innerHTML = lines.map(wrap).join('');
    } else {
      // 変わっていない行はそのまま。前後の共通部分を除いた真ん中だけ差し替える
      let head = 0;
      const maxHead = Math.min(prev.length, lines.length);
      while (head < maxHead && prev[head] === lines[head]) head++;
      let tail = 0;
      while (tail < maxHead - head &&
             prev[prev.length - 1 - tail] === lines[lines.length - 1 - tail]) tail++;
      const oldCount = prev.length - head - tail;
      const fresh = lines.slice(head, lines.length - tail);
      if (oldCount || fresh.length) {
        const kids = layer.children;
        const range = document.createRange();
        if (oldCount) {
          range.setStartBefore(kids[head]);
          range.setEndAfter(kids[head + oldCount - 1]);
          range.deleteContents();
        }
        if (fresh.length) {
          const tpl = document.createElement('template');
          tpl.innerHTML = fresh.map(wrap).join('');
          // つまみの箱は最後に置いてあるので、その手前に入れる
          const anchor = (kids[head] && kids[head] !== marksBox) ? kids[head] : (marksBox || null);
          layer.insertBefore(tpl.content, anchor);
        }
      }
    }
    lineHtml[i] = marks ? null : lines;
    if (tick) {
      const tickC = performance.now();
      void layer.offsetHeight;                   // 組み直しの重さを見る
      const tickD = performance.now();
      const took = tickD - tick;
      if (took > 8) console.log(`[内訳] repaint ${Math.round(took)}ms = 色 ${Math.round(tickA - tick)} 分割 ${Math.round(tickB - tickA)} DOM ${Math.round(tickC - tickB)} 組直し ${Math.round(tickD - tickC)} / 行数 ${lines.length} ${same && !marks ? '一部' : '全部'}`);
    }
    // 中身を入れ替えると巻き位置が先頭に戻ることがある。
    // 戻したままだと、文字とカーソルの位置がずれて見える
    if (layer.scrollTop !== areas[i].scrollTop) layer.scrollTop = areas[i].scrollTop;
  }

  function escapeText(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** 検索で見つかった場所に印を付ける */
  function withMarks(text, marks, offset = 0) {
    let out = '';
    let last = 0;
    for (const m of marks) {
      const from = m.at - offset;
      const to = from + m.length;
      if (to <= 0 || from >= text.length) continue;
      out += highlight(text.slice(last, Math.max(0, from)));
      out += `<mark class="${m.cls || 'hit'}${m.current ? ' current' : ''}">` +
        escapeText(text.slice(Math.max(0, from), to)) + '</mark>';
      last = to;
    }
    return out + highlight(text.slice(last));
  }

  function flush(i) {
    clearTimeout(timers[i]);
    hooks.onChange(i, expand(areas[i].value));
  }

  /** カーソル位置に差し込む */
  function insert(area, text) {
    const start = area.selectionStart;
    const end = area.selectionEnd;
    setRange(area, area.value.slice(0, start) + text + area.value.slice(end), start + text.length);
  }

  function setRange(area, text, from, to) {
    area.value = text;
    area.selectionStart = from;
    area.selectionEnd = to === undefined ? from : to;
    area.dispatchEvent(new Event('input'));
  }

  function lineAt(text, at) {
    const from = text.lastIndexOf('\n', at - 1) + 1;
    let to = text.indexOf('\n', at);
    if (to < 0) to = text.length;
    return [from, to];
  }

  /** VS Code と同じキー操作 */
  function shortcut(area, e) {
    const cmd = e.metaKey || e.ctrlKey;
    const alt = e.altKey;
    const shift = e.shiftKey;
    const text = area.value;
    const at = area.selectionStart;
    const [start, end] = lineAt(text, at);
    const line = text.slice(start, end);
    const indent = /^[\t ]*/.exec(line)[0];

    if (cmd && e.key === 'Enter') {                       // 行を足す
      e.preventDefault();
      if (shift) setRange(area, text.slice(0, start) + indent + '\n' + text.slice(start), start + indent.length);
      else setRange(area, text.slice(0, end) + '\n' + indent + text.slice(end), end + 1 + indent.length);
      return;
    }
    if (alt && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {   // 行の移動・複製
      e.preventDefault();
      const up = e.key === 'ArrowUp';
      if (shift) {
        setRange(area, text.slice(0, end) + '\n' + line + text.slice(end), up ? at : end + 1 + (at - start));
        return;
      }
      if (up) {
        if (start === 0) return;
        const [pf, pt] = lineAt(text, start - 1);
        const prev = text.slice(pf, pt);
        setRange(area, text.slice(0, pf) + line + '\n' + prev + text.slice(end), pf + (at - start));
      } else {
        if (end >= text.length) return;
        const [nf, nt] = lineAt(text, end + 1);
        const next = text.slice(nf, nt);
        setRange(area, text.slice(0, start) + next + '\n' + line + text.slice(nt),
          start + next.length + 1 + (at - start));
      }
      return;
    }
    if (cmd && shift && (e.key === 'K' || e.key === 'k')) {        // 行を削除
      e.preventDefault();
      setRange(area, text.slice(0, start) + text.slice(end < text.length ? end + 1 : end), start);
      return;
    }
    if (cmd && e.key === '/') {                                    // コメント
      e.preventDefault();
      const trimmed = line.trim();
      const replaced = (trimmed.startsWith('<!--') && trimmed.endsWith('-->'))
        ? line.replace('<!--', '').replace(/-->\s*$/, '').trim()
        : `<!-- ${line} -->`;
      setRange(area, text.slice(0, start) + replaced + text.slice(end), start + replaced.length);
      return;
    }
    if (cmd && (e.key === ']' || e.key === '[')) {                 // 字下げ
      e.preventDefault();
      const updated = e.key === ']' ? '\t' + line : line.replace(/^\t/, '').replace(/^ {4}/, '');
      setRange(area, text.slice(0, start) + updated + text.slice(end), start + updated.length);
      return;
    }
    if (cmd && (e.key === 'l' || e.key === 'L')) {                 // 行を選ぶ
      e.preventDefault();
      area.selectionStart = start;
      area.selectionEnd = end;
      return;
    }
    if (cmd && e.key === 'Backspace') {                            // 行頭まで消す
      e.preventDefault();
      if (at <= start) return;
      setRange(area, text.slice(0, start) + text.slice(at), start);
    }
  }

  /** その欄で使っている画像を、下に小さく並べる */
  function drawShots(i) {
    const box = shotBoxes[i];
    if (!box || !hooks.mediaUrl) return;
    const names = [];
    const re = /<img[^>]+src\s*=\s*["']([^"']+)["']/gi;
    let m;
    while ((m = re.exec(areas[i].value))) {
      const name = m[1];
      if (!names.includes(name)) names.push(name);
    }
    if (box.dataset.shown === names.join('|')) return;   // 変わっていなければ触らない
    box.dataset.shown = names.join('|');
    box.innerHTML = '';
    box.hidden = !names.length;
    for (const name of names) {
      if (!name.trim() || /^(javascript:|about:)/i.test(name)) continue;   // 中身の無い src は出さない
      const img = document.createElement('img');
      img.loading = 'lazy';
      img.title = name;
      // 読めない画像 (ファイルが無い等) は消し、全部消えたら枠ごと畳む
      img.onerror = () => {
        img.remove();
        if (!box.querySelector('img')) box.hidden = true;
      };
      img.src = hooks.mediaUrl(name);
      img.style.cursor = 'zoom-in';
      img.addEventListener('click', () => { if (hooks.onOpenImage) hooks.onOpenImage(img.src, name); });
      box.append(img);
    }
    if (!box.querySelector('img')) box.hidden = true;
  }

  // ---- タグの対応付けと折りたたみ ----

  /** 開きタグと閉じタグの組を、入れ子のまま拾い出す */
  function tagPairs(text) {
    const re = /<(\/?)([a-zA-Z][\w-]*)\b[^>]*?(\/?)>/g;
    const stack = [];
    const pairs = [];
    let m;
    while ((m = re.exec(text))) {
      const closing = m[1] === '/';
      const name = m[2].toLowerCase();
      if (m[3] === '/' || VOID.has(name)) continue;
      if (!closing) { stack.push({ name, start: m.index, end: re.lastIndex }); continue; }
      for (let k = stack.length - 1; k >= 0; k--) {
        if (stack[k].name !== name) continue;
        pairs.push({
          name,
          openStart: stack[k].start, openEnd: stack[k].end,
          closeStart: m.index, closeEnd: re.lastIndex,
        });
        stack.length = k;
        break;
      }
    }
    return pairs;
  }

  /** 折りたたんである場所を、元の中身に戻した文字列 */
  function expand(text) {
    let out = text;
    for (let n = 0; n < 20 && FOLD_MARK.test(out); n++) {
      FOLD_MARK.lastIndex = 0;
      out = out.replace(FOLD_MARK, (mark) => (folded.has(mark) ? folded.get(mark) : ''));
    }
    FOLD_MARK.lastIndex = 0;
    return out;
  }

  /** カーソルが乗っているタグと、その相方 */
  function pairAtCaret(i) {
    const area = areas[i];
    if (area.selectionStart !== area.selectionEnd) return null;
    const at = area.selectionStart;
    let best = null;
    for (const p of tagPairs(area.value)) {
      // タグの中にカーソルがあるときだけ。端に接しているだけでは光らせない
      const on = (at > p.openStart && at < p.openEnd) || (at > p.closeStart && at < p.closeEnd);
      if (!on) continue;
      if (!best || p.closeStart - p.openStart < best.closeStart - best.openStart) best = p;
    }
    return best;
  }

  const matchOf = {};   // 欄ごとに、いま光らせている組
  function showMatch(i) {
    if (find.query) return;               // 検索中はそちらの印を優先する
    const pair = pairAtCaret(i);
    const key = pair ? `${pair.openStart}-${pair.closeStart}` : '';
    if (matchOf[i] === key) return;       // 変わっていなければ塗り直さない
    matchOf[i] = key;
    if (!pair) { repaint(i); drawFolds(i); return; }
    repaint(i, [
      { at: pair.openStart, length: pair.openEnd - pair.openStart, cls: 'tagpair' },
      { at: pair.closeStart, length: pair.closeEnd - pair.closeStart, cls: 'tagpair' },
    ]);
    drawFolds(i);
  }

  /** 文字の位置を画面の座標に直す (折り返しがあっても正しく出る) */
  function topOfOffset(layer, offset) {
    const walker = document.createTreeWalker(layer, NodeFilter.SHOW_TEXT);
    let seen = 0;
    let node;
    while ((node = walker.nextNode())) {
      const len = node.nodeValue.length;
      if (seen + len > offset) {
        const at = offset - seen;
        const range = document.createRange();
        range.setStart(node, at);
        // 1 文字ぶんを囲って測る (幅の無い範囲は高さが取れないことがある)
        range.setEnd(node, Math.min(len, at + 1));
        const rect = range.getClientRects()[0] || range.getBoundingClientRect();
        if (!rect || !rect.height) return null;
        return rect.top - layer.getBoundingClientRect().top + layer.scrollTop;
      }
      seen += len;
    }
    return null;
  }

  /** 左端の三角 (折りたたみのつまみ) を並べ直す */
  function drawFolds(i) {
    const layer = layers[i];
    const text = areas[i].value;
    // 畳める組だけを先に集めて、位置順に並べる
    const wanted = [];
    for (const p of tagPairs(text)) {
      const inner = text.slice(p.openEnd, p.closeStart);
      const shut = /^\u22ef\u200b+$/.test(inner);
      if (!shut && !inner.includes('\n')) continue;      // 1 行なら畳む意味がない
      wanted.push({ at: p.openStart, name: p.name, shut });
    }
    wanted.sort((a, b) => a.at - b.at);
    const area = areas[i];
    const viewTop = area.scrollTop - 800;
    const viewBottom = area.scrollTop + area.clientHeight + 800;
    const sig = wanted.map((w) => `${w.at}${w.shut ? '!' : ''}`).join(',') + '|' + Math.round(area.scrollTop / 400);
    if (foldSig[i] === sig && layer.querySelector('.fold-marks')) return;
    foldSig[i] = sig;
    const old = layer.querySelector('.fold-marks');
    if (old) old.remove();
    if (!wanted.length) return;

    // 文字ノードを 1 回だけ歩いて、すべての位置をまとめて測る
    // (1 つずつ先頭から歩き直すと、長い本文では二乗の重さになる)
    const box = document.createElement('div');
    box.className = 'fold-marks';
    const layerTop = layer.getBoundingClientRect().top - layer.scrollTop;
    const walker = document.createTreeWalker(layer, NodeFilter.SHOW_TEXT);
    let seen = 0;
    let node = walker.nextNode();
    let k = 0;
    while (node && k < wanted.length) {
      const len = node.nodeValue.length;
      while (k < wanted.length && wanted[k].at < seen + len) {
        const w = wanted[k++];
        const at = w.at - seen;
        const range = document.createRange();
        range.setStart(node, at);
        range.setEnd(node, Math.min(len, at + 1));
        const rect = range.getClientRects()[0];
        if (!rect || !rect.height) continue;
        // 見えている範囲 (上下に余裕) の外は作らない。折り返しがあっても実測なので外さない
        const y = rect.top - layerTop;
        if (y < viewTop || y > viewBottom) continue;
        const knob = document.createElement('button');
        knob.className = `fold-knob${w.shut ? ' shut' : ''}`;
        knob.style.top = `${rect.top - layerTop}px`;
        knob.title = w.shut ? `${w.name} を開く` : `${w.name} を畳む`;
        knob.dataset.at = String(w.at);
        box.append(knob);
      }
      seen += len;
      node = walker.nextNode();
    }
    if (box.childNodes.length) layer.append(box);
  }

  /** つまみを押したときの開け閉め */
  function toggleFold(i, openStart) {
    const area = areas[i];
    const text = area.value;
    const pair = tagPairs(text).find((p) => p.openStart === openStart);
    if (!pair) return;
    const inner = text.slice(pair.openEnd, pair.closeStart);
    let next;
    if (/^\u22ef\u200b+$/.test(inner)) {
      next = text.slice(0, pair.openEnd) + (folded.get(inner) || '') + text.slice(pair.closeStart);
      folded.delete(inner);
    } else {
      const mark = '\u22ef' + '\u200b'.repeat(++foldSeq);
      folded.set(mark, inner);
      next = text.slice(0, pair.openEnd) + mark + text.slice(pair.closeStart);
    }
    const before = topOfOffset(layers[i], pair.openStart);
    const keep = area.scrollTop;
    area.value = next;
    matchOf[i] = null;
    repaint(i);
    fit(i);
    // 押した行が同じ高さに留まるように、巻き具合をそろえる
    const after = topOfOffset(layers[i], pair.openStart);
    const shift = before === null || after === null ? 0 : after - before;
    area.scrollTop = keep + shift;
    layers[i].scrollTop = area.scrollTop;
    drawFolds(i);
  }

  root.addEventListener('mousedown', (e) => {
    const knob = e.target.closest && e.target.closest('.fold-knob');
    if (!knob) return;
    e.preventDefault();     // 入力欄から焦点を奪わない
    const field = knob.closest('.field');
    toggleFold(Number(field.dataset.index), Number(knob.dataset.at));
  });

  // ---- ノート内検索 ----
  const find = { query: '', hits: [], current: -1 };

  function collect() {
    find.hits = [];
    if (!find.query) return 0;
    const needle = find.query.toLowerCase();
    areas.forEach((area, i) => {
      const body = area.value.toLowerCase();
      let at = body.indexOf(needle);
      while (at >= 0) {
        find.hits.push({ field: i, at });
        at = body.indexOf(needle, at + needle.length);
      }
    });
    return find.hits.length;
  }

  /** 見つかった場所すべてに印を付け直す */
  function paintHits() {
    areas.forEach((_, i) => {
      const marks = find.hits
        .filter((h) => h.field === i)
        .map((h) => ({ at: h.at, length: find.query.length, current: h === find.hits[find.current] }));
      repaint(i, marks.length ? marks : null);
    });
  }

  function show(step) {
    if (!find.hits.length) return 0;
    find.current = (find.current + step + find.hits.length) % find.hits.length;
    const hit = find.hits[find.current];
    const area = areas[hit.field];
    // 焦点は移さない (検索欄に打った続きが本文へ入らないように)
    area.setSelectionRange(hit.at, hit.at + find.query.length);
    paintHits();
    // 印の位置を測って、そこへ送る
    const mark = layers[hit.field].querySelector('mark.current');
    if (mark) {
      const top = Math.max(0, mark.offsetTop - area.clientHeight / 2);
      area.scrollTop = top;
      layers[hit.field].scrollTop = top;
    }
    area.closest('.field').scrollIntoView({ block: 'center' });
    return find.current + 1;
  }

  return {
    destroy() {
      for (const key of Object.keys(timers)) clearTimeout(timers[key]);
      window.removeEventListener('resize', onResize);
      root.innerHTML = '';
    },
    /** 外から高さを整え直す (開いた直後など) */
    refit: () => fitAll(),
    values: () => areas.map((a) => expand(a.value)),
    flush: () => areas.forEach((_, i) => flush(i)),
    setValues(fields) {
      const outer = root.scrollTop;
      areas.forEach((area, i) => {
        const next = fields[i] || '';
        if (area.value === next) return;
        area.value = next;
        repaint(i);
        fit(i);
        drawShots(i);
      });
      root.scrollTop = outer;
    },
    /** いま選ばれている範囲 (無ければ欄全体) */
    selectionOf(i) {
      const area = areas[i] || areas[0];
      const start = area.selectionStart;
      const end = area.selectionEnd;
      const whole = start === end;
      return {
        field: areas.indexOf(area),
        start: whole ? 0 : start,
        end: whole ? area.value.length : end,
        text: whole ? area.value : area.value.slice(start, end),
        whole,
      };
    },
    /**
     * 提案を本文に入れて、違いを色で見せる (確認モード)。
     * 入力欄は読み取り専用になり、適用か破棄を待つ。適用後も ⌘Z で戻せる
     */
    review(i, start, end, text) {
      const area = areas[i] || areas[0];
      const at = areas.indexOf(area);
      const before = area.value.slice(start, end);
      if (before === text) return false;
      const whole = area.value;
      remember(at, true);
      const keepScroll = area.scrollTop;
      area.value = whole.slice(0, start) + text + whole.slice(end);
      reviewing.set(at, { before: whole, after: area.value });
      area.readOnly = true;
      const wrapEl = area.closest('.field');
      if (wrapEl) wrapEl.classList.add('reviewing');
      paintDiff(at);
      flush(at);                                  // プレビューにも反映する
      const marksBox = layers[at].querySelector('.fold-marks');
      if (marksBox) marksBox.remove();
      area.scrollTop = keepScroll;
      return true;
    },
    /** 確認を終える。keep=false なら提案を取り消して元へ戻す */
    endReview(keep) {
      const fields = [...reviewing.keys()];
      for (const at of fields) {
        const area = areas[at];
        reviewing.delete(at);
        area.readOnly = false;
        const wrapEl = area.closest('.field');
        if (wrapEl) wrapEl.classList.remove('reviewing');
        if (!keep) {
          // 控えから戻す (past の最後がこの欄の提案前の姿)
          for (let k = past.length - 1; k >= 0; k--) {
            if (past[k].i !== at) continue;
            const snap = past.splice(k, 1)[0];
            area.value = snap.value;
            area.setSelectionRange(snap.at, snap.at);
            break;
          }
          flush(at);
        }
        matchOf[at] = null;
        repaint(at);
        fit(at);
        drawFolds(at);
        drawShots(at);
      }
      return fields.length;
    },
    isReviewing: () => reviewing.size > 0,
    /** 範囲を差し替える (1 回の取り消しで戻せる) */
    replaceRange(i, start, end, text) {
      const area = areas[i] || areas[0];
      const at = areas.indexOf(area);
      remember(at, true);
      const keep = area.scrollTop;
      setRange(area, area.value.slice(0, start) + text + area.value.slice(end), start, start + text.length);
      area.scrollTop = keep;
      layers[at].scrollTop = keep;
      flush(at);
      fit(at);
      drawFolds(at);
      drawShots(at);
    },
    insertAt(i, text, caret) {
      const area = areas[i] || areas[0];
      const start = area.selectionStart;
      remember(areas.indexOf(area), true);
      insert(area, text);
      // 差し込んだ中の決まった場所にカーソルを置く (スニペットの {cursor})
      if (caret !== undefined) area.setSelectionRange(start + caret, start + caret);
      area.focus({ preventScroll: true });
      const at = areas.indexOf(area);
      repaint(at);
      fit(at);
      flush(at);
      drawShots(at);
    },
    insert(text) {
      const area = areas.find((a) => a === document.activeElement) || areas[0];
      insert(area, text);
      repaint(areas.indexOf(area));
    },
    search(query) {
      find.query = query;
      find.current = -1;
      // 畳んである中に一致があれば、先に開いておく (開かないと探せない)
      if (query) {
        const needle = query.toLowerCase();
        areas.forEach((area, i) => {
          let guard = 0;
          while (guard++ < 50) {
            const text = area.value;
            let opened = false;
            for (const p of tagPairs(text)) {
              const inner = text.slice(p.openEnd, p.closeStart);
              if (!/^\u22ef\u200b+$/.test(inner)) continue;
              const hidden = expand(folded.get(inner) || '');
              if (!hidden.toLowerCase().includes(needle)) continue;
              toggleFold(i, p.openStart);
              opened = true;
              break;                         // 位置がずれるので、組を取り直す
            }
            if (!opened) break;
          }
        });
      }
      const total = collect();
      if (total) show(1);
      else areas.forEach((_, i) => repaint(i));
      return total;
    },
    /** いま印が付いているところだけを置き換える */
    replaceOne(text) {
      const hit = find.hits[find.current];
      if (!hit) return 0;
      const area = areas[hit.field];
      const body = area.value;
      area.value = body.slice(0, hit.at) + text + body.slice(hit.at + find.query.length);
      hooks.onChange(hit.field, area.value);
      if (hooks.onDirty) hooks.onDirty(hit.field);
      const total = collect();
      find.current = -1;
      if (total) show(1); else areas.forEach((_, i) => repaint(i));
      fit(hit.field);
      return total;
    },
    /** このノートの中を、まとめて置き換える */
    replaceAllHere(text) {
      if (!find.query) return 0;
      let done = 0;
      areas.forEach((area, i) => {
        const parts = area.value.split(find.query);
        if (parts.length < 2) return;
        done += parts.length - 1;
        area.value = parts.join(text);
        hooks.onChange(i, area.value);
        if (hooks.onDirty) hooks.onDirty(i);
        repaint(i);
        fit(i);
      });
      find.hits = []; find.current = -1;
      return done;
    },
    move: (step) => show(step),
    undo: () => apply(past, future),
    redo: () => apply(future, past),
    canUndo: () => past.length > 0,
    index: () => find.current + 1,
    clear() {
      const stay = find.hits[find.current];
      find.query = ''; find.hits = []; find.current = -1;
      areas.forEach((_, i) => repaint(i));
      if (stay) {
        const area = areas[stay.field];
        area.focus({ preventScroll: true });
        area.setSelectionRange(stay.at, stay.at);
      }
    },
  };
}
