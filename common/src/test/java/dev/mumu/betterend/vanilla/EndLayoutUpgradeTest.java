package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndLayoutUpgrade} 的边界测试。
 *
 * <p>这些用例钉的是「为什么」而不是「是什么」：旧世界迁移最危险的两种失败是
 * <b>把玩家的方块当成自己的</b>与<b>没备份就动世界</b>，所以每个用例都对着一条明确的安全规则。
 */
class EndLayoutUpgradeTest {

    private static final long SEED = 424242L;
    private static final UUID WORLD = new UUID(0x1111L, 0x2222L);
    /** 纯地形场顶块：中心楼因此 floorY=64、gateY=61、baseY=59。 */
    private static final int SURFACE = 64;
    private static final int GATE_Y = SURFACE - EndPodiumTemplate.GATE_BELOW_FLOOR;

    // ------------------------------------------------------------------ 旧标记解析

    @Test
    void legacyMarkIsOnlyAPlainTriple() {
        // 旧版 EndPodiumBuild 写的是 "x,y,z"；解析它才是「旧世界」的证据。
        assertEquals(Optional.of(new EndLayoutUpgrade.Gate(0, 17, 0)),
                EndLayoutUpgrade.parseLegacyGate("0,17,0"));
        // 新版 v2 串不是旧标记：不能拿它冒充旧门坐标。
        assertEquals(Optional.empty(), EndLayoutUpgrade.parseLegacyGate("v2:seed=1:gate=0,61,0"));
        assertEquals(Optional.empty(), EndLayoutUpgrade.parseLegacyGate("placed"));
        assertEquals(Optional.empty(), EndLayoutUpgrade.parseLegacyGate("0,17"));
        assertEquals(Optional.empty(), EndLayoutUpgrade.parseLegacyGate(null));
    }

    // ------------------------------------------------------------------ 预览：旧/新高度

    @Test
    void previewReportsOldAndNewGeometryForEveryPillar() {
        FakeWorld world = new FakeWorld(legacyGate(0, 59, 0));
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertEquals(EndSpikes.COUNT, preview.pillars().size());
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(SEED);
        for (int index = 0; index < spikes.size(); index++) {
            EndSpikes.Spike spike = spikes.get(index);
            EndLayoutUpgrade.PillarDelta pillar = preview.pillars().get(index);
            int tier = YungPillars.tier(spike.height());
            int offset = YungPillars.crystalOffset(tier);
            // 旧：被压低的锚点；新：冻结在 70/3。两边都必须报出来，否则用户看不到柱子被抬高几格。
            assertEquals(spike.height() + 1 - offset, pillar.oldTopAnchorY(), "柱#" + index + " 旧上段");
            assertEquals(spike.height() + 1 - offset - YungPillars.BOTTOM_DROP, pillar.oldBottomAnchorY(),
                    "柱#" + index + " 旧下段");
            assertEquals(EndLayoutUpgrade.NEW_UPPER_BASE_Y, pillar.newTopAnchorY());
            assertEquals(EndLayoutUpgrade.NEW_LOWER_BASE_Y, pillar.newBottomAnchorY());
            assertEquals(pillar.newTopAnchorY() + offset, pillar.newCrystalY());
            assertTrue(pillar.riseY() > 0, "柱#" + index + " 应当被抬高");
            assertEquals(offset, pillar.newCrystalY() - pillar.newTopAnchorY());
        }
    }

    @Test
    void previewReportsBothPodiumAnchorsAndTheRangeTheyCover() {
        FakeWorld world = new FakeWorld(legacyGate(0, 59, 0));
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        EndLayoutUpgrade.PodiumDelta podium = preview.podium().orElseThrow();
        assertEquals(new EndLayoutUpgrade.Gate(0, GATE_Y, 0), podium.targetGate());
        assertEquals(SURFACE, podium.newFloorY());
        assertEquals(59 + EndPodiumTemplate.GATE_BELOW_FLOOR, podium.oldFloorY(), "旧楼板 = 旧门平面 + 3");
        assertTrue(preview.ranges().stream().anyMatch(range -> range.name().equals("旧中心楼")));
        assertTrue(preview.ranges().stream().anyMatch(range -> range.name().equals("新中心楼")));
        assertTrue(preview.ranges().stream().anyMatch(range -> range.name().startsWith("新柱#0")));
    }

