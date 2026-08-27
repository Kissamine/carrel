package com.haru.ankieditor.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.callback.CefDragData
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import org.cef.misc.CefRange
import java.awt.Canvas as AwtCanvas
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Chromium のページを Compose に描く入れ物。
 *
 * ページはオフスクリーンで描かれ、その絵を Compose がそのまま描画する。
 * 画面上は普通の Compose の部品と同じ扱いなので、上にボタンを浮かせても隠れない。
 */
/** 動作確認のときだけ使う、描画回数と所要時間の目印 */
private var debugFrames = 0
private var frameTotal = 0.0

/** 受け取った画素の置き場。大きさが変わらない限り使い回す */
private var scratch: ByteArray? = null

class WebHandle {
    internal var browser: CefBrowser? = null
    internal var client: CefClient? = null

    /** 画面に出す絵。ページが描き変わるたびに差し替わる */
    internal val frame = mutableStateOf<ImageBitmap?>(null)

    /** 読み込み済みの内容の目印 (同じものを二度読み込まないため) */
    internal var loadedTag: String? = null

    /**
     * Compose が決めた表示領域の大きさ (実ピクセル)。
     * ネイティブ表示は必ずこれに合わせる。これを唯一の正とすることで、
     * ウインドウの拡縮を独自に追従した結果と食い違ったまま残るのを防ぐ
     */
    @Volatile
    internal var desiredSize: androidx.compose.ui.unit.IntSize? = null

    /** ネイティブ側の用意ができたか。できる前の読み込み命令は捨てられてしまう */
    @Volatile
    internal var created = false

    /** 用意ができる前に頼まれた読み込み先 (できたらすぐ読み込む) */
    @Volatile
    internal var pendingUrl: String? = null

    private val pending = mutableMapOf<Long, (Any?) -> Unit>()
    private val nextId = AtomicLong(1)

    /** ページの JavaScript を実行する。戻り値が要るときは onResult を渡す */
    fun run(js: String, onResult: ((Any?) -> Unit)? = null) {
        val b = browser ?: return
        if (onResult == null) {
            b.executeJavaScript(js, b.url, 0)
            return
        }
        // 結果は window.__ankiResult 経由で受け取る
        val id = nextId.getAndIncrement()
        pending[id] = onResult
        b.executeJavaScript(
            """
            (function () {
              try { window.__ankiReply($id, ($js)); }
              catch (e) { window.__ankiReply($id, null); }
            })();
            """.trimIndent(),
            b.url,
            0,
        )
    }

    internal fun deliver(id: Long, value: Any?) {
        pending.remove(id)?.invoke(value)
    }

    fun loadHtml(url: String) {
        // ネイティブ側がまだ無いうちに頼むと捨てられるので、覚えておいて後で読み込む
        pendingUrl = url
        if (created) browser?.loadURL(url)
    }

    internal fun markCreated() {
        created = true
        pendingUrl?.let { browser?.loadURL(it) }
    }
}

@Composable
fun rememberWebHandle(): WebHandle = remember { WebHandle() }

/**
 * ページを表示する。
 * [html] にはローカルサーバー上の URL を渡す (中身はサーバーが配る)。
 */
