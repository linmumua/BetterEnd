#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline 26.3 Codec verification for the DnT End Castle datapack.

Reads the *actual* datapack JSON from a built BetterEnd shadow jar and runs the real
Minecraft codecs (through tools/codec/DntCodecProbe.java) on the confirmed data sites:

  * every ``item_modifier`` root            -> LootItemFunctions.TYPED_CODEC
  * confirmed loot-table provider sites     -> ContextInt/ContextFloatProviders.DIRECT_CODEC
  * every ``set_components`` component map   -> DataComponentPatch.CODEC

Scope is deliberately narrow: only the provider keys whose provider type is confirmed by
javap against the target server jar (pool.rolls int, pool.bonus_rolls float,
set_count.count int, set_damage.damage float, set_ominous_bottle_amplifier.amplifier int,
enchant_with_levels.levels int, set_stew_effect.effects[].duration int). Arbitrary
``{min,max}`` objects at non-provider keys (e.g. predicate location ranges) are NOT
touched -- the tool is read-only and never migrates anything.

The probe only performs public static registration bootstrapping
(SharedConstants.tryDetectVersion() + Bootstrap.bootStrap()); it never starts a server,
world, network or plugin.

Exit code 0 = every case passed, 1 = at least one error/skip/mismatch.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import zipfile

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DEFAULT_SERVER_JAR = r"E:/我的世界插件/项目地址/MU核心/mucore-server/build/libs/mucore-server-26.3.local-SNAPSHOT.jar"
DEFAULT_BUNDLER_JAR = r"E:/我的世界插件/项目地址/MU核心/mucore-server/build/libs/mucore-bundler-26.3.local-SNAPSHOT.jar"
DEFAULT_API_JAR = r"C:/PluginLibs/mucore-api-26.3.local-SNAPSHOT.jar"

WORK_DIR = os.path.join(PROJECT_ROOT, "build", "codec-probe")
LIBS_DIR = os.path.join(WORK_DIR, "libs")
CLASSES_DIR = os.path.join(WORK_DIR, "classes")
PROBE_SRC = os.path.join(PROJECT_ROOT, "tools", "codec", "DntCodecProbe.java")
PROBE_CLASS = "DntCodecProbe"

DATAPACK_DATA_PREFIX = "datapacks/dnt-end-castle/data/"

C_LOOT_FN = "LOOT_FUNCTION_TYPED"
C_INT = "CONTEXT_INT"
C_FLOAT = "CONTEXT_FLOAT"
C_DCP = "DATA_COMPONENT_PATCH"
C_LOOT_TABLE = "LOOT_TABLE_DIRECT"

ENCODING = "utf-8"

# function type (namespace stripped) -> {provider key: codec}
FUNCTION_PROVIDER_SITES = {
    "set_count": {"count": C_INT},
    "set_damage": {"damage": C_FLOAT},
    "set_ominous_bottle_amplifier": {"amplifier": C_INT},
    "enchant_with_levels": {"levels": C_INT},
}


def norm_type(t):
    if not isinstance(t, str):
        return t
    if ":" in t:
        ns, path = t.split(":", 1)
        return path if ns == "minecraft" else t
    return t


# --------------------------------------------------------------------------- fixtures

# Preset fixtures that prove the codec gate is real: each entry is
# (id, codec, json, expectation, note).
PRESET_FIXTURES = [
    ("neg_root_array", C_LOOT_FN, "[]", "fail",
     "old-style item_modifier root array"),
    ("pos_root_sequence", C_LOOT_FN,
     '{"type":"minecraft:sequence","functions":[]}', "pass",
     "correct sequence wrapper"),
    ("neg_damage_missing_type", C_FLOAT, '{"min":0.01,"max":0.0}', "fail",
     "set_damage.damage without type"),
    ("pos_damage_uniform_inverted", C_FLOAT,
     '{"type":"minecraft:uniform","min":0.01,"max":0.0}', "pass",
     "inverted min>max kept, float codec allows"),
    ("neg_amplifier_missing_type", C_INT, '{"max":5,"min":3}', "fail",
     "set_ominous_bottle_amplifier.amplifier without type"),
    ("pos_amplifier_uniform", C_INT,
     '{"type":"minecraft:uniform","max":5,"min":3}', "pass",
     "amplifier uniform"),
    ("pos_uniform_unprefixed", C_INT,
     '{"type":"uniform","min":1,"max":2}', "pass",
     "unprefixed uniform resolves to minecraft:uniform"),
    ("pos_bare_number_int", C_INT, "3", "pass",
     "bare number is a valid inline int provider"),
    ("pos_bare_number_float", C_FLOAT, "0.0", "pass",
     "bare number is a valid inline float provider"),
    ("neg_components_map_color", C_DCP,
     '{"minecraft:map_color":9317301,"minecraft:item_name":{"translate":"x"}}', "fail",
     "removed component id map_color"),
    ("pos_components_item_name", C_DCP,
     '{"minecraft:item_name":{"translate":"x","fallback":"y","italic":false}}', "pass",
     "item_name component kept"),
]


