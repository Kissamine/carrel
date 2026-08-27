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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
    onDismiss: () -> Unit,
) {
    var defaultDeck by remember { mutableStateOf(prefs.defaultDeck) }
    var sortMode by remember { mutableStateOf(prefs.sortMode) }
    var editorMode by remember { mutableStateOf(prefs.editorMode) }
    var imageCorner by remember { mutableStateOf(prefs.imageCorner) }
    var textZoom by remember { mutableStateOf(prefs.textZoom) }

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
            tonalElevation = 3.dp,
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .fillMaxHeight(0.82f)
                .widthIn(max = 940.dp)
                // パネルの中のタップで閉じないようにする
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("設定", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "閉じる", Modifier.size(16.dp))
                }
            }
            HorizontalDivider()
            SettingsScreen(
                twoPane = true,
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