    // ------------------------------------------------------------------ 预览：所有权证据

    @Test
    void previewRefusesWhenPillarsHaveNoOwnershipEvidence() {
        FakeWorld world = new FakeWorld(legacyGate(0, 59, 0));
        world.crystals = false; // 世界里没有任何「这根柱是本插件放的」记录
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertFalse(preview.allowed(), "没有柱所有权证据时绝不能允许自动迁移");
        assertTrue(preview.blockers().stream().allMatch(reason -> !reason.isEmpty()));
        assertEquals(EndSpikes.COUNT, preview.blockers().stream()
                .filter(reason -> reason.contains("没有可靠所有权证据")).count());
        // 关键：不靠材料或模板形状反推——柱的证据行必须明说「没有证据」。
        for (EndLayoutUpgrade.PillarDelta pillar : preview.pillars()) {
            assertFalse(pillar.owned());
            assertTrue(pillar.evidence().contains("没有登记的原版结构晶"));
        }
    }

    @Test
    void previewRefusesWhenLegacyGateIsUnreadable() {
        FakeWorld world = new FakeWorld("placed");
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertFalse(preview.allowed());
        assertTrue(preview.blockers().stream().anyMatch(reason -> reason.contains("旧门坐标")));
        assertTrue(preview.podium().isEmpty(), "算不出旧门坐标就不该编一个 podium 出来");
    }

    @Test
    void previewRefusesAWorldThatAlreadyHasTheNewLayout() {
        FakeWorld world = new FakeWorld("v2:seed=" + SEED + ":gate=0," + GATE_Y + ",0");
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertFalse(preview.allowed());
        assertTrue(preview.blockers().stream().anyMatch(reason -> reason.contains("已经落过新版布局")));
    }

    // ------------------------------------------------------------------ 预览：玩家冲突

    @Test
    void playerBlockInsideTheNewTowerFootprintIsReportedAsConflict() {
        EndPodiumTemplate.Plan target = EndPodiumTemplate.planAtSurface(SURFACE);
        EndPodiumTemplate.Cell cell = target.writes().get(0);
        FakeWorld world = new FakeWorld(legacyGate(0, 59, 0));
        // 恰好是模板也想要的方块也不算数：同 id 不构成所有权证据。
        world.place(cell.x(), cell.y(), cell.z(), "minecraft:stone_bricks");

        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertFalse(preview.allowed());
        assertTrue(preview.conflicts().stream().anyMatch(line -> line.contains("stone_bricks")
                        && line.contains(cell.x() + ", " + cell.y() + ", " + cell.z())),
                "玩家方块必须逐格报出来：" + preview.conflicts());
    }

    @Test
    void unverifiableOldTowerBlocksAreReportedInsteadOfClaimed() {
        // 旧楼自己摆的方块也必须是冲突：placed 标记只证明「这里建过楼」，不证明「这一格现在还是那座楼」。
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndPodiumTemplate.Plan oldPlan = EndPodiumTemplate.plan(0, GATE_Y, 0);
        EndPodiumTemplate.Plan newPlan = EndPodiumTemplate.planAtSurface(SURFACE);
        Map<String, String> was = new HashMap<>();
        for (EndPodiumTemplate.Cell cell : oldPlan.cells()) {
            was.put(cell.x() + "/" + cell.y() + "/" + cell.z(), cell.state());
        }
        for (EndPodiumTemplate.Cell cell : newPlan.writes()) {
            String before = was.get(cell.x() + "/" + cell.y() + "/" + cell.z());
            if (before != null) {
                world.place(cell.x(), cell.y(), cell.z(), before);
            }
        }

        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertFalse(preview.allowed(), "旧楼方块不能凭模板比对认领成自己的");
        assertFalse(preview.conflicts().isEmpty());
        assertTrue(preview.conflicts().stream().anyMatch(line -> line.contains("不认领")),
                "冲突文案必须明说「本服务不认领这一格」：" + preview.conflicts());
    }

