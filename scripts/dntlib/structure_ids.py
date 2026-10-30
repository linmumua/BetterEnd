#!/usr/bin/env python3
"""The structure-id contract the two bundled structure packs share.

Two packs publish structures under ``mumu``: the nine Echoes structures
(``scripts/prepare_echoes.py``) and the two DnT ones (``scripts/prepare_dnt.py``).
Eleven public ids in total, and every one of them is published the same way, for
the same reason:

* a structure's id *is* its file path below ``worldgen/structure``, so publishing
  one under a different namespace means writing a **second file**, never renaming
  a string;
* the author's (legacy) definition must stay shipped, because a saved chunk keys
  its ``structures.starts``/``references`` by the id string it was generated
  under. Drop it and a world that already placed one logs an unknown-start error
  and loses the in-progress build;
* the legacy id must own **no** ``structure_set``. It exists to be *readable*, not
  to place anything -- a second set would put a second copy of the same structure
  in every world generated before the rename.

That triple (definition twice, placement once, no legacy placement) is one rule,
so it lives here once and both builders call it, instead of each pack carrying its
own copy that can drift from the other.

What is deliberately *not* here: the per-pack placement numbers (Echoes' spacing /
separation / salts, DnT's concentric-ring density). Those are pack-specific
balance decisions, not part of the id contract.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from typing import Any, Dict, Iterable, Iterator, List, Optional, Sequence, Set, Tuple

# --------------------------------------------------------------------------- #
# ids
# --------------------------------------------------------------------------- #
#: The namespace every public structure id lives in -- what a player types after
#: ``/locate structure`` and ``/place structure``.
PUBLIC_NAMESPACE = "mumu"

#: The author's namespace each pack's legacy id keeps. Two packs, two namespaces;
#: the *rule* is shared, the namespace is not.
ECHOES_LEGACY_NAMESPACE = "echoes_of_the_end__structures_"
DNT_LEGACY_NAMESPACE = "nova_structures"

#: The nine structures the Echoes jar ships. Pinned so a source change is a loud
#: failure in both the builder and this module, rather than a silently different
#: pack.
ECHOES_STRUCTURES: Tuple[str, ...] = (
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

#: The two structures DnT ships as placement targets.
DNT_STRUCTURES: Tuple[str, ...] = ("end_castle", "end_ship")

#: All eleven public ids, as the single list the two packs split between them.
ALL_PUBLIC_STRUCTURES: Tuple[str, ...] = ECHOES_STRUCTURES + DNT_STRUCTURES

#: ``legacy namespace -> the structures published out of it``. One table, so a
#: namespace can never be paired with another pack's names.
STRUCTURES_BY_LEGACY_NAMESPACE: Dict[str, Tuple[str, ...]] = {
    ECHOES_LEGACY_NAMESPACE: ECHOES_STRUCTURES,
    DNT_LEGACY_NAMESPACE: DNT_STRUCTURES,
}

#: ``legacy namespace -> whether the author's definition is still shipped``.
#:
#: Echoes keeps it: an Echoes world saved before the rename resolves its chunk
#: data against ``echoes_of_the_end__structures_:<name>``, and the ids are long
#: enough that nobody types them from a completion list.
#:
#: DnT does not. ``/locate structure`` completes from the registry, not from the
#: placement tables, so keeping ``nova_structures:end_castle`` / ``end_ship``
#: made every structure show up twice and was reported as a duplicate. The
#: definition is therefore *relocated* into ``mumu:`` instead of duplicated --
#: one file, one id. The cost is explicit and accepted: a world generated before
#: the rename no longer resolves those two saved structure starts (vanilla logs
#: "Unknown structure id" and drops them; the blocks themselves stay).
KEEPS_LEGACY_DEFINITION: Dict[str, bool] = {
    ECHOES_LEGACY_NAMESPACE: True,
    DNT_LEGACY_NAMESPACE: False,
}


def keeps_legacy_definition(legacy_namespace: str) -> bool:
    """Whether this pack still ships the author's definition beside the public one."""
    return KEEPS_LEGACY_DEFINITION.get(legacy_namespace, True)

#: The Echoes NBT payload names, where they differ from the structure/template-pool
#: name. The warship is the one mismatched pair: its ``worldgen/structure`` and
#: ``worldgen/template_pool`` documents are ``end_warship`` while the payload is
#: ``end_warship1`` -- the pool's ``location`` says so. Every other payload is
#: named after its structure.
ECHOES_NBT_NAMES: Dict[str, str] = {"end_warship": "end_warship1"}


