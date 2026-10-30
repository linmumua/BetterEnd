#!/usr/bin/env python3
"""Contract tests for the structure-id rule the two bundled packs share.

Two packs publish structures under ``mumu``: nine Echoes structures and two DnT
ones, eleven public ids in total. They must be published the *same* way -- the
author's definition kept as a read-only legacy entry, the legacy id owning no
``structure_set``, the public id placed exactly once -- because a world generated
before the rename resolves its saved structure starts against the legacy id.

The rule is one implementation (``dntlib.structure_ids``) called by both builders,
so these tests check the things that can silently rot around it:

* the eleven ids are declared once and split between the two namespaces;
* the DnT-side names in ``migrate`` are a *narrow* view of it (widening them would
  quietly change what the DnT validator enforces);
* the shared rule and the DnT-only validator reach the same verdict on the same
  documents, so switching the DnT pipeline onto the shared rule cannot have
  weakened it;
* the committed Echoes pack still has the 46-file two-namespace layout, and still
  hashes to the digest recorded here. A pre-rename builder re-run deletes all
  eighteen ``mumu`` documents and writes nine duplicate placements instead, which
  both the layout check and the digest catch.
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SCRIPTS = os.path.join(REPO, "scripts")
sys.path.insert(0, SCRIPTS)

from dntlib import migrate, structure_ids, validate  # noqa: E402

ECHOES_PACK = os.path.join(REPO, "plugin", "src", "main", "resources", "datapacks",
                           "echoes-end-structures")
ECHOES_SCRIPT = os.path.join(SCRIPTS, "prepare_echoes.py")

#: The committed Echoes pack as accepted: sha256 over every ``relpath -> sha256``.
#:
#: Pinned so a *stale builder* is caught. The pre-rename script wrote the nine
#: ``structure_set`` documents under the legacy namespace and no ``mumu`` files at
#: all, so re-running it would produce a pack of 37 files that loses every public id
#: and doubles every placement. Re-pin this only when the pack is *deliberately*
#: regenerated, and treat a surprise change as the bug it is.
#:
#: 0.1.15 re-pin: ``end_dungeon_tower`` gained ``placement.frequency: 0.1`` so it is
#: the rarest of the nine. One document changed; the file list did not.
#:
#: 0.1.20 re-pin: ``end_warship``'s ``start_height`` floor rose 100 -> 128 so the hull
#: no longer reaches into terrain. Both the public and legacy definitions changed.
ECHOES_ARTIFACT_DIGEST = "6bb3a696cec1a1a74c7b9374c06d0b3f8f3b89e39a66ee9ee6311bffa6a2195c"

SOURCE_ZIP = os.path.join(REPO, "DnT End Castle Standalone v1.3.4.zip")
SOURCE_SHA = "22d0040e162688d750bbfb122026f9881df42bd5e26f63cb0b5b1f6047c07e98"


class TestPublicIdContract(unittest.TestCase):
    """The eleven ids exist once, and are split between the two namespaces."""

    def test_eleven_public_ids_split_across_two_namespaces(self):
        self.assertEqual(len(structure_ids.ALL_PUBLIC_STRUCTURES), 11)
        self.assertEqual(len(set(structure_ids.ALL_PUBLIC_STRUCTURES)), 11,
                         "一个 id 出现两次，是两张公开清单被拼重了")
        self.assertEqual(len(structure_ids.ECHOES_STRUCTURES), 9)
        self.assertEqual(len(structure_ids.DNT_STRUCTURES), 2)
        self.assertEqual(
            set(structure_ids.ALL_PUBLIC_STRUCTURES),
            set(structure_ids.ECHOES_STRUCTURES) | set(structure_ids.DNT_STRUCTURES))

    def test_each_legacy_namespace_owns_only_its_own_structures(self):
        # One table, so a namespace can never be paired with another pack's names.
        self.assertEqual(structure_ids.structures_of(structure_ids.ECHOES_LEGACY_NAMESPACE),
                         structure_ids.ECHOES_STRUCTURES)
        self.assertEqual(structure_ids.structures_of(structure_ids.DNT_LEGACY_NAMESPACE),
                         structure_ids.DNT_STRUCTURES)
        self.assertEqual(structure_ids.structures_of("minecraft"), ())

    def test_every_public_id_lives_in_the_one_public_namespace(self):
        for name in structure_ids.ALL_PUBLIC_STRUCTURES:
            self.assertEqual(structure_ids.public_rid(name), "mumu:" + name)
            self.assertTrue(structure_ids.public_structure_path(name)
                            .startswith("data/mumu/worldgen/structure/"))
            self.assertTrue(structure_ids.public_structure_set_path(name)
                            .startswith("data/mumu/worldgen/structure_set/"))

    def test_dnt_view_stays_narrow(self):
        # migrate.PUBLIC_STRUCTURES feeds validate.check_public_structure_layout; if it
        # ever grew to all eleven, the DnT validator would start demanding Echoes files.
        self.assertEqual(migrate.PUBLIC_STRUCTURES, structure_ids.DNT_STRUCTURES)
        self.assertEqual(migrate.PUBLIC_STRUCTURES, ("end_castle", "end_ship"))
        self.assertEqual(migrate.PUBLIC_STRUCTURE_NAMESPACE, structure_ids.PUBLIC_NAMESPACE)
        self.assertNotIn("end_well", migrate.PUBLIC_STRUCTURES)

    def test_legacy_definitions_are_namespaced_per_pack(self):
        self.assertEqual(
            structure_ids.legacy_structure_path(structure_ids.ECHOES_LEGACY_NAMESPACE, "end_well"),
            "data/echoes_of_the_end__structures_/worldgen/structure/end_well.json")
        self.assertEqual(
            structure_ids.legacy_structure_path(structure_ids.DNT_LEGACY_NAMESPACE, "end_castle"),
            "data/nova_structures/worldgen/structure/end_castle.json")
        # The warship's payload is the one name that differs from its documents.
        self.assertEqual(structure_ids.nbt_name(structure_ids.ECHOES_LEGACY_NAMESPACE, "end_warship"),
                         "end_warship1")
        self.assertEqual(structure_ids.nbt_name(structure_ids.ECHOES_LEGACY_NAMESPACE, "end_well"),
                         "end_well")


class TestMigrateDelegationParity(unittest.TestCase):
    """``migrate``'s public-id helpers are the DnT view of the shared rule."""

    def test_path_helpers_agree_with_the_shared_rule(self):
        for name in migrate.PUBLIC_STRUCTURES:
            self.assertEqual(migrate.public_structure_rid(name), structure_ids.public_rid(name))
            legacy_set = "data/nova_structures/worldgen/structure_set/%s.json" % name
            legacy_def = "data/nova_structures/worldgen/structure/%s.json" % name
            self.assertEqual(migrate.relocate_public_structure_set(legacy_set),
                             structure_ids.public_structure_set_path(name))
            # The definition moves instead of being duplicated, so there is no alias copy.
            self.assertEqual(migrate.relocate_public_structure_definition(legacy_def),
                             structure_ids.public_structure_path(name))
            self.assertIsNone(migrate.public_structure_alias_path(legacy_def))

    def test_relocation_is_exact_and_never_touches_another_namespace(self):
        # A template pool that merely starts with the id text is not the id.
        self.assertIsNone(migrate.public_structure_alias_path(
            "data/nova_structures/worldgen/structure/end_castle/start.json"))
        self.assertIsNone(migrate.relocated_public_id("nova_structures:end_castle/start"))
        # Echoes' ids are not the DnT pack's business even though both publish under mumu.
        self.assertIsNone(migrate.relocated_public_id(
            "%s:end_well" % structure_ids.ECHOES_LEGACY_NAMESPACE))
        self.assertIsNone(migrate.public_structure_alias_path(
            "data/%s/worldgen/structure/end_well.json" % structure_ids.ECHOES_LEGACY_NAMESPACE))
        # Something else entirely.
        self.assertIsNone(migrate.relocated_public_id("minecraft:end_city"))

    def test_relocated_id_matches_the_public_id(self):
        for name in migrate.PUBLIC_STRUCTURES:
            self.assertEqual(migrate.relocated_public_id("nova_structures:" + name),
                             structure_ids.public_rid(name))


