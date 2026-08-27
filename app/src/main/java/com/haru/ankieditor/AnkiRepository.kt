package com.haru.ankieditor

import android.content.ContentResolver
import android.net.Uri

/**
 * AnkiDroid ContentProvider (com.ichi2.anki.flashcards) への読み取りアクセス層。
 * 読み取り専用スケルトン: 検索 → ノート取得 → モデル(テンプレート/CSS)取得。
 *
 * 公式の FlashCardsContract に依存せず、URI と列名を直接記述している
 * (Gradle 依存 com.ichi2.anki:api を後で追加して置き換えてもよい)。
 */
object AnkiContract {
    const val AUTHORITY = "com.ichi2.anki.flashcards"
    val NOTES_URI: Uri = Uri.parse("content://$AUTHORITY/notes")
    val MODELS_URI: Uri = Uri.parse("content://$AUTHORITY/models")
    val DECKS_URI: Uri = Uri.parse("content://$AUTHORITY/decks")
    const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    // decks 列
    const val DECK_NAME = "deck_name"

    // notes 列
    const val NOTE_ID = "_id"
    const val NOTE_MID = "mid"      // note type (model) id
    const val NOTE_FLDS = "flds"    // フィールド (\u001f 区切り)
    const val NOTE_TAGS = "tags"

    // models 列
    const val MODEL_ID = "_id"
    const val MODEL_NAME = "name"
    const val MODEL_FIELD_NAMES = "field_names"  // \u001f 区切り
    const val MODEL_CSS = "css"

    // models/#/templates 列
    const val TMPL_NAME = "card_template_name"
    const val TMPL_QFMT = "question_format"
    const val TMPL_AFMT = "answer_format"

    const val FIELD_SEP = '\u001f'
}


class AnkiRepository(private val resolver: ContentResolver) {

    /**
     * Anki のブラウザ検索構文 (例: "deck:眼科 tag:MTM 網膜") で検索。
     * selection にそのまま検索文字列を渡すと libanki 構文として解釈される。
     */
    fun searchNotes(query: String, limit: Int = 100): List<AnkiNote> {
        val notes = mutableListOf<AnkiNote>()
        val projection = arrayOf(
            AnkiContract.NOTE_ID,
            AnkiContract.NOTE_MID,
            AnkiContract.NOTE_FLDS,
            AnkiContract.NOTE_TAGS,
        )
        resolver.query(
            AnkiContract.NOTES_URI,
            projection,
            query.ifBlank { null },
            null,
            null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(AnkiContract.NOTE_ID)
            val iMid = c.getColumnIndexOrThrow(AnkiContract.NOTE_MID)
            val iFlds = c.getColumnIndexOrThrow(AnkiContract.NOTE_FLDS)
            val iTags = c.getColumnIndexOrThrow(AnkiContract.NOTE_TAGS)
            while (c.moveToNext() && notes.size < limit) {
                notes += AnkiNote(
                    id = c.getLong(iId),
                    modelId = c.getLong(iMid),
                    fields = c.getString(iFlds).split(AnkiContract.FIELD_SEP),
                    tags = c.getString(iTags) ?: "",
                )
            }
        }
        return notes
    }

    private val fieldNameCache = mutableMapOf<Long, List<String>>()

    /** モデルのフィールド名だけを軽量に取得 (ランキング用・メモリキャッシュ付き) */
    fun fieldNames(modelId: Long): List<String> = fieldNameCache.getOrPut(modelId) {
        var names = emptyList<String>()
        val uri = Uri.withAppendedPath(AnkiContract.MODELS_URI, modelId.toString())
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(AnkiContract.MODEL_FIELD_NAMES)
                    if (i >= 0) names = c.getString(i).split(AnkiContract.FIELD_SEP)
                }
            }
        }
        names
    }

    /** デッキ名一覧を取得 (サブデッキは "親::子" 形式) */
    fun getDecks(): List<String> {
        val decks = mutableListOf<String>()
        resolver.query(AnkiContract.DECKS_URI, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(AnkiContract.DECK_NAME)
            if (i < 0) return@use
            while (c.moveToNext()) {
                c.getString(i)?.let { decks += it }
            }
        }
        return decks.sorted()
    }

    /** モデル本体 + テンプレート一覧を取得 */
    fun getModel(modelId: Long): AnkiModel? {
        var model: AnkiModel? = null
        val modelUri = Uri.withAppendedPath(AnkiContract.MODELS_URI, modelId.toString())
        resolver.query(modelUri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                model = AnkiModel(
                    id = modelId,
                    name = c.getString(c.getColumnIndexOrThrow(AnkiContract.MODEL_NAME)),
                    fieldNames = c.getString(c.getColumnIndexOrThrow(AnkiContract.MODEL_FIELD_NAMES))
                        .split(AnkiContract.FIELD_SEP),
                    css = c.getString(c.getColumnIndexOrThrow(AnkiContract.MODEL_CSS)) ?: "",
                    templates = emptyList(),
                )
            }
        }
        val base = model ?: return null

        val templates = mutableListOf<AnkiTemplate>()
        val tmplUri = Uri.withAppendedPath(modelUri, "templates")
        resolver.query(tmplUri, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                templates += AnkiTemplate(
                    name = c.getString(c.getColumnIndexOrThrow(AnkiContract.TMPL_NAME)),
                    qfmt = c.getString(c.getColumnIndexOrThrow(AnkiContract.TMPL_QFMT)),
                    afmt = c.getString(c.getColumnIndexOrThrow(AnkiContract.TMPL_AFMT)),
                )
            }
        }
        return base.copy(templates = templates)
    }
}

