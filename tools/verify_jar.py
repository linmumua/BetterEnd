#!/usr/bin/env python3
"""校验 BetterEnd 的最终 Shadow 产物内容。

用法：
    python tools/verify_jar.py jars/BetterEnd-0.1.3-folia26.3.jar

检查项：
  1. 必备资源：BetterEnd 群系数据包、DnT 数据包与 CE 资源包、Echoes 末地结构数据包、生成清单、
     许可证与第三方声明。
  2. SnakeYAML 重定位：不得残留 org/yaml/snakeyaml/，必须存在 dev/mumu/betterend/libs/yaml/。
  3. 禁止项与 NMS 门禁：不得打入服务端 API（org.bukkit / io.papermc / net.minecraft）、
     CraftEngine、UAA；同一条规则的另一侧是「不能排多」——Shadow 的 net/minecraft/**
     排除不得误伤自家 NMS 桥接类 dev/mumu/betterend/vanilla/MuDragonFightBridge.class，
     它必须在产物里，否则运行期 NoClassDefFoundError。
  4. 描述符：paper-plugin.yml 的 api-version / folia-supported / main / bootstrapper / 版本展开。
  5. DnT 数据迁移成品：不得含旧版 worldgen/configured_feature/ 条目；chorus_plant 的配置特征、
     放置特征与模板池 feature_pool_element 引用闭合；wax_wings 内递归 time_check 必须
     clock=minecraft:overworld（其余 time_check 只要求 clock 为非空资源 ID）。
  6. ITEM_MODIFIER 成品：datapacks/*/data/*/item_modifier/*.json 的根必须为带非空 type 的对象
     （26.3 RegistryDataLoader 对该注册表用 LootItemFunctions.TYPED_CODEC，本包需
     {type: minecraft:sequence, functions: []}）。仅约束 item_modifier/ 目录下的根，loot_table
     内联的 modifier 数组不受影响。
  7. 数值提供器成品：三处确切的 set_damage / set_ominous_bottle_amplifier 数值键
     （vault_brigattine、vault_slope 的 pools[4].entries[0].modifier[0].damage，以及
     spawners/end_lighthouse/normal 的 pools[0].entries[5].modifier[0].amplifier）必须
     type=minecraft:uniform 且 min/max 保持原值；按真实结构逐层核对，不做全量 minmax 扫描。
  8. 组件成品：set_components.components 内不得含 map_color/minecraft:map_color（26.3 已移除该
     组件）；maps/end_castle 必须保留 exploration_map 与 item_name。只约束 set_components 的
     components，不误伤 item model 里合法的 minecraft:map_color 着色。
任何一项失败都以非 0 退出码结束，并打印失败原因清单。

仅使用标准库（zipfile/os/sys/glob/json），不引入外部依赖。
"""

from __future__ import annotations

import glob
import json
import os
import sys
import zipfile

