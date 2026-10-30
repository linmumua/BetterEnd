#!/usr/bin/env python3
"""CraftEngine 状态重平衡计划的安全校验与应用工具。

用法：
    # 1) 只校验（默认，不写任何文件，不需要停服证明）
    python tools/ce_state_apply.py --plan plan.json
    python tools/ce_state_apply.py --plan plan.json --root "E:/srv/plugins/CraftEngine"

    # 2) 应用（必须显式给出 --root，且必须能证明服务器已停止）
    python tools/ce_state_apply.py --plan plan.json --root "E:/srv/plugins/CraftEngine" --apply

    # 3) 回滚某个批次（只回退该批次 manifest 里记录的、且当前内容仍等于应用后哈希的文件）
    python tools/ce_state_apply.py --rollback "E:/srv/plugins/CraftEngine/state-rebalance-backups/<batch>/manifest.json" \
        --root "E:/srv/plugins/CraftEngine"

计划 schema（schema=1）：
    {
      "schema": 1,
      "root": "<CE 根目录绝对路径>",
      "changes": [
        {"path": "resources/betterend/configuration/blocks/x.yml",
         "before_sha256": "<64hex 或 null 表示该文件原本不存在>",
         "after_sha256": "<64hex>",
         "patch_file": "patches/0001-x.yml"}   # 相对 plan 目录的完整新文件
      ],
      "guards": [{"path": "cache/custom_block_states.json", "before_sha256": "<64hex 或 null>"}]
    }

安全边界（任何一条不满足就拒绝整批，不做部分写入）：
  * plan.root 必须与显式 --root 完全一致（应用/回滚时 --root 必填）。
  * 只允许改 `resources/**/configuration/*` 与 `cache/visual_block_states.json`；
    禁止 custom_block_states.json、config.yml、resources/*/pack.yml（包启用状态）、
    世界目录、可执行文件。
  * 所有路径必须是相对路径，拒绝绝对路径、盘符、`..`、以及经 Path.resolve 后
    逃出 root / plan 目录的符号链接、硬链接（junction）。
  * 拒绝重复 path；所有 before/after SHA-256 全量校验，patch 文件字节必须等于
    after_sha256；磁盘出现任何漂移就拒绝整批。
  * guards 必须覆盖 custom_block_states.json、config.yml 以及本次涉及的每个
    resources/*/pack.yml（存在时），并在应用前后各校验一次。
  * 默认 validate-only；--apply 前必须通过停服证明，证明不足一律拒绝。
    本工具没有 --force / --assume-stopped / --skip-stop-check 之类开关，
    也不接受在命令行注入检查函数。
  * --rollback 走与 --apply 完全同级的安全链：manifest 里的每次 path / backup_dir /
    backup_file 都按同一套相对路径 + 白名单 + Path.resolve 规则校验，备份内容必须
    等于 before_sha256，guards 必须未变，且同样要求停服证明；任何一项不通过都
    不写任何位置。只有“当前内容仍等于应用后哈希”的文件才回退，用户改动一律保留。
  * 不清理任何缓存（cache/ 下只写 visual_block_states.json 一个文件）。

停服证明（check_server_stopped）：
  服务器根 = CE根.parents[1]；按 server.properties 的 level-name 定位世界目录，
  对 world/session.lock、DIM-1/DIM1/dimensions 的 session.lock 逐个做独占加锁探测
  （Windows 用 msvcrt.locking，POSIX 用 fcntl.lockf），并辅以本机 java 进程的
  命令行/cwd 是否锚定在该服务器根上。任一项“可能仍在运行”或“无法判定”都拒绝写入。

退出码：
  0 成功（含幂等 no-op） / 1 用法或环境错误 / 2 计划非法或磁盘漂移
  3 停服证明不足被拒 / 4 应用失败（已尽量回滚） / 5 回滚被拒或部分跳过
"""

from __future__ import annotations

import argparse
import errno
import hashlib
import json
import os
import re
import shutil
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

SCHEMA_VERSION = 1

# 备份根目录（相对 CE 根），必须落在 resources 扫描树之外。
BACKUP_DIR_NAME = "state-rebalance-backups"

# 唯一允许修改的缓存文件；cache/ 下其它文件一律不动（不清理、不重建）。
CACHE_VISUAL_STATES = "cache/visual_block_states.json"

# 必须保持不变的关键文件（存在即要求 plan 提供 guard）。
REQUIRED_GUARD_FILES = ("cache/custom_block_states.json", "config.yml")

# CE 内置注册表包与全局模板包：即使位于 resources/**/configuration 也不许改（只能作 guard）。
PROTECTED_PACKS = ("internal", "default_templates")

EXECUTABLE_SUFFIXES = {
    ".jar", ".class", ".exe", ".dll", ".so", ".dylib", ".bat", ".cmd",
    ".ps1", ".sh", ".py", ".jnilib", ".msi",
}

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_INVALID_PLAN = 2
EXIT_REFUSED = 3
EXIT_APPLY_FAILED = 4
EXIT_ROLLBACK_PARTIAL = 5

_SHA_RE = re.compile(r"^[0-9a-f]{64}$")

# manifest 里单个文件条目允许的状态（rollback 只处理 replaced）。
KNOWN_ENTRY_STATES = ("pending", "replaced", "restored")


class PlanError(Exception):
    """计划非法 / 磁盘漂移。整批拒绝，不做任何写入。"""


class ApplyError(Exception):
    """应用过程中失败（已尝试回滚）。"""


# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _normcase(path: Path) -> str:
    return os.path.normcase(str(path))


def _is_within(root: Path, candidate: Path) -> bool:
    """candidate 是否在 root 之下（Windows 下大小写不敏感）。"""
    root_s = _normcase(root).rstrip("\\/")
    cand = _normcase(candidate)
    return cand == root_s or cand.startswith(root_s + os.sep)


def _parse_rel(raw: object, what: str) -> PurePosixPath:
    """把计划里的相对路径解析成 PurePosixPath，拒绝绝对路径 / 盘符 / '..'。"""
    if not isinstance(raw, str) or not raw.strip():
        raise PlanError("%s 必须是非空字符串：%r" % (what, raw))
    text = raw.strip().replace("\\", "/")
    if text.startswith("/") or text.startswith("//"):
        raise PlanError("%s 不允许绝对路径：%s" % (what, raw))
    if len(text) >= 2 and text[1] == ":" and text[0].isalpha():
        raise PlanError("%s 不允许带盘符：%s" % (what, raw))
    rel = PurePosixPath(text)
    if rel.is_absolute():
        raise PlanError("%s 不允许绝对路径：%s" % (what, raw))
    for part in rel.parts:
        if part == "..":
            raise PlanError("%s 不允许包含 '..'：%s" % (what, raw))
        if part == ".":
            raise PlanError("%s 不允许包含 '.'：%s" % (what, raw))
        if ":" in part:
            raise PlanError(
                "%s 的路径段不允许包含冒号（Windows ADS/流名）：%s" % (what, raw)
            )
    if not rel.parts:
        raise PlanError("%s 不能为空：%s" % (what, raw))
    return rel


