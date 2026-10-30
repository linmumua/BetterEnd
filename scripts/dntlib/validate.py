"""Reference-closure and schema validation for the *produced* DnT pack.

The migration is only useful if the output is self-consistent: every
``nova_structures:`` / ``dnt:`` reference must resolve inside the generated pack,
and the 26.3 discriminators must be the ones the server actually reads. This
module is deliberately strict about pack-owned ids and explicit (never silent)
about anything external.
"""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Set, Tuple

from dntlib import migrate
from dntlib import structure_ids

# category -> file suffix
FILE_CATEGORIES = {
    "loot_table": ".json",
    "item_modifier": ".json",
    "function": ".mcfunction",
    "enchantment": ".json",
    "advancement": ".json",
    "structure": ".nbt",
    "structure_set": ".json",
    "template_pool": ".json",
    "processor_list": ".json",
    "trial_spawner": ".json",
    "recipe": ".json",
    "predicate": ".json",
}


class Problem:
    def __init__(self, kind: str, detail: str) -> None:
        self.kind = kind
        self.detail = detail

    def __repr__(self) -> str:  # pragma: no cover - debug aid
        return "%s: %s" % (self.kind, self.detail)


#: Namespaces whose references must resolve inside the generated pack. ``mumu`` is
#: this project's own publishing namespace for the two DnT structures; leaving it
#: out would silently stop checking exactly the ids the rename created.
PACK_NAMESPACES = ("nova_structures:", "mumu:")


def index_pack_ids(files: Iterable[str]) -> Dict[str, Set[str]]:
    """Map ``category -> {namespace:path}`` for every data file in the pack.

    ``worldgen`` is a *group* of registries, not one: ``Registries.FEATURE`` is
    ``worldgen/feature`` and ``Registries.PLACED_FEATURE`` is
    ``worldgen/placed_feature`` (``FileToIdConverter.registry`` uses the registry
    path verbatim as the folder). Such files are indexed under ``worldgen/<sub>``
    so a placed feature's ``feature`` reference and a pool element's ``feature``
    reference resolve against the right registry instead of collapsing into a
    single ``worldgen`` bucket.
    """
    index: Dict[str, Set[str]] = {}
    for path in files:
        parts = path.split("/")
        if len(parts) < 4 or parts[0] != "data":
            continue
        namespace, category = parts[1], parts[2]
        if category == "tags":
            if len(parts) >= 5:
                tag_type = parts[3]
                tail = "/".join(parts[4:])
                index.setdefault("tags/%s" % tag_type, set()).add(_strip_ext("%s:%s" % (namespace, tail)))
            continue
        if category == "worldgen" and len(parts) >= 5:
            category = "worldgen/%s" % parts[3]
            tail = "/".join(parts[4:])
        else:
            tail = "/".join(parts[3:])
        index.setdefault(category, set()).add(_strip_ext("%s:%s" % (namespace, tail)))
    return index


def _strip_ext(rid: str) -> str:
    for suffix in (".json", ".mcfunction", ".nbt", ".snbt"):
        if rid.endswith(suffix):
            return rid[: -len(suffix)]
    return rid


def _holder_set_structures(value: Any):
    """Yield the structure ids a ``HolderSet<Structure>`` JSON value names directly.

    ``HolderSetCodec`` decodes ``either(TagKey.hashedCodec, compactListCodec)``: the
    direct branch is a single id or a list of ids, and the tag branch is a
    ``#``-prefixed string. Only the direct branch names structure ids, so a tag is
    skipped rather than reported as a dangling structure.
    """
    if isinstance(value, str):
        if not value.startswith("#"):
            yield "worldgen/structure", value
    elif isinstance(value, list):
        for entry in value:
            if isinstance(entry, str) and not entry.startswith("#"):
                yield "worldgen/structure", entry


