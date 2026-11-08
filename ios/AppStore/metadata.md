# App Store 申請メモ (iOS 版 Carrel 1.0)

App Store Connect に入力する内容の下書きと、申請までの手順。

## 1. 前提 (ご自身で行うもの)
- Apple Developer Program (有料) に登録し、承認を待つ: https://developer.apple.com/programs/
- 承認後、Xcode › Settings › Accounts に **その Apple ID** を追加する (Personal Team ではなく、有料チームが出ることを確認)
- App Store Connect › Agreements で「Paid Apps」は不要 (無料アプリ)。「Free Apps」の契約に同意が必要な場合がある

## 2. App Store Connect で App を作る
- プラットフォーム: iOS
- 名前: **Carrel**
- プライマリ言語: 日本語
- バンドル ID: `com.haru.ankipreview` (Xcode から初回アップロード時に自動登録される。先に作るなら Certificates, Identifiers & Profiles で App ID を登録)
- SKU: `carrel-ios`
- ユーザーアクセス: フルアクセス

## 3. App 情報
- サブタイトル (30 字以内): `見て・学んで・作れる暗記カード`
- カテゴリ: プライマリ **教育**、セカンダリ **仕事効率化**
- 年齢制限: すべて「なし」→ 4+
- プライバシーポリシー URL: `https://github.com/Kissamine/carrel/blob/master/docs/privacy.md`
- サポート URL: `https://github.com/Kissamine/carrel/blob/master/docs/support.md`
- マーケティング URL: `https://github.com/Kissamine/carrel`
- 著作権: `2026 Kujo Haru`
- 価格: 無料、配信国: すべて (または日本のみ)

## 4. App のプライバシー (Privacy Nutrition Label)
- 「データを収集しない」を選ぶ。解析・広告・アカウントなし。ファイル同期は利用者自身のフォルダへの読み書きで、開発者は取得しない

## 5. バージョン情報 (1.0)
**プロモーション用テキスト (170 字以内)**
```
英単語、資格試験、歴史。覚えたいことを、ひとつの場所に。タグとデッキで絞り込み、間隔反復で学び、カードもその場で作れます。
```

**説明**
```
Carrel は、暗記カードを「見て・学んで・作れる」アプリです。

■ 読みやすいカード
HTML と CSS のテンプレートをそのまま描画。画像はタップで拡大、カードの中の語を探してハイライトできます。iPad では一覧とカードを並べて表示します。

■ 覚えたいことを、ひとつの場所に
スペース区切りの語、-語 での除外、tag: / deck: 指定、"句" の検索。検索中は関連度の高い順に並び、タグとデッキで絞り込めます。

■ 忘れそうな頃に、もう一度
「学習」でデッキ・タグ・検索語・新規の枚数を選んで出題。× △ ○ ◎ の 4 段階で評価すると、間隔反復の考え方で次の出題日が決まります。よく使う条件は学習デッキとして保存できます。

■ 作る・直すも、この中で
テキストモードでは Markdown、HTML モードでは色分けつきのエディタで書けます。ノートタイプの欄・テンプレート・CSS も編集できます。

■ 外部ファイルの同期 (任意)
カードは .anki2 形式のファイルと互換性があります。iCloud Drive などのフォルダを選ぶと、ほかの端末やエディターで作ったカードを読み込み、この端末での変更を自動で書き出します。同期しなくても、Carrel の中だけで完結します。

Carrel はアカウント登録もサーバーへの通信も不要で、カードはすべて端末の中に保存されます。

※ Anki は Ankitects Pty Ltd の商標です。Carrel は Ankitects とは無関係の独立したアプリで、AnkiWeb には接続しません。
```

**キーワード (100 字以内、カンマ区切り)**
```
暗記カード,フラッシュカード,単語帳,間隔反復,英単語,資格,勉強,学習,暗記,anki2
```

**新機能** (1.0 なので初回リリースの一文)
```
初回リリース。
```

**スクリーンショット**
- iPhone 6.9": `ios/AppStore/iPhone/01–05` (1320×2868)
- iPad 13": `ios/AppStore/iPad/01–03` (2064×2752)
- 6.5" / 12.9" 第 2 世代の枠は 6.9" / 13" から自動で流用される設定にする

## 6. App Review 情報
- サインイン: 不要 (チェックを外す)
- 連絡先: 氏名・電話・メール
- メモ (英語でも可):
```
Carrel is a self-contained flashcard app. No account or network connection is required; three sample cards are preloaded on first launch, so the reviewer can browse, study (bottom-left menu or the graduation-cap button → 開始), and create cards right away.
The optional "外部ファイルの同期" setting lets users pick a folder on their own device/iCloud Drive to read and write card files in the .anki2 format. The app never connects to AnkiWeb or any server of ours.
NSAllowsArbitraryLoads is enabled only so that user-created card templates referencing http:// images can render; the app itself makes no network requests.
```

## 7. 輸出コンプライアンス
- `ITSAppUsesNonExemptEncryption = NO` を Info.plist に入れてあるので、ビルドごとの質問は出ない (HTTPS 以外の暗号化を使っていない)

## 8. ビルドのアップロード (有料チーム設定後)
```bash
cd ios/Carrel
xcodebuild -project Carrel.xcodeproj -scheme Carrel -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/Carrel.xcarchive \
  -allowProvisioningUpdates archive
xcodebuild -exportArchive -archivePath build/Carrel.xcarchive \
  -exportOptionsPlist ExportOptions.plist -exportPath build/export -allowProvisioningUpdates
```
`ExportOptions.plist` (`method = app-store-connect`, `destination = upload`) で App Store Connect に直接アップロードされる。
処理 (数分〜30 分) が終わると App Store Connect › TestFlight にビルドが現れるので、バージョン 1.0 の「ビルド」で選ぶ。

## 9. 申請
すべて入力したら「審査へ提出」。審査は通常 1〜3 日。