@Composable
fun WebPane(
    handle: WebHandle,
    html: String?,
    background: Color,
    modifier: Modifier = Modifier,
    zoom: Double = 1.0,
    /** false の間は待機表示なので、Chromium の描画頻度を落として負荷を抑える */
    active: Boolean = true,
    onPageReady: () -> Unit = {},
    onHtmlChanged: (WebHandle, String) -> Unit = { h, url -> h.loadHtml(url) },
    onBridge: (String) -> Unit = {},
) {
    val density = LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(Size.Zero) }
    val focusRequester = remember { FocusRequester() }
    val ready = rememberUpdatedState(onPageReady)
    val bridge = rememberUpdatedState(onBridge)

    DisposableEffect(Unit) {
        val client = CefRuntime.client()
        val dummy = AwtCanvas()

        // ページ側の window.cefQuery からの連絡を受け取る窓口
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
                    val message = request ?: return false
                    if (message.startsWith("reply:")) {
                        // run() の戻り値。reply:<番号>:<JSON>
                        val rest = message.removePrefix("reply:")
                        val id = rest.substringBefore(':').toLongOrNull()
                        val body = rest.substringAfter(':')
                        if (id != null) {
                            handle.deliver(id, parseJsonValue(body))
                        }
                    } else {
                        bridge.value(message)
                    }
                    callback?.success("")
                    return true
                }
            },
            true,
        )
        client.addMessageRouter(router)

        val handler = object : CefRenderHandler {
            override fun getViewRect(browser: CefBrowser?): Rectangle {
                val w = (size.width / density).toInt().coerceAtLeast(1)
                val h = (size.height / density).toInt().coerceAtLeast(1)
                return Rectangle(0, 0, w, h)
            }

            override fun getScreenInfo(browser: CefBrowser?, info: CefScreenInfo?): Boolean {
                // 画面の倍率を伝えないと、Chromium は等倍で描いてしまう (Retina でぼやける)
                val target = info ?: return false
                val rect = getViewRect(browser)
                target.Set(density, 32, 8, false, rect, rect)
                return true
            }

            override fun getScreenPoint(browser: CefBrowser?, point: Point?): Point =
                Point(point ?: Point(0, 0))

            override fun onPopupShow(browser: CefBrowser?, show: Boolean) {}

            override fun onPopupSize(browser: CefBrowser?, size: Rectangle?) {}

            override fun onPaint(
                browser: CefBrowser?,
                popup: Boolean,
                dirtyRects: Array<out Rectangle>?,
                buffer: ByteBuffer,
                width: Int,
                height: Int,
            ) {
                if (popup) return
                // Chromium は BGRA のまま渡してくる。Skia もその並びを直接扱えるので、
                // 画素を 1 つずつ変換せずにそのまま画像にする。
                // 入れ物は使い回す (毎回 15MB 確保すると描き替えが詰まる)
                val started = System.nanoTime()
                val needed = width * height * 4
                var bytes = scratch
                if (bytes == null || bytes.size != needed) {
                    bytes = ByteArray(needed)
                    scratch = bytes
                }
                buffer.rewind()
                buffer.get(bytes)
                val image = org.jetbrains.skia.Image.makeRaster(
                    org.jetbrains.skia.ImageInfo(
                        width,
                        height,
                        org.jetbrains.skia.ColorType.BGRA_8888,
                        org.jetbrains.skia.ColorAlphaType.PREMUL,
                    ),
                    bytes,
                    width * 4,
                )
                handle.frame.value = image.toComposeImageBitmap()
                if (System.getProperty("ankieditor.debug") != null) {
                    val ms = (System.nanoTime() - started) / 1_000_000.0
                    frameTotal += ms
                    if (++debugFrames % 30 == 0) {
                        println(
                            "[web] ${width}x$height 直近30枚の平均 " +
                                "${"%.1f".format(frameTotal / 30)}ms"
                        )
                        frameTotal = 0.0
                    }
                }
            }

            override fun onAcceleratedPaint(
                browser: CefBrowser?,
                popup: Boolean,
                dirtyRects: Array<out Rectangle>?,
                info: org.cef.handler.CefAcceleratedPaintInfo?,
            ) {}

            override fun onCursorChange(browser: CefBrowser?, cursorType: Int) = true

            override fun startDragging(
                browser: CefBrowser?,
                dragData: CefDragData?,
                mask: Int,
                x: Int,
                y: Int,
            ) = false

            override fun updateDragCursor(browser: CefBrowser?, operation: Int) {}

            override fun getDeviceScaleFactor(browser: CefBrowser?) = density

            override fun OnImeCompositionRangeChanged(
                browser: CefBrowser?,
                range: CefRange?,
                bounds: Array<out Rectangle>?,
            ) {}

            override fun OnTextSelectionChanged(
                browser: CefBrowser?,
                text: String?,
                range: CefRange?,
            ) {}
        }

        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadError(
                browser: CefBrowser?,
                frame: org.cef.browser.CefFrame?,
                errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                println("[web] 読み込み失敗 $failedUrl : $errorCode $errorText")
            }

            override fun onLoadingStateChange(
                browser: CefBrowser?,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                if (!isLoading) {
                    if (System.getProperty("ankieditor.debug") != null) {
                        println("[web] 読み込み完了 ${browser?.url}")
                    }
                    // ページ側から Kotlin へ返事をするための窓口を用意する
                    browser?.executeJavaScript(
                        """
                        window.__ankiReply = function (id, value) {
                          window.cefQuery({
                            request: 'reply:' + id + ':' + JSON.stringify(value === undefined ? null : value),
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

        client.addLifeSpanHandler(object : org.cef.handler.CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser?) {
                handle.markCreated()
            }
        })
        val browser = client.createBrowser(
            "about:blank",
            CefRendering.CefRenderingWithHandler(handler, dummy),
            false,
        )
        browser.createImmediately()
        handle.browser = browser
        handle.client = client

        onDispose {
            runCatching { client.removeMessageRouter(router) }
            runCatching { router.dispose() }
            runCatching { browser.close(true) }
            runCatching { client.dispose() }
            handle.browser = null
        }
    }

    // 大きさが変わったらページにも伝える
    LaunchedEffect(size, handle.browser, active) {
        val b = handle.browser ?: return@LaunchedEffect
        if (size.width <= 0f || size.height <= 0f) return@LaunchedEffect
        val w = (size.width / density).toInt().coerceAtLeast(1)
        val h = (size.height / density).toInt().coerceAtLeast(1)
        // 画面の情報も伝え直してから大きさを反映する (倍率が変わる場合に備えて)
        b.notifyScreenInfoChanged()
        b.wasResized(w, h)
        // live resize・編集時だけ滑らかにし、背面待機中は負荷を抑える。
        runCatching { b.setWindowlessFrameRate(if (active) 60 else 5) }
        if (System.getProperty("ankieditor.debug") != null) {
            println("[web] 表示領域 ${w}x$h (実ピクセル ${size.width.toInt()}x${size.height.toInt()})")
        }
    }

    LaunchedEffect(html) {
        val url = html ?: return@LaunchedEffect
        while (handle.browser == null) kotlinx.coroutines.delay(16)
        onHtmlChanged(handle, url)
    }

    LaunchedEffect(zoom) {
        handle.browser?.setZoomLevel(zoomLevelOf(zoom))
    }

    Canvas(
        modifier
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { handle.browser?.setFocus(it.isFocused) }
            .pointerInput(handle) {
                detectTapGestures(
                    onPress = { offset ->
                        focusRequester.requestFocus()
                        handle.browser?.sendMouseEvent(
                            mouseEvent(MouseEvent.MOUSE_PRESSED, offset, density)
                        )
                        tryAwaitRelease()
                        handle.browser?.sendMouseEvent(
                            mouseEvent(MouseEvent.MOUSE_RELEASED, offset, density)
                        )
                    },
                )
            }
            .pointerInput(handle) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Move -> handle.browser?.sendMouseEvent(
                                mouseEvent(MouseEvent.MOUSE_MOVED, change.position, density)
                            )

                            PointerEventType.Scroll -> {
                                val scroll = change.scrollDelta
                                handle.browser?.sendMouseWheelEvent(
                                    wheelEvent(change.position, scroll.y, density)
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }
            .onKeyEvent { event ->
                val b = handle.browser ?: return@onKeyEvent false
                (event.nativeKeyEvent as? java.awt.event.KeyEvent)?.let { b.sendKeyEvent(it) }
                true
            }
    ) {
        drawRect(background)
        val image = handle.frame.value ?: return@Canvas
        val canvasWidth = this.size.width.toInt().coerceAtLeast(1)
        val canvasHeight = this.size.height.toInt().coerceAtLeast(1)
        if (kotlin.math.abs(image.width - canvasWidth) <= 2) {
            // 大きさが一致しているときは等倍。ページの組み直しがそのまま出る
            drawImage(image)
        } else {
            // 幅を変えている途中だけ、次の絵が届くまで引き伸ばして繋ぐ
            drawImage(
                image = image,
                dstSize = androidx.compose.ui.unit.IntSize(canvasWidth, canvasHeight),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
    }
}

private fun mouseEvent(id: Int, offset: Offset, density: Double): MouseEvent {
    val x = (offset.x / density).toInt()
    val y = (offset.y / density).toInt()
    return MouseEvent(
        DUMMY_SOURCE,
        id,
        System.currentTimeMillis(),
        0,
        x,
        y,
        1,
        false,
        MouseEvent.BUTTON1,
    )
}

private fun wheelEvent(offset: Offset, delta: Float, density: Double): MouseWheelEvent {
    val x = (offset.x / density).toInt()
    val y = (offset.y / density).toInt()
    return MouseWheelEvent(
        DUMMY_SOURCE,
        MouseWheelEvent.MOUSE_WHEEL,
        System.currentTimeMillis(),
        0,
        x,
        y,
        0,
        false,
        MouseWheelEvent.WHEEL_UNIT_SCROLL,
        3,
        // 下へ転がすと下へ進むように向きをそろえる
        (-delta * 3).toInt(),
    )
}

/** マウスやキーの出どころとして使うだけの部品 */
private val DUMMY_SOURCE = AwtCanvas()

/** 倍率 (1.0 = 等倍) を Chromium の段階表現に直す */
private fun zoomLevelOf(zoom: Double): Double =
    if (zoom <= 0) 0.0 else kotlin.math.ln(zoom) / kotlin.math.ln(1.2)


/** ページから返ってきた JSON の値を、扱いやすい形にする */
private fun parseJsonValue(raw: String): Any? {
    val text = raw.trim()
    if (text.isEmpty() || text == "null") return null
    text.toDoubleOrNull()?.let { return it }
    if (text == "true" || text == "false") return text.toBoolean()
    return runCatching { org.json.JSONTokener(text).nextValue() }.getOrDefault(text)
}
