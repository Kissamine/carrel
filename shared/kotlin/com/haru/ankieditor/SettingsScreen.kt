package com.haru.ankieditor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 設定の 1 項目。Notion のように「一覧 → 詳細」で辿る */
enum class SettingsSection(
    val title: String,
    val icon: ImageVector,
) {
    ACCOUNT("アカウントと同期", Icons.Rounded.AccountCircle),
    SEARCH("検索", Icons.Rounded.Search),
    TAGS("タグ", Icons.Rounded.Sell),
    EDITOR("編集", Icons.Rounded.Edit),
    TEMPLATE("カードの見た目", Icons.Rounded.Style),
    MEDIA("メディアの整理", Icons.Rounded.PermMedia),
    APPEARANCE("表示", Icons.Rounded.Visibility),
}

/**
 * 設定画面。
 * 幅の広い画面では左に一覧・右に詳細の 2 列、狭い画面では一覧から詳細へ遷移する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    twoPane: Boolean,
    summaryOf: (SettingsSection) -> String,
    detail: @Composable (SettingsSection) -> Unit,
    onBack: () -> Unit,
    /**
     * 端末の「戻る」を受け取るための差し込み口。
     * Android では BackHandler を渡す。戻るという概念がない環境では何も渡さなくてよい
     */
    backHandler: @Composable (() -> Unit) -> Unit = {},
) {
    var section by remember { mutableStateOf<SettingsSection?>(if (twoPane) SettingsSection.ACCOUNT else null) }

    // 狭い画面では詳細 → 一覧 → 呼び出し元、の順に戻る
    backHandler {
        if (!twoPane && section != null) section = null else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (!twoPane && section != null) section!!.title else "設定")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!twoPane && section != null) section = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        if (twoPane) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                SectionList(
                    selected = section,
                    summaryOf = summaryOf,
                    onSelect = { section = it },
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                )
                VerticalDivider()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    section?.let {
                        Text(it.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        detail(it)
                    }
                }
            }
        } else {
            // 一覧 ⇄ 詳細も横スライドで行き来する
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "settingsNav",
            ) { current ->
                if (current == null) {
                    SectionList(
                        selected = null,
                        summaryOf = summaryOf,
                        onSelect = { section = it },
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        detail(current)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionList(
    selected: SettingsSection?,
    summaryOf: (SettingsSection) -> String,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        SettingsSection.entries.forEach { s ->
            val isSelected = s == selected
            ListItem(
                leadingContent = {
                    Icon(
                        s.icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = { Text(s.title, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    val summary = summaryOf(s)
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = if (isSelected) {
                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    ListItemDefaults.colors()
                },
                modifier = Modifier.clickable { onSelect(s) },
            )
            HorizontalDivider()
        }
    }
}

/** 設定内で使う小見出し */
@Composable
fun SettingsLabel(text: String, description: String? = null) {
    Text(text, style = MaterialTheme.typography.labelLarge)
    if (description != null) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
}

/** 選択式の 1 行 (現在値を右に出し、押すとメニューが開く) */
@Composable
fun <T> SettingsPicker(
    label: String,
    current: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        labelOf(current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(labelOf(o)) },
                    onClick = { onSelect(o); open = false },
                )
            }
        }
    }
}
