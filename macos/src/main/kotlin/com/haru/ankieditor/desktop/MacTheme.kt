package com.haru.ankieditor.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * macOS 版の配色。
 *
 * Claude のデスクトップアプリに寄せて、面はほぼ無彩色の暖かいダークで統一し、
 * 色はアイコンや選択中の項目など「目立たせたい所」にだけ使う。
 */
val MacColors = darkColorScheme(
    // 目を引かせたい所だけに使うアクセント
    primary = Color(0xFFD97757),
    onPrimary = Color(0xFF1A1917),
    primaryContainer = Color(0xFF3A2A22),
    onPrimaryContainer = Color(0xFFF3C9B6),
    inversePrimary = Color(0xFFB35C3C),

    secondary = Color(0xFFB7B4AC),
    onSecondary = Color(0xFF23221F),
    secondaryContainer = Color(0xFF33322F),
    onSecondaryContainer = Color(0xFFEDEBE3),

    tertiary = Color(0xFF9BB4A8),
    onTertiary = Color(0xFF1B2622),
    tertiaryContainer = Color(0xFF2C3A34),
    onTertiaryContainer = Color(0xFFD5E7DE),

    // 面。奥から手前へ、少しずつ明るくする
    background = Color(0xFF1F1E1D),
    onBackground = Color(0xFFF0EEE6),
    surface = Color(0xFF1F1E1D),
    onSurface = Color(0xFFF0EEE6),
    surfaceVariant = Color(0xFF33322F),
    onSurfaceVariant = Color(0xFFB7B4AC),
    surfaceTint = Color(0xFFD97757),
    surfaceDim = Color(0xFF191817),
    surfaceBright = Color(0xFF35342F),
    surfaceContainerLowest = Color(0xFF161514),
    surfaceContainerLow = Color(0xFF232220),
    surfaceContainer = Color(0xFF262624),
    surfaceContainerHigh = Color(0xFF2F2E2C),
    surfaceContainerHighest = Color(0xFF3A3936),
    inverseSurface = Color(0xFFF0EEE6),
    inverseOnSurface = Color(0xFF2A2926),

    outline = Color(0xFF6F6C65),
    outlineVariant = Color(0xFF3E3D3A),
)

/**
 * macOS の見た目に合わせた角の丸み。
 * iOS/Android のような大きな丸ではなく、控えめな角丸を使う。
 */
object MacShapes {
    /** ボタンや入力欄 */
    val control = RoundedCornerShape(7.dp)

    /** 一覧の行や小さなまとまり */
    val row = RoundedCornerShape(6.dp)

    /** 浮いている面 (設定のポップアップなど) */
    val panel = RoundedCornerShape(12.dp)

    /** 情報を伝える小さな帯 */
    val pill = RoundedCornerShape(50)
}
