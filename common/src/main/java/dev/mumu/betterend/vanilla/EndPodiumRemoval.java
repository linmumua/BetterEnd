package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.DustWastelandsGenerator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 旧中心楼（YUNG {@code tower_initial} 钟楼）的**显式清理服务**：预览 + token 确认 + 按区块回滚。
 *
 * <h2>为什么是独立类</h2>
 * 停用钟楼之后，{@link EndPodiumShell} 不再注册事件、也不再启动（入口由插件侧决定）。清理旧楼不能
 * 靠「顺手把 shell 再启动一次」，所以本类不持有 shell、不注册监听、不读运行期阶段：只认两样东西——
 * 世界里的布局记录与磁盘上的原始快照。
 *
 * <h2>回滚边界（三层里只碰最外一层）</h2>
 * <ul>
 *   <li><b>只回滚装饰格</b>（{@link EndPodiumTemplate.Plan#decorations()}）：钟体、砖墙、楼梯、屋顶；</li>
 *   <li>原版门家具（基岩柱/基岩圈/返回门/火把/龙蛋落点）**一格不碰**——那是功能核心；</li>
 *   <li>7×7 井口、门坑与四晶座头部（{@link EndPodiumTemplate.Plan#clearance()}）**保持现状**，
 *       不回填：把天然末地石填回门坑里正是旧 bug；</li>
 *   <li>当前方块已不等于模板目标的格视为**玩家改动**，保留并计入报告；</li>
 *   <li>当前已经等于快照里的原状（天然/空气）的格不动。</li>
 * </ul>
 *
 * <h2>证据链，不猜</h2>
 * <ol>
 *   <li>布局必须是本版 v2（{@link EndPodiumTemplate#decode}），且 {@code seed} 等于世界种子；</li>
 *   <li>快照 job **按坐标集匹配**：每个快照坐标都必须落在该布局的写入集里，且要覆盖我们要回滚的
 *       装饰格——不是「取最新的 .gz」；多个 job 并列覆盖时判为歧义，拒绝；</li>
 *   <li>预览给出 token（布局串 + job + 世界 + 可回滚坐标集 的哈希）；执行前重跑全部核验并重算 token，
 *       世界在两次之间变过就一定不匹配。</li>
 * </ol>
 *
 * <h2>执行顺序（失败不落封存）</h2>
 * 核验 → 逐格预检（按区块读）→ 把「当前状态」另存一份压缩快照（清理本身也可回滚）→ 按区块分批写回
 * （每批 ≤ {@link EndPodiumBuild#BATCH_CELLS}、批间 {@link EndPodiumBuild#BATCH_DELAY_TICKS}、写前
 * 与预检重核、跨区不读不写、不 join 别的区域）→ 全部批次成功后才把旧的 {@code .layout}/{@code .placed}
 * **原子归档**进 {@code podium-backups/<worldUUID>/sealed-<jobId>/} 并写 {@code .sealed} 封存标记。
 * 任一阶段失败：不封存、不删标记、已落的格与新快照都留在原处。
 */
public final class EndPodiumRemoval {

    /** PDC 键（与 {@link EndPodiumShell} 同源，读只用于判断「旧版本是否已迁移」）。 */
    private static final String KEY_STATE = EndPodiumShell.KEY_STATE;

    /** 一次清理能回滚的一格该怎么做。 */
    enum Action {
        /** 回滚到快照里的原状。 */
        RESTORE,
        /** 已经是原状，不必写。 */
        ALREADY_ORIGINAL,
        /** 当前不等于模板目标：玩家/别人改过，保留。 */
        KEEP_PLAYER
    }

    /** 预览/执行的结果码。 */
    public enum Status {
        /** 可以清理 / 清理完成。 */
        OK,
        /** 不是末地维度或不是本插件生成器。 */
        NOT_MANAGED,
        /** 世界里没有布局记录（新世界，或从未施工）。 */
        NO_LAYOUT,
        /** 布局串读不懂（旧版 x,y,z 或 v1）：只能人工核验，不做自动回滚。 */
        LAYOUT_UNREADABLE,
        /** 布局记录的种子与世界种子不一致。 */
        SEED_MISMATCH,
        /** 桥接里记录的实际门坐标与布局/旧标记不一致（不凭材质猜，直接拒绝）。 */
        GATE_MISMATCH,
        /** 该世界没有任何原始快照。 */
        SNAPSHOT_MISSING,
        /** 有快照，但没有一个与该布局逐格对应（或并列歧义）。 */
        SNAPSHOT_MISMATCH,
        /** 布局里没有可回滚的装饰格。 */
        NOTHING_TO_REMOVE,
        /** 执行前复检发现世界被改过：一格未写。 */
        CONFLICT,
        /** token 与当前证据不符（过期/伪造）。 */
        TOKEN_REJECTED,
        /** 区域任务排不进去（世界卸载/插件关闭）。 */
        SCHEDULE_FAILED,
        /** 写入过程报错：已落的格与新快照保留，未封存。 */
        WRITE_FAILED
    }

    /** 预览结果：{@code token} 只有 {@link Status#OK} 时非空。 */
    public record Preview(Status status, String detail, String token, Path snapshot,
                          int gateX, int gateY, int gateZ, long seed,
                          int decorations, int restorable, int playerChanged,
                          int furnitureKept, int clearanceKept,
                          List<EndPodiumTemplate.Cell> samples, boolean legacyEvidence) {

        public boolean ok() {
            return status == Status.OK;
        }
    }

    /** 执行结果：{@code archived} 是封存目录（失败时为 {@code null}）。 */
    public record Result(Status status, String detail, int restored, int kept, Path archived) {

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private final Plugin plugin;
    private final Path dataDir;
    /** 同一世界同时只允许一次清理（重复点击/两条命令并发会互相覆盖）。 */
    private final Set<UUID> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 最近一次执行里「因玩家改动而保留」的格数（只用于 Result 报告）。 */
    private volatile int lastKept;

    public EndPodiumRemoval(Plugin plugin, Path dataDir) {
        this.plugin = plugin;
        this.dataDir = dataDir;
    }

    /** 插件关闭：在途回调原地失效，不再排新任务。 */
    public void shutdown() {
        closed.set(true);
    }

    // ------------------------------------------------------------------ 预览

    /**
     * 只读预览：跑完整条证据链，给出计数、样本与 token。**不写世界、不写磁盘**。
     *
     * <p>回调在拥有中心区块的区域线程上执行；不是受管世界时同步回调。
     */
    public void preview(World world, Consumer<Preview> done) {
        if (!manages(world)) {
            done.accept(reject(Status.NOT_MANAGED, "世界 " + world.getName() + " 不是本插件的末地"));
            return;
        }
        boot(world, booted -> region(world, 0, 0, () -> previewWith(world, booted, done),
                problem -> done.accept(reject(Status.SCHEDULE_FAILED, problem))));
    }

    /** 证据核验之后的部分：逐格读世界（按区块分桶，每批只读自己那块）。 */
    private void previewWith(World world, Boot booted, Consumer<Preview> done) {
        Verdict verdict = verify(world, booted);
        if (verdict.preview != null) {
            done.accept(verdict.preview);
            return;
        }
        String gateProblem = checkRecordedGate(world, verdict.plan);
        if (gateProblem != null) {
            done.accept(reject(Status.GATE_MISMATCH, gateProblem));
            return;
        }
        Plan plan = verdict.plan;
        Map<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> buckets = group(plan.restorable);
        Counters counters = new Counters(buckets.size());
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> bucket
                : buckets.entrySet()) {
            int chunkX = bucket.getKey().x();
            int chunkZ = bucket.getKey().z();
            List<EndPodiumTemplate.Cell> cells = bucket.getValue();
            boolean queued = region(world, chunkX, chunkZ, () -> {
                for (EndPodiumTemplate.Cell cell : cells) {
                    String current = state(world, cell.x(), cell.y(), cell.z());
                    counters.states.put(pack(cell), current);
                    Action action = decide(cell, current, plan.before.get(pack(cell)));
                    if (action == Action.KEEP_PLAYER) {
                        counters.playerChanged.incrementAndGet();
                        if (counters.conflicts.size() < 32) {
                            counters.conflicts.add(cell);
                        }
                    } else if (action == Action.ALREADY_ORIGINAL) {
                        counters.alreadyOriginal.incrementAndGet();
                    } else {
                        counters.restorable.incrementAndGet();
                    }
                }
                counters.finish(() -> done.accept(verdict.toPreview(counters)));
            }, problem -> {
                counters.failed = problem;
                counters.finish(() -> done.accept(reject(Status.SCHEDULE_FAILED,
                        "读区块 " + chunkX + "," + chunkZ + " 失败：" + problem)));
            });
            if (!queued) {
                counters.failed = "无法请求区块 " + chunkX + "," + chunkZ;
                counters.finish(() -> done.accept(reject(Status.SCHEDULE_FAILED, counters.failed)));
            }
        }
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行清理：token 必须等于某个 {@link #preview} 的 token；执行前**重跑全部证据核验**并重算 token。
     *
     * <p>写入纪律与施工层一致：按区块分批 ≤{@link EndPodiumBuild#BATCH_CELLS}、批间延迟、写前与预检
     * 重核、跨区不读不写、不 join 别的区域。全部批次成功才归档旧标记并封存。
     */
    public void remove(World world, String token, Consumer<Result> done) {
        if (!manages(world)) {
            done.accept(fail(Status.NOT_MANAGED, "世界 " + world.getName() + " 不是本插件的末地"));
            return;
        }
        UUID worldId = world.getUID();
        String respawn = respawnBlock(world);
        if (respawn != null) {
            done.accept(fail(Status.CONFLICT, respawn));
            return;
        }
        if (!running.add(worldId)) {
            done.accept(fail(Status.CONFLICT, "这个世界的清理已经在进行中"));
            return;
        }
        boot(world, booted -> region(world, 0, 0,
                () -> removeWith(world, booted, token, done),
                problem -> finish(worldId, done, fail(Status.SCHEDULE_FAILED, problem))));
    }

    /** 证据核验之后的部分：预检 → 撤回快照 → 分批写 → 复扫 → 封存。 */
    private void removeWith(World world, Boot booted, String token, Consumer<Result> done) {
        UUID worldId = world.getUID();
        Verdict verdict = verify(world, booted);
        if (verdict.preview != null) {
            running.remove(worldId);
            done.accept(fail(verdict.preview.status(), verdict.preview.detail()));
            return;
        }
        String gateProblem = checkRecordedGate(world, verdict.plan);
        if (gateProblem != null) {
            running.remove(worldId);
            done.accept(fail(Status.GATE_MISMATCH, gateProblem));
            return;
        }
        Plan plan = verdict.plan;
        if (plan.restorable.isEmpty()) {
            running.remove(worldId);
            done.accept(fail(Status.NOTHING_TO_REMOVE, "布局里没有可回滚的装饰格"));
            return;
        }
        // 第一步：逐格预检（按区块读），结果就是后面写前重核的基准。
        // 多个区块的区域线程会并发 put：必须是并发容器 + 原子计数。
        Map<Long, String> before = new java.util.concurrent.ConcurrentHashMap<>();
        Map<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> buckets = group(plan.restorable);
        Counters counters = new Counters(buckets.size());
        Runnable afterPreflight = () -> {
            if (counters.failed != null) {
                running.remove(worldId);
                done.accept(fail(Status.SCHEDULE_FAILED, counters.failed));
                return;
            }
            if (counters.gateMismatch != null) {
                running.remove(worldId);
                done.accept(fail(Status.GATE_MISMATCH, counters.gateMismatch));
                return;
            }
            lastKept = counters.playerChanged.get();
            String expected = plan.token(stateDigest(before));
            if (token == null || !expected.equals(token.trim())) {
                running.remove(worldId);
                done.accept(fail(Status.TOKEN_REJECTED,
                        "token 与当前证据/现场不符：布局、快照 job、可回滚坐标集或已读到的方块状态在预览"
                                + "之后变过；请重新预览"));
                return;
            }
            // 玩家改动**不**整楼拒绝：只回滚「仍等于模板目标」的那些已证实装饰格，改动过的格保留并在
            // 结果里报数（写前若又发生变化，writeBatch 会安全中止）。
            List<Working> work = new ArrayList<>();
            for (EndPodiumTemplate.Cell cell : plan.restorable) {
                String current = before.get(pack(cell));
                if (current == null) {
                    running.remove(worldId);
                    done.accept(fail(Status.CONFLICT, "预检丢了 (" + cell.x() + "," + cell.y() + ","
                            + cell.z() + ") 的当前状态"));
                    return;
                }
                Action action = decide(cell, current, plan.before.get(pack(cell)));
                if (action == Action.RESTORE) {
                    work.add(new Working(cell, current, plan.before.get(pack(cell))));
                }
            }
            if (work.isEmpty()) {
                running.remove(worldId);
                done.accept(fail(Status.NOTHING_TO_REMOVE, "所有装饰格都已经是原状或已被玩家改动"));
                return;
            }
            archiveThenWrite(world, plan, work, done);
        };
        int total = buckets.size();
        AtomicInteger remaining = new AtomicInteger(total);
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> bucket
                : buckets.entrySet()) {
            int chunkX = bucket.getKey().x();
            int chunkZ = bucket.getKey().z();
            List<EndPodiumTemplate.Cell> cells = bucket.getValue();
            boolean queued = region(world, chunkX, chunkZ, () -> {
                for (EndPodiumTemplate.Cell cell : cells) {
                    String current = state(world, cell.x(), cell.y(), cell.z());
                    before.put(pack(cell), current);
                    if (decide(cell, current, plan.before.get(pack(cell))) == Action.KEEP_PLAYER) {
                        counters.playerChanged.incrementAndGet();
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    afterPreflight.run();
                }
            }, problem -> {
                counters.failed = problem;
                if (remaining.decrementAndGet() == 0) {
                    afterPreflight.run();
                }
            });
            if (!queued) {
                counters.failed = "无法请求区块 " + chunkX + "," + chunkZ;
                if (remaining.decrementAndGet() == 0) {
                    afterPreflight.run();
                }
            }
        }
    }

    /** 先把「当前状态」另存一份（清理可回滚），成功后才开始分批写回。 */
    private void archiveThenWrite(World world, Plan plan, List<Working> work, Consumer<Result> done) {
        UUID worldId = world.getUID();
        UUID undoJob = UUID.randomUUID();
        List<EndPodiumBackup.Cell> undo = new ArrayList<>(work.size());
        for (Working item : work) {
            undo.add(new EndPodiumBackup.Cell(item.cell.x(), item.cell.y(), item.cell.z(), item.before));
        }
        dispatchAsync(() -> {
            Path undoFile;
            try {
                Path written = EndPodiumBackup.write(dataDir, worldId, undoJob, undo);
                Path undoDir = dataDir.resolve(EndPodiumBackup.DIRECTORY_NAME)
                        .resolve(worldId.toString()).resolve("undo");
                Files.createDirectories(undoDir);
                undoFile = Files.move(written,
                        undoDir.resolve(undoJob + EndPodiumBackup.FILE_SUFFIX),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                rememberUndoJob(worldId, undoJob);
            } catch (Exception error) {
                finish(worldId, done, fail(Status.WRITE_FAILED,
                        "清理前的撤回快照落盘失败，一格未写：" + reason(error)));
                return;
            }
            writeBatches(world, plan, work, undoJob, undoFile, done);
        }, problem -> finish(worldId, done, fail(Status.SCHEDULE_FAILED,
                "异步线程排不进去：" + problem)));
    }

    /** 按区块分批写回；每格写前与预检状态重核。 */
    private void writeBatches(World world, Plan plan, List<Working> work, UUID undoJob, Path undoFile,
                              Consumer<Result> done) {
        UUID worldId = world.getUID();
        Map<EndPodiumTemplate.ChunkKey, List<Working>> buckets = new TreeMap<>(
                (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        for (Working item : work) {
            buckets.computeIfAbsent(EndPodiumTemplate.ChunkKey.of(item.cell.x(), item.cell.z()),
                    key -> new ArrayList<>()).add(item);
        }
        List<Batch> batches = new ArrayList<>();
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<Working>> bucket : buckets.entrySet()) {
            List<Working> cells = bucket.getValue();
            for (int from = 0; from < cells.size(); from += EndPodiumBuild.BATCH_CELLS) {
                int to = Math.min(from + EndPodiumBuild.BATCH_CELLS, cells.size());
                batches.add(new Batch(bucket.getKey().x(), bucket.getKey().z(),
                        List.copyOf(cells.subList(from, to))));
            }
        }
        int[] counters = new int[]{0, 0};
        writeBatch(world, plan, work, batches, 0, counters, undoJob, undoFile, done);
    }

    private void writeBatch(World world, Plan plan, List<Working> work, List<Batch> batches, int index,
                            int[] counters, UUID undoJob, Path undoFile, Consumer<Result> done) {
        UUID worldId = world.getUID();
        if (closed.get()) {
            finish(worldId, done, fail(Status.SCHEDULE_FAILED, "插件正在关闭：已落的格与新快照保留，未封存"));
            return;
        }
        if (index >= batches.size()) {
            verifyThenSeal(world, plan, work, undoJob, undoFile, counters, done);
            return;
        }
        Batch batch = batches.get(index);
        long delay = index == 0 ? 0L : EndPodiumBuild.BATCH_DELAY_TICKS;
        boolean queued = region(world, batch.chunkX, batch.chunkZ, delay, () -> {
            for (Working item : batch.cells) {
                String now = state(world, item.cell.x(), item.cell.y(), item.cell.z());
                if (!now.equals(item.before)) {
                    finish(worldId, done, fail(Status.CONFLICT, "施工期间 ("
                            + item.cell.x() + ", " + item.cell.y() + ", " + item.cell.z() + ") 从 "
                            + item.before + " 变成 " + now + "；立即中止，不覆盖这次改动。已落 "
                            + counters[0] + " 格，撤回快照 " + undoFile));
                    return;
                }
                world.getBlockAt(item.cell.x(), item.cell.y(), item.cell.z())
                        .setBlockData(data(item.restore), false);
                counters[0]++;
            }
            writeBatch(world, plan, work, batches, index + 1, counters, undoJob, undoFile, done);
        }, problem -> finish(worldId, done, fail(Status.SCHEDULE_FAILED,
                "写区块 " + batch.chunkX + "," + batch.chunkZ + " 失败：" + problem
                        + "（已落 " + counters[0] + " 格，撤回快照 " + undoFile + "）")));
        if (!queued) {
            finish(worldId, done, fail(Status.SCHEDULE_FAILED, "无法请求区块 " + batch.chunkX + ","
                    + batch.chunkZ + "（已落 " + counters[0] + " 格，撤回快照 " + undoFile + "）"));
        }
    }

    /**
     * 封存前复扫：要回滚的那些格必须**真的**不再是模板目标了。
     *
     * <p>没有这一遍就会出现「清了半座塔 + 旧标记被删」这种最坏结果：剩下的方块再也无法认领。
     * 任何一格还等于模板目标就不封存，报出前几格坐标。
     */
    private void verifyThenSeal(World world, Plan plan, List<Working> work, UUID undoJob, Path undoFile,
                                int[] counters, Consumer<Result> done) {
        UUID worldId = world.getUID();
        Map<EndPodiumTemplate.ChunkKey, List<Working>> buckets = new TreeMap<>(
                (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        for (Working item : work) {
            buckets.computeIfAbsent(EndPodiumTemplate.ChunkKey.of(item.cell.x(), item.cell.z()),
                    key -> new ArrayList<>()).add(item);
        }
        List<EndPodiumTemplate.Cell> leftovers =
                java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger remaining = new AtomicInteger(buckets.size());
        Runnable after = () -> {
            if (leftovers.isEmpty()) {
                seal(world, plan, undoJob, undoFile, counters, done);
                return;
            }
            StringBuilder sample = new StringBuilder();
            for (int i = 0; i < Math.min(8, leftovers.size()); i++) {
                EndPodiumTemplate.Cell cell = leftovers.get(i);
                sample.append('(').append(cell.x()).append(',').append(cell.y()).append(',')
                        .append(cell.z()).append(") ");
            }
            finish(worldId, done, fail(Status.WRITE_FAILED, "复扫发现 " + leftovers.size()
                    + " 格仍是模板目标（未写成功，" + sample + "…）：不封存、不删旧标记；旧标记仍在，可重试。"
                    + "已回滚 " + counters[0] + " 格，撤回快照 " + undoFile));
        };
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<Working>> bucket : buckets.entrySet()) {
            int chunkX = bucket.getKey().x();
            int chunkZ = bucket.getKey().z();
            List<Working> cells = bucket.getValue();
            boolean queued = region(world, chunkX, chunkZ, () -> {
                for (Working item : cells) {
                    String now = state(world, item.cell.x(), item.cell.y(), item.cell.z());
                    if (now.equals(item.before)) {
                        // before == 模板目标：说明这一格**没被真正清掉**（setBlockData 静默失效/被别的
                        // 插件改回），此时绝不能封存——那正是「清半座塔 + 旧标记被删」。
                        leftovers.add(item.cell);
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    after.run();
                }
            }, problem -> {
                finish(worldId, done, fail(Status.SCHEDULE_FAILED,
                        "复扫区块 " + chunkX + "," + chunkZ + " 失败：" + problem));
            });
            if (!queued) {
                finish(worldId, done, fail(Status.SCHEDULE_FAILED,
                        "复扫排不进区块 " + chunkX + "," + chunkZ));
            }
        }
    }

    /**
     * 全部批次成功后：把旧的 {@code .layout}/{@code .placed} 原子归档进
     * {@code podium-backups/<worldUUID>/sealed-<undoJob>/}，再写 {@code .sealed} 封存标记。
     *
     * <p>归档用 {@code AtomicFiles.replace}（临时文件 + 原子改名），所以不会出现「标记删掉了、备份没写成」
     * 的中间态：备份写成功之后才删原件。
     */
    private void seal(World world, Plan plan, UUID undoJob, Path undoFile, int[] counters,
                      Consumer<Result> done) {
        UUID worldId = world.getUID();
        dispatchAsync(() -> {
            Path sealDir = dataDir.resolve(EndPodiumBackup.DIRECTORY_NAME)
                    .resolve(worldId.toString()).resolve("sealed-" + undoJob);
            try {
                Files.createDirectories(sealDir);
                Path layoutFile = EndPodiumShell.layoutFile(dataDir, worldId);
                Path stateFile = EndPodiumShell.stateFile(dataDir, worldId);
                if (Files.isRegularFile(layoutFile)) {
                    dev.mumu.betterend.AtomicFiles.replace(sealDir.resolve("layout.txt"),
                            Files.readAllBytes(layoutFile));
                }
                if (Files.isRegularFile(stateFile)) {
                    dev.mumu.betterend.AtomicFiles.replace(sealDir.resolve("placed.txt"),
                            Files.readAllBytes(stateFile));
                }
                // 记录这次清理本身：布局、删除的格数、撤回快照路径。
                String summary = "layout=" + EndPodiumTemplate.encode(plan.layout) + "\n"
                        + "restored=" + counters[0] + "\n"
                        + "undo=" + undoFile + "\n";
                dev.mumu.betterend.AtomicFiles.replace(sealDir.resolve("removal.txt"),
                        summary.getBytes(StandardCharsets.UTF_8));
                // 备份都落盘了，才删原件：此后 placedPlan 不再返回，旧施工不能复活。
                Files.deleteIfExists(layoutFile);
                Files.deleteIfExists(stateFile);
                dev.mumu.betterend.AtomicFiles.replace(EndPodiumShell.sealedFile(dataDir, worldId),
                        undoJob.toString().getBytes(StandardCharsets.UTF_8));
                clearPdcMirror(world);
            } catch (IOException error) {
                finish(worldId, done, fail(Status.WRITE_FAILED,
                        "封存失败（旧标记仍在，清理可重试）：" + reason(error)
                                + "；已回滚 " + counters[0] + " 格，撤回快照 " + undoFile));
                return;
            }
            finish(worldId, done, new Result(Status.OK,
                    "已回滚 " + counters[0] + " 格装饰，原版核心/井口/四晶座/玩家改动保留；"
                            + "旧的布局与完成标记已归档到 " + sealDir + "，撤回快照 " + undoFile,
                    counters[0], lastKept, sealDir));
        }, problem -> finish(worldId, done, fail(Status.SCHEDULE_FAILED,
                "异步线程排不进去：" + problem + "（已回滚 " + counters[0] + " 格，撤回快照 " + undoFile + "）")));
    }

    // ------------------------------------------------------------------ 证据链

    /**
     * token = SHA-256(证据串 | 快照 job | 可回滚坐标集)。**纯函数**，preview 与 remove 用同一份输入重算，
     * 所以世界/布局/快照/格集任何一项变过，旧 token 一定对不上（单测直接钉这一条）。
     */
    static String token(String layoutText, UUID jobId, List<EndPodiumTemplate.Cell> restorable) {
        return token(layoutText, jobId, restorable, "");
    }

    /**
     * token = SHA-256(证据串 | 快照 job | 可回滚坐标集 | **当前状态摘要**)。
     *
     * <p>状态摘要来自预检逐格读到的「当前方块状态」（{@link #stateDigest}），所以世界在预览之后被改过
     * 时 token 一定对不上——这不是靠说明文字保证的，而是输入本身。
     */
    static String token(String layoutText, UUID jobId, List<EndPodiumTemplate.Cell> restorable,
                        String stateDigest) {
        StringBuilder text = new StringBuilder(String.valueOf(layoutText)).append('|').append(jobId)
                .append('|').append(stateDigest == null ? "" : stateDigest);
        for (EndPodiumTemplate.Cell cell : restorable) {
            text.append('|').append(cell.x()).append(',').append(cell.y()).append(',')
                    .append(cell.z());
        }
        return sha256(text.toString());
    }

    /** 当前状态摘要：排序后的 {@code x,y,z=state} 行的 SHA-256（与读的顺序无关）。 */
    static String stateDigest(Map<Long, String> states) {
        List<String> lines = new ArrayList<>(states.size());
        for (Map.Entry<Long, String> entry : states.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        java.util.Collections.sort(lines);
        return sha256(String.join(System.lineSeparator(), lines));
    }

    /**
     * 「真实装饰交集」：快照里有的格 ∩ 该布局的装饰集（{@code writes − clearance}）。
     *
     * <p>三条硬边界：家具（基岩柱/圈/返回门/火把/蛋）不在 {@code decorations} 里；净空（门坑、7×7 井口、
     * 四晶座头部）也不在；空气格不回滚。旧版布局的掩码与今天不同，这条交集规则保证**不扩大格集**——
     * 只动「那一轮真写过、并且今天仍然算装饰」的格。
     */
    static List<EndPodiumTemplate.Cell> restorableOf(EndPodiumTemplate.Plan plan,
                                                     List<EndPodiumBackup.Cell> snapshot) {
        Set<Long> have = new HashSet<>();
        for (EndPodiumBackup.Cell cell : snapshot) {
            have.add(pack(cell.x(), cell.y(), cell.z()));
        }
        List<EndPodiumTemplate.Cell> restorable = new ArrayList<>();
        for (EndPodiumTemplate.Cell cell : plan.decorations()) {
            if (!cell.air() && have.contains(pack(cell))) {
                restorable.add(cell);
            }
        }
        return restorable;
    }

    /**
     * 交叉确认：桥接里记录的**实际门坐标**必须与布局/旧标记一致（只读，不调 findExitPortal）。
     *
     * <p>这条把「旧标记可能被覆盖过」这个已知风险挡住一半：标记能骗人，原生字段里记的门位置不能。
     * 记录为空（从未生成过门）不参与判定——那说明这个世界从来没打过龙，不构成反证。
     */
    private String checkRecordedGate(World world, Plan plan) {
        try {
            org.bukkit.Location recorded = MuDragonFightBridge.read(world);
            if (recorded == null) {
                return null;
            }
            if (recorded.getBlockX() != plan.layout.gateX()
                    || recorded.getBlockY() != plan.layout.gateY()
                    || recorded.getBlockZ() != plan.layout.gateZ()) {
                return "桥接记录的门在 (" + recorded.getBlockX() + ", "
                        + recorded.getBlockY() + ", " + recorded.getBlockZ() + ")，证据里的布局在 ("
                        + plan.layout.gateX() + ", " + plan.layout.gateY() + ", "
                        + plan.layout.gateZ() + ")；不凭材质猜，拒绝";
            }
            return null;
        } catch (RuntimeException | LinkageError unavailable) {
            // 桥接不可用不影响「只按文件证据回滚」，所以这里只留痕，不当拒绝理由。
            plugin.getComponentLogger().warn("中心祭坛清理：桥接不可用，跳过门坐标交叉确认："
                    + reason(unavailable));
            return null;
        }
    }

    /**
     * 复活进行中不做清理：{@code seal} 会删掉 {@code .layout}/{@code .placed}，而原版正在跑复活流程，
     * 两边同时动中心区域只会留下说不清的状态。返回 {@code null} 表示可以继续。
     */
    private static String respawnBlock(World world) {
        try {
            org.bukkit.boss.DragonBattle battle = world.getEnderDragonBattle();
            if (battle == null) {
                return null;
            }
            if (battle.getRespawnPhase() != org.bukkit.boss.DragonBattle.RespawnPhase.NONE) {
                return "龙正在复活（阶段 " + battle.getRespawnPhase() + "），本次拒绝清理；等这轮结束再来";
            }
            return null;
        } catch (RuntimeException notReady) {
            return null;
        }
    }

    /**
     * 清掉世界 PDC 里的布局/完成镜像（**全局线程**上做，PDC 只允许在那儿碰）。
     *
     * <p>只删键、不写哨兵值：{@code WorldMarker.read} 把任何非 null 的 state 都当 {@code placed}，
     * 写个 "removed" 反而会让将来的重建看到「已完成」。
     */
    private void clearPdcMirror(World world) {
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                try {
                    world.getPersistentDataContainer().remove(
                            new NamespacedKey(plugin, EndPodiumShell.KEY_LAYOUT));
                    world.getPersistentDataContainer().remove(
                            new NamespacedKey(plugin, EndPodiumShell.KEY_STATE));
                } catch (RuntimeException error) {
                    plugin.getComponentLogger().warn("中心祭坛清理：清 PDC 镜像失败（文件已封存）："
                            + reason(error));
                }
            });
        } catch (RuntimeException refused) {
            plugin.getComponentLogger().warn("中心祭坛清理：PDC 镜像清理排不进全局线程（文件已封存）："
                    + reason(refused));
        }
    }

    /** 预读的证据原料：PDC 旧标记（全局线程读）、布局文件文本与快照（异步线程读）。 */
    private record Boot(String pdcRaw, String fileText, List<EndPodiumBackup.Snapshot> snapshots,
                        String diskProblem) {
    }

    /**
     * 证据原料的读取链：**PDC 只在全局线程读，磁盘只在异步线程读**，之后调用方再回到
     * 中心区块的区域线程做逐格世界读。三层各干各的，不在区域线程上碰 PDC 或 gunzip。
     */
    private void boot(World world, Consumer<Boot> done) {
        UUID worldId = world.getUID();
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                String pdc;
                try {
                    pdc = pdcLayout(world);
                } catch (RuntimeException error) {
                    pdc = null;
                }
                String pdcRaw = pdc;
                dispatchAsync(() -> {
                    String fileText = null;
                    String problem = null;
                    try {
                        Path file = EndPodiumShell.layoutFile(dataDir, worldId);
                        if (Files.isRegularFile(file)) {
                            fileText = Files.readString(file, StandardCharsets.UTF_8).trim();
                        }
                    } catch (IOException error) {
                        problem = "读布局文件失败：" + reason(error);
                    }
                    List<EndPodiumBackup.Snapshot> snapshots =
                            problem == null ? snapshots(worldId) : List.of();
                    done.accept(new Boot(pdcRaw, fileText, snapshots, problem));
                }, refused -> done.accept(new Boot(pdcRaw, null, List.of(),
                        "异步线程排不进去：" + refused)));
            });
        } catch (RuntimeException refused) {
            done.accept(new Boot(null, null, List.of(), "全局线程排不进去：" + refused));
        }
    }

    /** 内部：核验通过的证据。 */
    private static final class Plan {
        final EndPodiumTemplate.Layout layout;
        final String layoutText;
        final UUID jobId;
        final Path snapshot;
        /** 坐标 → 快照里的原状。 */
        final Map<Long, String> before;
        final List<EndPodiumTemplate.Cell> restorable;
        final int decorations;
        final int furnitureKept;
        final int clearanceKept;
        final int playerChangedBaseline;
        /** 证据是旧版 {@code x,y,z} 标记（无 seed、无版本），不是 v2 布局串。 */
        final boolean legacy;

        Plan(EndPodiumTemplate.Layout layout, String layoutText, UUID jobId, Path snapshot,
             Map<Long, String> before, List<EndPodiumTemplate.Cell> restorable, int decorations,
             int furnitureKept, int clearanceKept, int playerChangedBaseline, boolean legacy) {
            this.legacy = legacy;
            this.layout = layout;
            this.layoutText = layoutText;
            this.jobId = jobId;
            this.snapshot = snapshot;
            this.before = before;
            this.restorable = restorable;
            this.decorations = decorations;
            this.furnitureKept = furnitureKept;
            this.clearanceKept = clearanceKept;
            this.playerChangedBaseline = playerChangedBaseline;
        }

        /** token：只由「布局/旧标记串 + 快照 job + 可回滚坐标集」决定，证据变过就一定不同。 */
        String token(String digest) {
            return EndPodiumRemoval.token(layoutText, jobId, restorable, digest);
        }
    }

    /** 核验结果：要么给出 {@code plan}，要么给出拒绝的 {@code preview}。 */
    private static final class Verdict {
        final Plan plan;
        final Preview preview;

        private Verdict(Plan plan, Preview preview) {
            this.plan = plan;
            this.preview = preview;
        }

        static Verdict ok(Plan plan) {
            return new Verdict(plan, null);
        }

        static Verdict reject(Status status, String detail) {
            return new Verdict(null, EndPodiumRemoval.reject(status, detail));
        }

        Preview toPreview(Counters counters) {
            int known = counters.restorable.get() + counters.playerChanged.get()
                    + counters.alreadyOriginal.get();
            if (counters.gateMismatch != null) {
                return EndPodiumRemoval.reject(Status.GATE_MISMATCH, counters.gateMismatch);
            }
            if (counters.failed != null) {
                return EndPodiumRemoval.reject(Status.SCHEDULE_FAILED, counters.failed);
            }
            if (known < plan.restorable.size()) {
                return EndPodiumRemoval.reject(Status.CONFLICT, "只读到 " + known + "/" + plan.restorable.size()
                        + " 格，证据不完整");
            }
            if (counters.restorable.get() == 0) {
                return EndPodiumRemoval.reject(Status.NOTHING_TO_REMOVE,
                        "所有装饰格都已是原状或被玩家改动");
            }
            String basis = plan.legacy
                    ? "legacy 证据口径：旧标记 " + plan.layoutText + " + 快照头 worldUUID + 坐标集/模板几何"
                            + "（无 seed、无版本；不凭材质、不取最新文件）"
                    : "v2 布局 " + plan.layoutText;
            return new Preview(Status.OK,
                    "可回滚 " + counters.restorable.get() + " 格装饰（" + basis
                            + "，快照 job " + plan.jobId + "）；保留原版家具 " + plan.furnitureKept
                            + " 格、井口净空 " + plan.clearanceKept + " 格；玩家改动 "
                            + counters.playerChanged.get() + " 格保留并报告（不整楼拒绝）；已经是原状 "
                            + counters.alreadyOriginal.get() + " 格",
                    plan.token(counters.digest()), plan.snapshot, plan.layout.gateX(), plan.layout.gateY(),
                    plan.layout.gateZ(), plan.layout.seed(), plan.decorations, counters.restorable.get(),
                    counters.playerChanged.get(), plan.furnitureKept, plan.clearanceKept,
                    List.copyOf(counters.conflicts), plan.legacy);
        }
    }

    /**
     * 完整证据核验：布局版本/世界种子/快照 job 逐格对应。任何一个不成立都给具体拒绝理由。
     *
     * <p>只在区域线程（中心区块）上调用：布局与状态文件是插件目录里的普通文件，读它们不碰世界 PDC。
     */
    private Verdict verify(World world, Boot boot) {
        UUID worldId = world.getUID();
        if (Files.isRegularFile(EndPodiumShell.sealedFile(dataDir, worldId))) {
            return Verdict.reject(Status.NO_LAYOUT,
                    "这个世界已经清理并封存过（" + EndPodiumShell.sealedFile(dataDir, worldId)
                            + "）；不再有可清理的旧楼记录");
        }
        if (boot.diskProblem() != null) {
            return Verdict.reject(Status.LAYOUT_UNREADABLE, boot.diskProblem());
        }
        // 一条流：文件文本优先，否则用 boot 在**全局线程**读到的 PDC 旧标记。这里不再碰 PDC。
        String raw = boot.fileText() != null && !boot.fileText().isEmpty()
                ? boot.fileText() : boot.pdcRaw();
        if (raw == null || raw.isBlank()) {
            return Verdict.reject(Status.NO_LAYOUT, "世界没有布局记录（从未施工，或已清理过）");
        }
        String layoutText = raw.trim();
        EndPodiumTemplate.Layout layout = null;
        boolean legacy = false;
        try {
            layout = EndPodiumTemplate.decode(layoutText);
        } catch (IllegalArgumentException notV2) {
            // 旧版（0.1.26）只把门坐标写在世界 PDC 里，形如 "x,y,z"：没有 seed/版本，所以下面
            // 一律标注 legacy 口径，并且必须再由快照头 worldUUID + 坐标集/模板几何 + 桥接门坐标交叉确认。
            legacy = parseLegacyGate(layoutText) != null;
            if (!legacy) {
                return Verdict.reject(Status.LAYOUT_UNREADABLE, "布局 '" + layoutText + "' 读不懂："
                        + notV2.getMessage());
            }
        }
        EndPodiumTemplate.Plan plan;
        if (legacy) {
            int[] gate = parseLegacyGate(layoutText);
            if (gate == null) {
                return Verdict.reject(Status.LAYOUT_UNREADABLE, "旧标记 '" + layoutText + "' 不是 x,y,z");
            }
            // 关键：按**旧记录里的门坐标**算旧楼足迹，绝不按现在的地表重算——旧楼不是今天长出来的。
            plan = EndPodiumTemplate.plan(gate[0], gate[1], gate[2]);
        } else {
            if (layout.seed() != world.getSeed()) {
                return Verdict.reject(Status.SEED_MISMATCH, "布局种子 " + layout.seed() + " ≠ 世界种子 "
                        + world.getSeed());
            }
            plan = layout.plan();
        }
        List<EndPodiumBackup.Snapshot> candidates = boot.snapshots();
        if (candidates.isEmpty()) {
            return Verdict.reject(Status.SNAPSHOT_MISSING, "该世界没有任何原始快照（"
                    + snapshotDir(worldId) + "），无法证明哪些格是本插件写的");
        }
        Choice choice = chooseJob(plan, candidates, snapshotDir(worldId), worldId);
        if (choice.problem != null) {
            return Verdict.reject(choice.status, choice.problem);
        }
        // 多个区块的区域线程会并发 put：必须是并发容器 + 原子计数。
        Map<Long, String> before = new java.util.concurrent.ConcurrentHashMap<>();
        for (EndPodiumBackup.Cell cell : choice.snapshot.cells()) {
            before.put(pack(cell.x(), cell.y(), cell.z()), cell.state());
        }
        List<EndPodiumTemplate.Cell> restorable = restorableOf(plan, choice.snapshot.cells());
        long solidDecorations = plan.decorations().stream().filter(cell -> !cell.air()).count();
        if (solidDecorations > 0 && restorable.size() * 10L < solidDecorations * 9L) {
            return Verdict.reject(Status.SNAPSHOT_MISMATCH, "选中的快照 job " + choice.snapshot.jobId()
                    + " 只覆盖 " + restorable.size() + "/" + solidDecorations + " 格实心装饰；"
                    + "照它回滚会清掉半座塔却删掉旧标记，所以拒绝（先用完整快照，或人工确认范围）");
        }
        if (restorable.isEmpty()) {
            return Verdict.reject(Status.NOTHING_TO_REMOVE,
                    "快照 job " + choice.snapshot.jobId() + " 没有覆盖任何装饰格（只剩核心/净空）");
        }
        EndPodiumTemplate.Layout shown = layout != null ? layout
                : new EndPodiumTemplate.Layout(EndPodiumTemplate.LAYOUT_VERSION, world.getSeed(),
                        plan.gateX(), plan.gateY(), plan.gateZ());
        return Verdict.ok(new Plan(shown, layoutText, choice.snapshot.jobId(),
                choice.path, before, restorable, plan.decorations().size(),
                plan.furniture().size(), plan.clearance().size(), 0, legacy));
    }

    /** 旧标记 {@code "x,y,z"} → 门坐标；不是这个形状返回 {@code null}。 */
    static int[] parseLegacyGate(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split(",", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException notGate) {
            return null;
        }
    }

    /**
     * 读世界 PDC 里的旧布局标记（**只读**）。
     *
     * <p>PDC 底层是无锁 {@code HashMap}，只允许全局线程碰；本方法只在命令线程（Folia 下即全局 region
     * 线程）上被调用，区域线程路径只用插件目录里的文件与启动缓存。
     */
    private String pdcLayout(World world) {
        try {
            return world.getPersistentDataContainer()
                    .get(new NamespacedKey(plugin, EndPodiumShell.KEY_LAYOUT), PersistentDataType.STRING);
        } catch (RuntimeException error) {
            plugin.getComponentLogger().warn("中心祭坛清理：读世界 PDC 旧标记失败：" + reason(error));
            return null;
        }
    }

    /** 读该世界全部快照（按 job id 排序，便于稳定报告）。 */
    private List<EndPodiumBackup.Snapshot> snapshots(UUID worldId) {
        List<EndPodiumBackup.Snapshot> found = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Path dir = snapshotDir(worldId);
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString()
                            .endsWith(EndPodiumBackup.FILE_SUFFIX))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException error) {
            return found;
        }
        java.util.Set<UUID> undo = undoJobs(worldId);
        for (Path file : files) {
            String name = file.getFileName().toString();
            String jobText = name.substring(0, name.length() - EndPodiumBackup.FILE_SUFFIX.length());
            try {
                UUID job;
                try {
                    job = UUID.fromString(jobText);
                } catch (IllegalArgumentException notAJob) {
                    job = null;
                }
                if (job != null && undo.contains(job)) {
                    continue; // 撤回快照：不是施工证据
                }
                found.add(EndPodiumBackup.read(file));
            } catch (IOException | RuntimeException broken) {
                // 坏文件不参与匹配：宁可不清理，也不拿半个快照回滚。
                plugin.getComponentLogger().warn("中心祭坛清理：跳过读不懂的快照 " + file + "："
                        + reason(broken));
            }
        }
        return found;
    }

    /** 撤回快照的 job 名单：{@code podium-layout/<worldUUID>.undo-jobs}，一行一个。 */
    private Path undoJobsFile(UUID worldId) {
        return dataDir.resolve("podium-layout").resolve(worldId + ".undo-jobs");
    }

    private void rememberUndoJob(UUID worldId, UUID jobId) {
        Path file = undoJobsFile(worldId);
        try {
            Files.createDirectories(file.getParent());
            String existing = Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8) : "";
            dev.mumu.betterend.AtomicFiles.replace(file,
                    (existing + jobId + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            plugin.getComponentLogger().warn("中心祭坛清理：记录撤回快照 job 失败（下一轮可能把它当候选）："
                    + reason(error));
        }
    }

    /** undo 名单里的 job：这些是「清理前的现场」，永远不能当施工证据。 */
    private java.util.Set<UUID> undoJobs(UUID worldId) {
        java.util.Set<UUID> jobs = new HashSet<>();
        Path file = undoJobsFile(worldId);
        if (!Files.isRegularFile(file)) {
            return jobs;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                try {
                    jobs.add(UUID.fromString(text));
                } catch (IllegalArgumentException ignore) {
                    // 坏行忽略：名单只用来排除，不需要严格
                }
            }
        } catch (IOException error) {
            plugin.getComponentLogger().warn("中心祭坛清理：读撤回快照名单失败：" + reason(error));
        }
        return jobs;
    }

    private Path snapshotDir(UUID worldId) {
        return dataDir.resolve(EndPodiumBackup.DIRECTORY_NAME).resolve(worldId.toString());
    }

    /** 选择与本布局对应的快照 job。包私有：单测要直接驱动 {@link #chooseJob}。 */
    static final class Choice {
        final EndPodiumBackup.Snapshot snapshot;
        final Path path;
        final Status status;
        final String problem;

        private Choice(EndPodiumBackup.Snapshot snapshot, Path path, Status status, String problem) {
            this.snapshot = snapshot;
            this.path = path;
            this.status = status;
            this.problem = problem;
        }
    }

    /**
     * 快照 job 与布局的对应关系。全部条件都必须成立，缺一条就**不是**这一轮的证据：
     * <ol>
     *   <li>快照头里的 worldUUID 就是这个世界（{@link EndPodiumBackup.Snapshot#worldId()}）；</li>
     *   <li>每个坐标都落在该布局的 29×27×29 盒内，且最低 y 正好是该布局的 {@code baseY}
     *       —— 17k 格模板 + 精确盒对齐是「必要且近充分」的几何指纹；</li>
     *   <li>与 {@link EndPodiumTemplate#isFurniture} 掩码**不相交**（{@code reanchor} 写的那种
     *       「只含旧门家具」的快照在这里被排除，绝不能把门本体当装饰删）；</li>
     *   <li>覆盖尽可能多的装饰格；覆盖数并列判为歧义并拒绝——「取最新的 .gz」不是证据。</li>
     * </ol>
     */
    static Choice chooseJob(EndPodiumTemplate.Plan plan, List<EndPodiumBackup.Snapshot> candidates,
                            Path snapshotDir, UUID worldId) {
        Set<Long> writes = new HashSet<>(
                plan.writes().stream().map(EndPodiumRemoval::pack).toList());
        Set<Long> decorations = new HashSet<>(
                plan.decorations().stream().filter(cell -> !cell.air())
                        .map(EndPodiumRemoval::pack).toList());
        Map<Long, String> targetState = new HashMap<>();
        for (EndPodiumTemplate.Cell cell : plan.decorations()) {
            targetState.put(pack(cell), cell.state());
        }
        int best = -1;
        List<EndPodiumBackup.Snapshot> winners = new ArrayList<>();
        int wrongWorld = 0;
        int outside = 0;
        int furniture = 0;
        for (EndPodiumBackup.Snapshot snapshot : candidates) {
            if (snapshot.worldId() != null && !snapshot.worldId().equals(worldId)) {
                wrongWorld++;
                continue;
            }
            boolean insideLayout = true;
            boolean touchesFurniture = false;
            int minY = Integer.MAX_VALUE;
            int covered = 0;
            for (EndPodiumBackup.Cell cell : snapshot.cells()) {
                long key = pack(cell.x(), cell.y(), cell.z());
                minY = Math.min(minY, cell.y());
                if (EndPodiumTemplate.isFurniture(plan.gateX(), plan.gateY(), plan.gateZ(),
                        cell.x(), cell.y(), cell.z())) {
                    touchesFurniture = true;
                    break;
                }
                if (!writes.contains(key)) {
                    insideLayout = false;
                    break;
                }
                if (decorations.contains(key)) {
                    covered++;
                }
            }
            if (touchesFurniture) {
                furniture++;
                continue;
            }
            if (!insideLayout || minY != plan.baseY()) {
                outside++;
                continue;
            }
            if (covered == 0) {
                continue;
            }
            if (covered > best) {
                best = covered;
                winners.clear();
                winners.add(snapshot);
            } else if (covered == best) {
                winners.add(snapshot);
            }
        }
        if (winners.isEmpty()) {
            return new Choice(null, null, Status.SNAPSHOT_MISMATCH,
                    "没有一份快照同时满足「同世界 + 盒内/底面对齐 + 不碰门家具 + 覆盖装饰」："
                            + "世界不符 " + wrongWorld + "、坐标不在本布局 " + outside
                            + "、只含门家具 " + furniture + "（共 " + candidates.size() + " 份）");
        }
        if (winners.size() > 1) {
            // 同一几何跑过两轮（shell 的有界重入）会留下覆盖数相同的真施工快照。这里**不用任意
            // tie-break**：按「快照里仍等于模板目标的格最少」挑最早/最干净的一轮——越早的快照里，
            // 越多格还只是天然地形；只有这一条仍然并列，才真的拒绝。
            List<EndPodiumBackup.Snapshot> cleanest = new ArrayList<>();
            int fewest = Integer.MAX_VALUE;
            for (EndPodiumBackup.Snapshot snapshot : winners) {
                int stillTarget = 0;
                for (EndPodiumBackup.Cell cell : snapshot.cells()) {
                    String want = targetState.get(pack(cell.x(), cell.y(), cell.z()));
                    if (want != null && want.equals(cell.state())) {
                        stillTarget++;
                    }
                }
                if (stillTarget < fewest) {
                    fewest = stillTarget;
                    cleanest.clear();
                    cleanest.add(snapshot);
                } else if (stillTarget == fewest) {
                    cleanest.add(snapshot);
                }
            }
            if (cleanest.size() > 1) {
                StringBuilder jobs = new StringBuilder();
                for (EndPodiumBackup.Snapshot snapshot : cleanest) {
                    jobs.append(snapshot.jobId()).append(' ');
                }
                return new Choice(null, null, Status.SNAPSHOT_MISMATCH,
                        "有 " + cleanest.size() + " 份快照并列覆盖 " + best + " 格装饰且同样新（" + jobs
                                + "），无法唯一确定施工轮次；请人工指定后再清理");
            }
            winners = cleanest;
        }
        EndPodiumBackup.Snapshot winner = winners.get(0);
        return new Choice(winner,
                snapshotDir.resolve(winner.jobId() + EndPodiumBackup.FILE_SUFFIX), null, null);
    }

    // ------------------------------------------------------------------ 逐格判定

    /**
     * 一格的处置：只有「当前仍等于模板目标」且「快照里有原状」才回滚。
     *
     * <p>模板目标是空气的格不参与回滚（那是净空，填回去就会把门埋掉）；快照里没有这一格说明它不是
     * 本插件写的（或那一轮没写它），一律不动。
     */
    static Action decide(EndPodiumTemplate.Cell target, String current, String before) {
        if (before == null) {
            return Action.KEEP_PLAYER;
        }
        if (EndPodiumTemplate.isFunctionProtected(EndPodiumTemplate.blockId(current))) {
            // 原版门家具/龙蛋：即使它出现在快照里、坐标也落在装饰集内，也一律保留。
            return Action.KEEP_PLAYER;
        }
        if (current.equals(before)) {
            return Action.ALREADY_ORIGINAL; // 已经是原状：没有要落的笔
        }
        return target.state().equals(current) ? Action.RESTORE : Action.KEEP_PLAYER;
    }

    // ------------------------------------------------------------------ 内部工具

    private record Batch(int chunkX, int chunkZ, List<Working> cells) {
    }

    private record Working(EndPodiumTemplate.Cell cell, String before, String restore) {
    }

    /** 预览/预检的计数与样本；跨区块任务并发累加，所以只用原子量。 */
    private static final class Counters {
        final AtomicInteger restorable = new AtomicInteger();
        final AtomicInteger alreadyOriginal = new AtomicInteger();
        final AtomicInteger playerChanged = new AtomicInteger();
        volatile String failed;
        volatile String gateMismatch;
        final Map<Long, String> states = new java.util.concurrent.ConcurrentHashMap<>();

        String digest() {
            return EndPodiumRemoval.stateDigest(states);
        }
        final List<EndPodiumTemplate.Cell> conflicts =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger remaining;

        Counters(int buckets) {
            this.remaining = new AtomicInteger(buckets);
        }

        void finish(Runnable done) {
            if (remaining.decrementAndGet() == 0) {
                done.run();
            }
        }
    }

    private static Map<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> group(
            List<EndPodiumTemplate.Cell> cells) {
        Map<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> buckets = new TreeMap<>(
                (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        for (EndPodiumTemplate.Cell cell : cells) {
            buckets.computeIfAbsent(EndPodiumTemplate.ChunkKey.of(cell.x(), cell.z()),
                    key -> new ArrayList<>()).add(cell);
        }
        return new LinkedHashMap<>(buckets);
    }

    private static boolean manages(World world) {
        return world.getEnvironment() == World.Environment.THE_END
                && world.getGenerator() instanceof DustWastelandsGenerator;
    }

    private static String state(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getBlockData().getAsString();
    }

    private static BlockData data(String state) {
        return YungPillars.bukkit(state);
    }

    static long pack(EndPodiumTemplate.Cell cell) {
        return pack(cell.x(), cell.y(), cell.z());
    }

    private static long pack(int x, int y, int z) {
        return EndPodiumShield.pack(x, y, z);
    }

    /**
     * 在拥有该区块的区域线程上执行（区块先异步就绪）。与施工层同一套纪律：不 join、不跨区、不硬做。
     */
    private boolean region(World world, int chunkX, int chunkZ, Runnable task,
                           Consumer<String> onFailure) {
        return region(world, chunkX, chunkZ, 0L, task, onFailure);
    }

    /** 带延迟的版本：批间 {@link EndPodiumBuild#BATCH_DELAY_TICKS} 用，避免连续占住区域线程。 */
    private boolean region(World world, int chunkX, int chunkZ, long delayTicks, Runnable task,
                           Consumer<String> onFailure) {
        if (closed.get() || !plugin.isEnabled()) {
            if (onFailure != null) {
                onFailure.accept("插件已关闭");
            }
            return false;
        }
        try {
            world.getChunkAtAsync(chunkX, chunkZ).thenRun(() -> {
                if (closed.get() || !plugin.isEnabled()) {
                    if (onFailure != null) {
                        onFailure.accept("插件已关闭");
                    }
                    return;
                }
                try {
                    if (delayTicks <= 0L) {
                        Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ,
                                ignored -> task.run());
                    } else {
                        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ,
                                ignored -> task.run(), delayTicks);
                    }
                } catch (RuntimeException refused) {
                    if (onFailure != null) {
                        onFailure.accept("区域任务被拒绝：" + reason(refused));
                    }
                }
            }).exceptionally(error -> {
                if (onFailure != null) {
                    onFailure.accept("区块加载失败：" + reason(error));
                }
                return null;
            });
            return true;
        } catch (RuntimeException refused) {
            if (onFailure != null) {
                onFailure.accept("无法请求区块：" + reason(refused));
            }
            return false;
        }
    }

    /** 磁盘只能在异步线程做。 */
    private void dispatchAsync(Runnable task, Consumer<String> onFailure) {
        if (closed.get() || !plugin.isEnabled()) {
            onFailure.accept("插件已关闭");
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
        } catch (RuntimeException refused) {
            onFailure.accept(reason(refused));
        }
    }

    private void finish(UUID worldId, Consumer<Result> done, Result result) {
        running.remove(worldId);
        done.accept(result);
    }

    private static Preview reject(Status status, String detail) {
        return new Preview(status, detail, null, null, 0, 0, 0, 0L, 0, 0, 0, 0, 0, List.of(), false);
    }

    private static Result fail(Status status, String detail) {
        return new Result(status, detail, 0, 0, null);
    }

    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
