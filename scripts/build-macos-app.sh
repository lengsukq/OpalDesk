#!/usr/bin/env bash
set -euo pipefail

# Build and package OpalDesk.app (and optionally .dmg) for macOS 14+ (Apple Silicon / Universal).
# Safe to invoke from any working directory (repo root or macos/OpalDeskMac).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PKG_DIR="$REPO_ROOT/macos/OpalDeskMac"
OUT_DIR="$REPO_ROOT/build/out"
ICON_GENERATOR="$SCRIPT_DIR/generate-macos-icon.swift"
APP_NAME="OpalDesk"
BUNDLE_ID="io.github.lengsukq.opaldesk.mac"
APP_BUNDLE="$OUT_DIR/${APP_NAME}.app"

CREATE_DMG=0
SKIP_BUILD=0
SIGN_IDENTITY="${OPALDESK_MAC_SIGN_IDENTITY:--}"
ICON_TMP=""
DMG_STAGE=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [--dmg] [--skip-build] [--sign-identity <identity>]

Build and package ${APP_NAME}.app (and optionally .dmg) for macOS 14+.

Options:
  --dmg                       Also package a compressed UDZO .dmg image
  --skip-build                Reuse an already-built release binary instead of running swift build
  --sign-identity <identity>  Code-signing identity (defaults to ad-hoc "-")
  -h, --help                  Show this help message

Environment overrides:
  OPALDESK_MAC_SIGN_IDENTITY  Code-signing identity (defaults to ad-hoc "-")
  OPALDESK_MAC_VERSION        Bundle version override (defaults to helper/build.gradle.kts versionName)
EOF
}

