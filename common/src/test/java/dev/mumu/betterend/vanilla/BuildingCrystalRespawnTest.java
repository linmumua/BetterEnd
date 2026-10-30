package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildingCrystalRespawn} 里不碰服务端就能测的判定：四座口径、四颗齐备、额外晶拒绝、两轮幂等、
 * 以及**归属不满足时一格不读、一颗不交**。
 *
 * <p>这些判定都在生产路径上，写错的后果分别是「三颗就召龙」「附近多一颗水晶被原版算进回血」
 * 「同一轮启动两次」「跨区同步读 289 个区块」。世界、实体、区域调度与真实原版阶段仍要靠实机验收，
 * 这里不假装覆盖了它们。
 */
class BuildingCrystalRespawnTest {

    private static final int GATE_Y = 61;

    /** 模板真实四座 → {@link BuildingCrystalRespawn.Blueprint}：半径 8、头部在楼板上一格（模板局部 y=6）。 */
    @Test
    void seatsComeFromTheTemplateRingAtRadiusEightAndHeadOneAboveTheFloor() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(0, GATE_Y, 0);
        BuildingCrystalRespawn.Blueprint blueprint = BuildingCrystalRespawn.blueprintOf(plan);

        assertEquals(GATE_Y, blueprint.gateY(), "门环平面必须原样带过来");
        assertEquals(plan.floorY(), blueprint.floorY(), "楼板高度必须原样带过来");
        assertEquals(0, blueprint.centerX(), "中心建筑的 X 就是门环平面的 X");
        assertEquals(0, blueprint.centerZ(), "中心建筑的 Z 就是门环平面的 Z");
        assertEquals(BuildingCrystalRespawn.SLOT_COUNT, blueprint.slots().size(), "YUNG 中央建筑是四座");

        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            int dx = slot.x() - blueprint.centerX();
            int dz = slot.z() - blueprint.centerZ();
            assertTrue((Math.abs(dx) == EndPodiumTemplate.SEAT_RADIUS && dz == 0)
                            || (dx == 0 && Math.abs(dz) == EndPodiumTemplate.SEAT_RADIUS),
                    "槽位必须在半径 8 的两个轴上：" + slot);
            assertEquals(plan.floorY() + 1, slot.y(), "晶体头部在楼板上一格");
            assertEquals(plan.baseY() + 6, slot.y(), "模板局部 y=6 才是晶体位（localY=5 是楼板）");
        }
    }

    /** 只放三颗：拒绝，但**不是**值得重试的那种拒绝（重试也不会变出第四颗）。 */
    @Test
    void threeCrystalsAreRefusedWithoutRetrying() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (int i = 0; i < 3; i++) {
            runtime.addAt(blueprint.slots().get(i));
        }

        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertFalse(round.started(), "三颗不能启动");
        assertFalse(round.retry(), "缺一颗不是归属问题，重试没有意义");
        assertFalse(round.loadCores(), "没齐备就不该去加载 289 个区块");
        assertTrue(runtime.started.isEmpty(), "不能调用原版");
    }

    /** 四座上额外还有一颗：原版会把它算进回血与中止，所以硬性拒绝，一颗都不交。 */
    @Test
    void aStrayCrystalNearTheBuildingIsRefused() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        runtime.add(new BuildingCrystalRespawn.Crystal(UUID.randomUUID(), 3, blueprint.floorY() + 1, 3));

        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertFalse(round.started(), "附近有额外水晶时不能启动");
        assertFalse(round.retry(), "多一颗水晶不会因为重试而消失");
        assertTrue(runtime.started.isEmpty(), "不能调用原版");
        assertNotNull(round.refusal(), "拒绝必须带原因");
        assertTrue(round.refusal().contains("不在四座上"), round.refusal());
    }

    /** 两轮检查必须交出**同一批真实实体**且只启动一次；本类不删除、不复制、不消耗。 */
    @Test
    void twoRoundsHandOverTheSameFourRealCrystalsExactlyOnce() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        List<UUID> expected = new ArrayList<>();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            expected.add(runtime.addAt(slot));
        }

        BuildingCrystalRespawn.Round first = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());
        BuildingCrystalRespawn.Round second = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertTrue(first.started(), "四颗齐备且归属满足时必须交给原版");
        assertTrue(second.started(), "第二轮同样能交出（真正的「已经在跑」由原版阶段判定）");
        assertEquals(2, runtime.startCalls, "每一次 round 只调用一次 start，不重复");
        assertEquals(expected, runtime.started.subList(0, 4), "按蓝图槽位顺序交出四颗真水晶");
        assertEquals(expected, runtime.started.subList(4, 8), "第二轮交出的还是同一批实体");
        assertEquals(4, runtime.crystals.size(), "本类从不删除任何水晶（消费由原版负责）");
    }

    /** 同一世界同时只允许一轮检查在跑：并发的第二次放置不排新的一轮。 */
    @Test
    void onlyOneCheckingRoundAtATime() {
        BuildingCrystalRespawn.Run run = new BuildingCrystalRespawn.Run();

        assertTrue(run.begin(), "第一轮能抢到");
        assertFalse(run.begin(), "检查中不允许再排一轮");
        assertEquals(BuildingCrystalRespawn.State.CHECKING, run.state.get());

        run.idle();
        assertTrue(run.begin(), "没启动的一轮退回可再触发");

        run.state.set(BuildingCrystalRespawn.State.STOPPED);
        assertFalse(run.begin(), "关闭/卸载后原地返回");
    }

    /**
     * 归属不满足：**一个方块都不读**、一次实体查询都不做、一颗水晶都不交。
     * 这正是「不满足有限重试后保晶拒绝，且不跨区同步读写」的可测部分。
     */
    @Test
    void ownershipFailureNeverReadsTheWorld() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        runtime.unowned.add(BuildingCrystalRespawn.chunkKey(blueprint.chunkX(), blueprint.chunkZ()));

        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertFalse(round.started(), "归属不满足不能启动");
        assertTrue(round.retry(), "归属可能刚合并完，值得等一次重试");
        assertEquals(0, runtime.airReads, "归属核验之后才允许读方块");
        assertEquals(0, runtime.solidReads, "归属核验之后才允许读方块");
        assertEquals(0, runtime.crystalScans, "归属核验之后才允许查实体");
        assertTrue(runtime.started.isEmpty(), "不能调用原版");
    }

    /** 原版 findExitPortal 要同步读的 289 个区块没加载全：先预加载，不许带着半加载去启动。 */
    @Test
    void unloadedCoreChunksAskForAPreloadInsteadOfStarting() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        // 中心 ±8 内的一个外圈区块：不在四座/门环覆盖的区块里，但原版门搜索会 getChunk 它。
        runtime.unloaded.add(BuildingCrystalRespawn.chunkKey(blueprint.chunkX() + 8, blueprint.chunkZ() + 8));

        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertFalse(round.started(), "核心区没加载全不能启动");
        assertTrue(round.loadCores(), "必须走一次有限预加载再复检");
        assertFalse(round.retry(), "这不是归属问题，不走 20 tick 的归属重试");
        assertTrue(runtime.started.isEmpty(), "不能调用原版");
    }

    /** origin ±16 属于原版 isArenaLoaded 的 isTickThreadFor 半径：不满足时阶段会静默卡住，所以拒绝。 */
    @Test
    void arenaRingOutsideTheCoreAlsoHasToBelongToTheSameRegion() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        runtime.unowned.add(BuildingCrystalRespawn.chunkKey(
                blueprint.chunkX() + BuildingCrystalRespawn.arenaChunkRadius(runtime.sectionChunks),
                blueprint.chunkZ()));

        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertFalse(round.started(), "阶段推进区不归当前 region 时不能启动");
        assertTrue(round.retry(), "归属不满足走有限重试");
        assertEquals(0, runtime.crystalScans, "归属核验之后才允许查实体");
    }

    /** 桥接给不出 section 尺寸（-1）时不能猜：直接拒绝，且这不是值得重试的拒绝。 */
    @Test
    void unknownRegionSectionSizeIsRefusedInsteadOfGuessing() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        runtime.sectionChunks = -1;

        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertFalse(round.started(), "分区尺寸未知时不能启动");
        assertFalse(round.retry(), "桥接不可用不会因为重试而变好");
        assertTrue(runtime.started.isEmpty(), "不能调用原版");
        assertEquals(0, runtime.crystalScans, "读不到分区就先停下，连实体都不用查");
        assertTrue(round.refusal().contains("region section"), round.refusal());
    }

    /** arena 半径必须跟着运行期 section 尺寸走：MU 26.3 的 16 ⇒ ±24，写死 8 会算成 ±16。 */
    @Test
    void arenaRadiusFollowsTheRuntimeSectionSize() {
        assertEquals(24, BuildingCrystalRespawn.arenaChunkRadius(16));
        assertEquals(16, BuildingCrystalRespawn.arenaChunkRadius(8));
        assertEquals(8, BuildingCrystalRespawn.CORE_CHUNK_RADIUS);
        assertEquals(16, BuildingCrystalRespawn.MU_26_3_REGION_SECTION_CHUNKS);
        assertEquals(289, BuildingCrystalRespawn.chunkSquare(BuildingCrystalRespawn.CORE_CHUNK_RADIUS));
        assertEquals(2401, BuildingCrystalRespawn.chunkSquare(24));
    }

    /** 最小可验证集：289 核心 + 12 个外围 section 代表（section=16），不是 2401。 */
    @Test
    void requiredChunksAreTheMinimalVerifiableSet() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        List<long[]> required = BuildingCrystalRespawn.requiredChunks(
                blueprint, BuildingCrystalRespawn.MU_26_3_REGION_SECTION_CHUNKS);

        assertEquals(289 + 12, required.size(), "289 核心 + 12 个外围 section 代表");
        int outsideCore = 0;
        for (long[] chunk : required) {
            int dx = (int) chunk[0] - blueprint.chunkX();
            int dz = (int) chunk[1] - blueprint.chunkZ();
            assertTrue(Math.abs(dx) <= 24 && Math.abs(dz) <= 24,
                    "代表区块也要落在 origin ±24 的 arena 内：(" + chunk[0] + ", " + chunk[1] + ")");
            if (Math.abs(dx) > 8 || Math.abs(dz) > 8) {
                outsideCore++;
            }
        }
        assertEquals(12, outsideCore, "核心之外正好 12 个代表");
    }

    /** 玩家门槛必须是「以 (0,128,0) 为球心、半径 192 的球」，不是 gateY 立方体。 */
    @Test
    void playerGateUsesTheVanillaOriginAndExactSphere() {
        assertEquals(0, BuildingCrystalRespawn.DRAGON_ORIGIN_X);
        assertEquals(128, BuildingCrystalRespawn.DRAGON_ORIGIN_Y);
        assertEquals(0, BuildingCrystalRespawn.DRAGON_ORIGIN_Z);
        assertEquals(192, BuildingCrystalRespawn.PLAYER_RADIUS_BLOCKS);

        int r = BuildingCrystalRespawn.PLAYER_RADIUS_BLOCKS;
        assertTrue(BuildingCrystalRespawn.withinSphere(192, 0, 0, r), "球面上的 192 格算在内");
        assertTrue(BuildingCrystalRespawn.withinSphere(0, 192, 0, r), "竖直方向同样是球");
        assertTrue(BuildingCrystalRespawn.withinSphere(0, -192, 0, r), "球是双向的");
        assertFalse(BuildingCrystalRespawn.withinSphere(193, 0, 0, r), "193 格出界");
        assertFalse(BuildingCrystalRespawn.withinSphere(136, 0, 136, r), "对角 192.3 出界（立方体会误判为在内）");
        assertFalse(BuildingCrystalRespawn.withinSphere(136, 136, 0, r), "三轴对角 192.3 出界");
        assertTrue(BuildingCrystalRespawn.withinSphere(110, 0, 110, r), "155.6 在内");
        assertTrue(BuildingCrystalRespawn.withinSphere(110, 110, 111, r), "190.8 在内");
        assertFalse(BuildingCrystalRespawn.withinSphere(111, 111, 111, r), "192.2 出界");
    }

    /** ticket 只在终态释放：观测窗口到期、调度失败都不撤票（否则玩家走开后阶段永远推不动）。 */
    @Test
    void ticketsAreReleasedOnlyOnTerminalPhases() {
        assertTrue(BuildingCrystalRespawn.releasesTickets("NONE"), "没在跑/已中止");
        assertTrue(BuildingCrystalRespawn.releasesTickets("END"), "打完了");
        assertFalse(BuildingCrystalRespawn.releasesTickets("PREPARING_TO_SUMMON_PILLARS"));
        assertFalse(BuildingCrystalRespawn.releasesTickets("SUMMONING_PILLARS"));
        assertFalse(BuildingCrystalRespawn.releasesTickets("SUMMONING_DRAGON"));
        assertFalse(BuildingCrystalRespawn.releasesTickets("START"));
        assertFalse(BuildingCrystalRespawn.releasesTickets(null), "读不到阶段不等于终态");
    }

    /** ticket 没拿全就绝不调用 start：部分持有等于阶段必冻，宁可保晶拒绝。 */
    @Test
    void incompleteHoldNeverCallsStart() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        List<long[]> required = BuildingCrystalRespawn.requiredChunks(blueprint, runtime.sectionChunks);
        List<long[]> held = new ArrayList<>(required);
        held.remove(held.size() - 1);

        BuildingCrystalRespawn.Round round =
                BuildingCrystalRespawn.handOff(blueprint, runtime, required, held, new FakeReport());

        assertFalse(round.started(), "ticket 不足不能启动");
        assertEquals(0, runtime.startCalls, "绝不能调用原版");
    }

    /** 没有玩家：原版阶段不会 tick，启动只会白清一次门。 */
    @Test
    void noPlayerNearbyNeverCallsStart() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        runtime.playerPresent = false;
        List<long[]> required = BuildingCrystalRespawn.requiredChunks(blueprint, runtime.sectionChunks);

        BuildingCrystalRespawn.Round round =
                BuildingCrystalRespawn.handOff(blueprint, runtime, required, required, new FakeReport());

        assertFalse(round.started(), "没有玩家不能启动");
        assertEquals(0, runtime.startCalls, "绝不能调用原版");
        assertTrue(round.refusal() != null && round.refusal().contains("没有玩家"), round.refusal());
    }

    /** 玩家在场 + ticket 全量：这时才允许交给原版。 */
    @Test
    void startOnlyAfterFullTicketHoldAndAPlayer() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        List<UUID> expected = new ArrayList<>();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            expected.add(runtime.addAt(slot));
        }
        List<long[]> required = BuildingCrystalRespawn.requiredChunks(blueprint, runtime.sectionChunks);
        List<long[]> held = runtime.holdArena(blueprint, required);

        BuildingCrystalRespawn.Round round =
                BuildingCrystalRespawn.handOff(blueprint, runtime, required, held, new FakeReport());

        assertEquals(required.size(), held.size(), "本用例的前提是全量持有");
        assertTrue(round.started(), "两关都过时必须启动");
        assertEquals(1, runtime.startCalls);
        assertEquals(expected, runtime.started);
    }

    /** 原版拒绝接管：水晶全部原样保留，本类不改世界、不自行消耗。 */
    @Test
    void vanillaRefusalKeepsEveryCrystal() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        runtime.startResult = false;

        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport());

        assertFalse(round.started(), "原版没接管就等于没启动");
        assertFalse(round.retry(), "原版拒绝不是归属问题");
        assertEquals(1, runtime.startCalls, "调用过一次并拿到 false");
        assertEquals(4, runtime.crystals.size(), "四颗原样留在世界里");
    }

    /** 调原版抛异常（含链接失败）：同样保晶、不替代、不补充，且结论不卡在「检查中」。 */
    @Test
    void startFailureIsCaughtAndKeepsTheCrystals() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }
        runtime.startThrows = true;

        FakeReport report = new FakeReport();
        BuildingCrystalRespawn.Round round = BuildingCrystalRespawn.round(blueprint, runtime, report);

        assertFalse(round.started());
        assertFalse(round.retry());
        assertTrue(report.warnings.stream().anyMatch(line -> line.contains("调用原版失败")), report.warnings.toString());
        assertEquals(4, runtime.crystals.size(), "四颗原样留在世界里");
    }

    /** 龙还没被杀过 / 龙还在 / 复活已经在跑：三种前提不成立都不启动。 */
    @Test
    void fightPreconditionsAreChecked() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }

        runtime.fight = new BuildingCrystalRespawn.Fight(false, true, true);
        assertFalse(BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport()).started(), "没杀过龙");

        runtime.fight = new BuildingCrystalRespawn.Fight(true, false, true);
        assertFalse(BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport()).started(), "复活已经在跑");

        runtime.fight = new BuildingCrystalRespawn.Fight(true, true, false);
        assertFalse(BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport()).started(), "龙还在");

        assertTrue(runtime.started.isEmpty(), "三种情况都不该调用原版");
    }

    /** 晶座头部被堵 / 支撑被换料：都不启动（原版放不下水晶）。 */
    @Test
    void blockedSeatOrMissingSupportIsRefused() {
        BuildingCrystalRespawn.Blueprint blueprint = blueprint();
        Fake runtime = new Fake();
        for (BuildingCrystalRespawn.Slot slot : blueprint.slots()) {
            runtime.addAt(slot);
        }

        runtime.blockedHead = true;
        assertFalse(BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport()).started(), "头部被堵");

        runtime.blockedHead = false;
        runtime.missingSupport = true;
        assertFalse(BuildingCrystalRespawn.round(blueprint, runtime, new FakeReport()).started(), "没有支撑");

        assertTrue(runtime.started.isEmpty(), "都不该调用原版");
    }

    /** 生产适配只认 {@code placedPlan}：未建成/旧布局的空白蓝图不许被当成「可以启动」。 */
    @Test
    void podiumAdapterUsesOnlyThePlacedPlan() {
        BuildingCrystalRespawn.Podium empty = world -> Optional.empty();
        assertTrue(empty.blueprint(null).isEmpty(), "没建成时不给出蓝图");

        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(0, GATE_Y, 0);
        BuildingCrystalRespawn.Podium placed = world -> Optional.of(BuildingCrystalRespawn.blueprintOf(plan));
        assertNotNull(placed.blueprint(null).orElseThrow(), "建成后给出持久化布局");
    }

    // ------------------------------------------------------------------ 假实现

    private static BuildingCrystalRespawn.Blueprint blueprint() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(0, GATE_Y, 0);
        return BuildingCrystalRespawn.blueprintOf(plan);
    }

    static final class Fake implements BuildingCrystalRespawn.Runtime {
        final List<BuildingCrystalRespawn.Crystal> crystals = new ArrayList<>();
        final Set<Long> unowned = new HashSet<>();
        final Set<Long> unloaded = new HashSet<>();
        final List<UUID> started = new ArrayList<>();
        int startCalls;
        int crystalScans;
        int airReads;
        int solidReads;
        boolean startResult = true;
        boolean startThrows;
        boolean blockedHead;
        boolean missingSupport;
        /** MU 26.3 的实测值；设成 -1 模拟「桥接不可用」。 */
        int sectionChunks = BuildingCrystalRespawn.MU_26_3_REGION_SECTION_CHUNKS;
        int holdCalls;
        int releaseCalls;
        int playerChecks;
        boolean playerPresent = true;
        /** 模拟「只拿到前 N 张 ticket」；默认全拿。 */
        int holdLimit = Integer.MAX_VALUE;
        List<long[]> lastRequired = List.of();
        BuildingCrystalRespawn.Fight fight = new BuildingCrystalRespawn.Fight(true, true, true);

        UUID addAt(BuildingCrystalRespawn.Slot slot) {
            return add(new BuildingCrystalRespawn.Crystal(UUID.randomUUID(), slot.x(), slot.y(), slot.z()));
        }

        UUID add(BuildingCrystalRespawn.Crystal crystal) {
            crystals.add(crystal);
            return crystal.id();
        }

        @Override
        public List<BuildingCrystalRespawn.Crystal> crystalsNear(BuildingCrystalRespawn.Blueprint blueprint) {
            crystalScans++;
            return List.copyOf(crystals);
        }

        @Override
        public boolean air(int x, int y, int z) {
            airReads++;
            return !blockedHead;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            solidReads++;
            return !missingSupport;
        }

        @Override
        public boolean owned(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean owned(UUID crystal) {
            return crystals.stream().anyMatch(known -> known.id().equals(crystal));
        }

        @Override
        public boolean ownedChunk(int chunkX, int chunkZ) {
            return !unowned.contains(BuildingCrystalRespawn.chunkKey(chunkX, chunkZ));
        }

        @Override
        public boolean loaded(int chunkX, int chunkZ) {
            return !unloaded.contains(BuildingCrystalRespawn.chunkKey(chunkX, chunkZ));
        }

        @Override
        public String phase() {
            return "NONE";
        }

        @Override
        public int regionSectionChunks() {
            return sectionChunks;
        }

        @Override
        public List<long[]> holdArena(BuildingCrystalRespawn.Blueprint blueprint, List<long[]> required) {
            holdCalls++;
            lastRequired = required;
            int limit = Math.min(holdLimit, required.size());
            return List.copyOf(required.subList(0, limit));
        }

        @Override
        public void releaseArena(List<long[]> held) {
            releaseCalls++;
        }

        @Override
        public boolean playerNearby(BuildingCrystalRespawn.Blueprint blueprint, int radiusBlocks) {
            playerChecks++;
            return playerPresent;
        }

        @Override
        public BuildingCrystalRespawn.Fight fight() {
            return fight;
        }

        @Override
        public boolean start(List<UUID> ids) {
            startCalls++;
            if (startThrows) {
                throw new LinkageError("桥接类缺失");
            }
            if (!startResult) {
                return false;
            }
            for (UUID id : ids) {
                boolean known = crystals.stream().anyMatch(crystal -> crystal.id().equals(id));
                if (!known) {
                    throw new AssertionError("交出了世界里不存在的实体（等于造替身）：" + id);
                }
            }
            started.addAll(ids);
            return true;
        }
    }

    static final class FakeReport implements BuildingCrystalRespawn.Report {
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
    }
}
