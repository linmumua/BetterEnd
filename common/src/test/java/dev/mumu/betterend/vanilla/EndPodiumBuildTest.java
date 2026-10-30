package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndPodiumBuild}：中心祭坛施工的运行时规则。
 *
 * <p>这一组用例针对的是「错了就毁世界或毁玩家财产」的行为，不是外观：
 * <ul>
 *   <li><b>每批最多 1024 格、批间延迟 1 tick</b>，而且一个任务只碰自己那个区块；</li>
 *   <li><b>快照没成功，一格都不写</b>；快照覆盖每一格会被写的格子，且不含保护区；</li>
 *   <li><b>未知玩家方块/容器 → 整个世界停止</b>，不是跳过那一格拼一座残楼；</li>
 *   <li><b>施工期间被改动 → 立刻中止且不覆盖</b>；</li>
 *   <li><b>只有全部批次成功才落完成标记</b>，第二次运行什么都不写（重启不重建）；</li>
 *   <li><b>返回门坐标最多试 5 次</b>，不会无限重试。</li>
 * </ul>
 * 世界、调度、PDC、磁盘全部用假实现注入，所以这一组能在没有服务端的情况下跑。
 */
class EndPodiumBuildTest {

    private static final int PORTAL_X = 0;
    private static final int PORTAL_Y = 65;
    private static final int PORTAL_Z = 0;

    // --- 假世界 ---------------------------------------------------------------------------------

    private record Written(int task, int x, int y, int z, String state) {
    }

    /** 方块表 + 写入记录（写入记录带「第几个区域任务」标签，用来核对分批与区块归属）。 */
    private static final class FakeArea implements EndPodiumBuild.Area {
        final Map<String, String> states = new HashMap<>();
        final Set<String> tiles = new HashSet<>();
        final List<Written> writes = new ArrayList<>();
        int task;

        @Override
        public String state(int x, int y, int z) {
            return states.getOrDefault(key(x, y, z), "minecraft:air");
        }

        @Override
        public void set(int x, int y, int z, String state) {
            writes.add(new Written(task, x, y, z, state));
            states.put(key(x, y, z), state);
        }

        @Override
        public boolean tileState(int x, int y, int z) {
            return tiles.contains(key(x, y, z));
        }

        void put(int x, int y, int z, String state) {
            states.put(key(x, y, z), state);
        }

        String at(int x, int y, int z) {
            return state(x, y, z);
        }

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }

    /** 调度：区块总是已就绪，区域任务按排队顺序执行，并记录「哪个区块、延迟多少」。 */
    private static final class FakeDispatch implements EndPodiumBuild.Dispatch {
        final FakeArea area;
        final Deque<Runnable> queue = new ArrayDeque<>();
        /** 每个区域任务的「cx,cz,delay」。 */
        final List<String> regions = new ArrayList<>();
        int tasks;

        FakeDispatch(FakeArea area) {
            this.area = area;
        }

        @Override
        public void region(int chunkX, int chunkZ, long delayTicks, Runnable task) {
            regions.add(chunkX + "," + chunkZ + "," + delayTicks);
            queue.add(task);
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        /** 跑完所有已排的任务（其中新排的也照顺序跑）。 */
        void drain() {
            Runnable next;
            while ((next = queue.poll()) != null) {
                area.task = ++tasks;
                next.run();
            }
        }

        /** 只跑一个任务；用来在流程中间插入「世界卸载」这类外部变化。 */
        void drainOne() {
            Runnable next = queue.poll();
            if (next == null) {
                return;
            }
            area.task = ++tasks;
            next.run();
        }

        int chunkXOf(int task) {
            return Integer.parseInt(regions.get(task - 1).split(",")[0]);
        }

        int chunkZOf(int task) {
            return Integer.parseInt(regions.get(task - 1).split(",")[1]);
        }

        long delayOf(int task) {
            return Long.parseLong(regions.get(task - 1).split(",")[2]);
        }
    }

    private static final class FakeMarker implements EndPodiumBuild.Marker {
        final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String read(String key) {
            return values.get(key);
        }

        @Override
        public void write(String key, String value) {
            values.put(key, value);
        }
    }

    private static final class FakeSaver implements EndPodiumBuild.Saver {
        final List<List<EndPodiumBackup.Cell>> snapshots = new ArrayList<>();
        boolean fail;
        /** 备份成功那一刻要做的外部动作（模拟「备份之后世界被别人改了」）。 */
        Runnable afterSave;

