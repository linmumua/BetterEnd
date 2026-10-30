#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/ce_state_balance.py 的专属测试：全部在临时 fixture 上跑，不碰真实目录。"""

import hashlib
import json
import os
import sys
import tempfile
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(PROJECT, "tools"))

import ce_state_balance as bal  # noqa: E402


def dump(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def pack(root, name, namespace=None, enable=True):
    body = "namespace: %s\n" % (namespace or name)
    if not enable:
        body += "enable: false\n"
    dump(os.path.join(root, "resources", name, "pack.yml"), body)


def sha(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


MAPPINGS = """block_state_mappings:
  composter[level=7]: composter[level=6]
  composter[level=5]: composter[level=6]
  brown_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]: brown_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]
  brown_mushroom_block[down=true,east=false,north=true,south=true,up=true,west=true]: brown_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]
  brown_mushroom_block[down=true,east=true,north=false,south=true,up=true,west=true]: brown_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]
  red_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]: red_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]
  red_mushroom_block[down=true,east=false,north=true,south=true,up=true,west=true]: red_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]
  red_mushroom_block[down=true,east=true,north=false,south=true,up=true,west=true]: red_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]
  mushroom_stem[down=false,east=true,north=true,south=true,up=true,west=true]: mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]
  mushroom_stem[down=true,east=false,north=true,south=true,up=true,west=true]: mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]
  mushroom_stem[down=true,east=true,north=false,south=true,up=true,west=true]: mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]
  note_block[instrument=harp,note=1,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=2,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=3,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=4,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=5,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=6,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=7,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=8,powered=true]: note_block[instrument=harp,note=0,powered=true]
  note_block[instrument=harp,note=9,powered=true]: note_block[instrument=harp,note=0,powered=true]
"""

TEMPLATES = """templates:
  default:block_state/pillar:
    properties:
      axis:
        type: axis
        default: y
    appearances:
      axisY:
        auto_state: solid
        model: {path: "${pillar_model}"}
    variants:
      axis=x:
        appearance: axisY
"""


def build_root(tmp, blocks, mappings=MAPPINGS, visual=None, registry=None,
               templates=TEMPLATES, extra_pack_files=None):
    root = os.path.join(tmp, "ce")
    dump(os.path.join(root, "config.yml"), "settings: {}\n")
    dump(os.path.join(root, "cache", "visual_block_states.json"),
         json.dumps(visual or {}, ensure_ascii=False, indent=1))
    dump(os.path.join(root, "cache", "custom_block_states.json"),
         json.dumps(registry or {}, ensure_ascii=False, indent=1))
    pack(root, "internal")
    dump(os.path.join(root, "resources", "internal", "configuration", "mappings.yml"),
         mappings)
    pack(root, "default_templates", namespace="default")
    dump(os.path.join(root, "resources", "default_templates", "configuration",
                      "templates", "block_states.yml"), templates)
    pack(root, "betterend")
    dump(os.path.join(root, "resources", "betterend", "configuration", "blocks",
                      "blocks.yml"), blocks)
    for rel, text in (extra_pack_files or {}).items():
        dump(os.path.join(root, rel), text)
    return root


class BalanceTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    # --- 解析与展开 -------------------------------------------------------

    def test_duplicate_key_is_manual_review_not_crash(self):
        blocks = "blocks:\n  betterend:a:\n    state: {state: 'composter[level=7]'}\n" \
                 "  betterend:a:\n    state: {state: 'composter[level=5]'}\n"
        root = build_root(self.tmp, blocks)
        plan, patches, report = bal.build_plan(root)
        self.assertTrue(any("重复键" in m["reason"] for m in report["manual_review"]))
        self.assertEqual(plan["changes"], [])

    def test_template_macro_expansion(self):
        blocks = ("blocks:\n"
                  "  betterend:log:\n"
                  "    state:\n"
                  "      template: default:block_state/pillar\n"
                  "      arguments:\n"
                  "        pillar_model: betterend:block/${tree}_log\n")
        root = build_root(self.tmp, blocks, visual={"betterend:log[axis=x]":
                                                    "minecraft:note_block[instrument=harp,note=1,powered=true]"})
        # ${tree} 未提供 -> 该外观必须进 manual_review，而不是猜
        _, _, report = bal.build_plan(root)
        self.assertTrue(any("无法展开" in m["reason"] for m in report["manual_review"]))

        blocks = ("blocks:\n"
                  "  betterend:log:\n"
                  "    state:\n"
                  "      template: default:block_state/pillar\n"
                  "      arguments:\n"
                  "        pillar_model: betterend:block/a_log\n")
        root = build_root(self.tmp, blocks)
        _, _, report = bal.build_plan(root)
        self.assertEqual(report["audit"]["appearances"], 1)
        self.assertEqual(report["audit"]["donor_kinds"], {"auto": 1})

    def test_factory_instances_expand_and_stay_unpatchable(self):
        blocks = ("config_factory#fam:\n"
                  "  instances:\n"
                  "    - tree: alpha\n"
                  "    - tree: beta\n"
                  "  blueprint:\n"
                  "    blocks:\n"
                  "      betterend:${tree}_planks:\n"
                  "        state: {auto_state: solid, model: {path: 'betterend:block/${tree}_planks'}}\n")
        root = build_root(self.tmp, blocks)
        plan, _, report = bal.build_plan(root)
        self.assertEqual(report["audit"]["blocks"], 2)
        self.assertEqual(report["audit"]["appearances"], 2)
        origins = {a for a in report["audit"]["donor_kinds"]}
        self.assertEqual(origins, {"auto"})
        self.assertEqual(plan["changes"], [])

    def test_version_conditionals_follow_target(self):
        mappings = MAPPINGS + ("  $$26.1~26.2#old:\n"
                               "    composter[level=3]: composter[level=6]\n"
                               "  $$>=26.3#new:\n"
                               "    composter[level=4]: composter[level=6]\n"
                               "  $$fallback:\n"
                               "    composter[level=2]: composter[level=6]\n")
        root = build_root(self.tmp, "blocks: {}\n", mappings=mappings)
        _, _, report = bal.build_plan(root)
        capacity = report["audit"]["pool_families"]["composter"]
        # level 7/5/4 命中；level 3 被排除；fallback 因已有命中而不生效
        self.assertEqual(capacity, 3)

    # --- 冲突与补丁 -------------------------------------------------------

    def test_fixed_literal_conflict_generates_patch_keeping_comments(self):
        blocks = (
            "# 头注释必须保留\n"
            "blocks:\n"
            "  farmersdelight:basket:\n"
            "    # 篮子 A\n"
            "    state:\n"
            "      state: composter[level=7]\n"
            "      model: {path: farmersdelight:block/basket}\n"
            "  farmersdelight:other_basket:\n"
            "    # 篮子 B\n"
            "    state:\n"
            "      state: composter[level=7]\n"
            "      model: {path: farmersdelight:block/other_basket}\n")
        root = build_root(self.tmp, blocks)
        plan, patches, report = bal.build_plan(root)
        self.assertEqual(len(report["conflicts"]), 1)
        self.assertEqual(len(plan["changes"]), 1)
        change = plan["changes"][0]
        self.assertEqual(change["path"], "resources/betterend/configuration/blocks/blocks.yml")
        new_text = patches[change["patch_file"]]
        self.assertIn("# 头注释必须保留", new_text)
        self.assertIn("# 篮子 A", new_text)
        self.assertIn("composter[level=5]", new_text)
        self.assertEqual(new_text.count("composter[level=7]"), 1)
        self.assertEqual(hashlib.sha256(new_text.encode()).hexdigest(),
                         change["after_sha256"])
        self.assertTrue(change["before_sha256"])

    def test_conflict_without_free_candidate_is_manual_review(self):
        blocks = ("blocks:\n"
                  "  a:basket:\n"
                  "    state: {state: 'composter[level=7]', model: {path: a:basket}}\n"
                  "  a:other:\n"
                  "    state: {state: 'composter[level=7]', model: {path: a:other}}\n")
        mappings = "block_state_mappings:\n  composter[level=7]: composter[level=6]\n"
        root = build_root(self.tmp, blocks, mappings=mappings)
        plan, _, report = bal.build_plan(root)
        self.assertEqual(len(report["conflicts"]), 1)
        self.assertEqual(plan["changes"], [])
        self.assertTrue(any("同族无可借用" in m["reason"] for m in report["manual_review"]))

    def test_shared_auto_state_id_is_not_a_conflict(self):
        blocks = (
            "blocks:\n"
            "  farmersdelight:basket:\n"
            "    state:\n"
            "      appearances:\n"
            "        west:\n"
            "          auto_state: {type: non_tintable_leaves, id: basket_west}\n"
            "          model: {path: farmersdelight:block/basket}\n"
            "  farmersdelight:bamboo_basket:\n"
            "    state:\n"
            "      appearances:\n"
            "        west:\n"
            "          auto_state: {type: non_tintable_leaves, id: basket_west}\n"
            "          model: {path: farmersdelight:block/bamboo_basket}\n")
        visual = {"non_tintable_leaves[id=basket_west]": "minecraft:azalea_leaves[waterlogged=false]"}
        root = build_root(self.tmp, blocks, visual=visual)
        plan, _, report = bal.build_plan(root)
        self.assertEqual(report["conflicts"], [])
        shared = report["cache_consistency"]["shared_auto_state_ids"]
        self.assertEqual(len(shared), 1)
        self.assertEqual(shared[0]["ce_state"], "non_tintable_leaves[id=basket_west]")
        self.assertEqual(shared[0]["distinct_models"], 2)
        self.assertEqual(plan["changes"], [])

    # --- 均分 -------------------------------------------------------------

    def test_balance_is_capacity_limited_and_minimal(self):
        visual = {
            "b:cube1": "minecraft:brown_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]",
            "b:cube2": "minecraft:red_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]",
            "b:cube3": "minecraft:red_mushroom_block[down=true,east=false,north=true,south=true,up=true,west=true]",
            "b:cube4": "minecraft:red_mushroom_block[down=true,east=true,north=false,south=true,up=true,west=true]",
            "b:cube5": "minecraft:mushroom_stem[down=false,east=true,north=true,south=true,up=true,west=true]",
            "b:note": "minecraft:note_block[instrument=harp,note=1,powered=true]",
        }
        blocks = ("blocks:\n"
                  + "".join("  %s:\n    state: {auto_state: solid, model: {path: 'm/%s'}}\n"
                            % (k, k) for k in sorted(visual)))
        root = build_root(self.tmp, blocks, visual=visual)
        _, _, report = bal.build_plan(root)
        info = report["balance"]["solid"]
        # Equalisation is by physical property shape, not pool size: note_block has a different shape.
        rows = next(group["families"] for group in report["global_balance"].values()
                    if "brown_mushroom_block" in group["families"])
        self.assertNotIn("note_block", rows)
        self.assertEqual({fam: row["target"] for fam, row in rows.items()},
                         {"brown_mushroom_block": 2, "red_mushroom_block": 2, "mushroom_stem": 1})
        self.assertTrue(all(row["after"] == row["target"] for row in rows.values()))
        self.assertEqual(report["visual_rebalance"]["applied_moves"], 1)
        self.assertEqual(info["status"], "balanced")
        # 幂等：同一输入两次结果一致
        _, _, again = bal.build_plan(root)
        self.assertEqual(report["balance"], again["balance"])

    def test_exhausted_slots_are_reported_not_silently_balanced(self):
        # red/stem 各只有 1 个可借候选且都已被占 -> 不可能再往里塞，必须报残余不平衡
        mappings = ("block_state_mappings:\n"
                    "  red_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]:"
                    " red_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]\n"
                    "  mushroom_stem[down=false,east=true,north=true,south=true,up=true,west=true]:"
                    " mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]\n")
        visual = {
            "b:cube1": "minecraft:red_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]",
            "b:cube2": "minecraft:mushroom_stem[down=false,east=true,north=true,south=true,up=true,west=true]",
            "b:cube3": "minecraft:mushroom_stem[down=true,east=false,north=true,south=true,up=true,west=true]",
            "b:cube4": "minecraft:mushroom_stem[down=true,east=true,north=false,south=true,up=true,west=true]",
        }
        blocks = ("blocks:\n"
                  + "".join("  %s:\n    state: {auto_state: solid, model: {path: 'm/%s'}}\n"
                            % (k, k) for k in sorted(visual)))
        root = build_root(self.tmp, blocks, mappings=mappings, visual=visual)
        _, _, report = bal.build_plan(root)
        info = report["balance"]["solid"]
        self.assertEqual(info["status"], "residual")
        self.assertEqual(report["residual"]["mushroom_stem"],
                         {"excess": 2, "reason": "capacity_exceeded"})
        self.assertTrue(all(group["status"] == "residual" for group in report["global_balance"].values()))
        self.assertEqual(report["visual_rebalance"]["applied_moves"], 0)
        self.assertEqual(sum(info["by_family"].values()), 4, "keep all existing IDs; never silently drop demand")

    # --- 输出契约 ---------------------------------------------------------

    def test_plan_schema_guards_and_readonly(self):
        blocks = ("blocks:\n"
                  "  a:basket:\n"
                  "    state: {state: 'composter[level=7]', model: {path: a:basket}}\n"
                  "  a:other:\n"
                  "    state: {state: 'composter[level=7]', model: {path: a:other}}\n")
        root = build_root(self.tmp, blocks)
        before = {p: sha(os.path.join(root, p))
                  for p in ("config.yml", "cache/visual_block_states.json",
                            "cache/custom_block_states.json",
                            "resources/betterend/configuration/blocks/blocks.yml")}
        out = os.path.join(self.tmp, "out")
        plan, patches, report = bal.build_plan(root)
        bal.write_outputs(out, plan, patches, report)

        self.assertEqual(plan["schema"], 1)
        self.assertEqual(plan["root"], os.path.abspath(root))
        for key in ("schema", "root", "changes", "guards", "report"):
            self.assertIn(key, plan)
        guard_paths = {g["path"] for g in plan["guards"]}
        self.assertIn("config.yml", guard_paths)
        self.assertIn("cache/custom_block_states.json", guard_paths)
        self.assertIn("cache/visual_block_states.json", guard_paths)
        self.assertIn("resources/internal/configuration/mappings.yml", guard_paths)
        self.assertTrue(all(g["before_sha256"] for g in plan["guards"]))
        self.assertTrue(os.path.isfile(os.path.join(out, "report.yml")))
        for change in plan["changes"]:
            patch_path = os.path.join(out, change["patch_file"])
            self.assertTrue(patch_path.startswith(os.path.join(out, "patches")))
            self.assertTrue(os.path.isfile(patch_path))
            with open(patch_path, encoding="utf-8") as fh:
                self.assertEqual(hashlib.sha256(fh.read().encode()).hexdigest(),
                                 change["after_sha256"])
        # 目标目录只读：所有 guard 文件哈希不变
        after = {p: sha(os.path.join(root, p)) for p in before}
        self.assertEqual(before, after)

    def test_visual_rebalance_patch_is_applicable_and_idempotent(self):
        visual = {
            "b:cube1": "minecraft:brown_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]",
            "b:cube2": "minecraft:red_mushroom_block[down=false,east=true,north=true,south=true,up=true,west=true]",
            "b:cube3": "minecraft:red_mushroom_block[down=true,east=false,north=true,south=true,up=true,west=true]",
            "b:cube4": "minecraft:red_mushroom_block[down=true,east=true,north=false,south=true,up=true,west=true]",
            "b:cube5": "minecraft:mushroom_stem[down=false,east=true,north=true,south=true,up=true,west=true]",
        }
        blocks = ("blocks:\n"
                  + "".join("  %s:\n    state: {auto_state: solid, model: {path: 'm/%s'}}\n"
                            % (k, k) for k in sorted(visual)))
        root = build_root(self.tmp, blocks, visual=visual)
        plan, patches, report = bal.build_plan(root)

        self.assertTrue(report["visual_rebalance"]["applied_moves"] > 0)
        self.assertEqual([c["path"] for c in plan["changes"]],
                         ["cache/visual_block_states.json"])
        change = plan["changes"][0]
        new_map = patches[change["patch_file"]]
        self.assertEqual(json.loads(new_map), json.loads(patches[change["patch_file"]]))
        before = json.loads(open(os.path.join(root, "cache", "visual_block_states.json"),
                                 encoding="utf-8").read())
        after = json.loads(new_map)
        self.assertEqual(set(before), set(after), "键集合必须保持，只改 donor 值")
        # 每个 donor 都必须是该家族 mappings 里的合法空闲候选
        pairs = list(yaml.safe_load(MAPPINGS)["block_state_mappings"].items())
        pools, _ = bal.derive_pools([("x", bal.canon_key(a), bal.canon_key(b))
                                     for a, b in pairs])
        for key, donor in after.items():
            if before[key] == donor:
                continue
            fam = bal.family_of(bal.canon_key(donor))
            self.assertIn(bal.canon_key(donor), pools[fam])
        # 应用补丁后重跑：必须幂等（0 移动）
        with open(os.path.join(root, "cache", "visual_block_states.json"), "w",
                  encoding="utf-8", newline="\n") as fh:
            fh.write(new_map)
        plan2, _, report2 = bal.build_plan(root)
        self.assertEqual(report2["visual_rebalance"]["applied_moves"], 0)
        self.assertEqual(plan2["changes"], [])

    def test_entity_render_claim_conflicts_with_plain_model(self):
        # FD cover 用 entity_renderer，BE gate 用普通 model，钉同一个 forced state -> 真冲突
        blocks = (
            "blocks:\n"
            "  farmersdelight:gate_cover:\n"
            "    state:\n"
            "      state: 'mangrove_fence_gate[facing=east,in_wall=false,open=false,powered=true]'\n"
            "      entity_renderer:\n"
            "        - {type: item_display, item: fd:gate_elements}\n"
            "  betterend:mossy_gate:\n"
            "    state:\n"
            "      state: 'mangrove_fence_gate[facing=east,in_wall=false,open=false,powered=true]'\n"
            "      model: {path: betterend:block/mossy_gate}\n")
        mappings = ("block_state_mappings:\n"
                    "  mangrove_fence_gate[facing=east,in_wall=false,open=false,powered=true]:"
                    " mangrove_fence_gate[facing=east,in_wall=false,open=false,powered=false]\n")
        root = build_root(self.tmp, blocks, mappings=mappings)
        _, _, report = bal.build_plan(root)
        states = [c["state"] for c in report["conflicts"]]
        self.assertIn("mangrove_fence_gate[facing=east,in_wall=false,open=false,powered=true]",
                      states)
        # 唯一空闲候选被两边占着 -> 只能报告，不许猜
        self.assertTrue(any("同族无可借用" in m["reason"]
                            for m in report["manual_review"]))

    def test_entity_only_sharing_is_not_reported_as_conflict(self):
        blocks = (
            "blocks:\n"
            "  farmersdelight:skillet:\n"
            "    state:\n"
            "      appearances:\n"
            "        east:\n"
            "          state: 'iron_trapdoor[half=bottom,open=false,powered=true]'\n"
            "          transparent: true\n"
            "        east_support:\n"
            "          state: 'iron_trapdoor[half=bottom,open=false,powered=true]'\n"
            "          entity_renderer:\n"
            "            - {type: item_display, item: fd:skillet}\n")
        root = build_root(self.tmp, blocks)
        _, _, report = bal.build_plan(root)
        self.assertEqual(report["conflicts"], [])
        self.assertEqual(report["shared_render_bases"]["count"], 1)

    def test_two_family_pools_are_balanced_not_treated_as_sinks(self):
        # 树叶只有两个家族，容量各自都装得下整组；不能被误判成两个 sink 而跳均分
        mappings = ("block_state_mappings:\n"
                    "  azalea_leaves[distance=1,persistent=true,waterlogged=false]:"
                    " azalea_leaves[distance=7,persistent=true,waterlogged=false]\n"
                    "  azalea_leaves[distance=2,persistent=true,waterlogged=false]:"
                    " azalea_leaves[distance=7,persistent=true,waterlogged=false]\n"
                    "  azalea_leaves[distance=3,persistent=true,waterlogged=false]:"
                    " azalea_leaves[distance=7,persistent=true,waterlogged=false]\n"
                    "  flowering_azalea_leaves[distance=1,persistent=true,waterlogged=false]:"
                    " flowering_azalea_leaves[distance=7,persistent=true,waterlogged=false]\n"
                    "  flowering_azalea_leaves[distance=2,persistent=true,waterlogged=false]:"
                    " flowering_azalea_leaves[distance=7,persistent=true,waterlogged=false]\n"
                    "  flowering_azalea_leaves[distance=3,persistent=true,waterlogged=false]:"
                    " flowering_azalea_leaves[distance=7,persistent=true,waterlogged=false]\n")
        visual = {
            "l:1": "minecraft:azalea_leaves[distance=1,persistent=true,waterlogged=false]",
            "l:2": "minecraft:azalea_leaves[distance=2,persistent=true,waterlogged=false]",
            "l:3": "minecraft:azalea_leaves[distance=3,persistent=true,waterlogged=false]",
            "l:4": "minecraft:flowering_azalea_leaves[distance=1,persistent=true,waterlogged=false]",
        }
        blocks = ("blocks:\n"
                  + "".join("  %s:\n    state: {auto_state: non_tintable_leaves,"
                            " model: {path: 'm/%s'}}\n" % (k, k) for k in sorted(visual)))
        root = build_root(self.tmp, blocks, mappings=mappings, visual=visual)
        _, _, report = bal.build_plan(root)
        info = report["balance"]["non_tintable_leaves"]
        rows = next(group["families"] for group in report["global_balance"].values()
                    if "azalea_leaves" in group["families"])
        self.assertEqual({fam: row["target"] for fam, row in rows.items()},
                         {"azalea_leaves": 2, "flowering_azalea_leaves": 2})
        self.assertTrue(all(row["after"] == row["target"] for row in rows.values()))
        self.assertEqual(info["status"], "balanced")
        self.assertEqual(report["visual_rebalance"]["applied_moves"], 1)

    def test_disabled_pack_is_ignored(self):
        root = build_root(self.tmp, "blocks:\n  a:b:\n    state: {auto_state: solid}\n")
        dump(os.path.join(root, "resources", "betterend", "pack.yml"),
             "namespace: betterend\nenable: false\n")
        _, _, report = bal.build_plan(root)
        self.assertEqual(report["audit"]["blocks"], 0)
        self.assertEqual(report["packs_enabled"], ["default_templates", "internal"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
