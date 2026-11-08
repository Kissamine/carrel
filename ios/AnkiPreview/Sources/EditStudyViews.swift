import SwiftUI

// MARK: - ノートの作成・編集

struct NoteEditorView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss
    /// nil なら新規
    let note: Note?

    @State private var notetypeId: Int64 = 0
    @State private var deckId: Int64 = 0
    @State private var fields: [String] = []
    /// 開いたときの本文 (変えていない欄は元の HTML のまま保存する)
    @State private var original: [String] = []
    @State private var tags: [String] = []
    @State private var newTag = ""
    /// text = 文章として編集 (改行はそのまま) / html = HTML をそのまま編集
    @State private var mode = "text"
    /// 初期化が終わるまでモード切り替えの変換はしない (標準が HTML のとき、開いた直後の切り替えでタグが逃がされてしまうため)
    @State private var ready = false
    @FocusState private var focusedField: Int?

    private var notetype: Notetype? { store.notetypes[notetypeId] }

    /// HTML → 文章 (Markdown の記法に戻す。改行タグは改行に、ほかのタグは外す)
    static func toText(_ html: String) -> String { Markdown.fromHTML(html) }
    /// 文章 → HTML (Markdown の基本記法と改行を HTML に)
    static func toHTML(_ text: String) -> String { Markdown.toHTML(text) }
    /// 書式や画像など、文章にすると失われるものを含むか
    static func hasRichHTML(_ html: String) -> Bool {
        let simple = html.replacingOccurrences(of: "<br\\s*/?>|</?div>|</?p>|</?li>|</?ul>|</?ol>|</?(b|strong|i|em|s|del|code)>|</?h[1-6][^>]*>|<a [^>]*href=\"[^\"]+\"[^>]*>|</a>", with: "", options: [.regularExpression, .caseInsensitive])
        return simple.contains("<")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("ノートタイプ", selection: $notetypeId) {
                        ForEach(store.notetypes.values.sorted { $0.name < $1.name }) { nt in Text(nt.name).tag(nt.id) }
                    }
                    .onChange(of: notetypeId) { old, new in if ready { remapFields(from: old, to: new) } }
                    Picker("デッキ", selection: $deckId) {
                        ForEach(store.decks) { d in Text(d.name).tag(d.id) }
                    }
                }
                Section {
                    Picker("編集モード", selection: $mode) {
                        Text("テキスト").tag("text")
                        Text("HTML").tag("html")
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: mode) { old, new in if ready { switchMode(from: old, to: new) } }
                }
                if let nt = notetype {
                    ForEach(Array(nt.fieldNames.enumerated()), id: \.offset) { i, name in
                        Section {
                            let binding = Binding(get: { i < fields.count ? fields[i] : "" }, set: { if i < fields.count { fields[i] = $0 } })
                            if mode == "html" {
                                CodeTextView(text: binding, language: .html, minHeight: i == 0 ? 60 : 90)
                            } else {
                                TextEditor(text: binding)
                                    .frame(minHeight: i == 0 ? 60 : 90)
                                    .font(.body)
                                    .autocorrectionDisabled()
                                    .focused($focusedField, equals: i)
                            }
                        } header: { Text(name) } footer: {
                            if mode == "text", i < original.count, Self.hasRichHTML(original[i]) {
                                Text("この欄には書式や画像が含まれます。テキストモードで書き換えると書式は失われます (HTML モードならそのまま直せます)")
                            }
                        }
                    }
                }
                Section {
                    if !tags.isEmpty {
                        TagChips(tags: tags, wrap: true) { t in tags.removeAll { $0 == t } }
                            .padding(.vertical, 2)
                    }
                    HStack {
                        TextField("新しいタグを入力", text: $newTag)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .submitLabel(.done)
                            .onSubmit(addTypedTag)
                        if !newTag.trimmingCharacters(in: .whitespaces).isEmpty {
                            Button { addTypedTag() } label: { Image(systemName: "plus.circle.fill") }
                                .buttonStyle(.plain)
                        }
                        // 既存のタグから選ぶ
                        let candidates = store.allTags.map(\.0).filter { !tags.contains($0) }
                        if !candidates.isEmpty {
                            Menu {
                                ForEach(candidates.prefix(40), id: \.self) { t in Button(t) { tags.append(t) } }
                            } label: { Image(systemName: "tag.circle").imageScale(.large) }
                        }
                    }
                } header: { Text("タグ") } footer: {
                    Text(note == nil
                         ? "作ったカードは同期フォルダに自動で保存され、macOS 版 Carrel や Anki でも使えます。本文は HTML としてそのまま保存されます (改行は <br>)。"
                         : "本文は HTML のまま編集します。変更は同期フォルダへ自動で保存されます。")
                }
            }
            .contentMargins(.top, 18, for: .scrollContent)   // 題とのあいだを少しあける
            .navigationTitle(note == nil ? "新しいカード" : "カードを編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Image(systemName: "xmark") } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }.fontWeight(.semibold)
                        .disabled(fields.allSatisfy { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
                }
            }
            .onAppear(perform: setup)
        }
    }

    private func setup() {
        if let note {
            notetypeId = note.notetypeId
            deckId = store.noteDecks[note.id] ?? store.decks.first?.id ?? 1
            fields = note.fields
            original = note.fields
            tags = note.tags
        } else {
            notetypeId = store.notetypes.values.sorted { $0.name < $1.name }.first?.id ?? 0
            if let sel = store.selectedNote { notetypeId = sel.notetypeId; deckId = store.noteDecks[sel.id] ?? 0 }
            if deckId == 0 { deckId = store.selectedDeck ?? store.decks.first?.id ?? 1 }
            resizeFields()
            original = fields
            tags = Array(store.selectedTags)
            focusedField = 0
        }
        mode = store.editorMode == "html" ? "html" : "text"
        if mode == "text" { fields = fields.map(Self.toText) }
        DispatchQueue.main.async { ready = true }
    }

    /// モードの切り替え: HTML → 文章 / 文章 → HTML に本文を変換する
    private func switchMode(from old: String, to new: String) {
        guard old != new else { return }
        if new == "text" {
            fields = fields.map(Self.toText)
        } else {
            // 文章モードで変えていない欄は元の HTML に戻す (書式を失わないため)
            fields = fields.enumerated().map { i, t in
                if i < original.count, Self.toText(original[i]) == t { return original[i] }
                return Self.toHTML(t)
            }
        }
    }

    private func resizeFields() {
        let n = notetype?.fieldNames.count ?? 0
        if fields.count < n { fields += Array(repeating: "", count: n - fields.count) }
        if fields.count > n { fields = Array(fields.prefix(n)) }
    }

    /// ノートタイプを変えたとき、同じ名前の欄はそのまま、それ以外は位置で写す
    private func remapFields(from old: Int64, to new: Int64) {
        guard let oldNT = store.notetypes[old], let newNT = store.notetypes[new] else { resizeFields(); return }
        let oldFields = fields
        var used = Set<Int>()
        fields = newNT.fieldNames.enumerated().map { i, name -> String in
            if let j = oldNT.fieldNames.firstIndex(of: name), j < oldFields.count { used.insert(j); return oldFields[j] }
            return ""
        }
        // 名前が合わなかった欄は、空いている位置へ順に
        var leftovers = oldFields.enumerated().filter { !used.contains($0.offset) && !$0.element.isEmpty }.map(\.element)
        for i in fields.indices where fields[i].isEmpty && !leftovers.isEmpty { fields[i] = leftovers.removeFirst() }
        original = Array(repeating: "", count: fields.count)   // 元の HTML との対応は切れる
    }

    private func addTypedTag() {
        let t = newTag.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "_")
        guard !t.isEmpty else { return }
        if !tags.contains(t) { tags.append(t) }
        newTag = ""
    }

    private func save() {
        // テキストモード: 変えていない欄は元の HTML のまま、変えた欄だけ文章 → HTML に
        let body: [String] = mode == "html" ? fields : fields.enumerated().map { i, t in
            if i < original.count, Self.toText(original[i]) == t { return original[i] }
            return Self.toHTML(t)
        }
        if let note {
            if note.notetypeId != notetypeId {
                Task { await store.changeNotetype(note, to: notetypeId, fields: body, tags: tags); store.setDeck(note, deckId) }
            } else {
                store.updateNote(note, fields: body, tags: tags)
                if store.noteDecks[note.id] != deckId { store.setDeck(note, deckId) }
            }
        } else { store.addNote(notetypeId: notetypeId, deckId: deckId, fields: body, tags: tags) }
        dismiss()
    }
}