def _resolve_under(base: Path, rel: PurePosixPath, what: str) -> Path:
    """在 base 下解析 rel，并用 Path.resolve 校验没有经符号链接/junction 越界。"""
    target = base / rel.as_posix()
    resolved = target.resolve(strict=False)
    if not _is_within(base.resolve(), resolved):
        raise PlanError(
            "%s 经符号链接/junction 逃出根目录：%s（解析为 %s）" % (what, rel.as_posix(), resolved)
        )
    return target


def _check_allowed_change(rel: PurePosixPath) -> None:
    """白名单：只允许 resources/**/configuration/* 与 cache/visual_block_states.json。"""
    parts = [p.lower() for p in rel.parts]
    if parts == [p.lower() for p in PurePosixPath(CACHE_VISUAL_STATES).parts]:
        return
    if parts and parts[0] == "resources":
        for index, part in enumerate(parts):
            if part == "configuration" and index >= 2 and index < len(parts) - 1:
                return
    raise PlanError(
        "不允许修改该路径（只允许 resources/**/configuration/* 与 %s）：%s"
        % (CACHE_VISUAL_STATES, rel.as_posix())
    )


def _check_forbidden_change(rel: PurePosixPath) -> None:
    """给出比白名单更直白的拒绝原因。"""
    low = rel.as_posix().lower()
    parts = [p.lower() for p in rel.parts]
    if low == "cache/custom_block_states.json":
        raise PlanError("禁止修改 CE 的 custom_block_states.json：%s" % rel.as_posix())
    if low == "config.yml":
        raise PlanError("禁止修改 CE 的根配置 config.yml：%s" % rel.as_posix())
    if len(parts) == 1:
        raise PlanError("禁止修改 CE 根目录下的任何文件：%s" % rel.as_posix())
    if parts[0] == "resources" and parts[-1] == "pack.yml":
        raise PlanError("禁止修改资源包包启用状态 pack.yml：%s" % rel.as_posix())
    if parts[0] == "resources" and len(parts) >= 2 and parts[1] in PROTECTED_PACKS:
        raise PlanError(
            "禁止修改 CE 内置/全局模板包 resources/%s（只允许出现在 guards 里）：%s"
            % (parts[1], rel.as_posix())
        )
    if rel.suffix.lower() in EXECUTABLE_SUFFIXES:
        raise PlanError("禁止修改可执行文件/脚本：%s" % rel.as_posix())
    if parts and parts[0] in ("cache",) and low != CACHE_VISUAL_STATES:
        raise PlanError("禁止修改 cache/ 下除 %s 之外的文件：%s" % (CACHE_VISUAL_STATES, rel.as_posix()))