/**
 * キーワード検索結果の並べ替え。
 * ❶ Front に語 (または語の一部) を含むものを優先 — 一致した部分文字列が長いほど上位
 * ❷ 同点なら Back に語が出現する回数が多い順
 */
object CardRenderer {

    const val MEDIA_BASE_URL = "https://ankimedia.local/"
    const val JQUERY_URL = "${MEDIA_BASE_URL}__jquery.js"

    fun render(note: AnkiNote, model: AnkiModel, templateIndex: Int = 0, answer: Boolean = true): String {
        val tmpl = model.templates.getOrNull(templateIndex) ?: return errorHtml("テンプレートがありません")
        val fieldMap = model.fieldNames.zip(note.fields).toMap() + ("Tags" to note.tags)

        val front = expand(tmpl.qfmt, fieldMap)
        val html = if (answer) {
            expand(tmpl.afmt, fieldMap).replace("{{FrontSide}}", front)
        } else {
            front
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <script src="$JQUERY_URL"></script>
            <script>
            MathJax = {
              tex: { inlineMath: [['\\(', '\\)']], displayMath: [['\\[', '\\]']] },
              startup: { typeset: true }
            };
            </script>
            <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js" async></script>
            <style>
            ${model.css}
            </style>
            </head>
            <body class="card">
            $html
            </body>
            </html>
        """.trimIndent()
    }

    private fun expand(template: String, fields: Map<String, String>): String {
        var html = substituteFields(expandSections(template, fields), fields)
        // {{c1::表示テキスト::ヒント}} → 表示テキスト
        html = html.replace(Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?\\}\\}", RegexOption.DOT_MATCHES_ALL), "$1")
        // <anki-mathjax>x^2</anki-mathjax> → \(x^2\) / block指定は \[x^2\]
        // 中身は HTML エスケープされているので TeX に戻してから MathJax に渡す
        html = html.replace(
            Regex("<anki-mathjax([^>]*)>(.*?)</anki-mathjax>", RegexOption.DOT_MATCHES_ALL)
        ) { m ->
            val attrs = m.groupValues[1]
            val tex = unescapeHtml(m.groupValues[2])
            if (attrs.contains("block=\"true\"")) "\\[$tex\\]" else "\\($tex\\)"
        }
        return html
    }

    private fun unescapeHtml(s: String): String =
        s.replace("<br>", " ")
            .replace("<br/>", " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    /** {{#Field}}...{{/Field}} (非空なら表示) / {{^Field}}...{{/Field}} (空なら表示) をネスト対応で展開 */
    private fun expandSections(template: String, fields: Map<String, String>): String {
        val token = Regex("\\{\\{\\s*([#^/])\\s*([^}]+?)\\s*\\}\\}")
        class Frame(val type: Char, val name: String, val sb: StringBuilder = StringBuilder())
        val stack = ArrayDeque<Frame>()
        val root = StringBuilder()
        fun current(): StringBuilder = stack.lastOrNull()?.sb ?: root

        var idx = 0
        for (m in token.findAll(template)) {
            current().append(template, idx, m.range.first)
            idx = m.range.last + 1
            val type = m.groupValues[1][0]
            val name = m.groupValues[2]
            if (type == '/') {
                val frame = stack.removeLastOrNull() ?: continue
                val value = fields[frame.name] ?: ""
                val show = if (frame.type == '#') value.isNotBlank() else value.isBlank()
                if (show) current().append(frame.sb)
            } else {
                stack.addLast(Frame(type, name))
            }
        }
        current().append(template, idx, template.length)
        // 閉じ忘れセクションは中身をそのまま出す
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            current().append(f.sb)
        }
        return root.toString()
    }

    /** {{Field}} / {{text:Field}} / {{furigana:Field}} 等を置換。{{FrontSide}} は残す */
    private fun substituteFields(template: String, fields: Map<String, String>): String {
        return Regex("\\{\\{([^#^/}][^}]*)\\}\\}").replace(template) { m ->
            val raw = m.groupValues[1].trim()
            if (raw == "FrontSide") return@replace m.value
            val name = raw.substringAfterLast(':').trim()
            val filters = if (raw.contains(':')) raw.substringBeforeLast(':') else ""
            val value = fields[name] ?: return@replace ""
            if (filters.split(':').any { it.trim() == "text" }) {
                value.replace(Regex("<[^>]*>"), "")
            } else {
                value
            }
        }
    }

    private fun errorHtml(msg: String) =
        "<html><body style='color:#b6686f;font-family:sans-serif'>$msg</body></html>"
}
