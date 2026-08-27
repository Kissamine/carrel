package com.haru.ankieditor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * HTML エディタのキー操作。VS Code と同じ手触りにするためのもの。
 *
 * 画面の作りに依存しないよう、押されたキーと今の内容だけを受け取り、
 * 新しい内容を返す (何もしないときは null)。Android と macOS で共有する。
 */
object EditorShortcuts {

    /** 押されたキーの種類。プラットフォームごとの表現をここに寄せる */
    enum class Key {
        ENTER, UP, DOWN, LEFT, RIGHT, BACKSPACE, SLASH, BRACKET_LEFT, BRACKET_RIGHT,
        D, K, X, L, HOME, END,
    }

    /**
     * @param command macOS の ⌘ / Windows・Android の Ctrl
     */
    fun handle(
        key: Key,
        command: Boolean,
        shift: Boolean,
        alt: Boolean,
        value: TextFieldValue,
    ): TextFieldValue? = when {
        // Enter は今の行と同じ字下げを引き継ぐ。Shift+Enter は行頭から始める
        // (VS Code と同じ手触り)
        key == Key.ENTER && !command && !alt && !shift -> insertNewLine(value, keepIndent = true)
        key == Key.ENTER && !command && !alt && shift -> insertNewLine(value, keepIndent = false)

        // ⌘Enter で下に行を足す / ⌘⇧Enter で上に行を足す
        key == Key.ENTER && command && !shift -> lineBelow(value)
        key == Key.ENTER && command && shift -> lineAbove(value)

        // ⌥↑ / ⌥↓ で行を入れ替え、⇧⌥↑ / ⇧⌥↓ で行を複製
        key == Key.UP && alt && shift -> duplicateLine(value, below = false)
        key == Key.DOWN && alt && shift -> duplicateLine(value, below = true)
        key == Key.UP && alt -> moveLine(value, up = true)
        key == Key.DOWN && alt -> moveLine(value, up = false)

        // ⌘⇧K で行を削除
        key == Key.K && command && shift -> deleteLine(value)

        // ⌘/ でコメントの付け外し
        key == Key.SLASH && command -> toggleComment(value)

        // ⌘] / ⌘[ で字下げの増減
        key == Key.BRACKET_RIGHT && command -> indent(value, add = true)
        key == Key.BRACKET_LEFT && command -> indent(value, add = false)

        // ⌘D で今の単語を選ぶ
        key == Key.D && command && !shift -> selectWord(value)

        // ⌘L で行を選ぶ
        key == Key.L && command -> selectLine(value)

        // ⌘⌫ で行頭まで消す
        key == Key.BACKSPACE && command -> deleteToLineStart(value)

        else -> null
    }

    // ------------------------------------------------------------------ 部品

    /** 改行を入れる。keepIndent なら今の行の字下げを引き継ぐ */
    private fun insertNewLine(value: TextFieldValue, keepIndent: Boolean): TextFieldValue {
        val (start, end) = lineBounds(value.text, value.selection.min)
        val indent = if (keepIndent) indentOf(value.text.substring(start, end)) else ""
        return insert(value, "\n$indent")
    }

