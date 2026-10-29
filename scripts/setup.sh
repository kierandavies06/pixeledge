#!/usr/bin/env bash
# Downloads everything the build needs into .toolchain/ (JDK, Android SDK, Kotlin) and libs/ (Play Services
# Data Layer client). Safe to re-run; finished steps are skipped. Linux x86_64 only.
set -euo pipefail
cd "$(dirname "$0")/.."
TC=${TC:-$PWD/.toolchain}
mkdir -p "$TC" libs
M=https://dl.google.com/dl/android/maven2

if [ ! -x "$TC/jdk/bin/java" ]; then
  echo "== JDK 21"
  curl -fsSL -o "$TC/jdk.tgz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$TC/jdk" && tar xzf "$TC/jdk.tgz" -C "$TC/jdk" --strip-components=1 && rm "$TC/jdk.tgz"
fi
export JAVA_HOME=$TC/jdk PATH=$TC/jdk/bin:$PATH

if [ ! -x "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== Android command-line tools"
  curl -fsSL -o "$TC/cmd.zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$TC/sdk/cmdline-tools" && unzip -q "$TC/cmd.zip" -d "$TC/sdk/cmdline-tools"
  mv "$TC/sdk/cmdline-tools/cmdline-tools" "$TC/sdk/cmdline-tools/latest" && rm "$TC/cmd.zip"
fi
if [ ! -d "$TC/sdk/build-tools/35.0.0" ] || [ ! -d "$TC/sdk/platforms/android-35" ]; then
  echo "== Android SDK platform 35 + build-tools 35.0.0"
  yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TC/sdk" --licenses >/dev/null 2>&1 || true
  "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TC/sdk" "platforms;android-35" "build-tools;35.0.0" >/dev/null
fi

if [ ! -x "$TC/kotlinc/bin/kotlinc" ]; then
  echo "== Kotlin compiler 2.1.20"
  curl -fsSL -o "$TC/k.zip" "https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip"
  unzip -q "$TC/k.zip" -d "$TC" && rm "$TC/k.zip"
fi

# Wearable Data Layer client and the few libraries it needs. Jars are extracted from the AARs; R8 shrinks the result.
fetch_aar() {  # group-path artifact version
  local n=$2-$3 d; d=$(mktemp -d)
  [ -f "libs/$n.jar" ] && return
  echo "== $n"
  curl -fsSL -o "$d/$n.aar" "$M/$1/$2/$3/$n.aar"
  unzip -q -o "$d/$n.aar" -d "$d/x"
  cp "$d/x/classes.jar" "libs/$n.jar"
  [ -f "$d/x/proguard.txt" ] && cp "$d/x/proguard.txt" "libs/$n.pro"
  rm -rf "$d"
}
fetch_aar com/google/android/gms play-services-wearable 20.0.1
fetch_aar com/google/android/gms play-services-base 18.5.0
fetch_aar com/google/android/gms play-services-basement 18.9.0
fetch_aar com/google/android/gms play-services-tasks 18.2.0
fetch_aar androidx/core core 1.2.0
[ -f libs/collection-1.0.0.jar ] || curl -fsSL -o libs/collection-1.0.0.jar "$M/androidx/collection/collection/1.0.0/collection-1.0.0.jar"
echo "Toolchain ready."
