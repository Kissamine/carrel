import { prefs } from './prefs.js';
import { createFastEditor } from './fasteditor.js';
import { visibleModels, prettyModel, rankModel, BUILTIN_MODELS } from './models.js';
import { DEFAULT_KEYS, KEY_LABELS, currentKeys, keyOf, refreshTitlebarTips } from './app.js';
// 設定シート。左に項目、右に中身 (macOS のシステム設定と同じ並び)。

// keywords は設定内検索のための手がかり
const SECTIONS = [
  { id: 'anki', title: 'Anki',
    keywords: 'Anki プロフィール コレクション 同期 AnkiWeb 場所 本体' },
  { id: 'search', title: '検索',
    keywords: 'デッキ 並び順 ソート 読み込み 件数 起動時 関連順 名前順 更新順' },
  { id: 'tags', title: 'よく使うタグ',
    keywords: 'タグ 絞り込み 表示 非表示 並べ替え 星 ピン' },
  { id: 'keys', title: 'ショートカット',
    keywords: 'ショートカット キー 割り当て キーボード 変更' },
  { id: 'editor', title: 'エディタ',
    keywords: 'エディタ モード テキスト HTML 画像 角 角丸 リアルタイム プレビュー 保存 同期 ショートカット' },
  { id: 'snippets', title: 'スニペット',
    keywords: 'スニペット よく使う HTML コード 定型 挿入 ボタン' },
  { id: 'ai', title: 'AI',
    keywords: 'AI Claude API キー モデル 自動修正 書き直し' },
  { id: 'template', title: 'テンプレート',
    keywords: 'テンプレート 表面 裏面 CSS スタイル ノートタイプ カード' },
  { id: 'trash', title: '削除したカード',
    keywords: '削除 ごみ箱 復元 戻す アーカイブ' },
  { id: 'backup', title: 'バックアップ',
    keywords: 'バックアップ 控え 保存先 自動 間隔 手動 iCloud Dropbox 置き換え' },
  { id: 'media', title: 'メディア整理',
    keywords: 'メディア 画像 音声 動画 未使用 削除 整理' },
  { id: 'appearance', title: '表示',
    keywords: '表示 文字サイズ ズーム プレビュー 最小幅 既定 幅 見た目' },
];

export function openSettings(ctx) {
  ctx.reload = () => { renderDetail(); };
  const dialog = document.getElementById('settingsDialog');
  const nav = document.getElementById('settingsNav');
  const detail = document.getElementById('settingsDetail');
  let current = ctx.section || 'account';

  let filter = '';

  function matches(section) {
    if (!filter) return true;
    const needle = filter.toLowerCase();
    return (section.title + ' ' + (section.keywords || '')).toLowerCase().includes(needle);
  }

  function renderNav() {
    nav.innerHTML = '';
    const found = SECTIONS.filter(matches);
    if (!found.length) {
      nav.innerHTML = '<div class="nav-empty">見つかりません</div>';
      detail.innerHTML = '';
      return;
    }
    // 探している項目が今の場所に無ければ、最初の候補へ移る
    if (!found.some((x) => x.id === current)) {
      current = found[0].id;
      renderDetail();
    }
    for (const s of found) {
      const b = document.createElement('button');
      b.textContent = s.title;
      if (s.id === current) b.classList.add('on');
      b.onclick = () => { current = s.id; renderNav(); renderDetail(); };
      nav.appendChild(b);
    }
  }

  async function renderDetail() {
    detail.innerHTML = '';
    const section = SECTIONS.find((s) => s.id === current);
    const h = document.createElement('h3');
    h.textContent = section.title;
    detail.appendChild(h);
    if (current === 'anki') await ankiPane(detail, ctx);
    if (current === 'search') await searchPane(detail, ctx);
    if (current === 'tags') tagsPane(detail, ctx);
    if (current === 'keys') keysPane(detail, ctx);
    if (current === 'editor') editorPane(detail, ctx);
    if (current === 'template') await templatePane(detail, ctx);
    if (current === 'snippets') snippetsPane(detail, ctx);
    if (current === 'ai') await aiPane(detail, ctx);
    if (current === 'appearance') appearancePane(detail, ctx);
    if (current === 'trash') await trashPane(detail, ctx);
    if (current === 'backup') await backupPane(detail, ctx);
    if (current === 'media') await mediaPane(detail, ctx);
  }

  const search = document.getElementById('settingsSearch');
  search.value = '';
  search.oninput = () => { filter = search.value.trim(); renderNav(); };

  renderNav();
  renderDetail();
  ctx.reload = renderDetail;
  const close = () => {
    dialog.classList.add('closing');
    setTimeout(() => { dialog.classList.remove('closing'); dialog.close(); }, 180);
  };
  document.getElementById('settingsClose').onclick = close;
  dialog.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
  dialog.showModal();
}

function row(parent, hint) {
  const box = document.createElement('div');
  box.className = 'row';
  if (hint) {
    const p = document.createElement('div');
    p.className = 'hint';
    p.textContent = hint;
    box.appendChild(p);
  }
  parent.appendChild(box);
  return box;
}

async function ankiPane(detail, ctx) {
  const st = await ctx.ankiStatus();
  const box = row(detail, 'Carrel は Anki 本体のプロフィールを直接開いて編集します。AnkiWeb との同期は Anki 本体で行ってください');
  if (!st.profiles.length) {
    const none = document.createElement('div');
    none.className = 'hint';
    none.textContent = 'プロフィールが見つかりません。Anki をインストールして一度起動してください (~/Library/Application Support/Anki2)';
    box.appendChild(none);
    return;
  }
  const sel = document.createElement('select');
  sel.innerHTML = st.profiles.map(() => '<option></option>').join('');
  [...sel.options].forEach((o, i) => { o.value = st.profiles[i]; o.textContent = st.profiles[i]; });
  sel.value = st.profile || st.profiles[0];
  sel.onchange = async () => {
    await ctx.ankiSetProfile(sel.value);
    ctx.toast('プロフィールを ' + sel.value + ' にしました');
    await ctx.refreshLogin();
    ctx.reload();
  };
  const where = document.createElement('div');
  where.className = 'hint';
  where.style.marginTop = '8px';
  where.textContent = st.path || '';
  const state = document.createElement('div');
  state.className = 'hint';
  state.textContent = st.running
    ? 'Anki が起動中です。Anki を終了すると Carrel が編集を再開します'
    : 'Anki は終了しています (Carrel が編集できます)';
  const open = document.createElement('button');
  open.className = 'pill';
  open.textContent = 'Anki を開いて同期する';
  open.style.marginTop = '10px';
  open.onclick = () => { ctx.sync(); };
  box.append(sel, where, state, open);

  // iPhone 版のための iCloud Drive への書き出し
  const ms = await window.anki.mirrorStatus();
  const mbox = row(detail, 'iPhone 版 Carrel 用に iCloud Drive へ書き出す');
  const toggle = document.createElement('label');
  toggle.style.cssText = 'display:flex;align-items:center;gap:8px;cursor:pointer';
  const cb = document.createElement('input'); cb.type = 'checkbox'; cb.checked = ms.enabled;
  cb.onchange = async () => { await window.anki.mirrorSetEnabled(cb.checked); ctx.toast(cb.checked ? '保存のたびに iCloud へ書き出します' : 'iCloud への書き出しを止めました'); };
  toggle.append(cb, document.createTextNode('保存のたびに、変わった分だけ自動で書き出す'));
  const mwhere = document.createElement('div');
  mwhere.className = 'hint'; mwhere.style.marginTop = '8px';
  mwhere.textContent = (ms.dir || '') + (ms.at ? '　(最終: ' + new Date(ms.at).toLocaleString('ja-JP') + ')' : '');
  const now = document.createElement('button');
  now.className = 'pill'; now.textContent = '今すぐ書き出す'; now.style.marginTop = '10px';
  now.onclick = async () => {
    now.disabled = true; now.textContent = '書き出しています…';
    try {
      const r = await window.anki.mirrorNow(true);
      ctx.toast(r ? '書き出しました (メディア ' + r.copied + ' 件を更新)' : '書き出せませんでした');
    } catch (e) { ctx.toast('書き出しに失敗: ' + e.message); }
    now.disabled = false; now.textContent = '今すぐ書き出す';
  };
  const mhint = document.createElement('div');
  mhint.className = 'hint'; mhint.style.marginTop = '8px';
  mhint.textContent = 'iPhone 版 Carrel の設定で、このフォルダを一度選んでください。以後はアプリを開くたびに変更分を自動で取り込みます';
  mbox.append(toggle, mwhere, now, mhint);
}