def _iter_refs(obj: Any):
    """Yield ``(kind, id)`` reference pairs found in a data JSON document.

    Four worldgen chains are covered, each detected narrowly (never by guessing a
    field's meaning):

    * a *placed feature* document (it carries ``feature`` **and** ``placement``)
      references a configured feature -> ``worldgen/feature``;
    * a ``minecraft:feature_pool_element`` references a placed feature ->
      ``worldgen/placed_feature``;
    * a ``structure_set`` entry references a structure -> ``worldgen/structure``;
    * the two ``HolderSet<Structure>`` fields this pack carries do too: an
      advancement's ``location.structures`` and an ``exploration_map`` loot
      function's ``destination``. Both come from
      ``RegistryCodecs.holderSet(Registries.STRUCTURE)``, whose codec is
      ``either(TagKey.hashedCodec, compactListCodec)`` -- so a plain id string is a
      *direct* holder (one structure), a list is several, and only a ``#``-prefixed
      string is a tag. A tag is not a structure id and is skipped.
    """
    if isinstance(obj, dict):
        t = obj.get("type")
        if t == "minecraft:item" and isinstance(obj.get("name"), str):
            yield "item", obj["name"]
        if t == "minecraft:loot_table" and isinstance(obj.get("value"), str):
            yield "loot_table", obj["value"]
        if t in ("minecraft:set_loot_table",) and isinstance(obj.get("name"), str):
            yield "loot_table", obj["name"]
        if t in ("minecraft:run_function",) and isinstance(obj.get("function"), str):
            yield "function", obj["function"]
        if t == "minecraft:reference" and isinstance(obj.get("name"), str):
            yield "item_modifier", obj["name"]
        if isinstance(obj.get("feature"), str) and "placement" in obj:
            yield "worldgen/feature", obj["feature"]
        if obj.get("element_type") == "minecraft:feature_pool_element" and isinstance(obj.get("feature"), str):
            yield "worldgen/placed_feature", obj["feature"]
        # A structure_set entry: {"structure": <id>, "weight": <int>}.
        entries = obj.get("structures")
        if isinstance(entries, list):
            for entry in entries:
                if isinstance(entry, dict) and isinstance(entry.get("structure"), str) and "weight" in entry:
                    yield "worldgen/structure", entry["structure"]
        # LocationPredicate.structures (advancements): one direct id or a HolderSet list.
        location = obj.get("location")
        if isinstance(location, dict):
            yield from _holder_set_structures(location.get("structures"))
        # ExplorationMapFunction.destination: same HolderSet<Structure> shape.
        if t == "minecraft:exploration_map":
            yield from _holder_set_structures(obj.get("destination"))
        for value in obj.values():
            yield from _iter_refs(value)
    elif isinstance(obj, list):
        for item in obj:
            yield from _iter_refs(item)


def _is_structure_tag_path(relpath: str) -> bool:
    """Whether ``relpath`` is a ``tags/worldgen/structure`` document."""
    parts = relpath.split("/")
    return (len(parts) >= 6 and parts[0] == "data" and parts[2] == "tags"
            and parts[3] == "worldgen" and parts[4] == "structure")


def iter_registry_refs(relpath: str, obj: Any):
    """``_iter_refs`` plus the references that only a *path* can identify.

    A structure tag's ``values`` are structure ids, but the document itself carries
    no field saying so -- the registry the tag belongs to is in the path. Handled
    here rather than in :func:`_iter_refs` so the type of a reference never gets
    guessed from its value.
    """
    yield from _iter_refs(obj)
    if _is_structure_tag_path(relpath) and isinstance(obj, dict):
        values = obj.get("values")
        if isinstance(values, list):
            for value in values:
                if isinstance(value, dict):
                    value = value.get("id")
                if isinstance(value, str) and not value.startswith("#"):
                    yield "worldgen/structure", value


def _iter_tag_refs(obj: Any):
    if isinstance(obj, dict):
        for key, value in obj.items():
            if isinstance(value, str) and value.startswith("#"):
                yield "tag", value[1:]
            yield from _iter_tag_refs(value)
    elif isinstance(obj, list):
        for item in obj:
            yield from _iter_tag_refs(item)


def check_internal_closure(pack_files: List[str], data_docs: Dict[str, Any]) -> List[Problem]:
    """Every pack-owned reference must resolve inside the pack."""
    index = index_pack_ids(pack_files)
    problems: List[Problem] = []
    for path, doc in data_docs.items():
        for kind, rid in iter_registry_refs(path, doc):
            if not rid.startswith(PACK_NAMESPACES):
                continue
            if rid not in index.get(kind, set()):
                problems.append(Problem("dangling-" + kind, "%s -> %s" % (path, rid)))
    return problems