// MARK: - 学習

struct StudyView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var scheme
    let options: StudyOptions

    @State private var queue: [CardInfo] = []
    @State private var showBack = false
    @State private var shownAt = Date()
    @State private var done = 0
    @State private var total = 0
    /// 長押しで出す短い説明 (「あとで」「スキップ」)
    @State private var tip: String? = nil
    @State private var tipTask: Task<Void, Never>? = nil
    @StateObject private var preview = PreviewController()

    private var current: CardInfo? { queue.first }

    var body: some View {
        NavigationStack {
            Group {
                if let card = current, let note = store.notes.first(where: { $0.id == card.noteId }) {
                    let dark = scheme == .dark
                    let bg = store.cardBackground(for: note, dark: dark) ?? Color(.systemBackground)
                    // カードは画面いっぱい (上下の余白なし)。カウンターとボタンはその上に重ねる (地は透明) ので、
                    // Liquid Glass が中身を透かす。中身がボタンに隠れないぶんは HTML 側の余白でとる
                    // 地はフェードの外に置く (切り替えの一瞬、下の画面が透けないように)
                    ZStack {
                        bg.ignoresSafeArea()
                        PreviewView(html: store.studyHTML(card: card, back: showBack, dark: dark), mediaDir: Store.mediaDir,
                                    highlights: [], controller: preview,
                                    onImage: { url in store.lightbox = LightboxItem(url: url) })
                            .ignoresSafeArea()
                            .id(card.id)                 // カードが変わったら作り直して、フェードで切り替える
                            .transition(.opacity)
                    }
                    .overlay(alignment: .bottom) { answerBar(card) }
                    // カウンター (プレビューの「1 / 114」と同じ見た目)。スクロールしても動かないよう、カードの上に重ねて固定
                    .overlay(alignment: .top) {
                        Text("\(min(done + 1, total)) / \(total)")
                            .font(.footnote).monospacedDigit().foregroundStyle(.secondary).fixedSize()
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .glassEffect(.regular, in: Capsule())
                            .padding(.top, 4)
                    }
                    .toolbarBackground(.hidden, for: .navigationBar)
                    .toolbarColorScheme(bg.isDark ? .dark : .light, for: .navigationBar)
                } else {
                    ContentUnavailableView {
                        Label(done > 0 ? "今日の学習はおわり" : "学習するカードがありません", systemImage: "checkmark.seal")
                    } description: {
                        Text(done > 0 ? "\(done) 枚を学習しました。おつかれさまでした" : "期限の来たカードも未学習のカードもありません")
                    } actions: {
                        Button("閉じる") { dismiss() }.buttonStyle(.borderedProminent)
                    }
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Image(systemName: "xmark") } }

                if let card = current {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button { showBack.toggle() } label: { Label(showBack ? "表を見る" : "答えを見る", systemImage: "rectangle.portrait") }
                            Divider()
                            Button(role: .destructive) { suspend(card) } label: { Label("このカードを停止", systemImage: "pause.circle") }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            }
            .onAppear { queue = store.studyQueue(options); total = queue.count; shownAt = Date() }
        }
    }

    /// 下のボタン: 「答えを見る」→ 4 段階の評価
    @ViewBuilder
    private func answerBar(_ card: CardInfo) -> some View {
        HStack(spacing: 10) {
            if !showBack {
                // あとで (列の最後に回す) / スキップ (今回は出さない) / 答えを見る
                Button { skip() } label: {
                    Image(systemName: "arrow.uturn.forward").font(.title3).frame(width: 56, height: 26).padding(.vertical, 6)
                }
                .buttonStyle(.glass)
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.35).onEnded { _ in showTip("あとで (列の最後へ)") })
                Button { dropCurrent() } label: {
                    Image(systemName: "forward.end").font(.title3).frame(width: 56, height: 26).padding(.vertical, 6)
                }
                .buttonStyle(.glass)
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.35).onEnded { _ in showTip("スキップ (今回は出さない)") })
                Button { showBack = true } label: {
                    Text("答えを見る").font(.headline).frame(maxWidth: .infinity, minHeight: 26).padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
                .tint(Color.carrelBlueDeep)   // テーマの水色 (宮古島ブルー) の Liquid Glass
            } else {
                ForEach(Scheduler.Ease.allCases, id: \.rawValue) { ease in
                    Button { answer(card, ease) } label: {
                        Text(ease.label).font(.title2.weight(.bold))
                            .foregroundStyle(color(for: ease))
                            .frame(maxWidth: .infinity, minHeight: 26).padding(.vertical, 6)   // 「答えを見る」と同じ高さ
                    }
                    .buttonStyle(.glass)
                    // 長押しで次の間隔 ("10分" "4日" など)
                    .simultaneousGesture(LongPressGesture(minimumDuration: 0.35).onEnded { _ in showTip("\(ease.name)：\(Scheduler.preview(card, ease: ease, crt: store.crt))後") })
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        // 長押しの説明 (ボタンの上に一瞬だけ)
        .overlay(alignment: .top) {
            if let tip {
                Text(tip).font(.footnote).padding(.horizontal, 12).padding(.vertical, 6)
                    .glassEffect(.regular, in: Capsule())
                    .offset(y: -40)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.2), value: tip)
    }

    private func showTip(_ text: String) {
        tipTask?.cancel()
        tip = text
        tipTask = Task { try? await Task.sleep(for: .seconds(1.4)); if !Task.isCancelled { tip = nil } }
    }

    private func color(for ease: Scheduler.Ease) -> Color {
        switch ease { case .again: .red; case .hard: .orange; case .good: .green; case .easy: .blue }
    }

    private func answer(_ card: CardInfo, _ ease: Scheduler.Ease) {
        let spent = Int(Date().timeIntervalSince(shownAt) * 1000)
        // Store の更新も同じトランザクションに入れないと、先に描き直されてフェードが付かない
        withAnimation(.easeInOut(duration: 0.3)) {
            let next = store.answer(card, ease: ease, spentMs: spent)
            queue.removeFirst()
            // すぐ (10 分以内) にまた出る学習中カードは、列の後ろに戻す
            if next.queue == 1, Double(next.due) - Date().timeIntervalSince1970 < 600 { queue.append(next) }
            done += 1
            showBack = false
        }
        shownAt = Date()
    }

    private func skip() {
        guard !queue.isEmpty else { return }
        withAnimation(.easeInOut(duration: 0.3)) { queue.append(queue.removeFirst()); showBack = false }
        shownAt = Date()
    }

    /// 今回の学習からは外す (記録はしない)
    private func dropCurrent() {
        guard !queue.isEmpty else { return }
        withAnimation(.easeInOut(duration: 0.3)) { queue.removeFirst(); showBack = false }
        shownAt = Date()
    }

    private func suspend(_ card: CardInfo) {
        var c = card; c.queue = -1
        store.setCardQueue(c)
        withAnimation(.easeInOut(duration: 0.3)) { queue.removeFirst(); showBack = false }
        shownAt = Date()
    }
}

