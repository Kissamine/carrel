package com.haru.ankieditor

object SearchRanker {

    // ------------------------------------------------------------ あいまい一致

    /** 連続する 2 文字の並び。1 文字違いでも大半は一致するので日本語に向く */
    private fun bigrams(s: String): List<String> =
        if (s.length < 2) listOf(s) else (0 until s.length - 1).map { s.substring(it, it + 2) }

    /**
     * 語のうち、どれだけが本文に含まれるか (0.0〜1.0)。
     * 「緑内障も点眼治療」と「緑内障の点眼治療」なら 7 組中 5 組が一致して約 0.71 になる。
     */
    fun containment(keyword: String, text: String): Float {
        if (keyword.isBlank() || text.isBlank()) return 0f
        val grams = bigrams(keyword)
        if (grams.isEmpty()) return 0f
        return grams.count { text.contains(it) }.toFloat() / grams.size
    }

    /** Anki に投げる検索語。1 文字違いでも拾えるよう、語を分割して OR でつなぐ */
    fun keywordQuery(keyword: String): String {
        val k = keyword.trim()
        if (k.isEmpty()) return ""
        val tokens = if (k.contains(Regex("\\s"))) {
            k.split(Regex("\\s+")).filter { it.isNotBlank() }
        } else {
            chunks(k)
        }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "\"*${escapeAnki(it)}*\"" }
            .let { if (tokens.size > 1) "($it)" else it }
    }

    /** 区切りのない文字列を、少しずつ重ねながら短い塊に分ける */
    private fun chunks(s: String, size: Int = 3, step: Int = 2): List<String> {
        if (s.length <= size) return listOf(s)
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val end = (i + size).coerceAtMost(s.length)
            val piece = s.substring(i, end)
            if (piece.length >= 2) out += piece
            if (end == s.length) break
            i += step
        }
        return out.distinct()
    }

    private fun escapeAnki(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("*", "\\*")
        .replace("_", "\\_")


    /** 表示用のタイトル (Front 相当) をプレーンテキストで取り出す */
    fun title(note: AnkiNote): String =
        stripHtml(note.fields.firstOrNull() ?: "").trim()

    fun sort(
        notes: List<AnkiNote>,
        mode: SortMode,
        keyword: String,
        fieldNamesOf: (Long) -> List<String>,
    ): List<AnkiNote> {
        // 分割して OR で検索するぶん候補が広がるので、似ていないものはここで落とす
        val target = filterByKeyword(notes, keyword, fieldNamesOf)
        return when (mode) {
            SortMode.RELEVANCE -> rank(target, keyword, fieldNamesOf)
            SortMode.NAME_ASC -> target.sortedBy { title(it) }
            SortMode.NAME_DESC -> target.sortedByDescending { title(it) }
            SortMode.MODIFIED -> target.sortedByDescending { it.mtimeSecs }
        }
    }

    private fun filterByKeyword(
        notes: List<AnkiNote>,
        keyword: String,
        fieldNamesOf: (Long) -> List<String>,
    ): List<AnkiNote> {
        val kw = keyword.trim().lowercase()
        if (kw.length < 2) return notes
        return notes.filter { note ->
            val names = fieldNamesOf(note.modelId)
            val front = fieldValue(note, names, "Front", 0)
            val back = fieldValue(note, names, "Back", 1)
            front.contains(kw) || back.contains(kw) ||
                containment(kw, front) >= 0.5f || containment(kw, back) >= 0.5f
        }
    }

    fun rank(
        notes: List<AnkiNote>,
        keyword: String,
        fieldNamesOf: (Long) -> List<String>,
    ): List<AnkiNote> {
        val kw = keyword.trim().lowercase()
        if (kw.isBlank()) return notes

        val scored = notes.map { note ->
            val names = fieldNamesOf(note.modelId)
            Triple(
                note,
                frontScore(fieldValue(note, names, "Front", 0), kw),
                countOccurrences(fieldValue(note, names, "Back", 1), kw),
            )
        }
        return scored
            .sortedWith(compareByDescending<Triple<AnkiNote, Int, Int>> { it.second }.thenByDescending { it.third })
            .map { it.first }
    }

    /** 名前でフィールドを引き、無ければ位置で取得 */
    private fun fieldValue(note: AnkiNote, names: List<String>, name: String, fallbackIndex: Int): String {
        val i = names.indexOfFirst { it.equals(name, ignoreCase = true) }
        val raw = note.fields.getOrNull(if (i >= 0) i else fallbackIndex) ?: return ""
        return stripHtml(raw).lowercase()
    }

    /**
     * 完全一致が最上位。含まれない場合は「語のどれだけが含まれるか」で採点するので、
     * 1 文字違いでも上位に出る。
     */
    private fun frontScore(front: String, kw: String): Int {
        if (front.isBlank()) return 0
        if (front.contains(kw)) return 1000 + kw.length
        return (containment(kw, front) * 500).toInt()
    }

    private fun countOccurrences(text: String, kw: String): Int {
        if (text.isBlank() || kw.isBlank()) return 0
        var count = 0
        var i = text.indexOf(kw)
        while (i >= 0) {
            count++
            i = text.indexOf(kw, i + kw.length)
        }
        return count
    }

    /**
     * タグと実体参照を落として素のテキストにする。
     * Android の Html.fromHtml に相当する処理を、プラットフォームに依存しない形で持つ
     * (macOS 版と同じコードを共有するため)。
     */
    fun stripHtml(s: String): String = stripHtmlKeepingSpaces(s)
        .replace(Regex("\\s+"), " ")
        .trim()

    /** タグと実体参照だけ落とし、空白の詰めは呼び出し側に任せる版 */
    fun stripHtmlKeepingSpaces(s: String): String = s
        // 改行になるタグは空白にしてから落とす
        .replace(Regex("<\\s*(br|/p|/div|/li|/h[1-6]|/tr)\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]*>"), "")
        .let(::decodeEntities)

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        var out = s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        out = Regex("&#(x?)([0-9A-Fa-f]+);").replace(out) { m ->
            val radix = if (m.groupValues[1].isEmpty()) 10 else 16
            m.groupValues[2].toIntOrNull(radix)?.toChar()?.toString() ?: m.value
        }
        // & 自体の復元は最後に行う (先にやると上の変換とぶつかる)
        return out.replace("&amp;", "&")
    }
}

