import SwiftUI

struct ContentView: View {
    @EnvironmentObject var store: Store
    @State private var showSettings = false
    @State private var columns = NavigationSplitViewVisibility.all
    // サイドバー列の中では幅クラスが常にコンパクトになるので、画面全体の幅クラスをここで見る
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            NoteListView(showSettings: $showSettings, wide: sizeClass == .regular)
        } detail: {
            DetailView(showSettings: $showSettings)
        }
        .navigationSplitViewStyle(.balanced)
        .sheet(isPresented: $showSettings) { SettingsView().environmentObject(store) }
        .sheet(item: $store.editing) { target in NoteEditorView(note: target.note).environmentObject(store) }
        .fullScreenCover(isPresented: $store.studying) { StudyView(deck: store.selectedDeck).environmentObject(store) }
        .sheet(isPresented: $store.showGuide) { GuideView() }
        // 起動直後、同期フォルダの確認中はアイコンの起動画面
        .overlay { if store.checking && store.notes.isEmpty { LaunchSplash().transition(.opacity) } }
        .animation(.easeOut(duration: 0.25), value: store.checking)
        // 画像の拡大表示は画面全体に対して出す (詳細列から出すと、畳んだ Duo でサイドバーの分だけ横にずれる)
        .fullScreenCover(item: $store.lightbox) { item in LightboxView(url: item.url) }
    }
}

// MARK: - プレビュー画面

struct LightboxItem: Identifiable { let url: URL; var id: String { url.path } }

struct DetailView: View {
    @EnvironmentObject var store: Store
    @Environment(\.colorScheme) var scheme
    @Binding var showSettings: Bool
    @StateObject private var preview = PreviewController()
    @State private var showFind = false
    @State private var showInfo = false
    @State private var confirmDelete = false
    @FocusState private var findFocused: Bool