def _atomic_write_bytes(path: Path, data: bytes) -> None:
    tmp = path.parent / (".%s.tmp-%d" % (path.name, os.getpid()))
    with open(tmp, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(str(tmp), str(path))


def _atomic_write_json(path: Path, payload: object) -> None:
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    _atomic_write_bytes(path, text.encode("utf-8"))


def _replace_file(src: Path, dst: Path) -> None:
    """把暂存文件原子替换到目标位置（数据文件替换的唯一入口，便于单测注入失败）。"""
    os.replace(str(src), str(dst))


# --------------------------------------------------------------------------
# 计划模型
# --------------------------------------------------------------------------


@dataclass
class Change:
    rel: PurePosixPath
    before_sha256: str | None
    after_sha256: str
    patch_rel: PurePosixPath
    patch_abs: Path

    @property
    def text(self) -> str:
        return self.rel.as_posix()


@dataclass
class Guard:
    rel: PurePosixPath
    before_sha256: str | None

    @property
    def text(self) -> str:
        return self.rel.as_posix()


@dataclass
class Plan:
    path: Path
    sha256: str
    root: Path
    changes: list[Change] = field(default_factory=list)
    guards: list[Guard] = field(default_factory=list)


@dataclass
class ValidatedPlan:
    plan: Plan
    current_hashes: dict[str, str | None]
    already_applied: list[str]
    lines: list[str]


def load_plan(plan_path: str | os.PathLike, root_arg: str | None = None) -> Plan:
    """读取并做结构性校验（不含磁盘哈希比对）。"""
    path = Path(plan_path).resolve()
    if not path.is_file():
        raise PlanError("计划文件不存在：%s" % path)
    raw = path.read_bytes()
    plan_sha = hashlib.sha256(raw).hexdigest()
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PlanError("计划不是合法 UTF-8 JSON：%s" % exc) from exc
    if not isinstance(data, dict):
        raise PlanError("计划根必须是对象")

    schema = data.get("schema")
    if schema != SCHEMA_VERSION:
        raise PlanError("不支持的 schema：%r（要求 %d）" % (schema, SCHEMA_VERSION))

    root_field = data.get("root")
    if not isinstance(root_field, str) or not root_field.strip():
        raise PlanError("root 必须是非空字符串")
    root_path = Path(root_field)
    if not root_path.is_absolute():
        raise PlanError("root 必须是绝对路径：%s" % root_field)
    root = root_path.resolve()
    if not root.is_dir():
        raise PlanError("root 不是已存在的目录：%s" % root)
    if root_arg is not None:
        given = Path(root_arg).resolve()
        if given != root:
            raise PlanError("--root 与计划 root 不一致：%s != %s" % (given, root))

    plan = Plan(path=path, sha256=plan_sha, root=root)
    plan_dir = path.parent

    changes_raw = data.get("changes")
    if not isinstance(changes_raw, list):
        raise PlanError("changes 必须是数组（允许空数组：无变更计划，仍会校验 guards）")
    seen: dict[str, str] = {}
    for index, item in enumerate(changes_raw):
        label = "changes[%d]" % index
        if not isinstance(item, dict):
            raise PlanError("%s 必须是对象" % label)
        rel = _parse_rel(item.get("path"), "%s.path" % label)
        _check_forbidden_change(rel)
        _check_allowed_change(rel)
        _resolve_under(root, rel, "%s.path" % label)
        key = os.path.normcase(rel.as_posix())
        if key in seen:
            raise PlanError("changes 中存在重复 path：%s" % rel.as_posix())
        seen[key] = rel.as_posix()

        before = item.get("before_sha256")
        if before is not None and (not isinstance(before, str) or not _SHA_RE.match(before)):
            raise PlanError("%s.before_sha256 必须是 null 或 64 位小写十六进制" % label)
        after = item.get("after_sha256")
        if not isinstance(after, str) or not _SHA_RE.match(after):
            raise PlanError("%s.after_sha256 必须是 64 位小写十六进制" % label)
        if before is not None and before == after:
            raise PlanError("%s 的 before/after 哈希相同，属无操作变更：%s" % (label, rel.as_posix()))

        patch_rel = _parse_rel(item.get("patch_file"), "%s.patch_file" % label)
        patch_abs = _resolve_under(plan_dir, patch_rel, "%s.patch_file" % label)
        if not patch_abs.is_file():
            raise PlanError("%s.patch_file 指向的文件不存在：%s" % (label, patch_abs))
        patch_sha = sha256_file(patch_abs)
        if patch_sha != after:
            raise PlanError(
                "%s 的 patch 内容哈希与应用后哈希不符：%s（patch=%s, after=%s）"
                % (label, rel.as_posix(), patch_sha, after)
            )
        plan.changes.append(
            Change(rel=rel, before_sha256=before, after_sha256=after,
                   patch_rel=patch_rel, patch_abs=patch_abs)
        )

    guards_raw = data.get("guards", [])
    if not isinstance(guards_raw, list):
        raise PlanError("guards 必须是数组")
    guard_seen: dict[str, str] = {}
    for index, item in enumerate(guards_raw):
        label = "guards[%d]" % index
        if not isinstance(item, dict):
            raise PlanError("%s 必须是对象" % label)
        rel = _parse_rel(item.get("path"), "%s.path" % label)
        _resolve_under(root, rel, "%s.path" % label)
        key = os.path.normcase(rel.as_posix())
        if key in guard_seen:
            raise PlanError("guards 中存在重复 path：%s" % rel.as_posix())
        if key in seen:
            raise PlanError("同一路径同时出现在 changes 与 guards 中：%s" % rel.as_posix())
        guard_seen[key] = rel.as_posix()
        before = item.get("before_sha256")
        if before is not None and (not isinstance(before, str) or not _SHA_RE.match(before)):
            raise PlanError("%s.before_sha256 必须是 null 或 64 位小写十六进制" % label)
        plan.guards.append(Guard(rel=rel, before_sha256=before))

    missing = _missing_required_guards(plan)
    if missing:
        raise PlanError(
            "guards 未覆盖必须保持不变的关键文件：%s（请让规划端补齐 guards）"
            % ", ".join(sorted(missing))
        )
    return plan


def _pack_roots(change: Change) -> str | None:
    parts = list(change.rel.parts)
    low = [p.lower() for p in parts]
    if not low or low[0] != "resources" or "configuration" not in low:
        return None
    index = low.index("configuration")
    if index < 2:
        return None
    return "/".join(parts[:index])


def _missing_required_guards(plan: Plan) -> set[str]:
    """必须存在 guard 的关键文件：存在即要求，且本次涉及的每个 pack 的 pack.yml。"""
    have = {os.path.normcase(g.rel.as_posix()) for g in plan.guards}
    required: list[str] = []
    for name in REQUIRED_GUARD_FILES:
        if (plan.root / name).is_file():
            required.append(name)
    pack_roots = sorted({p for p in (_pack_roots(c) for c in plan.changes) if p})
    for pack in pack_roots:
        candidate = "%s/pack.yml" % pack
        if (plan.root / candidate).is_file():
            required.append(candidate)
    return {name for name in required if os.path.normcase(name) not in have}


def validate_plan(plan: Plan) -> ValidatedPlan:
    """磁盘级校验：before 漂移、guard 漂移、幂等状态。任何不一致都拒绝整批。"""
    lines: list[str] = []
    errors: list[str] = []
    current: dict[str, str | None] = {}
    already: list[str] = []

    for change in plan.changes:
        target = plan.root / change.rel.as_posix()
        if not target.exists():
            actual = None
        elif not target.is_file():
            errors.append("目标是目录而非文件：%s" % change.text)
            continue
        else:
            actual = sha256_file(target)
        current[change.text] = actual
        if actual == change.after_sha256:
            already.append(change.text)
            lines.append("[已应用] %s" % change.text)
            continue
        if change.before_sha256 is None:
            if actual is not None:
                errors.append(
                    "计划认为文件原本不存在，但磁盘上已存在：%s（sha=%s）" % (change.text, actual)
                )
            else:
                lines.append("[将新建] %s" % change.text)
            continue
        if actual is None:
            errors.append("计划要求修改的文件不存在：%s" % change.text)
            continue
        if actual != change.before_sha256:
            errors.append(
                "文件内容已漂移：%s（磁盘=%s，计划=%s）" % (change.text, actual, change.before_sha256)
            )
            continue
        lines.append("[将修改] %s" % change.text)

    for guard in plan.guards:
        target = plan.root / guard.rel.as_posix()
        if target.exists() and target.is_file():
            actual = sha256_file(target)
            if guard.before_sha256 is None:
                errors.append("guard 认为文件不应存在，但磁盘上存在：%s" % guard.text)
            elif actual != guard.before_sha256:
                errors.append(
                    "guard 文件已变化：%s（磁盘=%s，计划=%s）"
                    % (guard.text, actual, guard.before_sha256)
                )
            else:
                lines.append("[guard 不变] %s" % guard.text)
        else:
            if guard.before_sha256 is not None:
                errors.append("guard 文件缺失：%s" % guard.text)
            else:
                lines.append("[guard 不存在] %s" % guard.text)

    if errors:
        raise PlanError("校验失败，整批拒绝：\n  - " + "\n  - ".join(errors))
    return ValidatedPlan(plan=plan, current_hashes=current, already_applied=already, lines=lines)


def _guard_problems(guards: list[Guard], root: Path) -> list[str]:
    """返回 guard 漂移问题清单（空表示全部保持不变）。"""
    problems: list[str] = []
    for guard in guards:
        target = root / guard.rel.as_posix()
        if guard.before_sha256 is None:
            if target.exists():
                problems.append("%s 不应存在却出现了" % guard.text)
            continue
        if not target.is_file():
            problems.append("%s 消失了" % guard.text)
            continue
        actual = sha256_file(target)
        if actual != guard.before_sha256:
            problems.append("%s 变化了（%s != %s）" % (guard.text, actual, guard.before_sha256))
    return problems


def _verify_guards(plan: Plan, phase: str) -> None:
    problems = _guard_problems(plan.guards, plan.root)
    if problems:
        raise ApplyError("%s guard 校验失败：\n  - %s" % (phase, "\n  - ".join(problems)))


def _verify_targets_before(plan: Plan) -> None:
    """替换前最后一道漂移检测：备份之后若有人改了目标，就拒绝覆盖。"""
    problems: list[str] = []
    for change in plan.changes:
        target = plan.root / change.rel.as_posix()
        if change.before_sha256 is None:
            if target.exists():
                problems.append("%s 在备份后被创建" % change.text)
            continue
        if not target.is_file():
            problems.append("%s 在备份后被删除" % change.text)
            continue
        actual = sha256_file(target)
        if actual != change.before_sha256:
            problems.append(
                "%s 在备份后被改动（%s != %s）" % (change.text, actual, change.before_sha256)
            )
    if problems:
        raise ApplyError("替换前目标漂移，拒绝覆盖：\n  - " + "\n  - ".join(problems))


# --------------------------------------------------------------------------
# 停服证明
# --------------------------------------------------------------------------


@dataclass
class StopEvidence:
    state: str  # stopped / running / unknown
    details: list[str]

    @property
    def stopped(self) -> bool:
        return self.state == "stopped"

    @property
    def text(self) -> str:
        return "\n".join(self.details)


def _server_root_for(ce_root: Path) -> Path | None:
    parents = ce_root.parents
    if len(parents) < 2:
        return None
    server_root = parents[1]
    if server_root == ce_root or not server_root.is_dir():
        return None
    return server_root


def _read_level_name(props: Path) -> str | None:
    try:
        text = props.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == "level-name":
            name = value.strip().strip('"').strip("'")
            return name or None
    return None


def _lock_candidates(world_dir: Path) -> list[Path]:
    """世界的 session.lock 候选（原版 + 维度目录 + 自定义维度），只返回存在的。"""
    cands = [world_dir / "session.lock"]
    for dim in ("DIM-1", "DIM1"):
        cands.append(world_dir / dim / "session.lock")
    dims = world_dir / "dimensions"
    if dims.is_dir():
        try:
            for ns in sorted(dims.iterdir()):
                if not ns.is_dir():
                    continue
                for sub in sorted(ns.iterdir()):
                    if sub.is_dir():
                        cands.append(sub / "session.lock")
        except OSError:
            pass
    return [c for c in cands if c.exists()]


def _probe_exclusive_lock(path: Path) -> tuple[str, str]:
    """尝试独占锁住 session.lock：locked / free / unknown。

    Windows 用 msvcrt.locking（底层 LockFileEx 区间锁），POSIX 用 fcntl.lockf
    （Java 的 FileChannel.lock 在 Linux 上也是 POSIX 记录锁，两者互斥）。
    """
    flags = os.O_RDWR | getattr(os, "O_BINARY", 0)
    try:
        fd = os.open(str(path), flags)
    except FileNotFoundError:
        return "unknown", "session.lock 不存在：%s" % path
    except PermissionError as exc:
        return "unknown", "无法打开 session.lock（权限不足）：%s（%s）" % (path, exc)
    except OSError as exc:
        return "unknown", "无法打开 session.lock：%s（%s）" % (path, exc)
    try:
        if os.name == "nt":
            import msvcrt

            try:
                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                return "locked", "session.lock 已被占用：%s（%s）" % (path, exc)
            try:
                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
            except OSError:
                pass
            return "free", "session.lock 可独占加锁：%s" % path

        try:
            import fcntl
        except ImportError:
            return "unknown", "当前平台缺少 fcntl，无法判定文件锁"
        try:
            fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            if exc.errno in (errno.EACCES, errno.EAGAIN, errno.EDEADLK, errno.EWOULDBLOCK):
                return "locked", "session.lock 已被占用：%s（%s）" % (path, exc)
            return "unknown", "fcntl 加锁异常：%s（%s）" % (path, exc)
        try:
            fcntl.lockf(fd, fcntl.LOCK_UN)
        except OSError:
            pass
        return "free", "session.lock 可独占加锁：%s" % path
    finally:
        os.close(fd)


def _cmdline_matches(cmdline: list[str], server_root: Path) -> bool:
    needle = _normcase(server_root).rstrip("\\/")
    for arg in cmdline:
        if not arg:
            continue
        try:
            candidate = _normcase(Path(arg))
        except (OSError, ValueError):
            continue
        if candidate == needle or candidate.startswith(needle + os.sep):
            return True
    return False


def _scan_processes_with_psutil(server_root: Path) -> tuple[str, list[str]] | None:
    try:
        import psutil  # type: ignore
    except ImportError:
        return None
    details: list[str] = []
    inaccessible: list[str] = []
    saw_java = False
    try:
        iterator = psutil.process_iter(["pid", "name"])
    except Exception:  # psutil 自身不可用
        return "unknown", ["psutil 无法枚举进程"]
    for proc in iterator:
        try:
            name = (proc.info.get("name") or "").lower()
        except Exception:
            name = ""
        if not name.startswith("java"):
            continue
        saw_java = True
        pid = proc.info.get("pid")
        try:
            cwd = proc.cwd()
        except psutil.NoSuchProcess:
            continue
        except psutil.AccessDenied:
            cwd = None
            inaccessible.append("pid %s：无法读取 cwd（权限不足）" % pid)
        except Exception as exc:  # noqa: BLE001
            cwd = None
            inaccessible.append("pid %s：读取 cwd 异常（%s）" % (pid, exc))
        if cwd is not None and _normcase(Path(cwd)) == _normcase(server_root):
            return "match", details + ["pid %s 的 cwd 就是服务器根：%s" % (pid, server_root)]
        try:
            cmdline = proc.cmdline()
        except psutil.NoSuchProcess:
            continue
        except psutil.AccessDenied:
            inaccessible.append("pid %s：无法读取命令行（权限不足）" % pid)
            continue
        except Exception as exc:  # noqa: BLE001
            inaccessible.append("pid %s：读取命令行异常（%s）" % (pid, exc))
            continue
        if _cmdline_matches(cmdline, server_root):
            return "match", details + ["pid %s 的命令行锚定在服务器根：%s" % (pid, server_root)]
    if inaccessible:
        return "unknown", details + inaccessible
    if not saw_java:
        return "no_match", details + ["未发现任何 java 进程"]
    return "no_match", details + ["未发现锚定在 %s 的 java 进程" % server_root]


def _scan_processes_via_proc(server_root: Path) -> tuple[str, list[str]] | None:
    proc_root = Path("/proc")
    if not proc_root.is_dir():
        return None
    details: list[str] = []
    inaccessible: list[str] = []
    saw_java = False
    needle = _normcase(server_root)
    try:
        entries = [p for p in proc_root.iterdir() if p.name.isdigit()]
    except OSError as exc:
        return "unknown", ["无法读取 /proc：%s" % exc]
    for pid_dir in entries:
        try:
            cmdline = (pid_dir / "cmdline").read_bytes().decode("utf-8", "replace")
        except OSError:
            continue
        if not cmdline.strip("\x00"):
            continue
        args = [a for a in cmdline.split("\x00") if a]
        if not args or not os.path.basename(args[0]).lower().startswith("java"):
            continue
        saw_java = True
        try:
            cwd = Path(os.readlink(pid_dir / "cwd"))
        except OSError:
            cwd = None
            inaccessible.append("pid %s：无法读取 cwd（权限不足）" % pid_dir.name)
        if cwd is not None and _normcase(cwd) == needle:
            return "match", details + ["pid %s 的 cwd 就是服务器根" % pid_dir.name]
        if _cmdline_matches(args, server_root):
            return "match", details + ["pid %s 的命令行锚定在服务器根" % pid_dir.name]
    if inaccessible:
        return "unknown", details + inaccessible
    if not saw_java:
        return "no_match", details + ["未发现任何 java 进程"]
    return "no_match", details + ["未发现锚定在 %s 的 java 进程" % server_root]


def _scan_processes_via_powershell(server_root: Path) -> tuple[str, list[str]] | None:
    if os.name != "nt":
        return None
    import subprocess

    script = (
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | "
        "Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"
    )
    try:
        completed = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True, timeout=25, check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return "unknown", ["无法通过 PowerShell 枚举进程：%s" % exc]
    if completed.returncode != 0:
        return "unknown", ["PowerShell 枚举进程失败（退出码 %s）" % completed.returncode]
    text = completed.stdout.decode("utf-8", "replace").strip()
    if not text:
        return "no_match", ["未发现 java 进程"]
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        return "unknown", ["PowerShell 输出无法解析：%s" % exc]
    rows = payload if isinstance(payload, list) else [payload]
    needle = _normcase(server_root)
    for row in rows:
        if not isinstance(row, dict):
            continue
        command = row.get("CommandLine")
        if not isinstance(command, str):
            return "unknown", ["pid %s：无法取得命令行" % row.get("ProcessId")]
        if command.strip().lower().startswith("powershell"):
            continue
        # 命令行里出现服务器根（用 \\ 与 / 两种写法分别比对）
        for chunk in re.split(r"\s+", command):
            token = chunk.strip('"').strip("'")
            if not token:
                continue
            try:
                candidate = _normcase(Path(token))
            except (OSError, ValueError):
                continue
            if candidate.startswith(needle + os.sep) or candidate == needle:
                return "match", ["pid %s 的命令行锚定在服务器根" % row.get("ProcessId")]
    return "no_match", ["未发现锚定在 %s 的 java 进程" % server_root]


def _scan_java_processes(server_root: Path) -> tuple[str, list[str]]:
    for scanner in (_scan_processes_with_psutil, _scan_processes_via_proc,
                    _scan_processes_via_powershell):
        result = scanner(server_root)
        if result is not None:
            return result
    return "unknown", ["本机无法枚举进程，缺少可信停服证据"]


def check_server_stopped(root: Path) -> StopEvidence:
    """综合 session.lock 独占锁 + 本机 java 进程归属，判定服务器是否确已停止。

    只有“所有世界锁都能被独占 + 没有归因到本服务器根的 java 进程”才返回 stopped；
    任何“可能仍在运行”或“无法判定”都返回 running/unknown，由调用方拒绝写入。
    """
    details: list[str] = []
    ce_root = Path(root).resolve()
    server_root = _server_root_for(ce_root)
    if server_root is None:
        return StopEvidence("unknown", ["无法从 CE 根推出服务器根（期望 <server>/plugins/CraftEngine）：%s" % ce_root])
    details.append("服务器根：%s" % server_root)

    props = server_root / "server.properties"
    if not props.is_file():
        return StopEvidence(
            "unknown",
            details + ["缺少 server.properties，无法定位世界：%s" % props],
        )
    level = _read_level_name(props) or "world"
    world_dir = Path(level) if os.path.isabs(level) else server_root / level
    if not world_dir.is_dir():
        return StopEvidence("unknown", details + ["世界目录不存在：%s" % world_dir])
    details.append("世界目录：%s（level-name=%s）" % (world_dir, level))

    locks = _lock_candidates(world_dir)
    if not locks:
        return StopEvidence(
            "unknown",
            details + ["未找到任何 session.lock，无法证明服务器已停止"],
        )

    unknown_notes: list[str] = []
    for lock in locks:
        state, note = _probe_exclusive_lock(lock)
        if state == "locked":
            return StopEvidence("running", details + ["%s => 服务器仍持有世界锁" % note])
        if state == "unknown":
            unknown_notes.append(note)
        else:
            details.append(note)
    if unknown_notes:
        return StopEvidence("unknown", details + unknown_notes)

    proc_state, proc_details = _scan_java_processes(server_root)
    details.extend(proc_details)
    if proc_state == "match":
        return StopEvidence("running", details)
    if proc_state == "unknown":
        return StopEvidence("unknown", details)
    details.append("结论：世界锁全部可独占，且无归因到该服务器根的 java 进程")
    return StopEvidence("stopped", details)


# --------------------------------------------------------------------------
# 应用 / 回滚
# --------------------------------------------------------------------------


def _make_batch_id(plan: Plan) -> str:
    base = "%s-%s" % (time.strftime("%Y%m%d-%H%M%S"), plan.sha256[:8])
    candidate = base
    suffix = 1
    while (plan.root / BACKUP_DIR_NAME / candidate).exists():
        suffix += 1
        candidate = "%s-%d" % (base, suffix)
    return candidate


def _manifest_entry(change: Change) -> dict:
    return {
        "path": change.text,
        "before_sha256": change.before_sha256,
        "after_sha256": change.after_sha256,
        "existed": change.before_sha256 is not None,
        "backup_file": None,
        "state": "pending",
    }


def _restore_entries(root: Path, backup_dir: Path, entries: list[dict]) -> list[str]:
    """把已替换的条目回滚回备份内容；返回失败清单（失败也不删用户数据）。"""
    failures: list[str] = []
    for entry in reversed(entries):
        if entry.get("state") != "replaced":
            continue
        target = root / entry["path"]
        try:
            if entry.get("existed"):
                backup = backup_dir / entry["path"]
                if not backup.is_file():
                    failures.append("%s：备份缺失 %s" % (entry["path"], backup))
                    continue
                if sha256_file(backup) != entry["before_sha256"]:
                    failures.append("%s：备份内容与原始哈希不符" % entry["path"])
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                staging = target.parent / (".%s.restore-%d.tmp" % (target.name, os.getpid()))
                shutil.copy2(backup, staging)
                # 紧前再核一次：自愈期间目标若被外部改动，绝不覆盖用户内容。
                if not (target.is_file() and sha256_file(target) == entry["after_sha256"]):
                    try:
                        staging.unlink()
                    except OSError:
                        pass
                    failures.append(
                        "%s：目标在自愈前已被改动，未覆盖（保持现状）" % entry["path"]
                    )
                    continue
                os.replace(str(staging), str(target))
                if sha256_file(target) != entry["before_sha256"]:
                    failures.append("%s：恢复后哈希不匹配" % entry["path"])
                    continue
                entry["state"] = "restored"
            else:
                # 本批新建的文件：回滚就是移走（只在内容仍等于我们写入的哈希时）。
                # 隔离位置用完整相对路径，避免两个同名文件互相覆盖证据。
                if target.is_file() and sha256_file(target) == entry["after_sha256"]:
                    destination = backup_dir / "removed" / entry["path"]
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(str(target), str(destination))
                    entry["state"] = "restored"
                elif target.exists():
                    failures.append("%s：新建文件已被改动，未删除" % entry["path"])
                else:
                    entry["state"] = "restored"
        except OSError as exc:
            failures.append("%s：恢复失败（%s）" % (entry["path"], exc))
    return failures


def apply_batch(
    plan: Plan,
    validated: ValidatedPlan,
    *,
    stop_check=check_server_stopped,
    out=sys.stdout,
    batch_id: str | None = None,
) -> str:
    """先整批备份+暂存，再逐个 os.replace；失败时回滚本批已替换文件。"""
    root = plan.root
    batch = batch_id or _make_batch_id(plan)
    backup_rel = PurePosixPath(BACKUP_DIR_NAME) / batch
    backup_dir = root / backup_rel.as_posix()
    if backup_dir.exists():
        raise ApplyError("批次备份目录已存在，拒绝覆盖：%s" % backup_dir)
    if _is_within(root / "resources", backup_dir):
        raise ApplyError("备份目录不得位于 resources 扫描树内：%s" % backup_dir)

    backup_dir.mkdir(parents=True, exist_ok=False)
    entries = [_manifest_entry(c) for c in plan.changes]
    manifest: dict = {
        "schema": SCHEMA_VERSION,
        "batch": batch,
        "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "state": "staged",
        "root": str(root),
        "plan": {"path": str(plan.path), "sha256": plan.sha256},
        "backup_dir": backup_rel.as_posix(),
        "stop_evidence": [],
        "files": entries,
        "guards": [{"path": g.text, "before_sha256": g.before_sha256} for g in plan.guards],
    }
    temps: list[Path] = []
    created_dirs: list[Path] = []

    def persist() -> None:
        _atomic_write_json(backup_dir / "manifest.json", manifest)

    try:
        # 1) 备份原件（逐字节校验）
        for change, entry in zip(plan.changes, entries):
            if change.before_sha256 is None:
                continue
            target = root / change.rel.as_posix()
            backup = backup_dir / change.rel.as_posix()
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, backup)
            if sha256_file(backup) != change.before_sha256:
                raise ApplyError("备份校验失败：%s" % change.text)
            entry["backup_file"] = (backup_rel / change.rel).as_posix()
        persist()

        # 2) 整批暂存（同目录临时文件，保证 os.replace 原子且有 fsync）
        for change in plan.changes:
            target = root / change.rel.as_posix()
            if not target.parent.is_dir():
                missing: list[Path] = []
                cursor = target.parent
                while not cursor.exists():
                    missing.append(cursor)
                    cursor = cursor.parent
                target.parent.mkdir(parents=True, exist_ok=True)
                created_dirs.extend(missing)
            data = change.patch_abs.read_bytes()
            if hashlib.sha256(data).hexdigest() != change.after_sha256:
                raise ApplyError("patch 内容在暂存阶段发生变化：%s" % change.text)
            staging = target.parent / (".%s.ceapply-%s.tmp" % (target.name, batch))
            with open(staging, "wb") as fh:
                fh.write(data)
                fh.flush()
                os.fsync(fh.fileno())
            temps.append(staging)
        manifest["state"] = "staged"
        persist()

        # 3) 替换前再次确认停服 + guard 未变
        evidence = stop_check(root)
        manifest["stop_evidence"] = list(evidence.details)
        if not evidence.stopped:
            raise ApplyError(
                "停服证明不足（%s），拒绝写入：\n%s" % (evidence.state, evidence.text)
            )
        _verify_guards(plan, "替换前")
        _verify_targets_before(plan)

        # 4) 逐个原子替换
        manifest["state"] = "applying"
        for change, entry, staging in zip(plan.changes, entries, temps):
            target = root / change.rel.as_posix()
            _replace_file(staging, target)
            entry["state"] = "replaced"
            persist()

        # 5) 应用后校验
        for change in plan.changes:
            target = root / change.rel.as_posix()
            if not target.is_file() or sha256_file(target) != change.after_sha256:
                raise ApplyError("应用后哈希校验失败：%s" % change.text)
        _verify_guards(plan, "应用后")

        manifest["state"] = "applied"
        manifest["applied"] = time.strftime("%Y-%m-%dT%H:%M:%S")
        persist()
        print("[已应用] 批次 %s，%d 个文件" % (batch, len(plan.changes)), file=out)
        print("           备份与 manifest：%s" % (backup_dir / "manifest.json"), file=out)
        return batch

    except BaseException as exc:  # 含 KeyboardInterrupt：可捕捉的中断也回滚
        for staging in temps:
            try:
                if staging.exists():
                    staging.unlink()
            except OSError:
                pass
        failures = _restore_entries(root, backup_dir, entries)
        for path in created_dirs:
            try:
                if path.is_dir() and not any(path.iterdir()):
                    path.rmdir()
            except OSError:
                pass
        manifest["state"] = "rolled_back" if not failures else "rollback_failed"
        try:
            persist()
        except OSError:
            pass
        message = "应用到 %s 时失败：%s" % (batch, exc)
        if failures:
            message += "\n回滚未完成，下列文件需要人工处理（未删除任何用户数据）：\n  - " + \
                "\n  - ".join(failures)
        else:
            message += "\n本批已替换的文件已全部回滚。"
        raise ApplyError(message) from exc