def preset_outcome(status, expect):
    """Decide a preset fixture's outcome.

    Returns ``(good, rejected, actual)`` where:

    * ``good``     -- the observed status matches the expectation;
    * ``rejected`` -- counted as an actual decode rejection (only ``error``);
    * ``actual``   -- human label for reporting.

    Only ``status == "ok"`` counts as an acceptance and only ``status == "error"``
    counts as a decode rejection. Any other status (``missing`` -- case absent from the
    probe result, ``exception`` -- the probe threw, ``nocodec`` -- codec unavailable)
    is UNEXPECTED: it never satisfies ``expect == "fail"`` and is always fatal.
    """
    if status == "ok":
        return (expect == "pass"), False, "accept"
    if status == "error":
        return (expect == "fail"), True, "reject"
    return False, False, "unexpected(%s)" % status

# Scanner-scope fixture: a loot table exercising sequence.functions traversal and a
# non-provider {min,max} predicate range that must NOT be scanned.
SCANNER_FIXTURE = {
    "type": "minecraft:chest",
    "pools": [{
        "rolls": {"type": "minecraft:uniform", "min": 1, "max": 3},
        "bonus_rolls": 0.0,
        "entries": [{
            "type": "minecraft:item",
            "name": "minecraft:stone",
            "modifier": [
                {"type": "minecraft:sequence", "functions": [
                    {"type": "minecraft:set_count",
                     "count": {"type": "minecraft:uniform", "min": 1, "max": 3}},
                    {"type": "minecraft:set_name", "name": {"min": 0, "max": 1}},
                ]},
                {"type": "minecraft:set_damage", "damage": 0.5},
                {"type": "minecraft:set_ominous_bottle_amplifier",
                 "amplifier": {"type": "minecraft:uniform", "min": 1, "max": 2}},
                {"type": "minecraft:set_stew_effect", "effects": [
                    {"type": "minecraft:night_vision",
                     "duration": {"type": "minecraft:uniform", "min": 7, "max": 10}}]},
            ],
            "conditions": [{"condition": "minecraft:location_check",
                            "predicate": {"position": {"y": {"min": -100, "max": 500}}}}],
        }],
    }],
}

SCANNER_FIXTURE_EXPECTED = {
    ("pools[0].rolls", C_INT),
    ("pools[0].bonus_rolls", C_FLOAT),
    ("pools[0].entries[0].modifier[0].functions[0].count", C_INT),
    ("pools[0].entries[0].modifier[1].damage", C_FLOAT),
    ("pools[0].entries[0].modifier[2].amplifier", C_INT),
    ("pools[0].entries[0].modifier[3].effects[0].duration", C_INT),
}


# --------------------------------------------------------------------------- scanning

def loot_table_sites(root):
    """Return list of (path, codec, json_text) for a loot-table root object."""
    out = []
    if not isinstance(root, dict):
        return out
    for i, pool in enumerate(root.get("pools") or []):
        if not isinstance(pool, dict):
            continue
        base = "pools[%d]" % i
        if "rolls" in pool:
            out.append((base + ".rolls", C_INT, json.dumps(pool["rolls"])))
        if "bonus_rolls" in pool:
            out.append((base + ".bonus_rolls", C_FLOAT, json.dumps(pool["bonus_rolls"])))
        for j, fn in enumerate(pool.get("functions") or pool.get("modifier") or []):
            out += function_sites(fn, base + ".functions[%d]" % j)
        for k, entry in enumerate(pool.get("entries") or []):
            out += entry_sites(entry, base + ".entries[%d]" % k)
    return out


def entry_sites(entry, base):
    out = []
    if not isinstance(entry, dict):
        return out
    funcs = entry.get("modifier")
    if funcs is None:
        funcs = entry.get("functions")
    for j, fn in enumerate(funcs or []):
        out += function_sites(fn, base + ".modifier[%d]" % j)
    for k, child in enumerate(entry.get("children") or []):
        out += entry_sites(child, base + ".children[%d]" % k)
    return out


