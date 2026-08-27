package com.haru.ankieditor

import android.content.Context
import anki.card_rendering.RenderedTemplateNode
import anki.generic.Empty
import anki.search.SortOrder
import anki.sync.FullUploadOrDownloadRequest
import anki.sync.SyncAuth
import anki.sync.SyncCollectionResponse
import anki.sync.SyncLoginRequest
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.BackendFactory
import java.io.File

/**
 * Anki 公式 Rust バックエンド (rsdroid) を使った、AnkiDroid 非依存のデータ層。
 *
 * AnkiWeb から自前のコレクションを同期して保持するため、
 *  - メディアは公式のメディア同期でアプリ自身のフォルダに入る (自動・常に最新)
 *  - カードのレンダリングは PC 版と同一の公式実装が使われる
 */
/**
 * 同期結果。
 * auth はシャード URL が反映された最新のもの (次回以降これを使う)。
 * required が NONE 以外のときは同期は完了しておらず、どちらを優先するか利用者に選んでもらう。
 */
data class SyncResult(
    val message: String,
    val auth: SyncAuth,
    val required: FullSyncKind = FullSyncKind.NONE,
)

class AnkiBackendRepo(context: Context) : NoteStore {

    val collectionDir: File = File(context.filesDir, "collection")
    val mediaDir: File = File(collectionDir, "collection.media")

    private val collectionPath = File(collectionDir, "collection.anki2").absolutePath
    private val mediaDbPath = File(collectionDir, "collection.media.db2").absolutePath

    private var backend: Backend? = null

    /**
     * コレクションを開く (必要なら新規作成される)。
     * 起動直後は検索と同期が同時に走るため、二重に開いて
     * "Anki already open" にならないよう排他する。
     */
    @Synchronized
    fun open(): Backend {
        backend?.let { return it }
        ensureNativeLoaded()
        collectionDir.mkdirs()
        mediaDir.mkdirs()
        val b = BackendFactory.getBackend()
        b.openCollection(collectionPath, mediaDir.absolutePath, mediaDbPath)
        backend = b
        return b
    }

    fun close() {
        backend?.let {
            runCatching { it.closeCollection(false) }
            runCatching { it.close() }
        }
        backend = null
    }

    // ---------------------------------------------------------------- 同期

    /**
     * AnkiWeb へログインする。
     * endpoint は proto3 の optional フィールドで「未設定」と「空文字」が区別される。
     * AnkiWeb を使う場合は未設定のままにしないと、空文字を URL として解釈しようとして
     * "Invalid sync server specified" になる (カスタム同期サーバー使用時のみ設定する)。
     */
    fun login(username: String, password: String): SyncAuth =
        normalize(
            open().syncLogin(
                SyncLoginRequest.newBuilder()
                    .setUsername(username)
                    .setPassword(password)
                    .build()
            )
        )

    /** 空文字の endpoint を取り除き、ネットワークタイムアウトを設定する */
    private fun normalize(auth: SyncAuth): SyncAuth {
        val b = auth.toBuilder()
        if (auth.hasEndpoint() && auth.endpoint.isBlank()) b.clearEndpoint()
        if (!auth.hasIoTimeoutSecs()) b.ioTimeoutSecs = 30
        return b.build()
    }

