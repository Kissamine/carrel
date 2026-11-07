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
    @State private var tagText = ""
    @FocusState private var focusedField: Int?

    private var notetype: Notetype? { store.notetypes[notetypeId] }

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
                if let nt = notetype {
                    ForEach(Array(nt.fieldNames.enumerated()), id: \.offset) { i, name in
                        Section(name) {
                            TextEditor(text: Binding(get: { i < fields.count ? fields[i] : "" }, set: { if i < fields.count { fields[i] = $0 } }))
                                .frame(minHeight: i == 0 ? 60 : 90)
                                .font(.body)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: i)
                        }
                    }
                }
                Section {
                    TextField("スペース区切り", text: $tagText)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } header: { Text("タグ") } footer: {
                    Text(note == nil
                         ? "作ったカードは同期フォルダに自動で保存され、macOS 版 Carrel や Anki でも使えます。本文は HTML としてそのまま保存されます (改行は <br>)。"
                         : "本文は HTML のまま編集します。変更は同期フォルダへ自動で保存されます。")
                }
            }
            .navigationTitle(note == nil ? "新しいカード" : "カードを編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
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
            tagText = note.tags.joined(separator: " ")
        } else {
            notetypeId = store.notetypes.values.sorted { $0.name < $1.name }.first?.id ?? 0
            if let sel = store.selectedNote { notetypeId = sel.notetypeId; deckId = store.noteDecks[sel.id] ?? 0 }
            if deckId == 0 { deckId = store.selectedDeck ?? store.decks.first?.id ?? 1 }
            resizeFields()
            tagText = store.selectedTags.joined(separator: " ")
            focusedField = 0
        }
    }

    private func resizeFields() {
        let n = notetype?.fieldNames.count ?? 0
        if fields.count < n { fields += Array(repeating: "", count: n - fields.count) }
        if fields.count > n { fields = Array(fields.prefix(n)) }
    }

    private func save() {
        // 改行は Anki と同じく <br> にする (HTML をそのまま書いた行はそのまま)
        let body = fields.map { $0.contains("<") ? $0 : $0.replacingOccurrences(of: "\n", with: "<br>") }
        let tags = tagText.split(whereSeparator: { $0 == " " || $0 == "　" }).map(String.init)
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
    let deck: Int64?

    @State private var queue: [CardInfo] = []
    @State private var showBack = false
    @State private var shownAt = Date()
    @State private var done = 0
    @StateObject private var preview = PreviewController()

    private var current: CardInfo? { queue.first }

    var body: some View {
        NavigationStack {
            Group {
                if let card = current, let note = store.notes.first(where: { $0.id == card.noteId }) {
                    let dark = scheme == .dark
                    let bg = store.cardBackground(for: note, dark: dark) ?? Color(.systemBackground)
                    VStack(spacing: 0) {
                        PreviewView(html: store.studyHTML(card: card, back: showBack, dark: dark), mediaDir: Store.mediaDir,
                                    highlights: [], controller: preview,
                                    onImage: { url in store.lightbox = LightboxItem(url: url) })
                        answerBar(card)
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
            .navigationTitle(current.map { _ in "残り \(queue.count) 枚" } ?? "学習")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("終了") { dismiss() } }
                if let card = current {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button { showBack.toggle() } label: { Label(showBack ? "表を見る" : "答えを見る", systemImage: "rectangle.portrait") }
                            Button { skip() } label: { Label("あとで", systemImage: "arrow.uturn.forward") }
                            Divider()
                            Button(role: .destructive) { suspend(card) } label: { Label("このカードを停止", systemImage: "pause.circle") }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            }
            .onAppear { queue = store.studyQueue(deck: deck); shownAt = Date() }
        }
    }

    /// 下のボタン: 「答えを見る」→ 4 段階の評価
    @ViewBuilder
    private func answerBar(_ card: CardInfo) -> some View {
        HStack(spacing: 10) {
            if !showBack {
                Button { showBack = true } label: {
                    Text("答えを見る").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
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

    private func suspend(_ card: CardInfo) {
        var c = card; c.queue = -1
        store.setCardQueue(c)
        queue.removeFirst()
        showBack = false; shownAt = Date()
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
                    guide("学習する", "一覧の下にある「学習」を押すと、期限の来たカードと未学習のカードが順に出ます。答えを見てから「もう一度 / 難しい / 普通 / 簡単」で評価すると、Anki と同じ考え方で次の出題日が決まります。")
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
