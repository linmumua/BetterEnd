package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndPodiumTemplate}：中心祭坛模板的契约、锚点、保护区、区块分桶与预检谓词。
 *
 * <p>这一组用例钉的都是「错了就毁世界 / 毁龙战」的东西，不是「长得像不像」：
 * <ul>
 *   <li><b>资源字节固定</b>：SHA-256 与尺寸、格数、空气/灰标记/钟的数量一起钉住——哈希一变，下面所有
 *       「已核实模板内容无实体、无红混凝土」的结论就不再成立于新字节上；</li>
 *   <li><b>锚点</b>：返回门平面 = 模板局部 y=2，XZ 居中于返回门，不旋转；</li>
 *   <li><b>保护区零写</b>：{@code |dx|≤4 && |dz|≤4 && portalY-1..portalY+4} 内一格都不写；</li>
 *   <li><b>负坐标分桶</b>：{@code floorDiv(16)} 而不是移位，桶里的格子确实属于那个区块；</li>
 *   <li><b>局部修复集合</b>：用 MU 26.3 的 {@code EndPodiumFeature} 真实遍历盒逐格枚举，证明原版重建
 *       的写入范围整体落在保护区里，因此不需要填回；同时证明「如果再往下一格」就会真的擦到塔的地板；</li>
 *   <li><b>预检谓词</b>：末地石/空气可以覆盖，基岩/返回门/龙蛋永不覆盖，末地石砖与容器阻止整个世界；
 *       而且<b>「和目标一样」不是所有权证据</b>——玩家摆的同 id 末地石砖、同 id 不同朝向的楼梯、甚至
 *       与模板逐字符相同的方块，都必须让整楼停工。</li>
 * </ul>
 */
class EndPodiumTemplateTest {

    private static final int PORTAL_X = 7;
    private static final int PORTAL_Y = 70;
    private static final int PORTAL_Z = -3;

    // --- 模板契约 -------------------------------------------------------------------------------