# 期望的 jar 内路径。
REQUIRED_ENTRIES = [
    "paper-plugin.yml",
    # BetterEnd 自带群系数据包（由 bootstrap 注册为 BetterEnd/biomes）。
    "datapack/pack.mcmeta",
    # DnT 城堡数据包与 CraftEngine 资源包（由 prepare_dnt.py 生成）。
    "datapacks/dnt-end-castle/pack.mcmeta",
    "craftengine/dnt_end_castle/pack.yml",
    # DnT 迁移成品门禁：26.3 的 Registries.FEATURE 是 worldgen/feature（不是 1.21.x 的
    # worldgen/configured_feature），且 wax_wings 的 time_check 已补时钟。这些条目在旧包里
    # 缺 / 路径错，故独立列出，防止「只在生成目录修好、Shadow 内还是旧数据」。
    "datapacks/dnt-end-castle/data/nova_structures/worldgen/feature/chorus_plant.json",
    "datapacks/dnt-end-castle/data/nova_structures/worldgen/placed_feature/chorus_plant.json",
    "datapacks/dnt-end-castle/data/nova_structures/enchantment/wax_wings.json",
    # 生成清单：prepare_dnt.py 输出的迁移记录（源 SHA-256、目标格式、迁移规则）。
    "dnt-manifest.json",
    "dnt-CREDITS.txt",
    # Echoes of the End（Structures）数据包（由 prepare_echoes.py 直接落在
    # plugin/src/main/resources），bootstrap 注册为 BetterEnd/echoes-end-structures。
    # 城堡密度不在这里：它是 prepare_dnt.py 的迁移规则产物，落在 DnT 包内。
    "datapacks/echoes-end-structures/pack.mcmeta",
    # BetterEnd 自身 CE 资源包。
    "craftengine/betterend/pack.yml",
    # 入口类与 bootstrapper 类（须与 paper-plugin.yml 声明的值一致，见下方描述符检查）。
    "dev/mumu/betterend/BetterEndPlugin.class",
    "dev/mumu/betterend/biome/BiomeDatapack.class",
    # 许可证与第三方声明。
    "LICENSE",
    "LICENSE.ASSETS",
    "THIRD-PARTY-NOTICES",
    # 打包库自身许可证（独立路径，避免与根 LICENSE 同名）。
    "META-INF/licenses/snakeyaml-LICENSE.txt",
    # 中央祭坛必须真正接入产物，不能只留下参考下载与声明。
    "structures/yung_end_island/tower_initial.nbt",
    "structures/yung_end_island/LICENSE-YUNG-Better-End-Island.txt",
    "dev/mumu/betterend/vanilla/EndPodiumTemplate.class",
    "dev/mumu/betterend/vanilla/EndPodiumBuild.class",
    "dev/mumu/betterend/vanilla/EndPodiumShell.class",
    "dev/mumu/betterend/vanilla/EndPodiumShield.class",
    "dev/mumu/betterend/vanilla/EndPodiumBackup.class",
] + [
    f"structures/yung_end_island/pillar_{family}_{tier}.nbt"
    for family in ("bottom", "broken", "guarded", "initial")
    for tier in range(1, 10)
]

# 必须至少存在一项目录前缀（用于确认资源确有内容，而非空壳）。
REQUIRED_PREFIXES = [
    "datapacks/dnt-end-castle/data/",
    "datapacks/echoes-end-structures/data/",
    "craftengine/dnt_end_castle/resourcepack/assets/",
]

# 绝对不允许出现在产物里的前缀（服务端 API / NMS / CraftEngine / UAA / 其它库）。
FORBIDDEN_PREFIXES = [
    "org/bukkit/",
    "org/spigotmc/",
    "io/papermc/",
    "net/minecraft/",
    "net/momirealms/",
    "org/momirealms/",
    "com/fren_gor/",
    "net/kyori/",
]

# 自家 NMS 桥接类：与上面的 net/minecraft/ 是同一道门禁的两侧。
# 类名带「Minecraft」但包在 dev/mumu/ 下；Shadow 的 net/minecraft/** 排除一旦扩宽到
# 自家包（或有人加了更宽的 **/minecraft/**），产物就会缺这个类，且资源校验不会报错，
# 直到运行期才 NoClassDefFoundError。故这里要求它确实在产物内。
REQUIRED_OWN_CLASSES = [
    "dev/mumu/betterend/vanilla/MuDragonFightBridge.class",
]

RELOCATED_SNAKEYAML_PREFIX = "dev/mumu/betterend/libs/yaml/"
UNRELOCATED_SNAKEYAML_PREFIX = "org/yaml/snakeyaml/"

# DnT 数据迁移成品门禁用到的固定路径（相对 DnT 数据包根）。
DNT_PACK_ROOT = "datapacks/dnt-end-castle/"
DNT_CONFIGURED_CHORUS = DNT_PACK_ROOT + "data/nova_structures/worldgen/feature/chorus_plant.json"
DNT_PLACED_CHORUS = DNT_PACK_ROOT + "data/nova_structures/worldgen/placed_feature/chorus_plant.json"
DNT_POOL_CHORUS = (
    DNT_PACK_ROOT + "data/nova_structures/worldgen/template_pool/end_castle/chorus_plant.json"
)
DNT_WAX_WINGS = DNT_PACK_ROOT + "data/nova_structures/enchantment/wax_wings.json"
# 原包旧 DayTime 迁移后，wax_wings 的 time_check 必须锁定主世界时钟。
WAX_WINGS_CLOCK = "minecraft:overworld"

