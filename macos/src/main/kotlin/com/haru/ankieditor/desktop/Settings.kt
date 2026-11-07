package com.haru.ankieditor.desktop


import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haru.ankieditor.EditorMode
import com.haru.ankieditor.ImageCorner
import com.haru.ankieditor.MediaCleanupPane
import com.haru.ankieditor.SettingsLabel
import com.haru.ankieditor.SettingsPicker
import com.haru.ankieditor.SettingsScreen
import com.haru.ankieditor.SettingsSection
import com.haru.ankieditor.SortMode
import com.haru.ankieditor.TemplateEditor
import com.haru.ankieditor.UnusedMedia
import kotlin.math.roundToInt

/**
 * 設定画面。
 * 画面の作り (一覧 → 詳細) と中身の部品は Android 版と同じものを共有し、
 * ここでは macOS 固有の見せ方だけを足している。
 */
@Composable
fun SettingsDialog(
    repo: DesktopRepo,
    prefs: Prefs,
    auth: Auth?,
    decks: List<String>,
    collectionTags: List<String>,
    onLogout: () -> Unit,
    onOpenLogin: () -> Unit,
    onMessage: (String) -> Unit,
    onRefreshMeta: () -> Unit,
    onPreviewBoundsChanged: (Float, Float) -> Unit = { _, _ -> },
    /** 既定のプレビュー幅が変わったときに、いまの表示にも反映する */
    onPreviewWidthChanged: (Float) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var defaultDeck by remember { mutableStateOf(prefs.defaultDeck) }
    var sortMode by remember { mutableStateOf(prefs.sortMode) }
    var editorMode by remember { mutableStateOf(prefs.editorMode) }
    var imageCorner by remember { mutableStateOf(prefs.imageCorner) }
    var textZoom by remember { mutableStateOf(prefs.textZoom) }
    var previewMin by remember { mutableStateOf(prefs.previewMinWidth.toInt().toString()) }
    var previewDefault by remember { mutableStateOf(prefs.previewWidth.toInt().toString()) }
    var previewMax by remember { mutableStateOf(prefs.previewMaxWidth.toInt().toString()) }

    // 画面全体を薄暗くして、中央にパネルを浮かせる (Claude のデスクトップ版と同じ出し方)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MacShapes.panel,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 30.dp,
            // macOS のシートは細い縁取りが入る
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.80f)
                .widthIn(max = 860.dp)
                // パネルの中のタップで閉じないようにする
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
        Column(Modifier.fillMaxSize()) {
            // macOS のシートのタイトルバー。見出しは中央、閉じるは左上
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 12.dp),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart).size(26.dp),
                ) {
                    Icon(Icons.Rounded.Close, "閉じる", Modifier.size(15.dp))
                }
                Text(
                    "設定",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "palt",
                    ),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsScreen(
                twoPane = true,
                // 見出しはこのシート側で出している
                showTopBar = false,
                macStyle = true,
                onBack = onDismiss,
                summaryOf = { section ->
                    when (section) {
                        SettingsSection.ACCOUNT -> if (auth != null) "ログイン済み" else "未ログイン"
                        SettingsSection.SEARCH ->
                            "${defaultDeck ?: "すべてのデッキ"} · ${sortMode.label}"
                        SettingsSection.TAGS ->
                            if (prefs.pinnedTags.isEmpty()) "未設定"
                            else prefs.pinnedTags.joinToString(" ")
                        SettingsSection.EDITOR -> "${editorMode.label} · 画像 ${imageCorner.label}"
                        SettingsSection.TEMPLATE -> "表面・裏面・スタイル"
                        SettingsSection.MEDIA -> "未使用の画像・動画・音声を削除"
                        SettingsSection.APPEARANCE -> "文字サイズ $textZoom%"
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
                                Button(onClick = onOpenLogin, shape = CircleShape) {
                                    Text(if (auth != null) "ログインし直す" else "ログイン")
                                }
                                if (auth != null) {
                                    OutlinedButton(onClick = onLogout, shape = CircleShape) {
                                        Text("ログアウト")
                                    }
                                }
                            }
                        }

                        SettingsSection.SEARCH -> {
                            SettingsLabel("起動時の検索条件")
                            SettingsPicker(
                                label = "デッキ",
                                current = defaultDeck ?: "すべてのデッキ",
                                options = listOf("すべてのデッキ") + decks,
                                labelOf = { it },
                            ) {
                                defaultDeck = if (it == "すべてのデッキ") null else it
                                prefs.defaultDeck = defaultDeck
                            }
                            SettingsPicker(
                                label = "並び順",
                                current = sortMode,
                                options = SortMode.entries,
                                labelOf = { it.label },
                            ) { sortMode = it; prefs.sortMode = it }
                        }

                        SettingsSection.TAGS -> {
                            SettingsLabel(
                                "よく使うタグ",
                                "選んだタグは検索欄の先頭に出ます",
                            )
                            TagPicker(
                                all = collectionTags,
                                selected = prefs.pinnedTags,
                                onChange = { prefs.pinnedTags = it },
                            )
                            Spacer(Modifier.height(16.dp))
                            SettingsLabel(
                                "新しいノートに付けるタグ",
                                "「+」で作ったノートに最初から入ります",
                            )
                            TagPicker(
                                all = collectionTags,
                                selected = prefs.defaultTags,
                                onChange = { prefs.defaultTags = it },
                            )
                        }

                        SettingsSection.EDITOR -> {
                            SettingsPicker(
                                label = "既定の入力方式",
                                current = editorMode,
                                options = EditorMode.entries,
                                labelOf = { it.label },
                            ) { editorMode = it; prefs.editorMode = it }
                            SettingsPicker(
                                label = "画像の角",
                                current = imageCorner,
                                options = ImageCorner.entries,
                                labelOf = { it.label },
                            ) { imageCorner = it; prefs.imageCorner = it }
                        }

                        SettingsSection.TEMPLATE -> {
                            TemplateEditor(
                                repo = repo,
                                onSaved = { message ->
                                    onMessage(message)
                                    onRefreshMeta()
                                },
                            )
                        }

                        SettingsSection.MEDIA -> {
                            MediaCleanupPane(
                                repo = repo,
                                onDeleted = onMessage,
                                thumbnail = { name, kind, size, onClick ->
                                    MediaThumb(java.io.File(repo.mediaDir, name), kind, size, onClick)
                                },
                                preview = { item, onDismiss2 ->
                                    MediaPreviewDialog(
                                        java.io.File(repo.mediaDir, item.name),
                                        item,
                                        onDismiss2,
                                    )
                                },
                            )
                        }

                        SettingsSection.APPEARANCE -> {
                            SettingsLabel(
                                "プレビューの既定の幅",
                                "アプリを開いたときの幅です（dp）。境界をドラッグしても変わりません",
                            )
                            OutlinedTextField(
                                value = previewDefault,
                                onValueChange = { value ->
                                    previewDefault = value.filter(Char::isDigit).take(4)
                                    previewDefault.toFloatOrNull()?.let {
                                        val fixed = it.coerceIn(
                                            prefs.previewMinWidth,
                                            prefs.previewMaxWidth,
                                        )
                                        prefs.previewWidth = fixed
                                        onPreviewWidthChanged(fixed)
                                    }
                                },
                                label = { Text("幅") },
                                suffix = { Text("dp") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(18.dp))

                            SettingsLabel(
                                "プレビューの幅",
                                "境界ドラッグとウインドウ変更の両方に適用します（dp）",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = previewMin,
                                    onValueChange = { value ->
                                        previewMin = value.filter(Char::isDigit).take(4)
                                        previewMin.toFloatOrNull()?.let { min ->
                                            val fixed = min.coerceIn(220f, 1400f)
                                            prefs.previewMinWidth = fixed
                                            if (prefs.previewMaxWidth < fixed) {
                                                prefs.previewMaxWidth = fixed
                                                previewMax = fixed.toInt().toString()
                                            }
                                            onPreviewBoundsChanged(fixed, prefs.previewMaxWidth)
                                        }
                                    },
                                    label = { Text("最小幅") },
                                    suffix = { Text("dp") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = previewMax,
                                    onValueChange = { value ->
                                        previewMax = value.filter(Char::isDigit).take(4)
                                        previewMax.toFloatOrNull()?.let { max ->
                                            val fixed = max.coerceIn(prefs.previewMinWidth, 2400f)
                                            prefs.previewMaxWidth = fixed
                                            onPreviewBoundsChanged(prefs.previewMinWidth, fixed)
                                        }
                                    },
                                    label = { Text("最大幅") },
                                    suffix = { Text("dp") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                            SettingsLabel(
                                "プレビューの文字サイズ",
                                "カードの表示だけに効きます",
                            )
                            Text("$textZoom%", style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = textZoom.toFloat(),
                                onValueChange = {
                                    textZoom = it.roundToInt()
                                    prefs.textZoom = textZoom
                                },
                                valueRange = 60f..160f,
                                steps = 19,
                            )
                        }
                    }
                },
            )
        }
        }
    }
}

/** タグを複数選ぶ小さな一覧 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(all: List<String>, selected: List<String>, onChange: (List<String>) -> Unit) {
    var current by remember(selected) { mutableStateOf(selected) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        all.forEach { tag ->
            val on = tag in current
            FilterChip(
                selected = on,
                onClick = {
                    current = if (on) current - tag else current + tag
                    onChange(current)
                },
                shape = CircleShape,
                label = { Text(formatTag(tag), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
