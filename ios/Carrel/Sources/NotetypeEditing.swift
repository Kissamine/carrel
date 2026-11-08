import Foundation
import SQLite3
import CryptoKit

// MARK: - ノートタイプの編集 (欄・テンプレート・CSS)

/// 編集用の写し。保存すると CollectionDB.saveNotetype で書き戻す
struct NotetypeDraft {
    var id: Int64
    var name: String
    var css: String
    var fields: [FieldDraft]
    var templates: [TemplateDraft]
}
struct FieldDraft: Identifiable, Equatable {
    var id = UUID()
    var name: String
    /// 元からあった欄なら、その元の位置。新しく足した欄は nil
    var originalOrd: Int?
}
struct TemplateDraft: Identifiable, Equatable {
    var id: Int          // ord
    var name: String
    var front: String
    var back: String
}

/// protobuf のメッセージの一部のフィールドだけを差し替える (ほかはそのまま残す)
enum ProtoWriter {
    static func varint(_ v: UInt64) -> [UInt8] {
        var v = v; var out: [UInt8] = []
        repeat { var b = UInt8(v & 0x7f); v >>= 7; if v != 0 { b |= 0x80 }; out.append(b) } while v != 0
        return out
    }
    static func replacing(_ data: Data, strings: [Int: String] = [:], varints: [Int: UInt64] = [:]) -> Data {
        let bytes = [UInt8](data); var at = 0; var out: [UInt8] = []
        func readVarint() -> UInt64? {
            var result: UInt64 = 0, shift: UInt64 = 0
            while at < bytes.count {
                let b = bytes[at]; at += 1
                result |= UInt64(b & 0x7f) << shift
                if b & 0x80 == 0 { return result }
                shift += 7; if shift > 63 { return nil }
            }
            return nil
        }
        while at < bytes.count {
            let start = at
            guard let key = readVarint() else { break }
            let field = Int(key >> 3), wire = key & 7
            var end = at
            switch wire {
            case 0: _ = readVarint(); end = at
            case 1: end = at + 8
            case 5: end = at + 4
            case 2: guard let len = readVarint() else { return Data(out) }; end = at + Int(len)
            default: return Data(out)
            }
            if end > bytes.count { break }
            if strings[field] == nil && varints[field] == nil { out += bytes[start..<end] }
            at = end
        }
        for (f, s) in strings.sorted(by: { $0.key < $1.key }) {
            out += varint(UInt64(f << 3 | 2)); let u = Array(s.utf8); out += varint(UInt64(u.count)); out += u
        }
        for (f, v) in varints.sorted(by: { $0.key < $1.key }) { out += varint(UInt64(f << 3 | 0)); out += varint(v) }
        return Data(out)
    }
}

extension CollectionDB {
    /// 編集用に読み出す
    func notetypeDraft(id: Int64) throws -> NotetypeDraft? {
        var name = "", css = ""
        var found = false
        try query("select name, config from notetypes where id = \(id)") { s in
            found = true
            name = self.text(s, 0)
            css = ProtoReader.strings(in: self.blob(s, 1))[3] ?? ""
        }
        guard found else { return nil }
        var fields: [FieldDraft] = []
        try query("select ord, name from fields where ntid = \(id) order by ord") { s in
            fields.append(FieldDraft(name: self.text(s, 1), originalOrd: Int(sqlite3_column_int(s, 0))))
        }
        var templates: [TemplateDraft] = []
        try query("select ord, name, config from templates where ntid = \(id) order by ord") { s in
            let cfg = ProtoReader.strings(in: self.blob(s, 2))
            templates.append(TemplateDraft(id: Int(sqlite3_column_int(s, 0)), name: self.text(s, 1), front: cfg[1] ?? "", back: cfg[2] ?? ""))
        }
        return NotetypeDraft(id: id, name: name, css: css, fields: fields, templates: templates)
    }

