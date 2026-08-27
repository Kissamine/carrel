package com.haru.ankieditor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 編集対象 (表面 / 裏面 / スタイル) */
private enum class TemplatePart(val label: String) {
    FRONT("表面"),
    BACK("裏面"),
    CSS("スタイル"),
}

/**
 * ノートタイプの表面・裏面テンプレートとスタイル CSS を編集する画面。
 *
 * すべてのカードの見た目に関わるので、変更は「保存」を押したときだけ反映する。
 */
@Composable
fun TemplateEditor(
    repo: NoteStore,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var notetypes by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf<CardTemplate?>(null) }
    var part by remember { mutableStateOf(TemplatePart.FRONT) }

    var front by remember { mutableStateOf(TextFieldValue("")) }
    var back by remember { mutableStateOf(TextFieldValue("")) }
    var css by remember { mutableStateOf(TextFieldValue("")) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        notetypes = withContext(Dispatchers.IO) { repo.notetypes() }
        if (selectedId == null) selectedId = notetypes.firstOrNull()?.first
    }

    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        val t = withContext(Dispatchers.IO) { repo.templateOf(id) } ?: return@LaunchedEffect
        loaded = t
        front = TextFieldValue(t.front)
        back = TextFieldValue(t.back)
        css = TextFieldValue(t.css)
        dirty = false
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsLabel(
            "ノートタイプ",
            "表面・裏面・スタイルはこのノートタイプを使うすべてのカードに反映されます",
        )
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, shape = CircleShape) {
                Text(loaded?.name ?: "選択してください")
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                notetypes.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { selectedId = id; open = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).height(40.dp)) {
                TemplatePart.entries.forEachIndexed { index, p ->
                    SegmentedButton(
                        selected = part == p,
                        onClick = { part = p },
                        shape = SegmentedButtonDefaults.itemShape(index, TemplatePart.entries.size),
                        icon = {},
                        label = { Text(p.label, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(
                    enabled = dirty && loaded != null,
                    onClick = {
                        val base = loaded ?: return@TextButton
                        scope.launch {
                            saving = true
                            try {
                                withContext(Dispatchers.IO) {
                                    repo.saveTemplate(
                                        base.copy(
                                            front = front.text,
                                            back = back.text,
                                            css = css.text,
                                        )
                                    )
                                }
                                dirty = false
                                onSaved("テンプレートを保存しました")
                            } catch (e: Exception) {
                                onSaved("保存失敗: ${e.message}")
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val value = when (part) {
            TemplatePart.FRONT -> front
            TemplatePart.BACK -> back
            TemplatePart.CSS -> css
        }
        OutlinedTextField(
            value = value,
            onValueChange = { v ->
                when (part) {
                    TemplatePart.FRONT -> front = v
                    TemplatePart.BACK -> back = v
                    TemplatePart.CSS -> css = v
                }
                dirty = true
            },
            // 設定画面側が既にスクロールするので、ここではスクロールさせない
            // (入れ子にすると高さが無限になり測定できずクラッシュする)
            modifier = Modifier.fillMaxWidth().height(360.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            visualTransformation = if (part == TemplatePart.CSS) {
                CssSyntaxTransformation
            } else {
                HtmlSyntaxTransformation
            },
        )
    }
}