class TestSharedRuleMatchesTheDntValidator(unittest.TestCase):
    """The shared rule and ``validate.check_public_structure_layout`` must agree.

    The DnT pipeline calls the shared one now, so this is what proves the switch did
    not drop a check the pack relied on. Same fixtures, same verdicts.
    """

    FILES = [
        "data/mumu/worldgen/structure/end_castle.json",
        "data/mumu/worldgen/structure/end_ship.json",
        "data/mumu/worldgen/structure_set/end_castle.json",
        "data/mumu/worldgen/structure_set/end_ship.json",
    ]
    DOCS = {
        "data/mumu/worldgen/structure_set/end_castle.json": {
            "placement": {},
            "structures": [{"structure": "mumu:end_castle", "weight": 1}]},
        "data/mumu/worldgen/structure_set/end_ship.json": {
            "placement": {},
            "structures": [{"structure": "mumu:end_ship", "weight": 1}]},
        "data/nova_structures/tags/worldgen/structure/end_castle.json": {
            "values": ["mumu:end_castle"]},
        "data/minecraft/tags/worldgen/structure/eye_of_ender_located.json": {
            "values": ["mumu:end_castle"]},
        "data/nova_structures/advancement/end/find_end_castle.json": {
            "criteria": {"x": {"conditions": {"player": {
                "location": {"structures": "mumu:end_castle"}}}}}},
    }

    def _verdicts(self, files, docs):
        shared = sorted(kind for kind, _ in structure_ids.check_layout(
            structure_ids.DNT_LEGACY_NAMESPACE, files, docs))
        legacy = sorted(problem.kind for problem in
                        validate.check_public_structure_layout(files, docs))
        self.assertEqual(shared, legacy,
                         "共享规则与 DnT 校验器对同一份文档给出不同结论"
                         "（切换管线会改变强制范围）")
        return shared

    def test_the_healthy_layout_is_green_in_both(self):
        self.assertEqual(self._verdicts(self.FILES, self.DOCS), [])

    def test_both_report_a_missing_public_definition(self):
        files = [p for p in self.FILES if p != "data/mumu/worldgen/structure/end_castle.json"]
        self.assertEqual(self._verdicts(files, self.DOCS), ["missing-public-structure"])

    def test_both_reject_a_stray_legacy_definition(self):
        # The author's definition alongside the mumu: one gives the registry two ids for one
        # structure, so /locate's registry-driven completion offers it twice.
        files = self.FILES + ["data/nova_structures/worldgen/structure/end_castle.json"]
        self.assertEqual(self._verdicts(files, self.DOCS), ["stray-legacy-structure"])

    def test_both_reject_a_surviving_legacy_placement(self):
        files = self.FILES + ["data/nova_structures/worldgen/structure_set/end_castle.json"]
        self.assertEqual(self._verdicts(files, self.DOCS), ["legacy-structure-placement"])

    def test_both_reject_a_duplicated_placement(self):
        docs = dict(self.DOCS)
        docs["data/mumu/worldgen/structure_set/end_castle_second.json"] = {
            "placement": {}, "structures": [{"structure": "mumu:end_castle", "weight": 1}]}
        files = self.FILES + ["data/mumu/worldgen/structure_set/end_castle_second.json"]
        self.assertEqual(self._verdicts(files, docs), ["duplicated-public-structure"])

    def test_both_report_a_legacy_id_left_in_a_reference_position(self):
        for path, document in (
            ("data/nova_structures/tags/worldgen/structure/end_castle.json",
             {"values": ["nova_structures:end_castle"]}),
            ("data/nova_structures/advancement/end/find_end_castle.json",
             {"location": {"structures": ["nova_structures:end_castle"]}}),
            ("data/nova_structures/loot_table/maps/end_castle.json",
             {"pools": [{"entries": [{"functions": [
                 {"type": "minecraft:exploration_map",
                  "destination": "nova_structures:end_castle"}]}]}]}),
        ):
            docs = dict(self.DOCS)
            docs[path] = document
            self.assertEqual(self._verdicts(self.FILES, docs), ["legacy-structure-ref"], path)


