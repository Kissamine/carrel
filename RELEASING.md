# リリース手順 (macOS 版 Carrel)

## 0. 前提
- Apple Silicon の Mac、Node.js、Python 3
- 同梱する Python を用意しておく (Git 管理外。単体で動く python-build-standalone + anki ライブラリ):
  ```bash
  sh macos/setup-pyruntime.sh
  ```
  開発用に `macos/.venv` を別に作ってもよいが、配布物に入るのは `macos/pyruntime` だけ。
- `cd electron && npm install`

## 1. 無署名で配る (現在の方式・無料)
```bash
cd electron && npm run dist
```
`electron/dist/` に `Carrel-<version>-mac-arm64.dmg` と `.zip` ができる。
GitHub Releases にそのまま添付する。利用者は初回のみ Gatekeeper の警告が出るので、
README の「初回起動」の手順 (右クリック › 開く、または `xattr -d com.apple.quarantine`) を案内する。

バージョンを上げるときは `electron/package.json` の `version` を変え、`git tag v1.x.y` を打つ。

## 2. 署名・公証つき (現在の方式)
- Developer ID Application 証明書はキーチェーンに入っている (チーム 3ZRH7F9828)。別の Mac で作業するときは
  キーチェーンアクセスから .p12 で書き出して持っていく
- 公証の資格情報はキーチェーンのプロファイル `carrel-notary` に入れてある (`xcrun notarytool store-credentials carrel-notary --apple-id … --team-id 3ZRH7F9828`)
- ビルド:
  ```bash
  cd electron && APPLE_KEYCHAIN_PROFILE=carrel-notary npm run dist:signed
  ```
  `build/afterPack.js` が同梱 Python の実行ファイル・共有ライブラリにも署名してから、electron-builder がアプリ本体に署名し、
  公証サーバへ提出してステープルする。Hardened Runtime と entitlements (`build/entitlements.mac.plist`) は `package.json` に設定済み。

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
2. バージョンを上げるときは `ios/Carrel/Carrel.xcodeproj` の `MARKETING_VERSION` と `CURRENT_PROJECT_VERSION` を変える
3. アーカイブして App Store Connect にアップロード:
   ```bash
   cd ios/Carrel
   xcodebuild -project Carrel.xcodeproj -scheme Carrel -configuration Release -destination 'generic/platform=iOS' -archivePath build/Carrel.xcarchive -allowProvisioningUpdates archive
   xcodebuild -exportArchive -archivePath build/Carrel.xcarchive -exportOptionsPlist ExportOptions.plist -exportPath build/export -allowProvisioningUpdates
   ```
4. App Store Connect でビルドを選び、`metadata.md` の内容を入力して審査へ提出する
