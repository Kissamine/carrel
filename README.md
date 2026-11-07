# Carrel

<img src="electron/build/icon.png" width="96" alt="Carrel">

**Carrel** は Anki 互換のフラッシュカード編集アプリ (macOS) です。
カードの検索・編集・プレビューに特化し、AnkiWeb と同期できます。
Anki そのものを置き換えるのではなく、Anki と同じコレクションを「書きやすい机」で編集するためのツールです。

> Anki は Ankitects Pty Ltd の商標です。Carrel は Ankitects とは無関係の個人プロジェクトです。

## 主な機能

- **検索と一覧**: Anki の検索構文、タグ・デッキでの絞り込み、丸数字を正しく並べる並べ替え
- **HTML エディタ**: 構文色付け、折りたたみ、タグの対応ハイライト、検索と置換 (このノート / すべてのノート)、自動閉じタグ、画像のペースト
- **ライブプレビュー**: ノートタイプの CSS とテンプレートをそのまま適用。画像のライトボックス、カード内検索
- **AnkiWeb 同期**: Anki 公式の同期プロトコルで、Anki や AnkiMobile と同じコレクションを扱える
- **AI 支援 (任意)**: Claude / ChatGPT / Gemini の API キーを入れると、カードの書き換え・整形を依頼できる。キーは自分で用意する
- **テンプレート編集・メディア整理・スニペット**

## インストール

[Releases](https://github.com/Kissamine/carrel/releases) から `Carrel-<version>-mac-arm64.dmg` を取得し、
Carrel.app を Applications に入れてください。Apple Silicon (M1 以降) の Mac と macOS 13 以降が必要です。

### 初回起動

現在は Apple の署名・公証を行っていないため、初回に「開発元を確認できません」と出ます。
次のどちらかで開けます。

- Finder で Carrel.app を **右クリック › 開く** を選び、ダイアログで「開く」を押す
- またはターミナルで隔離属性を外す:
  ```bash
  xattr -d com.apple.quarantine /Applications/Carrel.app
  ```

2 回目以降は普通に開けます。

## 使い方

1. 初回起動時の案内 (または 設定 › AnkiWeb) からログインすると、コレクションを取得します
2. 左の一覧からカードを選ぶと右にプレビューが出ます。鉛筆アイコンで編集に入ります
3. 編集は自動で保存され、同期ボタンで AnkiWeb に送られます

データは `~/Library/Application Support/Carrel/` に置かれます。

## ソースからビルドする

```bash
git clone https://github.com/Kissamine/carrel.git
cd ankieditor/macos && python3 -m venv .venv && .venv/bin/pip install anki
cd ../electron && npm install
npm start            # 開発用に起動
npm run dist         # dist/ に dmg と zip を作る (無署名)
```

署名・公証つきで配る手順は [RELEASING.md](RELEASING.md) を参照してください。

## 構成

```
electron/     macOS 版 Carrel (Electron + 同梱 Python の anki ライブラリ)
ios/          iOS 版 (SwiftUI、閲覧と AnkiWeb からの取得のみ)
macos/bridge/ Python ブリッジ (anki ライブラリとの橋渡し)
app/ shared/  以前の Android 版と Compose 版 (保守は停止)
```

## ライセンス

[AGPL-3.0-or-later](LICENSE)。同梱しているソフトウェアのライセンスは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にまとめています。
