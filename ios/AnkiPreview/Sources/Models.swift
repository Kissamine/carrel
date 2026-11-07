import Foundation

/// ノート (1 枚のカードのもとになる記録)
struct Note: Identifiable, Hashable {
    let id: Int64
    let notetypeId: Int64
    let fields: [String]
    let tags: [String]
    let modified: Int64
    /// 全欄の HTML を落とした文章 (検索用)
    let plain: String
    let plainLower: String

    init(id: Int64, notetypeId: Int64, fields: [String], tags: [String], modified: Int64) {
        self.id = id; self.notetypeId = notetypeId; self.fields = fields; self.tags = tags; self.modified = modified
        let p = fields.map { Note.stripHTML($0.replacingOccurrences(of: "<br>", with: " ").replacingOccurrences(of: "</li>", with: " ").replacingOccurrences(of: "</p>", with: " ").replacingOccurrences(of: "</div>", with: " ")) }
            .filter { !$0.isEmpty }.joined(separator: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        plain = p
        plainLower = p.lowercased()
    }

    /// 作成日時 (id はミリ秒のタイムスタンプ)
    var created: Date { Date(timeIntervalSince1970: Double(id) / 1000) }

    /// 一覧に出す見出し: 最初の欄からタグを落として、<br> ごとに行に分ける (macOS 版と同じ)
    var titleLines: [String] {
        let raw = fields.first ?? ""
        let parts = raw.replacingOccurrences(of: "\n", with: "")
            .components(separatedBy: "<br>")
        return parts.map { Self.stripHTML($0) }.filter { !$0.isEmpty }.prefix(3).map { String($0) }
    }

    static func stripHTML(_ s: String) -> String {
        var out = s.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        out = out.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

struct Notetype: Identifiable {
    let id: Int64
    let name: String
    let css: String
    let fieldNames: [String]
    let templates: [CardTemplate]
    /// 夜間モード用の CSS を自分で持っているか
    var hasNightCSS: Bool { css.contains("night_mode") || css.contains("nightMode") }
}

struct CardTemplate {
    let name: String
    let front: String
    let back: String
}

struct Deck: Identifiable, Hashable {
    let id: Int64
    let name: String
}

/// カード (学習の記録)。queue: -1 停止 / 0 新規 / 1 学習中 / 2 復習 / 3 日またぎ学習 / -2,-3 保留
struct CardInfo: Identifiable, Hashable {
    let id: Int64
    let noteId: Int64
    let ord: Int
    let deckId: Int64
    let queue: Int
    let type: Int
    let due: Int64
    let ivl: Int
    let reps: Int
    let lapses: Int
    let flag: Int

    enum Status: String { case new = "未学習", learning = "学習中", review = "復習中", suspended = "停止中", buried = "保留中" }
    var status: Status {
        switch queue {
        case -1: return .suspended
        case -2, -3: return .buried
        case 0: return .new
        case 1, 3: return .learning
        default: return .review
        }
    }

    /// 次の期限。crt はコレクションの作成時刻 (秒)
    func dueDate(crt: Int64) -> Date? {
        switch queue {
        case 2, 3: return Date(timeIntervalSince1970: Double(crt + due * 86400))
        case 1: return Date(timeIntervalSince1970: Double(due))
        default: return nil
        }
    }
}
