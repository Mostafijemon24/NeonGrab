#!/usr/bin/env bash
# Build release APK on macOS (uses Android Studio's JDK + SDK).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

if [[ ! -x "$JBR/bin/java" ]]; then
  echo "Java not found. Install Android Studio or set JAVA_HOME to a JDK 17+."
  exit 1
fi
if [[ ! -d "$SDK" ]]; then
  echo "Android SDK not found at $SDK. Install SDK in Android Studio → Settings → Android SDK."
  exit 1
fi

export JAVA_HOME="$JBR"
export ANDROID_HOME="$SDK"

mkdir -p "$ANDROID"
cat > "$ANDROID/local.properties" <<EOF
sdk.dir=$SDK
EOF

cd "$ANDROID"
chmod +x gradlew
./gradlew clean
./gradlew assembleRelease --no-build-cache "$@"

echo ""
echo "APK: $ANDROID/app/build/outputs/apk/release/app-release-unsigned.apk"