        @Override
        public Path save(List<EndPodiumBackup.Cell> cells) throws IOException {
            if (fail) {
                throw new IOException("磁盘满");
            }
            snapshots.add(List.copyOf(cells));
            if (afterSave != null) {
                afterSave.run();
            }
            return Paths.get("podium-backups", "world", "job.snapshot.gz");
        }
    }

    private static final class FakeReport implements EndPodiumBuild.Report {
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warning(String message) {
            warnings.add(message);
        }

        String lastWarning() {
            return warnings.isEmpty() ? null : warnings.get(warnings.size() - 1);
        }
    }

    /**
     * 原生核心替身：记录它被调用时已经落下多少格、以及是否发生在装饰之前。
     * 生产实现是 {@code MuDragonFightBridge#anchor}，这里只钉「顺序 + 有限重试」。
     */
    private static final class FakeCore implements EndPodiumBuild.Core {
        final List<Integer> writesAtCall = new ArrayList<>();
        FakeArea area;
        int calls;
        boolean fail;
        boolean retryOnce;
        boolean verifyFails;
        EndPodiumBuild.Core.Result override;

        @Override
        public EndPodiumBuild.Core.Result place() {
            calls++;
            writesAtCall.add(area == null ? -1 : area.writes.size());
            if (override != null) {
                return override;
            }
            if (fail) {
                return EndPodiumBuild.Core.Result.fail("桥接不可用");
            }
            if (retryOnce && calls == 1) {
                return EndPodiumBuild.Core.Result.retry("区块还没就绪");
            }
            return EndPodiumBuild.Core.Result.done();
        }

        @Override
        public EndPodiumBuild.Core.Result verify() {
            return verifyFails ? EndPodiumBuild.Core.Result.fail("复活阶段变了")
                    : EndPodiumBuild.Core.Result.done();
        }
    }

    /** 一次测试的完整装配。 */
    private static final class Harness {
        final FakeArea area = new FakeArea();
        final FakeDispatch dispatch = new FakeDispatch(area);
        final FakeMarker marker = new FakeMarker();
        final FakeSaver saver = new FakeSaver();
        final FakeCore core = new FakeCore();
        final FakeReport report = new FakeReport();
        final List<String> events = new ArrayList<>();
        final EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);

        Harness() {
            core.area = area;
            // 原生核心在那：门环坐标上的 4 格基岩柱（EndPodiumFeature#place 的最后一段）。
            for (int dy = 0; dy < EndPodiumBuild.PILLAR_HEIGHT; dy++) {
                area.put(PORTAL_X, PORTAL_Y + dy, PORTAL_Z, "minecraft:bedrock");
            }
            // 净空区在真实新世界里是天然末地石：这里铺上，净空阶段才真的会落笔。
            for (EndPodiumTemplate.Cell cell : plan.clearance()) {
                area.put(cell.x(), cell.y(), cell.z(), "minecraft:end_stone");
            }
        }

        EndPodiumBuild build() {
            return new EndPodiumBuild(plan, area, dispatch, marker, saver, core, report,
                    () -> events.add("placed"), () -> events.add("failed"),
                    Math.floorDiv(PORTAL_X, 16), Math.floorDiv(PORTAL_Z, 16),
                    EndPodiumTemplate.encode(new EndPodiumTemplate.Layout(
                            EndPodiumTemplate.LAYOUT_VERSION, 1L,
                            PORTAL_X, PORTAL_Y, PORTAL_Z)));
        }

        /** 跑到结束（成功或中止）。 */
        EndPodiumBuild run() {
            EndPodiumBuild build = build();
            build.start();
            dispatch.drain();
            return build;
        }
    }

    private final Harness harness = new Harness();

    // --- 分批与区块 -------------------------------------------------------------------------------

