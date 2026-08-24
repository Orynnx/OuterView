from __future__ import annotations

import argparse
import hashlib
import tempfile
import zipfile
from pathlib import Path


DIRECTORY = Path(__file__).resolve().parent
SOURCE = DIRECTORY / "card"
ARCHIVE = DIRECTORY / "hello-card.zip"


def create_archive(destination: Path) -> None:
    inputs = sorted(path for path in SOURCE.rglob("*") if path.is_file())
    if SOURCE / "manifest.xml" not in inputs:
        raise SystemExit("card/manifest.xml is required")
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for path in inputs:
            entry = zipfile.ZipInfo(path.relative_to(SOURCE).as_posix(), (2026, 1, 1, 0, 0, 0))
            entry.create_system = 0
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, path.read_bytes(), compresslevel=9)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the license-clean OuterView Hello Card")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not args.check:
        create_archive(ARCHIVE)
        print(f"Wrote {ARCHIVE.name} {sha256(ARCHIVE)}")
        return
    if not ARCHIVE.is_file():
        raise SystemExit(f"missing {ARCHIVE}")
    with tempfile.TemporaryDirectory() as directory:
        expected = Path(directory) / ARCHIVE.name
        create_archive(expected)
        if expected.read_bytes() != ARCHIVE.read_bytes():
            raise SystemExit(f"{ARCHIVE.name} is stale")
    print(f"OK {ARCHIVE.name} {sha256(ARCHIVE)}")


if __name__ == "__main__":
    main()