def check_public_structure_layout(pack_files: List[str], data_docs: Dict[str, Any]) -> List[Problem]:
    """The two republished structures must exist twice, and only be placed once.

    A structure id is a registry key, and the registry's contents come from the file
    paths, so this is a *layout* check rather than a reference check:

    * the ``mumu:`` definition of each public structure must be shipped (it is the
      one placed), and the author's definition must still be shipped as the
      read-only legacy entry an old world's chunk data resolves against -- unless
      the pack publishes ``mumu:`` only, in which case that entry must be *gone*
      (see :data:`structure_ids.KEEPS_LEGACY_DEFINITION`);
    * no legacy id may remain in any *structure-reference* position -- a
      ``structure_set`` entry, a structure tag's ``values``, an advancement's
      ``location.structures`` or an ``exploration_map.destination``. A leftover
      legacy placement would generate a second castle on top of the ``mumu:`` one;
      a leftover legacy map/eye/advancement reference would point at a structure
      that never generates, so the map would locate nothing;
    * each ``mumu:`` structure must be placed exactly once, so a duplicated entry
      cannot double the density either.
    """
    problems: List[Problem] = []
    shipped = set(pack_files)
    for name in migrate.PUBLIC_STRUCTURES:
        legacy = "data/nova_structures/worldgen/structure/%s.json" % name
        alias = "data/%s/worldgen/structure/%s.json" % (
            migrate.PUBLIC_STRUCTURE_NAMESPACE, name)
        if alias not in shipped:
            problems.append(Problem("missing-public-structure", alias))
        if structure_ids.keeps_legacy_definition(structure_ids.DNT_LEGACY_NAMESPACE):
            if legacy not in shipped:
                problems.append(Problem("missing-legacy-structure", legacy))
        elif legacy in shipped:
            problems.append(Problem("stray-legacy-structure", legacy))
        stale = "data/nova_structures/worldgen/structure_set/%s.json" % name
        if stale in shipped:
            problems.append(Problem("legacy-structure-placement", stale))

    placed: Dict[str, int] = {}
    legacy_refs: Dict[str, int] = {}
    legacy_ids = {"nova_structures:%s" % name for name in migrate.PUBLIC_STRUCTURES}
    for path, doc in data_docs.items():
        parts = path.split("/")
        is_structure_set = (len(parts) >= 5 and parts[0] == "data"
                            and parts[2] == "worldgen" and parts[3] == "structure_set")
        for kind, rid in iter_registry_refs(path, doc):
            if kind != "worldgen/structure":
                continue
            if rid in legacy_ids:
                legacy_refs[rid] = legacy_refs.get(rid, 0) + 1
            if is_structure_set:
                placed[rid] = placed.get(rid, 0) + 1
    for name in migrate.PUBLIC_STRUCTURES:
        rid = migrate.public_structure_rid(name)
        count = placed.get(rid, 0)
        if count == 0:
            problems.append(Problem("unplaced-public-structure", rid))
        elif count > 1:
            problems.append(Problem("duplicated-public-structure", "%s x%d" % (rid, count)))
        legacy_rid = "nova_structures:%s" % name
        if legacy_refs.get(legacy_rid, 0):
            problems.append(Problem(
                "legacy-structure-ref",
                "%s x%d（旧 id 仍被引用：旧 definition 必须留在注册表里，但不许再出现在"
                " structure_set / 结构 tag / advancement 中，否则地图、末影之眼或成就指向"
                " 一座永不生成的结构，或与 %s 在同一格各生成一座）"
                % (legacy_rid, legacy_refs[legacy_rid], rid)))
    return problems


def _category(path: str) -> str:
    """The data-pack category of a ``data/<ns>/<category>/...`` path, else ``""``."""
    parts = path.split("/")
    return parts[2] if len(parts) >= 3 and parts[0] == "data" else ""


def check_loot_discriminators(pack_files: List[str], data_docs: Dict[str, Any]) -> List[Problem]:
    """No legacy ``functions``/``function`` discriminator may survive in loot data.

    Both keys are legal only where 26.3 actually reads them: ``SequenceFunction``'s
    payload field is ``functions`` and a run_function's payload field is
    ``function``. Anywhere else they are the 1.21.x discriminator the 26.3 codec no
    longer knows.
    """
    problems: List[Problem] = []
    for path, doc in data_docs.items():
        if _category(path) not in ("loot_table", "item_modifier"):
            continue
        for node in migrate.iter_dicts(doc):
            name = migrate.discriminator_name(node)
            if "functions" in node and name not in migrate.SEQUENCE_FUNCTION_TYPES:
                problems.append(Problem("legacy-loot-key", "%s -> functions" % path))
            if "function" in node and name not in migrate.RUN_FUNCTION_TYPES:
                problems.append(Problem("legacy-loot-key", "%s -> function" % path))
    return problems


