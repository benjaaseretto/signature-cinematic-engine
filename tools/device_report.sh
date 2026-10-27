#!/usr/bin/env bash
# Informe del dispositivo por ADB: modelo, SoC, cámaras, códecs, pantalla, temperatura y batería.
# Uso: tools/device_report.sh [serial]   → reports/device_<modelo>_<fecha>/
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB=(adb)
if [ $# -ge 1 ]; then ADB=(adb -s "$1"); fi

state="$("${ADB[@]}" get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
    echo "No hay dispositivo autorizado (estado: ${state:-ninguno})."
    echo "Activá Depuración USB, conectá en modo Transferencia de archivos y aceptá la huella RSA en el teléfono."
    exit 1
fi

sh() { "${ADB[@]}" shell "$@" 2>&1 | tr -d '\r'; }
model="$(sh getprop ro.product.model | tr ' ' '_')"
OUT="$ROOT/reports/device_${model}_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT"

{
    echo "# Dispositivo"
    for p in ro.product.manufacturer ro.product.model ro.product.device ro.product.name \
             ro.soc.manufacturer ro.soc.model ro.board.platform ro.hardware \
             ro.build.version.release ro.build.version.sdk ro.build.version.security_patch \
             ro.build.fingerprint ro.vendor.build.fingerprint ro.build.display.id \
             ro.vendor.camera.extensions.package ro.vendor.camera.extensions.service \
             persist.vendor.camera.privapp.list ro.surface_flinger.has_HDR_display \
             ro.surface_flinger.has_wide_color_display ro.media.xml_variant.codecs; do
        printf '%-48s %s\n' "$p" "$(sh getprop "$p")"
    done
} > "$OUT/props.txt"

sh dumpsys media.camera > "$OUT/media.camera.txt"
sh dumpsys media.player > "$OUT/media.player.txt" || true
sh cat /vendor/etc/media_codecs_performance.xml > "$OUT/media_codecs_performance.xml" 2>/dev/null || true
for f in /vendor/etc/media_codecs.xml /vendor/etc/media_codecs_c2.xml /vendor/etc/media_profiles_V1_0.xml \
         /vendor/etc/media_profiles.xml /apex/com.android.media.swcodec/etc/media_codecs.xml; do
    name="$(echo "$f" | tr '/' '_' | sed 's/^_//')"
    sh "cat $f 2>/dev/null" > "$OUT/$name" || true
    [ -s "$OUT/$name" ] || rm -f "$OUT/$name"
done
sh dumpsys SurfaceFlinger --display-id > "$OUT/displays.txt" || true
sh dumpsys display > "$OUT/display.txt"
sh dumpsys thermalservice > "$OUT/thermal.txt"
sh dumpsys battery > "$OUT/battery.txt"
sh cat /proc/cpuinfo > "$OUT/cpuinfo.txt"
sh cat /proc/meminfo > "$OUT/meminfo.txt"
sh pm list features > "$OUT/features.txt"
sh dumpsys package com.motorola.camera5 2>/dev/null | head -n 40 > "$OUT/moto_camera_pkg.txt" || true
sh dumpsys package com.motorola.camera3 2>/dev/null | head -n 40 >> "$OUT/moto_camera_pkg.txt" || true

{
    echo "# Resumen"
    cat "$OUT/props.txt"
    echo
    echo "## Cámaras (dumpsys media.camera)"
    grep -E "Camera ID|android.lens.facing|android.info.supportedHardwareLevel|physicalCameraIds|android.lens.info.availableFocalLengths|android.request.availableDynamicRangeProfilesMap|android.control.availableVideoStabilizationModes" \
        "$OUT/media.camera.txt" | head -n 120 || true
    echo
    echo "## Térmico"
    grep -E "Thermal Status|Temperature\{" "$OUT/thermal.txt" | head -n 30 || true
    echo
    echo "## Batería"
    grep -E "level|temperature|status" "$OUT/battery.txt" | head -n 10 || true
    echo
    echo "## Pantalla HDR"
    grep -iE "hdr|colorMode|supportedHdrTypes" "$OUT/display.txt" | head -n 20 || true
} > "$OUT/README.md"

echo "$OUT"