async function accountPaneLegacy(detail, ctx) {
  const stored = prefs.getItem('auth');
  if (stored) {
    const who = prefs.getItem('ankiUser') || 'AnkiWeb アカウント';
    const box = row(detail, 'ログイン中');
    const name = document.createElement('div');
    name.textContent = who;
    name.style.cssText = 'font-size:15px;margin-bottom:12px';
    box.appendChild(name);

    const sync = document.createElement('button');
    sync.className = 'pill';
    sync.textContent = '今すぐ同期';
    sync.onclick = () => { ctx.sync(); };
    const out = document.createElement('button');
    out.className = 'pill ghost';
    out.textContent = 'ログアウト';
    out.style.marginLeft = '8px';
    out.onclick = () => {
      prefs.removeItem('auth');
      prefs.removeItem('ankiUser');
      ctx.toast('ログアウトしました');
      ctx.refreshLogin();
      ctx.reload();
    };
    box.append(sync, out);

    return;
  }

  const box = row(detail, '未ログイン（カードと画像の同期に必要）');
  const user = document.createElement('input');
  user.placeholder = 'AnkiWeb のメールアドレス';
  const pass = document.createElement('input');
  pass.type = 'password';
  pass.placeholder = 'パスワード';
  pass.style.marginTop = '6px';
  const button = document.createElement('button');
  button.className = 'pill';
  button.textContent = 'ログイン';
  button.style.marginTop = '10px';
  button.onclick = async () => {
    try {
      const auth = await ctx.call('login', { username: user.value, password: pass.value });
      prefs.setItem('auth', JSON.stringify(auth));
      prefs.setItem('ankiUser', user.value);
      ctx.toast('ログインしました');
      ctx.refreshLogin();
      ctx.reload();
    } catch (e) {
      ctx.toast('ログインできません: ' + e.message);
    }
  };
  box.append(user, pass, button);
}

async function searchPane(detail, ctx) {
  const decks = await ctx.call('decks', {});
  const box = row(detail, '起動時のデッキ');
  const select = document.createElement('select');
  select.innerHTML = ['すべてのデッキ', ...decks].map((d) => `<option>${d}</option>`).join('');
  select.value = prefs.getItem('defaultDeck') || 'すべてのデッキ';
  select.onchange = () => prefs.setItem('defaultDeck', select.value);
  box.appendChild(select);

  const sortBox = row(detail, '並び順');
  const sort = document.createElement('select');
  sort.innerHTML = [
    ['NAME_ASC', '名前（昇順）'], ['NAME_DESC', '名前（降順）'],
    ['MTIME_DESC', '更新が新しい順'], ['MTIME_ASC', '更新が古い順'],
  ].map(([v, t]) => `<option value="${v}">${t}</option>`).join('');
  sort.value = prefs.getItem('sortMode') || 'NAME_ASC';
  sort.onchange = () => { prefs.setItem('sortMode', sort.value); ctx.research(); };
  sortBox.appendChild(sort);

  const limitBox = row(detail, '一度に読み込む件数');
  const limit = document.createElement('input');
  limit.type = 'number';
  limit.value = prefs.getItem('searchLimit') || '300';
  limit.onchange = () => { prefs.setItem('searchLimit', limit.value); ctx.research(); };
  limitBox.appendChild(limit);

}

function tagsPane(detail, ctx) {
  const pinned = new Set(JSON.parse(prefs.getItem('pinnedTags') || '[]'));
  const hidden = new Set(JSON.parse(prefs.getItem('hiddenTags') || '[]'));
  let order = JSON.parse(prefs.getItem('tagOrder') || '[]');
  order = [...order.filter((t) => ctx.tags.includes(t)),
           ...ctx.tags.filter((t) => !order.includes(t))];
  let filter = '';

  const save = () => {
    prefs.setItem('pinnedTags', JSON.stringify([...pinned]));
    prefs.setItem('hiddenTags', JSON.stringify([...hidden]));
    prefs.setItem('tagOrder', JSON.stringify(order));
    ctx.retag();
  };

  // ---- 上段: 説明と操作 ----
  const head = row(detail,
    '★ を付けたタグは、次にアプリを開いたときの絞り込みに使われます。' +
    '目のボタンで一覧に出すかどうかを切り替え、掴んで並べ替えられます');

  const tools = document.createElement('div');
  tools.className = 'tag-tools';
  const search = document.createElement('input');
  search.placeholder = 'タグを探す';
  search.oninput = () => { filter = search.value.trim(); draw(); };
  const showAll = document.createElement('button');
  showAll.className = 'pill ghost';
  showAll.textContent = 'すべて表示';
  showAll.onclick = () => { hidden.clear(); save(); draw(); };
  const hideEmpty = document.createElement('button');
  hideEmpty.className = 'pill ghost';
  hideEmpty.textContent = '0 件を隠す';
  hideEmpty.onclick = () => {
    order.forEach((t) => { if (!ctx.tagCounts[t]) hidden.add(t); });
    save(); draw();
  };
  const clearStars = document.createElement('button');
  clearStars.className = 'pill ghost';
  clearStars.textContent = '★ を外す';
  clearStars.onclick = () => { pinned.clear(); save(); draw(); };
  tools.append(search, showAll, hideEmpty, clearStars);
  head.appendChild(tools);

  // ---- 選んでいるタグのまとめ ----
  const summary = document.createElement('div');
  summary.className = 'tag-summary';
  head.appendChild(summary);

  const list = document.createElement('div');
  list.className = 'tag-list';
  head.appendChild(list);

  let dragging = null;

  function draw() {
    summary.textContent = pinned.size
      ? `起動時の絞り込み: ${[...pinned].join('  ')}`
      : '起動時の絞り込み: なし（すべてのカードを表示）';

    list.innerHTML = '';
    const shown = order.filter((t) => !filter || t.includes(filter));
    if (!shown.length) {
      list.innerHTML = '<div class="tag-empty">見つかりません</div>';
      return;
    }
    for (const tag of shown) {
      const count = ctx.tagCounts[tag];
      const line = document.createElement('div');
      line.className = 'tag-line' + (hidden.has(tag) ? ' off' : '');
      line.draggable = true;

      line.addEventListener('dragstart', () => { dragging = tag; line.classList.add('dragging'); });
      line.addEventListener('dragend', () => { dragging = null; line.classList.remove('dragging'); save(); });
      line.addEventListener('dragover', (e) => {
        e.preventDefault();
        if (!dragging || dragging === tag) return;
        const from = order.indexOf(dragging);
        const to = order.indexOf(tag);
        order.splice(from, 1);
        order.splice(to, 0, dragging);
        draw();
      });

      const grip = document.createElement('span');
      grip.className = 'grip';
      grip.textContent = '⠿';

      const star = document.createElement('button');
      star.className = 'star' + (pinned.has(tag) ? ' on' : '');
      star.textContent = pinned.has(tag) ? '★' : '☆';
      star.title = '起動時の絞り込みに使う';
      star.onclick = () => {
        if (pinned.has(tag)) pinned.delete(tag); else pinned.add(tag);
        save(); draw();
      };

      const name = document.createElement('span');
      name.className = 'tag-name';
      name.textContent = tag;

      const num = document.createElement('span');
      num.className = 'tag-count' + (count ? '' : ' zero');
      num.textContent = count === undefined ? '' : `${count ? count + ' 件' : '0 件'}`;

      const eye = document.createElement('button');
      eye.className = 'eye';
      eye.innerHTML = hidden.has(tag)
        ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">' +
          '<path d="M3 3l18 18"/><path d="M10.6 5.1A9.6 9.6 0 0 1 12 5c5 0 9 4.5 9 7 0 .9-.5 2-1.4 3.1"/>' +
          '<path d="M6.5 6.9C4.3 8.4 3 10.6 3 12c0 2.5 4 7 9 7 1.6 0 3-.4 4.2-1"/>' +
          '<path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/></svg>'
        : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">' +
          '<path d="M3 12s3.5-7 9-7 9 7 9 7-3.5 7-9 7-9-7-9-7z"/><circle cx="12" cy="12" r="3"/></svg>';
      eye.title = '一覧に出すかどうか';
      eye.onclick = () => {
        if (hidden.has(tag)) hidden.delete(tag); else hidden.add(tag);
        save(); draw();
      };

      line.append(grip, star, name, num, eye);
      list.appendChild(line);
    }
  }
  draw();
}

