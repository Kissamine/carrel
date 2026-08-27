package com.haru.ankieditor.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haru.ankieditor.AnkiNote
import com.haru.ankieditor.EditorMode
import com.haru.ankieditor.FullSyncKind
import com.haru.ankieditor.NoteContent
import com.haru.ankieditor.SearchRanker
import com.haru.ankieditor.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** カードの地色。WebView の初期描画で黒が見えないように敷いておく */
val CARD_BACKGROUND = Color(0xFFFFFFFF)

/** タグの先頭絵文字とテキストの間に半角スペースを入れる */
fun formatTag(tag: String): String {
    val m = Regex("^([^\\p{L}\\p{N}\\s]+)(\\S.*)$").find(tag) ?: return tag
    val (prefix, rest) = m.destructured
    return "$prefix $rest"
}

/**
 * 編集中の下書き。
 * 画面の組み立て中に中身を読まないことで、打鍵のたびの再構成を編集欄だけに抑える。
 */
@Stable
class DraftState {
    var fields by mutableStateOf<List<String>>(emptyList())
    var tags by mutableStateOf("")
    var fieldNames by mutableStateOf<List<String>>(emptyList())
}

/** 同期の結果、別の端末の編集と食い違ったノート */
data class NoteConflict(val noteId: Long, val mine: NoteContent, val server: NoteContent)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun App(repo: DesktopRepo, prefs: Prefs, server: MediaServer) {
    val scope = rememberCoroutineScope()

    // ---- 検索条件 ----
    var keyword by remember { mutableStateOf("") }
    var selectedDeck by remember { mutableStateOf(prefs.defaultDeck) }
    var selectedTags by remember { mutableStateOf(prefs.defaultTags.toSet()) }
    // 選んだタグはそのまま覚えておく (次に開いたときも同じ絞り込みで始まる)
    LaunchedEffect(selectedTags) { prefs.defaultTags = selectedTags.toList() }
    var manualSort by remember { mutableStateOf<SortMode?>(null) }
    val hasKeyword = keyword.isNotBlank()
    LaunchedEffect(hasKeyword) { manualSort = null }
    val effectiveSort = manualSort
        ?: if (hasKeyword) SortMode.RELEVANCE else prefs.sortMode

