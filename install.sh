#!/usr/bin/env bash
# Put Frame on a Fire TV over ADB (Wi-Fi, port 5555). Needs only `adb` on this computer.
#
#   TV_HOST=192.168.1.50 ./install.sh                  install or update, set the screensaver, open it
#   TV_HOST=... ./install.sh config URL KEY [ALBUM]    point the screensaver at Immich and sync now
#   TV_HOST=... ./install.sh sync                      fetch the photo list again and save some photos
#   TV_HOST=... ./install.sh preview                   show the screensaver now
#   TV_HOST=... ./install.sh fallback MODE             when Immich can't be shown: auto (default: the screensaver
#                                                      you had before, e.g. Aerial Views), aerial, amazon,
#                                                      sleep (the TV takes over), or none
#   TV_HOST=... ./install.sh offline saved|fallback    while Immich is offline: show saved photos (default)
#                                                      or switch to the fallback
#   TV_HOST=... ./install.sh import DIR                use DIR's .jpg/.png files as home screen backgrounds
#                                                      until Immich photos are saved (replaces earlier ones)
#   TV_HOST=... ./install.sh art on|off                famous paintings in the screensaver and on the home
#                                                      screen (default on; mixed with Immich photos)
#   TV_HOST=... ./install.sh weather ZIP|off [celsius] current temperature under the clock (US ZIP code)
#   TV_HOST=... ./install.sh homeonfire                install Home on Fire, which sends Home here
#   TV_HOST=... ./install.sh status                    what's installed and set
#   TV_HOST=... ./install.sh shot [file.png]           screenshot of the TV
#   TV_HOST=... ./install.sh remove                    put Amazon's screensaver back and uninstall
#
# TV_HOST may be a LAN IP, a hostname or a Tailscale address. ADB debugging turns itself off when the
# Fire TV restarts on some builds: if connect fails, turn it on again in Settings > My Fire TV >
# Developer options, and accept the "Allow USB debugging?" prompt on the TV the first time.
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.framehome
DREAM="$PKG/$PKG.ImmichDream"
AMAZON_DREAM=com.amazon.ftv.screensaver/.app.services.ScreensaverService
APK=build/frame.apk

: "${TV_HOST:?set TV_HOST to the Fire TV address, e.g. TV_HOST=192.168.1.50 $0}"
command -v adb >/dev/null || { echo "adb not found. macOS: brew install android-platform-tools. Linux: apt install adb."; exit 1; }
SERIAL="$TV_HOST"; [[ "$SERIAL" == *:* ]] || SERIAL="$TV_HOST:5555"

connect() {
  adb connect "$SERIAL" >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    state="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
    [ "$state" = device ] && return 0
    [ "$state" = unauthorized ] && echo "Accept 'Allow USB debugging?' on the TV (tick Always allow)..."
    sleep 2
    adb connect "$SERIAL" >/dev/null 2>&1 || true
  done
  echo "Can't reach $SERIAL over ADB. Is the TV on, on this network, with ADB debugging on?"; exit 1
}
tv() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }
q() { printf "%q" "$1"; }   # quote for the TV's shell

# Prints the receiver's reply (the broadcast's result data), or am's own output if there is none.
broadcast() {
  local out data
  out="$(tv "am broadcast -n $PKG/.ConfigReceiver $*")"
  data="$(printf '%s\n' "$out" | sed -n 's/.*data="\(.*\)"$/\1/p')"
  printf '%s\n' "${data:-$out}"
}

