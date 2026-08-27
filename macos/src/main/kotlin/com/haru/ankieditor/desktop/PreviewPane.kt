package com.haru.ankieditor.desktop

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.haru.ankieditor.AnkiNote

/**
 * カードのプレビュー。
 * Android 版と同じく、同じノートを編集している間は読み込み直さずに本文だけ差し替えて、
 * スクロール位置が先頭に戻らないようにしている。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewPane(
    note: AnkiNote?,
    html: String?,
    server: MediaServer,
    loading: Boolean,
    zoom: Double,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    findable: Boolean = false,
    /** 何かを前に出している間は、ページを引っ込める (ウインドウ描画のとき用) */
    covered: Boolean = false,
    /** macOS のウインドウ端を掴んで live resize している間 */
    windowResizing: Boolean = false,
    /** 一覧をしまっているときに戻すための操作 */
    onShowList: (() -> Unit)? = null,
    /** ⌘F が押された回数。増えたら検索バーを開く */
    findRequest: Int = 0,
) {
    val handle = rememberWebHandle()
    val resizeHandle = rememberWebHandle()
    val loadedNote = remember { longArrayOf(-1L) }
    // ページはローカルサーバーから配る (画像や音声を相対の名前で読めるように)
    val pageName = remember(note?.id) { "__preview_${note?.id ?: 0}.html" }

    // ノートを選ぶまではカードの白地を出さず、アプリの地色のままにする
    val hasCard = note != null && html != null
    // ページ自体に操作を持たせる (OS に描かせる方式では上に重ねられないため)
    val page = remember(
        html, onEdit != null, onAdd != null, findable, onRefresh != null, onShowList != null,
    ) {
        html?.let {
            it.replace(
                "</body>",
                CardHtml.overlay(
                    canEdit = onEdit != null,
                    canAdd = onAdd != null,
                    canFind = findable,
                    canRefresh = onRefresh != null,
                    canShowList = onShowList != null,
                ) + "</body>",
            )
        }
    }

    // 下地はカードの地色 (暗い方) に合わせる。白にすると幅を変えたときに白帯が見える
    val backdrop = MaterialTheme.colorScheme.surface
    Box(modifier.background(backdrop)) {
        // 表示するものが無いときは、ページの部品自体を置かない。
        // 置いたままだと、その下地が画面を覆って Compose の表示が見えなくなる
        var everShown by remember { mutableStateOf(false) }
        LaunchedEffect(hasCard) { if (hasCard) everShown = true }
        val showWeb = (hasCard || everShown) && !covered
        // ネイティブ NSView は AppKit の live resize 中に Compose より一拍遅れて
        // 古い枠へ描くため、隣の編集欄への重なりや白い未描画帯が発生する。
        // 同じ Chromium の OSR 画面を常に背面に用意し、ドラッグ中だけこちらを見せる。
        if (showWeb) WebPane(
            handle = resizeHandle,
            html = page,
            background = backdrop,
            modifier = Modifier.fillMaxSize(),
            active = windowResizing,
            onPageReady = { resizeHandle.run(com.haru.ankieditor.PreviewFindJs.script) },
            onHtmlChanged = { h, content ->
                val fallbackName = "__preview_resize_${note?.id ?: 0}.html"
                server.putPage(fallbackName, content)
                h.loadHtml(server.baseUrl + fallbackName)
            },
            onBridge = { message ->
                when (message) {
                    "ui:add" -> onAdd?.invoke()
                    "ui:edit" -> onEdit?.invoke()
                    "ui:refresh" -> onRefresh?.invoke()
                    "ui:showlist" -> onShowList?.invoke()
                }
            },
        )

        if (showWeb) NativeWebPane(
            handle = handle,
            html = page,
            background = backdrop,
            modifier = Modifier.fillMaxSize(),
            // ブラウザ自体は破棄せず、live resize 中だけ NSView を引っ込める。
            // ドラッグ終了後は同じページ・スクロール位置のまま即座に復帰する。
            visible = !windowResizing,
            onPageReady = { handle.run(com.haru.ankieditor.PreviewFindJs.script) },
            onHtmlChanged = { h, content ->
                val id = note?.id ?: -1L
                if (loadedNote[0] != id) {
                    loadedNote[0] = id
                    h.loadedTag = content
                    server.putPage(pageName, content)
                    h.loadHtml(server.baseUrl + pageName)
                } else if (h.loadedTag != content) {
                    h.loadedTag = content
                    // 同じノートの再描画では本文だけ入れ替える (位置が戻らないように)
                    h.run(CardHtml.replaceBodyScript(content))
                }
            },
            onBridge = { message ->
                when (message) {
                    "ui:add" -> onAdd?.invoke()
                    "ui:edit" -> onEdit?.invoke()
                    "ui:refresh" -> onRefresh?.invoke()
                    "ui:showlist" -> onShowList?.invoke()
                }
            },
        )

        // live resize の前後でスクロール位置を引き継ぐ。背面の表示が先頭に戻ったり、
        // ドラッグ終了時に元の位置へ跳ねたりしないようにする。
        LaunchedEffect(windowResizing, showWeb) {
            if (!showWeb) return@LaunchedEffect
            if (windowResizing) {
                handle.run("JSON.stringify({x:scrollX,y:scrollY})") { raw ->
                    val point = runCatching { org.json.JSONObject(raw?.toString().orEmpty()) }.getOrNull()
                    val x = point?.optDouble("x", 0.0) ?: 0.0
                    val y = point?.optDouble("y", 0.0) ?: 0.0
                    resizeHandle.run("scrollTo($x,$y)")
                }
            } else {
                resizeHandle.run("JSON.stringify({x:scrollX,y:scrollY})") { raw ->
                    val point = runCatching { org.json.JSONObject(raw?.toString().orEmpty()) }.getOrNull()
                    val x = point?.optDouble("x", 0.0) ?: 0.0
                    val y = point?.optDouble("y", 0.0) ?: 0.0
                    handle.run("scrollTo($x,$y)")
                }
            }
        }

        if (!hasCard) {
            Text(
                "ノートを選ぶとここに実際のカードが表示されます",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // カードが出ていないときは、ページ側のボタンが無いのでこちらに出す
        if (onShowList != null && (!hasCard || covered)) {
            Surface(
                onClick = onShowList,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                // ウインドウボタン (信号機) の右に並べる
                modifier = Modifier.align(Alignment.TopStart).padding(start = 86.dp, top = 12.dp)
                    .size(30.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        "一覧を表示",
                        Modifier.size(18.dp),
                    )
                }
            }
        }

        LaunchedEffect(findRequest) {
            if (findRequest > 0) handle.run("window.__ankiFindOpen && window.__ankiFindOpen()")
        }

        if (loading) {
            LinearProgressIndicator(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp)
            )
        }
    }
}

