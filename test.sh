#!/usr/bin/env bash
# Pruebas de la lógica pura en la JVM (sin dispositivo).
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
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi
OUT="$ROOT/build/test-classes"
MAIN="$ROOT/app/src/main/java/com/signature/cinematic"
TEST="$ROOT/app/src/test/java"
G_LIB="$HOME/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/lib"
if command -v kotlinc >/dev/null 2>&1; then
    KOTLIN_HOME="${KOTLIN_HOME:-$(brew --prefix kotlin)/libexec}"
    KSTDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
    KOTLINC_CMD=(kotlinc)
else
    KSTDLIB="$G_LIB/kotlin-stdlib-2.2.20.jar"
    KOTLINC_CMD=(java --enable-native-access=ALL-UNNAMED -cp "$G_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp "$KSTDLIB")
fi
rm -rf "$OUT" && mkdir -p "$OUT"

PURE_JAVA=(
    "$MAIN/color/ToneCurve.java"
    "$MAIN/control/FrameStats.java" "$MAIN/control/ExposureController.java"
    "$MAIN/control/WhiteBalanceSmoother.java"
    "$MAIN/engine/VideoMode.java"
)
PURE_KT=(
    "$MAIN/color/ColorScience.kt" "$MAIN/color/ColorChecker.kt" "$MAIN/color/CalibrationMath.kt"
    "$MAIN/color/ChartSampler.kt" "$MAIN/color/ColorProfile.kt" "$MAIN/engine/CaptureProfile.kt"
    "$MAIN/cinema/DepthMath.kt" "$MAIN/color/PhotoLook.kt"
)

"${KOTLINC_CMD[@]}" -jvm-target 17 -nowarn -d "$OUT" "${PURE_KT[@]}" "${PURE_JAVA[@]}" $(find "$TEST" -name '*.kt')
javac --release 17 -encoding UTF-8 -cp "$OUT:$KSTDLIB" -d "$OUT" "${PURE_JAVA[@]}" $(find "$TEST" -name '*.java')
java -cp "$OUT:$KSTDLIB" com.signature.cinematic.EngineLogicTest
java -cp "$OUT:$KSTDLIB" com.signature.cinematic.ColorScienceTestKt
java -cp "$OUT:$KSTDLIB" com.signature.cinematic.DepthMathTestKt
java -cp "$OUT:$KSTDLIB" com.signature.cinematic.PhotoLookTestKt