    @Test
    void previewAllowsWhenEverythingIsVerifiable() {
        // 天然地形 + 登记的十颗晶 UUID + 三段齐备：这是唯一允许执行的形状。
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertEquals(List.of(), preview.conflicts());
        assertTrue(preview.allowed(), "应当允许执行，但有这些拒绝理由：" + preview.blockers());
    }

    // ------------------------------------------------------------------ 预览：整项与复活

    @Test
    void previewRefusesWhenOneOfTheThreeSectionsIsMissing() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts onlyPillarsAndPodium = EndLayoutUpgrade.Parts.of(
                pillars(world), podiumSection());
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, onlyPillarsAndPodium, world);

        assertFalse(preview.allowed(), "只改门坐标、不落新 PDC 不算修复");
        assertTrue(preview.blockers().stream().anyMatch(reason -> reason.contains("缺少 pdc")));
    }

    @Test
    void previewRefusesWhileRespawnIsRunning() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        world.respawn = true;
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        assertFalse(preview.allowed());
        assertTrue(preview.blockers().stream().anyMatch(reason -> reason.contains("复活中")));
    }

    @Test
    void blockedSectionCarriesItsReasonInsteadOfPassingAsDone() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = EndLayoutUpgrade.Parts.of(
                pillars(world), podiumSection(),
                EndLayoutUpgrade.Section.blocked("pdc", "中央侧本轮未提供新布局串"));
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);

        assertFalse(preview.allowed());
        assertTrue(preview.blockers().contains("中央侧本轮未提供新布局串"));
    }

    // ------------------------------------------------------------------ token

    @Test
    void tokenChangesWhenTheDecisionMaterialChanges() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Preview one = EndLayoutUpgrade.preview(world, fullParts(world), world);
        EndLayoutUpgrade.Preview same = EndLayoutUpgrade.preview(world, fullParts(world), world);
        assertEquals(one.token(), same.token(), "世界没变，token 必须稳定");

        FakeWorld other = new FakeWorld(legacyGate(0, GATE_Y, 0));
        other.surface = SURFACE + 1;
        EndLayoutUpgrade.Preview moved = EndLayoutUpgrade.preview(other, fullParts(other), other);
        assertNotEquals(one.token(), moved.token(), "锚点变了 token 必须变，否则人确认的是旧目标");
    }

    // ------------------------------------------------------------------ apply：门禁

    @Test
    void applyRejectsAMissingOrMismatchedToken() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);

        assertThrows(IllegalArgumentException.class,
                () -> EndLayoutUpgrade.apply(world, parts, preview, null, world));
        assertThrows(IllegalArgumentException.class,
                () -> EndLayoutUpgrade.apply(world, parts, preview, " ", world));
        assertThrows(IllegalArgumentException.class,
                () -> EndLayoutUpgrade.apply(world, parts, preview, "0000000000000000", world));
        assertTrue(world.saved.isEmpty(), "token 不对时一个备份都不该落，更不该写世界");
    }

    @Test
    void applyRefusesAPreviewThatIsNotAllowed() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        world.crystals = false;
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);

        assertThrows(IllegalStateException.class,
                () -> EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world));
        assertTrue(world.saved.isEmpty());
    }

    @Test
    void applyRefusesWhileRespawnStartedAfterPreview() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);
        world.respawn = true; // 预览之后、apply 之前开始复活

        assertThrows(IllegalStateException.class,
                () -> EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world));
        assertTrue(world.saved.isEmpty());
    }

    @Test
    void applyAbortsBeforeAnyWriteWhenBackupFails() throws IOException {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);
        world.failSave = true;

        assertThrows(IOException.class,
                () -> EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world));
        assertEquals(0, world.written.size(), "备份失败必须中止，一格都不能写");
    }

    @Test
    void applyRefusesBeforeBackupWhenAChunkIsNotOwned() {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);
        world.ownedChunks = false;

        assertThrows(IllegalStateException.class,
                () -> EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world));
        assertTrue(world.saved.isEmpty(), "未知块不动：连备份都不该假装做过");
        assertEquals(0, world.written.size());
    }

    // ------------------------------------------------------------------ apply：快照与分批

    @Test
    void applySnapshotsEveryTargetCellBeforeReturningAGrant() throws IOException {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);

        EndLayoutUpgrade.Grant grant = EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world);

        assertEquals(preview.totalWrites(), world.savedCells.size(),
                "快照必须覆盖全部目标格（全范围），而不是只覆盖第一批");
        assertEquals(world.backupPath(), grant.backupFile());
        assertTrue(grant.batches().stream().allMatch(batch -> batch.writes().size() <= EndLayoutUpgrade.BATCH_CELLS));
    }

    @Test
    void batchesNeverMixChunksAndRespectTheCellCap() {
        List<EndLayoutUpgrade.Write> writes = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            writes.add(new EndLayoutUpgrade.Write(i % 16, 64, 0, "minecraft:air"));
        }
        writes.add(new EndLayoutUpgrade.Write(100, 64, 100, "minecraft:air"));

        List<EndLayoutUpgrade.Batch> batches = EndLayoutUpgrade.batch(writes);

        assertTrue(batches.size() >= 3, "1500 格同区块 + 1 格异区块至少三批：" + batches.size());
        for (EndLayoutUpgrade.Batch batch : batches) {
            assertTrue(batch.writes().size() <= EndLayoutUpgrade.BATCH_CELLS);
            for (EndLayoutUpgrade.Write write : batch.writes()) {
                assertEquals(batch.chunkX(), write.x() >> 4);
                assertEquals(batch.chunkZ(), write.z() >> 4);
            }
        }
    }

    @Test
    void grantWritesNothingWhenOneCellDriftedSinceTheSnapshot() throws IOException {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);
        EndLayoutUpgrade.Grant grant = EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world);

        EndLayoutUpgrade.Batch batch = grant.batches().get(0);
        EndLayoutUpgrade.Write victim = batch.writes().get(0);
        world.place(victim.x(), victim.y(), victim.z(), "minecraft:diamond_block"); // 玩家在施工前改了一格

        EndLayoutUpgrade.Result result = grant.write(batch, world, world);

        assertFalse(result.ok(), "快照之后被改动过就不能继续");
        assertEquals(0, result.written());
        assertEquals(0, world.written.size(), "整批一格都不写，而不是跳过那一格继续");
        assertTrue(result.notes().stream().anyMatch(line -> line.contains("diamond_block")));
    }

    @Test
    void grantWritesEveryCellWhenNothingDrifted() throws IOException {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);
        EndLayoutUpgrade.Grant grant = EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world);

        int written = 0;
        for (EndLayoutUpgrade.Batch batch : grant.batches()) {
            EndLayoutUpgrade.Result result = grant.write(batch, world, world);
            assertTrue(result.ok(), String.join(" / ", result.notes()));
            written += result.written();
        }

        assertEquals(preview.totalWrites(), written);
        assertEquals(preview.totalWrites(), world.written.size());
    }

    @Test
    void grantRefusesABatchWhoseChunkIsNotOwned() throws IOException {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);
        EndLayoutUpgrade.Grant grant = EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world);

        world.ownedChunks = false;
        EndLayoutUpgrade.Result result = grant.write(grant.batches().get(0), world, world);

        assertFalse(result.ok());
        assertTrue(result.notes().stream().anyMatch(line -> line.contains("未知块不动")));
        assertEquals(0, world.written.size());
    }

    // ------------------------------------------------------------------ 收尾：龙战状态

    @Test
    void verifyPreservedCatchesALostDragonOrGateState() throws IOException {
        FakeWorld world = new FakeWorld(legacyGate(0, GATE_Y, 0));
        world.previouslyKilled = true;
        world.dragonId = Optional.of(new UUID(7L, 7L));
        EndLayoutUpgrade.Parts parts = fullParts(world);
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, parts, world);
        EndLayoutUpgrade.Grant grant = EndLayoutUpgrade.apply(world, parts, preview, preview.token(), world);

        assertEquals(List.of(), grant.verifyPreserved(world), "迁移只读龙战状态，必须原样保住");

        world.dragonId = Optional.of(new UUID(8L, 8L));
        assertTrue(grant.verifyPreserved(world).stream().anyMatch(line -> line.contains("龙 UUID")));
    }

    // ------------------------------------------------------------------ 命令层文案

    @Test
    void linesCarryTheFiveFactsTheOperatorNeeds() {
        FakeWorld world = new FakeWorld(legacyGate(0, 59, 0));
        world.crystals = false;
        EndLayoutUpgrade.Preview preview = EndLayoutUpgrade.preview(world, fullParts(world), world);

        String text = String.join("\n", EndLayoutUpgrade.lines(preview));

        assertTrue(text.contains("上段"), "旧新高度（柱）");
        assertTrue(text.contains("中心楼：旧门"), "旧新锚点（中心楼）");
        assertTrue(text.contains("影响范围"), "影响范围");
        assertTrue(text.contains("证据："), "来源证据");
        assertTrue(text.contains("拒绝理由："), "拒绝理由");
        assertTrue(text.contains("备份位置："), "备份位置");
        assertTrue(text.contains(preview.token()), "确认 token");
        assertFalse(text.contains("<gradient"), "纯文本行不该带 MiniMessage 标签");
    }

    // ------------------------------------------------------------------ 夹具

    /** 一份合法的旧世界标记。 */
    private static String legacyGate(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** 柱段：三根柱的示例目标格 + 同一颗晶体的搬移。 */
    private static EndLayoutUpgrade.Section pillars(FakeWorld world) {
        List<EndLayoutUpgrade.Write> writes = new ArrayList<>();
        List<UUID> handoffs = new ArrayList<>();
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(SEED);
        for (int index = 0; index < 3; index++) {
            EndSpikes.Spike spike = spikes.get(index);
            writes.add(new EndLayoutUpgrade.Write(spike.centerX(), 70, spike.centerZ(), "minecraft:obsidian"));
            writes.add(new EndLayoutUpgrade.Write(spike.centerX(), 69, spike.centerZ(), "minecraft:obsidian"));
            world.structureCrystal(index).ifPresent(handoffs::add);
        }
        return EndLayoutUpgrade.Section.of("pillars", writes, handoffs, List.of());
    }

    /** 中心楼段：两格示例目标。 */
    private static EndLayoutUpgrade.Section podiumSection() {
        return EndLayoutUpgrade.Section.of("podium",
                List.of(new EndLayoutUpgrade.Write(0, 59, 0, "minecraft:purpur_block"),
                        new EndLayoutUpgrade.Write(1, 59, 0, "minecraft:purpur_pillar")),
                List.of(), List.of());
    }

    private static EndLayoutUpgrade.Section pdcSection() {
        EndPodiumTemplate.Layout layout = new EndPodiumTemplate.Layout(
                EndPodiumTemplate.LAYOUT_VERSION, SEED, 0, GATE_Y, 0);
        return EndLayoutUpgrade.Section.of("pdc", List.of(), List.of(),
                List.of(EndLayoutUpgrade.MARK_LAYOUT + "=" + layout.encode(),
                        EndLayoutUpgrade.MARK_STATE + "=" + EndLayoutUpgrade.PLACED));
    }

    private static EndLayoutUpgrade.Parts fullParts(FakeWorld world) {
        return EndLayoutUpgrade.Parts.of(pillars(world), podiumSection(), pdcSection());
    }

    /**
     * 假世界：既是 {@link EndLayoutUpgrade.World}，也是 {@link EndLayoutUpgrade.Backup} 与
     * {@link EndLayoutUpgrade.Writer}，这样一次用例就能同时断言「备份了什么、写了什么」。
     */
    private static final class FakeWorld implements EndLayoutUpgrade.World,
            EndLayoutUpgrade.Backup, EndLayoutUpgrade.Writer {

        private final String layoutMark;
        private final Map<String, String> overrides = new HashMap<>();
        boolean crystals = true;
        boolean respawn;
        boolean previouslyKilled;
        boolean ownedChunks = true;
        boolean failSave;
        int surface = SURFACE;
        Optional<UUID> dragonId = Optional.empty();
        final List<EndPodiumBackup.Cell> savedCells = new ArrayList<>();
        final List<String> saved = new ArrayList<>();
        final List<EndLayoutUpgrade.Write> written = new ArrayList<>();

        FakeWorld(String layoutMark) {
            this.layoutMark = layoutMark;
        }

        void place(int x, int y, int z, String state) {
            overrides.put(key(x, y, z), state);
        }

        private static String key(int x, int y, int z) {
            return x + "/" + y + "/" + z;
        }

        @Override
        public UUID worldId() {
            return WORLD;
        }

        @Override
        public long seed() {
            return SEED;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 255;
        }

        @Override
        public int surfaceTopY(int x, int z) {
            return surface;
        }

        @Override
        public boolean owns(int chunkX, int chunkZ) {
            return ownedChunks;
        }

        @Override
        public String state(int x, int y, int z) {
            String override = overrides.get(key(x, y, z));
            if (override != null) {
                return override;
            }
            // 岛体在 56 层以下，中心楼/柱都在它上面：默认是空气（天然地形，可以覆盖）。
            return y < 56 ? "minecraft:end_stone" : "minecraft:air";
        }

        @Override
        public boolean tileState(int x, int y, int z) {
            return false;
        }

        @Override
        public String mark(String key) {
            if (EndLayoutUpgrade.MARK_LAYOUT.equals(key)) {
                return layoutMark;
            }
            if (EndLayoutUpgrade.MARK_STATE.equals(key)) {
                return layoutMark == null || layoutMark.isBlank() ? null : EndLayoutUpgrade.PLACED;
            }
            return null;
        }

        @Override
        public Optional<UUID> structureCrystal(int spikeIndex) {
            return crystals ? Optional.of(new UUID(0L, spikeIndex + 1L)) : Optional.empty();
        }

        @Override
        public EndLayoutUpgrade.Fight fight() {
            return new EndLayoutUpgrade.Fight(previouslyKilled, respawn, dragonId, Optional.of(
                    new EndLayoutUpgrade.Gate(0, GATE_Y, 0)));
        }

        @Override
        public Path pathFor(UUID jobId) {
            return Path.of("build", "tmp", "endlayoutupgrade", jobId + EndPodiumBackup.FILE_SUFFIX);
        }

        Path backupPath() {
            return pathFor(EndLayoutUpgrade.jobIdOf(WORLD, SEED, new EndLayoutUpgrade.Gate(0, GATE_Y, 0)));
        }

        @Override
        public Path save(UUID jobId, List<EndPodiumBackup.Cell> cells) throws IOException {
            if (failSave) {
                throw new IOException("磁盘满（测试）");
            }
            saved.add(jobId.toString());
            savedCells.clear();
            savedCells.addAll(cells);
            return pathFor(jobId);
        }

        @Override
        public void write(int x, int y, int z, String state) {
            written.add(new EndLayoutUpgrade.Write(x, y, z, state));
            overrides.put(key(x, y, z), state);
        }
    }
}
