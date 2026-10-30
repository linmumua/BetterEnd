package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.terrain.IslandField;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新锚点（70/3）下的**纯**部分：重建蓝图、分桶、下限预检、冲突判定、持久化、以及「官方结构晶 vs 玩家晶」
 * 的识别判据。
 *
 * <p>这一组用例钉的都是「错了就毁世界 / 搬走玩家晶 / 静默截断」的东西：
 * <ul>
 *   <li><b>蓝图与生成是同一套</b>：同一格在生成期与重铺期写出同一个状态；</li>
 *   <li><b>重铺幂等</b>：同一份蓝图跑两遍逐格相同，跑在不同「污染」状态上也收敛到同一结果；</li>
 *   <li><b>地下段不被静默截断</b>：下段最低格恒为 3，世界底线抬到 4 就必须失败；</li>
 *   <li><b>真实冠高</b>：用模板实际内容算，不用 sizeY 推；</li>
 *   <li><b>分桶 ≤1024 且不重不漏</b>；</li>
 *   <li><b>玩家改动立即中止</b>：钻石块之类的非受管方块一律判冲突；</li>
 *   <li><b>只认原版槽位的官方晶</b>：玩家放的晶、别的高度、别的 XZ 都不认；</li>
 *   <li><b>登记表/快照</b>往返一致，坏文件明确报错；复活两次后登记的是最新的那颗晶。</li>
 * </ul>
 */
class YungPillarBlueprintTest {

    private static final long SEED = 42L;
    /** 终结维度：{@code min_y = 0}、{@code max_y = 256}（与原版一致，测试只关心下界）。 */
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 255;

    // --- 蓝图 -----------------------------------------------------------------------------------

