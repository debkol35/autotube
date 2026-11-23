#!/usr/bin/env bash
# AutoTube installer for macOS and Linux. In a terminal, in this folder:  bash install-autotube.sh
#
# Android Auto only shows full-screen apps whose install record says "Play Store", and only
# believes that record when the phone's own package installer made the install. Android 14+
# ignores that request from an ordinary app, but accepts it from adb (the USB debugging shell):
#   1. adb installs the APK
#   2. adb opens the phone's installer on that same APK, asking it to record Play Store.
#      You tap "Update" on the phone.
# Run it again after every AutoTube update, or Android Auto hides the app again.
#
# Options (environment): ANDROID_SERIAL=<id> picks a phone; AUTOTUBE_YES=1 skips the download question.

set -u
cd "$(dirname "$0")" || exit 1
PKG=com.autotube.car
GOOGLE_INSTALLER=com.google.android.packageinstaller
TOOLS_DIR="${AUTOTUBE_TOOLS_DIR:-$HOME/.autotube}"

say()  { printf '%s\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
err()  { printf '\033[31m%s\033[0m\n' "$*"; }
ok()   { printf '\033[32m%s\033[0m\n' "$*"; }

APK="${1:-$(ls -t AutoTube-v*.apk 2>/dev/null | head -1)}"
[ -n "$APK" ] && [ -f "$APK" ] || { err "No AutoTube-v*.apk next to this script."; exit 1; }
say "AutoTube installer"; say "APK: $APK"; say

# ---- 1. adb (Google's Android platform-tools)
case "$(uname -s)" in
  Darwin) OS=darwin; EXE="" ;;
  Linux) OS=linux; EXE="" ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows; EXE=".exe" ;;
  *) err "Unknown system $(uname -s)"; exit 1 ;;
esac
ADB=""
if [ -x "$TOOLS_DIR/platform-tools/adb$EXE" ]; then ADB="$TOOLS_DIR/platform-tools/adb$EXE"
elif command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"
fi
if [ -z "$ADB" ]; then
  URL="https://dl.google.com/android/repository/platform-tools-latest-$OS.zip"
  say "This needs Google's 'adb' tool (Android SDK platform-tools, about 8 MB), which isn't on this computer."
  say "It will be downloaded from $URL into $TOOLS_DIR"
  if [ "${AUTOTUBE_YES:-}" != "1" ]; then
    printf 'Download it now? [Y/n] '; read -r a
    case "$a" in ""|[Yy]*) ;; *) err "Cancelled."; exit 1 ;; esac
  fi
  command -v unzip >/dev/null 2>&1 || { err "Please install 'unzip' first (e.g. sudo apt install unzip)."; exit 1; }
  mkdir -p "$TOOLS_DIR" && curl -fL --progress-bar -o "$TOOLS_DIR/platform-tools.zip" "$URL" \
    && unzip -q -o "$TOOLS_DIR/platform-tools.zip" -d "$TOOLS_DIR" && rm -f "$TOOLS_DIR/platform-tools.zip" \
    || { err "Download failed."; exit 1; }
  ADB="$TOOLS_DIR/platform-tools/adb$EXE"
  [ -x "$ADB" ] || { err "Download didn't contain adb."; exit 1; }
  ok "adb ready."; say
fi
if [ -n "${ANDROID_SERIAL:-}" ]; then T=(-s "$ANDROID_SERIAL"); else T=(-d); fi

# ---- 2. The phone
phone_state() {
  local lines
  lines="$("$ADB" devices 2>/dev/null | tail -n +2 | grep -v '^[[:space:]]*$' | tr -d '\r')"
  if [ -n "${ANDROID_SERIAL:-}" ]; then lines="$(printf '%s\n' "$lines" | grep "^$ANDROID_SERIAL[[:space:]]")"
  else lines="$(printf '%s\n' "$lines" | grep -v '^emulator-')"; fi
  [ -z "$lines" ] && { echo none; return; }
  [ "$(printf '%s\n' "$lines" | wc -l)" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ] && { echo many; return; }
  printf '%s\n' "$lines" | head -1 | awk '{print $2}'
}

