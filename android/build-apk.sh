#!/usr/bin/env bash
# Builds the APK without Gradle.
#
# Gradle cannot run on this machine: every JVM here fails java.nio.channels.Selector.open()
# with "Unable to establish loopback connection", on three different JDKs, and Gradle needs
# a Selector to talk to its own daemon. The SDK tools below do not, so they still work.
#
# Rebuild the native library first if it changed:
#   cd ../librespot && cargo ndk -t "$ABI" -P 26 -o ./embed/jniLibs build --release \
#       -p librespot-embed --no-default-features --features rustls-tls-webpki-roots,cast
set -euo pipefail

SDK="${ANDROID_SDK:-$HOME/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-36/android.jar"
JAVA_HOME="${JAVA_HOME:-C:\Program Files\Java\jdk-21.0.10}"
KOTLINC="${KOTLINC:-/c/kotlinc/bin/kotlinc.bat}"
STDLIB="${STDLIB:-/c/kotlinc/lib/kotlin-stdlib.jar}"
OUT="build-manual"
# ponytail: un solo ABI por APK. El Chromecast (sabrina) es armeabi-v7a; ABI=arm64-v8a
# para un movil. Si hiciera falta un APK universal, empaquetar los dos directorios.
ABI="${ABI:-armeabi-v7a}"
SO="../librespot/embed/jniLibs/$ABI/liblibrespot_embed.so"

HERE="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)"

mkdir -p "$OUT/gen" "$OUT/classes"

# aapt2 needs `package` in the manifest; AGP injects it from `namespace`, so patch a copy
# rather than the original, which keeps a future Gradle build working.
sed '0,/<manifest/s|<manifest|<manifest package="org.librespot.embed"|' \
    app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"

echo "==> resources"
"$BT/aapt2.exe" compile --dir app/src/main/res -o "$OUT/res.zip"
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$AJ" --manifest "$OUT/AndroidManifest.xml" \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 35 "$OUT/res.zip"

echo "==> R.java"
# --release 17 a juego con el -jvm-target de kotlinc: con un JDK 21 por defecto
# javac emite class file 65 y d8 lo rechaza con "Unsupported class file major version".
"$JAVA_HOME/bin/javac" --release 17 -nowarn -d "$OUT/classes" "$OUT/gen/org/librespot/embed/R.java"

echo "==> kotlin"
# Absolute paths only: kotlinc is a Windows batch file and does not read Git Bash's
# relative paths, which shows up as a bogus "unresolved reference 'R'".
"$KOTLINC" -classpath "$(cygpath -w "$AJ");$HERE/$OUT/classes" \
    -jvm-target 17 -nowarn -d "$OUT/classes" app/src/main/kotlin/org/librespot/embed/*.kt

echo "==> dex"
# The kotlin.Metadata warnings are this kotlinc being newer than the R8 in build-tools.
# They only affect Kotlin reflection, which nothing here uses.
# kotlin-stdlib va como entrada de programa, no como --lib: sin ella el dex no lleva
# kotlin.jvm.internal.Intrinsics y el servicio muere con NoClassDefFoundError en el
# primer metodo que valide un parametro, mucho antes de tocar nada de Spotify.
"$BT/d8.bat" --release --min-api 26 --lib "$AJ" --output "$OUT" $(find "$OUT/classes" -name '*.class') "$(cygpath -w "$STDLIB")"

echo "==> package"
python - "$OUT" "$SO" "$ABI" <<'PY'
import shutil, sys, zipfile
out, so, abi = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copy(f"{out}/base.apk", f"{out}/app-unsigned.apk")
with zipfile.ZipFile(f"{out}/app-unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{out}/classes.dex", "classes.dex")
    z.write(so, f"lib/{abi}/liblibrespot_embed.so")
PY

echo "==> sign"
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
    mkdir -p "$HOME/.android"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/zipalign.exe" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/CrossfadeBridge-debug.apk" "$OUT/app-aligned.apk"
"$BT/apksigner.bat" verify "$OUT/CrossfadeBridge-debug.apk"

echo
echo "listo: $OUT/CrossfadeBridge-debug.apk"
