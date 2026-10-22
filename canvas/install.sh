#!/usr/bin/env bash
# Put Canvas on a Hisense CanvasTV (Google TV) over ADB (Wi-Fi, port 5555). Needs only `adb`.
#
#   TV_HOST=192.168.1.60 ./install.sh                install or update; turns on the overlay service,
#                                                    the screen-off permission and storage
#   TV_HOST=... ./install.sh learn                   run while Art Mode is on screen: remembers it, and
#                                                    looks for the folder its photo collection reads
#   TV_HOST=... ./install.sh findphotos              after adding a photo to Art Mode's collection from the
#                                                    phone app: shows where it landed
#   TV_HOST=... ./install.sh photos DIR              put the paintings in DIR (moves them from the old one)
#   TV_HOST=... ./install.sh art on|off|sync         paintings in the photo collection (off deletes them)
#   TV_HOST=... ./install.sh mat '#E8E3D9'|off       the mat around each painting (redoes the saved ones)
#   TV_HOST=... ./install.sh schedule 18:00-20:00|off  Art Mode in the evening when someone is home
#   TV_HOST=... ./install.sh away MIN                minutes with nobody seen before Art Mode turns off (10)
#   TV_HOST=... ./install.sh retry MIN               then minutes before it looks again (30)
#   TV_HOST=... ./install.sh sensors                 the TV's sensors; which one counts as presence
#   TV_HOST=... ./install.sh sensor auto|off|NAME    pick the presence sensor
#   TV_HOST=... ./install.sh key DPAD_DOWN           the remote key that moves focus onto the icons
#   TV_HOST=... ./install.sh start|sleep|tick        try it now: Art Mode on, off, or one schedule check
#   TV_HOST=... ./install.sh status                  what's set, and the last log lines
#   TV_HOST=... ./install.sh shot [file.png]         screenshot of the TV
#   TV_HOST=... ./install.sh remove                  delete the paintings, turn everything off, uninstall
#
# On the TV first: Settings > System > About > Android TV OS build, press OK 7 times (developer
# options); then Settings > System > Developer options > USB debugging and Wireless/Network debugging
# (or "ADB over network"). Accept the "Allow debugging?" prompt on the TV the first time.
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.canvashome
SERVICE="$PKG/$PKG.Overlay"
ADMIN="$PKG/.Admin"
APK=build/canvas.apk

: "${TV_HOST:?set TV_HOST to the TV address, e.g. TV_HOST=192.168.1.60 $0}"
command -v adb >/dev/null || { echo "adb not found. macOS: brew install android-platform-tools. Linux: apt install adb."; exit 1; }
SERIAL="$TV_HOST"; [[ "$SERIAL" == *:* ]] || SERIAL="$TV_HOST:5555"

connect() {
  adb connect "$SERIAL" >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    state="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
    [ "$state" = device ] && return 0
    [ "$state" = unauthorized ] && echo "Accept 'Allow debugging?' on the TV (tick Always allow)..."
    sleep 2
    adb connect "$SERIAL" >/dev/null 2>&1 || true
  done
  echo "Can't reach $SERIAL over ADB. Is the TV on, on this network, with network debugging on?"; exit 1
}
tv() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }
q() { printf "%q" "$1"; }   # quote for the TV's shell

# Prints the receiver's reply (the broadcast's result data, which may span lines), or am's own output.
broadcast() {
  local out data
  out="$(tv "am broadcast -n $PKG/.ConfigReceiver $*")"
  if [[ "$out" == *'data="'* ]]; then data="${out#*data=\"}"; data="${data%\"*}"; printf '%s\n' "$data"
  else printf '%s\n' "$out"; fi
}

# The activity on screen now, as package/class.
resumed() {
  tv dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity:' | head -1 \
    | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -1
}

case "${1:-install}" in
install)
  [ -f "$APK" ] || ./build.sh
  connect
  echo "$(tv getprop ro.product.manufacturer) $(tv getprop ro.product.model), Android $(tv getprop ro.build.version.release)"
  adb -s "$SERIAL" install -r "$APK"
  tv pm grant $PKG android.permission.READ_EXTERNAL_STORAGE || true
  tv pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE || true
  # The overlay service, added to any accessibility services already on.
  current="$(tv settings get secure enabled_accessibility_services)"
  case "$current" in
    null|"") services="$SERVICE" ;;
    *"$SERVICE"*) services="$current" ;;
    *) services="$current:$SERVICE" ;;
  esac
  tv settings put secure enabled_accessibility_services "$services"
  tv settings put secure accessibility_enabled 1
  # Lets the schedule turn the screen off. Only the force-lock policy.
  tv dpm set-active-admin "$ADMIN" >/dev/null 2>&1 || echo "Couldn't turn on the device admin; the schedule will use the accessibility lock instead."
  # Opening the app once lets Android deliver its boot and alarm broadcasts.
  tv am start -n "$PKG/.StatusActivity" >/dev/null
  sleep 2
  echo "Apps for the icons (from res/values/apps.xml) found on the TV:"
  tv pm list packages | sed 's/^package://' | grep -iE 'stremio|smarttube|teamsmart|liskov' | sed 's/^/  /' \
    || echo "  none: install Stremio and SmartTube (Downloader), or fix the names in res/values/apps.xml"
  echo "$(broadcast)"
  echo
  echo "Next: open Art Mode on the TV (the remote's power button, or the Art Mode app), then ./install.sh learn"
  ;;
