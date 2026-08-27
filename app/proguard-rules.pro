# --- Anki 公式バックエンド (rsdroid) -------------------------------------
# JNI から名前で参照されるため、難読化・削除しない
-keep class net.ankiweb.rsdroid.** { *; }

# protobuf の生成クラスはリフレクションで使われる
-keep class anki.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- WebView の JS ブリッジ ----------------------------------------------
# JavaScript から呼ばれるメソッドは名前が変わると動かなくなる
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Kotlin / Compose ----------------------------------------------------
-dontwarn kotlinx.coroutines.**