/** ウインドウタブのボタンに、キーの割り当てを決める */
function keysPane(detail, ctx) {
  const box = row(detail, '「変更」を押してから、割り当てたいキーを押してください');
  const list = document.createElement('div');
  list.className = 'keys-list';
  box.appendChild(list);

  const pretty = (combo) => combo
    .replace('Mod', '⌘')
    .replace('Shift', '⇧')
    .replace('Alt', '⌥')
    .replace(/\+/g, ' ');

  function draw() {
    const keys = currentKeys();
    list.innerHTML = '';
    for (const [name, label] of Object.entries(KEY_LABELS)) {
      const line = document.createElement('div');
      line.className = 'key-line';
      const what = document.createElement('span');
      what.className = 'what';
      what.textContent = label;
      const combo = document.createElement('kbd');
      combo.textContent = pretty(keys[name]);
      const change = document.createElement('button');
      change.className = 'pill ghost';
      change.textContent = '変更';
      change.onclick = () => {
        combo.textContent = 'キーを押してください…';
        line.classList.add('waiting');
        const grab = (e) => {
          e.preventDefault();
          const pressed = keyOf(e);
          if (!pressed) return;                 // 修飾キーだけなら待ち続ける
          window.removeEventListener('keydown', grab, true);
          line.classList.remove('waiting');
          const saved = { ...currentKeys(), [name]: pressed };
          prefs.setItem('shortcuts', JSON.stringify(saved));
          refreshTitlebarTips();
          draw();
        };
        window.addEventListener('keydown', grab, true);
      };
      line.append(what, combo, change);
      list.appendChild(line);
    }

    const reset = document.createElement('button');
    reset.className = 'pill ghost';
    reset.textContent = '初期状態に戻す';
    reset.style.marginTop = '12px';
    reset.onclick = () => {
      prefs.setItem('shortcuts', JSON.stringify(DEFAULT_KEYS));
      refreshTitlebarTips();
      draw();
    };
    list.appendChild(reset);
  }
  draw();
}

function editorPane(detail, ctx) {
  const modeBox = row(detail, 'エディタを開いたときのモード');
  const mode = document.createElement('select');
  mode.innerHTML = '<option value="rich">テキスト</option><option value="html">HTML</option>';
  mode.value = prefs.getItem('editorMode') || 'rich';
  mode.onchange = () => prefs.setItem('editorMode', mode.value);
  modeBox.appendChild(mode);

  const cornerBox = row(detail, '挿入する画像の角');
  const corner = document.createElement('select');
  corner.innerHTML = '<option value="SQUARE">四角</option><option value="ROUNDED">角丸</option>';
  corner.value = prefs.getItem('imageCorner') || 'SQUARE';
  cornerBox.appendChild(corner);

  // 角丸の大きさ。カードの CSS の「.round { border-radius: …px }」に反映する
  const radiusBox = row(detail, '角丸の大きさ (px) — 「角丸」で入れた画像 (class="round") に効きます');
  const radiusLine = document.createElement('div');
  radiusLine.className = 'pick-line';
  const radius = document.createElement('input');
  radius.type = 'number';
  radius.min = '0';
  radius.max = '64';
  radius.style.width = '90px';
  const radiusNote = document.createElement('span');
  radiusNote.className = 'hint';
  radiusNote.style.margin = '0 0 0 8px';
  radiusLine.append(radius, radiusNote);
  radiusBox.appendChild(radiusLine);
  radiusBox.hidden = corner.value !== 'ROUNDED';
  corner.onchange = () => {
    prefs.setItem('imageCorner', corner.value);
    radiusBox.hidden = corner.value !== 'ROUNDED';
  };

  // いまカードの CSS に書いてある値を読む
  let radiusTypeId = null;
  (async () => {
    try {
      const types = await ctx.call('notetypes', {});
      // 使っているノートタイプ (ノートが一番多いもの) を対象にする
      const target = (types || []).slice().sort((a, b) => (b.notes || 0) - (a.notes || 0))[0];
      if (!target) return;
      radiusTypeId = target.id;
      const tpl = await ctx.call('templateOf', { notetypeId: target.id });
      const m = /\.round\s*\{[^}]*border-radius\s*:\s*([\d.]+)px/.exec((tpl && tpl.css) || '');
      radius.value = m ? m[1] : '';
      radiusNote.textContent = m ? `カード CSS (${target.name}) の .round に反映` : `.round の定義が無いので、保存時に足します (${target.name})`;
    } catch { radiusNote.textContent = 'カードの CSS を読めませんでした'; }
  })();
  radius.onchange = async () => {
    const px = Math.max(0, Math.min(64, Number(radius.value) || 0));
    radius.value = String(px);
    if (!radiusTypeId) return;
    try {
      const tpl = await ctx.call('templateOf', { notetypeId: radiusTypeId });
      let css = (tpl && tpl.css) || '';
      if (/\.round\s*\{[^}]*\}/.test(css)) {
        css = css.replace(/(\.round\s*\{[^}]*border-radius\s*:\s*)[\d.]+px/, `$1${px}px`);
        if (!/\.round\s*\{[^}]*border-radius/.test(css)) {
          css = css.replace(/\.round\s*\{/, `.round { border-radius: ${px}px; `);
        }
      } else {
        css = css.replace(/\s*$/, `\n\n/* 「角丸」で入れた画像 */\n.round { border-radius: ${px}px; }\n`);
      }
      await ctx.call('saveTemplate', { notetypeId: radiusTypeId, front: tpl.front, back: tpl.back, css });
      ctx.toast(`角丸を ${px}px にしました`);
      ctx.redrawCard();
    } catch (e) { ctx.toast('CSS を更新できませんでした: ' + (e.message || e)); }
  };

  const wrapBox = row(detail, 'HTML エディタの長い行');
  const wrap = document.createElement('select');
  wrap.innerHTML = '<option value="on">折り返す</option><option value="off">折り返さない (横にスクロール)</option>';
  wrap.value = prefs.getItem('editorWrap') === 'off' ? 'off' : 'on';
  wrap.onchange = () => { prefs.setItem('editorWrap', wrap.value); if (ctx.applyEditorWrap) ctx.applyEditorWrap(); };
  wrapBox.appendChild(wrap);
  const wrapHint = document.createElement('p');
  wrapHint.className = 'hint';
  wrapHint.textContent = '折り返すと、2 行目以降はインデントの位置に揃いません (入力欄の仕組み上)。インデントを崩したくない場合は「折り返さない」を選んでください。';
  wrapBox.appendChild(wrapHint);

  const liveBox = row(detail, 'リアルタイムプレビュー');
  const live = document.createElement('select');
  live.innerHTML = '<option value="on">打つたびに更新</option><option value="off">手動で更新</option>';
  live.value = prefs.getItem('livePreview') || 'on';
  live.onchange = () => prefs.setItem('livePreview', live.value);
  liveBox.appendChild(live);
}