def function_sites(fn, base):
    out = []
    if not isinstance(fn, dict):
        return out
    t = norm_type(fn.get("type"))
    spec = FUNCTION_PROVIDER_SITES.get(t)
    if spec:
        for key, codec in spec.items():
            if key in fn:
                out.append((base + "." + key, codec, json.dumps(fn[key])))
    if t == "set_stew_effect":
        for i, eff in enumerate(fn.get("effects") or []):
            if isinstance(eff, dict) and "duration" in eff:
                out.append((base + ".effects[%d].duration" % i, C_INT,
                            json.dumps(eff["duration"])))
    if t == "set_components" and "components" in fn:
        out.append((base + ".components", C_DCP, json.dumps(fn["components"])))
    if t == "sequence":
        for i, sub in enumerate(fn.get("functions") or []):
            out += function_sites(sub, base + ".functions[%d]" % i)
    return out


def collect_minmax_objects(obj, path, acc):
    """Collect paths of objects carrying both min and max (for the non-provider report)."""
    if isinstance(obj, dict):
        if "min" in obj and "max" in obj:
            acc.append(path)
        for k, v in obj.items():
            collect_minmax_objects(v, ("%s.%s" % (path, k)) if path else k, acc)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            collect_minmax_objects(v, "%s[%d]" % (path, i), acc)
    return acc


# --------------------------------------------------------------------------- probe IO

def extract_libs(bundler_jar, force=False):
    marker = os.path.join(LIBS_DIR, ".bundler.marker")
    stamp = "%s:%d" % (os.path.abspath(bundler_jar), os.path.getsize(bundler_jar))
    if not force and os.path.isfile(marker):
        with open(marker, "r", encoding=ENCODING) as fh:
            if fh.read().strip() == stamp:
                jars = glob.glob(os.path.join(LIBS_DIR, "*.jar"))
                if jars:
                    return len(jars)
    os.makedirs(LIBS_DIR, exist_ok=True)
    count = 0
    with zipfile.ZipFile(bundler_jar) as z:
        for info in z.infolist():
            name = info.filename
            if not (name.startswith("META-INF/libraries/") and name.endswith(".jar")):
                continue
            # reject path traversal / absolute entries
            parts = name.split("/")
            if ".." in parts or name.startswith("/") or (len(name) > 1 and name[1] == ":"):
                print("  [skip unsafe entry] %s" % name, file=sys.stderr)
                continue
            base = os.path.basename(name)
            dest = os.path.join(LIBS_DIR, base)
            with z.open(info) as src, open(dest, "wb") as dst:
                dst.write(src.read())
            count += 1
    with open(marker, "w", encoding=ENCODING) as fh:
        fh.write(stamp)
    return count


def resolve_classpath(server_jar, libs, api_jar):
    entries = [os.path.abspath(server_jar)]
    entries += sorted(os.path.abspath(p) for p in libs)
    have_api = any("mucore-api" in os.path.basename(p) for p in libs)
    if api_jar and not have_api and os.path.isfile(api_jar):
        entries.append(os.path.abspath(api_jar))
    return os.pathsep.join(entries)


def ensure_compiled(cp):
    src_mtime = os.path.getmtime(PROBE_SRC)
    cls = os.path.join(CLASSES_DIR, PROBE_CLASS + ".class")
    if os.path.isfile(cls) and os.path.getmtime(cls) >= src_mtime:
        return
    os.makedirs(CLASSES_DIR, exist_ok=True)
    cmd = ["javac", "--release", "25", "-encoding", "UTF-8",
           "-cp", cp, "-d", CLASSES_DIR, PROBE_SRC]
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding=ENCODING, errors="replace")
    if proc.returncode != 0:
        print("javac failed:\n%s\n%s" % (proc.stdout, proc.stderr), file=sys.stderr)
        sys.exit(3)
    print("compiled probe -> %s" % CLASSES_DIR)


def run_probe(cp, cases):
    req_path = os.path.join(WORK_DIR, "request.json")
    res_path = os.path.join(WORK_DIR, "result.json")
    with open(req_path, "w", encoding=ENCODING) as fh:
        json.dump({"cases": cases}, fh)
    cmd = ["java",
           "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-cp", cp + os.pathsep + os.path.abspath(CLASSES_DIR),
           PROBE_CLASS, req_path, res_path]
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding=ENCODING, errors="replace")
    if proc.returncode != 0 or not os.path.isfile(res_path):
        print("probe process failed (exit %s):\n%s" % (proc.returncode, proc.stderr),
              file=sys.stderr)
        sys.exit(4)
    with open(res_path, "r", encoding=ENCODING) as fh:
        return json.load(fh)