learn)
  connect
  top="$(resumed)"
  [ -n "$top" ] || { echo "Couldn't tell what's on screen. dumpsys said:"; tv dumpsys activity activities | grep -i resumed | head -5; exit 1; }
  dream="$(tv dumpsys dreams | grep -iE 'mCurrentDream|dreaming=true|mDreamToken=' | head -3)"
  case "$top" in
    *launcher*|*tvlauncher*|*leanbacklauncher*)
      echo "On screen now: $top, which is the home screen. Open Art Mode first."; exit 1 ;;
  esac
  echo "On screen now: $top"
  if [ -n "$dream" ] && ! echo "$dream" | grep -q 'null'; then
    echo "Note: a screensaver (dream) is running: $dream"
    echo "If Art Mode is that screensaver rather than an app, the icons can show but the schedule can't start it."
  fi
  echo "$(broadcast --es art_component "$(q "$top")")"
  pkg="${top%%/*}"
  echo
  echo "Art Mode's other screens, in case another one is the gallery to start in the evening:"
  tv dumpsys package "$pkg" | grep -oE "$pkg/[A-Za-z0-9_.$]+" | sort -u | sed 's/^/  /' | head -20
  echo
  echo "Folders that may hold Art Mode's photo collection:"
  tv "find /sdcard /storage/emulated/0 -maxdepth 4 -type d \\( -iname '*art*' -o -iname '*canvas*' -o -iname '*photo*' -o -iname '*gallery*' -o -iname '*hisense*' \\) 2>/dev/null" \
    | sort -u | sed 's/^/  /' | head -30
  tv "ls -d /sdcard/Android/data/$pkg/* /sdcard/Android/data/$pkg/files/* 2>/dev/null" | sed 's/^/  /' | head -20
  echo
  echo "To be sure: add one photo to Art Mode's photo collection from the phone app, then ./install.sh findphotos"
  ;;
findphotos)
  connect
  echo "Pictures saved on the TV in the last 30 minutes:"
  found="$(tv "find /sdcard /storage -type f \\( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' -o -iname '*.heic' \\) -mmin -30 2>/dev/null" | grep -v '/canvas-' | sort -u)"
  if [ -z "$found" ]; then
    echo "  none in shared storage. Art Mode may keep its photos in its private storage, which other apps"
    echo "  can't write to. A USB stick is the other way in: see README.md, Photo collection."
  else
    printf '%s\n' "$found" | sed 's/^/  /'
    dir="$(dirname "$(printf '%s\n' "$found" | head -1)")"
    echo "Then: ./install.sh photos '$dir'"
  fi
  ;;
photos)
  connect; echo "$(broadcast --es photo_dir "$(q "${2:?usage: $0 photos /sdcard/FOLDER}")")" ;;
art)
  connect
  case "${2:?on, off or sync}" in
    sync) echo "$(broadcast --ez sync true)" ;;
    *) echo "$(broadcast --es art "$(q "$2")")" ;;
  esac ;;
mat)
  connect; echo "$(broadcast --es mat "$(q "${2:?a color like '#E8E3D9', or off}")")" ;;
schedule)
  connect; echo "$(broadcast --es schedule "$(q "${2:?like 18:00-20:00, or off}")")" ;;
away)
  connect; echo "$(broadcast --ei away "${2:?minutes}")" ;;
retry)
  connect; echo "$(broadcast --ei retry "${2:?minutes}")" ;;
sensors)
  connect
  echo "$(broadcast --ez sensors true)"
  echo
  echo "What Android itself lists (dumpsys sensorservice):"
  tv dumpsys sensorservice | sed -n '/Sensor List:/,/^$/p' | head -40
  ;;
sensor)
  connect; echo "$(broadcast --es sensor "$(q "${2:?auto, off or a name from ./install.sh sensors}")")" ;;
key)
  connect; echo "$(broadcast --es key "$(q "${2:?a key name like DPAD_DOWN, DPAD_UP, MENU or INFO}")")" ;;
start)
  connect; echo "$(broadcast --ez start true)" ;;
sleep)
  connect; echo "$(broadcast --ez sleep true)" ;;
tick)
  connect; echo "$(broadcast --ez tick true)" ;;
status)
  connect
  tv dumpsys package "$PKG" | grep -m1 versionName || echo "Canvas isn't installed."
  echo "accessibility services=$(tv settings get secure enabled_accessibility_services)"
  echo "on screen now: $(resumed)"
  echo "$(broadcast)"
  tv logcat -d -s Canvas:V | tail -n 15
  ;;
shot)
  connect; out="${2:-tv.png}"; adb -s "$SERIAL" exec-out screencap -p > "$out"; echo "saved $out" ;;
remove)
  connect
  echo "$(broadcast --es art off --ez unadmin true)" | head -1
  current="$(tv settings get secure enabled_accessibility_services)"
  rest="$(printf '%s' "$current" | tr ':' '\n' | grep -vF "$SERVICE" | paste -sd: - || true)"
  if [ -n "$rest" ]; then tv settings put secure enabled_accessibility_services "$rest"
  else tv settings delete secure enabled_accessibility_services >/dev/null; fi
  sleep 1
  adb -s "$SERIAL" uninstall "$PKG" || true
  echo "Canvas removed, with its paintings. Art Mode and its own photos are as they were."
  ;;
*)
  sed -n '2,25p' "$0"; exit 1 ;;
esac
