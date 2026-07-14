from __future__ import annotations

import argparse
import hashlib
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
CARD = ROOT / "card"
OUTPUT = ROOT / "dino-run.zip"


def card_files() -> list[Path]:
    files = sorted(path for path in CARD.rglob("*") if path.is_file())
    if not files or not (CARD / "manifest.xml").is_file():
        raise SystemExit("card/manifest.xml is required")
    return files


def build(output: Path) -> None:
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in card_files():
            name = path.relative_to(CARD).as_posix()
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            # ZipInfo defaults create_system to the host OS, which produced
            # different committed archives on Windows and Linux CI runners.
            info.create_system = 0
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes(), compresslevel=9)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the OuterView Dino Run card")
    parser.add_argument("--check", action="store_true", help="verify the committed ZIP")
    args = parser.parse_args()

    if args.check:
        if not OUTPUT.is_file():
            raise SystemExit(f"missing {OUTPUT}")
        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / OUTPUT.name
            build(candidate)
            if candidate.read_bytes() != OUTPUT.read_bytes():
                raise SystemExit(
                    f"{OUTPUT.name} is stale: expected {sha256(candidate)}, got {sha256(OUTPUT)}"
                )
        print(f"OK {OUTPUT.name} {sha256(OUTPUT)}")
        return

    build(OUTPUT)
    print(f"Wrote {OUTPUT} ({sha256(OUTPUT)})")


if __name__ == "__main__":
    main()
