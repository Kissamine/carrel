package com.haru.ankieditor

/**
 * HTML ソース編集用のエディタを WebView 上に組み立てる。
 *
 * Compose のテキスト欄に長い本文を入れると、1 文字打つたびに全体を測り直すため
 * 実測で 150ms/打鍵ほどかかっていた (色分けの有無に関係なく)。
 * ブラウザの textarea は同じ長さでも編集が軽く、日本語入力の変換も安定するので、
 * 「透明な textarea + 背後に色分けした表示」という作りにしている。
 *
 * Android と macOS で同じものを使う。
 */
object HtmlSourceEditorPage {

    /** ページを組み立てる。bridge は JavaScript から呼べる窓口の名前 */
    fun build(fieldNames: List<String>, values: List<String>, bridge: String): String {
        val fields = fieldNames.mapIndexed { i, name ->
            val value = escape(values.getOrElse(i) { "" })
            """
            <div class="label">${escape(name)}</div>
            <div class="wrap" data-index="$i">
              <pre class="hl" id="hl$i"></pre>
              <textarea id="ta$i" data-index="$i" spellcheck="false"
                        autocapitalize="off" autocorrect="off">$value</textarea>
            </div>
            <div class="thumbs" id="th$i"></div>
            """.trimIndent()
        }.joinToString("\n")

        return """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
  :root { color-scheme: dark; }
  body { background:#1C1C1C; color:#F0F0F0; margin:0; padding:12px 12px 24px;
         font-family:-apple-system,BlinkMacSystemFont,"Helvetica Neue",sans-serif; }
  .label { font-size:11px; color:#9aa0a6; margin:14px 0 5px; }
  .wrap { position:relative; border:1px solid #3a3f45; border-radius:10px;
          background:#161616; overflow:hidden; }
  .wrap.focus { border-color:#9FCBFF; }
  .hl, textarea {
    margin:0; padding:10px 12px; border:0; box-sizing:border-box;
    font-family:ui-monospace,SFMono-Regular,Menlo,monospace;
    font-size:13px; line-height:1.55; tab-size:4; -moz-tab-size:4;
    white-space:pre-wrap; word-break:break-word; overflow-wrap:anywhere;
  }
  .hl { position:absolute; inset:0; pointer-events:none; overflow:hidden; }
  textarea { position:relative; display:block; width:100%; min-height:64px;
             background:transparent; color:transparent; caret-color:#9FCBFF;
             resize:none; outline:none; overflow:auto; }
  textarea::selection { background:rgba(120,170,255,.35); }
  .tag { color:#569CD6; } .attr { color:#9CDCFE; } .val { color:#CE9178; }
  .ent { color:#D7BA7D; } .cmt { color:#6A9955; } .br { color:#808080; }
  mark.find { background:rgba(120,170,255,.45); color:inherit; }
  mark.find.cur { background:#f2b705; color:#111; }
  .thumbs { display:flex; flex-wrap:wrap; gap:6px; margin-top:6px; }
  .thumbs img { height:64px; border-radius:6px; }
</style></head>
<body>
$fields
<script>
(function () {
  var bridge = window.$bridge || (window.cefQuery ? {
    send: function (text) {
      window.cefQuery({ request: text, onSuccess: function () {}, onFailure: function () {} });
    },
    onFieldChanged: function (i, v) { this.send('field:' + i + ':' + v); },
    onFocus: function (i) { this.send('focus:' + i); },
    onBlur: function () { this.send('blur'); }
  } : null);
  var timers = {};
  // 検索の状態。paint から findRefresh を呼ぶので、先に用意しておく
  var find = { query: '', hits: [], current: -1 };

  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // VS Code (Dark+) 風の色分け。タグ・属性・値・実体参照・コメントを塗り分ける
  function highlight(src) {
    var out = '', last = 0;
    var re = /<!--[\s\S]*?-->|<\/?[A-Za-z][^>]*>|&[A-Za-z#][A-Za-z0-9]*;/g, m;
    while ((m = re.exec(src))) {
      out += escapeHtml(src.slice(last, m.index));
      var chunk = m[0];
      if (chunk.charAt(0) === '&') {
        out += '<span class="ent">' + escapeHtml(chunk) + '</span>';
      } else if (chunk.slice(0, 4) === '<!--') {
        out += '<span class="cmt">' + escapeHtml(chunk) + '</span>';
      } else {
        out += '<span class="br">' + paintTag(chunk) + '</span>';
      }
      last = m.index + chunk.length;
    }
    out += escapeHtml(src.slice(last));
    return out;
  }

  function paintTag(tag) {
    var out = '', last = 0;
    var name = /^<\/?\s*([A-Za-z][\w:-]*)/.exec(tag);
    if (name) {
      var at = tag.indexOf(name[1]);
      out += escapeHtml(tag.slice(0, at));
      out += '<span class="tag">' + escapeHtml(name[1]) + '</span>';
      last = at + name[1].length;
    }
    var re = /([A-Za-z_:][\w:.-]*)\s*=\s*("[^"]*"|'[^']*')/g, m;
    while ((m = re.exec(tag))) {
      if (m.index < last) continue;
      out += escapeHtml(tag.slice(last, m.index));
      out += '<span class="attr">' + escapeHtml(m[1]) + '</span>';
      out += escapeHtml(tag.slice(m.index + m[1].length, re.lastIndex - m[2].length));
      out += '<span class="val">' + escapeHtml(m[2]) + '</span>';
      last = re.lastIndex;
    }
    return out + escapeHtml(tag.slice(last));
  }

  // 内容に合わせて伸ばす。上限は画面の高さぶんまでとし、
  // それを超える欄はその中でスクロールする (際限なく伸ばすと WebView が描画しきれない)
  function fit(ta) {
    var limit = Math.max(320, window.innerHeight);
    ta.style.height = 'auto';
    var wanted = ta.scrollHeight + 2;
    ta.style.height = Math.min(wanted, limit) + 'px';
    var hl = document.getElementById('hl' + ta.dataset.index);
    if (hl) hl.scrollTop = ta.scrollTop;
  }

  function paint(index) {
    var ta = document.getElementById('ta' + index);
    var hl = document.getElementById('hl' + index);
    if (!ta || !hl) return;
    hl.innerHTML = highlight(ta.value) + '\n';
    fit(ta);
    findRefresh(index);
  }

  // 挿入した画像は編集欄の下に小さく並べる
  function thumbs(index) {
    var ta = document.getElementById('ta' + index);
    var box = document.getElementById('th' + index);
    if (!ta || !box) return;
    var names = [], re = /<img[^>]*src="([^"]*)"/g, m;
    while ((m = re.exec(ta.value))) {
      var src = m[1].trim();
      if (src && names.indexOf(src) < 0) names.push(src);
    }
    box.innerHTML = '';
    // 読み込めなかった画像は出さない (src が空・綴り違いなど)
    names.forEach(function (n) {
      var img = document.createElement('img');
      img.onerror = function () { img.remove(); };
      img.src = n;
      box.appendChild(img);
    });
  }

  function notify(index) {
    var ta = document.getElementById('ta' + index);
    clearTimeout(timers[index]);
    // 打鍵のたびに外へ知らせると再描画が増えるので、少し落ち着いてから伝える
    timers[index] = setTimeout(function () {
      if (bridge) bridge.onFieldChanged(index, ta.value);
      thumbs(index);
    }, 180);
  }

  document.querySelectorAll('textarea').forEach(function (ta) {
    var index = parseInt(ta.dataset.index, 10);
    paint(index);
    thumbs(index);

    ta.addEventListener('input', function () { paint(index); notify(index); });
    ta.addEventListener('scroll', function () {
      document.getElementById('hl' + index).scrollTop = ta.scrollTop;
    });
    ta.addEventListener('focus', function () {
      ta.parentNode.classList.add('focus');
      window.__ankiFocused = index;
      if (bridge) bridge.onFocus(index);
    });
    ta.addEventListener('blur', function () {
      ta.parentNode.classList.remove('focus');
      if (bridge) bridge.onBlur();
    });
    // VS Code と同じキー操作
    ta.addEventListener('keydown', function (e) {
      var cmd = e.metaKey || e.ctrlKey;
      // Enter は今の行の字下げを引き継ぐ。Shift+Enter は行頭から
      if (e.key === 'Enter' && !cmd && !e.altKey) {
        e.preventDefault();
        var at = ta.selectionStart, to = ta.selectionEnd, text = ta.value;
        var b = lineBounds(text, at);
        var indent = e.shiftKey ? '' : (text.slice(b[0], b[1]).match(/^[\t ]*/) || [''])[0];
        var inserted = '\n' + indent;
        setValue(ta, text.slice(0, at) + inserted + text.slice(to), at + inserted.length);
        paint(index);
        notify(index);
        return;
      }
      if (cmd || e.altKey) {
        if (applyShortcut(ta, e)) { paint(index); notify(index); }
        return;
      }
      if (e.key !== 'Tab') return;
      e.preventDefault();
      var start = ta.selectionStart, end = ta.selectionEnd;
      if (e.shiftKey) {
        var before = ta.value.slice(0, start);
        var cut = before.endsWith('\t') ? 1 : (before.endsWith('    ') ? 4 : 0);
        if (!cut) return;
        ta.value = ta.value.slice(0, start - cut) + ta.value.slice(start);
        ta.selectionStart = ta.selectionEnd = start - cut;
      } else {
        ta.value = ta.value.slice(0, start) + '\t' + ta.value.slice(end);
        ta.selectionStart = ta.selectionEnd = start + 1;
      }
      paint(index);
      notify(index);
    });
  });

  // ---- VS Code と同じキー操作 ----
  function lineBounds(text, at) {
    var start = text.lastIndexOf('\n', at - 1) + 1;
    var end = text.indexOf('\n', at);
    if (end < 0) end = text.length;
    return [start, end];
  }

  function setValue(ta, text, from, to) {
    ta.value = text;
    ta.selectionStart = from;
    ta.selectionEnd = to === undefined ? from : to;
  }

  function applyShortcut(ta, e) {
    var cmd = e.metaKey || e.ctrlKey, alt = e.altKey, shift = e.shiftKey;
    var text = ta.value, at = ta.selectionStart, to = ta.selectionEnd;
    var b = lineBounds(text, at), start = b[0], end = b[1];
    var line = text.slice(start, end);
    var indent = (line.match(/^[\t ]*/) || [''])[0];

    // ⌘Enter: 下に行を足す / ⌘⇧Enter: 上に行を足す
    if (cmd && e.key === 'Enter') {
      e.preventDefault();
      if (shift) {
        setValue(ta, text.slice(0, start) + indent + '\n' + text.slice(start), start + indent.length);
      } else {
        setValue(ta, text.slice(0, end) + '\n' + indent + text.slice(end), end + 1 + indent.length);
      }
      return true;
    }
    // ⌥↑ / ⌥↓: 行の入れ替え、⇧⌥↑ / ⇧⌥↓: 行の複製
    if (alt && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      e.preventDefault();
      var up = e.key === 'ArrowUp';
      if (shift) {
        setValue(ta, text.slice(0, end) + '\n' + line + text.slice(end),
                 up ? at : end + 1 + (at - start));
        return true;
      }
      if (up) {
        if (start === 0) return false;
        var pb = lineBounds(text, start - 1);
        var prev = text.slice(pb[0], pb[1]);
        setValue(ta, text.slice(0, pb[0]) + line + '\n' + prev + text.slice(end),
                 pb[0] + (at - start));
      } else {
        if (end >= text.length) return false;
        var nb = lineBounds(text, end + 1);
        var next = text.slice(nb[0], nb[1]);
        setValue(ta, text.slice(0, start) + next + '\n' + line + text.slice(nb[1]),
                 start + next.length + 1 + (at - start));
      }
      return true;
    }
    // ⌘⇧K: 行を削除
    if (cmd && shift && (e.key === 'K' || e.key === 'k')) {
      e.preventDefault();
      var cut = end < text.length ? end + 1 : end;
      setValue(ta, text.slice(0, start) + text.slice(cut), start);
      return true;
    }
    // ⌘/: コメントの付け外し
    if (cmd && e.key === '/') {
      e.preventDefault();
      var trimmed = line.trim(), replaced;
      if (trimmed.indexOf('<!--') === 0 && trimmed.lastIndexOf('-->') === trimmed.length - 3) {
        replaced = line.replace('<!--', '').replace(/-->\s*$/, '').trim();
      } else {
        replaced = '<!-- ' + line + ' -->';
      }
      setValue(ta, text.slice(0, start) + replaced + text.slice(end), start + replaced.length);
      return true;
    }
    // ⌘] / ⌘[: 字下げの増減
    if (cmd && (e.key === ']' || e.key === '[')) {
      e.preventDefault();
      var add = e.key === ']';
      var updated = add ? '\t' + line
        : line.replace(/^\t/, '').replace(/^ {4}/, '');
      setValue(ta, text.slice(0, start) + updated + text.slice(end), start + updated.length);
      return true;
    }
    // ⌘L: 行を選ぶ
    if (cmd && (e.key === 'l' || e.key === 'L')) {
      e.preventDefault();
      setValue(ta, text, start, end);
      return true;
    }
    // ⌘⌫: 行頭まで消す
    if (cmd && e.key === 'Backspace') {
      e.preventDefault();
      if (at <= start) return false;
      setValue(ta, text.slice(0, start) + text.slice(at), start);
      return true;
    }
    return false;
  }

  // ---- ノート内検索 ----
  function collect() {
    find.hits = [];
    if (!find.query) return 0;
    var lower = find.query.toLowerCase();
    document.querySelectorAll('textarea').forEach(function (ta) {
      var index = parseInt(ta.dataset.index, 10);
      var value = ta.value.toLowerCase(), from = value.indexOf(lower);
      while (from >= 0) {
        find.hits.push({ field: index, at: from });
        from = value.indexOf(lower, from + 1);
      }
    });
    return find.hits.length;
  }

  // 色分け層に一致箇所の目印を重ねる
  function findRefresh(index) {
    if (!find.query) return;
    var hl = document.getElementById('hl' + index);
    var ta = document.getElementById('ta' + index);
    if (!hl || !ta) return;
    var current = find.hits[find.current];
    var html = '', last = 0, value = ta.value, lower = value.toLowerCase();
    var needle = find.query.toLowerCase(), from = lower.indexOf(needle);
    while (from >= 0) {
      html += highlight(value.slice(last, from));
      var isCurrent = current && current.field === index && current.at === from;
      html += '<mark class="find' + (isCurrent ? ' cur' : '') + '">' +
              highlight(value.substr(from, find.query.length)) + '</mark>';
      last = from + find.query.length;
      from = lower.indexOf(needle, last);
    }
    html += highlight(value.slice(last));
    hl.innerHTML = html + '\n';
  }

  function show(step) {
    if (!find.hits.length) return 0;
    find.current = (find.current + step + find.hits.length) % find.hits.length;
    var hit = find.hits[find.current];
    var ta = document.getElementById('ta' + hit.field);
    ta.focus({ preventScroll: true });
    ta.setSelectionRange(hit.at, hit.at + find.query.length);
    document.querySelectorAll('textarea').forEach(function (t) {
      findRefresh(parseInt(t.dataset.index, 10));
    });
    var mark = document.querySelector('#hl' + hit.field + ' mark.cur');
    if (mark) mark.scrollIntoView({ block: 'center' });
    return find.current + 1;
  }

  window.__ankiSource = {
    search: function (query) {
      find.query = query;
      find.current = -1;
      var total = collect();
      document.querySelectorAll('textarea').forEach(function (t) {
        var i = parseInt(t.dataset.index, 10);
        if (query) findRefresh(i); else paint(i);
      });
      if (total) show(1);
      return total;
    },
    move: function (step) { return show(step); },
    clear: function () {
      find.query = ''; find.hits = []; find.current = -1;
      document.querySelectorAll('textarea').forEach(function (t) {
        paint(parseInt(t.dataset.index, 10));
      });
    },
    index: function () { return find.current + 1; },
    // カーソル位置に差し込む (画像や動画の挿入に使う)
    insert: function (index, text) {
      var ta = document.getElementById('ta' + (index >= 0 ? index : (window.__ankiFocused || 0)));
      if (!ta) return;
      var start = ta.selectionStart, end = ta.selectionEnd;
      ta.value = ta.value.slice(0, start) + text + ta.value.slice(end);
      ta.selectionStart = ta.selectionEnd = start + text.length;
      var i = parseInt(ta.dataset.index, 10);
      paint(i);
      if (bridge) bridge.onFieldChanged(i, ta.value);
      thumbs(i);
    },
    // 外から内容を入れ直す (モードの切り替えなど)
    setValue: function (index, text) {
      var ta = document.getElementById('ta' + index);
      if (!ta || ta.value === text) return;
      ta.value = text;
      paint(index);
      thumbs(index);
    }
  };
})();
</script>
</body></html>
        """.trimIndent()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