async function templatePane(detail, ctx) {
  const types = await ctx.call('notetypes', {});
  // 上に留まる部分 (ノートタイプ・タブ・検索)。テンプレート本体だけが巻かれる
  const top = document.createElement('div');
  top.className = 'tpl-sticky';
  const title = detail.querySelector('h3');
  detail.appendChild(top);
  if (title) top.prepend(title);            // 見出しも上に留める
  const pick = row(top, 'ノートタイプ');
  const select = document.createElement('select');
  select.innerHTML = types.map((t) => `<option value="${t.id}">${t.name}</option>`).join('');
  pick.appendChild(select);
  const head = document.createElement('div');
  top.appendChild(head);

  const area = document.createElement('div');
  detail.appendChild(area);

  async function load() {
    const tpl = await ctx.call('templateOf', { notetypeId: Number(select.value) });
    area.innerHTML = '';
    head.innerHTML = '';
    if (!tpl) { area.textContent = 'テンプレートがありません'; return; }

    // 上段: 表面 / 裏面 / CSS のタブ と 保存
    const bar = document.createElement('div');
    bar.className = 'tpl-bar';
    const tabs = document.createElement('div');
    tabs.className = 'ai-seg tpl-tabs';
    const TAB_NAMES = ['表面', '裏面', 'CSS'];
    TAB_NAMES.forEach((name, k) => {
      const b = document.createElement('button');
      b.textContent = name;
      b.dataset.index = String(k);
      if (k === 0) b.classList.add('on');
      tabs.appendChild(b);
    });
    const save = document.createElement('button');
    save.className = 'pill';
    save.textContent = '保存して反映';
    save.title = '⌘S でも保存できます';
    save.disabled = true;
    const grow = document.createElement('span');
    grow.className = 'grow';
    bar.append(tabs, grow, save);

    // 中段: テンプレート内検索
    const findBar = document.createElement('div');
    findBar.className = 'editor-find tpl-find';
    findBar.innerHTML = `
      <svg viewBox="0 0 24 24"><path d="M10 4a6 6 0 1 0 3.5 10.9l4.8 4.8 1.4-1.4-4.8-4.8A6 6 0 0 0 10 4zm0 2a4 4 0 1 1 0 8 4 4 0 0 1 0-8z" fill="currentColor"/></svg>
      <input placeholder="テンプレート内検索 (⌘F)">
      <span class="count"></span>
      <button class="prev">‹</button><button class="next">›</button>`;
    const findInput = findBar.querySelector('input');
    const findCount = findBar.querySelector('.count');

    const holder = document.createElement('div');
    holder.className = 'template-editor';
    head.append(bar, findBar);
    area.append(holder);

    const doSave = async () => {
      const [front, back, css] = editor.values();
      try {
        await ctx.call('saveTemplate', { notetypeId: Number(select.value), front, back, css });
        save.disabled = false;
        save.disabled = true;
        ctx.toast('テンプレートを保存しました');
        ctx.redrawCard();                       // 開いているカードに反映する
      } catch (e) {
        ctx.toast('保存できませんでした: ' + (e.message || e));
      }
    };
    const editor = createFastEditor(
      holder,
      TAB_NAMES,
      [tpl.front, tpl.back, tpl.css],
      {
        onChange: () => { save.disabled = false; },
        onDirty: () => { save.disabled = false; },
        onSave: doSave,
        onFind: () => { findInput.focus(); findInput.select(); },
        langOf: (i) => (i === 2 ? 'css' : 'html'),
      },
    );
    save.onclick = doSave;

    // タブ: 選んだ欄だけを見せる (エディタは 1 つのまま。検索は 3 つにまたがる)
    const show = (k) => {
      holder.querySelectorAll('.field-label').forEach((el, n) => { el.hidden = n !== k; });
      holder.querySelectorAll('.field').forEach((el, n) => { el.hidden = n !== k; });
      holder.querySelectorAll('.field-shots').forEach((el, n) => { if (n !== k) el.hidden = true; });
      tabs.querySelectorAll('button').forEach((b) => b.classList.toggle('on', Number(b.dataset.index) === k));
      editor.refit();
    };
    tabs.querySelectorAll('button').forEach((b) => { b.onclick = () => show(Number(b.dataset.index)); });
    show(0);

    // 検索: 見つかった欄のタブへ自動で移る
    let total = 0;
    const showHit = (at) => {
      findCount.textContent = total ? `${at}/${total}` : (findInput.value ? '0' : '');
      const cur = holder.querySelector('mark.hit.current');
      if (cur) {
        const field = cur.closest('.field');
        const k = [...holder.querySelectorAll('.field')].indexOf(field);
        if (k >= 0 && field.hidden) show(k);
      }
    };
    findInput.addEventListener('focus', () => findBar.classList.add('active'));
    findInput.addEventListener('blur', () => findBar.classList.remove('active'));
    findInput.addEventListener('input', () => { total = editor.search(findInput.value); showHit(editor.index()); });
    findInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); showHit(editor.move(e.shiftKey ? -1 : 1)); }
      if (e.key === 'ArrowRight') { e.preventDefault(); showHit(editor.move(1)); }
      if (e.key === 'ArrowLeft') { e.preventDefault(); showHit(editor.move(-1)); }
      if (e.key === 'Escape') { editor.clear(); findInput.value = ''; total = 0; showHit(0); }
      e.stopPropagation();
    });
    findBar.querySelector('.prev').onclick = () => showHit(editor.move(-1));
    findBar.querySelector('.next').onclick = () => showHit(editor.move(1));
  }

  select.onchange = load;
  await load();
}

/** よく使う HTML を登録する。HTML エディタの検索欄の下にボタンで並ぶ */
export function loadSnippets() {
  try { return JSON.parse(prefs.getItem('htmlSnippets') || '[]'); } catch { return []; }
}
function snippetsPane(detail, ctx) {
  const list = loadSnippets();
  let at = list.length ? 0 : -1;             // いま開いているもの
  const save = () => {
    prefs.setItem('htmlSnippets', JSON.stringify(list));
    if (ctx.refreshSnippets) ctx.refreshSnippets();
  };

  const hint = document.createElement('p');
  hint.className = 'hint';
  hint.textContent = 'HTML エディタの検索欄の下にボタンとして並びます。押すとカーソル位置に差し込まれ、{cursor} と書いた場所にカーソルが来ます。';
  detail.appendChild(hint);

  // 上: エディタに出るのと同じ並びのボタン。押すと下で編集できる
  const strip = document.createElement('div');
  strip.className = 'snip-strip';
  detail.appendChild(strip);

  // 下: 選んだものの編集
  const pane = document.createElement('div');
  pane.className = 'snip-pane';
  detail.appendChild(pane);

  function drawStrip() {
    strip.innerHTML = '';
    list.forEach((item, k) => {
      const b = document.createElement('button');
      b.className = 'snip-chip' + (k === at ? ' on' : '');
      b.textContent = item.name || '(無題)';
      b.onclick = () => { at = k; drawStrip(); drawPane(); };
      strip.appendChild(b);
    });
    const add = document.createElement('button');
    add.className = 'snip-chip add';
    add.textContent = '＋';
    add.title = '新しいスニペット';
    add.onclick = () => {
      list.push({ name: '', html: '' });
      at = list.length - 1;
      save(); drawStrip(); drawPane();
      const name = pane.querySelector('input');
      if (name) name.focus();
    };
    strip.appendChild(add);
  }

  function drawPane() {
    pane.innerHTML = '';
    const item = list[at];
    if (!item) {
      pane.innerHTML = '<div class="hint">まだありません。「＋」で作ってください。</div>';
      return;
    }
    const head = document.createElement('div');
    head.className = 'snip-head';
    const name = document.createElement('input');
    name.placeholder = 'ボタンに出す名前';
    name.value = item.name || '';
    name.oninput = () => { item.name = name.value; save(); drawStrip(); };
    const left = document.createElement('button');
    left.className = 'pill ghost';
    left.textContent = '←';
    left.title = '左へ';
    left.disabled = at === 0;
    left.onclick = () => { [list[at - 1], list[at]] = [list[at], list[at - 1]]; at--; save(); drawStrip(); drawPane(); };
    const right = document.createElement('button');
    right.className = 'pill ghost';
    right.textContent = '→';
    right.title = '右へ';
    right.disabled = at === list.length - 1;
    right.onclick = () => { [list[at + 1], list[at]] = [list[at], list[at + 1]]; at++; save(); drawStrip(); drawPane(); };
    const del = document.createElement('button');
    del.className = 'pill danger';
    del.textContent = '削除';
    del.onclick = () => {
      list.splice(at, 1);
      at = Math.min(at, list.length - 1);
      save(); drawStrip(); drawPane();
    };
    head.append(name, left, right, del);

    const code = document.createElement('textarea');
    code.className = 'snip-code';
    code.placeholder = '差し込む HTML  (例: <div class="mini">{cursor}</div>)';
    code.value = item.html || '';
    code.spellcheck = false;
    code.oninput = () => { item.html = code.value; save(); };
    code.addEventListener('keydown', (e) => {
      if (e.key !== 'Tab') return;              // タブ文字もそのまま入れられるように
      e.preventDefault();
      const a = code.selectionStart;
      code.value = code.value.slice(0, a) + '\t' + code.value.slice(code.selectionEnd);
      code.selectionStart = code.selectionEnd = a + 1;
      item.html = code.value; save();
    });

    const foot = document.createElement('div');
    foot.className = 'snip-foot';
    const cursorBtn = document.createElement('button');
    cursorBtn.className = 'pill ghost';
    cursorBtn.textContent = '{cursor} を入れる';
    cursorBtn.onclick = () => {
      const a = code.selectionStart;
      code.value = code.value.slice(0, a) + '{cursor}' + code.value.slice(code.selectionEnd);
      code.selectionStart = code.selectionEnd = a + 8;
      item.html = code.value; save(); code.focus();
    };
    foot.append(cursorBtn);

    pane.append(head, code, foot);
  }

  drawStrip();
  drawPane();
}

