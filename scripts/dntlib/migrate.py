"""DnT v1.3.4 (1.20.x/1.21.5 era) -> 26.3 schema migration rules.

Every rule below is backed by a concrete codec/file in the target source tree
(``MU核心/mucore-server/src/minecraft``) or by the shipped vanilla ``26.3`` data
pack; the rationale strings are surfaced in the generated manifest so a reviewer
can audit *why* each change was made. Nothing here "guesses" an API.

Target baseline: id 26.3, world_version 5023, data pack format 121.0,
resource pack format 97.1 (``resources/version.json``).
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

#: The public-id / legacy-definition contract is shared with the Echoes pack, so it
#: is defined once in dntlib.structure_ids; the DnT-side names below are thin views
#: of it and keep their original signatures.
from dntlib import structure_ids

TARGET_DATA_FORMAT = (121, 0)
TARGET_RESOURCE_FORMAT = (97, 1)
TARGET_DATA_VERSION = 5023

#: The one-line implementation of the missing ``phantom_boss_vanish`` function,
#: taken verbatim from the same-generation official Dungeons & Taverns v4.7.3
#: package (SHA-1 998c6f18e4ed6679235c635637b52075702a5cd6).
PHANTOM_BOSS_VANISH_LINE = "particle minecraft:smoke ~ ~ ~ 2 1 2 0 200 normal"
PHANTOM_BOSS_VANISH_PROVENANCE = (
    "nova_structures:function/phantom_boss_vanish.mcfunction restored from "
    "Dungeons & Taverns v4.7.3 (sha1 998c6f18e4ed6679235c635637b52075702a5cd6); "
    "reference v4.7.2 is identical. Single line, no other upstream assets added."
)

#: Old-format loot discriminator that the 26.3 codec no longer knows.
_LOOT_CATEGORIES = ("loot_table", "item_modifier")

#: 26.3 loot-function types whose MAP_CODEC payload field is literally
#: ``functions`` (``SequenceFunction``). A node already dispatching on one of
#: these must keep that field; renaming it to ``modifier`` would destroy it.
SEQUENCE_FUNCTION_TYPES = ("sequence", "minecraft:sequence")

#: 26.3 loot-function types whose payload field is literally ``function`` (a
#: referenced function id, not a discriminator). The legacy ``function`` ->
#: ``type`` rename must never fire on these.
RUN_FUNCTION_TYPES = ("run_function", "minecraft:run_function")

#: Loot contexts whose value is a *dispatched* number provider, keyed by the
#: owning function's discriminator (namespace optional). Each value is a
#: ``ContextIntProviders``/``ContextFloatProviders`` ``Either(ConstantValue,
#: dispatch)``: a bare number is legal, but a ``{min, max}`` object is a
#: ``UniformGenerator`` and must carry ``type`` (``RangeProvider.mapCodec``
#: dispatches on it):
#:   * ``SetItemCountFunction.count`` (int)
#:   * ``SetItemDamageFunction.damage`` (float)
#:   * ``SetOminousBottleAmplifierFunction.amplifier`` (int)
#:   * ``EnchantWithLevelsFunction.levels`` (int)
_PROVIDER_FIELDS_BY_TYPE = {
    "set_count": ("count",),
    "set_damage": ("damage",),
    "set_ominous_bottle_amplifier": ("amplifier",),
    "enchant_with_levels": ("levels",),
}

#: ``SetStewEffectFunction.effects[].duration`` is the same int-provider context.
_STEW_DURATION_TYPE = "set_stew_effect"

#: ``LootPool.CODEC`` reads its own providers: ``rolls`` (int, required) and
#: ``bonus_rolls`` (float, optional).
_POOL_PROVIDER_FIELDS = ("rolls", "bonus_rolls")

#: The retired 26.3 ``map_color`` component (``MapColorComponentRemovalFix``,
#: schema 5008; ``DataComponents`` no longer registers it). Loot JSON never
#: passes through the DFU, so it must be dropped directly.
MAP_COLOR_COMPONENT_KEYS = ("minecraft:map_color", "map_color")


class Report:
    """Accumulates per-rule evidence for the generated manifest."""

    def __init__(self) -> None:
        self.counts: Dict[str, int] = {}
        self.files: Dict[str, List[str]] = {}

    def hit(self, rule: str, path: str, n: int = 1) -> None:
        if n <= 0:
            return
        self.counts[rule] = self.counts.get(rule, 0) + n
        bucket = self.files.setdefault(rule, [])
        if path not in bucket:
            bucket.append(path)

    def as_manifest_block(self) -> Dict[str, Any]:
        return {
            "rules": {
                rule: {"occurrences": self.counts[rule], "files": sorted(self.files[rule])}
                for rule in sorted(self.counts)
            }
        }


def target_category(relpath: str) -> str:
    """Return the data-pack category for a ``data/<ns>/<category>/...`` path."""
    parts = relpath.split("/")
    if len(parts) >= 3 and parts[0] == "data":
        return parts[2]
    return ""


#: The clock vanilla's ``DayTimeToClockFix`` assigns to the overworld
#: (``WorldClocks.OVERWORLD``, id ``minecraft:overworld``). 26.3 ``TimeCheck``
#: reads ``WorldClock.CODEC.fieldOf("clock")`` (mandatory), and the legacy
#: ``DayTime`` value migrates to exactly this clock.
DEFAULT_WORLD_CLOCK = "minecraft:overworld"


def relocate_worldgen(relpath: str) -> str:
    """Final output path for a source entry.

    ``Registries.FEATURE`` is ``worldgen/feature`` in 26.3 (not the 1.21.x
    ``worldgen/configured_feature``), and ``FileToIdConverter.registry`` uses the
    registry path verbatim as the folder. A ``configured_feature`` document must
    therefore land in ``worldgen/feature``; every other path is returned as-is.
    """
    parts = relpath.split("/")
    if len(parts) >= 5 and parts[0] == "data" and parts[2] == "worldgen" and parts[3] == "configured_feature":
        parts[3] = "feature"
        return "/".join(parts)
    return relpath


#: The namespace this project publishes the DnT structures under. A structure's
#: id *is* its file path below ``worldgen/structure``, so publishing one under a
#: different namespace means writing a second file, not renaming a string.
#:
#: The ids, the path derivation and the "definition twice, placement once" rule are
#: shared with the Echoes pack and therefore live in :mod:`dntlib.structure_ids`;
#: everything below this point is the DnT view of that one definition.
PUBLIC_STRUCTURE_NAMESPACE = structure_ids.PUBLIC_NAMESPACE

#: The two structures DnT ships as placement targets, and the only worldgen ids
#: this project republishes. Everything else in the pack (template pools, the
#: ``.nbt`` payloads, biomes, loot, enchantments, the CraftEngine pack) keeps the
#: author's namespace: those ids are internal to the structures, they are
#: referenced from the ``.nbt``/pool JSON by the author's own ids, and renaming
#: them would be a much wider change with no visible benefit.
PUBLIC_STRUCTURES = structure_ids.DNT_STRUCTURES


def public_structure_rid(name: str) -> str:
    """The republished id of one public structure, e.g. ``mumu:end_castle``."""
    return structure_ids.public_rid(name)


def relocated_public_id(rid: Any) -> Optional[str]:
    """``nova_structures:<public structure>`` -> its ``mumu:`` id, else ``None``.

    Deliberately exact: only the two public structures are matched, so an id that
    merely starts with the same text (``nova_structures:end_castle/start``, a
    template pool) is never rewritten, and no other namespace is touched.
    """
    return structure_ids.relocated_id(structure_ids.DNT_LEGACY_NAMESPACE, rid)


def relocate_public_structure_set(relpath: str) -> str:
    """Final output path for one source entry, after the public namespace move.

    Only a *placement* moves: ``data/nova_structures/worldgen/structure_set/
    {end_castle,end_ship}.json`` is emitted as
    ``data/{{ns}}/worldgen/structure_set/...`` instead. The structure
    *definitions* are handled by :func:`relocate_public_structure_definition`.
    """
    return structure_ids.relocate_structure_set(
        structure_ids.DNT_LEGACY_NAMESPACE, relpath)


def relocate_public_structure_definition(relpath: str) -> str:
    """Final output path for one public structure *definition* source entry.

    DnT publishes ``mumu:`` only, so the author's definition moves instead of
    being kept as a second id -- see
    :data:`structure_ids.KEEPS_LEGACY_DEFINITION`. Nothing else in the pack is
    affected: template pools, ``.nbt`` payloads and biomes keep the author's
    namespace.
    """
    return structure_ids.relocate_structure_definition(
        structure_ids.DNT_LEGACY_NAMESPACE, relpath)


def public_structure_alias_path(relpath: str) -> Optional[str]:
    """The second path one public structure *definition* must also be written to.

    Returns ``data/{{ns}}/worldgen/structure/<name>.json`` for the two public
    structure definitions, else ``None``. Returning a path (rather than relocating
    the source entry) is what keeps the author's definition in the pack as a
    read-only legacy entry: a world saved before the rename stores its structure
    starts keyed by the old id, and a chunk's later neighbours can only place
    pieces while that id still resolves in the registry. Two ids therefore exist;
    only the ``mumu:`` one is ever placed, because only it appears in a
    ``structure_set``.

    ``None`` for DnT, which no longer ships the author's id at all: there the
    definition is moved by :func:`relocate_public_structure_definition` and a
    second write here would collide with it.
    """
    return structure_ids.alias_structure_path(
        structure_ids.DNT_LEGACY_NAMESPACE, relpath)


def assign_output_paths(names: List[str]) -> Dict[str, str]:
    """Map source entry names to final output paths, rejecting collisions.

    Two source entries that resolve to the same output path (e.g. a stray
    ``worldgen/feature/x.json`` alongside the relocated
    ``worldgen/configured_feature/x.json``) would silently overwrite each other,
    so this is a hard error rather than a hand-fix of the build output.
    """
    mapping: Dict[str, str] = {}
    used: Dict[str, str] = {}
    for name in names:
        out = relocate_public_structure_definition(relocate_worldgen(name))
        out = relocate_public_structure_set(out)
        if out in used and used[out] != name:
            raise ValueError("输出路径冲突：%s 与 %s 同时映射到 %s" % (used[out], name, out))
        used[out] = name
        mapping[name] = out
    return mapping


# --------------------------------------------------------------------------- #
# generic walkers
# --------------------------------------------------------------------------- #
def for_each_dict(obj: Any, fn) -> None:
    """Call ``fn(dict)`` for every dict node (depth-first, in place)."""
    if isinstance(obj, dict):
        fn(obj)
        for value in obj.values():
            for_each_dict(value, fn)
    elif isinstance(obj, list):
        for item in obj:
            for_each_dict(item, fn)


def _merge_list_to_condition(items: List[Any]) -> Dict[str, Any]:
    """Collapse a legacy condition list into one 26.3 ``Holder<LootItemCondition>``."""
    if len(items) == 1:
        return items[0]
    return {"type": "minecraft:all_of", "terms": items}


def iter_dicts(obj: Any):
    """Yield every dict node of a document (depth-first, read-only)."""
    if isinstance(obj, dict):
        yield obj
        for value in obj.values():
            yield from iter_dicts(value)
    elif isinstance(obj, list):
        for item in obj:
            yield from iter_dicts(item)


def discriminator_name(node: Dict[str, Any]) -> Optional[str]:
    """A node's discriminator, namespace stripped, whether already 26.3 (``type``)
    or legacy (``function``). ``None`` when neither is a string."""
    for key in ("type", "function"):
        value = node.get(key)
        if isinstance(value, str):
            return value.split(":")[-1]
    return None


def looks_like_untyped_uniform(value: Any) -> bool:
    """True for a ``{min, max}`` object that still lacks its ``type`` discriminator."""
    return isinstance(value, dict) and "type" not in value and set(value) == {"min", "max"}


def iter_number_provider_sites(doc: Any):
    """Yield every dict that *is* a number-provider value in one loot document.

    Deliberately scoped to codec-backed provider fields (a loot pool's
    ``rolls``/``bonus_rolls`` and the per-function fields above), so range
    *predicates* — ``time_check.value`` (``IntRangePredicate.Line``, whose
    ``min``/``max`` are optional providers and whose object is never dispatched)
    or an enchantment's own min/max range — are never considered.
    """
    for node in iter_dicts(doc):
        if "rolls" in node:
            for field in _POOL_PROVIDER_FIELDS:
                value = node.get(field)
                if isinstance(value, dict):
                    yield value
        base = discriminator_name(node)
        fields = _PROVIDER_FIELDS_BY_TYPE.get(base)
        if fields:
            for field in fields:
                value = node.get(field)
                if isinstance(value, dict):
                    yield value
        if base == _STEW_DURATION_TYPE:
            effects = node.get("effects")
            if isinstance(effects, list):
                for effect in effects:
                    if isinstance(effect, dict) and isinstance(effect.get("duration"), dict):
                        yield effect["duration"]


def iter_set_components_payloads(doc: Any):
    """Yield the ``components`` object of every ``set_components`` loot function."""
    for node in iter_dicts(doc):
        if discriminator_name(node) != "set_components":
            continue
        components = node.get("components")
        if isinstance(components, dict):
            yield components


def iter_pool_element_bodies(pool: Any):
    """Yield every ``elements[].element`` body of a 26.3 template_pool document.

    Structural, not textual: the pool root is the only place a ``blocks`` key may
    legitimately appear, so anchoring on the document shape (rather than scanning
    for the key anywhere) is what keeps an unrelated ``blocks`` list in an embedded
    NBT-ish structure from being rewritten.
    """
    if not isinstance(pool, dict):
        return
    entries = pool.get("elements")
    if not isinstance(entries, list):
        return
    for entry in entries:
        if isinstance(entry, dict) and isinstance(entry.get("element"), dict):
            yield entry["element"]


def iter_element_processors(pool: Any):
    """Yield every processor object of a template_pool's pool elements.

    ``StructurePoolElement.processors`` is a
    ``Holder<StructureProcessorList>`` with ``allowInline``; JSON may carry the
    inline list either as ``{"processors": [...]}``
    (``StructureProcessorType.DIRECT_CODEC``'s first alternative) or as a bare
    list (its second). Both shapes are walked so a legacy entry cannot hide in
    the less common one; a plain ``"minecraft:empty"`` string reference is a
    holder id and yields nothing.
    """
    for element in iter_pool_element_bodies(pool):
        processors = element.get("processors")
        if isinstance(processors, dict):
            processors = processors.get("processors")
        if isinstance(processors, list):
            for processor in processors:
                if isinstance(processor, dict):
                    yield processor


# --------------------------------------------------------------------------- #
# rule implementations
# --------------------------------------------------------------------------- #
def rule_pack_mcmeta(description: str) -> Dict[str, Any]:
    """26.3 pack.mcmeta (``PackMetadataSection`` + ``PackFormat``).

    Formats above the last pre-minor version (data 81, resources 64) must declare
    ``min_format``/``max_format`` and must NOT carry ``supported_formats``.
    """
    return {
        "pack": {
            "description": description,
            "min_format": list(TARGET_DATA_FORMAT),
            "max_format": list(TARGET_DATA_FORMAT),
        }
    }


def rule_loot_discriminators(obj: Any, relpath: str, report: Report) -> None:
    """``LootPool``/``LootPoolEntryContainer``/``LootTable`` name their function
    container ``modifier`` and dispatch on ``type`` (26.3), not ``functions`` /
    ``function`` (1.21.5).

    Both renames are node-context scoped so the 26.3 payload fields survive and
    re-running is a no-op:

    * ``functions`` -> ``modifier`` only when the node is **not** a 26.3 sequence
      function (``SequenceFunction``'s payload field genuinely is ``functions``);
    * ``function`` -> ``type`` only when the node does not already dispatch on
      ``type`` — a run_function's ``function`` payload (a resource id, not a
      discriminator) is therefore never rewritten, and an already-migrated node
      is left untouched.
    """
    def visit(node: Dict[str, Any]) -> None:
        discriminator = discriminator_name(node)
        if "functions" in node and discriminator not in SEQUENCE_FUNCTION_TYPES:
            node["modifier"] = node.pop("functions")
            report.hit("loot.functions_to_modifier", relpath)
        if "type" not in node and isinstance(node.get("function"), str):
            node["type"] = node.pop("function")
            report.hit("loot.function_discriminator_to_type", relpath)
    for_each_dict(obj, visit)


def rule_condition_discriminator(obj: Any, relpath: str, report: Report) -> None:
    """Old inline loot conditions dispatched on ``condition``; 26.3 dispatches on
    ``type`` (``LootItemCondition.DIRECT_CODEC``)."""
    def visit(node: Dict[str, Any]) -> None:
        if isinstance(node.get("condition"), str):
            node["type"] = node.pop("condition")
            report.hit("condition.discriminator_to_type", relpath)
    for_each_dict(obj, visit)


def rule_time_check_clock(obj: Any, relpath: str, report: Report) -> None:
    """Give a 26.3 ``minecraft:time_check`` condition its mandatory ``clock``.

    ``TimeCheck.MAP_CODEC`` reads ``WorldClock.CODEC.fieldOf("clock")`` (required);
    the legacy condition carried no clock. Run this *after*
    :func:`rule_condition_discriminator` so the node already dispatches on
    ``type``. An existing ``clock`` is never overwritten, ``period``/``value`` are
    left verbatim, and only real ``minecraft:time_check`` nodes are touched.
    """
    def visit(node: Dict[str, Any]) -> None:
        if node.get("type") != "minecraft:time_check":
            return
        if "clock" in node:
            return
        node["clock"] = DEFAULT_WORLD_CLOCK
        report.hit("condition.time_check_clock_default", relpath)
    for_each_dict(obj, visit)


def rule_advancement_predicates(obj: Any, relpath: str, report: Report) -> None:
    """26.3 criteria hold a *single* ``Holder<LootItemCondition>`` per trigger
    field (``PlayerTrigger.TriggerInstance`` etc.); the old format used a list."""
    criteria = obj.get("criteria")
    if not isinstance(criteria, dict):
        return
    for criterion in criteria.values():
        if not isinstance(criterion, dict):
            continue
        conditions = criterion.get("conditions")
        if not isinstance(conditions, dict):
            continue
        for key, value in list(conditions.items()):
            if isinstance(value, list):
                conditions[key] = _merge_list_to_condition(value)
                report.hit("advancement.predicate_list_to_single", relpath)


def rule_spawn_overrides_count(obj: Any, relpath: str, report: Report) -> None:
    """26.3 ``spawn_overrides`` spawn entries use ``{type, count, weight}``
    (``MobSpawnSettings.SpawnerData``), not ``minCount``/``maxCount``."""
    def visit(node: Dict[str, Any]) -> None:
        if "minCount" in node or "maxCount" in node:
            lo = node.pop("minCount", None)
            hi = node.pop("maxCount", None)
            if lo is None:
                lo = hi
            if hi is None:
                hi = lo
            node["count"] = lo if lo == hi else {
                "type": "minecraft:uniform",
                "min_inclusive": lo,
                "max_inclusive": hi,
            }
            report.hit("structure.spawn_override_count", relpath)
    for_each_dict(obj, visit)


#: The DnT end castle's placement. The only file this project retunes: the castle was authored
#: against the vanilla End, where it had to compete with nothing, and 219 of them per world is far
#: too many once the nine Echoes structures share the same islands.
#:
#: Addressed by its *final output* path -- the castle's structure_set is republished under
#: :data:`PUBLIC_STRUCTURE_NAMESPACE`, so matching the source path here would silently stop the
#: density fix from applying.
CASTLE_STRUCTURE_SET = "data/%s/worldgen/structure_set/end_castle.json" % PUBLIC_STRUCTURE_NAMESPACE

#: The castle placement this project ships.
#:
#: ``concentric_rings`` semantics, read off the vanilla stronghold placement the scheme was
#: introduced with (128 / distance 32 / spread 3 -> eight rings, per-ring membership 3, 6, 10, 15,
#: 21, 28, 36 and a truncated 9):
#:
#: * ``count``    the TOTAL number of structures. Ring ``r`` holds the triangle number
#:                ``(r+1)(r+2)/2``; rings 1..7 hold 3+6+10+15+21+28+36 = 119, so vanilla's 128
#:                leaves 9 in ring 8 -- which is why that ring is truncated, and is the proof that
#:                ``count`` is a total cap rather than a per-ring one.
#: * ``distance`` the radial pitch between rings, in chunks (x16 for blocks): ring ``r``'s inner
#:                radius is ``distance*16*((spread+2)/2 + (r-1)*2*spread)``, giving vanilla's 1280,
#:                4352, 7424 ... for distance 32 / spread 3.
#: * ``spread``   the radial thickness of each ring, in the same units (3*32*16 = 1536 blocks for
#:                vanilla). It does not change how many structures exist.
#:
#: So ``count`` is the only lever on "how many castles", and ``distance`` only moves the rings
#: outward. DnT ships count 219 / distance 32, i.e. ring 8 alone holds 219-119 = 100 castles. The
#: values here:
#:
#: * ``count`` 219 -> 112 halves the castles per world (-49%), and 112 < 119 keeps the whole
#:   population inside seven rings instead of stranding a lone castle in ring 8.
#: * ``distance`` 32 -> 48 puts ring 1 at 48*16*2.5 = 1920 blocks instead of 1280 -- outside
#:   BetterEnd's 1024-block inner void, so castles stop crowding the central island.
#:
#: ``spread``/``salt``/``preferred_biomes`` are the author's and are left alone.
CASTLE_PLACEMENT = {"count": 112, "distance": 48}

CONCENTRIC_RINGS = "minecraft:concentric_rings"


def rule_end_castle_placement(obj: Any, relpath: str, report: Report) -> None:
    """Thin out the DnT end castle's ``concentric_rings`` placement.

    Scoped to exactly :data:`CASTLE_STRUCTURE_SET`, and inside it to the keys named in
    :data:`CASTLE_PLACEMENT` -- so the sibling ``end_ship`` structure_set (a ``random_spread``
    placement that is dense enough already) and every other worldgen document are untouched
    regardless of where this rule is called from.

    Only a key that is already present is rewritten: a source that lost ``count`` is malformed for
    ``concentric_rings`` (the codec requires it), and silently inventing a value would hide that
    rather than fix it. Re-running is a no-op, so an already-migrated pack reports zero hits.
    """
    if relpath != CASTLE_STRUCTURE_SET:
        return
    placement = obj.get("placement")
    if not isinstance(placement, dict):
        return
    # Anything but the scheme this arithmetic applies to is a source change worth not guessing at.
    if placement.get("type") != CONCENTRIC_RINGS:
        return
    changed = False
    for key, value in CASTLE_PLACEMENT.items():
        if key in placement and placement[key] != value:
            placement[key] = value
            changed = True
    if changed:
        report.hit("structure.end_castle_placement", relpath)


#: 每个公开结构的 ``start_height`` 下界地板，单位是世界 Y。取值理由见 :func:`rule_structure_height_floor`。
STRUCTURE_HEIGHT_FLOORS: Dict[str, int] = {
    # 城堡的相对范围是 start-64 .. start+51（拼图下探到 layer_-6），地形天花板是 127。
    # start >= 192 才能让最低点 192-64 = 128 高于地形，因此城堡整座悬在空中。
    "end_castle": 192,
    # 飞船相对范围是 start+1 .. start+47，start >= 128 才能让船底 129 高于地形。
    "end_ship": 128,
}


def rule_structure_height_floor(obj: Any, relpath: str, report: Report) -> None:
    """Raise a public structure's ``start_height`` lower bound above the terrain ceiling.

    The problem this exists for: these structures carry no ``project_start_to_heightmap``, so their
    Y is an absolute number chosen by the author. ``end_castle`` spans ``start-64 .. start+51`` and
    ``end_ship`` spans ``start+1 .. start+47``, while this generator's terrain tops out at
    ``IslandField.TOP_SOLID_Y`` (127). With the author's bounds (castle 158, ship 80) the lower
    layers land *inside* solid rock wherever an island happens to sit, which is the "building stuck
    in the terrain" this fixes.

    The floor is the smallest value that clears the ceiling for that structure's own vertical
    extent, and only ``min_inclusive`` is raised: the upper bound stays the author's, so the
    distribution keeps its width. A structure that already starts high enough is left untouched, so
    re-running is a no-op.

    This is deliberately *not* a platform: a 115-block-tall castle cannot sit on one without either
    burying its dungeon or dumping a 300-block plateau into the island. Floating is the only shape
    that satisfies "never inside the terrain".
    """
    parts = relpath.split("/")
    if len(parts) < 5 or parts[0] != "data" or parts[2] != "worldgen" or parts[3] != "structure":
        return
    name = parts[4][:-len(".json")] if parts[4].endswith(".json") else parts[4]
    floor = STRUCTURE_HEIGHT_FLOORS.get(name)
    if floor is None:
        return
    height = obj.get("start_height")
    if not isinstance(height, dict):
        return
    if height.get("type") != "minecraft:uniform":
        return
    bounds = height.get("min_inclusive")
    if not isinstance(bounds, dict):
        return
    current = bounds.get("absolute")
    if not isinstance(current, int) or current >= floor:
        return
    # The upper bound has to move with the floor or the range inverts (castle 192..159 would be
    # rejected by the codec). It keeps its own WIDTH, so the author's distribution shape survives.
    upper = height.get("max_inclusive")
    upperValue = upper.get("absolute") if isinstance(upper, dict) else None
    if isinstance(upperValue, int):
        if upperValue < floor:
            upper["absolute"] = floor + (upperValue - current)
    bounds["absolute"] = floor
    report.hit("structure.start_height_floor", relpath)


# --------------------------------------------------------------------------- #
# public structure ids: the two DnT structures are published under mumu:
# --------------------------------------------------------------------------- #
def _rename_structure_set_entries(obj: Any, relpath: str, report: Report) -> None:
    """``structure_set.structures[].structure`` is a ``Holder<Structure>`` id.

    This is the *only* thing that makes a structure generate: a structure absent
    from every ``structure_set`` is registered and inert. Rewriting this field is
    therefore what actually moves the placement, and leaving a legacy definition
    out of every structure_set is what stops the old id from generating a second
    castle in the same cell.
    """
    entries = obj.get("structures")
    if not isinstance(entries, list):
        return
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        renamed = relocated_public_id(entry.get("structure"))
        if renamed is None:
            continue
        entry["structure"] = renamed
        report.hit("structure.public_namespace_structure_set", relpath)


def _rename_structure_tag_values(obj: Any, relpath: str, report: Report) -> None:
    """``tags/worldgen/structure/*.json`` values are structure ids.

    Two shipped tags name the castle: the pack's own
    ``nova_structures:end_castle`` and vanilla's ``minecraft:eye_of_ender_located``,
    which is how an eye of ender points at the castle. Both must name the id that
    is actually placed, or they match a structure that never generates.

    Values may be plain id strings or ``{"id": ..., "required": ...}`` objects; a
    ``#``-prefixed value is a nested tag and is left alone.
    """
    values = obj.get("values")
    if not isinstance(values, list):
        return
    for index, value in enumerate(values):
        if isinstance(value, dict):
            renamed = relocated_public_id(value.get("id"))
            if renamed is None:
                continue
            value["id"] = renamed
        else:
            renamed = relocated_public_id(value)
            if renamed is None:
                continue
            values[index] = renamed
        report.hit("structure.public_namespace_structure_tag", relpath)


def _rename_location_predicate_structures(obj: Any, relpath: str, report: Report) -> None:
    """``LocationPredicate.structures`` (a ``HolderSet<Structure>``) in advancements.

    DnT's ``find_end_castle`` / ``find_end_ship`` advancements fire when the player
    stands in the structure, matched by id. Nothing else in an advancement names a
    structure, so the walk is anchored on the ``location`` predicate itself rather
    than on any string that happens to match. Same ``HolderSet`` shape as the map
    destination: a ``#``-prefixed value is a tag and is left alone.
    """
    def rename_into(container: Any, index: Any) -> None:
        value = container[index]
        if not isinstance(value, str) or value.startswith("#"):
            return
        renamed = relocated_public_id(value)
        if renamed is None:
            return
        container[index] = renamed
        report.hit("structure.public_namespace_advancement", relpath)

    def visit(node: Dict[str, Any]) -> None:
        location = node.get("location")
        if not isinstance(location, dict):
            return
        structures = location.get("structures")
        if isinstance(structures, str):
            rename_into(location, "structures")
        elif isinstance(structures, list):
            for index in range(len(structures)):
                rename_into(structures, index)

    for_each_dict(obj, visit)


def _rename_exploration_map_destinations(obj: Any, relpath: str, report: Report) -> None:
    """``exploration_map.destination`` is a ``HolderSet<Structure>``, not a tag key.

    26.3 ``ExplorationMapFunction.MAP_CODEC`` reads
    ``RegistryCodecs.holderSet(Registries.STRUCTURE).fieldOf("destination")``, and
    ``HolderSetCodec`` decodes ``either(TagKey.hashedCodec, compactListCodec)``:

    * a plain id string (no ``#``) is the *direct* branch -- a one-element set of a
      directly named structure;
    * a ``#``-prefixed string is the tag branch and must stay verbatim;
    * a list is the direct branch with several entries.

    So the castle map's ``nova_structures:end_castle`` is a direct structure
    reference and has to be repointed at the id that is actually placed -- the
    legacy definition never generates, so a kept legacy destination would make the
    map locate nothing. Only the two public ids are rewritten (never a ``#`` tag,
    never another namespace).
    """
    def rename_value(value: Any) -> Optional[str]:
        if not isinstance(value, str) or value.startswith("#"):
            return None
        return relocated_public_id(value)

    def visit(node: Dict[str, Any]) -> None:
        if discriminator_name(node) != "exploration_map":
            return
        destination = node.get("destination")
        renamed = rename_value(destination)
        if renamed is not None:
            node["destination"] = renamed
            report.hit("structure.public_namespace_map_destination", relpath)
            return
        if isinstance(destination, list):
            for index, value in enumerate(destination):
                renamed = rename_value(value)
                if renamed is None:
                    continue
                destination[index] = renamed
                report.hit("structure.public_namespace_map_destination", relpath)

    for_each_dict(obj, visit)


def rule_public_structure_ids(obj: Any, relpath: str, report: Report) -> None:
    """Repoint every *registry reference* to the two public structures at ``mumu:``.

    The rename is a namespace move of two worldgen ids, and a structure id is a
    registry key referenced from exactly four kinds of place. Each is walked
    structurally, so nothing else is rewritten:

    * ``worldgen/structure_set`` -- ``structures[].structure`` (the placement);
    * ``tags/worldgen/structure`` -- ``values`` (map/eye tags naming the structure);
    * ``advancement`` -- ``location.structures`` (the "find it" advancements);
    * ``loot_table`` -- ``exploration_map.destination`` (a ``HolderSet<Structure>``
      whose *direct* branch is a structure id; see
      :func:`_rename_exploration_map_destinations`).

    A ``#``-prefixed destination/tag value is a tag, not a structure id, and is
    never touched. Template-pool ``location``/``start_pool`` references and the
    ``.nbt`` payloads keep the author's namespace. Re-running is a no-op.
    """
    parts = relpath.split("/")
    if len(parts) < 4 or parts[0] != "data":
        return
    if parts[2] == "worldgen" and len(parts) >= 5 and parts[3] == "structure_set":
        _rename_structure_set_entries(obj, relpath, report)
    elif parts[2] == "tags" and len(parts) >= 6 and parts[3] == "worldgen" and parts[4] == "structure":
        _rename_structure_tag_values(obj, relpath, report)
    elif parts[2] == "advancement":
        _rename_location_predicate_structures(obj, relpath, report)
    elif parts[2] == "loot_table":
        _rename_exploration_map_destinations(obj, relpath, report)


def inline_reference_functions(obj: Any, resolver, relpath: str, report: Report) -> Tuple[Any, List[str], List[str]]:
    """Resolve ``{"type": "minecraft:reference", "name": <item_modifier>}`` by
    splicing the referenced modifier's function list in place.

    The target ``LootItemFunctions`` registry does not expose a ``reference``
    function, so a dangling reference would fail the whole loot table. Inlining
    is behaviour preserving *because* the shipped ``nova_structures:loot_modifier``
    is the empty list; any non-empty target is reported back so the assertion
    "the inlined modifier is empty" is checked, never assumed.

    Returns ``(migrated, missing_ids, non_empty_ids)``.
    """
    missing: List[str] = []
    non_empty: List[str] = []

    def rebuild(node: Any) -> Any:
        if isinstance(node, list):
            out: List[Any] = []
            for item in node:
                if isinstance(item, dict) and item.get("type") == "minecraft:reference":
                    name = item.get("name")
                    resolved = resolver(name)
                    if resolved is None:
                        missing.append(str(name))
                        out.append(item)
                    else:
                        funcs = resolved if isinstance(resolved, list) else [resolved]
                        if funcs:
                            non_empty.append(str(name))
                        out.extend(funcs)
                        report.hit("loot.reference_inlined", relpath)
                else:
                    out.append(rebuild(item))
            return out
        if isinstance(node, dict):
            return {k: rebuild(v) for k, v in node.items()}
        return node

    return rebuild(obj), missing, non_empty


def rule_number_provider_types(obj: Any, relpath: str, report: Report) -> None:
    """Give a dispatched ``{min, max}`` number provider its mandatory ``type``.

    26.3 splits the old ``NumberProvider`` into ``ContextIntProviders`` /
    ``ContextFloatProviders``; both are ``Either(ConstantValue, dispatch)``, so a
    bare number is still valid but a ``{min, max}`` object (``UniformGenerator``,
    built by ``RangeProvider.mapCodec``) must dispatch on ``type``. Only
    codec-proven provider fields are inspected, so range predicates
    (``time_check.value``) and enchantment min/max ranges are never touched, and
    an existing ``type`` or a bare number is left verbatim (inverted
    ``min``/``max`` included — never swapped or clamped).
    """
    for value in iter_number_provider_sites(obj):
        if looks_like_untyped_uniform(value):
            value["type"] = "minecraft:uniform"
            report.hit("loot.number_provider_missing_type", relpath)


#: 26.3 ``StructureProcessorType`` id (namespace optional) whose payload carries
#: ``BlockState`` values under ``blocks``.
BLOCK_IGNORE_PROCESSOR = "block_ignore"


def legacy_block_state_to_26_3(entry: Any) -> Optional[Any]:
    """The 26.3 form of one ``1.20``-era block-state entry, or ``None`` to leave it alone.

    26.3's ``BlockState.CODEC`` (``world/level/block/state/BlockState.java:13``)
    is ``Codec.either(BLOCK.byNameCodec(), FULL_CODEC)``:

    * first branch — a plain JSON **string** resource id
      (``Registry.byNameCodec()`` is ``Identifier.CODEC.comapFlatMap(...)`` and
      ``Identifier.CODEC`` is ``Codec.STRING``), which accepts only a block's
      default state;
    * second branch — ``FULL_CODEC``, i.e. ``StateHolder.codec``
      (``world/level/block/state/StateHolder.java:188-201``),
      ``ownerCodec.dispatch("id", ...)`` with a
      ``lenientOptionalFieldOf("properties")`` payload, so the object form is
      ``{"id": "...", "properties": {...}}``.

    The pre-1.20 palette spelling ``{"Name": "...", "Properties": {...}}`` is
    neither, which is why a datapack carrying it fails at registry load (the
    live error is ``Failed to parse either. First: Not a string: {...};
    Second: No key id in MapLike[...]``). This function therefore returns:

    * ``None`` when the entry is already valid — a string, or an object keyed
      ``id``. Re-running the migration is a no-op, which is what makes the rule
      idempotent;
    * the plain string id when the legacy entry carried no (or empty)
      ``Properties``: the default state is exactly what the first branch
      expresses, and it is the minimal legal form;
    * ``{"id": ..., "properties": {...}}`` when it did carry properties, which
      is ``FULL_CODEC``'s shape;
    * ``None`` again for anything it cannot translate **without guessing** — a
      non-string ``Name``, a ``Properties`` that is not an object, or extra keys
      beyond ``Name``/``Properties``. Those are left byte-for-byte so the codec
      gate reports them instead of the migration silently inventing a value.
    """
    if isinstance(entry, str):
        return None
    if not isinstance(entry, dict):
        return None
    name = entry.get("Name")
    if not isinstance(name, str) or not name:
        return None
    if not set(entry).issubset({"Name", "Properties"}):
        return None
    properties = entry.get("Properties")
    if properties is None:
        return name
    if not isinstance(properties, dict):
        return None
    if not properties:
        return name
    return {"id": name, "properties": properties}


def rule_template_pool_block_states(obj: Any, relpath: str, report: Report) -> None:
    """Rewrite legacy ``{"Name": ...}`` block-state entries in a 26.3 template pool.

    The only site this pack uses is ``BlockIgnoreProcessor.blocks``, whose codec
    is ``BlockState.CODEC`` (see ``legacy_block_state_to_26_3``): in
    ``world/level/levelgen/structure/templatesystem/BlockIgnoreProcessor.java:17-19``
    the processor reads ``WEIRD_BLOCK_STATE_CODEC.listOf().fieldOf("blocks")``
    where ``WEIRD_BLOCK_STATE_CODEC = BlockState.CODEC.xmap(getBlock,
    Block::defaultBlockState)``. A legacy palette object fails that decode, which
    fails the whole ``template_pool`` registry entry, which aborts startup.

    Walking is structural (``elements[].element.processors``), never textual:
    the pack's structure ``.nbt`` payloads legitimately use ``Name``/``Properties``
    inside their block palettes and must keep them.

    Other 26.3 ``BlockState.CODEC`` sites this pack does not contain are
    deliberately *not* guessed at; each is a separate codec field:
      * ``ProcessorRule.output_state`` (``ProcessorRule.java:21``);
      * ``BlockStateMatchTest.block_state`` (``BlockStateMatchTest.java:9``);
      * ``BlockRotProcessor.rottable_blocks`` is a ``holderSet(Registries.BLOCK)``
        of *blocks*, not block states (``BlockRotProcessor.java:19``), so a
        ``{"Name": ...}`` there is a different (also wrong) bug.
    """
    for processor in iter_element_processors(obj):
        kind = processor.get("processor_type")
        if not isinstance(kind, str) or kind.split(":")[-1] != BLOCK_IGNORE_PROCESSOR:
            continue
        blocks = processor.get("blocks")
        if not isinstance(blocks, list):
            continue
        for index, entry in enumerate(blocks):
            replacement = legacy_block_state_to_26_3(entry)
            if replacement is None:
                continue
            blocks[index] = replacement
            report.hit("structure.template_pool_block_state_name_to_id", relpath)


def rule_remove_map_color_component(obj: Any, relpath: str, report: Report) -> None:
    """Drop the retired ``map_color`` component from ``set_components`` payloads.

    26.3 removed the component (``MapColorComponentRemovalFix``, data version
    5008; ``DataComponents`` no longer registers it). Loot JSON never passes
    through the DFU, so the stale display colour must be deleted directly. Scoped
    strictly to a ``set_components`` function's ``components`` object, so an
    unrelated occurrence (e.g. an item-model tint ``type: minecraft:map_color``)
    is never touched.
    """
    for components in iter_set_components_payloads(obj):
        for key in MAP_COLOR_COMPONENT_KEYS:
            if key in components:
                components.pop(key)
                report.hit("loot.map_color_component_removed", relpath)


def wrap_item_modifier_root(obj: Any, relpath: str, report: Report) -> Any:
    """Wrap a bare-array item_modifier root as one explicit 26.3 sequence.

    ``Registries.ITEM_MODIFIER`` loads with ``LootItemFunctions.TYPED_CODEC``, a
    dispatch on ``type``: a bare array root is not a valid item modifier (only
    nested function positions accept a list, via ``DIRECT_CODEC``'s
    ``Either(..., SequenceFunction.INLINE_CODEC)``). Wrapping the root in
    ``{type: minecraft:sequence, functions: [...]}`` (``SequenceFunction.MAP_CODEC``)
    keeps an empty modifier an empty no-op and a non-empty one equivalent. An
    already-wrapped (object) root is returned unchanged, so re-running is a no-op,
    and the file is never dropped. Only the *root* list is wrapped; nested arrays
    stay legal lists.
    """
    if isinstance(obj, list):
        report.hit("loot.item_modifier_root_wrapped", relpath)
        return {"type": "minecraft:sequence", "functions": obj}
    return obj


# --------------------------------------------------------------------------- #
# mcfunction / SNBT item stack migration
# --------------------------------------------------------------------------- #
def _match_bracket(text: str, start: int) -> int:
    """Return the index just past the bracket that opens at ``text[start]``."""
    open_ch = text[start]
    close_ch = {"{": "}", "[": "]"}[open_ch]
    depth = 0
    i = start
    in_str = False
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced %r in %r" % (open_ch, text))


def _find_key(text: str, key: str) -> Tuple[int, int]:
    """Locate ``key`` at compound level in SNBT.

    Returns ``(key_start, value_start)`` where ``key_start`` is the first
    character of the key (including a quote when present) and ``value_start`` is
    the first character after the ``:``. Handles SNBT's unquoted keys.
    """
    for needle in ('"%s":' % key, "%s:" % key):
        start = 0
        while True:
            idx = text.find(needle, start)
            if idx < 0:
                break
            if idx == 0 or text[idx - 1] in "{,":
                return idx, idx + len(needle)
            start = idx + 1
    return -1, -1


def flatten_enchantment_component(components: str) -> Tuple[str, int]:
    """``{"minecraft:enchantments":{"levels":{...}}}`` -> ``.. {..}``.

    26.3 ``ItemEnchantments.CODEC`` is a flat map (``enchantment -> level``);
    the 1.21.x wrapper object with a ``levels`` field is unknown and would drop
    the enchantment entirely.
    """
    needle = '"minecraft:enchantments":'
    changed = 0
    pos = 0
    while True:
        idx = components.find(needle, pos)
        if idx < 0:
            return components, changed
        val_start = components.index("{", idx)
        val_end = _match_bracket(components, val_start)
        value = components[val_start:val_end]
        if '"levels"' in value:
            lpos = value.index('"levels"')
            lbrace = value.index("{", lpos)
            lend = _match_bracket(value, lbrace)
            levels = value[lbrace:lend]
            components = components[:val_start] + levels + components[val_end:]
            changed += 1
            pos = val_start + len(levels)
        else:
            pos = val_end


def migrate_hand_items(text: str) -> Tuple[str, int]:
    """``HandItems:[{id:X,Count:N,components:{...}}]`` -> ``equipment:{mainhand:{...}}``.

    26.3 ``LivingEntity`` only reads ``equipment``/``attributes``; ``HandItems``
    survives solely inside the DFU entity fixer, which ``/summon`` never runs.
    """
    out = text
    changed = 0
    while True:
        idx = out.find("HandItems:[")
        if idx < 0:
            return out, changed
        list_start = out.index("[", idx)
        list_end = _match_bracket(out, list_start)
        inner = out[list_start + 1 : list_end - 1].strip()
        item_start = inner.index("{")
        item_end = _match_bracket(inner, item_start)
        item = inner[item_start:item_end]
        new_item, n = normalize_item_stack(item)
        changed += n + 1
        replacement = "equipment:{mainhand:%s}" % new_item
        out = out[:idx] + replacement + out[list_end:]
    return out, changed


def normalize_item_stack(item: str) -> Tuple[str, int]:
    """Normalise one SNBT item stack to the 26.3 ``{id, count, components}`` form."""
    changed = 0
    # Count -> count
    kstart, vstart = _find_key(item, "Count")
    if kstart >= 0:
        end = _value_end(item, vstart)
        count_val = item[vstart:end].strip()
        item = item[:kstart] + "count:" + count_val + item[end:]
        changed += 1
    # components first (so the enchantment rewrite works on a stable string)
    kstart, vstart = _find_key(item, "components")
    if kstart >= 0:
        cstart = item.index("{", vstart - 1)
        cend = _match_bracket(item, cstart)
        comps, n = flatten_enchantment_component(item[cstart:cend])
        item = item[:cstart] + comps + item[cend:]
        changed += n
    # id: bare -> namespaced string
    kstart, vstart = _find_key(item, "id")
    if kstart >= 0:
        end = _value_end(item, vstart)
        raw = item[vstart:end].strip()
        if not raw.startswith('"'):
            item = item[:vstart] + '"minecraft:%s"' % raw + item[end:]
            changed += 1
    return item, changed


def _value_end(text: str, start: int) -> int:
    """End index of a scalar SNBT value that begins at ``start``."""
    if text[start] == "{":
        return _match_bracket(text, start)
    if text[start] == "[":
        return _match_bracket(text, start)
    if text[start] == '"':
        i = start + 1
        while i < len(text):
            if text[i] == "\\":
                i += 2
                continue
            if text[i] == '"':
                return i + 1
            i += 1
        return len(text)
    i = start
    while i < len(text) and text[i] not in ",}]":
        i += 1
    return i


# --------------------------------------------------------------------------- #
# custom_model_data -> item_model (CE / vanilla-namespace conflict avoidance)
# --------------------------------------------------------------------------- #
def item_model_id_for_model(model_path: str) -> str:
    """``dnt:item/keys/end_castle_key`` -> ``dnt:keys/end_castle_key``.

    The ``minecraft:item_model`` component resolves to
    ``assets/<ns>/items/<path>.json``; dropping the redundant ``item/`` keeps the
    generated definition path tidy and namespaced under ``dnt``.
    """
    ns, _, path = model_path.partition(":")
    if path.startswith("item/"):
        path = path[len("item/"):]
    return "%s:%s" % (ns, path)


def build_custom_model_dispatch(archive: "object") -> Dict[str, Dict[int, str]]:
    """Map ``item id -> {custom_model_data int -> model path}`` from the
    ``assets/minecraft/items/*.json`` item model definitions in the source ZIP."""
    dispatch: Dict[str, Dict[int, str]] = {}
    for name in archive.by_prefix("assets/minecraft/items/"):
        if not name.endswith(".json"):
            continue
        item_id = "minecraft:" + name[len("assets/minecraft/items/"):-len(".json")]
        import json as _json

        data = _json.loads(archive.read(name).decode("utf-8"))
        model = data.get("model")
        table: Dict[int, str] = {}
        if isinstance(model, dict) and model.get("type") == "minecraft:range_dispatch":
            for entry in model.get("entries", []):
                thr = entry.get("threshold")
                mdl = entry.get("model", {}).get("model")
                if isinstance(thr, (int, float)) and isinstance(mdl, str):
                    table[int(thr)] = mdl
        if table:
            dispatch[item_id] = table
    return dispatch


def _cmd_float(components: Dict[str, Any]) -> Optional[int]:
    cmd = components.get("minecraft:custom_model_data")
    if isinstance(cmd, dict):
        floats = cmd.get("floats")
        if isinstance(floats, list) and floats:
            return int(floats[0])
    if isinstance(cmd, int):
        return int(cmd)
    return None


def rule_cmd_to_item_model(obj: Any, dispatch: Dict[str, Dict[int, str]], relpath: str, report: Report) -> None:
    """Add an explicit ``minecraft:item_model`` to DnT key stacks.

    The source pack overrode ``assets/minecraft/items/{trial_key,ominous_trial_key,
    filled_map}.json`` (a vanilla namespace) to dispatch key models on
    custom_model_data. That collides with vanilla/CE output, so instead every key
    stack points straight at a generated ``dnt`` item model. Base item behaviour
    is untouched, the original ``custom_model_data`` is retained verbatim, and
    loot/NBT stay consistent.
    """
    def visit(node: Dict[str, Any]) -> None:
        item_id = node.get("name")
        if not isinstance(item_id, str):
            return
        functions = node.get("modifier") or node.get("functions")
        if not isinstance(functions, list):
            return
        for fn in functions:
            if not isinstance(fn, dict) or fn.get("type") not in ("minecraft:set_components",):
                continue
            components = fn.get("components")
            if not isinstance(components, dict):
                continue
            value = _cmd_float(components)
            if value is None:
                continue
            model = dispatch.get(item_id, {}).get(value)
            if model is None:
                continue
            # Keep the original custom_model_data untouched (value fidelity) and
            # add an explicit item_model so the stack renders without any
            # minecraft-namespace item model override.
            components["minecraft:item_model"] = item_model_id_for_model(model)
            report.hit("ce.custom_model_data_to_item_model", relpath)

    for_each_dict(obj, visit)


def rule_cmd_to_item_model_nbt(root: Dict[str, Any], dispatch: Dict[str, Dict[int, str]], relpath: str, report: Report) -> int:
    """Same rewrite for item stacks embedded in structure block entities."""
    changed = 0
    for block in root.get("blocks", []):
        nbt = block.get("nbt")
        if not isinstance(nbt, dict):
            continue
        config = nbt.get("config")
        if not isinstance(config, dict):
            continue
        key_item = config.get("key_item")
        if not isinstance(key_item, dict):
            continue
        item_id = key_item.get("id")
        components = key_item.get("components")
        if not isinstance(item_id, str) or not isinstance(components, dict):
            continue
        value = _cmd_float(components)
        if value is None:
            continue
        model = dispatch.get(item_id, {}).get(value)
        if model is None:
            continue
        components["minecraft:item_model"] = item_model_id_for_model(model)
        changed += 1
    if changed:
        report.hit("ce.custom_model_data_to_item_model_nbt", relpath, changed)
    return changed


#: Keys whose *value* is an ``EntityPredicate`` in 26.3 but was a plain field in
#: the 1.21.x payloads (``DamagePredicate.sourceEntity``/``...``).
_ENTITY_PREDICATE_FIELDS = ("source_entity", "direct_entity")


def rule_entity_predicate_subtypes(obj: Any, relpath: str, report: Report) -> None:
    """Rename an entity predicate's ``type`` -> ``minecraft:entity_type``.

    26.3 ``EntityPredicate.CODEC`` is a ``dispatchedMap`` over sub-predicate names
    (``EntitySubPredicates``). The old inline key ``type`` parses as the
    unregistered ``minecraft:type`` and fails decoding.

    Two exact shapes carry an ``EntityPredicate`` (nothing else is matched, so
    loot ``type`` discriminators cannot be touched):
    * wrapped: a value of ``predicate`` whose owner also has a string ``entity``
      (i.e. ``entity_properties`` conditions / enchantment requirements);
    * bare: a value of ``source_entity`` / ``direct_entity``
      (``DamagePredicate.sourceEntity``, i.e.
      ``advancement.criteria.*.conditions.damage.source_entity``).
    """
    def rename(node: Dict[str, Any]) -> None:
        if isinstance(node.get("type"), str):
            node["minecraft:entity_type"] = node.pop("type")
            report.hit("entity_predicate.type_to_entity_type", relpath)

    def walk(node: Any, parent_key: Optional[str], parent: Any) -> None:
        if isinstance(node, dict):
            wrapped = parent_key == "predicate" and isinstance(parent, dict) and isinstance(parent.get("entity"), str)
            if (wrapped or parent_key in _ENTITY_PREDICATE_FIELDS) and isinstance(node, dict):
                rename(node)
            for key, value in list(node.items()):
                walk(value, key, node)
        elif isinstance(node, list):
            for item in node:
                walk(item, None, node)

    walk(obj, None, None)


def rule_entity_hurt_player_condition(obj: Any, relpath: str, report: Report) -> None:
    """Wrap a legacy bare ``player`` entity predicate as a loot condition.

    26.3 ``EntityHurtPlayerTrigger.TriggerInstance.player`` is an
    ``Optional<Holder<LootItemCondition>>``, so the old ``player`` EntityPredicate
    must become an ``entity_properties`` condition.
    """
    criteria = obj.get("criteria")
    if not isinstance(criteria, dict):
        return
    for criterion in criteria.values():
        if not isinstance(criterion, dict) or criterion.get("trigger") != "minecraft:entity_hurt_player":
            continue
        conditions = criterion.get("conditions")
        if not isinstance(conditions, dict):
            continue
        player = conditions.get("player")
        if isinstance(player, dict) and "type" not in player and "condition" not in player:
            conditions["player"] = {
                "type": "minecraft:entity_properties",
                "entity": "this",
                "predicate": player,
            }
            report.hit("advancement.hurt_player_wrapped_as_condition", relpath)
