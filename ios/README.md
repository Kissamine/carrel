# Anki Editor (iOS 版)

macOS 版 (Anki Editor) と基本機能を共有する、カードの閲覧専用アプリ。
Apple 標準の UI (SwiftUI / NavigationSplitView) だけで作っているので、
iPhone では「一覧 → プレビュー」の積み重ね、iPad や折りたたみ端末のような広い画面では
一覧とプレビューの 2 列に自動で切り替わる。

## 構成

```
ios/AnkiPreview/
  AnkiPreview.xcodeproj      Xcode で開く
  Sources/
    AnkiPreviewApp.swift     入口
    ContentView.swift        画面 (一覧・プレビュー・設定)
    Store.swift              状態 (取り込み・絞り込み・選択)
    CollectionDB.swift       Anki のコレクション (SQLite) を読み取り専用で読む
    Protobuf.swift           ノートタイプ設定 (protobuf) から CSS とテンプレートを取り出す
    TemplateRenderer.swift   {{欄}} などのテンプレートを本文 HTML にする
    PreviewView.swift        WKWebView でカードを描く
  Assets.xcassets            アイコン (macOS 版と同じ) とアクセントカラー
```

## データの流れ

設定 › AnkiWeb にログインして「今すぐ同期」。AnkiWeb からコレクションとメディアを受け取る (受信のみ。
iPhone からは何も送らないので学習記録は変わらない)。旧形式の同期プロトコル (multipart・無圧縮) を使うので
追加のライブラリはいらず、コレクションはスキーマ 11 で届く (ノートタイプとデッキは col 表の JSON)。
`Sources/AnkiWebSync.swift` にクライアント・無圧縮 zip の読み取り・キーチェーン保存がある。
検索語・タグ・デッキ・学習状況の絞り込みは次に開いたときも残る (macOS 版と同じ)。

## できること (閲覧向け)

- 一覧: デッキ / タグ / 学習状況 (期限切れ・未学習・復習中・停止中・フラグ付き) で絞り込み、
  名前・更新・作成・期限・復習回数・忘却回数で並べ替え、右端に期限ラベル、ランダムに開く
- 検索: スペース区切りで AND、`-語` で除外、`tag:名前` `deck:名前`、`"句"`。見出し以外に当たった語は前後を一覧に表示
- プレビュー: 表 / 裏の切替、カードタイプの切替、前後のカードへ移動 (⌘↑ / ⌘↓)、
  カード内検索 (⌘F、ハイライトと上下移動)、画像タップで拡大 (ピンチ・ダブルタップ)、カード情報、共有
- 見た目: 端末のライト / ダークに追従 (設定で固定も可)。ノートタイプに night_mode の CSS がなければ自動で暗い地色にする

## 自分の iPhone に入れる

Xcode でプロジェクトを開き、Signing & Capabilities で Team に自分の Apple ID を設定して、
USB でつないだ iPhone を選んで Run する。無料の Apple ID では署名が 7 日で切れるので週に一度 Run し直す。
Apple Developer Program (有料) なら 1 年有効で、TestFlight からも配れる。

## ビルド

```bash
cd ios/AnkiPreview
xcodebuild -project AnkiPreview.xcodeproj -scheme AnkiPreview \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Xcode で開いて ▶ でもよい。実機に入れるときは Signing & Capabilities でチームを選ぶ。
