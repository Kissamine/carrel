package com.haru.ankieditor

import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import org.json.JSONObject

/** 選択メニューに出す 1 項目 */
class FormatItem(val title: String, val js: String)

/** 見出しなどをまとめるサブメニュー */
class FormatGroup(val title: String, val items: List<FormatItem>)

/**
 * テキスト選択時に OS が出すフローティングメニューへ、
 * 書式設定の項目を差し込む WebView。
 *
 * 自前のポップアップを重ねると OS 標準のメニューと場所が競合するため、
 * startActionMode を横取りして同じメニューの中に統合する。
 */
class FormatWebView(context: Context) : WebView(context) {

    /** 差し込む項目。単独項目とサブメニューを混在できる */
    var formatGroups: List<FormatGroup> = emptyList()
    var formatItems: List<FormatItem> = emptyList()

    private fun run(js: String) = evaluateJavascript(js, null)

    private fun wrap(original: ActionMode.Callback): ActionMode.Callback {
        return object : ActionMode.Callback2() {
            private val actions = mutableMapOf<Int, String>()

            private fun addOurItems(menu: Menu) {
                actions.clear()
                var id = FIRST_ID
                formatItems.forEach { item ->
                    menu.add(GROUP_ID, id, Menu.NONE, item.title)
                    actions[id] = item.js
                    id++
                }
                formatGroups.forEach { group ->
                    val sub = menu.addSubMenu(GROUP_ID, id++, Menu.NONE, group.title)
                    group.items.forEach { item ->
                        sub.add(GROUP_ID, id, Menu.NONE, item.title)
                        actions[id] = item.js
                        id++
                    }
                }
            }

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val handled = original.onCreateActionMode(mode, menu)
                addOurItems(menu)
                return handled
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.removeGroup(GROUP_ID)
                val handled = original.onPrepareActionMode(mode, menu)
                addOurItems(menu)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val js = actions[item.itemId]
                if (js != null) {
                    run(js)
                    mode.finish()
                    return true
                }
                return original.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                original.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode, view: View?, outRect: android.graphics.Rect) {
                if (original is ActionMode.Callback2) {
                    original.onGetContentRect(mode, view, outRect)
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
            }
        }
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode =
        super.startActionMode(wrap(callback), type)

    private companion object {
        const val GROUP_ID = 0x414E4B  // 自分が足した項目を見分けるための ID
        const val FIRST_ID = 0x414E00
    }
}

// ---------------------------------------------------------------- メニュー定義

private fun exec(cmd: String, value: String? = null): String {
    val v = if (value == null) "undefined" else JSONObject.quote(value)
    return "AnkiEditor.exec(${JSONObject.quote(cmd)}, $v)"
}

private fun wrapTag(open: String, close: String): String =
    "AnkiEditor.wrap(${JSONObject.quote(open)}, ${JSONObject.quote(close)})"

/** カードの CSS に合わせた配色 */
private val COLORS = listOf(
    "赤" to "#ac4f50",
    "ピンク" to "#b6686f",
    "紫" to "#bf79af",
    "青" to "#516a91",
    "グレー" to "#A9ABB4",
    "白" to "#F0F0F0",
)

val FORMAT_ITEMS = listOf(
    FormatItem("太字", exec("bold")),
    FormatItem("斜体", exec("italic")),
    FormatItem("下線", exec("underline")),
)

val FORMAT_GROUPS = listOf(
    FormatGroup(
        "書式",
        listOf(
            FormatItem("通常のテキスト", exec("formatBlock", "p")),
            FormatItem("見出し1", exec("formatBlock", "h1")),
            FormatItem("見出し2", exec("formatBlock", "h2")),
            FormatItem("見出し3", exec("formatBlock", "h3")),
            FormatItem("見出し4", exec("formatBlock", "h4")),
            FormatItem("引用", exec("formatBlock", "blockquote")),
            FormatItem("箇条書きリスト", exec("insertUnorderedList")),
            FormatItem("番号付きリスト", exec("insertOrderedList")),
            FormatItem("取り消し線", exec("strikeThrough")),
            FormatItem("コード", wrapTag("<code>", "</code>")),
            FormatItem("数式", wrapTag("<anki-mathjax>", "</anki-mathjax>")),
        ),
    ),
    FormatGroup(
        "文字色",
        COLORS.map { (label, hex) -> FormatItem(label, exec("foreColor", hex)) },
    ),
    FormatGroup(
        "背景色",
        listOf("既定" to "#45474a").plus(COLORS).map { (label, hex) ->
            FormatItem(
                label,
                wrapTag("<span class=\"rounded-text\" style=\"background-color: $hex;\">", "</span>"),
            )
        },
    ),
    FormatGroup(
        "配置",
        listOf(
            FormatItem("左端揃え", exec("justifyLeft")),
            FormatItem("中央揃え", exec("justifyCenter")),
            FormatItem("右端揃え", exec("justifyRight")),
        ),
    ),
    FormatGroup(
        "その他",
        listOf(FormatItem("書式をクリア", exec("removeFormat"))),
    ),
)
