package com.haru.ankieditor

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * アプリの配色。
 *
 * Material 3 の既定のダーク配色は紫寄りだが、アイコンに合わせて青〜シアン側へ振っている。
 * 彩度は既定と同じくらいに抑えたまま色相だけを動かしているので、
 * くすんだ落ち着いた質感はそのまま残る。
 */
val AnkiEditorDarkColors = darkColorScheme(
    // 主要な操作 (選択中のチップ、リンク、カーソルなど)
    primary = Color(0xFF9FCBFF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF1E496F),
    onPrimaryContainer = Color(0xFFD2E4FF),
    inversePrimary = Color(0xFF38608F),

    // 補助的な面 (編集ボタンなど)。青みを帯びたグレーで主張しすぎない
    secondary = Color(0xFFBCC7DC),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4858),
    onSecondaryContainer = Color(0xFFD8E3F8),

    // アクセント。シアン寄りにしてアイコンの明るい側と揃える
    tertiary = Color(0xFFA3CDDA),
    onTertiary = Color(0xFF04363F),
    tertiaryContainer = Color(0xFF234C56),
    onTertiaryContainer = Color(0xFFBFE9F6),

    // 背景と面。既定の紫がかった黒をやめ、わずかに青を含ませる
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF414852),
    onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceTint = Color(0xFF9FCBFF),
    surfaceDim = Color(0xFF101418),
    surfaceBright = Color(0xFF363A3F),
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF272B2F),
    surfaceContainerHighest = Color(0xFF32363A),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3135),

    outline = Color(0xFF8B919A),
    outlineVariant = Color(0xFF414852),
)
