package com.haru.ankieditor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType


/**
 * 同じ文字列に対する塗り直しを避けるための 1 件だけのキャッシュ。
 * VisualTransformation は再描画のたびに呼ばれるため、
 * 長い本文では毎回の解析が入力の重さに直結する。
 */
private class HighlightCache(private val capacity: Int = 8) {
    // 複数のフィールド (Front / Back など) が同じ変換を共有するため、
    // 1 件だけだと互いに追い出し合って毎回作り直しになる
    private val entries = object : LinkedHashMap<String, AnnotatedString>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>) =
            size > capacity
    }

    @Synchronized
    fun get(source: String, build: (String) -> AnnotatedString): AnnotatedString =
        entries.getOrPut(source) { build(source) }
}

/** VS Code (Dark+) 風の配色 */
private object HtmlColors {
    val bracket = Color(0xFF808080)   // < > /
    val tag = Color(0xFF569CD6)       // タグ名
    val attr = Color(0xFF9CDCFE)      // 属性名
    val value = Color(0xFFCE9178)     // 属性値・文字列
    val entity = Color(0xFFD7BA7D)    // &nbsp; などの実体参照
    val comment = Color(0xFF6A9955)   // <!-- -->
}

/**
 * HTML を色分けして表示する VisualTransformation。
 * 文字の増減はしないのでオフセットは元のまま (OffsetMapping.Identity)。
 */
object HtmlSyntaxTransformation : VisualTransformation {

    private val cache = HighlightCache()

    private val tagRegex = Regex("<!--.*?-->|</?[A-Za-z][^>]*/?>", RegexOption.DOT_MATCHES_ALL)
    private val nameRegex = Regex("</?\\s*([A-Za-z][\\w:-]*)")
    private val attrRegex = Regex("([A-Za-z_:][\\w:.-]*)\\s*=\\s*(\"[^\"]*\"|'[^']*')")
    private val entityRegex = Regex("&[A-Za-z#][A-Za-z0-9]*;")

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(cache.get(text.text, ::highlight), OffsetMapping.Identity)

    fun highlight(source: String): AnnotatedString = highlightRange(source, 0, source.length)

    /**
     * [from]..[to] の範囲だけを色分けする。
     *
     * 色分けの計算自体は長文でも数ミリ秒だが、付けたスタイル (span) の数だけ
     * 打鍵のたびにテキストを組み直すコストがかかる。長い本文では画面に映る範囲だけ
     * 塗ることで、文字数に関係なく入力の軽さを保つ。
     */
    fun highlightRange(source: String, from: Int, to: Int): AnnotatedString = buildAnnotatedString {
        append(source)
        val lo = from.coerceIn(0, source.length)
        val hi = to.coerceIn(lo, source.length)
        if (lo == hi) return@buildAnnotatedString
        val window = source.substring(lo, hi)

        // 実体参照
        entityRegex.findAll(window).forEach {
            addStyle(
                SpanStyle(color = HtmlColors.entity),
                lo + it.range.first,
                lo + it.range.last + 1,
            )
        }

        tagRegex.findAll(window).forEach { m ->
            val start = lo + m.range.first
            val end = lo + m.range.last + 1
            val chunk = m.value

            if (chunk.startsWith("<!--")) {
                addStyle(SpanStyle(color = HtmlColors.comment), start, end)
                return@forEach
            }

            // タグ全体をまず括弧色にしてから、内側を塗り分ける
            addStyle(SpanStyle(color = HtmlColors.bracket), start, end)

            nameRegex.find(chunk)?.groups?.get(1)?.let { g ->
                addStyle(
                    SpanStyle(color = HtmlColors.tag, fontWeight = FontWeight.Medium),
                    start + g.range.first,
                    start + g.range.last + 1,
                )
            }

            attrRegex.findAll(chunk).forEach { a ->
                val name = a.groups[1]!!
                val value = a.groups[2]!!
                addStyle(
                    SpanStyle(color = HtmlColors.attr),
                    start + name.range.first,
                    start + name.range.last + 1,
                )
                addStyle(
                    SpanStyle(color = HtmlColors.value),
                    start + value.range.first,
                    start + value.range.last + 1,
                )
            }
        }
    }
}