case "${1:-install}" in
install)
  [ -f "$APK" ] || ./build.sh
  connect
  echo "$(tv getprop ro.build.version.name), Android $(tv getprop ro.build.version.release), model $(tv getprop ro.product.model)"
  adb -s "$SERIAL" install -r "$APK"
  current="$(tv settings get secure screensaver_components)"
  current_default="$(tv settings get secure screensaver_default_component)"
  if [ "$current" != "$DREAM" ] && [ ! -f .screensaver.orig ]; then
    printf '%s\n%s\n' "$current" "$current_default" > .screensaver.orig
    echo "Saved the current screensaver ($current) to .screensaver.orig"
  fi
  previous="$(head -1 .screensaver.orig 2>/dev/null || echo "")"
  # Lets Frame switch between its screensaver and the fallback by itself.
  grant="$(tv pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>&1 || true)"
  [ -n "$grant" ] && echo "Permission grant said: $grant"
  echo "$(broadcast --es previous "$(q "$previous")" ${FALLBACK:+--es fallback "$(q "$FALLBACK")"} --ez route true)"
  now="$(tv settings get secure screensaver_components)"
  if [ "$now" != "$DREAM" ] && [ "$now" = "$current" ] && ! tv dumpsys package $PKG | grep -q "WRITE_SECURE_SETTINGS: granted=true"; then
    echo "Fire OS didn't grant the permission, so Frame can't switch by itself. Setting Frame directly:"
    for key in screensaver_components screensaver_default_component; do tv settings put secure "$key" "$DREAM"; done
    tv settings put secure screensaver_enabled 1
    [ "$(tv settings get secure screensaver_components)" = "$DREAM" ] \
      || echo "WARNING: Fire OS did not keep the screensaver setting. The home screen still works; see README, Troubleshooting."
  fi
  echo "Apps on the home screen (from res/values/apps.xml) found on the TV:"
  tv pm list packages | sed 's/^package://' | grep -iE 'stremio|smarttube|teamsmart|liskov' | sed 's/^/  /' \
    || echo "  none: install Stremio and SmartTube with Downloader, or fix the names in res/values/apps.xml"
  tv am start -n "$PKG/.HomeActivity" >/dev/null
  echo "Frame is open on the TV. Next: ./install.sh config <immich-url> <api-key>, then ./install.sh homeonfire."
  ;;
config)
  [ $# -ge 3 ] || { echo "usage: $0 config http://immich-host:2283 API_KEY [ALBUM_UUID]"; exit 1; }
  connect
  echo "$(broadcast --es url "$(q "$2")" --es key "$(q "$3")" --es album "$(q "${4:-}")" --ez sync true)"
  ;;
sync)
  connect; echo "$(broadcast --ez sync true)" ;;
interval)
  connect; echo "$(broadcast --ei interval "${2:?seconds}")" ;;
fallback)
  connect; echo "$(broadcast --es fallback "$(q "${2:?auto, aerial, amazon, sleep or none}")" --ez route true)" ;;
offline)
  connect; echo "$(broadcast --es offline "$(q "${2:?saved or fallback}")" --ez route true)" ;;
