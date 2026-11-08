# リリース手順 (macOS 版 Carrel)

## 0. 前提
- Apple Silicon の Mac、Node.js、Python 3
- `macos/.venv` を作っておく (Git 管理外):
  ```bash
  cd macos && python3 -m venv .venv && .venv/bin/pip install anki
  ```
- `cd electron && npm install`

## 1. 無署名で配る (現在の方式・無料)
```bash
cd electron && npm run dist
```
`electron/dist/` に `Carrel-<version>-mac-arm64.dmg` と `.zip` ができる。
GitHub Releases にそのまま添付する。利用者は初回のみ Gatekeeper の警告が出るので、
README の「初回起動」の手順 (右クリック › 開く、または `xattr -d com.apple.quarantine`) を案内する。

バージョンを上げるときは `electron/package.json` の `version` を変え、`git tag v1.x.y` を打つ。

## 2. 署名・公証つきに切り替える (Apple Developer Program 加入後)
準備は済んでいる。やることは次の 3 つだけ。

1. Xcode › Settings › Accounts で **Developer ID Application** 証明書を作る (キーチェーンに入る)
2. App Store Connect で App 用パスワード (app-specific password) を発行する
3. 環境変数を入れて署名つきビルドを実行する
   ```bash
   export APPLE_ID="you@example.com"
   export APPLE_APP_SPECIFIC_PASSWORD="xxxx-xxxx-xxxx-xxxx"
   export APPLE_TEAM_ID="XXXXXXXXXX"      # Developer ID 証明書のチーム ID
   cd electron && npm run dist:signed
   ```
   `dist:signed` は証明書の自動検出を有効にし (`CSC_IDENTITY_AUTO_DISCOVERY` を外す)、
   `mac.notarize` を有効にして Apple の公証サーバに提出する。
   Hardened Runtime と entitlements (`build/entitlements.mac.plist`) は `package.json` に設定済み。

確認:
```bash
codesign --verify --deep --strict --verbose=2 "dist/mac-arm64/Carrel.app"
spctl -a -vv "dist/mac-arm64/Carrel.app"      # "accepted" "source=Notarized Developer ID" と出れば OK
```

### 署名で引っかかりやすい点
- `Resources/python/` の実行ファイルと `.so` も署名対象になる。electron-builder が自動で
  署名するが、失敗したら `build/entitlements.mac.plist` の `disable-library-validation` が
  有効か、venv 内にシンボリックリンク切れがないかを確認する。
- 公証に出す前に `npm run dist` (無署名) が通ることを確認しておくと切り分けが楽。

## 3. Intel Mac 向け
同梱 Python (`macos/.venv`) が arm64 なので、現状は Apple Silicon のみ。
Intel 向けを出すときは x86_64 の venv を別に作り、`--x64` でビルドする。


---

# リリース手順 (iOS 版 Carrel)

申請に入力する文面・手順の下書きは `ios/AppStore/metadata.md`、画像は `ios/AppStore/iPhone` と `ios/AppStore/iPad` にある。

1. Apple Developer Program (有料) のチームを Xcode › Settings › Accounts に追加する (無料の Personal Team では App Store に出せない)
2. バージョンを上げるときは `ios/AnkiPreview/AnkiPreview.xcodeproj` の `MARKETING_VERSION` と `CURRENT_PROJECT_VERSION` を変える
3. アーカイブして App Store Connect にアップロード:
   ```bash
   cd ios/AnkiPreview
   xcodebuild -project AnkiPreview.xcodeproj -scheme AnkiPreview -configuration Release -destination 'generic/platform=iOS' -archivePath build/Carrel.xcarchive -allowProvisioningUpdates archive
   xcodebuild -exportArchive -archivePath build/Carrel.xcarchive -exportOptionsPlist ExportOptions.plist -exportPath build/export -allowProvisioningUpdates
   ```
4. App Store Connect でビルドを選び、`metadata.md` の内容を入力して審査へ提出する
