import Foundation
import SQLite3
import CryptoKit

/// Anki のコレクション (SQLite) を読む。書き込みはしない
final class CollectionDB {
    private var db: OpaquePointer?

    init(path: String) throws {
        // 取り込んだ自前のコピーを開く。Anki は WAL 形式で書き出すので、読み取り専用で開くと
        // 実機では補助ファイル (-shm) を作れず "unable to open database file" になる。
        // 書き込みはしないが、補助ファイルを作れるよう読み書き可で開く (新規作成はしない)
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX, nil) != SQLITE_OK {
            let msg = db.map { String(cString: sqlite3_errmsg($0)) } ?? "open に失敗"
            throw NSError(domain: "AnkiPreview", code: 1, userInfo: [NSLocalizedDescriptionKey: msg])
        }
        // Anki の表は大文字小文字を区別しない独自の並び (unicase) を使っている。
        // それを知らないと並べ替えで失敗するので、同じ名前で登録しておく
        sqlite3_create_collation(db, "unicase", SQLITE_UTF8, nil) { _, lenA, a, lenB, b in
            guard let a, let b else { return 0 }
            let sa = String(decoding: UnsafeRawBufferPointer(start: a, count: Int(lenA)), as: UTF8.self)
            let sb = String(decoding: UnsafeRawBufferPointer(start: b, count: Int(lenB)), as: UTF8.self)
            switch sa.compare(sb, options: [.caseInsensitive]) {
            case .orderedAscending: return -1
            case .orderedDescending: return 1
            case .orderedSame: return 0
            }
        }
    }

    deinit { if let db { sqlite3_close(db) } }

    private func query(_ sql: String, _ row: (OpaquePointer) -> Void) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw NSError(domain: "AnkiPreview", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: String(cString: sqlite3_errmsg(db))])
        }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW { row(stmt) }
    }

    /// 書き込み (iPhone 内のコピーに対してだけ使う)
    func exec(_ sql: String) throws {
        if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK {
            throw NSError(domain: "AnkiPreview", code: 5, userInfo: [NSLocalizedDescriptionKey: String(cString: sqlite3_errmsg(db))])
        }
    }
    private static func quote(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "''") + "'" }

    /// ノートのタグを書き換える (Anki と同じく前後に空白を付けて保存)
    func setTags(noteId: Int64, tags: [String]) throws {
        let joined = tags.isEmpty ? "" : " " + tags.joined(separator: " ") + " "
        let now = Int64(Date().timeIntervalSince1970)
        try exec("update notes set tags = \(Self.quote(joined)), mod = \(now), usn = -1 where id = \(noteId)")
    }

    /// ノートの全カードを別のデッキへ
    func setDeck(noteId: Int64, deckId: Int64) throws {
        let now = Int64(Date().timeIntervalSince1970)
        try exec("update cards set did = \(deckId), mod = \(now), usn = -1 where nid = \(noteId)")
    }

    private func text(_ stmt: OpaquePointer, _ col: Int32) -> String {
        guard let c = sqlite3_column_text(stmt, col) else { return "" }
        return String(cString: c)
    }

    private func blob(_ stmt: OpaquePointer, _ col: Int32) -> Data {
        guard let p = sqlite3_column_blob(stmt, col) else { return Data() }
        return Data(bytes: p, count: Int(sqlite3_column_bytes(stmt, col)))
    }

    /// コレクションの作成時刻 (秒)。復習カードの期限はこれからの日数で入っている
    func creationTime() throws -> Int64 {
        var crt: Int64 = 0
        try query("select crt from col limit 1") { s in crt = sqlite3_column_int64(s, 0) }
        return crt
    }

    /// スキーマ 18 (ノートタイプが別の表) か、11 (col の JSON) か
    private func hasTable(_ name: String) -> Bool {
        var found = false
        try? query("select name from sqlite_master where type='table' and name='\(name)'") { _ in found = true }
        return found
    }

    func notetypes() throws -> [Notetype] {
        if !hasTable("notetypes") { return try notetypesFromJSON() }
        var fields: [Int64: [(Int, String)]] = [:]
        try query("select ntid, ord, name from fields order by ntid, ord") { s in
            fields[sqlite3_column_int64(s, 0), default: []].append((Int(sqlite3_column_int(s, 1)), self.text(s, 2)))
        }
        var templates: [Int64: [(Int, CardTemplate)]] = [:]
        try query("select ntid, ord, name, config from templates order by ntid, ord") { s in
            // CardTemplateConfig: 1 = 表面, 2 = 裏面
            let cfg = ProtoReader.strings(in: self.blob(s, 3))
            templates[sqlite3_column_int64(s, 0), default: []]
                .append((Int(sqlite3_column_int(s, 1)), CardTemplate(name: self.text(s, 2), front: cfg[1] ?? "", back: cfg[2] ?? "")))
        }
        var out: [Notetype] = []
        try query("select id, name, config from notetypes") { s in
            let id = sqlite3_column_int64(s, 0)
            // NotetypeConfig: 3 = CSS
            let cfg = ProtoReader.strings(in: self.blob(s, 2))
            out.append(Notetype(
                id: id, name: self.text(s, 1), css: cfg[3] ?? "",
                fieldNames: (fields[id] ?? []).sorted { $0.0 < $1.0 }.map { $0.1 },
                templates: (templates[id] ?? []).sorted { $0.0 < $1.0 }.map { $0.1 }
            ))
        }
        return out
    }

    func notes() throws -> [Note] {
        var out: [Note] = []
        try query("select id, mid, flds, tags, mod from notes") { s in
            let flds = self.text(s, 2).components(separatedBy: "\u{1f}")
            let tags = self.text(s, 3).split(separator: " ").map(String.init)
            out.append(Note(id: sqlite3_column_int64(s, 0), notetypeId: sqlite3_column_int64(s, 1),
                            fields: flds, tags: tags, modified: sqlite3_column_int64(s, 4)))
        }
        return out
    }

    /// スキーマ 11: col.models の JSON から
    private func notetypesFromJSON() throws -> [Notetype] {
        var raw = ""
        try query("select models from col limit 1") { s in raw = self.text(s, 0) }
        guard let obj = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: [String: Any]] else { return [] }
        return obj.compactMap { key, m in
            guard let id = Int64(key) ?? (m["id"] as? NSNumber)?.int64Value else { return nil }
            let flds = ((m["flds"] as? [[String: Any]]) ?? []).sorted { (($0["ord"] as? Int) ?? 0) < (($1["ord"] as? Int) ?? 0) }
            let tmpls = ((m["tmpls"] as? [[String: Any]]) ?? []).sorted { (($0["ord"] as? Int) ?? 0) < (($1["ord"] as? Int) ?? 0) }
            return Notetype(id: id, name: (m["name"] as? String) ?? "", css: (m["css"] as? String) ?? "",
                            fieldNames: flds.map { ($0["name"] as? String) ?? "" },
                            templates: tmpls.map { CardTemplate(name: ($0["name"] as? String) ?? "", front: ($0["qfmt"] as? String) ?? "", back: ($0["afmt"] as? String) ?? "") })
        }
    }

    func decks() throws -> [Deck] {
        if !hasTable("decks") {
            var raw = ""
            try query("select decks from col limit 1") { s in raw = self.text(s, 0) }
            guard let obj = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: [String: Any]] else { return [] }
            return obj.compactMap { key, d in
                guard let id = Int64(key) else { return nil }
                return Deck(id: id, name: (d["name"] as? String) ?? "")
            }.sorted { $0.name < $1.name }
        }
        var out: [Deck] = []
        try query("select id, name from decks order by name") { s in
            // 階層は \u{1f} 区切り
            out.append(Deck(id: sqlite3_column_int64(s, 0), name: self.text(s, 1).replacingOccurrences(of: "\u{1f}", with: "::")))
        }
        return out
    }

    /// 全カード (学習の記録)
    func cards() throws -> [CardInfo] {
        var out: [CardInfo] = []
        try query("select id, nid, ord, did, queue, type, due, ivl, reps, lapses, flags, factor, left, mod from cards order by nid, ord") { s in
            out.append(CardInfo(id: sqlite3_column_int64(s, 0), noteId: sqlite3_column_int64(s, 1),
                                ord: Int(sqlite3_column_int(s, 2)), deckId: sqlite3_column_int64(s, 3),
                                queue: Int(sqlite3_column_int(s, 4)), type: Int(sqlite3_column_int(s, 5)),
                                due: sqlite3_column_int64(s, 6), ivl: Int(sqlite3_column_int(s, 7)),
                                reps: Int(sqlite3_column_int(s, 8)), lapses: Int(sqlite3_column_int(s, 9)),
                                flag: Int(sqlite3_column_int(s, 10)) & 7,
                                factor: Int(sqlite3_column_int(s, 11)), left: Int(sqlite3_column_int(s, 12)),
                                mod: sqlite3_column_int64(s, 13)))
        }
        return out
    }

    // ---- 書き込み (アプリ内の自分のコピーに対して。同期フォルダへは Store が写す) ----

    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970) }
    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// コレクションの更新時刻を進める (Anki が「変わった」と分かるように)
    func touch() throws {
        try exec("update col set mod = \(Self.nowMs())")
    }

    /// 学習の結果でカードを書き換える
    func updateCard(_ c: CardInfo) throws {
        try exec("update cards set did = \(c.deckId), queue = \(c.queue), type = \(c.type), due = \(c.due), ivl = \(c.ivl), factor = \(c.factor), reps = \(c.reps), lapses = \(c.lapses), left = \(c.left), flags = \(c.flag), mod = \(Self.now()), usn = -1 where id = \(c.id)")
    }

    /// 復習の記録 (revlog)。ivl / lastIvl は日数 (学習中は負の秒数)、time はかかった時間 (ミリ秒)
    func insertRevlog(cardId: Int64, ease: Int, ivl: Int, lastIvl: Int, factor: Int, time: Int, type: Int) throws {
        var id = Self.nowMs()
        var dup = false
        try query("select 1 from revlog where id = \(id)") { _ in dup = true }
        if dup { id += 1 }
        try exec("insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (\(id), \(cardId), -1, \(ease), \(ivl), \(lastIvl), \(factor), \(time), \(type))")
    }

    /// ノートの本文とタグを書き換える
    func updateNote(id: Int64, fields: [String], tags: [String]) throws {
        let flds = fields.joined(separator: "\u{1f}")
        let sfld = Note.stripHTML(fields.first ?? "")
        let joined = tags.isEmpty ? "" : " " + tags.joined(separator: " ") + " "
        try exec("update notes set flds = \(Self.quote(flds)), sfld = \(Self.quote(sfld)), csum = \(Self.checksum(sfld)), tags = \(Self.quote(joined)), mod = \(Self.now()), usn = -1 where id = \(id)")
    }

    /// ノートを作る。カードは各カードタイプにつき 1 枚 (新規、並びは最後)。作ったノートの id を返す
    func insertNote(notetypeId: Int64, deckId: Int64, fields: [String], tags: [String], templateCount: Int) throws -> Int64 {
        var id = Self.nowMs()
        var dup = false
        try query("select 1 from notes where id = \(id)") { _ in dup = true }
        if dup { id += 1 }
        let flds = fields.joined(separator: "\u{1f}")
        let sfld = Note.stripHTML(fields.first ?? "")
        let joined = tags.isEmpty ? "" : " " + tags.joined(separator: " ") + " "
        try exec("insert into notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) values (\(id), \(Self.quote(Self.guid())), \(notetypeId), \(Self.now()), -1, \(Self.quote(joined)), \(Self.quote(flds)), \(Self.quote(sfld)), \(Self.checksum(sfld)), 0, '')")
        var pos: Int64 = 0
        try query("select coalesce(max(due), 0) from cards where type = 0") { s in pos = sqlite3_column_int64(s, 0) }
        for ord in 0..<max(1, templateCount) {
            let cid = id + Int64(ord) + 1
            try exec("insert into cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data) values (\(cid), \(id), \(deckId), \(ord), \(Self.now()), -1, 0, 0, \(pos + 1), 0, 0, 0, 0, 0, 0, 0, 0, '')")
        }
        return id
    }

    /// ノートとそのカードを消す。Anki と同じく graves に記録して、同期で消えたことが伝わるようにする
    func deleteNote(id: Int64) throws {
        var cids: [Int64] = []
        try query("select id from cards where nid = \(id)") { s in cids.append(sqlite3_column_int64(s, 0)) }
        for cid in cids { try exec("insert into graves (usn, oid, type) values (-1, \(cid), 0)") }
        try exec("insert into graves (usn, oid, type) values (-1, \(id), 1)")
        try exec("delete from cards where nid = \(id)")
        try exec("delete from notes where id = \(id)")
    }

    /// 一貫した形で別の場所へ写す (WAL の内容も含めて)
    func backup(to dest: URL) throws {
        var out: OpaquePointer?
        guard sqlite3_open_v2(dest.path, &out, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) == SQLITE_OK, let out else {
            throw NSError(domain: "AnkiPreview", code: 6, userInfo: [NSLocalizedDescriptionKey: "書き出し先を開けません"])
        }
        defer { sqlite3_close(out) }
        guard let b = sqlite3_backup_init(out, "main", db, "main") else {
            throw NSError(domain: "AnkiPreview", code: 7, userInfo: [NSLocalizedDescriptionKey: String(cString: sqlite3_errmsg(out))])
        }
        sqlite3_backup_step(b, -1)
        sqlite3_backup_finish(b)
    }

    /// Anki と同じ: 最初の欄 (HTML を落としたもの) の SHA1 の先頭 8 桁
    private static func checksum(_ s: String) -> Int64 {
        let digest = Insecure.SHA1.hash(data: Data(s.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined().prefix(8)
        return Int64(hex, radix: 16) ?? 0
    }

    /// Anki と同じ 10 文字の guid
    private static func guid() -> String {
        let table = Array("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&()*+,-./:;<=>?@[]^_`{|}~")
        var n = UInt64.random(in: 1...UInt64.max)
        var out = ""
        while n > 0 { out.append(table[Int(n % UInt64(table.count))]); n /= UInt64(table.count) }
        return String(out.prefix(10))
    }
}
