package com.haru.ankieditor.desktop

import com.jetbrains.cef.JCefAppConfig
import org.cef.CefApp
import org.cef.CefClient
import java.io.File

/**
 * Chromium (JCEF) の起動と後片付け。
 *
 * JavaFX の WebView は絵文字を描けず (Apple Color Emoji が □ になる)、
 * Compose の描画層とも重ならなかったため、Android 版と同じ Blink エンジンに揃えている。
 * 画面には「オフスクリーン描画」で受け取った絵をそのまま描くので、
 * 重なり順の問題が起きず、Compose の上に浮かせるボタンや設定画面もそのまま使える。
 */
object CefRuntime {

    @Volatile
    private var app: CefApp? = null

    @Synchronized
    fun client(): CefClient {
        app?.let { return it.createClient() }
        val config = JCefAppConfig.getInstance()
        val args = config.appArgsAsList.toTypedArray()
        CefApp.startup(args)
        val settings = config.cefSettings.apply {
            windowless_rendering_enabled = true
            // 画像や音声のキャッシュ置き場 (コレクションとは別に持つ)
            cache_path = File(
                System.getProperty("user.home"),
                "Library/Application Support/AnkiEditor/cef-cache",
            ).absolutePath
            log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING
            // 描き替えが間に合わない一瞬に見える下地。
            // カードの地色 (暗い色) にしておくと、幅を広げたときの継ぎ目が目立たない
            background_color = org.cef.CefSettings().ColorType(255, 28, 28, 28)
        }
        val created = CefApp.getInstance(args, settings, null as File?)
        app = created
        return created.createClient()
    }

    fun shutdown() {
        runCatching { app?.dispose() }
        app = null
    }
}
