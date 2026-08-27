package com.haru.ankieditor.desktop


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.haru.ankieditor.fadingRightEdge

/** ノートのタグ。既存のタグから選ぶか、新しく足せる */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TagEditor(
    tags: String,
    allTags: List<String>,
    onTagsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = remember(tags) { tags.trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
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
            items(current, key = { it }) { tag ->
                InputChip(
                    selected = true,
                    onClick = { set(current - tag) },
                    shape = CircleShape,
                    modifier = Modifier.height(30.dp),
                    label = { Text(formatTag(tag), style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "外す", Modifier.size(14.dp)) },
                )
            }
            item {
                Box {
                    AssistChip(
                        onClick = { picking = true },
                        shape = CircleShape,
                        modifier = Modifier.height(30.dp),
                        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) },
                        label = {
                            Text("タグを追加", style = MaterialTheme.typography.labelMedium)
                        },
                    )
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        allTags.filterNot { it in current }.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(formatTag(tag)) },
                                onClick = { set(current + tag); picking = false },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("新しいタグ...") },
                            onClick = { picking = false; adding = true },
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("新しいタグ") },
            text = {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    singleLine = true,
                    label = { Text("タグ名") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTag.isNotBlank(),
                    onClick = {
                        set(current + newTag.trim())
                        newTag = ""
                        adding = false
                    },
                ) { Text("追加") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("キャンセル") } },
        )
    }
}