import)
  dir="${2:?usage: $0 import FOLDER_OF_JPGS}"
  connect
  remote=/sdcard/Download/frame-import
  tv rm -rf "$remote"; tv mkdir -p "$remote"
  for f in "$dir"/*.jpg "$dir"/*.jpeg "$dir"/*.png; do
    [ -f "$f" ] && adb -s "$SERIAL" push "$f" "$remote/" >/dev/null
  done
  tv pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
  echo "$(broadcast --es import "$remote")"
  tv rm -rf "$remote"
  tv am start -n "$PKG/.HomeActivity" >/dev/null
  ;;
art)
  connect; echo "$(broadcast --es art "$(q "${2:?on or off}")" --ez route true)" ;;
weather)
  where="${2:?usage: $0 weather ZIP|off [celsius]}"
  connect
  if [ "$where" = off ]; then echo "$(broadcast --es weather_lat "''")"; exit 0; fi
  # Open-Meteo's geocoder, from this computer; the TV only gets the coordinates.
  geo="$(curl -fsS "https://geocoding-api.open-meteo.com/v1/search?name=$where&count=1&countryCode=US&format=json")"
  lat="$(printf '%s' "$geo" | grep -o '"latitude":[-0-9.]*' | head -1 | cut -d: -f2)"
  lon="$(printf '%s' "$geo" | grep -o '"longitude":[-0-9.]*' | head -1 | cut -d: -f2)"
  place="$(printf '%s' "$geo" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)"
  [ -n "$lat" ] && [ -n "$lon" ] || { echo "Couldn't find $where."; exit 1; }
  unit=fahrenheit; [ "${3:-}" = celsius ] && unit=celsius
  echo "Weather for $where ($place, $lat, $lon) in $unit."
  echo "$(broadcast --es weather_lat "$lat" --es weather_lon "$lon" --es weather_unit "$unit")"
  ;;
preview)
  connect; tv am start -n "$PKG/.SlideshowActivity" >/dev/null; echo "Showing the screensaver. Any key on the remote closes it." ;;
dream)
  # The real screensaver path. AOSP's Somnambulator starts the current dream; Fire OS may not ship it.
  connect
  if tv am start -n com.android.systemui/.Somnambulator 2>&1 | grep -q Error; then
    echo "This Fire OS has no Somnambulator. Wait for the screensaver timeout (Settings > Display & Sounds > Screensaver) instead."
  fi ;;
homeonfire)
  connect
  url="$(curl -fsSL https://api.github.com/repos/toolicious/home-on-fire/releases/latest \
        | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | sed 's/.*"\(http[^"]*\)"/\1/')"
  [ -n "$url" ] || { echo "Couldn't find the Home on Fire APK on GitHub."; exit 1; }
  echo "Downloading $url"
  curl -fsSL -o build/home-on-fire.apk "$url"
  adb -s "$SERIAL" install -r build/home-on-fire.apk
  tv pm grant io.github.toolicious.homeonfire android.permission.WRITE_SECURE_SETTINGS
  tv monkey -p io.github.toolicious.homeonfire -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  echo "Home on Fire is open on the TV: choose Frame as the target, turn on its accessibility service,"
  echo "and turn on launch at boot. Press Home to test. Fire OS 7 waits about 5 s before switching; that's"
  echo "Android 9's app-switch guard and no sideloaded app can skip it."
  ;;
status)
  connect
  tv dumpsys package "$PKG" | grep -m1 versionName || echo "Frame isn't installed."
  echo "screensaver_components=$(tv settings get secure screensaver_components)"
  echo "screensaver_default_component=$(tv settings get secure screensaver_default_component) enabled=$(tv settings get secure screensaver_enabled)"
  tv dumpsys package $PKG | grep -o "WRITE_SECURE_SETTINGS: granted=[a-z]*" | head -1
  echo "accessibility services=$(tv settings get secure enabled_accessibility_services)"
  echo "$(broadcast)"
  tv logcat -d -s Frame:V | tail -n 15
  ;;
shot)
  connect; out="${2:-tv.png}"; adb -s "$SERIAL" exec-out screencap -p > "$out"; echo "saved $out" ;;
remove)
  connect
  orig="$AMAZON_DREAM"; orig_default="$AMAZON_DREAM"
  if [ -f .screensaver.orig ]; then orig="$(sed -n 1p .screensaver.orig)"; orig_default="$(sed -n 2p .screensaver.orig)"; fi
  [ -z "$orig" ] || [ "$orig" = null ] && orig="$AMAZON_DREAM"
  [ -z "$orig_default" ] || [ "$orig_default" = null ] && orig_default="$orig"
  tv settings put secure screensaver_components "$orig"
  tv settings put secure screensaver_default_component "$orig_default"
  adb -s "$SERIAL" uninstall "$PKG" || true
  rm -f .screensaver.orig
  echo "Screensaver restored to $orig and Frame removed. Uninstall Home on Fire too if you added it."
  ;;
*)
  sed -n '2,18p' "$0"; exit 1 ;;
esac