/** 組み込み AI の設定。Claude / ChatGPT / Gemini を登録し、確認できたものだけ使える */
export const AI_KIND_INFO = {
  anthropic: { label: 'Claude', hint: 'sk-ant-…',
    models: [
      ['claude-opus-5-5', 'Opus 5.5'],
      ['claude-sonnet-5-5', 'Sonnet 5.5'],
      ['claude-fable-5-1', 'Fable 5.1'],
      ['claude-fable-5', 'Fable 5'],
      ['claude-opus-5', 'Opus 5'],
      ['claude-sonnet-5', 'Sonnet 5'],
      ['claude-haiku-4-5-20251001', 'Haiku 4.5'],
    ] },
  openai: { label: 'ChatGPT', hint: 'sk-…',
    models: [
      ['gpt-5.3-chat-latest', 'GPT-5.3'],
      ['gpt-5.2-pro', 'GPT-5.2 Pro'], ['gpt-5.2', 'GPT-5.2'],
      ['gpt-5.1', 'GPT-5.1'],
      ['gpt-5-pro', 'GPT-5 Pro'], ['gpt-5', 'GPT-5'], ['gpt-5-mini', 'GPT-5 mini'],
      ['gpt-4.1', 'GPT-4.1'],
    ] },
  gemini: { label: 'Gemini', hint: 'AIza…',
    models: [['gemini-2.5-pro', 'Gemini 2.5 Pro'], ['gemini-2.5-flash', 'Gemini 2.5 Flash']] },
};