# --- 26.3 codec 成品门禁（0.1.3 新增的有限实际校验）---

# ITEM_MODIFIER 注册表条目：datapacks/<pack>/data/<ns>/item_modifier/ 下（资源 ID 允许子目录，
# 故 <file>.json 可为任意深度路径）。26.3 的 RegistryDataLoader 对该注册表用
# LootItemFunctions.TYPED_CODEC，根必须是带非空 type 的对象；本包 loot_modifier 的正确形态为
# {type: minecraft:sequence, functions: []}。只约束 item_modifier/ 目录下的根对象，
# loot_table 内联的 modifier 数组仍合法。
ITEM_MODIFIER_PREFIX_SEGMENTS = 5  # 定位到 item_modifier 目录所需的前缀段数
ITEM_MODIFIER_EXPECTED_TYPE = "minecraft:sequence"

# 三处确切位置（相对 jar）的数值提供器必须为 minecraft:uniform，且 min/max 保持原值。
# 路径按真实结构逐层给出（int=列表下标，str=字典键），不做全量 minmax 扫描。
# damage 的 min=0.01 / max=0.0 为倒序保留，不交换。
UNIFORM_TYPE = "minecraft:uniform"
UNIFORM_POSITIONS = [
    (
        DNT_PACK_ROOT + "data/nova_structures/loot_table/chests/end_castle/vault_brigattine.json",
        ("pools", 4, "entries", 0, "modifier", 0, "damage"),
        0.01,
        0.0,
    ),
    (
        DNT_PACK_ROOT + "data/nova_structures/loot_table/chests/end_castle/vault_slope.json",
        ("pools", 4, "entries", 0, "modifier", 0, "damage"),
        0.01,
        0.0,
    ),
    (
        DNT_PACK_ROOT + "data/nova_structures/loot_table/spawners/end_lighthouse/normal.json",
        ("pools", 0, "entries", 5, "modifier", 0, "amplifier"),
        3,
        5,
    ),
]

# set_components 的 components 内不得出现地图染色组件（26.3 已移除）。只约束该对象，
# 不误伤 item model 里合法的 minecraft:map_color 着色。
FORBIDDEN_COMPONENT_KEYS = ("map_color", "minecraft:map_color")
# 修复后 maps/end_castle 必须仍保留探索地图与物品名（防止整体删除 modifier）。
DNT_MAPS_END_CASTLE = DNT_PACK_ROOT + "data/nova_structures/loot_table/maps/end_castle.json"

# paper-plugin.yml 的关键字段期望值（与实际类保持一致）。
EXPECTED_API_VERSION = "26.3"
EXPECTED_MAIN = "dev.mumu.betterend.BetterEndPlugin"
EXPECTED_BOOTSTRAPPER = "dev.mumu.betterend.biome.BiomeDatapack"


def read_descriptor(zf: zipfile.ZipFile) -> dict[str, str]:
    """把 paper-plugin.yml 按「顶层标量键: 值」粗略解析，足以读取版本类字段。"""
    raw = zf.read("paper-plugin.yml").decode("utf-8", errors="replace")
    fields: dict[str, str] = {}
    for line in raw.splitlines():
        if not line or line[0] in " \t#'":
            continue  # 只取顶层、非注释行
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        value = value.strip().strip("'\"")
        fields[key.strip()] = value
    return fields


def load_json_entry(zf: zipfile.ZipFile, entry: str, failures: list[str]):
    """读取并解析 jar 内 JSON；条目缺失或解析失败时记录失败并返回 None（不抛异常）。"""
    try:
        raw = zf.read(entry)
    except KeyError:
        return None  # 条目缺失由 REQUIRED_ENTRIES 门禁统一报告
    try:
        return json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        failures.append(f"JSON 无法解析：{entry}（{exc}）")
        return None


