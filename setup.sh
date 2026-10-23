#!/bin/bash
# CallBridge'i USB'ye bağlı iki cihaza kurar ve bağlantıyı doğrular.
#
#   ./setup.sh                      # SIM'i hazır olan cihaz tablet sayılır
#   ./setup.sh <tablet> <telefon>   # adb seri numaralarıyla
#   ./setup.sh --test               # kurulumdan sonra test çağrısı da dener
#   ./setup.sh relay [tablet]       # yalnızca tablette ses aktarıcısını başlatır
#   ./setup.sh relay noredirect     # aktarımda tablet hoparlörünü çağrıdan ayırma (yalnızca kıs)
#   ./setup.sh logs                 # iki cihazın ve aktarıcının loglarını ./logs altına çeker
#
# Ses aktarıcısı shell yetkisiyle çalışır ve tablet yeniden başlayana kadar açık kalır;
# her açılıştan sonra tablet USB'ye takılıyken bir kez ./setup.sh relay çalıştırılır.
#
# Gerekenler: iki cihazda USB hata ayıklama açık, ekran kilidi açık,
# telefon tabletin hotspot'una bağlı.
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.serhanensar.callbridge
APK=app/build/outputs/apk/debug/app-debug.apk
OUT="${TMPDIR:-/tmp}"
RELAY_LOG=/data/local/tmp/callbridge-relay.log

RUN_TEST=0
RELAY_ONLY=0
LOGS_ONLY=0
RELAY_OPT=""
ARGS=()
for a in "$@"; do
    case "$a" in
        --test) RUN_TEST=1 ;;
        relay) RELAY_ONLY=1 ;;
        logs) LOGS_ONLY=1 ;;
        noredirect) RELAY_OPT=noredirect ;;
        *) ARGS+=("$a") ;;
    esac
done

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$*"; }

# Tablette CallBridge APK'sının içindeki RelayDaemon'u shell yetkisiyle arka planda başlatır.
start_relay() {
    local dev="$1" apk uid
    apk=$("$ADB" -s "$dev" shell pm path "$PKG" | sed -n 's/^package://p' | head -1 | tr -d '\r')
    uid=$("$ADB" -s "$dev" shell pm list packages -U "$PKG" | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | tr -d '\r')
    [ -n "$apk" ] && [ -n "$uid" ] || fail "Tablette CallBridge kurulu değil"
    # Süreç adı (argv0) "callbridge-relay"; ^ ile yalnızca o eşleşir, bu komutun kendisi değil
    "$ADB" -s "$dev" shell "pkill -f '^callbridge-relay'; sleep 0.5; CLASSPATH=$apk setsid nohup app_process / $PKG.RelayDaemon $uid $RELAY_OPT > $RELAY_LOG 2>&1 < /dev/null &"
    sleep 2
    if "$ADB" -s "$dev" shell pgrep -f "'^callbridge-relay'" >/dev/null; then
        ok "Ses aktarıcısı çalışıyor ($("$ADB" -s "$dev" shell head -1 "$RELAY_LOG" | tr -d '\r'))"
    else
        "$ADB" -s "$dev" shell cat "$RELAY_LOG"
        fail "Ses aktarıcısı başlamadı"
    fi
}

if [ "$LOGS_ONLY" = 1 ]; then
    mkdir -p logs
    for d in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
        name=$("$ADB" -s "$d" shell getprop ro.product.model | tr -d '\r')
        "$ADB" -s "$d" exec-out run-as "$PKG" sh -c 'cat files/callbridge.log.1 files/callbridge.log 2>/dev/null' > "logs/$name.log"
        "$ADB" -s "$d" shell cat "$RELAY_LOG" 2>/dev/null > "logs/$name-relay.log" || true
        [ -s "logs/$name-relay.log" ] || rm -f "logs/$name-relay.log"
        ok "$name: $(wc -l < "logs/$name.log" | tr -d ' ') satır → logs/$name.log"
    done
    exit 0