def structures_of(legacy_namespace: str) -> Tuple[str, ...]:
    """The structures published out of ``legacy_namespace`` (``()`` if unknown)."""
    return STRUCTURES_BY_LEGACY_NAMESPACE.get(legacy_namespace, ())


def public_rid(name: str) -> str:
    """The republished id of one structure, e.g. ``mumu:end_well``."""
    return "%s:%s" % (PUBLIC_NAMESPACE, name)


def legacy_rid(legacy_namespace: str, name: str) -> str:
    """The author's id of one structure, e.g. ``nova_structures:end_castle``."""
    return "%s:%s" % (legacy_namespace, name)


def nbt_name(legacy_namespace: str, name: str) -> str:
    """The ``.nbt`` payload file name for one structure."""
    if legacy_namespace == ECHOES_LEGACY_NAMESPACE:
        return ECHOES_NBT_NAMES.get(name, name)
    return name


# --------------------------------------------------------------------------- #
# paths
# --------------------------------------------------------------------------- #
def public_structure_path(name: str) -> str:
    """``data/mumu/worldgen/structure/<name>.json`` -- the id that places."""
    return "data/%s/worldgen/structure/%s.json" % (PUBLIC_NAMESPACE, name)


def public_structure_set_path(name: str) -> str:
    """``data/mumu/worldgen/structure_set/<name>.json`` -- the only placement."""
    return "data/%s/worldgen/structure_set/%s.json" % (PUBLIC_NAMESPACE, name)


def legacy_structure_path(legacy_namespace: str, name: str) -> str:
    """``data/<legacy ns>/worldgen/structure/<name>.json`` -- readable, unplaced."""
    return "data/%s/worldgen/structure/%s.json" % (legacy_namespace, name)


def legacy_structure_set_path(legacy_namespace: str, name: str) -> str:
    """``data/<legacy ns>/worldgen/structure_set/<name>.json`` -- must never ship."""
    return "data/%s/worldgen/structure_set/%s.json" % (legacy_namespace, name)


def _split(relpath: str) -> Tuple[str, ...]:
    return tuple(relpath.split("/"))


def _is_definition_of(parts: Sequence[str], legacy_namespace: str) -> Optional[str]:
    """``<name>`` when ``parts`` is a ``worldgen/structure`` doc of the legacy ns."""
    if (len(parts) == 5 and parts[0] == "data" and parts[1] == legacy_namespace
            and parts[2] == "worldgen" and parts[3] == "structure"
            and parts[4].endswith(".json")):
        name = parts[4][:-len(".json")]
        if name in structures_of(legacy_namespace):
            return name
    return None


def _is_structure_set_of(parts: Sequence[str], legacy_namespace: str) -> Optional[str]:
    """``<name>`` when ``parts`` is a ``worldgen/structure_set`` doc of the legacy ns."""
    if (len(parts) == 5 and parts[0] == "data" and parts[1] == legacy_namespace
            and parts[2] == "worldgen" and parts[3] == "structure_set"
            and parts[4].endswith(".json")):
        name = parts[4][:-len(".json")]
        if name in structures_of(legacy_namespace):
            return name
    return None


def relocate_structure_set(legacy_namespace: str, relpath: str) -> str:
    """Final output path for one source entry, after the public namespace move.

    Only a *placement* moves: ``data/<legacy ns>/worldgen/structure_set/<name>.json``
    is emitted as ``data/mumu/worldgen/structure_set/<name>.json`` instead. The
    structure *definitions* stay at the author's path on purpose -- see
    :func:`alias_structure_path`.
    """
    if _is_structure_set_of(_split(relpath), legacy_namespace):
        return public_structure_set_path(_split(relpath)[4][:-len(".json")])
    return relpath


def alias_structure_path(legacy_namespace: str, relpath: str) -> Optional[str]:
    """The second path one public structure *definition* must also be written to.

    Returns ``data/mumu/worldgen/structure/<name>.json`` for the pack's public
    structure definitions, else ``None``. Returning a path (rather than relocating
    the source entry) is what keeps the author's definition in the pack as a
    read-only legacy entry.

    ``None`` for a namespace that does not keep one (see
    :data:`KEEPS_LEGACY_DEFINITION`): there the definition is *moved* by
    :func:`relocate_structure_definition` instead, and a second write here would
    collide with it.
    """
    if not keeps_legacy_definition(legacy_namespace):
        return None
    name = _is_definition_of(_split(relpath), legacy_namespace)
    return None if name is None else public_structure_path(name)


