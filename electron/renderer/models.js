// AI のモデル名まわり。表示名・並び順・「主要なモデル」の選び方をここにまとめる。

/** 表示名: claude-opus-5-5 → "Opus 5.5", gpt-6.1-sol → "GPT-6.1 Sol", gpt-5.5-pro → "GPT-5.5 Pro" */
export function prettyModel(id) {
  let m = /^claude-(opus|sonnet|haiku|fable)-(\d+)(?:-(\d+))?/.exec(id);
  if (m) {
    const name = m[1][0].toUpperCase() + m[1].slice(1);
    return `${name} ${m[2]}${m[3] && m[3].length < 3 ? '.' + m[3] : ''}` + (/\d{8}$/.test(id) ? ' (' + id.slice(-8) + ')' : '');
  }
  m = /^gpt-(\d+(?:\.\d+)?)(?:-([a-z]+(?:-[a-z]+)*))?(?:-(\d{4}-\d{2}-\d{2}))?$/.exec(id);
  if (m) {
    const tag = m[2] || '';
    const nick = { sol: 'Sol', astra: 'Astra', luna: 'Luna', terra: 'Terra', pro: 'Pro', mini: 'mini', nano: 'nano', 'chat-latest': '(chat)' }[tag];
    return `GPT-${m[1]}${tag ? ' ' + (nick || tag) : ''}` + (m[3] ? ` (${m[3]})` : '');
  }
  m = /^gemini-(\d+(?:\.\d+)?)-([a-z]+)/.exec(id);
  if (m) return `Gemini ${m[1]} ${m[2][0].toUpperCase() + m[2].slice(1)}`;
  return id;
}

/** 系統: Claude は Opus/Sonnet/Haiku/Fable、ChatGPT は Astra/Sol/Luna/Terra/Pro/無印/mini/nano */
export function familyOf(id) {
  let m = /^claude-(opus|sonnet|haiku|fable)/.exec(id);
  if (m) return m[1];
  m = /^gpt-\d+(?:\.\d+)?(?:-([a-z]+))?/.exec(id);
  if (m) return m[1] || 'base';
  m = /^gemini-\d+(?:\.\d+)?-([a-z]+)/.exec(id);
  if (m) return m[1];
  return id;
}

/** 版 (大きいほど新しい) */
export function versionOf(id) {
  let m = /^claude-[a-z]+-(\d+)(?:-(\d+))?/.exec(id);
  if (m) return Number(m[1]) + (m[2] && m[2].length < 3 ? Number(m[2]) / 10 : 0);
  m = /^(?:gpt|gemini)-(\d+(?:\.\d+)?)/.exec(id);
  if (m) return Number(m[1]);
  return 0;
}

/** 新しい・高性能な順。日付付き・codex などは後ろ */
export function rankModel(id) {
  const fam = familyOf(id);
  const tier = { opus: 5, astra: 5, pro: 5, fable: 4, sol: 4, sonnet: 3, base: 3, luna: 3, terra: 3, 'chat-latest': 2, mini: 1, haiku: 0, nano: 0, flash: 1 }[fam];
  const dated = /\d{8}$|\d{4}-\d{2}-\d{2}$/.test(id) ? 1 : 0;
  return versionOf(id) * 100 + (tier === undefined ? 2 : tier) - dated * 0.5;
}

/** 主要なモデル: 系統ごとに最新の 1 つ (日付付きは除く)。Claude は 4 系統、ChatGPT は愛称の 3 系統 */
export function primaryModels(kind, ids) {
  const want = kind === 'anthropic'
    ? ['fable', 'opus', 'sonnet', 'haiku']
    : kind === 'openai' ? ['astra', 'sol', 'luna'] : null;
  const pool = ids.filter((id) => !/codex|chat-latest/.test(id));   // 日付付きは並びで後ろに回る (無印があればそちら)
  if (!want) return pool.sort((a, b) => rankModel(b) - rankModel(a)).slice(0, 4);
  const out = [];
  for (const fam of want) {
    const best = pool.filter((id) => familyOf(id) === fam).sort((a, b) => rankModel(b) - rankModel(a))[0];
    if (best) out.push(best);
  }
  // 愛称付きが 1 つも無い (古い一覧) ときは、上位から 3 つ
  if (!out.length) return pool.sort((a, b) => rankModel(b) - rankModel(a)).slice(0, 3);
  return out;
}

/** 既定の一覧 (まだ取得していないとき用) */
export const BUILTIN_MODELS = {
  anthropic: ['claude-fable-5-1', 'claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-4-5-20251001'],
  openai: ['gpt-6-astra', 'gpt-6.1-sol', 'gpt-6-luna', 'gpt-5.5-pro', 'gpt-5.5'],
  gemini: ['gemini-2.5-pro', 'gemini-2.5-flash'],
};

/**
 * プルダウンに出すモデル: 主要なもの + 設定で追加したもの + いま選んでいるもの。
 * ids は取得済みの一覧 (無ければ既定)、extras は設定で追加した id
 */
export function visibleModels(kind, ids, extras, current) {
  const all = ids && ids.length ? ids : (BUILTIN_MODELS[kind] || []);
  const set = new Set(primaryModels(kind, all));
  for (const id of extras || []) set.add(id);
  if (current) set.add(current);
  return [...set].sort((a, b) => rankModel(b) - rankModel(a)).map((id) => [id, prettyModel(id)]);
}
