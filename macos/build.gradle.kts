import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.7.3"
}

val jbrHome = layout.projectDirectory
    .dir("runtime/jbr_jcef-25.0.4.1-osx-aarch64-b583.48/Contents/Home")

kotlin {
    // Android 版と共有する純 Kotlin のコード (モデル・検索・色分け・配色)
    sourceSets["main"].kotlin.srcDir("../shared/kotlin")
    compilerOptions {
        // JCEF は JDK のモジュールとして入っているので、明示的に読み込む
        freeCompilerArgs.add("-Xadd-modules=jcef")
    }
}

// コンパイルにも実行にも JCEF 入りの JetBrains Runtime を使う
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinJavaToolchain.jdk.use(jbrHome.asFile.absolutePath, JavaVersion.VERSION_21)
}

tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(
        javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    )
    options.release.set(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    // 埋め込みブラウザは Chromium (JBR に同梱された JCEF) を使う。
    // 実行時は runtime/ の JetBrains Runtime に含まれるモジュールを読み込む
}

compose.desktop {
    application {
        mainClass = System.getProperty("probe")?.let { "com.haru.ankieditor.desktop.FontProbe" } ?: "com.haru.ankieditor.desktop.MainKt"
        // 埋め込みブラウザ (JCEF) は macOS の AWT 内部に触るため、開放が必要
        jvmArgs += listOf(
            "--add-modules", "jcef",
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-exports", "java.desktop/sun.awt=ALL-UNNAMED",
        )
        nativeDistributions {
            // Python 版 anki を呼ぶための資材をアプリに同梱する
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appresources"))
            // 切り出したランタイムに必要なモジュールを含める
            //  jdk.httpserver … メディアを配るローカルサーバー
            //  java.prefs     … 設定の保存先
            modules("jdk.httpserver", "java.prefs", "java.naming", "jdk.unsupported")
            targetFormats(TargetFormat.Dmg)
            packageName = "Anki Editor"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "com.haru.ankieditor"
            }
        }
    }
}


/**
 * 配布物に同梱する資材を用意する。
 * バックエンドの起動に使う Python の場所と、橋渡しのスクリプトを入れておく。
 */
val prepareBackendResources by tasks.registering {
    val outDir = layout.projectDirectory.dir("appresources/common")
    val venv = layout.projectDirectory.dir(".venv")
    doLast {
        val bridgeSrc = layout.projectDirectory.file("bridge/anki_bridge.py").asFile
        val bridgeDst = outDir.file("bridge/anki_bridge.py").asFile
        bridgeDst.parentFile.mkdirs()
        bridgeSrc.copyTo(bridgeDst, overwrite = true)

        // Python の実行環境はアプリの中に持たせる。
        // 外 (Downloads など) に置いたままだと、macOS の保護領域へのアクセス確認で
        // 起動が止まってしまうため
        val pythonDst = outDir.dir("python").asFile
        pythonDst.deleteRecursively()
        pythonDst.parentFile.mkdirs()
        providers.exec {
            commandLine("cp", "-a", venv.asFile.absolutePath, pythonDst.absolutePath)
        }.result.get()

        outDir.file("python-path.txt").asFile.writeText("python/bin/python3")
    }
}

/**
 * 配布物の仕上げ。
 *  ・Python の実行権限を戻す (資材の取り込みで落ちてしまうため)
 *  ・Chromium の本体を実行環境に入れ直す
 *    (jpackage が作る実行環境には Frameworks が含まれないため)
 */
val fixBundledRuntime by tasks.registering {
    doLast {
        val appDir = layout.buildDirectory
            .dir("compose/binaries/main/app/Anki Editor.app").get().asFile

        val python = File(appDir, "Contents/app/resources/python/bin")
        if (python.isDirectory) {
            providers.exec { commandLine("chmod", "-R", "u+x", python.absolutePath) }.result.get()
        }

        // 実行環境を、Chromium (JCEF) 入りの JetBrains Runtime ごと入れ替える。
        // jpackage が切り出す実行環境には Chromium が含まれないため
        val jbrBundle = jbrHome.asFile.parentFile.parentFile
        val runtime = File(appDir, "Contents/runtime")
        if (jbrBundle.isDirectory) {
            runtime.deleteRecursively()
            providers.exec {
                commandLine("cp", "-a", jbrBundle.absolutePath, runtime.absolutePath)
            }.result.get()
        }
    }
}

// 開発中の実行は、配布物と同じ実行環境 (Chromium 入り) を使いたいので
// scripts/run-dev.sh から起動する。Gradle の run は使わない

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(fixBundledRuntime)
}

tasks.matching { it.name.startsWith("prepareAppResources") || it.name.startsWith("createDistributable") || it.name.startsWith("package") }
    .configureEach { dependsOn(prepareBackendResources) }


/** 開発用の起動スクリプトが使う、実行に必要な jar の一覧 */
tasks.register("printRuntimeClasspath") {
    val files = configurations.getByName("runtimeClasspath")
    doLast { files.forEach { println(it.absolutePath) } }
}
