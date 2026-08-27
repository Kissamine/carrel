#!/bin/bash
# 開発中の起動。配布物と同じ Chromium 入りの実行環境で動かす。
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
jbr="$here/runtime/jbr_jcef-25.0.4.1-osx-aarch64-b583.48/Contents/Home"

"$here/gradlew" -p "$here" jar --no-daemon -q
classpath="$(ls "$here"/build/libs/*.jar | head -1)"
for jar in $("$here/gradlew" -p "$here" -q printRuntimeClasspath --no-daemon); do
  classpath="$classpath:$jar"
done

exec "$jbr/bin/java" \
  --add-modules jcef \
  --add-opens java.desktop/sun.awt=ALL-UNNAMED \
  -Dankieditor.debug="${ANKI_DEBUG:-}" \
  -cp "$classpath" \
  com.haru.ankieditor.desktop.MainKt "$@"