    private fun insert(value: TextFieldValue, text: String): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        return TextFieldValue(
            text = value.text.substring(0, start) + text + value.text.substring(end),
            selection = TextRange(start + text.length),
        )
    }

    /** 今いる行の範囲 (改行は含まない) */
    private fun lineBounds(text: String, offset: Int): Pair<Int, Int> {
        val at = offset.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let {
            if (it < 0 || at == 0) 0 else it + 1
        }
        val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        return start to end
    }

    /** 行頭の空白 (字下げをそろえるために使う) */
    private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    private fun lineBelow(value: TextFieldValue): TextFieldValue {
        val (_, end) = lineBounds(value.text, value.selection.start)
        val (start, _) = lineBounds(value.text, value.selection.start)
        val indent = indentOf(value.text.substring(start, end))
        val inserted = "\n$indent"
        return TextFieldValue(
            text = value.text.substring(0, end) + inserted + value.text.substring(end),
            selection = TextRange(end + inserted.length),
        )
    }

    private fun lineAbove(value: TextFieldValue): TextFieldValue {
        val (start, end) = lineBounds(value.text, value.selection.start)
        val indent = indentOf(value.text.substring(start, end))
        val inserted = "$indent\n"
        return TextFieldValue(
            text = value.text.substring(0, start) + inserted + value.text.substring(start),
            selection = TextRange(start + indent.length),
        )
    }

    private fun moveLine(value: TextFieldValue, up: Boolean): TextFieldValue? {
        val text = value.text
        val (start, end) = lineBounds(text, value.selection.start)
        val line = text.substring(start, end)
        return if (up) {
            if (start == 0) return null
            val (prevStart, prevEnd) = lineBounds(text, start - 1)
            val prev = text.substring(prevStart, prevEnd)
            val rebuilt = text.substring(0, prevStart) + line + "\n" + prev + text.substring(end)
            TextFieldValue(rebuilt, TextRange(prevStart + (value.selection.start - start)))
        } else {
            if (end >= text.length) return null
            val (nextStart, nextEnd) = lineBounds(text, end + 1)
            val next = text.substring(nextStart, nextEnd)
            val rebuilt = text.substring(0, start) + next + "\n" + line + text.substring(nextEnd)
            val moved = start + next.length + 1 + (value.selection.start - start)
            TextFieldValue(rebuilt, TextRange(moved))
        }
    }

    private fun duplicateLine(value: TextFieldValue, below: Boolean): TextFieldValue {
        val (start, end) = lineBounds(value.text, value.selection.start)
        val line = value.text.substring(start, end)
        val rebuilt = value.text.substring(0, end) + "\n" + line + value.text.substring(end)
        val caret = if (below) end + 1 + (value.selection.start - start) else value.selection.start
        return TextFieldValue(rebuilt, TextRange(caret))
    }

    private fun deleteLine(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val (start, end) = lineBounds(text, value.selection.start)
        val cutEnd = if (end < text.length) end + 1 else end
        return TextFieldValue(
            text = text.substring(0, start) + text.substring(cutEnd),
            selection = TextRange(start.coerceAtMost(text.length - (cutEnd - start))),
        )
    }

    private fun deleteToLineStart(value: TextFieldValue): TextFieldValue {
        val (start, _) = lineBounds(value.text, value.selection.start)
        val at = value.selection.start
        if (at <= start) return value
        return TextFieldValue(
            text = value.text.substring(0, start) + value.text.substring(at),
            selection = TextRange(start),
        )
    }

    /** 選んでいる行に <!-- --> を付ける / 外す */
    private fun toggleComment(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val (start, _) = lineBounds(text, value.selection.min)
        val (_, end) = lineBounds(text, value.selection.max)
        val block = text.substring(start, end)
        val trimmed = block.trim()
        val updated = if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) {
            block.replaceFirst("<!--", "").replaceFirst(Regex("-->\\s*$"), "").trim()
        } else {
            "<!-- $block -->"
        }
        return TextFieldValue(
            text = text.substring(0, start) + updated + text.substring(end),
            selection = TextRange(start + updated.length),
        )
    }

    /** 選んでいる行すべての字下げを増減する */
    private fun indent(value: TextFieldValue, add: Boolean): TextFieldValue {
        val text = value.text
        val (start, _) = lineBounds(text, value.selection.min)
        val (_, end) = lineBounds(text, value.selection.max)
        val updated = text.substring(start, end).split("\n").joinToString("\n") { line ->
            if (add) "\t$line"
            else line.removePrefix("\t").let { if (it == line) line.removePrefix("    ") else it }
        }
        return TextFieldValue(
            text = text.substring(0, start) + updated + text.substring(end),
            selection = TextRange(start + updated.length),
        )
    }

    private fun selectWord(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val at = value.selection.start.coerceIn(0, text.length)
        fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'
        var from = at
        while (from > 0 && isWord(text[from - 1])) from--
        var to = at
        while (to < text.length && isWord(text[to])) to++
        if (from == to) return value
        return value.copy(selection = TextRange(from, to))
    }

    private fun selectLine(value: TextFieldValue): TextFieldValue {
        val (start, end) = lineBounds(value.text, value.selection.start)
        return value.copy(selection = TextRange(start, end))
    }
}
