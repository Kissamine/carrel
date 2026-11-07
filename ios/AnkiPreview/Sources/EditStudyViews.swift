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
    @FocusState private var focusedField: Int?

    private var notetype: Notetype? { store.notetypes[notetypeId] }

    /// HTML → 文章 (改行タグは改行に、ほかのタグは外す)
    static func toText(_ html: String) -> String {
        var t = html
        for tag in ["<br>", "<br/>", "<br />", "</div>", "</p>", "</li>"] { t = t.replacingOccurrences(of: tag, with: "\n", options: .caseInsensitive) }
        t = Note.stripHTML(t)
        while t.hasSuffix("\n") { t.removeLast() }
        return t
    }
    /// 文章 → HTML (記号を逃がして、改行は <br>)
    static func toHTML(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\n", with: "<br>")
    }
    /// 書式や画像など、文章にすると失われるものを含むか
    static func hasRichHTML(_ html: String) -> Bool {
        let simple = html.replacingOccurrences(of: "<br\\s*/?>|</?div>|</?p>|</?li>|</?ul>|</?ol>", with: "", options: [.regularExpression, .caseInsensitive])
        return simple.contains("<")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if note == nil {
                        Picker("ノートタイプ", selection: $notetypeId) {
                            ForEach(store.notetypes.values.sorted { $0.name < $1.name }) { nt in Text(nt.name).tag(nt.id) }
                        }
                        .onChange(of: notetypeId) { _, _ in resizeFields() }
                    } else {
                        LabeledContent("ノートタイプ", value: notetype?.name ?? "")
                    }
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
                    .onChange(of: mode) { old, new in switchMode(from: old, to: new) }
                }
                if let nt = notetype {
                    ForEach(Array(nt.fieldNames.enumerated()), id: \.offset) { i, name in
                        Section {
                            TextEditor(text: Binding(get: { i < fields.count ? fields[i] : "" }, set: { if i < fields.count { fields[i] = $0 } }))
                                .frame(minHeight: i == 0 ? 60 : 90)
                                .font(mode == "html" ? .system(.footnote, design: .monospaced) : .body)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: i)
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
        if let note { store.updateNote(note, fields: body, tags: tags) }
        else { store.addNote(notetypeId: notetypeId, deckId: deckId, fields: body, tags: tags) }
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
    @StateObject private var preview = PreviewController()

    private var current: CardInfo? { queue.first }

    var body: some View {
        NavigationStack {
            Group {
                if let card = current, let note = store.notes.first(where: { $0.id == card.noteId }) {
                    let dark = scheme == .dark
                    let bg = store.cardBackground(for: note, dark: dark) ?? Color(.systemBackground)
                    // ボタンのバーはカードの上に重ねる (地は透明)。カードはバーの下までスクロールでき、Liquid Glass が中身を透かす
                    PreviewView(html: store.studyHTML(card: card, back: showBack, dark: dark), mediaDir: Store.mediaDir,
                                highlights: [], controller: preview,
                                onImage: { url in store.lightbox = LightboxItem(url: url) })
                        .padding(.top, 44)   // 固定のカウンターの下に少し余白をとる
                        .safeAreaInset(edge: .bottom, spacing: 0) { answerBar(card) }
                    // カウンター (プレビューの「1 / 114」と同じ見た目)。スクロールしても動かないよう、カードの上に重ねて固定
                    .overlay(alignment: .top) {
                        Text("\(min(done + 1, total)) / \(total)")
                            .font(.footnote).monospacedDigit().foregroundStyle(.secondary).fixedSize()
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .glassEffect(.regular, in: Capsule())
                            .padding(.top, 4)
                    }
                    .background(bg)
                    .toolbarBackground(bg, for: .navigationBar)
                    .toolbarBackground(.visible, for: .navigationBar)
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
                    VStack(spacing: 2) { Image(systemName: "arrow.uturn.forward"); Text("あとで").font(.caption2) }.frame(width: 56).padding(.vertical, 4)
                }
                .buttonStyle(.glass)
                Button { dropCurrent() } label: {
                    VStack(spacing: 2) { Image(systemName: "forward.end"); Text("スキップ").font(.caption2) }.frame(width: 56).padding(.vertical, 4)
                }
                .buttonStyle(.glass)
                Button { showBack = true } label: {
                    Text("答えを見る").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
                .tint(Color.carrelBlueDeep)   // テーマの水色 (宮古島ブルー) の Liquid Glass
            } else {
                ForEach(Scheduler.Ease.allCases, id: \.rawValue) { ease in
                    Button { answer(card, ease) } label: {
                        VStack(spacing: 2) {
                            Text(ease.label).font(.subheadline.weight(.semibold))
                            Text(Scheduler.preview(card, ease: ease, crt: store.crt)).font(.caption2).opacity(0.8)
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 4)
                    }
                    .buttonStyle(.glass)
                    .tint(color(for: ease))
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    private func color(for ease: Scheduler.Ease) -> Color {
        switch ease { case .again: .red; case .hard: .orange; case .good: .green; case .easy: .blue }
    }

    private func answer(_ card: CardInfo, _ ease: Scheduler.Ease) {
        let spent = Int(Date().timeIntervalSince(shownAt) * 1000)
        let next = store.answer(card, ease: ease, spentMs: spent)
        queue.removeFirst()
        // すぐ (10 分以内) にまた出る学習中カードは、列の後ろに戻す
        if next.queue == 1, Double(next.due) - Date().timeIntervalSince1970 < 600 { queue.append(next) }
        done += 1
        showBack = false; shownAt = Date()
    }

    private func skip() {
        guard !queue.isEmpty else { return }
        queue.append(queue.removeFirst())
        showBack = false; shownAt = Date()
    }

    /// 今回の学習からは外す (記録はしない)
    private func dropCurrent() {
        guard !queue.isEmpty else { return }
        queue.removeFirst()
        showBack = false; shownAt = Date()
    }

    private func suspend(_ card: CardInfo) {
        var c = card; c.queue = -1
        store.setCardQueue(c)
        queue.removeFirst()
        showBack = false; shownAt = Date()
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
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Carrel でできること") {
                    guide("カードを見る", "一覧からカードを選ぶと右 (または次の画面) に表示されます。右上の「表 / 裏」で面を切り替え、下の矢印で前後のカードへ移れます。")
                    guide("カードを作る・直す", "一覧右上のメニューの「新しいカード」で作れます。表示中のカードは右上メニューの「編集」で本文とタグを、「カード情報」でデッキとタグを変えられます。")
                    guide("学習する", "一覧の下にある「学習」を押すと条件の画面が開き、デッキ・タグ・検索語・新規の枚数などを選べます。よく使う条件は名前を付けて学習デッキとして保存できます。学習中は「あとで」(列の最後へ) と「スキップ」(今回は出さない) も使えます。答えを見てから「もう一度 / 難しい / 普通 / 簡単」で評価すると、Anki と同じ考え方で次の出題日が決まります。")
                }
                Section("検索のコツ") {
                    guide("語で絞る", "スペース区切りで入れた語を すべて含む カードに絞り込みます。検索中は関連度の高い順に並びます。")
                    guide("除外する", "-語 でその語を含むカードを除きます。")
                    guide("タグ・デッキ", "tag:名前 / deck:名前 でタグやデッキを指定できます。ロゴの下のタグをタップしても絞り込めます。")
                    guide("句で探す", "\"二 語\" のように引用符で囲むと、ひとつながりの句として探します。")
                    guide("カードの中を探す", "カードを開いて下の虫めがねを押すと、そのカードの中の語を探してハイライトします。")
                }
                Section("外部ファイルの同期 (Anki のデータを使う)") {
                    guide("1. Mac で書き出す", "macOS 版 Carrel の設定「Anki 連携」で、iCloud Drive への書き出しをオンにします。保存のたびに iCloud Drive の Carrel/<プロフィール名> へ変更分だけ書き出されます。Anki を使っていない場合はこの手順は不要で、Carrel の中だけで完結します。")
                    guide("2. iPhone で選ぶ", "設定の「外部ファイルの同期」で「フォルダを選ぶ…」を押し、iCloud Drive の Carrel/<プロフィール名> を選びます。最初はカードと画像を取り込むので、画像が多いと時間がかかります (取り込み中もカードは見られます)。")
                    guide("3. あとは自動", "以後はアプリを開くたびに Mac 側の変更分を取り込み、iPhone で作った・直した・学習した内容は同じフォルダに自動で書き出されます。macOS 版はそれを読み取って Anki 本体に反映します。設定で「手動」にすると、「今すぐ同期」を押したときだけ取り込みます。")
                    guide("同期をやめる", "設定の「同期を解除」で、フォルダとの結びつきを外せます。アプリ内のカードはそのまま残ります。")
                }
                Section("Anki について") {
                    Text("Carrel は Anki (Ankitects) とは無関係の独立したアプリで、AnkiWeb には接続しません。Anki のコレクションを読み書きするのは、お使いの端末と iCloud Drive の中だけです。")
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
            Text(body).font(.footnote).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - 起動画面 (同期フォルダの確認中)

struct LaunchSplash: View {
    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 18) {
                Image("CarrelIcon")
                    .resizable().scaledToFit()
                    .frame(width: 120, height: 120)
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .shadow(color: .black.opacity(0.25), radius: 16, y: 8)
                ProgressView().controlSize(.small)
            }
        }
    }
}