async function aiPane(detail, ctx) {
  const hint = document.createElement('p');
  hint.className = 'hint';
  hint.textContent = 'エディタの検索欄右端「AI」から、選んだ範囲・欄・エディタ全体を指示どおりに書き直させます。' +
    '「確認」が通ったものだけが使え、複数あるときは「使う」で切り替えます。鍵はこの Mac の設定ファイルにだけ保存されます。';
  detail.appendChild(hint);

  const scopeBox = row(detail, 'AI の作業範囲 (窓を開いたときの既定)');
  const scopeSel = document.createElement('select');
  scopeSel.innerHTML =
    '<option value="auto">自動 (範囲を選んでいれば選択範囲、なければこの欄)</option>' +
    '<option value="selection">選択範囲 (選んでいなければこの欄)</option>' +
    '<option value="field">この欄</option>' +
    '<option value="all">全体</option>';
  scopeSel.value = prefs.getItem('aiScopeDefault') || 'auto';
  scopeSel.onchange = () => prefs.setItem('aiScopeDefault', scopeSel.value);
  scopeBox.appendChild(scopeSel);

  const webBox = row(detail, '必要なとき AI が Web を検索して確かめる (Claude / Gemini)');
  const web = document.createElement('select');
  web.innerHTML = '<option value="on">許可する</option><option value="off">許可しない</option>';
  web.value = prefs.getItem('aiWebSearch') === 'off' ? 'off' : 'on';
  web.onchange = () => prefs.setItem('aiWebSearch', web.value);
  webBox.appendChild(web);

  const list = document.createElement('div');
  list.className = 'ai-list';
  detail.appendChild(list);

  // 記憶: 他のカードから学んだ書き方。見る・直す・学び直す
  const memHead = document.createElement('h4');
  memHead.textContent = '記憶 (このデッキの書き方)';
  memHead.className = 'ai-mem-head';
  const memHint = document.createElement('p');
  memHint.className = 'hint';
  memHint.textContent = '他のカードから抜き出した class・表・見出しの並びなどのメモです。AI に指示するとき毎回いっしょに渡され、特に指示が無ければこの書き方に合わせます。自由に書き足せます。';
  const memBox = document.createElement('textarea');
  memBox.className = 'ai-memory-box';
  memBox.placeholder = 'まだありません。「カードから学ぶ」を押すと、開いているカードと同じノートタイプのカードから抜き出します。';
  const memRow = document.createElement('div');
  memRow.className = 'ai-row act';
  const learn = document.createElement('button');
  learn.className = 'pill';
  learn.textContent = 'カードから学ぶ';
  const memSave = document.createElement('button');
  memSave.className = 'pill ghost';
  memSave.textContent = 'メモを保存';
  const memWhen = document.createElement('span');
  memWhen.className = 'hint';
  memRow.append(learn, memSave, memWhen);
  const showMem = async () => {
    const m = ctx.memoryGet ? await ctx.memoryGet() : null;
    memBox.value = m && m.text ? m.text : '';
    memWhen.textContent = m && m.builtAt ? '更新: ' + new Date(m.builtAt).toLocaleString('ja-JP') : '';
  };
  // 学び方 と 対象のカード
  const modeRow = document.createElement('div');
  modeRow.className = 'ai-row';
  const modeLabel = document.createElement('span');
  modeLabel.className = 'hint';
  modeLabel.textContent = '学び方';
  const modeSel = document.createElement('select');
  modeSel.innerHTML = '<option value="merge">いまのメモを土台に更新 (足す・消す・簡潔に)</option>' +
    '<option value="append">いまのメモはそのまま、末尾に追記</option>' +
    '<option value="rebuild">根本から作り直す</option>';
  modeRow.append(modeLabel, modeSel);
  const pickRow = document.createElement('div');
  pickRow.className = 'ai-row';
  const pickLabel = document.createElement('span');
  pickLabel.className = 'hint';
  pickLabel.textContent = '対象';
  const pickInput = document.createElement('input');
  pickInput.placeholder = 'カードを検索して追加 (空なら同じノートタイプから自動で選ぶ)';
  const pickList = document.createElement('div');
  pickList.className = 'ai-mention mem-pick';
  pickList.hidden = true;
  const chosen = [];
  const chosenBox = document.createElement('div');
  chosenBox.className = 'mem-chosen';
  const drawChosen = () => {
    chosenBox.innerHTML = '';
    chosen.forEach((n, k) => {
      const c = document.createElement('span');
      c.className = 'chip-file note';
      const t = document.createElement('span'); t.textContent = n.title;
      const x = document.createElement('button'); x.textContent = '✕'; x.onclick = () => { chosen.splice(k, 1); drawChosen(); };
      c.append(t, x); chosenBox.appendChild(c);
    });
  };
  let pickTimer = null;
  pickInput.oninput = () => {
    clearTimeout(pickTimer);
    const q = pickInput.value.trim();
    if (!q) { pickList.hidden = true; return; }
    pickTimer = setTimeout(async () => {
      let found = [];
      try { found = await ctx.call('searchNotes', { query: q, limit: 12, brief: 1 }); } catch { found = []; }
      pickList.innerHTML = '';
      for (const n of found) {
        if (chosen.some((c) => c.id === n.id)) continue;
        const b = document.createElement('button');
        b.textContent = ctx.titleOf(n);
        b.onclick = () => { chosen.push({ id: n.id, title: ctx.titleOf(n) }); drawChosen(); pickInput.value = ''; pickList.hidden = true; };
        pickList.appendChild(b);
      }
      pickList.hidden = !pickList.childNodes.length;
    }, 150);
  };
  pickInput.onkeydown = (e) => { if (e.key === 'Escape') pickList.hidden = true; };
  const pickWrap = document.createElement('div');
  pickWrap.className = 'mem-pick-wrap';
  pickWrap.append(pickInput, pickList);
  pickRow.append(pickLabel, pickWrap);

  learn.onclick = async () => {
    learn.disabled = true; learn.textContent = '学んでいます…';
    try {
      await ctx.memoryLearn({ mode: modeSel.value, noteIds: chosen.length ? chosen.map((c) => c.id) : null });
      await showMem(); await drawBackups();
      ctx.toast(modeSel.value === 'append' ? '記憶に追記しました' : '書き方を記憶しました');
    } catch (e) { ctx.toast('学べません: ' + (e.message || e)); }
    learn.disabled = false; learn.textContent = 'カードから学ぶ';
  };

  // 控え (自動バックアップ) と復元
  const bakHead = document.createElement('div');
  bakHead.className = 'hint';
  bakHead.style.marginTop = '10px';
  bakHead.textContent = '控え (書き換える前に自動で取ります。最新 10 件)';
  const bakList = document.createElement('div');
  bakList.className = 'mem-backups';
  const drawBackups = async () => {
    const list = ctx.memoryBackups ? await ctx.memoryBackups() : [];
    bakList.innerHTML = '';
    if (!list.length) { bakList.innerHTML = '<div class="hint">まだありません</div>'; return; }
    list.forEach((b, k) => {
      const row2 = document.createElement('div');
      row2.className = 'mem-backup';
      const when = document.createElement('span');
      when.textContent = new Date(b.savedAt).toLocaleString('ja-JP') + (b.reason ? ` · ${b.reason}` : '') + ` · ${b.text.length} 文字`;
      const restore = document.createElement('button');
      restore.className = 'pill ghost';
      restore.textContent = '復元';
      restore.onclick = async () => {
        if (!window.confirm('この控えの内容に戻します。いまのメモは控えに残ります。よろしいですか？')) return;
        try { await ctx.memoryRestore(k); await showMem(); await drawBackups(); ctx.toast('復元しました'); }
        catch (e) { ctx.toast('復元できません: ' + (e.message || e)); }
      };
      row2.append(when, restore);
      bakList.appendChild(row2);
    });
  };
  memSave.onclick = async () => {
    if (ctx.memoryBackup) await ctx.memoryBackup('手で直す前');
    const m = (ctx.memoryGet ? await ctx.memoryGet() : null) || {};
    await window.anki.settingsSet('aiMemory', { ...m, text: memBox.value, builtAt: Date.now() });
    if (ctx.refreshMemory) ctx.refreshMemory();
    await showMem();
    ctx.toast('メモを保存しました');
  };
  await showMem();
  await drawBackups();

  const addRow = document.createElement('div');
  addRow.className = 'ai-add';
  const kind = document.createElement('select');
  kind.innerHTML = Object.entries(AI_KIND_INFO).map(([k, v]) => `<option value="${k}">${v.label}</option>`).join('');
  const add = document.createElement('button');
  add.className = 'pill';
  add.textContent = '＋ 追加';
  addRow.append(kind, add);
  detail.appendChild(addRow);

  async function draw() {
    const { providers, active } = await window.anki.aiList();
    list.innerHTML = '';
    if (!providers.length) {
      list.innerHTML = '<div class="hint">まだありません。下で種類を選んで追加してください。</div>';
    }
    for (const pv of providers) {
      const info = AI_KIND_INFO[pv.kind] || { label: pv.kind, hint: '', models: [] };
      const card = document.createElement('div');
      card.className = 'ai-card' + (active && active.id === pv.id ? ' active' : '');

      const head = document.createElement('div');
      head.className = 'ai-card-head';
      const title = document.createElement('b');
      title.textContent = info.label;
      const badge = document.createElement('span');
      badge.className = 'ai-badge ' + (pv.ok ? 'ok' : (pv.error ? 'ng' : 'unknown'));
      badge.textContent = pv.ok ? '✓ 接続済み' : (pv.error ? '✗ つながらない' : '未確認');
      badge.title = pv.error || '';
      const using = document.createElement('span');
      using.className = 'ai-using';
      using.textContent = active && active.id === pv.id ? '使用中' : '';
      head.append(title, badge, using);

      const keyRow = document.createElement('div');
      keyRow.className = 'ai-row';
      const key = document.createElement('input');
      key.type = 'password';
      key.placeholder = pv.keyHint ? `保存済み (${pv.keyHint}) — 変えるときだけ入力` : info.hint;
      key.autocomplete = 'off';
      // モデルはプルダウンで選ぶ。一覧に無いものは「その他」で直接入力
      const model = document.createElement('select');
      const other = document.createElement('input');
      other.placeholder = 'モデル ID を直接入力';
      other.hidden = true;
      let extras = (pv.extraModels || []).slice();
      const fillModels = () => {
        const shown = visibleModels(pv.kind, pv.models, extras, pv.model);
        model.innerHTML = shown.map(([id, label]) => `<option value="${id}">${label}</option>`).join('') +
          '<option value="__other">その他 (ID を直接入力)…</option>';
        if (pv.model && shown.some(([id]) => id === pv.model)) model.value = pv.model;
        else if (pv.model) { model.value = '__other'; other.hidden = false; other.value = pv.model; }
      };
      fillModels();
      model.onchange = () => {
        other.hidden = model.value !== '__other';
        if (!other.hidden) other.focus();
      };
      const chosenModel = () => (model.value === '__other' ? other.value.trim() : model.value);
      // 追加のモデル: 取得済みの一覧から、主要なもの以外を個別に足す
      const moreWrap = document.createElement('details');
      moreWrap.className = 'ai-more';
      const moreSum = document.createElement('summary');
      const moreList = document.createElement('div');
      moreList.className = 'ai-more-list';
      const drawMore = () => {
        const all = (pv.models && pv.models.length ? pv.models : (BUILTIN_MODELS[pv.kind] || []))
          .filter((id) => !/codex/.test(id))
          .sort((a, b) => rankModel(b) - rankModel(a));
        const shownIds = new Set(visibleModels(pv.kind, pv.models, [], pv.model).map(([id]) => id));
        const rest = all.filter((id) => !shownIds.has(id));
        moreSum.textContent = `その他のモデルを追加 (${rest.length} 件)` + (pv.modelsFetchedAt ? ` · 一覧の更新: ${new Date(pv.modelsFetchedAt).toLocaleDateString('ja-JP')}` : '');
        moreList.innerHTML = '';
        for (const id of rest) {
          const lab = document.createElement('label');
          lab.className = 'ai-more-item';
          const cb = document.createElement('input');
          cb.type = 'checkbox';
          cb.checked = extras.includes(id);
          cb.onchange = async () => {
            extras = cb.checked ? [...new Set([...extras, id])] : extras.filter((x) => x !== id);
            await window.anki.aiSave({ id: pv.id, modelsOnly: true, extraModels: extras });
            fillModels();
            if (ctx.refreshAi) ctx.refreshAi();
          };
          const t = document.createElement('span');
          t.textContent = prettyModel(id);
          const code = document.createElement('code');
          code.textContent = id;
          lab.append(cb, t, code);
          moreList.appendChild(lab);
        }
      };
      drawMore();
      const fetchModels = document.createElement('button');
      fetchModels.className = 'pill ghost';
      fetchModels.textContent = '一覧を今すぐ更新';
      fetchModels.title = '普段は 1 日 1 回、自動で取り直します';
      fetchModels.onclick = async () => {
        fetchModels.disabled = true;
        try {
          if (key.value.trim()) await window.anki.aiSave({ id: pv.id, kind: pv.kind, key: key.value.trim(), model: chosenModel() });
          const names = await window.anki.aiModels(pv.id);
          await window.anki.aiSave({ id: pv.id, modelsOnly: true, models: names });
          pv.models = names; pv.modelsFetchedAt = Date.now();
          fillModels(); drawMore();
          if (ctx.refreshAi) ctx.refreshAi();
          ctx.toast(`${names.length} 件のモデルを読み込みました`);
        } catch (e) { ctx.toast('取得できません: ' + (e.message || e)); }
        fetchModels.disabled = false;
      };
      moreWrap.append(moreSum, moreList, fetchModels);
      keyRow.append(key, model, other);

      const act = document.createElement('div');
      act.className = 'ai-row act';
      const test = document.createElement('button');
      test.className = 'pill';
      test.textContent = '確認';
      test.onclick = async () => {
        test.disabled = true; test.textContent = '確認中…';
        await window.anki.aiSave({ id: pv.id, kind: pv.kind, key: key.value.trim(), model: chosenModel() });
        const r = await window.anki.aiTest(pv.id);
        ctx.toast(r.ok ? `${info.label} につながりました (${r.model})` : `つながりません: ${r.error}`);
        if (ctx.refreshAi) ctx.refreshAi();
        draw();
      };
      const use = document.createElement('button');
      use.className = 'pill ghost';
      use.textContent = '使う';
      use.disabled = !pv.ok || (active && active.id === pv.id);
      use.onclick = async () => { await window.anki.aiUse(pv.id); if (ctx.refreshAi) ctx.refreshAi(); draw(); };
      const remove = document.createElement('button');
      remove.className = 'pill danger';
      remove.textContent = '削除';
      remove.onclick = async () => { await window.anki.aiRemove(pv.id); if (ctx.refreshAi) ctx.refreshAi(); draw(); };
      const when = document.createElement('span');
      when.className = 'hint';
      when.textContent = pv.checkedAt ? '確認: ' + new Date(pv.checkedAt * 1000).toLocaleString('ja-JP') : '';
      act.append(test, use, remove, when);

      card.append(head, keyRow, moreWrap, act);
      list.appendChild(card);
    }
  }

  detail.append(memHead, memHint, memBox, modeRow, pickRow, chosenBox, memRow, bakHead, bakList);

  add.onclick = async () => {
    const id = 'p' + Date.now().toString(36);
    await window.anki.aiSave({ id, kind: kind.value, key: '', model: (BUILTIN_MODELS[kind.value] || [''])[0] });
    draw();
  };
  await draw();
}

