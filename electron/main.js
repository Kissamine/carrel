// Carrel (Electron 版) の親プロセス。
// 画面は renderer/ にあり、データは Python の anki をそのまま動かして取る
// (macOS/bridge/anki_bridge.py は Compose 版と共用)。

const { shell, app, BrowserWindow, ipcMain, protocol, net, dialog, session, Menu } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');
const readline = require('readline');

if (!require('electron').app.isPackaged) require('electron').app.setName('Carrel (開発版)');

const HOME = path.join(os.homedir(), 'Library/Application Support/Carrel');
// 旧名 (Anki Editor) のころのデータがあれば、そのまま新しい場所に引き継ぐ
try {
  const OLD_HOME = path.join(os.homedir(), 'Library/Application Support/AnkiEditor');
  if (!fs.existsSync(HOME) && fs.existsSync(OLD_HOME)) fs.renameSync(OLD_HOME, HOME);
} catch { /* 引き継げなくても起動はする */ }

/** 起動にかかった時間を残す (標準出力は貯め込まれることがあるのでファイルに書く) */
const bootStarted = Date.now();
let markFirst = true;
function mark(name) {
  try {
    // 起動ごとに書き直す (際限なく増やさない)
    if (markFirst) { markFirst = false; try { fs.mkdirSync(HOME, { recursive: true }); } catch {} 
      try { fs.writeFileSync(path.join(HOME, 'startup.log'), ''); } catch {} }
    fs.appendFileSync(path.join(HOME, 'startup.log'),
      `${new Date().toISOString()} ${name} ${Date.now() - bootStarted}ms\n`);
  } catch { /* 記録できなくても進む */ }
}
// Anki 本体のプロフィール (~/Library/Application Support/Anki2/<名前>/) を直接開く。
// AnkiWeb には触れない (規約上、第三者クライアントの接続は認められていない)。同期は Anki が行う
const ANKI2_DIR = path.join(os.homedir(), 'Library/Application Support/Anki2');
function listProfiles() {
  try {
    return fs.readdirSync(ANKI2_DIR, { withFileTypes: true })
      .filter((d) => d.isDirectory() && fs.existsSync(path.join(ANKI2_DIR, d.name, 'collection.anki2')))
      .map((d) => d.name);
  } catch { return []; }
}
function currentProfile() {
  const all = listProfiles();
  const chosen = readSettings().ankiProfile;
  if (chosen && all.includes(chosen)) return chosen;
  return all.length ? all[0] : null;
}
function collectionPath() {
  const p = currentProfile();
  return p ? path.join(ANKI2_DIR, p, 'collection.anki2') : null;
}
/** Anki 本体が動いているか (動いている間はコレクションに触らない) */
function ankiRunning() {
  try {
    const out = require('child_process').execSync('pgrep -f "Anki.app/Contents/MacOS/" || true', { encoding: 'utf8' });
    return out.trim().length > 0;
  } catch { return false; }
}
let collectionOpen = false;

/** Python の場所。同梱があればそれを、無ければ開発用の venv を使う */
function pythonPath() {
  const candidates = [
    path.join(process.resourcesPath || '', 'python/bin/python3'),
    path.join(__dirname, '../macos/.venv/bin/python3'),
    '/usr/bin/python3',
  ];
  return candidates.find((p) => p && fs.existsSync(p)) || 'python3';
}

/** 1 行 1 JSON でやり取りするブリッジ */
class Bridge {
  constructor() {
    this.seq = 0;
    this.waiting = new Map();
    const bundled = path.join(process.resourcesPath || '', 'bridge/anki_bridge.py');
    const script = fs.existsSync(bundled)
      ? bundled
      : path.join(__dirname, '../macos/bridge/anki_bridge.py');
    this.child = spawn(pythonPath(), [script], { stdio: ['pipe', 'pipe', 'pipe'] });
    readline.createInterface({ input: this.child.stdout }).on('line', (line) => {
      let message;
      try { message = JSON.parse(line); } catch { return; }
      const pending = this.waiting.get(message.id);
      if (!pending) return;
      this.waiting.delete(message.id);
      if (message.ok) pending.resolve(message.result);
      else pending.reject(new Error(message.error || '失敗しました'));
    });
    this.child.stderr.on('data', (b) => process.stderr.write(`[bridge] ${b}`));
    this.alive = true;
    this.child.on('exit', () => {
      this.alive = false;
      // 待っている問い合わせは、待ちっぱなしにせず失敗として返す
      for (const [, pending] of this.waiting) pending.reject(new Error('裏側が終了しました'));
      this.waiting.clear();
    });
  }

  call(method, params) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      this.waiting.set(id, { resolve, reject });
      this.child.stdin.write(JSON.stringify({ id, method, params: params || {} }) + '\n');
    });
  }

  stop() {
    try { this.child.stdin.end(); } catch {}
  }
}

let bridge = null;
let mediaDir = null;

// ---- 設定の保存先 ----
// コレクションと同じ場所に固定する。
// app.getPath('userData') はアプリ名で変わるため、
// 開発中に起動したときと、書き出したアプリとで別の場所になってしまう
const settingsFile = () => path.join(HOME, 'settings.json');

function readSettings() {
  try {
    return JSON.parse(fs.readFileSync(settingsFile(), 'utf8'));
  } catch {
    // 以前の保存先 (アプリ名ごとの場所) に残っていれば引き継ぐ
    for (const name of ['AnkiEditor', 'anki-editor', 'Anki Editor']) {
      const old = path.join(os.homedir(), 'Library/Application Support', name, 'settings.json');
      try {
        const found = JSON.parse(fs.readFileSync(old, 'utf8'));
        writeSettings(found);
        console.log('[settings] 以前の設定を引き継ぎました: ' + old);
        return found;
      } catch { /* 無ければ次を見る */ }
    }
    return {};
  }
}

function writeSettings(all) {
  try {
    fs.mkdirSync(path.dirname(settingsFile()), { recursive: true });
    fs.writeFileSync(settingsFile(), JSON.stringify(all, null, 2));
  } catch (e) {
    console.log('[settings] 保存できません: ' + e.message);
  }
}

let mainWindow = null;

