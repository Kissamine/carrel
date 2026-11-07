import Foundation
import CoreText
import AppKit
// 文字列を 1 つの SVG パスにする (フォント名, 文字列)
let fontName = CommandLine.arguments[1], text = CommandLine.arguments[2]
let font = CTFontCreateWithName(fontName as CFString, 100, nil)
let attr = NSAttributedString(string: text, attributes: [.font: font, kCTKernAttributeName as NSAttributedString.Key: 0])
let line = CTLineCreateWithAttributedString(attr)
var d = ""
for run in (CTLineGetGlyphRuns(line) as! [CTRun]) {
    let n = CTRunGetGlyphCount(run)
    var glyphs = [CGGlyph](repeating: 0, count: n); var pos = [CGPoint](repeating: .zero, count: n)
    CTRunGetGlyphs(run, CFRangeMake(0, 0), &glyphs); CTRunGetPositions(run, CFRangeMake(0, 0), &pos)
    let rf = (CTRunGetAttributes(run) as NSDictionary)[kCTFontAttributeName] as! CTFont
    for i in 0..<n {
        guard let p = CTFontCreatePathForGlyph(rf, glyphs[i], nil) else { continue }
        var t = CGAffineTransform(translationX: pos[i].x, y: pos[i].y)
        let path = p.copy(using: &t)!
        path.applyWithBlock { el in
            let q = el.pointee.points
            switch el.pointee.type {
            case .moveToPoint: d += String(format: "M%.2f %.2f", q[0].x, -q[0].y)
            case .addLineToPoint: d += String(format: "L%.2f %.2f", q[0].x, -q[0].y)
            case .addQuadCurveToPoint: d += String(format: "Q%.2f %.2f %.2f %.2f", q[0].x, -q[0].y, q[1].x, -q[1].y)
            case .addCurveToPoint: d += String(format: "C%.2f %.2f %.2f %.2f %.2f %.2f", q[0].x, -q[0].y, q[1].x, -q[1].y, q[2].x, -q[2].y)
            case .closeSubpath: d += "Z"
            @unknown default: break
            }
        }
    }
}
let b = CTLineGetBoundsWithOptions(line, [.useGlyphPathBounds])
print(String(format: "%.2f %.2f %.2f %.2f", b.minX, -b.maxY, b.width, b.height))
print(d)
