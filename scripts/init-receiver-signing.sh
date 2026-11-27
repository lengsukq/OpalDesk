#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_PATH="$ROOT_DIR/opalcast-receiver.p12"
ALIAS="opalcast"
DEFAULT_PASSWORD="opalcast"
FORCE=0
PASSWORD="${OPALCAST_KEYSTORE_PASS:-}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Generates a permanent (100-year) PKCS#12 release signing keystore dedicated to the
standalone OpalCast Android receiver app (:receiver -> io.github.lengsukq.opalcast):
  $KEYSTORE_PATH (alias: $ALIAS)

Options:
  -p, --password <pass>  Keystore password (or set OPALCAST_KEYSTORE_PASS)
  -f, --force            Overwrite existing opalcast-receiver.p12
  -h, --help             Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--password)
      if [[ $# -lt 2 || -z "$2" ]]; then
        echo "ERROR: $1 requires a non-empty password value." >&2
        usage >&2
        exit 1
      fi
      PASSWORD="$2"
      shift 2
      ;;
    -f|--force)
      FORCE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

find_keytool() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    echo "$JAVA_HOME/bin/keytool"
    return 0
  fi
  local gradle_jdk
  gradle_jdk="$(find "$HOME/.gradle/jdks" -name keytool -type f 2>/dev/null | sort | tail -n 1 || true)"
  if [[ -n "$gradle_jdk" && -x "$gradle_jdk" ]]; then
    echo "$gradle_jdk"
    return 0
  fi
  local studio_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
  if [[ -x "$studio_jbr" ]]; then
    echo "$studio_jbr"
    return 0
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local jh
    jh="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$jh" && -x "$jh/bin/keytool" ]]; then
      echo "$jh/bin/keytool"
      return 0
    fi
  fi
  if command -v keytool >/dev/null 2>&1; then
    command -v keytool
    return 0
  fi
  return 1
}

sha256_stdin() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{print tolower($1)}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print tolower($1)}'
  else
    openssl dgst -sha256 -r | awk '{print tolower($1)}'
  fi
}

KEYTOOL="$(find_keytool || true)"
if [[ -z "$KEYTOOL" ]]; then
  echo "ERROR: keytool not found. Install JDK 17/21 or Android Studio." >&2
  exit 1
fi

if [[ -f "$KEYSTORE_PATH" && "$FORCE" -ne 1 ]]; then
  echo "ERROR: $KEYSTORE_PATH already exists. Use --force to overwrite." >&2
  exit 1
fi

if [[ -z "$PASSWORD" ]]; then
  if [[ -t 0 ]]; then
    read -r -s -p "Enter receiver keystore password (leave empty for default '${DEFAULT_PASSWORD}'): " PASSWORD
    echo ""
    if [[ -z "$PASSWORD" ]]; then
      PASSWORD="$DEFAULT_PASSWORD"
    fi
  else
    PASSWORD="$DEFAULT_PASSWORD"
  fi
fi

if [[ ${#PASSWORD} -lt 6 ]]; then
  echo "ERROR: Keystore password must be at least 6 characters." >&2
  exit 1
fi

rm -f "$KEYSTORE_PATH"

OPALCAST_KEYTOOL_PASS="$PASSWORD" "$KEYTOOL" -genkeypair \
  -storetype PKCS12 \
  -keystore "$KEYSTORE_PATH" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 36500 \
  -dname "CN=OpalCast Receiver, OU=OpalCast, O=OpalDesk, L=Local, ST=Local, C=US" \
  -storepass:env OPALCAST_KEYTOOL_PASS \
  -keypass:env OPALCAST_KEYTOOL_PASS

chmod 600 "$KEYSTORE_PATH"

CERT_SHA256="$(
  OPALCAST_KEYTOOL_PASS="$PASSWORD" "$KEYTOOL" -exportcert \
    -keystore "$KEYSTORE_PATH" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -storepass:env OPALCAST_KEYTOOL_PASS \
    | sha256_stdin
)"
if [[ -z "$CERT_SHA256" ]]; then
  echo "ERROR: Failed to compute SHA-256 fingerprint for generated receiver certificate." >&2
  exit 1
fi

cat <<EOF

========================================================================
OpalCast Receiver Signing Certificate Generated
========================================================================
Keystore Path : $KEYSTORE_PATH
Store Type    : PKCS12
Key Alias     : $ALIAS
SHA-256 (hex) : $CERT_SHA256

Local Gradle builds (:receiver) & ./scripts/build-receiver.sh will
automatically detect opalcast-receiver.p12 and sign with V1 + V2 + V3.

GitHub Actions Secrets (Settings -> Secrets and variables -> Actions):
  OPALCAST_KEYSTORE_BASE64       = base64 -i opalcast-receiver.p12 | tr -d '\\n'
  OPALCAST_KEYSTORE_PASS         = <your keystore password>
  OPALCAST_EXPECTED_CERT_SHA256  = $CERT_SHA256
========================================================================
EOF