    var body: some View {
        if let note = store.selectedNote {
            let dark = scheme == .dark
            let bg = store.cardBackground(for: note, dark: dark) ?? Color(.systemBackground)
            let barDark = bg.isDark
            let nt = store.notetypes[note.notetypeId]
            VStack(spacing: 0) {
                if showFind { findBar(barDark: barDark) }
                PreviewView(html: store.previewHTML(for: note, dark: dark) + "<!-- media \(store.mediaVersion) -->", mediaDir: Store.mediaDir,
                            highlights: store.highlightTerms, controller: preview,
                            onImage: { url in store.lightbox = LightboxItem(url: url) })
                    .ignoresSafeArea(edges: .bottom)
            }
            .background(bg)
            .toolbarBackground(bg, for: .navigationBar, .bottomBar)
            .toolbarBackground(.visible, for: .navigationBar, .bottomBar)
            .toolbarColorScheme(barDark ? .dark : .light, for: .navigationBar, .bottomBar)
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarRole(.editor)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    // 表 / 裏 (押すたびに切り替え)
                    Button {
                        store.side = store.side == "front" ? "back" : "front"
                    } label: {
                        Label(store.side == "front" ? "表" : "裏", systemImage: store.side == "front" ? "rectangle.portrait" : "rectangle.portrait.fill")
                            .labelStyle(.titleAndIcon)
                    }
                    .keyboardShortcut(.space, modifiers: [.command])
                    Menu {
                        if let nt, nt.templates.count > 1 {
                            Picker("カードタイプ", selection: $store.templateIndex) {
                                ForEach(Array(nt.templates.enumerated()), id: \.offset) { i, t in Text(t.name).tag(i) }
                            }
                            Divider()
                        }
                        Button { store.textZoom = max(0.7, store.textZoom - 0.1) } label: { Label("文字を小さく", systemImage: "textformat.size.smaller") }
                        Button { store.textZoom = min(1.8, store.textZoom + 0.1) } label: { Label("文字を大きく", systemImage: "textformat.size.larger") }
                        Button { store.textZoom = 1.0 } label: { Label("標準の大きさ", systemImage: "textformat") }
                        Divider()
                        Button { showInfo = true } label: { Label("カード情報", systemImage: "info.circle") }
                        ShareLink(item: store.shareText(for: note)) { Label("共有", systemImage: "square.and.arrow.up") }
                        Divider()
                        Button(role: .destructive) { confirmDelete = true } label: { Label("カードを削除", systemImage: "trash") }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button { store.move(by: -1) } label: { Image(systemName: "chevron.up") }
                        .disabled((store.selectedIndex ?? 0) <= 0)
                        .keyboardShortcut(.upArrow, modifiers: [.command])
                    Button { store.move(by: 1) } label: { Image(systemName: "chevron.down") }
                        .disabled((store.selectedIndex ?? -1) >= store.filtered.count - 1)
                        .keyboardShortcut(.downArrow, modifiers: [.command])
                    Spacer()
                    if let i = store.selectedIndex {
                        Text("\(i + 1) / \(store.filtered.count)").font(.footnote).monospacedDigit().foregroundStyle(.secondary).fixedSize().padding(.horizontal, 8)
                    }
                    Spacer()
                    // 編集 (ランダム表示は一覧側のメニューから)
                    Button { store.editing = EditTarget(note: note) } label: { Image(systemName: "pencil") }
                    Button {
                        showFind.toggle()
                        if showFind { findFocused = true } else { store.findText = "" }
                    } label: { Image(systemName: showFind ? "magnifyingglass.circle.fill" : "magnifyingglass") }
                        .keyboardShortcut("f", modifiers: [.command])
                }
            }
            .sheet(isPresented: $showInfo) { CardInfoView(note: note).environmentObject(store) }
            .confirmationDialog("このカードを削除しますか？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("削除", role: .destructive) { store.deleteNote(note) }
            } message: { Text("ノートとそのカードが消えます。同期フォルダにも反映されます") }
        } else if !store.hasCollection {
            ContentUnavailableView {
                Label("コレクションがありません", systemImage: "tray.and.arrow.down")
            } description: {
                Text("設定から、Anki のプロフィールのフォルダ (collection.anki2 と collection.media) を取り込んでください。")
            } actions: {
                Button("設定を開く") { showSettings = true }.buttonStyle(.borderedProminent)
            }
        } else {
            ContentUnavailableView("カードを選んでください", systemImage: "rectangle.on.rectangle", description: Text("左の一覧から選ぶと、ここにカードが出ます"))
        }
    }

    /// カード内検索のバー
    private func findBar(barDark: Bool) -> some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("カード内を検索", text: $store.findText)
                    .textFieldStyle(.plain)
                    .focused($findFocused)
                    .submitLabel(.search)
                    .onSubmit { preview.step(1) }
                    .autocorrectionDisabled()
                if preview.matchCount > 0 || !store.findText.isEmpty {
                    Text(preview.matchCount == 0 ? "0" : "\(preview.current) / \(preview.matchCount)")
                        .font(.footnote).monospacedDigit().foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            Button { preview.step(-1) } label: { Image(systemName: "chevron.up") }.disabled(preview.matchCount == 0)
            Button { preview.step(1) } label: { Image(systemName: "chevron.down") }.disabled(preview.matchCount == 0)
            Button("完了") { showFind = false; store.findText = "" }.fontWeight(.semibold)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .environment(\.colorScheme, barDark ? .dark : .light)
    }
}

// MARK: - カード情報