"$ADB" start-server >/dev/null 2>&1
shown=""; start=$(date +%s)
while :; do
  st="$(phone_state)"
  [ "$st" = device ] && break
  if [ "$st" != "$shown" ]; then
    shown="$st"
    case "$st" in
      none) warn "Waiting for the phone. If nothing happens:"
            say "  - Plug it in with a data USB cable."
            say "  - Turn on USB debugging: Settings > About phone > tap 'Build number' 7 times,"
            say "    then Settings > Developer options > USB debugging." ;;
      unauthorized) warn "On the PHONE: tap 'Allow' on 'Allow USB debugging?' (tick 'Always allow')." ;;
      offline) warn "The phone is connected but 'offline': unplug and replug the cable." ;;
      many) err "More than one phone is connected. Unplug the others, or set ANDROID_SERIAL=<id> (see 'adb devices')."; exit 1 ;;
      *) warn "Phone state: $st" ;;
    esac
  fi
  [ $(( $(date +%s) - start )) -gt 300 ] && { err "No phone after 5 minutes. Run this again when it's plugged in."; exit 1; }
  sleep 2
done
ok "Phone: $("$ADB" "${T[@]}" shell getprop ro.product.model | tr -d '\r') (Android $("$ADB" "${T[@]}" shell getprop ro.build.version.release | tr -d '\r'))"

record() {
  local out
  out="$("$ADB" "${T[@]}" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"
  INST="$(printf '%s\n' "$out" | grep -o 'installerPackageName=[^ ]*' | head -1 | cut -d= -f2)"
  INIT="$(printf '%s\n' "$out" | grep -o 'initiatingPackageName=[^ ]*' | head -1 | cut -d= -f2)"
}

# ---- 3. Install, then reinstall through the phone's installer with Play Store recorded
say; say "1/2 Installing AutoTube..."
if ! "$ADB" "${T[@]}" install -r "$APK"; then
  err "Install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, an AutoTube signed by someone else is"
  err "installed: uninstall it on the phone first (your AutoTube settings will be lost)."
  exit 1
fi

say; say "2/2 Opening the phone's installer to record Play Store..."
"$ADB" "${T[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" "${T[@]}" shell am force-stop "$PKG"
SINCE="$("$ADB" "${T[@]}" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '')"
"$ADB" "${T[@]}" shell am start --activity-clear-task -a android.intent.action.INSTALL_PACKAGE \
  -d "content://$PKG.apk/self.apk" -t application/vnd.android.package-archive \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending \
  --ez android.intent.extra.NOT_UNKNOWN_SOURCE true >/dev/null
# Newer phone installers ("PIA v2") drop the Play Store request when it comes from adb, and only
# accept it from AutoTube itself. They log this; then let AutoTube ask (allowed to install apps).
sleep 3
if "$ADB" "${T[@]}" shell "logcat -d -t '$SINCE' -s InstallRepository:E" 2>/dev/null | grep -q "is invalid. Remove it"; then
  say "This phone's installer only accepts the request from AutoTube itself: asking AutoTube to do it."
  "$ADB" "${T[@]}" shell am force-stop com.google.android.packageinstaller
  "$ADB" "${T[@]}" shell appops set --user 0 "$PKG" REQUEST_INSTALL_PACKAGES allow
  "$ADB" "${T[@]}" shell am start -n "$PKG/.MainActivity" --ez com.autotube.car.SETUP true >/dev/null
fi
warn ">>> On the PHONE: unlock it and tap 'Update'."
warn "    If Play Protect asks: 'More details' > 'Install without scanning' (or 'Install anyway')."

start=$(date +%s)
while :; do
  sleep 3; record
  [ "$INST" = com.android.vending ] && break
  [ $(( $(date +%s) - start )) -gt 300 ] && break
done

say; say "Install record: installer=$INST  made by=$INIT"
if [ "$INST" = com.android.vending ] && [ "$INIT" = "$GOOGLE_INSTALLER" ]; then
  ok "Done: Android Auto will accept AutoTube."
  say "Next on the phone: open AutoTube once (allow location 'all the time'), then"
  say "Settings > Android Auto > Customize launcher > turn on AutoTube."
elif [ "$INST" = com.android.vending ]; then
  warn "Recorded as Play Store, but this phone's installer is '$INIT', not Google's."
  warn "Android Auto may still hide AutoTube on this phone. Check Customize launcher."
else
  err "Not recorded as Play Store yet (was 'Update' tapped on the phone?). Run this again."
  exit 2
fi