function appearancePane(detail, ctx) {
  const minBox = row(detail, 'プレビューの最小幅（px）');
  const min = document.createElement('input');
  min.type = 'number';
  min.value = prefs.getItem('previewMinWidth') || '300';
  min.onchange = () => { prefs.setItem('previewMinWidth', min.value); ctx.applyWidths(); };
  minBox.appendChild(min);

  const cssBox = row(detail,
    '自分で書いた CSS を、アプリの見た目に上書きで反映できます（保存すると自動で反映）');
  const cssLine = document.createElement('div');
  cssLine.className = 'pick-line';
  const openCss = document.createElement('button');
  openCss.className = 'pill ghost';
  openCss.textContent = 'CSS のファイルを開く';
  openCss.onclick = () => window.anki.cssReveal();
  const cssPath = document.createElement('span');
  cssPath.className = 'pick-path';
  window.anki.cssPath().then((p2) => { cssPath.textContent = p2; });
  const inspect = document.createElement('button');
  inspect.className = 'pill ghost';
  inspect.textContent = '要素を調べる（⌥⌘I）';
  inspect.onclick = () => window.anki.openDevTools();
  cssLine.append(openCss, inspect, cssPath);
  cssBox.appendChild(cssLine);

  const zoomBox = row(detail, 'カードの文字サイズ (%)');
  const zoom = document.createElement('input');
  zoom.type = 'number';
  zoom.value = prefs.getItem('textZoom') || '100';
  zoom.onchange = () => { prefs.setItem('textZoom', zoom.value); ctx.redrawCard(); };
  zoomBox.appendChild(zoom);


  const widthBox = row(detail, 'プレビューの既定の幅（アプリを開いたときの幅・dp）');
  const width = document.createElement('input');
  width.type = 'number';
  width.value = prefs.getItem('previewWidth') || '600';
  width.onchange = () => {
    prefs.setItem('previewWidth', width.value);
    ctx.applyWidths({ useDefault: true });   // その場で反映する
  };
  widthBox.appendChild(width);
}

async function trashPane(detail, ctx) {
  const box = row(detail, '削除したカードは直近 200 枚まで控えてあります。戻すと新しいカードとして作られます');
  const list = await window.anki.trashAll();
  if (!list.length) { box.textContent = '削除したカードはありません'; return; }

  for (const item of list) {
    const line = document.createElement('div');
    line.className = 'trash-line';
    const when = new Date(item.deletedAt * 1000)
      .toLocaleString('ja-JP', { dateStyle: 'short', timeStyle: 'short' });
    const meta = document.createElement('span');
    meta.className = 'when';
    meta.textContent = when;
    const what = document.createElement('span');
    what.className = 'what';
    what.textContent = item.title || `ノート ${item.id}`;
    const back = document.createElement('button');
    back.className = 'pill ghost';
    back.textContent = '戻す';
    back.onclick = async () => {
      try {
        const defaults = await ctx.call('addingDefaults', {});
        await ctx.call('addNote', {
          notetypeId: item.modelId || defaults.notetypeId,
          deckId: defaults.deckId,
          fields: item.fields,
          tags: item.tags || '',
        });
        const rest = (await window.anki.trashAll()).filter((t) => t.deletedAt !== item.deletedAt);
        await window.anki.trashSave(rest);
        ctx.toast('戻しました');
        ctx.research();
        trashPane(detail, ctx);
      } catch (e) {
        ctx.toast('戻せません: ' + e.message);
      }
    };
    line.append(meta, what, back);
    box.appendChild(line);
  }

  const clear = document.createElement('button');
  clear.className = 'pill ghost';
  clear.textContent = '控えをすべて消す';
  clear.style.marginTop = '12px';
  clear.onclick = async () => {
    await window.anki.trashSave([]);
    ctx.toast('控えを消しました');
    trashPane(detail, ctx);
  };
  detail.appendChild(clear);
}

async function backupPane(detail, ctx) {
  const dir = prefs.getItem('backupDir');

  // ---- 保存先 ----
  const dirBox = row(detail,
    'コレクションの控えを、選んだフォルダに書き出します。' +
    'iCloud Drive や Dropbox のフォルダを選べば、そのままクラウドに置けます');
  const dirLine = document.createElement('div');
  dirLine.className = 'pick-line';
  const pick = document.createElement('button');
  pick.className = 'pill ghost';
  pick.textContent = '保存先を選ぶ';
  pick.onclick = async () => {
    const chosen = await window.anki.backupPick();
    if (chosen) { prefs.setItem('backupDir', chosen); ctx.reload(); }
  };
  const path = document.createElement('span');
  path.className = 'pick-path';
  path.textContent = dir || '未設定';
  dirLine.append(pick, path);
  dirBox.appendChild(dirLine);

  // ---- 保存先が決まるまで、以下は触れない ----
  const rest = document.createElement('div');
  rest.className = 'backup-rest' + (dir ? '' : ' locked');
  detail.appendChild(rest);

  const autoBox = row(rest, '');
  const label = document.createElement('label');
  label.className = 'check-line';
  const check = document.createElement('input');
  check.type = 'checkbox';
  check.checked = prefs.getItem('backupAuto') === '1';
  const text = document.createElement('span');
  text.textContent = '自動でバックアップを取る';
  label.append(check, text);
  autoBox.appendChild(label);

  const everyBox = row(rest, '間隔');
  const every = document.createElement('select');
  every.innerHTML = [
    ['1h', '1 時間ごと'], ['3h', '3 時間ごと'], ['6h', '6 時間ごと'],
    ['12h', '12 時間ごと'], ['1d', '1 日ごと'], ['3d', '3 日ごと'], ['1w', '1 週間ごと'],
  ].map(([v, t]) => `<option value="${v}">${t}</option>`).join('');
  every.value = prefs.getItem('backupEvery') || '1d';
  every.disabled = !check.checked;
  every.onchange = () => prefs.setItem('backupEvery', every.value);
  everyBox.appendChild(every);

  check.onchange = () => {
    prefs.setItem('backupAuto', check.checked ? '1' : '0');
    every.disabled = !check.checked;
  };


  const keepBox = row(rest, '残し方');
  const mode = document.createElement('select');
  mode.innerHTML =
    '<option value="1">毎回置き換える（1 本だけ残す）</option>' +
    '<option value="many">直近の何本かを残す</option>';
  const count = document.createElement('input');
  count.type = 'number';
  count.min = '2';
  count.style.marginLeft = '10px';
  count.style.minWidth = '90px';
  const saved = Number(prefs.getItem('backupKeep') || 1);
  mode.value = saved > 1 ? 'many' : '1';
  count.value = saved > 1 ? String(saved) : '5';
  count.hidden = mode.value !== 'many';
  mode.onchange = () => {
    count.hidden = mode.value !== 'many';
    prefs.setItem('backupKeep', mode.value === 'many' ? count.value : '1');
  };
  count.onchange = () => {
    const n = Math.max(2, Number(count.value) || 2);
    count.value = String(n);
    prefs.setItem('backupKeep', String(n));
  };
  const keepLine = document.createElement('div');
  keepLine.className = 'pick-line';
  keepLine.append(mode, count);
  keepBox.appendChild(keepLine);

  const nowBox = row(rest, '');
  const now = document.createElement('button');
  now.className = 'pill';
  now.textContent = '手動バックアップ';
  now.onclick = async () => {
    now.textContent = '書き出しています…';
    try {
      await ctx.backupNow(prefs.getItem('backupDir'));
      ctx.toast('控えを取りました');
      ctx.reload();
    } catch (e) {
      ctx.toast('取れません: ' + e.message);
      now.textContent = '手動バックアップ';
    }
  };
  nowBox.appendChild(now);

  // ---- いまある控え ----
  if (!dir) return;
  const listBox = row(rest, 'いまあるバックアップ');
  const files = await window.anki.backupList(dir);
  if (!files.length) { listBox.appendChild(document.createTextNode('まだありません')); return; }
  for (const f of files) {
    const line = document.createElement('div');
    line.className = 'trash-line';
    const open = document.createElement('button');
    open.className = 'pill ghost';
    open.textContent = 'ここから戻す';
    open.onclick = () => restorePane(detail, ctx, `${dir}/${f.name}`);
    const when = document.createElement('span');
    when.className = 'when';
    when.textContent = (f.size / 1048576).toFixed(1) + ' MB';
    const name = document.createElement('span');
    name.className = 'what';
    // anki_backup_20260828_203105 → 2026/08/28 20:31:05
    const m = /anki_backup_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})/.exec(f.name);
    name.textContent = m
      ? `${f.name}（${m[1]}/${m[2]}/${m[3]} ${m[4]}:${m[5]}:${m[6]}）`
      : f.name;
    line.append(when, name, open);
    listBox.appendChild(line);
  }
}

