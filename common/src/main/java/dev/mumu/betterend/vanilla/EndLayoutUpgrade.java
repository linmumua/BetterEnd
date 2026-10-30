package dev.mumu.betterend.vanilla;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 旧世界（0.1.26 及以前）显式迁移：**先只读预览，再由人确认执行**。
 *
 * <h2>为什么换 jar 不够</h2>
 * 换 jar 只改变「之后新生成的东西」。已经生成的世界里躺着<b>旧布局</b>：柱子按
 * {@code height + 1 - offset} 压低过、中心楼是按旧返回门坐标砌的、世界 PDC 里只有一句
 * {@code state=placed} 和一句 {@code "x,y,z"} 形式的旧门坐标（{@link EndPodiumBuild#MARK_LAYOUT} 的旧写法）。
 * 新代码的 {@link YungPillars} 与 {@link EndPodiumTemplate#planAtSurface(int)} 都不会回头去动它们，
 * 所以那种世界**没有被修复**，只是安静地保留了两套布局。这个类就是那条明说的入口。
 *
 * <h2>三件必须同时做的事（一次一整项，不许只改门坐标）</h2>
 * <ol>
 *   <li><b>柱</b>：旧柱段（上段/下段/座位）、旧晶座与<b>同一颗</b>原版结构晶的搬移，三项必须由柱侧一起给出；</li>
 *   <li><b>中心楼</b>：旧楼整体重定位到 {@code gateY = S - 3}（{@code S} = 纯地形场顶块），
 *       不允许新旧两座楼同时存在；</li>
 *   <li><b>持久化</b>：新 {@link EndPodiumTemplate.Layout}（{@code v2:seed=..:gate=..}）整项落盘。
 *       只改门坐标、不落新 PDC，等于下次重启又按旧锚点算一遍。</li>
 * </ol>
 * 因此迁移内容是**调用方（柱侧/中央侧）按段交上来的** {@link Section}：本类自己不重算模板几何，
 * 也就不会出现「两处各算一套 Y」的老问题。任何一段缺失、带 {@code blockers}、或整段为空，都让整项拒绝。
 *
 * <h2>所有权：不猜</h2>
 * 旧柱没有可靠所有权证据（世界里没有任何「这根柱是我们放的」的记录）时，<b>只报冲突并拒绝</b>，
 * 绝不靠「材料像黑曜石/哭泣黑曜石」或「和模板长得一样」反推所有权 —— 玩家自己摆的黑曜石柱会因此被
 * 当成自己的成品覆盖掉。唯一被承认的柱所有权证据是柱侧登记的同一颗结构晶 UUID
 * （{@code World#structureCrystal}）。
 *
 * <p>中心楼也一样：旧的 {@code placed} 标记只证明「这里建过楼」，不证明「这一格现在还是那座楼的一部分」。
 * 所以旧楼本体的方块在预览里会全部以冲突形式出现，逐格报给人工确认；可核验的只有原版门家具那几十格
 * （{@link EndPodiumTemplate#isFurniture}），而它们不在 {@link EndPodiumTemplate.Plan#writes()} 里。
 * 真正的旧楼搬迁由中央侧的受控重定位负责（核验门家具 → 压缩快照 → 清旧 → 搬迁锚点），本类只把
 * 「不能自动做」说清楚。
 *
 * <h2>执行顺序与线程</h2>
 * {@link #apply} 只做三件事：校验确认 token、读全范围快照、签发 {@link Grant}。
 * <b>快照没成功就一格都不写</b>（{@link Backup#save} 抛异常即中止）。真正的写世界由调用方按
 * {@link Grant#batches()} 逐个区块、每批最多 {@link #BATCH_CELLS} 格，调度到<b>拥有该区块的区域线程</b>
 * 上，再调用 {@link Grant#write}：它会逐格与快照重核，并且<b>区块不归当前执行者所有就整批不动</b>
 * （未知块不动）。龙战状态（active / 击杀记录 / 龙 UUID）本类只读、只核对，不写。
 *
 * <h2>不做什么</h2>
 * 不删除世界、不删除区块、不自动重复搬迁、不改全局 spike 缓存、不做「迁移中继续往上叠」的续建。
 * 复活进行中直接拒绝；迁移完不核对龙战状态就不算成功。
 */
public final class EndLayoutUpgrade {

    /** 语义键：调用方负责映射成 {@code EndPodiumShell.markKey} 里的世界 PDC 键。 */
    public static final String MARK_STATE = EndPodiumBuild.MARK_STATE;
    public static final String MARK_LAYOUT = EndPodiumBuild.MARK_LAYOUT;
    /** 旧版写成功的标记值；新布局另用 {@link EndPodiumTemplate#LAYOUT_VERSION} 区分。 */
    public static final String PLACED = EndPodiumBuild.PLACED;

    /** 一个区域任务最多多少格：与 {@link EndPodiumBuild#BATCH_CELLS} 同一个上限，连「批」的含义都一致。 */
    public static final int BATCH_CELLS = EndPodiumBuild.BATCH_CELLS;

    /**
     * 迁移后的上段/下段基准（{@link YungPillars} 冻结的 70 / 3）。
     *
     * <p>这两个数<b>只是预览里「新布局长什么样」的展示口径</b>，不参与任何写世界：真正要写的格子由
     * {@link Section} 交上来。保留它们的唯一理由是 preview 必须能对着旧值报出「新高度是多少」，
     * 否则用户看不到这次迁移会把柱子抬高几格。
     */
    public static final int NEW_UPPER_BASE_Y = 70;
    public static final int NEW_LOWER_BASE_Y = NEW_UPPER_BASE_Y - YungPillars.BOTTOM_DROP;

    /** 预览里最多列几条冲突样本，剩下的只报个数（避免一次刷几百行）。 */
    static final int MAX_CONFLICT_SAMPLES = 12;

    private EndLayoutUpgrade() {
    }

    // ---------------------------------------------------------------- 端口

    /**
     * 只读世界探针。<b>所有方法都必须在「拥有该区块的区域线程」上被调用</b>，调用方负责调度；
     * 本类不建线程、不 join、不假设区域大小。
     */
    public interface World {

        UUID worldId();

        long seed();

        /** 世界最低可建高度（终结维度 0）。 */ 
        int minY();

        /** 世界最高可建高度。 */
        int maxY();

        /**
         * 纯地形场在这一列的顶块 Y（不含任何建筑）。这是新中央锚点的<b>唯一</b>合法来源：
         * {@code floorY = S}、{@code gateY = S - 3}。读不到就抛。
         */
        int surfaceTopY(int x, int z);

        /** 该区块是否已就绪并且归当前执行者所有。false = 未知块，读也不读、写也不写。 */
        boolean owns(int chunkX, int chunkZ);

        /** 当前方块状态串（{@code BlockData#getAsString()}）。 */
        String state(int x, int y, int z);

        /** 这一格是不是容器/方块实体（只用来把中止原因讲清楚）。 */
        boolean tileState(int x, int y, int z);

        /** 世界 PDC 里的字符串，语义键见 {@link #MARK_STATE} / {@link #MARK_LAYOUT}。 */
        String mark(String key);

        /**
         * 柱侧登记的原版结构晶实体 UUID。<b>空 = 没有所有权证据</b>，不是「没有水晶」。
         *
         * @param spikeIndex {@link EndSpikes#forSeed(long)} 里的下标
         */
        Optional<UUID> structureCrystal(int spikeIndex);

        /** 龙战状态（只读；迁移必须原样保住）。 */
        Fight fight();
    }

    /** 返回门平面坐标。 */
    public record Gate(int x, int y, int z) {
    }

    /**
     * 龙战状态快照。
     *
     * @param previouslyKilled   {@code DragonBattle#hasBeenPreviouslyKilled()}（active 返回门是否已在）
     * @param respawnInProgress  正在复活：迁移必须拒绝
     * @param dragonId           当前龙实体 UUID（没有就是空）
     * @param gate               记录中的门坐标（没有就是空）
     */
    public record Fight(boolean previouslyKilled, boolean respawnInProgress, Optional<UUID> dragonId,
                        Optional<Gate> gate) {

        public Fight {
            Objects.requireNonNull(dragonId, "dragonId");
            Objects.requireNonNull(gate, "gate");
        }
    }

    /**
     * 快照端口。{@link #pathFor} 让预览能报出「备份会落在哪里」，{@link #save} 落盘。
     * <b>{@code save} 抛异常即中止整项迁移</b>（一格都不写）。
     */
    public interface Backup {

        /** 该 job 的备份目标路径（固定目录，不接受调用方自己拼的路径）。 */
        Path pathFor(UUID jobId);

        /** 落盘；异步线程上调用，只能碰磁盘。 */
        Path save(UUID jobId, List<EndPodiumBackup.Cell> cells) throws IOException;
    }

    /** 单格写入。只在拥有该区块的区域线程上被调用；不做物理/邻居更新由实现负责。 */
    public interface Writer {
        void write(int x, int y, int z, String state);
    }

    // ---------------------------------------------------------------- 迁移内容

    /** 一格目标状态。 */
    public record Write(int x, int y, int z, String state) {
        public Write {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * 迁移的一项：{@code name} 只用于报告（{@code "pillars"} / {@code "podium"} / {@code "pdc"}）。
     *
     * @param writes   要写的格子（含「清掉旧的两套」需要的格子，由该侧自己算）
     * @param handoffs 需要<b>原样搬过去</b>的实体（柱侧那同一颗结构晶）；本类只核对数量与不重复
     * @param marks    要写入世界 PDC 的 {@code key=value}；新布局串必须在这里
     * @param blockers 这一项为什么不能自动做；非空即让整项迁移拒绝
     */
    public record Section(String name, List<Write> writes, List<UUID> handoffs, List<String> marks,
                          List<String> blockers) {

        public Section {
            Objects.requireNonNull(name, "name");
            writes = List.copyOf(writes);
            handoffs = List.copyOf(handoffs);
            marks = List.copyOf(marks);
            blockers = List.copyOf(blockers);
        }

        /** 一个「本轮做不到」的段：带明确理由，绝不用空段冒充已完成。 */
        public static Section blocked(String name, String reason) {
            return new Section(name, List.of(), List.of(), List.of(), List.of(reason));
        }

        public static Section of(String name, List<Write> writes, List<UUID> handoffs, List<String> marks) {
            return new Section(name, writes, handoffs, marks, List.of());
        }
    }

    /** 整项迁移的三段（柱 / 中心楼 / 持久化）。顺序无关，但三段都必须出现。 */
    public record Parts(List<Section> sections) {

        public Parts {
            sections = List.copyOf(sections);
        }

        public static Parts of(Section... sections) {
            return new Parts(List.of(sections));
        }

        Optional<Section> named(String name) {
            for (Section section : sections) {
                if (section.name().equals(name)) return Optional.of(section);
            }
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- 预览结果

    /** 一根柱子的旧/新几何。{@code owned} 是「有没有可靠所有权证据」，不是「像不像旧柱」。 */
    public record PillarDelta(int index, int tier, boolean owned, String evidence,
                              int oldTopAnchorY, int oldBottomAnchorY, int oldSeatY, int oldCrystalY,
                              int newTopAnchorY, int newBottomAnchorY, int newSeatY, int newCrystalY) {

        public int riseY() {
            return newTopAnchorY - oldTopAnchorY;
        }

        public int crystalRiseY() {
            return newCrystalY - oldCrystalY;
        }
    }

    /** 中心楼的旧/新锚点。{@code legacyGate} 空 = 旧世界连旧标记都没有（更没证据）。 */
    public record PodiumDelta(Optional<Gate> legacyGate, Gate targetGate,
                              int oldFloorY, int newFloorY, int shiftY) {
    }

    /** 影响范围（闭区间）。 */
    public record Range(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public long cells() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /**
     * 一次预览的全部结论（不可变，不含任何写世界副作用）。
     *
     * @param token       确认 token：内容一变就变，供 {@link #apply} 校验「人确认的就是眼前这一份」
     * @param allowed     现在能不能 apply；false 时 {@link #blockers} 里必须是具体理由
     * @param conflicts   玩家改动/未知方块等需要人处理的点
     * @param evidence    支持本次判断的证据行（旧标记原文、登记的水晶 UUID、旧计划比对结论……）
     * @param backupPath  若 apply，快照会落在这里（固定目录，不接受外部路径）
     */
    public record Preview(UUID worldId, long seed, Fight fight, List<PillarDelta> pillars,
                          Optional<PodiumDelta> podium, List<Range> ranges, int totalWrites,
                          List<String> evidence, List<String> conflicts, List<String> blockers,
                          Optional<Path> backupPath, String token, boolean allowed) {

        public Preview {
            pillars = List.copyOf(pillars);
            ranges = List.copyOf(ranges);
            evidence = List.copyOf(evidence);
            conflicts = List.copyOf(conflicts);
            blockers = List.copyOf(blockers);
        }
    }

    /** 一批要写的格子：同一区块、最多 {@link #BATCH_CELLS} 格。 */
    public record Batch(int chunkX, int chunkZ, List<Write> writes) {

        public Batch {
            writes = List.copyOf(writes);
        }
    }

    /** 单批执行结果。{@code ok=false} 时 {@code notes} 里一定有原因，且**这一批一格都没写**。 */
    public record Result(boolean ok, int written, List<String> notes) {

        public Result {
            notes = List.copyOf(notes);
        }

        static Result ok(int written) {
            return new Result(true, written, List.of());
        }

        static Result refused(String reason) {
            return new Result(false, 0, List.of(reason));
        }
    }

    /**
     * 快照已完成、token 已核对的「准迁证」。
     *
     * <p>它不持有任何区域线程，也不自己排任务：调用方按 {@link #batches()} 把每一批调度到拥有该区块的
     * 区域线程上，再调 {@link #write}。中途任何一格与快照不符，那批就整批不动并返回失败。
     */
    public static final class Grant {

        private final Path backupFile;
        private final Fight fight;
        private final Map<Coord, String> before;
        private final List<Batch> batches;
        private final List<String> marks;

        private Grant(Path backupFile, Fight fight, Map<Coord, String> before, List<Batch> batches,
                      List<String> marks) {
            this.backupFile = backupFile;
            this.fight = fight;
            this.before = before;
            this.batches = List.copyOf(batches);
            this.marks = List.copyOf(marks);
        }

        /** 全范围快照落在哪个文件。用户要看到的「备份位置」就是它。 */
        public Path backupFile() {
            return backupFile;
        }

        /**
         * 要写入世界 PDC 的 {@code key=value}（新布局串就在里面）。
         *
         * <p><b>只能在这批格子全部写完、且 {@link #verifyPreserved} 通过之后才写</b>：完成标记是
         * 「这一轮成功了」的证据，提前写就等于用一句旧标记谎称新布局已完成。
         */
        public List<String> marks() {
            return marks;
        }

        /** apply 时的龙战状态；收尾用 {@link #verifyPreserved} 核对。 */
        public Fight fight() {
            return fight;
        }

        public List<Batch> batches() {
            return batches;
        }

        /**
         * 执行一批。
         *
         * <p>顺序是「先看后写」：整批逐格与快照重核<b>全部通过</b>才动第一格；任何一格被改动、区块不归
         * 当前执行者所有、或世界高度越界，都整批返回失败并<b>一格不写</b>。
         */
        public Result write(Batch batch, World world, Writer writer) {
            Objects.requireNonNull(batch, "batch");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(writer, "writer");
            if (!world.owns(batch.chunkX(), batch.chunkZ())) {
                return Result.refused("区块 (" + batch.chunkX() + ", " + batch.chunkZ()
                        + ") 未就绪或不属于当前区域线程：未知块不动，本批一格未写");
            }
            List<String> drifted = new ArrayList<>();
            List<String> outOfRange = new ArrayList<>();
            for (Write write : batch.writes()) {
                if (write.y() < world.minY() || write.y() > world.maxY()) {
                    outOfRange.add(describe(write) + " 超出世界高度 " + world.minY() + ".." + world.maxY());
                    continue;
                }
                String expected = before.get(new Coord(write.x(), write.y(), write.z()));
                if (expected == null) {
                    drifted.add(describe(write) + " 不在本次快照里（快照与实际范围不一致）");
                    continue;
                }
                String current = world.state(write.x(), write.y(), write.z());
                if (!expected.equals(current)) {
                    drifted.add(describe(write) + " 已被改动：" + expected + " → " + current);
                }
            }
            if (!outOfRange.isEmpty() || !drifted.isEmpty()) {
                List<String> notes = new ArrayList<>(outOfRange.size() + drifted.size() + 1);
                notes.add("本批 " + batch.writes().size() + " 格一格未写：预检未通过");
                notes.addAll(outOfRange);
                notes.addAll(drifted);
                return new Result(false, 0, notes);
            }
            int written = 0;
            for (Write write : batch.writes()) {
                writer.write(write.x(), write.y(), write.z(), write.state());
                written++;
            }
            return Result.ok(written);
        }

        /**
         * 收尾核对：龙战状态必须与 {@link #fight()} 完全一致。
         *
         * <p>迁移<b>不写</b> active、击杀记录与龙 UUID，它们留在 MU 原生 {@code EnderDragonFight} 里；
         * 这里只验证「搬完之后没有把它们弄丢」。返回空列表 = 已保住。
         */
        public List<String> verifyPreserved(World world) {
            Objects.requireNonNull(world, "world");
            Fight now = world.fight();
            List<String> problems = new ArrayList<>();
            if (now.previouslyKilled() != fight.previouslyKilled()) {
                problems.add("active 返回门状态变了：" + fight.previouslyKilled() + " → " + now.previouslyKilled());
            }
            if (!now.dragonId().equals(fight.dragonId())) {
                problems.add("龙 UUID 变了：" + fight.dragonId() + " → " + now.dragonId());
            }
            if (now.respawnInProgress()) {
                problems.add("收尾时复活又在进行，迁移结果不可信");
            }
            return problems;
        }

        private static String describe(Write write) {
            return "(" + write.x() + ", " + write.y() + ", " + write.z() + ")→" + write.state();
        }
    }

    private record Coord(int x, int y, int z) {
    }

    // ---------------------------------------------------------------- 预览

    /**
     * 只读预览：算出旧/新几何、影响范围、来源证据、玩家冲突与备份位置，并签发确认 token。
     * <b>不备份、不写世界</b>。
     *
     * @param parts 迁移内容（柱 / 中心楼 / 持久化三段）
     */
    public static Preview preview(World world, Parts parts, Backup backup) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(backup, "backup");

        List<String> evidence = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        UUID worldId = world.worldId();
        long seed = world.seed();
        Fight fight = world.fight();

        // ---- 持久化状态：新布局串能否解析，决定这是不是「已迁移」 ----
        String layoutMark = world.mark(MARK_LAYOUT);
        String stateMark = world.mark(MARK_STATE);
        boolean legacy = !PLACED.equals(stateMark) || layoutMark == null || layoutMark.isBlank();
        if (layoutMark != null && !layoutMark.isBlank()) {
            try {
                EndPodiumTemplate.Layout current = EndPodiumTemplate.decode(layoutMark);
                blockers.add("世界已经落过新版布局 " + current.encode() + "：这不是旧世界，不需要（也不允许）再迁移一次。");
                evidence.add("世界 PDC " + MARK_LAYOUT + " = " + layoutMark + "（可解析为 v"
                        + EndPodiumTemplate.LAYOUT_VERSION + "）");
            } catch (IllegalArgumentException ignored) {
                evidence.add("世界 PDC " + MARK_LAYOUT + " = " + layoutMark + "（不是 v"
                        + EndPodiumTemplate.LAYOUT_VERSION + "，按旧世界处理）");
            }
        } else {
            evidence.add("世界 PDC 没有 " + MARK_LAYOUT + " 记录：中心楼无版本化布局证据");
        }
        Optional<Gate> legacyGate = parseLegacyGate(layoutMark);
        if (legacy) {
            evidence.add("世界 PDC " + MARK_STATE + " = " + stateMark
                    + (legacyGate.isPresent() ? "，旧门坐标 = " + format(legacyGate.get()) : "，旧门坐标不可解析"));
        }

        // ---- 中心楼：新锚点由纯地形场顶块算，旧锚点只认已落盘的旧标记 ----
        Optional<PodiumDelta> podium = Optional.empty();
        List<Range> ranges = new ArrayList<>();
        int targetSurface = world.surfaceTopY(0, 0);
        int targetGateY = targetSurface - EndPodiumTemplate.GATE_BELOW_FLOOR;
        Gate targetGate = new Gate(0, targetGateY, 0);
        if (legacyGate.isPresent()) {
            Gate old = legacyGate.get();
            int oldFloorY = old.y() + EndPodiumTemplate.GATE_BELOW_FLOOR;
            podium = Optional.of(new PodiumDelta(legacyGate, targetGate, oldFloorY, targetSurface,
                    targetSurface - oldFloorY));
            EndPodiumTemplate.Plan oldPlan;
            EndPodiumTemplate.Plan newPlan = EndPodiumTemplate.planAtSurface(targetSurface);
            try {
                oldPlan = EndPodiumTemplate.plan(old.x(), old.y(), old.z());
            } catch (RuntimeException | LinkageError broken) {
                oldPlan = null;
                blockers.add("旧门坐标 " + format(old) + " 算不出旧中心楼计划（" + broken + "），无法核对旧楼范围");
            }
            ranges.add(rangeOf("旧中心楼", oldPlan));
            ranges.add(rangeOf("新中心楼", newPlan));
            conflicts.addAll(podiumConflicts(world, oldPlan, newPlan, targetGate, evidence, blockers));
        } else {
            blockers.add("读不到可解析的旧门坐标（" + MARK_LAYOUT + " = " + layoutMark
                    + "）：算不出旧中心楼在哪，无法核对旧楼范围与玩家改动。按规则不猜。");
        }

        // ---- 柱：旧/新几何按冻结公式各算一遍，所有权只认登记的水晶 UUID ----
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(seed);
        List<PillarDelta> pillars = new ArrayList<>(spikes.size());
        int writeFloor = world.minY();
        int writeCeil = world.maxY();
        for (int index = 0; index < spikes.size(); index++) {
            EndSpikes.Spike spike = spikes.get(index);
            int tier = Math.max(1, YungPillars.tier(spike.height()));
            int offset = YungPillars.crystalOffset(tier);
            int oldTop = spike.height() + 1 - offset;
            int oldBottom = oldTop - YungPillars.BOTTOM_DROP;
            int newTop = NEW_UPPER_BASE_Y;
            int newBottom = NEW_LOWER_BASE_Y;
            Optional<UUID> crystal = world.structureCrystal(index);
            boolean owned = crystal != null && crystal.isPresent();
            String note = owned
                    ? "已登记原版结构晶 " + crystal.get()
                    : "没有登记的原版结构晶 UUID：无所有权证据";
            if (!owned) {
                blockers.add("第 " + index + " 根柱（柱心 " + spike.centerX() + ", " + spike.centerZ()
                        + "）没有可靠所有权证据（世界里没有这根柱属于本插件的记录）。"
                        + "按规则不从材料/模板形状推断，拒绝自动迁移。");
            }
            pillars.add(new PillarDelta(index, tier, owned, note,
                    oldTop, oldBottom, oldTop + offset - 1, oldTop + offset,
                    newTop, newBottom, newTop + offset - 1, newTop + offset));
            Range oldRange = pillarRange("旧柱#" + index, spike, oldTop, oldBottom);
            Range newRange = pillarRange("新柱#" + index, spike, newTop, newBottom);
            ranges.add(oldRange);
            ranges.add(newRange);
            // 旧布局的下段本来就掉到世界底线以下（这正是它当年被截断的原因），那是历史事实，
            // 不是本次的阻断项：迁移只需要把「新的」放在合法高度里，不需要去写那些不存在的负层。
            if (oldRange.minY() < writeFloor) {
                evidence.add("旧柱#" + index + " 的下段名义最低 " + oldRange.minY() + "，低于世界底线 "
                        + writeFloor + "（旧模板当年就被截断，本次不补也不写那一段）");
            }
            if (newRange.minY() < writeFloor) {
                blockers.add("第 " + index + " 根柱的新模板内容低于世界底线 " + writeFloor
                        + "（最低 " + newRange.minY() + "）：不能静默裁剪，本次施工不成立。");
            }
            if (newRange.maxY() > writeCeil) {
                blockers.add("第 " + index + " 根柱的新模板内容高于世界顶线 " + writeCeil
                        + "（最高 " + newRange.maxY() + "）：不能静默裁剪。");
            }
        }

        // ---- 迁移内容三段：缺一段整项拒绝；任何一段自带 blockers 也整项拒绝 ----
        int totalWrites = 0;
        Set<String> seenNames = new LinkedHashSet<>();
        for (Section section : parts.sections()) {
            if (!seenNames.add(section.name())) {
                blockers.add("迁移内容里出现了两段同名的 " + section.name() + "：整项拒绝（无法判断该听哪一段）");
            }
            blockers.addAll(section.blockers());
            if (section.writes().isEmpty() && section.marks().isEmpty() && section.blockers().isEmpty()) {
                blockers.add("迁移内容里的 " + section.name() + " 段是空的：空段不构成「已完成」的证据");
            }
            totalWrites += section.writes().size();
            if (!section.handoffs().isEmpty() && section.writes().isEmpty()) {
                blockers.add(section.name() + " 段要求搬 " + section.handoffs().size()
                        + " 个实体却没有给任何目标格：不能只抬实体不改柱段");
            }
        }
        for (String required : new String[]{"pillars", "podium", "pdc"}) {
            if (parts.named(required).isEmpty()) {
                blockers.add("迁移内容缺少 " + required + " 段：柱段/晶座/同实体 + 中心楼/旧核心 + 新 PDC 必须整项处理，"
                        + "只改门坐标不算修复。");
            }
        }

        // ---- 复活进行中：直接拒绝 ----
        if (fight.respawnInProgress()) {
            blockers.add("龙正在复活中：此刻迁移会把复活流程弄成半套（门在搬、柱在被原版重写），拒绝。");
        }

        // ---- 备份位置与实际落盘同一个端口：预览报的就是 apply 会写的那个文件 ----
        Path backupPath = null;
        UUID jobId = jobIdOf(worldId, seed, targetGate);
        try {
            backupPath = backup.pathFor(jobId);
        } catch (RuntimeException unresolvable) {
            blockers.add("解析不出备份路径（" + unresolvable.getMessage() + "）：没有备份就不迁移");
        }
        Optional<Section> pdc = parts.named("pdc");
        if (pdc.isPresent() && !pdc.get().marks().isEmpty()) {
            evidence.add("apply 后要落的世界 PDC：" + String.join("；", pdc.get().marks()));
        }

        // ---- token：把决策相关工作全折进去 ----
        String token = tokenOf(worldId, seed, world.minY(), world.maxY(), targetGate, legacyGate,
                pillars, parts, ranges);

        boolean allowed = blockers.isEmpty() && conflicts.isEmpty();
        return new Preview(worldId, seed, fight, pillars, podium, ranges, totalWrites,
                evidence, conflicts, blockers, Optional.ofNullable(backupPath), token, allowed);
    }

    /**
     * 校验 token、读全范围快照、签发 {@link Grant}。<b>快照失败即中止，一格不写</b>。
     *
     * @param token 人看到的预览给出的确认 token；与 {@code preview.token()} 不一致就拒绝
     * @throws IOException 快照落盘失败（此时世界一格未动）
     */
    public static Grant apply(World world, Parts parts, Preview preview, String token, Backup backup)
            throws IOException {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(backup, "backup");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("缺少确认 token：迁移必须由人对着 preview 显式确认，不接受默认执行");
        }
        if (!token.equals(preview.token())) {
            throw new IllegalArgumentException("确认 token 与当前预览不一致（预览可能已过期或世界已变化）："
                    + "请重新 preview 并确认新的 token");
        }
        if (!preview.allowed()) {
            throw new IllegalStateException("这份预览本身不允许执行：" + String.join(" / ", preview.blockers())
                    + (preview.conflicts().isEmpty() ? "" : "；另有 " + preview.conflicts().size() + " 处冲突"));
        }
        if (!preview.worldId().equals(world.worldId())) {
            throw new IllegalArgumentException("预览属于世界 " + preview.worldId() + "，当前是 "
                    + world.worldId() + "：拒绝跨世界执行");
        }
        // 世界可能在这两步之间变了：几何与复活状态按「现在」再核一次，token 只证明「人确认的是这一份」。
        if (world.fight().respawnInProgress()) {
            throw new IllegalStateException("龙正在复活中：拒绝迁移");
        }
        Fight fight = world.fight();

        // 合并全部目标格（同名段已被 preview 拒过，这里只做去重与快照）。
        Map<Coord, Write> targets = new LinkedHashMap<>();
        for (Section section : parts.sections()) {
            for (Write write : section.writes()) {
                targets.putIfAbsent(new Coord(write.x(), write.y(), write.z()), write);
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("迁移内容里没有任何目标格：不做空迁移");
        }
        // 读快照：全部在调用方给的线程上下文上读（region owner 已由调用方按段调度）。
        Map<Coord, String> before = new LinkedHashMap<>(targets.size() * 2);
        List<EndPodiumBackup.Cell> cells = new ArrayList<>(targets.size());
        for (Coord coord : targets.keySet()) {
            int chunkX = coord.x() >> 4;
            int chunkZ = coord.z() >> 4;
            if (!world.owns(chunkX, chunkZ)) {
                throw new IllegalStateException("区块 (" + chunkX + ", " + chunkZ
                        + ") 未就绪或不属于当前区域线程：未知块不动，本次迁移在备份前中止（世界一格未动）");
            }
            String state = world.state(coord.x(), coord.y(), coord.z());
            before.put(coord, state);
            cells.add(new EndPodiumBackup.Cell(coord.x(), coord.y(), coord.z(), state));
        }
        UUID jobId = jobIdOf(preview.worldId(), preview.seed(), gateOf(preview));
        // 快照落盘：失败直接抛，绝不继续往下走。
        Path backupFile = backup.save(jobId, cells);

        List<Batch> batches = batch(targets.values());
        List<String> marks = new ArrayList<>();
        for (Section section : parts.sections()) {
            marks.addAll(section.marks());
        }
        return new Grant(backupFile, fight, before, batches, marks);
    }

    // ---------------------------------------------------------------- 内部

    /** 把目标格按区块分桶，每桶最多 {@link #BATCH_CELLS} 格（桶内保持交上来的顺序）。 */
    static List<Batch> batch(Iterable<Write> writes) {
        Map<Long, List<Write>> buckets = new LinkedHashMap<>();
        for (Write write : writes) {
            int chunkX = write.x() >> 4;
            int chunkZ = write.z() >> 4;
            long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(write);
        }
        List<Batch> batches = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, List<Write>> bucket : buckets.entrySet()) {
            int chunkX = (int) (bucket.getKey() >> 32);
            int chunkZ = (int) (long) bucket.getKey();
            List<Write> cells = bucket.getValue();
            for (int from = 0; from < cells.size(); from += BATCH_CELLS) {
                int to = Math.min(from + BATCH_CELLS, cells.size());
                batches.add(new Batch(chunkX, chunkZ, cells.subList(from, to)));
            }
        }
        return batches;
    }

    /**
     * 中心楼段的冲突核对：新计划要写的每一格，世界里必须是天然地形。
     *
     * <p><b>旧楼自己的方块也算冲突</b>，这是有意的：旧 {@code placed} 标记只证明「这里曾经建过楼」，
     * 不证明「这一格现在还是那座楼的一部分」——玩家完全可能在旧楼里塞了箱子或把自己的墙砌在旧墙位置上，
     * 而这些格在旧计划里的目标方块<b>恰好一样</b>。按材料或按模板目标比对都是「同模板推断所有权」，
     * 计划里明令禁止。可核验的只有原版门家具那几十格（{@link EndPodiumTemplate#isFurniture}），
     * 而它们根本不在 {@link EndPodiumTemplate.Plan#writes()} 里。
     *
     * <p>所以旧世界真实的样子会在这里被逐格报出来，交给中央侧的受控重定位
     * （{@code EndPodiumShell#reanchor}：核验门家具 → 压缩快照 → 清旧家具 → 锚点搬迁）去处理，
     * 本类只负责把它<b>说清楚</b>，不冒充自己核验过。
     */
    private static List<String> podiumConflicts(World world, EndPodiumTemplate.Plan oldPlan,
                                                EndPodiumTemplate.Plan newPlan, Gate targetGate,
                                                List<String> evidence, List<String> blockers) {
        List<String> conflicts = new ArrayList<>();
        if (oldPlan == null) {
            return conflicts;
        }
        int unknown = 0;
        int natural = 0;
        int protectedCell = 0;
        int unownedChunks = 0;
        for (EndPodiumTemplate.Cell cell : newPlan.writes()) {
            int chunkX = cell.x() >> 4;
            int chunkZ = cell.z() >> 4;
            if (!world.owns(chunkX, chunkZ)) {
                unownedChunks++;
                continue;
            }
            String current = world.state(cell.x(), cell.y(), cell.z());
            String id = EndPodiumTemplate.blockId(current);
            if (EndPodiumTemplate.isNaturalTerrain(id)) {
                natural++;
                continue;
            }
            // writes() 已经排除原版家具；这里再挡一次，是为了「实现变了也不误报」。
            if (EndPodiumTemplate.isFunctionProtected(id)) {
                protectedCell++;
                continue;
            }
            unknown++;
            if (conflicts.size() < MAX_CONFLICT_SAMPLES) {
                boolean tile = world.tileState(cell.x(), cell.y(), cell.z());
                conflicts.add("中心楼格 " + where(cell.x(), cell.y(), cell.z()) + " 现在是 " + current
                        + (tile ? "，而且是方块实体/容器" : "")
                        + "：不是天然地形 → 本服务不认领这一格（旧楼本体也会出现在这里），需人工确认");
            }
        }
        if (unownedChunks > 0) {
            blockers.add("中心楼有 " + unownedChunks + " 格落在未就绪/不归当前区域线程的区块里：未知块不动，"
                    + "无法完成逐格预检");
        }
        if (unknown >= MAX_CONFLICT_SAMPLES) {
            conflicts.add("中心楼还有更多冲突格未列出（共 " + unknown + " 格）");
        }
        evidence.add("中心楼逐格核对：新计划 " + newPlan.writes().size() + " 格；天然地形 " + natural
                + " 格、功能方块（跳过）" + protectedCell + " 格、需人工确认 " + unknown + " 格。"
                + "旧计划（" + oldPlan.cells().size() + " 格，门 " + format(new Gate(oldPlan.gateX(),
                oldPlan.gateY(), oldPlan.gateZ())) + "）只用来报旧楼范围与旧/新高差，不用来认领方块。");
        if (newPlan.gateY() != targetGate.y()) {
            blockers.add("新计划的门平面 " + newPlan.gateY() + " 与目标门平面 " + targetGate.y() + " 不一致："
                    + "布局与锚点不同源，拒绝");
        }
        return conflicts;
    }

    private static Range rangeOf(String name, EndPodiumTemplate.Plan plan) {
        if (plan == null) {
            return new Range(name, 0, 0, 0, -1, -1, -1);
        }
        return new Range(name, plan.minX(), plan.minY(), plan.minZ(), plan.maxX(), plan.maxY(), plan.maxZ());
    }

    /**
     * 一根柱的保守包围盒：模板半宽 {@link YungPillars#HALF_WIDTH}，高度取上段模板的
     * {@link YungPillars#TOP_HEIGHT}。<b>这是预览用的保守范围，不是模板的真实非空气内容范围</b>；
     * 逐格内容与最低 Y 由柱侧的足迹查询负责。
     */
    private static Range pillarRange(String name, EndSpikes.Spike spike, int topAnchorY, int bottomAnchorY) {
        int half = YungPillars.HALF_WIDTH;
        int highest = Math.max(topAnchorY + YungPillars.TOP_HEIGHT - 1,
                bottomAnchorY + YungPillars.BOTTOM_HEIGHT - 1);
        int lowest = Math.min(topAnchorY, bottomAnchorY);
        return new Range(name, spike.centerX() - half, lowest, spike.centerZ() - half,
                spike.centerX() + half, highest, spike.centerZ() + half);
    }

    /** 旧标记的 {@code "x,y,z"} 形式；解析不了就是空（不猜）。 */
    static Optional<Gate> parseLegacyGate(String layoutMark) {
        if (layoutMark == null) return Optional.empty();
        String[] parts = layoutMark.trim().split(",", -1);
        if (parts.length != 3) return Optional.empty();
        try {
            return Optional.of(new Gate(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
        } catch (NumberFormatException notLegacy) {
            return Optional.empty();
        }
    }

    /** 每轮一个新 UUID 之外的稳定 job id：同一世界同一目标重复 preview 得到同一个备份名。 */
    static UUID jobIdOf(UUID worldId, long seed, Gate targetGate) {
        String material = worldId + "|" + seed + "|" + targetGate.x() + "," + targetGate.y() + "," + targetGate.z();
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static Gate gateOf(Preview preview) {
        Optional<PodiumDelta> podium = preview.podium();
        return podium.map(PodiumDelta::targetGate).orElse(new Gate(0, 0, 0));
    }

    /**
     * 确认 token：预览里所有<b>会改变决定</b>的内容的 SHA-256 前 16 位十六进制。
     *
     * <p>不把 {@code token} 自己、也不把 {@code allowed} 折进去：前者循环，后者由这些内容唯一决定。
     */
    static String tokenOf(UUID worldId, long seed, int minY, int maxY, Gate targetGate,
                          Optional<Gate> legacyGate, List<PillarDelta> pillars, Parts parts,
                          List<Range> ranges) {
        StringBuilder material = new StringBuilder(512);
        material.append("world=").append(worldId)
                .append(";seed=").append(seed)
                .append(";y=").append(minY).append("..").append(maxY)
                .append(";target=").append(format(targetGate))
                .append(";legacy=").append(legacyGate.map(EndLayoutUpgrade::format).orElse("-"));
        for (PillarDelta pillar : pillars) {
            material.append(";p").append(pillar.index()).append(':').append(pillar.tier())
                    .append('/').append(pillar.oldTopAnchorY()).append('/').append(pillar.newTopAnchorY())
                    .append('/').append(pillar.owned());
        }
        for (Section section : parts.sections()) {
            material.append(";s:").append(section.name()).append('=')
                    .append(section.writes().size()).append('/')
                    .append(section.handoffs().size()).append('/')
                    .append(section.marks().size()).append('/')
                    .append(section.blockers().size());
            for (String mark : section.marks()) {
                material.append(";m:").append(mark);
            }
        }
        for (Range range : ranges) {
            material.append(";r:").append(range.name()).append('=')
                    .append(range.minX()).append(',').append(range.minY()).append(',').append(range.minZ())
                    .append('-').append(range.maxX()).append(',').append(range.maxY()).append(',').append(range.maxZ());
        }
        return digest(material.toString());
    }

    private static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是 JRE 必须提供的算法；真没有就别假装有 token。
            throw new IllegalStateException("JRE 不提供 SHA-256，无法生成确认 token", impossible);
        }
    }

    private static String format(Gate gate) {
        return gate.x() + "," + gate.y() + "," + gate.z();
    }

    private static String where(int x, int y, int z) {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    /**
     * 一份预览的纯文本行（不含 MiniMessage 标签），供命令层逐行输出。
     *
     * <p>第 4 项要求的五项全在：旧新高度、影响范围、来源证据、玩家冲突、备份位置。文案键与配色由命令层
     * 决定，这里不做。
     */
    public static List<String> lines(Preview preview) {
        Objects.requireNonNull(preview, "preview");
        List<String> lines = new ArrayList<>();
        lines.add("世界 " + preview.worldId() + "（种子 " + preview.seed() + "）旧布局预览");
        for (PillarDelta pillar : preview.pillars()) {
            lines.add("柱#" + pillar.index() + " 档位 " + pillar.tier()
                    + "：上段 " + pillar.oldTopAnchorY() + " → " + pillar.newTopAnchorY()
                    + "（" + signed(pillar.riseY()) + "），下段 " + pillar.oldBottomAnchorY()
                    + " → " + pillar.newBottomAnchorY() + "，晶座/水晶 " + pillar.oldSeatY() + "/"
                    + pillar.oldCrystalY() + " → " + pillar.newSeatY() + "/" + pillar.newCrystalY()
                    + "（" + signed(pillar.crystalRiseY()) + "）；" + pillar.evidence());
        }
        preview.podium().ifPresent(podium -> lines.add("中心楼：旧门 " + podium.legacyGate()
                .map(EndLayoutUpgrade::format).orElse("（不可解析）")
                + " 楼板 " + podium.oldFloorY() + " → 新门 " + format(podium.targetGate())
                + " 楼板 " + podium.newFloorY() + "（" + signed(podium.shiftY()) + "）"));
        for (Range range : preview.ranges()) {
            if (range.cells() > 0) {
                lines.add("影响范围 " + range.name() + "：" + range.minX() + ".." + range.maxX() + " / "
                        + range.minY() + ".." + range.maxY() + " / " + range.minZ() + ".." + range.maxZ()
                        + "，共 " + range.cells() + " 格");
            }
        }
        lines.add("本次将写 " + preview.totalWrites() + " 格");
        for (String evidence : preview.evidence()) {
            lines.add("证据：" + evidence);
        }
        for (String conflict : preview.conflicts()) {
            lines.add("冲突：" + conflict);
        }
        for (String blocker : preview.blockers()) {
            lines.add("拒绝理由：" + blocker);
        }
        lines.add(preview.backupPath().map(path -> "备份位置：" + path)
                .orElse("备份位置：尚未确定（apply 时由备份端口给出，固定目录 podium-backups/<世界UUID>/）"));
        lines.add(preview.allowed()
                ? "可以执行。确认 token：" + preview.token() + "（执行前请再对比一次上面五行，token 与本次预览绑定）"
                : "现在不能执行：先按上面的拒绝理由与冲突处理，再重新预览。确认 token：" + preview.token());
        return lines;
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }
}