    /** 蓝图确定性、无重复坐标、两类格互不重叠。 */
    @Test
    void theBlueprintIsDeterministicAndHasNoDuplicateCells() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint first = YungPillars.blueprint(spike, SEED, field);
            YungPillars.Blueprint second = YungPillars.blueprint(spike, SEED, field);
            assertEquals(first.cells().size(), second.cells().size());
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < first.cells().size(); i++) {
                YungPillars.Cell cell = first.cells().get(i);
                assertEquals(cell, second.cells().get(i), "两次蓝图在同一位置给出不同格");
                assertTrue(seen.add(cell.key()), "蓝图里出现重复坐标 " + cell.x() + "," + cell.y()
                        + "," + cell.z());
                assertFalse(cell.state().isEmpty(), "空格子目标状态");
            }
            int templates = 0;
            for (YungPillars.Cell cell : first.cells()) {
                if (cell.origin() == YungPillars.Origin.TEMPLATE) templates++;
            }
            assertEquals(first.templateCells(), templates, "模板格计数与来源标记不一致");
            assertTrue(first.templateCells() > 1000, "模板格太少：" + first.templateCells());
        }
    }

    /**
     * 座位与水晶洞：座位是基岩、水晶洞那一格**不在**蓝图里（它是空的，留给实体）。
     * 这条保证「搬晶体之前那一格永远是空的」，也就保证了晶体不会被埋。
     */
    @Test
    void theSeatIsBedrockAndTheCrystalCellIsNeverWritten() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, SEED, field);
            YungPillars.Layout layout = blueprint.layout();
            Map<Long, String> byKey = new HashMap<>();
            for (YungPillars.Cell cell : blueprint.cells()) byKey.put(cell.key(), cell.state());

            assertEquals("minecraft:bedrock",
                    byKey.get(YungPillars.pack(spike.centerX(), layout.seatY(), spike.centerZ())),
                    "座位不是基岩");
            assertNull(byKey.get(YungPillars.pack(spike.centerX(), layout.crystalY(),
                            spike.centerZ())),
                    "水晶洞被写了一个方块，晶体搬进去就会卡在方块里");
            assertTrue(blueprint.bounds().minY() <= layout.seatY());
            assertTrue(blueprint.bounds().maxY() >= layout.crystalY() - 1);
        }
    }

    /**
     * 晶洞净空 + 「搬迁必要」的反例（多种子 × 十柱）。
     *
     * <p>净空判据与 {@code YungPillarsTest#theCrystalNeighbourhoodIsClearExceptForTheUpstreamCageCorners}
     * 是同一套：{@code EndCrystal} 的 2×2×2 碰撞箱、中心 {@code (cx+0.5, crystalY, cz+0.5)}，只压到
     * {@code dx,dz ∈ {-1,0,1}} 九列里的 {@code crystalY} / {@code crystalY+1} 两层。九个直邻位必须空，
     * 四个对角只允许 upstream 自己的守卫栏杆（guarded 的 1/4/7 档在那里有 {@code iron_bars}）。
     * **不要求 3×3 全空**，也不向下看 {@code crystalY-1}（那是模板的基岩座位）。
     *
     * <p>反例：原版水晶位 {@code (cx, height+1, cz)} 在新布局里被模板砌成黑曜石（十柱实测如此），所以
     * 「把官方晶搬到 {@code 70+offset}」不是装饰，而是唯一不让晶体埋进柱芯的做法。这里断言旧槽**实心**，
     * 绝不反过来断言它空——「旧槽必须是 air」的用例会把「没搬、原地空着」误判成通过。
     */
    @Test
    void theCrystalBoxIsClearWhileTheOldVanillaSlotIsSolid() {
        for (long seed : new long[]{SEED, 7L, -99L, 2024L}) {
            IslandField field = new IslandField(seed);
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, seed, field);
                YungPillars.Layout layout = blueprint.layout();
                Map<Long, YungPillars.Cell> byKey = new LinkedHashMap<>();
                for (YungPillars.Cell cell : blueprint.cells()) byKey.put(cell.key(), cell);
                String where = "seed " + seed + " 柱 " + spike.centerX() + "," + spike.centerZ() + "（"
                        + layout.type() + "_" + layout.tier() + " rot " + layout.rotation() + "）";

                for (int dy = 0; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            YungPillars.Cell cell = byKey.get(YungPillars.pack(spike.centerX() + dx,
                                    layout.crystalY() + dy, spike.centerZ() + dz));
                            if (cell == null) continue;
                            boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                            assertTrue(corner, where + " 蓝图往晶体的正/侧邻位 (dx=" + dx + ",dy="
                                    + dy + ",dz=" + dz + ") 写了 " + cell.state() + "，晶体搬进去会卡住");
                            assertTrue(cell.state().startsWith("minecraft:iron_bars"),
                                    where + " 蓝图在晶体对角写了非栏杆方块 " + cell.state()
                                            + "，碰撞箱角落被实心方块占住");
                        }
                    }
                }

                YungPillars.Cell seat = byKey.get(YungPillars.pack(spike.centerX(), layout.seatY(),
                        spike.centerZ()));
                assertNotNull(seat, where + " 蓝图里没有座位格");
                assertEquals("minecraft:bedrock", seat.state(), where + " 座位不是基岩");
                assertEquals(YungPillars.Origin.TEMPLATE, seat.origin(), where + " 座位不是模板写的");

                YungPillars.Cell oldSlot = byKey.get(YungPillars.pack(spike.centerX(),
                        spike.height() + 1, spike.centerZ()));
                assertNotNull(oldSlot, where + " 旧槽 height+1 在蓝图里没有任何格——「必须搬晶」的前提没了");
                assertEquals("minecraft:obsidian", oldSlot.state(),
                        where + " 旧槽不再是实心黑曜石；模板若变了，「把晶体搬走」的理由要重新论证");
                assertEquals(YungPillars.Origin.TEMPLATE, oldSlot.origin(),
                        where + " 旧槽的实心不是模板写的，判据与结论对不上");
            }
        }
    }

    /** 原版残留格：旧基岩座与那格火都在集合里（目标按地形），且不会覆盖模板已经占用的坐标。 */
    @Test
    void theVanillaLeftoversCoverTheOldSeatAndFireButNeverOverrideTheTemplate() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, SEED, field);
            Map<Long, YungPillars.Cell> template = new LinkedHashMap<>();
            Map<Long, YungPillars.Cell> vanilla = new LinkedHashMap<>();
            for (YungPillars.Cell cell : blueprint.cells()) {
                (cell.origin() == YungPillars.Origin.TEMPLATE ? template : vanilla)
                        .put(cell.key(), cell);
            }
            // 旧基岩座与那格火：要么落在原版集合里（还原成空气/末地石），要么被模板覆盖。
            // 两种情况的共同要求是——最终状态既不是原版那块基岩，也不是那格火。
            Map<Long, String> finalStates = new HashMap<>();
            for (YungPillars.Cell cell : blueprint.cells()) {
                finalStates.put(cell.key(), cell.state());
            }
            long oldSeat = YungPillars.pack(spike.centerX(), spike.height(), spike.centerZ());
            long oldFire = YungPillars.pack(spike.centerX(), spike.height() + 1, spike.centerZ());
            assertNotNull(finalStates.get(oldSeat), "原版旧基岩座既没被收尾也没被模板覆盖");
            assertNotEquals("minecraft:bedrock", finalStates.get(oldSeat),
                    "原版旧基岩座会留在世界里");
            assertNotNull(finalStates.get(oldFire), "原版那格火既没被收尾也没被模板覆盖");
            assertNotEquals("minecraft:fire", finalStates.get(oldFire), "原版那格火会留在世界里");
            for (Long key : vanilla.keySet()) {
                assertFalse(template.containsKey(key), "同一格既算原版格又算模板格：" + key);
            }
            for (YungPillars.Cell cell : vanilla.values()) {
                assertTrue("minecraft:air".equals(cell.state())
                                || "minecraft:end_stone".equals(cell.state()),
                        "原版格的目标只能是空气或末地石，实际 " + cell.state());
            }
        }
    }

    // --- 生成期与重铺期一致 ---------------------------------------------------------------------

    /**
     * 同一格：生成期写出的状态 == 蓝图里的状态（多个种子 × 十根柱，逐档逐旋转都过一遍）。
     *
     * <p>唯一允许的差别是「模板空气 + 地形实心」：生成期不写（保住岛体与洞穴），重铺期写空气/末地石。
     * 这条用例把这条差别逐格钉住，避免两套写入悄悄漂移。
     *
     * <p>另外单独钉晶洞周围：晶体压到的 3×3×2 里，生成期写出的每一格都必须与蓝图一致（guarded 的对角
     * 栏杆也在里面），而九个直邻位必须两边都没写过——否则「生成时留了洞、重铺时又砌上」这类漂移会先把
     * 晶体埋掉，再让搬晶的日志看起来一切正常。
     */
    @Test
    void generationAndBlueprintAgreeOnEveryTemplateCell() {
        int checked = 0;
        for (long seed : new long[]{SEED, 7L, -99L}) {
            IslandField field = new IslandField(seed);
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                Map<Long, String> generated = populate(spike, field, seed);
                YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, seed, field);
                YungPillars.Layout layout = blueprint.layout();
                Map<Long, YungPillars.Cell> byKey = new HashMap<>();
                for (YungPillars.Cell cell : blueprint.cells()) byKey.put(cell.key(), cell);
                String where = "seed " + seed + " 柱 " + spike.centerX() + "," + spike.centerZ();
                for (YungPillars.Cell cell : blueprint.cells()) {
                    if (cell.origin() != YungPillars.Origin.TEMPLATE) continue;
                    String state = generated.get(YungPillars.pack(cell.x(), cell.y(), cell.z()));
                    if (state == null) {
                        assertEquals("minecraft:air", cell.state(),
                                where + " 生成期没写这一格，但蓝图不是空气：" + cell.x() + ","
                                        + cell.y() + "," + cell.z());
                        assertTrue(field.isSolid(cell.x(), cell.y(), cell.z()),
                                where + " 模板空气格在生成期被跳过，只能是「地形实心」那一类");
                        continue;
                    }
                    assertEquals(cell.state(), state, where + " 生成期与蓝图不一致：" + cell.x() + ","
                            + cell.y() + "," + cell.z());
                    checked++;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int x = spike.centerX() + dx;
                            int y = layout.crystalY() + dy;
                            int z = spike.centerZ() + dz;
                            long key = YungPillars.pack(x, y, z);
                            YungPillars.Cell blueprintCell = byKey.get(key);
                            String inBlueprint = blueprintCell == null ? null : blueprintCell.state();
                            String inWorld = generated.get(key);
                            boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                            if (!corner) {
                                assertNull(inBlueprint, where + " 蓝图往晶体邻位 " + x + "," + y + ","
                                        + z + " 写了 " + inBlueprint);
                                assertNull(inWorld, where + " 生成期往晶体邻位 " + x + "," + y + ","
                                        + z + " 写了 " + inWorld);
                                continue;
                            }
                            assertEquals(inBlueprint, inWorld,
                                    where + " 晶体对角 " + x + "," + y + "," + z
                                            + " 的「生成期 vs 蓝图」不一致");
                            if (inBlueprint != null) {
                                assertTrue(inBlueprint.startsWith("minecraft:iron_bars"),
                                        where + " 晶体对角 " + x + "," + y + "," + z + " 是 "
                                                + inBlueprint + "，不是 upstream 的栏杆");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 1000, "只核对了 " + checked + " 格");
    }

    // --- 幂等 -----------------------------------------------------------------------------------

    /**
     * 重铺幂等：把蓝图连跑两遍，逐格相同；再拿一份「被原版复活流程弄得乱七八糟」的世界跑一遍，
     * 结果仍然等于干净跑一遍的结果（这就是「按布局修复而不是只补现存实心」）。
     */
    @Test
    void rebuildingTwiceConvergesToTheSameWorld() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, SEED, field);

            FakeArea clean = new FakeArea();
            apply(blueprint, clean);
            Map<Long, String> once = new HashMap<>(clean.blocks);

            apply(blueprint, clean);
            assertEquals(once, clean.blocks, "同一份蓝图跑两遍结果不同（不幂等）");

            FakeArea wrecked = new FakeArea();
            // 模拟复活：21³ 被 removeBlock、原版按旧 H 重建、再加上爆炸留下的空气。
            wrecked.fillAir(spike.centerX() - 10, spike.height() - 10, spike.centerZ() - 10,
                    spike.centerX() + 10, spike.height() + 10, spike.centerZ() + 10);
            fillVanillaShaft(wrecked, spike);
            apply(blueprint, wrecked);
            for (Map.Entry<Long, String> entry : once.entrySet()) {
                assertEquals(entry.getValue(), wrecked.blocks.get(entry.getKey()),
                        "重铺没有收敛到干净结果：" + YungPillars.unpackX(entry.getKey()) + ","
                                + YungPillars.unpackY(entry.getKey()) + ","
                                + YungPillars.unpackZ(entry.getKey()));
            }
        }
    }

    // --- AIR 修复（地形修补格） -----------------------------------------------------------------

    /**
     * 地形修补格：原版复活会把岛面挖出洞，蓝图要把「地形说实心」的格补回末地石；但它必须**跳过
     * {@code cave_air}**（那是 {@code CaveCarver} 的洞穴，不是缺口）。
     */
    @Test
    void restoreCellsFillHolesButNeverFillCaves() {
        IslandField field = new IslandField(SEED);
        int restoreSeen = 0;
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, SEED, field);
            List<YungPillars.Cell> restore = new ArrayList<>();
            for (YungPillars.Cell cell : blueprint.cells()) {
                if (cell.origin() != YungPillars.Origin.RESTORE) continue;
                restore.add(cell);
                assertEquals("minecraft:end_stone", cell.state(), "地形修补格的目标不是末地石");
                assertTrue(field.isSolid(cell.x(), cell.y(), cell.z()),
                        "地形修补格必须落在地形实心格上");
            }
            if (restore.isEmpty()) continue;
            restoreSeen += restore.size();

            YungPillars.Cell hole = restore.get(0);
            YungPillars.Cell cave = restore.get(restore.size() - 1);
            FakeArea area = new FakeArea();
            area.blocks.put(hole.key(), "minecraft:air");
            area.blocks.put(cave.key(), "minecraft:cave_air");
            apply(blueprint, area);
            assertEquals("minecraft:end_stone", area.blocks.get(hole.key()),
                    "被擦成空气的地形没有被补回来（会留下永久缺口）");
            assertEquals("minecraft:cave_air", area.blocks.get(cave.key()),
                    "把 CaveCarver 的洞穴填了");
            assertFalse(YungPillars.shouldWrite(hole, "minecraft:air") == false,
                    "空气格应当允许写");
        }
        assertTrue(restoreSeen > 0, "没有任何地形修补格，AIR 修复等于没实现");
    }

    /**
     * 地形修补格够不到晶洞——先把地形量出来，再决定要不要把它当 bug。
     *
     * <p>{@code restoreCells} 的 y 窗口是 {@code [66, min(height+10, TOP_SOLID_Y)]}，晶洞在 {@code 92..108}，
     * 所以「修补格可能填掉晶洞」只在「柱心的岛面高过 90」时才成立。{@link IslandField} 是纯种子函数：
     * 实测四个种子、四十根柱里，修补格最高只到 71..74，离 {@code crystalY} 还剩 18..21 格。所以这是
     * **理论可能、实际不发生**，不写成 bug，也不为了让理论成立去改 {@code restoreCells}。
     *
     * <p>用例钉三件：修补格都落在地形实心格上；最高修补格比晶洞低至少 10 格；晶洞的 3×3×2 里既没有
     * 修补格、也没有实心地形——一旦地形真长到那儿，restore 就会把晶体埋回去，这条会立刻红（那时要做的
     * 是把晶洞从 {@code restoreCells} 里排除，而不是删掉这条断言）。
     */
    @Test
    void restoreCellsNeverReachTheCrystalHeight() {
        int measured = 0;
        for (long seed : new long[]{SEED, 7L, -99L, 2024L}) {
            IslandField field = new IslandField(seed);
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, seed, field);
                YungPillars.Layout layout = blueprint.layout();
                String where = "seed " + seed + " 柱 " + spike.centerX() + "," + spike.centerZ();
                int highestRestore = Integer.MIN_VALUE;
                Set<Long> restore = new HashSet<>();
                for (YungPillars.Cell cell : blueprint.cells()) {
                    if (cell.origin() != YungPillars.Origin.RESTORE) continue;
                    restore.add(cell.key());
                    highestRestore = Math.max(highestRestore, cell.y());
                    assertTrue(field.isSolid(cell.x(), cell.y(), cell.z()),
                            where + " 的修补格 " + cell.x() + "," + cell.y() + "," + cell.z()
                                    + " 不在实心地形上");
                }
                measured++;
                if (highestRestore == Integer.MIN_VALUE) continue;
                assertTrue(highestRestore < layout.crystalY(),
                        where + " 的修补格到了 y=" + highestRestore + "，已经够到晶洞 "
                                + layout.crystalY());
                assertTrue(layout.crystalY() - highestRestore >= 10,
                        where + " 的修补格离晶洞只剩 " + (layout.crystalY() - highestRestore)
                                + " 格，余量已经不够：这时必须把晶洞排除出 restoreCells");
                assertTrue(field.topY(spike.centerX(), spike.centerZ()) < layout.crystalY(),
                        where + " 的柱心地形顶端已经高过晶洞");
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int x = spike.centerX() + dx;
                            int z = spike.centerZ() + dz;
                            int y = layout.crystalY() + dy;
                            assertFalse(restore.contains(YungPillars.pack(x, y, z)),
                                    where + " 的修补格落进了晶洞 " + x + "," + y + "," + z);
                            assertFalse(field.isSolid(x, y, z), where + " 晶洞处的地形是实心的："
                                    + x + "," + y + "," + z + "，restoreCells 会把晶体埋回去");
                        }
                    }
                }
            }
        }
        assertTrue(measured >= 40, "只量到 " + measured + " 根柱子，样本不够");
    }

    // --- 下限与冠高 -----------------------------------------------------------------------------

    /** 下段最低格恒为 3（= 70-67），绝不静默截断；世界底线抬到 4 就必须失败。 */
    @Test
    void theLowerSegmentReachesY3AndNeverGetsSilentlyTruncated() {
        for (long seed : new long[]{SEED, 7L, -99L}) {
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                String where = "seed " + seed + " 柱 " + spike.centerX() + "," + spike.centerZ();
                YungPillars.Layout layout = YungPillars.layout(spike, seed);
                int lowest = YungPillars.lowestTemplateY(spike, seed);
                // 下段锚点是 3；模板实际内容从哪个 local y 开始由模板自己决定（各档不同），
                // 所以这里钉的是「锚点 + 模板真实最低 local y」，而不是假设模板占满 0..66。
                assertEquals(layout.bottomAnchorY()
                                + localMinY("pillar_bottom_" + layout.tier(), layout.rotation()),
                        lowest, where + " 的下段最低格不等于锚点+模板最低 local y");
                assertTrue(lowest >= YungPillars.LOWER_BASE_Y,
                        where + " 的下段落到锚点以下：" + lowest);
                assertTrue(YungPillars.withinWorld(spike, seed, WORLD_MIN_Y, WORLD_MAX_Y), where);
                YungPillars.checkWorldLimits(spike, seed, WORLD_MIN_Y, WORLD_MAX_Y);
                int impossibleFloor = lowest + 1;
                assertThrows(IllegalStateException.class,
                        () -> YungPillars.checkWorldLimits(spike, seed, impossibleFloor, WORLD_MAX_Y),
                        where + " 世界底线抬到 " + impossibleFloor + " 时应当失败，而不是截断");
            }
        }
    }

    /** 「真实冠高」：用模板实际非空气内容算，必须明显高于水晶槽，且不超出模板 box。 */
    @Test
    void theCrownHeightComesFromRealContentNotFromSizeY() {
        for (long seed : new long[]{SEED, 7L, -99L}) {
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                YungPillars.Layout layout = YungPillars.layout(spike, seed);
                int highest = YungPillars.highestTemplateY(spike, seed);
                String where = "seed " + seed + " 柱 " + spike.centerX() + "," + spike.centerZ();
                assertTrue(highest > layout.crystalY() + 10,
                        where + " 的冠部（" + highest + "）没有明显高出水晶槽（" + layout.crystalY()
                                + "）");
                assertTrue(highest <= layout.topAnchorY() + YungPillars.TOP_HEIGHT - 1,
                        where + " 的冠部越出上段模板 box");
                YungPillars.Bounds footprint = YungPillars.footprint(spike, seed);
                assertEquals(highest, footprint.maxY(), where + " footprint 的最高点与冠高不一致");
                assertTrue(footprint.minY() >= 3, where + " footprint 低于下段锚点");
                assertTrue(footprint.maxX() - footprint.minX() <= 2 * YungPillars.HALF_WIDTH,
                        where + " footprint 比模板还宽");
            }
        }
    }

    // --- 分桶 -----------------------------------------------------------------------------------

    /** 分桶：每桶 ≤1024 格、同桶同区块、所有格恰好出现一次。 */
    @Test
    void batchesStayUnderTheCellLimitAndCoverEveryCellOnce() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, SEED, field);
            List<YungPillars.Batch> batches = blueprint.batches(YungPillarLifecycle.MAX_CELLS_PER_TASK);
            Set<Long> seen = new HashSet<>();
            int total = 0;
            for (YungPillars.Batch batch : batches) {
                assertTrue(batch.size() > 0 && batch.size() <= YungPillarLifecycle.MAX_CELLS_PER_TASK,
                        "桶大小越界：" + batch.size());
                for (YungPillars.Cell cell : batch.cells()) {
                    assertEquals(cell.x() >> 4, batch.chunkX(), "桶里混了别的区块的格");
                    assertEquals(cell.z() >> 4, batch.chunkZ(), "桶里混了别的区块的格");
                    assertTrue(seen.add(cell.key()), "同一格被分进两个桶");
                    total++;
                }
            }
            assertEquals(blueprint.cells().size(), total, "分桶漏格");
            assertThrows(IllegalArgumentException.class, () -> blueprint.batches(0));
        }
    }

    // --- 冲突判定 -------------------------------------------------------------------------------

    /** 玩家放的方块一律判冲突；空气/黑曜石/火/末地石这类「本来就会出现在这里的」不算。 */
    @Test
    void onlyManagedStatesMayBeOverwritten() {
        assertTrue(YungPillars.acceptable("minecraft:air", "minecraft:obsidian", null));
        assertTrue(YungPillars.acceptable("minecraft:obsidian", "minecraft:air", null));
        assertTrue(YungPillars.acceptable("minecraft:cave_air[foo=bar]", "minecraft:air", null));
        assertTrue(YungPillars.acceptable("minecraft:fire[age=3]", "minecraft:air", null));
        assertTrue(YungPillars.acceptable("minecraft:iron_bars[north=true]", "minecraft:air", null));
        assertTrue(YungPillars.acceptable("minecraft:end_stone", "minecraft:air", null));
        assertTrue(YungPillars.acceptable("minecraft:bedrock", "minecraft:bedrock", null));
        assertFalse(YungPillars.acceptable("minecraft:diamond_block", "minecraft:air", null));
        assertFalse(YungPillars.acceptable("minecraft:chest[facing=north]", "minecraft:air", null));
        // 快照值也算「可覆盖」：一格里原本就是某种受管状态的另一个属性组合时不必中止。
        assertTrue(YungPillars.acceptable("minecraft:oak_planks", "minecraft:air",
                "minecraft:oak_planks"));
    }

    /** 蓝图里出现玩家方块时，{@code firstConflict} 必须给出具体坐标，而不是默默覆盖。 */
    @Test
    void aPlayerBlockInTheFootprintIsReportedAsAConflict() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, SEED, field);
        Map<Long, String> clean = new HashMap<>();
        for (YungPillars.Cell cell : blueprint.cells()) clean.put(cell.key(), cell.state());
        assertNull(YungPillarLifecycle.firstConflict(blueprint, clean), "干净世界不该报冲突");

        YungPillars.Cell sample = blueprint.cells().get(blueprint.cells().size() - 1);
        Map<Long, String> dirty = new HashMap<>(clean);
        dirty.put(sample.key(), "minecraft:diamond_block");
        String conflict = YungPillarLifecycle.firstConflict(blueprint, dirty);
        assertNotNull(conflict, "玩家方块没有被报成冲突");
        assertTrue(conflict.contains("diamond_block"), conflict);

        Map<Long, String> unread = new HashMap<>(clean);
        unread.remove(sample.key());
        assertNotNull(YungPillarLifecycle.firstConflict(blueprint, unread),
                "没读到的格必须当成冲突（不能当成空气顺手写掉）");
    }

    // --- 官方结构晶识别 -------------------------------------------------------------------------

    /** 阶段：初次（未击杀）与复活中的 SUMMONING_PILLARS 才认领；其它阶段一律不动。 */
    @Test
    void onlyTheInitialFightAndTheSummoningPillarsStageAreAccepted() {
        assertTrue(YungPillarLifecycle.acceptsStage(org.bukkit.boss.DragonBattle.RespawnPhase.NONE,
                false));
        assertFalse(YungPillarLifecycle.acceptsStage(org.bukkit.boss.DragonBattle.RespawnPhase.NONE,
                true));
        assertTrue(YungPillarLifecycle.acceptsStage(
                org.bukkit.boss.DragonBattle.RespawnPhase.SUMMONING_PILLARS, true));
        assertFalse(YungPillarLifecycle.acceptsStage(
                org.bukkit.boss.DragonBattle.RespawnPhase.START, true));
        assertFalse(YungPillarLifecycle.acceptsStage(
                org.bukkit.boss.DragonBattle.RespawnPhase.PREPARING_TO_SUMMON_PILLARS, true));
        assertFalse(YungPillarLifecycle.acceptsStage(
                org.bukkit.boss.DragonBattle.RespawnPhase.SUMMONING_DRAGON, true));
        assertFalse(YungPillarLifecycle.acceptsStage(
                org.bukkit.boss.DragonBattle.RespawnPhase.END, true));
    }

    /** 原版槽位只有一个：柱心 XZ + {@code height+1}。玩家放的晶不在那儿。 */
    @Test
    void onlyTheVanillaSlotIsRecognised() {
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(SEED);
        for (int index = 0; index < spikes.size(); index++) {
            EndSpikes.Spike spike = spikes.get(index);
            assertTrue(YungPillars.isVanillaCrystalSlot(spike, spike.centerX(), spike.height() + 1,
                    spike.centerZ()));
            assertEquals(index, YungPillarLifecycle.crystalSlotIndex(spikes, spike.centerX(),
                    spike.height() + 1, spike.centerZ()));
            YungPillars.Layout layout = YungPillars.layout(spike, SEED);
            // 新槽位不是「原版槽位」：已经在目标位置的晶不会被再搬一次。
            assertFalse(YungPillars.isVanillaCrystalSlot(spike, spike.centerX(), layout.crystalY(),
                    spike.centerZ()));
            // 别的高度、别的 XZ 都不认。
            assertFalse(YungPillars.isVanillaCrystalSlot(spike, spike.centerX(), spike.height(),
                    spike.centerZ()));
            assertFalse(YungPillars.isVanillaCrystalSlot(spike, spike.centerX() + 1,
                    spike.height() + 1, spike.centerZ()));
            assertEquals(-1, YungPillarLifecycle.crystalSlotIndex(spikes, spike.centerX(),
                    spike.height(), spike.centerZ()));
            assertEquals(-1, YungPillarLifecycle.spikeAt(spikes, spike.centerX() + 1,
                    spike.centerZ()));
            assertEquals(index, YungPillarLifecycle.spikeAt(spikes, spike.centerX(),
                    spike.centerZ()));
        }
    }

    /**
     * 同一个坐标上「真结构晶 / 玩家晶」必须被区分开：所有权来自 {@code generatedByDragonFight}（或已登记），
     * **不是**从坐标或材质推出来的。这一条就是「绝不搬玩家晶」的判据本身。
     */
    @Test
    void aPlayerCrystalAtTheVanillaSlotIsNeverOurs() {
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(SEED);
        EndSpikes.Spike spike = spikes.get(0);
        YungPillars.Layout layout = YungPillars.layout(spike, SEED);
        int bx = spike.centerX();
        int bz = spike.centerZ();
        // 同一坐标：槽位匹配对两者都成立，所以「坐标」区分不了它们。
        assertEquals(0, YungPillarLifecycle.crystalSlotIndex(spikes, bx, spike.height() + 1, bz));
        // 区分的唯一依据是实体标记 / 登记表。
        assertTrue(YungPillarLifecycle.owns(true, false), "原版柱晶（标记 true）应当被认领");
        assertFalse(YungPillarLifecycle.owns(false, false),
                "同一坐标的玩家晶（标记 false、未登记）被认领了");
        assertTrue(YungPillarLifecycle.owns(false, true), "已登记的同一颗晶应当继续被管");
        // 目标槽：重启后实体重新进世界时按新槽位反查；差一格就不认。
        assertEquals(0, YungPillarLifecycle.targetSlotIndex(spikes, SEED, bx, layout.crystalY(), bz));
        assertEquals(-1, YungPillarLifecycle.targetSlotIndex(spikes, SEED, bx,
                layout.crystalY() + 1, bz));
        // 玩家放在别的高度：连槽位都匹配不上。
        assertEquals(-1, YungPillarLifecycle.crystalSlotIndex(spikes, bx, spike.height() + 4, bz));
        // 桥接入口的 fail-closed：null 一律不是柱晶。
        //
        // 这里**故意不造 Bukkit 的 EnderCrystal 实例**：测试运行时的隔离设计不含 mucore-server，
        // 用 Proxy 实现 EnderCrystal 会去解析 Paper/NMS 父类型而缺类。所有权判据本身已经由上面
        // owns(...) 的三条断言覆盖，桥接实现（generatedByDragonFight）由桥接侧的编译期符号哨兵与
        // 它自己的用例负责。
        assertFalse(YungPillarLifecycle.pillarCrystal(null), "null 不许被当成柱晶");
    }

    // --- 已有世界的恢复（preview / restore 的纯逻辑） -------------------------------------------

    /** 旧布局蓝图：锚点 {@code height+1-offset} / {@code -67}，座位回到原版基岩座、晶体回到 height+1。 */
    @Test
    void theLegacyBlueprintUsesTheOldAnchors() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(spike, SEED, field);
            YungPillars.Layout layout = legacy.layout();
            int offset = YungPillars.crystalOffset(YungPillars.tier(spike.height()));
            assertEquals(spike.height() + 1 - offset, layout.topAnchorY(), "旧上段锚点");
            assertEquals(layout.topAnchorY() - YungPillars.BOTTOM_DROP, layout.bottomAnchorY(),
                    "旧下段锚点");
            assertEquals(spike.height(), layout.seatY(), "旧座位就是原版基岩座");
            assertEquals(spike.height() + 1, layout.crystalY(), "旧晶体就在原版槽位");
            // 新布局与旧布局的座位格从不同格，所以「一格属于哪套布局」是确定性的。
            assertNotEquals(YungPillars.layout(spike, SEED).seatY(), layout.seatY());
        }
    }

    /** 恢复要覆盖的并集：新模板格、旧模板格、原版 21³ 盒都在里面。 */
    @Test
    void theUnionFootprintCoversNewOldAndTheVanillaBox() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint now = YungPillars.blueprint(spike, SEED, field);
            YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(spike, SEED, field);
            java.util.Set<Long> union = YungPillarRecovery.unionKeys(spike, SEED, now, legacy);
        java.util.Set<Long> vanillaCells = YungPillars.vanillaCells(spike, field);
            assertTrue(union.contains(YungPillars.pack(spike.centerX(), now.layout().seatY(),
                    spike.centerZ())), "并集漏掉新座位");
            assertTrue(union.contains(YungPillars.pack(spike.centerX(), legacy.layout().seatY(),
                    spike.centerZ())), "并集漏掉旧座位");
            YungPillars.Bounds box = YungPillars.vanillaBox(spike);
            assertTrue(union.contains(YungPillars.pack(box.minX(), box.minY(), box.minZ())),
                    "并集漏掉原版 21³ 盒的角");
            assertTrue(union.contains(YungPillars.pack(box.maxX(), box.maxY(), box.maxZ())),
                    "并集漏掉原版 21³ 盒的角");
            for (YungPillars.Cell cell : now.cells()) {
                assertTrue(union.contains(cell.key()), "并集漏掉新模板格");
            }
        }
    }

    /** 分类：完整=OURS、缺一部分=PARTIAL、原版柱芯在=VANILLA、玩家方块=UNKNOWN_PLAYER。 */
    @Test
    void classificationDistinguishesOursPartialVanillaAndPlayerBlocks() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        YungPillars.Blueprint now = YungPillars.blueprint(spike, SEED, field);
        YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(spike, SEED, field);
        java.util.Set<Long> union = YungPillarRecovery.unionKeys(spike, SEED, now, legacy);
        java.util.Set<Long> vanillaCells = YungPillars.vanillaCells(spike, field);

        // 1) 全部按新蓝图写好 —— 空地图里的「没读到」也要显式填上
        Map<Long, String> ours = new HashMap<>();
        for (long key : union) ours.put(key, "minecraft:air");
        for (YungPillars.Cell cell : now.cells()) ours.put(cell.key(), cell.state());
        YungPillarRecovery.PillarReport report = YungPillarRecovery.classify(0, spike, now, legacy, vanillaCells, ours);
        assertEquals(YungPillarRecovery.Status.OURS, report.status(), describe(report));
        assertEquals(report.templateCells(), report.matched());
        assertTrue(report.adoptable());

        // 2) 挖掉一小部分模板格（保留 ≥ 3/4）—— PARTIAL
        Map<Long, String> partial = new HashMap<>(ours);
        int removed = 0;
        for (YungPillars.Cell cell : now.cells()) {
            if (cell.origin() != YungPillars.Origin.TEMPLATE
                    || "minecraft:air".equals(cell.state())) {
                continue;
            }
            if (removed++ % 10 == 0) partial.put(cell.key(), "minecraft:air");
        }
        YungPillarRecovery.PillarReport half = YungPillarRecovery.classify(0, spike, now, legacy, vanillaCells, partial);
        assertEquals(YungPillarRecovery.Status.PARTIAL, half.status(), describe(half));
        assertTrue(half.adoptable());

        // 3) 原版柱芯在、新模板几乎没有 —— VANILLA（不该被 adopt）
        Map<Long, String> vanilla = new HashMap<>();
        for (long key : union) vanilla.put(key, "minecraft:air");
        int r = spike.radius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                for (int y = 0; y < spike.height(); y++) {
                    vanilla.put(YungPillars.pack(spike.centerX() + dx, y, spike.centerZ() + dz),
                            "minecraft:obsidian");
                }
            }
        }
        YungPillarRecovery.PillarReport vanillaReport =
                YungPillarRecovery.classify(0, spike, now, legacy, vanillaCells, vanilla);
        assertEquals(YungPillarRecovery.Status.VANILLA, vanillaReport.status(),
                describe(vanillaReport));
        assertFalse(vanillaReport.adoptable(), "原版柱不许被 adopt");

        // 4) 足迹里出现玩家方块 —— UNKNOWN_PLAYER，一格都不许写
        Map<Long, String> dirty = new HashMap<>(ours);
        YungPillars.Cell sample = now.cells().get(now.cells().size() - 1);
        dirty.put(sample.key(), "minecraft:diamond_block");
        YungPillarRecovery.PillarReport dirtyReport =
                YungPillarRecovery.classify(0, spike, now, legacy, vanillaCells, dirty);
        assertEquals(YungPillarRecovery.Status.UNKNOWN_PLAYER, dirtyReport.status());
        assertFalse(dirtyReport.adoptable());
        assertTrue(dirtyReport.foreign() > 0);

        // 5) 全空气（区块被清空）—— NO_EVIDENCE，缺证就得说缺证
        Map<Long, String> empty = new HashMap<>();
        for (long key : union) empty.put(key, "minecraft:air");
        assertEquals(YungPillarRecovery.Status.NO_EVIDENCE,
                YungPillarRecovery.classify(0, spike, now, legacy, vanillaCells, empty).status());
    }

    /**
     * 旧布局柱（0.1.26）：**不能一票拒绝**，要分成 {@code LEGACY} 且可恢复。
     *
     * <p>这就是「当前已有世界必须有可用恢复路径」的判据：旧蓝图足迹完整 → 可接管；
     * 完全原版（两套蓝图都不命中）才拒绝。
     */
    @Test
    void aCompleteLegacyPillarIsMigratable() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        YungPillars.Blueprint now = YungPillars.blueprint(spike, SEED, field);
        YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(spike, SEED, field);
        java.util.Set<Long> union = YungPillarRecovery.unionKeys(spike, SEED, now, legacy);
        java.util.Set<Long> vanillaCells = YungPillars.vanillaCells(spike, field);

        Map<Long, String> oldWorld = new HashMap<>();
        for (long key : union) oldWorld.put(key, "minecraft:air");
        for (YungPillars.Cell cell : legacy.cells()) oldWorld.put(cell.key(), cell.state());
        YungPillarRecovery.PillarReport report =
                YungPillarRecovery.classify(0, spike, now, legacy, vanillaCells, oldWorld);
        assertEquals(YungPillarRecovery.Status.LEGACY, report.status(), describe(report));
        assertTrue(report.legacy(), "旧布局柱应该被标记为 legacy");
        assertTrue(report.adoptable(), "已生成的 0.1.26 柱必须可以被 restore 迁移");

        Map<Long, String> vanilla = new HashMap<>();
        for (long key : union) vanilla.put(key, "minecraft:air");
        int r = spike.radius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                for (int y = 0; y < spike.height(); y++) {
                    vanilla.put(YungPillars.pack(spike.centerX() + dx, y, spike.centerZ() + dz),
                            "minecraft:obsidian");
                }
            }
        }
        assertFalse(YungPillarRecovery.classify(0, spike, now, legacy, vanillaCells, vanilla)
                .adoptable(), "完全原版且无证据的柱不该被接管");
    }

    /** 迁移蓝图：包含全部新模板格 + 旧模板残件（按地形还原），无重复。 */
    @Test
    void theMigrationBlueprintCarriesTheLegacyRemnants() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            YungPillars.Blueprint now = YungPillars.blueprint(spike, SEED, field);
            YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(spike, SEED, field);
            YungPillars.Blueprint migration = YungPillars.migrationBlueprint(spike, SEED, field);
            java.util.Set<Long> vanillaCells = YungPillars.vanillaCells(spike, field);
            java.util.Set<Long> nowKeys = new java.util.HashSet<>();
            Map<Long, YungPillars.Cell> byKey = new HashMap<>();
            for (YungPillars.Cell cell : migration.cells()) {
                assertNull(byKey.put(cell.key(), cell), "迁移蓝图出现重复坐标");
            }
            for (YungPillars.Cell cell : now.cells()) {
                nowKeys.add(cell.key());
                assertEquals(cell.state(), byKey.get(cell.key()).state(), "新模板格被改写了");
            }
            assertTrue(migration.migrationCells() > 0, "没有迁移残件格，旧柱体将留在原地");
            for (YungPillars.Cell cell : legacy.cells()) {
                if (nowKeys.contains(cell.key()) || vanillaCells.contains(cell.key())) continue;
                YungPillars.Cell migrated = byKey.get(cell.key());
                assertNotNull(migrated, "旧模板残件没有被迁移蓝图覆盖");
                assertEquals(YungPillars.Origin.RESTORE, migrated.origin());
                assertEquals(field.isSolid(cell.x(), cell.y(), cell.z())
                                ? "minecraft:end_stone" : "minecraft:air", migrated.state(),
                        "残件应当按地形还原（实心→末地石，虚空→空气）");
            }
        }
    }

    /** 并集/迁移必须覆盖 y&lt;0 的原版柱芯段（MU 从 level.getMinY() 起写，运行时传真实值）。 */
    @Test
    void theUnionCoversTheDeepVanillaShaftDownToTheWorldFloor() {
        IslandField field = new IslandField(SEED);
        int worldMinY = -64;
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        YungPillars.Blueprint now = YungPillars.blueprint(spike, SEED, field);
        YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(spike, SEED, field);
        java.util.Set<Long> union = YungPillarRecovery.unionKeys(spike, SEED, now, legacy, worldMinY);
        assertTrue(union.contains(YungPillars.pack(spike.centerX(), worldMinY, spike.centerZ())),
                "并集漏掉世界底层那一格");
        assertTrue(union.contains(YungPillars.pack(spike.centerX(), -1, spike.centerZ())),
                "并集漏掉 y=-1 的原版柱芯");
        // 自动重铺的蓝图**不**包含这一段（不能把 81x64 格拉进每轮冲突判定）。
        assertNull(now.cells().stream()
                .filter(c -> c.y() < 0)
                .findAny().orElse(null), "蓝图不该包含 y<0 的格");
        // 迁移蓝图包含（按地形还原），旧柱体的深段才会被收拾干净。
        YungPillars.Blueprint migration = YungPillars.migrationBlueprint(spike, SEED, field, worldMinY);
        YungPillars.Cell deep = migration.cells().stream()
                .filter(c -> c.y() == worldMinY && c.x() == spike.centerX()
                        && c.z() == spike.centerZ())
                .findFirst().orElse(null);
        assertNotNull(deep, "迁移蓝图漏掉世界底层的原版柱芯");
        assertEquals(YungPillars.Origin.RESTORE, deep.origin());
        // 键里的 Y 必须能还原成负数：只取低 20 位会把 -64 解成 1048512。
        assertEquals(worldMinY, YungPillars.unpackY(deep.key()), "深段键的 Y 还原错了");
        assertEquals(spike.centerX(), YungPillars.unpackX(deep.key()));
        assertEquals(spike.centerZ(), YungPillars.unpackZ(deep.key()));
        int deepCells = 0;
        for (YungPillars.Cell cell : migration.cells()) {
            if (cell.y() >= 0) continue;
            assertEquals(cell.y(), YungPillars.unpackY(cell.key()), "深段键还原出了非负 Y");
            deepCells++;
        }
        assertTrue(deepCells > 1, "迁移蓝图没有覆盖整段深柱芯");
    }

    /** 紧凑键是 22/20/22 位有符号掩码：负 Y 与正负 X/Z 都必须原样往返，互不串位。 */
    @Test
    void theCompactKeyRoundTripsSignedCoordinates() {
        // -64 是被审查抓到的那个值（曾解成 1048512）；-1 是掩码边界，319 是末地上限附近。
        int[] ys = {-64, -1, 0, 3, 70, 319};
        int[] xs = {0, 7, -7, 1_000_000, -1_000_000, 2_097_151, -2_097_152};
        int[] zs = {0, 3, -3, 2_097_151, -2_097_152, 1_234_567, -1_234_567};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    long key = YungPillars.pack(x, y, z);
                    String where = x + "," + y + "," + z;
                    assertEquals(x, YungPillars.unpackX(key), "X 往返失败：" + where);
                    assertEquals(y, YungPillars.unpackY(key), "Y 往返失败：" + where);
                    assertEquals(z, YungPillars.unpackZ(key), "Z 往返失败：" + where);
                }
            }
        }
    }

    /** 待清除旧晶的队列：去重、先进先出、有界（两轮复活不会乱序）。 */
    @Test
    void staleCrystalsAreRetiredInOrderAndBounded() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        java.util.List<UUID> list = YungPillarLifecycle.staleAfterAdd(List.of(), a, 4);
        assertEquals(List.of(a), list);
        list = YungPillarLifecycle.staleAfterAdd(list, b, 4);
        assertEquals(List.of(a, b), list, "第二轮必须排在第一轮之后");
        list = YungPillarLifecycle.staleAfterAdd(list, a, 4);
        assertEquals(List.of(a, b), list, "重复登记不能改顺序");
        list = YungPillarLifecycle.staleAfterAdd(list, c, 2);
        assertEquals(List.of(b, c), list, "超出上限时丢最旧的");
        assertEquals(List.of(a), YungPillarLifecycle.staleAfterAdd(List.of(), a, 0),
                "limit 至少保留一个");
    }

    /**
     * 令牌：绑定世界 + 种子 + 布局版本 + **每根柱的检查结果** + 已 built 集合。
     *
     * <p>只绑下标是不够的：preview 与 restore 之间玩家改了柱体、只要分类结果仍是同一组下标，旧 token
     * 就会通过。这里逐项证明内容变了 token 就变。
     */
    @Test
    void theAdoptTokenBindsContentAndIsDeterministic() {
        UUID worldId = UUID.randomUUID();
        YungPillarRecovery.PillarReport a = report(1, YungPillarRecovery.Status.OURS, 100, 0);
        YungPillarRecovery.PillarReport b = report(3, YungPillarRecovery.Status.PARTIAL, 90, 10);
        java.util.Set<Integer> built = java.util.Set.of(0);
        String base = YungPillarRecovery.token(worldId, SEED, YungPillarStore.LAYOUT,
                List.of(a, b), built);
        assertEquals(base, YungPillarRecovery.token(worldId, SEED, YungPillarStore.LAYOUT,
                List.of(a, b), built), "同输入必须恒定");
        assertEquals(base, YungPillarRecovery.token(worldId, SEED, YungPillarStore.LAYOUT,
                List.of(b, a), built), "顺序不同（同一组柱）也必须是同一个 token");

        YungPillarRecovery.PillarReport changedHit =
                report(3, YungPillarRecovery.Status.PARTIAL, 89, 10);
        assertNotEquals(base, YungPillarRecovery.token(worldId, SEED, YungPillarStore.LAYOUT,
                List.of(a, changedHit), built), "柱体命中数变了 token 必须变");
        assertNotEquals(base, YungPillarRecovery.token(worldId, SEED, YungPillarStore.LAYOUT,
                List.of(a, b), java.util.Set.of()), "built 集合变了 token 必须变");
        assertNotEquals(base, YungPillarRecovery.token(UUID.randomUUID(), SEED,
                YungPillarStore.LAYOUT, List.of(a, b), built), "世界变了 token 必须变");
        assertNotEquals(base, YungPillarRecovery.token(worldId, SEED + 1,
                YungPillarStore.LAYOUT, List.of(a, b), built), "种子变了 token 必须变");
        assertNotEquals(base, YungPillarRecovery.token(worldId, SEED, 1,
                List.of(a, b), built), "布局版本变了 token 必须变");
        assertEquals(
                YungPillarRecovery.token(worldId, SEED, YungPillarStore.LAYOUT, List.of(),
                        java.util.Set.of()),
                YungPillarRecovery.token(worldId, SEED, YungPillarStore.LAYOUT, List.of(),
                        java.util.Set.of()));
    }

    /** 数量相同不代表同一现场：交换两个缺口或只替换官方 UUID 都要重新确认。 */
    @Test
    void productionTokenBindsExactStatesAndCrystalIdentity() {
        UUID world = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        YungPillarStore.Registry registry = YungPillarStore.Registry.empty().withCrystal(0, first);
        List<YungPillarRecovery.PillarReport> reports = List.of(report(0,
                YungPillarRecovery.Status.PARTIAL, 90, 0));
        Map<Long, String> states = Map.of(1L, "minecraft:air", 2L, "minecraft:obsidian");
        String token = YungPillarRecovery.token(world, SEED, registry, reports, states);
        assertNotEquals(token, YungPillarRecovery.token(world, SEED, registry, reports,
                Map.of(1L, "minecraft:obsidian", 2L, "minecraft:air")));
        assertNotEquals(token, YungPillarRecovery.token(world, SEED,
                registry.withCrystal(0, UUID.randomUUID()), reports, states));
        assertNotEquals(token, YungPillarRecovery.token(world, SEED,
                registry.withBuilt(0), reports, states));
        java.util.Map<Long, String> reversed = new java.util.LinkedHashMap<>();
        reversed.put(2L, "minecraft:obsidian");
        reversed.put(1L, "minecraft:air");
        assertEquals(token, YungPillarRecovery.token(world, SEED, registry, reports, reversed));
    }

    /** 旧布局迁移一根不能把其它九根的旧 built 当成已迁移。 */
    @Test
    void layoutUpgradeMarksOnlyThePillarActuallyVerified() {
        YungPillarStore.Registry legacy = new YungPillarStore.Registry(1, Map.of(),
                java.util.Set.of(0, 1, 2));
        YungPillarStore.Registry migrated = legacy.withCurrentBuilt(1);
        assertTrue(migrated.currentLayout());
        assertEquals(java.util.Set.of(1), migrated.built());
        assertEquals(java.util.Set.of(1, 3), migrated.withCurrentBuilt(3).built());
    }

    /** Folia 拒绝 runDelayed(0)，所以所有立即请求都必须规范成下一 tick。 */
    @Test
    void immediateRegionWorkNeverUsesAnInvalidZeroDelay() {
        assertEquals(1L, YungPillarLifecycle.regionDelay(0L));
        assertEquals(1L, YungPillarLifecycle.regionDelay(-1L));
        assertEquals(20L, YungPillarLifecycle.regionDelay(20L));
    }

    /** 整根 19 格宽的回读必须拆区块，不能在柱心线程直接读取邻区块。 */
    @Test
    void generatedPillarReadbackIsPartitionedIntoOwnedChunkBatches() {
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, SEED, new IslandField(SEED));
        List<YungPillarLifecycle.Work> work = YungPillarLifecycle.workOf(blueprint);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.Set<String> chunks = new java.util.HashSet<>();
        for (YungPillarLifecycle.Work batch : work) {
            chunks.add(batch.chunkX() + "," + batch.chunkZ());
            assertTrue(batch.keys().size() <= 1024);
            for (long key : batch.keys()) {
                assertEquals(batch.chunkX(), YungPillars.unpackX(key) >> 4);
                assertEquals(batch.chunkZ(), YungPillars.unpackZ(key) >> 4);
                assertTrue(seen.add(key));
            }
        }
        assertTrue(chunks.size() > 1);
        assertEquals(blueprint.cells().size(), seen.size());
    }

    private static YungPillarRecovery.PillarReport report(int index,
                                                         YungPillarRecovery.Status status,
                                                         int matched, int foreign) {
        return new YungPillarRecovery.PillarReport(index, 42, 0, 100, 9, status, 5000, matched,
                0, 5000 - matched, foreign, 0, 107, 108);
    }

    private static String describe(YungPillarRecovery.PillarReport report) {
        return report.status() + " matched=" + report.matched() + "/" + report.templateCells()
                + " missing=" + report.missing() + " mismatched=" + report.mismatched()
                + " foreign=" + report.foreign() + " legacy=" + report.legacyMatched();
    }

    // --- 持久化 ---------------------------------------------------------------------------------

    /** 登记表往返，复活两次后记的是最新的那颗晶，built 标记保留。 */
    @Test
    void theRegistryRoundTripsAndKeepsTheLatestCrystal() throws Exception {
        Path dir = Files.createTempDirectory("betterend-pillars");
        UUID worldId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        YungPillarStore.Registry registry = YungPillarStore.Registry.empty()
                .withCrystal(0, first)
                .withCrystal(1, second)
                .withBuilt(0);
        YungPillarStore.writeRegistry(dir, worldId, registry);
        YungPillarStore.Registry read = YungPillarStore.readRegistry(dir, worldId);
        assertEquals(YungPillarStore.LAYOUT, read.layout());
        assertTrue(read.currentLayout());
        assertEquals(first, read.crystal(0));
        assertEquals(second, read.crystal(1));
        assertTrue(read.built().contains(0));
        assertFalse(read.built().contains(1));

        // 第二次复活：同一根柱换一颗新晶（旧实体已消失），built 不会被清掉。
        YungPillarStore.Registry secondFight = read.withCrystal(0, second).withBuilt(1);
        assertEquals(second, secondFight.crystal(0), "复活两次后登记的不是最新的那颗晶");
        assertTrue(secondFight.built().contains(0));
        assertTrue(secondFight.built().contains(1));
        assertNotEquals(first, secondFight.crystal(0));
    }

    /** 完成标记可回滚：写盘失败不能留下「假完成」，且重复标记是幂等的。 */
    @Test
    void builtMarksCanBeRolledBackAndAreIdempotent() {
        YungPillarStore.Registry registry = YungPillarStore.Registry.empty();
        assertEquals(YungPillarStore.LAYOUT, registry.layout());
        YungPillarStore.Registry once = registry.withBuilt(3);
        YungPillarStore.Registry twice = once.withBuilt(3);
        assertEquals(once.built(), twice.built(), "重复标记不应改变内容");
        assertTrue(once.built().contains(3));
        YungPillarStore.Registry rolled = once.withoutBuilt(3);
        assertFalse(rolled.built().contains(3), "回滚后不能还显示已完成");
        assertEquals(rolled.built(), registry.built());
        assertEquals(once.withoutBuilt(9).built(), once.built(), "回滚不存在的下标应为空操作");
    }

    /** 登记表坏了就明确报错，绝不当成空表（否则会失去「这颗晶是我们的」证据）。 */
    @Test
    void aCorruptRegistryIsRefusedInsteadOfTreatedAsEmpty() throws Exception {
        Path dir = Files.createTempDirectory("betterend-pillars-bad");
        UUID worldId = UUID.randomUUID();
        Path file = YungPillarStore.registryFile(dir, worldId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not-a-registry\nlayout 2\n");
        assertThrows(java.io.IOException.class, () -> YungPillarStore.readRegistry(dir, worldId));

        Path unknown = YungPillarStore.registryFile(dir, UUID.randomUUID());
        Files.createDirectories(unknown.getParent());
        Files.writeString(unknown, "betterend-pillars 1\nlayout 2\nweird 1\n");
        assertThrows(java.io.IOException.class, () -> YungPillarStore.readRegistry(dir,
                UUID.fromString(unknown.getFileName().toString().replace(".txt", ""))));
    }

    /** 快照往返：重铺前那一份原状态必须能读回来，坐标与状态一个不差。 */
    @Test
    void theSnapshotRoundTrips() throws Exception {
        Path dir = Files.createTempDirectory("betterend-snapshot");
        UUID worldId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        List<YungPillars.Cell> cells = List.of(
                new YungPillars.Cell(42, 91, 0, "minecraft:bedrock", YungPillars.Origin.VANILLA),
                new YungPillars.Cell(42, 92, 0, "minecraft:fire[age=1]", YungPillars.Origin.VANILLA),
                new YungPillars.Cell(-13, 70, 39,
                        "minecraft:end_stone_brick_stairs[facing=north,half=top]",
                        YungPillars.Origin.TEMPLATE));
        Path file = YungPillarStore.writeSnapshot(dir, worldId, jobId, cells);
        YungPillarStore.Snapshot snapshot = YungPillarStore.readSnapshot(file);
        assertEquals(worldId, snapshot.worldId());
        assertEquals(jobId, snapshot.jobId());
        assertEquals(cells.size(), snapshot.cells().size());
        for (int i = 0; i < cells.size(); i++) {
            YungPillars.Cell expected = cells.get(i);
            YungPillars.Cell actual = snapshot.cells().get(i);
            assertEquals(expected.x(), actual.x());
            assertEquals(expected.y(), actual.y());
            assertEquals(expected.z(), actual.z());
            assertEquals(expected.state(), actual.state());
        }
    }

    // --- fixtures -------------------------------------------------------------------------------

    private static int localMinY(String templateName, int rotation) {
        PillarTemplate template = PillarTemplate.load(templateName).rotated(rotation);
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < template.count(); i++) {
            min = Math.min(min, template.y(i));
        }
        return min;
    }

    private static void apply(YungPillars.Blueprint blueprint, FakeArea area) {
        for (YungPillars.Cell cell : blueprint.cells()) {
            String current = area.blocks.get(cell.key());
            if (current == null) current = "minecraft:air";
            if (!YungPillars.shouldWrite(cell, current)) continue;
            assertTrue(YungPillars.acceptable(current, cell.state(), null),
                    "重铺拒绝写 " + cell.x() + "," + cell.y() + "," + cell.z());
            area.blocks.put(cell.key(), cell.state());
        }
    }

    /** 原版按旧 H 重建柱芯与笼子（复活流程的最后一步）。 */
    private static void fillVanillaShaft(FakeArea area, EndSpikes.Spike spike) {
        int r = spike.radius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                for (int y = 0; y < spike.height(); y++) {
                    area.blocks.put(YungPillars.pack(spike.centerX() + dx, y, spike.centerZ() + dz),
                            "minecraft:obsidian");
                }
            }
        }
        area.blocks.put(YungPillars.pack(spike.centerX(), spike.height(), spike.centerZ()),
                "minecraft:bedrock");
        area.blocks.put(YungPillars.pack(spike.centerX(), spike.height() + 1, spike.centerZ()),
                "minecraft:fire");
    }

    /** 生成期：跑一趟 {@link YungPillars#build}（柱心 ±1 区块），返回「格 → 状态」。 */
    private static Map<Long, String> populate(EndSpikes.Spike spike, IslandField field, long seed) {
        Map<Long, String> blocks = new HashMap<>();
        int centerChunkX = spike.centerX() >> 4;
        int centerChunkZ = spike.centerZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                YungPillars.build(region(chunkX, chunkZ, blocks), field, END_STONE, seed, chunkX,
                        chunkZ, YungPillarBlueprintTest::state);
            }
        }
        return blocks;
    }

    private static LimitedRegion region(int chunkX, int chunkZ, Map<Long, String> blocks) {
        return (LimitedRegion) Proxy.newProxyInstance(LimitedRegion.class.getClassLoader(),
                new Class<?>[]{LimitedRegion.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isInRegion" -> {
                        int x = (Integer) args[0];
                        int y = (Integer) args[1];
                        int z = (Integer) args[2];
                        yield y >= WORLD_MIN_Y && y <= WORLD_MAX_Y
                                && Math.abs((x >> 4) - chunkX) <= 1
                                && Math.abs((z >> 4) - chunkZ) <= 1;
                    }
                    case "setBlockData" -> {
                        int x = (Integer) args[0];
                        int y = (Integer) args[1];
                        int z = (Integer) args[2];
                        blocks.put(YungPillars.pack(x, y, z), ((BlockData) args[3]).getAsString());
                        yield null;
                    }
                    case "getType" -> throw new AssertionError("YungPillars 不许读方块");
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeRegion";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 运行期的方块面：一个内存表就够（重铺只读写状态串）。 */
    private static final class FakeArea {
        final Map<Long, String> blocks = new LinkedHashMap<>();

        void fillAir(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        blocks.put(YungPillars.pack(x, y, z), "minecraft:air");
                    }
                }
            }
        }
    }

    private static final Map<String, BlockData> STUBS = new HashMap<>();

    private static synchronized BlockData state(String blockState) {
        return STUBS.computeIfAbsent(blockState, YungPillarBlueprintTest::stub);
    }

    private static BlockData stub(String name) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAsString", "toString" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final BlockData END_STONE = stub("minecraft:end_stone");
}
