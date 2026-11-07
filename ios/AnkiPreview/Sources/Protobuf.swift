import Foundation

/// Anki のノートタイプ設定 (protobuf) から、必要な文字列だけを拾う最小限の読み手。
/// 依存ライブラリを増やさないため、varint と length-delimited だけを解釈する。
struct ProtoReader {
    private let bytes: [UInt8]
    private var at = 0

    init(_ data: Data) { bytes = [UInt8](data) }

    private mutating func varint() -> UInt64? {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while at < bytes.count {
            let b = bytes[at]; at += 1
            result |= UInt64(b & 0x7f) << shift
            if b & 0x80 == 0 { return result }
            shift += 7
            if shift > 63 { return nil }
        }
        return nil
    }

    /// 番号 → 文字列 (length-delimited のもの) を集める。同じ番号が複数あれば最後のもの
    static func strings(in data: Data) -> [Int: String] {
        var r = ProtoReader(data)
        var out: [Int: String] = [:]
        while r.at < r.bytes.count {
            guard let key = r.varint() else { break }
            let field = Int(key >> 3)
            let wire = key & 7
            switch wire {
            case 0: _ = r.varint()
            case 1: r.at += 8
            case 5: r.at += 4
            case 2:
                guard let len = r.varint(), r.at + Int(len) <= r.bytes.count else { return out }
                let slice = r.bytes[r.at..<(r.at + Int(len))]
                r.at += Int(len)
                if let s = String(bytes: slice, encoding: .utf8) { out[field] = s }
            default: return out
            }
        }
        return out
    }
}