def relocate_structure_definition(legacy_namespace: str, relpath: str) -> str:
    """Final output path for one structure *definition* source entry.

    A pack that keeps a legacy definition leaves the entry where the author put
    it and writes a second copy via :func:`alias_structure_path`. A pack that
    does not (DnT) moves the entry to ``data/mumu/worldgen/structure/<name>.json``
    instead, so exactly one file -- and therefore exactly one registry id --
    exists per structure.
    """
    if keeps_legacy_definition(legacy_namespace):
        return relpath
    name = _is_definition_of(_split(relpath), legacy_namespace)
    return relpath if name is None else public_structure_path(name)


def relocated_id(legacy_namespace: str, rid: Any) -> Optional[str]:
    """``<legacy ns>:<public structure>`` -> its ``mumu:`` id, else ``None``.

    Deliberately exact: only the pack's own public structures are matched, so an
    id that merely starts with the same text (``nova_structures:end_castle/start``,
    a template pool) is never rewritten, and no other namespace is touched.
    """
    if not isinstance(rid, str) or ":" not in rid:
        return None
    namespace, path = rid.split(":", 1)
    if namespace != legacy_namespace or path not in structures_of(legacy_namespace):
        return None
    return public_rid(path)


# --------------------------------------------------------------------------- #
# the shared layout rule
# --------------------------------------------------------------------------- #
def iter_structure_refs(relpath: str, document: Any) -> Iterator[str]:
    """Every structure id ``document`` names in a *reference* position.

    Mirrors the four places a ``RegistryCodecs.holderSet(Registries.STRUCTURE)``
    (or an equivalent structure-id field) appears in a data pack -- the same four
    ``migrate.rule_public_structure_ids`` rewrites:

    * ``worldgen/structure_set`` -- ``structures[].structure``;
    * ``tags/worldgen/structure`` -- ``values`` (a tag is identified by its path);
    * ``advancement`` -- ``location.structures``, one id or a compact list;
    * ``loot_table`` -- ``exploration_map.destination``, same holder-set shape.

    A ``#``-prefixed value is a tag, not a structure id, and is skipped. Template
    pool ``location``/``start_pool`` references are not structure ids either.
    """
    parts = _split(relpath)

    def holder_set(value: Any) -> Iterator[str]:
        if isinstance(value, str):
            if not value.startswith("#"):
                yield value
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, str) and not item.startswith("#"):
                    yield item

    def walk(node: Any) -> Iterator[str]:
        if isinstance(node, dict):
            entries = node.get("structures")
            if isinstance(entries, list):
                for entry in entries:
                    if (isinstance(entry, dict) and isinstance(entry.get("structure"), str)
                            and "weight" in entry):
                        yield entry["structure"]
            location = node.get("location")
            if isinstance(location, dict):
                yield from holder_set(location.get("structures"))
            if node.get("type") == "minecraft:exploration_map":
                yield from holder_set(node.get("destination"))
            for value in node.values():
                yield from walk(value)
        elif isinstance(node, list):
            for item in node:
                yield from walk(item)

    yield from walk(document)

    # A structure tag's values are structure ids, but only the path says so.
    if (len(parts) >= 6 and parts[0] == "data" and parts[2] == "tags"
            and parts[3] == "worldgen" and parts[4] == "structure"
            and isinstance(document, dict)):
        values = document.get("values")
        if isinstance(values, list):
            for value in values:
                if isinstance(value, dict):
                    value = value.get("id")
                if isinstance(value, str) and not value.startswith("#"):
                    yield value


