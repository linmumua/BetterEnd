#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CraftEngine 自定义方块视觉状态审计与同族均分（离线、默认只读）。

只做四件事，其余一律报告：

1. 展开启用资源包里的方块定义（模板 / config_factory / ${宏}），得到每个
   外观（appearance）的视觉状态来源：固定 `state:` 字面量，或 `auto_state: <组>`。
2. 合并当前占用：显式固定字面量 + cache/visual_block_states.json 的既有分配。
   空 model 的状态同样算被占用，绝不当空闲。
3. 用 internal/mappings.yml 的 LHS→RHS（等价外观、RHS 视为原版保留）推导每个
   原版家族的候选容量，按 auto_state 组做确定性均分 dry-run（只报告移动建议）。
4. 仅对「同一视觉状态下出现不同 model」的真实冲突，且能唯一定位到内联文件中的
   `state:` 字面量时，生成完整新文件补丁到 OUTDIR/patches/。

永不修改目标目录：cache、config.yml、任何 CUSTOM BLOCK ID / properties /
内部 state registry 数值都保持原样（CE 的 DefaultSectionSerializer 只存 ID +
properties，所以视觉状态变化不等于世界方块数据变化）。

不是万能 CE 解释器：无法安全回写的（config_factory 展开、未解析宏、模板条件、
来源不唯一）一律进 report 的 manual_review。

