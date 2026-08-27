package com.haru.ankieditor.desktop

/**
 * 描画済みのカード本文を、Android 版と同じ体裁の 1 枚の HTML に包む。
 * 追加している上書き CSS も Android 版と同一にして、見え方をそろえている。
 */
object CardHtml {

    /** メディアを配るローカルサーバーの URL。起動時に差し替える */
    @Volatile
    var mediaBaseUrl: String = "http://127.0.0.1/"

    /**
     * カードの上に浮かべる操作 (追加・検索・編集・更新) をページの中に作る。
     *
     * ページを OS に描かせる方式では、その上に Compose の部品を重ねられない。
     * ボタンと検索バーをページ自身に持たせることで、見た目は Android 版のまま、
     * 動きは OS 任せの滑らかさを両立させている。
     */
    fun overlay(
        canEdit: Boolean,
        canAdd: Boolean,
        canFind: Boolean,
        canRefresh: Boolean,
        canShowList: Boolean = false,
    ): String {
        fun button(id: String, title: String, kind: String, svg: String, shown: Boolean) =
            if (!shown) "" else
                """<button class="anki-fab $kind" id="$id" title="$title">$svg</button>"""

        val plus = """<svg viewBox="0 0 24 24"><path d="M11 5h2v14h-2z"/><path d="M5 11h14v2H5z"/></svg>"""
        val glass = """<svg viewBox="0 0 24 24"><path d="M10 4a6 6 0 1 0 3.5 10.9l4.8 4.8 1.4-1.4-4.8-4.8A6 6 0 0 0 10 4zm0 2a4 4 0 1 1 0 8 4 4 0 0 1 0-8z"/></svg>"""
        val pencil = """<svg viewBox="0 0 24 24"><path d="M3 17.2V21h3.8L17.8 10 14 6.2 3 17.2zM20.7 7.0a1 1 0 0 0 0-1.4l-2.3-2.3a1 1 0 0 0-1.4 0l-1.8 1.8L18.9 8.8l1.8-1.8z"/></svg>"""
        val reload = """<svg viewBox="0 0 24 24"><path d="M12 6V3L8 7l4 4V8a4 4 0 1 1-4 4H6a6 6 0 1 0 6-6z"/></svg>"""

        return """
        <style>
          .anki-bar {
            position: fixed; right: 16px; bottom: 16px; display: flex; gap: 12px;
            align-items: center; z-index: 2147483000;
          }
          .anki-fab {
            width: 48px; height: 48px; border: 0; border-radius: 50%; cursor: pointer;
            display: flex; align-items: center; justify-content: center;
            box-shadow: 0 3px 10px rgba(0,0,0,.35); transition: filter .15s ease;
          }
          .anki-fab:hover { filter: brightness(1.12); }
          .anki-fab svg { width: 21px; height: 21px; }
          .anki-fab.add { background: #1E496F; } .anki-fab.add svg { fill: #D2E4FF; }
          .anki-fab.find { background: #3C4858; } .anki-fab.find svg { fill: #D8E3F8; }
          .anki-fab.edit { background: #3C4858; } .anki-fab.edit svg { fill: #D8E3F8; }
          .anki-fab.refresh { background: #234C56; }
          .anki-fab.refresh svg { fill: #BFE9F6; }

          .anki-side {
            /* macOS のウインドウボタン (信号機) の右に並べる */
            position: fixed; top: 12px; left: 86px; width: 30px; height: 30px;
            border: 0; border-radius: 8px; cursor: pointer; z-index: 2147483000;
            background: rgba(60,72,88,.92); color: #D8E3F8; font-size: 17px; line-height: 1;
            box-shadow: 0 2px 8px rgba(0,0,0,.3);
          }
          .anki-side:hover { filter: brightness(1.15); }

          .anki-find {
            position: fixed; top: 12px; left: 50%;
            display: flex; align-items: center; gap: 6px; z-index: 2147483000;
            /* 閉じているときは画面の上へ隠しておき、開くと降りてくる */
            transform: translate(-50%, -160%); opacity: 0; pointer-events: none;
            transition: transform .24s cubic-bezier(.2,.8,.2,1), opacity .18s ease;
            background: #272B2F; color: #E1E2E8; border-radius: 50px; height: 52px;
            padding: 0 6px 0 16px; box-shadow: 0 8px 24px rgba(0,0,0,.45);
            font-family: -apple-system, BlinkMacSystemFont, sans-serif;
          }
          .anki-find.open { transform: translate(-50%, 0); opacity: 1; pointer-events: auto; }
          .anki-find input {
            background: transparent; border: 0; outline: none; color: #E1E2E8;
            font-size: 14px; width: 180px;
          }
          .anki-find .count { font-size: 12px; color: #C1C7CE; min-width: 34px; text-align: center; }
          .anki-find button {
            width: 34px; height: 34px; border: 0; border-radius: 50%; background: transparent;
            color: #E1E2E8; cursor: pointer; font-size: 15px;
          }
          .anki-find button:hover { background: rgba(255,255,255,.08); }
        </style>
        <div class="anki-find" id="ankiFind">
          <span style="opacity:.6">⌕</span>
          <input id="ankiFindInput" placeholder="カード内を検索">
          <span class="count" id="ankiFindCount"></span>
          <button id="ankiFindPrev">‹</button>
          <button id="ankiFindNext">›</button>
          <button id="ankiFindClose">✕</button>
        </div>
        ${
            if (!canShowList) "" else
                """<button class="anki-side" id="ankiShowList" title="一覧を表示">›</button>"""
        }
        <div class="anki-bar">
          ${button("ankiRefresh", "プレビューを更新", "refresh", reload, canRefresh)}
          ${button("ankiAdd", "新しいノート", "add", plus, canAdd)}
          ${button("ankiFindOpen", "このカードを検索", "find", glass, canFind)}
          ${button("ankiEdit", "編集", "edit", pencil, canEdit)}
        </div>
        <script>
        (function () {
          function tell(what) {
            if (window.cefQuery) {
              window.cefQuery({ request: what, onSuccess: function () {}, onFailure: function () {} });
            }
          }
          var byId = document.getElementById.bind(document);
          var side = byId('ankiShowList');
          if (side) side.addEventListener('click', function () { tell('ui:showlist'); });
          ['Add', 'Edit', 'Refresh'].forEach(function (name) {
            var el = byId('anki' + name);
            if (el) el.addEventListener('click', function () { tell('ui:' + name.toLowerCase()); });
          });

          var bar = byId('ankiFind'), input = byId('ankiFindInput'), count = byId('ankiFindCount');
          function show(total) {
            count.textContent = input.value ? (window.__ankiFind.index() + '/' + total) : '';
          }
          function openFind() {
            bar.classList.add('open');
            input.focus();
            input.select();
          }
          window.__ankiFindOpen = openFind;
          var open = byId('ankiFindOpen');
          if (open) open.addEventListener('click', openFind);
          // Esc で閉じる / ⌘F でも開ける
          document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
              bar.classList.remove('open');
              input.value = '';
              count.textContent = '';
              window.__ankiFind.clear();
            } else if ((e.metaKey || e.ctrlKey) && (e.key === 'f' || e.key === 'F')) {
              e.preventDefault();
              openFind();
            }
          });
          byId('ankiFindClose').addEventListener('click', function () {
            bar.classList.remove('open');
            input.value = '';
            count.textContent = '';
            window.__ankiFind.clear();
          });
          input.addEventListener('input', function () {
            show(window.__ankiFind.search(input.value));
          });
          byId('ankiFindPrev').addEventListener('click', function () {
            window.__ankiFind.move(-1); show(document.querySelectorAll('mark.anki-find').length);
          });
          byId('ankiFindNext').addEventListener('click', function () {
            window.__ankiFind.move(1); show(document.querySelectorAll('mark.anki-find').length);
          });
        })();
        </script>
        """.trimIndent()
    }