# --------------------------------------------------------------------------- report

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", default=None, help="target BetterEnd shadow jar")
    ap.add_argument("--server-jar", default=DEFAULT_SERVER_JAR)
    ap.add_argument("--bundler-jar", default=DEFAULT_BUNDLER_JAR)
    ap.add_argument("--api-jar", default=DEFAULT_API_JAR)
    ap.add_argument("--force-extract", action="store_true")
    args = ap.parse_args()

    target = args.jar
    if not target:
        cands = glob.glob(os.path.join(PROJECT_ROOT, "jars", "BetterEnd-*-folia26.3.jar"))
        if not cands:
            print("no target jar found", file=sys.stderr)
            sys.exit(2)

        def _ver(p):
            m = re.search(r"BetterEnd-([0-9]+(?:\.[0-9]+)*)-folia", os.path.basename(p))
            return tuple(int(x) for x in m.group(1).split(".")) if m else (0,)

        target = max(cands, key=_ver)

    for label, path in (("target jar", target), ("server jar", args.server_jar),
                        ("bundler jar", args.bundler_jar)):
        if not os.path.isfile(path):
            print("missing %s: %s" % (label, path), file=sys.stderr)
            sys.exit(2)

    parent = os.path.dirname(WORK_DIR)
    if not os.path.isdir(parent):
        print("work parent missing: %s" % parent, file=sys.stderr)
        sys.exit(2)
    os.makedirs(WORK_DIR, exist_ok=True)

    print("== DnT 26.3 codec verification ==")
    print("target jar : %s" % os.path.abspath(target))
    print("server jar : %s" % os.path.abspath(args.server_jar))
    print("bundler jar: %s" % os.path.abspath(args.bundler_jar))

    n_libs = extract_libs(args.bundler_jar, args.force_extract)
    libs = glob.glob(os.path.join(LIBS_DIR, "*.jar"))
    print("library jars: %d" % n_libs)
    cp = resolve_classpath(args.server_jar, libs, args.api_jar)
    ensure_compiled(cp)

    # ---- build all cases ----
    cases = []
    preset_meta = []
    for fid, codec, payload, expect, note in PRESET_FIXTURES:
        cid = "preset:" + fid
        cases.append({"id": cid, "codec": codec, "json": payload})
        preset_meta.append((cid, expect, note))

    scanner_sites = loot_table_sites(SCANNER_FIXTURE)
    scanner_found = {(p, c) for (p, c, _t) in scanner_sites}
    scanner_minmax = collect_minmax_objects(SCANNER_FIXTURE, "", [])

    jar_cases = []  # (id, codec, source, jsonpath, json)
    with zipfile.ZipFile(target) as z:
        names = [n for n in z.namelist()
                 if n.startswith(DATAPACK_DATA_PREFIX) and n.endswith(".json")]

        def load(name):
            return json.loads(z.read(name).decode(ENCODING))

        def rel(name):
            return name[len(DATAPACK_DATA_PREFIX):]

        # item_modifier roots
        for name in names:
            if "/item_modifier/" not in name:
                continue
            root = load(name)
            jar_cases.append(("root", C_LOOT_FN, rel(name), "$",
                              json.dumps(root)))

        # loot tables
        loot_minmax_nonprovider = 0
        loot_minmax_provider = 0
        for name in names:
            if "/loot_table/" not in name:
                continue
            root = load(name)
            sites = loot_table_sites(root)
            site_paths = {p for (p, _c, _t) in sites}
            mm = collect_minmax_objects(root, "", [])
            for p in mm:
                if p in site_paths:
                    loot_minmax_provider += 1
                else:
                    loot_minmax_nonprovider += 1
            for (p, codec, payload) in sites:
                jar_cases.append(("site", codec, rel(name), p, payload))
            # informational: full LootTable.DIRECT_CODEC (non-gating)
            jar_cases.append(("info", C_LOOT_TABLE, rel(name), "$",
                              json.dumps(root)))

    for kind, codec, src, jpath, payload in jar_cases:
        cases.append({"id": "%s|%s|%s|%s" % (kind, codec, src, jpath),
                      "codec": codec, "json": payload})

    result = run_probe(cp, cases)
    if not result.get("bootstrapped"):
        print("BOOTSTRAP FAILED: %s" % result.get("bootstrapError"))
        print("codecs: %s" % result.get("codecs"))
        print("RESULT: FAIL")
        sys.exit(1)
    by_id = {r["id"]: r for r in result.get("results", [])}

    print("codecs loaded: %s" % result.get("codecs"))

    fatal = False

    # ---- preset fixtures ----
    print("\n== preset fixtures (gate soundness) ==")
    preset_ok = 0
    rejected = 0
    unexpected = 0
    for cid, expect, note in preset_meta:
        r = by_id.get(cid, {"status": "missing"})
        status = r.get("status", "missing")
        good, was_rejected, actual = preset_outcome(status, expect)
        preset_ok += good
        if was_rejected:
            rejected += 1
        if not good:
            fatal = True
        if actual.startswith("unexpected"):
            unexpected += 1
        print("  [%s] %-32s expect=%-4s got=%-16s %s" % (
            "OK" if good else "BAD", cid.split(":", 1)[1], expect, actual, note))
    print("  presets matched: %d/%d ; negatives rejected (real decode error only): %d ; unexpected: %d" %
          (preset_ok, len(preset_meta), rejected, unexpected))

    # ---- scanner scope fixture ----
    print("\n== scanner scope fixture ==")
    missing = SCANNER_FIXTURE_EXPECTED - scanner_found
    extra = scanner_found - SCANNER_FIXTURE_EXPECTED
    nonprov = [p for p in scanner_minmax if p not in {x[0] for x in scanner_sites}]
    if missing or extra:
        fatal = True
        print("  MISMATCH missing=%s extra=%s" % (sorted(missing), sorted(extra)))
    else:
        print("  provider sites found: %d/%d (incl. nested sequence.functions)" %
              (len(scanner_found), len(SCANNER_FIXTURE_EXPECTED)))
    print("  non-provider {min,max} objects ignored: %d %s" % (len(nonprov), nonprov))

    # ---- target jar scan ----
    print("\n== target jar scan: %s ==" % os.path.basename(target))
    root_n = site_n = info_n = 0
    g_ok = g_err = g_other = 0
    info_ok = info_err = 0
    info_samples = []
    errors = []
    for kind, codec, src, jpath, payload in jar_cases:
        cid = "%s|%s|%s|%s" % (kind, codec, src, jpath)
        r = by_id.get(cid, {"status": "missing"})
        status = r.get("status")
        if kind == "info":
            info_n += 1
            if status == "ok":
                info_ok += 1
            else:
                info_err += 1
                if len(info_samples) < 3:
                    info_samples.append("%s [%s] -> %s" %
                                        (src, codec, (r.get("message") or "")[:120]))
            continue
        if kind == "root":
            root_n += 1
        else:
            site_n += 1
        if status == "ok":
            g_ok += 1
        elif status == "error":
            g_err += 1
            errors.append((src, jpath, codec, r.get("message") or ""))
        else:
            g_other += 1
            errors.append((src, jpath, codec,
                           "%s: %s" % (status, r.get("message") or "")))

    gating_total = root_n + site_n
    print("  item_modifier roots : %d  (codec %s)" % (root_n, C_LOOT_FN))
    print("  provider/component sites: %d" % site_n)
    print("  gating cases        : %d  ok=%d error=%d other=%d" %
          (gating_total, g_ok, g_err, g_other))
    print("  loot-table {min,max}: provider=%d non-provider(not scanned)=%d" %
          (loot_minmax_provider, loot_minmax_nonprovider))
    print("  informational LootTable.DIRECT_CODEC: ok=%d error=%d (non-gating)" %
          (info_ok, info_err))
    if info_err:
        print("    note: info error messages are verbatim codec output and are NOT")
        print("          classified here (they mix registry/holder availability and")
        print("          other reasons); full per-case messages are in %s" %
              os.path.join(WORK_DIR, "result.json"))
        for s in info_samples:
            print("    info sample: %s" % s)
        if info_err > len(info_samples):
            print("    ... %d more in result.json" % (info_err - len(info_samples)))

    if errors:
        fatal = True
        print("\n  ERRORS (%d):" % len(errors))
        for src, jpath, codec, msg in errors:
            print("   - %s :: %s [%s] -> %s" % (src, jpath, codec, msg[:200]))

    if gating_total == 0:
        fatal = True
        print("\n  no gating cases were produced (zero state) -> FAIL")

    print("\nRESULT: %s" % ("FAIL" if fatal else "PASS"))
    return 1 if fatal else 0


if __name__ == "__main__":
    sys.exit(main())
