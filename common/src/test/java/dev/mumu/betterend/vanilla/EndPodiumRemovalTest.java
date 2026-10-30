package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndPodiumRemoval} 的纯逻辑：逐格三分法、证据匹配（v2 与 legacy）、token 过期。
 *
 * <p>只测不需要世界的那几层，因为「删旧楼」最怕的三件事都在这里：把玩家改动删掉、拿一份不属于这个
 * 布局/这个世界的快照回滚、以及拿快照把门坑重新填死。世界读写路径（区域调度、分批写、封存）需要服务端，
 * 不在单测里假装覆盖。
 */
class EndPodiumRemovalTest {

    private static final int SURFACE = 64;
    /** 旧世界的门坐标（0.1.26 记录下来的 {@code x,y,z}）。 */
    private static final int[] LEGACY_GATE = {0, 61, 0};
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static EndPodiumTemplate.Plan plan() {
        return EndPodiumTemplate.planAtSurface(SURFACE);
    }

    private static EndPodiumTemplate.Plan legacyPlan() {
        return EndPodiumTemplate.plan(LEGACY_GATE[0], LEGACY_GATE[1], LEGACY_GATE[2]);
    }

    // --- 逐格三分法 -------------------------------------------------------------------------------

    /** 装饰格的三种归宿：回滚 / 已经是原状 / 玩家改过。 */
    @Test
    void decideRestoresOnlyCellsThatStillHoldTheTemplateTarget() {
        EndPodiumTemplate.Cell cell = firstSolidDecoration(plan());
        assertEquals(EndPodiumRemoval.Action.RESTORE,
                EndPodiumRemoval.decide(cell, cell.state(), "minecraft:end_stone"),
                "当前等于模板目标、原状是天然地形 → 回滚");
        assertEquals(EndPodiumRemoval.Action.ALREADY_ORIGINAL,
                EndPodiumRemoval.decide(cell, "minecraft:end_stone", "minecraft:end_stone"),
                "已经是原状 → 不必写");
        assertEquals(EndPodiumRemoval.Action.KEEP_PLAYER,
                EndPodiumRemoval.decide(cell, "minecraft:chest", "minecraft:end_stone"),
                "当前不是模板目标 → 玩家改动，保留");
        assertEquals(EndPodiumRemoval.Action.KEEP_PLAYER,
                EndPodiumRemoval.decide(cell, cell.state(), null),
                "快照里没有这一格 → 不是我们写的，保留");
    }

    /** 原版门家具/龙蛋即便出现在回滚集合里也一律保留（第二道保险）。 */
    @Test
    void decideNeverTouchesFunctionProtectedBlocks() {
        EndPodiumTemplate.Cell cell = firstSolidDecoration(plan());
        for (String functional : List.of("minecraft:bedrock", "minecraft:dragon_egg",
                "minecraft:end_portal[facing=north]", "minecraft:wall_torch[facing=north]")) {
            assertEquals(EndPodiumRemoval.Action.KEEP_PLAYER,
                    EndPodiumRemoval.decide(cell, functional, "minecraft:end_stone"), functional);
        }
    }

    /** 净空（门坑 / 7×7 井口 / 四晶座头部）永远不在可回滚集合里——填回去就把门埋了。 */
    @Test
    void clearanceCellsAreNeverRestorable() {
        EndPodiumTemplate.Plan plan = plan();
        for (EndPodiumTemplate.Cell cell : plan.decorations()) {
            assertFalse(EndPodiumTemplate.inPit(0, plan.gateY(), 0, cell.x(), cell.y(), cell.z()),
                    "装饰格不该落在门坑里: " + cell);
            assertFalse(EndPodiumTemplate.inWell(0, plan.gateY(), 0, cell.x(), cell.y(), cell.z()),
                    "装饰格不该落在井口里: " + cell);
        }
        List<EndPodiumTemplate.Cell> fromClearanceOnly = EndPodiumRemoval.restorableOf(plan,
                backupCells(plan.clearance()));
        assertTrue(fromClearanceOnly.isEmpty(), "只有净空格与家具格有快照时没有可回滚项");
    }