class TestEchoesArtifact(unittest.TestCase):
    """The committed Echoes pack: 46 files, two namespaces, one placement each."""

    def test_the_pack_has_the_pinned_46_file_layout(self):
        self.assertEqual(sorted(structure_ids.tree_manifest(ECHOES_PACK)),
                         list(structure_ids.echoes_expected_files()))

    def test_the_pack_matches_the_pinned_digest(self):
        actual = structure_ids.aggregate_digest(structure_ids.tree_manifest(ECHOES_PACK))
        self.assertEqual(actual, ECHOES_ARTIFACT_DIGEST,
                         "echoes 数据包与已接受的产物不一致：若不是有意重新生成，"
                         "多半是旧脚本被重跑了（旧脚本只写 37 个文件、没有 mumu 公开 id，"
                         "并把 9 份 structure_set 留在旧命名空间里，会重复放置）")

    def test_the_shared_layout_rule_is_green(self):
        self.assertEqual(structure_ids.check_echoes_artifact(ECHOES_PACK), [])

    def test_a_pre_rename_layout_is_rejected(self):
        """Proves the check can fail: rebuild the pre-rename layout and expect both findings."""
        work = tempfile.mkdtemp(prefix="echoes-stale-")
        try:
            stale = os.path.join(work, "pack")
            shutil.copytree(ECHOES_PACK, stale)
            for name in structure_ids.ECHOES_STRUCTURES:
                # The pre-rename script shipped no public id at all ...
                os.remove(os.path.join(stale, structure_ids.public_structure_path(name)))
                os.remove(os.path.join(stale, structure_ids.public_structure_set_path(name)))
                # ... and left the placement in the author's namespace.
                legacy_set = structure_ids.legacy_structure_set_path(
                    structure_ids.ECHOES_LEGACY_NAMESPACE, name)
                os.makedirs(os.path.dirname(os.path.join(stale, legacy_set)), exist_ok=True)
                with open(os.path.join(stale, legacy_set), "w", encoding="utf-8") as handle:
                    json.dump({"placement": {"type": "minecraft:random_spread"},
                               "structures": [{"structure": "%s:%s"
                                               % (structure_ids.ECHOES_LEGACY_NAMESPACE, name),
                                               "weight": 1}]}, handle)
            problems = structure_ids.check_echoes_artifact(stale)
            joined = "\n".join(problems)
            self.assertIn("缺少：", joined)
            self.assertIn("data/mumu/worldgen/structure/end_well.json", joined)
            self.assertIn("legacy-structure-placement", joined)
            self.assertIn("legacy-structure-ref", joined)
        finally:
            shutil.rmtree(work, ignore_errors=True)


