import Cocoa
import WebKit

// SVG を WebKit で描いて PNG にする (フィルタやマスクもブラウザと同じに出る)
let args = CommandLine.arguments
guard args.count >= 4, let sizeArg = Int(args[3]) else { print("usage: svg2png in.svg out.png size"); exit(1) }
let SIZE = sizeArg
let svgURL = URL(fileURLWithPath: args[1]); let outURL = URL(fileURLWithPath: args[2])
let app = NSApplication.shared; app.setActivationPolicy(.prohibited)
let cfg = WKWebViewConfiguration()
let web = WKWebView(frame: NSRect(x: 0, y: 0, width: SIZE, height: SIZE), configuration: cfg)
web.setValue(false, forKey: "drawsBackground")
let win = NSWindow(contentRect: web.frame, styleMask: [.borderless], backing: .buffered, defer: false)
win.contentView = web
let svg = try! String(contentsOf: svgURL, encoding: .utf8)
let html = "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;background:transparent;overflow:hidden}svg{display:block;width:\(SIZE)px;height:\(SIZE)px}</style></head><body>\(svg)</body></html>"
class D: NSObject, WKNavigationDelegate {
    func webView(_ w: WKWebView, didFinish n: WKNavigation!) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            let c = WKSnapshotConfiguration(); c.rect = w.bounds; c.snapshotWidth = NSNumber(value: SIZE)
            w.takeSnapshot(with: c) { img, err in
                guard let img, let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff), let png = rep.representation(using: .png, properties: [:]) else { print("snapshot failed", err ?? ""); exit(2) }
                try! png.write(to: outURL); exit(0)
            }
        }
    }
}
let d = D(); web.navigationDelegate = d
web.loadHTMLString(html, baseURL: svgURL.deletingLastPathComponent())
app.run()