    /** 装饰交集：只收「快照里有 + 今天仍算装饰 + 不是空气」的格，不扩大格集。 */
    @Test
    void restorableIsTheIntersectionOfSnapshotAndDecorations() {
        EndPodiumTemplate.Plan plan = plan();
        List<EndPodiumTemplate.Cell> restorable =
                EndPodiumRemoval.restorableOf(plan, backupCells(plan.writes()));
        assertEquals(plan.decorations().stream().filter(cell -> !cell.air()).count(),
                restorable.size(), "写入集快照 → 可回滚的全部实心装饰");
        for (EndPodiumTemplate.Cell cell : restorable) {
            assertTrue(plan.decorations().contains(cell), "回滚集必须是装饰的子集: " + cell);
            assertFalse(cell.air(), "空气格不回滚: " + cell);
        }
    }

    // --- 证据匹配：v2 与 legacy -------------------------------------------------------------------

    /** 只含旧门家具的快照（{@code reanchor} 写的那种）不能被当成施工快照。 */
    @Test
    void furnitureOnlySnapshotIsRejected() {
        EndPodiumTemplate.Plan plan = plan();
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(plan,
                List.of(snapshot(WORLD, UUID.randomUUID(), plan.furniture())), Path.of("x"), WORLD);
        assertNotNull(choice.problem);
        assertEquals(EndPodiumRemoval.Status.SNAPSHOT_MISMATCH, choice.status);
    }

    /** 坐标集落在本布局写入集内、底面对齐、且覆盖装饰格的快照被选中。 */
    @Test
    void snapshotCoveringTheDecorationsIsChosen() {
        EndPodiumTemplate.Plan plan = plan();
        UUID job = UUID.randomUUID();
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(plan,
                List.of(snapshot(WORLD, job, plan.writes()),
                        snapshot(WORLD, UUID.randomUUID(), plan.furniture())), Path.of("snap"), WORLD);
        assertNull(choice.problem, () -> String.valueOf(choice.problem));
        assertEquals(job, choice.snapshot.jobId());
        assertEquals(Path.of("snap").resolve(job + EndPodiumBackup.FILE_SUFFIX), choice.path);
    }

    /** 两份快照并列覆盖同样多装饰格 → 歧义，拒绝（不「取最新」）。 */
    @Test
    void ambiguousSnapshotsAreRefused() {
        EndPodiumTemplate.Plan plan = plan();
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(plan,
                List.of(snapshot(WORLD, UUID.randomUUID(), plan.writes()),
                        snapshot(WORLD, UUID.randomUUID(), plan.writes())), Path.of("snap"), WORLD);
        assertEquals(EndPodiumRemoval.Status.SNAPSHOT_MISMATCH, choice.status);
        assertTrue(choice.problem.contains("并列"), choice.problem);
    }

