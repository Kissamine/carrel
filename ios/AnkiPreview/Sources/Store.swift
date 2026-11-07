import Foundation
import SwiftUI
import Combine

/// 検索欄の文字列を分解したもの。スペース区切りで AND、"-語" で除外、tag: / deck: で絞り込み、"…" で句
/// 作成・編集の対象。note が nil なら新規
struct EditTarget: Identifiable { let note: Note?; var id: Int64 { note?.id ?? 0 } }

/// 学習の条件 (カスタム学習デッキ)
struct StudyOptions: Codable, Identifiable, Equatable {
    var id = UUID()
    var name = ""
    /// 空なら全デッキ
    var decks: [Int64] = []
    var tags: [String] = []
    /// true = 選んだタグを すべて 持つ / false = どれか
    var tagsAll = false
    var keyword = ""
    /// 期限の来ていない復習カードも含める
    var includeNotDue = false
    var includeSuspended = false
    /// 新規カードの上限 (-1 で無制限)
    var newLimit = 20
    var newFirst = false
    var random = false
    /// 全体の上限 (0 で無制限)
    var limit = 0

    var summary: String {
        var p: [String] = []
        p.append(decks.isEmpty ? "すべてのデッキ" : "\(decks.count) デッキ")
        if !tags.isEmpty { p.append("タグ \(tags.count)") }
        if !keyword.isEmpty { p.append("「\(keyword)」") }
        if includeNotDue { p.append("期限前も") }
        if random { p.append("ランダム") }
        return p.joined(separator: " · ")
    }

    func save(_ key: String) { if let d = try? JSONEncoder().encode(self) { UserDefaults.standard.set(d, forKey: key) } }
    static func load(_ key: String) -> StudyOptions? {
        guard let d = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(StudyOptions.self, from: d)
    }
    static func loadList(_ key: String) -> [StudyOptions] {
        guard let d = UserDefaults.standard.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([StudyOptions].self, from: d)) ?? []
    }
    static func saveList(_ list: [StudyOptions], _ key: String) {
        if let d = try? JSONEncoder().encode(list) { UserDefaults.standard.set(d, forKey: key) }
    }
}

struct SearchQuery {
    var terms: [String] = []
    var excluded: [String] = []
    var tags: [String] = []
    var decks: [String] = []
    var isEmpty: Bool { terms.isEmpty && excluded.isEmpty && tags.isEmpty && decks.isEmpty }

    static func parse(_ s: String) -> SearchQuery {
        var q = SearchQuery()
        var tokens: [String] = []
        var cur = ""
        var inQuote = false
        for ch in s {
            if ch == "\"" { inQuote.toggle(); continue }
            if (ch == " " || ch == "\u{3000}") && !inQuote {
                if !cur.isEmpty { tokens.append(cur); cur = "" }
                continue
            }
            cur.append(ch)
        }
        if !cur.isEmpty { tokens.append(cur) }
        for t in tokens {
            let low = t.lowercased()
            if low.hasPrefix("tag:"), low.count > 4 { q.tags.append(String(low.dropFirst(4))) }
            else if low.hasPrefix("deck:"), low.count > 5 { q.decks.append(String(low.dropFirst(5))) }
            else if low.hasPrefix("-"), low.count > 1 { q.excluded.append(String(low.dropFirst())) }
            else { q.terms.append(low) }
        }
        return q
    }
}

