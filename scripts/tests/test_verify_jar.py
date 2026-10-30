"""verify_jar 的 NMS 打包门禁单元测试。

运行：
    python -m unittest discover -s scripts/tests -v
或：
    python -m unittest scripts.tests.test_verify_jar -v

为什么单独测这一项：NMS 门禁是「两侧同时成立」的规则——产物里不能有
``net/minecraft/**``（服务端内部实现），但自家 ``dev/mumu/betterend/vanilla/
MuDragonFightBridge.class`` 必须还在（它引用 NMS 类型，Shadow 的排除规则一旦写宽就会
把它一起排掉）。资源校验对此完全沉默，只有运行期才 NoClassDefFoundError。

测试只用标准库在临时目录造极小的 zip（几条类目名，不加真实字节），再直接调用
``check_nms_packaging_gate``；不伪造一个「完整产物」，所以不会随产物内容演进失效。
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[2] / "tools"
sys.path.insert(0, str(TOOLS_DIR))

import verify_jar  # noqa: E402

BRIDGE = "dev/mumu/betterend/vanilla/MuDragonFightBridge.class"
#: 干净的自家类名（桥接类之外再放两个，确保门禁不是「只认一个路径」）。
CLEAN_OWN_ENTRIES = [
    BRIDGE,
    "dev/mumu/betterend/BetterEndPlugin.class",
    "dev/mumu/betterend/vanilla/EndPodiumTemplate.class",
]
CRAFTBUKKIT = "org/bukkit/craftbukkit/CraftServer.class"
NMS_SERVER = "net/minecraft/server/MinecraftServer.class"
NMS_ANY = "net/minecraft/world/level/Level.class"


def write_zip(entries: list[str]) -> str:
    """把给定条目名写进一个临时 zip，返回路径（条目名即全部内容，无需真实字节）。"""
    handle, path = tempfile.mkstemp(suffix=".jar")
    os.close(handle)
    with zipfile.ZipFile(path, "w") as zf:
        for name in entries:
            zf.writestr(name, b"")
    return path


class NmsGateTest(unittest.TestCase):
    """服务端实现入包须拒绝，自家桥接类缺失也须拒绝。"""

    def gate(self, entries: list[str]) -> list[str]:
        """跑一次门禁，返回失败清单（空表示通过）。"""
        path = write_zip(entries)
        self.addCleanup(os.remove, path)
        with zipfile.ZipFile(path) as zf:
            names = set(zf.namelist())
        failures: list[str] = []
        verify_jar.check_nms_packaging_gate(names, failures)
        return failures

    def test_nms_class_is_rejected(self):
        """net/minecraft/** 入包必须失败：那份字节属于服务端，打进插件会与运行期类冲突。"""
        for entry in (NMS_SERVER, NMS_ANY):
            with self.subTest(entry=entry):
                failures = self.gate(CLEAN_OWN_ENTRIES + [entry])
                self.assertTrue(failures, f"{entry} 未被拒绝")
                self.assertTrue(
                    any("net/minecraft/" in f for f in failures),
                    f"失败原因未指向 net/minecraft/：{failures}",
                )

    def test_craftbukkit_class_is_rejected(self):
        """CraftBukkit 实现同样必须失败（由 org/bukkit/ 前缀覆盖 craftbukkit 子包）。"""
        failures = self.gate(CLEAN_OWN_ENTRIES + [CRAFTBUKKIT])
        self.assertTrue(failures, "org/bukkit/craftbukkit 未被拒绝")
        self.assertTrue(
            any("org/bukkit/" in f for f in failures),
            f"失败原因未指向 org/bukkit/：{failures}",
        )

    def test_clean_own_classes_pass(self):
        """只有自家类时门禁必须放行：这条规则不能连自家包一起否掉。"""
        self.assertEqual(self.gate(CLEAN_OWN_ENTRIES), [])

    def test_missing_bridge_class_is_rejected(self):
        """缺自家桥接类必须失败：排除规则写宽时，资源校验不会报，只有这道门禁会。"""
        failures = self.gate([e for e in CLEAN_OWN_ENTRIES if e != BRIDGE])
        self.assertTrue(failures, f"缺少 {BRIDGE} 未被拒绝")
        self.assertTrue(
            any("MuDragonFightBridge.class" in f for f in failures),
            f"失败原因未指向桥接类：{failures}",
        )

    def test_forbidden_prefixes_never_cover_own_classes(self):
        """禁止前缀不得覆盖任何必备自家类，否则两侧规则自相矛盾（永远无法通过）。"""
        for entry in verify_jar.REQUIRED_OWN_CLASSES:
            for prefix in verify_jar.FORBIDDEN_PREFIXES:
                with self.subTest(entry=entry, prefix=prefix):
                    self.assertFalse(
                        entry.startswith(prefix),
                        f"必备类 {entry} 落在禁止前缀 {prefix} 下，门禁无法同时满足",
                    )


if __name__ == "__main__":
    unittest.main()
