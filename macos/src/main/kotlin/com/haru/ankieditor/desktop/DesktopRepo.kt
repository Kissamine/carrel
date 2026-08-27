package com.haru.ankieditor.desktop

import com.haru.ankieditor.AnkiNote
import com.haru.ankieditor.CardTemplate
import com.haru.ankieditor.FullSyncKind
import com.haru.ankieditor.MediaKind
import com.haru.ankieditor.NoteContent
import com.haru.ankieditor.UnusedMedia
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** AnkiWeb の認証情報。hkey とシャード URL を保持する */
data class Auth(val hkey: String, val endpoint: String?) {
    fun toJson(): JSONObject = params("hkey" to hkey, "endpoint" to endpoint)

    companion object {
        fun from(o: JSONObject): Auth = Auth(
            hkey = o.getString("hkey"),
            endpoint = if (o.isNull("endpoint")) null else o.optString("endpoint").ifBlank { null },
        )
    }
}

/** 時間のかかる処理の様子 */
data class JobStatus(
    val running: Boolean,
    val error: String?,
    val transferred: Int,
    val total: Int,
)

/** メディア同期の様子 */
data class MediaStatus(val active: Boolean, val detail: String)

/** 同期の結果。required が NONE 以外なら、どちらを優先するか利用者に選んでもらう */
data class SyncResult(val required: FullSyncKind, val auth: Auth)

/**
 * macOS 版のデータ層。
 * Android 版の AnkiBackendRepo と同じ役割・同じメソッド名で、中身をブリッジ経由にしてある。
 */
class DesktopRepo(private val bridge: AnkiBridge, val collectionDir: File) : com.haru.ankieditor.NoteStore {

    lateinit var mediaDir: File
        private set

    fun open() {
        collectionDir.mkdirs()
        val result = bridge.callObject(
            "open",
            params("path" to File(collectionDir, "collection.anki2").absolutePath),
        )!!
        mediaDir = File(result.getString("mediaDir"))
        mediaDir.mkdirs()
    }

    fun close() = bridge.stop()

    // ---------------------------------------------------------------- 同期

    fun login(username: String, password: String): Auth =
        Auth.from(bridge.callObject("login", params("username" to username, "password" to password))!!)

    fun syncCollection(auth: Auth): SyncResult {
        val r = bridge.callObject("syncCollection", params("auth" to auth.toJson()))!!
        return SyncResult(
            required = FullSyncKind.valueOf(r.getString("required")),
            auth = Auth.from(r.getJSONObject("auth")),
        )
    }

    /** コレクション全体の送受信を始める (実際の進み具合は [jobStatus] で見る) */
    fun startFullSync(auth: Auth, upload: Boolean) {
        bridge.call("fullSync", params("auth" to auth.toJson(), "upload" to upload))
    }

    /** メディア同期を始める (進み具合は [mediaStatus] で見る) */
    fun startMediaSync(auth: Auth) {
        bridge.call("syncMedia", params("auth" to auth.toJson()))
    }

    fun jobStatus(): JobStatus {
        val o = bridge.callObject("jobStatus") ?: return JobStatus(false, null, 0, 0)
        return JobStatus(
            running = o.optBoolean("running"),
            error = if (o.isNull("error")) null else o.optString("error").ifBlank { null },
            transferred = o.optInt("transferred"),
            total = o.optInt("total"),
        )
    }

    fun mediaStatus(): MediaStatus {
        val o = bridge.callObject("mediaStatus") ?: return MediaStatus(false, "")
        val parts = listOf("checked", "added", "removed")
            .map { o.optString(it) }
            .filter { it.isNotBlank() }
        return MediaStatus(o.optBoolean("active"), parts.joinToString(" · "))
    }

    // ------------------------------------------------------------ 参照/検索

    override fun decks(): List<String> = bridge.callArray("decks").strings()

    fun searchNotes(query: String, limit: Int = 200): List<AnkiNote> =
        bridge.callArray("searchNotes", params("query" to query, "limit" to limit)).objects().map(::note)

    override fun allTags(): List<String> = bridge.callArray("allTags").strings()

    override fun fieldNames(notetypeId: Long): List<String> =
        bridge.callArray("fieldNames", params("notetypeId" to notetypeId)).strings()