// MARK: - ノートタイプの編集 (欄・テンプレート・CSS)

struct NotetypeEditorView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss
    let notetypeId: Int64
    @State private var draft: NotetypeDraft? = nil
    @State private var templateIndex = 0
    @State private var newField = ""
    @State private var confirmSave = false

    var body: some View {
        Group {
            if let d = Binding($draft) {
                Form {
                    Section("名前") { TextField("ノートタイプの名前", text: d.name) }
                    Section {
                        ForEach(d.fields) { f in
                            TextField("欄の名前", text: f.name)
                        }
                        .onMove { from, to in draft?.fields.move(fromOffsets: from, toOffset: to) }
                        .onDelete { idx in if (draft?.fields.count ?? 0) > 1 { draft?.fields.remove(atOffsets: idx) } }
                        HStack {
                            TextField("新しい欄を追加", text: $newField).onSubmit(addField)
                            if !newField.trimmingCharacters(in: .whitespaces).isEmpty {
                                Button { addField() } label: { Image(systemName: "plus.circle.fill") }.buttonStyle(.plain)
                            }
                        }
                    } header: {
                        HStack { Text("欄"); Spacer(); EditButton().font(.footnote) }
                    } footer: {
                        Text("並べ替えや削除をすると、このノートタイプの全カードの本文も同じように並べ替わります。削除した欄の内容は消えます。")
                    }
                    if d.wrappedValue.templates.count > 1 {
                        Section {
                            Picker("カードタイプ", selection: $templateIndex) {
                                ForEach(Array(d.wrappedValue.templates.enumerated()), id: \.offset) { i, t in Text(t.name).tag(i) }
                            }
                        }
                    }
                    if templateIndex < d.wrappedValue.templates.count {
                        let t = d.templates[templateIndex]
                        Section("表面") { codeEditor(t.front) }
                        Section("裏面") { codeEditor(t.back) }
                    }
                    Section {
                        codeEditor(d.css, minHeight: 160, css: true)
                    } header: { Text("CSS") } footer: {
                        Text("欄は {{欄の名前}}、裏面で表面を出すには {{FrontSide}} と書きます。Anki と同じ書き方です。")
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(draft?.name ?? "ノートタイプ")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { if structureChanged { confirmSave = true } else { save() } }.fontWeight(.semibold)
                    .disabled(draft == nil || (draft?.fields.isEmpty ?? true))
            }
        }
        .confirmationDialog("欄の構成を変えます", isPresented: $confirmSave, titleVisibility: .visible) {
            Button("保存する", role: .destructive) { save() }
        } message: { Text("このノートタイプの全カードの本文が、新しい欄の並びに書き換わります。削除した欄の内容は元に戻せません") }
        .onAppear { if draft == nil { draft = store.notetypeDraft(notetypeId) } }
    }

    private var structureChanged: Bool {
        guard let d = draft else { return false }
        return d.fields.map { $0.originalOrd } != Array(0..<d.fields.count).map { Optional($0) } || d.fields.count != (store.notetypes[notetypeId]?.fieldNames.count ?? 0)
    }

    private func codeEditor(_ text: Binding<String>, minHeight: CGFloat = 110, css: Bool = false) -> some View {
        CodeTextView(text: text, language: css ? .css : .html, minHeight: minHeight)
    }

    private func addField() {
        let n = newField.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, !(draft?.fields.contains { $0.name == n } ?? true) else { return }
        draft?.fields.append(FieldDraft(name: n, originalOrd: nil))
        newField = ""
    }

    private func save() {
        guard let d = draft else { return }
        Task { await store.saveNotetype(d); dismiss() }
    }
}

// MARK: - 学習の条件 (カスタム学習デッキ)

struct StudySetupView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss
    @State private var o = StudyOptions()
    @State private var presetName = ""
    @State private var showSave = false

    var body: some View {
        NavigationStack {
            Form {
                if !store.studyPresets.isEmpty {
                    Section("保存した学習デッキ") {
                        ForEach(store.studyPresets) { p in
                            Button {
                                o = p
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(p.name).foregroundStyle(.primary)
                                        Text(p.summary).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Text("\(store.studyQueue(p).count) 枚").font(.footnote).foregroundStyle(.secondary)
                                    if o.id == p.id { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                                }
                            }
                        }
                        .onDelete { idx in store.studyPresets.remove(atOffsets: idx) }
                    }
                }
                Section("デッキ") {
                    Toggle("すべてのデッキ", isOn: Binding(get: { o.decks.isEmpty }, set: { if $0 { o.decks = [] } else if let d = store.decks.first { o.decks = [d.id] } }))
                    if !o.decks.isEmpty {
                        ForEach(store.decks) { d in
                            Toggle(d.name, isOn: Binding(get: { o.decks.contains(d.id) }, set: { on in
                                if on { o.decks.append(d.id) } else { o.decks.removeAll { $0 == d.id }; if o.decks.isEmpty { o.decks = [d.id] } }
                            }))
                        }
                    }
                }
                Section {
                    ForEach(store.allTags.prefix(40), id: \.0) { tag, count in
                        Toggle("\(tag)  \(count)", isOn: Binding(get: { o.tags.contains(tag) }, set: { on in
                            if on { o.tags.append(tag) } else { o.tags.removeAll { $0 == tag } }
                        }))
                    }
                    if o.tags.count > 1 {
                        Picker("複数のタグ", selection: $o.tagsAll) {
                            Text("どれかを持つ").tag(false)
                            Text("すべてを持つ").tag(true)
                        }
                    }
                } header: { Text("タグ") } footer: { Text("選ばなければタグでは絞りません") }
                Section("そのほかの条件") {
                    TextField("検索語 (一覧の検索と同じ書き方)", text: $o.keyword).autocorrectionDisabled()
                    Toggle("期限の来ていないカードも含める", isOn: $o.includeNotDue)
                    Toggle("停止中のカードも含める", isOn: $o.includeSuspended)
                    Stepper(o.newLimit < 0 ? "新規カード: 無制限" : "新規カード: \(o.newLimit) 枚まで", value: $o.newLimit, in: -1...200, step: 5)
                    Toggle("新規カードを先に", isOn: $o.newFirst)
                    Toggle("ランダムな順で", isOn: $o.random)
                    Stepper(o.limit == 0 ? "枚数の上限: なし" : "枚数の上限: \(o.limit) 枚", value: $o.limit, in: 0...500, step: 10)
                }
                Section {
                    Button { showSave = true } label: { Label(o.name.isEmpty ? "この条件を学習デッキとして保存…" : "「\(o.name)」を更新・別名で保存…", systemImage: "square.and.arrow.down") }
                }
            }
            .navigationTitle("学習")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Image(systemName: "xmark") } }
                ToolbarItem(placement: .confirmationAction) {
                    let n = store.studyQueue(o).count
                    Button("開始 (\(n))") { start() }.fontWeight(.semibold).disabled(n == 0)
                }
            }
            .alert("学習デッキの名前", isPresented: $showSave) {
                TextField("名前", text: $presetName)
                Button("保存") {
                    var p = o; p.name = presetName.trimmingCharacters(in: .whitespaces)
                    guard !p.name.isEmpty else { return }
                    if let i = store.studyPresets.firstIndex(where: { $0.id == p.id }) { store.studyPresets[i] = p } else { p.id = UUID(); store.studyPresets.append(p) }
                    o = p
                }
                Button("キャンセル", role: .cancel) {}
            }
            .onAppear {
                o = store.studyOptions
                // 一覧でデッキを絞っていれば、それを初期値に
                if let d = store.selectedDeck, o.decks.isEmpty, o.name.isEmpty { o.decks = [d] }
                presetName = o.name
            }
        }
    }

    private func start() {
        store.studyOptions = o
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { store.studying = true }
    }
}