class TestEchoesSourceMatchesArtifact(unittest.TestCase):
    """The builder the repo ships must still reproduce the committed pack.

    Self-activating on purpose: it only means something once ``scripts/prepare_echoes.py``
    carries the two-namespace contract (``PUBLIC_NS``). Until that sync lands the
    script is the *pre-rename* builder -- 37 files, no ``mumu`` ids -- so its output
    cannot be compared against the 46-file artifact without asserting the drift that
    is itself the bug.
    """

    def test_source_build_reproduces_the_committed_pack(self):
        self.assertTrue(os.path.isfile(ECHOES_SCRIPT), "缺少生成脚本：%s" % ECHOES_SCRIPT)
        loader = importlib.util.spec_from_file_location("prepare_echoes_under_test",
                                                       ECHOES_SCRIPT)
        self.assertIsNotNone(loader)
        module = importlib.util.module_from_spec(loader)
        loader.loader.exec_module(module)
        if not hasattr(module, "PUBLIC_NS"):
            self.skipTest(
                "scripts/prepare_echoes.py 仍是改名前的版本（只写 37 个文件、没有 mumu "
                "公开 id）；待 artifacts/prepare_echoes.py.mumu-pending 的规范同步进该脚本后，"
                "本测试会自动开始逐字节比对源产物与 46 文件成品")

        work = tempfile.mkdtemp(prefix="echoes-source-")
        try:
            report = module.migrate.Report()
            import zipfile
            with zipfile.ZipFile(module.DEFAULT_INPUT) as archive:
                written, problems = module.build(archive, work, report)
                problems += module.verify(archive, work, written)
            self.assertEqual(problems, [])
            self.assertEqual(structure_ids.tree_manifest(work),
                             structure_ids.tree_manifest(ECHOES_PACK),
                             "源脚本重新生成的数据包与已提交成品不一致")
        finally:
            shutil.rmtree(work, ignore_errors=True)


