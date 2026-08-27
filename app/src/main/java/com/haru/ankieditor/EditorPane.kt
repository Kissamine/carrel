package com.haru.ankieditor

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * ノート編集ペイン。
 *
 * テキストモードは PC 版 Anki と同じく WebView の contenteditable で実装している。
 * HTML モードでは同じ内容を色分けされた生の HTML として編集できる。どちらも編集結果は
 * onFieldsChange で即座に返るため、隣のプレビューがリアルタイムに追従する。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditorPane(
    note: AnkiNote,
    draft: DraftState,
    allTags: List<String>,
    mode: EditorMode,
    defaultImageCorner: ImageCorner,
    dirty: Boolean,
    saving: Boolean,
    mediaDir: File,
    addImage: suspend (Uri) -> String?,
    onDirty: () -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onFocusedFieldChange: (Int?) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onRefreshPreview: (() -> Unit)? = null,
    /** Ctrl+F が押された回数。増えたら検索欄へ移る */
    findRequest: Int = 0,
    /** 「いまの内容をすぐ伝える」手段を、外へ渡すための口 */
    onProvideFlush: (() -> Unit) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 編集中の内容はこのペインが持ち主になる。
    // 打鍵のたびに画面全体へ伝えると再構成が重くなるので、下書きへは書くだけにして
    // (プレビューはその変化を効果の中で拾う)、こちらからは読み直さない
    val fieldNames = remember(note.id) { draft.fieldNames }
    val initialFields = remember(note.id) { draft.fields }
    var tags by remember(note.id) { mutableStateOf(draft.tags) }
    // 最新の内容の控え。画面の組み立て中に読んでも再構成を誘発しないよう、
    // 監視対象ではないただのリストにしておく
    val latestFields = remember(note.id) { initialFields.toMutableList() }
    fun publishFields(values: List<String>) {
        latestFields.clear()
        latestFields.addAll(values)
        draft.fields = values
        onDirty()
    }
    fun publishTags(value: String) {
        tags = value
        draft.tags = value
        onDirty()
    }

    var richWebView by remember(note.id) { mutableStateOf<WebView?>(null) }
    var sourceWebView by remember(note.id) { mutableStateOf<WebView?>(null) }
    // 保存の直前に呼ばれる。ページ側の入力をすぐ吸い上げる
    LaunchedEffect(sourceWebView, richWebView) {
        onProvideFlush {
            sourceWebView?.evaluateJavascript("window.__ankiSource.flush()", null)
            richWebView?.evaluateJavascript("window.__ankiFlush && window.__ankiFlush()", null)
        }
    }

    // どのフィールドにカーソルがあるか (null = どこにもない)
    var focusedField by remember(note.id) { mutableStateOf<Int?>(null) }
    val imageStyle = rememberMediaStyle(defaultImageCorner)
    val videoStyle = rememberMediaStyle(defaultImageCorner)
    var videoUrl by remember { mutableStateOf("") }
    var videoStart by remember { mutableStateOf("") }
    var videoEnd by remember { mutableStateOf("") }
    // 挿入先が決まらないときに選ばせる
    var pendingHtml by remember { mutableStateOf<String?>(null) }
    var imageDialog by remember { mutableStateOf(false) }
    // ノート内検索
    var findQuery by remember(note.id) { mutableStateOf("") }
    var findIndex by remember(note.id) { mutableIntStateOf(0) }
    var findTotal by remember(note.id) { mutableIntStateOf(0) }
    val findFieldFocus = remember { FocusRequester() }
    LaunchedEffect(findRequest) {
        if (findRequest <= 0) return@LaunchedEffect
        // 編集用のページに入力の焦点があると、検索欄へ移っても打鍵がページ側へ行く。
        // 先にページ側の焦点を外してから移す
        richWebView?.clearFocus()
        sourceWebView?.clearFocus()
        kotlinx.coroutines.delay(30)
        runCatching { findFieldFocus.requestFocus() }
    }
    var videoDialog by remember { mutableStateOf(false) }

    // 他アプリでコピーされた画像を検知して、貼り付けを提案する
    var clipboardImage by remember { mutableStateOf<Uri?>(null) }
    var clipboardDismissed by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        fun refresh() {
            // クリップボードはアプリが前面にあるときしか読めないため、
            // 復帰時と変更通知の両方で拾う
            val uri = clipboardImageUri(context)
            if (uri != clipboardImage) {
                clipboardImage = uri
                clipboardDismissed = false
            }
        }
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }
        cm?.addPrimaryClipChangedListener(clipListener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose {
            cm?.removePrimaryClipChangedListener(clipListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // HTML モードの入力状態 (カーソル位置を保つため TextFieldValue で持つ)
    val htmlValues = remember(note.id, fieldNames.size) {
        mutableStateListOf<TextFieldValue>().apply {
            fieldNames.indices.forEach { add(TextFieldValue(initialFields.getOrElse(it) { "" })) }
        }
    }

    // HTML モードに切り替えたときだけ、その時点の最新の内容を取り込む
    // (テキストモードでの編集が古い値で上書きされないように)
    LaunchedEffect(mode) {
        if (mode != EditorMode.HTML) return@LaunchedEffect
        val latest = draft.fields
        fieldNames.indices.forEach { i ->
            val value = latest.getOrElse(i) { "" }
            if (i > htmlValues.lastIndex) htmlValues.add(TextFieldValue(value))
            else if (htmlValues[i].text != value) htmlValues[i] = htmlValues[i].copy(text = value)
        }
    }

    fun pushHtmlValues() = publishFields(htmlValues.map { it.text })

    fun imgTag(name: String): String = buildString {
        append("<img src=\"").append(name).append("\"")
        if (imageStyle.corner == ImageCorner.ROUND) append(" class=\"round\"")
        val css = imageStyle.css()
        if (css.isNotEmpty()) append(" style=\"").append(css).append("\"")
        append(">")
    }

    /**
     * 動画は iframe に直接スタイルを当てると崩れやすいので、
     * 見た目の指定は外側の div にまとめる。
     */
    fun videoTag(embedUrl: String): String {
        val roundClass = if (videoStyle.corner == ImageCorner.ROUND) " class=\"round\"" else ""
        val iframe = "<iframe width=\"100%\"$roundClass style=\"aspect-ratio: 16 / 9;\" " +
            "src=\"$embedUrl\" title=\"YouTube video player\" frameborder=\"0\" " +
            "allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; " +
            "picture-in-picture; web-share\" " +
            "referrerpolicy=\"strict-origin-when-cross-origin\" allowfullscreen=\"\"></iframe>"
        val css = videoStyle.css(asWrapper = true)
        return if (css.isEmpty()) iframe else "<div style=\"$css\">$iframe</div>"
    }

    /** 指定フィールドの末尾に追加する (カーソルが無いときの挿入先) */
    fun insertIntoField(index: Int, html: String) {
        when (mode) {
            EditorMode.RICH ->
                richWebView?.evaluateJavascript(
                    "AnkiEditor.appendTo($index, ${JSONObject.quote(html)})", null
                )

            EditorMode.HTML ->
                sourceWebView?.evaluateJavascript(
                    "window.__ankiSource.insert($index, ${JSONObject.quote(html)})", null
                )
        }
    }

    /** カーソル位置に挿入する。カーソルが無ければ挿入先を選ばせる */
    fun insertHtml(html: String) {
        val target = focusedField
        if (target == null) {
            pendingHtml = html
            return
        }
        when (mode) {
            EditorMode.RICH ->
                richWebView?.evaluateJavascript(
                    "document.execCommand('insertHTML', false, ${JSONObject.quote(html)})", null
                )

            EditorMode.HTML ->
                sourceWebView?.evaluateJavascript(
                    "window.__ankiSource.insert($target, ${JSONObject.quote(html)})", null
                )
        }
    }

    fun insertImage(name: String) = insertHtml(imgTag(name))

    suspend fun handlePicked(uri: Uri?) {
        if (uri == null) return
        addImage(uri)?.let { insertImage(it) }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> scope.launch { handlePicked(uri) } }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> scope.launch { handlePicked(uri) } }

    // 音声は Anki 標準の [sound:ファイル名] として入れる
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            addImage(uri)?.let { insertHtml("[sound:$it]") }
        }
    }

    // 画像を追加するダイアログ。角の形 → 読み込み元、の順で選ばせる
    if (imageDialog) {
        AlertDialog(
            onDismissRequest = { imageDialog = false },
            title = { Text("画像を追加") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SectionLabel("読み込み元")
                    SourceButtons(
                        onPhoto = {
                            imageDialog = false
                            pickPhoto.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onFile = {
                            imageDialog = false
                            pickFile.launch(arrayOf("image/*"))
                        },
                    )
                    Spacer(Modifier.height(20.dp))
                    AppearanceOptions(imageStyle)
                }
            },
            confirmButton = {
                TextButton(onClick = { imageDialog = false }) { Text("キャンセル") }
            },
        )
    }

    // 挿入先を選ぶダイアログ (画像フィールドを優先して並べる)
    pendingHtml?.let { html ->
        val candidates = remember(fieldNames) {
            val imageish = fieldNames.withIndex()
                .filter { it.value.contains("img", true) || it.value.contains("画像") }
            if (imageish.isNotEmpty()) imageish else fieldNames.withIndex().toList()
        }
        AlertDialog(
            onDismissRequest = { pendingHtml = null },
            title = { Text("どこに挿入しますか") },
            text = {
                Column {
                    Text(
                        "カーソルが置かれていないため、挿入先を選んでください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    candidates.forEach { (index, fname) ->
                        ListItem(
                            headlineContent = { Text(fname) },
                            modifier = Modifier.clickable {
                                insertIntoField(index, html)
                                pendingHtml = null
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingHtml = null }) { Text("キャンセル") }
            },
        )
    }

    if (videoDialog) {
        AlertDialog(
            onDismissRequest = { videoDialog = false },
            title = { Text("動画を追加") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SectionLabel("動画の URL")
                    OutlinedTextField(
                        value = videoUrl,
                        onValueChange = { videoUrl = it },
                        placeholder = { Text("https://youtu.be/...", maxLines = 1) },
                        singleLine = true,
                        shape = CircleShape,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("再生範囲 (空欄なら指定しない)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeInput(
                            label = "開始",
                            value = videoStart,
                            onValueChange = { videoStart = it },
                            modifier = Modifier.weight(1f),
                        )
                        TimeInput(
                            label = "終了",
                            value = videoEnd,
                            onValueChange = { videoEnd = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "1:30 や 90 のように指定できます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))
                    AppearanceOptions(videoStyle)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = videoUrl.isNotBlank(),
                    onClick = {
                        val embed = embedUrlOf(
                            videoUrl.trim(),
                            startSeconds = parseSeconds(videoStart),
                            endSeconds = parseSeconds(videoEnd),
                        )
                        videoDialog = false
                        insertHtml(videoTag(embed))
                        videoUrl = ""
                        videoStart = ""
                        videoEnd = ""
                    },
                ) { Text("挿入") }
            },
            dismissButton = {
                TextButton(onClick = { videoDialog = false }) { Text("キャンセル") }
            },
        )
    }

    Column(modifier) {
        // ---- ヘッダー ----
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.height(TOOLBAR_ITEM_HEIGHT)) {
                EditorMode.entries.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        shape = SegmentedButtonDefaults.itemShape(index, EditorMode.entries.size),
                        // 既定のチェックアイコンは幅を圧迫して文字が詰まるので出さない
                        icon = {},
                        label = {
                            Text(
                                m.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (saving) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            val keyboard = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            TextButton(
                onClick = {
                    // 保存したら入力は一段落なので、キーボードを畳んで結果を見せる
                    richWebView?.clearFocus()
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    onSave()
                },
                enabled = dirty && !saving,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "編集を終了")
            }
        }

        // ---- 書式のツールバー (テキストモードのみ) ----
        if (mode == EditorMode.RICH) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fun run(js: String) = richWebView?.evaluateJavascript(js, null)
                fun exec(cmd: String, value: String? = null) {
                    val v = if (value == null) "undefined" else JSONObject.quote(value)
                    run("AnkiEditor.exec(${JSONObject.quote(cmd)}, $v)")
                }
                fun wrapTag(open: String, close: String) {
                    run("AnkiEditor.wrap(${JSONObject.quote(open)}, ${JSONObject.quote(close)})")
                }

                // 段落の種類
                var blockMenu by remember { mutableStateOf(false) }
                Box {
                    TooltipIconButton("段落の種類", Icons.Default.Title) { blockMenu = true }
                    DropdownMenu(expanded = blockMenu, onDismissRequest = { blockMenu = false }) {
                        listOf(
                            Triple("通常のテキスト", Icons.Default.Notes, "p"),
                            Triple("見出し1", Icons.Default.Title, "h1"),
                            Triple("見出し2", Icons.Default.Title, "h2"),
                            Triple("見出し3", Icons.Default.Title, "h3"),
                            Triple("見出し4", Icons.Default.Title, "h4"),
                            Triple("引用", Icons.Default.FormatQuote, "blockquote"),
                        ).forEach { (label, icon, tag) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { Icon(icon, contentDescription = null) },
                                onClick = { exec("formatBlock", tag); blockMenu = false },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("コード") },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                            onClick = { wrapTag("<code>", "</code>"); blockMenu = false },
                        )
                    }
                }

                TooltipIconButton("太字", Icons.Default.FormatBold) { exec("bold") }
                TooltipIconButton("斜体", Icons.Default.FormatItalic) { exec("italic") }
                TooltipIconButton("下線", Icons.Default.FormatUnderlined) { exec("underline") }
                TooltipIconButton("取り消し線", Icons.Default.StrikethroughS) { exec("strikeThrough") }

                // 文字色 / 背景色
                var colorMenu by remember { mutableStateOf(false) }
                Box {
                    TooltipIconButton("文字色", Icons.Default.FormatColorText) { colorMenu = true }
                    DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                        EDITOR_COLORS.forEach { (label, hex) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { ColorSwatch(hex) },
                                onClick = { exec("foreColor", hex); colorMenu = false },
                            )
                        }
                    }
                }
                var bgMenu by remember { mutableStateOf(false) }
                Box {
                    TooltipIconButton("背景色", Icons.Default.FormatColorFill) { bgMenu = true }
                    DropdownMenu(expanded = bgMenu, onDismissRequest = { bgMenu = false }) {
                        (listOf("既定" to "#45474a") + EDITOR_COLORS).forEach { (label, hex) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { ColorSwatch(hex) },
                                onClick = {
                                    wrapTag(
                                        "<span class=\"rounded-text\" style=\"background-color: $hex;\">",
                                        "</span>",
                                    )
                                    bgMenu = false
                                },
                            )
                        }
                    }
                }

                TooltipIconButton("箇条書き", Icons.AutoMirrored.Filled.FormatListBulleted) { exec("insertUnorderedList") }
                TooltipIconButton("番号付きリスト", Icons.Default.FormatListNumbered) { exec("insertOrderedList") }

                // 配置
                var alignMenu by remember { mutableStateOf(false) }
                Box {
                    TooltipIconButton("配置", Icons.AutoMirrored.Filled.FormatAlignLeft) { alignMenu = true }
                    DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("左端揃え") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, contentDescription = null)
                            },
                            onClick = { exec("justifyLeft"); alignMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("中央揃え") },
                            leadingIcon = {
                                Icon(Icons.Default.FormatAlignCenter, contentDescription = null)
                            },
                            onClick = { exec("justifyCenter"); alignMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("右端揃え") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.FormatAlignRight, contentDescription = null)
                            },
                            onClick = { exec("justifyRight"); alignMenu = false },
                        )
                    }
                }

                TooltipIconButton("数式", Icons.Default.Functions) {
                    wrapTag("<anki-mathjax>", "</anki-mathjax>")
                }
                TooltipIconButton("書式をクリア", Icons.Default.FormatClear) { exec("removeFormat") }

            }
        }

        // ---- 検索とメディア追加。幅に応じて検索バーが伸び縮みする 1 行 ----
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 横幅が足りないときはメディアのボタンをアイコン + 「+」だけに縮める
            val compactTools = maxWidth < 620.dp
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoteFindBar(
                    modifier = Modifier.weight(1f),
                    compact = compactTools,
                    query = findQuery,
                    index = findIndex,
                    total = findTotal,
                    onQueryChange = { q ->
                        findQuery = q
                        when (mode) {
                            EditorMode.HTML ->
                                sourceWebView?.evaluateJavascript(
                                    "window.__ankiSource.search(${JSONObject.quote(q)})"
                                ) { result ->
                                    findTotal = result?.trim()?.toIntOrNull() ?: 0
                                    findIndex = if (findTotal == 0) 0 else 1
                                }

                            EditorMode.RICH -> richWebView?.findAllAsync(q)
                        }
                    },
                    onMove = { forward ->
                        when (mode) {
                            EditorMode.HTML ->
                                sourceWebView?.evaluateJavascript(
                                    "window.__ankiSource.move(${if (forward) 1 else -1})"
                                ) { result ->
                                    findIndex = result?.trim()?.toIntOrNull() ?: findIndex
                                }

                            EditorMode.RICH -> richWebView?.findNext(forward)
                        }
                    },
                    onClear = {
                        findQuery = ""
                        findTotal = 0
                        findIndex = 0
                        richWebView?.clearMatches()
                        sourceWebView?.evaluateJavascript("window.__ankiSource.clear()", null)
                    },
                    focusRequester = findFieldFocus,
                )
                Spacer(Modifier.width(8.dp))
                // 検索中は件数と前後ボタンが入ってくるので、
                // 狭い幅ではメディアのボタンを 1 つのメニューにたたんで検索欄を守る
                val foldMedia = compactTools && findQuery.isNotBlank()
                // たたむ / 開くときは、右端を軸に幅が伸び縮みするようにつなぐ
                AnimatedContent(
                    targetState = foldMedia,
                    transitionSpec = {
                        (fadeIn(tween(160)) +
                            expandHorizontally(tween(240), expandFrom = Alignment.End)) togetherWith
                            (fadeOut(tween(120)) +
                                shrinkHorizontally(tween(240), shrinkTowards = Alignment.End))
                    },
                    label = "mediaFold",
                ) { folded ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                if (!folded) {
                    onRefreshPreview?.let { refresh ->
                        TooltipIconButton("プレビューを更新", Icons.Default.Refresh) { refresh() }
                        Spacer(Modifier.width(4.dp))
                    }
                    MediaAddButton(
                        label = "画像を追加",
                        icon = Icons.Default.Image,
                        compact = compactTools,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = { imageDialog = true },
                    )
                    MediaAddButton(
                        label = "動画を追加",
                        icon = Icons.Default.Movie,
                        compact = compactTools,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = { videoDialog = true },
                    )
                    MediaAddButton(
                        label = "音声を追加",
                        icon = Icons.Default.MusicNote,
                        compact = compactTools,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = { pickAudio.launch(arrayOf("*/*")) },
                    )
                } else {
                    MediaAddMenu(
                        onRefresh = onRefreshPreview,
                        onImage = { imageDialog = true },
                        onVideo = { videoDialog = true },
                        onAudio = { pickAudio.launch(arrayOf("*/*")) },
                    )
                }
                }
                }
            }
        }

        // ---- クリップボードの画像を貼り付ける提案 ----
        val clip = clipboardImage
        if (clip != null && !clipboardDismissed) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            }
                        },
                        update = { it.setImageURI(clip) },
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "コピーした画像があります",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch { addImage(clip)?.let { insertImage(it) } }
                            clipboardDismissed = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("貼り付け", style = MaterialTheme.typography.labelMedium) }
                    IconButton(
                        onClick = { clipboardDismissed = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "閉じる",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // ---- 本体 (上端は背景色のグラデーションでぼかす) ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {

        when (mode) {
            EditorMode.RICH -> RichTextFields(
                note = note,
                fieldNames = fieldNames,
                initialFields = latestFields.toList(),
                mediaDir = mediaDir,
                onFieldsChange = { publishFields(it) },
                onFocusChanged = { focusedField = it },
                onFindResult = { index, total -> findIndex = index; findTotal = total },
                onWebViewReady = { richWebView = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            EditorMode.HTML -> HtmlSourceFields(
                note = note,
                fieldNames = fieldNames,
                initialFields = latestFields.toList(),
                mediaDir = mediaDir,
                onFieldsChange = { publishFields(it) },
                onFocusChanged = { focusedField = it },
                onCommand = { name ->
                    when (name) {
                        // ページ側は保存の直前に今の内容を送ってくるので、
                        // それが届いてから保存する
                        "save" -> scope.launch {
                            kotlinx.coroutines.delay(60)
                            onSave()
                        }
                        "find" -> scope.launch {
                            sourceWebView?.clearFocus()
                            kotlinx.coroutines.delay(30)
                            runCatching { findFieldFocus.requestFocus() }
                        }
                    }
                },
                onWebViewReady = { sourceWebView = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        }

        // 検索欄のすぐ下に罫線を引くと窮屈なので、
        // 背景色が上から下へ透けていくグラデーションでぼかす。
        // 重ねて描くだけなので編集領域の縦幅は減らない
        val fadeColor =
            if (mode == EditorMode.RICH) Color(0xFF1C1C1C) else MaterialTheme.colorScheme.surface
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(20.dp)
                .background(Brush.verticalGradient(listOf(fadeColor, Color.Transparent)))
        )
        }

        HorizontalDivider()
        TagEditor(
            tags = tags,
            allTags = allTags,
            onTagsChange = { publishTags(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * 読み込み元のボタン。横幅が足りるときは 1 行 2 列、
 * 狭いとき (スマホ幅など) は縦に積む。
 */
@Composable
private fun SourceButtons(onPhoto: () -> Unit, onFile: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 320.dp
        val photo: @Composable (Modifier) -> Unit = { m ->
            OutlinedButton(onClick = onPhoto, shape = CircleShape, modifier = m) {
                Text("写真から選ぶ", maxLines = 1)
            }
        }
        val file: @Composable (Modifier) -> Unit = { m ->
            OutlinedButton(onClick = onFile, shape = CircleShape, modifier = m) {
                Text("ファイルから選ぶ", maxLines = 1)
            }
        }
        if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photo(Modifier.weight(1f))
                file(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                photo(Modifier.fillMaxWidth())
                file(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * 画像と動画で共通の見た目設定 (角・サイズ・配置・上下余白)。
 * 画像は img 自身に、動画は外側の div に同じ CSS を当てる。
 */
@Stable
class MediaStyle(defaultCorner: ImageCorner) {
    var corner by mutableStateOf(defaultCorner)
    var width by mutableStateOf("")
    var widthUnit by mutableStateOf(CssUnit.PX)
    var maxWidth by mutableStateOf("")
    var maxWidthUnit by mutableStateOf(CssUnit.PX)
    var align by mutableStateOf(ImageAlign.INLINE)
    var marginTop by mutableFloatStateOf(0f)
    var marginBottom by mutableFloatStateOf(0f)
    var marginUnit by mutableStateOf(CssUnit.EM)
    var marginLinked by mutableStateOf(true)

    private fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString()
        else "%.2f".format(v).trimEnd('0').trimEnd('.')

    /**
     * @param asWrapper 外側の div に当てる場合は true。
     *   div はもともとブロック要素なので display:block を付けない。
     */
    fun css(asWrapper: Boolean = false): String {
        val mt = marginTop.takeIf { it > 0f }?.let { fmt(it) + marginUnit.suffix }
        val mb = marginBottom.takeIf { it > 0f }?.let { fmt(it) + marginUnit.suffix }
        return buildString {
            width.trim().takeIf { it.isNotEmpty() }?.let { append("width:$it${widthUnit.suffix};") }
            maxWidth.trim().takeIf { it.isNotEmpty() }
                ?.let { append("max-width:$it${maxWidthUnit.suffix};") }

            if (!asWrapper && align != ImageAlign.INLINE) append("display:block;")

            if (align == ImageAlign.CENTER && mt != null && mt == mb) {
                append("margin:$mt auto;")
            } else {
                mt?.let { append("margin-top:$it;") }
                mb?.let { append("margin-bottom:$it;") }
                when (align) {
                    ImageAlign.LEFT -> append("margin-right:auto;")
                    ImageAlign.CENTER -> append("margin-left:auto;margin-right:auto;")
                    ImageAlign.RIGHT -> append("margin-left:auto;")
                    ImageAlign.INLINE -> Unit
                }
            }
        }
    }
}

@Composable
fun rememberMediaStyle(defaultCorner: ImageCorner): MediaStyle =
    remember(defaultCorner) { MediaStyle(defaultCorner) }

/** 角・サイズ・配置・上下余白のまとまった設定 UI */
@Composable
private fun AppearanceOptions(style: MediaStyle) {
    SectionLabel("角の形")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(38.dp)) {
        ImageCorner.entries.forEachIndexed { index, c ->
            SegmentedButton(
                selected = style.corner == c,
                onClick = { style.corner = c },
                shape = SegmentedButtonDefaults.itemShape(index, ImageCorner.entries.size),
                icon = {},
                label = { Text(c.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel("サイズ (空欄なら指定しない)")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SizeInput(
            label = "幅",
            value = style.width,
            unit = style.widthUnit,
            onValueChange = { style.width = it },
            onUnitChange = { style.widthUnit = it },
            modifier = Modifier.weight(1f),
        )
        SizeInput(
            label = "最大幅",
            value = style.maxWidth,
            unit = style.maxWidthUnit,
            onValueChange = { style.maxWidth = it },
            onUnitChange = { style.maxWidthUnit = it },
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel("配置")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(38.dp)) {
        ImageAlign.entries.forEachIndexed { index, a ->
            SegmentedButton(
                selected = style.align == a,
                onClick = { style.align = a },
                shape = SegmentedButtonDefaults.itemShape(index, ImageAlign.entries.size),
                icon = {},
                label = {
                    Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel("上下の余白 (帯をドラッグ)")
    MarginEditor(
        top = style.marginTop,
        bottom = style.marginBottom,
        unit = style.marginUnit,
        linked = style.marginLinked,
        align = style.align,
        onTop = {
            style.marginTop = it
            if (style.marginLinked) style.marginBottom = it
        },
        onBottom = {
            style.marginBottom = it
            if (style.marginLinked) style.marginTop = it
        },
        onUnit = { style.marginUnit = it },
        onLinked = {
            style.marginLinked = it
            if (it) style.marginBottom = style.marginTop
        },
    )
}

/**
 * 動画 URL を埋め込み用の URL に変換する。
 * YouTube の各種形式 (watch?v= / youtu.be / shorts) に対応し、
 * それ以外の URL はそのまま使う。
 */
fun embedUrlOf(
    url: String,
    startSeconds: Int? = null,
    endSeconds: Int? = null,
): String {
    val id = Regex("""(?:youtu\.be/|v=|/embed/|/shorts/)([A-Za-z0-9_-]{6,})""")
        .find(url)?.groupValues?.get(1) ?: return url
    val si = Regex("""[?&]si=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
    // URL 側に t= が付いていれば開始時間として引き継ぐ
    val urlStart = Regex("""[?&](?:t|start)=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    val params = buildList {
        si?.let { add("si=$it") }
        (startSeconds ?: urlStart)?.let { add("start=$it") }
        endSeconds?.let { add("end=$it") }
    }
    return buildString {
        append("https://www.youtube.com/embed/").append(id)
        if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
    }
}

/** "1:30" や "90" を秒に変換する。空欄や不正な値は null */
fun parseSeconds(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(":")
    return runCatching {
        when (parts.size) {
            1 -> parts[0].toInt()
            2 -> parts[0].toInt() * 60 + parts[1].toInt()
            3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
            else -> null
        }
    }.getOrNull()?.takeIf { it >= 0 }
}

/** 再生位置 (mm:ss または 秒) の入力欄 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it == ':' }) },
        modifier = modifier.height(FIELD_HEIGHT),
        textStyle = MaterialTheme.typography.bodyMedium
            .copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        interactionSource = interaction,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                placeholder = {
                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                },
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interaction,
                        shape = CircleShape,
                    )
                },
            )
        },
    )
}

/** 長押しで名前が出るアイコンボタン */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** メニューに出す色見本 */
@Composable
private fun ColorSwatch(hex: String) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(android.graphics.Color.parseColor(hex)))
    )
}

/** カードの CSS に合わせた配色 */
private val EDITOR_COLORS = listOf(
    "赤" to "#ac4f50",
    "ピンク" to "#b6686f",
    "紫" to "#bf79af",
    "青" to "#516a91",
    "グレー" to "#A9ABB4",
    "白" to "#F0F0F0",
)

/** ダイアログ内の小見出し */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * 上下の余白を模式図で調整する。
 * 帯を上下にドラッグすると余白が増減し、± ボタンで細かく詰められる。
 */
@Composable
private fun MarginEditor(
    top: Float,
    bottom: Float,
    unit: CssUnit,
    linked: Boolean,
    align: ImageAlign,
    onTop: (Float) -> Unit,
    onBottom: (Float) -> Unit,
    onUnit: (CssUnit) -> Unit,
    onLinked: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    // 1 単位あたりの見た目の高さ (em は大きく、px は等倍)
    val dpPerUnit = when (unit) {
        CssUnit.EM -> 18f
        CssUnit.PX -> 1f
        CssUnit.PERCENT, CssUnit.VW -> 4f
    }
    val step = if (unit == CssUnit.EM) 0.1f else 1f
    val maxValue = if (unit == CssUnit.EM) 8f else 200f

    fun fmt(v: Float) =
        if (unit == CssUnit.EM) "%.1f".format(v) else v.toInt().toString()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = linked,
                onClick = { onLinked(!linked) },
                shape = CircleShape,
                modifier = Modifier.height(30.dp),
                label = { Text("上下を揃える", style = MaterialTheme.typography.labelSmall) },
            )
            Spacer(Modifier.weight(1f))
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { open = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                ) { Text(unit.suffix, style = MaterialTheme.typography.labelMedium) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    CssUnit.entries.forEach { u ->
                        DropdownMenuItem(text = { Text(u.suffix) }, onClick = { onUnit(u); open = false })
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 模式図
        // 配置 (左端 / 中央 / 右端 / インライン) もこの模式図で確認できる
        val blockAlignment = when (align) {
            ImageAlign.LEFT -> Alignment.Start
            ImageAlign.RIGHT -> Alignment.End
            ImageAlign.CENTER, ImageAlign.INLINE -> Alignment.CenterHorizontally
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalAlignment = blockAlignment,
        ) {
            MarginBand(
                value = top,
                dpPerUnit = dpPerUnit,
                label = "上 ${fmt(top)}${unit.suffix}",
                onDelta = { dy ->
                    val d = with(density) { dy.toDp().value } / dpPerUnit
                    onTop((top + d).coerceIn(0f, maxValue))
                },
            )
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = when (align) {
                    ImageAlign.LEFT -> Arrangement.Start
                    ImageAlign.RIGHT -> Arrangement.End
                    ImageAlign.CENTER, ImageAlign.INLINE -> Arrangement.Center
                },
            ) {
                if (align == ImageAlign.INLINE) {
                    Text(
                        "文…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "画像",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface,
                    )
                }
                if (align == ImageAlign.INLINE) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "…章",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            MarginBand(
                value = bottom,
                dpPerUnit = dpPerUnit,
                label = "下 ${fmt(bottom)}${unit.suffix}",
                // 下の帯は上方向に伸びるため、指の動きと見た目を合わせるには符号を反転する
                onDelta = { dy ->
                    val d = with(density) { dy.toDp().value } / dpPerUnit
                    onBottom((bottom - d).coerceIn(0f, maxValue))
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperRow("上", fmt(top) + unit.suffix, Modifier.weight(1f),
                onMinus = { onTop((top - step).coerceIn(0f, maxValue)) },
                onPlus = { onTop((top + step).coerceIn(0f, maxValue)) })
            StepperRow("下", fmt(bottom) + unit.suffix, Modifier.weight(1f),
                onMinus = { onBottom((bottom - step).coerceIn(0f, maxValue)) },
                onPlus = { onBottom((bottom + step).coerceIn(0f, maxValue)) })
        }
    }
}

/** 模式図の余白部分。上下にドラッグして増減させる */
@Composable
private fun MarginBand(
    value: Float,
    dpPerUnit: Float,
    label: String,
    onDelta: (Float) -> Unit,
) {
    val height = (value * dpPerUnit).coerceIn(0f, 64f)
    Box(
        Modifier
            .fillMaxWidth()
            .height((height + 22f).dp)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onDelta(it) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .height(height.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onMinus, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "減らす", modifier = Modifier.size(16.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onPlus, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Add, contentDescription = "増やす", modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 数値 + 単位 の 1 行入力。
 * 他の項目 (角の形・配置のセグメントボタン) と高さ・角丸をそろえるため、
 * 通常の OutlinedTextField ではなく余白を指定できる DecorationBox 版を使う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizeInput(
    label: String,
    value: String,
    unit: CssUnit,
    onValueChange: (String) -> Unit,
    onUnitChange: (CssUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it == '.' }) },
            modifier = Modifier.weight(1f).widthIn(min = 96.dp).height(FIELD_HEIGHT),
            textStyle = MaterialTheme.typography.bodyMedium
                .copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            interactionSource = interaction,
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = inner,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interaction,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    placeholder = {
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    },
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = true,
                            isError = false,
                            interactionSource = interaction,
                            shape = CircleShape,
                        )
                    },
                )
            },
        )
        Spacer(Modifier.width(6.dp))
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(
                onClick = { open = true },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(FIELD_HEIGHT),
            ) {
                Text(unit.suffix, style = MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                CssUnit.entries.forEach { u ->
                    DropdownMenuItem(
                        text = { Text(u.suffix) },
                        onClick = { onUnitChange(u); open = false },
                    )
                }
            }
        }
    }
}

/** ダイアログ内の入力部品の共通の高さ (セグメントボタンと同じ) */
private val FIELD_HEIGHT = 38.dp

/** ツールバーに並ぶもの (検索欄・メディア追加ボタン) の共通の高さ */
private val TOOLBAR_ITEM_HEIGHT = 32.dp

/** 既存タグから選ぶ + 新規追加。スペース区切りの生入力は表に出さない */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(
    tags: String,
    allTags: List<String>,
    onTagsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = remember(tags) {
        tags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    var picker by remember { mutableStateOf(false) }
    var newTagDialog by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    fun set(list: List<String>) = onTagsChange(list.distinct().joinToString(" "))

    // 「タグ」の見出しと中身を 1 行に並べる (縦の場所を取らないように)。
    // 幅を超えたぶんは横スクロールで、右端はグラデーションで自然に消す
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "タグ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(end = 12.dp),
            modifier = Modifier.weight(1f).fadingRightEdge(20.dp),
        ) {
            items(selected, key = { it }) { tag ->
                InputChip(
                    selected = true,
                    onClick = { set(selected - tag) },
                    shape = CircleShape,
                    modifier = Modifier.height(30.dp),
                    label = { Text(formatTag(tag), style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "外す",
                            modifier = Modifier.size(14.dp),
                        )
                    },
                )
            }
            item {
                AssistChip(
                    onClick = { picker = true },
                    shape = CircleShape,
                    modifier = Modifier.height(30.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("タグを追加", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }

    if (picker) {
        val candidates = allTags.filter { it !in selected }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("タグを追加") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) {
                        Text(
                            "追加できる既存タグがありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    candidates.forEach { tag ->
                        ListItem(
                            headlineContent = {
                                Text(formatTag(tag), style = MaterialTheme.typography.bodyMedium)
                            },
                            modifier = Modifier.clickable { set(selected + tag) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picker = false; newTag = ""; newTagDialog = true }) {
                    Text("新規タグを作成")
                }
            },
            dismissButton = {
                TextButton(onClick = { picker = false }) { Text("閉じる") }
            },
        )
    }

    if (newTagDialog) {
        AlertDialog(
            onDismissRequest = { newTagDialog = false },
            title = { Text("新規タグ") },
            text = {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it.replace(" ", "") },
                    label = { Text("タグ名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTag.isNotBlank(),
                    onClick = { set(selected + newTag.trim()); newTagDialog = false },
                ) { Text("追加") }
            },
            dismissButton = {
                TextButton(onClick = { newTagDialog = false }) { Text("キャンセル") }
            },
        )
    }
}

/** フィールド内の <img> をまとめて表示する小さなプレビュー */
@Composable
private fun FieldImagePreview(html: String, mediaDir: File) {
    val names = remember(html) {
        Regex("""<img[^>]*\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
    }
    if (names.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEach { name ->
            val file = File(mediaDir, name)
            if (file.isFile) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            adjustViewBounds = true
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        }
                    },
                    update = { iv ->
                        iv.setImageURI(null)
                        iv.setImageURI(Uri.fromFile(file))
                    },
                    modifier = Modifier
                        .height(72.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

/**
 * 各フィールドを contenteditable な div として 1 つの WebView に並べる。
 * 入力とフォーカスのたびに JS 側から Kotlin へ通知が返る。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RichTextFields(
    note: AnkiNote,
    fieldNames: List<String>,
    initialFields: List<String>,
    mediaDir: File,
    onFieldsChange: (List<String>) -> Unit,
    onFocusChanged: (Int?) -> Unit,
    onFindResult: (Int, Int) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestChange by rememberUpdatedState(onFieldsChange)
    val latestFocus by rememberUpdatedState(onFocusChanged)
    val latestFind by rememberUpdatedState(onFindResult)
    val current = remember(note.id) { initialFields.toMutableList() }
    val dirState = rememberUpdatedState(mediaDir)

    // 本文が長いと loadDataWithBaseURL では途中で切れるため、ローカル URL として配る
    val pageName = remember(note.id, fieldNames) {
        "__rich_${note.id}_${System.nanoTime()}.html".also {
            EditorPages.put(it, RichEditorPage.build(fieldNames, initialFields, "AnkiEditorBridge"))
        }
    }
    DisposableEffect(pageName) { onDispose { EditorPages.remove(pageName) } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FormatWebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(0xFF1C1C1C.toInt())
                webViewClient = MediaWebViewClient(ctx, { dirState.value })
                setFindListener { active, count, _ ->
                    latestFind(if (count == 0) 0 else active + 1, count)
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onFieldChanged(index: Int, value: String) {
                            while (current.size <= index) current.add("")
                            current[index] = value
                            latestChange(current.toList())
                        }

                        @JavascriptInterface
                        fun onFocus(index: Int) = latestFocus(index)

                        @JavascriptInterface
                        fun onBlur() = latestFocus(null)

                    },
                    "AnkiEditorBridge",
                )
                onWebViewReady(this)
            }
        },
        update = { web ->
            if (web.tag != pageName) {
                web.tag = pageName
                web.loadUrl(CardRenderer.MEDIA_BASE_URL + pageName)
            }
        },
    )
}

private fun escapeText(s: String): String =
    JSONObject.quote(s).let { it.substring(1, it.length - 1) }

/**
 * ハードウェアキーボードのキー処理。
 *
 * 矢印キーは、そのままだと親のスクロールやフォーカス移動に取られてしまうため、
 * ここでカーソル移動として処理する (Shift 併用で選択範囲を伸ばす)。
 * 日本語/英語の切り替えキーは、入力方法の切り替え画面を開く。
 */
private fun handleHardwareKey(
    event: ComposeKeyEvent,
    context: android.content.Context,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val native = event.nativeKeyEvent
    val shift = native.isShiftPressed

    fun move(newPos: Int): Boolean {
        val pos = newPos.coerceIn(0, value.text.length)
        onChange(
            value.copy(
                selection = if (shift) TextRange(value.selection.start, pos) else TextRange(pos)
            )
        )
        return true
    }

    return when (native.keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> move(value.selection.end - 1)
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> move(value.selection.end + 1)
        android.view.KeyEvent.KEYCODE_DPAD_UP -> move(verticalMove(value.text, value.selection.end, -1))
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> move(verticalMove(value.text, value.selection.end, 1))
        android.view.KeyEvent.KEYCODE_MOVE_HOME -> move(lineStartOf(value.text, value.selection.end))
        android.view.KeyEvent.KEYCODE_MOVE_END -> move(lineEndOf(value.text, value.selection.end))

        // 半角/全角・かな・英数・言語切替キー
        android.view.KeyEvent.KEYCODE_ZENKAKU_HANKAKU,
        android.view.KeyEvent.KEYCODE_KANA,
        android.view.KeyEvent.KEYCODE_EISU,
        android.view.KeyEvent.KEYCODE_LANGUAGE_SWITCH -> {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showInputMethodPicker()
            true
        }

        else -> false
    }
}

private fun lineStartOf(text: String, pos: Int): Int =
    text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }

private fun lineEndOf(text: String, pos: Int): Int =
    text.indexOf('\n', pos).let { if (it < 0) text.length else it }

/** 上下移動。論理行 (改行区切り) を単位に、同じ桁位置へ移動する */
private fun verticalMove(text: String, pos: Int, delta: Int): Int {
    val start = lineStartOf(text, pos)
    val column = pos - start
    return if (delta < 0) {
        if (start == 0) return 0
        val prevEnd = start - 1
        val prevStart = lineStartOf(text, prevEnd)
        (prevStart + column).coerceAtMost(prevEnd)
    } else {
        val end = lineEndOf(text, pos)
        if (end == text.length) return text.length
        val nextStart = end + 1
        val nextEnd = lineEndOf(text, nextStart)
        (nextStart + column).coerceAtMost(nextEnd)
    }
}

/** 各フィールドから検索語の位置を拾う。戻り値は (フィールド番号, 開始位置) の一覧 */
fun findMatches(fields: List<String>, query: String): List<Pair<Int, Int>> {
    if (query.isBlank()) return emptyList()
    val hits = mutableListOf<Pair<Int, Int>>()
    fields.forEachIndexed { index, text ->
        var from = text.indexOf(query, ignoreCase = true)
        while (from >= 0) {
            hits += index to from
            from = text.indexOf(query, from + 1, ignoreCase = true)
        }
    }
    return hits
}

/** ノート内検索のバー。虫眼鏡の横に常時出しておき、件数と前後移動を添える */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteFindBar(
    query: String,
    index: Int,
    total: Int,
    onQueryChange: (String) -> Unit,
    onMove: (Boolean) -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    // Enter で確定したか (確定後は矢印で一致箇所を移動する)
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(query) { if (query.isBlank()) confirmed = false }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Search,
            contentDescription = "ノート内検索",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = query,
            onValueChange = { confirmed = false; onQueryChange(it) },
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 120.dp)
                .height(TOOLBAR_ITEM_HEIGHT)
                .focusRequester(focusRequester)
                // Enter で確定したあとは、← → で一致箇所を行き来できる
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == androidx.compose.ui.input.key.Key.Enter -> {
                            confirmed = true
                            onMove(!event.isShiftPressed)
                            true
                        }

                        confirmed && event.key == androidx.compose.ui.input.key.Key.DirectionLeft -> {
                            onMove(false)
                            true
                        }

                        confirmed && event.key == androidx.compose.ui.input.key.Key.DirectionRight -> {
                            onMove(true)
                            true
                        }

                        else -> false
                    }
                },
            textStyle = MaterialTheme.typography.labelMedium
                .copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            interactionSource = interaction,
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = query,
                    innerTextField = inner,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interaction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    placeholder = {
                        Text(
                            "ノート内検索",
                            style = MaterialTheme.typography.labelMedium,
                            // 入力済みの文字と紛れないよう、はっきり薄くする
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            maxLines = 1,
                        )
                    },
                    trailingIcon = if (query.isEmpty()) null else {
                        {
                            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "検索語を消す",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    },
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = true,
                            isError = false,
                            interactionSource = interaction,
                            shape = CircleShape,
                        )
                    },
                )
            },
        )
        // 件数と前後移動は検索中だけ出す。狭い幅で検索欄を圧迫しないように
        AnimatedVisibility(
            visible = query.isNotBlank() || !compact,
            enter = fadeIn(tween(160)) + expandHorizontally(tween(240)),
            exit = fadeOut(tween(120)) + shrinkHorizontally(tween(240)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (query.isBlank()) "" else if (total == 0) "0/0" else "$index/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // 件数が増えてもボタンが跳ねないよう、最小幅を確保しておく
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            val arrow = if (compact) 30.dp else 36.dp
            IconButton(onClick = { onMove(false) }, enabled = total > 0, modifier = Modifier.size(arrow)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前へ")
            }
            IconButton(onClick = { onMove(true) }, enabled = total > 0, modifier = Modifier.size(arrow)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "次へ")
            }
            }
        }
    }
}

/**
 * 幅が足りないときの、メディア追加をまとめたメニュー。
 * ボタン 3 つ分の幅を 1 つに畳んで、検索欄がつぶれないようにする。
 */
@Composable
private fun MediaAddMenu(
    onRefresh: (() -> Unit)?,
    onImage: () -> Unit,
    onVideo: () -> Unit,
    onAudio: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.height(TOOLBAR_ITEM_HEIGHT).padding(horizontal = 4.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, contentDescription = "追加", modifier = Modifier.size(16.dp))
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("画像を追加") },
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                onClick = { open = false; onImage() },
            )
            DropdownMenuItem(
                text = { Text("動画を追加") },
                leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                onClick = { open = false; onVideo() },
            )
            DropdownMenuItem(
                text = { Text("音声を追加") },
                leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                onClick = { open = false; onAudio() },
            )
            if (onRefresh != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("プレビューを更新") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    onClick = { open = false; onRefresh() },
                )
            }
        }
    }
}

/**
 * メディアを追加するボタン。
 * 幅が足りるときは「画像を追加」のように文字を出し、
 * 狭いときはアイコン + 「+」だけに縮めて種類を色で見分けられるようにする。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaAddButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    compact: Boolean,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.height(TOOLBAR_ITEM_HEIGHT).padding(horizontal = 4.dp),
        ) {
            Row(
                Modifier.padding(horizontal = if (compact) 10.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = if (compact) label else null, modifier = Modifier.size(16.dp))
                if (compact) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
                } else {
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

/**
 * HTML ソースを編集する欄 (WebView 版)。
 *
 * 長い本文でも入力が重くならないよう、Compose のテキスト欄ではなく
 * ブラウザの textarea を使い、その背後に色分けした表示を敷いている。
 */
@Composable
private fun HtmlSourceFields(
    note: AnkiNote,
    fieldNames: List<String>,
    initialFields: List<String>,
    mediaDir: File,
    onFieldsChange: (List<String>) -> Unit,
    onFocusChanged: (Int?) -> Unit,
    onCommand: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestChange by rememberUpdatedState(onFieldsChange)
    val latestFocus by rememberUpdatedState(onFocusChanged)
    val latestCommand by rememberUpdatedState(onCommand)
    val current = remember(note.id) { initialFields.toMutableList() }
    val dirState = rememberUpdatedState(mediaDir)

    // ページはローカル URL として配る (大きい本文でも切れないように)
    val pageName = remember(note.id, fieldNames) {
        "__source_${note.id}_${System.nanoTime()}.html".also {
            EditorPages.put(
                it,
                HtmlSourceEditorPage.build(fieldNames, initialFields, "AnkiSourceBridge"),
            )
        }
    }
    DisposableEffect(pageName) { onDispose { EditorPages.remove(pageName) } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(0xFF1C1C1C.toInt())
                webViewClient = MediaWebViewClient(ctx, { dirState.value })
                // ページ内のエラーを見失わないよう、コンソール出力を記録に流す
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(
                        message: android.webkit.ConsoleMessage
                    ): Boolean {
                        android.util.Log.d(
                            "AnkiEditorJS",
                            "${message.message()} @${message.lineNumber()}",
                        )
                        return true
                    }
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onFieldChanged(index: Int, value: String) {
                            while (current.size <= index) current.add("")
                            current[index] = value
                            latestChange(current.toList())
                        }

                        @JavascriptInterface
                        fun onFocus(index: Int) = latestFocus(index)

                        @JavascriptInterface
                        fun onBlur() = latestFocus(null)

                        @JavascriptInterface
                        fun onCommand(name: String) = latestCommand(name)
                    },
                    "AnkiSourceBridge",
                )
                onWebViewReady(this)
            }
        },
        update = { web ->
            if (web.tag != pageName) {
                web.tag = pageName
                web.loadUrl(CardRenderer.MEDIA_BASE_URL + pageName)
            }
        },
    )
}
