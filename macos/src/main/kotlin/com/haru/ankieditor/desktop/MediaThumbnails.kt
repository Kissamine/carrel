package com.haru.ankieditor.desktop

import com.haru.ankieditor.MediaKind
import com.haru.ankieditor.UnusedMedia
import com.haru.ankieditor.formatSize
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * メディアの縮小画像を作る。
 * 一覧では小さくしか出さないので、画像はサンプリングして読み込み、
 * 動画は先頭付近のフレーム、音声は埋め込みのジャケットを使う。
 * (どれも取れなければ null を返し、種類のアイコンで代用する)
 */
private fun loadThumb(file: File, kind: MediaKind, target: Int): BufferedImage? = runCatching {
    if (!file.isFile) return null
    // macOS 版は画像だけを読む (動画・音声は種類のアイコンで代用する)
    if (kind != MediaKind.IMAGE && kind != MediaKind.OTHER) return null
    val source = ImageIO.read(file) ?: return null
    val scale = target.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1)
    if (scale >= 1f) return source
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = scaled.createGraphics()
    g.drawImage(source, 0, 0, width, height, null)
    g.dispose()
    scaled
}.getOrNull()

/** 一覧に並べる小さなサムネイル。読み込みは IO スレッドで行う */
@Composable
fun MediaThumb(
    file: File,
    kind: MediaKind,
    size: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null,
) {
    val bitmap by produceState<BufferedImage?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { loadThumb(file, kind, 128) }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.toComposeImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                when (kind) {
                    MediaKind.VIDEO -> Icons.Default.Movie
                    MediaKind.AUDIO -> Icons.Default.MusicNote
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/** サムネイルを押したときの拡大表示 */
@Composable
fun MediaPreviewDialog(file: File, item: UnusedMedia, onDismiss: () -> Unit) {
    val bitmap by produceState<BufferedImage?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { loadThumb(file, item.kind, 1024) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(
                Modifier.padding(16.dp).widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.toComposeImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "このファイルはプレビューできません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${item.kind.label} · ${formatSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("閉じる")
                }
            }
        }
    }
}