def run_apply(
    plan_path: str,
    root_arg: str | None,
    *,
    apply: bool = False,
    stop_check=check_server_stopped,
    out=sys.stdout,
) -> int:
    try:
        plan = load_plan(plan_path, root_arg)
        validated = validate_plan(plan)
    except PlanError as exc:
        print("[拒绝] %s" % exc, file=out)
        return EXIT_INVALID_PLAN

    print("[计划] %s（sha256=%s）" % (plan.path, plan.sha256[:16]), file=out)
    print("[根目录] %s" % plan.root, file=out)
    print("[变更] %d 个文件，[guards] %d 个" % (len(plan.changes), len(plan.guards)), file=out)
    for line in validated.lines:
        print("  " + line, file=out)

    if len(validated.already_applied) == len(plan.changes):
        print("[no-op] 所有目标文件已等于应用后哈希，本批无需写入。", file=out)
        return EXIT_OK
    if validated.already_applied:
        print(
            "[拒绝] 本批部分文件已是应用后状态，部分不是；疑似上次写入中断。"
            "请先人工确认或回滚上一批次，不做部分写入。",
            file=out,
        )
        return EXIT_INVALID_PLAN

    if not apply:
        print("[校验通过] 当前为 validate-only，未写入任何文件。", file=out)
        return EXIT_OK

    evidence = stop_check(plan.root)
    print("[停服证明] %s" % evidence.state, file=out)
    for detail in evidence.details:
        print("  " + detail, file=out)
    if not evidence.stopped:
        print("[拒绝] 无法证明服务器已停止，拒绝写入。", file=out)
        return EXIT_REFUSED

    try:
        apply_batch(plan, validated, stop_check=stop_check, out=out)
    except ApplyError as exc:
        print("[失败] %s" % exc, file=out)
        return EXIT_APPLY_FAILED
    return EXIT_OK


