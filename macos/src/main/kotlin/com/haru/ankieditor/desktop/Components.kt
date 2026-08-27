package com.haru.ankieditor.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haru.ankieditor.AnkiNote
import com.haru.ankieditor.SearchRanker
import com.haru.ankieditor.SortMode
import java.awt.Cursor

/** 検索欄・デッキ・タグ。Android 版と同じ並びを macOS の余白感に合わせたもの */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchControls(
    keyword: String,
    onKeyword: (String) -> Unit,
    pill: RoundedCornerShape,
    decks: List<String>,
    selectedDeck: String?,
    onDeck: (String?) -> Unit,
    tags: List<String>,
    pinned: List<String>,
    selectedTags: Set<String>,
    onToggleTag: (String) -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    syncing: Boolean,
    onHideList: () -> Unit = {},
) {
    val syncTransition = rememberInfiniteTransition(label = "searchSyncRotation")
    val syncRotation by syncTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "searchSyncRotation",
    )
    Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val interaction = remember { MutableInteractionSource() }
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = keyword,
                    onValueChange = onKeyword,
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    interactionSource = interaction,
                    decorationBox = { inner ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = keyword,
                            innerTextField = inner,
                            enabled = true,
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
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
                                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (keyword.isNotEmpty()) {
                                    IconButton(onClick = { onKeyword("") }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Close, "クリア", Modifier.size(16.dp))
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
            TooltipIconButton(
                "同期",
                Icons.Default.Sync,
                enabled = !syncing,
                iconModifier = Modifier.graphicsLayer {
                    rotationZ = if (syncing) syncRotation else 0f
                },
                onClick = onSync,
            )
            TooltipIconButton("設定", Icons.Default.Settings, onClick = onSettings)
            TooltipIconButton(
                "一覧をしまう",
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                onClick = onHideList,
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(end = 12.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            item {
                var open by remember { mutableStateOf(false) }
                Box {
                    FilterChip(
                        selected = selectedDeck != null,
                        onClick = { open = true },
                        shape = pill,
                        modifier = Modifier.height(30.dp),
                        label = {
                            Text(
                                selectedDeck ?: "すべてのデッキ",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp)) },
                    )
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text("すべてのデッキ") },
                            onClick = { onDeck(null); open = false },
                        )
                        decks.forEach { d ->
                            DropdownMenuItem(text = { Text(d) }, onClick = { onDeck(d); open = false })
                        }
                    }
                }
            }
            // 選択中のタグ → ピン留め → その他、の順に出す
            val ordered = (selectedTags.toList() + pinned + tags).distinct()
            items(ordered, key = { it }) { tag ->
                val on = tag in selectedTags
                FilterChip(
                    selected = on,
                    onClick = { onToggleTag(tag) },
                    shape = pill,
                    modifier = Modifier.height(30.dp),
                    label = {
                        Text(formatTag(tag), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    },
                )
            }
        }
    }
}

/** 件数と並び順 */
@Composable
fun ResultHeader(count: Int, error: String?, sort: SortMode, onSort: (SortMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            error ?: if (count == 0) "" else "$count 件",
            style = MaterialTheme.typography.labelMedium,
            color = if (error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(
                onClick = { open = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(sort.label, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        trailingIcon = { if (mode == sort) Icon(Icons.Default.Check, null) },
                        onClick = { onSort(mode); open = false },
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

/** 検索結果の一覧。右クリックで削除できる */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteList(
    notes: List<AnkiNote>,
    selected: AnkiNote?,
    onSelect: (AnkiNote) -> Unit,
    onDelete: (AnkiNote) -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(notes, key = { it.id }) { note ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    ListItem(
                        headlineContent = {
                            val title = remember(note.id, note.fields) {
                                com.haru.ankieditor.noteTitle(note)
                            }
                            Text(
                                if (title.isEmpty()) {
                                    androidx.compose.ui.text.AnnotatedString("(空)")
                                } else title,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (note.id == selected?.id) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        supportingContent = {
                            val tags = note.tags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (tags.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 6.dp),
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
                        colors = if (note.id == selected?.id) {
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else ListItemDefaults.colors(),
                        modifier = Modifier
                            .onClick { onSelect(note) }
                            .onClick(
                                matcher = PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Secondary),
                                onClick = { menu = true },
                            ),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("編集") },
                            onClick = { menu = false; onSelect(note) },
                        )
                        DropdownMenuItem(
                            text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete(note) },
                        )
                    }
                }
            }
        }
    }
}

/** 掴んで動かせる列の境界 */
@Composable
fun SplitHandle(onDrag: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxHeight()
            .width(10.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
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
            color = if (dragging) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.width(4.dp).height(40.dp),
        ) {}
    }
}

/** アイコンボタン + 説明 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(icon, contentDescription = label, modifier = iconModifier.size(20.dp))
        }
    }
}
