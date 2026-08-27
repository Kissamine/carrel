# Anki Editor — 引き継ぎ資料

医学系フラッシュカード用の Anki エディタ。**Android 版**（Galaxy Z Fold 8 / Android 17）と
**macOS 版**（Compose Desktop）があり、画面とロジックの大半を `shared/kotlin` で共有している。

## 全体像

| | Android | macOS |
|---|---|---|
| UI | Jetpack Compose (Material 3) | Compose Desktop (Material 3) |
| データ層 | rsdroid (Anki 公式 Rust バックエンド) を JNI で直接 | Python 版 anki を子プロセスで起動し、1 行 1 JSON で通信 |
| カード描画 | Android WebView | Chromium (JCEF、JBR 同梱) をネイティブ描画で埋め込み |
| 同期 | AnkiWeb (公式のプロトコル) | 同左 |

コレクションはアプリが自前で持ち、AnkiWeb と直接同期する（AnkiDroid には依存しない）。

## ディレクトリ

```
app/                     Android 版
macos/                   macOS 版
  bridge/anki_bridge.py  Python 版 anki を呼ぶ橋渡し (JSON-RPC over stdio)
  scripts/run-dev.sh     開発中の起動 (配布物と同じ実行環境を使う)
  runtime/               JCEF 入り JetBrains Runtime (Git 管理外・下記手順で取得)
  .venv/                 Python 実行環境 (Git 管理外・下記手順で作成)
shared/kotlin/           両方から使う共有コード
```

### 共有コード (`shared/kotlin/com/haru/ankieditor/`)

| ファイル | 中身 |
|---|---|
| `Model.kt` | ノート・テンプレート・メディア種別などのデータ型 |
| `NoteStore.kt` | 画面から見たデータ層の窓口 (両プラットフォームが実装) |
| `SearchRanker.kt` | 検索の並び替え、あいまい一致、タイトル整形 |
| `Theme.kt` | 配色 (Android・macOS 共通) |
| `HtmlHighlight.kt` | HTML/CSS の色分け、検索の強調、タブ表示、Tab 字下げ |
| `EditorShortcuts.kt` | VS Code 風のキー操作 (行移動・複製・コメント等) |
| `HtmlSourceEditorPage.kt` | HTML エディタのページ (Android で使用) |
| `RichEditorPage.kt` | リッチテキスト編集のページ (contenteditable) |
| `PreviewFindJs.kt` | プレビュー内検索 (CSS Custom Highlight を使い DOM を壊さない) |
| `SettingsScreen.kt` | 設定画面のシェル (一覧 → 詳細) |
| `TemplateEditor.kt` | 表面・裏面・スタイルの編集 |
| `MediaCleanup.kt` | 未使用メディアの整理 |

## 開発環境の再構築

```bash
# 1) Python 版 anki (macOS 版のバックエンド)
cd macos
python3 -m venv .venv
.venv/bin/pip install anki

# 2) JCEF 入り JetBrains Runtime (macOS 版の描画エンジン)
mkdir -p runtime && cd runtime
curl -L -o jbr.tar.gz \
  "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-25.0.4.1-osx-aarch64-b583.48.tar.gz"
tar xzf jbr.tar.gz
```

`macos/gradle.properties` の `org.gradle.java.installations.paths` と
`build.gradle.kts` の `jbrHome` が、この runtime の場所を指している。

## ビルドと実行

```bash
# Android (端末を USB 接続)
./gradlew :app:installDebug

# macOS を開発中に起動
cd macos && ANKI_DEBUG=1 ./scripts/run-dev.sh

# macOS を配布形式で作り、/Applications へ入れる
cd macos && ./gradlew createDistributable
rm -rf "/Applications/Anki Editor.app"
cp -R "build/compose/binaries/main/app/Anki Editor.app" /Applications/
```

`createDistributable` の後処理で、Python 実行環境の権限復帰と、
実行環境を JCEF 入り JBR に丸ごと差し替える処理が走る (`fixBundledRuntime`)。
起動時のログは `~/Library/Logs/AnkiEditor.log`。

## macOS 版で踏んだ落とし穴 (再発しやすい)

1. **JavaFX WebView は使えない** — カラー絵文字を描けない (👁 が □ になる)。Chromium 必須。
2. **Compose に重ねる描画方式**
   - オフスクリーン描画: Compose の上にボタンを重ねられるが、リサイズの追従が鈍い
   - ネイティブ描画 (現在): 滑らかだが、**その上に Compose の部品を置けない**
     → プレビュー上のボタンと検索バーは `CardHtml.overlay()` でページ自身に描いている
   - `compose.interop.blending` / `compose.swing.render.on.graphics` は**有効にすると画面全体が黒くなる**
3. **カードが無いときはページの入れ物ごと置かない** — 置くと下地が Compose を覆って真っ黒になる
4. **CEF は作成完了 (`onAfterCreated`) を待ってから読み込む** — 前に出した命令は黙って捨てられる
5. **`Modifier.width()` は親の制約に丸められる** — 幅 0 の外側に入れると中身も 0 になり、余白で負の幅になって落ちる。`requiredWidth` を使う
6. **エディタは Compose のテキスト欄** — ページ内に文字を打たせると日本語変換 (IME) が届かない

## Android 版で踏んだ落とし穴

1. **HTML エディタは WebView 実装** — Compose のテキスト欄では長文で 1 打鍵 150ms かかった (実測)。
   textarea + 背後の色分け表示にして 5ms まで短縮
2. **色分けはスタイルの数が支配的** — 計算自体は 48,000 文字でも 2.5ms。見えている範囲だけ塗る
3. **編集内容を画面全体の状態に置かない** — `DraftState` に隔離して再構成を編集欄だけに抑える

## いまの状態

**動いているもの**（両プラットフォーム）
検索（デッキ・タグ・並び替え・あいまい一致）／プレビュー（PC 版と同じ描画・カード内検索・文字サイズ）／
編集（テキスト・HTML の 2 モード、ノート内検索、タグ編集、VS Code 風のキー操作）／
ノートの追加・削除（確認付き・裏で同期）／AnkiWeb 同期（進捗表示・フル同期の確認・ノート単位の競合解決）／
設定（アカウント、検索、タグ、編集、カードの見た目、メディアの整理、表示）

**未確認・未着手**
- macOS 版のテキスト（リッチ）モードは日本語変換が通らない (CEF の IME 連携が未実装)
- macOS 版のメディア挿入ダイアログ（画像・動画・音声の幅や配置の指定）が未移植
- スニペット展開時のカーソル位置ずれ — 矢印キーと Backspace を同期処理にしたが、利用者の環境で未確認
- 一覧に同じノートが 2 件並ぶことがある (未調査)
- アプリは未署名。他の Mac へ配るには署名と公証が必要

## 引き継ぎ時の注意

- `macos/runtime`（738MB）と `macos/.venv`（46MB）は Git 管理外。上の手順で再取得する
- `keystore.properties` は Android のリリース署名鍵。共有しないこと
- コレクションの実体は `~/Library/Application Support/AnkiEditor/collection`（macOS）