    /**
     * 每个区域任务最多 1024 格、批间延迟 1 tick，而且**一个任务只写自己那个区块的格**：
     * 这是「按区块分桶、不跨区读取、不硬编码 Folia 区域大小」的可观察形式。
     */
    @Test
    void everyRegionTaskWritesAtMost1024CellsInItsOwnChunk() {
        harness.run();

        assertFalse(harness.area.writes.isEmpty(), "应该真的写了格子");
        Map<Integer, Integer> perTask = new HashMap<>();
        for (Written written : harness.area.writes) {
            perTask.merge(written.task(), 1, Integer::sum);
            assertEquals(EndPodiumTemplate.ChunkKey.of(written.x(), written.z()).x(),
                    harness.dispatch.chunkXOf(written.task()),
                    "第 " + written.task() + " 个任务写了区块外的格 (" + written.x() + "," + written.z() + ")");
            assertEquals(EndPodiumTemplate.ChunkKey.of(written.x(), written.z()).z(),
                    harness.dispatch.chunkZOf(written.task()));
        }
        for (Map.Entry<Integer, Integer> entry : perTask.entrySet()) {
            assertTrue(entry.getValue() <= EndPodiumBuild.BATCH_CELLS,
                    "第 " + entry.getKey() + " 个任务写了 " + entry.getValue() + " 格");
        }
        assertTrue(harness.dispatch.regions.size() > 4, "29×29×27 的体积应该被拆成多批");
        int firstWrite = harness.area.writes.get(0).task();
        assertEquals(0L, harness.dispatch.delayOf(firstWrite), "第一批落笔立即开始");
        for (Integer task : perTask.keySet()) {
            if (task == firstWrite) {
                continue;
            }
            assertEquals(EndPodiumBuild.BATCH_DELAY_TICKS, harness.dispatch.delayOf(task),
                    "第 " + task + " 批必须延迟一 tick，避免连续占住区域线程");
        }
    }

    /** 原版家具（基岩柱/基岩圈/返回门/火把/龙蛋落点）一格都不许写。 */
    @Test
    void neverWritesInsideTheVanillaFurniture() {
        harness.run();
        for (Written written : harness.area.writes) {
            assertFalse(EndPodiumTemplate.isFurniture(PORTAL_X, PORTAL_Y, PORTAL_Z,
                            written.x(), written.y(), written.z()),
                    "家具格被写了 (" + written.x() + "," + written.y() + "," + written.z() + ")");
        }
    }

    // --- 阶段顺序与核心 ----------------------------------------------------------------------------

    /**
     * 净空 → 原生核心 → 装饰：核心被调用时**净空已经全部落下、装饰一格都还没写**。
     *
     * <p>这是原生 {@code spawnExitPortal} 的安全前提：它的写区（半径 3.5）会被
     * {@code destroyBlock(..., true)} 打成掉落物，所以必须先清、再让原生写，最后才是装饰。
     */
    @Test
    void clearsFirstThenPlacesTheCoreThenDecorates() {
        harness.run();

        assertEquals(1, harness.core.calls, "核心只生成一次");
        assertEquals(1, harness.core.writesAtCall.size());
        assertEquals(harness.plan.clearance().size(), harness.core.writesAtCall.get(0),
                "核心生成时应该只落下了净空格");
        assertTrue(harness.events.contains("placed"), "整轮成功后要标完成");
        assertEquals(EndPodiumBuild.PLACED, harness.marker.values.get(EndPodiumBuild.MARK_STATE));
        assertTrue(harness.marker.values.get(EndPodiumBuild.MARK_LAYOUT).startsWith("v2:seed="),
                "完成标记里要带版本与种子：" + harness.marker.values.get(EndPodiumBuild.MARK_LAYOUT));

        // 装饰确实在核心之后才写：核心调用时刻之后的写里必须有实心格（空气格本来就已是空气，不落笔）。
        int afterCore = harness.area.writes.size() - harness.core.writesAtCall.get(0);
        long solidDecorations = harness.plan.decorations().stream().filter(cell -> !cell.air()).count();
        assertEquals(solidDecorations, afterCore, "核心之后落笔的实心装饰格数");
    }

    /** 核心生成失败：不写装饰、不标完成，警告里带原因。 */
    @Test
    void coreFailureStopsBeforeDecorationsAndNeverMarksPlaced() {
        harness.core.fail = true;

        harness.run();

        assertTrue(harness.marker.values.isEmpty(), "核心失败不能标完成");
        assertEquals(List.of("failed"), harness.events);
        assertNotNull(harness.report.lastWarning());
        assertTrue(harness.report.lastWarning().contains("原生功能核心无法生成"),
                () -> harness.report.lastWarning());
        assertEquals(harness.plan.clearance().size(), harness.area.writes.size(),
                "装饰一格都不许写");
    }

    /** 可重试状态（区块未就绪）走有限重试：第二次成功即继续。 */
    @Test
    void retryableCoreIsRetriedThenSucceeds() {
        harness.core.retryOnce = true;

        harness.run();

        assertEquals(2, harness.core.calls);
        assertTrue(harness.events.contains("placed"));
    }