def check_nms_packaging_gate(names: set[str], failures: list[str]) -> None:
    """NMS 打包门禁：既不许多排（服务端内部实现），也不许少排（自家桥接类）。

    两半必须一起判：只判「禁止 net/minecraft/」时，把排除规则写宽成能盖住
    dev/mumu/betterend/vanilla/MuDragonFightBridge.class 也不会失败；只判必备类时，
    NMS 误入类路径也不会失败。只读条目名集合，不解析 class 内容。
    """
    for prefix in FORBIDDEN_PREFIXES:
        hits = sorted(n for n in names if n.startswith(prefix))
        if hits:
            failures.append(f"不应打包 {prefix}（共 {len(hits)} 项，例如 {hits[0]}）")

    for entry in REQUIRED_OWN_CLASSES:
        if entry not in names:
            failures.append(
                f"缺少自家 NMS 桥接类：{entry}"
                f"（net/minecraft/** 排除不得误伤 dev/mumu/ 下的自家类）"
            )


def is_legacy_configured_feature(name: str) -> bool:
    """是否为旧版 datapacks/<pack>/data/<ns>/worldgen/configured_feature/ 条目（任意命名空间）。"""
    parts = name.split("/")
    return (
        len(parts) >= 6
        and parts[0] == "datapacks"
        and parts[2] == "data"
        and parts[4] == "worldgen"
        and parts[5] == "configured_feature"
    )


def iter_time_check_nodes(obj):
    """递归产出 type / condition 为 minecraft:time_check 的节点。"""
    if isinstance(obj, dict):
        if obj.get("type") == "minecraft:time_check" or obj.get("condition") == "minecraft:time_check":
            yield obj
        for value in obj.values():
            yield from iter_time_check_nodes(value)
    elif isinstance(obj, list):
        for item in obj:
            yield from iter_time_check_nodes(item)


def resolve_named_id(namespaced_id, registry_dir: str):
    """把 <namespace>:<path> 解析为相对数据包根的 jar 内路径（如 data/<ns>/<registry_dir>/<path>.json）。"""
    if not isinstance(namespaced_id, str) or ":" not in namespaced_id:
        return None
    namespace, _, path = namespaced_id.partition(":")
    if not namespace or not path:
        return None
    return f"data/{namespace}/{registry_dir}/{path}.json"


def is_item_modifier_entry(name: str) -> bool:
    """是否为 datapacks/<pack>/data/<ns>/item_modifier/ 下的 .json 条目（资源 ID 允许子目录）。"""
    parts = name.split("/")
    return (
        len(parts) > ITEM_MODIFIER_PREFIX_SEGMENTS
        and parts[0] == "datapacks"
        and parts[2] == "data"
        and parts[4] == "item_modifier"
        and name.endswith(".json")
    )


def iter_typed_nodes(obj, type_suffix: str):
    """递归产出 type（忽略命名空间前缀）等于给定后缀的字典节点。"""
    if isinstance(obj, dict):
        node_type = obj.get("type")
        if isinstance(node_type, str) and node_type.split(":")[-1] == type_suffix:
            yield obj
        for value in obj.values():
            yield from iter_typed_nodes(value, type_suffix)
    elif isinstance(obj, list):
        for item in obj:
            yield from iter_typed_nodes(item, type_suffix)


_MISSING = object()


def dig(node, path):
    """按 path 逐层取值：int 为列表下标，str 为字典键；任一层缺失/类型不符返回 _MISSING。"""
    for step in path:
        if isinstance(step, int):
            if not isinstance(node, list) or not (-len(node) <= step < len(node)):
                return _MISSING
            node = node[step]
        else:
            if not isinstance(node, dict) or step not in node:
                return _MISSING
            node = node[step]
    return node


def format_path(path) -> str:
    """把 ("pools", 4, "entries", 0) 渲染为 pools[4].entries[0]，用于失败信息。"""
    out = ""
    for step in path:
        if isinstance(step, int):
            out += f"[{step}]"
        else:
            out += ("" if not out else ".") + step
    return out


