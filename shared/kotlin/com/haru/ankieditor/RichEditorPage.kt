package com.haru.ankieditor

import org.json.JSONObject

/**
 * リッチテキスト (contenteditable) 編集ページ。
 * Android と macOS で同じものを使う。bridge は JavaScript から呼べる窓口の名前。
 */
object RichEditorPage {

/** contenteditable なフィールド一覧の HTML を組み立てる */
    fun build(fieldNames: List<String>, values: List<String>, bridge: String): String {
        val fieldsHtml = fieldNames.mapIndexed { i, name ->
            val value = values.getOrElse(i) { "" }
            """
            <div class="field-wrap">
              <div class="label">${escapeText(name)}</div>
              <div class="field" contenteditable="true" data-index="$i"
                   oninput="handleMarkdown(); ${bridge}.onFieldChanged($i, this.innerHTML)"
                   onfocus="${bridge}.onFocus($i)"
                   onblur="${bridge}.onBlur()">$value</div>
            </div>
            """.trimIndent()
        }.joinToString("\n")
    
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body {
                background: #1C1C1C; color: #F0F0F0; margin: 0; padding: 12px;
                font-family: sans-serif; font-size: 14px; line-height: 1.6;
              }
              .field-wrap { margin-bottom: 14px; }
              .label { font-size: 11px; color: #9AA0A6; margin-bottom: 4px; }
              .field {
                min-height: 2.4em; padding: 8px 10px;
                border: 1px solid #4A4A4A; border-radius: 8px;
                background: #262626; outline: none;
                overflow-wrap: anywhere;
              }
              .field:focus { border-color: #8AB4F8; }
              .field img { max-width: 100%; }
              .field img:not([class]), .field img[class=""] { display: block; }
              .field img.round { border-radius: 8px; }
              .field h1, .field h2, .field h3, .field h4 { margin: .4em 0; }
              .field blockquote {
                margin: .4em 0; padding-left: .8em; border-left: 3px solid #7e7e7e;
              }
              .field code {
                background: #3a3a3a; border-radius: 4px; padding: 0 .3em;
                font-family: monospace;
              }
              .rounded-text { padding: .2em .4em; border-radius: 5px; background: #45474a; }
            </style>
            <script>
        // いまの内容をすぐ外へ伝える (保存の直前に呼ぶ)
        window.__ankiFlush = function () {
          document.querySelectorAll('.field').forEach(function (field) {
            var i = parseInt(field.dataset.index, 10);
            if (window.${bridge}) window.${bridge}.onFieldChanged(i, field.innerHTML);
          });
        };
            // Chromium (macOS 版) では窓口が注入されないので、同じ名前で用意する
            if (!window.${bridge}) {
              window.${bridge} = {
                send: function (text) {
                  if (window.cefQuery) {
                    window.cefQuery({
                      request: text, onSuccess: function () {}, onFailure: function () {}
                    });
                  }
                },
                onFieldChanged: function (i, v) { this.send('field:' + i + ':' + v); },
                onFocus: function (i) { this.send('focus:' + i); },
                onBlur: function () { this.send('blur'); }
              };
            }
        // Chromium (macOS 版) では注入されないので、同じ名前の窓口をここで作る
        if (!window.${bridge}) {
          window.${bridge} = {
            send: function (text) {
              if (window.cefQuery) {
                window.cefQuery({ request: text, onSuccess: function () {}, onFailure: function () {} });
              }
            },
            onFieldChanged: function (i, v) { this.send('field:' + i + ':' + v); },
            onFocus: function (i) { this.send('focus:' + i); },
            onBlur: function () { this.send('blur'); }
          };
        }
              function setCaret(node, off) {
                var r = document.createRange();
                r.setStart(node, off); r.collapse(true);
                var s = window.getSelection();
                s.removeAllRanges(); s.addRange(r);
              }
              function esc(t) {
                return t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
              }
    
              /** 入力中に Markdown 記法をその場で変換する */
              function handleMarkdown() {
                var sel = window.getSelection();
                if (!sel || !sel.rangeCount) return;
                var range = sel.getRangeAt(0);
                var node = range.startContainer;
                if (node.nodeType !== 3) return;
                var text = node.textContent;
                var offset = range.startOffset;
                var before = text.slice(0, offset);
    
                var blocks = [[/^#\s${'$'}/, 'h1'], [/^##\s${'$'}/, 'h2'], [/^###\s${'$'}/, 'h3'],
                              [/^####\s${'$'}/, 'h4'], [/^>\s${'$'}/, 'blockquote']];
                for (var i = 0; i < blocks.length; i++) {
                  if (blocks[i][0].test(before)) {
                    node.textContent = text.slice(offset);
                    setCaret(node, 0);
                    document.execCommand('formatBlock', false, blocks[i][1]);
                    return;
                  }
                }
                if (/^[-*]\s${'$'}/.test(before)) {
                  node.textContent = text.slice(offset);
                  setCaret(node, 0);
                  document.execCommand('insertUnorderedList');
                  return;
                }
                if (/^\d+\.\s${'$'}/.test(before)) {
                  node.textContent = text.slice(offset);
                  setCaret(node, 0);
                  document.execCommand('insertOrderedList');
                  return;
                }
    
                var inlines = [[/\*\*([^*]+)\*\*${'$'}/, 'b'], [/(?:^|[^*])\*([^*]+)\*${'$'}/, 'i'],
                               [/~~([^~]+)~~${'$'}/, 's'], [/`([^`]+)`${'$'}/, 'code']];
                for (var j = 0; j < inlines.length; j++) {
                  var m = before.match(inlines[j][0]);
                  if (m) {
                    var marker = m[0].indexOf('*') === 0 || m[0].indexOf('~') === 0 ||
                                 m[0].indexOf('`') === 0 ? m[0] : m[0].slice(1);
                    var start = offset - marker.length;
                    var r = document.createRange();
                    r.setStart(node, start); r.setEnd(node, offset);
                    sel.removeAllRanges(); sel.addRange(r);
                    document.execCommand(
                      'insertHTML', false,
                      '<' + inlines[j][1] + '>' + esc(m[1]) + '</' + inlines[j][1] + '>'
                    );
                    return;
                  }
                }
              }
    
              window.AnkiEditor = {
                appendTo: function (index, html) {
                  var f = document.querySelector('.field[data-index="' + index + '"]');
                  if (!f) return;
                  f.innerHTML += html;
                  ${bridge}.onFieldChanged(index, f.innerHTML);
                },
                /** 選択範囲を指定タグで包む */
                wrap: function (open, close) {
                  var sel = window.getSelection();
                  if (!sel || sel.rangeCount === 0) return;
                  var html = '';
                  var container = document.createElement('div');
                  container.appendChild(sel.getRangeAt(0).cloneContents());
                  html = container.innerHTML;
                  document.execCommand('insertHTML', false, open + html + close);
                  notifyFocused();
                },
                exec: function (cmd, value) {
                  document.execCommand(cmd, false, value === undefined ? null : value);
                  notifyFocused();
                }
              };
    
              function notifyFocused() {
                var f = document.activeElement;
                if (f && f.classList && f.classList.contains('field')) {
                  ${bridge}.onFieldChanged(parseInt(f.dataset.index), f.innerHTML);
                }
              }
    
              document.addEventListener('selectionchange', function () {
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
                  ${bridge}.onSelectionCleared();
                  return;
                }
                var r = sel.getRangeAt(0).getBoundingClientRect();
                ${bridge}.onSelection(r.left, r.top, r.width, r.height);
              });
            </script>
            </head>
            <body>
            $fieldsHtml
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeText(s: String): String =
        JSONObject.quote(s).let { it.substring(1, it.length - 1) }
}
