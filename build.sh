#!/usr/bin/env bash
# Compila la app sin Android SDK: kotlinc + javac + d8 + apktool + firma.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" ]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    elif [ -d "/opt/homebrew/Cellar/openjdk/27/libexec/openjdk.jdk/Contents/Home" ]; then
        export JAVA_HOME="/opt/homebrew/Cellar/openjdk/27/libexec/openjdk.jdk/Contents/Home"
    elif [ -d "$HOME/.antigravity-ide/extensions/redhat.java-1.58.0-darwin-arm64/jre/25.0.4.1-macosx-aarch64" ]; then
        export JAVA_HOME="$HOME/.antigravity-ide/extensions/redhat.java-1.58.0-darwin-arm64/jre/25.0.4.1-macosx-aarch64"
    fi
    if [ -n "${JAVA_HOME:-}" ]; then
        export PATH="$JAVA_HOME/bin:$HOME/.local/android/platform-tools:$PATH"
    fi
fi
TOOLS="${TOOLS:-$HOME/rea-work/tools}"
SIGNER="${SIGNER:-$HOME/rea-work/uber-apk-signer.jar}"
ANDROID_JAR="$TOOLS/android-all.jar"
G_LIB="$HOME/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/lib"
if command -v kotlinc >/dev/null 2>&1; then
    KOTLIN_HOME="${KOTLIN_HOME:-$(brew --prefix kotlin)/libexec}"
    KSTDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
    KOTLINC_CMD=(kotlinc)
else
    KSTDLIB="$G_LIB/kotlin-stdlib-2.2.20.jar"
    KOTLINC_CMD=(java --enable-native-access=ALL-UNNAMED -cp "$G_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib)
fi
OUT="$ROOT/build"
APP="$ROOT/app/src/main"
APK_NAME="SignatureCinematic.apk"
# LiteRT 1.4.2 (Google Maven): clases Java; las .so de arm64 están en app/src/main/lib.
LIBS="$(find "$ROOT/third_party/litert" -name '*.jar' | sort | tr '\n' ':')"
LIBS="${LIBS%:}"

rm -rf "$OUT/classes" "$OUT/kclasses" "$OUT/dex" "$OUT/apk" "$OUT/signed"
mkdir -p "$OUT/classes" "$OUT/kclasses" "$OUT/dex" "$OUT/apk/res"

find "$APP/java" -name '*.java' > "$OUT/sources.txt"
find "$APP/java" -name '*.kt' > "$OUT/ksources.txt"
# kotlinc lee los .java para resolver referencias cruzadas; solo emite clases de Kotlin.
"${KOTLINC_CMD[@]}" -jvm-target 17 -no-reflect -nowarn -cp "$ANDROID_JAR:$KSTDLIB:$LIBS" -d "$OUT/kclasses" \
    $(cat "$OUT/ksources.txt") "$APP/java"
javac --release 17 -nowarn -encoding UTF-8 -Xlint:-options \
    -cp "$ANDROID_JAR:$OUT/kclasses:$KSTDLIB:$LIBS" -d "$OUT/classes" @"$OUT/sources.txt"

{ find "$OUT/classes" "$OUT/kclasses" -name '*.class'; echo "$KSTDLIB"; echo "$LIBS" | tr ':' '\n'; } > "$OUT/classes.txt"
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 35 \
    --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"

cp "$APP/AndroidManifest.xml" "$OUT/apk/"
cp -R "$APP/res/." "$OUT/apk/res/"
cp -R "$APP/assets" "$OUT/apk/assets"
cp -R "$APP/lib" "$OUT/apk/lib"
cp "$OUT"/dex/*.dex "$OUT/apk/"
cat > "$OUT/apk/apktool.yml" <<'YML'
version: 3.0.3
apkFileName: SignatureCinematic.apk
usesFramework:
  ids:
  - 1
sdkInfo:
  minSdkVersion: 35
  targetSdkVersion: 35
versionInfo:
  versionCode: 4
  versionName: 0.4.0
resourcesInfo:
  packageId: 127
doNotCompress:
- arsc
- tflite
YML

if command -v apktool >/dev/null 2>&1; then
    apktool b "$OUT/apk" -o "$OUT/unsigned.apk" >/dev/null
elif [ -f "$OUT/unsigned.apk" ]; then
    zip -q -j "$OUT/unsigned.apk" "$OUT"/dex/*.dex
fi

KS="$OUT/debug.p12"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass android -keypass android \
        -alias signature -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Signature Cinematic" >/dev/null
fi
java -jar "$SIGNER" -a "$OUT/unsigned.apk" -o "$OUT/signed" --allowResign \
    --ks "$KS" --ksAlias signature --ksPass android --ksKeyPass android >/dev/null
cp "$OUT/signed/unsigned-aligned-signed.apk" "$OUT/$APK_NAME"
echo "$OUT/$APK_NAME"
