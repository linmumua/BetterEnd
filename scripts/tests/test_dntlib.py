"""Unit tests for the DnT migration toolkit.

Run with:
    python -m unittest discover -s scripts/tests -v
or:
    PYTHONPATH=scripts python -m unittest tests.test_dntlib -v
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dntlib import migrate, nbt, safezip, validate  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCE_ZIP = os.path.join(REPO_ROOT, "DnT End Castle Standalone v1.3.4.zip")
SOURCE_SHA = "22d0040e162688d750bbfb122026f9881df42bd5e26f63cb0b5b1f6047c07e98"


def make_zip(entries, path):
    with zipfile.ZipFile(path, "w") as zf:
        for name, data in entries:
            info = zipfile.ZipInfo(name)
            zf.writestr(info, data)
    return path


def find_nodes(obj, predicate):
    """Every dict node of ``obj`` (depth-first) for which ``predicate`` is true."""
    found = []
    stack = [obj]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if predicate(node):
                found.append(node)
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)
    return found


def find_time_check(obj):
    nodes = find_nodes(obj, lambda n: n.get("type") == "minecraft:time_check")
    if len(nodes) != 1:
        raise AssertionError("expected exactly one time_check node, got %d" % len(nodes))
    return nodes[0]


class TestNbt(unittest.TestCase):
    def test_roundtrip_preserves_tag_types(self):
        root = {
            "DataVersion": 3837,
            "byte": nbt.Byte(7),
            "short": nbt.Short(9),
            "long": nbt.Long(1 << 40),
            "float": nbt.Float(1.5),
            "double": 2.5,
            "bytes": nbt.ByteArray(b"\x01\x02"),
            "ints": nbt.IntArray([1, 2, 3]),
            "longs": nbt.LongArray([4, 5]),
            "str": "héllo",
            "empty": nbt.ListTag([], nbt.TAG_COMPOUND),
            "nested": [{"a": 1}, {"a": 2}],
        }
        raw = nbt.dump_bytes("", root)
        _, back = nbt.load_bytes(raw)
        self.assertEqual(repr(root), repr(back))
        self.assertIsInstance(back["byte"], nbt.Byte)
        self.assertIsInstance(back["float"], nbt.Float)
        self.assertEqual(back["empty"].elem_id, nbt.TAG_COMPOUND)

    def test_gzip_detected(self):
        raw = nbt.dump_bytes("", {"x": 1}, compress=True)
        self.assertEqual(raw[:2], b"\x1f\x8b")
        _, root = nbt.load_bytes(raw)
        self.assertEqual(root["x"], 1)

    def test_rejects_non_compound_root(self):
        with self.assertRaises(nbt.NbtError):
            nbt.load_bytes(b"\x03\x00\x00")


class TestSafeZip(unittest.TestCase):
    def _zip(self, entries):
        fd, path = tempfile.mkstemp(suffix=".zip")
        os.close(fd)
        make_zip(entries, path)
        self.addCleanup(os.remove, path)
        return path

    def test_accepts_clean_archive_and_hashes(self):
        path = self._zip([("a/b.json", b"{}"), ("c.txt", b"hi")])
        arc = safezip.ZipArchive(path)
        self.assertEqual(arc.names, ["a/b.json", "c.txt"])
        self.assertEqual(len(arc.manifest()), 2)
        self.assertEqual(arc.sha256, safezip.sha256_file(path))

    def test_rejects_path_traversal(self):
        path = self._zip([("../evil", b"x")])
        with self.assertRaises(safezip.ZipSafetyError):
            safezip.ZipArchive(path)

    def test_rejects_absolute_paths(self):
        for bad in ("/etc/passwd", "C:/win"):
            path = self._zip([(bad, b"x")])
            with self.assertRaises(safezip.ZipSafetyError):
                safezip.ZipArchive(path)

    def test_rejects_backslash_names(self):
        # zipfile rewrites os.sep on Windows, so assert the normaliser directly.
        with self.assertRaises(safezip.ZipSafetyError):
            safezip.normalize_entry_name("a\\b")

    def test_rejects_duplicate_and_case_collision(self):
        for pair in ([("a.json", b"1"), ("a.json", b"2")], [("A.json", b"1"), ("a.json", b"2")]):
            path = self._zip(pair)
            with self.assertRaises(safezip.ZipSafetyError):
                safezip.ZipArchive(path)

    def test_rejects_oversize(self):
        path = self._zip([("big", b"x" * 4096)])
        with self.assertRaises(safezip.ZipSafetyError):
            safezip.ZipArchive(path, max_entry_bytes=1024)

    def test_rejects_wrong_sha256(self):
        path = self._zip([("a", b"x")])
        with self.assertRaises(safezip.ZipSafetyError):
            safezip.ZipArchive(path, expected_sha256="0" * 64)

    def test_directories_are_skipped(self):
        path = self._zip([("dir/", b""), ("dir/a.json", b"{}")])
        arc = safezip.ZipArchive(path)
        self.assertEqual(arc.names, ["dir/a.json"])


class TestLootRules(unittest.TestCase):
    def test_functions_to_modifier_and_function_to_type(self):
        doc = {
            "pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:item", "name": "minecraft:stick", "functions": [
                    {"function": "minecraft:set_count", "count": 3}]}
            ]}],
            "functions": [{"function": "minecraft:set_damage", "damage": 0.5}],
        }
        report = migrate.Report()
        migrate.rule_loot_discriminators(doc, "p", report)
        self.assertNotIn("functions", doc)
        self.assertIn("modifier", doc)
        self.assertEqual(doc["pools"][0]["entries"][0]["modifier"][0]["type"], "minecraft:set_count")
        self.assertEqual(doc["modifier"][0]["type"], "minecraft:set_damage")
        self.assertEqual(report.counts["loot.functions_to_modifier"], 2)

    def test_condition_discriminator_to_type(self):
        doc = {"requirements": {"condition": "minecraft:entity_properties", "entity": "this"}}
        report = migrate.Report()
        migrate.rule_condition_discriminator(doc, "p", report)
        self.assertEqual(doc["requirements"]["type"], "minecraft:entity_properties")
        self.assertNotIn("condition", doc["requirements"])

    def test_condition_discriminator_leaves_holder_refs_alone(self):
        doc = {"condition": {"type": "minecraft:random_chance", "chance": 0.1}}
        migrate.rule_condition_discriminator(doc, "p", migrate.Report())
        self.assertIn("condition", doc)

    def test_spawn_override_count(self):
        doc = {"spawn_overrides": {"monster": {"spawns": [
            {"type": "minecraft:phantom", "minCount": 4, "maxCount": 4, "weight": 100},
            {"type": "minecraft:enderman", "minCount": 1, "maxCount": 4, "weight": 200}]}}}
        report = migrate.Report()
        migrate.rule_spawn_overrides_count(doc, "p", report)
        spawns = doc["spawn_overrides"]["monster"]["spawns"]
        self.assertEqual(spawns[0]["count"], 4)
        self.assertEqual(spawns[1]["count"], {"type": "minecraft:uniform", "min_inclusive": 1, "max_inclusive": 4})
        self.assertNotIn("minCount", spawns[0])

    def test_advancement_predicate_list_to_single(self):
        doc = {"criteria": {"c": {"conditions": {"player": [{"condition": "minecraft:entity_properties"}]}}}}
        report = migrate.Report()
        migrate.rule_advancement_predicates(doc, "p", report)
        self.assertEqual(doc["criteria"]["c"]["conditions"]["player"], {"condition": "minecraft:entity_properties"})
        self.assertEqual(report.counts["advancement.predicate_list_to_single"], 1)

    def test_advancement_predicate_list_collapses_multi(self):
        doc = {"criteria": {"c": {"conditions": {"player": [{"a": 1}, {"b": 2}]}}}}
        migrate.rule_advancement_predicates(doc, "p", migrate.Report())
        self.assertEqual(doc["criteria"]["c"]["conditions"]["player"]["type"], "minecraft:all_of")

    def test_entity_predicate_type_to_entity_type(self):
        doc = {"entity": "this", "predicate": {"type": "#minecraft:arrows"}, "type": "minecraft:entity_properties"}
        report = migrate.Report()
        migrate.rule_entity_predicate_subtypes(doc, "p", report)
        self.assertEqual(doc["predicate"]["minecraft:entity_type"], "#minecraft:arrows")
        self.assertNotIn("type", doc["predicate"])

    def test_pack_mcmeta_format(self):
        mc = migrate.rule_pack_mcmeta("x")
        self.assertEqual(mc["pack"]["min_format"], [121, 0])
        self.assertEqual(mc["pack"]["max_format"], [121, 0])
        self.assertNotIn("supported_formats", mc["pack"])


class TestFunctionMigration(unittest.TestCase):
    SRC = ('execute as @s run summon minecraft:cave_spider ~ ~ ~ '
           '{HandItems:[{id:stick,Count:1,components:{"minecraft:enchantments":'
           '{"levels":{"nova_structures:boss_behaviour":1}}}}],Health:16f,'
           'attributes:[{id:max_health,base:16}],Tags:["x"]}')

    def test_hand_items_to_equipment(self):
        out, changed = migrate.migrate_hand_items(self.SRC)
        self.assertEqual(changed, 4)
        self.assertIn('equipment:{mainhand:{id:"minecraft:stick",count:1,', out)
        self.assertNotIn("HandItems", out)
        self.assertIn('"minecraft:enchantments":{"nova_structures:boss_behaviour":1}', out)
        self.assertNotIn('"levels"', out)
        self.assertIn("Health:16f", out)
        self.assertIn("attributes:[{id:max_health,base:16}]", out)

    def test_no_hand_items_is_noop(self):
        text = "say hello"
        out, changed = migrate.migrate_hand_items(text)
        self.assertEqual((out, changed), (text, 0))

    def test_match_bracket_handles_strings_and_nesting(self):
        text = '{a:"}", b:{c:1}}'
        self.assertEqual(migrate._match_bracket(text, 0), len(text))


class TestReferenceInlining(unittest.TestCase):
    def test_empty_modifier_is_removed(self):
        # runs after rule_loot_discriminators, so the discriminator is already "type"
        doc = {"pools": [{"modifier": [{"type": "minecraft:reference", "name": "ns:m"}]}]}
        resolver = lambda rid: []  # noqa: E731
        report = migrate.Report()
        out, missing, non_empty = migrate.inline_reference_functions(doc, resolver, "p", report)
        self.assertEqual(missing, [])
        self.assertEqual(non_empty, [])
        self.assertEqual(out["pools"][0]["modifier"], [])
        self.assertEqual(report.counts["loot.reference_inlined"], 1)

    def test_nonempty_target_is_reported(self):
        doc = [{"type": "minecraft:reference", "name": "ns:m"}]
        out, missing, non_empty = migrate.inline_reference_functions(
            doc, lambda rid: [{"function": "minecraft:set_count"}], "p", migrate.Report())
        self.assertEqual(non_empty, ["ns:m"])
        self.assertEqual(len(out), 1)

    def test_missing_target_is_reported(self):
        doc = [{"type": "minecraft:reference", "name": "ns:absent"}]
        out, missing, non_empty = migrate.inline_reference_functions(doc, lambda rid: None, "p", migrate.Report())
        self.assertEqual(missing, ["ns:absent"])
        self.assertEqual(out[0]["type"], "minecraft:reference")

    def test_bare_entity_predicate_under_source_entity(self):
        doc = {"damage": {"source_entity": {"type": "minecraft:phantom", "nbt": "{Tags:[\"x\"]}"}}}
        report = migrate.Report()
        migrate.rule_entity_predicate_subtypes(doc, "p", report)
        self.assertEqual(doc["damage"]["source_entity"]["minecraft:entity_type"], "minecraft:phantom")
        self.assertNotIn("type", doc["damage"]["source_entity"])

    def test_hurt_player_bare_predicate_wrapped_as_condition(self):
        doc = {"criteria": {"a": {"trigger": "minecraft:entity_hurt_player", "conditions": {
            "player": {"effects": {"minecraft:levitation": {}}}}}}}
        report = migrate.Report()
        migrate.rule_entity_hurt_player_condition(doc, "p", report)
        player = doc["criteria"]["a"]["conditions"]["player"]
        self.assertEqual(player["type"], "minecraft:entity_properties")
        self.assertEqual(player["entity"], "this")
        self.assertIn("minecraft:levitation", player["predicate"]["effects"])

    def test_hurt_player_rule_ignores_other_triggers(self):
        doc = {"criteria": {"a": {"trigger": "minecraft:location", "conditions": {"player": {"effects": {}}}}}}
        migrate.rule_entity_hurt_player_condition(doc, "p", migrate.Report())
        self.assertNotIn("type", doc["criteria"]["a"]["conditions"]["player"])

    def test_loot_type_discriminator_is_never_touched(self):
        # a loot function dict carries "type" as a discriminator; it must survive
        doc = {"modifier": [{"type": "minecraft:set_count", "count": {"min": 1, "max": 3}}]}
        migrate.rule_entity_predicate_subtypes(doc, "p", migrate.Report())
        self.assertEqual(doc["modifier"][0]["type"], "minecraft:set_count")
        self.assertNotIn("minecraft:entity_type", doc["modifier"][0])


class TestWorldgenMigration(unittest.TestCase):
    def test_relocate_configured_feature_to_feature(self):
        self.assertEqual(
            migrate.relocate_worldgen("data/nova_structures/worldgen/configured_feature/chorus_plant.json"),
            "data/nova_structures/worldgen/feature/chorus_plant.json")

    def test_relocate_leaves_every_other_path_alone(self):
        for path in (
            "data/nova_structures/worldgen/placed_feature/chorus_plant.json",
            "data/nova_structures/worldgen/template_pool/end_castle/chorus_plant.json",
            "data/nova_structures/loot_table/a.json",
            "assets/dnt/lang/en_us.json",
        ):
            self.assertEqual(migrate.relocate_worldgen(path), path)

    def test_assign_output_paths_rejects_collision(self):
        with self.assertRaises(ValueError):
            migrate.assign_output_paths([
                "data/ns/worldgen/configured_feature/x.json",
                "data/ns/worldgen/feature/x.json",
            ])

    def test_assign_output_paths_maps_without_collision(self):
        mapping = migrate.assign_output_paths([
            "data/ns/worldgen/configured_feature/x.json",
            "data/ns/worldgen/placed_feature/x.json",
        ])
        self.assertEqual(mapping["data/ns/worldgen/configured_feature/x.json"],
                         "data/ns/worldgen/feature/x.json")


class TestEndCastlePlacement(unittest.TestCase):
    """The one balance change this project makes to DnT's own worldgen.

    It is a migration rule rather than a runtime override so it lands in the DnT pack at build time
    with a visible count in the migration report, instead of depending on which pack the server
    resolves last.
    """

    # 城堡的 structure_set 已被发布到 mumu: 命名空间，所以这里必须是*最终产物*路径：
    # 规则按路径精确匹配，写成源路径会让调稀静默失效（这正是下面那条回归测试的意义）。
    CASTLE = "data/mumu/worldgen/structure_set/end_castle.json"

    @staticmethod
    def castle(count=219, distance=32, spread=3, salt=0):
        return {
            "placement": {
                "type": "minecraft:concentric_rings",
                "count": count,
                "distance": distance,
                "preferred_biomes": "#nova_structures:collections/end_voids",
                "salt": salt,
                "spread": spread,
            },
            "structures": [{"structure": "mumu:end_castle", "weight": 1}],
        }

    def test_thins_the_castle_and_reports_once(self):
        doc = self.castle()
        report = migrate.Report()
        migrate.rule_end_castle_placement(doc, self.CASTLE, report)
        placement = doc["placement"]
        self.assertEqual(placement["count"], 112)
        self.assertEqual(placement["distance"], 48)
        self.assertEqual(report.counts["structure.end_castle_placement"], 1)
        self.assertEqual(report.files["structure.end_castle_placement"], [self.CASTLE])

    def test_leaves_the_authors_other_keys_alone(self):
        doc = self.castle(salt=7, spread=5)
        migrate.rule_end_castle_placement(doc, self.CASTLE, migrate.Report())
        placement = doc["placement"]
        self.assertEqual(placement["spread"], 5, "spread is the author's")
        self.assertEqual(placement["salt"], 7, "salt is the author's")
        self.assertEqual(placement["preferred_biomes"], "#nova_structures:collections/end_voids")
        self.assertEqual(placement["type"], "minecraft:concentric_rings")
        self.assertEqual(doc["structures"], [{"structure": "mumu:end_castle", "weight": 1}])

    def test_is_idempotent(self):
        doc = self.castle(count=112, distance=48)
        report = migrate.Report()
        migrate.rule_end_castle_placement(doc, self.CASTLE, report)
        self.assertEqual(doc, self.castle(count=112, distance=48), "an already-migrated file must not move")
        self.assertEqual(sum(report.counts.values()), 0, "re-running must not count again")

    def test_never_touches_the_end_ship_or_any_other_structure_set(self):
        # end_ship is a random_spread placement that is dense enough already, and it is the file most
        # likely to be caught by a rule scoped to "structure_set" instead of to one path.
        ship = {
            "placement": {
                "type": "minecraft:random_spread",
                "salt": 79584390,
                "frequency": 0.25,
                "separation": 10,
                "spacing": 40,
                "spread_type": "triangular",
            },
            "structures": [{"structure": "mumu:end_ship", "weight": 1}],
        }
        original = json.loads(json.dumps(ship))
        report = migrate.Report()
        migrate.rule_end_castle_placement(
            ship, "data/mumu/worldgen/structure_set/end_ship.json", report)
        self.assertEqual(ship, original)
        self.assertEqual(sum(report.counts.values()), 0)

    def test_a_document_without_a_placement_does_not_crash(self):
        for doc in ({}, {"placement": None}, {"placement": []}, {"structures": []}):
            report = migrate.Report()
            migrate.rule_end_castle_placement(doc, self.CASTLE, report)
            self.assertEqual(sum(report.counts.values()), 0)

    def test_a_missing_key_is_not_invented(self):
        # concentric_rings requires count, so a source that lost it is malformed; the rule reports
        # nothing rather than papering over it with a value the author never wrote.
        doc = self.castle()
        del doc["placement"]["count"]
        report = migrate.Report()
        migrate.rule_end_castle_placement(doc, self.CASTLE, report)
        self.assertNotIn("count", doc["placement"])
        self.assertEqual(doc["placement"]["distance"], 48)

    def test_a_non_concentric_placement_is_left_alone(self):
        doc = self.castle()
        doc["placement"]["type"] = "minecraft:random_spread"
        report = migrate.Report()
        migrate.rule_end_castle_placement(doc, self.CASTLE, report)
        self.assertEqual(doc["placement"]["count"], 219)
        self.assertEqual(sum(report.counts.values()), 0)


class TestPublicStructureNamespace(unittest.TestCase):
    """The two DnT structures are republished under ``mumu:``.

    A structure id *is* its file path below ``worldgen/structure``, so this rename is a namespace
    move plus a repointing of every registry reference -- never a string replacement. The two halves
    that must hold at once:

    * the ``mumu:`` definition is the one a ``structure_set`` places;
    * the author's definition stays shipped as a read-only legacy entry, because a world saved
      before the rename keys its chunk structure starts by the old id (dropping it would also stop
      a castle that spans a chunk border from placing its remaining pieces).
    """

    STRUCTURE = "data/nova_structures/worldgen/structure/end_castle.json"
    SET = "data/nova_structures/worldgen/structure_set/end_castle.json"
    NEW_SET = "data/mumu/worldgen/structure_set/end_castle.json"
    NEW_STRUCTURE = "data/mumu/worldgen/structure/end_castle.json"

    def test_both_the_placement_and_the_definition_move(self):
        mapping = migrate.assign_output_paths([
            self.SET,
            "data/nova_structures/worldgen/structure_set/end_ship.json",
            # The definition moves too: DnT publishes mumu: only, so no second id survives.
            self.STRUCTURE,
            # an unrelated structure_set must not be caught by the rule.
            "data/nova_structures/worldgen/structure_set/end_lighthouse.json",
        ])
        self.assertEqual(mapping[self.SET], self.NEW_SET)
        self.assertEqual(mapping["data/nova_structures/worldgen/structure_set/end_ship.json"],
                         "data/mumu/worldgen/structure_set/end_ship.json")
        self.assertEqual(mapping[self.STRUCTURE], self.NEW_STRUCTURE)
        self.assertEqual(mapping["data/nova_structures/worldgen/structure_set/end_lighthouse.json"],
                         "data/nova_structures/worldgen/structure_set/end_lighthouse.json")

    def test_no_alias_copy_is_written_when_the_definition_moves(self):
        # A second write would collide with the relocated definition, so the alias path is None --
        # which is also what keeps a future "emit both" edit from silently reintroducing the
        # duplicate id.
        self.assertIsNone(migrate.public_structure_alias_path(self.STRUCTURE))
        for relpath in (
            "data/nova_structures/worldgen/structure/end_lighthouse.json",
            "data/nova_structures/worldgen/structure_set/end_castle.json",
            "data/nova_structures/worldgen/template_pool/end_castle/start.json",
            # an .nbt structure payload is not a definition document
            "data/nova_structures/structure/end_castle/base.nbt",
        ):
            self.assertIsNone(migrate.public_structure_alias_path(relpath), relpath)

    def test_the_castle_placement_rule_matches_the_relocated_path(self):
        # The regression this guards: the density rule is scoped by exact path, so a namespace move
        # that forgot to update CASTLE_STRUCTURE_SET would silently stop thinning the castle.
        self.assertEqual(migrate.CASTLE_STRUCTURE_SET, self.NEW_SET)
        doc = TestEndCastlePlacement.castle()
        report = migrate.Report()
        migrate.rule_end_castle_placement(doc, self.NEW_SET, report)
        self.assertEqual(doc["placement"]["count"], migrate.CASTLE_PLACEMENT["count"])
        self.assertEqual(doc["placement"]["distance"], migrate.CASTLE_PLACEMENT["distance"])

    def test_structure_set_entry_is_repointed(self):
        doc = {"placement": {}, "structures": [{"structure": "nova_structures:end_castle", "weight": 1}]}
        report = migrate.Report()
        migrate.rule_public_structure_ids(doc, self.NEW_SET, report)
        self.assertEqual(doc["structures"], [{"structure": "mumu:end_castle", "weight": 1}])
        self.assertEqual(report.counts["structure.public_namespace_structure_set"], 1)

    def test_structure_tag_values_are_repointed(self):
        # The pack's own tag (the castle map's `destination`) and vanilla's eye-of-ender tag both
        # name structures in `values`; both must name the id that actually places.
        doc = {"values": ["nova_structures:end_castle", "minecraft:end_city"]}
        report = migrate.Report()
        migrate.rule_public_structure_ids(
            doc, "data/nova_structures/tags/worldgen/structure/end_castle.json", report)
        self.assertEqual(doc["values"], ["mumu:end_castle", "minecraft:end_city"])
        self.assertEqual(report.counts["structure.public_namespace_structure_tag"], 1)

    def test_structure_tag_object_values_and_nested_tags(self):
        # 26.3 tag entries may be {"id": ..., "required": ...}; a "#"-prefixed value is a nested tag.
        doc = {"values": [{"id": "nova_structures:end_castle", "required": False}, "#minecraft:eyes"]}
        migrate.rule_public_structure_ids(
            doc, "data/minecraft/tags/worldgen/structure/eye_of_ender_located.json", migrate.Report())
        self.assertEqual(doc["values"], [{"id": "mumu:end_castle", "required": False}, "#minecraft:eyes"])

    def test_advancement_location_predicate_is_repointed(self):
        doc = {"criteria": {"a": {"conditions": {"player": {"predicate": {
            "location": {"structures": "nova_structures:end_castle"}}}}}}}
        report = migrate.Report()
        migrate.rule_public_structure_ids(doc, "data/nova_structures/advancement/end/find_end_castle.json", report)
        self.assertEqual(doc["criteria"]["a"]["conditions"]["player"]["predicate"]["location"]["structures"],
                         "mumu:end_castle")
        self.assertEqual(report.counts["structure.public_namespace_advancement"], 1)

    def test_advancement_holder_set_list_is_repointed(self):
        doc = {"location": {"structures": ["nova_structures:end_ship", "minecraft:end_city"]}}
        migrate.rule_public_structure_ids(doc, "data/nova_structures/advancement/end/find_end_ship.json",
                                         migrate.Report())
        self.assertEqual(doc["location"]["structures"], ["mumu:end_ship", "minecraft:end_city"])

    def test_the_map_destination_is_a_direct_structure_reference(self):
        # 26.3 ExplorationMapFunction.MAP_CODEC reads
        # RegistryCodecs.holderSet(Registries.STRUCTURE).fieldOf("destination"), and HolderSetCodec
        # decodes either(TagKey.hashedCodec, compactListCodec): a plain id (no "#") is a *direct*
        # holder, i.e. one named structure. The legacy definition never generates, so a kept legacy
        # destination would make the map locate nothing.
        doc = {"pools": [{"entries": [{"modifier": [{
            "type": "minecraft:exploration_map", "destination": "nova_structures:end_castle"}]}]}]}
        report = migrate.Report()
        migrate.rule_public_structure_ids(doc, "data/nova_structures/loot_table/maps/end_castle.json", report)
        self.assertEqual(doc["pools"][0]["entries"][0]["modifier"][0]["destination"], "mumu:end_castle")
        self.assertEqual(report.counts["structure.public_namespace_map_destination"], 1)

    def test_a_hash_destination_is_a_tag_and_is_preserved(self):
        # "#..." is HolderSetCodec's tag branch -- a tag key, not a structure id.
        doc = {"type": "minecraft:exploration_map",
               "destination": "#nova_structures:end_castle_tag"}
        original = json.loads(json.dumps(doc))
        report = migrate.Report()
        migrate.rule_public_structure_ids(doc, "data/nova_structures/loot_table/maps/end_castle.json", report)
        self.assertEqual(doc, original)
        self.assertEqual(sum(report.counts.values()), 0)

    def test_a_holder_set_list_destination_is_repointed(self):
        doc = {"type": "minecraft:exploration_map",
               "destination": ["nova_structures:end_ship", "minecraft:end_city"]}
        migrate.rule_public_structure_ids(
            doc, "data/nova_structures/loot_table/maps/end_castle.json", migrate.Report())
        self.assertEqual(doc["destination"], ["mumu:end_ship", "minecraft:end_city"])

    def test_template_pool_and_nbt_references_are_not_rewritten(self):
        # `start_pool` and a pool element's `location` are template ids in the author's namespace,
        # and a prefix match on "end_castle" would wrongly catch "end_castle/start".
        doc = {
            "start_pool": "nova_structures:end_castle/start",
            "elements": [{"element": {"element_type": "minecraft:single_pool_element",
                                      "location": "nova_structures:end_castle/base"}}],
        }
        original = json.loads(json.dumps(doc))
        report = migrate.Report()
        migrate.rule_public_structure_ids(doc, "data/nova_structures/worldgen/template_pool/end_castle/start.json",
                                         report)
        self.assertEqual(doc, original)
        self.assertEqual(sum(report.counts.values()), 0)

    def test_is_idempotent(self):
        doc = {"placement": {}, "structures": [{"structure": "mumu:end_castle", "weight": 1}]}
        original = json.loads(json.dumps(doc))
        report = migrate.Report()
        migrate.rule_public_structure_ids(doc, self.NEW_SET, report)
        self.assertEqual(doc, original)
        self.assertEqual(sum(report.counts.values()), 0, "re-running must not count again")


class TestTemplatePoolBlockStates(unittest.TestCase):
    """The Echoes pack's 0.1.6 startup abort: legacy block-state spelling in template pools.

    26.3's ``BlockState.CODEC`` is ``either(BLOCK.byNameCodec(), FULL_CODEC)`` -- a plain string id,
    or ``{"id": ..., "properties": ...}``. The pre-1.20 palette spelling ``{"Name": ...}`` is
    neither, so ``block_ignore.blocks`` failed to parse, the whole
    ``minecraft:worldgen/template_pool`` registry went Unbound, and the server refused to start.
    """

    POOL = "data/echoes_of_the_end__structures_/worldgen/template_pool/end_well.json"

    @staticmethod
    def pool(blocks):
        return {
            "name": "echoes_of_the_end__structures_:end_well",
            "fallback": "minecraft:empty",
            "elements": [{
                "weight": 1,
                "element": {
                    "element_type": "minecraft:single_pool_element",
                    "location": "echoes_of_the_end__structures_:end_well",
                    "projection": "rigid",
                    "processors": {"processors": [{
                        "processor_type": "minecraft:block_ignore",
                        "blocks": blocks,
                    }]},
                },
            }],
        }

    @staticmethod
    def blocks_of(doc):
        return doc["elements"][0]["element"]["processors"]["processors"][0]["blocks"]

    def test_legacy_name_becomes_a_plain_id(self):
        doc = self.pool([{"Name": "minecraft:structure_block"}])
        report = migrate.Report()
        migrate.rule_template_pool_block_states(doc, self.POOL, report)
        self.assertEqual(self.blocks_of(doc), ["minecraft:structure_block"])
        self.assertEqual(report.counts["structure.template_pool_block_state_name_to_id"], 1)
        self.assertEqual(report.files["structure.template_pool_block_state_name_to_id"], [self.POOL])

    def test_legacy_properties_become_the_id_object(self):
        doc = self.pool([{"Name": "minecraft:oak_log", "Properties": {"axis": "y"}}])
        migrate.rule_template_pool_block_states(doc, self.POOL, migrate.Report())
        self.assertEqual(self.blocks_of(doc),
                         [{"id": "minecraft:oak_log", "properties": {"axis": "y"}}])

    def test_an_empty_properties_object_collapses_to_the_plain_id(self):
        doc = self.pool([{"Name": "minecraft:oak_log", "Properties": {}}])
        migrate.rule_template_pool_block_states(doc, self.POOL, migrate.Report())
        self.assertEqual(self.blocks_of(doc), ["minecraft:oak_log"])

    def test_is_idempotent(self):
        doc = self.pool([{"Name": "minecraft:structure_block"}])
        migrate.rule_template_pool_block_states(doc, self.POOL, migrate.Report())
        migrated = json.loads(json.dumps(doc))
        report = migrate.Report()
        migrate.rule_template_pool_block_states(doc, self.POOL, report)
        self.assertEqual(doc, migrated, "a second pass must not move anything")
        self.assertEqual(sum(report.counts.values()), 0, "a second pass must not count again")

    def test_an_already_26_3_entry_is_not_touched(self):
        for blocks in (["minecraft:structure_block"],
                       [{"id": "minecraft:oak_log", "properties": {"axis": "y"}}]):
            doc = self.pool(json.loads(json.dumps(blocks)))
            report = migrate.Report()
            migrate.rule_template_pool_block_states(doc, self.POOL, report)
            self.assertEqual(self.blocks_of(doc), blocks)
            self.assertEqual(sum(report.counts.values()), 0)

    def test_every_element_is_migrated_not_just_the_first(self):
        doc = self.pool([{"Name": "minecraft:structure_block"}])
        second = json.loads(json.dumps(doc["elements"][0]))
        second["element"]["location"] = "echoes_of_the_end__structures_:other"
        second["element"]["processors"]["processors"][0]["blocks"] = [
            {"Name": "minecraft:air"}]
        doc["elements"].append(second)
        migrate.rule_template_pool_block_states(doc, self.POOL, migrate.Report())
        self.assertEqual(self.blocks_of(doc), ["minecraft:structure_block"])
        self.assertEqual(
            doc["elements"][1]["element"]["processors"]["processors"][0]["blocks"],
            ["minecraft:air"])

    def test_the_inline_bare_list_form_is_migrated_too(self):
        # StructureProcessorType.DIRECT_CODEC is withAlternative(fieldOf("processors"),
        # LIST_OBJECT_CODEC): the bare-list form is the second alternative and legal.
        doc = self.pool([{"Name": "minecraft:structure_block"}])
        processors = doc["elements"][0]["element"]["processors"]["processors"]
        doc["elements"][0]["element"]["processors"] = processors
        migrate.rule_template_pool_block_states(doc, self.POOL, migrate.Report())
        self.assertEqual(
            doc["elements"][0]["element"]["processors"][0]["blocks"],
            ["minecraft:structure_block"])

    def test_an_untranslatable_entry_is_left_verbatim_never_guessed(self):
        # Non-string Name, non-object Properties, and an unknown extra key are all cases where a
        # rewrite would have to invent something. Leaving them alone makes the codec gate report a
        # real problem instead of the migration hiding it behind a guessed value.
        for entry in ({"Name": 3}, {"Name": "minecraft:oak_log", "Properties": [1]},
                      {"Name": "minecraft:oak_log", "Extra": True}, {}, "minecraft:air"):
            doc = self.pool([json.loads(json.dumps(entry))])
            report = migrate.Report()
            migrate.rule_template_pool_block_states(doc, self.POOL, report)
            self.assertEqual(self.blocks_of(doc), [entry])
            self.assertEqual(sum(report.counts.values()), 0)

    def test_a_document_without_pool_elements_does_not_crash(self):
        for doc in ({}, {"elements": None}, {"elements": []}, {"elements": [{}]},
                    {"elements": [{"element": None}]},
                    {"elements": [{"element": {"processors": "minecraft:empty"}}]},
                    {"elements": [{"element": {"processors": {"processors": None}}}]},
                    {"elements": [{"element": {"processors": {"processors": [None, 3]}}}]},
                    {"elements": [{"element": {"processors": {"processors": [
                        {"processor_type": "minecraft:block_ignore", "blocks": None}]}}}]}):
            report = migrate.Report()
            migrate.rule_template_pool_block_states(doc, self.POOL, report)
            self.assertEqual(sum(report.counts.values()), 0)

    def test_a_non_block_ignore_processor_is_never_rewritten(self):
        # rule processors carry block states under output_state / block_state; this rule does not
        # claim them, so a `Name` there must survive untouched for a human to look at.
        doc = self.pool([{"Name": "minecraft:structure_block"}])
        processors = doc["elements"][0]["element"]["processors"]["processors"]
        processors.append({
            "processor_type": "minecraft:rule",
            "rules": [{"input_predicate": {"predicate_type": "minecraft:always_true"},
                       "location_predicate": {"predicate_type": "minecraft:always_true"},
                       "output_state": {"Name": "minecraft:stone"}}],
        })
        migrate.rule_template_pool_block_states(doc, self.POOL, migrate.Report())
        self.assertEqual(self.blocks_of(doc), ["minecraft:structure_block"])
        self.assertEqual(processors[1]["rules"][0]["output_state"],
                         {"Name": "minecraft:stone"})

    def test_a_blocks_key_outside_a_processor_is_not_touched(self):
        # Structural walk, not a key search: a `blocks` list elsewhere in the document (e.g. an
        # embedded palette) must never be handed to a block-state rewrite.
        doc = self.pool(["minecraft:structure_block"])
        doc["palette"] = {"blocks": [{"Name": "minecraft:stone"}]}
        migrate.rule_template_pool_block_states(doc, self.POOL, migrate.Report())
        self.assertEqual(doc["palette"]["blocks"], [{"Name": "minecraft:stone"}])


class TestTimeCheckClock(unittest.TestCase):
    DOC = {"requirements": {"condition": "minecraft:time_check",
                            "value": {"min": 0, "max": 12000}, "period": 24000}}

    def test_discriminator_then_clock_injection(self):
        doc = json.loads(json.dumps(self.DOC))
        report = migrate.Report()
        migrate.rule_condition_discriminator(doc, "p", report)
        migrate.rule_time_check_clock(doc, "p", report)
        node = doc["requirements"]
        self.assertEqual(node["type"], "minecraft:time_check")
        self.assertEqual(node["clock"], "minecraft:overworld")
        self.assertEqual(report.counts["condition.time_check_clock_default"], 1)

    def test_period_and_value_are_preserved_verbatim(self):
        doc = json.loads(json.dumps(self.DOC))
        migrate.rule_condition_discriminator(doc, "p", migrate.Report())
        migrate.rule_time_check_clock(doc, "p", migrate.Report())
        node = doc["requirements"]
        self.assertEqual(node["value"], {"min": 0, "max": 12000})
        self.assertEqual(node["period"], 24000)

    def test_existing_clock_is_never_overwritten(self):
        doc = {"type": "minecraft:time_check", "clock": "minecraft:the_end", "value": {}}
        report = migrate.Report()
        migrate.rule_time_check_clock(doc, "p", report)
        self.assertEqual(doc["clock"], "minecraft:the_end")
        self.assertEqual(report.counts.get("condition.time_check_clock_default", 0), 0)

    def test_other_condition_types_are_untouched(self):
        doc = {"type": "minecraft:weather_check", "raining": False}
        migrate.rule_time_check_clock(doc, "p", migrate.Report())
        self.assertNotIn("clock", doc)


class TestCmdToItemModel(unittest.TestCase):
    DISPATCH = {"minecraft:trial_key": {721: "dnt:item/keys/end_castle_key"}}

    def test_json_rewrite(self):
        doc = {"type": "minecraft:item", "name": "minecraft:trial_key", "modifier": [
            {"type": "minecraft:set_components", "components": {
                "minecraft:custom_model_data": {"floats": [721.0]},
                "minecraft:item_name": {"translate": "item.dnt.end_castle_key"}}}]}
        report = migrate.Report()
        migrate.rule_cmd_to_item_model(doc, self.DISPATCH, "p", report)
        comps = doc["modifier"][0]["components"]
        self.assertEqual(comps["minecraft:item_model"], "dnt:keys/end_castle_key")
        # original CMD is retained (value fidelity); item_model is added alongside
        self.assertEqual(comps["minecraft:custom_model_data"], {"floats": [721.0]})
        self.assertIn("minecraft:item_name", comps)

    def test_unknown_command_value_is_left_untouched(self):
        doc = {"type": "minecraft:item", "name": "minecraft:trial_key", "modifier": [
            {"type": "minecraft:set_components", "components": {"minecraft:custom_model_data": {"floats": [999.0]}}}]}
        migrate.rule_cmd_to_item_model(doc, self.DISPATCH, "p", migrate.Report())
        self.assertIn("minecraft:custom_model_data", doc["modifier"][0]["components"])

    def test_item_model_id_strips_item_prefix(self):
        self.assertEqual(migrate.item_model_id_for_model("dnt:item/keys/end_castle_key"), "dnt:keys/end_castle_key")
        self.assertEqual(migrate.item_model_id_for_model("dnt:chart"), "dnt:chart")

    def test_nbt_rewrite(self):
        root = {"blocks": [{"nbt": {"id": "minecraft:vault", "config": {"key_item": {
            "id": "minecraft:trial_key", "count": 1,
            "components": {"minecraft:custom_model_data": 721}}}}}]}
        report = migrate.Report()
        n = migrate.rule_cmd_to_item_model_nbt(root, self.DISPATCH, "p", report)
        self.assertEqual(n, 1)
        comps = root["blocks"][0]["nbt"]["config"]["key_item"]["components"]
        self.assertEqual(comps["minecraft:item_model"], "dnt:keys/end_castle_key")
        self.assertEqual(comps["minecraft:custom_model_data"], 721)


class TestValidate(unittest.TestCase):
    def test_internal_closure_detects_dangling(self):
        files = ["data/nova_structures/loot_table/a.json", "data/nova_structures/function/f.mcfunction"]
        docs = {"data/nova_structures/loot_table/a.json": {"type": "minecraft:loot_table", "value": "nova_structures:missing"}}
        problems = validate.check_internal_closure(files, docs)
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].kind, "dangling-loot_table")

    def test_internal_closure_accepts_present_target(self):
        files = ["data/nova_structures/function/f.mcfunction"]
        docs = {"data/nova_structures/a.json": {"type": "minecraft:run_function", "function": "nova_structures:f"}}
        self.assertEqual(validate.check_internal_closure(files, docs), [])

    def test_external_refs_are_not_flagged(self):
        files = []
        docs = {"data/ns/a.json": {"type": "minecraft:item", "name": "minecraft:stick"}}
        self.assertEqual(validate.check_internal_closure(files, docs), [])

    def test_internal_closure_covers_the_published_namespace(self):
        # The rename created `mumu:` ids; checking only `nova_structures:` would leave exactly the
        # references this project owns unverified.
        files = ["data/mumu/worldgen/structure_set/end_castle.json"]
        docs = {"data/mumu/worldgen/structure_set/end_castle.json": {
            "placement": {}, "structures": [{"structure": "mumu:end_castle", "weight": 1}]}}
        problems = validate.check_internal_closure(files, docs)
        self.assertEqual([p.kind for p in problems], ["dangling-worldgen/structure"])

    def test_structure_set_entry_is_a_structure_reference(self):
        files = ["data/mumu/worldgen/structure/end_castle.json"]
        docs = {"data/mumu/worldgen/structure_set/end_castle.json": {
            "placement": {}, "structures": [{"structure": "mumu:end_castle", "weight": 1}]}}
        self.assertEqual(validate.check_internal_closure(files, docs), [])

    def test_structure_tag_values_are_structure_references(self):
        path = "data/nova_structures/tags/worldgen/structure/end_castle.json"
        docs = {path: {"values": ["mumu:end_castle"]}}
        files = ["data/mumu/worldgen/structure/end_castle.json"]
        self.assertEqual(validate.check_internal_closure(files, docs), [])
        problems = validate.check_internal_closure([], docs)
        self.assertEqual([p.kind for p in problems], ["dangling-worldgen/structure"])

    def test_a_map_destination_is_a_structure_reference(self):
        # `exploration_map.destination` is a HolderSet<Structure>: a plain id is a direct holder and
        # must resolve, while a "#" value is a tag and is not a structure reference at all.
        path = "data/nova_structures/loot_table/maps/end_castle.json"
        docs = {path: {"pools": [{"entries": [{"modifier": [
            {"type": "minecraft:exploration_map", "destination": "mumu:end_castle"}]}]}]}}
        self.assertEqual(validate.check_internal_closure(["data/mumu/worldgen/structure/end_castle.json"], docs), [])
        problems = validate.check_internal_closure([], docs)
        self.assertEqual([p.kind for p in problems], ["dangling-worldgen/structure"])

        tagged = {path: {"type": "minecraft:exploration_map",
                         "destination": "#nova_structures:end_castle"}}
        self.assertEqual(validate.check_internal_closure([], tagged), [],
                         "a #tag 不是结构 id，不该按结构引用校验")

    def test_advancement_location_predicate_is_a_structure_reference(self):
        path = "data/nova_structures/advancement/end/find_end_castle.json"
        docs = {path: {"criteria": {"a": {"conditions": {"player": {"predicate": {
            "location": {"structures": "nova_structures:end_castle"}}}}}}}}
        files = ["data/nova_structures/worldgen/structure/end_castle.json"]
        self.assertEqual(validate.check_internal_closure(files, docs), [],
                         "legacy id still resolves against the retained legacy definition")

    def test_index_pack_ids_indexes_worldgen_subcategory(self):
        index = validate.index_pack_ids([
            "data/nova_structures/worldgen/feature/chorus_plant.json",
            "data/nova_structures/worldgen/placed_feature/chorus_plant.json",
            "data/nova_structures/worldgen/structure/end_castle.json",
        ])
        self.assertIn("nova_structures:chorus_plant", index["worldgen/feature"])
        self.assertIn("nova_structures:chorus_plant", index["worldgen/placed_feature"])
        self.assertIn("nova_structures:end_castle", index["worldgen/structure"])
        self.assertNotIn("worldgen", index, "worldgen must not collapse into one bucket")

    def test_closure_placed_feature_root_feature_resolves_to_feature_dir(self):
        path = "data/nova_structures/worldgen/placed_feature/chorus_plant.json"
        files = ["data/nova_structures/worldgen/feature/chorus_plant.json", path]
        docs = {path: {"feature": "nova_structures:chorus_plant", "placement": []}}
        self.assertEqual(validate.check_internal_closure(files, docs), [])

    def test_closure_placed_feature_fails_against_legacy_configured_feature_dir(self):
        # The old 1.21.x directory name leaves the placed feature's "feature"
        # reference dangling, which is exactly what the relocation fixes.
        path = "data/nova_structures/worldgen/placed_feature/chorus_plant.json"
        files = ["data/nova_structures/worldgen/configured_feature/chorus_plant.json", path]
        docs = {path: {"feature": "nova_structures:chorus_plant", "placement": []}}
        problems = validate.check_internal_closure(files, docs)
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].kind, "dangling-worldgen/feature")

    def test_closure_feature_pool_element_resolves_to_placed_feature(self):
        path = "data/nova_structures/worldgen/template_pool/end_castle/chorus_plant.json"
        files = ["data/nova_structures/worldgen/placed_feature/chorus_plant.json"]
        docs = {path: {"elements": [{"element": {
            "element_type": "minecraft:feature_pool_element",
            "feature": "nova_structures:chorus_plant", "projection": "rigid"}}]}}
        self.assertEqual(validate.check_internal_closure(files, docs), [])

    def test_closure_feature_pool_element_fails_without_placed_feature(self):
        path = "data/nova_structures/worldgen/template_pool/end_castle/chorus_plant.json"
        docs = {path: {"elements": [{"element": {
            "element_type": "minecraft:feature_pool_element",
            "feature": "nova_structures:chorus_plant"}}]}}
        problems = validate.check_internal_closure([], docs)
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].kind, "dangling-worldgen/placed_feature")

    def test_closure_external_feature_refs_are_not_flagged(self):
        path = "data/nova_structures/worldgen/template_pool/end_castle/chorus_plant.json"
        docs = {path: {"elements": [{"element": {
            "element_type": "minecraft:feature_pool_element",
            "feature": "minecraft:forest_rock"}}]}}
        self.assertEqual(validate.check_internal_closure([], docs), [])

    def test_generation_check_rejects_legacy_configured_feature(self):
        problems = validate.check_no_legacy_configured_feature(
            ["data/nova_structures/worldgen/configured_feature/chorus_plant.json"])
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].kind, "legacy-worldgen-path")

    def test_generation_check_accepts_feature_dir(self):
        self.assertEqual(validate.check_no_legacy_configured_feature(
            ["data/nova_structures/worldgen/feature/chorus_plant.json"]), [])

    def test_generation_check_flags_time_check_without_clock(self):
        docs = {"p": {"type": "minecraft:time_check", "value": {"min": 0, "max": 1}}}
        problems = validate.check_time_check_clock(["p"], docs)
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].kind, "time_check-missing-clock")

    def test_generation_check_flags_legacy_condition_key_without_clock(self):
        docs = {"p": {"condition": "minecraft:time_check", "value": {}}}
        problems = validate.check_time_check_clock(["p"], docs)
        self.assertEqual(len(problems), 1)

    def test_generation_check_accepts_time_check_with_clock(self):
        docs = {"p": {"type": "minecraft:time_check", "value": {}, "clock": "minecraft:overworld"}}
        self.assertEqual(validate.check_time_check_clock(["p"], docs), [])

    def test_loot_legacy_keys_flagged(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {"pools": [{"functions": []}]}}
        problems = validate.check_loot_discriminators(files, docs)
        self.assertEqual(len(problems), 1)

    def test_pack_mcmeta_requires_min_max(self):
        problems = validate.check_pack_mcmeta({"pack": {"pack_format": 71}}, 121)
        self.assertTrue(any("min_format" in p.detail for p in problems))

    def test_pack_mcmeta_rejects_supported_formats(self):
        mc = {"pack": {"min_format": [121, 0], "max_format": [121, 0], "supported_formats": [71, 121]}}
        problems = validate.check_pack_mcmeta(mc, 121)
        self.assertTrue(any("supported_formats" in p.detail for p in problems))


class TestLootDiscriminatorScoping(unittest.TestCase):
    """The 26.3 discriminator rename must not touch explicit sequence/run_function
    payload fields, and must be idempotent."""

    def test_sequence_functions_payload_is_not_renamed(self):
        doc = {"type": "minecraft:sequence", "functions": [{"type": "minecraft:set_count", "count": 3}]}
        report = migrate.Report()
        migrate.rule_loot_discriminators(doc, "p", report)
        self.assertIn("functions", doc, "sequence 的 functions 载荷不得被改名")
        self.assertNotIn("modifier", doc, "sequence 的 functions 载荷不得被改名为 modifier")
        self.assertEqual(report.counts.get("loot.functions_to_modifier", 0), 0)

    def test_sequence_without_namespace_is_recognized(self):
        doc = {"type": "sequence", "functions": []}
        migrate.rule_loot_discriminators(doc, "p", migrate.Report())
        self.assertIn("functions", doc, "无命名空间的 sequence 同样必须被识别")

    def test_legacy_sequence_function_is_migrated_to_type(self):
        doc = {"function": "minecraft:sequence", "functions": [{"function": "set_count", "count": 1}]}
        migrate.rule_loot_discriminators(doc, "p", migrate.Report())
        self.assertEqual(doc["type"], "minecraft:sequence")
        self.assertNotIn("function", doc)
        self.assertIn("functions", doc, "旧版 sequence 的 functions 载荷必须保留")
        self.assertEqual(doc["functions"][0]["type"], "set_count")

    def test_run_function_payload_is_preserved(self):
        doc = {"type": "minecraft:run_function", "function": "nova_structures:do_thing"}
        report = migrate.Report()
        migrate.rule_loot_discriminators(doc, "p", report)
        self.assertEqual(doc["type"], "minecraft:run_function", "run_function 判别符不得被覆盖")
        self.assertEqual(doc["function"], "nova_structures:do_thing",
                         "run_function 的 function 载荷不得被误认判别符")
        self.assertEqual(report.counts.get("loot.function_discriminator_to_type", 0), 0)

    def test_legacy_function_discriminator_is_still_renamed(self):
        doc = {"pools": [{"functions": [{"function": "minecraft:set_count", "count": 3}]}]}
        report = migrate.Report()
        migrate.rule_loot_discriminators(doc, "p", report)
        self.assertIn("modifier", doc["pools"][0], "旧容器仍须迁移为 modifier")
        self.assertEqual(doc["pools"][0]["modifier"][0]["type"], "minecraft:set_count")

    def test_rule_is_idempotent(self):
        doc = {"pools": [{"functions": [{"function": "minecraft:set_count", "count": {"min": 1, "max": 2}}]}]}
        migrate.rule_loot_discriminators(doc, "p", migrate.Report())
        snapshot = json.loads(json.dumps(doc))
        report2 = migrate.Report()
        migrate.rule_loot_discriminators(doc, "p", report2)
        self.assertEqual(doc, snapshot, "重复执行不得改变已迁移结果")
        self.assertEqual(sum(report2.counts.values()), 0)


class TestItemModifierRootWrap(unittest.TestCase):
    """A bare-array item_modifier root must be wrapped as an explicit sequence."""

    def test_empty_root_wrapped_as_empty_sequence(self):
        report = migrate.Report()
        out = migrate.wrap_item_modifier_root([], "p", report)
        self.assertEqual(out, {"type": "minecraft:sequence", "functions": []})
        self.assertEqual(report.counts["loot.item_modifier_root_wrapped"], 1)

    def test_nonempty_root_wrapped_preserving_items(self):
        items = [{"type": "minecraft:set_count", "count": 3}]
        out = migrate.wrap_item_modifier_root(list(items), "p", migrate.Report())
        self.assertEqual(out, {"type": "minecraft:sequence", "functions": items})

    def test_already_wrapped_root_is_untouched(self):
        doc = {"type": "minecraft:sequence", "functions": []}
        report = migrate.Report()
        out = migrate.wrap_item_modifier_root(doc, "p", report)
        self.assertIs(out, doc, "已是对象根的产物不得再次包裹")
        self.assertEqual(sum(report.counts.values()), 0)

    def test_nested_lists_are_never_wrapped(self):
        doc = {"type": "minecraft:sequence", "functions": [
            {"type": "minecraft:set_count", "count": 1},
            {"type": "minecraft:sequence", "functions": [{"type": "minecraft:set_count", "count": 2}]}]}
        before = json.loads(json.dumps(doc))
        out = migrate.wrap_item_modifier_root(doc, "p", migrate.Report())
        self.assertEqual(out, before, "仅根列表可包裹；内部裸数组必须保持")


class TestNumberProviderTypes(unittest.TestCase):
    """A dispatched {min,max} number provider must gain its mandatory type."""

    def test_untyped_uniform_in_set_count_gains_type(self):
        doc = {"type": "minecraft:set_count", "count": {"min": 1, "max": 3}}
        report = migrate.Report()
        migrate.rule_number_provider_types(doc, "p", report)
        self.assertEqual(doc["count"], {"min": 1, "max": 3, "type": "minecraft:uniform"})
        self.assertEqual(report.counts["loot.number_provider_missing_type"], 1)

    def test_bare_number_count_is_untouched(self):
        doc = {"type": "minecraft:set_count", "count": 5}
        report = migrate.Report()
        migrate.rule_number_provider_types(doc, "p", report)
        self.assertEqual(doc["count"], 5)
        self.assertEqual(sum(report.counts.values()), 0)

    def test_already_typed_count_is_untouched(self):
        doc = {"type": "minecraft:set_count", "count": {"min": 1, "max": 3, "type": "minecraft:uniform"}}
        report = migrate.Report()
        migrate.rule_number_provider_types(doc, "p", report)
        self.assertEqual(sum(report.counts.values()), 0, "已有 type 的提供器不得被重写")

    def test_pool_rolls_and_bonus_rolls(self):
        doc = {"pools": [{"rolls": {"min": 1, "max": 2}, "bonus_rolls": {"min": 0.0, "max": 1.0}, "entries": []}]}
        migrate.rule_number_provider_types(doc, "p", migrate.Report())
        self.assertEqual(doc["pools"][0]["rolls"], {"min": 1, "max": 2, "type": "minecraft:uniform"})
        self.assertEqual(doc["pools"][0]["bonus_rolls"], {"min": 0.0, "max": 1.0, "type": "minecraft:uniform"})

    def test_set_damage_keeps_inverted_min_max(self):
        doc = {"type": "minecraft:set_damage", "damage": {"min": 0.01, "max": 0.0}}
        migrate.rule_number_provider_types(doc, "p", migrate.Report())
        self.assertEqual(doc["damage"], {"min": 0.01, "max": 0.0, "type": "minecraft:uniform"},
                         "倒序 min/max 必须原样保留，不得交换或钳制")

    def test_amplifier_provider_gains_type(self):
        doc = {"type": "set_ominous_bottle_amplifier", "amplifier": {"max": 5, "min": 3}}
        migrate.rule_number_provider_types(doc, "p", migrate.Report())
        self.assertEqual(doc["amplifier"], {"max": 5, "min": 3, "type": "minecraft:uniform"})

    def test_stew_effect_duration_provider(self):
        doc = {"type": "minecraft:set_stew_effect",
               "effects": [{"type": "minecraft:levitation", "duration": {"min": 1, "max": 5}}]}
        migrate.rule_number_provider_types(doc, "p", migrate.Report())
        self.assertEqual(doc["effects"][0]["duration"], {"min": 1, "max": 5, "type": "minecraft:uniform"})

    def test_enchant_with_levels_levels_provider(self):
        doc = {"type": "minecraft:enchant_with_levels", "levels": {"min": 5, "max": 20}}
        migrate.rule_number_provider_types(doc, "p", migrate.Report())
        self.assertEqual(doc["levels"]["type"], "minecraft:uniform")

    def test_time_check_value_is_never_touched(self):
        doc = {"type": "minecraft:time_check", "clock": "minecraft:overworld", "value": {"min": 0, "max": 12000}}
        report = migrate.Report()
        migrate.rule_number_provider_types(doc, "p", report)
        self.assertEqual(doc["value"], {"min": 0, "max": 12000}, "time_check.value 是范围谓词，不得补 type")
        self.assertEqual(sum(report.counts.values()), 0)

    def test_other_minmax_objects_are_out_of_scope(self):
        doc = {"type": "minecraft:entity_properties", "predicate": {"y": {"min": -100, "max": 500}}}
        report = migrate.Report()
        migrate.rule_number_provider_types(doc, "p", report)
        self.assertEqual(doc["predicate"]["y"], {"min": -100, "max": 500})
        self.assertEqual(sum(report.counts.values()), 0)

    def test_rule_is_idempotent(self):
        doc = {"type": "minecraft:set_count", "count": {"min": 1, "max": 3}}
        migrate.rule_number_provider_types(doc, "p", migrate.Report())
        report2 = migrate.Report()
        migrate.rule_number_provider_types(doc, "p", report2)
        self.assertEqual(doc["count"], {"min": 1, "max": 3, "type": "minecraft:uniform"})
        self.assertEqual(sum(report2.counts.values()), 0)


class TestMapColorRemoval(unittest.TestCase):
    """The retired map_color component must be dropped inside set_components only."""

    def test_namespaced_map_color_is_removed(self):
        doc = {"type": "minecraft:set_components", "components": {
            "minecraft:map_color": 9317301, "minecraft:item_name": {"translate": "x"}}}
        report = migrate.Report()
        migrate.rule_remove_map_color_component(doc, "p", report)
        self.assertNotIn("minecraft:map_color", doc["components"])
        self.assertIn("minecraft:item_name", doc["components"], "非 map_color 组件必须保留")
        self.assertEqual(report.counts["loot.map_color_component_removed"], 1)

    def test_unnamespaced_map_color_is_removed(self):
        doc = {"type": "minecraft:set_components", "components": {"map_color": 1}}
        migrate.rule_remove_map_color_component(doc, "p", migrate.Report())
        self.assertNotIn("map_color", doc["components"])

    def test_item_model_tint_type_is_not_touched(self):
        doc = {"type": "minecraft:set_components", "components": {"minecraft:item_model": {
            "type": "minecraft:model", "model": "dnt:x", "tints": [{"type": "minecraft:map_color"}]}}}
        migrate.rule_remove_map_color_component(doc, "p", migrate.Report())
        self.assertIn("minecraft:item_model", doc["components"])
        tint = doc["components"]["minecraft:item_model"]["tints"][0]
        self.assertEqual(tint["type"], "minecraft:map_color",
                         "item model 的 tint type 是取值而非组件键，不得被删除")

    def test_only_set_components_context_is_scanned(self):
        doc = {"components": {"minecraft:map_color": 1}}  # not inside a set_components function
        report = migrate.Report()
        migrate.rule_remove_map_color_component(doc, "p", report)
        self.assertIn("minecraft:map_color", doc["components"], "非 set_components 语境不得被扫描")
        self.assertEqual(sum(report.counts.values()), 0)

    def test_rule_is_idempotent(self):
        doc = {"type": "minecraft:set_components",
               "components": {"minecraft:map_color": 1, "minecraft:item_name": "x"}}
        migrate.rule_remove_map_color_component(doc, "p", migrate.Report())
        report2 = migrate.Report()
        migrate.rule_remove_map_color_component(doc, "p", report2)
        self.assertEqual(doc["components"], {"minecraft:item_name": "x"})
        self.assertEqual(sum(report2.counts.values()), 0)


class TestValidateNewRules(unittest.TestCase):
    def test_loot_discriminators_allow_sequence_functions(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {"type": "minecraft:sequence", "functions": []}}
        self.assertEqual(validate.check_loot_discriminators(files, docs), [],
                         "显式 sequence 的 functions 载荷必须被接受")

    def test_loot_discriminators_allow_run_function_payload(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {"type": "minecraft:run_function", "function": "nova_structures:f"}}
        self.assertEqual(validate.check_loot_discriminators(files, docs), [])

    def test_loot_discriminators_reject_legacy_functions(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {"pools": [{"functions": []}]}}
        self.assertTrue(validate.check_loot_discriminators(files, docs), "裸 functions 旧容器仍须被拒绝")

    def test_number_provider_types_detects_untyped_uniform(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {"type": "minecraft:set_count", "count": {"min": 1, "max": 3}}}
        problems = validate.check_number_provider_types(files, docs)
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].kind, "number-provider-missing-type")

    def test_number_provider_types_accepts_typed(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {
            "type": "minecraft:set_count", "count": {"min": 1, "max": 3, "type": "minecraft:uniform"}}}
        self.assertEqual(validate.check_number_provider_types(files, docs), [])

    def test_number_provider_types_ignores_non_loot_category(self):
        files = ["data/ns/enchantment/a.json"]
        docs = {"data/ns/enchantment/a.json": {"value": {"min": 0, "max": 1}}}
        self.assertEqual(validate.check_number_provider_types(files, docs), [])

    def test_no_map_color_component_detects_leftover(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {
            "type": "minecraft:set_components", "components": {"minecraft:map_color": 1}}}
        problems = validate.check_no_map_color_component(files, docs)
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].kind, "retired-map-color-component")

    def test_no_map_color_component_ignores_other_context(self):
        files = ["data/ns/loot_table/a.json"]
        docs = {"data/ns/loot_table/a.json": {"minecraft:map_color": "not-a-component-key"}}
        self.assertEqual(validate.check_no_map_color_component(files, docs), [])


class TestPublicStructureLayout(unittest.TestCase):
    """``check_public_structure_layout``: one definition, exactly one placement each.

    This is the gate that makes the rename safe rather than merely done. DnT publishes
    ``mumu:`` only, so the author's definition must be *gone* (``/locate structure`` completes from
    the registry, and keeping both ids made every structure appear twice) while the ``mumu:`` one
    must be placed exactly once -- otherwise the old and new ids would each generate a castle in
    the same cell.
    """

    FILES = [
        "data/mumu/worldgen/structure/end_castle.json",
        "data/mumu/worldgen/structure/end_ship.json",
        "data/mumu/worldgen/structure_set/end_castle.json",
        "data/mumu/worldgen/structure_set/end_ship.json",
        "data/nova_structures/tags/worldgen/structure/end_castle.json",
        "data/minecraft/tags/worldgen/structure/eye_of_ender_located.json",
        "data/nova_structures/advancement/end/find_end_castle.json",
    ]
    DOCS = {
        "data/mumu/worldgen/structure_set/end_castle.json": {
            "placement": {}, "structures": [{"structure": "mumu:end_castle", "weight": 1}]},
        "data/mumu/worldgen/structure_set/end_ship.json": {
            "placement": {}, "structures": [{"structure": "mumu:end_ship", "weight": 1}]},
        # A tag and an advancement reference the same structure, and must not be read as placements.
        "data/nova_structures/tags/worldgen/structure/end_castle.json": {"values": ["mumu:end_castle"]},
        "data/minecraft/tags/worldgen/structure/eye_of_ender_located.json": {"values": ["mumu:end_castle"]},
        "data/nova_structures/advancement/end/find_end_castle.json": {
            "location": {"structures": "mumu:end_castle"}},
    }

    def test_a_correct_layout_is_clean(self):
        self.assertEqual(validate.check_public_structure_layout(self.FILES, self.DOCS), [])

    def test_a_stray_legacy_definition_is_rejected(self):
        # Keeping the author's definition alongside the mumu: one gives the registry two ids for
        # one structure, so /locate's completion lists it twice -- the duplicate this rule exists
        # to catch.
        files = self.FILES + ["data/nova_structures/worldgen/structure/end_castle.json"]
        kinds = [p.kind for p in validate.check_public_structure_layout(files, self.DOCS)]
        self.assertIn("stray-legacy-structure", kinds)

    def test_the_published_definition_must_be_shipped(self):
        files = [p for p in self.FILES if p != "data/mumu/worldgen/structure/end_castle.json"]
        kinds = [p.kind for p in validate.check_public_structure_layout(files, self.DOCS)]
        self.assertIn("missing-public-structure", kinds)

    def test_a_leftover_legacy_structure_set_is_rejected(self):
        files = self.FILES + ["data/nova_structures/worldgen/structure_set/end_castle.json"]
        kinds = [p.kind for p in validate.check_public_structure_layout(files, self.DOCS)]
        self.assertIn("legacy-structure-placement", kinds)

    def test_a_structure_set_still_naming_the_legacy_id_is_rejected(self):
        docs = dict(self.DOCS)
        docs["data/mumu/worldgen/structure_set/end_castle.json"] = {
            "placement": {}, "structures": [{"structure": "nova_structures:end_castle", "weight": 1}]}
        kinds = [p.kind for p in validate.check_public_structure_layout(self.FILES, docs)]
        self.assertIn("legacy-structure-ref", kinds)
        self.assertIn("unplaced-public-structure", kinds,
                      "此时 mumu 侧无人放置，两半都必须报出来")

    def test_an_unplaced_public_structure_is_rejected(self):
        docs = {k: v for k, v in self.DOCS.items()
                if k != "data/mumu/worldgen/structure_set/end_ship.json"}
        kinds = [p.kind for p in validate.check_public_structure_layout(self.FILES, docs)]
        self.assertIn("unplaced-public-structure", kinds)

    def test_a_duplicated_placement_is_rejected(self):
        files = self.FILES + ["data/mumu/worldgen/structure_set/end_castle_second.json"]
        docs = dict(self.DOCS)
        docs["data/mumu/worldgen/structure_set/end_castle_second.json"] = {
            "placement": {}, "structures": [{"structure": "mumu:end_castle", "weight": 1}]}
        kinds = [p.kind for p in validate.check_public_structure_layout(files, docs)]
        self.assertIn("duplicated-public-structure", kinds)

    def test_a_leftover_legacy_map_destination_is_rejected(self):
        # The map's destination is a direct HolderSet<Structure> entry, so a leftover legacy id
        # points at the (unplaced) legacy definition: the map would locate nothing.
        docs = dict(self.DOCS)
        docs["data/nova_structures/loot_table/maps/end_castle.json"] = {
            "pools": [{"entries": [{"modifier": [{
                "type": "minecraft:exploration_map",
                "destination": "nova_structures:end_castle"}]}]}]}
        kinds = [p.kind for p in validate.check_public_structure_layout(self.FILES, docs)]
        self.assertIn("legacy-structure-ref", kinds)


@unittest.skipUnless(os.path.isfile(SOURCE_ZIP), "source ZIP not present")
class TestPipelineIntegration(unittest.TestCase):
    """End-to-end: migrate the real archive and assert the contract."""

    @classmethod
    def setUpClass(cls):
        from prepare_dnt import main  # local import; scripts/ is on sys.path

        cls.out = tempfile.mkdtemp(prefix="dnt-out-")
        rc = main(["--input", SOURCE_ZIP, "--output", cls.out])
        cls.rc = rc

    @classmethod
    def tearDownClass(cls):
        import shutil

        shutil.rmtree(cls.out, ignore_errors=True)

    def _read(self, *parts):
        with open(os.path.join(self.out, *parts), encoding="utf-8") as fh:
            return fh.read()

    def test_pipeline_succeeds(self):
        self.assertEqual(self.rc, 0)

    def test_datapack_mcmeta_targets_121(self):
        mc = json.loads(self._read("datapacks", "dnt-end-castle", "pack.mcmeta"))
        self.assertEqual(mc["pack"]["min_format"], [121, 0])

    def test_ce_pack_yml_present(self):
        self.assertIn("namespace: dnt", self._read("craftengine", "dnt_end_castle", "pack.yml"))

    def test_ce_pack_root_has_no_build_only_files(self):
        # the CE bridge copies craftengine/dnt_end_castle/** verbatim
        root = os.path.join(self.out, "craftengine", "dnt_end_castle")
        self.assertEqual(sorted(os.listdir(root)), ["pack.yml", "resourcepack"])

    def test_manifest_lives_at_output_root(self):
        manifest = json.loads(self._read("dnt-manifest.json"))
        self.assertTrue(manifest["validation"]["ok"])
        self.assertEqual(manifest["target"]["data_pack_format"], [121, 0])
        self.assertEqual(manifest["source"]["sha256"], SOURCE_SHA)

    def test_phantom_boss_vanish_restored(self):
        text = self._read("datapacks", "dnt-end-castle", "data", "nova_structures", "function", "phantom_boss_vanish.mcfunction")
        self.assertEqual(text.strip(), migrate.PHANTOM_BOSS_VANISH_LINE)

    def test_vault_nbt_uses_item_model_and_keeps_data_version(self):
        path = os.path.join(self.out, "datapacks", "dnt-end-castle", "data", "nova_structures",
                            "structure", "end_castle", "room_content", "vault", "vault_greater_loot.nbt")
        with open(path, "rb") as fh:
            _, root = nbt.load_bytes(fh.read())
        self.assertLess(root["DataVersion"], 4301)  # old version kept for DFU
        comps = root["blocks"][1]["nbt"]["config"]["key_item"]["components"]
        self.assertIn("minecraft:item_model", comps)

    def test_no_minecraft_namespace_item_overrides(self):
        rp = os.path.join(self.out, "craftengine", "dnt_end_castle", "resourcepack", "assets")
        self.assertFalse(os.path.exists(os.path.join(rp, "minecraft", "items")))

    def test_generated_item_model_definition(self):
        path = os.path.join(self.out, "craftengine", "dnt_end_castle", "resourcepack", "assets",
                            "dnt", "items", "keys", "end_castle_key.json")
        with open(path, encoding="utf-8") as fh:
            body = json.load(fh)
        self.assertEqual(body["model"]["model"], "dnt:item/keys/end_castle_key")

    def test_wax_wings_time_check_gains_overworld_clock(self):
        doc = json.loads(self._read("datapacks", "dnt-end-castle", "data",
                                    "nova_structures", "enchantment", "wax_wings.json"))
        node = find_time_check(doc)
        self.assertEqual(node["clock"], "minecraft:overworld")
        self.assertEqual(node["value"], {"min": 0, "max": 12000})
        self.assertEqual(node["period"], 24000)

    def test_configured_feature_relocated_to_worldgen_feature(self):
        base = os.path.join(self.out, "datapacks", "dnt-end-castle", "data",
                            "nova_structures", "worldgen")
        self.assertTrue(os.path.isfile(os.path.join(base, "feature", "chorus_plant.json")))
        self.assertFalse(os.path.exists(os.path.join(base, "configured_feature")),
                         "legacy worldgen/configured_feature must not be emitted")
        with open(os.path.join(base, "feature", "chorus_plant.json"), encoding="utf-8") as fh:
            self.assertEqual(json.load(fh), {"type": "minecraft:chorus_plant", "config": {}})

    def test_placed_feature_chain_resolves_to_feature_dir(self):
        base = os.path.join(self.out, "datapacks", "dnt-end-castle", "data",
                            "nova_structures", "worldgen")
        with open(os.path.join(base, "placed_feature", "chorus_plant.json"), encoding="utf-8") as fh:
            self.assertEqual(json.load(fh)["feature"], "nova_structures:chorus_plant")
        self.assertTrue(os.path.isfile(os.path.join(base, "feature", "chorus_plant.json")))

    def test_feature_pool_element_chain_resolves_to_placed_feature(self):
        base = os.path.join(self.out, "datapacks", "dnt-end-castle", "data",
                            "nova_structures", "worldgen")
        with open(os.path.join(base, "template_pool", "end_castle", "chorus_plant.json"),
                  encoding="utf-8") as fh:
            pool = json.load(fh)
        elements = find_nodes(pool, lambda n: n.get("element_type") == "minecraft:feature_pool_element")
        self.assertEqual([e["feature"] for e in elements], ["nova_structures:chorus_plant"])
        self.assertTrue(os.path.isfile(os.path.join(base, "placed_feature", "chorus_plant.json")))

    def test_manifest_records_final_output_path_for_relocation(self):
        manifest = json.loads(self._read("dnt-manifest.json"))
        rules = manifest["migration"]["rules"]
        self.assertIn("worldgen.configured_feature_to_feature", rules)
        self.assertEqual(rules["worldgen.configured_feature_to_feature"]["files"],
                         ["data/nova_structures/worldgen/feature/chorus_plant.json"])

    # --- the two DnT structures are published under mumu: -------------------------------------

    def _data(self, *parts):
        return os.path.join(self.out, "datapacks", "dnt-end-castle", "data", *parts)

    def test_both_structure_definitions_ship_once(self):
        # One file, one registry id. Keeping the author's namespace definition as well gave the
        # registry two ids for one structure, and /locate's completion listed both.
        for name in migrate.PUBLIC_STRUCTURES:
            self.assertTrue(os.path.isfile(self._data("mumu", "worldgen", "structure", name + ".json")),
                            "缺少 mumu 定义：" + name)
            self.assertFalse(
                os.path.exists(self._data("nova_structures", "worldgen", "structure", name + ".json")),
                "作者命名空间的旧 definition 不得随包发出（否则 /locate 会出现两份）：" + name)

    def test_no_legacy_placement_survives(self):
        # A leftover legacy structure_set would generate a second castle in the same cell, because the
        # concentric_rings placement is deterministic per salt.
        for name in migrate.PUBLIC_STRUCTURES:
            self.assertFalse(
                os.path.exists(self._data("nova_structures", "worldgen", "structure_set", name + ".json")),
                "旧的 placement 不得随包发出：" + name)

    def test_the_new_structure_set_places_the_mumu_id(self):
        for name in migrate.PUBLIC_STRUCTURES:
            doc = json.loads(self._read("datapacks", "dnt-end-castle", "data", "mumu",
                                        "worldgen", "structure_set", name + ".json"))
            self.assertEqual(doc["structures"], [{"structure": "mumu:" + name, "weight": 1}], name)

    def test_castle_placement_regression(self):
        # The density fix must survive the namespace move. These are the project's deliberate values:
        # count 219 -> 112 halves the castles, distance 32 -> 48 pushes ring 1 to 1920 blocks (outside
        # BetterEnd's 1024-block central void). salt/spread stay the author's.
        doc = json.loads(self._read("datapacks", "dnt-end-castle", "data", "mumu",
                                    "worldgen", "structure_set", "end_castle.json"))
        self.assertEqual(doc["placement"], {
            "type": "minecraft:concentric_rings",
            "count": 112,
            "distance": 48,
            "preferred_biomes": "#nova_structures:collections/end_voids",
            "salt": 0,
            "spread": 3,
        })

    def test_ship_placement_is_untouched(self):
        # The ship is a random_spread placement that is dense enough already; the castle rule must not
        # have leaked onto it during the move.
        doc = json.loads(self._read("datapacks", "dnt-end-castle", "data", "mumu",
                                    "worldgen", "structure_set", "end_ship.json"))
        self.assertEqual(doc["placement"], {
            "type": "minecraft:random_spread",
            "salt": 79584390,
            "frequency": 0.25,
            "separation": 10,
            "spacing": 40,
            "spread_type": "triangular",
        })

    def test_structure_tags_point_at_the_placed_id(self):
        castle_map_tag = json.loads(self._read("datapacks", "dnt-end-castle", "data",
                                               "nova_structures", "tags", "worldgen", "structure",
                                               "end_castle.json"))
        self.assertEqual(castle_map_tag["values"], ["mumu:end_castle"],
                         "地图 tag 的 id 不变，但值必须指向真正会生成的结构")
        eyes = json.loads(self._read("datapacks", "dnt-end-castle", "data", "minecraft", "tags",
                                     "worldgen", "structure", "eye_of_ender_located.json"))
        self.assertEqual(eyes["values"], ["mumu:end_castle"],
                         "末影之眼必须能找到城堡")

    def test_find_advancements_point_at_the_placed_id(self):
        for name in migrate.PUBLIC_STRUCTURES:
            doc = json.loads(self._read("datapacks", "dnt-end-castle", "data", "nova_structures",
                                        "advancement", "end", "find_%s.json" % name))
            criterion = doc["criteria"][name]
            structures = criterion["conditions"]["player"]["predicate"]["location"]["structures"]
            self.assertEqual(structures, "mumu:" + name, "成就必须匹配真正会生成的结构")

    def test_the_manifest_records_the_namespace_move(self):
        rules = json.loads(self._read("dnt-manifest.json"))["migration"]["rules"]
        # 2 = both structures, for each of: the definition move, the structure_set file move, the
        # structure_set entry rewrite, the structure-tag values and the advancement predicate.
        self.assertEqual(rules["structure.public_namespace_definition_path"]["occurrences"], 2)
        self.assertEqual(rules["structure.public_namespace_structure_set_path"]["occurrences"], 2)
        self.assertEqual(rules["structure.public_namespace_structure_set"]["occurrences"], 2)
        self.assertEqual(rules["structure.public_namespace_structure_tag"]["occurrences"], 2)
        self.assertEqual(rules["structure.public_namespace_advancement"]["occurrences"], 2)
        # No second copy of any definition is written: the move above is the whole story.
        self.assertNotIn("structure.public_namespace_alias", rules)
        # 1 = the castle map (the ship has no map loot entry).
        self.assertEqual(rules["structure.public_namespace_map_destination"]["occurrences"], 1)
        self.assertEqual(rules["structure.public_namespace_map_destination"]["files"],
                         ["data/nova_structures/loot_table/maps/end_castle.json"])

    def test_validation_is_still_clean(self):
        # The new layout gate runs inside the pipeline, so a green manifest means it passed.
        self.assertTrue(json.loads(self._read("dnt-manifest.json"))["validation"]["ok"])
        self.assertEqual(json.loads(self._read("dnt-manifest.json"))["validation"]["problems"], [])

    def test_loot_modifier_root_is_explicit_sequence(self):
        doc = json.loads(self._read("datapacks", "dnt-end-castle", "data",
                                    "nova_structures", "item_modifier", "loot_modifier.json"))
        self.assertEqual(doc, {"type": "minecraft:sequence", "functions": []},
                         "item_modifier 根列表必须包裹为显式 sequence 且保留文件")

    def test_vault_damage_provider_is_typed_uniform(self):
        doc = json.loads(self._read("datapacks", "dnt-end-castle", "data", "nova_structures",
                                    "loot_table", "chests", "end_castle", "vault_brigattine.json"))
        damage = doc["pools"][4]["entries"][0]["modifier"][0]["damage"]
        self.assertEqual(damage, {"min": 0.01, "max": 0.0, "type": "minecraft:uniform"},
                         "倒序 min/max 必须保留，仅补 type minecraft:uniform")

    def test_spawner_amplifier_provider_is_typed_uniform(self):
        doc = json.loads(self._read("datapacks", "dnt-end-castle", "data", "nova_structures",
                                    "loot_table", "spawners", "end_lighthouse", "normal.json"))
        amplifier = doc["pools"][0]["entries"][5]["modifier"][0]["amplifier"]
        self.assertEqual(amplifier, {"max": 5, "min": 3, "type": "minecraft:uniform"})

    def test_maps_end_castle_drops_retired_map_color(self):
        doc = json.loads(self._read("datapacks", "dnt-end-castle", "data", "nova_structures",
                                    "loot_table", "maps", "end_castle.json"))
        modifier = doc["pools"][0]["entries"][0]["modifier"]
        self.assertEqual(modifier[0]["type"], "minecraft:exploration_map")
        self.assertEqual(modifier[0]["destination"], "mumu:end_castle",
                         "destination 是 HolderSet<Structure> 的 direct holder，必须指向会生成的结构；"
                         "旧 definition 没有 placement，留着会让地图找不到目标")
        self.assertEqual(modifier[0]["decoration"], "minecraft:target_x", "map 装饰不得改动")
        components = modifier[1]["components"]
        self.assertNotIn("minecraft:map_color", components, "已移除的 map_color 组件必须被丢弃")
        self.assertIn("minecraft:item_name", components, "item_name 必须保留")

    def test_manifest_records_three_new_rules(self):
        rules = json.loads(self._read("dnt-manifest.json"))["migration"]["rules"]
        self.assertEqual(rules["loot.item_modifier_root_wrapped"]["occurrences"], 1)
        self.assertEqual(rules["loot.number_provider_missing_type"]["occurrences"], 3)
        self.assertEqual(rules["loot.map_color_component_removed"]["occurrences"], 1)
        self.assertEqual(rules["loot.item_modifier_root_wrapped"]["files"],
                         ["data/nova_structures/item_modifier/loot_modifier.json"])

    def test_source_zip_is_not_modified(self):
        import hashlib

        with open(SOURCE_ZIP, "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
        self.assertEqual(digest, SOURCE_SHA, "原 ZIP 必须保持只读，流水线不得改写它")

    def test_output_is_deterministic(self):
        import hashlib

        def digest(root):
            h = hashlib.sha256()
            for dirpath, _dirs, names in sorted(os.walk(root)):
                for name in sorted(names):
                    p = os.path.join(dirpath, name)
                    h.update(os.path.relpath(p, root).encode("utf-8"))
                    with open(p, "rb") as fh:
                        h.update(fh.read())
            return h.hexdigest()

        out2 = tempfile.mkdtemp(prefix="dnt-out2-")
        self.addCleanup(lambda: __import__("shutil").rmtree(out2, ignore_errors=True))
        from prepare_dnt import main

        self.assertEqual(main(["--input", SOURCE_ZIP, "--output", out2]), 0)
        self.assertEqual(digest(self.out), digest(out2))
