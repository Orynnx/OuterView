#!/usr/bin/env python3
"""Fail when the current OuterView tree shares substantial source with REAREye history."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


SOURCE_SUFFIXES = {".aidl", ".gradle", ".java", ".kt", ".kts", ".xml"}
SKIP_PARTS = {".git", ".gradle", "build", "generated"}
KEYWORDS = {
    "as", "break", "catch", "class", "const", "continue", "data", "do", "else",
    "enum", "false", "finally", "for", "fun", "if", "import", "in", "interface",
    "internal", "is", "new", "null", "object", "override", "package", "private",
    "protected", "public", "return", "sealed", "static", "super", "this", "throw",
    "true", "try", "typealias", "val", "var", "void", "when", "while",
}
TOKEN_RE = re.compile(
    r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?|[^\s]',
    re.DOTALL,
)
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\r\n]*", re.DOTALL)


@dataclass(frozen=True)
class Source:
    identity: str
    text: str


def run(*args: str, cwd: Path) -> str:
    return subprocess.check_output(args, cwd=cwd, text=True, encoding="utf-8", errors="replace")


def current_sources(root: Path) -> list[Source]:
    result = []
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
            continue
        if any(part in SKIP_PARTS for part in path.relative_to(root).parts):
            continue
        result.append(Source(path.relative_to(root).as_posix(), path.read_text("utf-8", errors="replace")))
    return result


def historical_sources(repo: Path) -> list[Source]:
    objects: dict[str, str] = {}
    for line in run("git", "rev-list", "--objects", "--all", cwd=repo).splitlines():
        sha, _, name = line.partition(" ")
        if name and Path(name).suffix.lower() in SOURCE_SUFFIXES:
            objects.setdefault(sha, name)
    process = subprocess.Popen(
        ["git", "cat-file", "--batch"], cwd=repo, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, text=False,
    )
    assert process.stdin is not None and process.stdout is not None
    sources: list[Source] = []
    for sha, name in objects.items():
        process.stdin.write((sha + "\n").encode())
        process.stdin.flush()
        header = process.stdout.readline().decode("ascii", errors="replace").strip().split()
        if len(header) != 3 or header[1] != "blob":
            continue
        data = process.stdout.read(int(header[2]))
        process.stdout.read(1)
        sources.append(Source(f"{name}@{sha[:12]}", data.decode("utf-8", errors="replace")))
    process.stdin.close()
    process.wait()
    return sources


def tokens(text: str, structural: bool) -> list[str]:
    body = "\n".join(
        line for line in COMMENT_RE.sub(" ", text).splitlines()
        if not re.match(r"^\s*(?:package|import)\b", line)
    )
    raw = TOKEN_RE.findall(body)
    result = []
    for token in raw:
        if token.startswith(('"', "'")):
            result.append("STR")
        elif token[0].isdigit():
            result.append("NUM")
        elif structural and re.fullmatch(r"[A-Za-z_$][\w$]*", token) and token not in KEYWORDS:
            result.append("ID")
        else:
            result.append(token)
    return result


def lines(text: str) -> list[str]:
    cleaned = COMMENT_RE.sub(" ", text)
    return [
        re.sub(r"\s+", "", line) for line in cleaned.splitlines()
        if re.sub(r"\s+", "", line) and not re.match(r"^\s*(?:package|import)\b", line)
    ]


def digest_window(values: list[str], offset: int, size: int) -> bytes:
    return hashlib.blake2b("\x1f".join(values[offset:offset + size]).encode(), digest_size=16).digest()


def build_index(sources: list[Source], transform, window: int) -> dict[bytes, tuple[str, int]]:
    result: dict[bytes, tuple[str, int]] = {}
    for source in sources:
        values = transform(source.text)
        for offset in range(max(0, len(values) - window + 1)):
            result.setdefault(digest_window(values, offset, window), (source.identity, offset))
    return result


def scan(label: str, outer: list[Source], rear: list[Source], transform, window: int) -> list[str]:
    index = build_index(rear, transform, window)
    matches = []
    seen = set()
    for source in outer:
        values = transform(source.text)
        for offset in range(max(0, len(values) - window + 1)):
            hit = index.get(digest_window(values, offset, window))
            if hit is None:
                continue
            key = (source.identity, hit[0], label)
            if key not in seen:
                seen.add(key)
                matches.append(f"{label}: {source.identity}:{offset} <-> {hit[0]}:{hit[1]}")
    return matches


def exact_matches(outer: list[Source], rear: list[Source]) -> list[str]:
    rear_hashes = {}
    for source in rear:
        normalized = source.text.replace("\r\n", "\n")
        if len(normalized) >= 200:
            rear_hashes.setdefault(hashlib.sha256(normalized.encode()).digest(), source.identity)
    result = []
    for source in outer:
        normalized = source.text.replace("\r\n", "\n")
        if len(normalized) < 200:
            continue
        hit = rear_hashes.get(hashlib.sha256(normalized.encode()).digest())
        if hit:
            result.append(f"exact: {source.identity} <-> {hit}")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--outerview", type=Path, default=Path.cwd())
    parser.add_argument("--reareye", type=Path, required=True)
    args = parser.parse_args()
    outer = current_sources(args.outerview.resolve())
    rear = historical_sources(args.reareye.resolve())
    findings = exact_matches(outer, rear)
    findings += scan("lines-20", outer, rear, lines, 20)
    findings += scan("tokens-120", outer, rear, lambda value: tokens(value, False), 120)
    findings += scan("structure-300", outer, rear, lambda value: tokens(value, True), 300)
    print(f"OuterView sources: {len(outer)}; REAREye historical blobs: {len(rear)}")
    if findings:
        print("Substantial similarities found:")
        print("\n".join(sorted(findings)))
        return 1
    print("No exact file, 20-line, 120-token, or 300-token structural matches found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
