#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

EXT_GRADLE_HOME="/Volumes/Su-SSD/dependencies/gradle"
if [[ ! -d "$EXT_GRADLE_HOME" ]]; then
  echo "ERROR: External Gradle home not found at $EXT_GRADLE_HOME (refusing internal SSD)." >&2
  exit 1
fi

detect_jdk() {
  local found cand ver major best17=""
  # 1. Check JDK 21+ inside /Volumes/Su-SSD/dependencies/gradle/jdks or /Volumes/Su-SSD/dependencies first
  while IFS= read -r found; do
    [[ -z "$found" ]] && continue
    cand="$(dirname "$(dirname "$found")")"
    if [[ -x "$cand/bin/java" ]]; then
      ver="$("$cand/bin/java" -version 2>&1 | head -n 1 || true)"
      major="$(echo "$ver" | sed -nE 's/.*version "([0-9]+).*/\1/p' || true)"
      if [[ -n "$major" && "$major" -ge 21 ]]; then
        echo "$cand"
        return 0
      elif [[ -n "$major" && "$major" -ge 17 && -z "$best17" ]]; then
        best17="$cand"
      fi
    fi
  done < <(find /Volumes/Su-SSD/dependencies -maxdepth 6 -type f -path '*/bin/java' 2>/dev/null || true)

  if [[ -n "$best17" ]]; then
    echo "$best17"
    return 0
  fi
  return 1
}

JDK_HOME="$(detect_jdk || true)"
if [[ -z "$JDK_HOME" ]]; then
  echo "ERROR: No JDK 17/21 found under /Volumes/Su-SSD/dependencies." >&2
  exit 1
fi

export GRADLE_USER_HOME="$EXT_GRADLE_HOME"
export JAVA_HOME="$JDK_HOME"
export ANDROID_SDK_ROOT="/Volumes/Su-SSD/dependencies/android-sdk"
export ANDROID_HOME="/Volumes/Su-SSD/dependencies/android-sdk"
if [[ -d "/Volumes/Su-SSD/dependencies/.android" ]]; then
  export ANDROID_USER_HOME="/Volumes/Su-SSD/dependencies/.android"
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> External GRADLE_USER_HOME=$GRADLE_USER_HOME (100% on /Volumes/Su-SSD)"
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -n 2

cd "$ROOT_DIR"
TASKS=(
  ":bridge:test"
  ":helper:testDebugUnitTest"
  ":helper:assembleDebug"
)
if [[ $# -gt 0 ]]; then
  TASKS=("$@")
fi

COMMON_ARGS=(
  "--gradle-user-home=$GRADLE_USER_HOME"
  "--console=plain"
)

echo "==> Trying offline verification first using cached /Volumes/Su-SSD/dependencies/gradle..."
if ./gradlew --offline "${COMMON_ARGS[@]}" "${TASKS[@]}"; then
  echo "==> Offline verification & APK assembly succeeded!"
  exit 0
fi

echo "==> Retrying with external GRADLE_USER_HOME=$GRADLE_USER_HOME..."
./gradlew "${COMMON_ARGS[@]}" "${TASKS[@]}"