/// アプリ全体の状態。コレクションの取り込み・読み込み・絞り込み・選択
@MainActor
final class Store: ObservableObject {
    @Published var notes: [Note] = []
    @Published var notetypes: [Int64: Notetype] = [:]
    @Published var decks: [Deck] = []
    @Published var noteDecks: [Int64: Int64] = [:]
    @Published var cards: [Int64: [CardInfo]] = [:]
    @Published var crt: Int64 = 0
    // 検索条件は次に開いたときも残す (macOS 版と同じ)
    @Published var keyword = UserDefaults.standard.string(forKey: "lastKeyword") ?? "" {
        didSet { UserDefaults.standard.set(keyword, forKey: "lastKeyword"); query = SearchQuery.parse(keyword); refilter() }
    }
    @Published var selectedTags: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "lastTags") ?? []) {
        didSet { UserDefaults.standard.set(Array(selectedTags), forKey: "lastTags"); refilter() }
    }
    @Published var selectedDeck: Int64? = Int64(UserDefaults.standard.string(forKey: "defaultDeck") ?? "") {
        didSet { UserDefaults.standard.set(selectedDeck.map(String.init) ?? "", forKey: "defaultDeck"); refilter() }
    }
    /// 学習状況での絞り込み: all / due / new / review / suspended / flagged
    @Published var statusFilter = UserDefaults.standard.string(forKey: "statusFilter") ?? "all" {
        didSet { UserDefaults.standard.set(statusFilter, forKey: "statusFilter"); refilter() }
    }
    @Published var sortMode: String = UserDefaults.standard.string(forKey: "sortMode") ?? "name" {
        didSet { UserDefaults.standard.set(sortMode, forKey: "sortMode"); refilter() }
    }
    /// 絞り込み・並べ替えの結果。条件やデータが変わったときだけ計算し直す
    /// (画面が描き直されるたびに 1,000 件を並べ替えていると、取り込み中などに操作できないほど重くなる)
    @Published private(set) var filtered: [Note] = []
    @Published private(set) var allTags: [(String, Int)] = []
    @Published private(set) var query = SearchQuery.parse(UserDefaults.standard.string(forKey: "lastKeyword") ?? "")
    /// 選んでいるカード (次に開いたときも同じカードに戻る)
    @Published var selectedNoteId: Int64? = {
        let v = UserDefaults.standard.string(forKey: "selectedNoteId") ?? ""
        return Int64(v)
    }() {
        didSet {
            UserDefaults.standard.set(selectedNoteId.map(String.init) ?? "", forKey: "selectedNoteId")
            if oldValue != selectedNoteId { templateIndex = 0; findText = "" }
        }
    }
    /// 複数カードタイプのノートで、どのカードを見るか
    @Published var templateIndex = 0
    /// カード内検索
    @Published var findText = ""
    @Published var loading = false
    @Published var error: String? = nil
    @Published var importedAt: Date? = UserDefaults.standard.object(forKey: "importedAt") as? Date
    /// 外部ファイルの同期: auto = アプリを開くたび / manual = 手動だけ
    @AppStorage("syncMode") var syncMode: String = "auto"
    /// 起動直後、同期フォルダの更新を確かめている間 (この間はアイコンの起動画面を見せる)
    @Published var checking = false
    /// 最後に同期フォルダへ書き出した時刻
    @Published var pushedAt: Date? = UserDefaults.standard.object(forKey: "pushedAt") as? Date
    /// 同期フォルダへ写すのを待っている変更があるか
    @Published var pendingPush = false
    private var pushTimer: Timer?

    @AppStorage("textZoom") var textZoom: Double = 1.0
    /// 表示する面: front / back
    @AppStorage("side") var side: String = "back"
    @AppStorage("showTagsInList") var showTagsInList: Bool = true
    @AppStorage("showDueInList") var showDueInList: Bool = true
    /// 見た目: system / light / dark
    @AppStorage("appearance") var appearance: String = "system"
    /// カード編集の標準モード: text / html
    @AppStorage("editorMode") var editorMode: String = "text"

    /// 取り込んだコレクションの置き場所 (アプリの中)
    static var dataDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("collection", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
    static var collectionPath: URL { dataDir.appendingPathComponent("collection.anki2") }
    static var mediaDir: URL { dataDir.appendingPathComponent("collection.media", isDirectory: true) }

    var hasCollection: Bool { FileManager.default.fileExists(atPath: Self.collectionPath.path) }

    init() { Task { await load() } }

    /// 前回取り込んだフォルダ (ブックマーク)。選び直さずに更新できるように覚えておく
    var lastImportURL: URL? {
        guard let data = UserDefaults.standard.data(forKey: "importBookmark") else { return nil }
        var stale = false
        return try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale)
    }
    var lastImportName: String? { UserDefaults.standard.string(forKey: "importName") }

    /// 前回のフォルダから取り込み直す
    func reimport() async {
        guard let url = lastImportURL else { error = "前回のフォルダが見つかりません。選び直してください"; return }
        await importFolder(url)
    }

    /// 取り込み元 (iCloud) の collection.anki2 の更新日時
    nonisolated static func sourceModified(_ folder: URL) -> Date? {
        let ok = folder.startAccessingSecurityScopedResource()
        defer { if ok { folder.stopAccessingSecurityScopedResource() } }
        let col = folder.appendingPathComponent("collection.anki2")
        let keys: Set<URLResourceKey> = [.contentModificationDateKey]
        return try? col.resourceValues(forKeys: keys).contentModificationDate
    }

    /// アプリを開いたとき: 同期フォルダのコレクションが前回より新しければ、裏で取り込み直す。
    /// force = true なら設定が「手動」でも確かめる (設定画面の「今すぐ同期」)
    func checkForUpdates(force: Bool = false) async {
        guard force || syncMode == "auto" else { return }
        guard !loading, !importingMedia, let url = lastImportURL else { return }
        checking = true
        defer { checking = false }
        // 自分の変更が未送信なら、先に送ってから確かめる (取り込みで上書きされないように)
        if pendingPush { await pushNow() }
        let last = UserDefaults.standard.object(forKey: "importSourceMod") as? Date
        let mod = await Task.detached { Self.sourceModified(url) }.value
        guard let mod, last == nil || mod > last! else { return }
        await importFolder(url)
    }

    /// 同期フォルダの表示用パス (iCloud Drive/Carrel/KSMN のように)
    var syncFolderDisplay: String? {
        guard let url = lastImportURL else { return nil }
        let p = url.path
        if let r = p.range(of: "com~apple~CloudDocs/") { return "iCloud Drive/" + p[r.upperBound...] }
        return url.lastPathComponent
    }

    /// 同期を解除する (アプリ内のデータはそのまま残す)
    func unlinkSyncFolder() {
        pushTimer?.invalidate(); pendingPush = false
        for k in ["importBookmark", "importName", "importSourceMod"] { UserDefaults.standard.removeObject(forKey: k) }
        objectWillChange.send()
    }

    // ---- 同期フォルダへの書き出し (アプリ内で作った・直した内容を Mac 側へ) ----

    /// 変更のあと少し待ってから写す (続けて操作しても 1 回にまとめる)
    private func schedulePush() {
        pendingPush = true
        pushTimer?.invalidate()
        pushTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: false) { [weak self] _ in
            Task { @MainActor in await self?.pushNow() }
        }
    }

    /// 同期フォルダへ今すぐ写す (collection.anki2 を一貫した形で書き出す)
    func pushNow() async {
        pushTimer?.invalidate()
        guard let url = lastImportURL, hasCollection else { pendingPush = false; return }
        let src = Self.collectionPath
        let result: Result<Date?, Error> = await Task.detached(priority: .utility) {
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            do {
                let db = try CollectionDB(path: src.path)
                let tmp = url.appendingPathComponent("collection.incoming")
                try? FileManager.default.removeItem(at: tmp)
                try db.backup(to: tmp)
                let dest = url.appendingPathComponent("collection.anki2")
                var coordErr: NSError?
                var innerErr: Error?
                NSFileCoordinator().coordinate(writingItemAt: dest, options: .forReplacing, error: &coordErr) { d in
                    do { _ = try FileManager.default.replaceItemAt(d, withItemAt: tmp) } catch { innerErr = error }
                }
                if let e = coordErr ?? (innerErr as NSError?) { throw e }
                for side in ["collection.anki2-wal", "collection.anki2-shm"] { try? FileManager.default.removeItem(at: url.appendingPathComponent(side)) }
                return .success(Self.sourceModified(url))
            } catch { return .failure(error) }
        }.value
        switch result {
        case .success(let mod):
            pendingPush = false
            pushedAt = Date(); UserDefaults.standard.set(pushedAt, forKey: "pushedAt")
            // 自分が書いたものを「Mac 側の更新」と取り違えて取り込み直さないように
            if let mod { UserDefaults.standard.set(mod, forKey: "importSourceMod") }
        case .failure(let e):
            error = "同期フォルダへ書き出せません: " + e.localizedDescription
        }
    }

    /// 書き込みを 1 つ行い、同期フォルダへの書き出しを予約する
    private func write(_ body: (CollectionDB) throws -> Void) -> Bool {
        do {
            let db = try CollectionDB(path: Self.collectionPath.path)
            try body(db)
            try db.touch()
        } catch { self.error = error.localizedDescription; return false }
        schedulePush()
        return true
    }

    private func rebuildTags() {
        var count: [String: Int] = [:]
        for n in notes { for t in n.tags { count[t, default: 0] += 1 } }
        allTags = count.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
    }

    // ---- ノートの作成・編集・削除 ----

    /// 新しいノートを作る。作ったノートを選ぶ
    func addNote(notetypeId: Int64, deckId: Int64, fields: [String], tags: [String]) {
        guard let nt = notetypes[notetypeId] else { return }
        let clean = Array(NSOrderedSet(array: tags.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })) as! [String]
        var newId: Int64 = 0
        guard write({ db in newId = try db.insertNote(notetypeId: notetypeId, deckId: deckId, fields: fields, tags: clean, templateCount: nt.templates.count) }) else { return }
        let now = Int64(Date().timeIntervalSince1970)
        let note = Note(id: newId, notetypeId: notetypeId, fields: fields, tags: clean, modified: now)
        notes.append(note)
        noteDecks[newId] = deckId
        cards[newId] = (0..<max(1, nt.templates.count)).map { ord in
            CardInfo(id: newId + Int64(ord) + 1, noteId: newId, ord: ord, deckId: deckId, queue: 0, type: 0, due: 0, ivl: 0, reps: 0, lapses: 0, flag: 0, factor: 0, left: 0, mod: now)
        }
        rebuildTags(); refilter()
        selectedNoteId = newId
    }

    /// 本文とタグを書き換える
    func updateNote(_ note: Note, fields: [String], tags: [String]) {
        let clean = Array(NSOrderedSet(array: tags.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })) as! [String]
        guard write({ db in try db.updateNote(id: note.id, fields: fields, tags: clean) }) else { return }
        if let i = notes.firstIndex(where: { $0.id == note.id }) {
            notes[i] = Note(id: note.id, notetypeId: note.notetypeId, fields: fields, tags: clean, modified: Int64(Date().timeIntervalSince1970))
        }
        rebuildTags(); refilter()
    }

    /// ノートを消す
    func deleteNote(_ note: Note) {
        guard write({ db in try db.deleteNote(id: note.id) }) else { return }
        notes.removeAll { $0.id == note.id }
        cards[note.id] = nil; noteDecks[note.id] = nil
        if selectedNoteId == note.id { selectedNoteId = nil }
        rebuildTags(); refilter()
    }

    // ---- 学習 ----

    /// 学習の条件に合うカード。期限の来た復習・学習中 → 新規の順 (ランダム指定なら混ぜる)
    func studyQueue(_ o: StudyOptions) -> [CardInfo] {
        let tagSet = Set(o.tags)
        var all = cards.values.flatMap { $0 }
        if !o.decks.isEmpty { all = all.filter { o.decks.contains($0.deckId) } }
        if !tagSet.isEmpty {
            let okNotes = Set(notes.filter { n in o.tagsAll ? tagSet.isSubset(of: Set(n.tags)) : !tagSet.isDisjoint(with: n.tags) }.map(\.id))
            all = all.filter { okNotes.contains($0.noteId) }
        }
        if !o.keyword.trimmingCharacters(in: .whitespaces).isEmpty {
            let q = SearchQuery.parse(o.keyword)
            let okNotes = Set(notes.filter { matches($0, q) }.map(\.id))
            all = all.filter { okNotes.contains($0.noteId) }
        }
        if !o.includeSuspended { all = all.filter { $0.queue >= 0 } }
        var review = all.filter { $0.queue != 0 && $0.queue >= 0 }
        if !o.includeNotDue { review = review.filter { Scheduler.isDue($0, crt: crt) } }
        review.sort { $0.due < $1.due }
        var fresh = all.filter { $0.queue == 0 }.sorted { $0.due < $1.due }
        if o.newLimit >= 0 { fresh = Array(fresh.prefix(o.newLimit)) }
        var out = o.newFirst ? fresh + review : review + fresh
        if o.random { out.shuffle() }
        if o.limit > 0 { out = Array(out.prefix(o.limit)) }
        return out
    }

    /// 学習の条件 (前回のものを覚える) と、名前を付けて保存した条件
    @Published var studyOptions: StudyOptions = StudyOptions.load("studyOptions") ?? StudyOptions() {
        didSet { studyOptions.save("studyOptions") }
    }
    @Published var studyPresets: [StudyOptions] = StudyOptions.loadList("studyPresets") {
        didSet { StudyOptions.saveList(studyPresets, "studyPresets") }
    }
    @Published var studySetup = false

    /// 答えを付けて記録する。次の状態のカードを返す
    @discardableResult
    func answer(_ card: CardInfo, ease: Scheduler.Ease, spentMs: Int) -> CardInfo {
        let r = Scheduler.answer(card, ease: ease, crt: crt)
        var c = r.card
        c.mod = Int64(Date().timeIntervalSince1970)
        guard write({ db in
            try db.updateCard(c)
            try db.insertRevlog(cardId: c.id, ease: ease.rawValue, ivl: r.revlogIvl, lastIvl: r.lastIvl, factor: c.factor, time: min(60000, spentMs), type: r.revlogType)
        }) else { return card }
        if var cs = cards[c.noteId], let i = cs.firstIndex(where: { $0.id == c.id }) { cs[i] = c; cards[c.noteId] = cs }
        refilter()
        return c
    }

    /// カードの状態 (停止など) を書き換える
    func setCardQueue(_ c: CardInfo) {
        var c = c; c.mod = Int64(Date().timeIntervalSince1970)
        guard write({ db in try db.updateCard(c) }) else { return }
        if var cs = cards[c.noteId], let i = cs.firstIndex(where: { $0.id == c.id }) { cs[i] = c; cards[c.noteId] = cs }
        refilter()
    }

    /// 学習用の HTML (表か裏)
    func studyHTML(card: CardInfo, back: Bool, dark: Bool) -> String {
        guard let note = notes.first(where: { $0.id == card.noteId }), let nt = notetypes[note.notetypeId] else { return "" }
        let r = TemplateRenderer.render(note: note, notetype: nt, templateIndex: min(card.ord, max(0, nt.templates.count - 1)))
        return TemplateRenderer.page(body: back ? (r.back.isEmpty ? r.front : r.back) : r.front, css: nt.css, textZoom: textZoom, dark: dark, hasNightCSS: nt.hasNightCSS)
    }

    /// 取り込みの進み具合 (画面に出す)
    @Published var importProgress = ""
    /// メディアを裏で写している最中か (一覧や閲覧はその間も使える)
    @Published var importingMedia = false

    /// collection.anki2 と collection.media のあるフォルダを取り込む。
    /// まずコレクション本体 (小さい) を写してすぐ一覧を出し、メディア (1 GB を超えることがある) は裏で差分だけ写す
    func importFolder(_ url: URL) async {
        loading = true; error = nil; importProgress = "準備しています…"
        let dataDir = Self.dataDir, mediaDst = Self.mediaDir, colDst = Self.collectionPath
        let report: @Sendable (String) -> Void = { text in Task { @MainActor in self.importProgress = text } }

        // 1) コレクション本体
        let first: Result<URL, Error> = await Task.detached(priority: .userInitiated) {
            let fm = FileManager.default
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            do {
                var src = url
                if url.pathExtension == "anki2" { src = url.deletingLastPathComponent() }
                let col = src.appendingPathComponent("collection.anki2")
                guard Self.exists(col) else {
                    throw NSError(domain: "AnkiPreview", code: 3, userInfo: [NSLocalizedDescriptionKey: "collection.anki2 が見つかりません"])
                }
                report("カードを写しています…")
                let tmp = dataDir.appendingPathComponent("collection.incoming")
                try? fm.removeItem(at: tmp)
                try Self.copyWithDownload(col, to: tmp)
                for side in ["collection.anki2-wal", "collection.anki2-shm"] { try? fm.removeItem(at: dataDir.appendingPathComponent(side)) }
                try? fm.removeItem(at: colDst)
                try fm.moveItem(at: tmp, to: colDst)
                return .success(src)
            } catch { return .failure(error) }
        }.value
        guard case .success(let src) = first else {
            if case .failure(let e) = first { self.error = e.localizedDescription }
            loading = false; importProgress = ""
            return
        }
        importedAt = Date()
        UserDefaults.standard.set(importedAt, forKey: "importedAt")
        // 次回は選び直さずに済むよう、フォルダの場所を覚える。
        // ブックマークは、セキュリティスコープのアクセス中でないと作れない (外で作ると失敗して覚えられなかった)
        do {
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            let bm = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            UserDefaults.standard.set(bm, forKey: "importBookmark")
            UserDefaults.standard.set(url.lastPathComponent, forKey: "importName")
        } catch {
            self.error = "フォルダの場所を覚えられませんでした: " + error.localizedDescription
        }
        // 取り込み元の更新日時。次に開いたとき、これより新しければ自動で取り込み直す
        if let mod = Self.sourceModified(url) { UserDefaults.standard.set(mod, forKey: "importSourceMod") }
        await load()          // ここで一覧が出る。以降はメディアを裏で写すだけ
        loading = false

        // 2) メディア (差分)。写している間も閲覧できる
        importingMedia = true
        importProgress = "メディアを確認しています…"
        let mediaResult: (copied: Int, missing: Int) = await Task.detached(priority: .utility) {
            let fm = FileManager.default
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            let mediaSrc = src.appendingPathComponent("collection.media")
            try? fm.createDirectory(at: mediaDst, withIntermediateDirectories: true)
            guard Self.exists(mediaSrc), let names = try? fm.contentsOfDirectory(atPath: mediaSrc.path) else { return (0, 0) }
            let list = names.filter { !$0.hasPrefix(".") }
            let have: [String: Int] = Dictionary(uniqueKeysWithValues:
                ((try? fm.contentsOfDirectory(atPath: mediaDst.path)) ?? []).map { ($0, Self.size(mediaDst.appendingPathComponent($0))) })
            var copied = 0, missing = 0
            for (i, name) in list.enumerated() {
                // 画面の更新は 20 件ごとに (1 件ごとに描き直すと操作が重くなる)
                if i % 20 == 0 || i == list.count - 1 {
                    report("メディアを写しています… \(i + 1) / \(list.count)" + (missing > 0 ? " (未ダウンロード \(missing))" : ""))
                }
                let from = mediaSrc.appendingPathComponent(name)
                let to = mediaDst.appendingPathComponent(name)
                let srcSize = Self.size(from)
                if let had = have[name], had == srcSize, srcSize > 0 { continue }
                try? fm.removeItem(at: to)
                do {
                    try Self.copyWithDownload(from, to: to); copied += 1
                    // 写し終えた画像から順にプレビューに出す
                    if copied % 25 == 0 { Task { @MainActor in self.mediaVersion += 1 } }
                } catch { missing += 1 }
            }
            return (copied, missing)
        }.value
        importingMedia = false
        importProgress = ""
        if mediaResult.missing > 0 {
            self.error = "\(mediaResult.missing) 件のメディアがまだ iCloud から落ちていません。Mac 側のアップロードが終わってから、もう一度取り込むと続きを写します"
        }
        // 写し終えたメディアがプレビューに出るよう、開いているカードを描き直す
        mediaVersion += 1
    }

    /// メディアが増えたら上げる (プレビューの描き直しのきっかけ)
    @Published var mediaVersion = 0
    /// 拡大表示中の画像 (画面全体に対して出すため、ここで持つ)
    @Published var lightbox: LightboxItem? = nil
    /// 作成・編集中のノート (nil のノートは新規)
    @Published var editing: EditTarget? = nil
    @Published var studying = false
    @Published var showGuide = false

    nonisolated private static func exists(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) { return true }
        // iCloud でまだ落ちていない場合は、ダウンロードを頼んでから見直す
        try? FileManager.default.startDownloadingUbiquitousItem(at: url)
        return FileManager.default.fileExists(atPath: url.path)
    }

    nonisolated private static func size(_ url: URL) -> Int {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    }

    /// iCloud 上でまだ落ちていないファイルでも、落としてから写す
    nonisolated private static func copyWithDownload(_ from: URL, to: URL) throws {
        let fm = FileManager.default
        var lastError: Error? = nil
        for attempt in 0..<6 {
            do {
                var err: NSError? = nil
                var inner: Error? = nil
                NSFileCoordinator().coordinate(readingItemAt: from, options: [], error: &err) { real in
                    do { try fm.copyItem(at: real, to: to) } catch { inner = error }
                }
                if let err { throw err }
                if let inner { throw inner }
                return
            } catch {
                lastError = error
                try? fm.startDownloadingUbiquitousItem(at: from)
                Thread.sleep(forTimeInterval: attempt < 3 ? 0.5 : 1.5)
            }
        }
        throw lastError ?? NSError(domain: "AnkiPreview", code: 4, userInfo: [NSLocalizedDescriptionKey: "ファイルを写せません"])
    }

    func load() async {
        guard hasCollection else { return }
        loading = true; error = nil
        defer { loading = false }
        let path = Self.collectionPath.path
        do {
            let result = try await Task.detached(priority: .userInitiated) { () throws -> ([Note], [Notetype], [Deck], [CardInfo], Int64) in
                let db = try CollectionDB(path: path)
                return (try db.notes(), try db.notetypes(), try db.decks(), try db.cards(), try db.creationTime())
            }.value
            notes = result.0
            notetypes = Dictionary(uniqueKeysWithValues: result.1.map { ($0.id, $0) })
            cards = Dictionary(grouping: result.3, by: { $0.noteId })
            crt = result.4
            var nd: [Int64: Int64] = [:]
            for c in result.3 where nd[c.noteId] == nil { nd[c.noteId] = c.deckId }
            noteDecks = nd
            decks = result.2.filter { $0.name != "Default" || nd.values.contains($0.id) }
            if let sel = selectedNoteId, !notes.contains(where: { $0.id == sel }) { selectedNoteId = nil }
            if let d = selectedDeck, !decks.contains(where: { $0.id == d }) { selectedDeck = nil }
            var count: [String: Int] = [:]
            for n in notes { for t in n.tags { count[t, default: 0] += 1 } }
            allTags = count.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            refilter()
        } catch {
            self.error = "コレクションを読めません: " + error.localizedDescription
        }
    }

    // ---- 編集 (この iPhone 内のコピーだけが変わる。Anki には反映されない) ----

    func setTags(_ note: Note, _ tags: [String]) {
        let clean = Array(NSOrderedSet(array: tags.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })) as! [String]
        guard write({ db in try db.setTags(noteId: note.id, tags: clean) }) else { return }
        if let i = notes.firstIndex(where: { $0.id == note.id }) {
            notes[i] = Note(id: note.id, notetypeId: note.notetypeId, fields: note.fields, tags: clean, modified: Int64(Date().timeIntervalSince1970))
        }
        var count: [String: Int] = [:]
        for n in notes { for t in n.tags { count[t, default: 0] += 1 } }
        allTags = count.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
        refilter()
    }

    func setDeck(_ note: Note, _ deckId: Int64) {
        guard write({ db in try db.setDeck(noteId: note.id, deckId: deckId) }) else { return }
        noteDecks[note.id] = deckId
        if var cs = cards[note.id] {
            for i in cs.indices { cs[i].deckId = deckId }
            cards[note.id] = cs
        }
        refilter()
    }

    // ---- 学習状況 ----

    func deckName(for note: Note) -> String {
        guard let d = noteDecks[note.id] else { return "" }
        return decks.first { $0.id == d }?.name ?? ""
    }

    /// 今日の終わり (期限が今日までのものを「期限切れ」とみなす)
    private var endOfToday: Date {
        Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: Date())) ?? Date()
    }

    /// ノートの中でいちばん早い期限
    func nextDue(for note: Note) -> Date? {
        (cards[note.id] ?? []).compactMap { $0.dueDate(crt: crt) }.min()
    }

    func isDue(_ note: Note) -> Bool {
        guard let d = nextDue(for: note) else { return false }
        return d < endOfToday
    }

    /// 一覧の右端に出す短い状態 ("今日" "3日後" "期限切れ" "未学習" …)
    func dueLabel(for note: Note) -> (text: String, color: Color)? {
        let cs = cards[note.id] ?? []
        guard !cs.isEmpty else { return nil }
        if cs.allSatisfy({ $0.status == .suspended }) { return ("停止中", .secondary) }
        if let d = nextDue(for: note) {
            let days = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: Date()), to: Calendar.current.startOfDay(for: d)).day ?? 0
            if days < 0 { return ("期限切れ", .red) }
            if days == 0 { return ("今日", .orange) }
            if days < 30 { return ("\(days)日後", .secondary) }
            if days < 365 { return ("\(days / 30)か月後", .secondary) }
            return ("\(days / 365)年後", .secondary)
        }
        if cs.contains(where: { $0.status == .new }) { return ("未学習", .blue) }
        return nil
    }

    static let flagColors: [Int: Color] = [1: .red, 2: .orange, 3: .green, 4: .blue, 5: .pink, 6: .teal, 7: .purple]
    func flagColor(for note: Note) -> Color? {
        guard let f = (cards[note.id] ?? []).map(\.flag).first(where: { $0 > 0 }) else { return nil }
        return Self.flagColors[f]
    }

    // ---- 絞り込み ----


    /// ハイライトする語 (検索欄の語 + カード内検索)
    var highlightTerms: [String] {
        var t = query.terms
        let f = findText.trimmingCharacters(in: .whitespaces)
        if !f.isEmpty { t.append(f) }
        return t
    }

    private func matches(_ n: Note, _ q: SearchQuery) -> Bool {
        if q.isEmpty { return true }
        let text = n.plainLower
        let tags = n.tags.map { $0.lowercased() }
        for t in q.terms where !(text.contains(t) || tags.contains { $0.contains(t) }) { return false }
        for t in q.excluded where text.contains(t) { return false }
        for t in q.tags where !tags.contains(where: { $0.contains(t) }) { return false }
        if !q.decks.isEmpty {
            let d = deckName(for: n).lowercased()
            for t in q.decks where !d.contains(t) { return false }
        }
        return true
    }

    private func passesStatus(_ n: Note) -> Bool {
        let cs = cards[n.id] ?? []
        switch statusFilter {
        case "due": return isDue(n)
        case "new": return cs.contains { $0.status == .new }
        case "review": return cs.contains { $0.status == .review || $0.status == .learning }
        case "suspended": return cs.contains { $0.status == .suspended }
        case "flagged": return cs.contains { $0.flag > 0 }
        default: return true
        }
    }

    /// macOS 版と同じ関連度の点数
    private func score(_ n: Note, _ needle: String) -> Int {
        var p = 0
        let title = n.titleLines.joined(separator: " ").lowercased()
        if title.hasPrefix(needle) { p += 100 }
        if title.contains(needle) { p += 50 }
        if n.plainLower.contains(needle) { p += 10 }
        return p
    }

    func refilter() {
        let q = query
        let needle = keyword.trimmingCharacters(in: .whitespaces).lowercased()
        var list = notes.filter { n in
            if let d = selectedDeck, noteDecks[n.id] != d { return false }
            if !selectedTags.isEmpty, !selectedTags.isSubset(of: Set(n.tags)) { return false }
            if !passesStatus(n) { return false }
            return matches(n, q)
        }
        // 語で検索しているときは、並びが「名前」のままなら自動で関連度順に (macOS 版と同じ)
        let mode = (!needle.isEmpty && (sortMode == "name" || sortMode == "relevance")) ? "relevance" : sortMode
        switch mode {
        case "relevance":
            let points = Dictionary(uniqueKeysWithValues: list.map { ($0.id, score($0, needle)) })
            list.sort { (points[$0.id] ?? 0) != (points[$1.id] ?? 0) ? (points[$0.id] ?? 0) > (points[$1.id] ?? 0) : $0.sortKey < $1.sortKey }
        case "modified": list.sort { $0.modified > $1.modified }
        case "created": list.sort { $0.id > $1.id }
        case "due":
            let far = Date.distantFuture
            list.sort { (nextDue(for: $0) ?? far) < (nextDue(for: $1) ?? far) }
        case "reps":
            list.sort { (cards[$0.id] ?? []).reduce(0) { $0 + $1.reps } > (cards[$1.id] ?? []).reduce(0) { $0 + $1.reps } }
        case "lapses":
            list.sort { (cards[$0.id] ?? []).reduce(0) { $0 + $1.lapses } > (cards[$1.id] ?? []).reduce(0) { $0 + $1.lapses } }
        default:
            list.sort { $0.sortKey < $1.sortKey }
        }
        filtered = list
    }

    var hasActiveFilter: Bool { selectedDeck != nil || !selectedTags.isEmpty || statusFilter != "all" }

    func clearFilters() { selectedDeck = nil; selectedTags = []; statusFilter = "all"; keyword = "" }

    /// 並べ替え用: 丸数字を 3 桁の数字に直す (macOS 版と同じ)

    var selectedNote: Note? { notes.first { $0.id == selectedNoteId } }

    // ---- 前後の移動 ----

    var selectedIndex: Int? {
        guard let id = selectedNoteId else { return nil }
        return filtered.firstIndex { $0.id == id }
    }

    func move(by delta: Int) {
        let list = filtered
        guard !list.isEmpty else { return }
        let i = selectedIndex ?? (delta > 0 ? -1 : list.count)
        let j = min(max(i + delta, 0), list.count - 1)
        selectedNoteId = list[j].id
    }

    func pickRandom() {
        let list = filtered.filter { $0.id != selectedNoteId }
        if let n = list.randomElement() { selectedNoteId = n.id }
    }

    // ---- プレビュー ----

    /// 既定の暗い地色 (ノートタイプが夜間モードの色を持たないとき)
    static let darkBackground = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255)

    /// カードの地色 (CSS の body { background: … } から読む)。窓の隙間やツールバーの色をそろえるため
    func cardBackground(for note: Note, dark: Bool) -> Color? {
        guard let nt = notetypes[note.notetypeId] else { return dark ? Self.darkBackground : nil }
        let css = nt.css
        if dark {
            // 夜間モードの指定があればその色、なければ既定の暗い色
            let pattern = "\\.(?:night_mode|nightMode)[^{]*\\{[^}]*?background(?:-color)?\\s*:\\s*(#[0-9a-fA-F]{3,8})"
            if let re = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]),
               let m = re.firstMatch(in: css, range: NSRange(location: 0, length: (css as NSString).length)),
               let c = Color(hex: (css as NSString).substring(with: m.range(at: 1))) { return c }
            return Self.darkBackground
        }
        let pattern = "body\\s*\\{[^}]*?background(?:-color)?\\s*:\\s*(#[0-9a-fA-F]{3,8})"
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]),
              let m = re.firstMatch(in: css, range: NSRange(location: 0, length: (css as NSString).length)) else { return nil }
        return Color(hex: (css as NSString).substring(with: m.range(at: 1)))
    }

    /// プレビューの HTML。表面だけ、または裏面 (macOS 版の「カード」表示と同じ)
    func previewHTML(for note: Note, dark: Bool) -> String {
        guard let nt = notetypes[note.notetypeId] else { return TemplateRenderer.page(body: "<p>ノートタイプが見つかりません</p>", css: "", textZoom: textZoom, dark: dark, hasNightCSS: false) }
        let idx = min(templateIndex, max(nt.templates.count - 1, 0))
        let r = TemplateRenderer.render(note: note, notetype: nt, templateIndex: idx)
        let body: String
        if side == "front" { body = r.front } else { body = r.back.isEmpty ? r.front : r.back }
        return TemplateRenderer.page(body: body, css: nt.css, textZoom: textZoom, dark: dark, hasNightCSS: nt.hasNightCSS)
    }

    /// 共有用の文章 (表と裏)
    func shareText(for note: Note) -> String {
        guard let nt = notetypes[note.notetypeId] else { return note.plain }
        let r = TemplateRenderer.render(note: note, notetype: nt, templateIndex: templateIndex)
        let f = Note.stripHTML(r.front.replacingOccurrences(of: "<br>", with: "\n").replacingOccurrences(of: "</li>", with: "\n").replacingOccurrences(of: "</div>", with: "\n").replacingOccurrences(of: "</p>", with: "\n"))
        let b = Note.stripHTML(r.back.replacingOccurrences(of: "<br>", with: "\n").replacingOccurrences(of: "</li>", with: "\n").replacingOccurrences(of: "</div>", with: "\n").replacingOccurrences(of: "</p>", with: "\n"))
        return b.isEmpty ? f : f + "\n\n----\n\n" + b
    }
}


extension Color {
    init?(hex: String) {
        var h = hex.trimmingCharacters(in: .whitespaces); h.removeFirst()
        if h.count == 3 { h = h.map { "\($0)\($0)" }.joined() }
        guard h.count == 6 || h.count == 8, let v = UInt64(h.prefix(6), radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
    /// 暗い色かどうか (文字色を決めるため)
    var isDark: Bool {
        let ui = UIColor(self); var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 0.5
    }
}
