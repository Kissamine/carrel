pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
dependencyResolutionManagement {
    repositories {
        // Compose の依存に androidx の共通ライブラリが含まれるため google() も要る
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // JetBrains Runtime の拡張 API (jbr-api)
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        // KCEF (Compose 用の埋め込みブラウザ) の配布先
        maven("https://jogamp.org/deployment/maven")
    }
}
rootProject.name = "AnkiEditorMac"