@dataclass
class RollbackItem:
    rel: PurePosixPath
    target: Path
    entry: dict
    backup: Path | None

    @property
    def text(self) -> str:
        return self.rel.as_posix()


def _load_rollback_manifest(manifest_path: str, root_arg: str) -> tuple[Path, Path, dict]:
    """读取并结构校验 manifest。任何不受信内容都抛 PlanError（调用方保证零写入）。"""
    path = Path(manifest_path).resolve()
    if not path.is_file():
        raise PlanError("manifest 不存在：%s" % path)
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PlanError("manifest 无法解析：%s" % exc) from exc
    if not isinstance(manifest, dict):
        raise PlanError("manifest 根必须是对象")
    if manifest.get("schema") != SCHEMA_VERSION:
        raise PlanError("manifest schema 不受支持：%r" % manifest.get("schema"))

    root_field = manifest.get("root")
    if not isinstance(root_field, str) or not root_field.strip():
        raise PlanError("manifest.root 必须是非空字符串")
    if not Path(root_field).is_absolute():
        raise PlanError("manifest.root 必须是绝对路径：%s" % root_field)
    root = Path(root_field).resolve()
    given = Path(root_arg).resolve()
    if given != root:
        raise PlanError("--root（%s）与 manifest 记录的 root（%s）不一致" % (given, root))
    if not root.is_dir():
        raise PlanError("root 不是已存在的目录：%s" % root)

    entries = manifest.get("files")
    if not isinstance(entries, list) or not entries:
        raise PlanError("manifest 没有文件记录")
    return path, root, manifest