    /// 書き戻す。欄の並び・数が変わっていれば、そのノートタイプの全ノートの本文も並べ替える
    func saveNotetype(_ d: NotetypeDraft) throws {
        let now = Int64(Date().timeIntervalSince1970)
        try exec("begin")
        do {
            // --- 欄 ---
            var oldConfigs: [Int: Data] = [:]
            var oldNames: [Int: String] = [:]
            try query("select ord, name, config from fields where ntid = \(d.id)") { s in
                oldConfigs[Int(sqlite3_column_int(s, 0))] = self.blob(s, 2); oldNames[Int(sqlite3_column_int(s, 0))] = self.text(s, 1)
            }
            let fallbackCfg = oldConfigs[0] ?? Data()
            let structureChanged = d.fields.map { $0.originalOrd } != Array(0..<oldConfigs.count).map { Optional($0) }
            let renamed = d.fields.contains { f in f.originalOrd.map { oldNames[$0] != f.name } ?? false }
            if structureChanged || renamed {
                try exec("delete from fields where ntid = \(d.id)")
                for (i, f) in d.fields.enumerated() {
                    let cfg = f.originalOrd.flatMap { oldConfigs[$0] } ?? fallbackCfg
                    try run("insert into fields (ntid, ord, name, config) values (?, ?, ?, ?)", [d.id, Int64(i), f.name, cfg])
                }
            }
            if structureChanged {
                // 全ノートの本文を新しい並びに
                var notes: [(Int64, String)] = []
                try query("select id, flds from notes where mid = \(d.id)") { s in notes.append((sqlite3_column_int64(s, 0), self.text(s, 1))) }
                for (nid, flds) in notes {
                    let old = flds.components(separatedBy: "\u{1f}")
                    let new = d.fields.map { f -> String in
                        guard let o = f.originalOrd, o < old.count else { return "" }
                        return old[o]
                    }
                    let sfld = Note.stripHTML(new.first ?? "")
                    try run("update notes set flds = ?, sfld = ?, csum = ?, mod = ?, usn = -1 where id = ?",
                            [new.joined(separator: "\u{1f}"), sfld, Self.checksum(sfld), now, nid])
                }
                // 並べ替えの基準の欄 (sort_field_idx) が範囲外ならはじめの欄に
                var cfg = Data()
                try query("select config from notetypes where id = \(d.id)") { s in cfg = self.blob(s, 0) }
                let idx = ProtoReader.varints(in: cfg)[2] ?? 0
                if Int(idx) >= d.fields.count {
                    try run("update notetypes set config = ? where id = ?", [ProtoWriter.replacing(cfg, varints: [2: 0]), d.id])
                }
                // 構造が変わったので、Anki の同期では全体の送り直しが要る印 (scm)
                try exec("update col set scm = \(Int64(Date().timeIntervalSince1970 * 1000))")
            }
            // --- テンプレート ---
            for t in d.templates {
                var cfg = Data()
                try query("select config from templates where ntid = \(d.id) and ord = \(t.id)") { s in cfg = self.blob(s, 0) }
                let newCfg = ProtoWriter.replacing(cfg, strings: [1: t.front, 2: t.back])
                try run("update templates set name = ?, config = ?, mtime_secs = ?, usn = -1 where ntid = ? and ord = ?", [t.name, newCfg, now, d.id, Int64(t.id)])
            }
            // --- 名前と CSS ---
            var cfg = Data()
            try query("select config from notetypes where id = \(d.id)") { s in cfg = self.blob(s, 0) }
            try run("update notetypes set name = ?, config = ?, mtime_secs = ?, usn = -1 where id = ?", [d.name, ProtoWriter.replacing(cfg, strings: [3: d.css]), now, d.id])
            try exec("commit")
        } catch {
            try? exec("rollback")
            throw error
        }
    }

    /// 既存のノートタイプを写して新しく作る (欄・カードタイプ・CSS はそのまま)。新しい id を返す
    func cloneNotetype(from src: Int64, name: String) throws -> Int64 {
        let id = Int64(Date().timeIntervalSince1970 * 1000)
        let now = Int64(Date().timeIntervalSince1970)
        try exec("begin")
        do {
            try run("insert into notetypes (id, name, mtime_secs, usn, config) select ?, ?, ?, -1, config from notetypes where id = ?", [id, name, now, src])
            try run("insert into fields (ntid, ord, name, config) select ?, ord, name, config from fields where ntid = ?", [id, src])
            try run("insert into templates (ntid, ord, name, mtime_secs, usn, config) select ?, ord, name, ?, -1, config from templates where ntid = ?", [id, now, src])
            try exec("update col set scm = \(Int64(Date().timeIntervalSince1970 * 1000))")
            try exec("commit")
        } catch { try? exec("rollback"); throw error }
        return id
    }