/** CSS 用の配色 (VS Code Dark+ 相当) */
private object CssColors {
    val selector = Color(0xFFD7BA7D)   // セレクタ
    val atRule = Color(0xFFC586C0)     // @media など
    val property = Color(0xFF9CDCFE)   // プロパティ名
    val value = Color(0xFFCE9178)      // 値・文字列
    val number = Color(0xFFB5CEA8)     // 数値・単位
    val punct = Color(0xFF808080)      // { } : ;
    val comment = Color(0xFF6A9955)
}

/**
 * CSS を色分けして表示する VisualTransformation。
 * 波括弧の内と外で意味が変わるため、深さを見ながらセレクタと宣言を塗り分ける。
 */
object CssSyntaxTransformation : VisualTransformation {

    private val cache = HighlightCache()

    private val commentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val declRegex = Regex("""([-\w]+)\s*(:)([^;}]*)""")
    private val atRuleRegex = Regex("""@[-\w]+""")
    private val numberRegex = Regex("""-?\d*\.?\d+(px|em|rem|%|vw|vh|s|ms|deg)?""")

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(cache.get(text.text, ::highlight), OffsetMapping.Identity)

    fun highlight(source: String): AnnotatedString = buildAnnotatedString {
        append(source)

        val comments = commentRegex.findAll(source).map { it.range }.toList()
        fun inComment(index: Int) = comments.any { index in it }

        /** 波括弧の外側: セレクタと @ルール */
        fun styleSelector(range: IntRange) {
            if (range.isEmpty()) return
            addStyle(SpanStyle(color = CssColors.selector), range.first, range.last + 1)
            val part = source.substring(range.first, range.last + 1)
            atRuleRegex.findAll(part).forEach {
                addStyle(
                    SpanStyle(color = CssColors.atRule),
                    range.first + it.range.first,
                    range.first + it.range.last + 1,
                )
            }
        }

        /** 波括弧の内側: プロパティ名と値 */
        fun styleDeclarations(range: IntRange) {
            if (range.isEmpty()) return
            val part = source.substring(range.first, range.last + 1)
            declRegex.findAll(part).forEach { m ->
                val name = m.groups[1]!!
                val colon = m.groups[2]!!
                val value = m.groups[3]!!
                addStyle(
                    SpanStyle(color = CssColors.property),
                    range.first + name.range.first,
                    range.first + name.range.last + 1,
                )
                addStyle(
                    SpanStyle(color = CssColors.punct),
                    range.first + colon.range.first,
                    range.first + colon.range.last + 1,
                )
                if (!value.range.isEmpty()) {
                    addStyle(
                        SpanStyle(color = CssColors.value),
                        range.first + value.range.first,
                        range.first + value.range.last + 1,
                    )
                    numberRegex.findAll(value.value).forEach { n ->
                        addStyle(
                            SpanStyle(color = CssColors.number),
                            range.first + value.range.first + n.range.first,
                            range.first + value.range.first + n.range.last + 1,
                        )
                    }
                }
            }
        }

        var depth = 0
        var segmentStart = 0
        source.forEachIndexed { index, c ->
            if (inComment(index)) return@forEachIndexed
            if (c != '{' && c != '}' && c != ';') return@forEachIndexed

            if (c == ';') {
                addStyle(SpanStyle(color = CssColors.punct), index, index + 1)
                return@forEachIndexed
            }
            val segment = segmentStart until index
            if (depth == 0) styleSelector(segment) else styleDeclarations(segment)
            addStyle(SpanStyle(color = CssColors.punct), index, index + 1)
            depth = if (c == '{') depth + 1 else (depth - 1).coerceAtLeast(0)
            segmentStart = index + 1
        }
        if (segmentStart < source.length) {
            val tail = segmentStart until source.length
            if (depth == 0) styleSelector(tail) else styleDeclarations(tail)
        }

        // コメントは他の色に上書きされないよう最後に塗る
        comments.forEach { addStyle(SpanStyle(color = CssColors.comment), it.first, it.last + 1) }
    }
}

/**
 * 色分けの上に、ノート内検索で一致した箇所の背景を重ねる。
 *
 * 検索欄からフォーカスを奪わずに位置を示したいので、
 * カーソルではなく背景色で「いまどこにいるか」を伝える。
 */
