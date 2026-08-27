package com.haru.ankieditor.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.SwingUtilities
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
    var browserForLayout by remember { mutableStateOf<CefBrowser?>(null) }
    val container = remember {
        object : JPanel(BorderLayout()) {
            /**
             * SwingPanel の外枠が変わった同じレイアウトパス内で Chromium も広げる。
             * 通常の BorderLayout 任せだと macOS の live resize 中にネイティブ子ビューが
             * 一拍遅れ、古い幅の画面や未描画帯が残ることがある。
             */
            override fun doLayout() {
                val child = components.firstOrNull()
                if (child != null) child.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
                super.doLayout()
                browserForLayout?.let { browser ->
                    browser.wasResized(width.coerceAtLeast(1), height.coerceAtLeast(1))
                    browser.notifyScreenInfoChanged()
                }
            }
        }.apply {
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
        browserForLayout = browser
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent?) {
                val width = container.width.coerceAtLeast(1)
                val height = container.height.coerceAtLeast(1)
                // live resize 中は Compose と AWT のレイアウト通知順が入れ替わることがある。
                // 子ビューの bounds と CEF の viewport を同じイベント内で揃える。
                browser.uiComponent.setBounds(0, 0, width, height)
                browser.wasResized(width, height)
                browser.notifyScreenInfoChanged()
                // AppKit がこのイベント後に NSView のサイズを確定する場合にも追従する。
                SwingUtilities.invokeLater {
                    val settledWidth = container.width.coerceAtLeast(1)
                    val settledHeight = container.height.coerceAtLeast(1)
                    browser.uiComponent.setBounds(0, 0, settledWidth, settledHeight)
                    browser.wasResized(settledWidth, settledHeight)
                }
            }
        }
        container.addComponentListener(resizeListener)

        // Compose の再計測を待つと、macOS の live resize 中だけ NSView の幅が一拍遅れる。
        // ウインドウの resize イベントから、安定時の右・下余白を保った新しい bounds を
        // 直接算出し、同じイベント内で Chromium まで反映する。
        var hostWindow: Window? = null
        var lastWindowWidth = 0
        var lastWindowHeight = 0
        val windowResizeListener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent?) {
                val window = hostWindow ?: return
                val content = (window as? javax.swing.RootPaneContainer)?.contentPane ?: return
                if (lastWindowWidth <= 0 || lastWindowHeight <= 0 || container.parent == null) {
                    lastWindowWidth = content.width
                    lastWindowHeight = content.height
                    return
                }

                val point = SwingUtilities.convertPoint(container, 0, 0, content)
                // 直前の安定フレームにおける、プレビュー右側（境界＋エディタ）の幅。
                val rightInset = (lastWindowWidth - point.x - container.width).coerceAtLeast(0)
                val bottomInset = (lastWindowHeight - point.y - container.height).coerceAtLeast(0)
                val targetWidth = (content.width - point.x - rightInset).coerceAtLeast(1)
                val targetHeight = (content.height - point.y - bottomInset).coerceAtLeast(1)

                container.setSize(targetWidth, targetHeight)
                browser.uiComponent.setBounds(0, 0, targetWidth, targetHeight)
                browser.wasResized(targetWidth, targetHeight)
                browser.notifyScreenInfoChanged()
                container.revalidate()
                container.repaint()

                lastWindowWidth = content.width
                lastWindowHeight = content.height
            }
        }
        fun attachWindowListener() {
            val found = SwingUtilities.getWindowAncestor(container) ?: return
            if (hostWindow === found) return
            hostWindow?.removeComponentListener(windowResizeListener)
            hostWindow = found
            val content = (found as? javax.swing.RootPaneContainer)?.contentPane
            lastWindowWidth = content?.width ?: found.width
            lastWindowHeight = content?.height ?: found.height
            found.addComponentListener(windowResizeListener)
        }
        val hierarchyListener = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L ||
                event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L
            ) {
                SwingUtilities.invokeLater { attachWindowListener() }
            }
        }
        container.addHierarchyListener(hierarchyListener)
        SwingUtilities.invokeLater { attachWindowListener() }
        // 表示中の入れ物に後から足すので、並べ直しを促す
        container.revalidate()
        container.repaint()
        handle.browser = browser
        handle.client = client

        onDispose {
            runCatching { client.removeMessageRouter(router) }
            runCatching { router.dispose() }
            runCatching { container.removeComponentListener(resizeListener) }
            runCatching { container.removeHierarchyListener(hierarchyListener) }
            runCatching { hostWindow?.removeComponentListener(windowResizeListener) }
            runCatching { container.removeAll() }
            runCatching { browser.close(true) }
            runCatching { client.dispose() }
            browserForLayout = null
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

    // container.isVisible だけでは、Compose が作る SwingPanel 自体のネイティブ面が
    // 前面に残り、白い板として背面を覆ってしまう。非表示時は interop view の枠も
    // 0 にして、背面の OSR Chromium を確実に見せる。
    SwingPanel(
        factory = { container },
        modifier = if (visible) modifier else Modifier.size(0.dp),
        background = background,
    )
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