// MARK: - 使い方ガイド

struct GuideView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { store.showTutorial = true }
                    } label: { Label("チュートリアルをもう一度見る", systemImage: "play.circle") }
                }
                Section("Carrel でできること") {
                    guide("カードを見る", "一覧からカードを選ぶと右 (または次の画面) に表示されます。右上の「表 / 裏」で面を切り替え、下の矢印で前後のカードへ移れます。")
                    guide("カードを作る・直す", "一覧右上のメニューの「新しいカード」で作れます。表示中のカードは右上メニューの「編集」で本文とタグを、「カード情報」でデッキとタグを変えられます。")
                    guide("書式をつける (Markdown)", "編集画面のテキストモードでは Markdown の基本記法が使えます。**太字**、*斜体*、`コード`、~~取り消し~~、# 見出し (# の数で大きさ)、- 箇条書き、1. 番号付き、[文字](URL) のリンク。保存時に HTML に変換され、開き直すと同じ記法で表示されます。細かな書式は HTML モードで直接直せます。")
                    guide("学習する", "一覧の下にある「学習」を押すと条件の画面が開き、デッキ・タグ・検索語・新規の枚数などを選べます。よく使う条件は名前を付けて学習デッキとして保存できます。学習中は「あとで」(列の最後へ) と「スキップ」(今回は出さない) も使えます。答えを見てから「もう一度 / 難しい / 普通 / 簡単」で評価すると、Anki と同じ考え方で次の出題日が決まります。")
                }
                Section("検索のコツ") {
                    guide("語で絞る", "スペース区切りで入れた語を すべて含む カードに絞り込みます。検索中は関連度の高い順に並びます。")
                    guide("除外する", "-語 でその語を含むカードを除きます。")
                    guide("タグ・デッキ", "tag:名前 / deck:名前 でタグやデッキを指定できます。ロゴの下のタグをタップしても絞り込めます。")
                    guide("句で探す", "\"二 語\" のように引用符で囲むと、ひとつながりの句として探します。")
                    guide("カードの中を探す", "カードを開いて下の虫めがねを押すと、そのカードの中の語を探してハイライトします。")
                }
                Section("外部ファイルの同期") {
                    guide("Anki 形式のファイルと互換", "Carrel で作る・見るカードは .anki2 形式のファイルと互換性があります。Anki など、ほかのエディターで作ったカードのフォルダ (collection.anki2 と collection.media) を参照し、同期できます。")
                    guide("1. フォルダを選ぶ", "設定の「外部ファイルの同期」で「フォルダを選ぶ…」を押し、そのフォルダを選びます。iCloud Drive などのクラウドに置いたフォルダなら、ほかの端末とも共有できます。最初はカードと画像を取り込むので、画像が多いと時間がかかります (取り込み中もカードは見られます)。")
                    guide("2. あとは自動", "以後はアプリを開くたびにフォルダ側の変更分を取り込み、この端末で作った・直した・学習した内容は同じフォルダに自動で書き出されます。設定で「手動のみ」にすると、「今すぐ同期」を押したときだけ取り込みます。")
                    guide("同期をやめる", "設定の「同期を解除」で、フォルダとの結びつきを外せます。アプリ内のカードはそのまま残ります。同期しなくても、Carrel の中だけでカードを作って学べます。")
                }
                Section("Anki について") {
                    Text("Carrel は Anki (Ankitects) とは無関係の独立したアプリで、AnkiWeb には接続しません。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("使い方ガイド")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完了") { dismiss() } } }
        }
    }

    private func guide(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(verbatim: body).font(.footnote).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - チュートリアル (初回に自動で表示)

struct TutorialView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var scheme
    @State private var page = 0

    private struct Page { let title: String; let body: String; let color: Color }
    private let pages: [Page] = [
        Page(title: "Carrel へようこそ",
             body: "暗記カードを、見て・学んで・作れるアプリ。英単語、資格試験、歴史など、覚えたいことは何でも。まずはサンプルのカードが 3 枚入っています。", color: .carrelBlue),
        Page(title: "カードを見る",
             body: "一覧からカードを選ぶと内容が表示されます。右上の「表 / 裏」で面を切り替え、タグや検索で絞り込めます。", color: .teal),
        Page(title: "学習する",
             body: "帽子のボタンで学習開始。答えを見てから × △ ○ ◎ で評価すると、忘れそうな頃に同じカードがまた出てきます。", color: .green),
        Page(title: "作る・直す",
             body: "「新しいカード」で作成、カード表示中の鉛筆で編集。文章としても、HTML のままでも書けます。", color: .orange),
        Page(title: "ほかの端末と同期 (任意)",
             body: "iCloud Drive のフォルダを選ぶと、Anki 形式のファイルとしてほかの端末やエディターとカードを共有できます。使わなくても、この端末だけで完結します。", color: .purple),
    ]

    var body: some View {
        let p = pages[page]
        ZStack {
            // 背景: ページの色をごく薄く上から落とす
            LinearGradient(colors: [p.color.opacity(scheme == .dark ? 0.28 : 0.18), Color(.systemBackground)], startPoint: .top, endPoint: .center)
                .ignoresSafeArea()
                .animation(.easeInOut(duration: 0.4), value: page)
            VStack(spacing: 0) {
                TabView(selection: $page) {
                    ForEach(Array(pages.enumerated()), id: \.offset) { i, pg in
                        VStack(spacing: 26) {
                            Spacer(minLength: 10)
                            TutorialArt(index: i, color: pg.color)
                                .frame(height: 260)
                                .padding(.horizontal, 28)
                            VStack(spacing: 12) {
                                Text(pg.title).font(.title.weight(.bold))
                                Text(pg.body).font(.callout).foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center).lineSpacing(4)
                                    .padding(.horizontal, 32)
                            }
                            Spacer()
                        }
                        .tag(i)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .always))
                .indexViewStyle(.page(backgroundDisplayMode: .always))

                HStack {
                    if page < pages.count - 1 {
                        Button("スキップ") { dismiss() }.foregroundStyle(.secondary)
                        Spacer()
                        Button { withAnimation { page += 1 } } label: {
                            Text("次へ").font(.headline).frame(minWidth: 120).padding(.vertical, 6)
                        }
                        .buttonStyle(.glassProminent).tint(Color.carrelBlueDeep)
                    } else {
                        Spacer()
                        Button { dismiss() } label: {
                            Text("はじめる").font(.headline).frame(minWidth: 160).padding(.vertical, 6)
                        }
                        .buttonStyle(.glassProminent).tint(Color.carrelBlueDeep)
                        Spacer()
                    }
                }
                .padding(.horizontal, 24).padding(.top, 22).padding(.bottom, 24)
            }
        }
    }
}