struct CardInfoView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss
    let note: Note
    @State private var newTag = ""

    /// 編集後も最新の内容を見せる
    private var current: Note { store.notes.first { $0.id == note.id } ?? note }

    var body: some View {
        NavigationStack {
            List {
                Section("ノート") {
                    LabeledContent("ノートタイプ", value: store.notetypes[current.notetypeId]?.name ?? "")
                    Picker("デッキ", selection: Binding(
                        get: { store.noteDecks[current.id] ?? 0 },
                        set: { store.setDeck(current, $0) })) {
                        ForEach(store.decks) { d in Text(d.name).tag(d.id) }
                    }
                    LabeledContent("作成", value: current.created.formatted(date: .abbreviated, time: .shortened))
                    LabeledContent("更新", value: Date(timeIntervalSince1970: Double(current.modified)).formatted(date: .abbreviated, time: .shortened))
                }
                Section {
                    if !current.tags.isEmpty {
                        TagChips(tags: current.tags, wrap: true) { tag in
                            store.setTags(current, current.tags.filter { $0 != tag })
                        }
                        .padding(.vertical, 2)
                    }
                    HStack {
                        TextField("タグを追加", text: $newTag)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onSubmit(addTag)
                        // よく使うタグから選ぶ
                        let candidates = store.allTags.map(\.0).filter { !current.tags.contains($0) }
                        if !candidates.isEmpty {
                            Menu {
                                ForEach(candidates.prefix(30), id: \.self) { t in
                                    Button(t) { store.setTags(current, current.tags + [t]) }
                                }
                            } label: { Image(systemName: "plus.circle") }
                        }
                    }
                } header: { Text("タグ") } footer: {
                    Text("デッキとタグの変更は、この iPhone 内のコピーにだけ保存されます。Anki 本体には反映されず、次にフォルダから取り込み直すと元に戻ります。")
                }
                ForEach(store.cards[note.id] ?? []) { c in
                    let name = store.notetypes[note.notetypeId]?.templates[safe: c.ord]?.name ?? "カード \(c.ord + 1)"
                    Section(name) {
                        LabeledContent("状態", value: c.status.rawValue)
                        if let d = c.dueDate(crt: store.crt) {
                            LabeledContent("期限", value: d.formatted(date: .abbreviated, time: c.queue == 1 ? .shortened : .omitted))
                        }
                        if c.ivl > 0 { LabeledContent("間隔", value: c.ivl >= 30 ? "\(c.ivl / 30)か月 (\(c.ivl)日)" : "\(c.ivl)日") }
                        LabeledContent("復習回数", value: "\(c.reps)")
                        LabeledContent("忘却回数", value: "\(c.lapses)")
                        if c.flag > 0 {
                            LabeledContent("フラグ") { Circle().fill(Store.flagColors[c.flag] ?? .gray).frame(width: 12, height: 12) }
                        }
                    }
                }
            }
            .navigationTitle("カード情報")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完了") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func addTag() {
        let t = newTag.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "_")
        guard !t.isEmpty else { return }
        if !current.tags.contains(t) { store.setTags(current, current.tags + [t]) }
        newTag = ""
    }
}

/// タグを小さなチップで並べる。wrap = true なら折り返し、false なら 1 行に収まる分だけ
struct TagChips: View {
    let tags: [String]
    var wrap = false
    var onRemove: ((String) -> Void)? = nil

    var body: some View {
        if wrap {
            FlowLayout(spacing: 6) { ForEach(tags, id: \.self) { chip($0) } }
        } else {
            HStack(spacing: 5) {
                ForEach(tags, id: \.self) { chip($0) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()
        }
    }

    private func chip(_ tag: String) -> some View {
        HStack(spacing: 3) {
            Text(tag).font(.caption2).lineLimit(1)
            if let onRemove {
                Button { onRemove(tag) } label: { Image(systemName: "xmark").font(.system(size: 8, weight: .bold)) }
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 7).padding(.vertical, 3)
        .background(Color.accentColor.opacity(0.12), in: Capsule())
        .foregroundStyle(Color.accentColor)
        .fixedSize()
    }
}

/// 折り返す横並び
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0, x + s.width > width { x = 0; y += rowH + spacing; rowH = 0 }
            x += s.width + spacing; rowH = max(rowH, s.height)
        }
        return CGSize(width: width == .infinity ? x : width, height: y + rowH)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0, x + s.width > bounds.width { x = 0; y += rowH + spacing; rowH = 0 }
            v.place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + y), proposal: .unspecified)
            x += s.width + spacing; rowH = max(rowH, s.height)
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

