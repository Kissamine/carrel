package com.haru.ankieditor.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.awt.Dimension

/**
 * macOS 版の起動処理。
 *
 *  1. 公式の Python 版 anki を子プロセスとして起動する (バックエンド)
 *  2. メディアを配るローカルサーバーを立てる (WebView から画像を読むため)
 *  3. コレクションを開いて画面を出す
 */
fun main() {
    // ページの描画は Chromium のオフスクリーン描画で受け取り、Compose がそのまま描く。
    // 別の描画層に頼らないので、重なり順の問題が起きない。
    // ankieditor.windowed を付けて起動すると、OS に描かせるウインドウ描画で動く
    // プレビューは OS に描かせる (滑らかに追従する)。
    // その上にボタンは重ねられないので、操作はページ自身に持たせている。
    // 合成まわりの実験的な設定は入れない (画面全体が描かれなくなることがある)
    System.setProperty("sun.awt.noerasebackground", "true")
    // JCEF のネイティブ NSView と Compose を同じ合成面で扱う。
    // OSR のような画素転送はせず、AppKit/Chromium の GPU レイヤーをそのまま使う。
    System.setProperty("compose.interop.blending", "true")
    launchApp()
}

private fun launchApp() = application {
    var status by remember { mutableStateOf("起動中...") }
    var ready by remember { mutableStateOf<Triple<DesktopRepo, Prefs, MediaServer>?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val python = findPython() ?: error(
                    "Python が見つかりません。同梱の実行環境か python3 が必要です"
                )
                val script = findScript() ?: error("anki_bridge.py が見つかりません")

                status = "バックエンドを起動中..."
                val bridge = AnkiBridge(python, script)
                bridge.start()

                val home = File(System.getProperty("user.home"), "Library/Application Support/AnkiEditor")
                val repo = DesktopRepo(bridge, File(home, "collection"))
                repo.open()

                val server = MediaServer { repo.mediaDir }
                server.start()
                CardHtml.mediaBaseUrl = server.baseUrl

                Triple(repo, Prefs(), server)
            }
        }.onSuccess { ready = it }
            .onFailure {
                logFile().appendText("起動失敗: ${it.stackTraceToString()}\n")
                status = "起動に失敗しました: ${it.message}\n詳細: ${logFile().path}"
            }
    }

    Window(
        onCloseRequest = {
            ready?.let { (repo, _, server) ->
                runCatching { server.stop() }
                runCatching { repo.close() }
            }
            runCatching { CefRuntime.shutdown() }
            exitApplication()
        },
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
        title = "Anki Editor",
    ) {
        var minimumContentWidth by remember { mutableStateOf(900f) }
        var maximumContentWidth by remember { mutableStateOf<Float?>(null) }
        val windowScope = rememberCoroutineScope()
        var widthAnimation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        LaunchedEffect(minimumContentWidth) {
            // Compose の dp と macOS のウインドウ座標はいずれも論理ピクセル。
            // 編集中にプレビュー最低幅を割り込むところまで縮められないようにする。
            window.minimumSize = Dimension(minimumContentWidth.toInt(), 620)
        }
        LaunchedEffect(maximumContentWidth) {
            val maximum = maximumContentWidth
            if (maximum == null) {
                // 編集中は画面の利用可能幅まで自由に広げられる。
                window.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            } else {
                val maxWidth = maximum.toInt().coerceAtLeast(window.minimumSize.width)
                if (window.width > maxWidth) {
                    // 先に最大値を設定すると AppKit が一瞬で縮めてしまうため、
                    // 開閉と同じカーブで幅を戻してから上限を固定する。
                    window.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                    widthAnimation?.cancel()
                    val from = window.width.toFloat()
                    Animatable(from).animateTo(
                        maxWidth.toFloat(),
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) {
                        window.size = Dimension(value.toInt(), window.height)
                    }
                }
                window.maximumSize = Dimension(maxWidth, Int.MAX_VALUE)
            }
        }
        // macOS のタイトルバーは出さず、内容を上端まで広げる。
        // 閉じる・最小化のボタンだけが内容の上に浮く形になる
        // 動作確認用: 画面の内容と、重なっている部品の位置を書き出す
        LaunchedEffect(Unit) {
            if (System.getProperty("ankieditor.debug") == null) return@LaunchedEffect
            val w = window
            Thread({
                Thread.sleep(12000)
                javax.swing.SwingUtilities.invokeLater {
                    runCatching {
                        val pane = w.contentPane
                        val image = java.awt.image.BufferedImage(
                            pane.width.coerceAtLeast(1),
                            pane.height.coerceAtLeast(1),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB,
                        )
                        pane.paint(image.createGraphics())
                        javax.imageio.ImageIO.write(image, "png", File("/tmp/ae_window.png"))
                        println("[window] 保存 ${pane.width}x${pane.height}")
                        fun dump(c: java.awt.Container, depth: Int) {
                            c.components.forEach { child ->
                                println(
                                    "[window] " + "  ".repeat(depth) +
                                        "${child.javaClass.simpleName} " +
                                        "${child.x},${child.y} ${child.width}x${child.height} " +
                                        "見える=${child.isVisible}"
                                )
                                if (child is java.awt.Container) dump(child, depth + 1)
                            }
                        }
                        dump(pane, 0)
                    }.onFailure { println("[window] 保存に失敗: $it") }
                }
            }, "debug-capture").apply { isDaemon = true }.start()
        }

        LaunchedEffect(window) {
            runCatching {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
        MaterialTheme(colorScheme = com.haru.ankieditor.AnkiEditorDarkColors) {
            Surface(Modifier.fillMaxSize()) {
                val current = ready
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(status) }
                } else {
                    App(
                        current.first,
                        current.second,
                        current.third,
                        onMinimumWindowWidth = { minimumContentWidth = it },
                        onMaximumWindowWidth = { maximumContentWidth = it },
                        onResizeWindowBy = { delta ->
                            if (delta != 0f) {
                                widthAnimation?.cancel()
                                val from = window.width.toFloat()
                                val target = (from + delta)
                                    .coerceAtLeast(window.minimumSize.width.toFloat())
                                // 呼び出し元をアニメーション完了まで待たせる。
                                // これにより、エディタ列は必要な横幅が確保された後にだけ現れる。
                                Animatable(from).animateTo(
                                    target,
                                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                                ) {
                                    window.size = Dimension(value.toInt(), window.height)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 記録用のログ。配布したアプリでは画面にしか出せないので、ここにも残す */
private fun logFile(): File =
    File(System.getProperty("user.home"), "Library/Logs/AnkiEditor.log").apply {
        parentFile?.mkdirs()
    }

/**
 * バックエンドに使える Python を探す。
 * anki が入っていない Python (システムのものなど) を掴まないよう、実際に読み込めるか確かめる。
 */
private fun findPython(): File? {
    // 配布時は python-path.txt に実行環境の場所を書いてある
    val recorded = File(appResources(), "python-path.txt")
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.let { path ->
            // 相対で書かれていたらアプリ内の資材として解決する
            if (path.startsWith("/")) File(path) else File(appResources(), path)
        }
    val candidates = listOfNotNull(
        System.getProperty("ankieditor.python")?.let(::File),
        recorded,
        File(projectRoot(), "macos/.venv/bin/python3"),
        File("/usr/bin/python3"),
    )
    logFile().appendText(
        "resources=${appResources().path} 候補=${candidates.map { it.path }}\n"
    )
    // 取り込みの都合で実行権限が落ちていることがあるので、必要なら付け直す
    candidates.forEach { if (it.isFile && !it.canExecute()) runCatching { it.setExecutable(true) } }
    return candidates.firstOrNull { it.canExecute() && hasAnki(it) }
        ?: candidates.firstOrNull { it.canExecute() }
}

/** その Python で anki を読み込めるか */
private fun hasAnki(python: File): Boolean = runCatching {
    val p = ProcessBuilder(python.absolutePath, "-c", "import anki")
        .redirectErrorStream(true)
        .start()
    val ok = p.waitFor() == 0
    logFile().appendText("python=${python.path} anki=$ok\n")
    ok
}.getOrDefault(false)

private fun findScript(): File? {
    val candidates = listOfNotNull(
        System.getProperty("ankieditor.bridge")?.let(::File),
        File(appResources(), "bridge/anki_bridge.py"),
        File(projectRoot(), "macos/bridge/anki_bridge.py"),
    )
    return candidates.firstOrNull { it.isFile }
}

/** アプリとして配布したときのリソース置き場 */
private fun appResources(): File =
    File(System.getProperty("compose.application.resources.dir") ?: ".")

/** 開発中に実行したときのプロジェクト位置 */
private fun projectRoot(): File {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
        if (File(dir, "macos/bridge/anki_bridge.py").isFile) return dir
        dir = dir.parentFile
    }
    return File(System.getProperty("user.dir")).parentFile ?: File(".")
}