/// チュートリアルの挿絵 (SwiftUI で描く。テーマの色に追従)
struct TutorialArt: View {
    let index: Int
    let color: Color
    /// 同梱のアイコン画像 (アセットカタログでなく素のファイルなので、パスから読む)
    static let appIconImage: UIImage = {
        if let path = Bundle.main.path(forResource: "CarrelIconBlue", ofType: "png"), let img = UIImage(contentsOfFile: path) { return img }
        return UIImage(systemName: "c.circle.fill") ?? UIImage()
    }()
    @Environment(\.colorScheme) var scheme

    private var cardFill: Color { scheme == .dark ? Color(white: 0.16) : .white }
    private var line: Color { scheme == .dark ? Color(white: 0.36) : Color(white: 0.82) }
    private var lineSoft: Color { scheme == .dark ? Color(white: 0.26) : Color(white: 0.9) }

    var body: some View {
        switch index {
        case 0: welcome
        case 1: browse
        case 2: study
        case 3: edit
        default: sync
        }
    }

    /// 1: 重なったカードとアプリのアイコン
    private var welcome: some View {
        ZStack {
            ForEach(0..<3, id: \.self) { i in
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(cardFill)
                    .shadow(color: .black.opacity(0.18), radius: 10, y: 6)
                    .frame(width: 190, height: 130)
                    .rotationEffect(.degrees(Double(i - 1) * 9))
                    .offset(x: CGFloat(i - 1) * 26, y: CGFloat(i - 1) * -6)
            }
            Image(uiImage: Self.appIconImage)
                .resizable().scaledToFit().frame(width: 92, height: 92)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .shadow(color: color.opacity(0.45), radius: 22, y: 8)
                .offset(y: 8)
        }
    }