function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 900,
    minHeight: 620,
    backgroundColor: '#151515',
    // 半透明 (vibrancy) は、打鍵のたびにウインドウ全体を合成し直すため重い。
    // いまは使わない

    // これで信号機が Finder や Claude と同じ 28px になる
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 17, y: 16 },
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // 画面もカードのページも file:// なので、互いを触れるようにする
      // (これが無いと、プレビューの中身を親から差し替えられない)
      allowFileAccessFromFileURLs: true,
      // プレビューは別プロセスで描く (重いカードの組版で入力が止まらないように)
      webviewTag: true,
    },
  });
  // 画面側の console を親のログにも出す (不具合を追いやすくするため)
  win.webContents.on('console-message', (_e, level, message, line, source) => {
    console.log(`[renderer] ${message} (${source}:${line})`);
  });
  mainWindow = win;
  win.on('closed', () => { if (mainWindow === win) mainWindow = null; });
  // 閉じる前に、画面側へ「本当に閉じていいか」を聞く
  win.on('close', (e) => {
    if (win.allowClose) return;
    e.preventDefault();
    win.webContents.send('confirm-close');
  });
  // 窓が裏に回ったらコレクションを手放し、Anki を開けるようにする (画面側が保存してから閉じる)
  win.on('blur', () => { if (!win.isDestroyed()) win.webContents.send('window-blur'); });
  win.on('focus', () => { if (!win.isDestroyed()) win.webContents.send('window-focus'); });
  const query = process.env.ANKI_SELFTEST
    ? (process.env.ANKI_SELFTEST === 'small' ? '?selftest&small' : '?selftest')
    : '';
  win.loadURL('app://ui/index.html' + query);
  return win;
}

// 同じアプリを二重に起動しない。
// 先に起動している方がコレクションを掴んでいるため、後から開くと必ず失敗する
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    // 窓が閉じたあとに二度目を開かれることがある。
    // 壊れた窓に触ると落ちるので、無ければ作り直す
    if (!mainWindow || mainWindow.isDestroyed()) { createWindow(); return; }
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  });
}

// data: のページからも読めるように、独自の読み出し口を先に宣言しておく
protocol.registerSchemesAsPrivileged([
  { scheme: 'media', privileges: { standard: true, secure: true, supportFetchAPI: true, bypassCSP: true, corsEnabled: true } },
  { scheme: 'mjx', privileges: { standard: true, secure: true, supportFetchAPI: true, bypassCSP: true, corsEnabled: true } },
  { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, bypassCSP: true, corsEnabled: true } },
]);