CLI: python tools/ce_state_balance.py --root <CE插件目录> --output <OUTDIR>
输出: OUTDIR/plan.json, OUTDIR/report.yml[, OUTDIR/patches/*]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from collections import Counter, defaultdict

import yaml

SCHEMA = 1
TARGET_VERSION = "26.3"
MAX_TEMPLATE_DEPTH = 8

VAR_RE = re.compile(r"\$\{([A-Za-z0-9_./\-]+)(?::-([^}]*))?\}")
COND_RE = re.compile(r"^\s*\$\{([A-Za-z0-9_./\-]+)(?::-([^}]*))?\}\s*$")


class ToolError(Exception):
    """输入目录或配置本身不可用（不是「需要人看」的情况）。"""


class Unresolved(Exception):
    """宏/模板无法解析 —— 该外观转 manual_review，绝不猜。"""


# --------------------------------------------------------------------------
# YAML：拒绝重复键，保留行号，支持 $$>=版本 条件段
# --------------------------------------------------------------------------

class StrictLoader(yaml.SafeLoader):
    pass


def _strict_mapping(loader, node):
    seen = set()
    for key_node, _ in node.value:
        key = loader.construct_object(key_node, deep=True)
        if key in seen:
            raise yaml.constructor.ConstructorError(
                None, None, "duplicate key %r" % (key,), key_node.start_mark)
        seen.add(key)
    return yaml.SafeLoader.construct_mapping(loader, node, deep=True)


StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _strict_mapping)


def _ver(text):
    parts = re.findall(r"\d+", str(text))
    return tuple(int(p) for p in parts[:4]) or (0,)


def _cond_ok(expr, version):
    """只认比较符 / a~b 区间；未知写法抛 Unresolved，由调用方记录。"""
    expr = expr.strip()
    if not expr or expr.startswith("#"):
        return True
    if expr.startswith("["):
        raise Unresolved("range condition %s" % expr)
    if "~" in expr:
        lo, hi = (p.strip() for p in expr.split("~", 1))
        return _ver(lo) <= _ver(version) <= _ver(hi)
    m = re.match(r"^(>=|<=|==|=|>|<)\s*([0-9][0-9.]*)$", expr)
    if not m:
        raise Unresolved("condition %s" % expr)
    op = m.group(1)
    want = _ver(m.group(2))
    have = _ver(version)
    return {">=": have >= want, "<=": have <= want, ">": have > want,
            "<": have < want, "==": have[:len(want)] == want,
            "=": have[:len(want)] == want}[op]


def _apply_conditionals(node, version, warnings, path="<root>"):
    if isinstance(node, list):
        return [_apply_conditionals(v, version, warnings, path) for v in node]
    if not isinstance(node, dict):
        return node
    out = {}
    fallback = None
    matched = False
    for key, value in node.items():
        if isinstance(key, str) and key.startswith("$$"):
            expr = key[2:].split("#", 1)[0].strip()
            if expr == "fallback":
                if fallback is None:
                    fallback = value
                continue
            try:
                keep = _cond_ok(expr, version)
            except Unresolved as exc:
                warnings.append({"path": path, "reason": "version_condition:%s" % exc})
                continue
            if not keep:
                continue
            matched = True
            inner = _apply_conditionals(value, version, warnings, path)
            if isinstance(inner, dict):
                for k, v in inner.items():
                    out[k] = v
            else:
                out[key] = inner
            continue
        out[key] = _apply_conditionals(value, version, warnings, path)
    if fallback is not None and not matched:
        inner = _apply_conditionals(fallback, version, warnings, path)
        if isinstance(inner, dict):
            for k, v in inner.items():
                out[k] = v
    return out


def load_yaml(path, version, warnings):
    with open(path, "r", encoding="utf-8") as fh:
        data = yaml.load(fh, Loader=StrictLoader)
    return _apply_conditionals(data if data is not None else {}, version, warnings)


# --------------------------------------------------------------------------
# 宏替换（${name} / ${name:-default}）
# --------------------------------------------------------------------------

def subst_str(text, ctx):
    def repl(m):
        name, default = m.group(1), m.group(2)
        if name in ctx and ctx[name] is not None:
            return str(ctx[name])
        if default is not None:
            return default
        raise Unresolved(name)
    return VAR_RE.sub(repl, text)


def subst_tree(node, ctx):
    if isinstance(node, str):
        return subst_str(node, ctx)
    if isinstance(node, list):
        return [subst_tree(v, ctx) for v in node]
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if isinstance(k, str) and "${" in k:
                new_key = subst_str(k, ctx)
                if new_key in out:
                    raise Unresolved("展开后重复键 %r" % new_key)
                out[new_key] = subst_tree(v, ctx)
                continue
            if k in out:
                raise Unresolved("展开后重复键 %r" % k)
            out[k] = subst_tree(v, ctx)
        return out
    return node


def deep_merge(base, over):
    if isinstance(base, dict) and isinstance(over, dict):
        out = dict(base)
        for k, v in over.items():
            out[k] = deep_merge(base[k], v) if k in base else v
        return out
    return over if over is not None else base


def eval_argument(value, ctx):
    """模板 arguments 值：字符串 / 条件对象。"""
    if isinstance(value, dict) and "condition" in value:
        raw = value["condition"]
        m = COND_RE.match(str(raw))
        if not m:
            raise Unresolved("condition %s" % raw)
        name, default = m.group(1), m.group(2)
        actual = ctx.get(name, default if default is not None else "false")
        return value.get("on_true") if str(actual).lower() == "true" else value.get("on_false")
    if isinstance(value, str):
        return subst_str(value, ctx)
    return value


def resolve_template(node, registry, ctx, depth=0):
    """展开 node['template'] 指向的模板；返回深合并后的纯数据。"""
    if depth > MAX_TEMPLATE_DEPTH:
        raise Unresolved("template depth")
    if not isinstance(node, dict):
        return node
    ref = node.get("template")
    if isinstance(ref, list):
        # CE 允许一条定义引用多个模板，按顺序深合并
        merged = {}
        for item in ref:
            if not isinstance(item, str):
                continue
            merged = deep_merge(merged, resolve_template({"template": item},
                                                         registry, ctx, depth + 1))
        local = {k: v for k, v in node.items() if k not in ("template", "arguments")}
        return deep_merge(merged, subst_tree(local, ctx))
    if not isinstance(ref, str):
        return node
    call_ctx = dict(ctx)
    for key, value in (node.get("arguments") or {}).items():
        call_ctx[key] = eval_argument(value, call_ctx)
    try:
        ref = subst_str(ref, call_ctx)
    except Unresolved:
        raise Unresolved("template %s" % ref)
    if ref not in registry:
        raise Unresolved("template %s" % ref)
    base = resolve_template(registry[ref], registry, call_ctx, depth + 1)
    base = subst_tree(base, call_ctx)
    local = {k: v for k, v in node.items() if k not in ("template", "arguments")}
    local = subst_tree(local, call_ctx)
    return deep_merge(base, local)


# --------------------------------------------------------------------------
# 资源包 / 文件收集
# --------------------------------------------------------------------------

def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Pack:
    def __init__(self, name, directory, namespace, enabled):
        self.name = name
        self.dir = directory
        self.namespace = namespace
        self.enabled = enabled

    @property
    def rel(self):
        return "resources/" + self.name


def load_packs(root):
    res = os.path.join(root, "resources")
    if not os.path.isdir(res):
        raise ToolError("缺少 resources 目录: %s" % res)
    packs = []
    for name in sorted(os.listdir(res)):
        pdir = os.path.join(res, name)
        manifest = os.path.join(pdir, "pack.yml")
        if not os.path.isfile(manifest):
            continue
        with open(manifest, "r", encoding="utf-8") as fh:
            meta = yaml.load(fh, Loader=StrictLoader) or {}
        packs.append(Pack(name, pdir, meta.get("namespace", name),
                          meta.get("enable", True) is not False))
    return packs


def pack_files(pack):
    conf = os.path.join(pack.dir, "configuration")
    out = []
    for base, _dirs, files in os.walk(conf):
        for fn in sorted(files):
            if fn.endswith((".yml", ".yaml")):
                out.append(os.path.join(base, fn))
    return sorted(out)


def partition(section_key):
    if "#" in section_key:
        kind, ident = section_key.split("#", 1)
        return kind.replace("_", "-"), ident
    return section_key.replace("_", "-"), ""


# --------------------------------------------------------------------------
# 状态规范化
# --------------------------------------------------------------------------

def norm_block_id(block):
    block = str(block).strip()
    if block.startswith("minecraft:"):
        block = block[len("minecraft:"):]
    return block


def parse_state(text):
    text = str(text).strip()
    if text.startswith("minecraft:"):
        text = text[len("minecraft:"):]
    if "[" not in text:
        return norm_block_id(text), ()
    bid, rest = text.split("[", 1)
    props = {}
    for part in rest.rstrip().rstrip("]").split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            return norm_block_id(bid), ()
        k, v = part.split("=", 1)
        props[k.strip()] = v.strip()
    return norm_block_id(bid), tuple(sorted(props.items()))


def canon_state(bid, props):
    if not props:
        return bid
    return "%s[%s]" % (bid, ",".join("%s=%s" % kv for kv in props))


def canon_key(key):
    return canon_state(*parse_state(key))


def _with_namespace(state):
    """保持缓存里 `minecraft:` 前缀的写法，不要把 donor 写成另一种形态。"""
    state = str(state)
    if ":" in state.split("[", 1)[0]:
        return state
    return "minecraft:" + state


def model_signature(app, ctx):
    """视觉签名：区分 model / entity_renderer / 仅隐形基底三种渲染方式。

    只取 model 会把「实体渲染 cover」和「普通模型」判成同一外观而漏冲突；
    反过来把 entity_renderer 的旋转差异当成不同外观又会制造大量假冲突，
    所以这里额外给出渲染类型。
    """
    payload, kind = {}, "invisible"
    if "model" in app or "models" in app:
        kind = "model"
        payload["model"] = app.get("model", app.get("models"))
    if "entity_renderer" in app:
        kind = "entity"
        payload["entity_renderer"] = app["entity_renderer"]
    for key in ("display_entity", "item_display", "billboard"):
        if key in app:
            kind = "entity"
            payload[key] = app[key]
    if "transparent" in app:
        payload["transparent"] = app["transparent"]
    if not payload:
        return None
    try:
        payload = subst_tree(payload, ctx)
    except Unresolved:
        return "unresolved"
    return json.dumps({"kind": kind, "payload": payload},
                      sort_keys=True, ensure_ascii=False)


def render_kind(sig):
    if not sig or sig == "unresolved":
        return None
    try:
        return json.loads(sig).get("kind")
    except (ValueError, AttributeError):
        return None


# --------------------------------------------------------------------------
# 方块定义展开
# --------------------------------------------------------------------------

class Appearance:
    __slots__ = ("ce_key", "block_id", "donor_kind", "donor", "group", "auto_id",
                 "model_sig", "origin", "path", "factory", "reason")

    def __init__(self, ce_key, block_id, origin, path, factory):
        self.ce_key = ce_key
        self.block_id = block_id
        self.origin = origin
        self.path = path
        self.factory = factory
        self.donor_kind = "unknown"
        self.donor = None
        self.group = None
        self.auto_id = None
        self.model_sig = None
        self.reason = None


def _iter_appearances(block_id, state, ctx, origin, path, factory, manual):
    """把 state/states 段摊平成 (外观键, app 字典) 序列。

    CE 的视觉缓存以「外观」为粒度建键：
      * appearances 的键看起来像属性串（含 '='）时直接用，如 `stove[facing=north,fire=true]`
      * 否则用 `appearance=<外观名>`，如 `leaves[appearance=waterlogged]`
      * variants 指向的外观按外观名计一次，不按变体数量重复
    """
    if not isinstance(state, dict):
        return []
    appearances = state.get("appearances")
    if not isinstance(appearances, dict):
        # 紧凑写法：state 本身就是一个外观（auto_state/state + model/models）
        if any(k in state for k in ("auto_state", "state", "model", "models")):
            return [("", state)]
        return []
    variants = state.get("variants")
    if isinstance(variants, dict):
        rows, seen = [], set()
        for vkey, spec in variants.items():
            name = spec if isinstance(spec, str) else (
                spec.get("appearance") if isinstance(spec, dict) else None)
            if name is None or name not in appearances:
                manual.append({"path": path, "where": block_id,
                               "reason": "variant %r 指向未知 appearance" % vkey})
                continue
            if name not in seen:
                seen.add(name)
                rows.append((str(name).strip(), appearances[name]))
        return rows
    return [(str(akey).strip(), app) for akey, app in appearances.items()]


def expand_block(block_id, defn, registry, ctx, origin, path, factory, manual, depth=0):
    out = []
    try:
        resolved = resolve_template(defn, registry, ctx, depth) if isinstance(defn, dict) else {}
        state = None
        for key in ("state", "states"):
            candidate = resolved.get(key)
            if isinstance(candidate, dict):
                state = candidate
                break
        if isinstance(state, dict) and isinstance(state.get("template"), str):
            state = resolve_template(state, registry, ctx, depth + 1)
        if not isinstance(state, dict):
            if defn:
                manual.append({"path": path, "where": block_id,
                               "reason": "缺少 state/states 段"})
            return out
        state = subst_tree(state, ctx)
    except Unresolved as exc:
        manual.append({"path": path, "where": block_id,
                       "reason": "模板/宏无法展开: %s" % exc})
        return out
    for akey, app in _iter_appearances(block_id, state, ctx, origin, path, factory, manual):
        if not isinstance(app, dict):
            continue
        spec = app.get("auto_state")
        if isinstance(spec, dict) and isinstance(spec.get("id"), str):
            # 共享 auto_state.id：CE 以此 id 作为状态键，多个方块共用同一视觉状态
            ce_key = "%s[id=%s]" % (spec.get("type"), spec["id"])
        elif not akey:
            ce_key = block_id
        elif "=" in akey:
            ce_key = "%s[%s]" % (block_id, akey)
        else:
            ce_key = "%s[appearance=%s]" % (block_id, akey)
        rec = Appearance(ce_key, block_id, origin, path, factory)
        try:
            rec.model_sig = model_signature(app, ctx)
            if isinstance(app.get("state"), str):
                rec.donor_kind, rec.donor = "fixed", canon_key(app["state"])
            elif app.get("auto_state") is not None:
                spec = app["auto_state"]
                if isinstance(spec, dict):
                    rec.group = spec.get("type")
                    rec.auto_id = spec.get("id")
                elif isinstance(spec, str):
                    rec.group = spec
                if isinstance(rec.group, str):
                    rec.donor_kind = "auto"
                else:
                    rec.donor_kind = "unknown"
                    rec.reason = "auto_state 缺少 type"
            else:
                rec.donor_kind = "unknown"
                rec.reason = "既无 state 也无 auto_state"
        except Unresolved as exc:
            rec.donor_kind = "unknown"
            rec.reason = "宏无法解析: %s" % exc
        out.append(rec)
    return out


def collect(root, packs, version, manual):
    """返回 (appearances, templates, mappings_pairs, section_stats)。

    两遍：先收全部模板（跨包），再展开方块——避免包遍历顺序影响模板可见性。
    """
    registry = {}
    appearances = []
    mappings = []
    stats = Counter()
    mapping_sources = []
    loaded = []

    for pack in packs:
        if not pack.enabled:
            continue
        for path in pack_files(pack):
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            warnings = []
            try:
                tree = load_yaml(path, version, warnings)
            except yaml.YAMLError as exc:
                manual.append({"path": rel, "where": "<file>",
                               "reason": "YAML 不可用（含重复键）: %s" %
                                         str(exc).replace("\n", " ")[:200]})
                continue
            for w in warnings:
                manual.append({"path": rel, "where": "<version>",
                               "reason": w["reason"]})
            if isinstance(tree, dict):
                loaded.append((pack, rel, tree))

    # 第一遍：模板注册（后定义覆盖先定义）
    for _pack, _rel, tree in loaded:
        for key, value in tree.items():
            kind, _ident = partition(str(key))
            if kind == "templates" and isinstance(value, dict):
                registry.update(value)

    # 第二遍：方块展开
    for pack, rel, tree in loaded:
        for key, value in tree.items():
            kind, ident = partition(str(key))
            if kind == "block-state-mappings" and isinstance(value, dict):
                mapping_sources.append(((rel, ident), value))
            elif kind in ("blocks", "block") and isinstance(value, dict):
                for bid, defn in value.items():
                    stats["blocks"] += 1
                    appearances.extend(expand_block(
                        str(bid), defn, registry,
                        {"__NAMESPACE__": pack.namespace, "__FILE__": rel,
                         "__ID__": str(bid)},
                        "inline", rel, None, manual))
            elif kind == "config-factory":
                factories = 0
                instances = value.get("instances") if isinstance(value, dict) else None
                blueprint = value.get("blueprint") if isinstance(value, dict) else None
                if not isinstance(instances, list) or not isinstance(blueprint, dict):
                    manual.append({"path": rel, "where": key,
                                   "reason": "config_factory 缺少 instances/blueprint"})
                    continue
                for idx, inst in enumerate(instances):
                    if not isinstance(inst, dict):
                        manual.append({"path": rel, "where": key,
                                       "reason": "实例 #%d 不是映射" % idx})
                        continue
                    ctx = {"__NAMESPACE__": pack.namespace, "__FILE__": rel}
                    ctx.update(inst)
                    blocks = blueprint.get("blocks")
                    if not isinstance(blocks, dict):
                        continue
                    for bid_tpl, defn in blocks.items():
                        try:
                            bid = subst_str(str(bid_tpl), ctx)
                        except Unresolved:
                            manual.append({"path": rel, "where": key,
                                           "reason": "实例 #%d 方块 ID 含未知宏" % idx})
                            continue
                        factories += 1
                        stats["blocks"] += 1
                        blocks_ctx = dict(ctx)
                        blocks_ctx["__ID__"] = bid
                        appearances.extend(expand_block(
                            bid, defn, registry, blocks_ctx,
                            "factory:%s#%d" % (key, idx), rel, key, manual))
                stats["factory_instances"] += factories
    stats["files"] = len(loaded)

    for (rel, ident), table in mapping_sources:
        for lhs, rhs in table.items():
            if isinstance(rhs, str) and isinstance(lhs, str):
                mappings.append((rel, canon_key(lhs), canon_key(rhs)))
    return appearances, registry, mappings, stats


# --------------------------------------------------------------------------
# 候选容量（mappings LHS → 同族免费槽位）
# --------------------------------------------------------------------------

def family_of(key):
    return parse_state(key)[0]


def derive_pools(mappings):
    """family -> 可借用（与原版观感等价）的候选状态集合，RHS 视为原版保留。"""
    pools = defaultdict(set)
    reserved = defaultdict(set)
    for _rel, lhs, rhs in mappings:
        fam = family_of(rhs)
        reserved[fam].add(rhs)
        if lhs != rhs:
            pools[fam].add(lhs)
    return pools, reserved


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------

def _json_load(path, warnings, label):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as exc:
        warnings.append({"path": label, "reason": "读取失败: %s" % exc})
        return {}


def _read_text(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return None


def _block_span(lines, block_id):
    """找 `  <block_id>:` 顶层声明在文件中的行范围 [start, end)。"""
    pattern = re.compile(r"^([ \t]*)[\"']?" + re.escape(block_id) + r"[\"']?[ \t]*:[ \t]*$")
    for idx, line in enumerate(lines):
        m = pattern.match(line.rstrip("\r\n"))
        if not m:
            continue
        indent = len(m.group(1))
        for nxt in range(idx + 1, len(lines)):
            stripped = lines[nxt].strip()
            if not stripped or stripped.startswith("#"):
                continue
            current = len(lines[nxt]) - len(lines[nxt].lstrip())
            if current <= indent:
                return idx, nxt
        return idx, len(lines)
    return None


def build_plan(root):
    """纯函数：读 root，返回 (plan, patches, report)。不写目标目录。"""
    root = os.path.abspath(root)
    version = TARGET_VERSION
    manual = []
    warnings = []
    packs = load_packs(root)
    enabled = [p for p in packs if p.enabled]
    disabled = [p for p in packs if not p.enabled]

    appearances, registry, mappings, stats = collect(root, packs, version, manual)

    cache_dir = os.path.join(root, "cache")
    visual = _json_load(os.path.join(cache_dir, "visual_block_states.json"),
                        warnings, "cache/visual_block_states.json")
    registry_map = _json_load(os.path.join(cache_dir, "custom_block_states.json"),
                              warnings, "cache/custom_block_states.json")
    if not isinstance(visual, dict):
        visual = {}
    if not isinstance(registry_map, dict):
        registry_map = {}

    pools, reserved = derive_pools(mappings)
    capacity = {fam: len(states) for fam, states in pools.items()}
    # 家族真实占用：既有视觉分配 + 所有固定字面量（按状态去重）。
    # 剩余容量 = 候选数 - 真实占用，这才是「还能塞几个」；
    # 只看组内计数会把已占满的家族当成可继续接收，从而给出非法目标。
    occupied_states = {canon_key(d) for d in visual.values() if isinstance(d, str)}
    occupied_states |= {rec.donor for rec in appearances if rec.donor_kind == "fixed"}
    family_used = Counter(family_of(state) for state in occupied_states)
    family_free = {fam: max(0, capacity.get(fam, 0) - family_used.get(fam, 0))
                   for fam in capacity}

    # ---- 外观索引 ----
    by_ce_key = {}
    fixed_claims = defaultdict(list)          # 规范 vanilla 状态 -> 声明者
    for rec in appearances:
        if rec.donor_kind == "fixed":
            fixed_claims[rec.donor].append(rec)
        by_ce_key.setdefault(canon_key(rec.ce_key), []).append(rec)

    # ---- 占用合并：固定字面量 + 既有视觉分配 ----
    owners = defaultdict(list)                # 规范 vanilla 状态 -> 占用者
    for state, recs in fixed_claims.items():
        for rec in recs:
            owners[state].append({"ce_state": rec.ce_key, "model": rec.model_sig,
                                  "source": "fixed", "path": rec.path,
                                  "factory": rec.factory, "auto_key": None})
    unresolved_cache = []
    for ce_key, donor in visual.items():
        if not isinstance(donor, str):
            continue
        ck = canon_key(ce_key)
        cand = by_ce_key.get(ck)
        model = None
        source = "cache"
        auto_key = None
        if cand:
            model = cand[0].model_sig
            if cand[0].donor_kind == "fixed":
                source = "cache+fixed"
            if cand[0].donor_kind == "auto":
                auto_key = (cand[0].group, cand[0].auto_id)
        else:
            unresolved_cache.append(ce_key)
        owners[canon_key(donor)].append({"ce_state": ce_key, "model": model,
                                         "source": source, "path": None,
                                         "factory": None, "auto_key": auto_key})

    # ---- 冲突判定：同一视觉状态被不同 CE owner 认领 ----
    conflicts = []
    unverifiable = []
    shared_render = []
    for state, recs in sorted(owners.items()):
        # 每个 CE owner 取最可信的渲染签名（已知优先），避免 cache/fixed 同一 owner 自撞
        per_owner = {}
        for rec in recs:
            sig = rec["model"]
            cur = per_owner.get(rec["ce_state"])
            if cur is None or (cur in (None, "unresolved")
                               and sig not in (None, "unresolved")):
                per_owner[rec["ce_state"]] = sig
        if len(per_owner) < 2:
            continue
        shared_ids = {rec["auto_key"] for rec in recs
                      if rec["auto_key"] and rec["auto_key"][1]}
        if len(shared_ids) == 1 and all(rec["auto_key"] and rec["auto_key"][1]
                                        for rec in recs):
            continue                      # 共享 auto_state.id，另有 shared_auto 清单
        sigs = {sig for sig in per_owner.values() if sig not in (None, "unresolved")}
        kinds = {render_kind(sig) for sig in sigs}
        claims = sorted(({"ce_state": rec["ce_state"], "model": rec["model"],
                          "source": rec["source"], "path": rec["path"],
                          "factory": rec["factory"]}
                         for rec in recs), key=lambda x: x["ce_state"])
        if kinds and kinds <= {"entity", "invisible"}:
            # 全部靠实体渲染 / 隐形基底：共用同一个原版状态是正当做法
            shared_render.append({"state": state, "kinds": sorted(kinds),
                                  "claims": [c["ce_state"] for c in claims]})
            continue
        if len(sigs) >= 2:
            conflicts.append({"state": state, "kind": "render_mismatch",
                              "claims": claims})
        elif len(sigs) == 1:
            # 一个 owner 有明确渲染，其余认领者渲染未知 —— 不能当作不冲突
            conflicts.append({"state": state, "kind": "unverified_claim",
                              "claims": claims})
        else:
            unverifiable.append({"state": state, "owners": sorted(per_owner)})

    # 共享 auto_state.id：同一 id 被多个方块（不同 model）共用是配置里写明的意图，
    # 单独列出供人确认，不当作冲突。
    shared_auto = []
    for ce_key, recs in sorted(by_ce_key.items()):
        ids = {r.auto_id for r in recs if r.auto_id}
        if len(ids) != 1:
            continue
        models = sorted({r.model_sig for r in recs if r.model_sig})
        if len(models) < 2:
            continue
        shared_auto.append({"auto_state": "%s[id=%s]" % (recs[0].group, next(iter(ids))),
                            "ce_state": ce_key,
                            "claimed_by": sorted({r.block_id for r in recs}),
                            "distinct_models": len(models)})

    # ---- auto_state 组均分 dry-run（只报告） ----
    # 归属：先按精确 CE 状态键；不中则按 block id 推断（缓存与配置存在漂移时仍可归属）。
    block_groups = defaultdict(set)
    for rec in appearances:
        if rec.donor_kind == "auto" and rec.group:
            block_groups[rec.block_id].add(rec.group)
    groups = defaultdict(lambda: {"total": 0, "by_family": Counter(),
                                  "exact": 0, "inferred": 0})
    donor_group_missing = []
    entry_group = {}
    for ce_key, donor in visual.items():
        if not isinstance(donor, str):
            continue
        recs = by_ce_key.get(canon_key(ce_key)) or []
        rec = next((r for r in recs if r.donor_kind == "auto"), None)
        if rec is not None:
            group, how = rec.group, "exact"
        else:
            cand = block_groups.get(norm_block_id(ce_key.split("[")[0]))
            if not cand or len(cand) != 1:
                donor_group_missing.append(ce_key)
                continue
            group, how = next(iter(cand)), "inferred"
        fam = family_of(donor)
        entry_group[ce_key] = (group, fam)
        groups[group]["total"] += 1
        groups[group]["by_family"][fam] += 1
        groups[group][how] += 1

    # 家族属性形状类：只有属性名集合完全一致的家族才可互借。
    # 这样 waterlogged / persistent / 朝向 / 连接 / 开关 / 半块 都不会被跨类搬动。
    family_shape = {}
    for fam, states in reserved.items():
        if states:
            family_shape[fam] = tuple(k for k, _ in parse_state(sorted(states)[0])[1])
    for fam, states in pools.items():
        if fam not in family_shape and states:
            family_shape[fam] = tuple(k for k, _ in parse_state(sorted(states)[0])[1])

    def shape_of(fam):
        return family_shape.get(fam)

    shape_classes = defaultdict(list)
    for fam, shape in family_shape.items():
        shape_classes[shape].append(fam)
    for shape in shape_classes:
        shape_classes[shape].sort()

    # AutoStateGroup 候选：组名本身就是原版家族名 -> 只允许该家族（如 mushroom_stem）；
    # 否则按同形状族类展开（mushroom -> 三种蘑菇，non_tintable_leaves -> 全部树叶家族）。
    observed = defaultdict(set)
    for _key, (grp, fam) in entry_group.items():
        observed[grp].add(fam)
    allowed = {}
    for grp, fams in observed.items():
        if grp in family_shape:
            allowed[grp] = {grp}
            continue
        expand = set()
        for fam in fams:
            expand |= set(shape_classes.get(shape_of(fam), [fam]))
        allowed[grp] = expand

    global_counts_before = Counter(family_used)

    def water_fill(total, fams, caps, current):
        """容量受限的确定性均分：目标之和 = total；余数优先给当前占用最多的家族，
        这样在同样"尽量平"的前提下移动数最少（少移动、幂等）。"""
        targets, active, left = {}, list(fams), total
        while active:
            share, extra = divmod(left, len(active))
            saturated = [f for f in active if caps[f] <= share]
            if saturated:
                for fam in saturated:
                    targets[fam] = caps[fam]
                    left -= caps[fam]
                active = [f for f in active if f not in saturated]
                continue
            for fam in active:
                targets[fam] = share
            order = sorted(active, key=lambda f: (-current[f], f))
            for fam in order[:extra]:
                targets[fam] += 1
            return targets
        return targets

    # 全局族类目标：同一属性形状类内均分（容量受「剩余真实槽位」限制）
    def water_fill(total, fams, caps, current):
        targets, active, left = {}, list(fams), total
        while active:
            share, extra = divmod(left, len(active))
            saturated = [f for f in active if caps[f] <= share]
            if saturated:
                for fam in saturated:
                    targets[fam] = caps[fam]
                    left -= caps[fam]
                active = [f for f in active if f not in saturated]
                continue
            for fam in active:
                targets[fam] = share
            order = sorted(active, key=lambda f: (-current[f], f))
            for fam in order[:extra]:
                targets[fam] += 1
            return targets
        return targets

    global_counts = Counter(family_used)
    targets = {}
    for shape, fams in sorted(shape_classes.items(), key=lambda kv: str(kv[0])):
        total = sum(global_counts[f] for f in fams)
        if len(fams) < 2 or total == 0:
            for fam in fams:
                targets[fam] = global_counts[fam]
            continue
        room = {f: global_counts[f] + family_free.get(f, 0) for f in fams}
        targets.update(water_fill(total, fams, room, global_counts))

    # 单一 allocated-set：fixed 与 auto 共用同一个「已分配」集合；
    # 之后给 auto 找新槽位时，fixed 已占掉的候选绝不会再发第二次。
    allocated = set(occupied_states)
    allocated |= {canon_key(v) for v in visual.values() if isinstance(v, str)}

    def free_slots(fam):
        return sorted(st for st in pools.get(fam, ()) if st not in allocated)

    def dev(fam, count):
        return abs(count - targets.get(fam, count))

    # 锁值属性：这些属性名的取值必须与源 donor 完全一致，否则就是把
    # 含水 / 常驻 / 朝向 / 连接 / 开关 / 半块 换掉了。
    LOCKED_PROPS = {"waterlogged", "persistent", "facing", "half", "open", "powered",
                    "in_wall", "up", "down", "axes", "axis", "hinge", "attached",
                    "disarmed", "north", "east", "south", "west", "type", "age",
                    "stage", "lit", "signal_fire", "instrument", "note", "berries",
                    "distance", "power", "level", "rotation", "part", "shape",
                    "cracked", "moisture", "snowy", "occupied", "eggs", "hatch",
                    "layers", "bites", "delay", "eye", "charges", "orientation",
                    "tilt", "mode", "vertical_direction", "thickness"}

    def locked_values(state):
        return {k: v for k, v in parse_state(state)[1] if k in LOCKED_PROPS}

    def pick_donor(fam, src_state):
        """在目标家族里挑一个与源 donor 锁值属性完全一致的候选。"""
        want = locked_values(src_state)
        for cand in free_slots(fam):
            got = locked_values(cand)
            if not want:
                return cand
            if all(got.get(k) == v for k, v in want.items()):
                return cand
        return None

    new_visual = dict(visual)
    moves, rebalance_notes = [], []
    budget = 4000
    while budget > 0:
        budget -= 1
        progressed = False
        over = [f for f in sorted(global_counts)
                if global_counts[f] > targets.get(f, global_counts[f])]
        for src in over:
            if global_counts[src] <= targets.get(src, global_counts[src]):
                continue
            by_model = defaultdict(list)
            for key, (grp, fam) in entry_group.items():
                if fam != src:
                    continue
                recs = by_ce_key.get(canon_key(key)) or []
                sig = recs[0].model_sig if recs else None
                by_model[sig or key].append(key)
            for sig in sorted(by_model, key=str):
                keys = sorted(by_model[sig])
                if global_counts[src] <= targets.get(src, global_counts[src]):
                    break
                best = None
                for dst in sorted(family_shape):
                    if dst == src or shape_of(dst) != shape_of(src):
                        continue
                    if global_counts[dst] >= targets.get(dst, global_counts[dst]):
                        continue
                    slots = free_slots(dst)
                    if not slots:
                        continue
                    if any(dst not in allowed.get(entry_group[k][0], set()) for k in keys):
                        continue
                    donor = pick_donor(dst, new_visual[keys[0]])
                    if donor is None:
                        continue
                    before = dev(src, global_counts[src]) + dev(dst, global_counts[dst])
                    after = dev(src, global_counts[src] - len(keys)) + \
                        dev(dst, global_counts[dst] + len(keys))
                    gain = after - before
                    if gain < 0 and (best is None or gain < best[1]):
                        best = (dst, gain, donor)
                if best is None:
                    continue
                dst, _gain, donor = best
                donor = _with_namespace(donor)
                for key in keys:
                    moves.append({"ce_state": key,
                                  "group": entry_group[key][0],
                                  "from_family": src,
                                  "to_family": dst,
                                  "from_donor": new_visual[key],
                                  "to_donor": donor})
                    new_visual[key] = donor
                    entry_group[key] = (entry_group[key][0], dst)
                allocated.add(canon_key(donor))
                global_counts[src] -= len(keys)
                global_counts[dst] += len(keys)
                progressed = True
                break
            if progressed:
                break
        if not progressed:
            break
    if budget <= 0:
        rebalance_notes.append({"reason": "均分迭代触顶，结果按当前进度给出"})

    # 残余不平衡如实报告（槽位耗尽/组资格不允许），不 clamp 后假装均衡
    residual = {}
    for shape, fams in sorted(shape_classes.items(), key=lambda kv: str(kv[0])):
        for fam in fams:
            gap = targets.get(fam, global_counts[fam]) - global_counts[fam]
            if gap == 0:
                continue
            if gap > 0:
                residual[fam] = {"deficit": gap, "reason": "slots_exhausted"}
                continue
            stuck = True
            for key, (grp, entry_fam) in entry_group.items():
                if entry_fam != fam:
                    continue
                if any(free_slots(d) for d in allowed.get(grp, set())
                       if d != fam and shape_of(d) == shape_of(fam)
                       and global_counts[d] < targets.get(d, global_counts[d])):
                    stuck = False
                    break
            residual[fam] = {"excess": -gap,
                             "reason": "slots_exhausted" if stuck else "group_restricted"}

    global_after = Counter(family_of(canon_key(v)) for v in new_visual.values()
                          if isinstance(v, str))
    global_after.update(family_of(rec.donor) for rec in appearances
                        if rec.donor_kind == "fixed")

    # Existing cached/fixed donors can outlive a reduced mapping pool. Keeping their IDs is correct,
    # but clamping the target to that immovable count must not disguise a capacity violation as balance.
    # Count physical slots once: entity-rendered aliases may legitimately share a donor.
    occupied = {canon_key(v) for v in new_visual.values() if isinstance(v, str)}
    occupied.update(canon_key(rec.donor) for rec in appearances if rec.donor_kind == "fixed")
    for fam, used in Counter(family_of(state) for state in occupied).items():
        cap = capacity.get(fam)
        if cap is not None and used > cap:
            residual.setdefault(fam, {"excess": used - cap, "reason": "capacity_exceeded"})

    # 组视图（参考）：真正的均分口径是上面的全局族类
    balance = {}
    for group, info in sorted(groups.items()):
        fams = sorted(info["by_family"])
        entry = {
            "total": info["total"],
            "by_family": dict(sorted(info["by_family"].items())),
            "capacity": {f: capacity.get(f) for f in fams},
            "family_free_slots": {f: family_free.get(f) for f in fams},
            "allowed_families": sorted(allowed.get(group, set())),
            "attribution": {"exact": info["exact"], "inferred": info["inferred"]},
        }
        if len(allowed.get(group, ())) < 2:
            entry["status"] = "single_pool"
        elif any(fam in residual for fam in fams):
            entry["status"] = "residual"
        else:
            entry["status"] = "balanced"
        balance[group] = entry

    # 全局族类均分（报告可逐条复算：before/after/target/capacity/free）
    global_balance = {}
    for shape, fams in sorted(shape_classes.items(), key=lambda kv: str(kv[0])):
        if len(fams) < 2:
            continue
        rows = {fam: {"before": global_counts_before.get(fam, 0),
                      "after": global_after.get(fam, 0),
                      "target": targets.get(fam, 0),
                      "capacity": capacity.get(fam),
                      "free_slots": family_free.get(fam)}
                for fam in fams
                if global_counts_before.get(fam) or global_after.get(fam)}
        if not rows:
            continue
        global_balance[str(list(shape))] = {
            "families": rows,
            "status": ("balanced" if all(r["after"] == r["target"]
                                         for r in rows.values())
                       and not any(fam in residual for fam in rows) else "residual"),
        }

    visual_change = None
    if moves:
        raw = _read_text(os.path.join(root, "cache", "visual_block_states.json"))
        if raw is not None:
            after = json.dumps(new_visual, ensure_ascii=False, separators=(",", ":"))
            visual_change = {
                "path": "cache/visual_block_states.json",
                "before_sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
                "after_sha256": hashlib.sha256(after.encode("utf-8")).hexdigest(),
                "patch_file": "patches/00-visual_block_states.json",
                "_content": after,
            }

    # ---- registry / 缓存一致性（ID 与 properties 必须原样保留） ----
    derived_keys = {canon_key(r.ce_key) for r in appearances}
    registry_keys = {canon_key(k) for k in registry_map}
    missing_registry = sorted(derived_keys - registry_keys)
    orphan_registry = sorted(registry_keys - derived_keys)
    coverage = {
        "visual_entries": len(visual),
        "matched_to_config": len(visual) - len(unresolved_cache),
        "not_matched": len(unresolved_cache),
        "registry_entries": len(registry_map),
        "config_states": len(derived_keys),
        "config_states_in_registry": len(derived_keys & registry_keys),
    }
    coverage["visual_cache_stale"] = bool(
        visual and coverage["matched_to_config"] * 2 < coverage["visual_entries"])

    # ---- 固定字面量补丁：仅冲突 + 唯一定位 + 同族空闲候选 ----
    patches = {}
    changes = []
    for conflict in conflicts:
        state = conflict["state"]
        fam = family_of(state)
        free = sorted(st for st in pools.get(fam, ()) if st not in owners)
        movers = [c for c in conflict["claims"]
                  if c["source"] in ("fixed", "cache+fixed") and c["path"]
                  and c["factory"] is None]
        if not movers:
            manual.append({"path": None, "where": state,
                           "reason": "冲突但无可安全回写的固定字面量（factory/自动分配）"})
            continue
        if not free:
            manual.append({"path": None, "where": state,
                           "reason": "同族无可借用的空闲状态，保持原样"})
            continue
        mover = sorted(movers, key=lambda c: c["ce_state"])[0]
        target = free[0]
        path = mover["path"]
        if path in patches:
            continue
        src = os.path.join(root, path)
        try:
            with open(src, "r", encoding="utf-8") as fh:
                text = fh.read()
        except OSError as exc:
            manual.append({"path": path, "where": state, "reason": "读取失败: %s" % exc})
            continue
        old_lit = state.replace("minecraft:", "", 1) if state.startswith("minecraft:") else state
        block_id = mover["ce_state"].split("[", 1)[0]
        lines = text.splitlines(keepends=True)
        span = _block_span(lines, block_id)
        if span is None:
            manual.append({"path": path, "where": mover["ce_state"],
                           "reason": "找不到该方块的顶层声明，不改"})
            continue
        start, end = span
        literal = re.compile(r"(?m)^([ \t]*(?:-[ \t]*)?state:[ \t]*)([\"']?)"
                             + re.escape(old_lit) + r"\2[ \t]*$")
        hits = [i for i in range(start, end) if literal.match(lines[i])]
        if len(hits) != 1:
            manual.append({"path": path, "where": mover["ce_state"],
                           "reason": "方块块内字面量定位不唯一(%d 处)，不改" % len(hits)})
            continue
        idx = hits[0]
        lines[idx] = literal.sub(lambda m: m.group(1) + (m.group(2) or "") + target
                                 + (m.group(2) or ""), lines[idx], count=1)
        text_new = "".join(lines)
        patches[path] = text_new
        changes.append({"path": path,
                        "before_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                        "after_sha256": hashlib.sha256(text_new.encode("utf-8")).hexdigest(),
                        "patch_file": "patches/%02d-%s"
                                      % (len(changes), path.replace("/", "__")),
                        "_rewrite": {"ce_state": mover["ce_state"],
                                     "from": old_lit, "to": target}})

    # ---- guards ----
    # 本批要改的路径不能同时出现在 guards 里（apply 会拒绝同路径双重身份）。
    changed_paths = {change["path"] for change in changes}
    if visual_change is not None:
        changed_paths.add(visual_change["path"])
    guards = []
    guard_paths = ["config.yml",
                   "cache/custom_block_states.json",
                   "cache/visual_block_states.json"]
    for pack in enabled:
        guard_paths.append(pack.rel + "/pack.yml")
        for path in pack_files(pack):
            guard_paths.append(os.path.relpath(path, root).replace(os.sep, "/"))
    for rel in guard_paths:
        if rel in changed_paths:
            continue
        full = os.path.join(root, rel)
        if os.path.isfile(full):
            guards.append({"path": rel, "before_sha256": _sha256(full)})

    if visual_change is not None:
        patches[visual_change["path"]] = visual_change["_content"]
        changes.insert(0, visual_change)

    report = {
        "schema": SCHEMA,
        "target_version": version,
        "packs_enabled": [p.name for p in enabled],
        "packs_disabled": [p.name for p in disabled],
        "audit": {
            "config_files": stats["files"],
            "blocks": stats["blocks"],
            "appearances": len(appearances),
            "donor_kinds": dict(Counter(r.donor_kind for r in appearances)),
            "auto_groups": dict(sorted(Counter(r.group for r in appearances
                                               if r.group).items())),
            "cache_visual_states": len(visual),
            "registry_ids": len(registry_map),
            "mapping_pairs": len(mappings),
            "pool_families": {f: capacity[f] for f in sorted(capacity) if capacity[f]},
        },
        "balance": balance,
        "global_balance": global_balance,
        "residual": residual,
        "visual_rebalance": {
            "applied_moves": len(moves),
            "moves": moves,
            "skipped": rebalance_notes[:20],
            "patch": "patches/00-visual_block_states.json" if moves else None,
            "note": "只改 cache/visual_block_states.json 的既有条目值；CE block ID、"
                    "properties 与 custom_block_states.json 的内部 id 全部不动。"
                    "每条 move 都带完整 from_donor/to_donor，可按 patch 独立复算。",
        },
        "conflicts": [{"state": c["state"], "kind": c.get("kind"),
                       "claims": c["claims"]} for c in conflicts],
        "unverifiable_claims": unverifiable[:20],
        "shared_render_bases": {
            "count": len(shared_render),
            "sample": shared_render[:10],
            "note": "全部由 entity_renderer 或隐形基底渲染，共用同一原版状态是正当的",
        },
        "cache_consistency": {
            "coverage": coverage,
            "config_states_missing_from_registry": missing_registry[:40],
            "registry_states_missing_from_config": orphan_registry[:40],
            "visual_states_without_group": unresolved_cache[:40],
            "visual_states_not_in_config": {
                "count": len(unresolved_cache),
                "sample": sorted(unresolved_cache)[:20],
                "note": "缓存里的状态键在当前配置中找不到（多半是配置改过而缓存未重建）；"
                        "这些状态仍计入占用，但只按 block id 推断 auto_state 组",
            },
            "visual_states_without_auto_group": {
                "count": len(donor_group_missing),
                "sample": sorted(donor_group_missing)[:20],
            },
            "shared_auto_state_ids": shared_auto[:20],
        },
        "manual_review": manual,
        "warnings": warnings,
        "notes": [
            "CE 的 DefaultSectionSerializer 只保存 block ID + properties，内部 state registry 数值不变；"
            "视觉状态变化不等于需要改世界里的方块数据。",
            "均分只在 auto_state 组内按同族（同一原版方块家族）重分配，不跨形状/朝向/含水/染色。",
            "被借用的原版状态仍然属于原版方块家族，本工具不会、也不能让原版外观完全消失。",
            "auto_state 组内的实际分配由 CE 运行时完成，工具只做确定性 dry-run 与建议。",
            "visual_block_states.json 若与当前配置/registry 不同步，占用与均分只能算近似，"
            "报告里已标注覆盖率和 stale 标志；config_factory 展开出的字段不回写，"
            "gate/fence 家族的 donor 改动应由源端（ce-balance-betterend）负责。",
        ],
    }

    plan = {
        "schema": SCHEMA,
        "root": root,
        "changes": [{k: c[k] for k in ("path", "before_sha256", "after_sha256",
                                       "patch_file")} for c in changes],
        "guards": guards,
        "report": report,
    }
    patch_files = {c["patch_file"]: patches[c["path"]] for c in changes}
    return plan, patch_files, report


# --------------------------------------------------------------------------
# 输出
# --------------------------------------------------------------------------

def _safe_write(outdir, rel, text):
    dest = os.path.abspath(os.path.join(outdir, rel))
    if os.path.commonpath([dest, os.path.abspath(outdir)]) != os.path.abspath(outdir):
        raise ToolError("越界路径: %s" % rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def write_outputs(outdir, plan, patches, report):
    os.makedirs(outdir, exist_ok=True)
    _safe_write(outdir, "plan.json",
                json.dumps(plan, ensure_ascii=False, indent=2, sort_keys=False) + "\n")
    _safe_write(outdir, "report.yml",
                yaml.safe_dump(report, allow_unicode=True, sort_keys=False,
                               default_flow_style=False, width=120))
    for rel, text in sorted(patches.items()):
        _safe_write(outdir, rel, text)


def summarize(plan, report):
    audit = report["audit"]
    kinds = audit["donor_kinds"]
    lines = [
        "CE 视觉状态审计（只读，未修改目标目录）",
        "  启用包 %d 个 / 配置 %d 个文件 / 方块 %d / 外观 %d"
        % (len(report["packs_enabled"]), audit["config_files"],
           audit["blocks"], audit["appearances"]),
        "  视觉来源: 固定 %d，auto_state %d，未知 %d"
        % (kinds.get("fixed", 0), kinds.get("auto", 0), kinds.get("unknown", 0)),
        "  冲突 %d 组，可安全补丁 %d 个文件，manual_review %d 条"
        % (len(report["conflicts"]), len(plan["changes"]), len(report["manual_review"])),
    ]
    for group, info in sorted(report["balance"].items()):
        if "target" in info:
            lines.append("  均分 %s: 现状 %s → 目标 %s，至少移动 %d"
                         % (group, info["by_family"], info["target"], info["minimal_moves"]))
        else:
            lines.append("  均分 %s: 现状 %s（%s%s）"
                         % (group, info["by_family"], info.get("status", "?"),
                            "，缺口 %d" % info["shortfall"] if info.get("shortfall") else ""))
    moved = report["visual_rebalance"]["applied_moves"]
    if moved:
        lines.append("  visual 补丁: cache/visual_block_states.json 改写 %d 处 donor（键与 ID 不变）"
                     % moved)
    for change in plan["changes"]:
        meta = change.get("_rewrite") or {}
        if meta:
            lines.append("  配置补丁 %s: %s %s → %s"
                         % (change["path"], meta.get("ce_state", ""),
                            meta.get("from", ""), meta.get("to", "")))
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="CraftEngine 自定义方块视觉状态审计与同族均分（默认只读）")
    parser.add_argument("--root", required=True, help="CraftEngine 插件目录")
    parser.add_argument("--output", required=True, help="报告与补丁输出目录")
    args = parser.parse_args(argv)
    try:
        plan, patches, report = build_plan(args.root)
    except ToolError as exc:
        sys.stderr.write("错误: %s\n" % exc)
        return 2
    write_outputs(args.output, plan, patches, report)
    print(summarize(plan, report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
