package com.haru.ankieditor.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Python 版 anki (bridge/anki_bridge.py) を子プロセスとして起動し、
 * 1 行 1 JSON でやり取りする。
 *
 * Android 版が rsdroid を直接呼んでいるのと同じ API を、同じ名前で用意してある。
 * 公式実装をそのまま使うので、カードの描画も同期も PC 版 Anki と同じ結果になる。
 */
class AnkiBridge(
    private val python: File,
    private val script: File,
) {
    private lateinit var process: Process
    private lateinit var writer: BufferedWriter
    private lateinit var reader: BufferedReader
    private val nextId = AtomicLong(1)
    private val lock = Any()

    val alive: Boolean get() = ::process.isInitialized && process.isAlive

    fun start() {
        val pb = ProcessBuilder(python.absolutePath, script.absolutePath)
        pb.redirectErrorStream(false)
        process = pb.start()
        writer = process.outputStream.bufferedWriter()
        reader = process.inputStream.bufferedReader()
        // Python 側の例外やログは、そのままこちらの標準エラーへ流す
        Thread({
            process.errorStream.bufferedReader().forEachLine { System.err.println("[anki] $it") }
        }, "anki-bridge-stderr").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { call("close") }
        runCatching { writer.close() }
        runCatching { process.destroy() }
    }

    /**
     * バックエンドを呼ぶ。応答が返るまで待つので、UI スレッドから呼ばないこと。
     */
    fun call(method: String, params: JSONObject = JSONObject()): Any? {
        val id = nextId.getAndIncrement()
        val request = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)

        val line = synchronized(lock) {
            writer.write(request.toString())
            writer.write("\n")
            writer.flush()
            reader.readLine() ?: throw IllegalStateException("バックエンドが応答しません")
        }

        val response = JSONObject(line)
        if (!response.optBoolean("ok")) {
            throw RuntimeException(response.optString("error", "バックエンドでエラーが起きました"))
        }
        return if (response.isNull("result")) null else response.get("result")
    }

    fun callObject(method: String, params: JSONObject = JSONObject()): JSONObject? =
        call(method, params) as? JSONObject

    fun callArray(method: String, params: JSONObject = JSONObject()): JSONArray =
        call(method, params) as? JSONArray ?: JSONArray()
}

/** JSONObject を組み立てる小さな糖衣 */
fun params(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    pairs.forEach { (k, v) ->
        o.put(
            k,
            when (v) {
                null -> JSONObject.NULL
                is List<*> -> JSONArray(v)
                else -> v
            },
        )
    }
    return o
}
