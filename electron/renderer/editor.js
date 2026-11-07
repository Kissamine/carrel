// HTML ソース編集欄。
// Compose 版 (shared/kotlin/.../HtmlSourceEditorPage.kt) の作りをそのまま移したもの。
// 「透明な textarea + 背後に色分けした表示」で、長い本文でも打鍵が重くならない。

export function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** VS Code (Dark+) 風の色分け */
export function highlight(src) {
  let out = '', last = 0;
  const re = /<!--[\s\S]*?-->|<\/?[A-Za-z][^>]*>|&[A-Za-z#][A-Za-z0-9]*;/g;
  let m;
  while ((m = re.exec(src))) {
    out += escapeHtml(src.slice(last, m.index));
    const chunk = m[0];
    if (chunk.charAt(0) === '&') out += `<span class="ent">${escapeHtml(chunk)}</span>`;
    else if (chunk.slice(0, 4) === '<!--') out += `<span class="cmt">${escapeHtml(chunk)}</span>`;
    else out += `<span class="br">${paintTag(chunk)}</span>`;
    last = m.index + chunk.length;
  }
  return out + escapeHtml(src.slice(last));
}

function paintTag(tag) {
  let out = '', last = 0;
  const name = /^<\/?\s*([A-Za-z][\w:-]*)/.exec(tag);
  if (name) {
    const at = tag.indexOf(name[1]);
    out += escapeHtml(tag.slice(0, at));
    out += `<span class="tag">${escapeHtml(name[1])}</span>`;
    last = at + name[1].length;
  }
  const re = /([A-Za-z_:][\w:.-]*)\s*=\s*("[^"]*"|'[^']*')/g;
  let m;
  while ((m = re.exec(tag))) {
    if (m.index < last) continue;
    out += escapeHtml(tag.slice(last, m.index));
    out += `<span class="attr">${escapeHtml(m[1])}</span>`;
    out += escapeHtml(tag.slice(m.index + m[1].length, re.lastIndex - m[2].length));
    out += `<span class="val">${escapeHtml(m[2])}</span>`;
    last = re.lastIndex;
  }
  return out + escapeHtml(tag.slice(last));
}

function lineBounds(text, at) {
  const start = text.lastIndexOf('\n', at - 1) + 1;
  let end = text.indexOf('\n', at);
  if (end < 0) end = text.length;
  return [start, end];
}

function setValue(ta, text, from, to) {
  ta.value = text;
  ta.selectionStart = from;
  ta.selectionEnd = to === undefined ? from : to;
}

/** VS Code と同じキー操作。処理したら true */
function applyShortcut(ta, e) {
  const cmd = e.metaKey || e.ctrlKey, alt = e.altKey, shift = e.shiftKey;
  const text = ta.value, at = ta.selectionStart;
  const [start, end] = lineBounds(text, at);
  const line = text.slice(start, end);
  const indent = (line.match(/^[\t ]*/) || [''])[0];

  if (cmd && e.key === 'Enter') {                       // 行を足す
    e.preventDefault();
    if (shift) setValue(ta, text.slice(0, start) + indent + '\n' + text.slice(start), start + indent.length);
    else setValue(ta, text.slice(0, end) + '\n' + indent + text.slice(end), end + 1 + indent.length);
    return true;
  }
  if (alt && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {   // 行の移動・複製
    e.preventDefault();
    const up = e.key === 'ArrowUp';
    if (shift) {
      setValue(ta, text.slice(0, end) + '\n' + line + text.slice(end), up ? at : end + 1 + (at - start));
      return true;
    }
    if (up) {
      if (start === 0) return false;
      const pb = lineBounds(text, start - 1);
      const prev = text.slice(pb[0], pb[1]);
      setValue(ta, text.slice(0, pb[0]) + line + '\n' + prev + text.slice(end), pb[0] + (at - start));
    } else {
      if (end >= text.length) return false;
      const nb = lineBounds(text, end + 1);
      const next = text.slice(nb[0], nb[1]);
      setValue(ta, text.slice(0, start) + next + '\n' + line + text.slice(nb[1]),
        start + next.length + 1 + (at - start));
    }
    return true;
  }
  if (cmd && shift && (e.key === 'K' || e.key === 'k')) {        // 行を削除
    e.preventDefault();
    setValue(ta, text.slice(0, start) + text.slice(end < text.length ? end + 1 : end), start);
    return true;
  }
  if (cmd && e.key === '/') {                                     // コメントの付け外し
    e.preventDefault();
    const trimmed = line.trim();
    const replaced = (trimmed.startsWith('<!--') && trimmed.endsWith('-->'))
      ? line.replace('<!--', '').replace(/-->\s*$/, '').trim()
      : `<!-- ${line} -->`;
    setValue(ta, text.slice(0, start) + replaced + text.slice(end), start + replaced.length);
    return true;
  }
  if (cmd && (e.key === ']' || e.key === '[')) {                  // 字下げ
    e.preventDefault();
    const updated = e.key === ']' ? '\t' + line : line.replace(/^\t/, '').replace(/^ {4}/, '');
    setValue(ta, text.slice(0, start) + updated + text.slice(end), start + updated.length);
    return true;
  }
  if (cmd && (e.key === 'l' || e.key === 'L')) {                  // 行を選ぶ
    e.preventDefault();
    setValue(ta, text, start, end);
    return true;
  }
  if (cmd && e.key === 'Backspace') {                             // 行頭まで消す
    e.preventDefault();
    if (at <= start) return false;
    setValue(ta, text.slice(0, start) + text.slice(at), start);
    return true;
  }
  return false;
}

/**
 * 編集欄を組み立てる。
 * hooks: { onChange(index, value), onSave(), onFind() }
 */
export function createEditor(root, fieldNames, values, hooks) {
  root.innerHTML = '';
  const find = { query: '', hits: [], current: -1 };
  const timers = {};

  fieldNames.forEach((name, i) => {
    const label = document.createElement('div');
    label.className = 'field-label';
    label.textContent = name;
    const wrap = document.createElement('div');
    wrap.className = 'field';
    wrap.innerHTML = `<pre class="hl" id="hl${i}"></pre>` +
      `<textarea id="ta${i}" data-index="${i}" spellcheck="false"></textarea>`;
    root.appendChild(label);
    root.appendChild(wrap);
    wrap.querySelector('textarea').value = values[i] || '';
  });

  const areas = () => Array.from(root.querySelectorAll('textarea'));
  const ta = (i) => root.querySelector('#ta' + i);
  const hl = (i) => root.querySelector('#hl' + i);

  // 高さの調整は、打鍵のたびではなく少し落ち着いてから (毎回やると入力が引っかかる)
  const fitTimers = {};
  function fit(area, now = false) {
    const i = area.dataset.index;
    if (!now) {
      clearTimeout(fitTimers[i]);
      fitTimers[i] = setTimeout(() => fit(area, true), 60);
      return;
    }
    const limit = Math.max(320, root.clientHeight);
    const next = Math.min(area.scrollHeight, limit);
    // いまの高さで足りているなら、測り直さない
    if (Math.abs(area.clientHeight - next) < 2 && area.scrollHeight <= limit) return;
    const scroll = area.scrollTop;
    const outer = root.scrollTop;
    area.style.height = 'auto';
    area.style.height = Math.min(area.scrollHeight + 2, limit) + 'px';
    area.scrollTop = scroll;
    if (root.scrollTop !== outer) root.scrollTop = outer;
    const layer = hl(i);
    if (layer) layer.scrollTop = area.scrollTop;
  }

  // 長い本文を丸ごと塗り直すと 1 打鍵ごとに重くなる。
  // 色を付けるのはカーソルの周りだけにし、さらに
  // 「前・色付き・後ろ」の 3 つに分けて、変わったところだけ書き換える
  const FULL_LIMIT = 4000;   // これ以下なら全体を色分け
  const WINDOW = 1200;       // 越えたときに色を付ける前後の幅
  const shown = {};          // 欄ごとに、いま出している範囲と中身

  function windowOf(area) {
    const text = area.value;
    if (text.length <= FULL_LIMIT) return [0, text.length];
    const at = area.selectionStart || 0;
    let from = Math.max(0, at - WINDOW);
    let to = Math.min(text.length, at + WINDOW);
    from = text.lastIndexOf('\n', from) + 1;
    const nextBreak = text.indexOf('\n', to);
    to = nextBreak < 0 ? text.length : nextBreak;
    return [from, to];
  }

  function paint(i, { force = false } = {}) {
    const area = ta(i), layer = hl(i);
    if (!area || !layer) return;
    const text = area.value;
    const [from, to] = windowOf(area);
    const state = shown[i];

    // 3 つの入れ物を用意する (初回だけ)
    if (!state || force || !layer.firstChild) {
      layer.textContent = '';
      const before = document.createTextNode('');
      const middle = document.createElement('span');
      const after = document.createTextNode('');
      layer.append(before, middle, after);
      shown[i] = { before, middle, after, from: -1, to: -1, text: '' };
    }
    const box = shown[i];

    // 前後は範囲が動いたときだけ書き換える (ここが一番重い)
    if (box.from !== from) box.before.nodeValue = text.slice(0, from);
    if (box.to !== to || box.tail !== text.length) {
      box.after.nodeValue = text.slice(to) + '\n';
      box.tail = text.length;
    }
    // 色付きの部分は、中身が変わったときだけ塗り直す
    const middleText = text.slice(from, to);
    if (box.text !== middleText) {
      box.middle.innerHTML = highlight(middleText);
      box.text = middleText;
    }
    box.from = from;
    box.to = to;

    fit(area);
    if (find.query) findRefresh(i);
  }

  // 打鍵のたびに描き直すのではなく、次の描画のタイミングでまとめて 1 回にする
  const pending = new Set();
  let frame = null;
  function schedulePaint(i) {
    if (window.__noPaint) return;   // 重さの内訳を測るためのつまみ
    pending.add(i);
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      const list = [...pending];
      pending.clear();
      for (const index of list) paint(index);
    });
  }

  function notify(i) {
    clearTimeout(timers[i]);
    timers[i] = setTimeout(() => hooks.onChange(i, ta(i).value), 180);
  }

  function findRefresh(i) {
    const area = ta(i), layer = hl(i);
    if (!area || !layer || !find.query) return;
    const current = find.hits[find.current];
    const value = area.value, lower = value.toLowerCase(), needle = find.query.toLowerCase();
    let html = '', last = 0, from = lower.indexOf(needle);
    while (from >= 0) {
      html += highlight(value.slice(last, from));
      const isCurrent = current && current.field === i && current.at === from;
      html += `<mark class="find${isCurrent ? ' cur' : ''}">` +
        highlight(value.substr(from, find.query.length)) + '</mark>';
      last = from + find.query.length;
      from = lower.indexOf(needle, last);
    }
    html += highlight(value.slice(last));
    layer.innerHTML = html + '\n';
    layer.scrollTop = area.scrollTop;
    shown[i] = null;   // 3 分割の組み立ては作り直す
  }

  function collect() {
    find.hits = [];
    if (!find.query) return 0;
    const needle = find.query.toLowerCase();
    areas().forEach((area) => {
      const i = parseInt(area.dataset.index, 10);
      const value = area.value.toLowerCase();
      let from = value.indexOf(needle);
      while (from >= 0) {
        find.hits.push({ field: i, at: from });
        from = value.indexOf(needle, from + 1);
      }
    });
    return find.hits.length;
  }

  function show(step) {
    if (!find.hits.length) return 0;
    find.current = (find.current + step + find.hits.length) % find.hits.length;
    const hit = find.hits[find.current];
    const area = ta(hit.field);
    // ここで焦点を移すと検索欄の続きが本文に入ってしまうので、選択だけ入れる
    area.setSelectionRange(hit.at, hit.at + find.query.length);
    areas().forEach((a) => findRefresh(parseInt(a.dataset.index, 10)));
    const mark = root.querySelector(`#hl${hit.field} mark.cur`);
    if (mark) {
      area.scrollTop = Math.max(0, mark.offsetTop - area.clientHeight / 2);
      hl(hit.field).scrollTop = area.scrollTop;
      const y = area.parentNode.offsetTop + (mark.offsetTop - area.scrollTop) - root.clientHeight / 2;
      root.scrollTo(0, Math.max(0, y));
    }
    return find.current + 1;
  }

  areas().forEach((area) => {
    const i = parseInt(area.dataset.index, 10);
    paint(i);
    area.addEventListener('input', () => { schedulePaint(i); notify(i); });
    area.addEventListener('scroll', () => { hl(i).scrollTop = area.scrollTop; });
    area.addEventListener('focus', () => {
      area.parentNode.classList.add('focus');
      if (hooks.onFocus) hooks.onFocus(i);
    });
    area.addEventListener('blur', () => area.parentNode.classList.remove('focus'));
    area.addEventListener('keydown', (e) => {
      // 日本語の変換中は何もしない (変換確定の Enter で改行が入ってしまうため)
      if (e.isComposing || e.keyCode === 229) return;
      const cmd = e.metaKey || e.ctrlKey;
      if (cmd && (e.key === 's' || e.key === 'S')) {
        e.preventDefault(); hooks.onChange(i, area.value); hooks.onSave(); return;
      }
      if (cmd && (e.key === 'f' || e.key === 'F')) { e.preventDefault(); hooks.onFind(); return; }
      if (cmd && (e.key === 'z' || e.key === 'Z' || e.key === 'y' || e.key === 'Y')) return;
      if (e.key === 'Enter' && !cmd && !e.altKey) {   // 字下げを引き継ぐ
        e.preventDefault();
        const at = area.selectionStart, to = area.selectionEnd, text = area.value;
        const [s, en] = lineBounds(text, at);
        const indent = e.shiftKey ? '' : (text.slice(s, en).match(/^[\t ]*/) || [''])[0];
        const inserted = '\n' + indent;
        setValue(area, text.slice(0, at) + inserted + text.slice(to), at + inserted.length);
        schedulePaint(i); notify(i); return;
      }
      if (cmd || e.altKey) {
        if (applyShortcut(area, e)) { schedulePaint(i); notify(i); }
        return;
      }
      if (e.key !== 'Tab') return;
      e.preventDefault();
      const start = area.selectionStart, end = area.selectionEnd;
      if (e.shiftKey) {
        const before = area.value.slice(0, start);
        const cut = before.endsWith('\t') ? 1 : (before.endsWith('    ') ? 4 : 0);
        if (!cut) return;
        setValue(area, area.value.slice(0, start - cut) + area.value.slice(start), start - cut);
      } else {
        setValue(area, area.value.slice(0, start) + '\t' + area.value.slice(end), start + 1);
      }
      schedulePaint(i); notify(i);
    });
    // カーソルが大きく動いたら、その周りを色分けし直す
    area.addEventListener('click', () => { if (area.value.length > FULL_LIMIT) schedulePaint(i); });
    area.addEventListener('keyup', (e) => {
      if (area.value.length > FULL_LIMIT && e.key.startsWith('Arrow')) schedulePaint(i);
    });
  });

  return {
    values: () => areas().map((a) => a.value),
    /** 中身だけを入れ替える (作り直さないので、見ている位置が動かない) */
    setValues(fields) {
      const outer = root.scrollTop;
      areas().forEach((a, i) => {
        const next = fields[i] || '';
        if (a.value === next) return;
        const at = Math.min(a.selectionStart, next.length);
        a.value = next;
        if (document.activeElement === a) a.setSelectionRange(at, at);
        paint(parseInt(a.dataset.index, 10), { force: true });
      });
      root.scrollTop = outer;
    },
    flush: () => areas().forEach((a) => {
      const i = parseInt(a.dataset.index, 10);
      clearTimeout(timers[i]);
      hooks.onChange(i, a.value);
    }),
    search(query) {
      find.query = query; find.current = -1;
      const total = collect();
      areas().forEach((a) => {
        const i = parseInt(a.dataset.index, 10);
        if (query) findRefresh(i); else paint(i, { force: true });
      });
      if (total) show(1);
      return total;
    },
    move: (step) => show(step),
    index: () => find.current + 1,
    clear() {
      const stay = find.hits[find.current];
      find.query = ''; find.hits = []; find.current = -1;
      areas().forEach((a) => paint(parseInt(a.dataset.index, 10), { force: true }));
      if (stay) {                       // 飛んだ場所に留まる
        const area = ta(stay.field);
        area.focus({ preventScroll: true });
        area.setSelectionRange(stay.at, stay.at);
      }
    },
  };
}