    /// 2: 一覧とプレビュー
    private var browse: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(0..<4, id: \.self) { i in
                    VStack(alignment: .leading, spacing: 6) {
                        Capsule().fill(i == 1 ? color : line).frame(width: i == 1 ? 86 : [70, 86, 60, 78][i], height: 9)
                        HStack(spacing: 4) {
                            Capsule().fill(lineSoft).frame(width: 28, height: 7)
                            Capsule().fill(lineSoft).frame(width: 20, height: 7)
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(i == 1 ? color.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
            .frame(width: 130)
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(cardFill)
                .shadow(color: .black.opacity(0.18), radius: 10, y: 6)
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Q.").font(.caption.weight(.bold)).foregroundStyle(color)
                        Capsule().fill(line).frame(width: 110, height: 10)
                        Capsule().fill(line).frame(width: 80, height: 10)
                        Rectangle().fill(lineSoft).frame(height: 1).padding(.vertical, 6)
                        Capsule().fill(lineSoft).frame(width: 120, height: 8)
                        Capsule().fill(lineSoft).frame(width: 96, height: 8)
                        Capsule().fill(lineSoft).frame(width: 110, height: 8)
                    }
                    .padding(18)
                }
        }
        .padding(.horizontal, 8)
    }

    /// 3: カードと評価ボタン
    private var study: some View {
        VStack(spacing: 18) {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(cardFill)
                .shadow(color: .black.opacity(0.18), radius: 10, y: 6)
                .frame(height: 150)
                .overlay {
                    VStack(spacing: 10) {
                        Capsule().fill(line).frame(width: 150, height: 11)
                        Capsule().fill(line).frame(width: 100, height: 11)
                        Spacer().frame(height: 4)
                        Capsule().fill(color.opacity(0.35)).frame(width: 120, height: 9)
                    }
                }
            HStack(spacing: 10) {
                ForEach(Array(zip(["×", "△", "○", "◎"], [Color.red, .orange, .green, .blue])), id: \.0) { sym, c in
                    Text(sym).font(.title2.weight(.bold)).foregroundStyle(c)
                        .frame(maxWidth: .infinity, minHeight: 26).padding(.vertical, 8)
                        .background(cardFill, in: Capsule())
                        .overlay(Capsule().stroke(line, lineWidth: 1))
                }
            }
        }
        .padding(.horizontal, 10)
    }

