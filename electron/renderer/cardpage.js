// プレビューに流し込むページ。
// カード内検索は CSS Custom Highlight API を使い、DOM を書き換えない
// (書き換えると MathJax や画像が作り直されて、検索した文字が消えることがある)。

export function cardPage(rendered, extraCss = '') {
  const body = rendered.body || '';
  // どちらもアプリに同梱したものを読む (外の回線に頼らない)。
  // 必要なカードのときだけ読み込む
  const needsMath = /anki-mathjax|\\\(|\\\[|\$\$/.test(body);
  const needsJquery = /<script/i.test(body);
  // jQuery は本文の組み立てで使われることがあるので先に読む。
  // MathJax は重いので、本文を出したあと (defer) に回す
  const libs = needsJquery ? '<script src="mjx://jquery/jquery.min.js"><\/script>' : '';
  const mathLib = needsMath
    ? '<script src="mjx://mathjax/tex-chtml.js" defer><\/script>'
    : '';

  return `<!doctype html><html><head><meta charset="utf-8">
<base href="media://media/">
${libs}
<style>${rendered.css || ''}</style>
<style>
  body { overflow-x: hidden; }
  /* スクロールの棒は出さない (見た目を邪魔しないため) */
  ::-webkit-scrollbar { width: 0; height: 0; }
  html { scrollbar-width: none; }
  /* プレビュー全体の余白をほんの少し広げる */
  body.card { border: 6px solid transparent; border-left-width: 10px; border-right-width: 10px; }
  img, table, pre, iframe, video { max-width: 100%; }
  ::highlight(anki-find) { background: rgba(120,170,255,.45); }
  ::highlight(anki-find-current) { background: #f2b705; color: #111; }
  .anki-find {
    position: fixed; top: 12px; left: 50%; max-width: 80%; box-sizing: border-box;
    display: flex; align-items: center; gap: 6px; z-index: 2147483000;
    transform: translate(-50%, -160%); opacity: 0; pointer-events: none;
    transition: transform .24s cubic-bezier(.2,.8,.2,1), opacity .18s ease;
    background: #272B2F; color: #E1E2E8; border-radius: 50px; height: 52px;
    padding: 0 6px 0 16px; box-shadow: 0 8px 24px rgba(0,0,0,.45);
    font-family: -apple-system, BlinkMacSystemFont, sans-serif;
  }
  .anki-find.open { transform: translate(-50%, 0); opacity: 1; pointer-events: auto; }
  .anki-find input {
    background: transparent; border: 0; outline: none; color: #E1E2E8;
    font-size: 14px; width: 180px; min-width: 0; flex: 1 1 auto;
  }
  .anki-find .count { font-size: 12px; color: #C1C7CE; min-width: 34px; text-align: center; flex: 0 0 auto; }
  .anki-find button {
    width: 34px; height: 34px; border: 0; border-radius: 50%; background: transparent;
    color: #E1E2E8; cursor: pointer; font-size: 15px; flex: 0 0 auto;
  }
  .anki-find button:hover { background: rgba(255,255,255,.08); }
  ${extraCss}
</style></head>
<body class="card">
<script>window.__pageErrors=[];window.addEventListener('error',function(e){window.__pageErrors.push(e.message+' @'+e.lineno);});<\/script>
<div id="ankiContent">${rendered.body}</div>
${mathLib}
<div class="anki-find" id="find">
  <input id="findInput" placeholder="カード内を検索">
  <span class="count" id="findCount"></span>
  <button id="findPrev">‹</button><button id="findNext">›</button><button id="findClose">✕</button>
</div>
<script>
// Anki の数式は <anki-mathjax>…</anki-mathjax> で入っている。
// MathJax が読める書き方に直してから組版させる
(function () {
  // 数式は MathJax の用意ができてから組む
  function typeset() {
    if (!window.MathJax) return;
    // 数式の組版は重い。画面を先に出してから、手が空いたところで行う
    var run = window.requestIdleCallback || function (fn) { setTimeout(fn, 200); };
    if (window.MathJax.startup && window.MathJax.startup.promise) {
      window.MathJax.startup.promise.then(function () {
        run(function () {
          if (window.MathJax.typesetPromise) window.MathJax.typesetPromise();
        });
      });
    } else if (window.MathJax.typesetPromise) {
      run(function () { window.MathJax.typesetPromise(); });
    }
  }

  // 箇条書きの中で改行やタブを入れて書くと、その空白が 1 文字ぶんの隙間として出る。
  // (・ や ▶︎ などの目印と本文の間に、余分な空きができる)
  // 目印が付いたときと同じ見た目になるよう、行頭・行末の空白だけ取り除く
  function tidyLists(root) {
    var list = (root || document).querySelectorAll('li');
    for (var i = 0; i < list.length; i++) {
      var li = list[i];
      var head = li.firstChild;
      var tail = li.lastChild;
      if (head && head.nodeType === 3) head.nodeValue = head.nodeValue.replace(/^[ \\t\\r\\n]+/, '');
      if (tail && tail.nodeType === 3) tail.nodeValue = tail.nodeValue.replace(/[ \\t\\r\\n]+$/, '');
    }
  }

  function toTex() {
    var nodes = document.querySelectorAll('anki-mathjax');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      var tex = node.textContent || '';
      var block = node.getAttribute('block') === 'true';
      var span = document.createElement('span');
      // MathJax の区切り記号。テンプレートの中で書くと逆斜線が消えてしまうので、
      // 文字コードから作る
      var bs = String.fromCharCode(92);
      span.textContent = block
        ? bs + '[' + tex + bs + ']'
        : bs + '(' + tex + bs + ')';
      node.parentNode.replaceChild(span, node);
    }
    if (nodes.length && window.MathJax && window.MathJax.typesetPromise) {
      window.MathJax.typesetPromise();
    }
    return nodes.length;
  }
  tidyLists();
  toTex();
  typeset();
  // 画像をクリックしたら、親の画面で拡大して見せる (リンクの中の画像は除く)
  document.addEventListener('click', function (e) {
    var img = e.target && e.target.closest ? e.target.closest('img') : null;
    if (!img) return;
    var link = img.closest('a');
    if (link && !/[.](png|jpe?g|gif|webp|avif|svg)([?]|$)/i.test(link.getAttribute('href') || '')) return;
    var src = img.currentSrc || img.src;
    if (!src) return;
    e.preventDefault();
    window.postMessage({ type: 'anki-host', channel: 'open-image', payload: src }, '*');
  }, true);
  // 親から本文だけ差し替えてもらう (読み込み直さないので、画面が一瞬消えない)
  window.__ankiOpenFind = function () {
    var b = document.getElementById('find');
    var input2 = document.getElementById('findInput');
    if (b) { b.classList.add('open'); if (input2) { input2.focus(); input2.select(); } }
  };
  // innerHTML で入れた <script> は動かない決まりなので、入れ直して走らせる
  function runScripts(box) {
    var list = box.querySelectorAll('script');
    for (var i = 0; i < list.length; i++) {
      var old = list[i];
      var tag = document.createElement('script');
      for (var k = 0; k < old.attributes.length; k++) {
        tag.setAttribute(old.attributes[k].name, old.attributes[k].value);
      }
      tag.text = old.textContent || '';
      old.parentNode.replaceChild(tag, old);
    }
  }

  window.__ankiSetBody = function (html) {
    var box = document.getElementById('ankiContent');
    if (!box) return;
    box.innerHTML = html;
    tidyLists(box);
    runScripts(box);
    var count = toTex();
    if (!count) return;
    // あとから数式が書かれた場合は、その時点で MathJax を読み込む
    if (!window.MathJax && !window.__ankiMathLoading) {
      window.__ankiMathLoading = true;
      var tag = document.createElement('script');
      tag.src = 'mjx://mathjax/tex-chtml.js';
      tag.onload = typeset;
      document.head.appendChild(tag);
      return;
    }
    typeset();
  };
  // MathJax はあとから読み込まれるので、その時点でもう一度組み直す
  window.addEventListener('load', function () {
    if (window.MathJax && window.MathJax.typesetPromise) window.MathJax.typesetPromise();
  });
})();

(function () {
  var bar = document.getElementById('find');
  var input = document.getElementById('findInput');
  var count = document.getElementById('findCount');
  var hits = [], current = -1;

  function textNodes() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        if (bar.contains(n)) return NodeFilter.FILTER_REJECT;
        return n.nodeValue.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    var out = [], n;
    while ((n = walker.nextNode())) out.push(n);
    return out;
  }

  function search(query) {
    hits = []; current = -1;
    if (window.CSS && CSS.highlights) CSS.highlights.clear();
    if (!query) { count.textContent = ''; return; }
    var needle = query.toLowerCase();
    textNodes().forEach(function (node) {
      var text = node.nodeValue.toLowerCase(), from = text.indexOf(needle);
      while (from >= 0) {
        var range = document.createRange();
        range.setStart(node, from);
        range.setEnd(node, from + query.length);
        hits.push(range);
        from = text.indexOf(needle, from + query.length);
      }
    });
    paint();
    if (hits.length) move(1); else count.textContent = '0/0';
  }

  function paint() {
    if (!window.CSS || !CSS.highlights) return;
    CSS.highlights.set('anki-find', new Highlight(...hits.filter(function (_, i) { return i !== current; })));
    if (current >= 0) CSS.highlights.set('anki-find-current', new Highlight(hits[current]));
  }

  function move(step) {
    if (!hits.length) return;
    current = (current + step + hits.length) % hits.length;
    paint();
    count.textContent = (current + 1) + '/' + hits.length;
    var rect = hits[current].getBoundingClientRect();
    window.scrollBy(0, rect.top - window.innerHeight / 2);
  }

  function open() { bar.classList.add('open'); input.focus(); input.select(); }
  function close() {
    bar.classList.remove('open'); input.value = ''; search('');
  }

  input.addEventListener('input', function () { search(input.value); });
  input.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); move(e.shiftKey ? -1 : 1); }
    if (e.key === 'ArrowRight') { e.preventDefault(); move(1); }
    if (e.key === 'ArrowLeft') { e.preventDefault(); move(-1); }
    if (e.key === 'Escape') close();
  });
  document.getElementById('findPrev').onclick = function () { move(-1); };
  document.getElementById('findNext').onclick = function () { move(1); };
  document.getElementById('findClose').onclick = close;
  document.addEventListener('keydown', function (e) {
    // 写す: メニューが拾えない経路 (合成した打鍵など) でもこの中で効かせる
    if ((e.metaKey || e.ctrlKey) && (e.key === 'c' || e.key === 'C') && !e.shiftKey) {
      var picked = String(getSelection ? getSelection() : '');
      if (picked) { e.preventDefault(); document.execCommand('copy'); }
      return;
    }
    if ((e.metaKey || e.ctrlKey) && (e.key === 'f' || e.key === 'F')) { e.preventDefault(); open(); }
    if ((e.metaKey || e.ctrlKey) && (e.key === 's' || e.key === 'S')) {
      e.preventDefault();
      parent.postMessage('save', '*');
    }
    if (e.key === 'Escape') close();
  });
  // カードの地色を親へ伝える (幅が変わる一瞬、同じ色を後ろに敷いておくため)
  function tellBackground() {
    var bg = getComputedStyle(document.body).backgroundColor;
    if (!bg || bg === 'rgba(0, 0, 0, 0)') bg = getComputedStyle(document.documentElement).backgroundColor;
    parent.postMessage({ type: 'cardBackground', color: bg }, '*');
  }
  tellBackground();
  window.addEventListener('load', tellBackground);

  // 親から「検索を開いて」と言われたときのため
  window.addEventListener('message', function (e) {
    if (e.data === 'find') { open(); return; }
    if (e.data && e.data.type === 'body' && window.__ankiSetBody) {
      window.__ankiSetBody(e.data.html);
    }
  });
})();
<\/script>
</body></html>`;
}
