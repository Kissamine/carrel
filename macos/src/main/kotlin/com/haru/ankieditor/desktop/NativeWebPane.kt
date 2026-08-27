package com.haru.ankieditor.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * ページを OS にそのまま描かせる表示 (ウインドウ描画)。
 *
 * 公式の macOS 版 Anki と同じ仕組みで、リサイズも合成も OS が受け持つため動きが滑らか。
 * ただし本物の部品が前面に出るので、この上に Compose のボタンを重ねることはできない。
 */
@Composable
fun NativeWebPane(
    handle: WebHandle,
    html: String?,
    background: Color,
    modifier: Modifier = Modifier,
    /** 設定などを前に出したいときは false。本物の部品は Compose の上に出てしまうため */
    visible: Boolean = true,
    onPageReady: () -> Unit = {},
    onHtmlChanged: (WebHandle, String) -> Unit = { h, url -> h.loadHtml(url) },
    onBridge: (String) -> Unit = {},
) {
    val ready = rememberUpdatedState(onPageReady)
    val bridge = rememberUpdatedState(onBridge)
    // 広げた瞬間に空白が見えないよう、下地の色をカードと同じにしておく
    val container = remember {
        JPanel(BorderLayout()).apply {
            isOpaque = true
            this.background = java.awt.Color(
                (background.red * 255).toInt(),
                (background.green * 255).toInt(),
                (background.blue * 255).toInt(),
            )
        }
    }

    DisposableEffect(Unit) {
        val client = CefRuntime.client()

        // ページの中のボタンや検索から呼ばれる窓口
        val router = org.cef.browser.CefMessageRouter.create()
        router.addHandler(
            object : org.cef.handler.CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser?,
                    frame: org.cef.browser.CefFrame?,
                    queryId: Long,
                    request: String?,
                    persistent: Boolean,
                    callback: org.cef.callback.CefQueryCallback?,
                ): Boolean {
                    val message = request.orEmpty()
                    if (message.startsWith("reply:")) {
                        // run() の戻り値。reply:<番号>:<JSON>
                        val rest = message.removePrefix("reply:")
                        val id = rest.substringBefore(':').toLongOrNull()
                        if (id != null) handle.deliver(id, parseJsonValue(rest.substringAfter(':')))
                    } else if (message.isNotEmpty()) {
                        bridge.value(message)
                    }
                    callback?.success("")
                    return true
                }
            },
            true,
        )
        client.addMessageRouter(router)
        // ネイティブ側の用意ができた合図。ここから読み込み命令が効くようになる
        client.addLifeSpanHandler(object : org.cef.handler.CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser?) {
                if (System.getProperty("ankieditor.debug") != null) {
                    println("[native] ブラウザ作成完了")
                }
                handle.markCreated()
            }
        })

        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadError(
                browser: CefBrowser?,
                frame: org.cef.browser.CefFrame?,
                errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                println("[native] 読み込み失敗 $failedUrl : $errorCode $errorText")
            }

            override fun onLoadingStateChange(
                browser: CefBrowser?,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                if (!isLoading) {
                    if (System.getProperty("ankieditor.debug") != null) {
                        println("[native] 読み込み完了 ${browser?.url}")
                    }
                    // ページから Kotlin へ返事をするための窓口を用意する
                    browser?.executeJavaScript(
                        """
                        window.__ankiReply = function (id, value) {
                          window.cefQuery({
                            request: 'reply:' + id + ':' +
                                     JSON.stringify(value === undefined ? null : value),
                            onSuccess: function () {}, onFailure: function () {}
                          });
                        };
                        """.trimIndent(),
                        browser.url,
                        0,
                    )
                    ready.value()
                }
            }
        })
        val browser = client.createBrowser("about:blank", CefRendering.DEFAULT, false)
        browser.uiComponent.background = container.background
        container.add(browser.uiComponent, BorderLayout.CENTER)
        // 表示中の入れ物に後から足すので、並べ直しを促す
        container.revalidate()
        container.repaint()
        handle.browser = browser
        handle.client = client

        onDispose {
            runCatching { client.removeMessageRouter(router) }
            runCatching { router.dispose() }
            runCatching { container.removeAll() }
            runCatching { browser.close(true) }
            runCatching { client.dispose() }
            handle.browser = null
        }
    }

    LaunchedEffect(html) {
        val url = html ?: return@LaunchedEffect
        while (handle.browser == null) kotlinx.coroutines.delay(16)
        onHtmlChanged(handle, url)
    }

    // ページの地の色を読み取って下地に使う。
    // 幅を広げた瞬間に現れる部分が、ページと同じ色で埋まるので継ぎ目が見えない
    LaunchedEffect(html) {
        if (html == null) return@LaunchedEffect
        var found = false
        repeat(30) {
            if (found) return@repeat
            kotlinx.coroutines.delay(200)
            handle.run("getComputedStyle(document.body).backgroundColor") { value ->
                val color = parseCssColor(value?.toString())
                if (color != null) {
                    found = true
                    container.background = color
                    handle.browser?.uiComponent?.background = color
                    if (System.getProperty("ankieditor.debug") != null) {
                        println("[native] ページの地色 = $color")
                    }
                }
            }
        }
    }

    // 重ねて出すものがあるときは、いったん引っ込める
    LaunchedEffect(visible) { container.isVisible = visible }

    SwingPanel(factory = { container }, modifier = modifier, background = background)
}


/** ページから返ってきた JSON の値を、扱いやすい形にする */
private fun parseJsonValue(raw: String): Any? {
    val text = raw.trim()
    if (text.isEmpty() || text == "null") return null
    text.toDoubleOrNull()?.let { return it }
    if (text == "true" || text == "false") return text.toBoolean()
    return runCatching { org.json.JSONTokener(text).nextValue() }.getOrDefault(text)
}

/** "rgb(28, 28, 28)" のような文字列を色に直す */
private fun parseCssColor(raw: String?): java.awt.Color? {
    val numbers = Regex("\\d+").findAll(raw ?: return null).map { it.value.toInt() }.toList()
    if (numbers.size < 3) return null
    return java.awt.Color(numbers[0], numbers[1], numbers[2])
}
