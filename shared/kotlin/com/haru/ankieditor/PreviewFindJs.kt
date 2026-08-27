package com.haru.ankieditor

/**
 * プレビュー (WebView) の中を検索するための JavaScript。
 *
 * 文字を <mark> で包むと、テンプレート側の CSS と干渉して表示が崩れることがあるため、
 * ブラウザの「範囲だけを塗る」仕組み (CSS Custom Highlight) を使い、
 * ページの中身には一切手を触れない。使えない環境では従来どおり包む方式に戻す。
 *
 * search(語) は件数を、move(±1) は現在の番号を返す。
 */
object PreviewFindJs {
    val script: String = """
        window.__ankiFind = (function () {
          var ranges = [], current = -1, marks = [];
          // 範囲だけを塗れるか (最近の Chromium なら使える)
          var canHighlight = typeof CSS !== 'undefined' && CSS.highlights &&
                             typeof Highlight !== 'undefined';

          var style = document.createElement('style');
          style.textContent =
            '::highlight(anki-find) { background: rgba(120,170,255,.45); }' +
            '::highlight(anki-find-current) { background: #f2b705; color: #111; }' +
            'mark.anki-find { background: rgba(120,170,255,.45); color: inherit; }' +
            'mark.anki-find.current { background: #f2b705; color: #111; }';
          document.head.appendChild(style);

          function textNodes() {
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
              acceptNode: function (node) {
                if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                var tag = node.parentNode && node.parentNode.nodeName;
                if (tag === 'SCRIPT' || tag === 'STYLE') return NodeFilter.FILTER_REJECT;
                // 画面に出ていない所は飛ばす
                var el = node.parentElement;
                if (el && el.closest('.anki-find, .anki-bar, .anki-side')) {
                  return NodeFilter.FILTER_REJECT;
                }
                return NodeFilter.FILTER_ACCEPT;
              }
            });
            var out = [], node;
            while ((node = walker.nextNode())) out.push(node);
            return out;
          }

          function clear() {
            if (canHighlight) {
              CSS.highlights.delete('anki-find');
              CSS.highlights.delete('anki-find-current');
            }
            marks.forEach(function (m) {
              var parent = m.parentNode;
              if (!parent) return;
              parent.replaceChild(document.createTextNode(m.textContent), m);
              parent.normalize();
            });
            marks = [];
            ranges = [];
            current = -1;
          }

          function search(query) {
            clear();
            if (!query) return 0;
            var needle = query.toLowerCase();

            if (canHighlight) {
              // 範囲を作って塗るだけ。ページの中身はそのまま
              textNodes().forEach(function (node) {
                var value = node.nodeValue.toLowerCase(), from = value.indexOf(needle);
                while (from >= 0) {
                  var range = document.createRange();
                  range.setStart(node, from);
                  range.setEnd(node, from + query.length);
                  ranges.push(range);
                  from = value.indexOf(needle, from + query.length);
                }
              });
              paint();
            } else {
              // 古い環境向け: 一致箇所を包んで色を付ける
              textNodes().forEach(function (node) {
                var value = node.nodeValue;
                var from = value.toLowerCase().indexOf(needle), offset = 0;
                if (from < 0) return;
                var frag = document.createDocumentFragment();
                while (from >= 0) {
                  frag.appendChild(document.createTextNode(value.slice(offset, from)));
                  var mark = document.createElement('mark');
                  mark.className = 'anki-find';
                  mark.textContent = value.substr(from, query.length);
                  frag.appendChild(mark);
                  marks.push(mark);
                  offset = from + query.length;
                  from = value.toLowerCase().indexOf(needle, offset);
                }
                frag.appendChild(document.createTextNode(value.slice(offset)));
                node.parentNode.replaceChild(frag, node);
              });
            }

            var total = canHighlight ? ranges.length : marks.length;
            if (total) move(1);
            return total;
          }

          function paint() {
            if (!canHighlight) return;
            var others = [], now = [];
            ranges.forEach(function (range, index) {
              if (index === current) now.push(range); else others.push(range);
            });
            var rest = new Highlight();
            others.forEach(function (range) { rest.add(range); });
            CSS.highlights.set('anki-find', rest);
            if (now.length) CSS.highlights.set('anki-find-current', new Highlight(now[0]));
            else CSS.highlights.delete('anki-find-current');
          }

          function move(step) {
            var total = canHighlight ? ranges.length : marks.length;
            if (!total) return 0;
            if (!canHighlight && current >= 0 && marks[current]) {
              marks[current].classList.remove('current');
            }
            current = (current + step + total) % total;
            if (canHighlight) {
              paint();
              var rect = ranges[current].getBoundingClientRect();
              var target = window.scrollY + rect.top - window.innerHeight / 2;
              window.scrollTo({ top: Math.max(0, target), behavior: 'auto' });
            } else {
              var mark = marks[current];
              mark.classList.add('current');
              mark.scrollIntoView({ block: 'center' });
            }
            return current + 1;
          }

          return {
            search: search,
            move: move,
            clear: clear,
            index: function () { return current + 1; },
            total: function () { return canHighlight ? ranges.length : marks.length; }
          };
        })();
    """.trimIndent()
}