    /** 资源就是固定提交的原字节：哈希、尺寸、格数与分类计数一起钉住。 */
    @Test
    void resourceIsThePinnedCommit() throws IOException, NoSuchAlgorithmException {
        byte[] bytes;
        try (InputStream in = EndPodiumTemplate.class.getResourceAsStream(EndPodiumTemplate.RESOURCE)) {
            assertTrue(in != null, "jar 内缺少 " + EndPodiumTemplate.RESOURCE);
            bytes = in.readAllBytes();
        }
        assertEquals(49_016, bytes.length, "模板字节数");
        assertEquals(EndPodiumTemplate.SHA256, sha256(bytes));

        PillarTemplate template = PillarTemplate.load(EndPodiumTemplate.NAME);
        assertEquals(EndPodiumTemplate.SIZE_X, template.sizeX);
        assertEquals(EndPodiumTemplate.SIZE_Y, template.sizeY);
        assertEquals(EndPodiumTemplate.SIZE_Z, template.sizeZ);
        assertEquals(EndPodiumTemplate.CELLS, template.count(), "含空气的格数");

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < template.count(); i++) {
            counts.merge(EndPodiumTemplate.blockId(template.state(i)), 1, Integer::sum);
        }
        assertEquals(Map.of(
                "minecraft:air", 13_589,
                "minecraft:end_stone_bricks", 2_909,
                "minecraft:obsidian", 343,
                "minecraft:end_stone_brick_wall", 207,
                "minecraft:end_stone_brick_stairs", 154,
                "minecraft:end_stone_brick_slab", 115,
                "minecraft:gray_concrete", 65,
                "minecraft:bell", 1), counts, "模板方块分类");
    }

    /** 坐标都落在 29×27×29 内，且没有重复格（NBT 里每个 pos 只出现一次）。 */
    @Test
    void everyCoordinateIsInsideAndUnique() {
        PillarTemplate template = PillarTemplate.load(EndPodiumTemplate.NAME);
        Set<Long> seen = new TreeSet<>();
        for (int i = 0; i < template.count(); i++) {
            int x = template.x(i);
            int y = template.y(i);
            int z = template.z(i);
            assertTrue(x >= 0 && x < 29 && y >= 0 && y < 27 && z >= 0 && z < 29,
                    "越界格 " + x + "," + y + "," + z);
            assertTrue(seen.add(pack(x, y, z)), "重复格 " + x + "," + y + "," + z);
        }
        assertEquals(EndPodiumTemplate.CELLS, seen.size());
    }

    /** 灰标记只出现在模板里，落到计划里必须是基岩；钟保留原状态。 */
    @Test
    void markersBecomeBedrockAndTheBellKeepsItsState() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        int markers = 0;
        int bells = 0;
        for (EndPodiumTemplate.Cell cell : plan.cells()) {
            assertFalse(cell.state().startsWith("minecraft:gray_concrete"),
                    "灰标记不许留在世界里: " + cell);
            if (cell.kind() == EndPodiumTemplate.Kind.BEDROCK) {
                markers++;
                assertEquals("minecraft:bedrock", cell.state());
            }
            if (cell.state().startsWith("minecraft:bell")) {
                bells++;
                assertTrue(cell.state().contains("attachment=ceiling"), cell.state());
                assertTrue(cell.state().contains("facing=south"), cell.state());
            }
        }
        assertEquals(EndPodiumTemplate.MARKERS, markers);
        assertEquals(1, bells, "钟只有一个");
    }

    /** 空气格留在计划里、且目标是空气：它们负责「清掉天然地形」，不是「什么都不做」。 */
    @Test
    void airCellsArePartOfThePlan() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        int air = 0;
        for (EndPodiumTemplate.Cell cell : plan.cells()) {
            if (cell.kind() == EndPodiumTemplate.Kind.AIR) {
                air++;
                assertEquals("minecraft:air", cell.state());
                assertTrue(cell.air());
            }
        }
        assertEquals(EndPodiumTemplate.AIR_CELLS, air);
        assertEquals(plan.cells().size(), plan.writes().size() + plan.skipped().size(),
                "每个格子要么写、要么被保护区跳过");
    }

    // --- 锚点 -----------------------------------------------------------------------------------

    /** {@code base = (portalX-14, portalY-2, portalZ-14)}，不旋转；返回门那一格必须在保护区里。 */
    @Test
    void anchorPutsLocalY2OnThePortalPlane() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertEquals(PORTAL_X - 14, plan.baseX());
        assertEquals(PORTAL_Y - 2, plan.baseY());
        assertEquals(PORTAL_Z - 14, plan.baseZ());
        assertEquals(PORTAL_X - 14, plan.minX());
        assertEquals(PORTAL_X + 14, plan.maxX());
        assertEquals(PORTAL_Y - 2, plan.minY());
        assertEquals(PORTAL_Y + 24, plan.maxY());
        assertEquals(PORTAL_Z - 14, plan.minZ());
        assertEquals(PORTAL_Z + 14, plan.maxZ());

        // 局部 (14, 2, 14) 就是返回门那一格：它必须被保护区挡住。
        EndPodiumTemplate.Cell portalCell = cellAt(plan, PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertTrue(portalCell != null, "模板中心必须有一格（空气也算）");
        assertTrue(plan.protects(PORTAL_X, PORTAL_Y, PORTAL_Z));
        assertFalse(plan.writes().contains(portalCell), "返回门那一格不许写");
    }

    /** 同一个返回门坐标多次计算结果一致（记录值相等），且不共享可变状态。 */
    @Test
    void planIsDeterministicAndImmutable() {
        EndPodiumTemplate.Plan first = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Plan second = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertEquals(first.cells(), second.cells());
        assertEquals(first.byChunk(), second.byChunk());

        assertThrows(UnsupportedOperationException.class,
                () -> first.cells().add(new EndPodiumTemplate.Cell(0, 0, 0, "minecraft:air",
                        EndPodiumTemplate.Kind.AIR)));
        assertThrows(UnsupportedOperationException.class, () -> first.byChunk().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> first.byChunk().get(first.byChunk().keySet().iterator().next()).clear());
    }

    // --- 保护区 ---------------------------------------------------------------------------------

    /** 家具格一格不写；写的格子里没有一格是家具。 */
    @Test
    void furnitureIsNeverWritten() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertTrue(plan.furniture().size() > 0, "家具集合为空说明掩码失效");
        for (EndPodiumTemplate.Cell cell : plan.furniture()) {
            assertTrue(plan.protects(cell.x(), cell.y(), cell.z()), cell::toString);
            assertFalse(plan.writes().contains(cell), "家具格不许落笔: " + cell);
        }
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            assertFalse(plan.protects(cell.x(), cell.y(), cell.z()), "家具被误判成可写: " + cell);
        }
    }

    /**
     * 新锚点的实际数字（门环在中心 (0,61,0)、楼板 y=64、底面 y=59）：
     * 82 格家具、108 格净空、17,193 格装饰，并跨 4 个区块。
     */
    @Test
    void newAnchorSplitsFurnitureClearanceAndDecoration() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.planAtSurface(64);
        assertEquals(61, plan.gateY());
        assertEquals(64, plan.floorY());
        assertEquals(64, plan.podiumTopY());
        assertEquals(64, plan.surfaceTopY());
        assertEquals(59, plan.baseY());
        assertEquals(59, plan.minY());
        assertEquals(85, plan.maxY());

        assertEquals(82, plan.furniture().size(), "原版门家具所在的模板格");
        assertEquals(108, plan.clearance().size(), "必须清成空气的格（门坑 + 井口 + 四晶座头部）");
        assertEquals(17_193, plan.decorations().size(), "装饰/结构格");
        assertEquals(17_301, plan.writes().size(), "要落笔的全部格");
        assertEquals(EndPodiumTemplate.CELLS, plan.writes().size() + plan.furniture().size(),
                "模板格要么写、要么是家具");

        assertEquals(4, plan.byChunk().size(), "29×29 跨 4 个区块");
        assertEquals(new HashSet<>(List.of(
                new EndPodiumTemplate.ChunkKey(-1, -1),
                new EndPodiumTemplate.ChunkKey(-1, 0),
                new EndPodiumTemplate.ChunkKey(0, -1),
                new EndPodiumTemplate.ChunkKey(0, 0))), new HashSet<>(plan.byChunk().keySet()));
        assertEquals(plan.byChunk().keySet(), plan.clearanceByChunk().keySet(),
                "净空与装饰落点同一批区块");
    }

    /**
     * 井口就是中心 7×7（楼板那一层）：48 格被清成空气，中心那格仍是柱顶家具。
     * 半径 3 的晶体头部（原版 {@code tryRespawn} 认的位置）因此也在净空里。
     */
    @Test
    void wellIsTheCenterSevenBySevenAndCoversRadiusThreeHeads() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.planAtSurface(64);
        assertEquals(48, plan.well().size(), "7×7 减掉中心柱顶");
        for (EndPodiumTemplate.Cell cell : plan.well()) {
            assertEquals("minecraft:air", cell.state());
            assertEquals(plan.floorY(), cell.y());
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                assertTrue(EndPodiumTemplate.inWell(0, plan.gateY(), 0, dx, plan.floorY(), dz),
                        "井口内 (" + dx + "," + dz + ")");
            }
        }
        // 半径 3 的头部是空气；下一层（楼板下 1 格）仍是砖圈，构成一级台阶。
        for (int[] dir : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            assertEquals("minecraft:air", stateAt(plan, dir[0], plan.floorY(), dir[1]),
                    "半径 3 晶体头部 (" + dir[0] + "," + dir[1] + ")");
            assertEquals("minecraft:end_stone_bricks", stateAt(plan, dir[0], plan.floorY() - 1, dir[1]),
                    "半径 3 的砖圈是出入台阶，不许被清掉");
        }
    }

    /** 四晶座：基岩支撑在 {@code floorY}，晶体头部在 {@code floorY+1} 且必须是空气。 */
    @Test
    void fourSeatsAreBedrockSupportsWithAirHeads() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.planAtSurface(64);
        assertEquals(4, plan.seats().size());
        Set<String> expected = new HashSet<>(List.of("8,0", "-8,0", "0,8", "0,-8"));
        Set<String> actual = new HashSet<>();
        for (EndPodiumTemplate.Seat seat : plan.seats()) {
            actual.add(seat.x() + "," + seat.z());
            assertEquals(plan.floorY(), seat.supportY());
            assertEquals(plan.floorY() + 1, seat.headY());
            assertEquals("minecraft:bedrock", stateAt(plan, seat.x(), seat.supportY(), seat.z()),
                    "支撑 " + seat);
            assertEquals("minecraft:air", stateAt(plan, seat.x(), seat.headY(), seat.z()),
                    "晶体头部 " + seat);
            assertTrue(EndPodiumTemplate.isSeatHead(0, plan.gateY(), 0, seat.x(), seat.headY(), seat.z()));
        }
        assertEquals(expected, actual, "四座在 (±8, 0) 与 (0, ±8)");
    }

    /** 布局串是「版本 + 种子 + 门坐标」；旧格式（只有坐标）必须被拒绝，绝不当新布局用。 */
    @Test
    void layoutCodecCarriesVersionAndSeedAndRejectsTheLegacyString() {
        EndPodiumTemplate.Layout layout = new EndPodiumTemplate.Layout(EndPodiumTemplate.LAYOUT_VERSION,
                123456789L, 0, 61, 0);
        String encoded = layout.encode();
        assertEquals("v2:seed=123456789:gate=0,61,0", encoded);
        assertEquals(layout, EndPodiumTemplate.decode(encoded));
        assertEquals(61, EndPodiumTemplate.decode(encoded).plan().gateY());

        for (String legacy : List.of("0,64,0", "v1:seed=1:gate=0,64,0")) {
            assertThrows(IllegalArgumentException.class, () -> EndPodiumTemplate.decode(legacy),
                    "旧布局必须被拒绝: " + legacy);
        }
        assertThrows(IllegalArgumentException.class, () -> EndPodiumTemplate.decode("v2:seed=x:gate=0,61,0"));
    }

    /** 计划里的实际状态（世界坐标）：家具集合里也算，方便断言柱顶/井口。 */
    private static String stateAt(EndPodiumTemplate.Plan plan, int x, int y, int z) {
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            if (cell.x() == x && cell.y() == y && cell.z() == z) {
                return cell.state();
            }
        }
        return "minecraft:bedrock"; // 只有家具格会走到这里，模板里那些格的目标就是 bedrock
    }

    /** 返回门的每一样功能方块（门、基岩柱、火把、龙蛋）都在保护区里，永远不被模板覆盖。 */    @Test
    void vanillaPortalFurnitureIsCoveredByTheProtection() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        // 返回门内圈（2.5 半径）在 portalY 层。
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz <= EndPodiumTemplate.VANILLA_INNER_D2) {
                    assertTrue(plan.protects(PORTAL_X + dx, PORTAL_Y, PORTAL_Z + dz),
                            "返回门方块 " + dx + "," + dz);
                }
            }
        }
        // 4 格基岩柱与 4 支火把。
        for (int dy = 0; dy <= 3; dy++) {
            assertTrue(plan.protects(PORTAL_X, PORTAL_Y + dy, PORTAL_Z));
        }
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            assertTrue(plan.protects(PORTAL_X + dir[0], PORTAL_Y + 2, PORTAL_Z + dir[1]),
                    "火把 " + dir[0] + "," + dir[1]);
        }
        // 龙蛋（基岩柱顶那格）。
        assertTrue(plan.protects(PORTAL_X, PORTAL_Y + 4, PORTAL_Z));
    }

    // --- 局部修复集合 ---------------------------------------------------------------------------

    /**
     * 原版重建范围 ⊆ 保护区，所以 {@link EndPodiumTemplate.Plan#repairs()} 是空集；而且这条空集是
     * 「算出来的」而不是「没实现」：把遍历盒往下挪一格就会擦到塔的地板（同一组用例里断言）。
     */
    @Test
    void vanillaRebuildNeverTouchesTheTower() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertTrue(plan.repairs().isEmpty(), "这套锚点下没有需要填回的格子");

        int written = 0;
        for (int dy = -EndPodiumTemplate.VANILLA_BOX_BELOW; dy <= EndPodiumTemplate.VANILLA_BOX_ABOVE; dy++) {
            for (int dx = -EndPodiumTemplate.VANILLA_BOX_RADIUS; dx <= EndPodiumTemplate.VANILLA_BOX_RADIUS; dx++) {
                for (int dz = -EndPodiumTemplate.VANILLA_BOX_RADIUS; dz <= EndPodiumTemplate.VANILLA_BOX_RADIUS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > EndPodiumTemplate.VANILLA_RADIUS_D2) {
                        continue;
                    }
                    written++;
                    assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(PORTAL_X, PORTAL_Y, PORTAL_Z,
                                    PORTAL_X + dx, PORTAL_Y + dy, PORTAL_Z + dz),
                            "遍历盒与谓词不一致: " + dx + "," + dy + "," + dz);
                    // 新契约：原版那团写入范围在本布局里要么是它自己的家具，要么已经被我们清成 AIR。
                    // 这条替代了旧版「整盒零写入」——净空必须先清，否则原生 spawnExitPortal 会
                    // destroyBlock(..., true) 把建筑方块打成掉落物。
                    boolean furniture = EndPodiumTemplate.isFurniture(PORTAL_X, PORTAL_Y, PORTAL_Z,
                            PORTAL_X + dx, PORTAL_Y + dy, PORTAL_Z + dz);
                    assertTrue(furniture || "minecraft:air".equals(
                                    stateAt(plan, PORTAL_X + dx, PORTAL_Y + dy, PORTAL_Z + dz)),
                            "原版写入范围必须是家具或已清净空: " + dx + "," + dy + "," + dz);
                }
            }
        }
        assertEquals(145, written, "原版遍历盒里实际被写的格数（dy=-1..3 的圆盘）");

        // 再往下一格（dy=-2）就真的会盖到塔的地板：25 格里全是计划里的实心格。
        int floorHits = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + 4 + dz * dz > EndPodiumTemplate.VANILLA_RADIUS_D2) {
                    continue;
                }
                EndPodiumTemplate.Cell cell = cellAt(plan, PORTAL_X + dx, PORTAL_Y - 2, PORTAL_Z + dz);
                if (cell != null && cell.kind() == EndPodiumTemplate.Kind.SOLID) {
                    floorHits++;
                }
            }
        }
        assertEquals(25, floorHits, "返回门下两格是塔的完整地板：遍历盒下沿若再深一格就要修复");
    }

    /** 原版半径的整数等价：{@code 3.5 ⇒ d²≤12}、{@code 2.5 ⇒ d²≤6}，且下沿是 {@code portalY-1}。 */
    @Test
    void vanillaWritePredicateMatchesTheServer() {
        assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 0, 64, 0));
        assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 3, 64, 1));
        assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 3, 64, 0));
        assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 0, 63, 0));
        assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 1, 66, 1));
        assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 0, 67, 0));
        assertFalse(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 3, 64, 2), "3,0,2 的 d²=13 > 12");
        assertTrue(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 2, 66, 2), "2,2,2 的 d² 正好 12");
        assertFalse(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 0, 68, 0), "d²=16 > 12");
        assertFalse(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 0, 62, 0), "下沿是 portalY-1");
        assertFalse(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 0, 96, 0), "上沿 portalY+32");
        assertFalse(EndPodiumTemplate.inVanillaPodiumWrite(0, 64, 0, 4, 64, 0), "3.5 半径够不到 dx=4");
    }

    // --- 区块分桶 -------------------------------------------------------------------------------

    /** 负坐标走 floorDiv：桶键与格子、桶大小总和都对得上，且有序。 */
    @Test
    void chunkBucketsHandleNegativeCoordinates() {
        assertEquals(new EndPodiumTemplate.ChunkKey(-1, 0), EndPodiumTemplate.ChunkKey.of(-1, 0));
        assertEquals(new EndPodiumTemplate.ChunkKey(-1, 0), EndPodiumTemplate.ChunkKey.of(-16, 15));
        assertEquals(new EndPodiumTemplate.ChunkKey(-2, -1), EndPodiumTemplate.ChunkKey.of(-17, -1));
        assertEquals(new EndPodiumTemplate.ChunkKey(0, 0), EndPodiumTemplate.ChunkKey.of(0, 0));

        // 让施工体积整体落在负坐标上：baseX = -114 … -86，baseZ = 100 … 128。
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(-100, PORTAL_Y, 114);
        assertTrue(plan.minX() < 0 && plan.minZ() > 0);
        int total = 0;
        List<EndPodiumTemplate.ChunkKey> keys = new ArrayList<>(plan.byChunk().keySet());
        for (EndPodiumTemplate.ChunkKey key : keys) {
            List<EndPodiumTemplate.Cell> bucket = plan.byChunk().get(key);
            total += bucket.size();
            for (EndPodiumTemplate.Cell cell : bucket) {
                assertEquals(key, EndPodiumTemplate.ChunkKey.of(cell.x(), cell.z()),
                        "格子被放进错误的桶: " + cell);
            }
        }
        assertEquals(plan.decorations().size(), total, "所有装饰格都被分到某个桶里（净空另走 clearanceByChunk）");
        assertEquals(plan.writes().size(), plan.decorations().size() + plan.clearance().size(),
                "写入集合 = 净空 + 装饰");
        assertEquals(plan.writes().stream().distinct().count(), plan.writes().size(), "写入集合没有重复格");
        assertTrue(keys.size() >= 2, "29×29 的塔不可能只落在一个区块里");
        List<EndPodiumTemplate.ChunkKey> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        assertEquals(sorted, keys, "桶键按 x、z 升序");
        boolean negative = false;
        for (EndPodiumTemplate.ChunkKey key : keys) {
            if (key.x() < 0) {
                negative = true;
            }
        }
        assertTrue(negative, "这组坐标必须出现负区块，否则测不到 floorDiv");
    }

    // --- 预检谓词 -------------------------------------------------------------------------------

    /** 天然地形可覆盖；门的功能方块跳过；末地石砖 / 容器属于未知，必须阻止整个世界。 */
    @Test
    void preflightClassifiesMaterials() {
        String brick = "minecraft:end_stone_bricks";
        assertEquals(EndPodiumTemplate.Verdict.NATURAL,
                EndPodiumTemplate.classify("minecraft:end_stone"));
        assertEquals(EndPodiumTemplate.Verdict.NATURAL,
                EndPodiumTemplate.classify("minecraft:air"));
        assertEquals(EndPodiumTemplate.Verdict.NATURAL,
                EndPodiumTemplate.classify("minecraft:cave_air"));
        assertEquals(EndPodiumTemplate.Verdict.NATURAL,
                EndPodiumTemplate.classify("minecraft:void_air"));
        assertEquals(EndPodiumTemplate.Verdict.FUNCTION_PROTECTED,
                EndPodiumTemplate.classify("minecraft:bedrock"));
        assertEquals(EndPodiumTemplate.Verdict.FUNCTION_PROTECTED,
                EndPodiumTemplate.classify("minecraft:end_portal"));
        assertEquals(EndPodiumTemplate.Verdict.FUNCTION_PROTECTED,
                EndPodiumTemplate.classify("minecraft:dragon_egg"));
        assertEquals(EndPodiumTemplate.Verdict.UNKNOWN,
                EndPodiumTemplate.classify(brick), "末地石砖不能一概当天然");
        assertEquals(EndPodiumTemplate.Verdict.UNKNOWN,
                EndPodiumTemplate.classify("minecraft:chest[facing=north]"));
        assertEquals(EndPodiumTemplate.Verdict.UNKNOWN,
                EndPodiumTemplate.classify("minecraft:obsidian"));

        assertTrue(EndPodiumTemplate.canWrite("minecraft:end_stone"));
        assertTrue(EndPodiumTemplate.canWrite("minecraft:air"));
        assertFalse(EndPodiumTemplate.canWrite(brick), "同 id 不是所有权证据");
        assertFalse(EndPodiumTemplate.canWrite("minecraft:bedrock"));
        assertFalse(EndPodiumTemplate.canWrite("minecraft:chest"));

        assertTrue(EndPodiumTemplate.isNaturalTerrain("minecraft:end_stone"));
        assertFalse(EndPodiumTemplate.isNaturalTerrain(brick));
        assertTrue(EndPodiumTemplate.isFunctionProtected("minecraft:bedrock"));
        assertFalse(EndPodiumTemplate.isFunctionProtected(brick));

        assertEquals("minecraft:oak_stairs", EndPodiumTemplate.blockId("minecraft:oak_stairs[facing=north]"));
        assertEquals("minecraft:stone", EndPodiumTemplate.blockId("minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> EndPodiumTemplate.blockId(null));
    }

    // --- 玩家建筑：像模板也不行 ---------------------------------------------------------------

    /**
     * 玩家恰好在某个目标格上摆了<b>和目标同 id</b> 的末地石砖：这是预检里最危险的一格，因为「同 id」
     * 曾被当成「本服务已经放下过」。整楼必须被拒绝，一格都不写。
     */
    @Test
    void playerEndStoneBricksOnATargetCellBlockTheWholeWorld() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell target = firstCellWithBlockId(plan, "minecraft:end_stone_bricks");
        List<EndPodiumTemplate.Cell> blocked = blocked(plan, target,
                "minecraft:end_stone_bricks[facing=north]");
        assertEquals(List.of(target), blocked, "玩家末地石砖必须让整个世界停下来");
        assertFalse(wouldWrite(plan, target, "minecraft:end_stone_bricks[facing=north]"));
    }

    /**
     * 同 id 但朝向相反的玩家楼梯：只比较方块 id 的旧写法会把它当成自己的，逐字符比较的「修复」也
     * 拦不住形状差异，所以这条要连属性一起拒绝。
     */
    @Test
    void playerStairsWithTheSameIdButAnotherFacingBlockTheWholeWorld() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell target = firstCellWithBlockId(plan, "minecraft:end_stone_brick_stairs");
        String opposite = oppositeFacing(target.state());
        assertNotEquals(target.state(), opposite);
        assertEquals(List.of(target), blocked(plan, target, opposite), "朝向不同的楼梯必须拒绝");
        assertFalse(wouldWrite(plan, target, opposite));
    }

    /**
     * 连<b>逐字符相同</b>的状态串都不算所有权：玩家手摆的花/台阶恰好和模板一致时，仍然要拒绝。
     * 这一条专门堵「把 id 比较换成状态串比较」这种假修复。
     */
    @Test
    void playerBlockIdenticalToTheDesiredStateStillBlocksTheWholeWorld() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        for (String blockId : List.of("minecraft:end_stone_bricks", "minecraft:end_stone_brick_stairs",
                "minecraft:end_stone_brick_slab", "minecraft:end_stone_brick_wall", "minecraft:obsidian")) {
            EndPodiumTemplate.Cell target = firstCellWithBlockId(plan, blockId);
            assertEquals(List.of(target), blocked(plan, target, target.state()),
                    "逐字符相同也要拒绝: " + target.state());
            assertFalse(wouldWrite(plan, target, target.state()));
        }
    }

    /** 正向对照：全世界都是天然地形时预检通过，说明上面三条不是「永远拦住」。 */
    @Test
    void naturalWorldPassesTheSameGate() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertTrue(blocked(plan, null, null).isEmpty(), "全天然地形不该被拦");
        assertTrue(wouldWrite(plan, null, null));
    }


    // --- 预加载 ---------------------------------------------------------------------------------

    /** 预热把 palette 的每一项（灰标记换成基岩）和空气/基岩都交给解析器：状态串必须都合规。 */
    @Test
    void preloadOffersEveryParsableState() {
        List<String> offered = new ArrayList<>();
        EndPodiumTemplate.preload(offered::add);

        PillarTemplate template = PillarTemplate.load(EndPodiumTemplate.NAME);
        assertEquals(template.paletteSize() + 2, offered.size(), "palette 每项 + 空气 + 基岩");
        assertTrue(offered.contains("minecraft:air"));
        assertTrue(offered.contains("minecraft:bedrock"));
        for (String state : offered) {
            assertTrue(isParsableState(state), "不合规的状态串: " + state);
            assertNotEquals("minecraft:gray_concrete", EndPodiumTemplate.blockId(state),
                    "灰标记不许出现在要解析的状态里");
        }
    }

    // --- 高度图风险 -----------------------------------------------------------------------------

    /**
     * 中心那一列从局部 y=6 起是塔身内部，顶部有实心屋顶：把塔放在 worldgen 阶段，龙战读高度图时就会
     * 把返回门／龙蛋放到楼顶。这一条是「禁止先生成」的可执行证据。
     */
    @Test
    void centerColumnDocumentsWhyWorldgenMustNotBuildIt() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        List<EndPodiumTemplate.Cell> column = plan.centerColumn();
        assertFalse(column.isEmpty(), "返回门那一列必须有格子（空气格也算）");

        int highestSolid = Integer.MIN_VALUE;
        for (EndPodiumTemplate.Cell cell : column) {
            assertTrue(cell.x() == PORTAL_X && cell.z() == PORTAL_Z);
            if (cell.kind() != EndPodiumTemplate.Kind.AIR) {
                highestSolid = Math.max(highestSolid, cell.y());
            }
            if (plan.protects(cell.x(), cell.y(), cell.z())) {
                assertFalse(plan.writes().contains(cell), "保护区内那一列不许写");
            }
        }
        assertTrue(highestSolid > PORTAL_Y + 4, "塔顶必须在龙蛋之上：实心最高 " + highestSolid);
        assertEquals(PORTAL_Y + 24, highestSolid, "屋脊（模板局部 y=26 的墙）比龙蛋高 20 格");
    }

    // --- 工具 -----------------------------------------------------------------------------------

    /** 保护区判定（与实现同构，但用「负向」写法独立表达一次，避免把实现抄进断言）。 */
    private static boolean relatesToProtection(EndPodiumTemplate.Cell cell) {
        int dx = Math.abs(cell.x() - PORTAL_X);
        int dz = Math.abs(cell.z() - PORTAL_Z);
        boolean insidePlane = dx <= 4 && dz <= 4;
        boolean insideHeight = cell.y() >= PORTAL_Y - 1 && cell.y() <= PORTAL_Y + 4;
        return insidePlane && insideHeight;
    }

    /**
     * 模拟运行层的整楼预检：把每一格都当作天然空气，只把 {@code override} 那一格换成
     * {@code state}，返回所有 {@link EndPodiumTemplate.Verdict#UNKNOWN} 的格子。
     *
     * <p>任何一格 UNKNOWN 都必须让整个世界停下来（一格不写），所以「墙为空」就等于「可以施工」。
     */
    private static List<EndPodiumTemplate.Cell> blocked(EndPodiumTemplate.Plan plan,
                                                        EndPodiumTemplate.Cell override, String state) {
        List<EndPodiumTemplate.Cell> unknown = new ArrayList<>();
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            String current = override != null && cell.equals(override) ? state : "minecraft:air";
            if (EndPodiumTemplate.classify(current) == EndPodiumTemplate.Verdict.UNKNOWN) {
                unknown.add(cell);
            }
        }
        return unknown;
    }

    /** 预检通过 = 会真的动笔；有一条 UNKNOWN 就一格不写。 */
    private static boolean wouldWrite(EndPodiumTemplate.Plan plan,
                                      EndPodiumTemplate.Cell override, String state) {
        return blocked(plan, override, state).isEmpty();
    }

    /** 要写的格子里第一个该方块 id 的格（找不到就抛，避免测试静默跳过）。 */
    private static EndPodiumTemplate.Cell firstCellWithBlockId(EndPodiumTemplate.Plan plan, String blockId) {
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            if (EndPodiumTemplate.blockId(cell.state()).equals(blockId)) {
                return cell;
            }
        }
        throw new IllegalStateException("计划里没有 " + blockId + " 的格子");
    }

    /** 把一个状态串的水平朝向换成反方向。 */
    private static String oppositeFacing(String state) {
        String[][] pairs = {{"north", "south"}, {"south", "north"}, {"east", "west"}, {"west", "east"}};
        for (String[] pair : pairs) {
            String from = "facing=" + pair[0];
            if (state.contains(from)) {
                return state.replace(from, "facing=" + pair[1]);
            }
        }
        throw new IllegalStateException("状态串没有 facing: " + state);
    }

    private static EndPodiumTemplate.Cell cellAt(EndPodiumTemplate.Plan plan, int x, int y, int z) {
        for (EndPodiumTemplate.Cell cell : plan.cells()) {
            if (cell.x() == x && cell.y() == y && cell.z() == z) {
                return cell;
            }
        }
        return null;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x << 42) | ((long) y << 21) | (long) z;
    }

    /** {@code minecraft:id} 或 {@code minecraft:id[k=v,...]}，键值都是小写字母数字下划线。 */
    private static boolean isParsableState(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        if (!id.matches("minecraft:[a-z0-9_]+")) {
            return false;
        }
        if (bracket < 0) {
            return true;
        }
        if (!state.endsWith("]")) {
            return false;
        }
        String properties = state.substring(bracket + 1, state.length() - 1);
        for (String pair : properties.split(",")) {
            if (!pair.matches("[a-z0-9_]+=[a-z0-9_]+")) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