def check_number_provider_types(pack_files: List[str], data_docs: Dict[str, Any]) -> List[Problem]:
    """A dispatched ``{min, max}`` number provider in loot data must carry ``type``.

    Same codec-backed scope as :func:`dntlib.migrate.iter_number_provider_sites`,
    so a predicate like ``time_check.value`` is never reported.
    """
    problems: List[Problem] = []
    for path, doc in data_docs.items():
        if _category(path) not in ("loot_table", "item_modifier"):
            continue
        for value in migrate.iter_number_provider_sites(doc):
            if migrate.looks_like_untyped_uniform(value):
                problems.append(Problem("number-provider-missing-type", path))
    return problems


def check_no_map_color_component(pack_files: List[str], data_docs: Dict[str, Any]) -> List[Problem]:
    """The retired ``map_color`` component must not survive in set_components payloads.

    Only the ``components`` object of a ``set_components`` function is inspected, so
    unrelated ``map_color`` strings are never flagged.
    """
    problems: List[Problem] = []
    for path, doc in data_docs.items():
        if _category(path) not in ("loot_table", "item_modifier"):
            continue
        for components in migrate.iter_set_components_payloads(doc):
            for key in migrate.MAP_COLOR_COMPONENT_KEYS:
                if key in components:
                    problems.append(Problem("retired-map-color-component", "%s -> %s" % (path, key)))
    return problems


def _iter_dicts(obj: Any):
    """Yield every dict node of a document (depth-first)."""
    if isinstance(obj, dict):
        yield obj
        for value in obj.values():
            yield from _iter_dicts(value)
    elif isinstance(obj, list):
        for item in obj:
            yield from _iter_dicts(item)


def check_no_legacy_configured_feature(pack_files: List[str]) -> List[Problem]:
    """A generated pack must not ship the legacy ``worldgen/configured_feature``
    folder: 26.3 ``Registries.FEATURE`` is ``worldgen/feature``."""
    problems: List[Problem] = []
    for path in pack_files:
        if path.endswith(".json") and "/worldgen/configured_feature/" in path:
            problems.append(Problem("legacy-worldgen-path", path))
    return problems


def check_time_check_clock(pack_files: List[str], data_docs: Dict[str, Any]) -> List[Problem]:
    """Every ``minecraft:time_check`` condition in the output needs a ``clock``.

    26.3 ``TimeCheck.MAP_CODEC`` reads ``WorldClock.CODEC.fieldOf("clock")``; a
    missing clock makes the whole condition fail to decode.
    """
    problems: List[Problem] = []
    for path, doc in data_docs.items():
        for node in _iter_dicts(doc):
            is_time_check = node.get("type") == "minecraft:time_check" or node.get("condition") == "minecraft:time_check"
            if is_time_check and "clock" not in node:
                problems.append(Problem("time_check-missing-clock", path))
    return problems


def check_pack_mcmeta(mcmeta: Dict[str, Any], expected_major: int) -> List[Problem]:
    problems: List[Problem] = []
    pack = mcmeta.get("pack")
    if not isinstance(pack, dict):
        return [Problem("pack.mcmeta", "missing 'pack' object")]
    if "supported_formats" in pack:
        problems.append(Problem("pack.mcmeta", "'supported_formats' must be absent above the pre-minor format"))
    minf = pack.get("min_format")
    maxf = pack.get("max_format")
    if minf is None or maxf is None:
        problems.append(Problem("pack.mcmeta", "min_format/max_format required"))
    else:
        major = minf[0] if isinstance(minf, list) else minf
        if major != expected_major:
            problems.append(Problem("pack.mcmeta", "min_format major %r != %d" % (major, expected_major)))
    return problems


def check_reference_targets_empty(pack_files: List[str], data_docs: Dict[str, Any]) -> List[Problem]:
    """Placeholder kept intentionally empty.

    The "referenced modifier must be empty" assertion is enforced during
    inlining (see :func:`dntlib.migrate.inline_reference_functions`), because
    after inlining the reference is gone and the target could not be recovered
    from the produced files.
    """
    return []
