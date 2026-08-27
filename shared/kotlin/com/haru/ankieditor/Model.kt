package com.haru.ankieditor

/**
 * Android 版と macOS 版で共有するデータモデル。
 * どちらのプラットフォーム API にも依存しない純粋な Kotlin だけを置く。
 */

data class AnkiNote(
    val id: Long,
    val modelId: Long,
    val fields: List<String>,
    val tags: String,
    /** 最終更新 (epoch 秒)。並び替え用 */
    val mtimeSecs: Int = 0,
)

/** ノートタイプの表面・裏面テンプレートとスタイル CSS */
data class CardTemplate(
    val notetypeId: Long,
    val name: String,
    val front: String,
    val back: String,
    val css: String,
)

/** メディアの種類。整理画面で種類ごとに絞り込むために使う */
enum class MediaKind(val label: String, val extensions: Set<String>) {
    IMAGE("画像", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "heic")),
    VIDEO("動画", setOf("mp4", "webm", "mov", "mkv", "avi", "m4v", "ogv")),
    AUDIO("音声", setOf("mp3", "m4a", "wav", "ogg", "oga", "opus", "flac", "aac", "3gp")),
    OTHER("その他", emptySet());

    companion object {
        fun of(fileName: String): MediaKind {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }
    }
}

/** 未使用メディア 1 件 */
data class UnusedMedia(
    val name: String,
    val kind: MediaKind,
    val sizeBytes: Long,
)

/** 編集画面の入力方式 */
enum class EditorMode(val label: String) {
    RICH("テキスト"),
    HTML("HTML"),
}

/** 画像サイズ指定に使う CSS の単位 */
enum class CssUnit(val suffix: String) {
    EM("em"),
    PX("px"),
    PERCENT("%"),
    VW("vw"),
}

/** 挿入する画像の配置。img 自身に付ける CSS で表現する */
enum class ImageAlign(val label: String, val css: String) {
    INLINE("インライン", ""),
    LEFT("左端", "display:block;margin-right:auto;"),
    CENTER("中央", "display:block;margin-left:auto;margin-right:auto;"),
    RIGHT("右端", "display:block;margin-left:auto;"),
}

/** 挿入する画像の角の形 (class="round" を付けるかどうか) */
enum class ImageCorner(val label: String) {
    SQUARE("角なし"),
    ROUND("角丸"),
}

/** 検索結果の並び順 */
enum class SortMode(val label: String) {
    RELEVANCE("関連順"),
    NAME_ASC("名前（昇順）"),
    NAME_DESC("名前（降順）"),
    MODIFIED("更新日時順"),
}

data class AnkiTemplate(
    val name: String,
    val qfmt: String,
    val afmt: String,
)

data class AnkiModel(
    val id: Long,
    val name: String,
    val fieldNames: List<String>,
    val css: String,
    val templates: List<AnkiTemplate>,
)

/** ノート 1 件の中身。同期の前後で競合を調べるのに使う */
data class NoteContent(val fields: List<String>, val tags: String)

/** サーバーとコレクションの構造がずれたときに必要になる操作 */
enum class FullSyncKind {
    /** どちらも不要。通常のマージ同期で完了した */
    NONE,

    /** サーバー側を丸ごと取り込む必要がある (この端末の未同期の変更は失われる) */
    DOWNLOAD,

    /** この端末を丸ごと送る必要がある (サーバー側の変更は失われる) */
    UPLOAD,
}