fi

if [ "$RELAY_ONLY" = 1 ]; then
    HOST="${ARGS[0]:-}"
    if [ -z "$HOST" ]; then
        for d in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
            sim=$("$ADB" -s "$d" shell getprop gsm.sim.state | tr -d '\r')
            if [[ "$sim" == *READY* || "$sim" == *LOADED* ]]; then HOST="$d"; break; fi
        done
    fi
    [ -n "$HOST" ] || fail "Tablet bulunamadı; seri numarasını ver: $0 relay <tablet>"
    say "Tablette ses aktarıcısı başlatılıyor"
    start_relay "$HOST"
    exit 0
fi

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

start_relay "$HOST"

# ---- telefon ----

say "Telefona kuruluyor"
install "$CLIENT"
"$ADB" -s "$CLIENT" shell appops set "$PKG" USE_FULL_SCREEN_INTENT allow 2>/dev/null || true
write_prefs "$CLIENT" client "$PIN"
"$ADB" -s "$CLIENT" logcat -c
start_bridge "$CLIENT"
service_running "$CLIENT" BridgeClientService && ok "Telefon servisi açık" || fail "Telefon servisi başlamadı"
# Arama hesabını aç ve varsayılan giden arama hesabı yap (servis başlarken kaydedilir)
ACCOUNT="$PKG/.BridgeConnectionService callbridge 0"
"$ADB" -s "$CLIENT" shell "telecom set-phone-account-enabled $ACCOUNT; telecom set-user-selected-outgoing-phone-account $ACCOUNT" >/dev/null \
    && ok "Arama hesabı açık (çağrılar Samsung ekranında)" || echo "  Arama hesabı açılamadı; telefonda Ayarlar → Arama hesapları"

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
    say "Test çağrısı (telefon kilitliyken, tabletten)"
    "$ADB" -s "$CLIENT" shell input keyevent KEYCODE_SLEEP
    sleep 2
    "$ADB" -s "$HOST" shell input keyevent KEYCODE_WAKEUP
    "$ADB" -s "$HOST" shell am start -W -n "$PKG/.BridgeActivity" >/dev/null
    sleep 1
    tap_text "$HOST" "Cihazlara test çağrısı" || fail "Tablette test düğmesi bulunamadı"
    sleep 3
    # Gelen çağrı arama hesabı üzerinden sistemin (Samsung'un) ekranında çalmalı
    if "$ADB" -s "$CLIENT" shell dumpsys telecom | grep -E "^ *Call TC@|state=RINGING" | grep -q RINGING; then
        ok "Telefonda sistemin gelen arama ekranı çalıyor"
    elif "$ADB" -s "$CLIENT" shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity:" | grep -q IncomingCallActivity; then
        ok "Telefonda CallBridge çağrı ekranı açıldı (arama hesabı kapalı)"
    else
        echo "  Telefonda çağrı görünmedi"
    fi
    "$ADB" -s "$CLIENT" exec-out screencap -p > "$OUT/cb-ringing.png"
    "$ADB" -s "$CLIENT" shell input keyevent KEYCODE_CALL # çalan çağrıyı yanıtlar
    sleep 2
    "$ADB" -s "$CLIENT" exec-out screencap -p > "$OUT/cb-incall.png"
    "$ADB" -s "$CLIENT" shell input keyevent KEYCODE_ENDCALL
    sleep 2
    "$ADB" -s "$HOST" logcat -d -s CallBridge | grep -E "Komut (accept|end)" | tail -2 | sed 's/^/  tablet: /'
    echo "  Ekran görüntüleri: $OUT/cb-ringing.png, $OUT/cb-incall.png"
fi

say "Hazır"
echo "Tablet PIN'i: $PIN"
echo "Logları izlemek için: $ADB -s $HOST logcat -s CallBridge"
