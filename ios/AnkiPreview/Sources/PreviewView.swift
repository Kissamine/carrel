import SwiftUI
import WebKit
import Combine

/// プレビューの操作口 (カード内検索の移動など)。画面側から呼ぶ
@MainActor
final class PreviewController: ObservableObject {
    weak var webView: WKWebView?
    @Published var matchCount = 0
    @Published var current = 0
    var lastTerms: [String] = []

    func highlight(_ terms: [String]) {
        lastTerms = terms
        guard let web = webView else { return }
        let json = (try? JSONSerialization.data(withJSONObject: terms)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        web.evaluateJavaScript("__ankiHighlight(\(json))") { [weak self] v, _ in
            self?.matchCount = (v as? Int) ?? 0
            self?.current = 0
        }
    }

    func step(_ d: Int) {
        webView?.evaluateJavaScript("__ankiStep(\(d))") { [weak self] v, _ in
            self?.current = (v as? Int) ?? 0
        }
    }
}

/// カードのプレビュー (WKWebView)。メディアはアプリの中に取り込んだフォルダから読む
struct PreviewView: UIViewRepresentable {
    let html: String
    let mediaDir: URL
    let highlights: [String]
    var controller: PreviewController
    var onImage: (URL) -> Void = { _ in }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.userContentController.add(context.coordinator, name: "anki")
        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.backgroundColor = .clear
        view.navigationDelegate = context.coordinator
        controller.webView = view
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        controller.webView = view
        if context.coordinator.lastHTML != html {
            context.coordinator.lastHTML = html
            context.coordinator.pendingHighlights = highlights
            // 画像を読めるよう、HTML をメディアと同じ場所に書いてから読み込む
            let file = mediaDir.appendingPathComponent("__preview.html")
            try? FileManager.default.createDirectory(at: mediaDir, withIntermediateDirectories: true)
            try? html.write(to: file, atomically: true, encoding: .utf8)
            view.loadFileURL(file, allowingReadAccessTo: mediaDir)
        } else if context.coordinator.lastHighlights != highlights {
            context.coordinator.lastHighlights = highlights
            controller.highlight(highlights)
        }
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.configuration.userContentController.removeScriptMessageHandler(forName: "anki")
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: PreviewView
        var lastHTML: String?
        var lastHighlights: [String] = []
        var pendingHighlights: [String] = []
        init(_ parent: PreviewView) { self.parent = parent }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            lastHighlights = pendingHighlights
            parent.controller.highlight(pendingHighlights)
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // 外部リンクは Safari へ
            if action.navigationType == .linkActivated, let url = action.request.url, ["http", "https", "mailto"].contains(url.scheme ?? "") {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let dict = message.body as? [String: Any], dict["type"] as? String == "image",
                  let src = dict["src"] as? String, let url = URL(string: src) else { return }
            parent.onImage(url)
        }
    }
}