app.whenReady().then(async () => {
  // カードの中の画像・音声は media:// で読む。
  // プレビューは別のパーティションで動くので、そちらにも登録する
  // 画像は長くキャッシュさせる (プレビューの本文を差し替えるたびに 10 MB 分の画像を読み直して
  // 復号していると、打鍵のたびに重くなり、メモリも膨らんでプレビューが落ちる)。
  // 同じ名前で中身が変わったときのために、更新時刻と大きさを ETag にする
  const serveMedia = async (request) => {
    const name = decodeURIComponent(new URL(request.url).pathname.replace(/^\//, ''));
    const file = path.join(mediaDir || '', name);
    let etag = '';
    try { const st = fs.statSync(file); etag = `"${st.size}-${Math.floor(st.mtimeMs)}"`; } catch { /* 無ければそのまま */ }
    if (etag && request.headers.get('if-none-match') === etag) {
      return new Response(null, { status: 304, headers: { 'ETag': etag, 'Cache-Control': 'public, max-age=31536000' } });
    }
    const res = await net.fetch('file://' + file);
    if (!res.ok) return res;
    const headers = new Headers(res.headers);
    headers.set('Cache-Control', 'public, max-age=31536000');
    if (etag) headers.set('ETag', etag);
    return new Response(res.body, { status: res.status, headers });
  };
  protocol.handle('media', serveMedia);

  // 数式の組版に使う MathJax は同梱したものを読む
  const packed = (name) => path.join(process.resourcesPath || '', name);
  const roots = {
    mathjax: fs.existsSync(packed('mathjax')) ? packed('mathjax')
      : path.join(__dirname, 'node_modules/mathjax/es5'),
    jquery: fs.existsSync(packed('vendor-web')) ? packed('vendor-web')
      : path.join(__dirname, 'vendor-web'),
  };
  const serveMath = (request) => {
    const url = new URL(request.url);
    const root = roots[url.hostname] || roots.mathjax;
    const name = decodeURIComponent(url.pathname.replace(/^\//, ''));
    return net.fetch('file://' + path.join(root, name));
  };
  protocol.handle('mjx', serveMath);

  const preview = session.fromPartition('persist:preview');
  preview.protocol.handle('media', serveMedia);
  preview.protocol.handle('mjx', serveMath);

  // 画面もカードのページも app:// で配る。
  // 同じ出どころになるので、親からカードの中身を直接差し替えられる
  let cardPage = '<!doctype html><html><body></body></html>';
  ipcMain.handle('preview:page', (_e, html) => { cardPage = html; });

  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
  };
  const serveApp = (request) => {
    const url = new URL(request.url);
    // カードのページも同じ出どころ (app://ui/) で配る。
    // 出どころが違うと、親から中身を触れなくなる
    if (url.pathname === '/card') {
      return new Response(cardPage, { headers: { 'content-type': types['.html'] } });
    }
    const name = decodeURIComponent(url.pathname.replace(/^\//, '')) || 'index.html';
    const file = path.join(__dirname, 'renderer', name);
    const type = types[path.extname(file)] || 'application/octet-stream';
    try {
      return new Response(fs.readFileSync(file), { headers: { 'content-type': type } });
    } catch {
      return new Response('not found', { status: 404 });
    }
  };
  protocol.handle('app', serveApp);
  preview.protocol.handle('app', serveApp);

  // ---- 画面はすぐ出す ----
  // Python 側の用意 (anki の読み込みとコレクションを開くの) は数秒かかる。
  // 待ってから窓を出すと「押しても何も起きない」時間が長くなるので、
  // 先に窓を出しておき、問い合わせの方を待たせる
  const { webContents } = require('electron');
  const focusedContents = (fn) => { const c = webContents.getFocusedWebContents(); if (c) fn(c); };
  // メニューバー。画面側の操作は 'menu' で画面へ送る。ショートカットは設定の割り当てに追従する
  const send = (action) => () => { if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('menu', action); };
  const toAccel = (k) => (k ? k.replace(/\bMod\b/, 'CmdOrCtrl').replace(/\bAlt\b/, 'Option') : undefined);
  function buildMenu(keys = {}) {
    const k = (name) => toAccel(keys[name]);
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      { label: 'Carrel', submenu: [
        { role: 'about', label: 'Carrel について' },
        { type: 'separator' },
        { label: '設定…', accelerator: k('settings') || 'CmdOrCtrl+,', click: send('settings') },
        { type: 'separator' },
        { role: 'services', label: 'サービス' },
        { type: 'separator' },
        { role: 'hide', label: 'Carrel を隠す' },
        { role: 'hideOthers', label: 'ほかを隠す' },
        { role: 'unhide', label: 'すべてを表示' },
        { type: 'separator' },
        { role: 'quit', label: 'Carrel を終了' },
      ] },
      { label: 'ファイル', submenu: [
        { label: '新しいノート…', accelerator: 'CmdOrCtrl+N', click: send('addNote') },
        { label: '保存', accelerator: k('save') || 'CmdOrCtrl+S', click: send('save') },
        { type: 'separator' },
        { label: 'Anki を開いて同期', accelerator: k('sync') || 'CmdOrCtrl+R', click: send('sync') },
        { type: 'separator' },
        { label: 'バックアップを作成…', click: send('backup') },
        { label: 'コレクションのフォルダを Finder で表示', click: () => { const p = collectionPath(); if (p) shell.showItemInFolder(p); } },
        { type: 'separator' },
        { role: 'close', label: 'ウインドウを閉じる' },
      ] },
      { label: '編集', submenu: [
        { role: 'undo', label: '取り消す' }, { role: 'redo', label: 'やり直す' }, { type: 'separator' },
        // 役割 (role) のままだと、焦点がプレビュー (webview) にあるとき親の窓に効いてしまい、
        // 1 回目の ⌘C が空振りする。いま焦点のある中身に直接かける
        { label: 'カット', accelerator: 'CmdOrCtrl+X', click: () => focusedContents((c) => c.cut()) },
        { label: 'コピー', accelerator: 'CmdOrCtrl+C', click: () => focusedContents((c) => c.copy()) },
        { label: 'ペースト', accelerator: 'CmdOrCtrl+V', click: () => focusedContents((c) => c.paste()) },
        { label: 'ペーストしてスタイルを合わせる', accelerator: 'CmdOrCtrl+Shift+V', click: () => focusedContents((c) => c.pasteAndMatchStyle()) },
        { label: 'すべてを選択', accelerator: 'CmdOrCtrl+A', click: () => focusedContents((c) => c.selectAll()) },
        { type: 'separator' },
        { label: 'カード内を検索…', accelerator: k('find') || 'CmdOrCtrl+F', click: send('find') },
        { label: '検索と置換…', click: send('replace') },
        { type: 'separator' },
        { label: 'スペルと文法', submenu: [
          { role: 'toggleSpellChecker', label: '入力中にスペルチェック' },
        ] },
        { label: '音声入力を開始', role: 'startSpeaking' },
        { label: '読み上げを停止', role: 'stopSpeaking' },
      ] },
      { label: '表示', submenu: [
        { label: '一覧を表示 / 隠す', accelerator: k('toggleList') || 'CmdOrCtrl+B', click: send('toggleList') },
        { label: 'エディタを開く / 閉じる', accelerator: k('edit') || 'CmdOrCtrl+E', click: send('edit') },
        { type: 'separator' },
        { label: 'カードを再読み込み', accelerator: 'CmdOrCtrl+Shift+R', click: send('reload') },
        { type: 'separator' },
        { role: 'togglefullscreen', label: 'フルスクリーンにする' },
        { type: 'separator' },
        { label: '開発者ツール', accelerator: 'Alt+CmdOrCtrl+I', click: () => focusedContents((c) => c.toggleDevTools()) },
      ] },
      { label: 'ウインドウ', submenu: [
        { role: 'minimize', label: 'しまう' },
        { role: 'zoom', label: '拡大 / 縮小' },
        { type: 'separator' },
        { role: 'front', label: 'すべてを手前に移動' },
      ] },
      { label: 'ヘルプ', submenu: [
        { label: '使い方 (README)', click: () => shell.openExternal('https://github.com/Kissamine/carrel#readme') },
        { label: 'Carrel の GitHub ページ', click: () => shell.openExternal('https://github.com/Kissamine/carrel') },
        { label: '問題を報告…', click: () => shell.openExternal('https://github.com/Kissamine/carrel/issues') },
        { type: 'separator' },
        { label: 'Anki のマニュアル', click: () => shell.openExternal('https://docs.ankiweb.net/') },
        { type: 'separator' },
        { label: 'ショートカットの設定…', click: send('keys') },
      ] },
    ]));
  }
  buildMenu();
  // 画面側がショートカットの割り当てを知らせてきたら、メニューの表示も合わせる
  ipcMain.handle('menu:keys', (_e, keys) => { buildMenu(keys || {}); });
  mark('アプリの準備ができた');
  const win0 = createWindow();
  win0.once('ready-to-show', () => mark('窓が出せる状態'));
  win0.webContents.once('did-finish-load', () => mark('画面を読み込んだ'));

  /** コレクションを開く。開けない理由 (NO_PROFILE / ANKI_RUNNING) はそのまま投げる */
  async function openCollection() {
    if (collectionOpen) return;
    const colPath = collectionPath();
    if (!colPath) throw new Error('NO_PROFILE');
    if (ankiRunning()) throw new Error('ANKI_RUNNING');
    const r = await bridge.call('open', { path: colPath });
    mediaDir = r.mediaDir;
    collectionOpen = true;
    mark('コレクションを開いた');
  }

  async function startBridge() {
    bridge = new Bridge();
    collectionOpen = false;
    mark('ブリッジを起動');
    if (!collectionPath() || ankiRunning()) {
      // 開けない理由は画面側が案内する (Anki が終了したら自動で開く)
      mark(collectionPath() ? 'Anki が起動中なので待機' : 'Anki のプロフィールが無い');
      return;
    }
    let opened = null;
    let lastError = null;
    for (let i = 0; i < 5 && !opened; i++) {
      if (i === 2) {
        // 2 回試して駄目なら、前回の取り残しが掴んでいる可能性が高い。
        // 自分以外のブリッジを終わらせてから、もう一度試す
        try {
          require('child_process').execSync(
            `pkill -f anki_bridge.py -P 1 || true; pkill -f "anki_bridge.py" || true`,
            { stdio: 'ignore' },
          );
          mark('取り残しの裏側を片づけた');
          bridge.stop();
          try { bridge.child.kill(); } catch { /* すでに終了 */ }
          bridge = new Bridge();
          await new Promise((r) => setTimeout(r, 500));
        } catch { /* 片付けられなくても、次の試行へ進む */ }
      }
      try {
        await openCollection();
        opened = true;
      } catch (e) {
        lastError = e;
        if (/ANKI_RUNNING|NO_PROFILE/.test(e.message || '')) return;
        await new Promise((r) => setTimeout(r, 700));
      }
    }
    if (!opened) throw lastError || new Error('コレクションを開けません');
  }

  let ready = startBridge();

  /** 裏側が終わっていたら、立ち上げ直す (窓を閉じて開き直したときなど) */
  async function ensureBridge() {
    if (bridge && bridge.alive) return ready;
    mark('裏側を立ち上げ直す');
    ready = startBridge();
    return ready;
  }

  ready.catch((e) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.webContents.executeJavaScript(
      `document.body.innerHTML = '<div style="padding:60px;color:#ffb4ab;' +
       'font:14px -apple-system;line-height:1.8">コレクションを開けませんでした。<br>' +
       ${JSON.stringify(String(e && e.message))} +
       '<br><br>ほかに Anki や Carrel が動いていないか確認して、開き直してください。</div>'`,
    ).catch(() => {});
  });

  ipcMain.handle('anki', async (_event, method, params) => {
    await ensureBridge();
    if (method === 'open') { collectionOpen = false; await openCollection(); return { path: collectionPath(), mediaDir }; }
    if (method === 'close') {
      if (collectionOpen) { await bridge.call('close', {}); collectionOpen = false; mark('コレクションを閉じた'); }
      return null;
    }
    if (!collectionOpen) await openCollection();   // 閉じていれば開き直す。Anki が動いていれば ANKI_RUNNING
    return bridge.call(method, params);
  });
  ipcMain.handle('mediaDir', async () => {
    await ensureBridge();
    if (!collectionOpen) { try { await openCollection(); } catch { /* 画面側が案内する */ } }
    return mediaDir;
  });
  // Anki のプロフィールと本体の状態
  ipcMain.handle('anki:status', () => ({
    profiles: listProfiles(), profile: currentProfile(), path: collectionPath(),
    running: ankiRunning(), open: collectionOpen,
  }));
  ipcMain.handle('anki:setProfile', async (_e, name) => {
    const all = readSettings(); all.ankiProfile = name; writeSettings(all);
    if (collectionOpen) { try { await bridge.call('close', {}); } catch {} collectionOpen = false; }
    return currentProfile();
  });
  // iCloud Drive へ書き出す (iOS 版 Carrel が読む)。保存のたびに変わった分だけ
  const mirrorDir = () => {
    const s = readSettings();
    if (s.icloudMirrorDir) return s.icloudMirrorDir;
    const p = currentProfile();
    return p ? path.join(os.homedir(), 'Library/Mobile Documents/com~apple~CloudDocs/Carrel', p) : null;
  };
  const mirrorEnabled = () => readSettings().icloudMirror !== false;
  let mirroring = null;
  async function mirrorNow(force = false) {
    const src = collectionPath(), dest = mirrorDir();
    if (!src || !dest || (!force && !mirrorEnabled())) return null;
    if (mirroring) return mirroring;
    mirroring = (async () => {
      try {
        await ensureBridge();
        const r = await bridge.call('mirror', { src, dest });
        const all = readSettings(); all.icloudMirroredAt = Date.now(); writeSettings(all);
        mark('iCloud へ書き出し: メディア ' + r.copied + ' 件を写し、' + r.removed + ' 件を消した');
        return r;
      } finally { mirroring = null; }
    })();
    return mirroring;
  }
  ipcMain.handle('mirror:now', (_e, force) => mirrorNow(!!force));
  // iPhone 側 (iCloud) との違いを調べる / 取り込む。取り込んだあとは Anki 側の内容で iCloud をそろえる
  ipcMain.handle('mirror:diff', async () => {
    const dest = mirrorDir();
    if (!dest || !mirrorEnabled() || !fs.existsSync(path.join(dest, 'collection.anki2'))) return null;
    await ensureBridge();
    if (!collectionOpen) await openCollection();
    return bridge.call('diffMirror', { dest });
  });
  ipcMain.handle('mirror:pull', async (_e, choices, theirsAll) => {
    const dest = mirrorDir();
    if (!dest) return null;
    await ensureBridge();
    if (!collectionOpen) await openCollection();
    const r = await bridge.call('pullMirror', { dest, choices: choices || {}, theirsAll: !!theirsAll });
    mark('iCloud から取り込み: 追加 ' + r.added + ' / 更新 ' + r.updated + ' / 削除 ' + r.deleted + ' / カード ' + r.cards);
    await mirrorNow(true);
    return r;
  });
  ipcMain.handle('mirror:status', () => {
    const s = readSettings();
    return { enabled: mirrorEnabled(), dir: mirrorDir(), at: s.icloudMirroredAt || null };
  });
  ipcMain.handle('mirror:setEnabled', (_e, on) => { const s = readSettings(); s.icloudMirror = !!on; writeSettings(s); return mirrorEnabled(); });

  // Anki 本体を開く (同期は Anki で行う)。その前にこちらはコレクションを手放す
  ipcMain.handle('anki:launch', async () => {
    if (collectionOpen) { try { await bridge.call('close', {}); } catch {} collectionOpen = false; mark('Anki を開くためにコレクションを閉じた'); }
    require('child_process').spawn('open', ['-a', 'Anki'], { detached: true, stdio: 'ignore' }).unref();
    return true;
  });

  // プレビューの最大幅より広くしない (0 なら制限なし)
  ipcMain.handle('window:maxWidth', (_e, width) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.setMaximumSize(width > 0 ? Math.round(width) : 100000, 100000);
    if (width > 0 && mainWindow.getSize()[0] > width) {
      mainWindow.setSize(Math.round(width), mainWindow.getSize()[1]);
    }
  });

  ipcMain.handle('window:adjustLeft', (_e, delta) => {
    if (!mainWindow || !delta) return;
    const b = mainWindow.getBounds();
    const width = Math.max(400, Math.round(b.width - delta));
    mainWindow.setBounds(
      { x: Math.round(b.x + (b.width - width)), y: b.y, width, height: b.height },
      true,
    );
  });

  ipcMain.handle('window:closeNow', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.allowClose = true;
    mainWindow.close();
  });

  // 画像・音声を選んでもらい、中身をそのまま画面側へ渡す
  ipcMain.handle('pickMedia', async () => {
    const picked = await dialog.showOpenDialog({
      properties: ['openFile'],
      filters: [{
        name: 'メディア',
        extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'avif', 'svg',
                     'mp4', 'mov', 'webm', 'mp3', 'm4a', 'wav', 'ogg'],
      }],
    });
    if (picked.canceled || !picked.filePaths.length) return null;
    const file = picked.filePaths[0];
    return { name: path.basename(file), dataBase64: fs.readFileSync(file).toString('base64') };
  });

  // ---- 設定・控え・バックアップ ----
  // 組み込み AI。鍵はこちら側だけが持ち、画面側へは渡さない。
  // Claude / OpenAI / Gemini を同じ形で呼べるようにしてある
  const AI_KINDS = {
    anthropic: { label: 'Claude', defaultModel: 'claude-sonnet-5' },
    openai: { label: 'ChatGPT', defaultModel: 'gpt-5' },
    gemini: { label: 'Gemini', defaultModel: 'gemini-2.5-pro' },
  };
  function providersOf(all) { return Array.isArray(all.aiProviders) ? all.aiProviders : []; }
  function activeProvider(all) {
    const list = providersOf(all);
    return list.find((x) => x.id === all.aiActive && x.ok) || list.find((x) => x.ok) || null;
  }
  // 考える深さ (高速 / 低 / 中 / 高) を、それぞれの API の言い方に直す
  function effortOf(kind, effort) {
    const e = effort || 'medium';
    if (kind === 'anthropic') return e === 'fast' ? null : e;                 // low / medium / high
    if (kind === 'openai') return e === 'fast' ? 'minimal' : e;              // minimal / low / medium / high
    if (kind === 'gemini') return { fast: 0, low: 1024, medium: 4096, high: 12288 }[e];   // thinkingBudget
    return null;
  }
  let aiAbort = null;   // いま走っている問い合わせを止めるため
  async function askProvider(pv, { system, prompt, maxTokens, effort, attachments, webSearch }) {
    if (aiAbort) { try { aiAbort.abort(); } catch { /* すでに終了 */ } }
    aiAbort = new AbortController();
    const signal = aiAbort.signal;
    const model = pv.model || AI_KINDS[pv.kind].defaultModel;
    let max = Math.min(maxTokens || 8000, 128000);
    const level = effortOf(pv.kind, effort);
    const search = !!webSearch;
    const files = (attachments || []).filter((a) => a.dataBase64);
    // 添付 (画像・PDF) を、それぞれの API の形にする
    const userContent = (kind) => {
      if (!files.length) return kind === 'gemini' ? [{ text: prompt }] : prompt;
      const parts = [];
      for (const f of files) {
        const isPdf = f.mime === 'application/pdf';
        if (kind === 'anthropic') {
          parts.push(isPdf
            ? { type: 'document', source: { type: 'base64', media_type: 'application/pdf', data: f.dataBase64 }, title: f.name }
            : { type: 'image', source: { type: 'base64', media_type: f.mime, data: f.dataBase64 } });
        } else if (kind === 'openai') {
          parts.push(isPdf
            ? { type: 'file', file: { filename: f.name, file_data: `data:application/pdf;base64,${f.dataBase64}` } }
            : { type: 'image_url', image_url: { url: `data:${f.mime};base64,${f.dataBase64}` } });
        } else {
          parts.push({ inlineData: { mimeType: f.mime, data: f.dataBase64 } });
        }
      }
      if (kind === 'anthropic') parts.push({ type: 'text', text: prompt });
      else if (kind === 'openai') parts.push({ type: 'text', text: prompt });
      else parts.push({ text: prompt });
      return parts;
    };
    // 深さの指定を受け付けないモデルもあるので、断られたら指定なしでもう一度
    const send = async (withEffort, withTools = true) => {
      if (pv.kind === 'anthropic') {
        const body = { model, max_tokens: max, system, messages: [{ role: 'user', content: userContent('anthropic') }] };
        const headers = { 'content-type': 'application/json', 'x-api-key': pv.key, 'anthropic-version': '2023-06-01' };
        if (withEffort && level) { body.output_config = { effort: level }; headers['anthropic-beta'] = 'effort-2025-11-24'; }
        if (withTools && search) body.tools = [{ type: 'web_search_20250305', name: 'web_search', max_uses: 4 }];
        return fetch('https://api.anthropic.com/v1/messages', { method: 'POST', headers, body: JSON.stringify(body), signal });
      }
      if (pv.kind === 'openai') {
        const body = {
          model,
          max_completion_tokens: max,
          messages: [{ role: 'system', content: system }, { role: 'user', content: userContent('openai') }],
        };
        if (withEffort && level) body.reasoning_effort = level;
        return fetch('https://api.openai.com/v1/chat/completions', {
          method: 'POST', signal,
          headers: { 'content-type': 'application/json', authorization: `Bearer ${pv.key}` },
          body: JSON.stringify(body),
        });
      }
      if (pv.kind === 'gemini') {
        const generationConfig = { maxOutputTokens: max };
        if (withEffort && level !== null && level !== undefined) generationConfig.thinkingConfig = { thinkingBudget: level };
        const tools = withTools && search ? [{ google_search: {} }] : undefined;
        return fetch(
          `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(pv.key)}`,
          {
            method: 'POST', signal,
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
              systemInstruction: { parts: [{ text: system }] },
              contents: [{ role: 'user', parts: userContent('gemini') }],
              generationConfig,
              ...(tools ? { tools } : {}),
            }),
          },
        );
      }
      throw new Error('知らない種類です: ' + pv.kind);
    };
    let res = await send(true, true);
    // 出力の上限がそのモデルの許容を超えていたら、控えめな値でやり直す
    if (!res.ok && res.status === 400) {
      const why0 = await res.clone().text().catch(() => '');
      if (/max_tokens|max_completion_tokens|maxOutputTokens|output.?limit/i.test(why0)) {
        max = 8000;
        res = await send(true, true);
      }
    }
    if (!res.ok && res.status === 400) {
      // 深さや検索の道具を受け付けないモデルなら、それを外してもう一度
      const why = await res.text().catch(() => '');
      if (/tool|web_search|google_search/i.test(why)) res = await send(true, false);
      else if (/effort|thinking|output_config|reasoning/i.test(why)) res = await send(false, true);
      else res = { ok: false, status: 400, text: async () => why };
      if (!res.ok && res.status === 400) {
        const why2 = await res.text().catch(() => '');
        if (/effort|thinking|output_config|reasoning|tool/i.test(why2)) res = await send(false, false);
        else res = { ok: false, status: 400, text: async () => why2 };
      }
    }
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      let why = `HTTP ${res.status}`;
      try {
        const parsed = JSON.parse(detail);
        why = (parsed.error && (parsed.error.message || parsed.error)) || why;
      } catch { /* 素のまま */ }
      throw new Error(String(why).slice(0, 300));
    }
    const data = await res.json();
    let text = '';
    let sources = [];
    if (pv.kind === 'anthropic') {
      text = (data.content || []).filter((x) => x.type === 'text').map((x) => x.text).join('');
      for (const block of data.content || []) {
        if (block.type !== 'web_search_tool_result' || !Array.isArray(block.content)) continue;
        for (const hit of block.content) if (hit.url) sources.push(hit.url);
      }
    } else if (pv.kind === 'openai') {
      text = (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '';
    } else {
      const parts = (data.candidates && data.candidates[0] && data.candidates[0].content &&
        data.candidates[0].content.parts) || [];
      text = parts.map((x) => x.text || '').join('');
      const meta = data.candidates && data.candidates[0] && data.candidates[0].groundingMetadata;
      for (const c of (meta && meta.groundingChunks) || []) if (c.web && c.web.uri) sources.push(c.web.uri);
    }
    sources = [...new Set(sources)].slice(0, 5);
    return { text, model, kind: pv.kind, label: AI_KINDS[pv.kind].label, sources };
  }

  // 画面へ返す形 (鍵は含めない)
  function publicProvider(pv) {
    return {
      id: pv.id, kind: pv.kind, label: AI_KINDS[pv.kind] ? AI_KINDS[pv.kind].label : pv.kind,
      model: pv.model || (AI_KINDS[pv.kind] ? AI_KINDS[pv.kind].defaultModel : ''),
      ok: !!pv.ok, checkedAt: pv.checkedAt || 0, error: pv.error || '',
      models: Array.isArray(pv.models) ? pv.models.map((x) => (Array.isArray(x) ? x[0] : x)) : [],   // 取得済みの id
      modelsFetchedAt: pv.modelsFetchedAt || 0,
      extraModels: Array.isArray(pv.extraModels) ? pv.extraModels : [],   // 設定で足した id
      keyHint: pv.key ? '…' + String(pv.key).slice(-4) : '',
    };
  }
  ipcMain.handle('ai:list', () => {
    const all = readSettings();
    const active = activeProvider(all);
    return { providers: providersOf(all).map(publicProvider), active: active ? publicProvider(active) : null };
  });
  ipcMain.handle('ai:save', (_e, pv) => {
    const all = readSettings();
    const list = providersOf(all);
    const at = list.findIndex((x) => x.id === pv.id);
    const merged = { ...(at >= 0 ? list[at] : {}), ...pv };
    if (!pv.key) merged.key = at >= 0 ? list[at].key : '';   // 鍵が空なら前のまま
    if (pv.modelsOnly) {                                       // 一覧や追加分だけ覚える (鍵やモデルは触らない)
      if (at >= 0) {
        if (Array.isArray(pv.models)) { list[at].models = pv.models; list[at].modelsFetchedAt = Date.now(); }
        if (Array.isArray(pv.extraModels)) list[at].extraModels = pv.extraModels;
        all.aiProviders = list; writeSettings(all);
      }
      return;
    }
    if (at >= 0 && (list[at].key !== merged.key || list[at].model !== merged.model || list[at].kind !== merged.kind)) {
      merged.ok = false; merged.error = '';                   // 変えたら確認し直し
    }
    if (at >= 0) list[at] = merged; else list.push({ ...merged, ok: false });
    all.aiProviders = list;
    if (!all.aiActive) all.aiActive = merged.id;
    writeSettings(all);
    return publicProvider(merged);
  });
  ipcMain.handle('ai:remove', (_e, id) => {
    const all = readSettings();
    all.aiProviders = providersOf(all).filter((x) => x.id !== id);
    if (all.aiActive === id) all.aiActive = (all.aiProviders[0] || {}).id || null;
    writeSettings(all);
  });
  ipcMain.handle('ai:use', (_e, id, model) => {
    const all = readSettings();
    all.aiActive = id;
    // エディタから選び直したモデルは、確認済みのまま差し替える (同じ鍵なので)
    if (model) {
      const pv = providersOf(all).find((x) => x.id === id);
      if (pv) pv.model = model;
    }
    writeSettings(all);
  });
  ipcMain.handle('ai:test', async (_e, id) => {
    const all = readSettings();
    const list = providersOf(all);
    const pv = list.find((x) => x.id === id);
    if (!pv) throw new Error('見つかりません');
    try {
      const r = await askProvider(pv, { system: '短く答える', prompt: '「OK」とだけ返して', maxTokens: 16 });
      pv.ok = true; pv.error = ''; pv.checkedAt = Math.round(Date.now() / 1000);
      writeSettings(all);
      return { ok: true, reply: r.text.trim().slice(0, 40), model: r.model };
    } catch (e) {
      pv.ok = false; pv.error = e.message || String(e); pv.checkedAt = Math.round(Date.now() / 1000);
      writeSettings(all);
      return { ok: false, error: pv.error };
    }
  });
  // そのプロバイダで実際に使えるモデルの一覧
  ipcMain.handle('ai:models', async (_e, id) => {
    const pv = providersOf(readSettings()).find((x) => x.id === id);
    if (!pv || !pv.key) throw new Error('先に鍵を保存してください');
    let res;
    let names = [];
    if (pv.kind === 'anthropic') {
      res = await fetch('https://api.anthropic.com/v1/models?limit=100', {
        headers: { 'x-api-key': pv.key, 'anthropic-version': '2023-06-01' },
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      names = ((await res.json()).data || []).map((m) => m.id);
    } else if (pv.kind === 'openai') {
      res = await fetch('https://api.openai.com/v1/models', { headers: { authorization: `Bearer ${pv.key}` } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      names = ((await res.json()).data || []).map((m) => m.id)
        .filter((n) => /^gpt-\d/.test(n) && !/(audio|realtime|transcribe|tts|image|embedding|search|moderation|codex|instruct|turbo|4o|3\.5)/.test(n));
    } else if (pv.kind === 'gemini') {
      res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models?pageSize=100&key=${encodeURIComponent(pv.key)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      names = ((await res.json()).models || [])
        .filter((m) => (m.supportedGenerationMethods || []).includes('generateContent'))
        .map((m) => String(m.name).replace(/^models\//, ''));
    }
    return names.sort();
  });

  // 資料 (画像・PDF) を選ぶ。中身はそのまま AI へ渡す
  ipcMain.handle('ai:pickFiles', async () => {
    const picked = await dialog.showOpenDialog({
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: '画像・PDF', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'pdf'] }],
    });
    if (picked.canceled) return [];
    const mimes = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', pdf: 'application/pdf' };
    return picked.filePaths.map((file) => {
      const ext = path.extname(file).slice(1).toLowerCase();
      const data = fs.readFileSync(file);
      return { name: path.basename(file), mime: mimes[ext] || 'application/octet-stream', size: data.length, dataBase64: data.toString('base64') };
    });
  });
  // URL の中身を文字にして持ってくる (HTML の場合はタグを落とす)
  ipcMain.handle('ai:fetchUrl', async (_e, url) => {
    const res = await fetch(url, { headers: { 'user-agent': 'Mozilla/5.0 Carrel' } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const type = res.headers.get('content-type') || '';
    if (type.includes('pdf')) {
      const buf = Buffer.from(await res.arrayBuffer());
      return { name: url, mime: 'application/pdf', dataBase64: buf.toString('base64'), size: buf.length };
    }
    if (type.startsWith('image/')) {
      const buf = Buffer.from(await res.arrayBuffer());
      return { name: url, mime: type.split(';')[0], dataBase64: buf.toString('base64'), size: buf.length };
    }
    let html = await res.text();
    html = html.replace(/<script[\s\S]*?<\/script>/gi, '').replace(/<style[\s\S]*?<\/style>/gi, '');
    const title = (/<title[^>]*>([^<]*)<\/title>/i.exec(html) || [])[1] || url;
    let text = html.replace(/<br\s*\/?>|<\/(p|div|li|tr|h\d)>/gi, '\n').replace(/<[^>]+>/g, '');
    text = text.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n').trim();
    return { name: title.trim(), url, text: text.slice(0, 60000) };
  });

  ipcMain.handle('ai:ask', async (_e, payload) => {
    const all = readSettings();
    const pv = activeProvider(all);
    if (!pv) throw new Error('使える AI がありません (設定 › AI で追加して「確認」してください)');
    try {
      return await askProvider(pv, payload);
    } catch (e) {
      if (e && (e.name === 'AbortError' || /abort/i.test(e.message || ''))) throw new Error('中断しました');
      throw e;
    } finally {
      aiAbort = null;
    }
  });
  ipcMain.handle('clipboard:text', () => require('electron').clipboard.readText());
  // 貼り付け (Finder で ⌘C したファイル) の file:// 一覧から、画像・PDF の中身を返す
  ipcMain.handle('files:readUris', (_e, uris) => {
    const mimes = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', pdf: 'application/pdf' };
    const out = [];
    for (const uri of (Array.isArray(uris) ? uris : [])) {
      if (!/^file:\/\//.test(uri)) continue;
      let file;
      try { file = decodeURIComponent(new URL(uri).pathname); } catch { continue; }
      const ext = path.extname(file).slice(1).toLowerCase();
      if (!mimes[ext] || !fs.existsSync(file)) continue;
      const data = fs.readFileSync(file);
      out.push({ name: path.basename(file), mime: mimes[ext], size: data.length, dataBase64: data.toString('base64') });
    }
    return out;
  });
  ipcMain.handle('ai:cancel', () => { if (aiAbort) { try { aiAbort.abort(); } catch { /* すでに終了 */ } } });

  ipcMain.handle('settings:all', () => readSettings());
  ipcMain.handle('settings:set', (_e, key, value) => {
    const all = readSettings();
    if (value === null) delete all[key]; else all[key] = value;
    writeSettings(all);
  });

  const editsFile = () => path.join(HOME, 'pending-edits.json');
  ipcMain.handle('edits:all', () => {
    try { return JSON.parse(fs.readFileSync(editsFile(), 'utf8')); } catch { return {}; }
  });
  ipcMain.handle('edits:save', (_e, all) => {
    try {
      fs.mkdirSync(HOME, { recursive: true });
      fs.writeFileSync(editsFile(), JSON.stringify(all));
    } catch (err) { console.log('[edits] 保存できません: ' + err.message); }
  });

  const trashFile = () => path.join(HOME, 'deleted-notes.json');
  ipcMain.handle('trash:all', () => {
    try { return JSON.parse(fs.readFileSync(trashFile(), 'utf8')); } catch { return []; }
  });
  ipcMain.handle('trash:save', (_e, list) => {
    try {
      fs.mkdirSync(HOME, { recursive: true });
      fs.writeFileSync(trashFile(), JSON.stringify(list));
    } catch (err) { console.log('[trash] 保存できません: ' + err.message); }
  });

  ipcMain.handle('backup:pick', async () => {
    const picked = await dialog.showOpenDialog({
      properties: ['openDirectory', 'createDirectory'],
      message: 'バックアップの保存先を選んでください（iCloud Drive などのフォルダも選べます）',
    });
    return picked.canceled ? null : picked.filePaths[0];
  });

  ipcMain.handle('backup:now', (_e, dir, keep) => {
    const source = collectionPath();
    if (!source || !fs.existsSync(source)) throw new Error('コレクションが見つかりません');
    fs.mkdirSync(dir, { recursive: true });
    const now = new Date();
    const two = (n) => String(n).padStart(2, '0');
    const stamp = `${now.getFullYear()}${two(now.getMonth() + 1)}${two(now.getDate())}` +
      `_${two(now.getHours())}${two(now.getMinutes())}${two(now.getSeconds())}`;
    const target = path.join(dir, `anki_backup_${stamp}.anki2`);
    fs.copyFileSync(source, target);
    const kept = Math.max(1, Number(keep) || 1);
    const files = fs.readdirSync(dir)
      .filter((f) => f.startsWith('anki_backup_') && f.endsWith('.anki2'))
      .sort();
    while (files.length > kept) {
      try { fs.unlinkSync(path.join(dir, files.shift())); } catch { /* 消せなくても続ける */ }
    }
    return { file: target, size: fs.statSync(target).size, count: files.length };
  });

  ipcMain.handle('backup:list', (_e, dir) => {
    try {
      return fs.readdirSync(dir)
        .filter((f) => f.startsWith('anki_backup_') && f.endsWith('.anki2'))
        .sort().reverse()
        .map((f) => ({ name: f, size: fs.statSync(path.join(dir, f)).size }));
    } catch { return []; }
  });

  ipcMain.handle('preview:preload', () => {
    // asar の中は直接読めないので、取り出した方を指す
    const inside = path.join(__dirname, 'renderer/preview-preload.js');
    const outside = inside.replace('app.asar', 'app.asar.unpacked');
    return 'file://' + (fs.existsSync(outside) ? outside : inside);
  });
  // 自分で書いた CSS を読み込む口 (アプリを作り直さずに見た目を変えられる)
  const cssFile = () => path.join(HOME, 'custom.css');
  // 要素を調べる道具 (Chrome の開発者ツールと同じもの)
  ipcMain.handle('devtools:open', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    const wc = mainWindow.webContents;
    if (wc.isDevToolsOpened()) wc.closeDevTools();
    else wc.openDevTools({ mode: 'detach' });
  });

  ipcMain.handle('css:path', () => cssFile());
  ipcMain.handle('css:read', () => {
    try { return fs.readFileSync(cssFile(), 'utf8'); } catch { return ''; }
  });
  ipcMain.handle('css:reveal', () => {
    try {
      fs.mkdirSync(HOME, { recursive: true });
      if (!fs.existsSync(cssFile())) {
        fs.writeFileSync(cssFile(),
          '/* ここに書いた内容が、アプリの見た目に上書きで反映されます。\n' +
          '   保存すると自動で反映されます (アプリの作り直しは不要)。 */\n');
      }
      require('electron').shell.showItemInFolder(cssFile());
    } catch { /* 開けなくても続ける */ }
  });
  // 保存したら、その場で反映する
  try {
    fs.mkdirSync(HOME, { recursive: true });
    fs.watch(HOME, (_type, name) => {
      if (name === 'custom.css' && mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('css-changed');
      }
    });
  } catch { /* 見張れなくても動く */ }

  ipcMain.handle('collection:path', () => collectionPath());
  ipcMain.handle('mark', (_e, name) => mark(name));

  // 実際の打鍵の重さを書き残す (原因を追うため)
  ipcMain.handle('typing:log', (_e, line) => {
    try {
      fs.mkdirSync(HOME, { recursive: true });
      fs.appendFileSync(path.join(HOME, 'typing.log'),
        `${new Date().toISOString()} ${line}\n`);
    } catch { /* 書けなくても操作には影響しない */ }
  });

  // どの処理に時間がかかっているのかを、実際に記録して調べる
  ipcMain.handle('profile:type', async (_e, text, gapMs) => {
    if (!mainWindow) return null;
    const wc = mainWindow.webContents;
    try { wc.debugger.attach('1.3'); } catch { /* すでに付いている */ }
    await wc.debugger.sendCommand('Profiler.enable');
    await wc.debugger.sendCommand('Profiler.setSamplingInterval', { interval: 200 });
    await wc.debugger.sendCommand('Profiler.start');
    for (const ch of text) {
      wc.sendInputEvent({ type: 'keyDown', keyCode: ch });
      wc.sendInputEvent({ type: 'char', keyCode: ch });
      wc.sendInputEvent({ type: 'keyUp', keyCode: ch });
      await new Promise((r) => setTimeout(r, gapMs || 70));
    }
    await new Promise((r) => setTimeout(r, 800));
    const { profile } = await wc.debugger.sendCommand('Profiler.stop');
    try { wc.debugger.detach(); } catch { /* すでに外れている */ }

    // 自分自身に費やした時間で並べる
    const self = new Map();
    const byId = new Map(profile.nodes.map((n) => [n.id, n]));
    const total = profile.timeDeltas.reduce((a, b) => a + Math.max(0, b), 0);
    profile.samples.forEach((id, i) => {
      const dt = Math.max(0, profile.timeDeltas[i] || 0);
      const node = byId.get(id);
      if (!node) return;
      const f = node.callFrame;
      const key = `${f.functionName || '(無名)'} ${(f.url || '').split('/').pop()}:${f.lineNumber + 1}`;
      self.set(key, (self.get(key) || 0) + dt);
    });
    const top = [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)
      .map(([k, v]) => `${k} ${(v / 1000).toFixed(0)}ms`);
    return { totalMs: Math.round(total / 1000), top };
  });

  // 本物のキー入力を送る (打鍵の重さを、実際の経路で測るため)
  // 調べもの用: 本物の右クリックを窓に送る
  ipcMain.handle('input:rightClick', async (_e, x, y) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    const wc = mainWindow.webContents;
    wc.sendInputEvent({ type: 'mouseDown', x, y, button: 'right', clickCount: 1 });
    wc.sendInputEvent({ type: 'mouseUp', x, y, button: 'right', clickCount: 1 });
  });
  ipcMain.handle('input:type', async (_e, text, gapMs) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    for (const ch of text) {
      mainWindow.webContents.sendInputEvent({ type: 'keyDown', keyCode: ch });
      mainWindow.webContents.sendInputEvent({ type: 'char', keyCode: ch });
      mainWindow.webContents.sendInputEvent({ type: 'keyUp', keyCode: ch });
      await new Promise((r) => setTimeout(r, gapMs || 60));
    }
  });

  app.on('activate', async () => {
    if (BrowserWindow.getAllWindows().length) return;
    try { await ensureBridge(); } catch { /* 画面側で知らせる */ }
    if (!BrowserWindow.getAllWindows().length) createWindow();
  });
});

// プレビュー (webview) の中のリンクは、アプリの中で開かずに既定のブラウザへ渡す
app.on('web-contents-created', (_e, contents) => {
  // 画像の右クリック: コピー / 保存
  contents.on('context-menu', (ev, params) => {
    if (params.mediaType !== 'image' || !params.srcURL) return;
    ev.preventDefault();
    const src = params.srcURL;
    const nameOf = () => {
      try {
        if (src.startsWith('data:')) return 'image.' + ((/^data:image\/(\w+)/.exec(src) || [])[1] || 'png').replace('jpeg', 'jpg');
        return decodeURIComponent(new URL(src).pathname.split('/').pop() || 'image.png');
      } catch { return 'image.png'; }
    };
    const bytesOf = async () => {
      if (src.startsWith('media://')) {
        const name = decodeURIComponent(src.replace(/^media:\/\/media\//, ''));
        return fs.readFileSync(path.join(mediaDir, name));
      }
      if (src.startsWith('data:')) return Buffer.from(src.split(',')[1] || '', 'base64');
      const res = await net.fetch(src);
      return Buffer.from(await res.arrayBuffer());
    };
    Menu.buildFromTemplate([
      { label: '画像をコピー', click: () => contents.copyImageAt(params.x, params.y) },
      { label: '画像を保存…', click: async () => {
        try {
          const picked = await dialog.showSaveDialog({ defaultPath: path.join(app.getPath('downloads'), nameOf()) });
          if (picked.canceled || !picked.filePath) return;
          fs.writeFileSync(picked.filePath, await bytesOf());
        } catch (err) { dialog.showErrorBox('保存できませんでした', String(err && err.message)); }
      } },
    ]).popup({ window: BrowserWindow.fromWebContents(contents.hostWebContents || contents) || undefined });
  });
  if (contents.getType() !== 'webview') return;
  const outside = (url) => /^https?:|^mailto:/i.test(url);
  contents.on('will-navigate', (e, url) => {
    if (!outside(url)) return;
    e.preventDefault();
    shell.openExternal(url).catch((err) => mark('ブラウザで開けない: ' + err.message));
  });
  contents.setWindowOpenHandler(({ url }) => {
    if (outside(url)) {
      shell.openExternal(url).catch((err) => mark('ブラウザで開けない: ' + err.message));
    }
    return { action: 'deny' };
  });
});

app.on('will-quit', () => {
  if (bridge) { bridge.stop(); try { bridge.child.kill(); } catch { /* すでに終了 */ } }
});

app.on('window-all-closed', () => {
  // macOS は窓を閉じてもアプリが残る。ここで裏側を止めると、
  // 開き直したときに何も問い合わせられなくなる (同期も一生始まらない)
  if (process.platform === 'darwin') return;
  if (bridge) bridge.stop();
  app.quit();
});