    fun noteContent(id: Long): NoteContent? =
        bridge.callObject("noteContent", params("id" to id))?.let {
            NoteContent(it.getJSONArray("fields").strings(), it.getString("tags"))
        }

    // ------------------------------------------------------------------ 描画

    /** 保存済みのノートを描画する (表面 + 裏面) */
    fun renderNote(note: AnkiNote): String =
        wrapRendered(bridge.callObject("renderNote", params("id" to note.id))!!)

    /** 編集中の内容を保存せずに描画する */
    fun renderDraft(note: AnkiNote, fields: List<String>, tags: String): String = wrapRendered(
        bridge.callObject(
            "renderDraft",
            params(
                "id" to note.id,
                "modelId" to note.modelId,
                "fields" to fields,
                "tags" to tags,
            ),
        )!!
    )

    private fun wrapRendered(r: JSONObject): String {
        val error = r.optString("error", "")
        if (error.isNotBlank()) return errorHtml(error)
        return CardHtml.wrap(r.optString("body", ""), r.optString("css", ""))
    }

    private fun errorHtml(msg: String) =
        "<html><body style='color:#b6686f;font-family:-apple-system'>$msg</body></html>"

    // ------------------------------------------------------------------ 編集

    fun saveNote(note: AnkiNote, fields: List<String>, tags: String) {
        bridge.call("saveNote", params("id" to note.id, "fields" to fields, "tags" to tags))
    }

    fun addingDefaults(): Pair<Long, Long>? =
        bridge.callObject("addingDefaults")?.let { it.getLong("deckId") to it.getLong("notetypeId") }

    fun deckId(name: String): Long? =
        (bridge.call("deckId", params("name" to name)) as? Number)?.toLong()

    fun addNote(notetypeId: Long, deckId: Long, fields: List<String>, tags: String): AnkiNote =
        note(
            bridge.callObject(
                "addNote",
                params(
                    "notetypeId" to notetypeId,
                    "deckId" to deckId,
                    "fields" to fields,
                    "tags" to tags,
                ),
            )!!
        )

    fun deleteNotes(ids: List<Long>): Int =
        (bridge.call("deleteNotes", params("ids" to ids)) as? Number)?.toInt() ?: 0

    /** 画像などをコレクションのメディアに登録し、実際に使われたファイル名を返す */
    fun addMedia(desiredName: String, bytes: ByteArray): String =
        bridge.call(
            "addMedia",
            params("name" to desiredName, "dataBase64" to Base64.getEncoder().encodeToString(bytes)),
        ) as String

    // -------------------------------------------------------- ノートタイプ編集

    override fun notetypes(): List<Pair<Long, String>> =
        bridge.callArray("notetypes").objects().map { it.getLong("id") to it.getString("name") }

    override fun templateOf(notetypeId: Long): CardTemplate? =
        bridge.callObject("templateOf", params("notetypeId" to notetypeId))?.let {
            CardTemplate(
                notetypeId = it.getLong("notetypeId"),
                name = it.getString("name"),
                front = it.getString("front"),
                back = it.getString("back"),
                css = it.getString("css"),
            )
        }

    override fun saveTemplate(template: CardTemplate) {
        bridge.call(
            "saveTemplate",
            params(
                "notetypeId" to template.notetypeId,
                "front" to template.front,
                "back" to template.back,
                "css" to template.css,
            ),
        )
    }

    // ---------------------------------------------------------- メディア整理

    override fun unusedMedia(): List<UnusedMedia> =
        bridge.callArray("unusedMedia").objects().map {
            val name = it.getString("name")
            UnusedMedia(name, MediaKind.of(name), it.optLong("sizeBytes"))
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))

    override fun deleteMedia(names: List<String>) {
        if (names.isNotEmpty()) bridge.call("deleteMedia", params("names" to names))
    }

    // ------------------------------------------------------------------ 変換

    private fun note(o: JSONObject) = AnkiNote(
        id = o.getLong("id"),
        modelId = o.getLong("modelId"),
        fields = o.getJSONArray("fields").strings(),
        tags = o.getString("tags"),
        mtimeSecs = o.optInt("mtimeSecs"),
    )
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
