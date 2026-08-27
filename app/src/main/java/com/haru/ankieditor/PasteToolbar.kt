package com.haru.ankieditor

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.math.roundToInt

/** クリップボードに画像があればその Uri を返す */
fun clipboardImageUri(context: Context): Uri? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        val type = context.contentResolver.getType(uri) ?: continue
        if (type.startsWith("image/")) return uri
    }
    return null
}

/**
 * 選択メニューに「画像を貼り付け」を足したツールバー。
 *
 * 見た目を自前で作らず OS のフローティング ActionMode をそのまま使うので、
 * 端末ごとのシステム標準のデザインと表示位置になる。
 * 切り取り/コピー/貼り付け/すべて選択 の文言も OS のものを使う。
 */
private class FloatingTextToolbar(
    private val view: View,
    private val imageUri: () -> Uri?,
    private val onPasteImage: (Uri) -> Unit,
) : TextToolbar {

    private var mode: ActionMode? = null
    private var contentRect: Rect = Rect.Zero

    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    private fun addItems(menu: Menu) {
        fun add(id: Int, titleRes: Int) {
            menu.add(0, id, id, titleRes).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (onCut != null) add(ID_CUT, android.R.string.cut)
        if (onCopy != null) add(ID_COPY, android.R.string.copy)
        if (onPaste != null) add(ID_PASTE, android.R.string.paste)
        if (imageUri() != null) {
            menu.add(0, ID_PASTE_IMAGE, ID_PASTE_IMAGE, "画像を貼り付け")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (onSelectAll != null) add(ID_SELECT_ALL, android.R.string.selectAll)
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(m: ActionMode, menu: Menu): Boolean {
            addItems(menu)
            return true
        }

        override fun onPrepareActionMode(m: ActionMode, menu: Menu): Boolean {
            menu.clear()
            addItems(menu)
            return true
        }

        override fun onActionItemClicked(m: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_CUT -> onCut?.invoke()
                ID_COPY -> onCopy?.invoke()
                ID_PASTE -> onPaste?.invoke()
                ID_SELECT_ALL -> onSelectAll?.invoke()
                ID_PASTE_IMAGE -> imageUri()?.let(onPasteImage)
            }
            m.finish()
            return true
        }

        override fun onDestroyActionMode(m: ActionMode) {
            mode = null
            status = TextToolbarStatus.Hidden
        }

        /** カーソル/選択範囲の位置。OS はこれを見てメニューを配置する */
        override fun onGetContentRect(m: ActionMode, v: View, outRect: android.graphics.Rect) {
            outRect.set(
                contentRect.left.roundToInt(),
                contentRect.top.roundToInt(),
                contentRect.right.roundToInt(),
                contentRect.bottom.roundToInt(),
            )
        }
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        contentRect = rect
        onCopy = onCopyRequested
        onPaste = onPasteRequested
        onCut = onCutRequested
        onSelectAll = onSelectAllRequested

        val current = mode
        if (current == null) {
            mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            current.invalidate()
        }
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        mode?.finish()
        mode = null
        status = TextToolbarStatus.Hidden
    }

    private companion object {
        const val ID_CUT = 0
        const val ID_COPY = 1
        const val ID_PASTE = 2
        const val ID_SELECT_ALL = 3
        const val ID_PASTE_IMAGE = 4
    }
}

/** 配下のテキスト入力の選択メニューに「画像を貼り付け」を追加する */
@Composable
fun PasteAwareTextToolbar(
    onPasteImage: (Uri) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val latestPaste by rememberUpdatedState(onPasteImage)

    val toolbar = remember(view) {
        FloatingTextToolbar(
            view = view,
            imageUri = { clipboardImageUri(context) },
            onPasteImage = { latestPaste(it) },
        )
    }

    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        content()
    }
}
