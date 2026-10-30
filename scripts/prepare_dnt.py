#!/usr/bin/env python3
"""Prepare the bundled DnT "End Castle" data/resource packs from the source ZIP.

Usage:
    python scripts/prepare_dnt.py --input "<DnT End Castle Standalone v1.3.4.zip>" \
                                  --output plugin/build/generated/dnt

The source archive is never modified. The script emits, deterministically:

    <output>/datapacks/dnt-end-castle/{pack.mcmeta, data/...}
    <output>/craftengine/dnt_end_castle/{pack.yml, resourcepack/assets/..., manifest.json}

Every migration rule is schema-checked against the 26.3 server source and the
generated pack is validated (reference closure, discriminators, formats) before
the script reports success. Failures are loud: the process exits non-zero.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dntlib import migrate, nbt, safezip, structure_ids, validate  # noqa: E402

SOURCE_SHA256 = "22d0040e162688d750bbfb122026f9881df42bd5e26f63cb0b5b1f6047c07e98"

DATAPACK_DIR = "datapacks/dnt-end-castle"
CE_DIR = "craftengine/dnt_end_castle"
DATAPACK_DESCRIPTION = "Dungeons and Taverns - End Castle (26.3 migration)"
CE_PACK_YML = """author: Nova Wostra (Dungeons and Taverns); migrated by linmumua
description: DnT End Castle content for CraftEngine (26.3)
version: '1.3.4'
namespace: dnt
enable: true
"""


def dumps(obj: Any) -> bytes:
    """Deterministic UTF-8 JSON (stable key order, 2-space indent, trailing NL)."""
    return (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def write_file(path: str, data: bytes) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)


def build_data_pack(archive: safezip.ZipArchive, out_root: str, report: migrate.Report,
                    dispatch: Optional[Dict[str, Dict[int, str]]] = None) -> Tuple[List[str], List[str], Dict[str, Any], List[validate.Problem]]:
    pack_root = os.path.join(out_root, DATAPACK_DIR)
    files: List[str] = []
    relnames: List[str] = []
    docs: Dict[str, Any] = {}
    problems: List[validate.Problem] = []

    write_file(os.path.join(pack_root, "pack.mcmeta"), dumps(migrate.rule_pack_mcmeta(DATAPACK_DESCRIPTION)))
    files.append("%s/pack.mcmeta" % DATAPACK_DIR)

    emitted: set = set()

    def emit(relname: str, data: bytes) -> None:
        if relname in emitted:
            raise ValueError("输出路径冲突：%s 被生成两次" % relname)
        emitted.add(relname)
        write_file(os.path.join(pack_root, relname), data)
        files.append("%s/%s" % (DATAPACK_DIR, relname))
        relnames.append(relname)

    # Final output path per source entry (worldgen/configured_feature ->
    # worldgen/feature); collisions are rejected, never hand-fixed later.
    source_names = list(archive.by_prefix("data/"))
    out_paths = migrate.assign_output_paths(source_names)

    # pass 1: load every data document so loot references can be resolved
    json_docs: Dict[str, Any] = {}
    for name in archive.by_prefix("data/"):
        if name.endswith(".json"):
            json_docs[name] = json.loads(archive.read(name).decode("utf-8"))

    def resolve_item_modifier(rid: Optional[str]) -> Optional[Any]:
        if not rid or ":" not in rid:
            return None
        ns, path = rid.split(":", 1)
        return json_docs.get("data/%s/item_modifier/%s.json" % (ns, path))

    # The custom_model_data dispatch table is read by two consumers (the loot/NBT
    # rewrite and the generated CE item models) and is derived purely from the
    # source ZIP, so the caller computes it ONCE and hands the same dict to both.
    # Recomputing it here would re-read and re-parse every
    # assets/minecraft/items/*.json for an identical result.
    dispatch = migrate.build_custom_model_dispatch(archive) if dispatch is None else dispatch

    # pass 2: migrate and write
    for name in archive.by_prefix("data/"):
        out_name = out_paths[name]
        if name.endswith(".json"):
            obj = json_docs[name]
            category = migrate.target_category(name)
            if category in migrate._LOOT_CATEGORIES:
                migrate.rule_loot_discriminators(obj, out_name, report)
                obj, missing, non_empty = migrate.inline_reference_functions(obj, resolve_item_modifier, out_name, report)
                for miss in missing:
                    problems.append(validate.Problem("dangling-reference", "%s -> %s" % (out_name, miss)))
                for ne in non_empty:
                    problems.append(validate.Problem("nonempty-reference-target", "%s -> %s" % (out_name, ne)))
                migrate.rule_cmd_to_item_model(obj, dispatch, out_name, report)
                migrate.rule_number_provider_types(obj, out_name, report)
                migrate.rule_remove_map_color_component(obj, out_name, report)
                # Registries.ITEM_MODIFIER dispatches on "type" (LootItemFunctions.
                # TYPED_CODEC), so a bare-array root is illegal; wrap it last, after
                # every legacy function item has been migrated and references inlined.
                if category == "item_modifier":
                    obj = migrate.wrap_item_modifier_root(obj, out_name, report)
            if category in ("enchantment", "advancement"):
                migrate.rule_condition_discriminator(obj, out_name, report)
                # EntityPredicate only occurs inside enchantment requirements and
                # advancement criteria/damage, so loot's "type" discriminators
                # can never be touched.
                migrate.rule_entity_predicate_subtypes(obj, out_name, report)
            if category == "advancement":
                migrate.rule_advancement_predicates(obj, out_name, report)
                migrate.rule_entity_hurt_player_condition(obj, out_name, report)
            if category == "worldgen":
                migrate.rule_spawn_overrides_count(obj, out_name, report)
                # 本项目的平衡改动，不是 26.3 迁移：把末地城堡改稀（219->112 座、首环外推到
                # 1920 格），理由与 concentric_rings 的 count/distance/spread 语义写在
                # migrate.CASTLE_PLACEMENT 的注释里。规则自己限定文件名，只认 end_castle。
                migrate.rule_end_castle_placement(obj, out_name, report)
                # 城堡与飞船没有 heightmap 投影，Y 是作者写死的绝对值；下界低于地形天花板时，
                # 下层会埋在岩石里。规则只抬下界，见 migrate.STRUCTURE_HEIGHT_FLOORS。
                migrate.rule_structure_height_floor(obj, out_name, report)
            # 两个公开结构改挂 mumu: 命名空间。规则按 registry 类型（structure_set /
            # worldgen structure tag / advancement 的 location.structures）自限路径，
            # 因此每份 JSON 都过一遍是安全的：模板池、战利品（探索地图的 destination 是
            # *tag* id）、CE 物品与 biome 一概不动。
            migrate.rule_public_structure_ids(obj, out_name, report)
            # Runs on every JSON, after the condition discriminator, so a
            # time_check already dispatches on "type" when the clock is added.
            migrate.rule_time_check_clock(obj, out_name, report)
            if migrate.relocate_worldgen(name) != name:
                report.hit("worldgen.configured_feature_to_feature", out_name)
            if migrate.relocate_public_structure_definition(name) != name:
                report.hit("structure.public_namespace_definition_path", out_name)
            if migrate.relocate_public_structure_set(name) != name:
                report.hit("structure.public_namespace_structure_set_path", out_name)
            emit(out_name, dumps(obj))
            docs[out_name] = obj
            # 两个公开结构的 definition 已在 assign_output_paths 里直接搬到 mumu: 下（DnT 不再
            # 保留作者命名空间的只读旧 definition），所以这里一般取到 None。旧做法是「原路径留
            # 一份 + mumu 再写一份」，但 /locate structure 的补全来自注册表，两份 id 会让每个
            # 结构在补全里出现两次，按服主要求改成一个 id。
            alias = migrate.public_structure_alias_path(name)
            if alias is not None:
                emit(alias, dumps(obj))
                docs[alias] = obj
                report.hit("structure.public_namespace_alias", alias)
        elif name.endswith(".mcfunction"):
            text = archive.read(name).decode("utf-8")
            migrated, changed = migrate.migrate_hand_items(text)
            if changed:
                report.hit("function.item_stack_to_equipment", out_name, changed)
            emit(out_name, migrated.encode("utf-8"))
        elif name.endswith(".nbt"):
            raw = archive.read(name)
            root_name, root = nbt.load_bytes(raw)
            if migrate.rule_cmd_to_item_model_nbt(root, dispatch, out_name, report):
                emit(out_name, nbt.dump_bytes(root_name, root))
            else:
                emit(out_name, raw)
        else:
            emit(out_name, archive.read(name))

    # restore the referenced function (provenance recorded in the manifest)
    vanish = "data/nova_structures/function/phantom_boss_vanish.mcfunction"
    if not archive.has(vanish):
        emit(vanish, (migrate.PHANTOM_BOSS_VANISH_LINE + "\n").encode("utf-8"))
        report.hit("function.phantom_boss_vanish_restored", vanish)

    problems.extend(validate.check_internal_closure(relnames, docs))
    # The "definition twice, placement once, no legacy placement" rule is shared with
    # the Echoes pack (structure_ids), so both packs are held to the same one; its
    # (kind, detail) pairs use the same kind names validate shipped, so the manifest
    # report reads the same. A separate test pins this check and
    # validate.check_public_structure_layout to the same verdicts, so switching the
    # pipeline over cannot quietly weaken it.
    problems.extend(validate.Problem(kind, detail) for kind, detail in
                    structure_ids.check_layout(structure_ids.DNT_LEGACY_NAMESPACE,
                                               relnames, docs))
    problems.extend(validate.check_no_legacy_configured_feature(relnames))
    problems.extend(validate.check_time_check_clock(relnames, docs))
    return files, relnames, docs, problems


def build_ce_pack(archive: safezip.ZipArchive, out_root: str, report: migrate.Report,
                  dispatch: Optional[Dict[str, Dict[int, str]]] = None) -> Tuple[List[str], Dict[str, Any]]:
    pack_root = os.path.join(out_root, CE_DIR)
    rp = os.path.join(pack_root, "resourcepack")
    files: List[str] = []
    # Same table the data pack used; see build_data_pack. The consumers only read it.
    dispatch = migrate.build_custom_model_dispatch(archive) if dispatch is None else dispatch

    write_file(os.path.join(pack_root, "pack.yml"), CE_PACK_YML.encode("utf-8"))
    files.append("%s/pack.yml" % CE_DIR)

    # copy every DnT-namespaced asset verbatim
    for name in archive.by_prefix("assets/dnt/"):
        if name.endswith("/"):
            continue
        write_file(os.path.join(rp, name), archive.read(name))
        files.append("%s/resourcepack/%s" % (CE_DIR, name))

    # fold minecraft-namespace lang into dnt (lang files merge across namespaces,
    # so this avoids clobbering vanilla/CE en_us.json)
    locales = set()
    for name in archive.by_prefix("assets/minecraft/lang/"):
        locales.add(name.rsplit("/", 1)[-1])
    for name in archive.by_prefix("assets/dnt/lang/"):
        locales.add(name.rsplit("/", 1)[-1])
    for locale in sorted(locales):
        merged: Dict[str, Any] = {}
        dnt_name = "assets/dnt/lang/%s" % locale
        if archive.has(dnt_name):
            merged.update(json.loads(archive.read(dnt_name).decode("utf-8")))
        mc_name = "assets/minecraft/lang/%s" % locale
        if archive.has(mc_name):
            for key, value in json.loads(archive.read(mc_name).decode("utf-8")).items():
                merged.setdefault(key, value)
            report.hit("ce.minecraft_lang_folded_into_dnt", mc_name)
        target = "assets/dnt/lang/%s" % locale
        write_file(os.path.join(rp, target), dumps(merged))
        rel = "%s/resourcepack/%s" % (CE_DIR, target)
        if rel not in files:
            files.append(rel)

    # generate one dnt item model definition per dispatched variant
    generated: List[str] = []
    for item_id, table in sorted(dispatch.items()):
        for cmd in sorted(table):
            model = table[cmd]
            model_id = migrate.item_model_id_for_model(model)
            target = "assets/dnt/items/%s.json" % model_id.split(":", 1)[1]
            body = {"model": {"type": "minecraft:model", "model": model}}
            write_file(os.path.join(rp, target), dumps(body))
            rel = "%s/resourcepack/%s" % (CE_DIR, target)
            if rel not in files:
                files.append(rel)
            generated.append(model_id)
            report.hit("ce.item_model_generated", rel)

    info = {
        "namespace": "dnt",
        "dispatch": {k: {str(c): v for c, v in sorted(t.items())} for k, t in sorted(dispatch.items())},
        "generated_item_models": sorted(set(generated)),
    }
    return files, info


def build_manifest(archive: safezip.ZipArchive, out_root: str, report: migrate.Report, ce_info: Dict[str, Any], problems: List[validate.Problem]) -> Dict[str, Any]:
    credits = ""
    if archive.has("credits.txt"):
        credits = archive.read("credits.txt").decode("utf-8", errors="replace").strip()
    return {
        "source": {
            "file": os.path.basename(archive.path),
            "sha256": archive.sha256,
            "entries": len(archive.names),
        },
        "target": {
            "minecraft": "26.3",
            "world_version": migrate.TARGET_DATA_VERSION,
            "data_pack_format": list(migrate.TARGET_DATA_FORMAT),
            "resource_pack_format": list(migrate.TARGET_RESOURCE_FORMAT),
        },
        "migration": report.as_manifest_block(),
        # The eleven public mumu ids are shared with the Echoes pack, so the manifest
        # records what this pack publishes rather than leaving the split implicit.
        "structures": {
            "public_namespace": migrate.PUBLIC_STRUCTURE_NAMESPACE,
            "public_ids": [migrate.public_structure_rid(name)
                           for name in migrate.PUBLIC_STRUCTURES],
            "legacy_namespace": structure_ids.DNT_LEGACY_NAMESPACE,
            # 作者命名空间的 id 不再随包发出（definition 被搬到 mumu: 下），留这张表是为了让
            # 升级脚本知道"这些旧 id 曾经存在"：旧世界里已经生成的城堡/飞船以其为键，重新出现
            # 时不会再解析。它是记录，不是已发布的 registry id。
            "legacy_ids_removed": [structure_ids.legacy_rid(structure_ids.DNT_LEGACY_NAMESPACE, name)
                                   for name in migrate.PUBLIC_STRUCTURES],
            "legacy_definitions_unplaced": False,
        },
        "restored_functions": {
            "nova_structures:function/phantom_boss_vanish.mcfunction": migrate.PHANTOM_BOSS_VANISH_PROVENANCE,
        },
        "craftengine": ce_info,
        "validation": {
            "problems": [p.detail for p in problems],
            "ok": not problems,
        },
        "credits": credits,
        "unshipped_source_assets": {
            "pack.png": "DnT resource-pack icon; CraftEngine owns pack icons, so it is not re-shipped.",
            "assets/minecraft/items/*.json": "vanilla-namespace item model dispatchers replaced by dnt item_model components.",
            "assets/minecraft/lang/*.json": "folded into assets/dnt/lang/*.json (merged, non-destructive).",
        },
    }


def build_pack(archive: safezip.ZipArchive, out_root: str,
               report: migrate.Report) -> Tuple[List[str], List[str], List[validate.Problem], Dict[str, Any]]:
    """Emit both halves under ``out_root``; returns (files, ce_files, problems, manifest).

    The only place that decides *how many times* the custom_model_data dispatch is
    derived: once, here, and then handed to the data-pack rewrite and the generated
    CE item models. Both consumers read it and never mutate it, so the emitted bytes
    are the same as when each half derived its own private copy.
    """
    dispatch = migrate.build_custom_model_dispatch(archive)
    files, relnames, docs, problems = build_data_pack(archive, out_root, report, dispatch)
    problems.extend(validate.check_loot_discriminators(relnames, docs))
    problems.extend(validate.check_number_provider_types(relnames, docs))
    problems.extend(validate.check_no_map_color_component(relnames, docs))
    with open(os.path.join(out_root, DATAPACK_DIR, "pack.mcmeta"), encoding="utf-8") as fh:
        mcmeta = json.load(fh)
    problems.extend(validate.check_pack_mcmeta(mcmeta, migrate.TARGET_DATA_FORMAT[0]))
    ce_files, ce_info = build_ce_pack(archive, out_root, report, dispatch)
    manifest = build_manifest(archive, out_root, report, ce_info, problems)
    return files, ce_files, problems, manifest


def check_existing_output(archive: safezip.ZipArchive, out_root: str) -> int:
    """Regenerate into a temporary directory and diff it against ``out_root``.

    The source of truth is the source ZIP plus these rules, never the tree that
    happens to be on disk: a stale tree (an older script run, a hand edit, a
    partially deleted output) is exactly what this reports. Nothing under
    ``out_root`` is read-modified-written, so a failing check leaves the build
    output untouched.
    """
    if not os.path.isdir(out_root):
        print("error: --check 需要一个已生成的输出目录：%s" % out_root, file=sys.stderr)
        return 2

    fresh_root = tempfile.mkdtemp(prefix="dnt-check-")
    try:
        report = migrate.Report()
        _, _, problems, manifest = build_pack(archive, fresh_root, report)
        # The manifest and the credits file are part of the output tree too, so they
        # are compared like every other byte.
        write_file(
            os.path.join(fresh_root, "dnt-manifest.json"),
            (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
        )
        if archive.has("credits.txt"):
            write_file(os.path.join(fresh_root, "dnt-CREDITS.txt"), archive.read("credits.txt"))

        drift = structure_ids.compare_manifest(
            structure_ids.tree_manifest(fresh_root), out_root)
    finally:
        shutil.rmtree(fresh_root, ignore_errors=True)

    if problems:
        print("源 ZIP 生成的产物本身未通过校验（%d 处）：" % len(problems), file=sys.stderr)
        for problem in problems:
            print("  - %s: %s" % (problem.kind, problem.detail), file=sys.stderr)
    if drift:
        print("现有产物与源 ZIP 重新生成的结果不一致（%d 处）：" % len(drift), file=sys.stderr)
        for problem in drift:
            print("  - " + problem, file=sys.stderr)
        return 1
    if problems:
        return 1
    print("现有产物与源重新生成的结果逐字节一致：%s" % out_root)
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Prepare the migrated DnT End Castle packs.")
    parser.add_argument("--input", required=True, help="path to the DnT source ZIP (never modified)")
    parser.add_argument("--output", required=True, help="output root (datapacks/ + craftengine/)")
    parser.add_argument("--sha256", default=SOURCE_SHA256, help="expected SHA-256 of the input ZIP")
    parser.add_argument("--check", action="store_true",
                        help="生成到临时目录并与 --output 现有产物逐字节比对，不改动 --output")
    args = parser.parse_args(argv)

    if not os.path.isfile(args.input):
        print("error: input not found: %s" % args.input, file=sys.stderr)
        return 2

    archive = safezip.ZipArchive(args.input, expected_sha256=args.sha256 or None)
    out_root = os.path.abspath(args.output)

    if args.check:
        return check_existing_output(archive, out_root)

    if os.path.isdir(out_root):
        shutil.rmtree(out_root)
    os.makedirs(out_root, exist_ok=True)

    report = migrate.Report()
    files, ce_files, problems, manifest = build_pack(archive, out_root, report)

    # Written to the output ROOT, never inside craftengine/dnt_end_castle: the CE
    # bridge copies that pack directory verbatim into the CraftEngine resources
    # dir, so build-only artefacts must stay out of it.
    write_file(
        os.path.join(out_root, "dnt-manifest.json"),
        (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )
    if archive.has("credits.txt"):
        write_file(os.path.join(out_root, "dnt-CREDITS.txt"), archive.read("credits.txt"))

    print("DnT pack prepared:")
    print("  input sha256 : %s" % archive.sha256)
    print("  datapack     : %s (%d files)" % (DATAPACK_DIR, len(files)))
    print("  craftengine  : %s (%d files)" % (CE_DIR, len(ce_files)))
    for rule in sorted(report.counts):
        print("  migrated     : %-44s %5d" % (rule, report.counts[rule]))
    if problems:
        print("VALIDATION FAILED (%d problems):" % len(problems), file=sys.stderr)
        for problem in problems:
            print("  - %s: %s" % (problem.kind, problem.detail), file=sys.stderr)
        return 1
    print("  validation   : OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
