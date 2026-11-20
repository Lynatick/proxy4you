#!/usr/bin/env bash
# Проверка сборки на Mi Stick по сети. Запускать на Mac (не в песочнице).
#
#   ./scripts/check-mistick.sh                          # просто :5555
#   PAIR_PORT=40545 PAIR_CODE=388081 DEBUG_PORT=38015 ./scripts/check-mistick.sh
#
# Переменные: IP (192.168.3.28), ADB (из Android SDK), APK (Dist/…1.7.0.apk).
set -u

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
IP="${IP:-192.168.3.28}"
APK="${APK:-$(cd "$(dirname "$0")/.." && pwd)/Dist/YouTubeProxy-TV-1.7.0.apk}"

if [ ! -x "$ADB" ]; then
    echo "adb не найден: $ADB" >&2
    exit 1
fi
if [ ! -f "$APK" ]; then
    echo "APK не найден: $APK" >&2
    exit 1
fi

echo "== adb: $("$ADB" version | head -1)"

"$ADB" connect "$IP:5555" || true
"$ADB" devices -l

if [ -n "${PAIR_PORT:-}" ] && [ -n "${PAIR_CODE:-}" ]; then
    echo "== pair $IP:$PAIR_PORT"
    "$ADB" pair "$IP:$PAIR_PORT" "$PAIR_CODE" || true
    if [ -n "${DEBUG_PORT:-}" ]; then
        "$ADB" connect "$IP:$DEBUG_PORT" || true
        "$ADB" devices -l
    fi
fi

if ! "$ADB" devices | grep -qE "\bdevice\b"; then
    echo "Устройство не подключено. Включите «Отладка по сети» и повторите." >&2
    exit 2
fi

echo "== ABI"; "$ADB" shell getprop ro.product.cpu.abi
echo "== версия Android"; "$ADB" shell getprop ro.build.version.release
echo "== установка"; "$ADB" install -r "$APK"
echo "== запуск"; "$ADB" shell monkey -p tv.mistick.proxy 1
sleep 8

LOG="$(mktemp -t mistick)"
"$ADB" logcat -d | grep -iE "MiStickProxy|AndroidRuntime" > "$LOG"
echo "== лог: $LOG"
tail -60 "$LOG"

echo
echo "Проверьте маркеры:"
echo "  ядро <kind> поднято на 127.0.0.1:<port>  — встроенное ядро запустилось"
echo "  Подключено · ядро <kind> · …            — сервис и VPN работают"
echo "  FATAL EXCEPTION                          — падение, пришлите лог"