    /**
     * 重叠的「两轮施工」快照：覆盖数相同，按「仍等于模板目标的格最少」选更早/更干净的一轮，
     * 而不是随便挑一个（也不因为并列就永远清不了）。
     */
    @Test
    void equallyCoveringSnapshotsPreferTheCleanestRound() {
        EndPodiumTemplate.Plan plan = plan();
        UUID dirtyJob = UUID.randomUUID();
        UUID cleanJob = UUID.randomUUID();
        // 脏的那份：所有格都「已经是模板目标」（说明那一轮真的写下去过）
        EndPodiumBackup.Snapshot dirty = snapshot(WORLD, dirtyJob, plan.writes());
        // 干净的那份：同样的坐标集，但记录的原状是天然地形（那一轮还没写/刚开始）
        List<EndPodiumBackup.Cell> cleanCells = new ArrayList<>();
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            cleanCells.add(new EndPodiumBackup.Cell(cell.x(), cell.y(), cell.z(),
                    "minecraft:end_stone"));
        }
        EndPodiumBackup.Snapshot clean = new EndPodiumBackup.Snapshot(WORLD, cleanJob,
                List.copyOf(cleanCells));
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(plan, List.of(dirty, clean),
                Path.of("snap"), WORLD);
        assertNull(choice.problem, () -> String.valueOf(choice.problem));
        assertEquals(cleanJob, choice.snapshot.jobId(), "选更干净的一轮");
    }

    /** 别的世界的快照（头里 worldUUID 不同）不能拿来清这个世界。 */
    @Test
    void snapshotFromAnotherWorldIsRejected() {
        EndPodiumTemplate.Plan plan = plan();
        UUID otherWorld = UUID.randomUUID();
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(plan,
                List.of(snapshot(otherWorld, UUID.randomUUID(), plan.writes())), Path.of("snap"), WORLD);
        assertEquals(EndPodiumRemoval.Status.SNAPSHOT_MISMATCH, choice.status);
        assertTrue(choice.problem.contains("世界不符"), choice.problem);
    }

    /** 别的布局（门坐标不同 → 盒子不对齐）的快照被拒绝。 */
    @Test
    void snapshotFromAnotherLayoutIsRejected() {
        EndPodiumTemplate.Plan plan = plan();
        EndPodiumTemplate.Plan other = EndPodiumTemplate.planAtSurface(SURFACE + 40);
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(plan,
                List.of(snapshot(WORLD, UUID.randomUUID(), other.writes())), Path.of("snap"), WORLD);
        assertEquals(EndPodiumRemoval.Status.SNAPSHOT_MISMATCH, choice.status);
    }

    /**
     * **legacy 路径可以成功**：旧标记 {@code "0,61,0"} + 按该门坐标算出的旧楼足迹 + 头里 worldUUID
     * 匹配的快照 → 选中。这条钉住「0.1.26 旧楼不是一律不可处理」。
     */
    @Test
    void legacyGateMatchesAYSnapshotTakenForThatTower() {
        EndPodiumTemplate.Plan legacy = legacyPlan();
        UUID job = UUID.randomUUID();
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(legacy,
                List.of(snapshot(WORLD, job, legacy.writes())), Path.of("snap"), WORLD);
        assertNull(choice.problem, () -> String.valueOf(choice.problem));
        assertEquals(job, choice.snapshot.jobId());
        assertFalse(EndPodiumRemoval.restorableOf(legacy, choice.snapshot.cells()).isEmpty());
    }

    /** legacy 证据不匹配（快照属于另一个门坐标的楼）→ 拒绝，并说清是哪一类不符。 */
    @Test
    void legacyMismatchIsRefused() {
        EndPodiumTemplate.Plan legacy = legacyPlan();
        EndPodiumTemplate.Plan other = EndPodiumTemplate.plan(0, 64, 0);
        EndPodiumRemoval.Choice choice = EndPodiumRemoval.chooseJob(legacy,
                List.of(snapshot(WORLD, UUID.randomUUID(), other.writes())), Path.of("snap"), WORLD);
        assertEquals(EndPodiumRemoval.Status.SNAPSHOT_MISMATCH, choice.status);
        assertNotNull(choice.problem);
    }

    /** 旧标记解析：只认 {@code x,y,z}，别的东西不能当门坐标。 */
    @Test
    void legacyGateParsingIsStrict() {
        assertEquals(-7, EndPodiumRemoval.parseLegacyGate(" 0, 61,-7 ")[2]);
        assertNull(EndPodiumRemoval.parseLegacyGate("v2:seed=1:gate=0,61,0"));
        assertNull(EndPodiumRemoval.parseLegacyGate("0,61"));
        assertNull(EndPodiumRemoval.parseLegacyGate("0,61,x"));
        assertNull(EndPodiumRemoval.parseLegacyGate(null));
    }

    // --- token ------------------------------------------------------------------------------------

    /** token 由证据决定：坐标集、job、布局串任一变化都必须让旧 token 失效。 */
    @Test
    void tokenExpiresWhenAnyEvidenceChanges() {
        EndPodiumTemplate.Plan plan = plan();
        List<EndPodiumTemplate.Cell> cells = EndPodiumRemoval.restorableOf(plan,
                backupCells(plan.writes()));
        UUID job = UUID.randomUUID();
        String token = EndPodiumRemoval.token("v2:seed=1:gate=0,61,0", job, cells);
        assertEquals(token, EndPodiumRemoval.token("v2:seed=1:gate=0,61,0", job, cells), "同样证据同 token");
        assertNotEquals(token, EndPodiumRemoval.token("v2:seed=2:gate=0,63,0", job, cells),
                "布局变了");
        assertNotEquals(token, EndPodiumRemoval.token("v2:seed=1:gate=0,61,0", UUID.randomUUID(), cells),
                "job 变了");
        assertNotEquals(token, EndPodiumRemoval.token("v2:seed=1:gate=0,61,0", job,
                cells.subList(0, cells.size() - 1)), "坐标集变了");
        assertEquals(64, token.length(), "SHA-256 十六进制");
    }

    /** token 也覆盖**当前现场状态**：同样的布局/job/格集，世界被改过 token 就得变。 */
    @Test
    void tokenExpiresWhenTheWorldStateChanges() {
        EndPodiumTemplate.Plan plan = plan();
        List<EndPodiumTemplate.Cell> cells = EndPodiumRemoval.restorableOf(plan,
                backupCells(plan.writes()));
        UUID job = UUID.randomUUID();
        String clean = EndPodiumRemoval.stateDigest(statesOf(cells, "minecraft:end_stone_bricks"));
        String dirty = EndPodiumRemoval.stateDigest(statesOf(cells, "minecraft:chest"));
        assertNotEquals(EndPodiumRemoval.token("legacy:0,61,0", job, cells, clean),
                EndPodiumRemoval.token("legacy:0,61,0", job, cells, dirty),
                "现场状态变了，token 必须失效（不只靠说明文字）");
    }

    /** 状态摘要与读的顺序无关（按区块并发读也不影响结果）。 */
    @Test
    void stateDigestIgnoresReadOrder() {
        EndPodiumTemplate.Plan plan = plan();
        List<EndPodiumTemplate.Cell> cells = EndPodiumRemoval.restorableOf(plan,
                backupCells(plan.writes()));
        java.util.Map<Long, String> forward = statesOf(cells, "minecraft:end_stone_bricks");
        java.util.Map<Long, String> reverse = new java.util.HashMap<>();
        List<EndPodiumTemplate.Cell> flipped = new ArrayList<>(cells);
        java.util.Collections.reverse(flipped);
        for (EndPodiumTemplate.Cell cell : flipped) {
            reverse.put(EndPodiumRemoval.pack(cell), "minecraft:end_stone_bricks");
        }
        assertEquals(EndPodiumRemoval.stateDigest(forward), EndPodiumRemoval.stateDigest(reverse));
    }

    // --- 工具 ------------------------------------------------------------------------------------

    private static java.util.Map<Long, String> statesOf(List<EndPodiumTemplate.Cell> cells,
                                                        String state) {
        java.util.Map<Long, String> map = new java.util.HashMap<>();
        for (EndPodiumTemplate.Cell cell : cells) {
            map.put(EndPodiumRemoval.pack(cell), state);
        }
        return map;
    }

    private static EndPodiumTemplate.Cell firstSolidDecoration(EndPodiumTemplate.Plan plan) {
        for (EndPodiumTemplate.Cell cell : plan.decorations()) {
            if (!cell.air()) {
                return cell;
            }
        }
        throw new AssertionError("计划里没有实心装饰格");
    }

    /** 用模板目标当「原状」造一份快照；坐标集就是这些格。 */
    private static EndPodiumBackup.Snapshot snapshot(UUID worldId, UUID job,
                                                     List<EndPodiumTemplate.Cell> cells) {
        return new EndPodiumBackup.Snapshot(worldId, job, backupCells(cells));
    }

    private static List<EndPodiumBackup.Cell> backupCells(List<EndPodiumTemplate.Cell> cells) {
        List<EndPodiumBackup.Cell> copy = new ArrayList<>(cells.size());
        for (EndPodiumTemplate.Cell cell : cells) {
            copy.add(new EndPodiumBackup.Cell(cell.x(), cell.y(), cell.z(), cell.state()));
        }
        return List.copyOf(copy);
    }
}
