package com.haru.ankieditor.desktop

import com.haru.ankieditor.EditorMode
import com.haru.ankieditor.ImageCorner
import com.haru.ankieditor.SortMode
import java.util.prefs.Preferences

/**
 * 設定の保存先。Android 版の SharedPreferences に相当する。
 * 認証情報 (hkey) もここに入るが、macOS のユーザー設定領域なので他のユーザーからは読めない。
 */
class Prefs {
    private val store: Preferences = Preferences.userRoot().node("com/haru/ankieditor")

    /** 一覧の区切り文字。タグに空白が入ることがあるので改行では分けない */
    private val sep = "\u001f"

    private fun list(key: String): List<String> =
        store.get(key, "").split(sep).filter { it.isNotBlank() }

    private fun putList(key: String, values: List<String>) =
        store.put(key, values.joinToString(sep))

    var hkey: String?
        get() = store.get("hkey", null)
        set(v) = if (v == null) store.remove("hkey") else store.put("hkey", v)

    var endpoint: String?
        get() = store.get("endpoint", null)
        set(v) = if (v == null) store.remove("endpoint") else store.put("endpoint", v)

    var defaultDeck: String?
        get() = store.get("defaultDeck", null)
        set(v) = if (v == null) store.remove("defaultDeck") else store.put("defaultDeck", v)

    var defaultTags: List<String>
        get() = list("defaultTags")
        set(v) = putList("defaultTags", v)

    var pinnedTags: List<String>
        get() = list("pinnedTags")
        set(v) = putList("pinnedTags", v)

    var tagOrder: List<String>
        get() = list("tagOrder")
        set(v) = putList("tagOrder", v)

    var sortMode: SortMode
        get() = runCatching { SortMode.valueOf(store.get("sortMode", "")) }
            .getOrDefault(SortMode.NAME_ASC)
        set(v) = store.put("sortMode", v.name)

    var editorMode: EditorMode
        // macOS 版はページ内に文字を打つと日本語変換が通らないため、既定を HTML にしている
        get() = runCatching { EditorMode.valueOf(store.get("editorMode", "")) }
            .getOrDefault(EditorMode.HTML)
        set(v) = store.put("editorMode", v.name)

    var imageCorner: ImageCorner
        get() = runCatching { ImageCorner.valueOf(store.get("imageCorner", "")) }
            .getOrDefault(ImageCorner.ROUND)
        set(v) = store.put("imageCorner", v.name)

    /** 検索一覧の幅 (dp)。ウインドウの大きさを変えても動かさず、掴んだときだけ変える */
    var listWidth: Float
        get() = store.getFloat("listWidth", 340f)
        set(v) = store.putFloat("listWidth", v)

    /** 検索一覧を出しているか */
    var listVisible: Boolean
        get() = store.getBoolean("listVisible", true)
        set(v) = store.putBoolean("listVisible", v)

    /** 編集列の幅 (dp) */
    var editorWidth: Float
        get() = store.getFloat("editorWidth", 520f)
        set(v) = store.putFloat("editorWidth", v)

    var textZoom: Int
        get() = store.getInt("textZoom", 100)
        set(v) = store.putInt("textZoom", v)

    val auth: Auth? get() = hkey?.let { Auth(it, endpoint) }

    fun setAuth(auth: Auth?) {
        hkey = auth?.hkey
        endpoint = auth?.endpoint
    }
}
