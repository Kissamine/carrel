# Anki Editor (読み取り専用スケルトン)

AnkiDroid の ContentProvider (`com.ichi2.anki.flashcards`) 経由で、ノートの**検索 → 一覧 → 実CSS適用プレビュー**を行う編集専用クライアントの土台。暗記機能なし。

## このスケルトンで検証すること
1. ContentProvider への疎通(パーミッション取得含む)
2. Anki検索構文がそのまま query の selection として通ること
3. 自前 WebView で model.css + テンプレートを適用したとき、表・line-height が PC 版どおりに出ること(← 本来の目的)

## ビルド・実行
1. Android Studio でこのフォルダを開く
2. Fold 8 を開発者モード + USBデバッグにして接続、Run
3. **AnkiDroid 側の設定**: AnkiDroid → 設定 → 詳細設定 → 「サードパーティ API を有効化」(バージョンにより名称・場所が異なる)
4. 初回起動時にパーミッションダイアログが出るので許可
5. 検索バーに `deck:xxx` や `tag:xxx`、通常のキーワードを入れて検索

## 既知の制限(スケルトン段階)
- **編集・保存は未実装**(次段階。update は `notes/<id>` に ContentValues)
- テンプレート展開は基本置換のみ:
  - `{{cloze:...}}` 未対応(穴埋めノートは崩れて表示される)
  - `{{#Field}}...{{/Field}}` 条件分岐 未対応
  - `{{hint:...}}`, `{{furigana:...}}` 未対応
- 画像 (`collection.media`) は表示されない。AnkiDroid のメディアフォルダへのアクセスは別途 (SAF or `content://com.ichi2.anki.flashcards/media`) 対応が必要
- JavaScript は無効化してある(MathJax を使うカードは `javaScriptEnabled = true` + MathJax CDN 注入が必要)
- ContentProvider の列名は AnkiDroid のバージョンで変わる可能性あり。安定させるなら公式 API ライブラリ `com.ichi2.anki:api` への置き換えを推奨

## 次の段階
1. フィールド編集 UI (フィールドごとの HTML エディタ)
2. `resolver.update()` での保存 + 一覧リフレッシュ
3. 展開時 2 ペインでエディタ|プレビューのライブ更新
4. メディア表示対応
