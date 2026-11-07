// 2 つの文字列の違いを、語のまとまりで求める。
// 行ごとにまず比べ、変わった行のかたまりだけを語ごとに比べる (長い本文でも重くならない)。

function tokens(text) {
  // タグ / 語 / 空白 / 記号 に分ける
  return text.match(/<[^>]*>|[A-Za-z0-9_]+|[ぁ-んァ-ヶ一-龠々ー]+|\s+|[^\sA-Za-z0-9_ぁ-んァ-ヶ一-龠々ー<]/g) || [];
}

/** 最長共通部分列で 2 つの列を並べ、[種別, 文字列] の列にする。種別: '=' '+' '-' */
function align(a, b) {
  const n = a.length;
  const m = b.length;
  if (n * m > 6_000_000) {                        // 大きすぎるときは丸ごと入れ替え扱い
    const out = [];
    if (n) out.push(['-', a.join('')]);
    if (m) out.push(['+', b.join('')]);
    return out;
  }
  // DP 表 (行ごとに Uint32Array)
  const dp = new Array(n + 1);
  for (let i = 0; i <= n; i++) dp[i] = new Uint32Array(m + 1);
  for (let i = n - 1; i >= 0; i--) {
    const row = dp[i];
    const next = dp[i + 1];
    for (let j = m - 1; j >= 0; j--) {
      row[j] = a[i] === b[j] ? next[j + 1] + 1 : Math.max(next[j], row[j + 1]);
    }
  }
  const out = [];
  const push = (kind, text) => {
    if (!text) return;
    const last = out[out.length - 1];
    if (last && last[0] === kind) last[1] += text; else out.push([kind, text]);
  };
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) { push('=', a[i]); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { push('-', a[i]); i++; }
    else { push('+', b[j]); j++; }
  }
  while (i < n) push('-', a[i++]);
  while (j < m) push('+', b[j++]);
  return out;
}

/** 前後の文字列の違い。[種別, 文字列] の列 */
export function diffText(before, after) {
  if (before === after) return [['=', before]];
  const la = before.split('\n');
  const lb = after.split('\n');
  const lines = align(la.map((l) => l + '\n'), lb.map((l) => l + '\n'));
  // 行ごとの結果のうち、消えた行と増えた行が隣り合うところは語ごとに見直す
  const out = [];
  for (let k = 0; k < lines.length; k++) {
    const [kind, text] = lines[k];
    if (kind === '-' && lines[k + 1] && lines[k + 1][0] === '+') {
      const fine = align(tokens(text), tokens(lines[k + 1][1]));
      out.push(...fine);
      k++;
      continue;
    }
    out.push([kind, text]);
  }
  // 末尾に足した改行を戻す
  const fix = (s) => s;
  const merged = [];
  for (const [kind, text] of out) {
    const last = merged[merged.length - 1];
    if (last && last[0] === kind) last[1] += text; else merged.push([kind, fix(text)]);
  }
  // 最後の要素の余分な改行を落とす
  let total = merged.map(([k, t]) => (k === '+' ? '' : t)).join('');
  if (total.endsWith('\n') && !before.endsWith('\n')) {
    for (let k = merged.length - 1; k >= 0; k--) {
      if (merged[k][0] !== '+' && merged[k][1].endsWith('\n')) { merged[k][1] = merged[k][1].slice(0, -1); break; }
    }
  }
  total = merged.map(([k, t]) => (k === '-' ? '' : t)).join('');
  if (total.endsWith('\n') && !after.endsWith('\n')) {
    for (let k = merged.length - 1; k >= 0; k--) {
      if (merged[k][0] !== '-' && merged[k][1].endsWith('\n')) { merged[k][1] = merged[k][1].slice(0, -1); break; }
    }
  }
  return merged.filter(([, t]) => t.length);
}