def _resolve_rollback_items(
    root: Path, manifest: dict
) -> tuple[list[RollbackItem], list[Guard], list[str], Path]:
    """把 manifest 里的路径全部按与 plan 同级的安全规则解析。"""
    backup_rel = _parse_rel(manifest.get("backup_dir"), "manifest.backup_dir")
    backup_dir = _resolve_under(root, backup_rel, "manifest.backup_dir")
    batch = manifest.get("batch")
    if isinstance(batch, str) and batch and backup_dir.name != batch:
        raise PlanError(
            "manifest.backup_dir 与 batch 不一致：%s != %s" % (backup_dir.name, batch)
        )
    if str(backup_dir.relative_to(root)).split(os.sep)[0] != BACKUP_DIR_NAME:
        raise PlanError("backup_dir 必须位于 %s 之下：%s" % (BACKUP_DIR_NAME, backup_dir))
    if not backup_dir.is_dir():
        raise PlanError("备份目录不存在：%s" % backup_dir)

    items: list[RollbackItem] = []
    untouched: list[str] = []
    seen: set[str] = set()
    entry_keys: set[str] = set()
    for index, entry in enumerate(manifest["files"]):
        label = "files[%d]" % index
        if not isinstance(entry, dict):
            raise PlanError("%s 必须是对象" % label)
        rel = _parse_rel(entry.get("path"), "%s.path" % label)
        _check_forbidden_change(rel)
        _check_allowed_change(rel)
        target = _resolve_under(root, rel, "%s.path" % label)
        key = os.path.normcase(rel.as_posix())
        if key in seen:
            raise PlanError("manifest 中存在重复 path：%s" % rel.as_posix())
        seen.add(key)
        entry_keys.add(key)

        after = entry.get("after_sha256")
        if not isinstance(after, str) or not _SHA_RE.match(after):
            raise PlanError("%s.after_sha256 必须是 64 位小写十六进制" % label)
        existed = entry.get("existed")
        if not isinstance(existed, bool):
            raise PlanError("%s.existed 必须是布尔值" % label)
        before = entry.get("before_sha256")
        backup: Path | None = None
        if existed:
            if not isinstance(before, str) or not _SHA_RE.match(before):
                raise PlanError("%s.before_sha256 必须是 64 位小写十六进制" % label)
        elif before is not None:
            raise PlanError("%s.existed=false 时 before_sha256 必须为 null" % label)

        if entry.get("state") not in KNOWN_ENTRY_STATES:
            raise PlanError(
                "%s.state 不受支持：%r（允许 %s）"
                % (label, entry.get("state"), ", ".join(KNOWN_ENTRY_STATES))
            )
        if entry.get("state") != "replaced":
            # 未在本批替换的条目不需要可用备份（例如上次在备份阶段就失败了）。
            untouched.append(
                "%s（状态 %s，未在本批替换）" % (rel.as_posix(), entry.get("state"))
            )
            continue
        if existed:
            backup_rel_entry = _parse_rel(entry.get("backup_file"), "%s.backup_file" % label)
            backup = _resolve_under(root, backup_rel_entry, "%s.backup_file" % label)
            if not _is_within(backup_dir.resolve(), backup.resolve()):
                raise PlanError(
                    "%s.backup_file 不在本批次备份目录内：%s" % (label, backup)
                )
            if not backup.is_file():
                raise PlanError("%s 的备份缺失：%s" % (label, backup))
        items.append(RollbackItem(rel=rel, target=target, entry=entry, backup=backup))

    guards_raw = manifest.get("guards", [])
    if not isinstance(guards_raw, list):
        raise PlanError("manifest.guards 必须是数组")
    guards: list[Guard] = []
    guard_keys: set[str] = set()
    for index, raw in enumerate(guards_raw):
        label = "guards[%d]" % index
        if not isinstance(raw, dict):
            raise PlanError("%s 必须是对象" % label)
        rel = _parse_rel(raw.get("path"), "%s.path" % label)
        _resolve_under(root, rel, "%s.path" % label)
        key = os.path.normcase(rel.as_posix())
        if key in entry_keys:
            raise PlanError("同一路径同时出现在 files 与 guards 中：%s" % rel.as_posix())
        if key in guard_keys:
            raise PlanError("manifest.guards 中存在重复 path：%s" % rel.as_posix())
        guard_keys.add(key)
        before = raw.get("before_sha256")
        if before is not None and (not isinstance(before, str) or not _SHA_RE.match(before)):
            raise PlanError("%s.before_sha256 必须是 null 或 64 位小写十六进制" % label)
        guards.append(Guard(rel=rel, before_sha256=before))
    return items, guards, untouched, backup_dir