def check_dnt_migration_gates(
    zf: zipfile.ZipFile, names: set[str], failures: list[str], parsed: dict[str, object]
) -> None:
    """本次发布重点：DnT 迁移成品不得退回旧数据（目录名 / time_check 时钟）。"""

    def load(entry: str):
        """同一条目只解析一次，避免在专项检查与全量扫描中重复报告同一解析错误。"""
        if entry not in parsed:
            parsed[entry] = load_json_entry(zf, entry, failures)
        return parsed[entry]

    # 1) 不得含旧版 worldgen/configured_feature/（26.3 Registries.FEATURE == worldgen/feature）。
    legacy = [n for n in sorted(names) if is_legacy_configured_feature(n)]
    if legacy:
        failures.append(
            f"数据包含旧版 worldgen/configured_feature/ 条目（应为 worldgen/feature，"
            f"共 {len(legacy)} 项，例如 {legacy[0]}）"
        )

    # 2) chorus_plant 配置特征 + 放置特征 + 模板池引用闭合。
    configured = load(DNT_CONFIGURED_CHORUS)
    if isinstance(configured, dict):
        if not isinstance(configured.get("type"), str) or not configured["type"]:
            failures.append(f"配置特征缺少 type：{DNT_CONFIGURED_CHORUS}")

    placed = load(DNT_PLACED_CHORUS)
    if isinstance(placed, dict):
        ref = placed.get("feature")
        rel = resolve_named_id(ref, "worldgen/feature")
        if rel is None:
            failures.append(f"放置特征的 feature 引用非法：{DNT_PLACED_CHORUS} -> {ref!r}")
        elif DNT_PACK_ROOT + rel not in names:
            failures.append(
                f"放置特征的 feature 引用无法解析到配置特征：{DNT_PLACED_CHORUS} -> {ref!r}"
                f"（缺 {DNT_PACK_ROOT + rel}）"
            )

    pool = load(DNT_POOL_CHORUS)
    if isinstance(pool, dict):
        pool_refs = []
        elements = pool.get("elements")
        if isinstance(elements, list):
            for entry in elements:
                element = entry.get("element") if isinstance(entry, dict) else None
                if isinstance(element, dict) and element.get("element_type") == "minecraft:feature_pool_element":
                    pool_refs.append(element.get("feature"))
        if not pool_refs:
            failures.append(f"模板池缺少 feature_pool_element：{DNT_POOL_CHORUS}")
        for ref in pool_refs:
            rel = resolve_named_id(ref, "worldgen/placed_feature")
            if rel is None or DNT_PACK_ROOT + rel not in names:
                failures.append(
                    f"模板池 feature_pool_element 引用无法解析到放置特征：{DNT_POOL_CHORUS} -> {ref!r}"
                )

    # 3) time_check 时钟：wax_wings 必须 clock=minecraft:overworld；其余只要求 clock 为非空资源 ID。
    wax_wings_time_checks = 0
    for name in sorted(names):
        if not (name.startswith(DNT_PACK_ROOT + "data/") and name.endswith(".json")):
            continue
        doc = load(name)
        if doc is None:
            continue
        for node in iter_time_check_nodes(doc):
            clock = node.get("clock")
            if name == DNT_WAX_WINGS:
                wax_wings_time_checks += 1
                if clock != WAX_WINGS_CLOCK:
                    failures.append(
                        f"wax_wings 的 time_check 必须 clock={WAX_WINGS_CLOCK}，实际 {clock!r}：{name}"
                    )
            elif not (isinstance(clock, str) and clock.strip()):
                failures.append(f"time_check 缺少 clock（须为非空资源 ID）：{name}")
    if DNT_WAX_WINGS in names and wax_wings_time_checks == 0:
        failures.append(f"wax_wings 内未找到 time_check（clock 门禁落空）：{DNT_WAX_WINGS}")