class HtmlSearchTransformation(
    private val base: VisualTransformation,
    private val query: String,
    /** いま選ばれている一致の開始位置 (この欄にないときは null) */
    private val currentStart: Int?,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = base.filter(text)
        if (query.isBlank()) return transformed
        val source = transformed.text.text
        val builder = AnnotatedString.Builder(transformed.text)
        var from = source.indexOf(query, ignoreCase = true)
        while (from >= 0) {
            builder.addStyle(
                SpanStyle(background = if (from == currentStart) CURRENT_HIT else OTHER_HIT),
                from,
                (from + query.length).coerceAtMost(source.length),
            )
            from = source.indexOf(query, from + query.length, ignoreCase = true)
        }
        return TransformedText(builder.toAnnotatedString(), transformed.offsetMapping)
    }

    private companion object {
        val CURRENT_HIT = Color(0xFFB08B00)
        val OTHER_HIT = Color(0x553B6EA5)
    }
}


/**
 * 長い本文向けに、指定した範囲だけを色分けする変換。
 * 範囲の外は素のままなので、スタイルの数が増えず打鍵が重くならない。
 */
class WindowedHtmlTransformation(private val from: Int, private val to: Int) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            cache.get(Triple(text.text, from, to)) {
                HtmlSyntaxTransformation.highlightRange(text.text, from, to)
            },
            OffsetMapping.Identity,
        )

    private companion object {
        val cache = KeyedHighlightCache<Triple<String, Int, Int>>()
    }
}

/** 任意のキーで色分け結果を使い回すためのキャッシュ */
class KeyedHighlightCache<K>(private val capacity: Int = 4) {
    private val entries = object : LinkedHashMap<K, AnnotatedString>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, AnnotatedString>) =
            size > capacity
    }

    @Synchronized
    fun get(key: K, build: () -> AnnotatedString): AnnotatedString = entries.getOrPut(key, build)
}

/**
 * 色分けする範囲を決める。
 *
 * 実測では、色を付けるほど打鍵が重くなる原因は計算ではなくスタイル (span) の数だった。
 * 12,000 文字ぶん塗ると 1 打鍵に 70ms 以上かかる一方、画面に見えているのはせいぜい
 * 2,000 文字ほどなので、その範囲に絞る。短い本文はそのまま全部塗る。
 */
fun highlightWindow(length: Int, cursor: Int, full: Int = 2500, radius: Int = 1500): IntRange? {
    if (length <= full) return null
    val step = radius / 2
    val center = (cursor / step) * step
    return (center - radius).coerceAtLeast(0)..(center + radius).coerceAtMost(length)
}

/** タブ 1 つを何文字ぶんの幅で見せるか (VS Code の既定と同じ) */
const val TAB_WIDTH = 4

/**
 * タブ文字を、決まった幅の字下げに見せる変換。
 *
 * 空白に置き換えて表示すると、見た目の文字数と実際の文字数がずれてしまい、
 * 矢印キーやスニペット (text expander) のカーソル移動が狂う。
 * ここでは文字はタブのまま 1 文字として扱い、字間だけを広げて幅を出す。
 */
class TabIndentTransformation(
    private val base: VisualTransformation,
    private val width: Int = TAB_WIDTH,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = base.filter(text)
        val source = transformed.text
        if ('\t' !in source.text) return transformed

        val builder = AnnotatedString.Builder(source)
        // タブ 1 文字ぶんの幅に、足りない分の字間を足す
        val extra = TextUnit((width - 1).toFloat(), TextUnitType.Em)
        source.text.forEachIndexed { index, c ->
            if (c == '\t') {
                builder.addStyle(SpanStyle(letterSpacing = extra), index, index + 1)
            }
        }
        // 文字の数は変えないので、位置の対応はそのまま
        return TransformedText(builder.toAnnotatedString(), transformed.offsetMapping)
    }
}

/**
 * Tab キーを字下げとして扱う。
 * 選択がないときはタブを差し込み、Shift+Tab では直前の字下げを 1 段戻す。
 */
fun insertTab(value: TextFieldValue, shift: Boolean): TextFieldValue {
    val text = value.text
    val start = value.selection.start
    if (shift) {
        // 直前がタブ、または空白 TAB_WIDTH 個ならそれを削る
        val removeTab = start > 0 && text[start - 1] == '\t'
        val spaces = if (removeTab) 0 else {
            var n = 0
            while (n < TAB_WIDTH && start - n - 1 >= 0 && text[start - n - 1] == ' ') n++
            n
        }
        val remove = if (removeTab) 1 else spaces
        if (remove == 0) return value
        return TextFieldValue(
            text = text.removeRange(start - remove, start),
            selection = TextRange(start - remove),
        )
    }
    val end = value.selection.end
    return TextFieldValue(
        text = text.substring(0, start) + "\t" + text.substring(end),
        selection = TextRange(start + 1),
    )
}