    fun wrap(body: String, css: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <base href="$mediaBaseUrl">
        <script src="${mediaBaseUrl}__jquery.js"></script>
        <script>
        MathJax = {
          tex: { inlineMath: [['\\(', '\\)']], displayMath: [['\\[', '\\]']] },
          startup: { typeset: true }
        };
        </script>
        <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js" async></script>
        <style>
        $css
        </style>
        <style>
        /* ノートタイプの CSS の後に置く上書き (Android 版と同じ) */
        a { overflow-wrap: anywhere; word-break: break-word; }
        img:not([class]), img[class=""] { display: block; }
        img, table, pre, iframe, video { max-width: 100%; }
        body { overflow-x: hidden; }
        </style>
        </head>
        <body class="card">
        $body
        </body>
        </html>
    """.trimIndent()

    /**
     * 本文だけを差し替えるスクリプト。
     * 読み込み直すとスクロール位置が戻ってしまうので、編集中はこちらを使う。
     */
    fun replaceBodyScript(html: String): String {
        val body = html.substringAfter("<body class=\"card\">", "").substringBeforeLast("</body>")
        val json = org.json.JSONObject.quote(body)
        return """
            (function () {
              document.body.innerHTML = $json;
              document.body.querySelectorAll('script').forEach(function (old) {
                var s = document.createElement('script');
                if (old.src) { s.src = old.src; } else { s.textContent = old.textContent; }
                old.parentNode.replaceChild(s, old);
              });
              if (window.MathJax && MathJax.typesetPromise) { MathJax.typesetPromise(); }
            })();
        """.trimIndent()
    }
}