/** CSS の色分け (テンプレートのスタイル欄用) */
export function highlightCss(src) {
  let out = '';
  let last = 0;
  // コメント / 波括弧 / 「プロパティ: 値;」 / それ以外はセレクタ
  const re = /\/\*[\s\S]*?\*\/|[{}]|([A-Za-z-]+)(\s*:\s*)([^;{}]*)(;?)/g;
  let m;
  while ((m = re.exec(src))) {
    const before = src.slice(last, m.index);
    out += before.trim() ? `<span class="tk-sel">${escapeHtml(before)}</span>` : escapeHtml(before);
    const chunk = m[0];
    if (chunk.startsWith('/*')) out += `<span class="tk-cm">${escapeHtml(chunk)}</span>`;
    else if (chunk === '{' || chunk === '}') out += `<span class="tk-br">${chunk}</span>`;
    else {
      out += `<span class="tk-prop">${escapeHtml(m[1])}</span>${escapeHtml(m[2])}` +
        `<span class="tk-val">${escapeHtml(m[3])}</span>${escapeHtml(m[4])}`;
    }
    last = re.lastIndex;
  }
  const rest = src.slice(last);
  out += rest.trim() ? `<span class="tk-sel">${escapeHtml(rest)}</span>` : escapeHtml(rest);
  return out;
}