    /** 落 placed 之前的运行时状态核对失败：不标完成。 */
    @Test
    void verifyFailureBeforePlacedStopsTheCompletion() {
        harness.core.verifyFails = true;

        harness.run();

        assertTrue(harness.marker.values.isEmpty(), "核对失败不能标完成");
        assertEquals(List.of("failed"), harness.events);
    }

    // --- 快照 -----------------------------------------------------------------------------------

    /** 快照覆盖「每一格可能被写的格子」，并且全在保护区之外；备份成功之前一格都不写。 */
    @Test
    void snapshotCoversTheWholeBuildVolumeAndIsTakenBeforeAnyWrite() {
        harness.run();

        assertEquals(1, harness.saver.snapshots.size());
        List<EndPodiumBackup.Cell> snapshot = harness.saver.snapshots.get(0);
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertEquals(plan.writes().size(), snapshot.size(),
                "快照应该覆盖全部待写格（本锚点下没有需要跳过的功能方块）");
        Set<String> cells = new HashSet<>();
        for (EndPodiumBackup.Cell cell : snapshot) {
            assertTrue(cells.add(FakeArea.key(cell.x(), cell.y(), cell.z())), "快照里有重复坐标");
            assertFalse(EndPodiumTemplate.isFurniture(PORTAL_X, PORTAL_Y, PORTAL_Z,
                    cell.x(), cell.y(), cell.z()), "原版家具不该进快照（本服务不会写它们）");
        }
        assertTrue(harness.area.writes.size() > 0);
        assertTrue(harness.area.writes.get(0).task() > 1,
                "第一格落笔必须发生在预检批次之后（预检自己不开任务写）");
    }

    /** 备份失败：一格都不写、不落完成标记、记一条可读的警告。 */
    @Test
    void backupFailureWritesNothingAndDoesNotMarkPlaced() {
        harness.saver.fail = true;

        harness.run();

        assertTrue(harness.area.writes.isEmpty(), "快照没成功就不许写世界");
        assertTrue(harness.marker.values.isEmpty(), "失败不能标成功");
        assertEquals(List.of("failed"), harness.events);
        assertNotNull(harness.report.lastWarning());
        assertTrue(harness.report.lastWarning().contains("快照落盘失败"),
                () -> "警告里要讲清楚原因：" + harness.report.lastWarning());
    }

    /**
     * 所有权边界（一）：玩家在目标格放了一块**同 ID** 的末地石砖。同 ID 不构成所有权证据——
     * 首次施工不许把它当成「上次已经放好了」。
     */
    @Test
    void playerBricksWithTheSameBlockIdAreRejected() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell victim = firstCellWith(plan, "minecraft:end_stone_bricks");
        harness.area.put(victim.x(), victim.y(), victim.z(), "minecraft:end_stone_bricks");

