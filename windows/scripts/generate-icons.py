#!/usr/bin/env python3
"""
Generates Tauri v2 Windows/Desktop RGBA PNGs and multi-resolution `icon.ico`
in `windows/src-tauri/icons/` from `scripts/generate-macos-icon.swift`.
"""

import shutil
import struct
import subprocess
import tempfile
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def verify_rgba_png(png_bytes: bytes, label: str) -> tuple[int, int]:
    if len(png_bytes) < 33 or png_bytes[:8] != PNG_SIGNATURE:
        raise ValueError(f"{label}: invalid PNG signature")
    chunk_type = png_bytes[12:16]
    if chunk_type != b"IHDR":
        raise ValueError(f"{label}: first chunk is not IHDR")
    width, height, bit_depth, color_type = struct.unpack(">IIBB", png_bytes[16:26])
    if bit_depth != 8 or color_type != 6:
        raise ValueError(
            f"{label}: expected 8-bit RGBA (color_type=6), got bit_depth={bit_depth}, color_type={color_type}"
        )
    return width, height


def pack_ico(frames: list[tuple[int, bytes]]) -> bytes:
    header = struct.pack("<HHH", 0, 1, len(frames))
    dir_entries = bytearray()
    payload = bytearray()
    offset = 6 + 16 * len(frames)

    for px, data in frames:
        verify_rgba_png(data, f"ico-{px}px")
        w_byte = 0 if px >= 256 else px
        h_byte = 0 if px >= 256 else px
        dir_entries.extend(
            struct.pack("<BBBBHHII", w_byte, h_byte, 0, 0, 1, 32, len(data), offset)
        )
        payload.extend(data)
        offset += len(data)

    return bytes(header) + bytes(dir_entries) + bytes(payload)


def main() -> None:
    repo_root = Path(__file__).resolve().parents[2]
    swift_script = repo_root / "scripts" / "generate-macos-icon.swift"
    icons_dir = repo_root / "windows" / "src-tauri" / "icons"
    icons_dir.mkdir(parents=True, exist_ok=True)

    swift_bin = shutil.which("swift")
    if not swift_bin or not swift_script.is_file():
        raise RuntimeError(
            "generate-icons.py requires macOS `swift` and `scripts/generate-macos-icon.swift`."
        )

    with tempfile.TemporaryDirectory(prefix="opaldesk-win-icons-") as tmp:
        tmp_dir = Path(tmp)
        subprocess.run([swift_bin, str(swift_script), str(tmp_dir)], check=True)

        png_16 = (tmp_dir / "icon_16x16.png").read_bytes()
        png_32 = (tmp_dir / "icon_32x32.png").read_bytes()
        png_64 = (tmp_dir / "icon_32x32@2x.png").read_bytes()
        png_128 = (tmp_dir / "icon_128x128.png").read_bytes()
        png_256 = (tmp_dir / "icon_128x128@2x.png").read_bytes()
        png_512 = (tmp_dir / "icon_512x512.png").read_bytes()

        sips_bin = shutil.which("sips")
        ico_frames: list[tuple[int, bytes]] = [(16, png_16), (32, png_32)]
        if sips_bin:
            out_48 = tmp_dir / "icon_48x48.png"
            subprocess.run(
                [sips_bin, "-z", "48", "48", str(tmp_dir / "icon_128x128@2x.png"), "--out", str(out_48)],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            png_48 = out_48.read_bytes()
            if png_48[25] == 6:
                ico_frames.append((48, png_48))

        ico_frames.extend([(64, png_64), (128, png_128), (256, png_256)])

        targets = {
            "32x32.png": png_32,
            "128x128.png": png_128,
            "128x128@2x.png": png_256,
            "icon.png": png_512,
        }
        for filename, raw in targets.items():
            w, h = verify_rgba_png(raw, filename)
            (icons_dir / filename).write_bytes(raw)
            print(f"[icons] wrote {filename} ({w}x{h} RGBA, {len(raw)} bytes)")

        ico_bytes = pack_ico(ico_frames)
        (icons_dir / "icon.ico").write_bytes(ico_bytes)
        print(f"[icons] wrote icon.ico ({len(ico_frames)} sizes, {len(ico_bytes)} bytes)")


if __name__ == "__main__":
    main()
