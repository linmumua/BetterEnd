"""ce_state_apply 的单元测试。

运行：
    python -m unittest discover -s scripts/tests -v
或：
    python -m unittest scripts.tests.test_ce_state_apply -v

全部用例只在 tempdir 里造一个假的 <server>/plugins/CraftEngine 结构，绝不触碰
真实 CE 目录，也不启动/停止任何服务器。
"""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path, PurePosixPath
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parents[2] / "tools"
sys.path.insert(0, str(TOOLS_DIR))

import ce_state_apply as ce  # noqa: E402

MUTABLE = "resources/betterend/configuration/blocks/a.yml"
MUTABLE2 = "resources/betterend/configuration/items/b.yml"
VISUAL = "cache/visual_block_states.json"
CUSTOM = "cache/custom_block_states.json"
PACK_YML = "resources/betterend/pack.yml"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@contextlib.contextmanager
def hold_exclusive_lock(path: Path):
    """在另一个文件句柄上独占锁住文件，模拟 Minecraft 服务器持有 session.lock。"""
    fd = os.open(str(path), os.O_RDWR | getattr(os, "O_BINARY", 0))
    try:
        if os.name == "nt":
            import msvcrt

            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        try:
            if os.name == "nt":
                import msvcrt

                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.lockf(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


class ApplyHarness(unittest.TestCase):
    """构造一个假的 <server>/plugins/CraftEngine 目录树与 plan 目录。"""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        base = Path(self._tmp.name)
        self.server = base / "server"
        self.root = self.server / "plugins" / "CraftEngine"
        for sub in (
            "resources/betterend/configuration/blocks",
            "resources/betterend/configuration/items",
            "cache",
        ):
            (self.root / sub).mkdir(parents=True)
        (self.server / "world").mkdir(parents=True)
        self.plan_dir = base / "plan"
        (self.plan_dir / "patches").mkdir(parents=True)

        self.write("config.yml", "config: v1\n")
        self.write(CUSTOM, '{"states": []}\n')
        self.write(PACK_YML, "enabled: true\n")
        self.write(MUTABLE, "a: 1\n")
        self.write(MUTABLE2, "b: 1\n")
        self.write(VISUAL, '{"visual": []}\n')
        (self.server / "server.properties").write_text("level-name=world\n", encoding="utf-8")
        (self.server / "world" / "session.lock").write_bytes(b"\x00\x00\x00")

    # -- helpers ---------------------------------------------------------
    def write(self, rel: str, text: str) -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def read(self, rel: str) -> str:
        return (self.root / rel).read_text(encoding="utf-8")

    def sha_of(self, rel: str) -> str:
        return ce.sha256_file(self.root / rel)

    def stopped(self, root):
        """测试注入的停服检查：进程内注入，CLI 没有这个开关。"""
        return ce.StopEvidence("stopped", ["测试注入：视为已停服"])

    def snapshot(self) -> dict[str, str]:
        """服务器树内除备份目录外的全部文件哈希（用于断言“零写入”）。"""
        result: dict[str, str] = {}
        for path in sorted(self.server.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(self.server).as_posix()
            if ce.BACKUP_DIR_NAME in rel.split("/"):
                continue
            result[rel] = ce.sha256_file(path)
        return result

    def backup_dir(self) -> Path:
        return self.root / ce.BACKUP_DIR_NAME

    def manifests(self) -> list[Path]:
        directory = self.backup_dir()
        if not directory.is_dir():
            return []
        return sorted(directory.glob("*/manifest.json"))

    def dump_plan(self, payload: dict, name: str = "plan.json") -> Path:
        path = self.plan_dir / name
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return path

    def make_plan(self, changes=None, guards=None, root=None) -> Path:
        root = Path(root) if root is not None else self.root
        if changes is None:
            changes = [(MUTABLE, "a: 2\n")]
        if guards is None:
            guards = [CUSTOM, "config.yml", PACK_YML]

        plan_changes = []
        for index, (rel, new_text) in enumerate(changes):
            data = new_text.encode("utf-8")
            patch_name = "patches/%04d-%s" % (index, PurePosixPath(rel).name)
            (self.plan_dir / patch_name).write_bytes(data)
            target = root / rel
            plan_changes.append(
                {
                    "path": rel,
                    "before_sha256": ce.sha256_file(target) if target.is_file() else None,
                    "after_sha256": sha(data),
                    "patch_file": patch_name,
                }
            )
        plan_guards = []
        for rel in guards:
            target = root / rel
            plan_guards.append(
                {
                    "path": rel,
                    "before_sha256": ce.sha256_file(target) if target.is_file() else None,
                }
            )
        return self.dump_plan(
            {"schema": 1, "root": str(root), "changes": plan_changes, "guards": plan_guards}
        )


class TestPlanValidation(ApplyHarness):
    def test_validate_only_is_default_and_writes_nothing(self):
        plan = self.make_plan()
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_apply(str(plan), str(self.root), out=out)

        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertIn("validate-only", out.getvalue())
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(self.backup_dir().exists())

    def test_plan_root_must_match_explicit_root(self):
        plan = self.make_plan()
        other = self.server / "plugins"
        with self.assertRaises(ce.PlanError):
            ce.load_plan(str(plan), str(other))

    def test_hash_drift_rejects_whole_batch_without_writing(self):
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE2, "b: 2\n")])
        self.write(MUTABLE2, "tampered: 1\n")
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_apply(str(plan), str(self.root), apply=True,
                            stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
        self.assertIn("漂移", out.getvalue())
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(self.backup_dir().exists())

    def test_guard_drift_rejects_batch(self):
        plan = self.make_plan()
        self.write(CUSTOM, '{"states": ["changed"]}\n')
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_apply(str(plan), str(self.root), apply=True,
                            stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
        self.assertIn(CUSTOM, out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_required_guards_must_cover_protected_files(self):
        # 少了 custom_block_states.json 的 guard：拒绝，防止“顺手把它也改了”没人发现。
        plan = self.make_plan(guards=["config.yml", PACK_YML])
        with self.assertRaises(ce.PlanError) as ctx:
            ce.load_plan(str(plan), str(self.root))
        self.assertIn(CUSTOM, str(ctx.exception))

    def test_patch_content_must_match_after_hash(self):
        data = "a: 2\n".encode("utf-8")
        (self.plan_dir / "patches/tampered.yml").write_bytes(data)
        plan = self.dump_plan(
            {
                "schema": 1,
                "root": str(self.root),
                "changes": [
                    {
                        "path": MUTABLE,
                        "before_sha256": self.sha_of(MUTABLE),
                        "after_sha256": sha(b"a: 3\n"),  # 与 patch 内容不符
                        "patch_file": "patches/tampered.yml",
                    }
                ],
                "guards": [
                    {"path": CUSTOM, "before_sha256": self.sha_of(CUSTOM)},
                    {"path": "config.yml", "before_sha256": self.sha_of("config.yml")},
                    {"path": PACK_YML, "before_sha256": self.sha_of(PACK_YML)},
                ],
            }
        )
        with self.assertRaises(ce.PlanError) as ctx:
            ce.load_plan(str(plan), str(self.root))
        self.assertIn("patch", str(ctx.exception))

    def test_duplicate_paths_rejected(self):
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE, "a: 3\n")])
        with self.assertRaises(ce.PlanError) as ctx:
            ce.load_plan(str(plan), str(self.root))
        self.assertIn("重复", str(ctx.exception))

    def test_forbidden_targets_rejected(self):
        cases = [
            CUSTOM,
            "config.yml",
            PACK_YML,
            "resources/betterend/resourcepack/assets/x.json",
            "plugins-other/foo.jar",
        ]
        for rel in cases:
            with self.subTest(rel=rel):
                plan = self.make_plan(changes=[(rel, "x: 1\n")])
                with self.assertRaises(ce.PlanError):
                    ce.load_plan(str(plan), str(self.root))

    def test_path_escapes_rejected(self):
        cases = [
            "../evil.yml",
            str(self.root / MUTABLE),           # 绝对路径
            "C:/Windows/evil.yml",              # 盘符
            "resources/../../evil.yml",
        ]
        for rel in cases:
            with self.subTest(rel=rel):
                plan = self.dump_plan(
                    {
                        "schema": 1,
                        "root": str(self.root),
                        "changes": [
                            {
                                "path": rel,
                                "before_sha256": None,
                                "after_sha256": sha(b"x: 1\n"),
                                "patch_file": "patches/0000-a.yml",
                            }
                        ],
                        "guards": [],
                    }
                )
                (self.plan_dir / "patches/0000-a.yml").write_bytes(b"x: 1\n")
                with self.assertRaises(ce.PlanError):
                    ce.load_plan(str(plan), str(self.root))

    def test_symlink_escape_rejected(self):
        outside = Path(self._tmp.name) / "outside"
        outside.mkdir()
        link = self.root / "resources" / "betterend" / "configuration" / "linked"
        try:
            os.symlink(outside, link, target_is_directory=True)
        except (OSError, NotImplementedError, AttributeError):
            self.skipTest("当前环境无法创建符号链接")

        plan = self.make_plan(changes=[("resources/betterend/configuration/linked/x.yml", "x: 1\n")])
        with self.assertRaises(ce.PlanError) as ctx:
            ce.load_plan(str(plan), str(self.root))
        self.assertIn("符号链接", str(ctx.exception))

    def test_resolve_under_rejects_escape_without_symlink_support(self):
        # 与上面等价，但不依赖“能否创建符号链接”：直接把 resolve 指向根外。
        outside = Path(self._tmp.name) / "outside.yml"
        outside.write_bytes(b"x: 1\n")
        real_resolve = Path.resolve

        def fake_resolve(self_path, strict=False):
            if self_path.name == "escaped.yml":
                return outside
            return real_resolve(self_path, strict=strict)

        with mock.patch.object(Path, "resolve", fake_resolve):
            with self.assertRaises(ce.PlanError) as ctx:
                ce._resolve_under(
                    self.root,
                    PurePosixPath("resources/betterend/configuration/escaped.yml"),
                    "changes[0].path",
                )
        self.assertIn("逃出根目录", str(ctx.exception))

    def test_patch_file_must_stay_inside_plan_dir(self):
        outside = Path(self._tmp.name) / "outside.yml"
        outside.write_bytes(b"a: 2\n")
        plan = self.dump_plan(
            {
                "schema": 1,
                "root": str(self.root),
                "changes": [
                    {
                        "path": MUTABLE,
                        "before_sha256": self.sha_of(MUTABLE),
                        "after_sha256": sha(b"a: 2\n"),
                        "patch_file": "../outside.yml",
                    }
                ],
                "guards": [],
            }
        )
        with self.assertRaises(ce.PlanError):
            ce.load_plan(str(plan), str(self.root))


    def test_empty_changes_is_legal_noop_but_still_checks_guards(self):
        plan = self.make_plan(changes=[])
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_apply(str(plan), str(self.root), apply=True,
                            stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertIn("no-op", out.getvalue())
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(self.backup_dir().exists())

        # 无变更也必须校验 guards：guard 漂移则整批拒绝。
        self.write(CUSTOM, '{"states": ["drifted"]}\n')
        before = self.snapshot()
        out = io.StringIO()
        code = ce.run_apply(str(plan), str(self.root), apply=True,
                            stop_check=self.stopped, out=out)
        self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_protected_packs_rejected_as_changes_but_allowed_as_guards(self):
        for pack in ("internal", "default_templates"):
            with self.subTest(pack=pack):
                plan = self.make_plan(
                    changes=[("resources/%s/configuration/blocks/x.yml" % pack, "x: 1\n")]
                )
                with self.assertRaises(ce.PlanError) as ctx:
                    ce.load_plan(str(plan), str(self.root))
                self.assertIn(pack, str(ctx.exception))

        # 同一路径作为 guard 是允许的（只读校验，不写入）。
        self.write("resources/internal/configuration/blocks/x.yml", "x: 0\n")
        plan = self.make_plan(
            guards=[CUSTOM, "config.yml", PACK_YML,
                    "resources/internal/configuration/blocks/x.yml"]
        )
        out = io.StringIO()
        self.assertEqual(ce.run_apply(str(plan), str(self.root), out=out), ce.EXIT_OK,
                         out.getvalue())

    def test_ads_colon_in_path_rejected(self):
        for rel in (
            "resources/betterend/configuration/blocks/a.yml:evil",
            "resources/betterend/configuration/blocks:a.yml",
        ):
            with self.subTest(rel=rel):
                plan = self.make_plan(changes=[(rel, "x: 1\n")])
                with self.assertRaises(ce.PlanError) as ctx:
                    ce.load_plan(str(plan), str(self.root))
                self.assertIn("冒号", str(ctx.exception))

    def test_non_string_plan_fields_raise_planerror(self):
        cases = [
            {"path": 123},
            {"after_sha256": 123},
            {"before_sha256": 123},
            {"patch_file": None},
        ]
        for patch in cases:
            with self.subTest(patch=patch):
                entry = {
                    "path": MUTABLE,
                    "before_sha256": self.sha_of(MUTABLE),
                    "after_sha256": sha(b"a: 2\n"),
                    "patch_file": "patches/0000-a.yml",
                }
                entry.update(patch)
                (self.plan_dir / "patches/0000-a.yml").write_bytes(b"a: 2\n")
                plan = self.dump_plan(
                    {"schema": 1, "root": str(self.root), "changes": [entry], "guards": []}
                )
                with self.assertRaises(ce.PlanError):
                    ce.load_plan(str(plan), str(self.root))


class TestStopEvidence(ApplyHarness):
    def test_apply_refused_when_evidence_insufficient(self):
        plan = self.make_plan()
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_apply(
            str(plan), str(self.root), apply=True,
            stop_check=lambda root: ce.StopEvidence("unknown", ["无法判定"]),
            out=out,
        )

        self.assertEqual(code, ce.EXIT_REFUSED, out.getvalue())
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(self.backup_dir().exists())

    def test_apply_refused_when_server_still_running(self):
        plan = self.make_plan()
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_apply(
            str(plan), str(self.root), apply=True,
            stop_check=lambda root: ce.StopEvidence("running", ["session.lock 被占用"]),
            out=out,
        )

        self.assertEqual(code, ce.EXIT_REFUSED, out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_cli_has_no_force_or_assume_stopped_bypass(self):
        for flag in ("--force", "--assume-stopped", "--skip-stop-check", "--no-stop-check"):
            with self.subTest(flag=flag):
                with self.assertRaises(SystemExit):
                    with contextlib.redirect_stderr(io.StringIO()):
                        ce.main(["--plan", "x", "--root", "y", "--apply", flag])

    def test_apply_requires_explicit_root(self):
        plan = self.make_plan()
        with self.assertRaises(SystemExit):
            with contextlib.redirect_stderr(io.StringIO()):
                ce.main(["--plan", str(plan), "--apply"])

    def test_real_check_unknown_without_session_lock(self):
        (self.server / "world" / "session.lock").unlink()
        evidence = ce.check_server_stopped(self.root)
        self.assertEqual(evidence.state, "unknown")
        self.assertIn("session.lock", evidence.text)

    def test_real_check_unknown_without_server_properties(self):
        (self.server / "server.properties").unlink()
        evidence = ce.check_server_stopped(self.root)
        self.assertEqual(evidence.state, "unknown")

    def test_real_check_unknown_for_standalone_root(self):
        standalone = Path(self._tmp.name) / "standalone" / "CraftEngine"
        standalone.mkdir(parents=True)
        evidence = ce.check_server_stopped(standalone)
        self.assertEqual(evidence.state, "unknown")

    @unittest.skipIf(os.name != "nt", "POSIX fcntl 记录锁在同进程内不互斥，需另一进程验证")
    def test_real_check_detects_held_session_lock(self):
        lock = self.server / "world" / "session.lock"
        with hold_exclusive_lock(lock):
            evidence = ce.check_server_stopped(self.root)
        self.assertEqual(evidence.state, "running", evidence.text)

    def test_real_check_reports_stopped_when_lock_is_free(self):
        # 只有锁可独占 + 进程扫描给出明确 no_match 才算 stopped；
        # 某些环境进程不可枚举，此时必须是 unknown 而不是 stopped。
        evidence = ce.check_server_stopped(self.root)
        self.assertIn(evidence.state, ("stopped", "unknown"))


class TestApply(ApplyHarness):
    def test_apply_writes_all_files_and_keeps_guards(self):
        guard_hashes = {rel: self.sha_of(rel) for rel in (CUSTOM, "config.yml", PACK_YML)}
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE2, "b: 2\n")])
        out = io.StringIO()

        code = ce.run_apply(str(plan), str(self.root), apply=True,
                            stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertEqual(self.read(MUTABLE), "a: 2\n")
        self.assertEqual(self.read(MUTABLE2), "b: 2\n")
        # guard 覆盖的文件一个字节都没动（含 custom_block_states.json / config.yml / pack.yml）
        for rel, expected in guard_hashes.items():
            self.assertEqual(self.sha_of(rel), expected, rel)
        # 备份与 manifest 落在 resources 扫描树之外
        manifests = self.manifests()
        self.assertEqual(len(manifests), 1)
        self.assertFalse(
            str(manifests[0].relative_to(self.root)).startswith("resources" + os.sep)
        )
        payload = json.loads(manifests[0].read_text(encoding="utf-8"))
        self.assertEqual(payload["state"], "applied")
        self.assertEqual([f["state"] for f in payload["files"]], ["replaced", "replaced"])
        for entry in payload["files"]:
            self.assertTrue((self.root / payload["backup_dir"] / entry["path"]).is_file())

    def test_apply_is_idempotent_noop_on_second_run(self):
        plan = self.make_plan()
        out = io.StringIO()
        self.assertEqual(
            ce.run_apply(str(plan), str(self.root), apply=True,
                         stop_check=self.stopped, out=out),
            ce.EXIT_OK,
            out.getvalue(),
        )
        after_first = self.snapshot()

        def boom(_root):
            raise AssertionError("幂等路径不应再调用停服检查")

        out = io.StringIO()
        code = ce.run_apply(str(plan), str(self.root), apply=True, stop_check=boom, out=out)

        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertIn("no-op", out.getvalue())
        self.assertEqual(self.snapshot(), after_first)
        self.assertEqual(len(self.manifests()), 1)  # 没有再建第二个批次

    def test_partial_previous_state_is_rejected(self):
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE2, "b: 2\n")])
        # 只把第一个文件写成应用后内容：疑似上次中断，必须拒绝而不是补写
        self.write(MUTABLE, "a: 2\n")
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_apply(str(plan), str(self.root), apply=True,
                            stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_second_replace_failure_rolls_back_first_file(self):
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE2, "b: 2\n")])
        before = self.snapshot()
        real = ce._replace_file
        calls = {"n": 0}

        def flaky(src, dst):
            calls["n"] += 1
            if calls["n"] == 2:
                raise OSError("模拟磁盘写入失败")
            return real(src, dst)

        out = io.StringIO()
        with mock.patch.object(ce, "_replace_file", flaky):
            code = ce.run_apply(str(plan), str(self.root), apply=True,
                                stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_APPLY_FAILED, out.getvalue())
        self.assertEqual(self.snapshot(), before)  # 第一个文件已原子恢复
        payload = json.loads(self.manifests()[0].read_text(encoding="utf-8"))
        self.assertEqual(payload["state"], "rolled_back")
        self.assertEqual(payload["files"][0]["state"], "restored")

    def test_keyboard_interrupt_is_rolled_back(self):
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE2, "b: 2\n")])
        before = self.snapshot()
        real = ce._replace_file
        calls = {"n": 0}

        def interrupted(src, dst):
            calls["n"] += 1
            if calls["n"] == 2:
                raise KeyboardInterrupt()
            return real(src, dst)

        out = io.StringIO()
        with mock.patch.object(ce, "_replace_file", interrupted):
            code = ce.run_apply(str(plan), str(self.root), apply=True,
                                stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_APPLY_FAILED, out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_same_basename_new_files_failure_isolated_separately(self):
        # 两个同 basename 的新建文件，第二个替换失败 → 第一个的自愈隔离必须
        # 落在完整相对路径下，不能只按 basename 互相覆盖证据。
        first = "resources/betterend/configuration/blocks/x.yml"
        second = "resources/betterend/configuration/items/x.yml"
        plan = self.make_plan(changes=[(first, "a: 1\n"), (second, "b: 1\n")])
        real = ce._replace_file
        calls = {"n": 0}

        def flaky(src, dst):
            calls["n"] += 1
            if calls["n"] == 2:
                raise OSError("模拟磁盘写入失败")
            return real(src, dst)

        out = io.StringIO()
        with mock.patch.object(ce, "_replace_file", flaky):
            code = ce.run_apply(str(plan), str(self.root), apply=True,
                                stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_APPLY_FAILED, out.getvalue())
        self.assertFalse((self.root / first).exists())
        self.assertFalse((self.root / second).exists())

        payload = json.loads(self.manifests()[0].read_text(encoding="utf-8"))
        removed = self.root / payload["backup_dir"] / "removed"
        self.assertEqual((removed / first).read_text(encoding="utf-8"), "a: 1\n")
        self.assertFalse((removed / second).exists())  # 第二个没写入过，不该有隔离副本

    def test_self_heal_does_not_overwrite_edit_made_during_failure(self):
        # 第二个文件替换失败触发自愈时，第一个（已被我们替换）若已被外部编辑，
        # 自愈不得用备份覆盖它，必须保留现状并把它列为需人工处理。
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE2, "b: 2\n")])
        real = ce._replace_file
        calls = {"n": 0}

        def fail_second_after_user_edit(src, dst):
            calls["n"] += 1
            if calls["n"] == 2:
                self.write(MUTABLE, "user: edited mid-flight\n")
                raise OSError("模拟磁盘写入失败")
            return real(src, dst)

        out = io.StringIO()
        with mock.patch.object(ce, "_replace_file", fail_second_after_user_edit):
            code = ce.run_apply(str(plan), str(self.root), apply=True,
                                stop_check=self.stopped, out=out)

        self.assertEqual(code, ce.EXIT_APPLY_FAILED, out.getvalue())
        # 用户编辑被保留，没有被备份覆盖回 "a: 1"
        self.assertEqual(self.read(MUTABLE), "user: edited mid-flight\n")
        # 第二个文件从未写入成功，保持原样
        self.assertEqual(self.read(MUTABLE2), "b: 1\n")
        payload = json.loads(self.manifests()[0].read_text(encoding="utf-8"))
        self.assertEqual(payload["state"], "rollback_failed")
        self.assertIn("已被改动", out.getvalue())

    def test_target_edited_after_backup_is_not_overwritten(self):
        # 备份之后、替换之前目标被改动：必须拒绝覆盖（最终 before 漂移检测）。
        plan = self.make_plan()
        calls = {"n": 0}

        def edit_then_confirm(root):
            calls["n"] += 1
            if calls["n"] >= 2:  # 第二次调用发生在备份+暂存之后、替换之前
                self.write(MUTABLE, "user: late edit\n")
            return ce.StopEvidence("stopped", ["测试注入：视为已停服"])

        out = io.StringIO()
        code = ce.run_apply(str(plan), str(self.root), apply=True,
                            stop_check=edit_then_confirm, out=out)

        self.assertGreaterEqual(calls["n"], 2)
        self.assertEqual(code, ce.EXIT_APPLY_FAILED, out.getvalue())
        self.assertIn("漂移", out.getvalue())
        self.assertEqual(self.read(MUTABLE), "user: late edit\n")  # 用户改动被保留

    def test_new_file_is_created_and_rolled_back(self):
        new_rel = "resources/betterend/configuration/blocks/new.yml"
        plan = self.make_plan(
            changes=[(new_rel, "new: 1\n")],
            guards=[CUSTOM, "config.yml", PACK_YML],
        )
        out = io.StringIO()
        self.assertEqual(
            ce.run_apply(str(plan), str(self.root), apply=True,
                         stop_check=self.stopped, out=out),
            ce.EXIT_OK,
            out.getvalue(),
        )
        self.assertEqual(self.read(new_rel), "new: 1\n")
        payload = json.loads(self.manifests()[0].read_text(encoding="utf-8"))
        self.assertFalse(payload["files"][0]["existed"])

        out = io.StringIO()
        code = ce.run_rollback(str(self.manifests()[0]), str(self.root), out=out)
        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertFalse((self.root / new_rel).exists())