// MARK: - 画像の拡大表示

struct LightboxView: View {
    @Environment(\.dismiss) var dismiss
    let url: URL

    var body: some View {
        GeometryReader { geo in
            // 画像は常に画面いっぱい。ボタンは「物理画面」の横幅の中央に置く。
            // 畳んだ Duo ではこのビューの枠が画面に対して横にずれることがあるので、
            // 枠の座標ではなく画面の座標で中央を求めて、そのぶんを差し引く
            let frame = geo.frame(in: .global)
            let screenW = UIScreen.main.bounds.width
            ZStack {
                Color.black.ignoresSafeArea()
                if let img = UIImage(contentsOfFile: url.path) {
                    ZoomableImage(image: img).ignoresSafeArea()
                } else {
                    ContentUnavailableView("画像を開けません", systemImage: "photo").foregroundStyle(.white)
                }
                HStack(spacing: 10) { shareButton; closeButton }
                    .position(x: screenW / 2 - frame.minX, y: frame.height - 16 - 24)
            }
        }
        .colorScheme(.dark)
        .statusBarHidden()
    }

    private var shareButton: some View {
        ShareLink(item: url) { Image(systemName: "square.and.arrow.up").modifier(RoundGlass()) }
            .buttonStyle(.plain)
    }

    private var closeButton: some View {
        Button { dismiss() } label: { Image(systemName: "xmark").modifier(RoundGlass()) }
            .buttonStyle(.plain)
    }
}

/// OS 標準と同じ正円の Liquid Glass ボタン
private struct RoundGlass: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(.title3.weight(.semibold))
            .foregroundStyle(.white)
            .frame(width: 48, height: 48)
            .contentShape(Circle())
            .glassEffect(.regular.interactive(), in: Circle())
    }
}

/// ピンチで拡大できる画像 (UIScrollView)
struct ZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.delegate = context.coordinator
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 6
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.backgroundColor = .clear
        let iv = UIImageView(image: image)
        iv.contentMode = .scaleAspectFit
        iv.isUserInteractionEnabled = true
        scroll.addSubview(iv)
        context.coordinator.imageView = iv
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        tap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(tap)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        DispatchQueue.main.async {
            context.coordinator.imageView?.frame = scroll.bounds
            scroll.contentSize = scroll.bounds.size
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var imageView: UIImageView?
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            // 小さいときは中央に寄せる
            guard let iv = imageView else { return }
            let dx = max((scrollView.bounds.width - scrollView.contentSize.width) / 2, 0)
            let dy = max((scrollView.bounds.height - scrollView.contentSize.height) / 2, 0)
            iv.center = CGPoint(x: scrollView.contentSize.width / 2 + dx, y: scrollView.contentSize.height / 2 + dy)
        }
        @objc func doubleTap(_ g: UITapGestureRecognizer) {
            guard let scroll = g.view as? UIScrollView else { return }
            if scroll.zoomScale > 1.01 { scroll.setZoomScale(1, animated: true); return }
            let p = g.location(in: imageView)
            let w = scroll.bounds.width / 3, h = scroll.bounds.height / 3
            scroll.zoom(to: CGRect(x: p.x - w / 2, y: p.y - h / 2, width: w, height: h), animated: true)
        }
    }
}

// MARK: - 一覧

struct NoteListView: View {
    @EnvironmentObject var store: Store
    @Binding var showSettings: Bool
    /// 横に広い画面 (Duo を開いたとき・iPad) か
    var wide: Bool