def check_layout(legacy_namespace: str, shipped_files: Iterable[str],
                 data_docs: Dict[str, Any]) -> List[Tuple[str, str]]:
    """The shared id-layout rule; returns ``(kind, detail)`` problems.

    ``shipped_files`` are the pack's ``data/...`` paths (root-relative, forward
    slashes) and ``data_docs`` maps those same paths to their parsed JSON, so a
    reference inside a document can be inspected without re-reading the pack.

    The four failure modes are exactly the ways the two-namespace layout can be
    broken, and each is a live bug rather than a style nit:

    * ``missing-public-structure``  -- the placed definition is gone;
    * ``missing-legacy-structure``  -- an old world's chunk data no longer resolves
      (only for a namespace that still ships the author's definition);
    * ``stray-legacy-structure`` -- the author's definition is still shipped by a
      namespace that publishes only ``mumu:``, which makes the structure appear
      twice in ``/locate structure``'s registry-driven completion;
    * ``legacy-structure-placement`` -- a legacy ``structure_set`` survived, and it
      puts a second copy of the structure in every previously generated world;
    * ``legacy-structure-ref`` -- a tag / advancement / map still names the legacy
      id, so the map or eye points at a structure that never generates;
    * ``unplaced-public-structure`` / ``duplicated-public-structure`` -- the
      ``mumu:`` id is placed zero or 2+ times, which is either nothing or a
      doubled density.
    """
    names = structures_of(legacy_namespace)
    shipped: Set[str] = set(shipped_files)
    problems: List[Tuple[str, str]] = []

    for name in names:
        public = public_structure_path(name)
        legacy = legacy_structure_path(legacy_namespace, name)
        if public not in shipped:
            problems.append(("missing-public-structure", public))
        if keeps_legacy_definition(legacy_namespace):
            if legacy not in shipped:
                problems.append(("missing-legacy-structure", legacy))
        elif legacy in shipped:
            # Two ids for one structure: /locate completes from the registry, so the
            # alias shows up beside the public id and the structure looks duplicated.
            problems.append(("stray-legacy-structure", legacy))
        stale = legacy_structure_set_path(legacy_namespace, name)
        if stale in shipped:
            problems.append(("legacy-structure-placement", stale))

    legacy_ids = {legacy_rid(legacy_namespace, name) for name in names}
    placed: Dict[str, int] = {}
    legacy_refs: Dict[str, int] = {}
    for path in sorted(data_docs):
        parts = _split(path)
        is_structure_set = (len(parts) >= 5 and parts[0] == "data"
                            and parts[2] == "worldgen" and parts[3] == "structure_set")
        for rid in iter_structure_refs(path, data_docs[path]):
            if rid in legacy_ids:
                legacy_refs[rid] = legacy_refs.get(rid, 0) + 1
            if is_structure_set:
                placed[rid] = placed.get(rid, 0) + 1

    for name in names:
        rid = public_rid(name)
        count = placed.get(rid, 0)
        if count == 0:
            problems.append(("unplaced-public-structure", rid))
        elif count > 1:
            problems.append(("duplicated-public-structure", "%s x%d" % (rid, count)))
        old = legacy_rid(legacy_namespace, name)
        if legacy_refs.get(old, 0):
            problems.append((
                "legacy-structure-ref",
                "%s x%d（旧 id 仍被引用：旧 definition 必须留在注册表里，但不许再出现在"
                " structure_set / 结构 tag / advancement 中，否则地图、末影之眼或成就指向"
                " 一座永不生成的结构，或与 %s 在同一格各生成一座）"
                % (old, legacy_refs[old], rid)))
    return problems


# --------------------------------------------------------------------------- #
# source-vs-artifact consistency
# --------------------------------------------------------------------------- #
def echoes_expected_files() -> Tuple[str, ...]:
    """The exact file list the Echoes pack must ship -- 46 paths, sorted.

    Derivable from the ids alone, which is why it can be pinned here instead of
    hand-listed: nine ``.nbt`` payloads, nine template pools, and then the
    two-namespace split of definition (twice) and placement (once) for each id,
    plus ``pack.mcmeta``.

    This is the list that catches an *old* builder being re-run: the pre-rename
    script wrote the nine ``structure_set`` documents under the legacy namespace
    and no ``mumu`` files at all, so a stale run loses all eighteen public
    documents and adds nine duplicate placements -- both visible as a set
    difference here, before anything is unzipped onto a server.
    """
    expected: Set[str] = {"pack.mcmeta"}
    for name in ECHOES_STRUCTURES:
        expected.add("data/%s/structure/%s.nbt"
                     % (ECHOES_LEGACY_NAMESPACE, nbt_name(ECHOES_LEGACY_NAMESPACE, name)))
        expected.add("data/%s/worldgen/template_pool/%s.json" % (ECHOES_LEGACY_NAMESPACE, name))
        expected.add(legacy_structure_path(ECHOES_LEGACY_NAMESPACE, name))
        expected.add(public_structure_path(name))
        expected.add(public_structure_set_path(name))
    return tuple(sorted(expected))


def tree_manifest(root: str) -> Dict[str, str]:
    """``relpath -> sha256`` for every file under ``root`` (forward slashes)."""
    manifest: Dict[str, str] = {}
    for directory, _, files in os.walk(root):
        for name in files:
            absolute = os.path.join(directory, name)
            relative = os.path.relpath(absolute, root).replace(os.sep, "/")
            with open(absolute, "rb") as handle:
                manifest[relative] = hashlib.sha256(handle.read()).hexdigest()
    return manifest


