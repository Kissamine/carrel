package com.haru.ankieditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** バイト数を人が読める形にする */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * どのノートからも参照されていないメディアを整理する画面。
 *
 * 種類 (画像 / 動画 / 音声 / その他) ごとに絞り込み、
 * 削除する前に必ず候補の一覧を見て取捨選択できるようにしている。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaCleanupPane(
    repo: NoteStore,
    onDeleted: (String) -> Unit,
    /** 一覧に出す小さな絵。読み込み方は環境ごとに違うので外から渡す */
    thumbnail: @Composable (name: String, kind: MediaKind, size: Dp, onClick: () -> Unit) -> Unit,
    /** 拡大表示。省略すると拡大しない */
    preview: @Composable (item: UnusedMedia, onDismiss: () -> Unit) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()

    var scanned by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<UnusedMedia>>(emptyList()) }
    var kinds by remember { mutableStateOf(setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO)) }
    var checked by remember { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<UnusedMedia?>(null) }

    suspend fun scan() {
        scanning = true
        items = withContext(Dispatchers.IO) { repo.unusedMedia() }
        checked = emptySet()
        scanned = true
        scanning = false
    }

    val visible = items.filter { it.kind in kinds }
    // 表示されていないものを誤って消さないよう、選択も表示中のものだけを対象にする
    val selected = visible.filter { it.name in checked }

    SettingsLabel(
        "未使用メディアの削除",
        "どのノートからも参照されていないファイルを探します。削除したものはゴミ箱に入り、次の同期で AnkiWeb にも反映されます",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { scope.launch { scan() } }, enabled = !scanning && !deleting) {
            Text(if (scanned) "再スキャン" else "未使用メディアを調べる")
        }
        if (scanning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }

    if (!scanned) return

    Spacer(Modifier.height(12.dp))

    // 種類ごとの絞り込み。件数と合計サイズを出しておくと消す判断がしやすい
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaKind.entries.forEach { kind ->
            val group = items.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            FilterChip(
                selected = kind in kinds,
                onClick = {
                    kinds = if (kind in kinds) kinds - kind else kinds + kind
                },
                label = {
                    Text("${kind.label} ${group.size}件 (${formatSize(group.sumOf { it.sizeBytes })})")
                },
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    if (items.isEmpty()) {
        Text("未使用のメディアはありませんでした", style = MaterialTheme.typography.bodyMedium)
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "${selected.size} / ${visible.size} 件を選択",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            checked = if (selected.size == visible.size) {
                checked - visible.map { it.name }.toSet()
            } else {
                checked + visible.map { it.name }
            }
        }) {
            Text(if (selected.size == visible.size && visible.isNotEmpty()) "すべて解除" else "すべて選択")
        }
    }

    HorizontalDivider()

    // 設定画面自体がスクロールするので、ここでは入れ子のスクロールを作らない
    visible.forEach { item ->
        val isChecked = item.name in checked
        ListItem(
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = {
                            checked = if (isChecked) checked - item.name else checked + item.name
                        },
                    )
                    thumbnail(item.name, item.kind, 44.dp) { previewing = item }
                }
            },
            headlineContent = {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    "${item.kind.label} · ${formatSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            modifier = Modifier.clickable {
                checked = if (isChecked) checked - item.name else checked + item.name
            },
        )
        HorizontalDivider()
    }

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = { confirming = true },
        enabled = selected.isNotEmpty() && !deleting,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text("選択した ${selected.size} 件を削除")
    }

    previewing?.let { item ->
        preview(item) { previewing = null }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("メディアを削除") },
            text = {
                Text(
                    "${selected.size} 件 (${formatSize(selected.sumOf { it.sizeBytes })}) を削除します。" +
                        "ゴミ箱に移動され、次の同期で AnkiWeb からも削除されます。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val names = selected.map { it.name }
                    scope.launch {
                        deleting = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { repo.deleteMedia(names) }
                        }
                        deleting = false
                        onDeleted(
                            result.fold(
                                onSuccess = { "${names.size} 件のメディアを削除しました" },
                                onFailure = { "削除に失敗しました: ${it.message}" },
                            )
                        )
                        if (result.isSuccess) scan()
                    }
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("キャンセル") }
            },
        )
    }
}