class TestRollback(ApplyHarness):
    def _apply_two(self) -> Path:
        plan = self.make_plan(changes=[(MUTABLE, "a: 2\n"), (MUTABLE2, "b: 2\n")])
        out = io.StringIO()
        self.assertEqual(
            ce.run_apply(str(plan), str(self.root), apply=True,
                         stop_check=self.stopped, out=out),
            ce.EXIT_OK,
            out.getvalue(),
        )
        return self.manifests()[0]

    def rollback(self, manifest: Path, out=None):
        """回滚时注入停服检查（CLI 没有这个开关，仅进程内测试可用）。"""
        return ce.run_rollback(
            str(manifest), str(self.root), stop_check=self.stopped, out=out or io.StringIO()
        )

    def test_rollback_restores_original_content(self):
        manifest = self._apply_two()
        out = io.StringIO()

        code = self.rollback(manifest, out)

        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertEqual(self.read(MUTABLE), "a: 1\n")
        self.assertEqual(self.read(MUTABLE2), "b: 1\n")
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(payload["state"], "rolled_back")

    def test_rollback_refuses_to_overwrite_user_edits(self):
        manifest = self._apply_two()
        self.write(MUTABLE, "user: edited\n")  # 应用之后用户改过
        out = io.StringIO()

        code = self.rollback(manifest, out)

        self.assertEqual(code, ce.EXIT_ROLLBACK_PARTIAL, out.getvalue())
        self.assertEqual(self.read(MUTABLE), "user: edited\n")  # 保留用户改动
        self.assertEqual(self.read(MUTABLE2), "b: 1\n")         # 未改动的已回滚
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(payload["state"], "rollback_partial")

    def test_rollback_requires_matching_root(self):
        manifest = self._apply_two()
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_rollback(str(manifest), str(self.server / "plugins"), out=out)

        self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_rollback_refused_without_stop_proof(self):
        manifest = self._apply_two()
        before = self.snapshot()
        out = io.StringIO()

        code = ce.run_rollback(
            str(manifest), str(self.root),
            stop_check=lambda root: ce.StopEvidence("unknown", ["无法判定"]), out=out,
        )

        self.assertEqual(code, ce.EXIT_REFUSED, out.getvalue())
        self.assertEqual(self.snapshot(), before)  # 一个字节都没回滚

    def test_cli_rollback_refused_without_stop_proof(self):
        # 真实 CLI 路径：删掉 session.lock 后真实检查只能给出 unknown → 拒绝。
        manifest = self._apply_two()
        (self.server / "world" / "session.lock").unlink()
        before = self.snapshot()
        out = io.StringIO()

        with contextlib.redirect_stdout(out):
            code = ce.main(["--rollback", str(manifest), "--root", str(self.root)])

        self.assertEqual(code, ce.EXIT_REFUSED, out.getvalue())
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(self.read(MUTABLE), "a: 2\n")  # 仍是应用后内容

    def test_rollback_rejects_escaping_backup_dir(self):
        manifest = self._apply_two()
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        for evil in ("../outside", "C:/Windows/Temp", "/tmp/x", "resources/betterend"):
            with self.subTest(backup_dir=evil):
                payload["backup_dir"] = evil
                forged = self.plan_dir / "forged.json"
                forged.write_text(json.dumps(payload), encoding="utf-8")
                before = self.snapshot()
                out = io.StringIO()

                code = self.rollback(forged, out)

                self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
                self.assertEqual(self.snapshot(), before)

    def test_rollback_rejects_escaping_entry_path(self):
        manifest = self._apply_two()
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        for evil in ("../config.yml", "C:/Windows/evil.yml", "../../etc/passwd"):
            with self.subTest(path=evil):
                payload["files"][0]["path"] = evil
                forged = self.plan_dir / "forged-path.json"
                forged.write_text(json.dumps(payload), encoding="utf-8")
                before = self.snapshot()

                code = self.rollback(forged, io.StringIO())

                self.assertEqual(code, ce.EXIT_INVALID_PLAN)
                self.assertEqual(self.snapshot(), before)

    def test_rollback_rejects_escaping_backup_file(self):
        manifest = self._apply_two()
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        for evil in ("../config.yml", "config.yml", "C:/Windows/win.ini"):
            with self.subTest(backup_file=evil):
                payload["files"][0]["backup_file"] = evil
                forged = self.plan_dir / "forged-backup.json"
                forged.write_text(json.dumps(payload), encoding="utf-8")
                before = self.snapshot()

                code = self.rollback(forged, io.StringIO())

                self.assertEqual(code, ce.EXIT_INVALID_PLAN)
                self.assertEqual(self.snapshot(), before)

    def test_rollback_rejects_non_allowed_targets(self):
        # 伪造 manifest 想把回滚写到 custom_block_states.json / internal 注册表。
        manifest = self._apply_two()
        base = json.loads(manifest.read_text(encoding="utf-8"))
        for evil in (
            CUSTOM,
            "config.yml",
            PACK_YML,
            "resources/internal/configuration/blocks/x.yml",
        ):
            with self.subTest(path=evil):
                payload = json.loads(json.dumps(base))
                evil_target = self.root / evil
                evil_target.parent.mkdir(parents=True, exist_ok=True)
                if not evil_target.exists():
                    evil_target.write_text("victim: 1\n", encoding="utf-8")
                before = self.snapshot()
                payload["files"] = [
                    {
                        "path": evil,
                        "before_sha256": sha(b"attacker: 1\n"),
                        "after_sha256": self.sha_of(evil),
                        "existed": True,
                        "backup_file": "%s/%s" % (payload["backup_dir"], evil),
                        "state": "replaced",
                    }
                ]
                forged = self.plan_dir / "forged-allowed.json"
                forged.write_text(json.dumps(payload), encoding="utf-8")

                code = self.rollback(forged, io.StringIO())

                self.assertEqual(code, ce.EXIT_INVALID_PLAN)
                self.assertEqual(self.snapshot(), before)

    def test_rollback_rejects_corrupted_backup_without_writing(self):
        manifest = self._apply_two()
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        backup = self.root / payload["files"][0]["backup_file"]
        backup.write_text("corrupted\n", encoding="utf-8")
        before = self.snapshot()
        out = io.StringIO()

        code = self.rollback(manifest, out)

        self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
        self.assertIn("备份内容", out.getvalue())
        self.assertEqual(self.snapshot(), before)          # 连第一个文件都没被覆盖
        self.assertEqual(self.read(MUTABLE), "a: 2\n")
        self.assertEqual(self.read(MUTABLE2), "b: 2\n")

    def test_rollback_rejects_guard_drift(self):
        manifest = self._apply_two()
        self.write(CUSTOM, '{"states": ["drifted"]}\n')
        before = self.snapshot()
        out = io.StringIO()

        code = self.rollback(manifest, out)

        self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_rollback_rejects_non_string_manifest_fields(self):
        manifest = self._apply_two()
        base = json.loads(manifest.read_text(encoding="utf-8"))
        mutations = [
            lambda p: p["files"][0].__setitem__("path", 123),
            lambda p: p["files"][0].__setitem__("after_sha256", 123),
            lambda p: p["files"][0].__setitem__("before_sha256", 123),
            lambda p: p["files"][0].__setitem__("backup_file", 123),
            lambda p: p.__setitem__("backup_dir", 123),
            lambda p: p["files"][0].__setitem__("existed", "yes"),
            lambda p: p["files"][0].__setitem__("state", None),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                payload = json.loads(json.dumps(base))
                mutate(payload)
                forged = self.plan_dir / ("bad-%d.json" % index)
                forged.write_text(json.dumps(payload), encoding="utf-8")
                before = self.snapshot()
                out = io.StringIO()

                code = self.rollback(forged, out)

                self.assertEqual(code, ce.EXIT_INVALID_PLAN, out.getvalue())
                self.assertNotIn("Traceback", out.getvalue())
                self.assertEqual(self.snapshot(), before)

    def test_rollback_of_pending_only_manifest_is_noop(self):
        # 上次在备份阶段就失败：manifest 全是 pending、没有可用备份 → no-op 而非报错。
        self._apply_two()
        manifest = self.manifests()[0]
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        payload["state"] = "rolled_back"
        for entry in payload["files"]:
            entry["state"] = "pending"
            entry["backup_file"] = None
        manifest.write_text(json.dumps(payload), encoding="utf-8")
        before = self.snapshot()
        out = io.StringIO()

        code = self.rollback(manifest, out)

        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertIn("no-op", out.getvalue())
        self.assertEqual(self.snapshot(), before)

    def test_rollback_detects_edit_made_inside_stop_check(self):
        # 停服检查之后、替换之前被编辑：紧前再校验必须拦住，保留该文件并记 partial。
        manifest = self._apply_two()
        calls = {"n": 0}

        def edit_in_stop_check(root):
            calls["n"] += 1
            self.write(MUTABLE, "user: edited during stop check\n")
            return ce.StopEvidence("stopped", ["测试注入：视为已停服"])

        out = io.StringIO()
        code = ce.run_rollback(
            str(manifest), str(self.root), stop_check=edit_in_stop_check, out=out
        )

        self.assertEqual(calls["n"], 1)
        self.assertEqual(code, ce.EXIT_ROLLBACK_PARTIAL, out.getvalue())
        self.assertEqual(self.read(MUTABLE), "user: edited during stop check\n")
        self.assertEqual(self.read(MUTABLE2), "b: 1\n")  # 其余文件仍正常回滚
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(payload["state"], "rollback_partial")
        states = {f["path"]: f["state"] for f in payload["files"]}
        self.assertEqual(states[MUTABLE], "replaced")     # 被编辑的保留未回滚
        self.assertEqual(states[MUTABLE2], "restored")

    def test_rollback_isolates_same_basename_new_files(self):
        first = "resources/betterend/configuration/blocks/x.yml"
        second = "resources/betterend/configuration/items/x.yml"
        plan = self.make_plan(changes=[(first, "a: 1\n"), (second, "b: 1\n")])
        out = io.StringIO()
        self.assertEqual(
            ce.run_apply(str(plan), str(self.root), apply=True,
                         stop_check=self.stopped, out=out),
            ce.EXIT_OK,
            out.getvalue(),
        )
        manifest = self.manifests()[0]

        code = self.rollback(manifest, out)
        self.assertEqual(code, ce.EXIT_OK, out.getvalue())
        self.assertFalse((self.root / first).exists())
        self.assertFalse((self.root / second).exists())

        payload = json.loads(manifest.read_text(encoding="utf-8"))
        removed = self.root / payload["backup_dir"] / "removed"
        # 两份内容分别隔离在完整相对路径下，互不覆盖。
        self.assertEqual((removed / first).read_text(encoding="utf-8"), "a: 1\n")
        self.assertEqual((removed / second).read_text(encoding="utf-8"), "b: 1\n")

    def test_rollback_rejects_duplicate_and_guard_overlap(self):
        manifest = self._apply_two()
        base = json.loads(manifest.read_text(encoding="utf-8"))

        duplicated = json.loads(json.dumps(base))
        duplicated["files"] = [base["files"][0], dict(base["files"][0])]
        forged = self.plan_dir / "dup.json"
        forged.write_text(json.dumps(duplicated), encoding="utf-8")
        self.assertEqual(self.rollback(forged, io.StringIO()), ce.EXIT_INVALID_PLAN)

        overlap = json.loads(json.dumps(base))
        overlap["guards"] = [
            {"path": base["files"][0]["path"], "before_sha256": base["files"][0]["before_sha256"]}
        ]
        forged2 = self.plan_dir / "overlap.json"
        forged2.write_text(json.dumps(overlap), encoding="utf-8")
        self.assertEqual(self.rollback(forged2, io.StringIO()), ce.EXIT_INVALID_PLAN)


class TestPathRules(unittest.TestCase):
    def test_allowed_paths(self):
        for rel in (
            "resources/betterend/configuration/blocks/x.yml",
            "resources/a/b/configuration/items/y.yml",
            "cache/visual_block_states.json",
        ):
            with self.subTest(rel=rel):
                ce._check_allowed_change(PurePosixPath(rel))

    def test_disallowed_paths(self):
        for rel in (
            "config.yml",
            "cache/custom_block_states.json",
            "resources/betterend/pack.yml",
            "resources/betterend/configuration",          # 目录本身
            "resources/betterend/resourcepack/assets/a.json",
            "resources/configuration",
        ):
            with self.subTest(rel=rel):
                with self.assertRaises(ce.PlanError):
                    rel_path = PurePosixPath(rel)
                    ce._check_forbidden_change(rel_path)
                    ce._check_allowed_change(rel_path)

    def test_parse_rel_rejects_unsafe_forms(self):
        for raw in ("", "   ", "/abs/x.yml", "//unc/x.yml", "C:/x.yml", "a/../../b.yml"):
            with self.subTest(raw=raw):
                with self.assertRaises(ce.PlanError):
                    ce._parse_rel(raw, "test")

    def test_parse_rel_normalizes_dot_prefix(self):
        # './a.yml' 被 pathlib 规范化成 'a.yml'，仍在根内，属安全形态而非逃逸。
        self.assertEqual(str(ce._parse_rel("./a.yml", "test")), "a.yml")


if __name__ == "__main__":
    unittest.main()