    /// 並び・設定などのメニュー。Duo のように横に広い (サイドバーが出る) ときは下側に置く
    private var optionsMenu: some View {
        Menu {
            Menu {
                Button("すべてのデッキ") { store.selectedDeck = nil }
                ForEach(store.decks) { d in
                    Button { store.selectedDeck = d.id } label: {
                        if store.selectedDeck == d.id { Label(d.name, systemImage: "checkmark") } else { Text(d.name) }
                    }
                }
            } label: { Label(store.decks.first { $0.id == store.selectedDeck }?.name ?? "すべてのデッキ", systemImage: "rectangle.stack") }
            Picker(selection: $store.statusFilter) {
                ForEach(Self.statusNames, id: \.0) { key, name, icon in Label(name, systemImage: icon).tag(key) }
            } label: {
                Label("学習状況", systemImage: Self.statusNames.first { $0.0 == store.statusFilter }?.2 ?? "tray.full")
            }
            .pickerStyle(.menu)
            Divider()
            Picker("並び", selection: $store.sortMode) {
                Label("名前", systemImage: "textformat").tag("name")
                Label("関連度 (検索中)", systemImage: "sparkle.magnifyingglass").tag("relevance")
                Label("更新が新しい順", systemImage: "clock").tag("modified")
                Label("作成が新しい順", systemImage: "plus.circle").tag("created")
                Label("期限が近い順", systemImage: "calendar").tag("due")
                Label("復習回数が多い順", systemImage: "repeat").tag("reps")
                Label("忘れやすい順", systemImage: "exclamationmark.triangle").tag("lapses")
            }
            Divider()
            Button { store.editing = EditTarget(note: nil) } label: { Label("新しいカード", systemImage: "plus") }
            Button { store.studying = true } label: { Label("学習を始める", systemImage: "graduationcap") }
            Divider()
            Button { store.pickRandom() } label: { Label("ランダムに開く", systemImage: "shuffle") }
            if store.hasActiveFilter {
                Button { store.clearFilters() } label: { Label("絞り込みを解除", systemImage: "xmark.circle") }
            }
            Divider()
            Button { store.showGuide = true } label: { Label("使い方ガイド", systemImage: "questionmark.circle") }
            Button { showSettings = true } label: { Label("設定", systemImage: "gearshape") }
        } label: { Image(systemName: "ellipsis.circle") }
    }