/** 追加・検索・編集・更新のボタン (置き場所は呼び出し側が決める) */
@Composable
private fun PreviewButtons(
    onAdd: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    findable: Boolean,
    small: Boolean = false,
    onToggleFind: () -> Unit,
) {
    if (onRefresh != null) {
        FloatingButton(Icons.Default.Refresh, "プレビューを更新", small = true, onClick = onRefresh)
    }
    if (onAdd != null) {
        FloatingButton(Icons.Default.Add, "新しいノート", accent = true, small = small, onClick = onAdd)
    }
    if (findable) {
        FloatingButton(Icons.Default.Search, "このカードを検索", small = small, onClick = onToggleFind)
    }
    if (onEdit != null) {
        FloatingButton(Icons.Default.Edit, "編集", small = small, onClick = onEdit)
    }
}

/**
 * カードの上に浮かべる小さなボタン。
 * macOS の見た目に合わせて、丸ではなく控えめな角丸の四角にしている。
 */
@Composable
private fun FloatingButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Boolean = false,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    // Android 版と同じ丸いボタン。色の役割もそろえている
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = when {
            accent -> MaterialTheme.colorScheme.primaryContainer
            small -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = when {
            accent -> MaterialTheme.colorScheme.onPrimaryContainer
            small -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        },
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
        modifier = Modifier.size(if (small) 40.dp else 48.dp),
    ) {
        Icon(icon, label, Modifier.size(if (small) 18.dp else 20.dp))
    }
}
