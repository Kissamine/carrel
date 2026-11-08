# Carrel プライバシーポリシー / Privacy Policy

最終更新: 2026 年 11 月 8 日

## 日本語

Carrel (iOS / iPadOS 版および macOS 版、以下「本アプリ」) は、個人開発者 Kujo Haru が提供する暗記カードアプリです。本アプリは利用者の個人情報を収集しません。

### 収集する情報
本アプリは、利用者の氏名・メールアドレス・位置情報・端末識別子・利用状況などの情報を収集・送信しません。解析ツールや広告 SDK は組み込んでいません。

### データの保存場所
作成したカード、学習の記録、設定は、利用者の端末内 (アプリのサンドボックス) にだけ保存されます。

### 外部ファイルの同期 (任意)
利用者が設定で「外部ファイルの同期」のフォルダを選んだ場合、本アプリはそのフォルダ (例: iCloud Drive 内のフォルダ) にカードのファイル (.anki2 形式) と画像を読み書きします。これは利用者自身のストレージであり、開発者はその内容にアクセスできません。iCloud Drive を利用する場合は Apple のプライバシーポリシーが適用されます。

### ネットワーク通信
本アプリ自体はサーバーに接続しません。ただし、カードのテンプレート (HTML) に外部の画像やスクリプトの URL が含まれている場合、その内容を表示するために該当する URL へ接続することがあります。これは利用者が作成・取り込んだカードの内容によるものです。

macOS 版の AI 支援機能 (任意) は、利用者が自分で用意した API キーを入力したときだけ、利用者が依頼したカードの本文を選択した AI サービス (Anthropic / OpenAI / Google) に送信します。この機能を使わない限り通信は発生しません。

### 第三者への提供
開発者が利用者の情報を取得しないため、第三者へ提供することもありません。

### お問い合わせ
GitHub の Issues: https://github.com/Kissamine/carrel/issues

### 商標について
Anki は Ankitects Pty Ltd の商標です。Carrel は Ankitects とは無関係の独立したアプリで、AnkiWeb には接続しません。

## English

Carrel (for iOS / iPadOS and macOS, "the App") is a flashcard app developed by an individual developer, Kujo Haru. The App does not collect any personal information.

- **Data collection:** The App does not collect or transmit names, email addresses, location, device identifiers, or usage data. It contains no analytics or advertising SDKs.
- **Storage:** Cards, review history, and settings are stored only on the user's device.
- **Optional file sync:** If the user chooses a folder (for example in iCloud Drive) in Settings, the App reads and writes card files (.anki2 format) and images in that folder. The folder belongs to the user; the developer has no access to it.
- **Network:** The App itself does not connect to any server. Card templates created or imported by the user may reference external images or scripts, which are fetched to render those cards. The optional AI feature in the macOS version sends only the card text the user asks about, to the AI provider the user configured with their own API key.
- **Third parties:** No data is shared with third parties.
- **Contact:** https://github.com/Kissamine/carrel/issues

Anki is a trademark of Ankitects Pty Ltd. Carrel is an independent app, not affiliated with Ankitects, and does not connect to AnkiWeb.