    var body: some View {
        List(selection: $store.selectedNoteId) {
            ForEach(Array(store.filtered.enumerated()), id: \.element.id) { i, note in
                NoteRow(note: note, query: store.query).tag(note.id)
                    // 先頭行の上の線は、タグの帯の下に重なって見えるので消す
                    .listRowSeparator(i == 0 ? .hidden : .visible, edges: .top)
                    .listRowInsets(EdgeInsets(top: 10, leading: 20, bottom: 10, trailing: 20))
                    // 選んだカードは macOS 版と同じく角丸の控えめな地で示す
                    .listRowBackground(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(store.selectedNoteId == note.id ? Color(.secondarySystemFill) : .clear)
                            .padding(.horizontal, 8).padding(.vertical, 2)
                    )
            }
        }
        .listStyle(.plain)
        // 見開き (サイドバー) でも iPhone 幅と同じ地の色にする。サイドバー列は既定では灰色がかった別の色になり、
        // 検索バー裏のグラデーションが浮いて見えるため
        .scrollContentBackground(.hidden)
        .background(Color(.systemBackground))
        // デッキ・学習状況・タグの帯は検索バーの直下に固定 (macOS 版と同じ)
        .safeAreaInset(edge: .top, spacing: 0) {
            if wide || !store.allTags.isEmpty {
                VStack(spacing: 6) {
                    // 見開きでは、下のバーの検索アイコンでなく、ロゴの下に検索欄を置く
                    // (カード内検索のボタンと並んで紛らわしいため)
                    if wide { wideSearchField }
                    if !store.allTags.isEmpty { filters }
                }
                    .padding(.top, 6).padding(.bottom, 18)
                    // 検索バーごと覆う地。チップの部分までは不透明で、その下の余白でだけ透明になる
                    // (一覧をスクロールしたとき、チップと行が重なって読めなくならないように)
                    .background {
                        // 上 (ナビゲーションバーごと) は不透明、いちばん下の 18pt だけ透明に落とす
                        VStack(spacing: 0) {
                            Color(.systemBackground)
                            LinearGradient(colors: [Color(.systemBackground), Color(.systemBackground).opacity(0)],
                                           startPoint: .top, endPoint: .bottom)
                                .frame(height: 18)
                        }
                        .ignoresSafeArea(edges: .top)
                    }
            }
        }
        .toolbarBackground(.hidden, for: .navigationBar)
        .modifier(CompactSearch(enabled: !wide, text: $store.keyword))
        .autocorrectionDisabled()
        .navigationTitle("")   // 題はロゴ (左端) で出すので、文字の題は空に
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { CarrelLogoView(height: 20).padding(.leading, 4) }
                .sharedBackgroundVisibility(.hidden)   // ロゴはボタン (ガラスの地) に見せない
            if !wide {
                ToolbarItem(placement: .topBarTrailing) { optionsMenu }
            }
            if wide {
                ToolbarItem(placement: .bottomBar) { optionsMenu }
            }
            // 学習 (期限の来たカード + 未学習)
            ToolbarItem(placement: .bottomBar) {
                Button { store.studying = true } label: { Image(systemName: "graduationcap") }
                    .disabled(!store.hasCollection)
            }
            ToolbarItem(placement: .status) {
                Text("\(store.filtered.count) 件").font(.footnote).foregroundStyle(.secondary).fixedSize().padding(.horizontal, 8)
            }
            // iPhone 幅では検索を下のバーの右端に (縮んでアイコンになる)
            if !wide {
                ToolbarSpacer(.flexible, placement: .bottomBar)
                DefaultToolbarItem(kind: .search, placement: .bottomBar)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if store.importingMedia {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(store.importProgress).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.bar, in: Capsule())
                .padding(.bottom, 6)
            }
        }
        .overlay {
            if store.loading {
                VStack(spacing: 10) {
                    ProgressView().controlSize(.large)
                    if !store.importProgress.isEmpty { Text(store.importProgress).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            else if !store.hasCollection {
                ContentUnavailableView {
                    Label("コレクションがありません", systemImage: "tray.and.arrow.down")
                } description: {
                    Text("設定から、Anki のプロフィールのフォルダ (collection.anki2 と collection.media) を取り込んでください。")
                } actions: {
                    Button("設定を開く") { showSettings = true }.buttonStyle(.borderedProminent)
                }
            } else if store.notes.isEmpty, let e = store.error {
                // 読み込みに失敗したときは、設定を開かなくても分かるように一覧に出す
                ContentUnavailableView {
                    Label("カードを読み込めません", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(e)
                } actions: {
                    Button("もう一度読み込む") { Task { await store.load() } }.buttonStyle(.borderedProminent)
                }
            } else if store.filtered.isEmpty {
                ContentUnavailableView.search(text: store.keyword)
            }
        }
    }

    private static let statusNames: [(String, String, String)] = [
        ("all", "すべて", "tray.full"), ("due", "期限切れ・今日", "calendar.badge.exclamationmark"), ("new", "未学習", "sparkles"),
        ("review", "学習中・復習中", "repeat"), ("suspended", "停止中", "pause.circle"), ("flagged", "フラグ付き", "flag"),
    ]

    /// 見開き用の検索欄 (ロゴの下)
    private var wideSearchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("検索", text: $store.keyword)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
            if !store.keyword.isEmpty {
                Button { store.keyword = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 9)
        .glassEffect(.regular, in: Capsule())
        .padding(.horizontal, 16)
        .padding(.bottom, 6)   // タグ一覧との間を少しあける
    }

    /// タグのチップ (横スクロール)。デッキと学習状況はメニューから
    private var filters: some View {
        Group {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(store.allTags, id: \.0) { tag, count in
                        Button {
                            if store.selectedTags.contains(tag) { store.selectedTags.remove(tag) } else { store.selectedTags.insert(tag) }
                        } label: { chip("\(tag) \(count)", on: store.selectedTags.contains(tag), icon: nil) }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func chip(_ text: String, on: Bool, icon: String?) -> some View {
        HStack(spacing: 4) {
            if let icon { Image(systemName: icon).font(.caption) }
            Text(text).font(.subheadline)
        }
        .padding(.horizontal, 12).padding(.vertical, 7)
        .background(on ? Color.accentColor.opacity(0.22) : Color(.secondarySystemFill), in: Capsule())
        .foregroundStyle(on ? Color.accentColor : .primary)
    }
}

/// iPhone 幅のときだけ、下のバーに縮む検索を付ける
private struct CompactSearch: ViewModifier {
    let enabled: Bool
    @Binding var text: String
    func body(content: Content) -> some View {
        if enabled {
            content
                .searchable(text: $text, placement: .toolbar, prompt: "検索")
                .searchToolbarBehavior(.minimize)
        } else {
            content
        }
    }
}

struct NoteRow: View {
    @EnvironmentObject var store: Store
    let note: Note
    let query: SearchQuery

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if let flag = store.flagColor(for: note) {
                Circle().fill(flag).frame(width: 8, height: 8).padding(.top, 6)
            }
            VStack(alignment: .leading, spacing: 3) {
                let lines = note.titleLines
                // macOS 版と同じく、見出しの 3 行は同じ大きさ・同じ色で
                ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                    Text(line).font(.system(size: 16, weight: .semibold)).lineLimit(i == 0 ? 2 : 1)
                }
                if let snip = snippet {
                    Text(snip).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                } else if store.showTagsInList, !note.tags.isEmpty {
                    TagChips(tags: note.tags)
                }
            }
            Spacer(minLength: 0)
            if store.showDueInList, let due = store.dueLabel(for: note) {
                Text(due.text).font(.caption2).foregroundStyle(due.color).padding(.top, 3)
            }
        }
        .padding(.vertical, 2)
    }

    /// 検索語が見出し以外に当たったとき、その前後を見せる
    private var snippet: AttributedString? {
        guard let term = query.terms.first, !term.isEmpty else { return nil }
        let title = note.titleLines.joined(separator: " ").lowercased()
        if title.contains(term) { return nil }
        guard let r = note.plainLower.range(of: term) else { return nil }
        let plain = note.plain
        // plain と plainLower は同じ長さとは限らないので、文字数で位置をとる
        let pos = note.plainLower.distance(from: note.plainLower.startIndex, to: r.lowerBound)
        let start = max(0, pos - 24)
        let chars = Array(plain)
        let end = min(chars.count, pos + term.count + 60)
        guard start < chars.count, pos < chars.count else { return nil }
        var out = AttributedString((start > 0 ? "…" : "") + String(chars[start..<end]) + (end < chars.count ? "…" : ""))
        if let hit = out.range(of: String(chars[pos..<min(chars.count, pos + term.count)]), options: .caseInsensitive) {
            out[hit].foregroundColor = .accentColor
            out[hit].font = .caption.bold()
        }
        return out
    }
}

// MARK: - 設定

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) var dismiss
    @State private var picking = false
    @State private var confirmUnlink = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let name = store.lastImportName {
                        LabeledContent("同期中のフォルダ") {
                            Text(store.syncFolderDisplay ?? name).multilineTextAlignment(.trailing).foregroundStyle(.secondary)
                        }
                        if let at = store.importedAt { LabeledContent("最後に取り込み", value: at.formatted(date: .abbreviated, time: .shortened)) }
                        if let at = store.pushedAt { LabeledContent("最後に書き出し", value: at.formatted(date: .abbreviated, time: .shortened)) }
                        Picker("同期のタイミング", selection: $store.syncMode) {
                            Text("アプリを開くたび").tag("auto")
                            Text("手動のみ").tag("manual")
                        }
                        Button {
                            dismiss(); Task { await store.checkForUpdates(force: true); await store.pushNow() }
                        } label: {
                            HStack {
                                Label("今すぐ同期", systemImage: "arrow.triangle.2.circlepath")
                                if store.loading || store.importingMedia { Spacer(); ProgressView().controlSize(.small); Text(store.importProgress).font(.footnote).foregroundStyle(.secondary) }
                            }
                        }.disabled(store.loading || store.importingMedia)
                        Button { picking = true } label: { Label("別のフォルダを選ぶ…", systemImage: "folder") }
                            .disabled(store.loading || store.importingMedia)
                        Button(role: .destructive) { confirmUnlink = true } label: { Label("同期を解除", systemImage: "link.badge.minus") }
                    } else {
                        Button { picking = true } label: {
                            HStack {
                                Label("フォルダを選ぶ…", systemImage: "folder")
                                if store.loading || store.importingMedia { Spacer(); ProgressView().controlSize(.small); Text(store.importProgress).font(.footnote).foregroundStyle(.secondary) }
                            }
                        }.disabled(store.loading || store.importingMedia)
                    }
                    if let e = store.error { Text(e).font(.footnote).foregroundStyle(.red) }
                } header: { Text("外部ファイルの同期") } footer: {
                    Text(store.lastImportName == nil
                         ? "Anki のコレクション (collection.anki2 と collection.media の入ったフォルダ) を iCloud Drive などに置き、ここで選ぶと、そのカードを Carrel で見る・学ぶ・直すことができます。macOS 版 Carrel の「Anki 連携」で書き出したフォルダを選ぶのが簡単です。同期しなくても、Carrel の中だけでカードを作って学べます。"
                         : "iPhone で作った・直した・学習した内容はこのフォルダへ自動で書き出され、Mac 側の変更はアプリを開いたときに取り込みます。")
                }
                Section("データ") {
                    LabeledContent("ノート", value: "\(store.notes.count) 件")
                    LabeledContent("今日の期限", value: "\(store.notes.filter { store.isDue($0) }.count) 件")
                    Button { dismiss(); store.showGuide = true } label: { Label("使い方ガイド", systemImage: "questionmark.circle") }
                }
                Section("表示") {
                    Picker("見た目", selection: $store.appearance) {
                        Text("端末に合わせる").tag("system")
                        Text("ライト").tag("light")
                        Text("ダーク").tag("dark")
                    }
                    Picker("最初に見せる面", selection: $store.side) {
                        Text("表面").tag("front")
                        Text("裏面").tag("back")
                    }
                    HStack {
                        Text("文字の大きさ")
                        Slider(value: $store.textZoom, in: 0.7...1.8, step: 0.05)
                        Text("\(Int(store.textZoom * 100))%").monospacedDigit().frame(width: 48, alignment: .trailing)
                    }
                }
                Section("一覧") {
                    Toggle("タグを表示", isOn: $store.showTagsInList)
                    Toggle("期限を表示", isOn: $store.showDueInList)
                }
            }
            .navigationTitle("設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完了") { dismiss() } } }
            .confirmationDialog("同期を解除しますか？", isPresented: $confirmUnlink, titleVisibility: .visible) {
                Button("解除", role: .destructive) { store.unlinkSyncFolder() }
            } message: { Text("フォルダとの結びつきを外します。アプリ内のカードはそのまま残り、以後は自動で取り込み・書き出しをしません") }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.folder, .data], allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first { dismiss(); Task { await store.importFolder(url) } }
            }
        }
    }
}
