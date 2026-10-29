#!/usr/bin/env bash
# Usage: ./build.sh app|watch   ->  build/<target>/edgelight.apk
# Env: VERSION, VERSION_CODE, COMMIT, TC (toolchain dir), KEYSTORE/KS_PASS/KEY_ALIAS (release signing)
# Minimal APK build: aapt2 -> kotlinc -> R8 -> sign. No Gradle.
set -euo pipefail
T=${1:?usage: build.sh app|watch}
cd "$(dirname "$0")"
TC=${TC:-$(realpath .toolchain)}
export JAVA_HOME=$TC/jdk PATH=$TC/jdk/bin:$PATH
BT=$TC/sdk/build-tools/35.0.0
AJ=$TC/sdk/platforms/android-35/android.jar
KC=$TC/kotlinc
OUT=build/$T
LIBS=$(ls libs/*.jar | tr '\n' ':')
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex $OUT/gen

# Build identity, shown in the apps' About screens. Overridable via env (CI can pass COMMIT / VERSION_CODE).
if [ -z "${COMMIT:-}" ] && git rev-parse HEAD >/dev/null 2>&1; then
  COMMIT=$(git rev-parse --short=7 HEAD)
  [ -n "$(git status --porcelain 2>/dev/null)" ] && COMMIT="$COMMIT-dirty"
  VERSION_CODE=${VERSION_CODE:-$(git rev-list --count HEAD)}
fi
COMMIT=${COMMIT:-dev}; VERSION=${VERSION:-1.0}; VERSION_CODE=${VERSION_CODE:-1}
BUILD_TIME=$(date -u '+%Y-%m-%d %H:%M')
cat > $OUT/gen/BuildInfo.kt <<KT
package uk.me.kierandavies.pixeledge

object BuildInfo {
    const val VERSION = "$VERSION"
    const val CODE = $VERSION_CODE
    const val COMMIT = "$COMMIT"
    const val TIME = "$BUILD_TIME UTC"
    const val LABEL = "v$VERSION ($VERSION_CODE) · $COMMIT"
}
KT

$BT/aapt2 compile --dir $T/res -o $OUT/res.zip
$BT/aapt2 compile --dir shared/res -o $OUT/shared_res.zip
$BT/aapt2 link -o $OUT/base.apk -I $AJ --manifest $T/AndroidManifest.xml --min-sdk-version 31 --target-sdk-version 35 \
  --version-code $VERSION_CODE --version-name $VERSION --proguard $OUT/aapt.pro $OUT/res.zip $OUT/shared_res.zip

SRC="shared $T/src $OUT/gen"
$KC/bin/kotlinc -jvm-target 17 -no-reflect -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions \
  -cp "$AJ:$LIBS" -d $OUT/classes $SRC 2>&1 | grep -v '^warning' || true

cat > $OUT/rules.pro <<'R'
-dontwarn **
-keepattributes SourceFile,LineNumberTable
R
PG=$(for f in libs/*.pro; do [ "$f" = libs/core-1.2.0.pro ] || printf -- '--pg-conf %s ' "$f"; done)
java -cp $BT/lib/d8.jar com.android.tools.r8.R8 --release --min-api 31 --lib $AJ \
  --pg-conf $OUT/aapt.pro --pg-conf $OUT/rules.pro $PG --output $OUT/dex \
  $(find $OUT/classes -name '*.class') $(ls libs/*.jar) $KC/lib/kotlin-stdlib.jar 2>&1 | grep -v "^Info\|^  \|^}" || true

cp $OUT/base.apk $OUT/unsigned.apk
python3 - $OUT <<'P'
import sys, zipfile
o = sys.argv[1]
z = zipfile.ZipFile(o + '/unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write(o + '/dex/classes.dex', 'classes.dex'); z.close()
P
$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
# Signing: set KEYSTORE, KS_PASS and KEY_ALIAS for a release build; otherwise a throwaway debug key is used.
# The phone and watch apps MUST be signed with the same key or the Data Layer sync won't connect.
if [ -n "${KEYSTORE:-}" ]; then
  : "${KS_PASS:?set KS_PASS}" "${KEY_ALIAS:?set KEY_ALIAS}"
  export KS_PASS
  $BT/apksigner sign --ks "$KEYSTORE" --ks-pass env:KS_PASS --ks-key-alias "$KEY_ALIAS" --out $OUT/edgelight.apk $OUT/aligned.apk
else
  KS=$TC/debug.keystore
  [ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=edge" >/dev/null
  $BT/apksigner sign --ks $KS --ks-pass pass:android --out $OUT/edgelight.apk $OUT/aligned.apk
fi
ls -l $OUT/edgelight.apk
