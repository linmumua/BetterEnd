package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.terrain.IslandField;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link YungPillars} + {@link PillarTemplate}：36 个模板、档位规则、四向旋转、锚点、分块重放。
 *
 * <p>这一组用例钉的都是「错了就毁世界 / 毁龙战」的东西，而不是「长得像不像」：
 * <ul>
 *   <li><b>36 个模板都在</b>，尺寸是放置算术假设的 19×69×19 与 19×67×19；</li>
 *   <li><b>档位与水晶偏移</b>照抄上游 {@code chooseTemplates} 与 {@code EndSpikeMixin} 的表；</li>
 *   <li><b>锚点</b>是上游的 {@code upperBaseY=70} / {@code lowerBaseY=3}，座位 = {@code 70+offset-1}、
 *       水晶槽 = {@code 70+offset}；原版旧座与那格火必须被收尾；</li>
 *   <li><b>旋转</b>位置与方块朝向同步转，且可逆；</li>
 *   <li><b>上下两段严丝合缝</b>（{@code bottom.maxY + 1 == top.minY}）；</li>
 *   <li><b>不留占位块</b>：orange / magenta / purple / gray 一个都不许作为最终方块留在世界里；</li>
 *   <li><b>不挖岛</b>：地形说实心的格子永远不许被写成空气；</li>
 *   <li><b>水晶槽</b>：新槽（{@code 70+offset}）从来没被写过、没被围死，可以直接够到；</li>
 *   <li><b>原版柱芯与笼子被清理</b>：原版写过的每一格都被重写，guarded 柱的笼子最后是空气；</li>
 *   <li><b>邻区块重放</b>：更晚的装饰步骤擦掉一格之后，跑得更晚的那个 populator 必须把它写回来
 *       （这一条是「只写自己区块」方案过不了的，见 {@link YungPillars} 类注释的收敛说明）；</li>
 *   <li><b>不越界、幂等、与区块顺序无关、不读方块</b>（fake region 的 {@code getType} 直接报错）。</li>
 * </ul>
 *
 * <p>方块用 {@link Proxy} 打桩：{@code Material} 注册表要服务端，而本模块的单元测试没有服务端；
 * 这一趟只把对象透传下去，所以「可区分 + 有名字」就够。{@link IslandField} 是真家伙，因为地形是
 * 「这一格原本是岛体还是虚空」的唯一依据。
 */
class YungPillarsTest {

    private static final long SEED = 42L;
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 255;
    private static final BlockData END_STONE = stub("minecraft:end_stone");

    // --- 模板 -----------------------------------------------------------------------------------

    /**
     * 36 个模板全部能读、尺寸与放置算术一致。{@code initial} 那 9 个虽然不参与放置（见
     * {@link YungPillars} 的类注释），也必须在包里、必须能读——资源缺一个就是启动失败，不能悄悄少一
     * 根柱子。
     */
    @Test
    void allThirtySixTemplatesLoadWithTheSizesThePlacementAssumes() {
        for (int tier = 1; tier <= 9; tier++) {
            for (String type : new String[]{"guarded", "broken", "initial"}) {
                PillarTemplate top = PillarTemplate.load("pillar_" + type + "_" + tier);
                assertEquals(19, top.sizeX, "上段宽 " + type + "_" + tier);
                assertEquals(19, top.sizeZ, "上段长 " + type + "_" + tier);
                assertEquals(YungPillars.TOP_HEIGHT, top.sizeY, "上段高 " + type + "_" + tier);
                assertTrue(top.count() > 500, type + "_" + tier + " 只读到 " + top.count() + " 个方块");
            }
            PillarTemplate bottom = PillarTemplate.load("pillar_bottom_" + tier);
            assertEquals(19, bottom.sizeX, "下段宽 " + tier);
            assertEquals(19, bottom.sizeZ, "下段长 " + tier);
            assertEquals(YungPillars.BOTTOM_HEIGHT, bottom.sizeY, "下段高 " + tier);
            assertTrue(bottom.count() > 500, "bottom_" + tier + " 只读到 " + bottom.count() + " 个方块");
        }
    }