    /// 4: 編集画面
    private var edit: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(cardFill)
            .shadow(color: .black.opacity(0.18), radius: 10, y: 6)
            .overlay(alignment: .topLeading) {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(["表", "裏", "タグ"], id: \.self) { name in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(name).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                            if name == "タグ" {
                                HStack(spacing: 6) {
                                    ForEach(["英単語", "重要"], id: \.self) { t in
                                        Text(t).font(.caption2).padding(.horizontal, 8).padding(.vertical, 3)
                                            .background(color.opacity(0.15), in: Capsule()).foregroundStyle(color)
                                    }
                                    Image(systemName: "plus.circle").font(.caption).foregroundStyle(.secondary)
                                }
                            } else {
                                RoundedRectangle(cornerRadius: 8).fill(lineSoft).frame(height: name == "表" ? 34 : 54)
                                    .overlay(alignment: .topLeading) {
                                        Capsule().fill(line).frame(width: name == "表" ? 120 : 150, height: 8).padding(10)
                                    }
                            }
                        }
                    }
                }
                .padding(18)
            }
            .overlay(alignment: .bottomTrailing) {
                Image(systemName: "pencil")
                    .font(.title3.weight(.semibold)).foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(color, in: Circle())
                    .shadow(color: color.opacity(0.4), radius: 10, y: 4)
                    .offset(x: 10, y: 10)
            }
            .padding(.horizontal, 24)
    }

    /// 5: 端末とクラウド
    private var sync: some View {
        HStack(spacing: 14) {
            device(icon: "iphone", label: "iPhone")
            arrows
            Image(systemName: "icloud.fill")
                .font(.system(size: 52)).foregroundStyle(color)
                .shadow(color: color.opacity(0.4), radius: 14, y: 6)
            arrows
            device(icon: "laptopcomputer", label: "Mac など")
        }
    }
    private var arrows: some View {
        VStack(spacing: 6) {
            Image(systemName: "arrow.right").font(.caption.weight(.bold))
            Image(systemName: "arrow.left").font(.caption.weight(.bold))
        }
        .foregroundStyle(.secondary)
    }
    private func device(icon: String, label: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 44)).foregroundStyle(.primary)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(width: 84)
    }
}
