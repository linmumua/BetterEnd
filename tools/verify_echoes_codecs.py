#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline 26.3 Codec verification for the bundled Echoes of the End (Structures) pack.

Companion to ``tools/verify_dnt_codecs.py``: same mechanism (real Minecraft codecs driven
through ``tools/codec/DntCodecProbe.java`` under ``Bootstrap.bootStrap()``, plain ``JsonOps``),
different pack. It exists because 0.1.6 shipped with ``block_ignore`` carrying the pre-1.20
palette spelling ``{"Name": ...}`` in all nine ``worldgen/template_pool`` files, which made
``minecraft:worldgen/template_pool`` fail to parse and aborted server startup. The DnT gate
never saw it (that pack has no ``block_ignore`` at all), and the Java contract test was
asserting the broken shape, so nothing decoded these documents with the real 26.3 codecs.

Source of the documents
-----------------------
Defaults to the pack **directory** ``plugin/src/main/resources/datapacks/echoes-end-structures``
so the gate runs before any build. ``--jar`` reads the same tree out of a built shadow jar at
``datapacks/echoes-end-structures/``; that is the form that catches "fixed in the source tree
but the jar still has the old bytes".

Which codecs, and why exactly these
-----------------------------------
``DntCodecProbe`` decodes with ``JsonOps.INSTANCE``, **not** ``RegistryOps``, so any codec that
resolves a registry reference by id cannot be judged with it. Measured against the MU核心 26.3
sources (not guessed):

* ``StructureTemplatePool.DIRECT_CODEC`` reads ``fallback`` as a
  ``Holder<StructureTemplatePool>`` (``RegistryFileCodec``), which under non-``RegistryOps``
  feeds the string ``"minecraft:empty"`` straight into the record codec, so the *whole* pool
  document can never decode offline -- before or after a fix. Informational only.
* ``Structure.DIRECT_CODEC`` -> ``StructureSettings.CODEC`` reads ``biomes`` as a
  ``holderSet(Registries.BIOME)``; a tag reference reports "Registries are not available in this
  context". Informational only.
* ``StructureSet.DIRECT_CODEC`` -> ``StructureSelectionEntry`` holds
  ``Holder<Structure>``. Informational only.
* ``StructurePoolElement.CODEC`` dispatches on ``element_type`` through a *built-in* registry and
  decodes its inline ``processors`` object through ``StructureProcessorType.DIRECT_CODEC``, so an
  ``element`` body decodes for real offline. **Gating.**
* ``StructureProcessorType.DIRECT_CODEC`` is
  ``Codec.withAlternative(LIST_OBJECT_CODEC.fieldOf("processors"), LIST_OBJECT_CODEC)``; the first
  branch hits the inline ``{"processors": [...]}`` object directly. This is the shortest path to
  the defect. **Gating.**
* ``BlockState.CODEC`` is what ``BlockIgnoreProcessor``'s ``blocks`` list is built from
  (``WEIRD_BLOCK_STATE_CODEC = BlockState.CODEC.xmap(...)``). **Gating**, applied per element so a
  failure names the exact ``blocks[i]``.
* ``StructurePlacement.CODEC`` and ``HeightProvider.CODEC`` both dispatch through built-in
  registries, so the two sub-objects this pack's generator actually rewrites
  (``structure_set`` ``placement``, ``structure`` ``start_height``) decode for real. **Gating.**

Blind spot, stated plainly: because the three root codecs above need a registry view, this gate
does **not** decode a whole ``template_pool``/``structure``/``structure_set`` root document. The
``elements[].element`` bodies (where every block state lives) are covered, but root-only fields
are not. ``EchoesPackContractTest`` pins those shapes structurally instead. A green run here means
"every block-state and placement site decodes with the real 26.3 codecs", not "the whole
datapack parsed".