cleanup() {
    if [[ -n "$ICON_TMP" ]]; then
        rm -rf "$ICON_TMP"
    fi
    if [[ -n "$DMG_STAGE" ]]; then
        rm -rf "$DMG_STAGE"
    fi
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dmg)
            CREATE_DMG=1
            shift
            ;;
        --skip-build)
            SKIP_BUILD=1
            shift
            ;;
        --sign-identity)
            if [[ $# -lt 2 || -z "$2" ]]; then
                echo "ERROR: $1 requires a non-empty signing identity." >&2
                usage >&2
                exit 1
            fi
            SIGN_IDENTITY="$2"
            shift 2
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

VERSION="${OPALDESK_MAC_VERSION:-}"
if [[ -z "$VERSION" && -f "$REPO_ROOT/helper/build.gradle.kts" ]]; then
    VERSION="$(sed -nE 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$REPO_ROOT/helper/build.gradle.kts" | head -n 1 || true)"
fi
VERSION="${VERSION:-0.0.1}"

echo "==> Packaging ${APP_NAME}.app (v${VERSION})"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    echo "==> Building release binary via SwiftPM..."
    (cd "$PKG_DIR" && swift build -c release)
fi

BIN_DIR="$(cd "$PKG_DIR" && swift build -c release --show-bin-path)"
BINARY_PATH="$BIN_DIR/$APP_NAME"
if [[ ! -x "$BINARY_PATH" ]]; then
    echo "ERROR: Release binary not found at $BINARY_PATH" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS" "$APP_BUNDLE/Contents/Resources"

cp -f "$BINARY_PATH" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
chmod +x "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
printf 'APPL????' > "$APP_BUNDLE/Contents/PkgInfo"

# Generate native vector AppIcon.icns when macOS icon tools are available.
ICON_KEY_XML=""
if command -v iconutil >/dev/null 2>&1 && command -v swift >/dev/null 2>&1; then
    if [[ ! -f "$ICON_GENERATOR" ]]; then
        echo "ERROR: Icon generator script not found at $ICON_GENERATOR" >&2
        exit 1
    fi
    ICON_TMP="$(mktemp -d "${TMPDIR:-/tmp}/opaldesk-icon.XXXXXX")"
    ICONSET_DIR="$ICON_TMP/AppIcon.iconset"
    mkdir -p "$ICONSET_DIR"
    echo "==> Generating AppIcon.icns..."
    if ! swift "$ICON_GENERATOR" "$ICONSET_DIR"; then
        echo "ERROR: Failed to render iconset PNGs via $ICON_GENERATOR" >&2
        exit 1
    fi
    if ! iconutil -c icns "$ICONSET_DIR" -o "$APP_BUNDLE/Contents/Resources/AppIcon.icns"; then
        echo "ERROR: Failed to compile AppIcon.icns via iconutil" >&2
        exit 1
    fi
    ICON_KEY_XML=$'    <key>CFBundleIconFile</key>\n    <string>AppIcon</string>'
else
    echo "WARNING: iconutil or swift not found; skipping AppIcon.icns generation." >&2
fi

xml_escape() {
    local s="$1"
    s="${s//&/&amp;}"
    s="${s//</&lt;}"
    s="${s//>/&gt;}"
    s="${s//\"/&quot;}"
    s="${s//\'/&apos;}"
    printf '%s' "$s"
}

FEEDBACK_PLIST_XML=""
if [[ -n "${OPALDESK_SENTRY_DSN:-}" ]]; then
    FEEDBACK_PLIST_XML+=$'    <key>OpalDeskSentryDSN</key>\n    <string>'"$(xml_escape "$OPALDESK_SENTRY_DSN")"$'</string>\n'
fi
if [[ -n "${OPALDESK_SENTRY_TUNNEL_URL:-}" ]]; then
    FEEDBACK_PLIST_XML+=$'    <key>OpalDeskSentryTunnelURL</key>\n    <string>'"$(xml_escape "$OPALDESK_SENTRY_TUNNEL_URL")"$'</string>\n'
fi
if [[ -n "${OPALDESK_FEEDBACK_STATUS_URL:-}" ]]; then
    FEEDBACK_PLIST_XML+=$'    <key>OpalDeskFeedbackStatusURL</key>\n    <string>'"$(xml_escape "$OPALDESK_FEEDBACK_STATUS_URL")"$'</string>\n'
fi

cat > "$APP_BUNDLE/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>zh_CN</string>
    <key>CFBundleDisplayName</key>
    <string>${APP_NAME}</string>
    <key>CFBundleExecutable</key>
    <string>${APP_NAME}</string>
    <key>CFBundleIdentifier</key>
    <string>${BUNDLE_ID}</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>${APP_NAME}</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${VERSION}</string>
${ICON_KEY_XML}
    <key>LSApplicationCategoryType</key>
    <string>public.app-category.utilities</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>NSBonjourServices</key>
    <array>
        <string>_opaldesk._tcp</string>
    </array>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSLocalNetworkUsageDescription</key>
    <string>OpalDesk 需要访问局域网以通过 Bonjour 自动发现并低延迟连接同网络下的 Android 桌面服务。</string>
    <key>NSBluetoothAlwaysUsageDescription</key>
    <string>OpalDesk 需要使用蓝牙近场发现已信任的 Android 手机，并在无同局域网时一键拉起手机热点。</string>
    <key>NSMicrophoneUsageDescription</key>
    <string>OpalDesk 需要访问麦克风以将 Mac 的语音实时回传至投屏中的 Android 设备。</string>
    <key>NSCameraUsageDescription</key>
    <string>OpalDesk 需要访问摄像头以在 Root 模式下将 Mac 摄像头画面映射至投屏中的 Android 设备。</string>
    <key>NSSupportsAutomaticGraphicsSwitching</key>
    <true/>
    <key>CFBundleURLTypes</key>
    <array>
        <dict>
            <key>CFBundleTypeRole</key>
            <string>Viewer</string>
            <key>CFBundleURLName</key>
            <string>${BUNDLE_ID}.url</string>
            <key>CFBundleURLSchemes</key>
            <array>
                <string>opaldesk</string>
            </array>
        </dict>
    </array>
    <key>CFBundleDocumentTypes</key>
    <array>
        <dict>
            <key>CFBundleTypeExtensions</key>
            <array>
                <string>apk</string>
                <string>apks</string>
                <string>apkm</string>
                <string>xapk</string>
            </array>
            <key>CFBundleTypeName</key>
            <string>Android Application Package</string>
            <key>CFBundleTypeRole</key>
            <string>Viewer</string>
            <key>LSHandlerRank</key>
            <string>Alternate</string>
            <key>LSItemContentTypes</key>
            <array>
                <string>com.android.package-archive</string>
                <string>io.github.lengsukq.opaldesk.apks</string>
                <string>io.github.lengsukq.opaldesk.apkm</string>
                <string>io.github.lengsukq.opaldesk.xapk</string>
            </array>
        </dict>
    </array>
    <key>UTImportedTypeDeclarations</key>
    <array>
        <dict>
            <key>UTTypeConformsTo</key>
            <array>
                <string>public.zip-archive</string>
                <string>public.data</string>
            </array>
            <key>UTTypeDescription</key>
            <string>Android Package Archive</string>
            <key>UTTypeIdentifier</key>
            <string>com.android.package-archive</string>
            <key>UTTypeTagSpecification</key>
            <dict>
                <key>public.filename-extension</key>
                <array>
                    <string>apk</string>
                </array>
                <key>public.mime-type</key>
                <array>
                    <string>application/vnd.android.package-archive</string>
                </array>
            </dict>
        </dict>
        <dict>
            <key>UTTypeConformsTo</key>
            <array>
                <string>public.zip-archive</string>
                <string>public.data</string>
            </array>
            <key>UTTypeDescription</key>
            <string>Android Split APK Archive</string>
            <key>UTTypeIdentifier</key>
            <string>io.github.lengsukq.opaldesk.apks</string>
            <key>UTTypeTagSpecification</key>
            <dict>
                <key>public.filename-extension</key>
                <array>
                    <string>apks</string>
                </array>
            </dict>
        </dict>
        <dict>
            <key>UTTypeConformsTo</key>
            <array>
                <string>public.zip-archive</string>
                <string>public.data</string>
            </array>
            <key>UTTypeDescription</key>
            <string>APKMirror Bundle Archive</string>
            <key>UTTypeIdentifier</key>
            <string>io.github.lengsukq.opaldesk.apkm</string>
            <key>UTTypeTagSpecification</key>
            <dict>
                <key>public.filename-extension</key>
                <array>
                    <string>apkm</string>
                </array>
            </dict>
        </dict>
        <dict>
            <key>UTTypeConformsTo</key>
            <array>
                <string>public.zip-archive</string>
                <string>public.data</string>
            </array>
            <key>UTTypeDescription</key>
            <string>Android XAPK Bundle Archive</string>
            <key>UTTypeIdentifier</key>
            <string>io.github.lengsukq.opaldesk.xapk</string>
            <key>UTTypeTagSpecification</key>
            <dict>
                <key>public.filename-extension</key>
                <array>
                    <string>xapk</string>
                </array>
            </dict>
        </dict>
    </array>
    <key>NSServices</key>
    <array>
        <dict>
            <key>NSMenuItem</key>
            <dict>
                <key>default</key>
                <string>发送到 Android 手机 (OpalDesk)</string>
            </dict>
            <key>NSMessage</key>
            <string>sendFilesToAndroid</string>
            <key>NSPortName</key>
            <string>${APP_NAME}</string>
            <key>NSRequiredContext</key>
            <dict/>
            <key>NSSendTypes</key>
            <array>
                <string>public.file-url</string>
                <string>NSFilenamesPboardType</string>
                <string>public.png</string>
                <string>public.tiff</string>
                <string>public.utf8-plain-text</string>
                <string>NSStringPboardType</string>
            </array>
        </dict>
    </array>
${FEEDBACK_PLIST_XML}</dict>
</plist>
EOF

plutil -lint "$APP_BUNDLE/Contents/Info.plist"

if command -v codesign >/dev/null 2>&1; then
    echo "==> Code-signing bundle (identity: ${SIGN_IDENTITY})..."
    if [[ "$SIGN_IDENTITY" == "-" ]]; then
        codesign --force --deep --sign - "$APP_BUNDLE"
    else
        codesign --force --deep --options runtime --timestamp --sign "$SIGN_IDENTITY" "$APP_BUNDLE"
    fi
    codesign --verify --deep --strict --verbose=2 "$APP_BUNDLE"
fi

echo "==> Created app bundle: $APP_BUNDLE"

if [[ "$CREATE_DMG" -eq 1 ]]; then
    DMG_PATH="$OUT_DIR/${APP_NAME}_v${VERSION}.dmg"
    DMG_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/opaldesk-dmg.XXXXXX")"
    cp -R "$APP_BUNDLE" "$DMG_STAGE/"
    ln -s /Applications "$DMG_STAGE/Applications"
    rm -f "$DMG_PATH"
    echo "==> Creating DMG image: $DMG_PATH"
    hdiutil create -volname "$APP_NAME" -srcfolder "$DMG_STAGE" -ov -format UDZO "$DMG_PATH"
    echo "==> Created DMG: $DMG_PATH"
fi
