#!/usr/bin/env python3
"""Fail when the current OuterView tree shares substantial source with REAREye history."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
from collections import defaultdict
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path


SOURCE_SUFFIXES = {
    ".aidl", ".c", ".cc", ".cpp", ".gradle", ".h", ".java", ".js", ".json",
    ".kt", ".kts", ".properties", ".ps1", ".py", ".sh", ".toml", ".xml", ".yml", ".yaml",
}
FUNCTION_SUFFIXES = {".java", ".kt", ".kts"}
SKIP_PARTS = {".git", ".gradle", "build", "generated"}
WRAPPER_ALLOWLIST = {
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
}
STANDARD_LICENSE_HASHES = {
    # GNU GPL v3.0 is a verbatim standard legal text, not project implementation.
    "LICENSE": "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
}
FUNCTION_MIN_TOKENS = 40
FUNCTION_SIMILARITY = 0.90
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
KOTLIN_FUNCTION_RE = re.compile(
    r"\bfun\s+(?:<[^>{}()]*>\s*)?(?:[A-Za-z_$][\w$]*\.)*([A-Za-z_$][\w$]*)"
    r"\s*(?:<[^>{}()]*>)?\s*\(",
)
JAVA_FUNCTION_RE = re.compile(
    r"(?m)^[ \t]*(?:(?:public|private|protected|static|final|abstract|synchronized|native|"
    r"default|strictfp)\s+)*(?:[A-Za-z_$][\w$<>, ?\[\].]*\s+)([A-Za-z_$][\w$]*)\s*\([^;{}]*\)"
    r"\s*(?:throws\s+[^{}]+)?\{",
)


@dataclass(frozen=True)
class FileBlob:
    path: str
    data: bytes


@dataclass(frozen=True)
class Source:
    identity: str
    text: str


@dataclass(frozen=True)
class Function:
    source: str
    line: int
    name: str
    text: str

    @property
    def identity(self) -> str:
        return f"{self.source}:{self.line}:{self.name}"


def run(*args: str, cwd: Path) -> str:
    return subprocess.check_output(args, cwd=cwd, text=True, encoding="utf-8", errors="replace")


def included(path: Path, root: Path) -> bool:
    return path.is_file() and not any(part in SKIP_PARTS for part in path.relative_to(root).parts)


def current_files(root: Path) -> list[FileBlob]:
    return [
        FileBlob(path.relative_to(root).as_posix(), path.read_bytes())
        for path in root.rglob("*")
        if included(path, root)
    ]


def historical_files(repo: Path, suffixes: set[str] | None = None) -> list[FileBlob]:
    objects: dict[str, str] = {}
    for line in run("git", "rev-list", "--objects", "--all", cwd=repo).splitlines():
        sha, _, name = line.partition(" ")
        if not name or (suffixes is not None and Path(name).suffix.lower() not in suffixes):
            continue
        objects.setdefault(sha, name)

    process = subprocess.run(
        ["git", "cat-file", "--batch"], cwd=repo,
        input="".join(f"{sha}\n" for sha in objects).encode(), stdout=subprocess.PIPE, check=True,
    )
    output = memoryview(process.stdout)
    position = 0
    files: list[FileBlob] = []
    for sha, name in objects.items():
        newline = process.stdout.find(b"\n", position)
        if newline < 0:
            raise RuntimeError(f"missing git cat-file header for {sha}")
        header = bytes(output[position:newline]).decode("ascii", errors="replace").split()
        position = newline + 1
        if len(header) != 3 or header[1] != "blob":
            if len(header) == 3:
                position += int(header[2]) + 1
            continue
        size = int(header[2])
        data = bytes(output[position:position + size])
        position += size + 1
        files.append(FileBlob(f"{name}@{sha[:12]}", data))
    return files


def sources(files: list[FileBlob]) -> list[Source]:
    return [
        Source(file.path, file.data.decode("utf-8", errors="replace"))
        for file in files
        if Path(file.path.rsplit("@", 1)[0]).suffix.lower() in SOURCE_SUFFIXES
    ]


def tokens(text: str, structural: bool) -> list[str]:
    body = "\n".join(
        line for line in COMMENT_RE.sub(" ", text).splitlines()
        if not re.match(r"^\s*(?:package|import)\b", line)
    )
    result = []
    for token in TOKEN_RE.findall(body):
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


def build_index(sources_to_index: list[Source], transform, window: int) -> dict[bytes, tuple[str, int]]:
    result: dict[bytes, tuple[str, int]] = {}
    for source in sources_to_index:
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


def exact_matches(outer: list[FileBlob], rear: list[FileBlob]) -> tuple[list[str], int]:
    rear_hashes: dict[bytes, FileBlob] = {}
    for file in rear:
        rear_hashes.setdefault(hashlib.sha256(file.data).digest(), file)
    findings = []
    allowed = 0
    for file in outer:
        file_hash = hashlib.sha256(file.data)
        hit = rear_hashes.get(file_hash.digest())
        if hit is None:
            continue
        rear_path = hit.path.rsplit("@", 1)[0]
        if file.path in WRAPPER_ALLOWLIST and rear_path == file.path:
            allowed += 1
            continue
        if STANDARD_LICENSE_HASHES.get(file.path) == file_hash.hexdigest() and rear_path == file.path:
            allowed += 1
            continue
        findings.append(f"exact-file: {file.path} <-> {hit.path}")
    return findings, allowed


def preserve_comment_offsets(match: re.Match[str]) -> str:
    return re.sub(r"[^\r\n]", " ", match.group())


def matching_brace(text: str, opening: int) -> int | None:
    depth = 0
    for match in TOKEN_RE.finditer(text, opening):
        token = match.group()
        if token == "{":
            depth += 1
        elif token == "}":
            depth -= 1
            if depth == 0:
                return match.end()
    return None


def function_body_start(text: str, declaration_end: int) -> int | None:
    for match in TOKEN_RE.finditer(text, declaration_end):
        if match.group() == "{":
            return match.start()
        if match.group() == ";":
            return None
    return None


def functions_in(source: Source) -> list[Function]:
    if Path(source.identity.rsplit("@", 1)[0]).suffix.lower() not in FUNCTION_SUFFIXES:
        return []
    cleaned = COMMENT_RE.sub(preserve_comment_offsets, source.text)
    found: list[tuple[int, Function]] = []
    for pattern in (KOTLIN_FUNCTION_RE, JAVA_FUNCTION_RE):
        for declaration in pattern.finditer(cleaned):
            opening = function_body_start(cleaned, declaration.end())
            if opening is None:
                continue
            end = matching_brace(cleaned, opening)
            if end is None:
                continue
            line = source.text.count("\n", 0, declaration.start()) + 1
            found.append((declaration.start(), Function(
                source.identity,
                line,
                declaration.group(1),
                source.text[declaration.start():end],
            )))
    result: list[Function] = []
    previous_start = -1
    for start, function in sorted(found, key=lambda item: item[0]):
        if start != previous_start:
            result.append(function)
            previous_start = start
    return result


def eligible_functions(sources_to_scan: list[Source]) -> list[tuple[Function, list[str], list[str]]]:
    unique: dict[bytes, tuple[Function, list[str], list[str]]] = {}
    for source in sources_to_scan:
        for function in functions_in(source):
            structural_tokens = tokens(function.text, structural=True)
            if len(structural_tokens) < FUNCTION_MIN_TOKENS:
                continue
            raw_tokens = tokens(function.text, structural=False)
            key = hashlib.sha256("\x1f".join(raw_tokens).encode()).digest()
            unique.setdefault(key, (function, raw_tokens, structural_tokens))
    return list(unique.values())


def anchor_index(functions: list[tuple[Function, list[str], list[str]]], structural: bool, window: int, maximum_hits: int) -> dict[bytes, set[int]]:
    all_hits: dict[bytes, set[int]] = defaultdict(set)
    for number, (_, raw_tokens, structural_tokens) in enumerate(functions):
        values = structural_tokens if structural else raw_tokens
        for offset in range(max(0, len(values) - window + 1)):
            all_hits[digest_window(values, offset, window)].add(number)
    return {key: value for key, value in all_hits.items() if len(value) <= maximum_hits}


def function_matches(outer: list[Source], rear: list[Source]) -> list[str]:
    outer_functions = eligible_functions(outer)
    rear_functions = eligible_functions(rear)
    raw_anchors = anchor_index(rear_functions, structural=False, window=10, maximum_hits=8)
    structural_anchors = anchor_index(rear_functions, structural=True, window=32, maximum_hits=1)
    findings = []
    for outer_function, outer_raw_tokens, outer_structural_tokens in outer_functions:
        candidates: set[int] = set()
        for structural, window, index in (
            (False, 10, raw_anchors),
            (True, 32, structural_anchors),
        ):
            values = outer_structural_tokens if structural else outer_raw_tokens
            for offset in range(max(0, len(values) - window + 1)):
                candidates.update(index.get(digest_window(values, offset, window), set()))
        for candidate in candidates:
            rear_function, _, rear_structural_tokens = rear_functions[candidate]
            if min(len(outer_structural_tokens), len(rear_structural_tokens)) / max(
                len(outer_structural_tokens), len(rear_structural_tokens)
            ) < FUNCTION_SIMILARITY / (2 - FUNCTION_SIMILARITY):
                continue
            ratio = SequenceMatcher(None, outer_structural_tokens, rear_structural_tokens, autojunk=False).ratio()
            if ratio >= FUNCTION_SIMILARITY:
                findings.append(
                    f"function-{FUNCTION_SIMILARITY:.0%}: {outer_function.identity} <-> "
                    f"{rear_function.identity} (structural-token similarity {ratio:.2%})"
                )
    return sorted(set(findings))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--outerview", type=Path, default=Path.cwd())
    parser.add_argument("--reareye", type=Path, required=True)
    args = parser.parse_args()
    outer_files = current_files(args.outerview.resolve())
    rear_files = historical_files(args.reareye.resolve())
    outer = sources(outer_files)
    rear = sources(rear_files)
    findings, allowed_exact_files = exact_matches(outer_files, rear_files)
    findings += scan("lines-20", outer, rear, lines, 20)
    findings += scan("tokens-120", outer, rear, lambda value: tokens(value, False), 120)
    findings += scan("structure-300", outer, rear, lambda value: tokens(value, True), 300)
    findings += function_matches(outer, rear)
    print(
        f"OuterView files: {len(outer_files)}; sources: {len(outer)}; "
        f"REAREye historical blobs: {len(rear_files)}; sources: {len(rear)}"
    )
    print(f"Allowed identical standard files: {allowed_exact_files}")
    if findings:
        print("Substantial similarities found:")
        print("\n".join(sorted(findings)))
        return 1
    print("No disallowed exact file, 20-line, 120-token, 300-token structural, or function-level matches found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
