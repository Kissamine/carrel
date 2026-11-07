// テキスト (リッチ) 編集。contenteditable なので日本語入力もそのまま使える。
// HTML 編集と同じ中身を扱い、モードを切り替えても内容は共有する。

export function createRichEditor(root, fieldNames, values, hooks) {
  root.innerHTML = '';
  const boxes = [];

  fieldNames.forEach((name, i) => {
    const label = document.createElement('div');
    label.className = 'field-label';
    label.textContent = name;
    const box = document.createElement('div');
    box.className = 'field rich';
    box.contentEditable = 'true';
    box.spellcheck = false;
    box.dataset.index = String(i);
    box.innerHTML = values[i] || '';
    box.addEventListener('input', () => hooks.onChange(i, box.innerHTML));
    box.addEventListener('focus', () => { box.classList.add('focus'); hooks.onFocus(i); });
    box.addEventListener('blur', () => box.classList.remove('focus'));
    box.addEventListener('keydown', (e) => {
      if (e.isComposing || e.keyCode === 229) return;   // 変換中は触らない
      const cmd = e.metaKey || e.ctrlKey;
      if (cmd && (e.key === 'z' || e.key === 'Z' || e.key === 'y' || e.key === 'Y')) return;
      if (cmd && (e.key === 's' || e.key === 'S')) {
        e.preventDefault(); hooks.onChange(i, box.innerHTML); hooks.onSave();
      }
    });
    root.appendChild(label);
    root.appendChild(box);
    boxes.push(box);
  });

  return {
    destroy() { root.innerHTML = ''; },
    values: () => boxes.map((b) => b.innerHTML),
    /** 中身だけを入れ替える (見ている位置はそのまま) */
    setValues(fields) {
      const outer = root.scrollTop;
      boxes.forEach((b, i) => {
        const next = fields[i] || '';
        if (b.innerHTML !== next) b.innerHTML = next;
      });
      root.scrollTop = outer;
    },
    flush: () => boxes.forEach((b, i) => hooks.onChange(i, b.innerHTML)),
    /** 書式を付ける (選択範囲に対して) */
    format(command, value) {
      document.execCommand(command, false, value);
      const active = document.activeElement;
      if (active && active.dataset && active.dataset.index !== undefined) {
        hooks.onChange(Number(active.dataset.index), active.innerHTML);
      }
    },
    /** カーソル位置に差し込む */
    insert(html) {
      document.execCommand('insertHTML', false, html);
      const active = document.activeElement;
      if (active && active.dataset && active.dataset.index !== undefined) {
        hooks.onChange(Number(active.dataset.index), active.innerHTML);
      }
    },
    // リッチ側に検索は用意しない (HTML 側で行う)
    search: () => 0,
    move: () => 0,
    index: () => 0,
    clear: () => {},
  };
}
