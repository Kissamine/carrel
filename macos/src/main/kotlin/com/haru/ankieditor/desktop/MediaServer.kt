package com.haru.ankieditor.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * コレクションのメディアを WebView へ配るための、ローカル専用の小さな HTTP サーバー。
 *
 * Android 版は WebViewClient でリクエストを横取りしていたが、
 * JavaFX の WebView にはその仕組みがないので 127.0.0.1 に立てて配る。
 * 外部からは繋がらないようループバックだけで待ち受ける。
 */
class MediaServer(private val mediaDir: () -> File) {

    private var server: HttpServer? = null
    var baseUrl: String = "http://127.0.0.1/"
        private set

    fun start() {
        if (server != null) return
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange -> handle(exchange) }
        http.executor = null
        http.start()
        server = http
        baseUrl = "http://127.0.0.1:${http.address.port}/"
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** 編集ページなど、その場で作る HTML の置き場 */
    private val pages = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun putPage(name: String, html: String) {
        pages[name] = html
    }

    fun removePage(name: String) {
        pages.remove(name)
    }

    private fun handle(exchange: HttpExchange) {
        val name = URLDecoder.decode(exchange.requestURI.path.trimStart('/'), "UTF-8")
        try {
            pages[name]?.let { page ->
                send(exchange, "text/html; charset=utf-8", page.toByteArray(Charsets.UTF_8))
                return
            }
            if (name == "__jquery.js") {
                val bytes = javaClass.getResourceAsStream("/jquery.min.js")?.readBytes()
                if (bytes == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    send(exchange, "application/javascript", bytes)
                }
                return
            }
            // メディアフォルダの外は返さない
            val dir = mediaDir().canonicalFile
            val file = File(dir, name).canonicalFile
            if (!file.path.startsWith(dir.path) || !file.isFile) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            send(exchange, mimeOf(file.name), file.readBytes())
        } catch (e: Exception) {
            runCatching { exchange.sendResponseHeaders(500, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, mime: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", mime)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "js" -> "application/javascript"
        "css" -> "text/css"
        else -> "application/octet-stream"
    }
}