    /// 使われていないノートタイプを消す。使っているノートがあれば消さずに枚数を返す
    func deleteNotetype(id: Int64) throws -> Int {
        var inUse = 0
        try query("select count(*) from notes where mid = \(id)") { s in inUse = Int(sqlite3_column_int(s, 0)) }
        if inUse > 0 { return inUse }
        try exec("begin")
        do {
            try exec("delete from templates where ntid = \(id)")
            try exec("delete from fields where ntid = \(id)")
            try exec("delete from notetypes where id = \(id)")
            try exec("update col set scm = \(Int64(Date().timeIntervalSince1970 * 1000))")
            try exec("commit")
        } catch { try? exec("rollback"); throw error }
        return 0
    }

    /// ノートを別のノートタイプに移す。fields は新しい並びの本文。カードはカードタイプの数に合わせる
    func changeNotetype(noteId: Int64, to mid: Int64, fields: [String], tags: [String], templateCount: Int) throws {
        let now = Int64(Date().timeIntervalSince1970)
        try exec("begin")
        do {
            let sfld = Note.stripHTML(fields.first ?? "")
            let joined = tags.isEmpty ? "" : " " + tags.joined(separator: " ") + " "
            try run("update notes set mid = ?, flds = ?, sfld = ?, csum = ?, tags = ?, mod = ?, usn = -1 where id = ?",
                    [mid, fields.joined(separator: "\u{1f}"), sfld, Self.checksum(sfld), joined, now, noteId])
            var cards: [(Int64, Int, Int64)] = []   // id, ord, did
            try query("select id, ord, did from cards where nid = \(noteId) order by ord") { s in cards.append((sqlite3_column_int64(s, 0), Int(sqlite3_column_int(s, 1)), sqlite3_column_int64(s, 2))) }
            let count = max(1, templateCount)
            for (cid, ord, _) in cards where ord >= count {
                try exec("insert or ignore into graves (usn, oid, type) values (-1, \(cid), 0)")
                try exec("delete from cards where id = \(cid)")
            }
            let have = Set(cards.map { $0.1 })
            let did = cards.first?.2 ?? 1
            var pos: Int64 = 0
            try query("select coalesce(max(due), 0) from cards where type = 0") { s in pos = sqlite3_column_int64(s, 0) }
            for ord in 0..<count where !have.contains(ord) {
                let cid = Int64(Date().timeIntervalSince1970 * 1000) + Int64(ord)
                try exec("insert into cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data) values (\(cid), \(noteId), \(did), \(ord), \(now), -1, 0, 0, \(pos + 1), 0, 0, 0, 0, 0, 0, 0, 0, '')")
            }
            try exec("commit")
        } catch { try? exec("rollback"); throw error }
    }
}

extension ProtoReader {
    /// 番号 → 数値 (varint のもの)
    static func varints(in data: Data) -> [Int: UInt64] {
        let bytes = [UInt8](data); var at = 0; var out: [Int: UInt64] = [:]
        func readVarint() -> UInt64? {
            var result: UInt64 = 0, shift: UInt64 = 0
            while at < bytes.count {
                let b = bytes[at]; at += 1
                result |= UInt64(b & 0x7f) << shift
                if b & 0x80 == 0 { return result }
                shift += 7; if shift > 63 { return nil }
            }
            return nil
        }
        while at < bytes.count {
            guard let key = readVarint() else { break }
            let field = Int(key >> 3), wire = key & 7
            switch wire {
            case 0: if let v = readVarint() { out[field] = v }
            case 1: at += 8
            case 5: at += 4
            case 2: guard let len = readVarint() else { return out }; at += Int(len)
            default: return out
            }
        }
        return out
    }
}
