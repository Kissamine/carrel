package com.haru.ankieditor.desktop


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haru.ankieditor.AnkiNote
import com.haru.ankieditor.FullSyncKind
import com.haru.ankieditor.SearchRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 画面に重ねて出すものをまとめて置く */
@Composable
fun Dialogs(
    repo: DesktopRepo,
    prefs: Prefs,
    showLogin: Boolean,
    onLoginDone: (Auth?) -> Unit,
    showAddNote: Boolean,
    onAddNoteDismiss: () -> Unit,
    onCreate: (Long, Long) -> Unit,
    decks: List<String>,
    defaultDeck: String?,
    deleteTarget: AnkiNote?,
    onDeleteDismiss: () -> Unit,
    onDeleteConfirm: (AnkiNote) -> Unit,
    fullSyncPrompt: FullSyncKind?,
    onFullSyncDismiss: () -> Unit,
    onFullSync: (FullSyncKind) -> Unit,
    conflicts: List<NoteConflict>,
    onConflictsChange: (List<NoteConflict>) -> Unit,
    onTakeMine: (NoteConflict) -> Unit,
    showSettings: Boolean,
    onSettingsDismiss: () -> Unit,
    auth: Auth?,
    onLogout: () -> Unit,
    onOpenLogin: () -> Unit,
    onMessage: (String) -> Unit,
    collectionTags: List<String>,
    onRefreshMeta: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // ---- ログイン ----
    if (showLogin) {
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) onLoginDone(null) },
            title = { Text("AnkiWeb にログイン") },
            text = {
                Column {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("メールアドレス") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("パスワード") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions.Default,
                    )
                    err?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && user.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            err = null
                            runCatching {
                                withContext(Dispatchers.IO) { repo.login(user.trim(), password) }
                            }.onSuccess { onLoginDone(it) }
                                .onFailure { err = "ログイン失敗: ${it.message}" }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "接続中..." else "ログイン") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { onLoginDone(null) }) { Text("キャンセル") }
            },
        )
    }

    // ---- 新しいノート ----
    if (showAddNote) {
        var types by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
        var typeId by remember { mutableStateOf(0L) }
        var deckName by remember { mutableStateOf(defaultDeck) }
        var fallbackDeckId by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                types = repo.notetypes()
                val defaults = repo.addingDefaults()
                fallbackDeckId = defaults?.first ?: 0L
                typeId = defaults?.second ?: types.firstOrNull()?.first ?: 0L
            }
        }
        AlertDialog(
            onDismissRequest = onAddNoteDismiss,
            title = { Text("新しいノート") },
            text = {
                Column {
                    Text("ノートタイプ", style = MaterialTheme.typography.labelMedium)
                    var openType by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { openType = true }, shape = CircleShape) {
                            Text(
                                types.firstOrNull { it.first == typeId }?.second ?: "読み込み中...",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Rounded.ArrowDropDown, null)
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
                        OutlinedButton(onClick = { openDeck = true }, shape = CircleShape) {
                            Text(deckName ?: "既定のデッキ", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.ArrowDropDown, null)
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
                        onCreate(typeId, id)
                    },
                ) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = onAddNoteDismiss) { Text("キャンセル") } },
        )
    }

    // ---- 削除の確認 ----
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onDeleteDismiss,
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
            confirmButton = { TextButton(onClick = { onDeleteConfirm(target) }) { Text("削除") } },
            dismissButton = { TextButton(onClick = onDeleteDismiss) { Text("キャンセル") } },
        )
    }

    // ---- フル同期の確認 ----
    fullSyncPrompt?.let { kind ->
        AlertDialog(
            onDismissRequest = onFullSyncDismiss,
            title = { Text("同期の競合") },
            text = {
                Text(
                    if (kind == FullSyncKind.DOWNLOAD) {
                        "他の端末での変更が大きく、コレクション全体の取り込みが必要です。" +
                            "取り込むと、この Mac のまだ同期していない変更は失われます。"
                    } else {
                        "この Mac の変更が大きく、コレクション全体の送信が必要です。" +
                            "送信すると、サーバー側 (他の端末) のまだ取り込んでいない変更は失われます。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { onFullSync(kind) }) {
                    Text(if (kind == FullSyncKind.DOWNLOAD) "サーバーを優先" else "この Mac を優先")
                }
            },
            dismissButton = { TextButton(onClick = onFullSyncDismiss) { Text("あとで") } },
        )
    }

    // ---- ノート単位の競合 ----
    if (conflicts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { onConflictsChange(emptyList()) },
            title = { Text("${conflicts.size} 件のノートが競合しました") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "他の端末の編集が先に同期されていたため、この Mac で保存した内容は反映されていません。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    conflicts.forEach { c ->
                        Text(
                            SearchRanker.title(AnkiNote(c.noteId, 0L, c.server.fields, c.server.tags))
                                .take(80).ifBlank { "(空)" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "この Mac: " + SearchRanker
                                .title(AnkiNote(c.noteId, 0L, c.mine.fields, c.mine.tags)).take(60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = {
                                onConflictsChange(conflicts.filterNot { it.noteId == c.noteId })
                                onTakeMine(c)
                            }) { Text("この Mac の内容にする") }
                            TextButton(onClick = {
                                onConflictsChange(conflicts.filterNot { it.noteId == c.noteId })
                            }) { Text("サーバーのまま") }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onConflictsChange(emptyList()) }) { Text("閉じる") }
            },
        )
    }

    // ---- 設定 ----
    if (showSettings) {
        SettingsDialog(
            repo = repo,
            prefs = prefs,
            auth = auth,
            decks = decks,
            collectionTags = collectionTags,
            onLogout = onLogout,
            onOpenLogin = onOpenLogin,
            onMessage = onMessage,
            onRefreshMeta = onRefreshMeta,
            onDismiss = onSettingsDismiss,
        )
    }
}
