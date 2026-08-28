#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

FORBIDDEN_DIR_NAMES = {".gradle", "build", "captures", "__pycache__", ".idea"}
FORBIDDEN_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".apk", ".aab", ".pyc", ".log"}
FORBIDDEN_BASENAMES = {"keystore.properties", "local.properties", ".env", "last-crash.txt"}


def violations(root: Path) -> list[str]:
    bad: list[str] = []
    for p in root.rglob("*"):
        rel = p.relative_to(root)
        if any(part in FORBIDDEN_DIR_NAMES for part in rel.parts):
            bad.append(str(rel))
            continue
        if p.is_file() and (p.name in FORBIDDEN_BASENAMES or p.suffix.lower() in FORBIDDEN_SUFFIXES):
            bad.append(str(rel))
    return sorted(set(bad))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=".")
    args = ap.parse_args()
    bad = violations(Path(args.root).resolve())
    if bad:
        print("android release guard: forbidden artifacts detected")
        for item in bad[:200]:
            print(f" - {item}")
        return 1
    print("android release guard: clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