    /**
     * コレクションを同期する。
     *
     * 通常の差分同期は Anki 本体と同じ規則でマージされる (先に同期した側の内容が入り、
     * 後から同期した側は自分の変更を上乗せする)。
     *
     * サーバーとコレクションの構造がずれてフル同期が必要になった場合は、
     * どちらかが必ず失われるので、ここでは実行せずに required を返して呼び出し側に判断を委ねる。
     */
    fun syncCollection(rawAuth: SyncAuth): SyncResult {
        val b = open()
        val resp = b.syncCollection(normalize(rawAuth), false)

        // AnkiWeb はユーザーごとに接続先サーバー (シャード) を割り当て、
        // その URL を newEndpoint で返す。以降のリクエストはそちらへ送る必要がある。
        val auth = if (resp.newEndpoint.isNotBlank()) {
            normalize(rawAuth).toBuilder().setEndpoint(resp.newEndpoint).build()
        } else {
            normalize(rawAuth)
        }

        return when (resp.required) {
            SyncCollectionResponse.ChangesRequired.NO_CHANGES -> SyncResult("変更なし", auth)
            SyncCollectionResponse.ChangesRequired.FULL_SYNC,
            SyncCollectionResponse.ChangesRequired.FULL_DOWNLOAD ->
                SyncResult("サーバー側の取り込みが必要です", auth, FullSyncKind.DOWNLOAD)
            SyncCollectionResponse.ChangesRequired.FULL_UPLOAD ->
                SyncResult("この端末の内容の送信が必要です", auth, FullSyncKind.UPLOAD)
            else -> SyncResult("同期完了", auth)
        }
    }

    /** フル同期を実行する。upload = true ならこの端末の内容でサーバーを置き換える */
    fun fullSync(auth: SyncAuth, upload: Boolean) {
        open().fullUploadOrDownload(
            FullUploadOrDownloadRequest.newBuilder()
                .setAuth(normalize(auth))
                .setUpload(upload)
                .build()
        )
    }

    /** ノートの現在の中身を読む (存在しなければ null) */
    fun noteContent(id: Long): NoteContent? = runCatching {
        val n = open().getNote(id)
        NoteContent(n.fieldsList.toList(), n.tagsList.joinToString(" "))
    }.getOrNull()

    /** メディア同期 (完了までブロックする。IO スレッドで呼ぶこと) */
    fun syncMedia(auth: SyncAuth) {
        open().syncMedia(normalize(auth))
    }

    // ------------------------------------------------------------ 参照/検索

    override fun decks(): List<String> =
        runCatching { open().getDeckNames(true, true).map { it.name }.sorted() }
            .getOrDefault(emptyList())

    /** Anki 検索構文でノートを検索する */
    fun searchNotes(query: String, limit: Int = 200): List<AnkiNote> {
        val b = open()
        val order = SortOrder.newBuilder().setNone(Empty.getDefaultInstance()).build()
        val ids = b.searchNotes(query.ifBlank { "deck:*" }, order)
        return ids.take(limit).mapNotNull { id ->
            runCatching {
                val n = b.getNote(id)
                AnkiNote(
                    id = n.id,
                    modelId = n.notetypeId,
                    fields = n.fieldsList.toList(),
                    tags = n.tagsList.joinToString(" "),
                    mtimeSecs = n.mtimeSecs,
                )
            }.getOrNull()
        }
    }

    /** コレクション内の全タグ (編集画面の選択候補に使う) */
    override fun allTags(): List<String> =
        runCatching { open().allTags().sorted() }.getOrDefault(emptyList())

    override fun fieldNames(notetypeId: Long): List<String> =
        runCatching { open().getNotetype(notetypeId).fieldsList.map { it.name } }
            .getOrDefault(emptyList())

    // ------------------------------------------------------------ メディア整理

