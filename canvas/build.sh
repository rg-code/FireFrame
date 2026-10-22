#!/usr/bin/env bash
# Build Canvas without Gradle or Android Studio. Output: build/canvas.apk
#
# Uses the Android SDK's build-tools (aapt2, d8, zipalign, apksigner) when it finds an SDK, or
# else Debian/Ubuntu's packaged tools:
#   apt install openjdk-17-jdk-headless aapt dalvik-exchange zipalign apksigner
# with an android.jar for API 28 or newer in ANDROID_JAR (any SDK platform's android.jar works).
#
# The APK is signed with ../signing/frame.jks (Frame's key), kept in the project on purpose: every build has the same
# signature, so `adb install -r` updates in place and keeps the settings.
set -euo pipefail
cd "$(dirname "$0")"

MIN_SDK=28      # Android 9. The CanvasTV runs Google TV on Android 12 or later.
TARGET_SDK=29   # 29 keeps the legacy shared-storage access the photo folder needs.
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
for guess in "$SDK" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools; do
  if [ -n "$guess" ] && [ -d "$guess/build-tools" ]; then SDK="$guess"; break; fi
done

if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
  JAR="${ANDROID_JAR:-$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar}"
  AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
  MODE=sdk
else
  JAR="${ANDROID_JAR:-/opt/android/android.jar}"
  ZIPALIGN="$(command -v zipalign || true)"; APKSIGNER="$(command -v apksigner || true)"
  DX="$(command -v dalvik-exchange || command -v dx || true)"
  MODE=debian
fi
[ -f "$JAR" ] || { echo "No android.jar. Install an SDK platform (sdkmanager 'platforms;android-34') or set ANDROID_JAR."; exit 1; }
command -v javac >/dev/null || { echo "No javac. Install a JDK (17 works)."; exit 1; }

rm -rf build && mkdir -p build/gen build/classes build/dex
if [ "$MODE" = sdk ]; then
  "$AAPT2" compile --dir res -o build/res.zip
  "$AAPT2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip
else
  aapt package -f -M AndroidManifest.xml -S res -I "$JAR" -F build/unsigned.apk -J build/gen \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
fi

# Java 8 bytecode, no lambdas in the source: dx can't desugar them, and the APK stays tiny.
javac -nowarn -Xlint:-options --release 8 -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')

if [ "$MODE" = sdk ]; then
  "$D8" --release --min-api $MIN_SDK --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
else
  "$DX" --dex --min-sdk-version=$MIN_SDK --output=build/dex/classes.dex build/classes
fi
(cd build/dex && zip -q ../unsigned.apk classes.dex)

"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk
"$APKSIGNER" sign --ks ../signing/frame.jks --ks-pass pass:framehome --key-pass pass:framehome \
  --ks-key-alias frame --min-sdk-version $MIN_SDK --out build/canvas.apk build/aligned.apk
rm -f build/canvas.apk.idsig
echo "built $(pwd)/build/canvas.apk ($(wc -c < build/canvas.apk | tr -d ' ') bytes, $MODE tools)"
