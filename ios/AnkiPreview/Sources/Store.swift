import Foundation
import SwiftUI
import Combine

/// 検索欄の文字列を分解したもの。スペース区切りで AND、"-語" で除外、tag: / deck: で絞り込み、"…" で句
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

    @AppStorage("textZoom") var textZoom: Double = 1.0
    /// 表示する面: front / back
    @AppStorage("side") var side: String = "back"
    @AppStorage("showTagsInList") var showTagsInList: Bool = true
    @AppStorage("showDueInList") var showDueInList: Bool = true
    /// 見た目: system / light / dark
    @AppStorage("appearance") var appearance: String = "system"

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
        // 次回は選び直さずに済むよう、フォルダの場所を覚える
        if let bm = try? url.bookmarkData() {
            UserDefaults.standard.set(bm, forKey: "importBookmark")
            UserDefaults.standard.set(url.lastPathComponent, forKey: "importName")
        }
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

    func refilter() {
        let q = query
        var list = notes.filter { n in
            if let d = selectedDeck, noteDecks[n.id] != d { return false }
            if !selectedTags.isEmpty, !selectedTags.isSubset(of: Set(n.tags)) { return false }
            if !passesStatus(n) { return false }
            return matches(n, q)
        }
        switch sortMode {
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