def compare_manifest(expected: Dict[str, str], root: str) -> List[str]:
    """Byte-level drift between a recorded manifest and what is on disk.

    Missing, extra and modified files are all reported: a builder that silently
    stopped emitting one document is exactly the failure a manifest exists for.
    """
    actual = tree_manifest(root) if os.path.isdir(root) else {}
    problems: List[str] = []
    for path in sorted(set(expected) - set(actual)):
        problems.append("缺少：" + path)
    for path in sorted(set(actual) - set(expected)):
        problems.append("多出：" + path)
    for path in sorted(set(expected) & set(actual)):
        if expected[path] != actual[path]:
            problems.append("内容不一致：%s（期望 %s，实际 %s）"
                            % (path, expected[path][:12], actual[path][:12]))
    return problems


def aggregate_digest(manifest: Dict[str, str]) -> str:
    """One sha256 over a ``relpath -> digest`` manifest, for a single pinned number.

    ``compare_manifest`` names the exact files (that is what a failure message
    needs); this is the compact form for recording "the artifact as accepted".
    """
    digest = hashlib.sha256()
    for path in sorted(manifest):
        digest.update(("%s\0%s\n" % (path, manifest[path])).encode("utf-8"))
    return digest.hexdigest()


def load_pack_docs(root: str) -> Dict[str, Any]:
    """Parse every ``data/**/*.json`` below ``root`` into ``relpath -> document``."""
    docs: Dict[str, Any] = {}
    data_root = os.path.join(root, "data")
    if not os.path.isdir(data_root):
        return docs
    for directory, _, files in os.walk(data_root):
        for name in files:
            if not name.endswith(".json"):
                continue
            absolute = os.path.join(directory, name)
            relative = os.path.relpath(absolute, root).replace(os.sep, "/")
            try:
                with open(absolute, encoding="utf-8") as handle:
                    docs[relative] = json.load(handle)
            except (OSError, ValueError):
                # A malformed document is not this check's business: the pack's own
                # validator reports it with the file and the parse error.
                continue
    return docs


def check_echoes_artifact(root: str) -> List[str]:
    """Everything the committed Echoes pack must satisfy; ``[]`` means green."""
    problems: List[str] = []
    actual = tree_manifest(root)
    expected_files = set(echoes_expected_files())
    for path in sorted(expected_files - set(actual)):
        problems.append("缺少：" + path)
    for path in sorted(set(actual) - expected_files):
        problems.append("多出（应为 46 个文件的固定布局）：" + path)
    shipped = [path for path in actual if path.startswith("data/")]
    for kind, detail in check_layout(ECHOES_LEGACY_NAMESPACE, shipped, load_pack_docs(root)):
        problems.append("%s: %s" % (kind, detail))
    return problems


def check_dnt_artifact(root: str) -> List[str]:
    """The DnT datapack half of the same rule (its file list is the ZIP's own)."""
    pack_root = os.path.join(root, "datapacks", "dnt-end-castle")
    if not os.path.isdir(pack_root):
        return ["缺少 DnT 数据包目录：" + pack_root]
    shipped = list(tree_manifest(pack_root))
    return ["%s: %s" % (kind, detail)
            for kind, detail in check_layout(DNT_LEGACY_NAMESPACE, shipped,
                                             load_pack_docs(pack_root))]


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify the shared structure-id layout of a bundled pack.")
    parser.add_argument("--pack", required=True, choices=("echoes", "dnt"),
                        help="which pack's layout rule to apply")
    parser.add_argument("--root", required=True,
                        help="pack/pack-output root to verify (never written)")
    parser.add_argument("--manifest", default=None,
                        help="recorded manifest JSON to compare against byte-for-byte")
    parser.add_argument("--write-manifest", default=None,
                        help="write the current tree's manifest JSON here, then stop")
    arguments = parser.parse_args(argv)

    if not os.path.isdir(arguments.root):
        print("找不到待校验目录：%s" % arguments.root, file=sys.stderr)
        return 2

    if arguments.write_manifest:
        manifest = tree_manifest(arguments.root)
        payload = {"aggregate": aggregate_digest(manifest), "files": manifest}
        with open(arguments.write_manifest, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
        print("已记录 %d 个文件的清单：%s" % (len(manifest), arguments.write_manifest))
        return 0

    problems: List[str] = []
    if arguments.pack == "echoes":
        problems.extend(check_echoes_artifact(arguments.root))
    else:
        problems.extend(check_dnt_artifact(arguments.root))
    if arguments.manifest:
        with open(arguments.manifest, encoding="utf-8") as handle:
            recorded = json.load(handle)["files"]
        problems.extend(compare_manifest(recorded, arguments.root))

    if problems:
        print("结构 id 布局校验未通过：", file=sys.stderr)
        for problem in problems:
            print("  - " + problem, file=sys.stderr)
        return 1
    print("结构 id 布局校验通过：%s（%d 个文件）"
          % (arguments.root, len(tree_manifest(arguments.root))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
