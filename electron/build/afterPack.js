// electron-builder の afterPack フック。
// 同梱した Python (Resources/python) の中の実行ファイル・共有ライブラリを、アプリ本体を封印する前に
// 同じ Developer ID で署名する (電子署名のない Mach-O が残っていると公証が通らない)。
// また、コピーで実体化してしまった python / python3 を python3.12 へのシンボリックリンクに戻す
// (同じ中身のファイルが 3 つあると codesign --verify が "file modified" で失敗する)。
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

function machOFiles(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isSymbolicLink()) continue;
    if (e.isDirectory()) { machOFiles(p, out); continue; }
    if (!e.isFile()) continue;
    if (/\.(so|dylib)$/.test(e.name) || (fs.statSync(p).mode & 0o111)) {
      // 先頭 4 バイトで Mach-O か判定 (スクリプトは除く)
      const fd = fs.openSync(p, 'r'); const b = Buffer.alloc(4); fs.readSync(fd, b, 0, 4, 0); fs.closeSync(fd);
      const magic = b.readUInt32BE(0);
      if ([0xfeedface, 0xfeedfacf, 0xcefaedfe, 0xcffaedfe, 0xcafebabe].includes(magic)) out.push(p);
    }
  }
  return out;
}

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'darwin') return;
  const appName = context.packager.appInfo.productFilename;
  const resources = path.join(context.appOutDir, `${appName}.app`, 'Contents', 'Resources');
  const py = path.join(resources, 'python');
  if (!fs.existsSync(py)) return;

  // 1) 実体化したコピーをシンボリックリンクに
  const bin = path.join(py, 'bin');
  for (const name of ['python', 'python3']) {
    const p = path.join(bin, name);
    try { fs.rmSync(p, { force: true }); } catch {}
    fs.symlinkSync('python3.12', p);
  }
  try { fs.rmSync(path.join(bin, 'python3-config'), { force: true }); fs.rmSync(path.join(bin, 'python3.12-config'), { force: true }); } catch {}

  // 2) 署名 (環境変数 CSC_NAME か、キーチェーンの Developer ID を自動検出。無署名ビルドでは何もしない)
  let sign = process.env.CSC_NAME || null;
  if (!sign && process.env.CSC_IDENTITY_AUTO_DISCOVERY !== 'false') {
    const list = execFileSync('security', ['find-identity', '-v', '-p', 'codesigning']).toString();
    const m = list.match(/"(Developer ID Application: [^"]+)"/);
    if (m) sign = m[1];
  }
  if (!sign) { console.log('  • afterPack: 署名なし (Developer ID が見つからないか無効化されている)'); return; }
  const files = machOFiles(py);
  const entitlements = path.join(__dirname, 'entitlements.mac.plist');
  console.log(`  • afterPack: 同梱 Python の Mach-O ${files.length} 個に署名`);
  for (const f of files) {
    execFileSync('codesign', ['--sign', sign, '--force', '--timestamp', '--options', 'runtime', '--entitlements', entitlements, f], { stdio: 'pipe' });
  }
};
