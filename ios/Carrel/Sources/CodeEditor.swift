import SwiftUI
import UIKit

// MARK: - 色分けつきのコード入力欄 (HTML / CSS)

/// UITextView を使った軽い色分けエディタ。入力のたびに全体を塗り直すのではなく、少し待ってから
/// 文字列はそのままで属性だけを付け直す (カーソル位置も保たれる)。長すぎる本文は色分けしない
struct CodeTextView: UIViewRepresentable {
    enum Language { case html, css }
    @Binding var text: String
    var language: Language = .html
    var minHeight: CGFloat = 110

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let v = UITextView()
        v.delegate = context.coordinator
        v.backgroundColor = .clear
        v.font = Self.font
        v.textColor = .label
        v.isScrollEnabled = false
        v.autocorrectionType = .no
        v.autocapitalizationType = .none
        v.smartQuotesType = .no
        v.smartDashesType = .no
        v.textContainerInset = UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 0)
        v.textContainer.lineFragmentPadding = 0
        v.text = text
        context.coordinator.recolor(v, immediate: true)
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        context.coordinator.parent = self
        if v.text != text {
            v.text = text
            context.coordinator.recolor(v, immediate: true)
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? UIScreen.main.bounds.width - 64
        let h = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        return CGSize(width: width, height: max(minHeight, h))
    }

    static let font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
    /// これより長い本文は色分けしない (重くならないように)
    static let colorLimit = 30_000

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: CodeTextView
        private var pending: DispatchWorkItem?
        init(_ p: CodeTextView) { parent = p }

        func textViewDidChange(_ v: UITextView) {
            parent.text = v.text
            v.invalidateIntrinsicContentSize()
            recolor(v, immediate: false)
        }

        func recolor(_ v: UITextView, immediate: Bool) {
            pending?.cancel()
            let work = DispatchWorkItem { [weak v, parent] in
                guard let v else { return }
                Highlighter.apply(to: v.textStorage, language: parent.language)
            }
            pending = work
            if immediate { work.perform() } else { DispatchQueue.main.asyncAfter(deadline: .now() + 0.18, execute: work) }
        }
    }
}

/// 正規表現で、タグ・属性・値・実体参照・コメントに色を付ける (macOS 版と同じ配色)
enum Highlighter {
    static let tag = UIColor(red: 0x56 / 255, green: 0x9C / 255, blue: 0xD6 / 255, alpha: 1)       // 青
    static let attr = UIColor(red: 0x9C / 255, green: 0xDC / 255, blue: 0xFE / 255, alpha: 1)      // 水色
    static let str = UIColor(red: 0xCE / 255, green: 0x91 / 255, blue: 0x78 / 255, alpha: 1)       // 橙
    static let cmt = UIColor(red: 0x6A / 255, green: 0x99 / 255, blue: 0x55 / 255, alpha: 1)       // 緑
    static let ent = UIColor(red: 0xD7 / 255, green: 0xBA / 255, blue: 0x7D / 255, alpha: 1)       // 黄
    static let punct = UIColor.secondaryLabel

    private static let htmlRe = try! NSRegularExpression(pattern: "<!--[\\s\\S]*?-->|</?[A-Za-z][^>]*>|&[A-Za-z#][A-Za-z0-9]*;")
    private static let tagNameRe = try! NSRegularExpression(pattern: "^</?\\s*([A-Za-z][\\w:-]*)")
    private static let attrRe = try! NSRegularExpression(pattern: "([A-Za-z_:][\\w:.-]*)\\s*=\\s*(\"[^\"]*\"|'[^']*')")
    private static let cssRe = try! NSRegularExpression(pattern: "/\\*[\\s\\S]*?\\*/|([A-Za-z-]+)\\s*:\\s*([^;{}]*)|[{};]")

    static func apply(to storage: NSTextStorage, language: CodeTextView.Language) {
        let s = storage.string as NSString
        let all = NSRange(location: 0, length: s.length)
        storage.beginEditing()
        storage.setAttributes([.font: CodeTextView.font, .foregroundColor: UIColor.label], range: all)
        if s.length <= CodeTextView.colorLimit {
            switch language {
            case .html: paintHTML(storage, s, all)
            case .css: paintCSS(storage, s, all)
            }
        }
        storage.endEditing()
    }

    private static func paintHTML(_ st: NSTextStorage, _ s: NSString, _ all: NSRange) {
        for m in htmlRe.matches(in: s as String, range: all) {
            let r = m.range
            let chunk = s.substring(with: r)
            if chunk.hasPrefix("&") { st.addAttribute(.foregroundColor, value: ent, range: r); continue }
            if chunk.hasPrefix("<!--") { st.addAttribute(.foregroundColor, value: cmt, range: r); continue }
            st.addAttribute(.foregroundColor, value: punct, range: r)
            let ns = chunk as NSString
            if let n = tagNameRe.firstMatch(in: chunk, range: NSRange(location: 0, length: ns.length)) {
                st.addAttribute(.foregroundColor, value: tag, range: NSRange(location: r.location + n.range(at: 1).location, length: n.range(at: 1).length))
            }
            for a in attrRe.matches(in: chunk, range: NSRange(location: 0, length: ns.length)) {
                st.addAttribute(.foregroundColor, value: attr, range: NSRange(location: r.location + a.range(at: 1).location, length: a.range(at: 1).length))
                st.addAttribute(.foregroundColor, value: str, range: NSRange(location: r.location + a.range(at: 2).location, length: a.range(at: 2).length))
            }
        }
    }

