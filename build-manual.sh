#!/bin/bash
# Manual APK build for Mandala Eclipse Live — no Gradle (the Gradle daemon's
# TCP loopback is hijacked in this sandbox; see AGENTS.md).
# Steps: aapt2 compile -> aapt2 link -> javac -> d8 -> zipalign -> apksigner.
set -e
PROJ=~/workspace/mandala-wallpaper/android
OUT=$PROJ/app/build/manual
SDK=~/android-sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=~/jdk/jdk-21.0.12.1+1
export PATH=$JAVA_HOME/bin:$PATH

rm -rf "$OUT"; mkdir -p "$OUT/compiled_res" "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "== aapt2 compile =="
$BT/aapt2 compile --dir $PROJ/app/src/main/res -o $OUT/compiled_res.zip

echo "== aapt2 link =="
# aapt2 (unlike AGP) needs the package attribute in the manifest: inject it
# into a build-time copy so the repo manifest stays AGP-8 clean.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.apexforge.mandalawallpaper"|' \
  $PROJ/app/src/main/AndroidManifest.xml > $OUT/AndroidManifest.xml
$BT/aapt2 link -o $OUT/base.apk \
  -I $AJAR \
  --manifest $OUT/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  -A $PROJ/app/src/main/assets \
  $OUT/compiled_res.zip \
  --java $OUT/gen

echo "== javac =="
find $PROJ/app/src/main/java $OUT/gen -name "*.java" > $OUT/sources.txt
javac -source 17 -target 17 -nowarn -cp $AJAR -d $OUT/classes @$OUT/sources.txt
echo "compiled $(wc -l < $OUT/sources.txt) sources"

echo "== d8 =="
$BT/d8 --min-api 26 --lib $AJAR --output $OUT/dex \
  $(find $OUT/classes -name "*.class" | tr '\n' ' ')

echo "== package + align + sign =="
cp $OUT/base.apk $OUT/unaligned.apk
$BT/d8 --version > /dev/null # sanity
cd $OUT/dex && zip -q -X ../unaligned.apk classes.dex && cd - > /dev/null
$BT/zipalign -f 4 $OUT/unaligned.apk $OUT/mandala-eclipse-live.apk
if [ ! -f ~/.android-debug.keystore ]; then
  keytool -genkeypair -noprompt -keystore ~/.android-debug.keystore \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
$BT/apksigner sign --ks ~/.android-debug.keystore --ks-pass:android \
  --key-pass:android --out $OUT/mandala-eclipse-live-signed.apk $OUT/mandala-eclipse-live.apk
$BT/apksigner verify --print-certs $OUT/mandala-eclipse-live-signed.apk | head -3
ls -la $OUT/mandala-eclipse-live-signed.apk
echo BUILD_OK