def _verify_rollback_preconditions(
    root: Path, items: list[RollbackItem], guards: list[Guard]
) -> tuple[list[RollbackItem], list[str]]:
    """整批校验备份内容与 guards；返回 (可回滚条目, 因用户改动而跳过的说明)。

    备份内容不符或 guards 漂移属结构性问题，抛 PlanError（调用方保证零写入）；
    目标被用户改动只按文件跳过，保持不覆盖。
    """
    problems: list[str] = []
    for item in items:
        if item.backup is None:
            continue
        actual = sha256_file(item.backup)
        if actual != item.entry["before_sha256"]:
            problems.append(
                "备份内容与原始哈希不符：%s（备份=%s，期望=%s）"
                % (item.text, actual, item.entry["before_sha256"])
            )
    problems.extend(_guard_problems(guards, root))
    if problems:
        raise PlanError("回滚前置校验失败（未写入任何文件）：\n  - " + "\n  - ".join(problems))

    eligible: list[RollbackItem] = []
    skipped: list[str] = []
    for item in items:
        if not item.target.exists():
            skipped.append("%s：目标已被删除，拒绝重建" % item.text)
            continue
        if not item.target.is_file():
            skipped.append("%s：目标已不是普通文件" % item.text)
            continue
        if sha256_file(item.target) != item.entry["after_sha256"]:
            skipped.append(
                "%s：内容已不等于应用后哈希（可能被用户改过），拒绝覆盖" % item.text
            )
            continue
        eligible.append(item)
    return eligible, skipped