    // ---- 一覧と選択 ----
    var notes by remember { mutableStateOf<List<AnkiNote>>(emptyList()) }
    var decks by remember { mutableStateOf<List<String>>(emptyList()) }
    var collectionTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<AnkiNote?>(null) }
    var previewHtml by remember { mutableStateOf<String?>(null) }
    var previewNoteId by remember { mutableStateOf<Long?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var renderJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()

    // ---- 編集 ----
    var editing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newNoteTypeId by remember { mutableStateOf(0L) }
    var newNoteDeckId by remember { mutableStateOf(0L) }
    var editorMode by remember { mutableStateOf(prefs.editorMode) }
    // 打鍵のたびに画面全体が作り直されないよう、下書きは専用の入れ物に持たせる
    val draft = remember { DraftState() }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveTick by remember { mutableStateOf(0) }

    // ---- 同期 ----
    var auth by remember { mutableStateOf(prefs.auth) }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAddNote by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AnkiNote?>(null) }
    val pendingEdits = remember { mutableStateMapOf<Long, NoteContent>() }
    var conflicts by remember { mutableStateOf<List<NoteConflict>>(emptyList()) }
    var fullSyncPrompt by remember { mutableStateOf<FullSyncKind?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // ⌘F が押された合図 (プレビュー側 / 編集側)
    var previewFindRequest by remember { mutableStateOf(0) }
    var editorFindRequest by remember { mutableStateOf(0) }
    // 本文が長いときはライブプレビューを止めて、手動更新に切り替える
    var livePreview by remember { mutableStateOf(true) }

    // 列の幅は「幅そのもの」で持つ。ウインドウの大きさを変えても動かず、
    // 境目を掴んだときだけ変わる (次に開いたときも同じ幅で始まる)
    var listWidth by remember { mutableStateOf(prefs.listWidth.dp) }
    var listVisible by remember { mutableStateOf(prefs.listVisible) }
    var editorWidth by remember { mutableStateOf(prefs.editorWidth.dp) }

    fun currentQuery(): String = buildList {
        selectedDeck?.let { add("deck:\"$it\"") }
        selectedTags.forEach { add("tag:\"$it\"") }
        SearchRanker.keywordQuery(keyword).takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" ")

    fun search() {
        scope.launch {
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val found = repo.searchNotes(currentQuery())
                    SearchRanker.sort(found, effectiveSort, keyword) { repo.fieldNames(it) }
                }
            }.onSuccess {
                notes = it
                if (it.isEmpty()) error = "該当ノートなし"
            }.onFailure { error = "検索失敗: ${it.message}" }
        }
    }

    fun refreshMeta() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repo.decks() to repo.allTags() }
            }.onSuccess { (d, t) -> decks = d; collectionTags = t }
        }
    }

    fun select(note: AnkiNote) {
        selected = note
        previewLoading = true
        renderJob?.cancel()
        renderJob = scope.launch {
            val html = withContext(Dispatchers.IO) {
                runCatching { repo.renderNote(note) }.getOrNull()
            }
            if (html != null && selected?.id == note.id) {
                previewHtml = html
                previewNoteId = note.id
            }
            previewLoading = false
        }
    }

    /** コレクション全体の送受信が終わるまで、様子を見ながら待つ */
    suspend fun awaitFullSync(label: String) {
        while (true) {
            val status = withContext(Dispatchers.IO) { repo.jobStatus() }
            status.error?.let { error(it) }
            if (!status.running) return
            message = if (status.total > 0) {
                "$label ${status.transferred} / ${status.total}"
            } else {
                label
            }
            delay(400)
        }
    }

    /** メディア同期が終わるまで待つ (件数を出しながら) */
    suspend fun awaitMediaSync(quiet: Boolean) {
        while (true) {
            val status = withContext(Dispatchers.IO) { repo.mediaStatus() }
            if (!status.active) return
            if (!quiet) {
                message = "メディアを同期中... " + status.detail
            }
            delay(500)
        }
    }

    suspend fun performSync(quiet: Boolean, refreshList: Boolean) {
        val a = auth ?: return
        if (syncing) return
        syncing = true
        val mine = pendingEdits.toMap()
        if (!quiet) message = "コレクションを同期中..."
        try {
            val result = withContext(Dispatchers.IO) { repo.syncCollection(a) }
            if (result.auth != a) {
                auth = result.auth
                prefs.setAuth(result.auth)
            }
            if (result.required != FullSyncKind.NONE) {
                val firstTime = result.required == FullSyncKind.DOWNLOAD &&
                    notes.isEmpty() && pendingEdits.isEmpty()
                if (firstTime) {
                    message = "コレクションを取得中..."
                    withContext(Dispatchers.IO) { repo.startFullSync(result.auth, upload = false) }
                    awaitFullSync("コレクションを取得中...")
                } else {
                    fullSyncPrompt = result.required
                    message = null
                    return
                }
            }
            if (!quiet) message = "メディアを同期中... (初回は時間がかかります)"
            withContext(Dispatchers.IO) { repo.startMediaSync(result.auth) }
            awaitMediaSync(quiet)

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
            message = when {
                clashed.isNotEmpty() -> "${clashed.size} 件のノートが競合しました"
                quiet -> null
                else -> "同期完了"
            }
            if (refreshList) {
                refreshMeta()
                search()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "同期失敗: ${e.message}"
        } finally {
            syncing = false
        }
    }

    fun sync() {
        if (auth == null) { showLogin = true; return }
        scope.launch { performSync(quiet = false, refreshList = true) }
    }

    fun syncInBackground() {
        if (auth == null) return
        scope.launch { performSync(quiet = true, refreshList = false) }
    }

    LaunchedEffect(Unit) {
        refreshMeta()
        search()
        if (auth != null) sync()
    }

    // 動作確認用: 最初のノートを自動で開く
    LaunchedEffect(notes.size) {
        if (System.getProperty("ankieditor.debug") == null) return@LaunchedEffect
        if (selected == null) notes.firstOrNull()?.let { select(it) }
    }

    // 検索欄は打つたびに反映する (少し待ってからまとめて)
    LaunchedEffect(keyword, selectedDeck, selectedTags, effectiveSort) {
        delay(250)
        search()
        // 条件を変えたら、一覧は先頭から見せる
        // (前の結果でスクロールした位置が残っていると、途中から始まって戸惑う)
        listState.scrollToItem(0)
    }

    /** 手動更新のときに、いまの下書きで描き直す */
    fun refreshPreview() {
        val note = selected ?: return
        scope.launch {
            val rendered = withContext(Dispatchers.IO) {
                runCatching { repo.renderDraft(note, draft.fields, draft.tags) }.getOrNull()
            } ?: return@launch
            previewHtml = rendered
            previewNoteId = note.id
        }
    }

    /** そのノートの内容を編集欄に読み込む */
    fun loadDraft(note: AnkiNote) {
        draft.fieldNames = repo.fieldNames(note.modelId)
        draft.fields = note.fields
        draft.tags = note.tags
        dirty = false
    }

    fun startEditing() {
        val note = selected ?: return
        loadDraft(note)
        editorMode = prefs.editorMode
        editing = true
    }

    fun stopEditing() {
        editing = false
        dirty = false
        if (creating) {
            creating = false
            selected = null
            previewHtml = null
            previewNoteId = null
        } else {
            selected?.let { select(it) }
        }
    }

    fun startNewNote(notetypeId: Long, deckId: Long) {
        val names = repo.fieldNames(notetypeId)
        newNoteTypeId = notetypeId
        newNoteDeckId = deckId
        draft.fieldNames = names
        draft.fields = List(names.size) { "" }
        draft.tags = prefs.defaultTags.joinToString(" ")
        selected = AnkiNote(0L, notetypeId, draft.fields, draft.tags)
        previewHtml = null
        previewNoteId = null
        creating = true
        editing = true
        dirty = true
        editorMode = prefs.editorMode
    }

    fun saveEdits() {
        val note = selected ?: return
        scope.launch {
            saving = true
            try {
                if (creating) {
                    val added = withContext(Dispatchers.IO) {
                        repo.addNote(newNoteTypeId, newNoteDeckId, draft.fields, draft.tags)
                    }
                    creating = false
                    dirty = false
                    notes = listOf(added) + notes
                    pendingEdits[added.id] = NoteContent(draft.fields, draft.tags)
                    message = "ノートを追加しました"
                    select(added)
                    syncInBackground()
                    return@launch
                }
                withContext(Dispatchers.IO) { repo.saveNote(note, draft.fields, draft.tags) }
                dirty = false
                pendingEdits[note.id] = NoteContent(draft.fields, draft.tags)
                message = "保存しました"
                saveTick++
                notes = notes.map {
                    if (it.id == note.id) it.copy(fields = draft.fields, tags = draft.tags) else it
                }
                selected = notes.firstOrNull { it.id == note.id } ?: note
            } catch (e: Exception) {
                message = "保存失敗: ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    fun deleteNote(note: AnkiNote) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repo.deleteNotes(listOf(note.id)) }
            }.onSuccess {
                notes = notes.filterNot { it.id == note.id }
                pendingEdits.remove(note.id)
                if (selected?.id == note.id) {
                    selected = null
                    previewHtml = null
                    previewNoteId = null
                    editing = false
                }
                message = "ノートを削除しました"
                syncInBackground()
            }.onFailure { message = "削除失敗: ${it.message}" }
        }
    }

    // 保存したら自動で同期する
    LaunchedEffect(saveTick) {
        if (saveTick == 0) return@LaunchedEffect
        delay(1500)
        if (auth == null) return@LaunchedEffect
        performSync(quiet = false, refreshList = false)
    }

    // 編集中のライブプレビュー。
    // 下書きはこの効果の中だけで読む (画面の組み立て中に読むと全体が作り直される)
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        snapshotFlow { draft.fields to draft.tags }.collectLatest { (fields, tags) ->
            val size = fields.sumOf { it.length }
            // 長すぎる本文では打鍵ごとの再描画をやめる (Android 版と同じ考え方)
            livePreview = size <= 12000
            if (!livePreview) return@collectLatest
            val note = selected ?: return@collectLatest
            delay(if (size > 4000) 700L else 300L)
            val rendered = withContext(Dispatchers.IO) {
                runCatching { repo.renderDraft(note, fields, tags) }.getOrNull()
            } ?: return@collectLatest
            if (selected?.id == note.id) {
                previewHtml = rendered
                previewNoteId = note.id
            }
        }
    }

    LaunchedEffect(message, syncing) {
        if (!syncing && message != null && !message!!.startsWith("同期失敗")) {
            delay(2500)
            message = null
        }
    }

    val pill = RoundedCornerShape(50)

    // ---- Mac で一般的なキー操作 ----
    // ⌘F 検索 / ⌘S 保存 / ⌘N 新規 / ⌘R 同期 / ⌘, 設定 / Esc 閉じる
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val command = event.isMetaPressed
                when {
                    command && event.key == Key.F -> {
                        if (editing) editorFindRequest++ else previewFindRequest++
                        true
                    }

                    command && event.key == Key.S -> {
                        if (editing && dirty) saveEdits()
                        true
                    }

                    command && event.key == Key.N -> {
                        showAddNote = true
                        true
                    }

                    command && event.key == Key.R -> {
                        sync()
                        true
                    }

                    command && event.key == Key.Comma -> {
                        showSettings = true
                        true
                    }

                    event.key == Key.Escape -> when {
                        showSettings -> { showSettings = false; true }
                        editing -> { stopEditing(); true }
                        else -> false
                    }

                    else -> false
                }
            }
    ) {
    Scaffold { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val total = maxWidth
            val density = LocalDensity.current

            Row(Modifier.fillMaxSize()) {
                // ---- 左: 検索と一覧 ----
                // プレビューが 600 ピクセルを切らないよう、一覧の幅に上限をかける
                val previewFloor = with(density) { 600.toDp() } + if (editing) editorWidth else 0.dp
                val listMax = (total - previewFloor - 20.dp).coerceAtLeast(220.dp)
                val actualListWidth = listWidth.coerceIn(220.dp, listMax)
                // 一覧はしまえる。幅を 0 まで縮めて、そのまま隠す
                val shownListWidth by animateDpAsState(
                    targetValue = if (listVisible) actualListWidth else 0.dp,
                    animationSpec = tween(240),
                    label = "listWidth",
                )
                // 外側だけ縮めて切り取る。中身は常に本来の幅で測る
                // (幅 0 のまま測らせると、中の部品が負の幅を要求して落ちる)
                Box(
                    Modifier
                        .width(shownListWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                Column(Modifier.requiredWidth(actualListWidth).fillMaxHeight()) {
                    // macOS のウインドウボタン (信号機) と重ならないよう上を空ける
                    Spacer(Modifier.height(28.dp))
                    SearchControls(
                        keyword = keyword,
                        onKeyword = { keyword = it },
                        pill = pill,
                        decks = decks,
                        selectedDeck = selectedDeck,
                        onDeck = { selectedDeck = it },
                        tags = collectionTags,
                        pinned = prefs.pinnedTags,
                        selectedTags = selectedTags,
                        onToggleTag = { tag ->
                            selectedTags =
                                if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                        },
                        onSettings = { showSettings = true },
                        onSync = { sync() },
                        syncing = syncing,
                        onHideList = {
                            listVisible = false
                            prefs.listVisible = false
                        },
                    )
                    ResultHeader(
                        count = notes.size,
                        error = error,
                        sort = effectiveSort,
                        onSort = {
                            manualSort = it
                            if (!hasKeyword) prefs.sortMode = it
                        },
                    )
                    NoteList(
                        notes = notes,
                        selected = selected,
                        // 編集中でも別のカードに移れる。書きかけは先に保存してから移る
                        onSelect = { note ->
                            if (editing && dirty) saveEdits()
                            select(note)
                            if (editing && !creating) loadDraft(note)
                        },
                        onDelete = { deleteTarget = it },
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                }

                if (listVisible) SplitHandle { delta ->
                    val moved = actualListWidth + with(density) { delta.toDp() }
                    listWidth = moved.coerceIn(220.dp, listMax)
                    prefs.listWidth = listWidth.value
                }

                // ---- 右: プレビュー (+ 編集) ----
                // 編集を開くと、プレビューが左へ詰まりながら編集列が右から出てくる
                val editorShown by animateFloatAsState(
                    targetValue = if (editing) 1f else 0f,
                    animationSpec = tween(260),
                    label = "editorWidth",
                )
                Row(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        PreviewPane(
                            note = selected,
                            server = server,
                            html = previewHtml.takeIf { previewNoteId == selected?.id },
                            loading = previewLoading,
                            zoom = prefs.textZoom / 100.0,
                            onEdit = if (selected != null && !editing) ({ startEditing() }) else null,
                            onAdd = if (editing) null else ({ showAddNote = true }),
                            onRefresh = if (editing && !livePreview) ({ refreshPreview() }) else null,
                            // 編集中でもカード内検索は使えるようにしておく
                            findable = selected != null,
                            findRequest = previewFindRequest,
                            onShowList = if (listVisible) null else ({
                                listVisible = true
                                prefs.listVisible = true
                            }),
                            // 設定や確認を前に出している間は、ページを引っ込める
                            covered = showSettings || showLogin || showAddNote ||
                                deleteTarget != null || fullSyncPrompt != null ||
                                conflicts.isNotEmpty(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // いまのウインドウ幅で編集列に使える上限。
                    // プレビューの最低幅 (600px) を先に確保する
                    val editorRoom =
                        (total - actualListWidth - with(density) { 600.toDp() } - 20.dp)
                            .coerceAtLeast(280.dp)
                    val effectiveEditorWidth = if (editorWidth > editorRoom) editorRoom else editorWidth
                    if (editorShown > 0.001f) {
                        SplitHandle { delta ->
                            val moved = editorWidth - with(density) { delta.toDp() }
                            // 編集列を広げても、プレビューは 250dp を残す
                            val editorMax =
                                (total - actualListWidth - with(density) { 600.toDp() } - 20.dp)
                                    .coerceAtLeast(320.dp)
                            editorWidth = moved.coerceIn(320.dp, editorMax)
                            prefs.editorWidth = editorWidth.value
                        }
                        Box(
                            Modifier
                                .width(effectiveEditorWidth * editorShown)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            EditorPane(
                                note = selected ?: return@Box,
                                draft = draft,
                                allTags = collectionTags,
                                mode = editorMode,
                                dirty = dirty,
                                saving = saving,
                                server = server,
                                onDirty = { dirty = true },
                                onModeChange = { editorMode = it; prefs.editorMode = it },
                                onSave = { saveEdits() },
                                onClose = { stopEditing() },
                                findRequest = editorFindRequest,
                                modifier = Modifier.requiredWidth(effectiveEditorWidth).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 進み具合の知らせ (画面の右上に静かに出る) ----
    Box(Modifier.fillMaxSize().padding(top = 34.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(180)) + slideInVertically { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically { -it / 2 },
        ) {
            Surface(
                shape = MacShapes.pill,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.padding(top = 12.dp, end = 16.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (syncing) {
                        CircularProgressIndicator(
                            Modifier.size(13.dp),
                            strokeWidth = 1.6.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(9.dp))
                    }
                    Text(
                        message.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    }

    if (showSettings) {
        SettingsDialog(
            repo = repo,
            prefs = prefs,
            auth = auth,
            decks = decks,
            collectionTags = collectionTags,
            onLogout = { auth = null; prefs.setAuth(null) },
            onOpenLogin = { showSettings = false; showLogin = true },
            onMessage = { message = it },
            onRefreshMeta = { refreshMeta(); search() },
            onDismiss = { showSettings = false },
        )
    }

    Dialogs(
        repo = repo,
        prefs = prefs,
        showLogin = showLogin,
        onLoginDone = { a ->
            showLogin = false
            if (a != null) {
                auth = a
                prefs.setAuth(a)
                sync()
            }
        },
        showAddNote = showAddNote,
        onAddNoteDismiss = { showAddNote = false },
        onCreate = { typeId, deckId -> showAddNote = false; startNewNote(typeId, deckId) },
        decks = decks,
        defaultDeck = selectedDeck ?: prefs.defaultDeck,
        deleteTarget = deleteTarget,
        onDeleteDismiss = { deleteTarget = null },
        onDeleteConfirm = { note -> deleteTarget = null; deleteNote(note) },
        fullSyncPrompt = fullSyncPrompt,
        onFullSyncDismiss = { fullSyncPrompt = null },
        onFullSync = { kind ->
            val a = auth
            fullSyncPrompt = null
            if (a != null) scope.launch {
                syncing = true
                message = if (kind == FullSyncKind.DOWNLOAD) "取り込み中..." else "送信中..."
                runCatching {
                    withContext(Dispatchers.IO) {
                        repo.startFullSync(a, upload = kind == FullSyncKind.UPLOAD)
                    }
                    awaitFullSync(
                        if (kind == FullSyncKind.DOWNLOAD) "取り込み中..." else "送信中..."
                    )
                    withContext(Dispatchers.IO) { repo.startMediaSync(a) }
                    awaitMediaSync(false)
                }.onSuccess {
                    pendingEdits.clear()
                    message = "同期完了"
                    refreshMeta()
                    search()
                }.onFailure { message = "同期失敗: ${it.message}" }
                syncing = false
            }
        },
        conflicts = conflicts,
        onConflictsChange = { conflicts = it },
        onTakeMine = { c ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repo.saveNote(AnkiNote(c.noteId, 0L, c.mine.fields, c.mine.tags), c.mine.fields, c.mine.tags)
                    }
                }
                notes = notes.map {
                    if (it.id == c.noteId) it.copy(fields = c.mine.fields, tags = c.mine.tags) else it
                }
                selected?.takeIf { it.id == c.noteId }?.let { select(it) }
                pendingEdits[c.noteId] = c.mine
                saveTick++
            }
        },
        showSettings = false,
        onSettingsDismiss = { showSettings = false },
        auth = auth,
        onLogout = { auth = null; prefs.setAuth(null) },
        onOpenLogin = { showSettings = false; showLogin = true },
        onMessage = { message = it },
        collectionTags = collectionTags,
        onRefreshMeta = { refreshMeta(); search() },
    )
}