    private static func paintCSS(_ st: NSTextStorage, _ s: NSString, _ all: NSRange) {
        for m in cssRe.matches(in: s as String, range: all) {
            let chunk = s.substring(with: m.range)
            if chunk.hasPrefix("/*") { st.addAttribute(.foregroundColor, value: cmt, range: m.range); continue }
            if m.range(at: 1).location != NSNotFound {
                st.addAttribute(.foregroundColor, value: attr, range: m.range(at: 1))
                st.addAttribute(.foregroundColor, value: str, range: m.range(at: 2))
            } else {
                st.addAttribute(.foregroundColor, value: punct, range: m.range)
            }
        }
    }
}

// MARK: - テキストモードの Markdown (ごく基本の記法だけ)

/// テキストモードでは Markdown のよく使う記法を受け付け、保存時に HTML にする。
/// 逆に HTML を開くときは、同じ記法の文章に戻す (戻せないタグは文字として残す)
enum Markdown {
    /// 文章 → HTML。**太字** *斜体* `コード` # 見出し - 箇条書き 1. 番号 [文字](URL) 改行→<br>
    static func toHTML(_ text: String) -> String {
        let lines = text.components(separatedBy: "\n")
        var out: [String] = []
        var list: String? = nil   // "ul" / "ol"
        func closeList() { if let l = list { out.append("</\(l)>"); list = nil } }
        for raw in lines {
            let line = raw
            if let m = raw.range(of: "^(#{1,6})\\s+", options: .regularExpression) {
                closeList()
                let level = raw[m].filter { $0 == "#" }.count
                out.append("<h\(level)>\(inline(String(raw[m.upperBound...])))</h\(level)>")
            } else if let m = raw.range(of: "^\\s*[-*+]\\s+", options: .regularExpression) {
                if list != "ul" { closeList(); out.append("<ul>"); list = "ul" }
                out.append("<li>\(inline(String(raw[m.upperBound...])))</li>")
            } else if let m = raw.range(of: "^\\s*\\d+[.)]\\s+", options: .regularExpression) {
                if list != "ol" { closeList(); out.append("<ol>"); list = "ol" }
                out.append("<li>\(inline(String(raw[m.upperBound...])))</li>")
            } else {
                closeList()
                out.append(inline(line))
            }
        }
        closeList()
        // 見出し・箇条書きの行の前後には <br> を入れない。それ以外の行は <br> でつなぐ
        var html = ""
        for (i, piece) in out.enumerated() {
            let block = piece.hasPrefix("<h") || piece.hasPrefix("<ul") || piece.hasPrefix("<ol") || piece.hasPrefix("<li") || piece.hasPrefix("</ul") || piece.hasPrefix("</ol")
            let prevBlock = i > 0 && (out[i - 1].hasPrefix("<h") || out[i - 1].hasPrefix("</ul") || out[i - 1].hasPrefix("</ol") || out[i - 1].hasPrefix("<ul") || out[i - 1].hasPrefix("<ol") || out[i - 1].hasPrefix("<li"))
            if i > 0 && !block && !prevBlock { html += "<br>" }
            html += piece
        }
        return html
    }

    private static func inline(_ s: String) -> String {
        var t = s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
        t = t.replacingOccurrences(of: "`([^`]+)`", with: "<code>$1</code>", options: .regularExpression)
        t = t.replacingOccurrences(of: "\\*\\*([^*]+)\\*\\*", with: "<b>$1</b>", options: .regularExpression)
        t = t.replacingOccurrences(of: "(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])", with: "<i>$1</i>", options: .regularExpression)
        t = t.replacingOccurrences(of: "~~([^~]+)~~", with: "<s>$1</s>", options: .regularExpression)
        t = t.replacingOccurrences(of: "\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)", with: "<a href=\"$2\">$1</a>", options: .regularExpression)
        return t
    }

    /// HTML → 文章。対応する記法に戻し、改行タグは改行に、ほかのタグは外す
    static func fromHTML(_ html: String) -> String {
        var t = html
        func rep(_ pattern: String, _ with: String) { t = t.replacingOccurrences(of: pattern, with: with, options: [.regularExpression, .caseInsensitive]) }
        rep("<(b|strong)>([\\s\\S]*?)</\\1>", "**$2**")
        rep("<(i|em)>([\\s\\S]*?)</\\1>", "*$2*")
        rep("<s>([\\s\\S]*?)</s>|<del>([\\s\\S]*?)</del>", "~~$1$2~~")
        rep("<code>([\\s\\S]*?)</code>", "`$1`")
        rep("<a [^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", "[$2]($1)")
        for n in 1...6 { rep("<h\(n)[^>]*>([\\s\\S]*?)</h\(n)>", "\n" + String(repeating: "#", count: n) + " $1\n") }
        rep("<li[^>]*>([\\s\\S]*?)</li>", "- $1\n")
        rep("</?(ul|ol)[^>]*>", "\n")
        rep("<br\\s*/?>|</div>|</p>", "\n")
        t = Note.stripHTML(t)
        t = t.replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
        while t.hasSuffix("\n") { t.removeLast() }
        while t.hasPrefix("\n") { t.removeFirst() }
        return t
    }
}
