#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

detect_jdk() {
  local candidates=()
  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("$JAVA_HOME")
  fi
  for p in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/Cellar/openjdk*/*/libexec/openjdk.jdk/Contents/Home \
    /Volumes/Su-SSD/dependencies/*jdk* \
    /Volumes/Su-SSD/dependencies/*jdk*/Contents/Home \
    /Volumes/Su-SSD/dependencies/*java* \
    /Volumes/Su-SSD/dependencies/*java*/Contents/Home \
    /Volumes/Su-SSD/dependencies/*jbr*/Contents/Home \
    "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
    "$HOME/.sdkman/candidates/java"/* \
    /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [[ -d "$p" ]] && candidates+=("$p")
  done

  # Also check ~/.zshrc / ~/.zprofile for exported JAVA_HOME without sourcing zsh syntax in bash
  for rc in "$HOME/.zprofile" "$HOME/.zshrc"; do
    if [[ -f "$rc" ]]; then
      while IFS= read -r line; do
        local val
        val="$(echo "$line" | sed -nE 's/.*JAVA_HOME=["'\'']?([^"'\''#]+)["'\'']?.*/\1/p' | xargs)"
        [[ -n "$val" ]] && candidates+=("$val")
      done < <(grep -E 'JAVA_HOME=' "$rc" 2>/dev/null || true)
    fi
  done

  local cand
  for cand in "${candidates[@]}"; do
    if [[ -x "$cand/bin/java" ]]; then
      echo "$cand"
      return 0
    fi
  done

  # Fallback: search up to depth 4 in /Volumes/Su-SSD/dependencies for bin/java
  local found
  found="$(find /Volumes/Su-SSD/dependencies -maxdepth 4 -type f -path '*/bin/java' 2>/dev/null | head -n 1 || true)"
  if [[ -n "$found" ]]; then
    echo "$(dirname "$(dirname "$found")")"
    return 0
  fi
  return 1
}

JDK_HOME="$(detect_jdk || true)"
if [[ -z "$JDK_HOME" ]]; then
  echo "ERROR: No JDK found. Contents of /Volumes/Su-SSD/dependencies:" >&2
  ls -1 /Volumes/Su-SSD/dependencies 2>/dev/null || true
  exit 1
fi

echo "==> Using JAVA_HOME=$JDK_HOME"
"$JDK_HOME/bin/java" -version 2>&1
cd "$ROOT_DIR"
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :bridge:test :helper:testDebugUnitTest :helper:assembleDebug