/**
 * 一覧のタイトルに使う、簡単な HTML だけを反映した文字列。
 *
 * カードの見出しには <sup>01</sup> のような通し番号や &nbsp; がよく入るので、
 * それらは見た目に残しつつ、その他のタグは落として 1 行の文字列にする。
 */
object TitleFormatter {

    private val supRegex = Regex("<sup[^>]*>(.*?)</sup>", RegexOption.DOT_MATCHES_ALL)
    private val subRegex = Regex("<sub[^>]*>(.*?)</sub>", RegexOption.DOT_MATCHES_ALL)
    private val brRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /** 上付き・下付きの位置 (整形後の文字列に対する範囲) */
    data class Marks(
        val text: String,
        val superscripts: List<IntRange>,
        val subscripts: List<IntRange>,
    )

    fun format(source: String): Marks {
        // 改行になるタグを先に改行へ
        var work = brRegex.replace(source, "\n")
        work = Regex("</(p|div|li|h[1-6]|tr)>", RegexOption.IGNORE_CASE).replace(work, "\n")

        val supers = mutableListOf<IntRange>()
        val subs = mutableListOf<IntRange>()
        val out = StringBuilder()
        var index = 0

        // 上付き・下付きの中身は残し、位置を覚えておく
        val marker = Regex(
            "<(sup|sub)[^>]*>(.*?)</\\1>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        marker.findAll(work).forEach { m ->
            out.append(stripTags(work.substring(index, m.range.first)))
            val inner = stripTags(m.groupValues[2])
            val start = out.length
            out.append(inner)
            val range = start until out.length
            if (m.groupValues[1].lowercase() == "sup") supers += range else subs += range
            index = m.range.last + 1
        }
        out.append(stripTags(work.substring(index)))

        // 余分な空白を詰める (位置がずれるので、詰めるのは前後だけにする)
        val text = out.toString().replace(Regex("[ \\t]+"), " ").trim()
        val shift = out.length - out.toString().trimStart().length
        fun move(r: IntRange) = (r.first - shift).coerceAtLeast(0) until
            (r.last + 1 - shift).coerceIn(0, text.length)
        return Marks(
            text = text,
            superscripts = supers.map(::move).filter { !it.isEmpty() },
            subscripts = subs.map(::move).filter { !it.isEmpty() },
        )
    }

    private fun stripTags(s: String): String =
        SearchRanker.stripHtmlKeepingSpaces(s)
}

/**
 * 一覧に出すタイトル。
 *
 * <br> は改行に、&nbsp; などの実体参照は元の文字に戻す。
 * 上付き・下付き (<sup>/<sub>) は中身の文字だけを残し、見た目は普通の文字に揃える
 * (小さく浮いた文字が並ぶと一覧が読みにくいため)。
 */
fun noteTitle(note: AnkiNote, limit: Int = 200): androidx.compose.ui.text.AnnotatedString {
    val marks = TitleFormatter.format(note.fields.firstOrNull() ?: "")
    return androidx.compose.ui.text.AnnotatedString(marks.text.take(limit))
}
