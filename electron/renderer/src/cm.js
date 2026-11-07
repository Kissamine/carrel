// CodeMirror から使うものだけを取り出して、1 つのファイルにまとめる入口。
// (画面側は file:// で動くので、そのままでは npm の読み込み方が使えない)

export { EditorState, Compartment } from '@codemirror/state';
export {
  EditorView, keymap, lineNumbers, highlightActiveLine,
  drawSelection, rectangularSelection, crosshairCursor, placeholder,
} from '@codemirror/view';
export {
  defaultKeymap, history, historyKeymap, indentWithTab,
  indentMore, indentLess, insertNewlineAndIndent, insertBlankLine,
  moveLineUp, moveLineDown, copyLineUp, copyLineDown,
  deleteLine, selectLine, deleteToLineStart, undo, redo,
  toggleComment,
} from '@codemirror/commands';
export { html } from '@codemirror/lang-html';
export { syntaxHighlighting, HighlightStyle, bracketMatching, indentUnit } from '@codemirror/language';
export { tags } from '@lezer/highlight';
export { searchKeymap } from '@codemirror/search';
