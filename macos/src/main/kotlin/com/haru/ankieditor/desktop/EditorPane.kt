package com.haru.ankieditor.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.haru.ankieditor.AnkiNote
import com.haru.ankieditor.EditorMode
import com.haru.ankieditor.EditorShortcuts
import com.haru.ankieditor.HtmlSearchTransformation
import com.haru.ankieditor.HtmlSyntaxTransformation
import com.haru.ankieditor.RichEditorPage
import com.haru.ankieditor.TAB_WIDTH
import com.haru.ankieditor.TabIndentTransformation
import com.haru.ankieditor.WindowedHtmlTransformation
import com.haru.ankieditor.highlightWindow
import com.haru.ankieditor.insertTab
import kotlinx.coroutines.launch

/** ツールバーに並ぶものの高さ (Android 版と同じ考え方) */
private val TOOLBAR_ITEM_HEIGHT = 30.dp

/**
 * ノートの編集ペイン。
 *
 * HTML の編集は Compose のテキスト欄で行う。ページの中に文字を打つ形にすると、
 * オフスクリーン描画では日本語変換が届かないため。
 * 色分け・字下げ・ノート内検索は Android 版と同じ共有コードを使う。
 */
@Composable
fun EditorPane(
    note: AnkiNote,
    draft: DraftState,
    allTags: List<String>,
    mode: EditorMode,
    dirty: Boolean,
    saving: Boolean,
    server: MediaServer,
    onDirty: () -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onInsertImage: (() -> Unit)? = null,
    /** ⌘F が押された回数。増えたら検索欄へ移る */
    findRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    val fieldNames = remember(note.id) { draft.fieldNames }
    val latestFields = remember(note.id) { draft.fields.toMutableList() }
    var tags by remember(note.id) { mutableStateOf(draft.tags) }

    val handle = rememberWebHandle()
    val htmlScroll = rememberScrollState()
    var findQuery by remember(note.id) { mutableStateOf("") }
    var findIndex by remember(note.id) { mutableStateOf(0) }
    var findTotal by remember(note.id) { mutableStateOf(0) }
    var currentHit by remember(note.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    // HTML 欄へ「ここへ飛べ」と伝えるための窓口
    var jumper by remember(note.id) { mutableStateOf<((Int, Int, Int) -> Unit)?>(null) }
    val findFocus = remember { FocusRequester() }
    LaunchedEffect(findRequest) {
        if (findRequest > 0) runCatching { findFocus.requestFocus() }
    }

    fun publish(values: List<String>) {
        latestFields.clear()
        latestFields.addAll(values)
        draft.fields = values
        onDirty()
    }

    val bridge = remember(note.id) {
        EditorBridge { index, value ->
            val values = latestFields.toMutableList()
            while (values.size <= index) values.add("")
            values[index] = value
            publish(values)
        }
    }

    // テキストモードのページ (Chromium で表示する)
    val page = remember(note.id, mode, fieldNames) {
        if (mode != EditorMode.RICH) return@remember null
        val name = "__editor_${note.id}_${System.nanoTime()}.html"
        server.putPage(name, RichEditorPage.build(fieldNames, latestFields.toList(), "AnkiBridge"))
        name
    }
    DisposableEffect(page) { onDispose { page?.let { server.removePage(it) } } }

    Column(modifier) {
        // ---- ヘッダー ----
        Row(
            // ウインドウの縁との余白を、左の検索列と同じ間隔にそろえる
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.height(TOOLBAR_ITEM_HEIGHT)) {
                EditorMode.entries.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        shape = SegmentedButtonDefaults.itemShape(index, EditorMode.entries.size),
                        icon = {},
                        label = {
                            Text(
                                m.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
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
            TextButton(onClick = onSave, enabled = dirty && !saving) {
                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存")
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "編集を終了") }
        }

        // ---- 検索とメディア追加 ----
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 620.dp
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoteFindBar(
                    query = findQuery,
                    index = findIndex,
                    total = findTotal,
                    compact = compact,
                    enabled = mode == EditorMode.HTML,
                    modifier = Modifier.weight(1f),
                    focusRequester = findFocus,
                    onQueryChange = { q ->
                        findQuery = q
                        val hits = findMatches(latestFields.toList(), q)
                        findTotal = hits.size
                        findIndex = if (hits.isEmpty()) 0 else 1
                        hits.firstOrNull()?.let { (field, at) ->
                            currentHit = field to at
                            jumper?.invoke(field, at, q.length)
                        }
                    },
                    onMove = { forward ->
                        val hits = findMatches(latestFields.toList(), findQuery)
                        if (hits.isEmpty()) return@NoteFindBar
                        val next = if (forward) {
                            if (findIndex >= hits.size) 1 else findIndex + 1
                        } else {
                            if (findIndex <= 1) hits.size else findIndex - 1
                        }
                        findIndex = next
                        val (field, at) = hits[next - 1]
                        currentHit = field to at
                        jumper?.invoke(field, at, findQuery.length)
                    },
                    onClear = {
                        findQuery = ""
                        findTotal = 0
                        findIndex = 0
                        currentHit = null
                    },
                )
                Spacer(Modifier.width(8.dp))
                if (onInsertImage != null) {
                    MediaAddButton(
                        "画像を追加",
                        Icons.Default.Image,
                        compact,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                        onInsertImage,
                    )
                }
            }
        }

        when (mode) {
            EditorMode.HTML -> HtmlSourceFields(
                note = note,
                fieldNames = fieldNames,
                initialFields = latestFields.toList(),
                findQuery = findQuery,
                currentHit = currentHit,
                scrollState = htmlScroll,
                onValuesChange = { publish(it) },
                onRegisterJump = { jumper = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            EditorMode.RICH -> WebPane(
                handle = handle,
                html = page,
                background = Color(0xFF1C1C1C),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onHtmlChanged = { h, name -> h.loadHtml(server.baseUrl + name) },
                onBridge = { message -> bridge.handle(message) },
            )
        }

        HorizontalDivider()
        TagEditor(
            tags = tags,
            allTags = allTags,
            onTagsChange = {
                tags = it
                draft.tags = it
                onDirty()
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

/**
 * HTML を直接編集する欄。
 * 色分けと字下げ、検索の強調は Android 版と同じ共有の実装を使っている。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HtmlSourceFields(
    note: AnkiNote,
    fieldNames: List<String>,
    initialFields: List<String>,
    findQuery: String,
    currentHit: Pair<Int, Int>?,
    scrollState: androidx.compose.foundation.ScrollState,
    onValuesChange: (List<String>) -> Unit,
    onRegisterJump: ((Int, Int, Int) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val values = remember(note.id, fieldNames.size) {
        mutableStateListOf<TextFieldValue>().apply {
            fieldNames.indices.forEach { add(TextFieldValue(initialFields.getOrElse(it) { "" })) }
        }
    }
    val offsets = remember(note.id) { HashMap<Int, Float>() }
    val heights = remember(note.id) { HashMap<Int, Float>() }
    val layouts = remember(note.id) { HashMap<Int, TextLayoutResult>() }
    var boxTop by remember(note.id) { mutableStateOf(0f) }
    var boxHeight by remember(note.id) { mutableStateOf(0f) }

    fun push() = onValuesChange(values.map { it.text })

    // 検索から呼ばれる「その位置へ飛ぶ」処理
    LaunchedEffect(note.id) {
        onRegisterJump { field, at, length ->
            if (field > values.lastIndex) return@onRegisterJump
            values[field] = values[field].copy(selection = TextRange(at, at + length))
            scope.launch {
                val top = offsets[field] ?: return@launch
                val text = values[field].text
                val contentTop = top - boxTop + scrollState.value
                val layout = layouts[field]
                val inField = if (layout == null) {
                    (heights[field] ?: 0f) * (at.toFloat() / text.length.coerceAtLeast(1))
                } else {
                    layout.getLineTop(layout.getLineForOffset(at.coerceIn(0, text.length)))
                }
                val target = contentTop + 24f * density + inField - boxHeight / 2f
                scrollState.animateScrollTo(
                    target.coerceIn(0f, scrollState.maxValue.toFloat()).toInt()
                )
            }
        }
    }

    Column(
        modifier
            .onGloballyPositioned {
                boxTop = it.positionInRoot().y
                boxHeight = it.size.height.toFloat()
            }
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fieldNames.forEachIndexed { i, name ->
            if (i > values.lastIndex) return@forEachIndexed
            var focused by remember { mutableStateOf(false) }

            // Android 版の HTML エディタと同じ見た目にそろえる
            // (ラベルは欄の上、角丸の枠、等幅の文字)
            Column {
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF161616))
                        .border(
                            width = 1.dp,
                            color = if (focused) MaterialTheme.colorScheme.primary
                            else Color(0xFF3A3F45),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = values[i],
                        onValueChange = { values[i] = it; push() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .onGloballyPositioned {
                                offsets[i] = it.positionInRoot().y
                                heights[i] = it.size.height.toFloat()
                            }
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // Tab は字下げとして扱う (VS Code と同じ)
                                if (event.key == Key.Tab) {
                                    values[i] = insertTab(values[i], event.isShiftPressed)
                                    push()
                                    return@onPreviewKeyEvent true
                                }
                                // 矢印キーはこちらで即座に動かす。
                                // 標準の処理は画面の更新を待つため、text expander のような
                                // 高速なキー送出だと移動を取りこぼしてカーソルがずれる
                                if ((event.key == Key.DirectionLeft || event.key == Key.DirectionRight) &&
                                    !event.isAltPressed && !event.isMetaPressed
                                ) {
                                    val value = values[i]
                                    val step = if (event.key == Key.DirectionLeft) -1 else 1
                                    val to = (value.selection.end + step)
                                        .coerceIn(0, value.text.length)
                                    values[i] = value.copy(
                                        selection = if (event.isShiftPressed) {
                                            TextRange(value.selection.start, to)
                                        } else TextRange(to)
                                    )
                                    return@onPreviewKeyEvent true
                                }
                                if (event.key == Key.Backspace &&
                                    !event.isAltPressed && !event.isMetaPressed
                                ) {
                                    val value = values[i]
                                    val sel = value.selection
                                    val (from, to) = if (sel.min != sel.max) {
                                        sel.min to sel.max
                                    } else {
                                        (sel.min - 1).coerceAtLeast(0) to sel.min
                                    }
                                    if (to > from) {
                                        values[i] = TextFieldValue(
                                            text = value.text.removeRange(from, to),
                                            selection = TextRange(from),
                                        )
                                        push()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if ((event.key == Key.DirectionUp || event.key == Key.DirectionDown) &&
                                    !event.isAltPressed && !event.isMetaPressed
                                ) {
                                    val moved = moveCaretVertically(
                                        value = values[i],
                                        layout = layouts[i],
                                        up = event.key == Key.DirectionUp,
                                        extend = event.isShiftPressed,
                                    )
                                    if (moved != null) {
                                        values[i] = moved
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                // その他の操作 (行の移動・複製・コメントなど)
                                val handled = shortcutKeyOf(event.key)?.let { key ->
                                    EditorShortcuts.handle(
                                        key = key,
                                        command = event.isMetaPressed || event.isCtrlPressed,
                                        shift = event.isShiftPressed,
                                        alt = event.isAltPressed,
                                        value = values[i],
                                    )
                                }
                                if (handled != null) {
                                    values[i] = handled
                                    push()
                                    true
                                } else false
                            },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { layouts[i] = it },
                        visualTransformation = run {
                            // 長い本文では、見えている辺りだけ色を付けて打鍵を軽く保つ
                            val value = values[i]
                            val window = highlightWindow(value.text.length, value.selection.start)
                            val base = if (window == null) HtmlSyntaxTransformation
                            else WindowedHtmlTransformation(window.first, window.last)
                            val colored = if (findQuery.isBlank()) base
                            else HtmlSearchTransformation(
                                base,
                                findQuery,
                                currentHit?.takeIf { it.first == i }?.second,
                            )
                            TabIndentTransformation(colored)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 上下の矢印でカーソルを 1 行動かす。
 * 組まれた行 (TextLayoutResult) を見て、いまの横位置を保ったまま隣の行へ移す。
 */
private fun moveCaretVertically(
    value: TextFieldValue,
    layout: TextLayoutResult?,
    up: Boolean,
    extend: Boolean,
): TextFieldValue? {
    val text = value.text
    if (layout == null) return null
    val caret = value.selection.end.coerceIn(0, text.length)
    val line = layout.getLineForOffset(caret)
    val target = if (up) line - 1 else line + 1
    if (target < 0 || target > layout.lineCount - 1) return null

    val x = layout.getHorizontalPosition(caret, usePrimaryDirection = true)
    val y = (layout.getLineTop(target) + layout.getLineBottom(target)) / 2f
    val to = layout.getOffsetForPosition(androidx.compose.ui.geometry.Offset(x, y))
        .coerceIn(0, text.length)
    return value.copy(
        selection = if (extend) TextRange(value.selection.start, to) else TextRange(to)
    )
}

/** Compose のキーを、共有のキー操作の種類に読み替える */
private fun shortcutKeyOf(key: Key): EditorShortcuts.Key? = when (key) {
    Key.Enter, Key.NumPadEnter -> EditorShortcuts.Key.ENTER
    Key.DirectionUp -> EditorShortcuts.Key.UP
    Key.DirectionDown -> EditorShortcuts.Key.DOWN
    Key.DirectionLeft -> EditorShortcuts.Key.LEFT
    Key.DirectionRight -> EditorShortcuts.Key.RIGHT
    Key.Backspace -> EditorShortcuts.Key.BACKSPACE
    Key.Slash -> EditorShortcuts.Key.SLASH
    Key.LeftBracket -> EditorShortcuts.Key.BRACKET_LEFT
    Key.RightBracket -> EditorShortcuts.Key.BRACKET_RIGHT
    Key.D -> EditorShortcuts.Key.D
    Key.K -> EditorShortcuts.Key.K
    Key.X -> EditorShortcuts.Key.X
    Key.L -> EditorShortcuts.Key.L
    Key.MoveHome -> EditorShortcuts.Key.HOME
    Key.MoveEnd -> EditorShortcuts.Key.END
    else -> null
}

/** 一致箇所 (フィールド番号 と 開始位置) を全部返す */
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

/** ページから届く連絡をほどく係 (テキストモードで使う) */
class EditorBridge(private val onChanged: (Int, String) -> Unit) {
    fun handle(message: String) {
        if (!message.startsWith("field:")) return
        val rest = message.removePrefix("field:")
        val index = rest.substringBefore(':').toIntOrNull() ?: return
        onChanged(index, rest.substringAfter(':'))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteFindBar(
    query: String,
    index: Int,
    total: Int,
    compact: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onMove: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Search,
            "ノート内検索",
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 120.dp)
                .height(TOOLBAR_ITEM_HEIGHT)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.labelMedium
                .copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interaction,
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = query,
                    innerTextField = inner,
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interaction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    placeholder = {
                        Text(
                            if (enabled) "ノート内検索" else "HTML モードで使えます",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            maxLines = 1,
                        )
                    },
                    trailingIcon = if (query.isEmpty()) null else {
                        {
                            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "検索語を消す", Modifier.size(14.dp))
                            }
                        }
                    },
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = false,
                            interactionSource = interaction,
                            shape = CircleShape,
                        )
                    },
                )
            },
        )
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
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconButton(onClick = { onMove(false) }, enabled = total > 0, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前へ")
                }
                IconButton(onClick = { onMove(true) }, enabled = total > 0, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "次へ")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaAddButton(
    label: String,
    icon: ImageVector,
    compact: Boolean,
    container: Color,
    content: Color,
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
                Icon(icon, if (compact) label else null, Modifier.size(16.dp))
                if (compact) {
                    Icon(Icons.Default.Add, null, Modifier.size(12.dp))
                } else {
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