def check_registry_data_gates(
    zf: zipfile.ZipFile, names: set[str], failures: list[str], parsed: dict[str, object]
) -> None:
    """0.1.3 新增：三个 26.3 codec 相关的成品门禁（item_modifier 根 / uniform 数值 / set_components 组件）。"""

    def load(entry: str):
        if entry not in parsed:
            parsed[entry] = load_json_entry(zf, entry, failures)
        return parsed[entry]

    # 1) item_modifier 根对象必须是带非空 type 的 dict（26.3 ITEM_MODIFIER 用 TYPED_CODEC）。
    for name in sorted(names):
        if not is_item_modifier_entry(name):
            continue
        doc = load(name)
        if doc is None:
            continue
        if not isinstance(doc, dict):
            failures.append(
                f"item_modifier 根必须为对象且带非空 type（本包需 "
                f"{{type: {ITEM_MODIFIER_EXPECTED_TYPE}, functions: []}}）：{name}"
            )
            continue
        entry_type = doc.get("type")
        if not (isinstance(entry_type, str) and entry_type.strip()):
            failures.append(
                f"item_modifier 根缺少非空 type（本包需 {ITEM_MODIFIER_EXPECTED_TYPE}）：{name}"
            )

    # 2) 三处确切的 uniform 数值提供器（按下标逐层核对，不做全量 minmax 扫描）。
    for name, path, want_min, want_max in UNIFORM_POSITIONS:
        if name not in names:
            failures.append(f"缺少必备条目：{name}")
            continue
        doc = load(name)
        if doc is None:
            continue
        where = f"{name} 的 {format_path(path)}"
        node = dig(doc, path)
        if node is _MISSING:
            failures.append(f"{where} 位置缺失")
            continue
        if not isinstance(node, dict):
            failures.append(f"{where} 必须为对象，实际 {type(node).__name__}")
            continue
        if node.get("type") != UNIFORM_TYPE:
            failures.append(f"{where} 必须 type={UNIFORM_TYPE}，实际 {node.get('type')!r}")
        if node.get("min") != want_min or node.get("max") != want_max:
            failures.append(
                f"{where} 必须保持 min={want_min} max={want_max}，"
                f"实际 min={node.get('min')!r} max={node.get('max')!r}"
            )

    # 3) set_components.components 不得含地图染色组件（只约束该对象）。
    for name in sorted(names):
        if not (name.startswith(DNT_PACK_ROOT + "data/") and name.endswith(".json")):
            continue
        doc = load(name)
        if doc is None:
            continue
        for node in iter_typed_nodes(doc, "set_components"):
            components = node.get("components")
            if not isinstance(components, dict):
                continue
            for key in FORBIDDEN_COMPONENT_KEYS:
                if key in components:
                    failures.append(
                        f"set_components.components 不得含 {key}（26.3 已移除该组件）：{name}"
                    )

    # maps/end_castle 修复后必须仍保留 exploration_map 与 item_name。
    if DNT_MAPS_END_CASTLE in names:
        doc = load(DNT_MAPS_END_CASTLE)
        if isinstance(doc, dict):
            if not any(iter_typed_nodes(doc, "exploration_map")):
                failures.append(f"maps/end_castle 必须保留 exploration_map：{DNT_MAPS_END_CASTLE}")
            kept_item_name = any(
                isinstance(node.get("components"), dict)
                and ("item_name" in node["components"] or "minecraft:item_name" in node["components"])
                for node in iter_typed_nodes(doc, "set_components")
            )
            if not kept_item_name:
                failures.append(f"maps/end_castle 必须保留 item_name 组件：{DNT_MAPS_END_CASTLE}")


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        jar_path = argv[1]
    else:
        candidates = sorted(glob.glob(os.path.join("jars", "BetterEnd-*.jar")))
        if not candidates:
            print("未找到 jars/BetterEnd-*.jar，请显式给出 jar 路径。", file=sys.stderr)
            return 2
        jar_path = candidates[-1]

    if not os.path.isfile(jar_path):
        print(f"jar 不存在：{jar_path}", file=sys.stderr)
        return 2

    failures: list[str] = []
    warnings: list[str] = []
    # 同一条目只解析一次（跨「DnT 迁移成品」与「注册表成品」两轮扫描共享），避免重复报解析错误。
    parsed: dict[str, object] = {}

    try:
        zf = zipfile.ZipFile(jar_path)
    except zipfile.BadZipFile as exc:
        print(f"jar 不是有效 ZIP（可能损坏）：{jar_path}（{exc}）", file=sys.stderr)
        return 2

    with zf:
        names = set(zf.namelist())

        # 1. 必备资源（含目录条目）
        for entry in REQUIRED_ENTRIES:
            if entry not in names:
                failures.append(f"缺少必备条目：{entry}")
        for prefix in REQUIRED_PREFIXES:
            if not any(n.startswith(prefix) for n in names):
                failures.append(f"缺少目录内容：{prefix}")

        # 2. SnakeYAML 重定位
        if any(n.startswith(UNRELOCATED_SNAKEYAML_PREFIX) for n in names):
            failures.append("存在未重定位的 org/yaml/snakeyaml/，重定位未生效")
        if not any(n.startswith(RELOCATED_SNAKEYAML_PREFIX) for n in names):
            failures.append(f"缺少重定位后的 SnakeYAML 类：{RELOCATED_SNAKEYAML_PREFIX}")

        # 3. 禁止项与 NMS 门禁（多排服务端实现 / 少排自家桥接类都判失败）
        check_nms_packaging_gate(names, failures)

        # 4. 描述符
        if "paper-plugin.yml" in names:
            fields = read_descriptor(zf)
            api_version = fields.get("api-version", "")
            if api_version != EXPECTED_API_VERSION:
                failures.append(
                    f"paper-plugin.yml 的 api-version 期望 {EXPECTED_API_VERSION}，实际 '{api_version}'"
                )
            if fields.get("folia-supported", "").lower() != "true":
                failures.append("paper-plugin.yml 未声明 folia-supported: true")
            if fields.get("main", "") != EXPECTED_MAIN:
                failures.append(
                    f"paper-plugin.yml 的 main 期望 {EXPECTED_MAIN}，实际 '{fields.get('main', '')}'"
                )
            bootstrapper = fields.get("bootstrapper", "")
            if bootstrapper != EXPECTED_BOOTSTRAPPER:
                failures.append(
                    f"paper-plugin.yml 的 bootstrapper 期望 {EXPECTED_BOOTSTRAPPER}，"
                    f"实际 '{bootstrapper}'"
                )
            version = fields.get("version", "")
            if "${" in version or version == "":
                failures.append(f"paper-plugin.yml 的 version 未被展开：'{version}'")

        # 5. DnT 迁移成品（目录名 / time_check 时钟）
        check_dnt_migration_gates(zf, names, failures, parsed)

        # 6-8. 26.3 codec 成品（item_modifier 根 / uniform 数值 / set_components 组件）
        check_registry_data_gates(zf, names, failures, parsed)

        # 提示项（不判失败）
        if any(n.endswith("module-info.class") for n in names):
            warnings.append(
                "产物内存在 module-info.class：重定位后的库可能仍带旧模块描述符；"
                "作为 classpath 使用不受影响，如需可在 shadowJar 中排除。"
            )

    print(f"校验产物：{jar_path}")
    print(f"  条目总数：{len(names)}")
    if warnings:
        print("  [警告]")
        for w in warnings:
            print(f"    - {w}")
    if failures:
        print("  [失败]")
        for f in failures:
            print(f"    - {f}")
        print(f"\n校验未通过：{len(failures)} 项失败。")
        return 1

    print(
        "  [通过] 必备资源（含目录条目）、SnakeYAML 重定位、禁止项与 NMS 门禁"
        "（无服务端实现、且自家桥接类在包内）、描述符版本、"
        "DnT 迁移成品（worldgen/feature 目录与 time_check 时钟）、"
        "注册表成品（item_modifier 根 / uniform 数值提供器 / set_components 组件）均符合预期。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
