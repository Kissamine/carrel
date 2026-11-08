import Foundation

/// Anki のテンプレート ({{欄}} / {{#欄}}…{{/欄}} / {{^欄}}…{{/欄}} / {{FrontSide}} / {{hint:欄}} / {{text:欄}}) を
/// 本文の HTML に組み立てる。プレビュー用なので cloze や特殊フィルタは素通し
enum TemplateRenderer {
    static func render(note: Note, notetype: Notetype, templateIndex: Int = 0) -> (front: String, back: String) {
        guard templateIndex < notetype.templates.count else { return ("", "") }
        let tpl = notetype.templates[templateIndex]
        var values: [String: String] = [:]
        for (i, name) in notetype.fieldNames.enumerated() { values[name] = i < note.fields.count ? note.fields[i] : "" }
        values["Tags"] = note.tags.joined(separator: " ")
        values["Type"] = notetype.name
        values["Card"] = tpl.name
        let front = expand(tpl.front, values: values)
        values["FrontSide"] = front
        let back = expand(tpl.back, values: values)
        return (front, back)
    }

    static func expand(_ template: String, values: [String: String]) -> String {
        var out = template
        // 条件 {{#X}}…{{/X}} と {{^X}}…{{/X}} を、内側から順に解決する (入れ子に強い順番)
        let cond = try! NSRegularExpression(pattern: "\\{\\{([#^])([^}]+)\\}\\}((?:(?!\\{\\{[#^]).)*?)\\{\\{/\\2\\}\\}", options: [.dotMatchesLineSeparators])
        var guardCount = 0
        while guardCount < 200 {
            guardCount += 1
            let ns = out as NSString
            guard let m = cond.firstMatch(in: out, range: NSRange(location: 0, length: ns.length)) else { break }
            let kind = ns.substring(with: m.range(at: 1))
            let name = ns.substring(with: m.range(at: 2)).trimmingCharacters(in: .whitespaces)
            let body = ns.substring(with: m.range(at: 3))
            let has = !(values[name] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let keep = (kind == "#") ? has : !has
            out = ns.replacingCharacters(in: m.range, with: keep ? body : "")
        }
        // 置き換え {{欄}} / {{text:欄}} / {{hint:欄}} / {{cloze:欄}}
        let field = try! NSRegularExpression(pattern: "\\{\\{([^#^/}][^}]*)\\}\\}")
        let ns = out as NSString
        var result = ""
        var last = 0
        for m in field.matches(in: out, range: NSRange(location: 0, length: ns.length)) {
            result += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let raw = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            let parts = raw.components(separatedBy: ":")
            let name = parts.last!.trimmingCharacters(in: .whitespaces)
            let filters = parts.dropLast().map { $0.trimmingCharacters(in: .whitespaces) }
            var v = values[name] ?? ""
            if filters.contains("text") { v = Note.stripHTML(v) }
            if filters.contains("hint"), !v.isEmpty {
                v = "<a class=\"hint\" href=\"#\" onclick=\"this.style.display='none';document.getElementById('hint-\(name.hashValue & 0xffff)').style.display='block';return false;\">\(name)</a>" +
                    "<div id=\"hint-\(name.hashValue & 0xffff)\" class=\"hint\" style=\"display:none\">\(v)</div>"
            }
            result += v
            last = m.range.location + m.range.length
        }
        result += ns.substring(from: last)
        return result
    }

    /// プレビューのページ全体。macOS 版と同じ下地 (箇条書きの余分な空白の除去、数式の組版) に加えて、
    /// 夜間モードの既定色・語のハイライト・画像タップの通知を入れる
    static func page(body: String, css: String, textZoom: Double, dark: Bool, hasNightCSS: Bool) -> String {
        let bg = dark ? "#1C1C1E" : "#FFFFFF"
        let fg = dark ? "#E4E2E6" : "#111111"
        // ノートタイプが夜間モードの色を持たないときは、body/.card の地色だけ暗い色で上書きする
        let nightOverride = (dark && !hasNightCSS) ? """
        html.night_mode body, html.night_mode .card { background: \(bg) !important; color: \(fg) !important; }
        html.night_mode a { color: #6EA8FE; }
        """ : ""
        // テンプレートに script があれば、Anki 本体と同じく jQuery を先に用意する (macOS 版と同じ判断)
        let libs = body.range(of: "<script", options: .caseInsensitive) != nil ? "<script src=\"__jquery.min.js\"></script>" : ""
        return """
        <!doctype html><html class="\(dark ? "night_mode nightMode" : "")"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <meta name="color-scheme" content="\(dark ? "dark" : "light")">
        \(libs)
        <style>
        html { font-size: \(Int(100 * textZoom))%; }
        body { margin: 0; padding: 14px 16px calc(14px + env(safe-area-inset-bottom)); background: \(bg); color: \(fg); -webkit-text-size-adjust: 100%; overflow-wrap: break-word; }
        img, table, pre, iframe, video { max-width: 100%; }
        mark.__hl { background: rgba(255, 214, 0, 0.55); color: inherit; border-radius: 3px; padding: 0 1px; }
        mark.__hl.__cur { background: #FF9F0A; color: #000; }
        \(css)
        \(nightOverride)
        </style></head>
        <body class="card \(dark ? "night_mode nightMode" : "")">
        <div id="ankiContent">\(body)</div>
        <script>
        (function () {
          // 箇条書きの行頭・行末の空白を取り除く (目印との間に余分な空きが出ないように)
          var list = document.querySelectorAll('li');
          for (var i = 0; i < list.length; i++) {
            var li = list[i], a = li.firstChild, b = li.lastChild;
            if (a && a.nodeType === 3) a.nodeValue = a.nodeValue.replace(/^[ \\t\\r\\n]+/, '');
            if (b && b.nodeType === 3) b.nodeValue = b.nodeValue.replace(/[ \\t\\r\\n]+$/, '');
          }
          // <anki-mathjax> を MathJax の区切りに直す
          var nodes = document.querySelectorAll('anki-mathjax');
          var bs = String.fromCharCode(92);
          for (var k = 0; k < nodes.length; k++) {
            var n = nodes[k], span = document.createElement('span');
            var block = n.getAttribute('block') === 'true';
            span.textContent = block ? bs + '[' + n.textContent + bs + ']' : bs + '(' + n.textContent + bs + ')';
            n.parentNode.replaceChild(span, n);
          }
          if (nodes.length || /\\\\\\(|\\\\\\[/.test(document.body.innerHTML)) {
            var s = document.createElement('script');
            s.src = 'https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js';
            document.head.appendChild(s);
          }
          // 画像をタップしたらアプリに知らせる (拡大表示)
          document.addEventListener('click', function (e) {
            var t = e.target;
            if (t && t.tagName === 'IMG' && !t.closest('a') && window.webkit && window.webkit.messageHandlers.anki) {
              window.webkit.messageHandlers.anki.postMessage({ type: 'image', src: t.currentSrc || t.src });
            }
          });
        })();
        // ---- 語のハイライトとカード内検索 ----
        var __marks = [], __cur = -1;
        function __ankiClear() {
          var ms = document.querySelectorAll('mark.__hl');
          for (var i = 0; i < ms.length; i++) {
            var m = ms[i], p = m.parentNode;
            p.replaceChild(document.createTextNode(m.textContent), m);
            p.normalize();
          }
          __marks = []; __cur = -1;
        }
        function __ankiHighlight(terms) {
          __ankiClear();
          terms = (terms || []).filter(function (t) { return t && t.length; });
          if (!terms.length) return 0;
          var re = new RegExp('(' + terms.map(function (t) { return t.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&'); }).join('|') + ')', 'gi');
          var walker = document.createTreeWalker(document.getElementById('ankiContent'), NodeFilter.SHOW_TEXT, {
            acceptNode: function (n) {
              var p = n.parentNode && n.parentNode.tagName;
              return (p === 'SCRIPT' || p === 'STYLE' || p === 'MARK') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
            }
          });
          var texts = [];
          while (walker.nextNode()) texts.push(walker.currentNode);
          for (var i = 0; i < texts.length; i++) {
            var node = texts[i], text = node.nodeValue;
            if (!re.test(text)) continue;
            re.lastIndex = 0;
            var frag = document.createDocumentFragment(), last = 0, m;
            while ((m = re.exec(text))) {
              frag.appendChild(document.createTextNode(text.slice(last, m.index)));
              var mark = document.createElement('mark'); mark.className = '__hl'; mark.textContent = m[0];
              frag.appendChild(mark); __marks.push(mark);
              last = m.index + m[0].length;
              if (!m[0].length) re.lastIndex++;
            }
            frag.appendChild(document.createTextNode(text.slice(last)));
            node.parentNode.replaceChild(frag, node);
          }
          return __marks.length;
        }
        function __ankiStep(d) {
          if (!__marks.length) return 0;
          if (__cur >= 0) __marks[__cur].classList.remove('__cur');
          __cur = ((__cur < 0 ? (d > 0 ? -1 : 0) : __cur) + d + __marks.length) % __marks.length;
          var m = __marks[__cur];
          m.classList.add('__cur');
          m.scrollIntoView({ block: 'center', behavior: 'smooth' });
          return __cur + 1;
        }
        </script>
        </body></html>
        """
    }
}
