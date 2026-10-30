"""Minimal, dependency-free NBT reader/writer (Java Edition), type-preserving.

The DnT migration inspects ~385 gzipped structure templates and rewrites a
handful (the vault ``key_item`` components). A naive reader that maps every
numeric tag onto Python int/float loses TAG_Byte/TAG_Short/TAG_Int/TAG_Long and
would corrupt the file on write. Hence every tag decodes onto a typed value:

TAG_End None | TAG_Byte Byte | TAG_Short Short | TAG_Int int | TAG_Long Long
TAG_Float Float | TAG_Double float | TAG_Byte_Array ByteArray | TAG_String str
TAG_List ListTag | TAG_Compound dict | TAG_Int_Array IntArray | TAG_Long_Array LongArray

Strings use Java modified UTF-8 (DataOutput.writeUTF).
"""

from __future__ import annotations

import gzip
import struct
from typing import Any, Dict, Tuple

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

_TAG_NAMES = {
    TAG_END: "End", TAG_BYTE: "Byte", TAG_SHORT: "Short", TAG_INT: "Int",
    TAG_LONG: "Long", TAG_FLOAT: "Float", TAG_DOUBLE: "Double",
    TAG_BYTE_ARRAY: "ByteArray", TAG_STRING: "String", TAG_LIST: "List",
    TAG_COMPOUND: "Compound", TAG_INT_ARRAY: "IntArray", TAG_LONG_ARRAY: "LongArray",
}


class NbtError(ValueError):
    """Raised on malformed or unsupported NBT payloads."""


class Byte(int):
    pass


class Short(int):
    pass


class Long(int):
    pass


class Float(float):
    pass


class ByteArray(bytes):
    pass


class IntArray(list):
    pass


class LongArray(list):
    pass


class ListTag(list):
    """A TAG_List that remembers its element tag id (needed for empty lists)."""

    def __init__(self, iterable=(), elem_id: int = TAG_END) -> None:
        super().__init__(iterable)
        self.elem_id = elem_id


