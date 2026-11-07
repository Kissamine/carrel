package com.haru.ankieditor

import android.content.Context
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import anki.sync.SyncAuth
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /**
     * ハードウェアキーボードのショートカット。画面側から差し込む。
     * 入力欄にフォーカスがあっても届くよう、Activity で受けている
     */
    var shortcutHandler: ((android.view.KeyEvent) -> Boolean)? = null

    /** Ctrl+○ の組み合わせは、この経路でも届く (入力中はこちらだけのことがある) */
    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (shortcutHandler?.invoke(event) == true) return true
        return super.onKeyShortcut(keyCode, event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        android.util.Log.d(
            "AnkiEditorKeys",
            "code=${event.keyCode} ctrl=${event.isCtrlPressed} action=${event.action} " +
                "handler=${shortcutHandler != null}",
        )
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            shortcutHandler?.invoke(event) == true
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private lateinit var repo: AnkiBackendRepo

    private var defaultDeck by mutableStateOf<String?>(null)
    private var tagOrder by mutableStateOf<List<String>>(emptyList())
    private var pinnedTags by mutableStateOf<List<String>>(emptyList())
    private var defaultTags by mutableStateOf<List<String>>(emptyList())
    private var sortMode by mutableStateOf(SortMode.NAME_ASC)
    private var editorMode by mutableStateOf(EditorMode.RICH)
    private var imageCorner by mutableStateOf(ImageCorner.SQUARE)
    private var splitFraction by mutableFloatStateOf(0.4f)
    private var editorFraction by mutableFloatStateOf(0.5f)
    private var textZoomWide by mutableIntStateOf(85)
    private var textZoomCompact by mutableIntStateOf(100)
    private var auth by mutableStateOf<SyncAuth?>(null)

    private fun prefs() = getSharedPreferences("ankieditor", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo = AnkiBackendRepo(this)

        defaultDeck = prefs().getString(PREF_DEFAULT_DECK, null)
        tagOrder = prefs().getString(PREF_TAG_ORDER, "")
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        pinnedTags = prefs().getString(PREF_PINNED_TAGS, "")
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        defaultTags = prefs().getString(PREF_DEFAULT_TAGS, "")
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        sortMode = runCatching {
            SortMode.valueOf(prefs().getString(PREF_SORT, null) ?: SortMode.NAME_ASC.name)
        }.getOrDefault(SortMode.NAME_ASC)
        editorMode = runCatching {
            EditorMode.valueOf(prefs().getString(PREF_EDITOR_MODE, null) ?: EditorMode.RICH.name)
        }.getOrDefault(EditorMode.RICH)
        imageCorner = runCatching {
            ImageCorner.valueOf(prefs().getString(PREF_IMAGE_CORNER, null) ?: ImageCorner.SQUARE.name)
        }.getOrDefault(ImageCorner.SQUARE)
        splitFraction = prefs().getFloat(PREF_SPLIT, 0.4f)
        editorFraction = prefs().getFloat(PREF_EDITOR_SPLIT, 0.5f)
        textZoomWide = prefs().getInt(PREF_TEXT_ZOOM, 85)
        textZoomCompact = prefs().getInt(PREF_TEXT_ZOOM_COMPACT, 100)
        auth = savedAuth()

        setContent {
            MaterialTheme(colorScheme = AnkiEditorDarkColors) {
                AppScreen(
                    repo = repo,
                    auth = auth,
                    defaultDeck = defaultDeck,
                    tagOrder = tagOrder,
                    pinnedTags = pinnedTags,
                    defaultTags = defaultTags,
                    sortMode = sortMode,
                    defaultEditorMode = editorMode,
                    defaultImageCorner = imageCorner,
                    splitFraction = splitFraction,
                    editorFraction = editorFraction,
                    textZoomWide = textZoomWide,
                    textZoomCompact = textZoomCompact,
                    onSetAuth = { a ->
                        auth = a
                        prefs().edit()
                            .putString(PREF_HKEY, a?.hkey)
                            .putString(PREF_ENDPOINT, a?.endpoint)
                            .apply()
                    },
                    onSetDefaultDeck = { deck ->
                        defaultDeck = deck
                        prefs().edit().putString(PREF_DEFAULT_DECK, deck).apply()
                    },
                    onSetTagOrder = { order ->
                        tagOrder = order
                        prefs().edit().putString(PREF_TAG_ORDER, order.joinToString("\n")).apply()
                    },
                    onSetPinnedTags = { pinned ->
                        pinnedTags = pinned
                        prefs().edit().putString(PREF_PINNED_TAGS, pinned.joinToString("\n")).apply()
                    },
                    onSetDefaultTags = { t ->
                        defaultTags = t
                        prefs().edit().putString(PREF_DEFAULT_TAGS, t.joinToString("\n")).apply()
                    },
                    onSetSortMode = { mode ->
                        sortMode = mode
                        prefs().edit().putString(PREF_SORT, mode.name).apply()
                    },
                    onSetDefaultEditorMode = { m ->
                        editorMode = m
                        prefs().edit().putString(PREF_EDITOR_MODE, m.name).apply()
                    },
                    onSetDefaultImageCorner = { c ->
                        imageCorner = c
                        prefs().edit().putString(PREF_IMAGE_CORNER, c.name).apply()
                    },
                    onSetSplitFraction = { f ->
                        splitFraction = f
                        prefs().edit().putFloat(PREF_SPLIT, f).apply()
                    },
                    onSetEditorFraction = { f ->
                        editorFraction = f
                        prefs().edit().putFloat(PREF_EDITOR_SPLIT, f).apply()
                    },
                    onSetTextZoomWide = { z ->
                        textZoomWide = z
                        prefs().edit().putInt(PREF_TEXT_ZOOM, z).apply()
                    },
                    onSetTextZoomCompact = { z ->
                        textZoomCompact = z
                        prefs().edit().putInt(PREF_TEXT_ZOOM_COMPACT, z).apply()
                    },
                )
            }
        }
    }

    private fun savedAuth(): SyncAuth? {
        val hkey = prefs().getString(PREF_HKEY, null) ?: return null
        val b = SyncAuth.newBuilder().setHkey(hkey).setIoTimeoutSecs(30)
        // AnkiWeb の場合 endpoint は「未設定」でなければならない (空文字は不正な URL 扱いになる)
        prefs().getString(PREF_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { b.endpoint = it }
        return b.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        repo.close()
    }

    companion object {
        private const val PREF_DEFAULT_DECK = "default_deck"
        private const val PREF_TAG_ORDER = "tag_order"
        private const val PREF_PINNED_TAGS = "pinned_tags"
        private const val PREF_DEFAULT_TAGS = "default_tags"
        private const val PREF_SORT = "sort_mode"
        private const val PREF_EDITOR_MODE = "editor_mode"
        private const val PREF_IMAGE_CORNER = "image_corner"
        private const val PREF_SPLIT = "split_fraction"
        private const val PREF_EDITOR_SPLIT = "editor_split_fraction"
        private const val PREF_TEXT_ZOOM = "text_zoom"
        private const val PREF_TEXT_ZOOM_COMPACT = "text_zoom_compact"
        private const val PREF_HKEY = "sync_hkey"
        private const val PREF_ENDPOINT = "sync_endpoint"
    }
}

/** 選択したファイルの表示名を取得する (取れなければ拡張子付きの既定名) */
fun displayNameOf(context: Context, uri: android.net.Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
    if (!name.isNullOrBlank()) return name

    val ext = context.contentResolver.getType(uri)
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
    return "image-${System.currentTimeMillis()}.$ext"
}

/** タグの先頭絵文字とテキストの間に半角スペースを入れる (例: "👁眼科" → "👁 眼科") */
fun formatTag(tag: String): String {
    val m = Regex("^([^\\p{L}\\p{N}\\s]+)(\\S.*)$").find(tag) ?: return tag
    val (prefix, rest) = m.destructured
    return "$prefix $rest"
}

/**
 * 編集中の下書き。
 *
 * 打鍵のたびに画面全体が作り直されるのを避けるため、下書きは画面の状態から切り離して
 * この入れ物に持たせている。中身を読むのは編集欄と、プレビュー更新の効果の中だけ。
 */
@Stable
class DraftState {
    var fields by mutableStateOf<List<String>>(emptyList())
    var tags by mutableStateOf("")
    var fieldNames by mutableStateOf<List<String>>(emptyList())
}

/**
 * 同期の結果、自分が保存した内容が別の端末の編集で置き換わってしまったノート。
 * どちらを採るかはノート単位で選べるようにする。
 */
data class NoteConflict(
    val noteId: Long,
    val mine: NoteContent,
    val server: NoteContent,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    repo: AnkiBackendRepo,
    auth: SyncAuth?,
    defaultDeck: String?,
    tagOrder: List<String>,
    pinnedTags: List<String>,
    defaultTags: List<String>,
    sortMode: SortMode,
    defaultEditorMode: EditorMode,
    defaultImageCorner: ImageCorner,
    splitFraction: Float,
    editorFraction: Float,
    textZoomWide: Int,
    textZoomCompact: Int,
    onSetAuth: (SyncAuth?) -> Unit,
    onSetDefaultDeck: (String?) -> Unit,
    onSetTagOrder: (List<String>) -> Unit,
    onSetPinnedTags: (List<String>) -> Unit,
    onSetDefaultTags: (List<String>) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetDefaultEditorMode: (EditorMode) -> Unit,
    onSetDefaultImageCorner: (ImageCorner) -> Unit,
    onSetSplitFraction: (Float) -> Unit,
    onSetEditorFraction: (Float) -> Unit,
    onSetTextZoomWide: (Int) -> Unit,
    onSetTextZoomCompact: (Int) -> Unit,
) {
    // 一覧のスクロール位置。プレビューへ切り替えても失われないよう、
    // 一覧そのものではなく画面全体のスコープで保持する
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var keyword by remember { mutableStateOf("") }
    // ソートは既定では自動 (キーワードなし → 名前昇順 / キーワードあり → 関連順)。
    // ユーザーが明示的に選んだらそれを優先し、検索の有無が変わったら自動に戻す
    var manualSort by remember { mutableStateOf<SortMode?>(null) }
    val hasKeyword = keyword.isNotBlank()
    LaunchedEffect(hasKeyword) { manualSort = null }
    val effectiveSort = manualSort
        ?: if (hasKeyword) SortMode.RELEVANCE else sortMode
    var selectedDeck by remember { mutableStateOf(defaultDeck) }
    var showSettings by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    // 選んだタグはそのまま覚えておく (次に開いたときも同じ絞り込みで始まる)
    var selectedTags by remember { mutableStateOf(defaultTags.toSet()) }
    LaunchedEffect(selectedTags) { onSetDefaultTags(selectedTags.toList()) }
    var decks by remember { mutableStateOf<List<String>>(emptyList()) }
    var collectionTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var notes by remember { mutableStateOf<List<AnkiNote>>(emptyList()) }
    var selected by remember { mutableStateOf<AnkiNote?>(null) }
    var previewHtml by remember { mutableStateOf<String?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    // 表示中の HTML がどのノートのものかを覚えておく。
    // 素早く選び替えると前のノートの描画が後から終わって上書きしてしまうため
    var previewNoteId by remember { mutableStateOf<Long?>(null) }
    var renderJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ---- 編集状態 ----
    var editing by remember { mutableStateOf(false) }
    var editorMode by remember(defaultEditorMode) { mutableStateOf(defaultEditorMode) }
    // 打鍵のたびに画面全体が作り直されないよう、下書きは専用の入れ物に入れる。
    // 画面の組み立て中にこの中身を読まなければ、再構成は編集欄だけで済む
    val draft = remember { DraftState() }
    // ライブプレビューが動いているか (長すぎる本文では手動更新に切り替える)
    var livePreview by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<Int?>(null) }
    // 保存のたびに増やす。これを合図に自動同期する
    var saveTick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    // Ctrl+F が押された合図 (プレビュー側 / 編集側)
    var previewFindRequest by remember { mutableIntStateOf(0) }
    var editorFindRequest by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    // この端末で保存したが、まだ同期を通していない内容 (競合の判定に使う)
    val pendingEdits = remember { mutableStateMapOf<Long, NoteContent>() }
    var conflicts by remember { mutableStateOf<List<NoteConflict>>(emptyList()) }
    var fullSyncPrompt by remember { mutableStateOf<FullSyncKind?>(null) }
    // ---- 追加・削除 ----
    var creating by remember { mutableStateOf(false) }
    var newNoteTypeId by remember { mutableLongStateOf(0L) }
    var newNoteDeckId by remember { mutableLongStateOf(0L) }
    var showAddNote by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AnkiNote?>(null) }
    var noteMenuTarget by remember { mutableStateOf<AnkiNote?>(null) }
    val scope = rememberCoroutineScope()

    // Fold 8 展開時 (幅 >= 600dp) は 2 ペイン
    val twoPane = LocalConfiguration.current.screenWidthDp >= 600

    fun currentQuery(): String = buildList {
        selectedDeck?.let { add("deck:\"$it\"") }
        // タグは絵文字や記号を含むことがあるため必ずクォートする
        selectedTags.forEach { add("tag:\"$it\"") }
        // 1 文字違いでも拾えるよう、語を分割した OR 検索にする
        SearchRanker.keywordQuery(keyword).takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" ")

    fun search() {
        scope.launch {
            error = null
            try {
                notes = withContext(Dispatchers.IO) {
                    val found = repo.searchNotes(currentQuery())
                    SearchRanker.sort(found, effectiveSort, keyword) { repo.fieldNames(it) }
                }
                if (notes.isEmpty()) error = "該当ノートなし"
            } catch (e: Exception) {
                error = "検索失敗: ${e.message}"
            }
        }
    }

    /** 検索実行 + ソフトウェアキーボードを閉じる */
    fun submitSearch() {
        keyboard?.hide()
        focusManager.clearFocus()
        search()
    }

    fun refreshDecks() {
        scope.launch {
            decks = withContext(Dispatchers.IO) { repo.decks() }
            collectionTags = withContext(Dispatchers.IO) { repo.allTags() }
        }
    }

    /**
     * 同期の本体。
     *
     * 通常の差分同期では、先に同期した側の内容がサーバーに入り、後から同期した側は
     * その上に自分の変更を重ねる。同じノートを両方で触っていた場合は片方が上書きされるので、
     * 同期後に「自分が保存した内容」と「いま手元にある内容」を突き合わせて競合を検出する。
     *
     * quiet = true のときは進行状況を表示しない (追加・削除の裏で走らせる同期)。
     * refreshList = true のときは同期後に一覧を検索し直す。
     */
    suspend fun performSync(quiet: Boolean, refreshList: Boolean) {
        val a = auth ?: return
        if (syncing) return
        syncing = true
        // 同期の途中で新たに保存されたものを取りこぼさないよう、開始時点の分だけを見る
        val mine = pendingEdits.toMap()
        if (!quiet) syncMessage = "コレクションを同期中..."
        try {
            val result = withContext(Dispatchers.IO) { repo.syncCollection(a) }
            // 割り当てられたシャード URL を保存し、以降の同期に使う
            if (result.auth.endpoint != a.endpoint) onSetAuth(result.auth)

            if (result.required != FullSyncKind.NONE) {
                // 手元が空 (初回) なら迷う余地がないので、そのまま取り込む
                val firstTime = result.required == FullSyncKind.DOWNLOAD &&
                    notes.isEmpty() && pendingEdits.isEmpty()
                if (firstTime) {
                    if (!quiet) syncMessage = "コレクションを取得中..."
                    withContext(Dispatchers.IO) { repo.fullSync(result.auth, upload = false) }
                } else {
                    // どちらかが必ず失われるので、利用者に選んでもらう
                    fullSyncPrompt = result.required
                    syncMessage = null
                    return
                }
            }

            if (!quiet) syncMessage = "メディアを同期中... (初回は時間がかかります)"
            withContext(Dispatchers.IO) { repo.syncMedia(result.auth) }

            val clashed = withContext(Dispatchers.IO) {
                mine.mapNotNull { (id, saved) ->
                    val now = repo.noteContent(id) ?: return@mapNotNull null
                    if (now.fields != saved.fields || now.tags.trim() != saved.tags.trim()) {
                        NoteConflict(id, saved, now)
                    } else null
                }
            }
            mine.keys.forEach { pendingEdits.remove(it) }
            if (clashed.isNotEmpty()) conflicts = clashed

            syncMessage = when {
                clashed.isNotEmpty() -> "${clashed.size} 件のノートが競合しました"
                quiet -> null
                else -> "同期完了"
            }
            if (refreshList) {
                refreshDecks()
                search()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AnkiEditor", "sync failed", e)
            syncMessage = "同期失敗: ${e.message}"
        } finally {
            syncing = false
        }
    }

    fun sync() {
        if (auth == null) { showLogin = true; return }
        scope.launch { performSync(quiet = false, refreshList = true) }
    }

    /** 追加・削除のあとに走らせる、表示を伴わない同期 */
    fun syncInBackground() {
        if (auth == null) return
        scope.launch { performSync(quiet = true, refreshList = false) }
    }

    LaunchedEffect(Unit) {
        refreshDecks()
        if (auth != null) sync()
    }

    fun select(note: AnkiNote) {
        // プレビューを見るタイミングでは入力を続けないので、キーボードを引っ込める
        keyboard?.hide()
        focusManager.clearFocus()
        selected = note
        // 前のカードを表示したまま描画する。null にすると WebView が作り直されて
        // 一瞬まっ黒な画面が挟まるため、ここではクリアしない
        previewLoading = true
        renderJob?.cancel()
        renderJob = scope.launch {
            val html = withContext(Dispatchers.IO) {
                runCatching { repo.renderNote(note) }
                    .getOrElse { "<html><body style='color:#b6686f'>描画失敗: ${it.message}</body></html>" }
            }
            // 描画中に別のノートへ切り替わっていたら捨てる
            if (selected?.id == note.id) {
                previewHtml = html
                previewNoteId = note.id
                previewLoading = false
            }
        }
    }

    // 検索結果に出現したタグを頻度順で
    val tagsInResults = remember(notes) {
        notes.flatMap { it.tags.trim().split(Regex("\\s+")) }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
    }

    // 設定に出す候補: 保存済みの並び順 → ピン留め → 結果に出たもの
    val knownTags = remember(tagsInResults, tagOrder, pinnedTags) {
        val rest = (pinnedTags + tagsInResults).distinct().filter { it !in tagOrder }
        tagOrder + rest
    }

    // 検索バーに出すチップ。
    // ピン留めしたタグは検索結果に無くても常に表示し、並びは設定した順序に従う。
    val suggestedTags = remember(tagsInResults, selectedTags, tagOrder, pinnedTags) {
        val candidates = (pinnedTags + tagsInResults).distinct().filter { it !in selectedTags }
        val orderIndex = tagOrder.withIndex().associate { (i, t) -> t to i }
        candidates.sortedWith(
            compareByDescending<String> { it in pinnedTags }
                .thenBy { orderIndex[it] ?: Int.MAX_VALUE }
        ).take(30)
    }

    // 入力のたびに自動検索 (250ms デバウンス)
    LaunchedEffect(keyword, selectedDeck, selectedTags, effectiveSort) {
        delay(250)
        search()
        // 条件を変えたら、一覧は先頭から見せる
        // (前の結果でスクロールした位置が残っていると、途中から始まって戸惑う)
        listState.scrollToItem(0)
    }

    val context = LocalContext.current

    /** 選んだ画像をコレクションのメディアに登録し、使われたファイル名を返す */
    val addImage: suspend (android.net.Uri) -> String? = { uri ->
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                repo.addMedia(displayNameOf(context, uri), bytes)
            }.onFailure {
                android.util.Log.e("AnkiEditor", "add image failed", it)
            }.getOrNull()
        }
    }

    /** そのノートの内容を編集欄に読み込む */
    fun loadDraft(note: AnkiNote) {
        scope.launch {
            draft.fieldNames = withContext(Dispatchers.IO) { repo.fieldNames(note.modelId) }
            draft.fields = note.fields
            draft.tags = note.tags
            dirty = false
        }
    }

    fun startEditing() {
        val note = selected ?: return
        scope.launch {
            draft.fieldNames = withContext(Dispatchers.IO) { repo.fieldNames(note.modelId) }
            draft.fields = note.fields
            draft.tags = note.tags
            dirty = false
            editorMode = defaultEditorMode
            editing = true
        }
    }

    /** ライブプレビューを止めているときに、その場でプレビューを描き直す */
    fun refreshPreview() {
        val note = selected ?: return
        scope.launch {
            previewLoading = true
            val html = withContext(Dispatchers.IO) {
                runCatching { repo.renderDraft(note, draft.fields, draft.tags) }.getOrNull()
            }
            if (html != null && selected?.id == note.id) {
                previewHtml = html
                previewNoteId = note.id
            }
            previewLoading = false
        }
    }

    fun stopEditing() {
        editing = false
        dirty = false
        if (creating) {
            // 作りかけの新規ノートは保存せずに捨てる
            creating = false
            selected = null
            previewHtml = null
            previewNoteId = null
        } else {
            // 保存していない下書きを破棄し、保存済みの内容で描画し直す
            selected?.let { select(it) }
        }
    }

    /** 空のノートを用意して編集画面を開く (保存を押した時点で実際に追加される) */
    fun startNewNote(notetypeId: Long, deckId: Long) {
        val names = repo.fieldNames(notetypeId)
        newNoteTypeId = notetypeId
        newNoteDeckId = deckId
        draft.fieldNames = names
        draft.fields = List(names.size) { "" }
        draft.tags = defaultTags.joinToString(" ")
        selected = AnkiNote(
            id = 0L,
            modelId = notetypeId,
            fields = draft.fields,
            tags = draft.tags,
        )
        previewHtml = null
        previewNoteId = null
        creating = true
        editing = true
        dirty = true
        editorMode = defaultEditorMode
    }

    fun deleteNote(note: AnkiNote) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repo.deleteNotes(listOf(note.id)) }
                notes = notes.filterNot { it.id == note.id }
                pendingEdits.remove(note.id)
                if (selected?.id == note.id) {
                    selected = null
                    previewHtml = null
                    previewNoteId = null
                    editing = false
                }
                syncMessage = "ノートを削除しました"
                syncInBackground()
            } catch (e: Exception) {
                android.util.Log.e("AnkiEditor", "delete failed", e)
                syncMessage = "削除失敗: ${e.message}"
            }
        }
    }

    fun saveEdits() {
        // 保存ボタン・ショートカット・カード移動の自動保存が重なっても、
        // 新規ノートを複数回 add しない。
        if (saving) return
        val note = selected ?: return
        val wasCreating = creating
        val fieldsToSave = draft.fields.toList()
        val tagsToSave = draft.tags
        saving = true
        scope.launch {
            try {
                if (wasCreating) {
                    val added = withContext(Dispatchers.IO) {
                        repo.addNote(newNoteTypeId, newNoteDeckId, fieldsToSave, tagsToSave)
                    }
                    creating = false
                    dirty = false
                    notes = listOf(added) + notes
                    pendingEdits[added.id] = NoteContent(fieldsToSave, tagsToSave)
                    syncMessage = "ノートを追加しました"
                    select(added)
                    // 追加はその場で静かに同期する (進行状況は出さない)
                    syncInBackground()
                    return@launch
                }
                withContext(Dispatchers.IO) { repo.saveNote(note, fieldsToSave, tagsToSave) }
                dirty = false
                pendingEdits[note.id] = NoteContent(fieldsToSave, tagsToSave)
                syncMessage = "保存しました"
                saveTick++
                // 一覧の表示内容も更新する
                notes = notes.map {
                    if (it.id == note.id) it.copy(fields = fieldsToSave, tags = tagsToSave) else it
                }
                if (selected?.id == note.id) {
                    val saved = notes.firstOrNull { it.id == note.id } ?: note
                    selected = saved
                    // 保存した内容でプレビューを描き直す
                    // (手動更新のときは、ここで初めて画面に反映される)
                    select(saved)
                }
            } catch (e: Exception) {
                android.util.Log.e("AnkiEditor", "save failed", e)
                syncMessage = "保存失敗: ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    // 保存したら自動で AnkiWeb に同期する。
    // 連続して保存したときに何度も走らないよう、少し待ってからまとめて実行する
    LaunchedEffect(saveTick) {
        // 効果の中で合図の値を書き換えると自分自身が再起動されてしまうので、
        // ここでは saveTick を触らない
        if (saveTick == 0) return@LaunchedEffect
        delay(1500)
        if (auth == null) return@LaunchedEffect
        // 編集中に一覧が並び替わらないよう、ここでは検索し直さない
        performSync(quiet = false, refreshList = false)
    }

    // 打鍵に追従したプレビュー更新は、負荷に見合うときだけ行う。
    //  ・スマホ幅ではプレビューが同時に見えないので不要
    //  ・本文が長いと 1 文字ごとの再描画が編集の重さに直結する
    // 下書きの中身はこの効果の中だけで読む (画面の組み立て中に読むと全体が作り直される)
    LaunchedEffect(editing, twoPane) {
        if (!editing) {
            livePreview = twoPane
            return@LaunchedEffect
        }
        snapshotFlow { draft.fields to draft.tags }
            .collectLatest { (fields, tags) ->
                val size = fields.sumOf { it.length }
                val live = twoPane && size <= LIVE_PREVIEW_LIMIT
                livePreview = live
                if (!live) return@collectLatest
                val note = selected ?: return@collectLatest
                delay(if (size > 4000) 900L else 350L)
                val rendered = withContext(Dispatchers.IO) {
                    runCatching { repo.renderDraft(note, fields, tags) }.getOrNull()
                } ?: return@collectLatest
                if (selected?.id == note.id) {
                    previewHtml = rendered
                    previewNoteId = note.id
                }
            }
    }

    // 成功時の同期メッセージは表示しっぱなしにせず、しばらくしたら消す
    LaunchedEffect(syncMessage, syncing) {
        val m = syncMessage
        if (!syncing && m != null && !m.startsWith("同期失敗")) {
            delay(2500)
            syncMessage = null
        }
    }

    val pill = RoundedCornerShape(50)

    val searchControls: @Composable () -> Unit = {
        Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Material3 のテキストフィールドは最小幅 280dp を持つため、
                // 狭いカラムだとはみ出して負の幅を生む。Box で幅を確定させてから埋める
                // 高さを詰めても文字が見切れないよう、内側の余白まで指定できる
                // DecorationBox 版を使う (通常の OutlinedTextField は最小 56dp)
                val interaction = remember { MutableInteractionSource() }
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                            .copy(color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = interaction,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        decorationBox = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = keyword,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = interaction,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                placeholder = {
                                    Text(
                                        "キーワードで検索",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                trailingIcon = {
                                    if (keyword.isNotEmpty()) {
                                        IconButton(
                                            onClick = { keyword = "" },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "クリア",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                                container = {
                                    OutlinedTextFieldDefaults.Container(
                                        enabled = true,
                                        isError = false,
                                        interactionSource = interaction,
                                        shape = pill,
                                    )
                                },
                            )
                        },
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "設定",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(end = 12.dp),
                // 検索欄とタグの間に少しだけ余白を取る
                modifier = Modifier.padding(top = 8.dp).fadingRightEdge(16.dp),
            ) {
                item {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        FilterChip(
                            selected = selectedDeck != null,
                            onClick = { open = true },
                            shape = pill,
                            modifier = Modifier.height(32.dp),
                            label = {
                                Text(
                                    selectedDeck ?: "すべてのデッキ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(
                                text = { Text("すべてのデッキ") },
                                onClick = { selectedDeck = null; open = false },
                            )
                            decks.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(d) },
                                    onClick = { selectedDeck = d; open = false },
                                )
                            }
                        }
                    }
                }
                items(selectedTags.toList(), key = { "sel-$it" }) { tag ->
                    InputChip(
                        selected = true,
                        onClick = { selectedTags = selectedTags - tag; search() },
                        shape = pill,
                        modifier = Modifier.height(32.dp),
                        label = {
                            Text(formatTag(tag), style = MaterialTheme.typography.labelMedium)
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "タグを外す",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
                items(suggestedTags, key = { "sug-$it" }) { tag ->
                    SuggestionChip(
                        onClick = { selectedTags = selectedTags + tag; search() },
                        shape = pill,
                        modifier = Modifier.height(32.dp),
                        label = {
                            Text(formatTag(tag), style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
        }
    }

    // 結果一覧のヘッダー。件数と並び替えを、絞り込みチップとは別の行に分ける
    val resultHeader: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 検索のエラーもこの固定高さの行に出す (行が増減してレイアウトが動かないように)
            Text(
                error ?: if (notes.isEmpty()) "" else "${notes.size} 件",
                style = MaterialTheme.typography.labelMedium,
                color = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            var openSort by remember { mutableStateOf(false) }
            Box {
                TextButton(
                    onClick = { openSort = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(effectiveSort.label, style = MaterialTheme.typography.labelMedium)
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = openSort, onDismissRequest = { openSort = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            trailingIcon = {
                                if (mode == effectiveSort) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                manualSort = mode
                                if (!hasKeyword) onSetSortMode(mode)
                                openSort = false
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }

    // ---- 新しいノートを追加 ----
    if (showAddNote) {
        var types by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
        var typeId by remember { mutableLongStateOf(0L) }
        var deckName by remember { mutableStateOf(selectedDeck ?: defaultDeck) }
        var fallbackDeckId by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            types = withContext(Dispatchers.IO) { repo.notetypes() }
            val defaults = withContext(Dispatchers.IO) { repo.addingDefaults() }
            fallbackDeckId = defaults?.first ?: 0L
            typeId = defaults?.second ?: types.firstOrNull()?.first ?: 0L
        }
        AlertDialog(
            onDismissRequest = { showAddNote = false },
            title = { Text("新しいノート") },
            text = {
                Column {
                    Text("ノートタイプ", style = MaterialTheme.typography.labelMedium)
                    var openType by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { openType = true }, shape = RoundedCornerShape(50)) {
                            Text(
                                types.firstOrNull { it.first == typeId }?.second ?: "読み込み中...",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = openType, onDismissRequest = { openType = false }) {
                            types.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { typeId = id; openType = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("デッキ", style = MaterialTheme.typography.labelMedium)
                    var openDeck by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { openDeck = true }, shape = RoundedCornerShape(50)) {
                            Text(
                                deckName ?: "既定のデッキ",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = openDeck, onDismissRequest = { openDeck = false }) {
                            DropdownMenuItem(
                                text = { Text("既定のデッキ") },
                                onClick = { deckName = null; openDeck = false },
                            )
                            decks.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { deckName = name; openDeck = false },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typeId != 0L,
                    onClick = {
                        val id = deckName?.let { repo.deckId(it) } ?: fallbackDeckId
                        showAddNote = false
                        startNewNote(typeId, id)
                    },
                ) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showAddNote = false }) { Text("キャンセル") }
            },
        )
    }

    // ---- 一覧を長押ししたときの操作 ----
    noteMenuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { noteMenuTarget = null },
            title = {
                Text(
                    SearchRanker.title(target).take(80).ifBlank { "(空)" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column {
                    TextButton(onClick = {
                        noteMenuTarget = null
                        select(target)
                        startEditing()
                    }) { Text("編集") }
                    TextButton(onClick = {
                        deleteTarget = target
                        noteMenuTarget = null
                    }) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { noteMenuTarget = null }) { Text("閉じる") }
            },
        )
    }

    // ---- ノートの削除は必ず確認を挟む ----
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("ノートを削除") },
            text = {
                Column {
                    Text("このノートを削除します。取り消せません。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        SearchRanker.title(target).take(160).ifBlank { "(空)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    deleteNote(target)
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
            },
        )
    }

    // ---- フル同期が必要になった (= 構造がずれた) ときの確認 ----
    fullSyncPrompt?.let { kind ->
        AlertDialog(
            onDismissRequest = { fullSyncPrompt = null },
            title = { Text("同期の競合") },
            text = {
                Text(
                    if (kind == FullSyncKind.DOWNLOAD) {
                        "他の端末での変更が大きく、コレクション全体の取り込みが必要です。" +
                            "取り込むと、この端末のまだ同期していない変更は失われます。"
                    } else {
                        "この端末の変更が大きく、コレクション全体の送信が必要です。" +
                            "送信すると、サーバー側 (他の端末) のまだ取り込んでいない変更は失われます。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = auth
                    fullSyncPrompt = null
                    if (a != null) scope.launch {
                        syncing = true
                        syncMessage = if (kind == FullSyncKind.DOWNLOAD) "取り込み中..." else "送信中..."
                        try {
                            withContext(Dispatchers.IO) {
                                repo.fullSync(a, upload = kind == FullSyncKind.UPLOAD)
                            }
                            withContext(Dispatchers.IO) { repo.syncMedia(a) }
                            pendingEdits.clear()
                            syncMessage = "同期完了"
                            refreshDecks()
                            search()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            syncMessage = "同期失敗: ${e.message}"
                        } finally {
                            syncing = false
                        }
                    }
                }) {
                    Text(if (kind == FullSyncKind.DOWNLOAD) "サーバーを優先" else "この端末を優先")
                }
            },
            dismissButton = {
                TextButton(onClick = { fullSyncPrompt = null }) { Text("あとで") }
            },
        )
    }

    // ---- ノート単位の競合。どちらを採るかを 1 件ずつ選ぶ ----
    if (conflicts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { conflicts = emptyList() },
            title = { Text("${conflicts.size} 件のノートが競合しました") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "他の端末の編集が先に同期されていたため、この端末で保存した内容は反映されていません。" +
                            "この端末の内容で上書きするノートを選んでください。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    conflicts.forEach { c ->
                        val serverText = SearchRanker
                            .title(AnkiNote(c.noteId, 0L, c.server.fields, c.server.tags))
                        val mineText = SearchRanker
                            .title(AnkiNote(c.noteId, 0L, c.mine.fields, c.mine.tags))
                        Text(
                            serverText.take(80).ifBlank { "(空)" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "この端末: " + mineText.take(60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = {
                                conflicts = conflicts.filterNot { it.noteId == c.noteId }
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            repo.saveNote(
                                                AnkiNote(c.noteId, 0L, c.mine.fields, c.mine.tags),
                                                c.mine.fields,
                                                c.mine.tags,
                                            )
                                        }
                                    }
                                    notes = notes.map {
                                        if (it.id == c.noteId) {
                                            it.copy(fields = c.mine.fields, tags = c.mine.tags)
                                        } else it
                                    }
                                    selected?.takeIf { it.id == c.noteId }?.let { select(it) }
                                    pendingEdits[c.noteId] = c.mine
                                    saveTick++
                                }
                            }) { Text("この端末の内容にする") }
                            TextButton(onClick = {
                                conflicts = conflicts.filterNot { it.noteId == c.noteId }
                            }) { Text("サーバーのまま") }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { conflicts = emptyList() }) { Text("閉じる") }
            },
        )
    }

    if (showLogin) {
        LoginDialog(
            repo = repo,
            onDismiss = { showLogin = false },
            onLoggedIn = { a ->
                onSetAuth(a)
                showLogin = false
                sync()
            },
        )
    }

    // 設定の中身。幅の広い画面ではダイアログ、折りたたみ時は全画面で表示する
    // 設定は Notion のように「一覧 → 詳細」で辿る

    // 編集ページの内容を、いますぐ受け取る (保存の直前に使う)
    var flushEditor by remember { mutableStateOf({ }) }

    // ---- ハードウェアキーボードのショートカット ----
    // Ctrl+F 検索 (編集中はエディタ内) / Ctrl+S 保存
    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity, editing, dirty, saving) {
        activity?.shortcutHandler = { event ->
            when {
                !event.isCtrlPressed -> false
                event.keyCode == android.view.KeyEvent.KEYCODE_F -> {
                    if (editing) editorFindRequest++ else previewFindRequest++
                    true
                }

                event.keyCode == android.view.KeyEvent.KEYCODE_S -> {
                    android.util.Log.d(
                        "AnkiEditorKeys",
                        "Ctrl+S 編集中=$editing 変更あり=$dirty 保存中=$saving",
                    )
                    // ページ側の入力はまとめて伝えているので、保存の前に確定させる
                    if (editing && !saving) {
                        flushEditor()
                        scope.launch {
                            delay(120)
                            if (dirty) saveEdits()
                        }
                    }
                    true
                }

                else -> false
            }
        }
        onDispose { activity?.shortcutHandler = null }
    }

    // 同期などの知らせは、端末の標準の出し方 (下中央のトースト) に合わせる。
    // 続けて出るときは前のものを消して、重ならないようにする
    val toastContext = LocalContext.current
    val toast = remember { arrayOfNulls<android.widget.Toast>(1) }
    LaunchedEffect(syncMessage) {
        val m = syncMessage ?: return@LaunchedEffect
        toast[0]?.cancel()
        toast[0] = android.widget.Toast
            .makeText(toastContext, m, android.widget.Toast.LENGTH_SHORT)
            .also { it.show() }
    }

    // 戻るジェスチャで 設定 → 編集 → プレビュー の順に 1 つずつ戻る
    BackHandler(enabled = showSettings || editing || (!twoPane && selected != null)) {
        when {
            showSettings -> showSettings = false
            editing -> stopEditing()
            else -> selected = null
        }
    }

    val settingsOverlay: @Composable () -> Unit = {
        SettingsScreen(
            twoPane = twoPane,
            onBack = { showSettings = false },
            backHandler = { onBack -> BackHandler(enabled = true) { onBack() } },
            summaryOf = { section ->
                when (section) {
                    SettingsSection.ACCOUNT -> if (auth != null) "ログイン済み" else "未ログイン"
                    SettingsSection.SEARCH ->
                        listOfNotNull(
                            defaultDeck ?: "すべてのデッキ",
                            sortMode.label,
                        ).joinToString(" · ")
                    SettingsSection.TAGS ->
                        if (pinnedTags.isEmpty()) "未設定" else pinnedTags.joinToString(" ") { formatTag(it) }
                    SettingsSection.EDITOR -> "${defaultEditorMode.label} · 画像 ${defaultImageCorner.label}"
                    SettingsSection.TEMPLATE -> "表面・裏面・スタイル"
                    SettingsSection.MEDIA -> "未使用の画像・動画・音声を削除"
                    SettingsSection.APPEARANCE -> "文字サイズ スマホ $textZoomCompact% · 広い $textZoomWide%"
                }
            },
            detail = { section ->
                when (section) {
                    SettingsSection.ACCOUNT -> {
                        SettingsLabel(
                            "AnkiWeb アカウント",
                            if (auth != null) "ログイン済み" else "未ログイン (カードと画像の同期に必要)",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showLogin = true }, shape = pill) {
                                Text(if (auth != null) "ログインし直す" else "ログイン")
                            }
                            if (auth != null) {
                                Button(
                                    onClick = { showSettings = false; sync() },
                                    shape = pill,
                                    enabled = !syncing,
                                ) { Text("今すぐ同期") }
                            }
                        }
                    }

                    SettingsSection.SEARCH -> {
                        SettingsLabel("起動時の検索条件")
                        SettingsPicker(
                            label = "デフォルトのデッキ",
                            current = defaultDeck,
                            options = listOf<String?>(null) + decks,
                            labelOf = { it ?: "すべてのデッキ" },
                            onSelect = { onSetDefaultDeck(it); selectedDeck = it },
                        )
                        HorizontalDivider()
                        SettingsPicker(
                            label = "デフォルトの並び順",
                            current = sortMode,
                            options = SortMode.entries,
                            labelOf = { it.label },
                            onSelect = { onSetSortMode(it) },
                        )
                        Spacer(Modifier.height(16.dp))
                        SettingsLabel(
                            "デフォルトの検索タグ",
                            "起動時にこのタグで絞り込んだ状態から始まります",
                        )
                        if (knownTags.isEmpty()) {
                            Text(
                                "タグがまだありません (検索するとここに出ます)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            knownTags.forEach { tag ->
                                ListItem(
                                    headlineContent = {
                                        Text(formatTag(tag), style = MaterialTheme.typography.bodyMedium)
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = tag in defaultTags,
                                            onCheckedChange = { checked ->
                                                val next =
                                                    if (checked) defaultTags + tag else defaultTags - tag
                                                onSetDefaultTags(next)
                                                selectedTags =
                                                    if (checked) selectedTags + tag else selectedTags - tag
                                            },
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val checked = tag !in defaultTags
                                        val next =
                                            if (checked) defaultTags + tag else defaultTags - tag
                                        onSetDefaultTags(next)
                                        selectedTags =
                                            if (checked) selectedTags + tag else selectedTags - tag
                                    },
                                )
                            }
                        }
                    }

                    SettingsSection.TAGS -> {
                        SettingsLabel(
                            "タグの表示と並び順",
                            "★ を付けたタグは検索結果に無くても常に表示されます。長押しドラッグ、または▲▼で並び替え。",
                        )
                        if (knownTags.isEmpty()) {
                            Text(
                                "タグがまだありません (検索するとここに出ます)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TagReorderList(
                                tags = knownTags,
                                pinned = pinnedTags,
                                onReorder = onSetTagOrder,
                                onTogglePin = { tag ->
                                    onSetPinnedTags(
                                        if (tag in pinnedTags) pinnedTags - tag else pinnedTags + tag
                                    )
                                },
                            )
                        }
                    }

                    SettingsSection.EDITOR -> {
                        SettingsLabel("挿入する画像の既定の角")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(40.dp)) {
                            ImageCorner.entries.forEachIndexed { index, c ->
                                SegmentedButton(
                                    selected = defaultImageCorner == c,
                                    onClick = { onSetDefaultImageCorner(c) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index, ImageCorner.entries.size
                                    ),
                                    icon = {},
                                    label = {
                                        Text(
                                            c.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        SettingsLabel("既定の入力方式")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(40.dp)) {
                            EditorMode.entries.forEachIndexed { index, m ->
                                SegmentedButton(
                                    selected = defaultEditorMode == m,
                                    onClick = { onSetDefaultEditorMode(m) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index, EditorMode.entries.size
                                    ),
                                    icon = {},
                                    label = {
                                        Text(
                                            m.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    SettingsSection.TEMPLATE -> {
                        TemplateEditor(
                            repo = repo,
                            onSaved = { message ->
                                syncMessage = message
                                // 見た目が変わるので、開いているプレビューを描き直す
                                selected?.let { select(it) }
                                saveTick++
                            },
                        )
                    }

                    SettingsSection.MEDIA -> {
                        MediaCleanupPane(
                            repo = repo,
                            onDeleted = { message -> syncMessage = message },
                            thumbnail = { name, kind, size, onClick ->
                                MediaThumb(File(repo.mediaDir, name), kind, size, onClick)
                            },
                            preview = { item, onDismiss ->
                                MediaPreviewDialog(File(repo.mediaDir, item.name), item, onDismiss)
                            },
                        )
                    }

                    SettingsSection.APPEARANCE -> {
                        SettingsLabel(
                            "プレビューの文字サイズ",
                            "カードの CSS と同じ 481px を境に、狭い表示と広い表示で別々に設定できます",
                        )
                        Text(
                            "スマホ幅 (481px 未満) — $textZoomCompact%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = textZoomCompact.toFloat(),
                            onValueChange = { onSetTextZoomCompact(it.roundToInt()) },
                            valueRange = 60f..140f,
                            steps = 15,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "広い表示 (481px 以上) — $textZoomWide%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = textZoomWide.toFloat(),
                            onValueChange = { onSetTextZoomWide(it.roundToInt()) },
                            valueRange = 60f..140f,
                            steps = 15,
                        )
                    }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
    // スマホ幅でプレビューを開いている間は、時刻などが並ぶ上端の帯も
    // カードの地色にそろえて、画面をひと続きに見せる
    val previewFullScreen = !twoPane && selected != null && !editing
    val shellColor by animateColorAsState(
        targetValue = if (previewFullScreen) CARD_BACKGROUND else MaterialTheme.colorScheme.background,
        animationSpec = tween(220),
        label = "shellColor",
    )
    Scaffold(containerColor = shellColor) { padding ->
        if (twoPane) {
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val total = maxWidth
                val totalWidthPx = with(LocalDensity.current) { total.toPx() }
                // 編集に入ると検索列が左へ引っ込み、右から編集列が出てくる。
                // 中身は常に本来の幅で測り、外側の箱だけを縮めて切り取る
                // (幅 0 で測ると子要素が負の幅を要求してクラッシュするため)
                // 幅を 0 までアニメーションさせると中の要素が負の幅を要求してしまうため、
                // AnimatedVisibility で出し入れする (非表示時は測定自体が行われない)
                val listTarget = (total * splitFraction).coerceAtLeast(200.dp)
                val editorTarget = (total * editorFraction).coerceAtLeast(280.dp)
                Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = !editing,
                        // 中身も一緒に動く push 型 (領域だけ開く reveal ではない)
                        enter = expandHorizontally(expandFrom = Alignment.Start) +
                            slideInHorizontally { -it },
                        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) +
                            slideOutHorizontally { -it },
                    ) {
                        Column(Modifier.width(listTarget).fillMaxHeight()) {
                            searchControls()
                            resultHeader()
                            NoteList(
                                notes,
                                selected,
                                // 編集中でも別のノートへ移れる。書きかけは先に保存する
                                onSelect = { note ->
                                    if (editing && dirty) saveEdits()
                                    select(note)
                                    if (editing && !creating) loadDraft(note)
                                },
                                state = listState,
                                modifier = Modifier.fillMaxSize().fadingRightEdge(),
                                onLongPress = { noteMenuTarget = it },
                            )
                        }
                    }
                    if (!editing) {
                        SplitHandle(
                            onDrag = { delta ->
                                val next = (splitFraction + delta / totalWidthPx)
                                    .coerceIn(0.25f, 0.75f)
                                onSetSplitFraction(next)
                            },
                        )
                    }
                    PreviewPane(
                        selected, previewHtml.takeIf { previewNoteId == selected?.id }, previewLoading, repo.mediaDir, textZoomCompact, textZoomWide,
                        Modifier.weight(1f).fillMaxHeight(),
                        onEdit = if (selected != null && !editing) ::startEditing else null,
                        onAdd = if (editing) null else ({ showAddNote = true }),
                        // 手動更新に切り替わっているときは、プレビュー側からも更新できるように
                        onRefresh = if (editing && !livePreview) ({ refreshPreview() }) else null,
                        // 編集していないときはカード内検索を出す
                        findable = selected != null && !editing,
                        findRequest = previewFindRequest,
                    )
                    if (editing) {
                        SplitHandle(
                            onDrag = { delta ->
                                // 左へドラッグすると編集列が広がる。
                                // プレビューは最低 30% を確保する
                                val next = (editorFraction - delta / totalWidthPx)
                                    .coerceIn(0.25f, 0.70f)
                                onSetEditorFraction(next)
                            },
                        )
                    }
                    AnimatedVisibility(
                        visible = editing && selected != null,
                        // 右から押し出して入ってくる
                        enter = expandHorizontally(expandFrom = Alignment.End) +
                            slideInHorizontally { it },
                        exit = shrinkHorizontally(shrinkTowards = Alignment.End) +
                            slideOutHorizontally { it },
                    ) {
                        Row(Modifier.width(editorTarget).fillMaxHeight()) {
                            selected?.let { note ->
                                EditorPane(
                                    note = note,
                                    draft = draft,
                                    allTags = collectionTags,
                                    mode = editorMode,
                                    defaultImageCorner = defaultImageCorner,
                                    dirty = dirty,
                                    saving = saving,
                                    mediaDir = repo.mediaDir,
                                    addImage = addImage,
                                    onDirty = { dirty = true },
                                    onModeChange = { editorMode = it },
                                    onFocusedFieldChange = { editingField = it },
                                    onSave = { saveEdits() },
                                    onClose = { stopEditing() },
                                    onRefreshPreview =
                                        if (livePreview) null else ({ refreshPreview() }),
                                    findRequest = editorFindRequest,
                                    onProvideFlush = { flushEditor = it },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // スマホ幅では 一覧 → プレビュー → 編集 と全画面で切り替える
            val phase = when {
                editing && selected != null -> 2
                selected != null -> 1
                else -> 0
            }
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "phones",
            ) { p ->
                when (p) {
                    0 -> Box(Modifier.padding(padding).fillMaxSize()) {
                        Column {
                            searchControls()
                            resultHeader()
                            NoteList(
                                notes, null, ::select, listState, Modifier.fillMaxSize(),
                                onLongPress = { noteMenuTarget = it },
                            )
                        }
                        AddNoteFab(
                            onClick = { showAddNote = true },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                    }

                    1 -> Box(Modifier.padding(padding).fillMaxSize()) {
                        PreviewPane(
                            selected, previewHtml.takeIf { previewNoteId == selected?.id }, previewLoading, repo.mediaDir,
                            textZoomCompact, textZoomWide,
                            Modifier.fillMaxSize(),
                            onEdit = ::startEditing,
                            onAdd = { showAddNote = true },
                            findable = selected != null,
                            findRequest = previewFindRequest,
                        )
                        SmallFloatingActionButton(
                            onClick = { selected = null },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 80.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "一覧に戻る",
                            )
                        }
                    }

                    else -> selected?.let { note ->
                        EditorPane(
                            note = note,
                            draft = draft,
                            allTags = collectionTags,
                            mode = editorMode,
                            defaultImageCorner = defaultImageCorner,
                            dirty = dirty,
                            saving = saving,
                            mediaDir = repo.mediaDir,
                            addImage = addImage,
                            onDirty = { dirty = true },
                            onModeChange = { editorMode = it },
                            onFocusedFieldChange = { editingField = it },
                            onSave = { saveEdits() },
                            onClose = { stopEditing() },
                            onRefreshPreview = null,
                            findRequest = editorFindRequest,
                            onProvideFlush = { flushEditor = it },
                            modifier = Modifier.padding(padding).fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    // 設定を開くときはフェードで重ねる (中の一覧 → 詳細だけ横スライド)
    AnimatedVisibility(
        visible = showSettings,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(Modifier.fillMaxSize()) { settingsOverlay() }
    }
    }
}

@Composable
fun LoginDialog(
    repo: AnkiBackendRepo,
    onDismiss: () -> Unit,
    onLoggedIn: (SyncAuth) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("AnkiWeb にログイン") },
        text = {
            Column {
                Text(
                    "カードと画像の同期に使用します。認証情報は端末内にのみ保存されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("メールアドレス") },
                    singleLine = true,
                    enabled = !busy,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("パスワード") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                )
                err?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        err = null
                        try {
                            val a = withContext(Dispatchers.IO) { repo.login(username, password) }
                            onLoggedIn(a)
                        } catch (e: Exception) {
                            err = "ログイン失敗: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "ログイン中..." else "ログイン") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("キャンセル") }
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun NoteList(
    notes: List<AnkiNote>,
    selected: AnkiNote?,
    onSelect: (AnkiNote) -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
    onLongPress: (AnkiNote) -> Unit = {},
) {
    LazyColumn(modifier, state = state) {
        items(notes, key = { it.id }) { note ->
            // 見出しの <sup> や <br>、&nbsp; などは見た目に反映する
            val title = remember(note.id, note.fields) { noteTitle(note) }
            ListItem(
                headlineContent = {
                    Text(
                        if (title.isEmpty()) androidx.compose.ui.text.AnnotatedString("(空)") else title,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        style = MaterialTheme.typography.bodySmall.copy(
                            // word-break: normal 相当。単語の途中で切らずに折り返す
                            lineBreak = LineBreak.Paragraph,
                            // 和文の字間を詰める (CSS の font-feature-settings: "palt")
                            fontFeatureSettings = "palt",
                        ),
                        fontWeight = if (note.id == selected?.id) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                supportingContent = {
                    val tags = note.tags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (tags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 7.dp),
                        ) {
                            tags.take(4).forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        formatTag(tag),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                // 長押しで削除などの操作を出す
                modifier = Modifier.combinedClickable(
                    onClick = { onSelect(note) },
                    onLongClick = { onLongPress(note) },
                ),
            )
            HorizontalDivider()
        }
    }
}

/**
 * 2 列カラムの境界。掴んで動かせることが分かるよう、
 * 縦線の中央にグリップ (丸いつまみ) を重ねて表示する。
 */
@Composable
fun SplitHandle(onDrag: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val handleColor =
        if (dragging) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline

    Box(
        Modifier
            .fillMaxHeight()
            .width(20.dp)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onDrag(it) },
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(thickness = 1.dp)
        Surface(
            shape = RoundedCornerShape(50),
            color = handleColor,
            shadowElevation = 2.dp,
            modifier = Modifier.width(6.dp).height(48.dp),
        ) {}
        // グリップの点 (3 点) でつまめることを示す
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(2.dp),
                ) {}
            }
        }
    }
}

/** 長押しドラッグ、または ▲▼ ボタンで並び替えできるタグリスト。★ で常時表示を切り替える */
@Composable
fun TagReorderList(
    tags: List<String>,
    pinned: List<String>,
    onReorder: (List<String>) -> Unit,
    onTogglePin: (String) -> Unit,
) {
    val itemHeight = 44.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    // 並び替え中は外から渡される tags でリセットしないよう、初回だけ取り込む
    var list by remember { mutableStateOf(tags) }
    LaunchedEffect(tags) {
        if (tags.toSet() != list.toSet()) {
            list = list.filter { it in tags } + tags.filter { it !in list }
        }
    }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun move(from: Int, to: Int) {
        if (to !in list.indices) return
        list = list.toMutableList().also { it.add(to, it.removeAt(from)) }
        onReorder(list)
    }

    Column {
        list.forEachIndexed { index, tag ->
            val dragging = index == dragIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .pointerInput(index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragIndex = index; dragOffset = 0f },
                            onDragEnd = { dragIndex = -1; dragOffset = 0f },
                            onDragCancel = { dragIndex = -1; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val shift = (dragOffset / itemHeightPx).roundToInt()
                                val target = (dragIndex + shift).coerceIn(0, list.lastIndex)
                                if (target != dragIndex) {
                                    dragOffset -= (target - dragIndex) * itemHeightPx
                                    move(dragIndex, target)
                                    dragIndex = target
                                }
                            },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTag(tag),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onTogglePin(tag) }) {
                    if (tag in pinned) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "常時表示を解除",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(Icons.Outlined.StarOutline, contentDescription = "常時表示にする")
                    }
                }
                IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上へ")
                }
                IconButton(onClick = { move(index, index + 1) }, enabled = index < list.lastIndex) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下へ")
                }
            }
        }
    }
}

/** ノートを新しく作るボタン。編集ボタンと同じ形・大きさで並べる */
@Composable
fun AddNoteFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
        modifier = modifier.size(48.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = "新しいノート", modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PreviewPane(
    note: AnkiNote?,
    html: String?,
    loading: Boolean,
    mediaDir: File,
    zoomCompact: Int,
    zoomWide: Int,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    findable: Boolean = false,
    /** Ctrl+F が押された回数。増えたら検索バーを開く */
    findRequest: Int = 0,
) {
    val dirState = rememberUpdatedState(mediaDir)
    // プレビュー内検索
    var findOpen by remember(note?.id) { mutableStateOf(false) }
    var findQuery by remember(note?.id) { mutableStateOf("") }
    var findIndex by remember(note?.id) { mutableIntStateOf(0) }
    var findTotal by remember(note?.id) { mutableIntStateOf(0) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val findFieldFocus = remember { FocusRequester() }
    LaunchedEffect(findRequest) {
        if (findRequest > 0 && findable) {
            findOpen = true
            runCatching { findFieldFocus.requestFocus() }
        }
    }
    // カードと同じ背景色を敷いておく。WebView の初期描画前に黒が見えるのを防ぐ
    BoxWithConstraints(modifier.fillMaxHeight().background(CARD_BACKGROUND)) {
        // カードの CSS が @media (min-width: 481px) で切り替わるため、
        // 同じ境目で文字サイズの設定も切り替える
        val textZoom = if (maxWidth < ANKI_COMPACT_BREAKPOINT) zoomCompact else zoomWide
        // WebView は常に置いておく (作り直すと初期描画で黒が見える)。
        // 読み込みが終わるまでは透明にして、カード色の背景だけを見せる
        // フェードするのはノートを切り替えたときだけ。
        // 編集中の再描画のたびに透過させると、打鍵のたびにちらついてしまう
        var pageReady by remember { mutableStateOf(false) }
        LaunchedEffect(note?.id) { pageReady = false }
        val contentAlpha by animateFloatAsState(
            targetValue = if (pageReady && html != null) 1f else 0f,
            label = "previewAlpha",
        )

        // 同じノートを編集している間は読み込み直さず中身だけ差し替える。
        // 再読み込みするとスクロール位置が先頭に戻ってしまうため
        val loadedNote = remember { longArrayOf(-1L) }

        AndroidView(
            modifier = Modifier.fillMaxSize().alpha(contentAlpha),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setBackgroundColor(CARD_BACKGROUND.toArgb())
                    webViewClient = MediaWebViewClient(
                        ctx,
                        { dirState.value },
                        onPageReady = { web ->
                            pageReady = true
                            // 検索用の関数を読み込み直しのたびに入れ直す
                            web.evaluateJavascript(PreviewFindJs.script, null)
                        },
                    )
                    webRef = this
                }
            },
            update = { web ->
                web.settings.textZoom = textZoom
                if (html == null) return@AndroidView
                val id = note?.id ?: -1L
                when {
                    loadedNote[0] != id -> {
                        // 別のノートに切り替わったときだけ読み込み直す
                        loadedNote[0] = id
                        web.tag = html
                        web.loadDataWithBaseURL(
                            CardRenderer.MEDIA_BASE_URL, html, "text/html", "utf-8", null
                        )
                    }

                    web.tag != html -> {
                        web.tag = html
                        web.evaluateJavascript(replaceBodyScript(html), null)
                    }
                }
            },
        )

        if (note == null) {
            Text(
                "ノートを選択するとここに実レンダリングが表示されます",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // プレビュー内検索のバー。カードの上に重ねて出す
        AnimatedVisibility(
            visible = findOpen,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                // カードの上に浮いて見えるよう、影を強めに落とす
                shadowElevation = 10.dp,
                tonalElevation = 3.dp,
                modifier = Modifier.padding(12.dp),
            ) {
                Row(
                    Modifier.height(52.dp).padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = findQuery,
                        onValueChange = { q ->
                            findQuery = q
                            val web = webRef
                            if (q.isBlank()) {
                                findTotal = 0
                                findIndex = 0
                                web?.evaluateJavascript("window.__ankiFind.clear()", null)
                            } else {
                                web?.evaluateJavascript(
                                    "window.__ankiFind.search(${JSONObject.quote(q)})"
                                ) { result ->
                                    findTotal = result?.trim()?.toIntOrNull() ?: 0
                                    findIndex = if (findTotal == 0) 0 else 1
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelLarge
                            .copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.width(150.dp).focusRequester(findFieldFocus),
                        decorationBox = { inner ->
                            if (findQuery.isEmpty()) {
                                Text(
                                    "カード内を検索",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.55f),
                                    maxLines = 1,
                                )
                            }
                            inner()
                        },
                    )
                    Text(
                        if (findQuery.isBlank()) "" else "$findIndex/$findTotal",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    IconButton(
                        onClick = {
                            webRef?.evaluateJavascript("window.__ankiFind.move(-1)") { r ->
                                findIndex = r?.trim()?.toIntOrNull() ?: findIndex
                            }
                        },
                        enabled = findTotal > 0,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前へ")
                    }
                    IconButton(
                        onClick = {
                            webRef?.evaluateJavascript("window.__ankiFind.move(1)") { r ->
                                findIndex = r?.trim()?.toIntOrNull() ?: findIndex
                            }
                        },
                        enabled = findTotal > 0,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "次へ")
                    }
                    IconButton(
                        onClick = {
                            findOpen = false
                            findQuery = ""
                            findTotal = 0
                            webRef?.evaluateJavascript("window.__ankiFind.clear()", null)
                        },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "検索を閉じる")
                    }
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }
        // 追加・編集ボタン。カードの上に右下で浮かせる (追加は編集の左隣)
        if (onEdit != null || onAdd != null || onRefresh != null || findable) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                if (onRefresh != null) {
                    SmallFloatingActionButton(
                        onClick = onRefresh,
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "プレビューを更新")
                    }
                }
                if (onAdd != null) AddNoteFab(onAdd)
                // カード内検索。追加と編集の間に、同じ大きさで並べる
                if (findable) {
                    FloatingActionButton(
                        onClick = {
                            findOpen = !findOpen
                            if (!findOpen) {
                                findQuery = ""
                                findTotal = 0
                                findIndex = 0
                                webRef?.evaluateJavascript("window.__ankiFind.clear()", null)
                            }
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "このカードを検索",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (onEdit != null) {
                    FloatingActionButton(
                        onClick = onEdit,
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "編集",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 描画済み HTML の body だけを差し替えるスクリプトを作る。
 * innerHTML では <script> が実行されないので、入れ直して実行し直す。
 * 数式も組版し直す。
 */
private fun replaceBodyScript(html: String): String {
    val marker = "<body class=\"card\">"
    val start = html.indexOf(marker)
    val body = if (start < 0) html else {
        val from = start + marker.length
        val end = html.lastIndexOf("</body>")
        if (end > from) html.substring(from, end) else html.substring(from)
    }
    return "(function(){" +
        "document.body.innerHTML=" + org.json.JSONObject.quote(body) + ";" +
        "document.body.querySelectorAll('script').forEach(function(o){" +
        "var s=document.createElement('script');s.textContent=o.textContent;" +
        "o.parentNode.replaceChild(s,o);});" +
        "if(window.MathJax&&MathJax.typesetPromise){MathJax.typesetPromise();}" +
        "})()"
}

/** これを超える本文では、打鍵ごとのプレビュー更新をやめる */
private const val LIVE_PREVIEW_LIMIT = 12000

/** カードテンプレート側の body 背景と同じ色 */
private val CARD_BACKGROUND = Color(0xFF2C2C2C)

/** Anki のカード CSS が使う分岐点 (min-width: 481px) と同じ値 */
private val ANKI_COMPACT_BREAKPOINT = 481.dp

/**
 * カード内の相対パスを、同期済みの collection.media (アプリ自身のフォルダ) から供給する。
 * jQuery はアセット同梱版を返す。
 */
internal class MediaWebViewClient(
    private val context: Context,
    private val mediaDir: () -> File,
    private val onPageReady: ((WebView) -> Unit)? = null,
) : WebViewClient() {

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageReady?.invoke(view)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (url.host != "ankimedia.local") return null
        val name = url.lastPathSegment ?: return null

        // 編集ページは大きくなりがちで、loadDataWithBaseURL では途中で切れることがある。
        // ここから配ると大きさの制限を受けない
        EditorPages.take(name)?.let { page ->
            return runCatching {
                WebResourceResponse(
                    "text/html", "utf-8", page.byteInputStream(Charsets.UTF_8)
                )
            }.getOrNull()
        }

        if (name == "__jquery.js") {
            return runCatching {
                WebResourceResponse(
                    "application/javascript", "utf-8", context.assets.open("jquery.min.js")
                )
            }.getOrNull()
        }

        val file = File(mediaDir(), name)
        if (!file.isFile) return null
        return runCatching {
            WebResourceResponse(guessMime(name), null, file.inputStream())
        }.getOrNull()
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
    }
}


/**
 * 編集用のページを WebView へ渡すための置き場。
 *
 * 本文が長いノートでは HTML が数百 KB になり、loadDataWithBaseURL では
 * 途中で切れて後ろのフィールドが空になってしまう。ページ自体を
 * ローカルの URL として配ることで、大きさの制限を避けている。
 */
object EditorPages {
    private val pages = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun put(name: String, html: String) {
        pages[name] = html
    }

    fun take(name: String): String? = pages[name]

    fun remove(name: String) {
        pages.remove(name)
    }
}
