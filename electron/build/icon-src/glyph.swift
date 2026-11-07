import Foundation
import CoreText
import AppKit

// 指定フォントの "C" の輪郭を SVG パス (y 下向き) で出す
let fonts = CommandLine.arguments.dropFirst()
var out: [String: Any] = [:]
for name in fonts {
    let font = CTFontCreateWithName(name as CFString, 1000, nil)
    let actual = CTFontCopyPostScriptName(font) as String
    var ch: [UniChar] = [0x43]
    var glyph: [CGGlyph] = [0]
    guard CTFontGetGlyphsForCharacters(font, &ch, &glyph, 1), let path = CTFontCreatePathForGlyph(font, glyph[0], nil) else { continue }
    var d = ""
    path.applyWithBlock { el in
        let p = el.pointee.points
        switch el.pointee.type {
        case .moveToPoint: d += String(format: "M %.1f %.1f ", p[0].x, -p[0].y)
        case .addLineToPoint: d += String(format: "L %.1f %.1f ", p[0].x, -p[0].y)
        case .addQuadCurveToPoint: d += String(format: "Q %.1f %.1f %.1f %.1f ", p[0].x, -p[0].y, p[1].x, -p[1].y)
        case .addCurveToPoint: d += String(format: "C %.1f %.1f %.1f %.1f %.1f %.1f ", p[0].x, -p[0].y, p[1].x, -p[1].y, p[2].x, -p[2].y)
        case .closeSubpath: d += "Z "
        @unknown default: break
        }
    }
    let b = path.boundingBoxOfPath
    out[name] = ["ps": actual, "d": d, "x": b.minX, "y": -b.maxY, "w": b.width, "h": b.height]
}
let data = try! JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted])
FileHandle.standardOutput.write(data)
