#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="/Users/tsifrokayf/Library/Android/sdk"
BT="$SDK/build-tools/37.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
OUT="$DIR/build"
KEYSTORE="/Users/tsifrokayf/Desktop/OnePlus16/HzFixer/hzfix.jks"
FINAL_APK="/Users/tsifrokayf/Desktop/OnePlus16/OP16HzUnlock-1.1.apk"

rm -rf "$OUT"
mkdir -p "$OUT/res_flat" "$OUT/gen" "$OUT/stub_classes" "$OUT/classes" "$OUT/dex"

# 1. Compile Xposed API stubs (compileOnly - NOT packaged into DEX)
javac --release 11 -d "$OUT/stub_classes" \
  $(find "$DIR/stubs" -name '*.java')

# 2. Compile resources + link manifest + assets
"$BT/aapt2" compile --dir "$DIR/res" -o "$OUT/res_flat/res.zip"
"$BT/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$DIR/AndroidManifest.xml" \
  -A "$DIR/assets" \
  --java "$OUT/gen" \
  -o "$OUT/unsigned_unaligned.apk" \
  "$OUT/res_flat/res.zip"

# 3. Compile module sources against Android + Xposed stubs
javac --release 11 \
  -classpath "$ANDROID_JAR:$OUT/stub_classes" \
  -d "$OUT/classes" \
  "$DIR/src/com/op16/hzunlock/"*.java

# 4. DEX only module classes
"$BT/d8" \
  --min-api 29 \
  --lib "$ANDROID_JAR" \
  --classpath "$OUT/stub_classes" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

# 5. Add classes.dex to APK
(cd "$OUT/dex" && zip -qj "$OUT/unsigned_unaligned.apk" classes.dex)

# 6. Align and sign
"$BT/zipalign" -f -p 4 "$OUT/unsigned_unaligned.apk" "$OUT/unsigned.apk"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias hzfix \
  --ks-pass pass:hzfix123 \
  --key-pass pass:hzfix123 \
  --out "$FINAL_APK" \
  "$OUT/unsigned.apk"

"$BT/apksigner" verify "$FINAL_APK"
ls -lh "$FINAL_APK"
