package dev.mumu.betterend.vanilla;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 中心祭坛的运行时状态机：**预检 → 快照 → 分桶落笔 → 完成标记**。
 *
 * <h2>为什么单独一层</h2>
 * 服务端对象（{@code World}、{@code RegionScheduler}、文件路径）全部留在 {@link EndPodiumShell} 里；
 * 这一层只认四个小接口（{@link Area}、{@link Dispatch}、{@link Marker}、{@link Saver}），于是
 * 「每批最多 1024 格」「备份没成功一格都不写」「施工期间被改动就中止」「只有全部批次成功才落完成标记」
 * 这几条规则能在没有服务端的 JUnit 里逐条钉住（{@code EndPodiumBuildTest}）。
 *
 * <h2>四个阶段的边界</h2>
 * <ol>
 *   <li><b>预检</b>：逐格读世界。**只放行天然末地地形（{@code end_stone} 与三种空气）与原版门家具**
 *       （基岩/返回门/龙蛋/火把——后者跳过且绝不覆盖）；其余一律**中止整个世界**——包括「恰好和目标
 *       方块同 id、甚至状态串逐字符相同」的玩家方块，因为同 id 不构成所有权证据（玩家也能放末地石砖
 *       和楼梯），首次施工不做这种推断。中止而不是跳过，是为了不拼出一座参着玩家方块的残塔还标完成。
 *       同一遍读到的状态就是快照，所以「预检看到的东西」与「备份里记的东西」按构造是同一份。</li>
 *   <li><b>快照</b>：全部预检通过后才把快照异步落盘（{@link Saver}）；落盘没成功，一格都不写。</li>
 *   <li><b>净空</b>：按区块分桶、每批最多 {@link #BATCH_CELLS} 格，先把门坑、7×7 井口与四晶座头部
 *       清成 AIR。每格写之前与快照重核，发现被改动立刻中止且**不覆盖**。</li>
 *   <li><b>原生核心 → 装饰</b>：净空写完后调用一次 {@link Core}（把门环钉到 {@code gateY} 并让原生
 *       写基岩柱/基岩圈/返回门/火把），再落装饰。顺序反了就会让「谁写的」说不清，也会让基岩柱压掉装饰。</li>
 * </ol>
 * 只有全部批次都成功，才在中心区块写 {@code placed} 标记与**完整布局串**（版本 + 世界种子 + 门坐标）；
 * 失败不标成功，已落盘的快照留在原处供手工恢复。**不做自动续建，也不做静默迁移**：中途中止/重启后的
 * 半成品在下一次尝试里会因为自己的方块不是天然地形而被拦下；旧世界存在其它坐标的返回门时由
 * {@link Core} 明确拒绝，只能走显式升级重定位。
 *
 * <h2>线程</h2>
 * 所有世界读写都通过 {@link Dispatch} 落到「拥有该区块的区域线程」或「该区块就绪后的区域线程」上，
 * 磁盘只走 {@link Dispatch#async}。本类自己不建线程、不 join、不假设 Folia 的区域大小——
 * 区块归属由 {@link Dispatch} 的实现（生产环境是 {@code RegionScheduler}）决定。
 */
final class EndPodiumBuild {

    /** 一个区域任务最多写多少格；剩下的排到下一 tick，避免长时间占住区域线程。 */
    static final int BATCH_CELLS = 1024;
    /** 批与批之间的延迟（tick）。 */
    static final long BATCH_DELAY_TICKS = 1L;
    /** 返回门坐标读不到时的重试间隔（tick）。 */
    static final long RETRY_TICKS = 20L;
    /** 返回门坐标的尝试次数上限（含第一次）。 */
    static final int MAX_ATTEMPTS = 5;

    /** 完成标记的键：{@code state} 是 {@link #PLACED}，{@code layout} 是完成时的返回门坐标。 */
    static final String MARK_STATE = "state";
    static final String MARK_LAYOUT = "layout";
    static final String PLACED = "placed";

    private static final String AIR = "minecraft:air";
    private static final String BEDROCK = "minecraft:bedrock";
    /** 原版祭坛中心那根基岩柱的高度（{@code EndPodiumFeature.PODIUM_PILLAR_HEIGHT}）。 */
    static final int PILLAR_HEIGHT = 4;

    /** 一个区块内的方块读写面；**只在拥有该区块的区域线程上调用**。 */
    interface Area {
        /** 当前方块状态串（{@code BlockData#getAsString()}）。 */
        String state(int x, int y, int z);

        /** 直接写方块状态，不做物理/邻居更新。 */
        void set(int x, int y, int z, String state);

        /** 这一格是不是容器/方块实体——只用来把中止原因讲清楚。 */
        boolean tileState(int x, int y, int z);
    }

    /**
     * 原生功能核心（MU 的 {@code EnderDragonFight#spawnExitPortal}，经 {@code MuDragonFightBridge}）。
     *
     * <p>净空写完、装饰写之前调用一次：门环平面由 {@code gateY} 钉死，核心自己写基岩柱、基岩圈、
     * 返回门与火把。<b>本服务不写这些格</b>（{@link EndPodiumTemplate#isFurniture}），顺序反过来就会
     * 让「谁写的」说不清。
     */
    interface Core {
        /**
         * 尝试生成/定位原生核心。
         *
         * @return 结果；{@link Result#retryable()} 为真时由本类在 {@link #RETRY_TICKS} 后用有限次重试
         */
        Result place();

        /**
         * 落 placed 之前的最后一次核对：核心的**运行时状态**（复活阶段、击杀记录、龙 UUID）有没有在
         * 施工期间被改过。逐格比对只能兜住方块，兜不住这些字段；不一致就中止，不标完成。
         */
        default Result verify() {
            return Result.done();
        }

        /** 一次尝试的结果。{@code retryable} 只覆盖「区块还没就绪/暂不归属本区域」这类可等的状态。 */
        record Result(boolean ok, boolean retryable, String reason) {

            public static Result done() {
                return new Result(true, false, "");
            }

            public static Result retry(String reason) {
                return new Result(false, true, reason);
            }

            public static Result fail(String reason) {
                return new Result(false, false, reason);
            }
        }
    }

    /** 调度面：区域线程上的延迟任务（区块先就绪再由实现负责）、异步磁盘任务。 */
    interface Dispatch {
        /**
         * 在拥有该区块的区域线程上延迟执行；实现必须**先让目标区块就绪**（生产环境用
         * {@code World#getChunkAtAsync}，它只在加载期间持有临时 ticket，读完就释放，所以不会像
         * {@code addPluginChunkTicket} 那样泄漏），再排任务。所有区域动作都走这一个入口，
         * 包括慢 IO 回来之后的续跑与完成标记。
         */
        void region(int chunkX, int chunkZ, long delayTicks, Runnable task);

        /** 在异步线程上执行（磁盘 IO，绝不能在世界线程做）。 */
        void async(Runnable task);
    }

    /** 世界 PDC 上的两个字符串；**只在区域线程上读写**。 */
    interface Marker {
        String read(String key);

        void write(String key, String value);
    }

    /** 快照落盘；异步线程上调用，只能碰磁盘。 */
    interface Saver {
        Path save(List<EndPodiumBackup.Cell> cells) throws Exception;
    }

    /** 日志出口（生产环境是插件日志，不是玩家语言配置）。 */
    interface Report {
        void info(String message);

        void warning(String message);
    }

    /** 返回门坐标（{@code DragonBattle#getEndPortalLocation()} 的方块坐标）。 */
    record Portal(int x, int y, int z) {
    }

    /** 一批要处理的格子：属于同一个区块的连续下标区间。 */
    private record Batch(int chunkX, int chunkZ, int from, int to) {
    }

    /** 预检结论：这一格预检时是什么、要不要落笔。 */
    private record Planned(String before, boolean write) {
    }

    private final EndPodiumTemplate.Plan plan;
    private final Area area;
    private final Dispatch dispatch;
    private final Marker marker;
    private final Saver saver;
    private final Core core;
    private final Report report;
    private final Runnable onPlaced;
    private final Runnable onFailed;
    private final int centerChunkX;
    private final int centerChunkZ;
    /** 完成时要写进世界 PDC 的布局串（版本 + 种子 + 门坐标，见 {@link EndPodiumTemplate#encode}）。 */
    private final String layout;

    /** 施工顺序（净空批次在前、装饰批次在后），{@code planned} 与它一一对应。 */
    private final List<EndPodiumTemplate.Cell> order;
    private final List<Batch> batches;
    private final Planned[] planned;
    /** {@link #batches} 里前多少批属于「净空」阶段；之后的属于「装饰」阶段。 */
    private final int clearanceBatches;
    /** 本模板会写的方块 id 集合；只用于把中止原因讲清楚（疑似本插件残留）。 */
    private final Set<String> templateIds;

    /** 外部终止（世界卸载/插件关闭）或本流程已到终态：两者都让后续回调原地返回。 */
    private volatile boolean finished;
    private int skipped;
    private int written;
    private int coreAttempts;
    private boolean corePlaced;
    private Path backupFile;

    /**
     * @param plan        布局（门环坐标已确定）
     * @param centerChunkX 中心所在区块（完成标记与 PDC 都落在它的区域线程上）
     * @param core        原生功能核心；净空写完、装饰写之前调用一次
     */
    EndPodiumBuild(EndPodiumTemplate.Plan plan, Area area, Dispatch dispatch, Marker marker,
                   Saver saver, Core core, Report report, Runnable onPlaced, Runnable onFailed,
                   int centerChunkX, int centerChunkZ, String layout) {
        this.plan = plan;
        this.area = area;
        this.dispatch = dispatch;
        this.marker = marker;
        this.saver = saver;
        this.core = core;
        this.report = report;
        this.onPlaced = onPlaced;
        this.onFailed = onFailed;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.layout = layout;

        List<EndPodiumTemplate.Cell> flattened = new ArrayList<>(plan.writes().size());
        List<Batch> built = new ArrayList<>();
        appendBatches(plan.clearanceByChunk(), flattened, built);
        this.clearanceBatches = built.size();
        appendBatches(plan.byChunk(), flattened, built);
        this.order = List.copyOf(flattened);
        this.batches = List.copyOf(built);
        this.planned = new Planned[flattened.size()];
        Set<String> ids = new HashSet<>();
        for (EndPodiumTemplate.Cell cell : flattened) {
            ids.add(EndPodiumTemplate.blockId(cell.state()));
        }
        this.templateIds = Set.copyOf(ids);
    }

    /** 把一个分桶表按 {@link #BATCH_CELLS} 切批，并追加到 {@code flattened}。 */
    private static void appendBatches(Map<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> buckets,
                                      List<EndPodiumTemplate.Cell> flattened, List<Batch> built) {
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> bucket
                : buckets.entrySet()) {
            List<EndPodiumTemplate.Cell> cells = bucket.getValue();
            int chunkX = bucket.getKey().x();
            int chunkZ = bucket.getKey().z();
            for (int from = 0; from < cells.size(); from += BATCH_CELLS) {
                int to = Math.min(from + BATCH_CELLS, cells.size());
                built.add(new Batch(chunkX, chunkZ, flattened.size(), flattened.size() + to - from));
                flattened.addAll(cells.subList(from, to));
            }
        }
    }

    /** 世界卸载或插件关闭：后续回调一律原地返回，不再排新任务。 */
    void stop() {
        finished = true;
    }

    /** 开始干活。幂等：世界里已经有完成标记就什么都不写。 */
    void start() {
        String state = marker.read(MARK_STATE);
        if (PLACED.equals(state)) {
            report.info("中心祭坛已在 " + marker.read(MARK_LAYOUT) + " 完成过，本次不重建。");
            complete();
            return;
        }
        // 布局是算出来的（地形 → 建筑 → 功能槽），门环 y 由 Core 钉死，所以这里不再「先确认门在不在」：
        // 新世界本来就没有门，旧世界的门由 Core 判定为「需要显式升级」并拒绝，绝不用旧门倒推建筑。
        dispatch.region(centerChunkX, centerChunkZ, 0L, () -> {
            if (finished) {
                return;
            }
            guard(() -> preflight(0));
        });
    }

    /**
     * 原生核心在场证明：门环坐标上那根 4 格基岩柱（{@code EndPodiumFeature#place} 的最后一段：
     * {@code origin.above(0..3)} 全写 {@code bedrock}）。基岩在生存里拆不掉，所以「它在」就说明这个
     * 坐标真的是原生核心，而不是某个被读错的、空的或别人搭的位置。
     *
     * <p>由 {@link Core} 的实现在 {@code anchor} 成功之后调用（新世界则由它自己刚生成的核心保证），
     * 只读这 4 格基岩，绝不读返回门方块本身（{@code minecraft:end_portal}），也一个都不写。
     *
     * @return 非 null 表示原生核心不在预期位置，必须中止
     */
    static String podiumBodyProblem(EndPodiumTemplate.Plan plan, Area area) {
        for (int dy = 0; dy < PILLAR_HEIGHT; dy++) {
            int y = plan.gateY() + dy;
            String state = area.state(plan.gateX(), y, plan.gateZ());
            if (!BEDROCK.equals(EndPodiumTemplate.blockId(state))) {
                return "门环坐标 (" + plan.gateX() + ", " + plan.gateY() + ", " + plan.gateZ()
                        + ") 的基岩柱第 " + (dy + 1) + " 格是 " + state + "，不是原生核心的 bedrock；"
                        + "按「核心不在预期位置」处理，拒绝动工（一格未写）";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 预检

    private void preflight(int index) {
        if (finished) {
            return;
        }
        if (index >= batches.size()) {
            backup();
            return;
        }
        Batch batch = batches.get(index);
        long delay = index == 0 ? 0L : BATCH_DELAY_TICKS;
        schedule(batch, delay, () -> {
            String problem = preflightBatch(batch);
            if (problem != null) {
                abort(problem);
                return;
            }
            preflight(index + 1);
        });
    }

    /** 读一批并判定；返回非 null 表示必须中止整个世界改建。 */
    private String preflightBatch(Batch batch) {
        for (int i = batch.from(); i < batch.to(); i++) {
            EndPodiumTemplate.Cell cell = order.get(i);
            String current = area.state(cell.x(), cell.y(), cell.z());
            // 只按「这一格到底是什么」判定，**不按「它像不像目标方块」推断所有权**：玩家完全可能在模板
            // 位置上放一块末地石砖、放一段同 ID 但朝向不同的楼梯、甚至逐字符相同的状态串。那种格与
            // 目标再像也不属于本服务，必须拒绝整个世界，而不是当成「上次已经放好了」直接跳过——跳过
            // 就会拼出一座带着玩家方块的塔，还会把它标成完成。这里用两个语义明确的谓词（等价于
            // EndPodiumTemplate.classify(current) 的放行面）：天然末地地形，或门的功能方块。
            String id = EndPodiumTemplate.blockId(current);
            if (EndPodiumTemplate.isNaturalTerrain(id)) {
                planned[i] = new Planned(current, true);
            } else if (EndPodiumTemplate.isFunctionProtected(id)) {
                // 保护区外的基岩/门/蛋：绝不覆盖，只记一笔（本锚点下不会出现，见 EndPodiumTemplate）。
                planned[i] = new Planned(current, false);
                skipped++;
            } else {
                return notOurs(i, cell, current);
            }
        }
        return null;
    }

    /**
     * 中止原因：把「容器/方块实体」「玩家方块」「疑似本插件残留」分开说，管理员才知道该动谁。
     *
     * <p>半成品世界会一直停在这里（不自动续建、不自动恢复）：这是刻意的，因为「把玩家放的末地石砖
     * 当成自己上次放的」比停下来糟糕得多。要恢复就用手工路径——快照在
     * {@code plugins/BetterEnd/podium-backups/<worldUUID>/*.snapshot.gz}。
     */
    private String notOurs(int index, EndPodiumTemplate.Cell cell, String current) {
        String where = "(" + cell.x() + ", " + cell.y() + ", " + cell.z() + ")";
        String id = EndPodiumTemplate.blockId(current);
        StringBuilder message = new StringBuilder("第 ").append(index + 1).append(" 格 ").append(where)
                .append(" 是 ");
        if (area.tileState(cell.x(), cell.y(), cell.z())) {
            message.append("容器/方块实体 ").append(current)
                    .append("，不是天然末地地形；为避免破坏玩家财产，整个世界停止改建");
        } else {
            message.append(current);
            if (id.equals(EndPodiumTemplate.blockId(cell.state()))) {
                message.append("（与目标方块同 ID，但同 ID 不构成所有权证据：玩家也能放它，"
                        + "所以首次施工不把它当成自己上次放的）");
            }
            message.append("，既不是天然末地地形，也不是门的功能方块；按玩家建筑处理，整个世界停止改建");
        }
        if (templateIds.contains(id)) {
            message.append("。这个 id 正是本模板会写的方块之一，也可能是本插件上次没来得及标 placed 的"
                    + "成品/半成品——请先核对；本插件不自动续建，也不会替你删掉这些方块");
        }
        message.append("。");
        if (backupFile == null) {
            message.append("（本次一格未写）");
        } else {
            message.append("（本次已写下的格与原方块快照 ").append(backupFile).append(" 保留，可手工恢复）");
        }
        return message.toString();
    }

    // ------------------------------------------------------------------ 快照

    private void backup() {
        if (finished) {
            return;
        }
        List<EndPodiumBackup.Cell> cells = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            Planned plannedCell = planned[i];
            if (plannedCell == null || !plannedCell.write()) {
                continue; // 保护区外仍不许覆盖的功能方块不写，也就不必进快照
            }
            EndPodiumTemplate.Cell cell = order.get(i);
            cells.add(new EndPodiumBackup.Cell(cell.x(), cell.y(), cell.z(), plannedCell.before()));
        }
        dispatch.async(() -> guard(() -> {
            Path file;
            try {
                file = saver.save(cells);
            } catch (Exception error) {
                dispatch.region(centerChunkX, centerChunkZ, 0L,
                        () -> abort("原方块快照落盘失败，一格未写：" + reason(error)));
                return;
            }
            backupFile = file;
            // 慢 IO 期间原点区块可能已经卸载，所以续跑也走 Dispatch.region（它自己先让区块就绪），
            // 不假定预检时那张临时 ticket 还在。
            dispatch.region(centerChunkX, centerChunkZ, 0L, () -> guard(() -> write(0)));
        }));
    }

    // ------------------------------------------------------------------ 落笔

    private void write(int index) {
        if (finished) {
            return;
        }
        if (index >= batches.size()) {
            markPlaced();
            return;
        }
        if (index == clearanceBatches && !corePlaced) {
            // 净空写完、装饰写之前：先把原生功能核心钉到 gateY 上（只做一次）。
            placeCore(index);
            return;
        }
        Batch batch = batches.get(index);
        long delay = index == 0 ? 0L : BATCH_DELAY_TICKS;
        schedule(batch, delay, () -> {
            String problem = writeBatch(batch);
            if (problem != null) {
                abort(problem);
                return;
            }
            write(index + 1);
        });
    }

    /**
     * 生成/定位原生功能核心（基岩柱、基岩圈、返回门、火把）。**必须在装饰之前**：这些是
     * {@link EndPodiumTemplate#isFurniture} 的格，本服务一格都不写，装饰写得比它们晚才不会互相覆盖。
     *
     * <p>可等状态（区块未就绪、暂不归属本区域——原生写区横跨四个区块）走有限重试；其余状态
     * （版本不符、链接失败、期望坐标不符、写入后校验失败）立刻中止：不留 placed、不退回旧门高度。
     */
    private void placeCore(int index) {
        long delay = coreAttempts == 0 ? 0L : RETRY_TICKS;
        dispatch.region(centerChunkX, centerChunkZ, delay, () -> {
            if (finished) {
                return;
            }
            guard(() -> {
                Core.Result result = core.place();
                if (result.ok()) {
                    coreAttempts = 0;
                    corePlaced = true;
                    write(index);
                    return;
                }
                coreAttempts++;
                if (result.retryable() && coreAttempts < MAX_ATTEMPTS) {
                    report.warning("中心祭坛：原生核心还没就绪（第 " + coreAttempts + " 次）——"
                            + result.reason() + "；重新准备原生写区的全部区块后再试。");
                    // 关键：原生写区是 origin±4 的 7×7×7，会落到 2×2 个区块上。只重排中心区块等于把
                    // 「旁边那块还没加载」错当成「以后也不会就绪」，所以重试前把涉及的全部区块都准备一遍。
                    warmChunksThenRetry(index);
                    return;
                }
                abort(clearanceBatches + " 批净空已经写下，但原生功能核心无法生成：" + result.reason());
            });
        });
    }

    /** 把这次原生调用可能碰到的所有区块都异步准备一次，成功后再重试核心。 */
    private void warmChunksThenRetry(int index) {
        List<EndPodiumTemplate.ChunkKey> chunks = new ArrayList<>(plan.coreChunks());
        if (chunks.isEmpty()) {
            placeCore(index);
            return;
        }
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(chunks.size());
        for (EndPodiumTemplate.ChunkKey chunk : chunks) {
            dispatch.region(chunk.x(), chunk.z(), 0L, () -> {
                if (remaining.decrementAndGet() == 0 && !finished) {
                    placeCore(index);
                }
            });
        }
    }

    /** 写一批；每格落笔前与快照重核。返回非 null 表示必须中止。 */
    private String writeBatch(Batch batch) {
        for (int i = batch.from(); i < batch.to(); i++) {
            Planned plannedCell = planned[i];
            if (plannedCell == null || !plannedCell.write()) {
                continue;
            }
            EndPodiumTemplate.Cell cell = order.get(i);
            String now = area.state(cell.x(), cell.y(), cell.z());
            if (cell.state().equals(now)) {
                continue; // 已经是目标方块：这一格没有要落的笔
            }
            if (!now.equals(plannedCell.before())) {
                return "施工期间 ( " + cell.x() + ", " + cell.y() + ", " + cell.z() + " ) 从 "
                        + plannedCell.before() + " 变成 " + now + "；立即中止，不覆盖这次改动";
            }
            area.set(cell.x(), cell.y(), cell.z(), cell.state());
            written++;
        }
        return null;
    }

    private void markPlaced() {
        dispatch.region(centerChunkX, centerChunkZ, 0L, () -> {
            if (finished) {
                return;
            }
            guard(() -> {
                Core.Result checked = core.verify();
                if (!checked.ok()) {
                    abort("落完成标记前的核对失败：" + checked.reason());
                    return;
                }
                marker.write(MARK_LAYOUT, layout);
                marker.write(MARK_STATE, PLACED);
                report.info("中心祭坛施工完成：写 " + written + " 格，待写 " + order.size() + " 格，"
                        + "净空 " + plan.clearance().size() + " 格、装饰 " + plan.decorations().size() + " 格，"
                        + "原版家具跳过 " + plan.furniture().size() + " 格，"
                        + "原方块快照 " + backupFilePath());
                if (skipped > 0) {
                    // 跳过一格就是留一个洞，必须可查：这通常是足迹里出现了外来的基岩/门/蛋方块。
                    report.warning("中心祭坛有 " + skipped + " 格因是门的功能方块（基岩/返回门/龙蛋）而"
                            + "没有落笔，外观上会缺这几格；需要的话请人工核对后处理。");
                }
                complete();
            });
        });
    }

    // ------------------------------------------------------------------ 终态

    private void abort(String reason) {
        if (finished) {
            return;
        }
        finished = true;
        String tail = backupFile == null
                ? "；这次一格都没写（快照也还没落盘）"
                : "；已写下的格与原方块快照 " + backupFile + " 保留，可据此手工恢复；本插件不自动续建、不自动恢复";
        report.warning("中心祭坛停止改建：" + reason + tail);
        onFailed.run();
    }

    private void complete() {
        if (finished) {
            return;
        }
        finished = true;
        onPlaced.run();
    }

    private String backupFilePath() {
        return backupFile == null ? "(未落盘)" : backupFile.toString();
    }

    /** 统一的「在拥有这一批目标区块的区域线程上跑」；已终止就不再排新任务。 */
    private void schedule(Batch batch, long delayTicks, Runnable task) {
        dispatch.region(batch.chunkX(), batch.chunkZ(), delayTicks, () -> {
            if (finished) {
                return;
            }
            guard(task);
        });
    }

    /**
     * 批任务体里冒出来的 {@link RuntimeException}（写方块失败、PDC 写不进去、某个插件在
     * {@code setBlockData} 上抛异常……）必须统一收口：记下来、中止、把快照路径带出去，
     * 绝不让异常把状态留在 BUILDING 里假装还在施工，也绝不标成功。
     */
    private void guard(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException error) {
            abort("施工过程中出错：" + reason(error));
        }
    }

    // ------------------------------------------------------------------ 有限重试与局部修复

    /**
     * 返回门坐标的有限重试：读不到就隔 {@link #RETRY_TICKS} tick 再来，最多 {@link #MAX_ATTEMPTS} 次；
     * 用完调用 {@code gaveUp}，由调用方等后续区块/龙事件——这里不做常驻扫描，也不无限重试。
     *
     * <p>{@code read} 每次都在区域线程上调用（第一次由调用方保证，后续由 {@code later} 保证：它负责把
     * 传进来的任务在 {@link #RETRY_TICKS} 后重新排到区域线程上）。所有分支都是同步的：测试注入一个
     * 立即执行的 {@code later} 就能数清次数。
     */
    static void awaitPortal(Supplier<Portal> read, Consumer<Runnable> later, Consumer<Portal> found,
                            Consumer<Integer> gaveUp) {
        awaitPortal(read, later, found, gaveUp, 1);
    }

    private static void awaitPortal(Supplier<Portal> read, Consumer<Runnable> later,
                                    Consumer<Portal> found, Consumer<Integer> gaveUp, int attempt) {
        Portal portal = read.get();
        if (portal != null) {
            found.accept(portal);
            return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            gaveUp.accept(attempt);
            return;
        }
        later.accept(() -> awaitPortal(read, later, found, gaveUp, attempt + 1));
    }

    /**
     * 龙死亡/重生后的一次有界修复：只把 {@code repairs} 里**当前是空气**的模板外壳填回去。
     *
     * <p>{@code repairs} 由 {@link EndPodiumTemplate} 用真服务端的写入范围固化，已经扣掉保护区、
     * 龙蛋、返回门与水晶净空；本方法再按「当前必须是空气」过滤一次，所以玩家在别处主动拆掉的方块
     * 不会被恢复，也不会重建整栋建筑。只跑一次，不做重试。
     *
     * @return 实际填回的格数
     */
    static int repair(List<EndPodiumTemplate.Cell> repairs, Area area) {
        int filled = 0;
        for (EndPodiumTemplate.Cell cell : repairs) {
            if (cell.air()) {
                continue; // 模板本来就是空气：没什么可填
            }
            String now = area.state(cell.x(), cell.y(), cell.z());
            if (!AIR.equals(EndPodiumTemplate.blockId(now))) {
                continue; // 只填被清空的格子，不动还有方块的位置
            }
            area.set(cell.x(), cell.y(), cell.z(), cell.state());
            filled++;
        }
        return filled;
    }

    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }
}
