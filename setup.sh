#!/bin/bash
# CallBridge'i USB'ye bağlı iki cihaza kurar ve bağlantıyı doğrular.
#
#   ./bridge-setup.sh                      # SIM'i hazır olan cihaz tablet sayılır
#   ./bridge-setup.sh <tablet> <telefon>   # adb seri numaralarıyla
#   ./bridge-setup.sh --test               # kurulumdan sonra test çağrısı da dener
#
# Gerekenler: iki cihazda USB hata ayıklama açık, ekran kilidi açık,
# telefon tabletin hotspot'una bağlı.
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.serhanensar.callbridge
APK=app/build/outputs/apk/debug/app-debug.apk
OUT="${TMPDIR:-/tmp}"

RUN_TEST=0
ARGS=()
for a in "$@"; do
    if [ "$a" = "--test" ]; then RUN_TEST=1; else ARGS+=("$a"); fi
done

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$*"; }

# ---- cihazlar ----

DEVICES=($("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'))
[ ${#DEVICES[@]} -ge 2 ] || { "$ADB" devices -l; fail "İki cihaz bulunamadı (yetkilendirme sorusunu onayladın mı?)"; }

if [ ${#ARGS[@]} -ge 2 ]; then
    HOST="${ARGS[0]}"; CLIENT="${ARGS[1]}"
else
    HOST=""; CLIENT=""
    for d in "${DEVICES[@]}"; do
        sim=$("$ADB" -s "$d" shell getprop gsm.sim.state | tr -d '\r')
        if [[ "$sim" == *READY* || "$sim" == *LOADED* ]] && [ -z "$HOST" ]; then HOST="$d"; else CLIENT="$d"; fi
    done
    [ -n "$HOST" ] && [ -n "$CLIENT" ] || fail "SIM'i hazır cihaz bulunamadı; seri numaralarını elle ver: $0 <tablet> <telefon>"
fi
model() { "$ADB" -s "$1" shell getprop ro.product.model | tr -d '\r'; }
ok "Tablet: $(model "$HOST") ($HOST)"
ok "Telefon: $(model "$CLIENT") ($CLIENT)"

# ---- derleme ----

say "APK derleniyor"
./gradlew -q assembleDebug
ok "$APK"

# ---- yardımcılar ----

# Ekranın arayüz ağacını döker; eski döküm kalmasın diye önce siler, gerekirse yeniden dener.
ui_dump() {
    local dev="$1"
    for _ in 1 2 3; do
        "$ADB" -s "$dev" shell rm -f /sdcard/cb-ui.xml
        "$ADB" -s "$dev" shell uiautomator dump /sdcard/cb-ui.xml >/dev/null 2>&1 || true
        if "$ADB" -s "$dev" exec-out cat /sdcard/cb-ui.xml 2>/dev/null | grep -q "<hierarchy"; then
            "$ADB" -s "$dev" exec-out cat /sdcard/cb-ui.xml
            return 0
        fi
        sleep 1
    done
    return 1
}

# Ekrandaki metne dokunur: arayüz ağacından düğmenin ortasını bulur.
tap_text() {
    local dev="$1" text="$2" xy
    xy=$(ui_dump "$dev" | python3 -c '
import re, sys
want = sys.argv[1]
xml = sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", n)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if t and b and t.group(1).replace("&#10;", "\n").startswith(want):
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$text")
    [ -n "$xy" ] || return 1
    "$ADB" -s "$dev" shell input tap $xy
}

# Ekranda bu metin var mı (yaklaşık eşleşme).
screen_has() {
    ui_dump "$1" | grep -q "$2"
}

service_running() {
    "$ADB" -s "$1" shell dumpsys activity services "$PKG" | grep -q "$2"
}

write_prefs() {
    local dev="$1" role="$2" pin="$3"
    "$ADB" -s "$dev" shell am force-stop "$PKG"
    printf '%s\n' \
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
        "<map>" \
        "    <string name=\"role\">$role</string>" \
        "    <string name=\"pin\">$pin</string>" \
        "    <string name=\"host\"></string>" \
        "    <boolean name=\"enabled\" value=\"true\" />" \
        "    <boolean name=\"auto_silence\" value=\"true\" />" \
        "</map>" |
        "$ADB" -s "$dev" exec-in run-as "$PKG" sh -c 'mkdir -p shared_prefs && cat > shared_prefs/callbridge.xml'
}

# Uygulamayı açar ve kayıtlı role göre köprüyü başlatır.
start_bridge() {
    local dev="$1"
    "$ADB" -s "$dev" shell input keyevent KEYCODE_WAKEUP
    "$ADB" -s "$dev" shell am start -W -n "$PKG/.BridgeActivity" --ez start true >/dev/null
    sleep 2
}

install() {
    local dev="$1"
    "$ADB" -s "$dev" install -r -g "$APK" >/dev/null
    "$ADB" -s "$dev" shell dumpsys deviceidle whitelist +"$PKG" >/dev/null
}

# ---- tablet ----

PIN=$(python3 -c 'import secrets; print("%06d" % secrets.randbelow(10**6))')

say "Tablete kuruluyor"
install "$HOST"
"$ADB" -s "$HOST" shell cmd notification allow_dnd "$PKG" || echo "  (Rahatsız Etmeyin erişimi verilemedi; zil susturma çalışmayabilir)"
write_prefs "$HOST" host "$PIN"
"$ADB" -s "$HOST" logcat -c
start_bridge "$HOST"
if service_running "$HOST" CallHostService; then
    ok "Tablet servisi açık, PIN $PIN"
else
    "$ADB" -s "$HOST" exec-out screencap -p > "$OUT/cb-tablet.png"
    fail "Tablet servisi başlamadı; ekran görüntüsü: $OUT/cb-tablet.png"
fi

# ---- telefon ----

say "Telefona kuruluyor"
install "$CLIENT"
"$ADB" -s "$CLIENT" shell appops set "$PKG" USE_FULL_SCREEN_INTENT allow 2>/dev/null || true
write_prefs "$CLIENT" client "$PIN"
"$ADB" -s "$CLIENT" logcat -c
start_bridge "$CLIENT"
service_running "$CLIENT" BridgeClientService && ok "Telefon servisi açık" || fail "Telefon servisi başlamadı"

say "Bağlantı bekleniyor"
for i in $(seq 1 15); do
    if "$ADB" -s "$HOST" logcat -d -s CallBridge | grep -q "Bağlandı"; then
        ok "Telefon tablete bağlandı"
        CONNECTED=1
        break
    fi
    sleep 1
done
if [ -z "${CONNECTED:-}" ]; then
    echo "Telefon log'u:"; "$ADB" -s "$CLIENT" logcat -d -s CallBridge | tail -20
    gw=$("$ADB" -s "$CLIENT" shell ip route | tr -d '\r' | awk '/wlan/ && /default/ {print $3}')
    [ -n "$gw" ] || gw="yok (telefon hotspot'a bağlı değil mi?)"
    echo "Telefonun ağ geçidi: $gw"
    fail "Bağlantı kurulamadı"
fi

# ---- test çağrısı ----

if [ "$RUN_TEST" = 1 ]; then
    say "Test çağrısı"
    tap_text "$CLIENT" "Test çağrısı" || fail "'Test çağrısı' düğmesi bulunamadı"
    sleep 2
    screen_has "$CLIENT" "Test çağrısı" && screen_has "$CLIENT" "Yanıtla" && ok "Telefonda çağrı ekranı açıldı" ||
        echo "  Çağrı ekranı görünmedi (bildirim olarak gelmiş olabilir)"
    tap_text "$CLIENT" "Yanıtla" && sleep 1.5
    screen_has "$CLIENT" "Görüşmede" && ok "Yanıtla komutu tabletten döndü" || echo "  'Görüşmede' görünmedi"
    tap_text "$CLIENT" "Kapat" && sleep 1.5
    "$ADB" -s "$HOST" logcat -d -s CallBridge | grep -E "Komut (accept|end)" | sed 's/^/  tablet: /'
fi

say "Hazır"
echo "Tablet PIN'i: $PIN"
echo "Logları izlemek için: $ADB -s $HOST logcat -s CallBridge"
