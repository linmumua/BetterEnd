"""Safe, deterministic access to the DnT source archive.

The migration must never trust the input ZIP: it refuses path traversal, absolute
paths, Windows drive letters, backslash separators, duplicate (or
case-insensitively colliding) entries and entries whose declared uncompressed
size exceeds the configured caps. It also verifies the whole-file SHA-256 of the
input before doing anything else, and exposes entries in a deterministic
(byte-order sorted) sequence.
"""

from __future__ import annotations

import hashlib
import posixpath
import zipfile
from typing import Dict, List, Optional

DEFAULT_MAX_ENTRIES = 20_000
DEFAULT_MAX_ENTRY_BYTES = 64 * 1024 * 1024
DEFAULT_MAX_TOTAL_BYTES = 512 * 1024 * 1024


class ZipSafetyError(Exception):
    """Raised when an archive is unsafe, malformed or has a wrong digest."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_entry_name(name: str) -> str:
    """Return a safe, POSIX, relative name or raise :class:`ZipSafetyError`."""
    if not name:
        raise ZipSafetyError("empty entry name")
    if "\\" in name:
        raise ZipSafetyError("backslash in entry name: %r" % name)
    if name.startswith("/") or name.startswith("//"):
        raise ZipSafetyError("absolute entry name: %r" % name)
    if len(name) > 1 and name[1] == ":":
        raise ZipSafetyError("drive-letter entry name: %r" % name)
    # deterministic normalisation; reject any escape attempt
    normalized = posixpath.normpath(name)
    if normalized.startswith("../") or normalized == "..":
        raise ZipSafetyError("path traversal in entry name: %r" % name)
    if normalized.startswith("/"):
        raise ZipSafetyError("absolute (normalised) entry name: %r" % name)
    return normalized


class ZipArchive:
    """Read-only view over a validated archive."""

    def __init__(
        self,
        path: str,
        *,
        expected_sha256: Optional[str] = None,
        max_entries: int = DEFAULT_MAX_ENTRIES,
        max_entry_bytes: int = DEFAULT_MAX_ENTRY_BYTES,
        max_total_bytes: int = DEFAULT_MAX_TOTAL_BYTES,
    ) -> None:
        self.path = path
        self.sha256 = sha256_file(path)
        if expected_sha256 and self.sha256.lower() != expected_sha256.lower():
            raise ZipSafetyError(
                "SHA-256 mismatch for %s: expected %s, got %s"
                % (path, expected_sha256, self.sha256)
            )
        self._data: Dict[str, bytes] = {}
        self._names: List[str] = []
        with zipfile.ZipFile(path) as zf:
            infos = zf.infolist()
            if len(infos) > max_entries:
                raise ZipSafetyError("%d entries exceeds cap %d" % (len(infos), max_entries))
            total = 0
            seen: Dict[str, str] = {}
            for info in sorted(infos, key=lambda i: i.filename.encode("utf-8")):
                if info.is_dir():
                    continue
                raw = info.filename
                name = normalize_entry_name(raw)
                if info.file_size > max_entry_bytes:
                    raise ZipSafetyError(
                        "entry %r uncompressed size %d exceeds cap %d"
                        % (name, info.file_size, max_entry_bytes)
                    )
                total += info.file_size
                if total > max_total_bytes:
                    raise ZipSafetyError("total uncompressed size exceeds cap %d" % max_total_bytes)
                folded = name.lower()
                if folded in seen:
                    raise ZipSafetyError(
                        "duplicate/case-colliding entries: %r and %r" % (seen[folded], name)
                    )
                seen[folded] = name
                self._names.append(name)
                self._data[name] = zf.read(info)
        self._names.sort()

    @property
    def names(self) -> List[str]:
        return list(self._names)

    def read(self, name: str) -> bytes:
        return self._data[name]

    def has(self, name: str) -> bool:
        return name in self._data

    def by_prefix(self, prefix: str) -> List[str]:
        return [n for n in self._names if n.startswith(prefix)]

    def manifest(self) -> List[dict]:
        """Deterministic per-entry manifest (name, size, sha256), sorted by name."""
        return [
            {"path": n, "size": len(self._data[n]), "sha256": sha256_bytes(self._data[n])}
            for n in self._names
        ]