def run_rollback(
    manifest_path: str, root_arg: str, *, stop_check=check_server_stopped, out=sys.stdout
) -> int:
    """按批次 manifest 回滚；与 apply 同级的整批校验 + 停服证明，然后暂存再原子替换。"""
    try:
        path, root, manifest = _load_rollback_manifest(manifest_path, root_arg)
        items, guards, untouched, backup_dir = _resolve_rollback_items(root, manifest)
        eligible, skipped = _verify_rollback_preconditions(root, items, guards)
    except PlanError as exc:
        print("[拒绝] manifest 未通过校验，未写入任何文件：\n%s" % exc, file=out)
        return EXIT_INVALID_PLAN

    if not items:
        print("[no-op] 该 manifest 没有本批已替换的文件，无需回滚。", file=out)
        return EXIT_OK

    evidence = stop_check(root)
    print("[停服证明] %s" % evidence.state, file=out)
    for detail in evidence.details:
        print("  " + detail, file=out)
    if not evidence.stopped:
        print("[拒绝] 无法证明服务器已停止，拒绝回滚（未写入任何文件）。", file=out)
        return EXIT_REFUSED

    # 先整批暂存（只写临时文件），再逐个原子替换
    staged: list[tuple[RollbackItem, Path | None]] = []
    try:
        for item in eligible:
            if item.backup is None:
                staged.append((item, None))  # 本批新建的文件：回滚即移除
                continue
            item.target.parent.mkdir(parents=True, exist_ok=True)
            staging = item.target.parent / (
                ".%s.rollback-%d.tmp" % (item.target.name, os.getpid())
            )
            shutil.copy2(item.backup, staging)
            with open(staging, "rb+") as fh:
                os.fsync(fh.fileno())
            if sha256_file(staging) != item.entry["before_sha256"]:
                raise ApplyError("回滚暂存内容与原始哈希不符：%s" % item.text)
            staged.append((item, staging))
    except (OSError, ApplyError) as exc:
        for _, staging in staged:
            if staging is not None:
                try:
                    staging.unlink()
                except OSError:
                    pass
        print("[失败] 回滚暂存失败，未改动任何目标文件：%s" % exc, file=out)
        return EXIT_APPLY_FAILED

    restored: list[str] = []
    io_failures: list[str] = []
    drifted: list[str] = []
    for item, staging in staged:
        # 暂存与替换之间仍可能有人改了目标：每个文件替换/移走前再核一次 after 哈希。
        try:
            current_ok = (
                item.target.is_file()
                and sha256_file(item.target) == item.entry["after_sha256"]
            )
        except OSError as exc:
            if staging is not None:
                try:
                    staging.unlink()
                except OSError:
                    pass
            io_failures.append("%s：读取当前内容失败（%s）" % (item.text, exc))
            continue
        if not current_ok:
            if staging is not None:
                try:
                    staging.unlink()
                except OSError:
                    pass
            drifted.append("%s：替换前发现内容已不等于应用后哈希，保留该文件" % item.text)
            continue
        try:
            if staging is not None:
                _replace_file(staging, item.target)
                if sha256_file(item.target) != item.entry["before_sha256"]:
                    io_failures.append("%s：回滚后哈希与原始不符" % item.text)
                    continue
            else:
                # 移除本批新建的文件，但移到备份目录而不是删除用户数据
                destination = backup_dir / "removed" / item.rel.as_posix()
                destination.parent.mkdir(parents=True, exist_ok=True)
                os.replace(str(item.target), str(destination))
            item.entry["state"] = "restored"
            restored.append(item.text)
            print("[已回滚] %s" % item.text, file=out)
        except OSError as exc:
            io_failures.append("%s：回滚失败（%s）" % (item.text, exc))

    manifest["state"] = (
        "rolled_back" if not (skipped or io_failures or drifted) else "rollback_partial"
    )
    manifest["rolled_back_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    try:
        _atomic_write_json(path, manifest)
    except OSError as exc:
        print("[警告] manifest 写回失败：%s" % exc, file=out)

    for note in untouched:
        print("[跳过] %s" % note, file=out)
    if skipped or drifted:
        print("[保留] 下列文件内容已不等于应用后哈希，未覆盖（用户改动受保护）：", file=out)
        for note in skipped + drifted:
            print("  - %s" % note, file=out)
    if io_failures:
        print("[回滚失败] 下列文件回滚未完成（未删除任何用户数据）：", file=out)
        for note in io_failures:
            print("  - %s" % note, file=out)
    if skipped or drifted or io_failures:
        return EXIT_ROLLBACK_PARTIAL
    print("[回滚完成] 共 %d 个文件" % len(restored), file=out)
    return EXIT_OK


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="校验/应用/回滚 CraftEngine 状态重平衡计划（默认只校验）。"
    )
    parser.add_argument("--plan", help="计划 JSON 路径")
    parser.add_argument("--root", help="CE 根目录绝对路径（--apply/--rollback 必填）")
    parser.add_argument("--apply", action="store_true", help="实际写入（需停服证明）")
    parser.add_argument("--rollback", metavar="MANIFEST", help="按批次 manifest 回滚")
    args = parser.parse_args(argv)

    if args.rollback:
        if args.apply:
            parser.error("--rollback 不能与 --apply 同时使用")
        if not args.root:
            parser.error("--rollback 必须显式提供 --root")
        return run_rollback(args.rollback, args.root)

    if not args.plan:
        parser.error("需要 --plan（或 --rollback）")
    if args.apply and not args.root:
        parser.error("--apply 必须显式提供 --root，不能用计划里的 root 兜底")
    return run_apply(args.plan, args.root, apply=args.apply)


if __name__ == "__main__":
    sys.exit(main())
