package com.haru.ankieditor.desktop


import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
    statusMessage: String? = null,
    syncing: Boolean = false,
    lockWidthDuringWindowResize: Boolean = false,
    /** ⌘F が押された回数。増えたら検索バーを開く */
    findRequest: Int = 0,
) {
    val handle = rememberWebHandle()
    val loadedNote = remember { longArrayOf(-1L) }
    // ページはローカルサーバーから配る (画像や音声を相対の名前で読めるように)
    val pageName = remember(note?.id) { "__preview_${note?.id ?: 0}.html" }
    var webReady by remember(note?.id) { mutableStateOf(false) }

    // ノートを選ぶまではカードの白地を出さず、アプリの地色のままにする
    val hasCard = note != null && html != null
    // ページ自体に操作を持たせる (OS に描かせる方式では上に重ねられないため)
    // 操作ボタンの表示状態は本文HTMLから分離する。編集画面を開閉するだけで
    // bodyを差し替えたりJCEFを再描画したりしない。
    val page = remember(html) {
        html?.let {
            it.replace(
                "</body>",
                CardHtml.overlay(
                    // 編集と検索はタイトルバー側に置いた
                    canEdit = false,
                    canAdd = false,
                    canFind = false,
                    canRefresh = true,
                ) + "</body>",
            )
        }
    }
    val controlsScript = remember(
        onEdit != null, onAdd != null, findable, onRefresh != null,
    ) {
        mapOf(
            "ankiEdit" to false,
            "ankiAdd" to false,
            "ankiFindOpen" to false,
            "ankiRefresh" to (onRefresh != null),
        ).entries.joinToString(";") { (id, shown) ->
            "(function(e){if(e)e.style.display='${if (shown) "flex" else "none"}'})(document.getElementById('$id'))"
        }
    }

    // 下地はカードの地色 (暗い方) に合わせる。白にすると幅を変えたときに白帯が見える
    val backdrop = MaterialTheme.colorScheme.surface
    Box(modifier.background(backdrop).clipToBounds()) {
        // 起動時からネイティブ Chromium を作って待機させる。
        // OSR/Canvas への画素転送は行わず、Chromium のウインドウを直接表示する。
        NativeWebPane(
            handle = handle,
            html = page,
            background = backdrop,
            modifier = Modifier.fillMaxSize(),
            visible = hasCard && webReady && !loading && !covered,
            lockWidthDuringWindowResize = lockWidthDuringWindowResize,
            onPageReady = {
                handle.run(com.haru.ankieditor.PreviewFindJs.script)
                handle.run(controlsScript)
                if (handle.browser?.url?.contains(pageName) == true) {
                    webReady = true
                }
            },
            onHtmlChanged = { h, content ->
                val id = note?.id ?: -1L
                if (loadedNote[0] != id) {
                    webReady = false
                    loadedNote[0] = id
                    h.loadedTag = content
                    server.putPage(pageName, content)
                    h.loadHtml(server.baseUrl + pageName)
                } else if (h.loadedTag != content) {
                    h.loadedTag = content
                    // 同じノートの再描画では本文だけ入れ替える (位置が戻らないように)
                    h.run(CardHtml.replaceBodyScript(content))
                    webReady = true
                }
            },
            onBridge = { message ->
                when (message) {
                    "ui:add" -> onAdd?.invoke()
                    "ui:edit" -> onEdit?.invoke()
                    "ui:refresh" -> onRefresh?.invoke()
                }
            },
        )

        if (!hasCard && !loading) {
            Text(
                "ノートを選ぶとここに実際のカードが表示されます",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = loading || (hasCard && !webReady),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f),
                shadowElevation = 8.dp,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }

        LaunchedEffect(findRequest) {
            if (findRequest > 0) handle.run("window.__ankiFindOpen && window.__ankiFindOpen()")
        }

        // ボタンだけを表示・非表示にする。本文やviewportには触れない。
        LaunchedEffect(
            onEdit != null, onAdd != null, findable, onRefresh != null,
        ) {
            handle.run(controlsScript)
        }

        PreviewStatus(statusMessage, syncing, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PreviewStatus(message: String?, syncing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "syncRotation")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "syncRotation",
    )
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(180)) + slideInVertically { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically { it / 2 },
        modifier = modifier.padding(bottom = 22.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (syncing) {
                    Icon(
                        Icons.Rounded.Refresh,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Text(message.orEmpty(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
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
        FloatingButton(Icons.Rounded.Refresh, "プレビューを更新", small = small, onClick = onRefresh)
    }
    if (onAdd != null) {
        FloatingButton(Icons.Rounded.Add, "新しいノート", accent = true, small = small, onClick = onAdd)
    }
    if (findable) {
        FloatingButton(Icons.Rounded.Search, "このカードを検索", small = small, onClick = onToggleFind)
    }
    if (onEdit != null) {
        FloatingButton(Icons.Rounded.Edit, "編集", small = small, onClick = onEdit)
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