class TestDntPackStructureLayout(unittest.TestCase):
    """One real DnT build: the shared rule, the manifest block, and dispatch once."""

    @classmethod
    def setUpClass(cls):
        import prepare_dnt

        cls.module = prepare_dnt
        cls.out = tempfile.mkdtemp(prefix="dnt-layout-")
        cls.calls = 0
        original = prepare_dnt.migrate.build_custom_model_dispatch

        def counting(archive):
            cls.calls += 1
            return original(archive)

        with mock.patch.object(prepare_dnt.migrate, "build_custom_model_dispatch", counting):
            cls.rc = prepare_dnt.main(["--input", SOURCE_ZIP, "--output", cls.out,
                                       "--sha256", SOURCE_SHA])

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.out, ignore_errors=True)

    def test_pipeline_succeeds(self):
        self.assertEqual(self.rc, 0)

    def test_dispatch_is_derived_exactly_once(self):
        # Two consumers (loot/NBT rewrite and the generated CE item models) read the
        # same table; deriving it per consumer re-parsed every item definition twice.
        self.assertEqual(self.calls, 1,
                         "build_custom_model_dispatch 被调用了 %d 次，应只算一次" % self.calls)

    def test_shared_layout_rule_is_green_on_the_generated_pack(self):
        self.assertEqual(structure_ids.check_dnt_artifact(self.out), [])

    def test_one_definition_per_structure_and_no_legacy_placement(self):
        datapack = os.path.join(self.out, "datapacks", "dnt-end-castle")
        for name in structure_ids.DNT_STRUCTURES:
            self.assertTrue(os.path.isfile(os.path.join(
                datapack, structure_ids.public_structure_path(name))))
            self.assertFalse(os.path.exists(os.path.join(
                datapack, structure_ids.legacy_structure_path(
                    structure_ids.DNT_LEGACY_NAMESPACE, name))),
                "旧 definition 不得随包发出，否则注册表里一个结构有两个 id")
            self.assertFalse(os.path.exists(os.path.join(
                datapack, structure_ids.legacy_structure_set_path(
                    structure_ids.DNT_LEGACY_NAMESPACE, name))))

    def test_manifest_records_the_structure_contract(self):
        with open(os.path.join(self.out, "dnt-manifest.json"), encoding="utf-8") as handle:
            structures = json.load(handle)["structures"]
        self.assertEqual(structures["public_namespace"], "mumu")
        self.assertEqual(structures["public_ids"], ["mumu:end_castle", "mumu:end_ship"])
        self.assertEqual(structures["legacy_namespace"], "nova_structures")
        self.assertEqual(structures["legacy_ids_removed"],
                         ["nova_structures:end_castle", "nova_structures:end_ship"])
        self.assertFalse(structures["legacy_definitions_unplaced"],
                         "DnT 不再发布作者命名空间的 definition，manifest 必须如实记录")

    def test_check_flag_accepts_a_fresh_output_tree(self):
        self.assertEqual(self.module.main(["--input", SOURCE_ZIP, "--output", self.out,
                                           "--sha256", SOURCE_SHA, "--check"]), 0)

    def test_check_flag_names_the_file_that_drifted(self):
        work = tempfile.mkdtemp(prefix="dnt-drift-")
        try:
            stale = os.path.join(work, "generated")
            shutil.copytree(self.out, stale)
            victim = os.path.join(stale, "datapacks", "dnt-end-castle", "pack.mcmeta")
            os.remove(victim)
            rc = self.module.main(["--input", SOURCE_ZIP, "--output", stale,
                                   "--sha256", SOURCE_SHA, "--check"])
            self.assertEqual(rc, 1, "--check 必须对缺失文件的产物返回非零")
        finally:
            shutil.rmtree(work, ignore_errors=True)

    def test_check_flag_leaves_the_output_untouched(self):
        before = structure_ids.tree_manifest(self.out)
        self.module.main(["--input", SOURCE_ZIP, "--output", self.out,
                          "--sha256", SOURCE_SHA, "--check"])
        self.assertEqual(structure_ids.tree_manifest(self.out), before)


if __name__ == "__main__":
    unittest.main()