def _decode_modified_utf8(data: bytes) -> str:
    out = []
    i = 0
    n = len(data)
    while i < n:
        b = data[i]
        if b < 0x80:
            out.append(chr(b)); i += 1
        elif (b & 0xE0) == 0xC0:
            if i + 1 >= n:
                raise NbtError("truncated modified-utf8 2-byte sequence")
            out.append(chr(((b & 0x1F) << 6) | (data[i + 1] & 0x3F))); i += 2
        elif (b & 0xF0) == 0xE0:
            if i + 2 >= n:
                raise NbtError("truncated modified-utf8 3-byte sequence")
            out.append(chr(((b & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)))
            i += 3
        else:
            raise NbtError("invalid modified-utf8 leading byte 0x%02x" % b)
    return "".join(out)


def _encode_modified_utf8(text: str) -> bytes:
    out = bytearray()
    for ch in text:
        cp = ord(ch)
        if 0x01 <= cp <= 0x7F:
            out.append(cp)
        elif cp <= 0x7FF:
            out.append(0xC0 | (cp >> 6)); out.append(0x80 | (cp & 0x3F))
        elif cp <= 0xFFFF:
            out.append(0xE0 | (cp >> 12)); out.append(0x80 | ((cp >> 6) & 0x3F))
            out.append(0x80 | (cp & 0x3F))
        else:
            raise NbtError("code point U+%04X not representable in modified utf-8" % cp)
    return bytes(out)


class _Reader:
    __slots__ = ("data", "pos")

    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def _take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise NbtError("unexpected end of NBT payload")
        chunk = self.data[self.pos : self.pos + n]
        self.pos += n
        return chunk

    def _num(self, fmt: str, width: int) -> Any:
        return struct.unpack(fmt, self._take(width))[0]

    def string(self) -> str:
        return _decode_modified_utf8(self._take(self._num(">H", 2)))

    def payload(self, tag_id: int) -> Any:
        if tag_id == TAG_BYTE:
            return Byte(struct.unpack(">b", self._take(1))[0])
        if tag_id == TAG_SHORT:
            return Short(self._num(">h", 2))
        if tag_id == TAG_INT:
            return self._num(">i", 4)
        if tag_id == TAG_LONG:
            return Long(self._num(">q", 8))
        if tag_id == TAG_FLOAT:
            return Float(struct.unpack(">f", self._take(4))[0])
        if tag_id == TAG_DOUBLE:
            return self._num(">d", 8)
        if tag_id == TAG_BYTE_ARRAY:
            return ByteArray(self._take(self._num(">i", 4)))
        if tag_id == TAG_STRING:
            return self.string()
        if tag_id == TAG_LIST:
            elem = self._take(1)[0]
            size = self._num(">i", 4)
            return ListTag((self.payload(elem) for _ in range(size)), elem)
        if tag_id == TAG_COMPOUND:
            result = {}
            while True:
                child = self._take(1)[0]
                if child == TAG_END:
                    return result
                name = self.string()
                result[name] = self.payload(child)
        if tag_id == TAG_INT_ARRAY:
            return IntArray(self._num(">i", 4) for _ in range(self._num(">i", 4)))
        if tag_id == TAG_LONG_ARRAY:
            return LongArray(self._num(">q", 8) for _ in range(self._num(">i", 4)))
        raise NbtError("unknown tag id %d" % tag_id)


def load_bytes(data: bytes) -> Tuple[str, Dict[str, Any]]:
    """Parse an (optionally gzip-compressed) NBT document -> (root_name, root)."""
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    reader = _Reader(data)
    tag_id = reader._take(1)[0]
    if tag_id != TAG_COMPOUND:
        raise NbtError("root tag is not a compound (got %d)" % tag_id)
    return reader.string(), reader.payload(TAG_COMPOUND)


def load_file(path: str) -> Tuple[str, Dict[str, Any]]:
    with open(path, "rb") as fh:
        return load_bytes(fh.read())


def _tag_id(value: Any) -> int:
    if isinstance(value, bool):
        raise NbtError("bool is not an NBT type")
    if isinstance(value, Byte):
        return TAG_BYTE
    if isinstance(value, Short):
        return TAG_SHORT
    if isinstance(value, Long):
        return TAG_LONG
    if isinstance(value, int):
        return TAG_INT
    if isinstance(value, Float):
        return TAG_FLOAT
    if isinstance(value, float):
        return TAG_DOUBLE
    if isinstance(value, ByteArray):
        return TAG_BYTE_ARRAY
    if isinstance(value, str):
        return TAG_STRING
    if isinstance(value, IntArray):
        return TAG_INT_ARRAY
    if isinstance(value, LongArray):
        return TAG_LONG_ARRAY
    if isinstance(value, dict):
        return TAG_COMPOUND
    if isinstance(value, (ListTag, list, tuple)):
        return TAG_LIST
    raise NbtError("cannot serialise %r as NBT" % type(value))


def _write_payload(out: bytearray, value: Any) -> None:
    tid = _tag_id(value)
    if tid == TAG_BYTE:
        out += struct.pack(">b", int(value))
    elif tid == TAG_SHORT:
        out += struct.pack(">h", int(value))
    elif tid == TAG_INT:
        out += struct.pack(">i", int(value))
    elif tid == TAG_LONG:
        out += struct.pack(">q", int(value))
    elif tid == TAG_FLOAT:
        out += struct.pack(">f", float(value))
    elif tid == TAG_DOUBLE:
        out += struct.pack(">d", float(value))
    elif tid == TAG_BYTE_ARRAY:
        out += struct.pack(">i", len(value)); out += bytes(value)
    elif tid == TAG_STRING:
        raw = _encode_modified_utf8(value)
        out += struct.pack(">H", len(raw)); out += raw
    elif tid == TAG_LIST:
        elem = value.elem_id if isinstance(value, ListTag) else (_tag_id(value[0]) if value else TAG_END)
        out.append(elem); out += struct.pack(">i", len(value))
        for item in value:
            if _tag_id(item) != elem:
                raise NbtError("heterogeneous NBT list: %d vs %d" % (elem, _tag_id(item)))
            _write_payload(out, item)
    elif tid == TAG_COMPOUND:
        for key, child in value.items():
            out.append(_tag_id(child))
            raw = _encode_modified_utf8(key)
            out += struct.pack(">H", len(raw)); out += raw
            _write_payload(out, child)
        out.append(TAG_END)
    elif tid == TAG_INT_ARRAY:
        out += struct.pack(">i", len(value))
        for item in value:
            out += struct.pack(">i", int(item))
    elif tid == TAG_LONG_ARRAY:
        out += struct.pack(">i", len(value))
        for item in value:
            out += struct.pack(">q", int(item))
    else:
        raise NbtError("cannot serialise tag id %d" % tid)


def dump_bytes(name: str, root: Dict[str, Any], compress: bool = True) -> bytes:
    out = bytearray()
    out.append(TAG_COMPOUND)
    raw = _encode_modified_utf8(name)
    out += struct.pack(">H", len(raw)); out += raw
    _write_payload(out, root)
    payload = bytes(out)
    return gzip.compress(payload, mtime=0) if compress else payload


def tag_name(tag_id: int) -> str:
    return _TAG_NAMES.get(tag_id, "Unknown(%d)" % tag_id)