    /**
     * どのノートからも参照されていないメディアを調べる。
     * 判定は Anki 本体と同じ仕組み (checkMedia) を使うので、
     * テンプレート側で参照しているファイルなども正しく「使用中」と見なされる。
     */
    override fun unusedMedia(): List<UnusedMedia> = runCatching {
        open().checkMedia().unusedList.map { name ->
            UnusedMedia(
                name = name,
                kind = MediaKind.of(name),
                sizeBytes = File(mediaDir, name).let { if (it.isFile) it.length() else 0L },
            )
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
    }.getOrDefault(emptyList())

    /**
     * メディアをゴミ箱へ移す。
     * 同期すると AnkiWeb 側からも削除される。
     */
    override fun deleteMedia(names: List<String>) {
        if (names.isEmpty()) return
        open().trashMediaFiles(names)
    }

    // -------------------------------------------------------- ノートタイプ編集

    /** ノートタイプ一覧 (id と名前) */
    override fun notetypes(): List<Pair<Long, String>> =
        runCatching { open().getNotetypeNames().map { it.id to it.name } }
            .getOrDefault(emptyList())

    /** テンプレート (表面/裏面) とスタイル CSS を取得する */
    override fun templateOf(notetypeId: Long): CardTemplate? = runCatching {
        val nt = open().getNotetype(notetypeId)
        val t = nt.templatesList.firstOrNull() ?: return null
        CardTemplate(
            notetypeId = notetypeId,
            name = nt.name,
            front = t.config.qFormat,
            back = t.config.aFormat,
            css = nt.config.css,
        )
    }.getOrNull()

    /**
     * 表面/裏面/CSS を書き戻す。
     * すべてのカードの見た目に影響するので、保存は明示的な操作のときだけ行う。
     */
    override fun saveTemplate(template: CardTemplate) {
        val b = open()
        val nt = b.getNotetype(template.notetypeId)
        val first = nt.templatesList.firstOrNull() ?: return
        val updatedTemplate = first.toBuilder().setConfig(
            first.config.toBuilder()
                .setQFormat(template.front)
                .setAFormat(template.back)
                .build()
        ).build()
        val updated = nt.toBuilder()
            .setConfig(nt.config.toBuilder().setCss(template.css).build())
            .setTemplates(0, updatedTemplate)
            .build()
        b.updateNotetype(updated)
    }

    // ------------------------------------------------------------ レンダリング

    /**
     * ノートの 1 枚目のカードを公式レンダラーで描画し、完全な HTML を返す。
     * テンプレート展開・条件分岐・クローズ・フィルタはすべてバックエンド側で処理される。
     */
    fun renderNote(note: AnkiNote): String {
        val b = open()
        val cardId = b.cardsOfNote(note.id).firstOrNull()
            ?: return errorHtml("このノートにカードがありません")
        val resp = b.renderExistingCard(cardId, false, false)
        val answer = withAudio(b, resp.answerNodesList.joinToString("") { nodeText(it) })
        val css = runCatching { b.getNotetype(note.modelId).config.css }.getOrDefault("")
        return wrap(answer, css)
    }

    /**
     * 編集中の内容を保存せずに描画する (ライブプレビュー用)。
     * コレクションに書き込まないので、打鍵のたびに呼んでも安全。
     */
    fun renderDraft(note: AnkiNote, fields: List<String>, tags: String): String {
        val b = open()
        val nt = b.getNotetype(note.modelId)
        val template = nt.templatesList.firstOrNull()
            ?: return errorHtml("テンプレートがありません")
        // 新規ノート (まだ id がない) は、その場で空のノートを作って下書きにする
        val base = if (note.id == 0L) b.newNote(note.modelId) else b.getNote(note.id)
        val draft = base.toBuilder()
            .clearFields().addAllFields(fields)
            .clearTags().addAllTags(splitTags(tags))
            .build()
        val resp = b.renderUncommittedCard(draft, 0, template, false, false)
        val answer = withAudio(b, resp.answerNodesList.joinToString("") { nodeText(it) })
        return wrap(answer, nt.config.css)
    }

    /** 編集内容をコレクションに保存する */
    fun saveNote(note: AnkiNote, fields: List<String>, tags: String) {
        val b = open()
        val updated = b.getNote(note.id).toBuilder()
            .clearFields().addAllFields(fields)
            .clearTags().addAllTags(splitTags(tags))
            .build()
        b.updateNotes(listOf(updated), false)
    }

    /** 「追加」画面の既定値 (直前に使ったデッキとノートタイプ) */
    fun addingDefaults(): Pair<Long, Long>? = runCatching {
        val d = open().defaultsForAdding(0)
        d.deckId to d.notetypeId
    }.getOrNull()

    /** デッキ名から id を引く (存在しなければ null) */
    fun deckId(name: String): Long? =
        runCatching { open().getDeckIdByName(name).takeIf { it != 0L } }.getOrNull()

    /**
     * 新しいノートを追加し、追加されたノートを返す。
     * 次の同期でそのまま AnkiWeb にも送られる。
     */
    fun addNote(notetypeId: Long, deckId: Long, fields: List<String>, tags: String): AnkiNote {
        val b = open()
        val proto = b.newNote(notetypeId).toBuilder()
            .clearFields().addAllFields(fields)
            .clearTags().addAllTags(splitTags(tags))
            .build()
        val id = b.addNote(proto, deckId).noteId
        val added = b.getNote(id)
        return AnkiNote(
            id = added.id,
            modelId = added.notetypeId,
            fields = added.fieldsList.toList(),
            tags = added.tagsList.joinToString(" "),
            mtimeSecs = added.mtimeSecs,
        )
    }

    /** ノートを削除する (カードも一緒に消える)。戻り値は消えた件数 */
    fun deleteNotes(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return open().removeNotes(ids, emptyList()).count
    }

    /**
     * 画像などをコレクションのメディアに登録し、実際に使われたファイル名を返す。
     * バックエンド経由で登録するので、次回の同期で AnkiWeb にもアップロードされる。
     */
    fun addMedia(desiredName: String, bytes: ByteArray): String =
        open().addMediaFile(desiredName, com.google.protobuf.ByteString.copyFrom(bytes))

    private fun splitTags(tags: String): List<String> =
        tags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /**
     * [sound:xxx.mp3] は描画時に [anki:play:...] という目印に置き換わるので、
     * 実際に再生できる audio 要素に差し替える。
     * 保存されるノート側は Anki 標準の [sound:] のままなので、PC 版とも互換。
     */
    private fun withAudio(b: Backend, html: String): String {
        val extracted = runCatching { b.extractAvTags(html, false) }.getOrNull() ?: return html
        val names = extracted.avTagsList.map { tag ->
            if (tag.hasSoundOrVideo()) tag.soundOrVideo else null
        }
        var out = extracted.text
        names.forEachIndexed { index, name ->
            val player = if (name == null) "" else
                "<audio controls preload=\"none\" src=\"$name\" " +
                    "style=\"display:block;width:100%;max-width:320px;margin:.6em 0;\"></audio>"
            out = out.replace("[anki:play:q:$index]", player)
                .replace("[anki:play:a:$index]", player)
        }
        return out
    }

    private fun nodeText(node: RenderedTemplateNode): String =
        if (node.valueCase == RenderedTemplateNode.ValueCase.TEXT) node.text
        else node.replacement.currentText

    private fun wrap(body: String, css: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <script src="${CardRenderer.JQUERY_URL}"></script>
        <script>
        MathJax = {
          tex: { inlineMath: [['\\(', '\\)']], displayMath: [['\\[', '\\]']] },
          startup: { typeset: true }
        };
        </script>
        <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js" async></script>
        <style>
        $css
        </style>
        <style>
        /* ノートタイプの CSS の後に置く上書き。
           参考文献などの長い URL が折り返されず横にはみ出すのを防ぐ */
        a { overflow-wrap: anywhere; word-break: break-word; }
        /* class を付けていない画像は既定でブロック表示にする。
           後ろに続くテキストが自然に次の行へ落ちる。
           .tablet や .syringe のように文中に置く画像は class があるので影響しない */
        img:not([class]), img[class=""] { display: block; }
        img, table, pre, iframe, video { max-width: 100%; }
        body { overflow-x: hidden; }
        </style>
        </head>
        <body class="card">
        $body
        </body>
        </html>
    """.trimIndent()

    private fun errorHtml(msg: String) =
        "<html><body style='color:#b6686f;font-family:sans-serif'>$msg</body></html>"

    companion object {
        @Volatile
        private var nativeLoaded = false

        /**
         * rsdroid の NativeMethods は自前でライブラリをロードしないため、
         * バックエンド生成前にアプリ側で明示的にロードする必要がある。
         */
        @Synchronized
        fun ensureNativeLoaded() {
            if (nativeLoaded) return
            System.loadLibrary("rsdroid")
            nativeLoaded = true
        }
    }
}