/** 控えの中身を一覧で見て、選んだカードだけを戻す */
async function restorePane(detail, ctx, file) {
  detail.innerHTML = '';
  const head = document.createElement('h3');
  head.textContent = 'この控えから戻す';
  detail.appendChild(head);

  const where = row(detail, file.split('/').pop());
  const search = document.createElement('input');
  search.placeholder = 'カードを探す（見出しの一部）';
  where.appendChild(search);

  const list = document.createElement('div');
  list.className = 'restore-list';
  detail.appendChild(list);

  const back = document.createElement('button');
  back.className = 'pill ghost';
  back.textContent = '控えの一覧へ戻る';
  back.style.marginTop = '12px';
  back.onclick = () => ctx.reload();
  detail.appendChild(back);

  async function draw() {
    list.textContent = '読み込んでいます…';
    let notes = [];
    try {
      notes = await ctx.call('backupNotes', {
        path: file, query: search.value.trim(), limit: 200,
      });
    } catch (e) {
      list.textContent = '読めません: ' + e.message;
      return;
    }
    list.innerHTML = '';
    if (!notes.length) { list.textContent = '見つかりません'; return; }
    for (const note of notes.slice(0, 100)) {
      const line = document.createElement('div');
      line.className = 'trash-line';
      const size = document.createElement('span');
      size.className = 'when';
      size.textContent = note.sizes.reduce((a, b) => a + b, 0) + ' 文字';
      const what = document.createElement('span');
      what.className = 'what';
      what.textContent = ctx.titleOf(note);
      const put = document.createElement('button');
      put.className = 'pill';
      put.textContent = '戻す';
      put.onclick = async () => {
        put.textContent = '戻しています…';
        try {
          const done = await ctx.call('restoreNote', { path: file, id: note.id });
          ctx.toast(`戻しました（${done.before.reduce((a, b) => a + b, 0)} → ` +
            `${done.after.reduce((a, b) => a + b, 0)} 文字）`);
          ctx.research();
        } catch (e) {
          ctx.toast('戻せません: ' + e.message);
        }
        put.textContent = '戻す';
      };
      line.append(size, what, put);
      list.appendChild(line);
    }
  }

  let timer = null;
  search.oninput = () => { clearTimeout(timer); timer = setTimeout(draw, 250); };
  await draw();
}

async function mediaPane(detail, ctx) {
  const hint = document.createElement('p');
  hint.className = 'hint';
  hint.textContent = 'どのカードからも使われていない画像・音声を見つけて片づけます。削除したものは Anki のメディアのごみ箱へ移り、Anki 本体から戻せます。';
  detail.appendChild(hint);

  const top = document.createElement('div');
  top.className = 'media-top';
  const scan = document.createElement('button');
  scan.className = 'pill';
  scan.textContent = '調べる';
  const stat = document.createElement('div');
  stat.className = 'media-stat';
  top.append(scan, stat);
  detail.appendChild(top);

  const tools = document.createElement('div');
  tools.className = 'media-tools';
  tools.hidden = true;
  tools.innerHTML = `
    <input id="mediaFilter" placeholder="名前で絞り込む">
    <select id="mediaSort">
      <option value="size">大きい順</option>
      <option value="name">名前順</option>
    </select>
    <button class="pill ghost" id="mediaAll">すべて選ぶ</button>
    <button class="pill ghost" id="mediaNone">選択を外す</button>
    <span class="grow"></span>
    <button class="pill danger" id="mediaDelete" disabled>削除</button>`;
  detail.appendChild(tools);

  const grid = document.createElement('div');
  grid.className = 'media-grid';
  detail.appendChild(grid);

  const missingBox = document.createElement('div');
  missingBox.className = 'media-missing';
  missingBox.hidden = true;
  detail.appendChild(missingBox);

  let items = [];
  const picked = new Set();
  const mb = (n) => (n / 1048576).toFixed(n >= 10485760 ? 0 : 1) + ' MB';
  const kb = (n) => (n >= 1048576 ? mb(n) : Math.max(1, Math.round(n / 1024)) + ' KB');
  const isImage = (name) => /\.(png|jpe?g|gif|webp|avif|svg|bmp)$/i.test(name);
  const isSound = (name) => /\.(mp3|m4a|wav|ogg|flac|aac)$/i.test(name);

  function updateDelete() {
    const size = items.filter((x) => picked.has(x.name)).reduce((a, b) => a + b.sizeBytes, 0);
    const del = tools.querySelector('#mediaDelete');
    del.disabled = !picked.size;
    del.textContent = picked.size ? `${picked.size} 件を削除 (${kb(size)})` : '削除';
  }

  function draw() {
    const needle = tools.querySelector('#mediaFilter').value.trim().toLowerCase();
    const order = tools.querySelector('#mediaSort').value;
    const shown = items
      .filter((x) => !needle || x.name.toLowerCase().includes(needle))
      .sort((a, b) => (order === 'size' ? b.sizeBytes - a.sizeBytes : a.name.localeCompare(b.name)));
    grid.innerHTML = '';
    for (const item of shown) {
      const card = document.createElement('label');
      card.className = 'media-card' + (picked.has(item.name) ? ' on' : '');
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.checked = picked.has(item.name);
      check.onchange = () => {
        if (check.checked) picked.add(item.name); else picked.delete(item.name);
        card.classList.toggle('on', check.checked);
        updateDelete();
      };
      const thumb = document.createElement('div');
      thumb.className = 'media-thumb';
      if (isImage(item.name)) {
        const img = document.createElement('img');
        img.loading = 'lazy';
        img.src = 'media://media/' + encodeURIComponent(item.name);
        img.onerror = () => { thumb.textContent = '画像'; };
        thumb.appendChild(img);
      } else {
        thumb.textContent = isSound(item.name) ? '♪' : (item.name.split('.').pop() || '?').toUpperCase();
      }
      const name = document.createElement('div');
      name.className = 'media-name';
      name.textContent = item.name;
      name.title = item.name;
      const size = document.createElement('div');
      size.className = 'media-size';
      size.textContent = kb(item.sizeBytes);
      card.append(check, thumb, name, size);
      grid.appendChild(card);
    }
    if (!shown.length) grid.innerHTML = '<div class="hint">該当なし</div>';
  }

  scan.onclick = async () => {
    scan.disabled = true;
    scan.textContent = '調べています…';
    try {
      const report = await ctx.call('mediaReport', {});
      items = report.unused;
      picked.clear();
      const unusedSize = items.reduce((a, b) => a + b.sizeBytes, 0);
      stat.innerHTML =
        `<b>${report.total}</b> ファイル (${mb(report.totalBytes)}) のうち ` +
        `<b>${items.length}</b> 件 (${mb(unusedSize)}) が未使用`;
      tools.hidden = !items.length;
      draw();
      updateDelete();
      missingBox.hidden = !report.missing.length;
      missingBox.innerHTML = report.missing.length
        ? `<div class="hint">カードから参照されているのに見つからないファイル ${report.missing.length} 件</div>` +
          report.missing.slice(0, 50).map((n) => `<code>${n}</code>`).join('')
        : '';
      if (!items.length) grid.innerHTML = '<div class="hint">未使用のファイルはありません</div>';
    } catch (e) {
      ctx.toast('調べられませんでした: ' + (e.message || e));
    }
    scan.disabled = false;
    scan.textContent = 'もう一度調べる';
  };

  tools.querySelector('#mediaFilter').oninput = draw;
  tools.querySelector('#mediaSort').onchange = draw;
  tools.querySelector('#mediaAll').onclick = () => { items.forEach((x) => picked.add(x.name)); draw(); updateDelete(); };
  tools.querySelector('#mediaNone').onclick = () => { picked.clear(); draw(); updateDelete(); };
  tools.querySelector('#mediaDelete').onclick = async () => {
    const names = [...picked];
    if (!names.length) return;
    if (!window.confirm(`${names.length} 件をメディアのごみ箱へ移します。よろしいですか？`)) return;
    await ctx.call('deleteMedia', { names });
    items = items.filter((x) => !picked.has(x.name));
    picked.clear();
    ctx.toast(`${names.length} 件を片づけました`);
    draw();
    updateDelete();
    if (!items.length) { tools.hidden = true; grid.innerHTML = '<div class="hint">未使用のファイルはありません</div>'; }
  };
}