Measured, not assumed: ``BlockState.CODEC`` is *lenient* about the ``properties`` payload --
``{"id": "minecraft:oak_log", "properties": {"axis": "nope"}}`` and even
``{"properties": 5}`` decode to the default state instead of erroring, because
``StateHolder.codec`` wraps them in ``lenientOptionalFieldOf``. Only a bad block id or a non-string
``id`` actually fails. So this gate proves the ``id``/``Name`` spelling (the real defect) and must
not be described as validating property values.
"""

import argparse
import glob
import json
import os
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Reuse the DnT gate's plumbing instead of copying it: library extraction, classpath assembly,
# probe compilation and the preset-outcome classifier are pack-independent and already proven.
import verify_dnt_codecs as dnt  # noqa: E402

PROJECT_ROOT = dnt.PROJECT_ROOT

PACK_DIR = os.path.join(
    PROJECT_ROOT, "plugin", "src", "main", "resources", "datapacks", "echoes-end-structures")
PACK_JAR_PREFIX = "datapacks/echoes-end-structures/"

WORK_DIR = os.path.join(PROJECT_ROOT, "build", "codec-probe-echoes")

C_POOL_ELEMENT = "POOL_ELEMENT_DIRECT"
C_PROCESSOR_LIST = "PROCESSOR_LIST_DIRECT"
C_BLOCK_STATE = "BLOCK_STATE"
C_STRUCTURE_PLACEMENT = "STRUCTURE_PLACEMENT"
C_HEIGHT_PROVIDER = "HEIGHT_PROVIDER"

# Informational only -- see the module docstring: these three need a registry view.
INFO_CODECS = ("TEMPLATE_POOL_DIRECT", "STRUCTURE_DIRECT", "STRUCTURE_SET_DIRECT")

#: Prefixes of a template_pool path, relative to the pack root.
POOL_PREFIX = "data/"
POOL_SEGMENT = "/worldgen/template_pool/"
STRUCTURE_SEGMENT = "/worldgen/structure/"
STRUCTURE_SET_SEGMENT = "/worldgen/structure_set/"

BLOCK_IGNORE = "block_ignore"

# Preset fixtures: each is (id, codec, json, expectation, note). They exist to prove the gate can
# actually fail -- an all-green scan is meaningless if every fixture passes.
PRESET_FIXTURES = [
    # --- the 0.1.6 defect, at all three granularities the scan gates ---
    ("neg_block_state_legacy_name", C_BLOCK_STATE,
     '{"Name":"minecraft:structure_block"}', "fail",
     "pre-1.20 palette object: the exact value that aborted 0.1.6"),
    ("neg_block_state_legacy_properties", C_BLOCK_STATE,
     '{"Name":"minecraft:oak_log","Properties":{"axis":"y"}}', "fail",
     "legacy Name + Properties"),
    ("pos_block_state_plain_id", C_BLOCK_STATE,
     '"minecraft:structure_block"', "pass",
     "canonical form the migration emits"),
    ("pos_block_state_id_object", C_BLOCK_STATE,
     '{"id":"minecraft:structure_block"}', "pass",
     "full form without properties is legal too"),
    ("pos_block_state_id_properties", C_BLOCK_STATE,
     '{"id":"minecraft:oak_log","properties":{"axis":"y"}}', "pass",
     "FULL_CODEC's property form"),
    ("neg_block_state_unknown_block", C_BLOCK_STATE,
     '{"id":"minecraft:not_a_block"}', "fail",
     "an object that is not a block must not be accepted either"),
    ("neg_processor_list_legacy_blocks", C_PROCESSOR_LIST,
     '{"processors":[{"processor_type":"minecraft:block_ignore",'
     '"blocks":[{"Name":"minecraft:structure_block"}]}]}', "fail",
     "the shipped defect through the processor-list codec"),
    ("pos_processor_list_blocks", C_PROCESSOR_LIST,
     '{"processors":[{"processor_type":"minecraft:block_ignore",'
     '"blocks":["minecraft:structure_block"]}]}', "pass",
     "the same document after migration"),
    ("neg_placement_missing_keys", C_STRUCTURE_PLACEMENT,
     '{"type":"minecraft:random_spread","spacing":160}', "fail",
     "placement codec rejects a half-specified spread"),
    ("pos_placement_complete", C_STRUCTURE_PLACEMENT,
     '{"type":"minecraft:random_spread","spacing":160,"separation":16,"salt":1}', "pass",
     "the placement the generator writes"),
    ("pos_height_absolute", C_HEIGHT_PROVIDER, '{"absolute":0}', "pass",
     "the start_height the pack ships"),
    ("neg_height_not_a_number", C_HEIGHT_PROVIDER, '{"absolute":"nope"}', "fail",
     "height provider rejects a non-numeric anchor"),
]

#: A single_pool_element carrying the legacy spelling, and its migrated twin.
LEGACY_ELEMENT = {
    "element_type": "minecraft:single_pool_element",
    "location": "echoes_of_the_end__structures_:end_well",
    "projection": "rigid",
    "processors": {"processors": [
        {"processor_type": "minecraft:block_ignore",
         "blocks": [{"Name": "minecraft:structure_block"}]}]},
}
FIXED_ELEMENT = {
    "element_type": "minecraft:single_pool_element",
    "location": "echoes_of_the_end__structures_:end_well",
    "projection": "rigid",
    "processors": {"processors": [
        {"processor_type": "minecraft:block_ignore",
         "blocks": ["minecraft:structure_block"]}]},
}

PRESET_FIXTURES += [
    ("neg_element_legacy_processors", C_POOL_ELEMENT, json.dumps(LEGACY_ELEMENT), "fail",
     "a whole single_pool_element carrying the legacy spelling"),
    ("pos_element_migrated_processors", C_POOL_ELEMENT, json.dumps(FIXED_ELEMENT), "pass",
     "the same element after migration"),
]

# Scanner-scope fixture: proves the structural walk finds every site it should and does NOT treat
# an unrelated `blocks` list (or a `Name` inside NBT-ish data) as a block state.
SCANNER_FIXTURE = {
    "name": "echoes_of_the_end__structures_:probe",
    "fallback": "minecraft:empty",
    "elements": [
        {"weight": 1, "element": dict(FIXED_ELEMENT)},
        {"weight": 2, "element": {
            "element_type": "minecraft:single_pool_element",
            "location": "echoes_of_the_end__structures_:probe2",
            "projection": "rigid",
            "processors": {"processors": [
                {"processor_type": "minecraft:block_ignore",
                 "blocks": ["minecraft:structure_block", "minecraft:air"]}]},
        }},
    ],
}

SCANNER_FIXTURE_EXPECTED = {
    ("elements[0].element", C_POOL_ELEMENT),
    ("elements[0].element.processors", C_PROCESSOR_LIST),
    ("elements[0].element.processors.processors[0].blocks[0]", C_BLOCK_STATE),
    ("elements[1].element", C_POOL_ELEMENT),
    ("elements[1].element.processors", C_PROCESSOR_LIST),
    ("elements[1].element.processors.processors[0].blocks[0]", C_BLOCK_STATE),
    ("elements[1].element.processors.processors[0].blocks[1]", C_BLOCK_STATE),
}


# --------------------------------------------------------------------------- scanning

def legacy_block_state_paths(obj, prefix=""):
    """Every ``{"Name": ...}`` / ``{"Properties": ...}`` object anywhere in a document.

    Read-only reporting helper: the codec gate is the authority, this only summarises *where*
    a legacy block-state spelling still sits, for the human reading a failure.
    """
    found = []
    if isinstance(obj, dict):
        if "Name" in obj or "Properties" in obj:
            found.append(prefix or "<root>")
        for key, value in obj.items():
            found += legacy_block_state_paths(value, "%s.%s" % (prefix, key) if prefix else key)
    elif isinstance(obj, list):
        for index, value in enumerate(obj):
            found += legacy_block_state_paths(value, "%s[%d]" % (prefix, index))
    return found


def template_pool_sites(doc):
    """Return ``(site_path, codec, json_text)`` for one template_pool document.

    Structural: anchors on ``elements[].element`` rather than searching for a ``blocks`` key
    anywhere, so a ``blocks``-named list elsewhere in the document is never fed to a block-state
    codec.
    """
    sites = []
    if not isinstance(doc, dict):
        return sites
    entries = doc.get("elements")
    if not isinstance(entries, list):
        return sites
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            continue
        element = entry.get("element")
        if not isinstance(element, dict):
            continue
        base = "elements[%d].element" % index
        sites.append((base, C_POOL_ELEMENT, json.dumps(element)))
        processors = element.get("processors")
        if isinstance(processors, dict):
            inline = processors.get("processors")
            if isinstance(inline, list):
                sites.append((base + ".processors", C_PROCESSOR_LIST, json.dumps(processors)))
                for p_index, processor in enumerate(inline):
                    if not isinstance(processor, dict):
                        continue
                    kind = processor.get("processor_type")
                    if not isinstance(kind, str) or kind.split(":")[-1] != BLOCK_IGNORE:
                        continue
                    blocks = processor.get("blocks")
                    if not isinstance(blocks, list):
                        continue
                    for b_index, block in enumerate(blocks):
                        sites.append((
                            "%s.processors.processors[%d].blocks[%d]" % (base, p_index, b_index),
                            C_BLOCK_STATE, json.dumps(block)))
    return sites


def structure_sites(doc):
    """``start_height`` of one worldgen/structure document, if it has one."""
    if isinstance(doc, dict) and "start_height" in doc:
        return [("start_height", C_HEIGHT_PROVIDER, json.dumps(doc["start_height"]))]
    return []


def structure_set_sites(doc):
    """``placement`` of one worldgen/structure_set document, if it has one."""
    if isinstance(doc, dict) and "placement" in doc:
        return [("placement", C_STRUCTURE_PLACEMENT, json.dumps(doc["placement"]))]
    return []


def document_sites(relpath, doc):
    """Route one pack-relative JSON path to the sites its category gates."""
    if POOL_SEGMENT in relpath:
        return template_pool_sites(doc)
    if STRUCTURE_SET_SEGMENT in relpath:
        return structure_set_sites(doc)
    if STRUCTURE_SEGMENT in relpath:
        return structure_sites(doc)
    return []


# --------------------------------------------------------------------------- sources

def load_pack(pack_dir=None, jar=None):
    """Yield ``(relative_path, document)`` for every JSON in the pack, JSON categories only."""
    if jar:
        with zipfile.ZipFile(jar) as archive:
            names = [n for n in archive.namelist()
                     if n.startswith(PACK_JAR_PREFIX) and n.endswith(".json")]
            for name in sorted(names):
                yield name[len(PACK_JAR_PREFIX):], json.loads(archive.read(name).decode("utf-8"))
        return
    for directory, _, files in os.walk(pack_dir):
        for name in sorted(files):
            if not name.endswith(".json"):
                continue
            absolute = os.path.join(directory, name)
            relative = os.path.relpath(absolute, pack_dir).replace(os.sep, "/")
            with open(absolute, "r", encoding="utf-8") as handle:
                yield relative, json.load(handle)


# --------------------------------------------------------------------------- probe IO

def run_probe(cp, cases):
    """Same protocol as the DnT gate, but its own request/result files."""
    os.makedirs(WORK_DIR, exist_ok=True)
    request_path = os.path.join(WORK_DIR, "request.json")
    result_path = os.path.join(WORK_DIR, "result.json")
    with open(request_path, "w", encoding="utf-8") as handle:
        json.dump({"cases": cases}, handle)
    cmd = ["java",
           "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-cp", cp + os.pathsep + os.path.abspath(dnt.CLASSES_DIR),
           dnt.PROBE_CLASS, request_path, result_path]
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0 or not os.path.isfile(result_path):
        print("probe process failed (exit %s):\n%s" % (proc.returncode, proc.stderr),
              file=sys.stderr)
        sys.exit(4)
    with open(result_path, "r", encoding="utf-8") as handle:
        return json.load(handle)


# --------------------------------------------------------------------------- main

def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack-dir", default=PACK_DIR,
                        help="pack directory (default: the source tree)")
    parser.add_argument("--jar", default=None,
                        help="read the pack from a built jar instead of the source tree")
    parser.add_argument("--server-jar", default=dnt.DEFAULT_SERVER_JAR)
    parser.add_argument("--bundler-jar", default=dnt.DEFAULT_BUNDLER_JAR)
    parser.add_argument("--api-jar", default=dnt.DEFAULT_API_JAR)
    parser.add_argument("--force-extract", action="store_true")
    args = parser.parse_args(argv)

    if not args.jar and not os.path.isdir(args.pack_dir):
        print("missing pack directory: %s" % args.pack_dir, file=sys.stderr)
        return 2
    for label, path in (("server jar", args.server_jar), ("bundler jar", args.bundler_jar)):
        if not os.path.isfile(path):
            print("missing %s: %s" % (label, path), file=sys.stderr)
            return 2

    print("== Echoes 26.3 codec verification ==")
    print("pack source: %s" % (os.path.abspath(args.jar) if args.jar
                               else os.path.abspath(args.pack_dir)))
    print("server jar : %s" % os.path.abspath(args.server_jar))

    dnt.extract_libs(args.bundler_jar, args.force_extract)
    libs = glob.glob(os.path.join(dnt.LIBS_DIR, "*.jar"))
    cp = dnt.resolve_classpath(args.server_jar, libs, args.api_jar)
    dnt.ensure_compiled(cp)

    # ---- build cases ----
    cases = []
    preset_meta = []
    for fid, codec, payload, expect, note in PRESET_FIXTURES:
        cid = "preset:" + fid
        cases.append({"id": cid, "codec": codec, "json": payload})
        preset_meta.append((cid, expect, note))

    scanner_found = {(path, codec) for (path, codec, _t) in template_pool_sites(SCANNER_FIXTURE)}

    gating_cases = []   # (relpath, site, codec, payload)
    info_cases = []     # (relpath, codec, payload)
    legacy_offenders = []
    counts = {"template_pool": 0, "structure": 0, "structure_set": 0}
    for relpath, doc in load_pack(pack_dir=args.pack_dir, jar=args.jar):
        for path in legacy_block_state_paths(doc):
            legacy_offenders.append("%s :: %s" % (relpath, path))
        if POOL_SEGMENT in relpath:
            counts["template_pool"] += 1
        elif STRUCTURE_SET_SEGMENT in relpath:
            counts["structure_set"] += 1
        elif STRUCTURE_SEGMENT in relpath:
            counts["structure"] += 1
        for site, codec, payload in document_sites(relpath, doc):
            gating_cases.append((relpath, site, codec, payload))
        # informational: root-level codecs that need a registry view (never gating)
        if POOL_SEGMENT in relpath:
            info_cases.append((relpath, "TEMPLATE_POOL_DIRECT", json.dumps(doc)))
        elif STRUCTURE_SET_SEGMENT in relpath:
            info_cases.append((relpath, "STRUCTURE_SET_DIRECT", json.dumps(doc)))
        elif STRUCTURE_SEGMENT in relpath:
            info_cases.append((relpath, "STRUCTURE_DIRECT", json.dumps(doc)))

    def case_id(kind, codec, relpath, site):
        return "%s|%s|%s|%s" % (kind, codec, relpath, site)

    for relpath, site, codec, payload in gating_cases:
        cases.append({"id": case_id("gate", codec, relpath, site),
                      "codec": codec, "json": payload})
    for relpath, codec, payload in info_cases:
        cases.append({"id": case_id("info", codec, relpath, "$"),
                      "codec": codec, "json": payload})

    result = run_probe(cp, cases)
    if not result.get("bootstrapped"):
        print("BOOTSTRAP FAILED: %s" % result.get("bootstrapError"))
        print("codecs: %s" % result.get("codecs"))
        print("RESULT: FAIL")
        return 1
    by_id = {r["id"]: r for r in result.get("results", [])}
    print("codecs loaded: %s" % result.get("codecs"))

    fatal = False

    # ---- preset fixtures ----
    print("\n== preset fixtures (gate soundness) ==")
    preset_ok = rejected = unexpected = 0
    for cid, expect, note in preset_meta:
        status = by_id.get(cid, {"status": "missing"}).get("status", "missing")
        good, was_rejected, actual = dnt.preset_outcome(status, expect)
        preset_ok += good
        rejected += was_rejected
        unexpected += actual.startswith("unexpected")
        if not good:
            fatal = True
        print("  [%s] %-36s expect=%-4s got=%-16s %s" % (
            "OK" if good else "BAD", cid.split(":", 1)[1], expect, actual, note))
    print("  presets matched: %d/%d ; negatives rejected (real decode error only): %d ; "
          "unexpected: %d" % (preset_ok, len(preset_meta), rejected, unexpected))

    # ---- scanner scope ----
    print("\n== scanner scope fixture ==")
    missing = SCANNER_FIXTURE_EXPECTED - scanner_found
    extra = scanner_found - SCANNER_FIXTURE_EXPECTED
    if missing or extra:
        fatal = True
        print("  MISMATCH missing=%s extra=%s" % (sorted(missing), sorted(extra)))
    else:
        print("  gated sites found: %d/%d (both pool entries, every block state)" % (
            len(scanner_found), len(SCANNER_FIXTURE_EXPECTED)))

    # ---- pack scan ----
    print("\n== pack scan ==")
    print("  documents: template_pool=%d structure=%d structure_set=%d" % (
        counts["template_pool"], counts["structure"], counts["structure_set"]))
    if not (counts["template_pool"] and counts["structure"] and counts["structure_set"]):
        fatal = True
        print("  zero state: at least one category produced no document -> FAIL")

    g_ok = g_err = g_other = 0
    errors = []
    for relpath, site, codec, _payload in gating_cases:
        entry = by_id.get(case_id("gate", codec, relpath, site), {"status": "missing"})
        status = entry.get("status")
        if status == "ok":
            g_ok += 1
        elif status == "error":
            g_err += 1
            errors.append((relpath, site, codec, entry.get("message") or ""))
        else:
            g_other += 1
            errors.append((relpath, site, codec,
                           "%s: %s" % (status, entry.get("message") or "")))

    print("  gating cases: %d  ok=%d error=%d other=%d" % (
        len(gating_cases), g_ok, g_err, g_other))
    if not gating_cases:
        fatal = True
        print("  no gating cases were produced (zero state) -> FAIL")

    if legacy_offenders:
        fatal = True
        print("\n  LEGACY block-state spellings still present (%d):" % len(legacy_offenders))
        for offender in legacy_offenders[:20]:
            print("   - %s" % offender)
        if len(legacy_offenders) > 20:
            print("   ... %d more" % (len(legacy_offenders) - 20))

    if errors:
        fatal = True
        print("\n  DECODE ERRORS (%d):" % len(errors))
        for relpath, site, codec, message in errors:
            print("   - %s :: %s [%s] -> %s" % (relpath, site, codec, message[:220]))

    # ---- informational (registry-needing root codecs) ----
    info_ok = info_bad = 0
    info_samples = []
    for relpath, codec, _payload in info_cases:
        status = by_id.get(case_id("info", codec, relpath, "$"), {"status": "missing"}).get("status")
        if status == "ok":
            info_ok += 1
        else:
            info_bad += 1
            if len(info_samples) < 3:
                entry = by_id.get(case_id("info", codec, relpath, "$"), {})
                info_samples.append("%s [%s] -> %s" % (
                    relpath, codec, (entry.get("message") or "")[:110]))
    print("\n  informational root codecs (non-gating): ok=%d other=%d" % (info_ok, info_bad))
    print("    These three codecs resolve holders by registry id and DntCodecProbe uses plain")
    print("    JsonOps, so a root document cannot decode offline either way -- a failure here is")
    print("    NOT evidence about the content. Blind spot: root-only fields are pinned by")
    print("    EchoesPackContractTest structurally instead.")
    for sample in info_samples:
        print("    info sample: %s" % sample)

    print("\nRESULT: %s" % ("FAIL" if fatal else "PASS"))
    return 1 if fatal else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