        assertRejected(victim, "minecraft:end_stone_bricks");
    }

    /** 所有权边界（二）：同 ID 但朝向不同的玩家楼梯，也不是我们的。 */
    @Test
    void playerStairsWithAnotherFacingAreRejected() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell victim = firstCellWith(plan, "minecraft:end_stone_brick_stairs");
        harness.area.put(victim.x(), victim.y(), victim.z(), otherFacing(victim.state()));

        assertRejected(victim, "minecraft:end_stone_brick_stairs");
    }

    /**
     * 所有权边界（三）：与目标**逐字符完全相同**的状态串也不是所有权证据。只按 id 或只按状态串比较的
     * 修法都会漏掉这一条，所以单独钉一次。
     */
    @Test
    void playerBlockWithIdenticalStateStringIsStillRejected() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell victim = firstCellWith(plan, "minecraft:end_stone_brick_slab");
        harness.area.put(victim.x(), victim.y(), victim.z(), victim.state());

        assertRejected(victim, "minecraft:end_stone_brick_slab");
    }

    /**
     * 保护区外的基岩/门/蛋：跳过不覆盖（那是玩家或管理员放的功能方块），但要留下可查的警告，
     * 而不是静默在墙上留个洞。
     */
    @Test
    void functionBlockOutsideTheProtectionZoneIsSkippedWithAWarning() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell victim = plan.writes().get(0);
        harness.area.put(victim.x(), victim.y(), victim.z(), "minecraft:bedrock");

        harness.run();

        assertEquals("minecraft:bedrock", harness.area.at(victim.x(), victim.y(), victim.z()),
                "功能方块不许被覆盖");
        assertEquals(EndPodiumBuild.PLACED, harness.marker.values.get(EndPodiumBuild.MARK_STATE));
        assertTrue(harness.report.warnings.stream().anyMatch(line -> line.contains("功能方块")),
                () -> "跳过了格子就必须有警告：" + harness.report.warnings);
    }

    /** 三条所有权用例的共同断言：一格不写、不标成功、警告里出现那个方块。 */
    private void assertRejected(EndPodiumTemplate.Cell victim, String blockId) {
        harness.run();

        assertTrue(harness.area.writes.isEmpty(), "预检拒绝时一格都不许写");
        assertTrue(harness.marker.values.isEmpty(), "拒绝不能标成功");
        assertEquals(List.of("failed"), harness.events);
        String warning = harness.report.lastWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("停止改建"), warning);
        assertTrue(warning.contains(blockId), warning);
        assertEquals(blockId, EndPodiumTemplate.blockId(harness.area.at(victim.x(), victim.y(), victim.z())),
                "玩家放下的方块必须原地保留");
    }

    private static EndPodiumTemplate.Cell firstCellWith(EndPodiumTemplate.Plan plan, String blockId) {
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            if (EndPodiumTemplate.blockId(cell.state()).equals(blockId)) {
                return cell;
            }
        }
        throw new AssertionError("模板里没有 " + blockId);
    }

    /** 把状态串里的 {@code facing=} 换成另一个水平方向。 */
    private static String otherFacing(String state) {
        int at = state.indexOf("facing=");
        if (at < 0) {
            throw new AssertionError("状态串没有 facing：" + state);
        }
        int end = state.indexOf(']', at);
        String facing = state.substring(at + "facing=".length(), end);
        String other = switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
        return state.substring(0, at + "facing=".length()) + other + state.substring(end);
    }

    // --- 预检拒绝 ---------------------------------------------------------------------------------

    /**
     * 门环坐标上那根 4 格基岩柱不在（坐标被读错、或那里根本不是原生核心）：{@code podiumBodyProblem}
     * 必须明确报出来。这条挡住「凭一个坐标就在空地里盖一座塔」。
     *
     * <p>新契约里这项核验由 {@link EndPodiumBuild.Core} 在 {@code anchor} 之后调用（新世界是它自己刚
     * 生成的核心），施工层不再自己先读——所以这里直接钉那个纯谓词，而不是钉 {@code start()} 的行为。
     */
    @Test
    void podiumBodyProblemRejectsAPortalWithoutTheVanillaBody() {
        EndPodiumTemplate.Plan plan = harness.plan;
        assertNull(EndPodiumBuild.podiumBodyProblem(plan, harness.area), "基岩柱在的时候不该报问题");

        harness.area.put(PORTAL_X, PORTAL_Y + 2, PORTAL_Z, "minecraft:air"); // 基岩柱少了一格
        String problem = EndPodiumBuild.podiumBodyProblem(plan, harness.area);
        assertNotNull(problem);
        assertTrue(problem.contains("基岩柱"), problem);
    }

    /** 未知玩家方块（这里用别人放的末地石砖）→ 整个世界停止改制，前面的桶也不落笔。 */
    @Test
    void unknownPlayerBlockAbortsTheWholeWorldBeforeAnyWrite() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell victim = plan.writes().get(plan.writes().size() - 1);
        harness.area.put(victim.x(), victim.y(), victim.z(), "minecraft:end_stone_bricks");

        harness.run();

        assertTrue(harness.area.writes.isEmpty(), "预检发现玩家建筑时一格都不许写");
        assertTrue(harness.marker.values.isEmpty());
        assertEquals(List.of("failed"), harness.events);
        String warning = harness.report.lastWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("停止改建"), warning);
        assertTrue(warning.contains("end_stone_bricks"), warning);
    }

    /** 容器/方块实体要单独说明：这是玩家财产，不是「未知方块」。 */
    @Test
    void containerIsReportedAsBlockEntity() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        EndPodiumTemplate.Cell victim = plan.writes().get(0);
        harness.area.put(victim.x(), victim.y(), victim.z(), "minecraft:chest[facing=north,type=single]");
        harness.area.tiles.add(FakeArea.key(victim.x(), victim.y(), victim.z()));

        harness.run();

        assertTrue(harness.area.writes.isEmpty());
        String warning = harness.report.lastWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("容器") || warning.contains("方块实体"), warning);
    }

    // --- 落笔期间被改动 ---------------------------------------------------------------------------

    /** 备份之后、落笔之前有人在**中间批**里改了一格：立刻中止、不覆盖，后面的批不再执行。 */
    @Test
    void changeInAMiddleBatchAbortsWithoutOverwriting() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        List<EndPodiumTemplate.Cell> writes = plan.writes();
        EndPodiumTemplate.Cell middle = writes.get(writes.size() / 2);
        harness.saver.afterSave = () ->
                harness.area.put(middle.x(), middle.y(), middle.z(), "minecraft:obsidian");

        harness.run();

        assertEquals("minecraft:obsidian", harness.area.at(middle.x(), middle.y(), middle.z()),
                "施工期间别人的改动不许被覆盖");
        assertTrue(harness.marker.values.isEmpty(), "中止不能标成功");
        assertEquals(List.of("failed"), harness.events);
        String warning = harness.report.lastWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("施工期间"), warning);
        long solid = writes.stream().filter(cell -> !cell.air()).count();
        assertTrue(harness.area.writes.size() > 0, "中间批之前的部分应当已经落了笔");
        assertTrue(harness.area.writes.size() < solid,
                "应该在中间那格就停下，而不是把剩下半栋也写完：" + harness.area.writes.size() + " / " + solid);
    }

    // --- 成功与幂等 -------------------------------------------------------------------------------

    /**
     * 成功路径逐格核对：模板的每一个实心格都被写到、且状态串与模板一致，一格不多一格不少、没有重复写。
     * 「总写数对得上」比「没抛异常」才叫成功。
     */
    @Test
    void writesEverySolidTemplateCellExactlyOnce() {
        harness.run();

        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        Map<String, String> wanted = new HashMap<>();
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            if (cell.air()) {
                continue;
            }
            wanted.put(FakeArea.key(cell.x(), cell.y(), cell.z()), cell.state());
        }
        assertEquals(wanted.size() + harness.plan.clearance().size(), harness.area.writes.size(),
                "实心装饰格 + 净空格应当一格不多一格不少地写一遍");

        Set<String> writtenAt = new HashSet<>();
        Set<String> clearedAt = new HashSet<>();
        for (EndPodiumTemplate.Cell cell : plan.clearance()) {
            clearedAt.add(FakeArea.key(cell.x(), cell.y(), cell.z()));
        }
        for (Written written : harness.area.writes) {
            String key = FakeArea.key(written.x(), written.y(), written.z());
            assertTrue(writtenAt.add(key), "同一格被写了两次：" + key);
            if (clearedAt.contains(key)) {
                assertEquals("minecraft:air", written.state(), "净空格必须写成空气：" + key);
                continue;
            }
            assertEquals(wanted.get(key), written.state(), "写下的状态必须等于模板：" + key);
        }
        Set<String> expected = new HashSet<>(wanted.keySet());
        expected.addAll(clearedAt);
        assertEquals(expected, writtenAt, "模板的每一个实心格与净空格都该被写到");
        // 读回来的世界也对：随便挑一个模板实心格，世界里的状态就该是模板要给的那个状态串。
        EndPodiumTemplate.Cell probe = firstCellWith(plan, "minecraft:end_stone_bricks");
        assertEquals(probe.state(), harness.area.at(probe.x(), probe.y(), probe.z()));
    }

    /** 全部成功之后才写完成标记，并且只写一次。 */
    @Test
    void marksPlacedOnlyAfterEveryBatchSucceeded() {
        harness.run();

        assertEquals(EndPodiumBuild.PLACED, harness.marker.values.get(EndPodiumBuild.MARK_STATE));
        assertEquals(EndPodiumTemplate.encode(new EndPodiumTemplate.Layout(
                        EndPodiumTemplate.LAYOUT_VERSION, 1L, PORTAL_X, PORTAL_Y, PORTAL_Z)),
                harness.marker.values.get(EndPodiumBuild.MARK_LAYOUT),
                "完成标记里必须是带版本与种子的完整布局串");
        assertEquals(List.of("placed"), harness.events);
        assertTrue(harness.report.warnings.isEmpty(), "成功路径不该有警告：" + harness.report.warnings);
    }

    /** 世界里已有完成标记：重启后再触发一次也什么都不写（幂等）。 */
    @Test
    void secondRunOnAPlacedWorldWritesNothing() {
        harness.run();

        Harness again = new Harness();
        again.marker.values.putAll(harness.marker.values);
        again.area.states.putAll(harness.area.states);
        again.run();

        assertTrue(again.area.writes.isEmpty(), "第二次运行不该写任何一格");
        assertEquals(0, again.dispatch.tasks, "第二次运行连区域任务都不该排");
        assertEquals(List.of("placed"), again.events);
        assertTrue(again.report.infos.stream().anyMatch(line -> line.contains("完成过")),
                () -> "应该说明为什么不重建：" + again.report.infos);
    }

    /** 世界卸载/插件关闭：在途流程立刻停手，不排新任务、不落标记。 */
    @Test
    void stoppingMidwayLeavesNoMarker() {
        EndPodiumBuild build = harness.build();
        build.start();
        harness.dispatch.drainOne(); // 第一批预检
        build.stop();
        harness.dispatch.drain();

        assertTrue(harness.area.writes.isEmpty());
        assertTrue(harness.marker.values.isEmpty());
        assertTrue(harness.events.isEmpty(), "被外部终止不是成功也不是失败");
    }

    // --- 有限重试 ---------------------------------------------------------------------------------

    /**
     * 原生核心的可等状态最多试 {@link EndPodiumBuild#MAX_ATTEMPTS} 次；用完就中止、不标完成。
     * （旧版的「等返回门坐标」整条路径已经删除：布局由地形算出来，门是功能槽，不是锚点来源。）
     */
    @Test
    void retryableCoreGivesUpAfterMaxAttempts() {
        harness.core.override = EndPodiumBuild.Core.Result.retry("区块还没就绪");

        harness.run();

        assertEquals(EndPodiumBuild.MAX_ATTEMPTS, harness.core.calls);
        assertTrue(harness.marker.values.isEmpty(), "重试用完也不许标完成");
        assertEquals(List.of("failed"), harness.events);
        assertTrue(harness.report.warnings.stream().anyMatch(line -> line.contains("原生核心还没就绪")),
                () -> "每次重试都要留痕：" + harness.report.warnings);
    }

    // --- 复活后的局部修复 -------------------------------------------------------------------------

    /**
     * 局部修复只填「本服务管过、当前是空气」的模板外壳：模板本来是空气的格不管，还有方块的格不动。
     * 这一条挡住两种坏行为——把整栋建筑重建一遍，以及恢复玩家在别处主动拆掉的方块。
     */
    @Test
    void repairFillsOnlyAirCellsOfTheManagedShell() {
        List<EndPodiumTemplate.Cell> repairs = List.of(
                new EndPodiumTemplate.Cell(0, 66, 0, "minecraft:end_stone_bricks",
                        EndPodiumTemplate.Kind.SOLID),
                new EndPodiumTemplate.Cell(1, 66, 0, "minecraft:end_stone_bricks",
                        EndPodiumTemplate.Kind.SOLID),
                new EndPodiumTemplate.Cell(2, 66, 0, "minecraft:air", EndPodiumTemplate.Kind.AIR));
        harness.area.put(0, 66, 0, "minecraft:air");            // 被清空的外壳：要填回
        harness.area.put(1, 66, 0, "minecraft:end_stone");      // 还是天然地形：不是「被清空」，不动

        int filled = EndPodiumBuild.repair(repairs, harness.area);

        assertEquals(1, filled);
        assertEquals(1, harness.area.writes.size());
        assertEquals("minecraft:end_stone_bricks", harness.area.at(0, 66, 0));
        assertEquals("minecraft:end_stone", harness.area.at(1, 66, 0), "玩家/地形还在的格不许动");
        assertEquals("minecraft:end_stone", harness.area.at(2, 66, 0), "模板空气格不参与修复");
    }

    /**
     * 当前锚点下原版重建的 145 格全在保护区里，所以修复集合是空集：修复钩子接好了，但一次都不该写。
     * 这条把 {@link EndPodiumTemplate} 的几何结论和运行层的钩子绑在一起。
     */
    @Test
    void realPlanHasNothingToRepair() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
        assertTrue(plan.repairs().isEmpty(), "按本锚点，原版重建擦不到塔");
        assertEquals(0, EndPodiumBuild.repair(plan.repairs(), harness.area));
        assertTrue(harness.area.writes.isEmpty());
    }
}
