import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// リリース署名。keystore.properties があればそれを使う (このファイルは Git に入れない)
// android {} の中だと java が Gradle の拡張と衝突するため、ここで読み込む
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // Android 版と macOS 版で共有する純 Kotlin のコード
    sourceSets["main"].java.srcDir("../shared/kotlin")
    namespace = "com.haru.ankieditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.haru.ankieditor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // 端末が arm64 なので、デバッグビルドは 1 ABI に絞って APK を小さくする。
        // リリースは AAB が ABI ごとに分割するため制限しない。
        debug {
            ndk { abiFilters += "arm64-v8a" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // 直接配布用の APK は ABI ごとに分割してサイズを抑える (リリースのみ)
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/*.version",
            )
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Anki 公式 Rust バックエンド (AnkiDroid が使用しているもの)。
    // AnkiWeb 同期 (メディア含む) と公式のカードレンダリングを提供する。
    implementation("io.github.david-allison:anki-android-backend:0.1.66-anki26.05")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.compose.material:material-icons-extended")
}