    /** 模板名是白名单，且只从 jar 内固定目录读：任何「按路径读」的入口都不存在。 */
    @Test
    void templateNamesAreWhitelistedAndNoPathIsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> PillarTemplate.load("../../pillar_guarded_1"));
        assertThrows(IllegalArgumentException.class, () -> PillarTemplate.load("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> PillarTemplate.load("pillar_guarded_10"));
        assertThrows(IllegalArgumentException.class, () -> PillarTemplate.load("pillar_guarded_0"));
        assertThrows(IllegalArgumentException.class, () -> PillarTemplate.load("pillar_guarded_1.nbt"));
    }

    /**
     * 座位标记：{@code gray_concrete} 只有 broken / guarded 才有，各 1 块，位置在柱心列的
     * {@code (9, offset-1, 9)}；水晶格 {@code (9, offset, 9)} 必须空。这是锚点方案的全部依据。
     */
    @Test
    void everyGuardedAndBrokenTemplateHasExactlyOneSeatUnderAnEmptyCrystalHole() {
        for (int tier = 1; tier <= 9; tier++) {
            int offset = YungPillars.crystalOffset(tier);
            for (String type : new String[]{"guarded", "broken"}) {
                PillarTemplate template = PillarTemplate.load("pillar_" + type + "_" + tier);
                int seats = 0;
                for (int i = 0; i < template.count(); i++) {
                    if (template.kind(i) != PillarTemplate.Kind.SEAT) continue;
                    seats++;
                    assertEquals(9, template.x(i), type + "_" + tier + " 座位不在中轴");
                    assertEquals(9, template.z(i), type + "_" + tier + " 座位不在中轴");
                    assertEquals(offset - 1, template.y(i), type + "_" + tier + " 座位高度不是 offset-1");
                }
                assertEquals(1, seats, type + "_" + tier + " 的座位标记不是唯一一块");
                assertNull(cellAt(template, 9, offset, 9),
                        type + "_" + tier + " 的水晶格里有方块，水晶会被埋");
                for (int turns = 0; turns < 4; turns++) {
                    assertNull(cellAt(template.rotated(turns), 9, offset, 9),
                            type + "_" + tier + " 转到 " + turns + " 个 90° 后水晶格被占");
                }
            }
        }
    }

    /**
     * 目标水晶洞的**净空**（逐档 × guarded/broken × 四向旋转，共 72 个组合）。
     *
     * <p>{@code EndCrystal} 的碰撞箱是 2×2×2、中心在 {@code (cx+0.5, crystalY, cz+0.5)}，所以它压到的方块
     * 只有 {@code dx,dz ∈ {-1,0,1}} 这九列里的 {@code crystalY} / {@code crystalY+1} 两层。九个直邻位必须
     * 全空；四个**对角**允许、且只允许 upstream 自己的守卫栏杆（{@code iron_bars}，guarded 的 1/4/7 档四角
     * 各一根，见 {@code pillar_guarded_1/4/7.nbt} 的 {@code (8,offset,8)} 一带），因为那不是埋晶，而是把晶体
     * 围起来的笼子。
     *
     * <p>这条用例故意**不**要求 3×3 全空：运行期的净空核验如果照抄「3×3 必须 air」，就会把 upstream 的
     * 笼子判成缺陷，进而去挖玩家/结构方块。也不向下多看一层（{@code crystalY-1} 是模板的基岩座位，本来就该实心）。
     */
    @Test
    void theCrystalNeighbourhoodIsClearExceptForTheUpstreamCageCorners() {
        int cageCorners = 0;
        for (int tier = 1; tier <= 9; tier++) {
            int offset = YungPillars.crystalOffset(tier);
            for (String type : new String[]{"guarded", "broken"}) {
                for (int turns = 0; turns < 4; turns++) {
                    PillarTemplate template = PillarTemplate.load("pillar_" + type + "_" + tier)
                            .rotated(turns);
                    String where = type + "_" + tier + " 转到 " + turns + " 个 90°";
                    for (int dy = 0; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int index = indexAt(template, 9 + dx, offset + dy, 9 + dz);
                                if (index < 0) {
                                    continue;
                                }
                                String state = template.state(index);
                                boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                                assertTrue(corner, where + " 在晶体的正/侧邻位 (dx=" + dx + ",dy="
                                        + dy + ",dz=" + dz + ") 放了方块 " + state
                                        + "，晶体搬进去就会卡住");
                                assertTrue(state.startsWith("minecraft:iron_bars"),
                                        where + " 在晶体对角放了非栏杆方块 " + state
                                                + "，碰撞箱角落被实心方块占住");
                                cageCorners++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(cageCorners > 0,
                "没有任何对角栏杆——「只放行铁栏杆」这一支成了空转，判据就退化成 3×3 全空");
    }

    /** {@code initial} 不是另一种外观，它是「还没有水晶」的流程；本轮不用它，所以它不该有座位。 */
    @Test
    void initialTemplatesHaveNoSeatBecauseTheyAreNotUsed() {
        for (int tier = 1; tier <= 9; tier++) {
            PillarTemplate template = PillarTemplate.load("pillar_initial_" + tier);
            for (int i = 0; i < template.count(); i++) {
                assertNotEquals(PillarTemplate.Kind.SEAT, template.kind(i),
                        "initial_" + tier + " 里出现了座位标记——那说明它其实能当重生外观用，结论要重写");
            }
        }
    }

    // --- 规则 -----------------------------------------------------------------------------------

    /** {@code tier = min((height-73)/3, 9)} 与 {@code EndSpikeMixin} 的水晶偏移表。 */
    @Test
    void tierAndCrystalOffsetMatchTheOfficialRule() {
        int[] heights = {76, 79, 82, 85, 88, 91, 94, 97, 100, 103};
        int[] tiers = {1, 2, 3, 4, 5, 6, 7, 8, 9, 9};
        int[] offsets = {22, 22, 26, 27, 27, 32, 32, 32, 38, 38};
        for (int i = 0; i < heights.length; i++) {
            assertEquals(heights[i], 76 + 3 * i, "原版柱高公式");
            assertEquals(tiers[i], YungPillars.tier(heights[i]), "档位 " + heights[i]);
            assertEquals(offsets[i], YungPillars.crystalOffset(tiers[i]), "水晶偏移 " + heights[i]);
        }
        assertEquals(9, YungPillars.tier(76 + 3 * 9), "第 10 档没有模板，按 9 取");
        assertThrows(IllegalArgumentException.class, () -> YungPillars.crystalOffset(0));
        assertThrows(IllegalArgumentException.class, () -> YungPillars.crystalOffset(10));
    }

    /** 旋转公式就是上游的 {@code new Random(seed ^ x ^ z).nextInt(4)}，且同一根柱子恒定。 */
    @Test
    void rotationIsTheOfficialSeedXorDraw() {
        for (long seed : new long[]{SEED, 0L, -1L, 123456789L, Long.MIN_VALUE}) {
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                int expected = new java.util.Random(seed ^ (long) spike.centerX() ^ (long) spike.centerZ())
                        .nextInt(4);
                assertEquals(expected, YungPillars.rotation(seed, spike.centerX(), spike.centerZ()));
            }
        }
    }

    /** 重生外观只有 guarded / broken 两种：{@code initial} 永不上场。 */
    @Test
    void onlyGuardedAndBrokenAreUsedForRegrownPillars() {
        for (long seed : new long[]{SEED, 7L, -99L}) {
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                String type = YungPillars.type(spike);
                assertEquals(spike.guarded() ? "guarded" : "broken", type);
                assertNotEquals("initial", type);
            }
        }
    }

    /**
     * 锚点（新布局）：上段 70、下段 3、座位 = {@code 70+offset-1}、水晶槽 = {@code 70+offset}。
     *
     * <p>旧版把上段压到 {@code height+1-offset} 让原版水晶留在原地；那套公式已经废弃（见
     * {@link YungPillars} 类注释的「旧布局」一节），这里钉的是上游 {@code BetterSpikeFeature} 的
     * {@code topY = 70} 与 {@code centerPos.below(67)}。
     */
    @Test
    void theAnchorIsTheUpstreamSeventyAndThree() {
        for (long seed : new long[]{SEED, 7L, -99L, 2024L}) {
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                YungPillars.Layout layout = YungPillars.layout(spike, seed);
                String where = "seed " + seed + " 柱 " + spike.centerX() + "," + spike.centerZ();
                assertEquals(YungPillars.UPPER_BASE_Y, layout.topAnchorY(), where + " 上段锚点不是 70");
                assertEquals(70, layout.topAnchorY(), where + " 上段锚点不是上游的 70");
                assertEquals(YungPillars.LOWER_BASE_Y, layout.bottomAnchorY(), where + " 下段锚点不是 3");
                assertEquals(3, layout.bottomAnchorY(), where + " 下段锚点不是 70-67=3");
                assertEquals(layout.bottomAnchorY() + YungPillars.BOTTOM_DROP, layout.topAnchorY(),
                        where + " 上下两段不是相差 67");
                assertEquals(YungPillars.tier(spike.height()), layout.tier(), where + " 档位");
                assertEquals(YungPillars.crystalOffset(layout.tier()), layout.offset(),
                        where + " 水晶偏移");
                assertEquals(layout.topAnchorY() + layout.offset(), layout.crystalY(),
                        where + " 水晶槽不是 topY+offset");
                assertEquals(layout.crystalY() - 1, layout.seatY(), where + " 座位不是水晶槽下一格");
                // 档位全表：水晶 92..108，且永远不再等于原版槽位 height+1。
                assertTrue(layout.crystalY() >= 92 && layout.crystalY() <= 108,
                        where + " 水晶 y 越界：" + layout.crystalY());
                assertNotEquals(spike.height() + 1, layout.crystalY(),
                        where + " 新水晶槽与旧原版槽位重合，识别判据会失效");
                assertNotEquals(spike.height(), layout.seatY(),
                        where + " 新座位与旧原版基岩座重合，识别判据会失效");
                assertTrue(layout.rotation() >= 0 && layout.rotation() < 4, where + " 旋转量");
            }
        }
    }

    /** 十根柱的布局顺序与 {@link EndSpikes#forSeed} 一致，且与逐个 {@code layout} 相等。 */
    @Test
    void layoutsCoverAllTenSpikesInVanillaOrder() {
        for (long seed : new long[]{SEED, 7L, 2024L}) {
            List<EndSpikes.Spike> spikes = EndSpikes.forSeed(seed);
            List<YungPillars.Layout> layouts = YungPillars.layouts(seed);
            assertEquals(spikes.size(), layouts.size());
            for (int i = 0; i < spikes.size(); i++) {
                assertEquals(YungPillars.layout(spikes.get(i), seed), layouts.get(i));
            }
            // 档位覆盖 1..9：十根柱子按种子洗牌后，档位集合必须仍在模板表范围内。
            for (YungPillars.Layout layout : layouts) {
                assertTrue(layout.tier() >= 1 && layout.tier() <= 9, "档位 " + layout.tier());
                assertEquals("pillar_" + layout.type() + "_" + layout.tier(), layout.topName());
                assertEquals("pillar_bottom_" + layout.tier(), layout.bottomName());
            }
        }
    }

    // --- 旋转 -----------------------------------------------------------------------------------

    /** 四个朝向的墙 / 铁栏杆的连接关系跟着位置一起转，且转回去得到原状态。 */
    @Test
    void rotationTurnsPositionsAndFacesTogether() {
        for (String name : new String[]{"pillar_guarded_1", "pillar_guarded_9", "pillar_broken_4",
                "pillar_bottom_9", "pillar_bottom_1"}) {
            PillarTemplate base = PillarTemplate.load(name);
            for (int turns = 1; turns < 4; turns++) {
                PillarTemplate rotated = base.rotated(turns);
                assertEquals(base.count(), rotated.count(), name + " 旋转后方块数变了");
                for (int i = 0; i < rotated.count(); i++) {
                    // 把旋转后的格子按 -turns 转回去，必须能找回原来的那一格，且状态是它旋转后的样子。
                    int pivotX = base.sizeX / 2;
                    int pivotZ = base.sizeZ / 2;
                    int dx = rotated.x(i) - pivotX;
                    int dz = rotated.z(i) - pivotZ;
                    for (int t = 0; t < turns; t++) {
                        int next = dx;
                        dx = dz;
                        dz = -next;
                    }
                    int index = indexAt(base, pivotX + dx, rotated.y(i), pivotZ + dz);
                    assertTrue(index >= 0, name + " 转 " + turns + " 后 (" + rotated.x(i) + ","
                            + rotated.y(i) + "," + rotated.z(i) + ") 转回去找不到原始格子");
                    assertEquals(base.stateOf(index).rotated(turns).asString(), rotated.state(i),
                            name + " 转 " + turns + " 后方块状态没跟着转");
                }
                assertEquals(base.count(), rotated.rotated(4 - turns).count());
            }
        }
        // 具体的四分之一圈：位于柱心正东（dx=+1）的格子转 90° 后到正南（dz=+1）。
        PillarTemplate base = PillarTemplate.load("pillar_guarded_9");
        int east = indexAt(base, base.sizeX / 2 + 1, 19, base.sizeZ / 2);
        assertTrue(east >= 0, "样本格不存在，这条用例无效");
        PillarTemplate turned = base.rotated(1);
        int index = indexAt(turned, base.sizeX / 2, 19, base.sizeZ / 2 + 1);
        assertTrue(index >= 0, "正东的格子转 90° 后没有出现在正南");
        assertEquals(base.stateOf(east).rotated(1).asString(), turned.state(index));
    }

    /** 墙的 north/east/south/west 是顺时针轮转，{@code up} 与水浸不变。 */
    @Test
    void wallConnectionsRotateClockwise() {
        PillarTemplate.State wall = new PillarTemplate.State("minecraft:end_stone_brick_wall",
                new LinkedHashMap<>(Map.of("north", "tall", "east", "low", "south", "none",
                        "west", "none", "up", "true", "waterlogged", "false")));
        String turned = wall.rotated(1).asString();
        assertTrue(turned.contains("east=tall"), turned);
        assertTrue(turned.contains("south=low"), turned);
        assertTrue(turned.contains("west=none"), turned);
        assertTrue(turned.contains("north=none"), turned);
        assertTrue(turned.contains("up=true"), turned);
        assertTrue(turned.contains("waterlogged=false"), turned);
        assertEquals(wall.asString(), wall.rotated(4).asString(), "转满一圈没回到原状态");
        PillarTemplate.State bars = new PillarTemplate.State("minecraft:iron_bars",
                new LinkedHashMap<>(Map.of("north", "true", "east", "false", "south", "false",
                        "west", "false", "waterlogged", "false")));
        assertTrue(bars.rotated(1).asString().contains("east=true"), bars.rotated(1).asString());
        // 不认识的旋转属性必须报错，而不是按原样贴下去。
        PillarTemplate.State unknown = new PillarTemplate.State("minecraft:oak_sign",
                new LinkedHashMap<>(Map.of("weird_property", "north")));
        assertThrows(IllegalArgumentException.class, () -> unknown.rotated(1));
    }

    // --- 整根柱子 -------------------------------------------------------------------------------

    /**
     * 完整一趟：上下两段都放出来了、占位块一个不剩、座位是基岩、水晶格从未被写、不挖岛。
     */
    @Test
    void theBuildPlacesBothHalvesAndLeavesNoMarkerBehind() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            World world = populateAround(field, spike);
            YungPillars.Layout layout = YungPillars.layout(spike, SEED);
            PillarTemplate top = PillarTemplate.load("pillar_" + layout.type() + "_" + layout.tier())
                    .rotated(layout.rotation());
            PillarTemplate bottom = PillarTemplate.load("pillar_bottom_" + layout.tier())
                    .rotated(layout.rotation());
            String where = "柱 " + spike.centerX() + "," + spike.centerZ();

            assertTrue(world.blocks.size() > 1000, where + " 只写了 " + world.blocks.size() + " 格");

            // 占位块一个都不许留下。
            for (Map.Entry<Long, String> entry : world.blocks.entrySet()) {
                String state = entry.getValue();
                for (String marker : new String[]{"orange_terracotta", "magenta_terracotta",
                        "purple_concrete", "gray_concrete"}) {
                    assertFalse(state.contains(marker), where + " 在 " + entry.getKey() + " 留下了 "
                            + marker + "（" + state + "）");
                }
            }

            // 座位就是模板的座位标记（新布局：70+offset-1），不再落在原版基岩座上。
            assertEquals("minecraft:bedrock",
                    world.blocks.get(key(spike.centerX(), layout.seatY(), spike.centerZ())),
                    where + " 的水晶座不是基岩");

            // 新水晶槽（模板的水晶洞）从来没被写过：状态只能由实体占着。
            long crystal = key(spike.centerX(), layout.crystalY(), spike.centerZ());
            assertFalse(world.writtenOnce.contains(crystal), where + " 写了一格压到水晶槽上");
            assertNull(world.blocks.get(crystal), where + " 在水晶槽里放了方块");

            // 原版旧座与那格火必须被收尾：新布局下它们不再和模板座位重合。
            long oldSeat = key(spike.centerX(), spike.height(), spike.centerZ());
            assertTrue(world.writtenOnce.contains(oldSeat), where + " 原版旧基岩座没被收尾");
            assertNotEquals("minecraft:bedrock", world.blocks.get(oldSeat),
                    where + " 原版旧基岩座还留着");
            long oldFire = key(spike.centerX(), spike.height() + 1, spike.centerZ());
            assertTrue(world.writtenOnce.contains(oldFire), where + " 原版那格火没被收尾");
            assertNotEquals("minecraft:fire", world.blocks.get(oldFire), where + " 原版那格火还留着");

            // 两段的高度范围相接：下段最高的那一层正好在上段最低那一层的下面。
            int bottomTop = layout.bottomAnchorY() + maxLocalY(bottom);
            int topBottom = layout.topAnchorY() + minLocalY(top);
            assertEquals(topBottom - 1, bottomTop, where + " 的上下两段之间有空隙");
            assertEquals(YungPillars.BOTTOM_HEIGHT - 1, maxLocalY(bottom), "下段不是贴着接缝的那一份");
            assertEquals(0, minLocalY(top), "上段不是从接缝开始的");

            // 两段都真的落地了。
            assertTrue(writtenOf(world, top, spike.centerX(), layout.topAnchorY(), spike.centerZ()) > 100,
                    where + " 的上段几乎没落地");
            assertTrue(writtenOf(world, bottom, spike.centerX(), layout.bottomAnchorY(),
                    spike.centerZ()) > 100, where + " 的下段几乎没落地");

            // 不挖岛：地形说实心的格子，最终状态永远不是空气。
            for (Map.Entry<Long, String> entry : world.blocks.entrySet()) {
                if (!entry.getValue().equals("minecraft:air")) continue;
                long cell = entry.getKey();
                assertFalse(field.isSolid(x(cell), y(cell), z(cell)),
                        where + " 把岛体挖成了空气: " + x(cell) + "," + y(cell) + "," + z(cell));
            }
        }
    }

    /**
     * 水晶槽与攻击路径。新水晶槽（{@code 70+offset}）必须空着（官方结构晶随后被搬进去），四个水平方向
     * 必须至少有两面通，而周围不许出现挖不动的方块（基岩只在它脚下一格，那是模板的座位）。
     */
    @Test
    void theCrystalSlotStaysEmptyAndCanBeReached() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            World world = populateAround(field, spike);
            String where = "柱 " + spike.centerX() + "," + spike.centerZ();
            YungPillars.Layout layout = YungPillars.layout(spike, SEED);
            int crystalY = layout.crystalY();
            long crystal = key(spike.centerX(), crystalY, spike.centerZ());
            assertNull(world.blocks.get(crystal), where + " 的水晶格被占了");

            int open = 0;
            int[][] sideways = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] dir : sideways) {
                String at = world.blocks.get(key(spike.centerX() + dir[0], crystalY,
                        spike.centerZ() + dir[1]));
                if (at == null || at.equals("minecraft:air")) open++;
                else assertFalse(at.equals("minecraft:bedrock"),
                        where + " 在水晶旁边砌了基岩，" + at + " at " + dir[0] + "," + dir[1]);
            }
            assertTrue(open >= 2, where + " 的水晶四面被砌死，玩家够不到（只有 " + open + " 面通）");

            String above = world.blocks.get(key(spike.centerX(), crystalY + 1, spike.centerZ()));
            if (above != null) {
                assertFalse(above.equals("minecraft:bedrock"), where + " 在水晶头顶砌了基岩");
            }
            assertEquals("minecraft:bedrock",
                    world.blocks.get(key(spike.centerX(), layout.seatY(), spike.centerZ())),
                    where + " 的水晶座不是模板的基岩座位");
        }
    }

    /**
     * 原版柱芯与笼子：原版自己写过的每一格都必须被重写过（否则会从模板的空隙里露出来，变成叠建筑），
     * 而模板没占的位置只能变成「末地石（还给地形）」或「空气（本来是虚空）」。guarded 柱的 5×5 笼子
     * 同理——模板自己也有笼子，所以这里不假设笼子最后一定是空气，只要求它不再是原版那一套。
     */
    @Test
    void theVanillaShaftAndCageAreClearedInsideTheFootprint() {
        IslandField field = new IslandField(SEED);
        World world = populateEverySpike(field, SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            String where = "柱 " + spike.centerX() + "," + spike.centerZ();
            YungPillars.Layout layout = YungPillars.layout(spike, SEED);
            PillarTemplate top = PillarTemplate.load("pillar_" + layout.type() + "_" + layout.tier())
                    .rotated(layout.rotation());
            PillarTemplate bottom = PillarTemplate.load("pillar_bottom_" + layout.tier())
                    .rotated(layout.rotation());
            Set<Long> templateCells = new HashSet<>();
            templateCells.addAll(cellsOf(top, spike.centerX(), layout.topAnchorY(), spike.centerZ()));
            templateCells.addAll(cellsOf(bottom, spike.centerX(), layout.bottomAnchorY(),
                    spike.centerZ()));

            int r = spike.radius();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    for (int y = 0; y < spike.height(); y++) {
                        long cell = key(spike.centerX() + dx, y, spike.centerZ() + dz);
                        assertTrue(world.writtenOnce.contains(cell), where + " 漏掉了原版柱芯的 "
                                + x(cell) + "," + y(cell) + "," + z(cell));
                        if (templateCells.contains(cell)) continue;
                        String state = world.blocks.get(cell);
                        assertTrue("minecraft:air".equals(state) || "minecraft:end_stone".equals(state),
                                where + " 的清理结果既不是空气也不是末地石: " + state + " at "
                                        + x(cell) + "," + y(cell) + "," + z(cell));
                    }
                }
            }
            if (!spike.guarded()) continue;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 0; dy <= 3; dy++) {
                        if (Math.abs(dx) != 2 && Math.abs(dz) != 2 && dy != 3) continue;
                        long cell = key(spike.centerX() + dx, spike.height() + dy, spike.centerZ() + dz);
                        assertTrue(world.writtenOnce.contains(cell), where + " 的笼子没被重写: "
                                + x(cell) + "," + y(cell) + "," + z(cell));
                        if (templateCells.contains(cell)) continue;
                        assertEquals("minecraft:air", world.blocks.get(cell),
                                where + " 的笼子没拆干净: " + x(cell) + "," + y(cell) + "," + z(cell));
                    }
                }
            }
        }
    }

    /** 占位块的比例：orange 三成哭泣黑曜石，magenta 八成空气 / 各一成，purple 全黑曜石。 */
    @Test
    void thePlaceholderDistributionMatchesTheOfficialProcessors() {
        int orange = 0;
        int orangeCrying = 0;
        int magenta = 0;
        int magentaAir = 0;
        int magentaCrying = 0;
        int magentaObsidian = 0;
        for (long seed : new long[]{SEED, 7L, 2024L}) {
            IslandField field = new IslandField(seed);
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                YungPillars.Layout layout = YungPillars.layout(spike, seed);
                PillarTemplate top = PillarTemplate.load("pillar_" + layout.type() + "_" + layout.tier())
                        .rotated(layout.rotation());
                World world = populateAround(field, spike, seed);
                for (int i = 0; i < top.count(); i++) {
                    int x = spike.centerX() - top.sizeX / 2 + top.x(i);
                    int z = spike.centerZ() - top.sizeZ / 2 + top.z(i);
                    int y = layout.topAnchorY() + top.y(i);
                    String state = world.blocks.get(key(x, y, z));
                    if (state == null) continue;
                    switch (top.kind(i)) {
                        case ORANGE -> {
                            orange++;
                            if (state.equals("minecraft:crying_obsidian")) orangeCrying++;
                            else assertEquals("minecraft:obsidian", state);
                        }
                        case MAGENTA -> {
                            magenta++;
                            switch (state) {
                                case "minecraft:air" -> magentaAir++;
                                case "minecraft:crying_obsidian" -> magentaCrying++;
                                case "minecraft:obsidian" -> magentaObsidian++;
                                default -> throw new AssertionError("magenta 变成了 " + state);
                            }
                        }
                        case PURPLE -> assertEquals("minecraft:obsidian", state);
                        default -> { }
                    }
                }
            }
        }
        assertTrue(orange > 500, "orange 样本太小: " + orange);
        assertTrue(magenta > 500, "magenta 样本太小: " + magenta);
        double cryingRate = (double) orangeCrying / orange;
        assertTrue(Math.abs(cryingRate - 0.3) < 0.05, "orange 的哭泣黑曜石比例 " + cryingRate);
        double airRate = (double) magentaAir / magenta;
        assertTrue(Math.abs(airRate - 0.8) < 0.07, "magenta 的空气比例 " + airRate);
        assertTrue(((double) magentaCrying / magenta) > 0.05, "magenta 的哭泣黑曜石没了");
        assertTrue(((double) magentaObsidian / magenta) > 0.05, "magenta 的黑曜石没了");
    }

    // --- 分块重放 -------------------------------------------------------------------------------

    /**
     * 同一个种子、同一根柱子：九个区块跑两遍，结果逐格相同。
     */
    @Test
    void theBuildIsDeterministicAndIdempotent() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        World first = populateAround(field, spike);
        World second = populateAround(field, spike);
        assertEquals(first.blocks, second.blocks, "同一根柱子跑两遍结果不同");
    }

    /**
     * <b>P0：邻区块重放。</b>模拟真实顺序——柱心区块 S 的 populator 先跑完，然后邻区块 N 的装饰步骤
     * （原版 {@code EndSpikeFeature} 的写入半径是 1 区块）把 S 里的一批格子擦成空气，最后 N 自己的
     * populator 才跑。N 的可达范围包含整个 S，所以那些格子必须被原样写回来。
     *
     * <p>「只写自己区块的格」的方案在这里就挂了：被擦的格属于 S，而 S 的 populator 不会再跑。
     */
    @Test
    void aLaterDecorationIsRepairedByTheNextNeighbouringPopulator() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        int centerChunkX = spike.centerX() >> 4;
        int centerChunkZ = spike.centerZ() >> 4;

        World world = new World(field, SEED);
        world.populate(centerChunkX, centerChunkZ);
        Map<Long, String> before = new HashMap<>();
        for (Map.Entry<Long, String> entry : world.blocks.entrySet()) {
            long cell = entry.getKey();
            if ((x(cell) >> 4) == centerChunkX && (z(cell) >> 4) == centerChunkZ) {
                before.put(cell, entry.getValue());
            }
        }
        assertTrue(before.size() > 200, "柱心区块只写了 " + before.size() + " 格，这条用例没意义");

        world.erase(before.keySet());
        for (long cell : before.keySet()) {
            assertEquals("minecraft:air", world.blocks.get(cell), "擦除步骤没把格子清掉");
        }

        // 邻区块的装饰步骤之后，它自己的 populator 才跑。
        world.populate(centerChunkX + 1, centerChunkZ);

        for (Map.Entry<Long, String> entry : before.entrySet()) {
            assertEquals(entry.getValue(), world.blocks.get(entry.getKey()),
                    "邻区块的 populator 没有重放这一格: " + x(entry.getKey()) + ","
                            + y(entry.getKey()) + "," + z(entry.getKey()));
        }
    }

    /**
     * 九个区块跑完之后，每一格都写过，而且柱心区块里绝大部分格子被不止一个区块写过——那正是「重放」
     * 与「只写自己区块」的区别，退化成后者时这条会红。
     */
    @Test
    void everyReachableCellIsReplayedByEveryPopulatorThatCanReachIt() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        int centerChunkX = spike.centerX() >> 4;
        int centerChunkZ = spike.centerZ() >> 4;
        World world = new World(field, SEED);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.populate(centerChunkX + dx, centerChunkZ + dz);
            }
        }
        assertTrue(world.blocks.size() > 1000, "只写了 " + world.blocks.size() + " 格");
        for (Map.Entry<Long, String> entry : world.blocks.entrySet()) {
            assertEquals(entry.getValue(), world.lastValue.get(entry.getKey()),
                    "一格被写了两个不同的值: " + x(entry.getKey()) + "," + y(entry.getKey())
                            + "," + z(entry.getKey()));
        }
        int replayed = 0;
        for (Map.Entry<Long, Set<Long>> entry : world.writers.entrySet()) {
            long cell = entry.getKey();
            if (!world.blocks.containsKey(cell)) continue;
            if ((x(cell) >> 4) == centerChunkX && (z(cell) >> 4) == centerChunkZ
                    && entry.getValue().size() > 1) {
                replayed++;
            }
        }
        assertTrue(replayed > 100, "重叠区只有 " + replayed + " 格被重放过，看起来退化成 owner-only 了");
    }

    /** 越界：区域外的格子一个都不写（fake region 会为任何越界调用直接抛错）。 */
    @Test
    void nothingIsWrittenOutsideTheRegion() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            World world = populateAround(field, spike);
            assertTrue(world.outside.isEmpty(), "越界写了 " + world.outside.size() + " 格");
        }
    }

    /** 不读方块：判定只能来自纯函数，否则会被 CraftEngine 的 deceive-material 骗到。 */
    @Test
    void theBuildReadsNoBlock() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        // getType 在这个 fake 里直接抛 AssertionError，所以「跑完没炸」就是「没读过」。
        new World(field, SEED).populate(spike.centerX() >> 4, spike.centerZ() >> 4);
    }

    // --- fixtures -------------------------------------------------------------------------------

    private static World populateAround(IslandField field, EndSpikes.Spike spike) {
        return populateAround(field, spike, SEED);
    }

    /**
     * 覆盖全部十根柱位的世界：每根柱子周围九个区块都跑一遍。柱位之间会重叠（相邻柱心只隔一两个区块），
     * 所以去重后再跑——否则「另一根柱子只被写了一部分」会被误当成漏写。
     */
    private static World populateEverySpike(IslandField field, long seed) {
        World world = new World(field, seed);
        Set<Long> done = new HashSet<>();
        for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
            int centerChunkX = spike.centerX() >> 4;
            int centerChunkZ = spike.centerZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    if (!done.add(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL))) continue;
                    world.populate(chunkX, chunkZ);
                }
            }
        }
        return world;
    }

    /** 把柱心周围九个区块各跑一遍世界生成。 */
    private static World populateAround(IslandField field, EndSpikes.Spike spike, long seed) {
        World world = new World(field, seed);
        int centerChunkX = spike.centerX() >> 4;
        int centerChunkZ = spike.centerZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.populate(centerChunkX + dx, centerChunkZ + dz);
            }
        }
        return world;
    }

    /** 模板在世界上占的全部格子。 */
    private static Set<Long> cellsOf(PillarTemplate template, int centerX, int anchorY, int centerZ) {
        Set<Long> cells = new HashSet<>(template.count() * 2);
        int originX = centerX - template.sizeX / 2;
        int originZ = centerZ - template.sizeZ / 2;
        for (int i = 0; i < template.count(); i++) {
            cells.add(key(originX + template.x(i), anchorY + template.y(i), originZ + template.z(i)));
        }
        return cells;
    }

    private static int writtenOf(World world, PillarTemplate template, int centerX, int anchorY,
                                 int centerZ) {
        int hits = 0;
        for (int i = 0; i < template.count(); i++) {
            int x = centerX - template.sizeX / 2 + template.x(i);
            int z = centerZ - template.sizeZ / 2 + template.z(i);
            int y = anchorY + template.y(i);
            if (world.blocks.containsKey(key(x, y, z))) {
                hits++;
            }
        }
        return hits;
    }

    private static int minLocalY(PillarTemplate template) {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < template.count(); i++) {
            min = Math.min(min, template.y(i));
        }
        return min;
    }

    private static int maxLocalY(PillarTemplate template) {
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < template.count(); i++) {
            max = Math.max(max, template.y(i));
        }
        return max;
    }

    /** 模板里有没有这一格（有就返回 {@code kind}，没有返回 null）。 */
    private static PillarTemplate.Kind cellAt(PillarTemplate template, int x, int y, int z) {
        int index = indexAt(template, x, y, z);
        return index < 0 ? null : template.kind(index);
    }

    private static int indexAt(PillarTemplate template, int x, int y, int z) {
        for (int i = 0; i < template.count(); i++) {
            if (template.x(i) == x && template.y(i) == y && template.z(i) == z) {
                return i;
            }
        }
        return -1;
    }

    /** 状态串 → 打桩的 {@link BlockData}；同一状态串永远是同一个对象，便于按身份断言。 */
    private static final Map<String, BlockData> STUBS = new HashMap<>();

    private static synchronized BlockData state(String blockState) {
        return STUBS.computeIfAbsent(blockState, YungPillarsTest::stub);
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

    /**
     * 一个世界：九个区块的 populator 依次往里写，可以再来一次装饰步骤（{@link #erase(Set)}）模拟
     * 「更晚的原版特征」。
     *
     * <p>{@code getType} 故意抛错：{@link YungPillars} 不许读方块。{@code setBlockData} 越界时也抛错，
     * 并记下越界坐标供用例报告。
     */
    private static final class World {
        final Map<Long, String> blocks = new HashMap<>();
        /** 至少被写过一次的格子。 */
        final Set<Long> writtenOnce = new HashSet<>();
        /** 每格最后一次写入的值（用来断言「不同区块写的是同一个值」）。 */
        final Map<Long, String> lastValue = new HashMap<>();
        /** 每格被哪些区块写过——重放的直接证据。 */
        final Map<Long, Set<Long>> writers = new HashMap<>();
        final Set<Long> outside = new HashSet<>();
        private final IslandField field;
        private final long seed;

        World(IslandField field, long seed) {
            this.field = field;
            this.seed = seed;
        }

        void populate(int chunkX, int chunkZ) {
            YungPillars.build(regionFor(chunkX, chunkZ), field, END_STONE, seed, chunkX, chunkZ,
                    YungPillarsTest::state);
        }

        /** 模拟一个更晚的装饰步骤：把一批格子擦成空气。 */
        void erase(Set<Long> cells) {
            for (long cell : cells) {
                blocks.put(cell, "minecraft:air");
            }
        }

        LimitedRegion regionFor(int chunkX, int chunkZ) {
            return (LimitedRegion) Proxy.newProxyInstance(
                    LimitedRegion.class.getClassLoader(),
                    new Class<?>[]{LimitedRegion.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isInRegion" -> inRegion((Integer) args[0], (Integer) args[1],
                                (Integer) args[2], chunkX, chunkZ);
                        case "setBlockData" -> {
                            int x = (Integer) args[0];
                            int y = (Integer) args[1];
                            int z = (Integer) args[2];
                            BlockData data = (BlockData) args[3];
                            long cell = key(x, y, z);
                            if (!inRegion(x, y, z, chunkX, chunkZ)) {
                                outside.add(cell);
                                throw new AssertionError("越界写入 " + x + "," + y + "," + z);
                            }
                            // 同一趟里先清理后放模板，所以一格在一次 populate 里可能被写两次
                            // （清理写空气/末地石，模板再覆盖它）。这里只记最后一次的值。
                            String state = data.getAsString();
                            lastValue.put(cell, state);
                            blocks.put(cell, state);
                            writtenOnce.add(cell);
                            long chunk = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                            writers.computeIfAbsent(cell, k -> new HashSet<>()).add(chunk);
                            yield null;
                        }
                        case "getType" -> throw new AssertionError(
                                "YungPillars 不许读方块（" + args[0] + "," + args[1] + "," + args[2] + "）");
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "FakeRegion";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private static boolean inRegion(int x, int y, int z, int chunkX, int chunkZ) {
            return y >= WORLD_MIN_Y && y <= WORLD_MAX_Y
                    && Math.abs((x >> 4) - chunkX) <= 1
                    && Math.abs((z >> 4) - chunkZ) <= 1;
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFFL);
    }

    private static int x(long k) {
        return (int) (k >> 42);
    }

    private static int y(long k) {
        return (int) (k & 0xFFFFF);
    }

    private static int z(long k) {
        return (int) ((k >> 20) & 0x3FFFFF) << 10 >> 10;
    }
}
