#!/usr/bin/env python3
"""Repack "Echoes of the End (Structures)" 12.00.14 into BetterEnd's third bundled datapack.

Usage:
    python scripts/prepare_echoes.py [--input plugin/echoes_of_the_end_structures_-12.00.14.jar] \
                                     [--output plugin/src/main/resources/datapacks/echoes-end-structures]

Unlike the DnT pack (built into ``plugin/build/generated``) this one is emitted straight into
``plugin/src/main/resources``: the content is nine NBT files plus a handful of JSON documents, the
source jar is committed beside it, and nothing is version-expanded at build time. Committing the
result keeps a source-only checkout complete -- ``:common:test`` reads the pack without needing a
build step, and ``verifyJar`` sees it through the normal resources copy.

What this script owns
---------------------
1. ``data/<ns>/structure/*.nbt`` came from the jar **verbatim**. The payload is never re-serialised:
   the NBT goes through this script as bytes, because a re-encode through our own writer risks
   changing tag widths or ordering that the server's structure DataFixer reads. (The jar also
   carries a duplicate ``data/<ns>/structures/`` tree; its decompressed payload is byte-identical to
   ``structure/``'s for all nine -- only the gzip mtime header differs -- and 26.3 reads the
   singular directory, which is the one the 26.3-targeting DnT pack uses too.)
2. ``worldgen/structure/*.json`` -- only the ``biomes`` field is rewritten, to ``#minecraft:is_end``.
   The source hardcodes ``minecraft:end_barrens``/``end_highlands``/``end_midlands``, which never
   appear in this world: BetterEnd's generator paints the 27 ``betterend:*`` biomes, and this
   plugin's own biome pack adds exactly those 27 to the vanilla ``#minecraft:is_end`` tag (see
   ``plugin/src/main/resources/datapack/data/minecraft/tags/worldgen/biome/is_end.json`` and
   ``EndBiomeTagTest``). Referencing that tag rather than a private copy of the list is deliberate:
   it is the same tag two of the nine already ship, it needs no third-party pack, and the tag cannot
   drift from the shipped biome set without ``everyShippedBiomeIsInTheEndTag`` failing.
   Everything else -- ``terrain_adaptation``, ``project_start_to_heightmap``, ``start_height``,
   ``step``, ``spawn_overrides``, ``use_expansion_hack``, ``max_distance_from_center`` -- is the
   author's and is preserved.
3. ``worldgen/structure_set/*.json`` -- the nine placements are unified to one spacing/separation so
   all nine are equally likely (the source ranged 120..220 / 2..29, i.e. wildly different odds).
   Each structure keeps its own salt; salts must differ or nine identical grids would drop their
   structures into the same cells.
4. ``worldgen/template_pool/*.json`` -- taken from the source and run through one migration rule,
   ``migrate.rule_template_pool_block_states``. All nine carry
   ``block_ignore.blocks: [{"Name": "minecraft:structure_block"}]``, the pre-1.20 palette spelling,
   while 26.3's ``BlockState.CODEC`` accepts only a plain string id or
   ``{"id": ..., "properties": {...}}``. Left as-is the whole ``minecraft:worldgen/template_pool``
   registry fails to parse (``Unbound values in registry``) and startup aborts -- the 0.1.6
   incident. Everything else in the pool
   (``name``/``fallback``/``weight``/``element_type``/``location``/``projection``) is the author's
   and is preserved.
5. ``pack.mcmeta`` -- the 26.3 scheme (``min_format``/``max_format``, data format 121), matching the
   DnT pack. The source's ``107`` is a *lower* data format, but that does NOT mean the content is
   26.3-ready: the block-state spelling inside ``block_ignore`` did change (see 4 and
   ``migrate.legacy_block_state_to_26_3``), which is exactly the assumption that shipped broken in
   0.1.6. The structure NBT payloads themselves are unchanged, which is why the DnT pack's
   DataVersion 3819 NBT still loads.

The DnT castle's density is **not** touched here. It is a build-time migration rule in the DnT
pipeline instead (``migrate.rule_end_castle_placement``, called from ``prepare_dnt.py``), so it lands
in that pack's own output and shows up as a line in its migration report. Nothing in this pack
overrides another pack's registry entries.

Two things this script deliberately does *not* touch: the three empty
``data/minecraft/tags/worldgen/biome/is_{beach,jungle,taiga}.json`` files the mod jar carries
(MCreator leftovers with empty ``values``; they would be no-ops, and shipping them invites the
misreading that the pack edits vanilla tags), and ``forge:spawn_type`` inside the NBT (a Forge-only
mob-spawner key whose values are plain enum names; Paper ignores unknown block-entity keys).

Every check is a real one: the script re-parses what it wrote and exits non-zero on any problem.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import sys
import zipfile
from typing import Any, Dict, List, Sequence, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Read-only use of the DnT migration library's NBT reader: the authoritative way to check block ids
# and loot references without re-serialising anything.
from dntlib import migrate, nbt  # noqa: E402

# The pinned source. Bumping the source jar must be a deliberate edit here, not a silent rebuild.
SOURCE_SHA256 = "d0b903cb4c18c8b91dae96bb9590564df4890fbdc94262ec54c3fcc2ea37d394"
DEFAULT_INPUT = "plugin/echoes_of_the_end_structures_-12.00.14.jar"
DEFAULT_OUTPUT = "plugin/src/main/resources/datapacks/echoes-end-structures"

NS = "echoes_of_the_end__structures_"
PACK_ID = "echoes-end-structures"

# --- placement (see the docstring: one spacing for all nine = equal odds) -----------------------
#
# random_spread reserves one candidate per spacing x spacing chunk cell (so spacing is the only
# density knob -- separation does not change how many structures exist) and puts that candidate at a
# random offset inside [0, spacing - separation). The source's nine spacings average 181.1 chunks.
# 160 is "slightly denser": -12% linear, +28% areal, i.e. the nearest structure moves from ~965 to
# ~855 blocks away. separation 16 is 10% of spacing, at or below the source's tightest ratio
# (20/190 = 10.5%) and well under its worst (29/220 = 13.2%), so the 144-chunk offset window stays
# wide enough for placements to look scattered instead of grid-locked. separation must stay strictly
# below spacing or the window is empty and the placement degenerates.
SPACING = 160
SEPARATION = 16
BIOMES = "#minecraft:is_end"

# --- the nine structures the source ships ------------------------------------------------------
#
# Pinned so a source change is a loud failure here rather than a silently different pack. The
# warship is the one mismatched pair: its structure/template_pool files are ``end_warship`` while the
# NBT is ``end_warship1`` (the pool's ``location`` says so); the closure check below proves it.
STRUCTURES: Sequence[str] = (
    "crying_obsidian_tower",
    "end_camp",
    "end_dungeon_tower",
    "end_enchanted_temple",
    "end_pillar",
    "end_shulker_tower",
    "end_temple",
    "end_warship",
    "end_well",
)

# The MCreator leftovers that must never be shipped; asserted absent from the output below.
REJECTED_TAGS = ("is_beach", "is_jungle", "is_taiga")

# Written as a literal, not through dumps(): the 26.3 format pair reads as "[121, 0]" inline in the
# DnT pack, and an indent-based dump would break the pair across four lines for no benefit.
PACK_META = (
    b'{\n'
    b'  "pack": {\n'
    b'    "description": "Echoes of the End (Structures) 12.00.14, repacked for BetterEnd",\n'
    b'    "min_format": [121, 0],\n'
    b'    "max_format": [121, 0]\n'
    b'  }\n'
    b'}\n'
)


def dumps(obj: Any) -> bytes:
    """Deterministic UTF-8 JSON (2-space indent, trailing newline, source key order preserved)."""
    return (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def write_file(path: str, data: bytes) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(data)


def unpack(root: Any) -> Dict[str, Any]:
    """The root compound of a parsed structure NBT (the reader returns (name, compound))."""
    return root[1] if isinstance(root, tuple) else root


def build(archive: zipfile.ZipFile, out_root: str,
          report=None) -> Tuple[List[str], List[str]]:
    """Write the pack; return (written paths relative to out_root, problems)."""
    written: List[str] = []
    problems: List[str] = []
    report = migrate.Report() if report is None else report

    def emit(relative: str, data: bytes) -> None:
        if relative in written:
            problems.append("重复输出：" + relative)
            return
        write_file(os.path.join(out_root, relative), data)
        written.append(relative)

    # 1. structure NBT, verbatim from the singular directory 26.3 reads.
    present = set()
    for name in archive.namelist():
        prefix = "data/" + NS + "/structure/"
        if name.startswith(prefix) and name.endswith(".nbt"):
            present.add(name[len(prefix):-len(".nbt")])
    missing = sorted(set(STRUCTURES) - present - {"end_warship"}) + (
        ["end_warship1"] if "end_warship1" not in present else [])
    if missing:
        problems.append("源 jar 缺少这些结构 NBT：" + "、".join(missing))
    unexpected = sorted(present - set(STRUCTURES) - {"end_warship1"})
    if unexpected:
        problems.append("源 jar 含有未登记的结构 NBT：" + "、".join(unexpected))

    for name in sorted(present):
        emit("data/%s/structure/%s.nbt" % (NS, name),
             archive.read("data/%s/structure/%s.nbt" % (NS, name)))

    # 2. structure JSON: rewrite only `biomes`.
    for name in STRUCTURES:
        source = "data/%s/worldgen/structure/%s.json" % (NS, name)
        if source not in archive.namelist():
            problems.append("源 jar 缺少 " + source)
            continue
        document = json.loads(archive.read(source))
        document["biomes"] = BIOMES
        emit("data/%s/worldgen/structure/%s.json" % (NS, name), dumps(document))

    # 3. structure_set JSON: one placement for all nine, salts kept.
    salts: List[int] = []
    for name in STRUCTURES:
        source = "data/%s/worldgen/structure_set/%s.json" % (NS, name)
        if source not in archive.namelist():
            problems.append("源 jar 缺少 " + source)
            continue
        document = json.loads(archive.read(source))
        placement = document.get("placement", {})
        if placement.get("type") != "minecraft:random_spread":
            problems.append("%s 的放置类型不是 random_spread：%s" % (source, placement.get("type")))
        placement["spacing"] = SPACING
        placement["separation"] = SEPARATION
        document["placement"] = placement
        salt = placement.get("salt")
        if not isinstance(salt, int):
            problems.append("%s 缺少整数 salt" % source)
        else:
            salts.append(salt)
        emit("data/%s/worldgen/structure_set/%s.json" % (NS, name), dumps(document))
    if len(set(salts)) != len(salts):
        problems.append("salt 有重复：%s" % sorted(salts))

    # 4. template_pool JSON, migrated to the 26.3 block-state spelling.
    for name in STRUCTURES:
        source = "data/%s/worldgen/template_pool/%s.json" % (NS, name)
        if source not in archive.namelist():
            problems.append("源 jar 缺少 " + source)
            continue
        document = json.loads(archive.read(source))
        migrate.rule_template_pool_block_states(document, source, report)
        emit("data/%s/worldgen/template_pool/%s.json" % (NS, name), dumps(document))

    # 5. pack.mcmeta, in the 26.3 scheme (same shape as the DnT pack).
    emit("pack.mcmeta", PACK_META)

    return written, problems


def verify(archive: zipfile.ZipFile, out_root: str, written: List[str]) -> List[str]:
    """Re-read what was written; every check is on the emitted bytes, not on the intent."""
    problems: List[str] = []
    shipped = set(written)

    # Reference closure: every template_pool location resolves to a shipped NBT.
    for name in STRUCTURES:
        path = os.path.join(out_root, "data/%s/worldgen/template_pool/%s.json" % (NS, name))
        if not os.path.isfile(path):
            continue
        pool = json.load(open(path, encoding="utf-8"))
        for element in pool.get("elements", []):
            body = element.get("element", {})
            location = body.get("location")
            if not isinstance(location, str):
                continue  # not a single_pool_element
            namespace, _, identifier = location.partition(":")
            if namespace != NS:
                problems.append("%s 引用了外部模板 %s" % (name, location))
                continue
            target = "data/%s/structure/%s.nbt" % (NS, identifier)
            if target not in shipped:
                problems.append("%s 引用的模板不存在：%s" % (name, target))
            if body.get("projection") != "rigid":
                problems.append("%s 的 %s 投影不是 rigid：%s" % (name, identifier, body.get("projection")))
            processors = body.get("processors", {}).get("processors", [])
            ignoring = [p for p in processors
                        if p.get("processor_type") == "minecraft:block_ignore"]
            if not ignoring:
                problems.append("%s 缺少 block_ignore 处理器（会留下结构方块）" % identifier)
            for processor in ignoring:
                blocks = processor.get("blocks")
                if not isinstance(blocks, list) or not blocks:
                    problems.append("%s 的 block_ignore.blocks 为空或不是列表" % identifier)
                    continue
                for block in blocks:
                    # 26.3 BlockState.CODEC: a plain id string, or {"id": ..., "properties": ...}.
                    # Anything else (e.g. the legacy {"Name": ...}) fails the whole
                    # minecraft:worldgen/template_pool registry and aborts startup.
                    if not isinstance(block, str):
                        problems.append(
                            "%s 的 block_ignore.blocks 含非字符串方块状态 %r"
                            "（26.3 只接受字符串 id 或 {id, properties}）" % (identifier, block))
                    elif block != "minecraft:structure_block":
                        problems.append(
                            "%s 的 block_ignore 忽略了预期外的方块 %s（应只忽略"
                            " minecraft:structure_block）" % (identifier, block))

    # Per-NBT checks: parse it, and inspect the block palette and the loot references.
    vanilla = re.compile(r"^minecraft:[a-z0-9_./-]+$")
    for name in sorted(STRUCTURES_MAP.values()):
        target = "data/%s/structure/%s.nbt" % (NS, name)
        path = os.path.join(out_root, target)
        if not os.path.isfile(path):
            problems.append("缺少 " + target)
            continue
        raw = open(path, "rb").read()
        # The bytes must be the source's: identical file names make a swapped-payload mistake silent.
        source = "data/%s/structure/%s.nbt" % (NS, name)
        if gzip.decompress(raw) != gzip.decompress(archive.read(source)):
            problems.append(target + " 与源 jar 的 NBT 负载不一致")
        try:
            document = unpack(nbt.load_bytes(raw))
        except Exception as error:  # noqa: BLE001 - any parse failure is the finding
            problems.append("%s 无法解析：%s" % (target, error))
            continue
        for entry in document.get("palette", []):
            block = entry.get("Name", "")
            if not vanilla.match(block):
                problems.append("%s 含非原版方块 %s" % (target, block))
        # Loot references live inside the NBT. Only vanilla tables may be named, or the chest is a
        # dangling reference the server logs at placement time.
        for table in collect_loot_tables(document):
            if not table.startswith("minecraft:"):
                problems.append("%s 引用了非原版战利品表 %s" % (target, table))

    # Placement: one spacing/separation, distinct salts, and the vanilla window constraint.
    salts, spacing_values, separation_values = [], set(), set()
    for name in STRUCTURES:
        path = os.path.join(out_root, "data/%s/worldgen/structure_set/%s.json" % (NS, name))
        if not os.path.isfile(path):
            continue
        placement = json.load(open(path, encoding="utf-8"))["placement"]
        salts.append(placement.get("salt"))
        spacing_values.add(placement.get("spacing"))
        separation_values.add(placement.get("separation"))
    if spacing_values != {SPACING}:
        problems.append("spacing 不一致：%s" % sorted(spacing_values))
    if separation_values != {SEPARATION}:
        problems.append("separation 不一致：%s" % sorted(separation_values))
    if SEPARATION >= SPACING:
        problems.append("separation 必须小于 spacing")
    if len(set(salts)) != len(salts):
        problems.append("salt 有重复：%s" % sorted(salts))

    # The nine structures must point at the biome tag, never at the hardcoded vanilla biomes.
    for name in STRUCTURES:
        path = os.path.join(out_root, "data/%s/worldgen/structure/%s.json" % (NS, name))
        if not os.path.isfile(path):
            continue
        document = json.load(open(path, encoding="utf-8"))
        if document.get("biomes") != BIOMES:
            problems.append("%s 的 biomes 不是 %s" % (name, BIOMES))

    # pack.mcmeta must be the 26.3 scheme.
    meta = json.load(open(os.path.join(out_root, "pack.mcmeta"), encoding="utf-8"))["pack"]
    if meta.get("min_format") != [121, 0] or meta.get("max_format") != [121, 0]:
        problems.append("pack.mcmeta 不是 121 格式：%s" % meta)
    if "pack_format" in meta:
        problems.append("pack.mcmeta 混用了旧的 pack_format")

    # MCreator's empty vanilla tags must not be shipped.
    for tag in REJECTED_TAGS:
        stray = "data/minecraft/tags/worldgen/biome/%s.json" % tag
        if stray in shipped or os.path.isfile(os.path.join(out_root, stray)):
            problems.append("不得携带空的原版标签 " + stray)

    # Anything on disk we did not write is a drift signal, reported instead of deleted.
    for directory, _, files in os.walk(out_root):
        for name in files:
            absolute = os.path.join(directory, name)
            relative = os.path.relpath(absolute, out_root).replace(os.sep, "/")
            if relative not in shipped:
                problems.append("输出目录存在非本脚本生成的文件，请手动清理：" + relative)
    return problems


def collect_loot_tables(document: Any, found: List[str] = None) -> List[str]:
    """Every ``LootTable``/``loot_table`` value in a parsed structure NBT."""
    found = [] if found is None else found
    if isinstance(document, dict):
        for key, value in document.items():
            if key in ("LootTable", "loot_table") and isinstance(value, str):
                found.append(value)
            else:
                collect_loot_tables(value, found)
    elif isinstance(document, (list, tuple)):
        for value in document:
            collect_loot_tables(value, found)
    return found


# The NBT file names, which differ from the structure JSON names only for the warship.
STRUCTURES_MAP = {name: ("end_warship1" if name == "end_warship" else name) for name in STRUCTURES}


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", default=DEFAULT_INPUT)
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    arguments = parser.parse_args(argv)

    if not os.path.isfile(arguments.input):
        print("找不到源数据包 jar：%s" % arguments.input, file=sys.stderr)
        return 2
    digest = hashlib.sha256(open(arguments.input, "rb").read()).hexdigest()
    if digest != SOURCE_SHA256:
        print("源 jar 的 SHA-256 不是本脚本适配的版本：\n  实际 %s\n  期望 %s\n"
              "若确认是新的源版本，先更新脚本里的 SOURCE_SHA256 并重新核算放置参数。"
              % (digest, SOURCE_SHA256), file=sys.stderr)
        return 2

    report = migrate.Report()
    with zipfile.ZipFile(arguments.input) as archive:
        written, problems = build(archive, arguments.output, report)
        problems += verify(archive, arguments.output, written)

    if problems:
        print("生成的 echoes 数据包未通过自检：", file=sys.stderr)
        for problem in problems:
            print("  - " + problem, file=sys.stderr)
        return 1

    print("已生成 %s（%d 个文件）" % (arguments.output, len(written)))
    # Every rule that fired, so a reviewer sees *what* was migrated instead of trusting a diff.
    for rule in sorted(report.counts):
        print("  迁移 %-48s %d 处（%d 个文件）"
              % (rule, report.counts[rule], len(report.files[rule])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
