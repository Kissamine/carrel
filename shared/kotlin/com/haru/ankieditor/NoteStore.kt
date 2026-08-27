package com.haru.ankieditor

/**
 * 共有の画面が使うデータ層の窓口。
 *
 * Android 版は rsdroid を直接、macOS 版は Python 版 anki を子プロセスで呼ぶが、
 * 画面から見た振る舞いは同じなので、この形にそろえて画面のコードを共有する。
 */
interface NoteStore {
    fun notetypes(): List<Pair<Long, String>>
    fun templateOf(notetypeId: Long): CardTemplate?
    fun saveTemplate(template: CardTemplate)
    fun unusedMedia(): List<UnusedMedia>
    fun deleteMedia(names: List<String>)
    fun fieldNames(notetypeId: Long): List<String>
    fun allTags(): List<String>
    fun decks(): List<String>
}
