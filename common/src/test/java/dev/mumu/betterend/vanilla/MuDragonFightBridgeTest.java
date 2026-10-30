package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MuDragonFightBridge}：冻结接口 + 真实符号哨兵。
 *
 * <p>这一组<b>不启动服务端</b>，所以只验两件不需要世界就能验的事：
 * <ul>
 *   <li><b>接口被冻结成什么样</b>：状态集合、{@code ok()} 的语义、{@code Frame}/{@code Outcome} 的字段与
 *       空值约定。调用方（{@code EndPodiumShell} 的 3 个调用点）按这些语义分支，改一个就毁掉重试策略；</li>
 *   <li><b>不可用时绝不抛异常</b>：{@code available()} / {@code unavailableReason()} / {@code read()} 在没有
 *       服务端的测试进程里也必须老实返回，而不是把异常扔进施工线程。</li>
 * </ul>
 *
 * <p>{@link #symbolCanary()} 是<b>编译期</b>哨兵：它引用桥接用到的每一个 NMS/Bukkit 符号（字段、方法、
 * 类型），一旦服务端 jar 换了签名，<b>连测试都编译不过</b>，比运行期 {@code LinkageError} 早得多。
 * 它永远不会被调用，所以不需要也不应该加断言。
 */
class MuDragonFightBridgeTest {

    /** 状态集合是冻结契约：多一个少一个都要先改调用方的 switch。 */
    @Test
    void slotSetIsFrozen() {
        List<String> expected = List.of(
                "OK",
                "DISABLED_VERSION",
                "DISABLED_LINKAGE",
                "NO_DRAGON_FIGHT",
                "EXPECTED_MISMATCH",
                "TARGET_REJECTED",
                "CHUNK_NOT_LOADED",
                "CHUNK_NOT_OWNED",
                "WRITE_MISMATCH",
                "FRAME_MISSING");
        List<String> actual = Arrays.stream(MuDragonFightBridge.Status.values())
                .map(Enum::name)
                .toList();
        assertEquals(expected, actual, "Status 集合被冻结；改动必须同步 EndPodiumShell 的 switch");
    }

    /** {@code ok()} 只认 {@code OK}：其余任何状态都必须让调用方走失败分支。 */
    @Test
    void okOnlyForOk() {
        Location before = new Location(null, 1, 2, 3);
        Location after = new Location(null, 4, 5, 6);
        assertTrue(new MuDragonFightBridge.Outcome(
                MuDragonFightBridge.Status.OK, "fine", before, after).ok());
        for (MuDragonFightBridge.Status status : MuDragonFightBridge.Status.values()) {
            if (status == MuDragonFightBridge.Status.OK) {
                continue;
            }
            assertFalse(new MuDragonFightBridge.Outcome(status, "no", before, after).ok(),
                    status + " 不能被视为成功");
        }
    }

    /** 失败时 {@code before}/{@code after} 允许为 null，日志要能原样打印；成功时两者都必须有值。 */
    @Test
    void outcomeToleratesNullLocations() {
        MuDragonFightBridge.Outcome outcome = new MuDragonFightBridge.Outcome(
                MuDragonFightBridge.Status.CHUNK_NOT_LOADED, "区块还没加载", null, null);
        assertFalse(outcome.ok());
        assertEquals(MuDragonFightBridge.Status.CHUNK_NOT_LOADED, outcome.status());
        assertEquals("区块还没加载", outcome.detail());
        assertNull(outcome.before());
        assertNull(outcome.after());
    }

    /** {@code Frame.matchesRecorded} 是「门实物就是记录那一格」的判定，字段必须原样带出来。 */
    @Test
    void frameCarriesPatternGeometry() {
        Location center = new Location(null, 0, 96, 0);
        Location frontTopLeft = new Location(null, -3, 99, -3);
        MuDragonFightBridge.Frame frame = new MuDragonFightBridge.Frame(
                MuDragonFightBridge.Status.OK, "found", center, frontTopLeft, 7, 7, 7, true);
        assertEquals(MuDragonFightBridge.Status.OK, frame.status());
        assertEquals(center, frame.center());
        assertEquals(frontTopLeft, frame.frontTopLeft());
        assertEquals(7, frame.width());
        assertEquals(7, frame.height());
        assertEquals(7, frame.depth());
        assertTrue(frame.matchesRecorded());
        assertNotNull(frame.detail());
    }

    /**
     * 没有服务端时三条查询式 API 都必须老实返回 {@code false} / 文字 / {@code null}。
     *
     * <p>这条用例同时钉住「不可用就整体关」：{@code read} 在不可用时返回 {@code null}，
     * 于是调用方把它当「新世界」，而不是当成「记录是 0,0,0」去覆盖别人的门。
     */
    @Test
    void queriesNeverThrowAndFailClosed() {
        boolean available = MuDragonFightBridge.available();
        String reason = MuDragonFightBridge.unavailableReason();
        assertNotNull(reason, "unavailableReason 必须有内容");
        if (available) {
            assertNull(MuDragonFightBridge.read(null), "世界为 null 时必须返回 null");
        } else {
            assertTrue(reason.contains("："), "不可用原因应带状态码前缀：" + reason);
            assertNull(MuDragonFightBridge.read(null), "不可用时 read 必须返回 null");
        }
    }

    /** 不可用时 {@code anchor} 必须在动手前就返回，而不是写一半再报错。 */
    @Test
    void anchorFailsBeforeWritingWhenUnavailable() {
        MuDragonFightBridge.Outcome outcome = MuDragonFightBridge.anchor(
                null, null, new Location(null, 0, 96, 0), false);
        assertFalse(outcome.ok());
        assertNull(outcome.after(), "没写成时 after 必须是 null");
        assertTrue(
                outcome.status() == MuDragonFightBridge.Status.NO_DRAGON_FIGHT
                        || outcome.status() == MuDragonFightBridge.Status.DISABLED_VERSION
                        || outcome.status() == MuDragonFightBridge.Status.DISABLED_LINKAGE,
                "应当是「不可用」或「没有世界」：" + outcome.status());
    }

    /** 不可用时 {@code readFrame} 也必须拒绝，且 center 为 null（reanchor 正是靠这点中止）。 */
    @Test
    void readFrameFailsClosed() {
        MuDragonFightBridge.Frame frame = MuDragonFightBridge.readFrame(null);
        assertFalse(frame.matchesRecorded(), "没有实物就不能说匹配");
        assertNull(frame.center());
        assertTrue(frame.status() != MuDragonFightBridge.Status.OK);
    }

    // ------------------------------------------------------------------ 门平面成品判定（纯逻辑）

    /**
     * 门平面的种类切分必须是 20 门格 / 16 基岩圈 / 1 中心柱 / 12 四角。
     *
     * <p>这一格之差就是 P0：中心 {@code (0,0)} 被原生柱覆盖成基岩，按门格核对会让真实新世界必定失败。
     */
    @Test
    void gatePlaneSplitsInto20And16AndOneCenter() {
        int gate = 0;
        int rim = 0;
        int pillar = 0;
        int none = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                switch (MuDragonFightBridge.kindAt(dx, dz)) {
                    case GATE -> gate++;
                    case RIM -> rim++;
                    case PILLAR -> pillar++;
                    case NONE -> none++;
                }
            }
        }
        assertEquals(20, gate, "门格必须是 20（5×5 减四角再减中心那一格）");
        assertEquals(16, rim, "基岩圈必须是 16");
        assertEquals(1, pillar, "门平面上只有中心那一格是柱顶");
        assertEquals(12, none, "7×7 的四角共 12 格：原生距离判定不碰它们");
        assertEquals(MuDragonFightBridge.Kind.PILLAR, MuDragonFightBridge.kindAt(0, 0),
                "中心必须是柱顶而不是门格");
        assertEquals(MuDragonFightBridge.Kind.GATE, MuDragonFightBridge.kindAt(1, 1), "半径 2.5 内仍是门格");
        assertEquals(MuDragonFightBridge.Kind.RIM, MuDragonFightBridge.kindAt(2, 2), "半径 3.5 内是基岩圈");
    }

    /** 按原生写入顺序模拟门平面：先写门格与基岩圈，再由中心柱覆盖 {@code origin.above(0..3)}。 */
    private static Material[][][] nativeGatePlane(boolean active, boolean withPillarOverwrite) {
        int reach = 3;
        int height = 4; // PILLAR_HEIGHT：门平面 + 上方三格
        Material[][][] grid = new Material[reach * 2 + 1][height][reach * 2 + 1];
        // EndPodiumFeature#place 主循环：insideRim(d²≤6) 写门格（含中心），6<d²≤12 写基岩圈，其余不碰。
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr > 12) {
                    continue;
                }
                grid[dx + reach][0][dz + reach] =
                        distanceSqr <= 6 ? MuDragonFightBridge.gateMaterial(active) : Material.BEDROCK;
            }
        }
        if (withPillarOverwrite) {
            // 主循环之后的 `origin.above(0..3) → BEDROCK`，包括门平面那一格。
            for (int dy = 0; dy < height; dy++) {
                grid[reach][dy][reach] = Material.BEDROCK;
            }
        }
        return grid;
    }

    private static MuDragonFightBridge.MaterialLookup lookupOf(Material[][][] grid) {
        int reach = 3;
        return (dx, dy, dz) -> {
            if (dy < 0 || dy >= grid[0].length || Math.abs(dx) > reach || Math.abs(dz) > reach) {
                return Material.AIR;
            }
            Material material = grid[dx + reach][dy][dz + reach];
            return material == null ? Material.AIR : material;
        };
    }

    /** 原生顺序（门 → 柱覆盖）必须通过，而且核到的正是 20 门格 / 16 圈 / 4 柱格。 */
    @Test
    void nativeWriteOrderPasses() {
        for (boolean active : new boolean[] {false, true}) {
            MuDragonFightBridge.FrameCheck check =
                    MuDragonFightBridge.verifyGatePlane(active, lookupOf(nativeGatePlane(active, true)));
            assertTrue(check.ok(), "active=" + active + " 时成品判定不该失败：" + check.problems());
            assertEquals(20, check.gateCells(), "门格只能算 20 格（不含中心）");
            assertEquals(16, check.rimCells());
            assertEquals(4, check.pillarCells(), "中心柱要核 gateY..gateY+3 共 4 格");
        }
    }

    /**
     * 少掉柱覆盖就必须失败：这证明判定确实要求「中心是基岩」，而不是「中心也是门格」。
     *
     * <p>旧实现（21 格全按门格）等价于这一份模拟：中心被当成 {@code AIR}/{@code END_PORTAL} 才对，
     * 于是真实世界里原生柱写下的基岩会被判成不符——正是 P0 的现场。
     */
    @Test
    void missingPillarOverwriteIsRejected() {
        for (boolean active : new boolean[] {false, true}) {
            MuDragonFightBridge.FrameCheck check =
                    MuDragonFightBridge.verifyGatePlane(active, lookupOf(nativeGatePlane(active, false)));
            assertFalse(check.ok(), "active=" + active + "：中心没有柱覆盖时必须判定失败");
            assertTrue(
                    check.problems().stream().anyMatch(problem -> problem.contains("中心柱")),
                    "失败原因必须指向中心柱：" + check.problems());
        }
    }

    /** target 只支持原点列：x/z 不为 0 必须拒绝，而不是照 native 原点静默写到别处。 */
    @Test
    void targetMustStayOnOriginColumn() {
        assertNull(MuDragonFightBridge.coordinateProblem(0, 96, 0, -64, 320), "原点列应当通过");
        String offset = MuDragonFightBridge.coordinateProblem(1, 96, 0, -64, 320);
        assertNotNull(offset, "x=1 必须拒绝");
        assertTrue(offset.contains("原点列"), "原因要指出原点列：" + offset);
        assertNotNull(MuDragonFightBridge.coordinateProblem(0, 96, -8, -64, 320), "z=-8 必须拒绝");
        assertNotNull(MuDragonFightBridge.coordinateProblem(0, -62, 0, -64, 320),
                "太靠近底部边界必须拒绝（原生会写到 y-3）");
        assertNotNull(MuDragonFightBridge.coordinateProblem(0, 318, 0, -64, 320), "太靠近顶部边界必须拒绝");
    }

    /**
     * 柱晶识别必须 fail-closed：不可用 / 不是 CraftEnderCrystal / null 都只能返回 {@code false}，绝不抛。
     *
     * <p>这一条是「宁可一颗晶都不当成柱晶」的保险：判定错了会把玩家放的晶当柱晶清掉。
     */
    @Test
    void pillarCrystalFailsClosed() {
        assertFalse(MuDragonFightBridge.isPillarCrystal(null), "null 不是柱晶");
        // 没有服务端时版本闸门直接关掉：任何实体都不认。
        if (!MuDragonFightBridge.available()) {
            assertFalse(MuDragonFightBridge.isPillarCrystal(null));
        }
    }

    /**
     * section 尺寸查询必须「要么真值、要么 -1」，绝不猜。
     *
     * <p>集成层按它算区块分区；写死 8 会让 ±24 竞技场的所有权预检全错，猜 16 也只是撞对一次。
     */
    @Test
    void regionSectionChunkSizeNeverGuesses() {
        assertEquals(-1, MuDragonFightBridge.regionSectionChunkSize(null), "世界为 null 必须 -1");
        if (!MuDragonFightBridge.available()) {
            assertEquals(-1, MuDragonFightBridge.regionSectionChunkSize(null));
        }
    }

    // ------------------------------------------------------------------ 编译期符号哨兵

    /**
     * 桥接依赖的真实符号清单。<b>永远不会被调用</b>：只为让 javac 逐个解析这些成员。
     *
     * <p>清单与 {@link MuDragonFightBridge} 里实际用到的一一对应：少写一个，就少一道「服务端升级后
     * 才发现」的预警；多写一个不存在的，测试直接编译失败——这正是这里想要的效果。
     */
    @SuppressWarnings({"unused", "ConstantConditions"})
    private static void symbolCanary(World world, Location location) {
        // Bukkit 侧：版本锁与区域所有权判定，签名里不出现任何 NMS 类型。
        String minecraftVersion = Bukkit.getMinecraftVersion();
        boolean owned = Bukkit.isOwnedByCurrentRegion(location);

        // World → ServerLevel：MU 的 CraftWorld#getHandle() 协变返回 ServerLevel。
        if (!(world instanceof CraftWorld craft)) {
            return;
        }
        ServerLevel level = craft.getHandle();
        boolean chunkLoaded = world.isChunkLoaded(0, 0);

        // 龙战对象与它的持久化字段。
        EnderDragonFight fight = level.getDragonFight();
        BlockPos origin = fight.origin;
        BlockPos portal = fight.exitPortalLocation;
        fight.exitPortalLocation = new BlockPos(portal.getX(), portal.getY(), portal.getZ());
        fight.setDirty();
        fight.spawnExitPortal(true);

        // 门实物：图案 + 每格坐标 + 尺寸。
        BlockPattern.BlockPatternMatch match = fight.findExitPortal();
        BlockPos center = match.getBlock(3, 3, 3).getPos();
        BlockPos frontTopLeft = match.getFrontTopLeft();
        int width = match.getWidth();
        int height = match.getHeight();
        int depth = match.getDepth();

        // 位置与材质读取。
        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();
        org.bukkit.Material material = world.getBlockAt(blockX, blockY, blockZ).getType();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        // 柱晶标记：CraftEnderCrystal#getHandle() → EndCrystal#generatedByDragonFight。
        org.bukkit.entity.EnderCrystal crystal = null;
        boolean pillarCrystal = crystal instanceof org.bukkit.craftbukkit.entity.CraftEnderCrystal craftCrystal
                && craftCrystal.getHandle().generatedByDragonFight;

        // 动态分区尺寸：ServerLevel#regioniser#regionSectionChunkSize（public final int）。
        int sectionChunkSize = level.regioniser.regionSectionChunkSize;
        int sectionChunkShift = level.regioniser.sectionChunkShift;

        throw new AssertionError("符号哨兵不该被调用：" + minecraftVersion + owned + chunkLoaded + origin
                + center + frontTopLeft + width + height + depth + material + minY + maxY);
    }
}
