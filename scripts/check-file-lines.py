#!/usr/bin/env python3
"""Check that all source files in the repository stay within a maximum line count."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

SOURCE_EXTENSIONS = frozenset({".java", ".kt", ".swift", ".rs", ".js", ".mjs", ".ts"})
EXCLUDED_DIRS = frozenset(
    {".git", "build", ".gradle", ".build", "node_modules", "target", "out", "dist"}
)


def count_lines(file_path: Path) -> int:
    with file_path.open("r", encoding="utf-8", errors="replace") as handle:
        return sum(1 for _ in handle)


def scan_repository(root: Path) -> list[tuple[int, str]]:
    results: list[tuple[int, str]] = []
    for current_root, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in EXCLUDED_DIRS)
        for filename in sorted(filenames):
            ext = os.path.splitext(filename)[1].lower()
            if ext not in SOURCE_EXTENSIONS:
                continue
            full_path = Path(current_root) / filename
            if full_path.is_symlink():
                continue
            rel_path = full_path.relative_to(root).as_posix()
            results.append((count_lines(full_path), rel_path))
    return results


def parse_args(argv: list[str]) -> argparse.Namespace:
    default_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(
        description="Verify that source files do not exceed the maximum allowed line count."
    )
    parser.add_argument(
        "--max-lines",
        type=int,
        default=800,
        help="Maximum allowed number of lines per source file (default: 800).",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=default_root,
        help="Repository root directory to scan (default: parent of scripts/).",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    root = args.root.resolve()
    max_lines = args.max_lines

    files = scan_repository(root)
    total_files = len(files)
    if total_files == 0:
        print(f"OK: 0 source files scanned under {root} (max limit: {max_lines} lines).")
        return 0

    max_count, max_file = max(files, key=lambda item: (item[0], item[1]))
    violations = sorted(
        (item for item in files if item[0] > max_lines),
        key=lambda item: (-item[0], item[1]),
    )

    if violations:
        print(f"FAIL: {len(violations)} of {total_files} source files exceed {max_lines} lines:")
        for line_count, rel_path in violations:
            print(f"  {line_count:>5} lines  {rel_path}")
        return 1

    print(
        f"OK: {total_files} source files scanned, 0 exceed {max_lines} lines. "
        f"Max: {max_count} lines ({max_file})."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
