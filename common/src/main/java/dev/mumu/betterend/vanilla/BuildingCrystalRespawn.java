package dev.mumu.betterend.vanilla;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 建筑四晶座接入原版复活状态机的手册：**玩家放下的四颗真水晶 → 原版 {@code initiateRespawn}**。
 *
 * <h2>为什么不是「沿半径 3 的祭坛路径」</h2>
 * 原版 {@code EnderDragonFight#tryRespawn()} 只认返回门环半径 3 位置上那四颗水晶，而 YUNG 中央建筑的四座
 * 晶座在半径 {@link EndPodiumTemplate#SEAT_RADIUS}（8）。本类不改动原版那条路径（它继续兼容、继续
 * 自己触发），只是在**四座都放好**时把四个实体原样交给 MU 26.3 的
 * {@code DragonBattle#initiateRespawn(Collection)}，由原版负责 beam、阶段推进、毁晶中止和最终消耗。
 *
 * <h2>本类只做判定与转交，不搬运、不替代</h2>
 * <ul>
 *   <li>不移动、不克隆、不删除任何实体：判定通过后把**同样的四个实体**传下去。</li>
 *   <li>不消耗第五颗水晶、不改玩家背包、不动原版 {@code respawnCrystals} 之外的任何状态。</li>
 *   <li>失败（含 {@code initiateRespawn} 返回 {@code false} 或抛异常）时**四颗水晶全部原样保留**，
 *       只在日志里说明原因；本类从不「先吞掉再看」。</li>
 *   <li>不取消 {@code EntityPlaceEvent}，也不取消原版的放置逻辑。</li>
 * </ul>
 *
 * <h2>事件驱动，不常驻扫描</h2>
 * {@code EntityPlaceEvent} 触发时实体**还没有加入世界**（{@code EndCrystalItem#useOn} 先发事件再
 * {@code addFreshEntity}），所以事件里什么都不查；只在拥有中心区块的区域线程上排**一次**检查
 * （{@link #FIRST_DELAY_TICKS} tick 之后）。之后没有轮询、没有全世界实体扫描，只有一次有界的
 * {@code getNearbyEntities}（建筑 ±12 格）。同一轮检查在跑时，后续放置事件一律忽略。
 *
 * <h2>线程</h2>
 * 四座（±8）与原生核心覆盖区（门环 ±4）横跨相邻区块，全部读写都必须在**同时拥有它们的那个区域线程**
 * 上完成，所以每一轮都先做一次**只读坐标的归属核验**（{@link #ownership}：{@code isOwnedByCurrentRegion}
 * 不带任何世界读取），核验不过就有限重试（{@link #MAX_ATTEMPTS} 次 × {@link #RETRY_TICKS} tick），
 * 仍然不过就**拒绝启动并保留水晶**——本类绝不跨区同步读写，也不拿实体 owner 线程代替区域线程。
 */
public final class BuildingCrystalRespawn implements Listener {

    /** 建筑晶座数量：YUNG 中央建筑是**四座**半径 8 的晶座，不是八颗水晶。 */
    static final int SLOT_COUNT = 4;

    /** 探针：建筑附近的有界查询半径（格）。四座在 ±8，再加 4 格余量以便发现「附近多放了一颗」。 */
    static final int SEARCH_RADIUS_XZ = 12;
    static final int SEARCH_UP = 8;
    static final int SEARCH_DOWN = 4;

    /**
     * 原生核心覆盖区：{@code initiateRespawn} 内部会调原版 {@code EnderDragonFight#findExitPortal()}，
     * 后者按「origin 所在区块 ±8」（17×17 = <b>289 个区块</b>）逐区块 {@code level.getChunk(x, z)} 找
     * 末地传送门方块，然后 {@code spawnExitPortal} 在那里落门。所以只核验四座与门环那 4 个区块是**不够**的：
     * 289 个区块必须都已加载、且都由当前区域线程拥有，否则原版会在我们脚下同步读别的区块。
     *
     * <p>这里的 {@code 8} 不是估算：{@code mucore-server-26.3.local-SNAPSHOT.jar} 里
     * {@code net.minecraft.world.level.dimension.end.EnderDragonFight#findExitPortal} 的字节码是
     * {@code bipush -8 / bipush 8} 两层循环。
     */
    static final int CORE_CHUNK_RADIUS = 8;

    /**
     * MU 26.3 的实测 section 边长（{@code grid-exponent=4}）。
     *
     * <p><b>只用于测试与文档对照</b>：生产路径必须调 {@link MuDragonFightBridge#regionSectionChunkSize(World)}
     * 拿运行期真值（{@code -1} 表示无法确定，按「安全拒绝」处理），任何写死或兜底猜测都会在别的
     * {@code grid-exponent} 部署上算错阶段推进区范围。
     */
    static final int MU_26_3_REGION_SECTION_CHUNKS = 16;

    /**
     * 阶段推进所需的区域跨度：{@code EnderDragonFight#isArenaLoaded()} 调
     * {@code TickThread.isTickThreadFor(level, originChunkX, originChunkZ, 8 + regionSectionChunkSize)}。
     *
     * <p>section 尺寸不是常数：{@code ThreadedRegionizer.regionSectionChunkSize = 1 << config.gridExponent}
     * （{@code TickRegions.java}），MU 自带 bench 用 {@code grid-exponent=4} ⇒ 16 ⇒ 真实半径 24。
     * 写死 8 会让核验只覆盖 ±16，于是 {@code initiateRespawn} 返回 true、本类打印「已交给原版」，而原版
     * {@code isArenaLoaded()} 恒为 false：阶段连 START 都不推进，玩家看到「水晶在、龙不出」。
     */
    static int arenaChunkRadius(int sectionChunks) {
        return CORE_CHUNK_RADIUS + sectionChunks;
    }

    /** {@code (2r+1)²}：只用于日志里的数量说明。 */
    static int chunkSquare(int radius) {
        int side = radius * 2 + 1;
        return side * side;
    }

    /**
     * 原版竞技场原点：MU 的 {@code EnderDragonFight.origin} 是固定的 {@code (0, 128, 0)}
     * （{@code DRAGON_SPAWN_Y = 128}，{@code END_PODIUM_LOCATION} 同值），不是建筑的 {@code gateY}。
     *
     * <p>玩家门槛必须用它：原版 {@code updatePlayers()} 只认「离 origin 的**球形**距离 ≤ 192」的活玩家，
     * 用 {@code gateY} 当球心或拿立方体近似都会算错边界（门平面比 128 低几十格）。
     */
    static final int DRAGON_ORIGIN_X = 0;
    static final int DRAGON_ORIGIN_Y = 128;
    static final int DRAGON_ORIGIN_Z = 0;

    /** {@code origin ±192} 格内至少有一个玩家：与 {@code updatePlayers} 的球形半径同值。 */
    static final int PLAYER_RADIUS_BLOCKS = 192;

    /** 精确球距判定（闭区间），与 {@code updatePlayers} 的 {@code distanceToSqr <= 192²} 一致。 */
    static boolean withinSphere(double dx, double dy, double dz, int radiusBlocks) {
        return dx * dx + dy * dy + dz * dz <= (double) radiusBlocks * radiusBlocks;
    }

    /**
     * 阶段推进区的 ticket 只在这两种终态释放：{@code NONE}（没在跑/已中止）与 {@code END}（打完了）。
     *
     * <p>观测窗口到期、观测调度失败、读阶段失败都**不**释放：把票撤掉会让 region 立刻丢掉外围 section，
     * 玩家走开一分钟再回来就永远推进不了。超窗持票直到终态事件或关闭，是本类明确的取舍。
     */
    static boolean releasesTickets(String phase) {
        return "NONE".equals(phase) || "END".equals(phase);
    }

    /**
     * 启动前必须加载并持有的**最小可验证集**。
     *
     * <p>原版 {@code isArenaLoaded()} 判的是「region 是否覆盖 origin ±(8 + sectionSize)」，而 MU 的 region
     * 是按 **section** 建立的：一个 section 里只要有一个 chunk 有 ticket，这个 section 就有 region，
     * 它覆盖的全部 chunk 都归那个 region。所以不需要把 49×49 = 2401 个区块全加载：
     * <ol>
     *   <li>289 个 ±{@link #CORE_CHUNK_RADIUS} 核心区块 —— {@code findExitPortal} 会逐个同步 {@code getChunk}；</li>
     *   <li>外围 12 个 section 各取一个代表区块 —— 覆盖 origin 周围 {@code section} 偏移
     *       {@code (-2..-1, 0..1)}<sup>2</sup> 的 16 个 section 里、289 没覆盖到的那 12 个。</li>
     * </ol>
     * 289 覆盖的 section 会被自动识别（若该 section 内离 origin 最近的 chunk 已在 ±8 内就跳过），
     * 所以 sectionSize 变化时数量会跟着变，而不是写死 12。
     *
     * @return 去重后的 {@code [chunkX, chunkZ]} 列表，顺序稳定
     */
    static List<long[]> requiredChunks(Blueprint blueprint, int sectionChunks) {
        Set<Long> chunks = new LinkedHashSet<>();
        int centerChunkX = blueprint.chunkX();
        int centerChunkZ = blueprint.chunkZ();
        for (int chunkX = centerChunkX - CORE_CHUNK_RADIUS; chunkX <= centerChunkX + CORE_CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = centerChunkZ - CORE_CHUNK_RADIUS; chunkZ <= centerChunkZ + CORE_CHUNK_RADIUS; chunkZ++) {
                chunks.add(chunkKey(chunkX, chunkZ));
            }
        }
        int shift = Integer.numberOfTrailingZeros(sectionChunks);
        if (shift <= 0 || (1 << shift) != sectionChunks) {
            return freeze(chunks); // section 尺寸不是 2 的幂：只交可验证的 289 核心，不放宽
        }
        int originSectionX = centerChunkX >> shift;
        int originSectionZ = centerChunkZ >> shift;
        for (int sectionX = -2; sectionX <= 1; sectionX++) {
            for (int sectionZ = -2; sectionZ <= 1; sectionZ++) {
                int baseChunkX = (originSectionX + sectionX) << shift;
                int baseChunkZ = (originSectionZ + sectionZ) << shift;
                int repX = clamp(centerChunkX, baseChunkX, baseChunkX + sectionChunks - 1);
                int repZ = clamp(centerChunkZ, baseChunkZ, baseChunkZ + sectionChunks - 1);
                if (Math.abs(repX - centerChunkX) <= CORE_CHUNK_RADIUS
                        && Math.abs(repZ - centerChunkZ) <= CORE_CHUNK_RADIUS) {
                    continue; // 这个 section 已被 289 核心覆盖
                }
                chunks.add(chunkKey(repX, repZ));
            }
        }
        return freeze(chunks);
    }

    private static List<long[]> freeze(Set<Long> chunks) {
        List<long[]> frozen = new ArrayList<>(chunks.size());
        for (long key : chunks) {
            frozen.add(new long[]{(int) (key >> 32), (int) key});
        }
        return List.copyOf(frozen);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    /**
     * 起跑前的本地门槛 1：{@code origin ±192} 格内至少有一个玩家。
     *
     * @return {@code null} 表示通过；否则是中文原因
     */
    static String playerGate(Blueprint blueprint, Runtime runtime) {
        return runtime.playerNearby(blueprint, PLAYER_RADIUS_BLOCKS)
                ? null
                : "origin ±" + PLAYER_RADIUS_BLOCKS + " 格内没有玩家：原版在没有玩家的阶段不会推进，"
                + "启动只会把门清掉、水晶交出去然后冻住";
    }

    /**
     * 起跑前的本地门槛 2：要加载/持有的区块**一个都不能少**。
     *
     * <p>部分持有不是「差不多能用」：少一个 section，原版 {@code isArenaLoaded()} 就是 false，阶段冻住；
     * 所以只要 {@code held.size() != required.size()} 就释放已持有部分并保晶拒绝。
     *
     * @return {@code null} 表示通过；否则是中文原因
     */
    static String ticketGate(List<long[]> required, List<long[]> held) {
        if (held.size() == required.size()) {
            return null;
        }
        return "阶段推进区的区块 ticket 只拿到 " + held.size() + "/" + required.size()
                + "（没有全量持有），阶段会在中途冻住";
    }

    /**
     * 交晶前的完整本地门槛：**先玩家、再 ticket、最后才是 {@link #round}（里面会调 {@code start}）**。
     *
     * <p>顺序就是语义：两关任一不过都不会走到原版，所以「hold 失败/没玩家时不调用 start」在这条路径上
     * 是可断言的，而不是靠日志里一句承诺。
     */
    static Round handOff(Blueprint blueprint, Runtime runtime, List<long[]> required,
                         List<long[]> held, Report report) {
        String players = playerGate(blueprint, runtime);
        if (players != null) {
            report.warning("建筑四晶复活不启动：" + players);
            return new Round(false, false, false, players);
        }
        String tickets = ticketGate(required, held);
        if (tickets != null) {
            report.warning("建筑四晶复活不启动：" + tickets);
            return new Round(false, false, false, tickets);
        }
        return round(blueprint, runtime, report);
    }

    /** 启动后的有界观测：每 2 秒读一次阶段，最多 {@link #MAX_OBSERVATIONS} 次，不常驻轮询、不动任何实体。 */
    static final long OBSERVE_TICKS = 40L;
    static final int MAX_OBSERVATIONS = 30;

    /**
     * 阶段连续这么多次没有变化才告警。
     *
     * <p>阈值来自原版时长而不是手感：{@code PREPARING_TO_SUMMON_PILLARS} 100 tick +
     * {@code SUMMONING_PILLARS} 400 tick（10 根 × 40），每 {@link #OBSERVE_TICKS}=40 tick 读一次
     * ⇒ 至少要 13 次才可能跨过整段；取 16 留余量。原来的 4 次（160 tick）会在**正常流程**里 100% 假报。
     */
    static final int STALLED_OBSERVATIONS = 16;

    /** 事件之后等 tick 再检查：实体在这个 tick 才真正进入世界。 */
    static final long FIRST_DELAY_TICKS = 1L;
    /** 归属核验失败后的重试间隔与次数（含第一次）；用完就拒绝，不做常驻扫描。 */
    static final long RETRY_TICKS = 20L;
    static final int MAX_ATTEMPTS = 5;

    // ------------------------------------------------------------------ 注入面

    /** 一个晶体槽位（**世界坐标**）：{@code y} 是晶体头部所在高度（= 支撑基岩上一格）。 */
    public record Slot(int x, int y, int z) {
    }

    /**
     * 已建成建筑的蓝图。**只有中央施工服务在新版完成标记下才会给出**，所以这里的值一定是持久化布局，
     * 不是按当前地形重算出来的。
     */
    public record Blueprint(int centerX, int centerZ, int gateY, int floorY, List<Slot> slots) {
        public Blueprint {
            slots = List.copyOf(slots);
        }

        public int chunkX() {
            return Math.floorDiv(centerX, 16);
        }

        public int chunkZ() {
            return Math.floorDiv(centerZ, 16);
        }
    }

    /** 中央施工服务的查询面：未建成/旧版布局/构建中一律 {@link Optional#empty()}。 */
    public interface Podium {
        Optional<Blueprint> blueprint(World world);
    }

    /**
     * 生产适配：把 {@link EndPodiumShell#placedPlan(World)} 翻译成 {@link Blueprint}。
     *
     * <p>唯一允许的来源就是 {@code placedPlan} —— 它只在世界 PDC 里 {@code state=placed} 且布局版本匹配时
     * 返回；{@code plannedPlan}（按当前地形现算）**不能**用在这里，否则会在没建成的世界里「按计划」启动复活。
     *
     * <p>槽位取模板的 {@link EndPodiumTemplate.Seat#headY()}（{@code supportY + 1}），不用
     * {@code supportY}：那是基岩支撑所在的一格，水晶实体在它上面。
     */
    public static Podium podiumOf(EndPodiumShell shell) {
        return world -> shell.placedPlan(world).map(BuildingCrystalRespawn::blueprintOf);
    }

    /** {@link EndPodiumTemplate.Plan} → {@link Blueprint}；四座顺序与模板一致（+X、−X、+Z、−Z）。 */
    static Blueprint blueprintOf(EndPodiumTemplate.Plan plan) {
        List<Slot> slots = new ArrayList<>(plan.seats().size());
        for (EndPodiumTemplate.Seat seat : plan.seats()) {
            slots.add(new Slot(seat.x(), seat.headY(), seat.z()));
        }
        return new Blueprint(plan.gateX(), plan.gateZ(), plan.gateY(), plan.floorY(), slots);
    }

    /** 水晶实体的只读快照：判定只需要 UUID 与方块坐标，不持有实体引用。 */
    record Crystal(UUID id, int x, int y, int z) {
    }

    /** 龙战的可判定状态；{@code null} 表示这一次读不到（世界正在卸载之类）。 */
    record Fight(boolean previouslyKilled, boolean respawnNotRunning, boolean dragonAbsent) {
    }

    /**
     * 世界读取面：**只在同时拥有四座与核心覆盖区的区域线程上调用**。
     *
     * <p>生产实现见 {@link WorldRuntime}；单测直接给假实现，所以判定规则本身不需要服务端。
     */
    interface Runtime {
        /**
         * 建筑附近（±{@link #SEARCH_RADIUS_XZ} 格）的活水晶，一次有界查询。
         * 已死/已失效的实体由实现过滤掉——「有效」这一条不重复进判定。
         */
        List<Crystal> crystalsNear(Blueprint blueprint);

        /** 这一格是不是空的（空气/洞穴空气/虚空空气）。 */
        boolean air(int x, int y, int z);

        /** 这一格是不是实心方块（晶座支撑）。 */
        boolean solid(int x, int y, int z);

        /** 这一格是否由当前区域线程拥有；只做坐标判定，不读世界。 */
        boolean owned(int x, int y, int z);

        /** 这个区块是否由当前区域线程拥有；同样只做坐标判定（chunk 级重载，不构造 Location）。 */
        boolean ownedChunk(int chunkX, int chunkZ);

        /** 这个区块是否已经加载（原版 {@code findExitPortal} 会同步 {@code getChunk}，未加载就会跨区阻塞）。 */
        boolean loaded(int chunkX, int chunkZ);

        /** 当前复活阶段名；读不到时返回 {@code null}。只用于启动后的有界观测。 */
        String phase();

        /**
         * 一个 region section 覆盖多少区块边。
         *
         * @return 正数 = 可信值（MU 26.3 实测 16）；{@code -1} = 无法确定（桥接不可用/版本不符），
         *         调用方必须安全拒绝，不能退回任何猜测的默认值
         */
        int regionSectionChunks();

        /**
         * 原地持有阶段推进区的区块 ticket。
         *
         * <p><b>必须在 {@link #start} 之前调用</b>：ticket 是让 MU 的 region 覆盖这些 section 的唯一手段，
         * 交给原版之后再补持有，等于先让原版在「arena 未加载」的状态下把门清掉。
         *
         * @param required 要持有的区块（{@link #requiredChunks}），最多 301 个
         * @return 实际持有的 {@code [chunkX, chunkZ]}；已持有与本次新加都要记进去
         */
        List<long[]> holdArena(Blueprint blueprint, List<long[]> required);

        /** 释放 {@link #holdArena} 返回的 ticket；幂等，任何情况下都不动方块与实体。 */
        void releaseArena(List<long[]> held);

        /** {@code origin ±radiusBlocks} 内是否至少有一个玩家（原版无玩家时阶段不推进）。 */
        boolean playerNearby(Blueprint blueprint, int radiusBlocks);

        /** 这个水晶实体是否由当前区域线程拥有（实体归它自己的 owner 线程）。 */
        boolean owned(UUID crystal);

        /** 龙战状态；读不到时返回 {@code null}。 */
        Fight fight();

        /**
         * 调原版 {@code DragonBattle#initiateRespawn(Collection<EnderCrystal>)}，传入**真实**四颗。
         *
         * @return 原版是否真的接管了；{@code false} 时调用方必须把水晶当「原样保留」处理
         */
        boolean start(List<UUID> crystals);
    }

    /** 调度面：在拥有该区块的区域线程上延迟执行（实现负责先让区块就绪）。 */
    interface Dispatch {
        boolean region(World world, int chunkX, int chunkZ, long delayTicks, Runnable task,
                       java.util.function.Consumer<String> onFailure);

        /**
         * 异步把给定区块全部加载好，再回调 {@code ready}。
         *
         * <p>这一步是 {@code findExitPortal} 与 {@code isArenaLoaded} 的前置条件：前者会在区域线程上同步
         * {@code getChunk} 289 个区块，后者要求这些 section 已经有 region。只有「四颗真水晶已经在四座上」
         * 时才会走到这里，而且只加载 {@link #requiredChunks} 这份最小可验证集（289 核心 + 12 个外围代表），
         * 不是 49×49 的整片。
         */
        boolean prepare(World world, List<long[]> chunks, Runnable ready,
                        java.util.function.Consumer<String> onFailure);
    }

    /** 日志出口（管理员诊断，中文，不是玩家语言配置）。 */
    interface Report {
        void info(String message);

        void warning(String message);
    }

    // ------------------------------------------------------------------ 判定结果

    /**
     * 一次判定的结论。
     *
     * @param start     能否启动
     * @param retry     不能启动时，值不值得等一次重试（只有「归属不满足」值得：区域可能刚合并完）
     * @param refusal   不能启动的中文原因
     * @param crystals  能启动时要原样交给原版的四个 UUID（按蓝图槽位顺序）
     */
    record Verdict(boolean start, boolean retry, String refusal, List<UUID> crystals) {
        static Verdict refuse(String why) {
            return new Verdict(false, false, why, List.of());
        }

        static Verdict retry(String why) {
            return new Verdict(false, true, why, List.of());
        }

        static Verdict start(List<UUID> crystals) {
            return new Verdict(true, false, null, List.copyOf(crystals));
        }
    }

    /**
     * 一轮检查的结果（{@link #round} 的返回值）。
     *
     * @param started   是否已经交给原版
     * @param retry     要不要等一次重试（归属不满足：区域可能刚合并/拆分完）
     * @param loadCores 判定已能启动，只是 289 个核心区块还没全部加载：先异步加载再复检
     * @param refusal   不能启动的中文原因
     */
    record Round(boolean started, boolean retry, boolean loadCores, String refusal) {
    }

    // ------------------------------------------------------------------ 状态机

    /** 一个受管世界在本次运行内的状态。 */
    enum State {
        /** 没在检查，也没启动过：下一次放置事件可以抢到。 */
        IDLE,
        /** 正在检查（含有限重试）。 */
        CHECKING,
        /** 已经交给原版：本轮不再启动第二次，除非原版那一轮真的结束了。 */
        STARTED,
        /** 世界大战卸载/插件关闭：后续回调一律原地返回。 */
        STOPPED
    }

    static final class Run {
        final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
        /** 启动后的有界观测状态：上一次读到的阶段名，以及「阶段连续没变」的次数。 */
        final AtomicReference<String> lastPhase = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger stalled = new java.util.concurrent.atomic.AtomicInteger();
        /** 已经告警过卡阶段：同一轮的后续观测不再重复刷同一条。 */
        final java.util.concurrent.atomic.AtomicBoolean stalledReported =
                new java.util.concurrent.atomic.AtomicBoolean();
        /** 这个 Run 属于哪个世界；关闭/卸载时释放 ticket 要用（不在回调里再查一遍世界表）。 */
        volatile World world;
        /** 本轮为阶段推进区持有的区块 ticket（{@code [chunkX, chunkZ]}）。 */
        final AtomicReference<List<long[]>> heldArena = new AtomicReference<>(List.of());

        /**
         * 放置事件能不能抢到这一轮。
         *
         * <p>{@link State#CHECKING} 时拒绝：并发的第二次放置不允许再排一轮。{@link State#STARTED} 时放行，
         * 因为原版可能已经中止（毁晶）或已经打完一轮，玩家有权再放一次；真正的「已经在跑」由
         * {@link #decide} 读 {@code getRespawnPhase()} 判定，不是靠这个内存标志冒充。
         */
        boolean begin() {
            while (true) {
                State now = state.get();
                if (now == State.CHECKING || now == State.STOPPED) {
                    return false;
                }
                if (state.compareAndSet(now, State.CHECKING)) {
                    return true;
                }
            }
        }

        /** 这一轮没启动：退回可再触发的状态（已经 STARTED 的不会被这条回退）。 */
        void idle() {
            state.updateAndGet(now -> now == State.CHECKING ? State.IDLE : now);
        }

        boolean stopped() {
            return state.get() == State.STOPPED;
        }
    }

    // ------------------------------------------------------------------ 生产字段

    private final BooleanSupplier enabled;
    private final Podium podium;
    private final Dispatch dispatch;
    private final Report report;
    /** 生产实例才有：阶段推进区的区块 ticket 必须以本插件名义持有。测试构造里是 {@code null}。 */
    private final Plugin plugin;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    /** 生产构造：世界判定、区域调度、日志全部走插件。 */
    public BuildingCrystalRespawn(Plugin plugin, Podium podium) {
        this(plugin::isEnabled, podium, new WorldDispatch(plugin), new PluginReport(plugin), plugin);
    }

    /** 单测构造：不碰 Bukkit 的调度与日志（因此也不持有任何 ticket）。 */
    BuildingCrystalRespawn(BooleanSupplier enabled, Podium podium, Dispatch dispatch, Report report) {
        this(enabled, podium, dispatch, report, null);
    }

    BuildingCrystalRespawn(BooleanSupplier enabled, Podium podium, Dispatch dispatch, Report report,
                           Plugin plugin) {
        this.enabled = enabled;
        this.podium = podium;
        this.dispatch = dispatch;
        this.report = report;
        this.plugin = plugin;
    }

    /** 关闭：在途回调原地返回。**不**回收任何实体、**不**改龙战状态——水晶是玩家的。 */
    public void close() {
        for (Run run : runs.values()) {
            run.state.set(State.STOPPED);
            releaseArena(run);
        }
        runs.clear();
    }

    // ------------------------------------------------------------------ 事件

    /**
     * 玩家放下水晶。**这里什么都不查**：事件发出时实体还没进世界，{@code getLocation()} 还不可信，
     * 而且这一步跑在放置者所在区域的线程上，不一定是同时拥有四座的那个区域线程。
     *
     * <p>不取消事件（{@code ignoreCancelled = true} 只是让我们不插手被别的插件否掉的放置）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal)) {
            return;
        }
        World world = worldOf(event);
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        trigger(world, "玩家放置水晶");
    }

    /** 世界卸载：在途回调一律作废，避免它们在世界没了之后还去排下一轮。 */
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        Run run = runs.remove(event.getWorld().getUID());
        if (run != null) {
            run.state.set(State.STOPPED);
            releaseArena(run);
        }
    }

    /**
     * 末影龙生成：这是本类能复用到的**唯一**既有终态信号（原版没有可监听的 abort 事件）。
     *
     * <p>观测窗口到期后 ticket 是刻意保持的（见 {@link #observe}），否则玩家走开一分钟再回来，
     * 外围 section 又没了、阶段永远推进不了。真正的释放点就放在这里：龙已经出来 = 这一轮阶段走完了。
     * 只做一次有界复核（读阶段 → {@code NONE}/{@code END} 才释放），不是定时扫描。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        Run run = runs.get(world.getUID());
        if (run == null || run.heldArena.get().isEmpty()) {
            return;
        }
        boolean queued = dispatch.region(world, 0, 0, 0L,
                () -> releaseIfSettled(world, run, "末影龙已生成"),
                problem -> report.warning("建筑四晶复活：龙生成后没能复核阶段（" + problem
                        + "），阶段推进区 ticket 保持持有直到世界卸载或插件关闭"));
        if (!queued) {
            report.warning("建筑四晶复活：龙生成后无法请求中心区块，阶段推进区 ticket 保持持有直到世界卸载或插件关闭");
        }
    }

    /** 终态复核：阶段到 NONE/END 才释放这一轮的 ticket，否则保持并说明。 */
    private void releaseIfSettled(World world, Run run, String why) {
        if (!running(world, run)) {
            return; // 世界卸载/插件关闭已经释放过，这里什么都不做
        }
        String phase;
        try {
            phase = new WorldRuntime(world).phase();
        } catch (RuntimeException | LinkageError failed) {
            report.warning("建筑四晶复活：" + why + "，但读阶段失败（" + reason(failed)
                    + "）；阶段推进区 ticket 保持持有");
            return;
        }
        if (phase == null) {
            report.info("建筑四晶复活：" + why + "，但读不到龙战对象；阶段推进区 ticket 保持持有");
            return;
        }
        if (!releasesTickets(phase)) {
            report.info("建筑四晶复活：" + why + "，阶段仍是 " + phase + "；阶段推进区 ticket 继续保持到终态");
            return;
        }
        report.info("建筑四晶复活：" + why + "，阶段 " + phase + "；释放阶段推进区 ticket");
        releaseArena(run);
    }

    /** 事件的方块优先（水晶是贴着座放的），没有方块就退回实体自己的世界；两者都读不到就不管。 */
    private static World worldOf(EntityPlaceEvent event) {
        try {
            Block block = event.getBlock();
            if (block != null) {
                return block.getWorld();
            }
            Entity entity = event.getEntity();
            return entity == null ? null : entity.getWorld();
        } catch (RuntimeException unloaded) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 一轮检查

    /** 排一轮检查；同一世界同时只允许一轮。 */
    private void trigger(World world, String why) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        Run run = runs.computeIfAbsent(world.getUID(), ignored -> new Run());
        if (!run.begin()) {
            return; // 已经有检查在跑：这一次放置由那一轮一起看
        }
        schedule(world, run, 0, why);
    }

    /**
     * 把「读布局 + 核验 + 判定 + 转交」排到中心区块的区域线程上。
     *
     * <p>中心区块固定是蓝图中心（建筑锚在原点，与 {@code EndPodiumShell} 一致）：布局本身要读世界 PDC，
     * 只能在自己的区域线程上读，所以排任务前不碰它。
     */
    private void schedule(World world, Run run, int attempt, String why) {
        schedule(world, run, attempt, why, attempt == 0 ? FIRST_DELAY_TICKS : RETRY_TICKS, false);
    }

    private void schedule(World world, Run run, int attempt, String why, long delay, boolean arenaPrepared) {
        int chunkX = 0;
        int chunkZ = 0;
        boolean queued = dispatch.region(world, chunkX, chunkZ, delay,
                () -> guard(world, run, attempt, why, arenaPrepared),
                problem -> abandon(world, run, "第 " + (attempt + 1) + " 轮检查没能排进区域线程：" + problem));
        if (!queued) {
            abandon(world, run, "第 " + (attempt + 1) + " 轮检查无法请求中心区块");
        }
    }

    /** 区域任务体：任何 {@link RuntimeException} 都收口成「拒绝并保留水晶」，绝不让状态卡在检查中。 */
    private void guard(World world, Run run, int attempt, String why, boolean arenaPrepared) {
        if (!running(world, run)) {
            return;
        }
        try {
            examine(world, run, attempt, why, arenaPrepared);
        } catch (RuntimeException | LinkageError error) {
            abandon(world, run, "检查过程中出错（" + reason(error) + "），本次不启动");
        }
    }

    /**
     * 一轮：**轻量归属 → 四颗齐备 → 阶段推进区准备 → 全量判定 → 原样转交**。
     *
     * <p>这个顺序是审查出的 P0-2，不是风格：未加载的 section 在 MU 里
     * {@code getRegionAtUnsynchronised} 返回 {@code null} ⇒ {@code isOwnedByCurrentRegion} 为
     * {@code false}。如果先跑全量归属核验（它包含 origin ±arena 环），那圈区块永远不可能通过，
     * 而唯一能创建它们的 {@link #prepareArena} 又被挡在后面 —— 加载永不可达，只会重试到拒绝，
     * 表现为「四颗水晶放好了，插件一直说归属不满足」。
     *
     * <p>所以先只核验四座与门环（那几个区块本来就该随中心建筑加载），确认四颗齐备，再异步准备
     * ±arena 并持有 ticket，最后才允许全量核验与原版调用。
     */
    private void examine(World world, Run run, int attempt, String why, boolean arenaPrepared) {
        Blueprint blueprint = podium.blueprint(world).orElse(null);
        if (blueprint == null) {
            // 世界没受管、建筑没建成、或还是旧版布局：什么都不做，也不提示（这是正常的早期世界）。
            run.idle();
            return;
        }
        run.world = world;
        Runtime runtime = new WorldRuntime(world);
        int section = runtime.regionSectionChunks();
        if (section <= 0) {
            // 桥接说「无法确定分区」：不猜、不退回默认，直接保晶拒绝（否则会带着错误的 arena 范围启动）。
            report.warning("建筑四晶复活：" + why + "后读不到这个世界的 region section 尺寸"
                    + "（MU 桥接不可用、版本不符或取值非法），无法确定阶段推进区范围；本次不启动，四颗水晶原样保留");
            run.idle();
            return;
        }
        int arenaRadius = arenaChunkRadius(section);
        List<long[]> required = requiredChunks(blueprint, section);

        String near = nearOwnership(blueprint, runtime);
        if (near != null) {
            retryOrGiveUp(world, run, attempt, why, near, arenaPrepared);
            return;
        }
        Verdict gather = gather(blueprint, runtime.crystalsNear(blueprint), runtime);
        if (!gather.start()) {
            // 三颗、额外晶、龙没死、复活已经在跑…… 都在这里：一个区块都不加载、一张 ticket 都不加。
            report.warning("建筑四晶复活不启动：" + gather.refusal());
            run.idle();
            return;
        }
        String arena = arenaOwnership(blueprint, runtime, arenaRadius);
        if (arena != null) {
            if (!arenaPrepared && attempt + 1 < MAX_ATTEMPTS) {
                prepareArena(world, run, required, attempt, why);
                return;
            }
            retryOrGiveUp(world, run, attempt, why, arena, arenaPrepared);
            return;
        }
        String players = playerGate(blueprint, runtime);
        if (players != null) {
            // 没有玩家时原版阶段不会推进：此时启动等于白清一次门，还要占 301 张 ticket。
            report.warning("建筑四晶复活不启动：" + players + "；四颗水晶原样保留");
            run.idle();
            return;
        }
        // ticket 必须在交晶**之前**全量持有：交给原版之后再补，门已经被清掉、阶段已经冻住了。
        List<long[]> held;
        try {
            held = runtime.holdArena(blueprint, required);
        } catch (RuntimeException | LinkageError refused) {
            report.warning("建筑四晶复活：阶段推进区 ticket 持有失败（" + reason(refused)
                    + "）；本次不启动，四颗水晶原样保留");
            run.idle();
            return;
        }
        run.heldArena.set(held);
        Round round = handOff(blueprint, runtime, required, held, report);
        if (round.started()) {
            run.state.set(State.STARTED);
            run.lastPhase.set(null);
            run.stalled.set(0);
            run.stalledReported.set(false);
            report.info("建筑四晶复活：阶段推进区已在启动前全量持有 " + held.size()
                    + " 个区块 ticket，阶段结束/中止/关闭时释放。");
            observe(world, run, blueprint, 1, why, arenaRadius);
            return;
        }
        // 没启动：这一轮的 ticket 立刻还回去（水晶不动、阶段不动）。
        releaseArena(run);
        if (round.loadCores() && attempt + 1 < MAX_ATTEMPTS) {
            prepareArena(world, run, required, attempt, why);
            return;
        }
        if (round.retry()) {
            retryOrGiveUp(world, run, attempt, why, round.refusal(), arenaPrepared);
            return;
        }
        run.idle();
    }

    /** 归属不满足：有限重试，用完就保晶拒绝（不做常驻扫描）。 */
    private void retryOrGiveUp(World world, Run run, int attempt, String why, String refusal,
                               boolean arenaPrepared) {
        if (attempt + 1 < MAX_ATTEMPTS) {
            report.warning("建筑四晶复活：" + why + "后第 " + (attempt + 1) + " 次核验不过（" + refusal + "），"
                    + RETRY_TICKS + " tick 后再核验一次。");
            schedule(world, run, attempt + 1, why, RETRY_TICKS, arenaPrepared);
            return;
        }
        report.warning("建筑四晶复活：" + why + "后连续 " + MAX_ATTEMPTS + " 次核验都不过（" + refusal
                + "），本次不启动；四颗水晶原样保留在世界里，下一次放置水晶事件会重新检查（不做常驻扫描）");
        run.idle();
    }

    /**
     * 阶段推进区准备：异步加载**最小可验证集**（`289 核心 + 外围 section 代表`，section=16 时 301 个）。
     *
     * <p>原版 {@code isArenaLoaded()} 判的是 region 是否覆盖 origin ±(8+section)，而 MU 的 region 是按
     * section 建立的 —— 一个 section 里任意一个 chunk 有 ticket，该 section 就有 region。所以加载
     * 16 个 section 各一个代表就够，不需要 49×49 的 2401 个区块。
     */
    private void prepareArena(World world, Run run, List<long[]> required, int attempt, String why) {
        report.info("建筑四晶复活：" + why + "后四座齐备，正在预先加载阶段推进区的最小可验证集（"
                + required.size() + " 个区块：origin ±" + CORE_CHUNK_RADIUS + " 的 "
                + chunkSquare(CORE_CHUNK_RADIUS) + " 个核心 + 外围 section 代表），完成后复检并全量持有 ticket。");
        boolean queued = dispatch.prepare(world, required,
                () -> schedule(world, run, attempt + 1, why, 0L, true),
                problem -> abandon(world, run, "阶段推进区区块预加载失败：" + problem));
        if (!queued) {
            abandon(world, run, "无法请求阶段推进区区块的预加载");
        }
    }

    /** 释放本轮持有的 ticket（幂等；本类不回收任何实体、不改龙战状态）。 */
    private void releaseArena(Run run) {
        List<long[]> held = run.heldArena.getAndSet(List.of());
        if (held.isEmpty()) {
            return;
        }
        World world = run.world;
        if (world == null) {
            return;
        }
        try {
            new WorldRuntime(world).releaseArena(held);
        } catch (RuntimeException | LinkageError failed) {
            report.warning("建筑四晶复活：释放阶段推进区 ticket 失败（" + reason(failed)
                    + "）；插件关闭时服务端仍会回收本插件的 ticket");
        }
    }

    /**
     * 启动后的**有界观测**：每 {@link #OBSERVE_TICKS} tick 读一次原版阶段，最多
     * {@link #MAX_OBSERVATIONS} 次就停。
     *
     * <p>为什么必须观测：原版阶段一旦启动，{@code isArenaLoaded()} 与门搜索都在**原版自己的**区域判定下
     * 进行；如果 origin ±{@code arenaRadius} 的区块不都由同一个 region 拥有，阶段会安静地停在
     * {@code SUMMONING_PILLARS} 而不是报错——那就是玩家看到的「四颗水晶在、龙一直不出」。本类不去「修」它
     * （不搬水晶、不销毁、不 abort 原版阶段），只把这件事说清楚，让管理员去查区域划分。
     */
    private void observe(World world, Run run, Blueprint blueprint, int step, String why, int arenaRadius) {
        if (step > MAX_OBSERVATIONS) {
            report.warning("建筑四晶复活：" + why + "后阶段观测窗口（" + MAX_OBSERVATIONS + " 次，约 "
                    + (OBSERVE_TICKS * MAX_OBSERVATIONS / 20) + " 秒）结束，本类不再跟踪；"
                    + "阶段推进区 ticket **保持持有**（超窗不释放），直到阶段到 NONE/END、明确中止、"
                    + "世界卸载或插件关闭才释放");
            return;
        }
        boolean queued = dispatch.region(world, blueprint.chunkX(), blueprint.chunkZ(), OBSERVE_TICKS,
                () -> observeOnce(world, run, blueprint, step, why, arenaRadius),
                problem -> report.warning("建筑四晶复活：第 " + step + " 次阶段观测没能排进区域线程（" + problem
                        + "），停止观测；阶段推进区 ticket 保持持有"));
        if (!queued) {
            report.warning("建筑四晶复活：第 " + step + " 次阶段观测无法请求中心区块，停止观测；"
                    + "阶段推进区 ticket 保持持有");
        }
    }

    private void observeOnce(World world, Run run, Blueprint blueprint, int step, String why, int arenaRadius) {
        if (!running(world, run)) {
            return;
        }
        String phase;
        try {
            phase = new WorldRuntime(world).phase();
        } catch (RuntimeException | LinkageError error) {
            report.warning("建筑四晶复活：读阶段失败（" + reason(error) + "），停止观测；"
                    + "阶段推进区 ticket 保持持有");
            return;
        }
        if (phase == null) {
            observe(world, run, blueprint, step + 1, why, arenaRadius);
            return;
        }
        if (releasesTickets(phase)) {
            report.info("建筑四晶复活：" + why + "这一轮的原版阶段已到 " + phase + "，观测结束，"
                    + "阶段推进区 ticket 释放");
            releaseArena(run);
            return;
        }
        String last = run.lastPhase.getAndSet(phase);
        if (phase.equals(last)) {
            int stalled = run.stalled.incrementAndGet();
            if (stalled >= STALLED_OBSERVATIONS && run.stalledReported.compareAndSet(false, true)) {
                report.warning("建筑四晶复活：原版阶段停在 " + phase + " 已连续 " + stalled + " 次观测（约 "
                        + (OBSERVE_TICKS * stalled / 20) + " 秒）没有推进。原版 isArenaLoaded() 要求 origin 区块 ±"
                        + arenaRadius + " 的区块都由同一个 region 拥有；不满足时阶段不会报错，只会停住。"
                        + "本类不销毁、不搬动水晶，也不 abort 原版阶段；请检查该末地区域划分后重新放水晶");
            }
        } else {
            run.stalled.set(0);
        }
        observe(world, run, blueprint, step + 1, why, arenaRadius);
    }

    /** 世界卸载/插件关闭/调度失败：让在途回调作废并退回可再触发的状态。 */
    private void abandon(World world, Run run, String why) {
        report.warning("建筑四晶复活：" + world.getName() + " " + why + "；四颗水晶原样保留，"
                + "本次不启动（不做常驻扫描）");
        run.idle();
    }

    private boolean running(World world, Run run) {
        return enabled.getAsBoolean() && !run.stopped() && runs.get(world.getUID()) == run;
    }

    // ------------------------------------------------------------------ 一轮的纯逻辑

    /**
     * 一轮检查：**归属核验 → 判定 → 二次校验 → 原样转交**。
     *
     * <p>顺序不是风格问题：归属核验只做坐标判定、不读世界，所以它能先跑；否则
     * {@code getNearbyEntities} 与读方块就可能落到别的区域上去。
     *
     * <p>「二次校验」是防竞态而不是仪式：{@code decide} 里读到的龙战阶段、四槽、四个实体，在同一 tick 内
     * 再读一遍并要求结论完全一致；任何差异（别的插件/玩家这一刻动了水晶、原版刚好自己启动、区域在两次
     * 读取之间被拆分）都直接放弃，绝不带着半新的结论去调原版。
     */
    static Round round(Blueprint blueprint, Runtime runtime, Report report) {
        if (runtime.regionSectionChunks() <= 0) {
            // 桥接给不出分区尺寸：不猜、不退回默认，直接拒绝（重试也不会变出可用桥接）。
            String why = "读不到这个世界的 region section 尺寸（MU 桥接不可用或版本不符），无法确定阶段推进区范围";
            report.warning("建筑四晶复活不启动：" + why);
            return new Round(false, false, false, why);
        }
        String unowned = ownership(blueprint, runtime);
        if (unowned != null) {
            return new Round(false, true, false, unowned); // 归属不满足：等一次重试（区域可能刚合并完）
        }
        Verdict first = decide(blueprint, runtime.crystalsNear(blueprint), runtime);
        if (!first.start()) {
            if (!first.retry()) {
                report.warning("建筑四晶复活不启动：" + first.refusal());
            }
            return new Round(false, first.retry(), false, first.refusal());
        }
        // 能启动了，但原版 findExitPortal 会在区域线程上同步 getChunk 289 个区块：先把它们加载好再复检。
        // 顺序不能反——在未加载的区块上做完判定再异步加载，中间玩家可能已经把水晶拿走。
        String notLoaded = unloadedCores(blueprint, runtime);
        if (notLoaded != null) {
            return new Round(false, false, true, notLoaded);
        }
        Verdict again = decide(blueprint, runtime.crystalsNear(blueprint), runtime);
        if (!again.start() || !again.crystals().equals(first.crystals())) {
            String why = again.start()
                    ? "两次读到的四颗水晶不一致（第一次 " + first.crystals() + "，第二次 " + again.crystals() + "）"
                    : again.refusal();
            report.warning("建筑四晶复活放弃启动：" + why + "；四颗水晶原样保留");
            return new Round(false, false, false, why);
        }
        boolean started;
        try {
            started = runtime.start(again.crystals());
        } catch (RuntimeException | LinkageError error) {
            report.warning("建筑四晶复活调用原版失败（" + reason(error) + "）；四颗水晶原样保留，"
                    + "不替代、不补充、不自行消耗");
            return new Round(false, false, false, "调用原版失败：" + reason(error));
        }
        if (!started) {
            report.warning("建筑四晶复活：原版拒绝接管（阶段可能刚变化），四颗水晶原样保留；"
                    + "原版半径 3 的路径仍然可用");
            return new Round(false, false, false, "原版拒绝接管");
        }
        report.info("建筑四晶复活已交给原版：四座晶座的 " + again.crystals().size()
                + " 颗真实水晶进入原版复活流程（beam/阶段/中止与最终消耗都由原版负责）");
        return new Round(true, false, false, null);
    }

    /**
     * 全量归属核验：**只调用坐标判定，一个方块都不读**。
     *
     * <p>这是给 {@link #round} 与测试用的完整口径，三段合起来：
     * {@link #nearOwnership}（四座与门环）、289 个核心区块、{@link #arenaOwnership}（阶段推进区）。
     *
     * <p>注意：生产路径**不能**把它放在 {@link #prepareArena} 之前 —— 阶段推进区未加载时这一段永远
     * 不通过（MU 对没有 section 的区块返回 {@code null} region），会挡住唯一的加载步骤。生产顺序见
     * {@link #examine}。
     *
     * @return {@code null} 表示全部属于当前区域；否则是中文原因
     */
    static String ownership(Blueprint blueprint, Runtime runtime) {
        String near = nearOwnership(blueprint, runtime);
        if (near != null) {
            return near;
        }
        int centerChunkX = blueprint.chunkX();
        int centerChunkZ = blueprint.chunkZ();
        for (int chunkX = centerChunkX - CORE_CHUNK_RADIUS; chunkX <= centerChunkX + CORE_CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = centerChunkZ - CORE_CHUNK_RADIUS; chunkZ <= centerChunkZ + CORE_CHUNK_RADIUS; chunkZ++) {
                if (!runtime.ownedChunk(chunkX, chunkZ)) {
                    return "区块 (" + chunkX + ", " + chunkZ + ") 不属于当前区域：原版 findExitPortal 会同步读取"
                            + " origin ±" + CORE_CHUNK_RADIUS + " 的 " + chunkSquare(CORE_CHUNK_RADIUS)
                            + " 个区块，本插件不跨区同步读写";
                }
            }
        }
        return arenaOwnership(blueprint, runtime, arenaChunkRadius(runtime.regionSectionChunks()));
    }

    /**
     * 轻量归属：四座的晶体头部/基岩支撑，以及四座与门环覆盖的区块（owned + loaded）。
     *
     * <p>这一段只覆盖「中心建筑本来就该加载」的几个区块，所以它可以在 {@link #prepareArena} 之前通过，
     * 用来判断「值不值得花代价去加载阶段推进区」。
     */
    static String nearOwnership(Blueprint blueprint, Runtime runtime) {
        for (Slot slot : blueprint.slots()) {
            if (!runtime.owned(slot.x(), slot.y(), slot.z())) {
                return "晶座 " + where(slot) + " 的晶体头部不属于当前区域（四颗必须与原生核心同一个区域线程）";
            }
            if (!runtime.owned(slot.x(), slot.y() - 1, slot.z())) {
                return "晶座 " + where(slot) + " 的基岩支撑不属于当前区域";
            }
        }
        for (long key : nearChunks(blueprint)) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (!runtime.ownedChunk(chunkX, chunkZ)) {
                return "区块 (" + chunkX + ", " + chunkZ + ") 不属于当前区域（四座与原生门环覆盖区必须同一个区域线程）";
            }
            if (!runtime.loaded(chunkX, chunkZ)) {
                return "区块 (" + chunkX + ", " + chunkZ + ") 尚未加载（判定要读这里的方块，不能在本轮读）";
            }
        }
        return null;
    }

    /**
     * 阶段推进区归属：origin ±（8 + regionSectionChunkSize）的区块全部归当前 region。
     *
     * <p>{@code radius} 由 {@link #arenaChunkRadius} 从运行期 section 尺寸算出（MU 上常见是 24）：
     * 原版 {@code isArenaLoaded()} 用的是 {@code isTickThreadFor(..., 8 + regionSectionChunkSize)}，
     * 写死 8 会把半径算成 16，于是核验通过、原版阶段却不推进。
     */
    static String arenaOwnership(Blueprint blueprint, Runtime runtime, int radius) {
        int centerChunkX = blueprint.chunkX();
        int centerChunkZ = blueprint.chunkZ();
        for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
            for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                if (!runtime.ownedChunk(chunkX, chunkZ)) {
                    return "区块 (" + chunkX + ", " + chunkZ + ") 不属于当前区域：原版 isArenaLoaded 要求 origin ±"
                            + radius + " 的区块同属一个 region，否则复活阶段会静默停住";
                }
            }
        }
        return null;
    }

    /**
     * 四座与原生门环覆盖的区块（用「区块」而不是「格」表达，因为 {@code decide} 读的是区块里的方块）。
     * 口径与 {@code EndPodiumTemplate.isFurniture} 一致：门环 {@code ±VANILLA_BOX_RADIUS}。
     */
    static Set<Long> nearChunks(Blueprint blueprint) {
        Set<Long> chunks = new LinkedHashSet<>();
        int radius = EndPodiumTemplate.VANILLA_BOX_RADIUS;
        for (int x = blueprint.centerX() - radius; x <= blueprint.centerX() + radius; x++) {
            for (int z = blueprint.centerZ() - radius; z <= blueprint.centerZ() + radius; z++) {
                chunks.add(chunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
            }
        }
        for (Slot slot : blueprint.slots()) {
            chunks.add(chunkKey(Math.floorDiv(slot.x(), 16), Math.floorDiv(slot.z(), 16)));
        }
        return chunks;
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * 289 个核心区块里还差哪些没加载。
     *
     * @return {@code null} 表示全部已加载；否则是中文原因（带第一个缺的区块）
     */
    static String unloadedCores(Blueprint blueprint, Runtime runtime) {
        int centerChunkX = blueprint.chunkX();
        int centerChunkZ = blueprint.chunkZ();
        for (int chunkX = centerChunkX - CORE_CHUNK_RADIUS; chunkX <= centerChunkX + CORE_CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = centerChunkZ - CORE_CHUNK_RADIUS; chunkZ <= centerChunkZ + CORE_CHUNK_RADIUS; chunkZ++) {
                if (!runtime.loaded(chunkX, chunkZ)) {
                    return "原生门搜索范围里有区块还没加载（第一块 " + chunkX + ", " + chunkZ + "）";
                }
            }
        }
        return null;
    }

    /**
     * 判定：四座各自唯一、四颗互不相同、位置与净空有效、世界受管且新版已建成、龙已死且没有复活在跑，
     * 四颗实体与当前区域一致。
     *
     * <p>「附近额外晶体」是硬性拒绝：原版会把这些水晶也算进回血与中止，等于让玩家拿额外水晶改流程。
     *
     * <p>分两段调用：{@link #gather} 只读实体与龙战状态（不读方块），生产路径先靠它决定「值不值得
     * 去加载阶段推进区」；本方法再补四座头部/支撑的净空校验。
     */
    static Verdict decide(Blueprint blueprint, List<Crystal> crystals, Runtime runtime) {
        Verdict gathered = gather(blueprint, crystals, runtime);
        if (!gathered.start()) {
            return gathered;
        }
        for (Slot slot : blueprint.slots()) {
            if (!runtime.air(slot.x(), slot.y(), slot.z())) {
                return Verdict.refuse("晶座 " + where(slot) + " 的头部不是空的（净空被堵），"
                        + "原版无法把水晶放在这里");
            }
            if (!runtime.solid(slot.x(), slot.y() - 1, slot.z())) {
                return Verdict.refuse("晶座 " + where(slot) + " 下方没有实心支撑（基岩座被清空或换了材料）");
            }
        }
        return gathered;
    }

    /**
     * 不读方块的判定：四座数、龙战前提、四颗互不相同、每座唯一、附近没有额外晶体、四颗与当前区域一致。
     *
     * <p>生产路径先用它决定要不要去加载阶段推进区（那是 49×49 区块的代价），再调 {@link #decide}
     * 补净空与支撑。测试里两段都可以单独钉。
     */
    static Verdict gather(Blueprint blueprint, List<Crystal> crystals, Runtime runtime) {
        if (blueprint.slots().size() != SLOT_COUNT) {
            return Verdict.refuse("建筑晶座不是 " + SLOT_COUNT + " 座（布局里有 " + blueprint.slots().size()
                    + " 座），这个布局不属于本适配");
        }
        Fight fight = runtime.fight();
        if (fight == null) {
            return Verdict.refuse("读不到这个世界的龙战对象");
        }
        if (!fight.previouslyKilled()) {
            return Verdict.refuse("末影龙还没有被击败过，复活前提不成立");
        }
        if (!fight.respawnNotRunning()) {
            return Verdict.refuse("原版复活流程已经在进行（阶段不是 NONE），本次不重复启动");
        }
        if (!fight.dragonAbsent()) {
            return Verdict.refuse("末影龙还在世界里");
        }
        Set<UUID> seen = new HashSet<>();
        for (Crystal crystal : crystals) {
            if (!seen.add(crystal.id())) {
                return Verdict.refuse("附近两次读到同一颗水晶（" + crystal.id() + "），实体数据不完整");
            }
        }
        Map<Slot, Crystal> chosen = new LinkedHashMap<>();
        List<Crystal> strays = new ArrayList<>();
        for (Crystal crystal : crystals) {
            Slot hit = null;
            for (Slot slot : blueprint.slots()) {
                if (slot.x() == crystal.x() && slot.y() == crystal.y() && slot.z() == crystal.z()) {
                    hit = slot;
                    break;
                }
            }
            if (hit == null) {
                strays.add(crystal);
                continue;
            }
            if (chosen.put(hit, crystal) != null) {
                return Verdict.refuse("晶座 " + where(hit) + " 上有两颗水晶（" + crystal.id() + "），"
                        + "每座只能有一颗");
            }
        }
        if (!strays.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (Crystal stray : strays) {
                list.append(list.length() == 0 ? "" : "、")
                        .append("(").append(stray.x()).append(", ").append(stray.y()).append(", ")
                        .append(stray.z()).append(")");
            }
            return Verdict.refuse("建筑附近还有 " + strays.size() + " 颗不在四座上的水晶 " + list
                    + "：原版会把它们算进回血与中止，先把它们拿走再放四颗");
        }
        if (chosen.size() != SLOT_COUNT) {
            return Verdict.refuse("四座里只放了 " + chosen.size() + " 颗水晶，需要四颗都放好才能启动"
                    + "（不放好就启动等于用一个不完整的复活流程）");
        }
        List<UUID> ids = new ArrayList<>(SLOT_COUNT);
        for (Slot slot : blueprint.slots()) {
            Crystal crystal = chosen.get(slot);
            if (!runtime.owned(crystal.id())) {
                return Verdict.refuse("水晶 " + crystal.id() + "（" + where(slot)
                        + "）不属于当前区域：实体归它自己的 owner 线程，不跨区启动");
            }
            ids.add(crystal.id());
        }
        return Verdict.start(ids);
    }

    private static String where(Slot slot) {
        return "(" + slot.x() + ", " + slot.y() + ", " + slot.z() + ")";
    }

    static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    // ------------------------------------------------------------------ 生产实现

    /**
     * 世界读取面：{@link Runtime} 的真服务端实现。每个 {@code crystalsNear} 调用都会重建
     * UUID → 实体 的映射，{@link #start} 只把映射里的**同一个**实体交出去。
     */
    private final class WorldRuntime implements Runtime {
        private final World world;
        private final Map<UUID, EnderCrystal> handles = new HashMap<>();

        WorldRuntime(World world) {
            this.world = world;
        }

        @Override
        public List<Crystal> crystalsNear(Blueprint blueprint) {
            handles.clear();
            Location center = new Location(world, blueprint.centerX() + 0.5, blueprint.gateY(),
                    blueprint.centerZ() + 0.5);
            Collection<Entity> found;
            try {
                found = world.getNearbyEntities(center, SEARCH_RADIUS_XZ + 0.5, SEARCH_UP,
                        SEARCH_RADIUS_XZ + 0.5, entity -> entity instanceof EnderCrystal);
            } catch (RuntimeException notReady) {
                return List.of(); // 区块/实体表暂不可读：按「一颗都没看到」处理，结论一定是「不放」
            }
            List<Crystal> crystals = new ArrayList<>(found.size());
            for (Entity entity : found) {
                if (!(entity instanceof EnderCrystal crystal)) {
                    continue;
                }
                if (crystal.isDead() || !crystal.isValid()) {
                    continue; // 有效性在这里过滤，判定只看位置与 UUID
                }
                Location at = crystal.getLocation();
                handles.put(crystal.getUniqueId(), crystal);
                crystals.add(new Crystal(crystal.getUniqueId(), at.getBlockX(), at.getBlockY(),
                        at.getBlockZ()));
            }
            return crystals;
        }

        @Override
        public boolean air(int x, int y, int z) {
            Block block = world.getBlockAt(x, y, z);
            return block.isEmpty() && !block.isLiquid();
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getType().isSolid();
        }

        @Override
        public boolean owned(int x, int y, int z) {
            return Bukkit.isOwnedByCurrentRegion(new Location(world, x + 0.5, y, z + 0.5));
        }

        @Override
        public boolean ownedChunk(int chunkX, int chunkZ) {
            return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
        }

        @Override
        public boolean loaded(int chunkX, int chunkZ) {
            return world.isChunkLoaded(chunkX, chunkZ);
        }

        @Override
        public String phase() {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    return null;
                }
                DragonBattle.RespawnPhase phase = battle.getRespawnPhase();
                return phase == null ? null : phase.name();
            } catch (RuntimeException notReady) {
                return null;
            }
        }

        /**
         * 玩家在场：与 MU 的 {@code EnderDragonFight#updatePlayers()} 同口径 ——
         * <b>以 {@code (0, 128, 0)} 为球心、半径 192 的球</b>内的活玩家。
         *
         * <p>三条实现约束：
         * <ol>
         *   <li>本地 API 只有立方体查询，所以先用 {@code getNearbyPlayers(origin, 192)} 做一次有界粗筛；</li>
         *   <li>候选玩家先看 {@code isOwnedByCurrentRegion} —— <b>不属于当前 region 的实体连 location 都不读</b>，
         *       避免跨区拉别人；</li>
         *   <li>再按精确球距与 {@code isDead}/{@code isValid} 过滤，而不是把立方体当球。</li>
         * </ol>
         * 读不到（区块/玩家表暂不可用）按「没有玩家」处理：宁可保晶拒绝，也不要启动一轮原版不会推进的阶段。
         */
        @Override
        public boolean playerNearby(Blueprint blueprint, int radiusBlocks) {
            // 球心是**整数坐标**：MU 的 validPlayer 是
            // EntitySelector.ENTITY_STILL_ALIVE.and(withinDistance(origin.x, 128 + origin.y, origin.z, 192.0))，
            // 而 withinDistance 用实体自身位置（脚位）对整数球心求平方距离；origin 是 BlockPos.ZERO，
            // 所以球心就是 (0, 128, 0)。这里加 0.5 会让边界整整差一格。
            Location origin = new Location(world, DRAGON_ORIGIN_X, DRAGON_ORIGIN_Y, DRAGON_ORIGIN_Z);
            Collection<Player> candidates;
            try {
                candidates = world.getNearbyPlayers(origin, radiusBlocks);
            } catch (RuntimeException notReady) {
                return false;
            }
            for (Player player : candidates) {
                try {
                    if (!Bukkit.isOwnedByCurrentRegion(player)) {
                        continue; // 先判归属：不属于当前 region 的实体连 isDead/位置都不读
                    }
                    if (player.isDead() || !player.isValid()) {
                        continue;
                    }
                    Location at = player.getLocation();
                    if (withinSphere(at.getX() - origin.getX(), at.getY() - origin.getY(),
                            at.getZ() - origin.getZ(), radiusBlocks)) {
                        return true;
                    }
                } catch (RuntimeException skipped) {
                    // 单个玩家读不到（刚下线/正在迁移）不算「有玩家」，继续看下一个。
                }
            }
            return false;
        }

        /**
         * region section 尺寸：直接问 MU 桥接，不写死、不猜。
         *
         * <p>桥接契约：正数可信，{@code -1} 表示不可启用（版本不符/链接失败/世界不是 CraftWorld/取值非法）。
         * {@code -1} 由调用方按「无法确定分区」安全拒绝，绝不退回某个默认值——写死 8 会算出 ±16，
         * 而 MU 26.3 实际是 16 ⇒ ±24；那样核验会通过、原版阶段却不推进。
         */
        @Override
        public int regionSectionChunks() {
            return MuDragonFightBridge.regionSectionChunkSize(world);
        }

        /**
         * 原地持有阶段推进区的区块 ticket。
         *
         * <p>只在区域线程上调用。逐块加 ticket，失败即停（已加的部分照常返回并会被释放）——
         * 持有不是启动的前提，拿不到只告警，不销毁、不 abort。
         */
        /**
         * 原地持有阶段推进区的区块 ticket（**必须在 {@code start} 之前**，见 {@link #examine}）。
         *
         * <p>{@code addPluginChunkTicket} 返回 {@code false} 不等于失败：可能本来就是我们之前加的。
         * 所以每一块都用 {@code getPluginChunkTickets} 复核一次「本插件是否确实持有」，只有确实拿不到的
         * 才不记进返回值——这样 {@link #ticketGate} 能凭数量判断是否全量，而不是被一个 false 骗过去。
         */
        @Override
        public List<long[]> holdArena(Blueprint blueprint, List<long[]> required) {
            if (plugin == null) {
                return List.of();
            }
            List<long[]> held = new ArrayList<>(required.size());
            try {
                for (long[] chunk : required) {
                    int chunkX = (int) chunk[0];
                    int chunkZ = (int) chunk[1];
                    boolean added = world.addPluginChunkTicket(chunkX, chunkZ, plugin);
                    boolean ours = added || world.getPluginChunkTickets(chunkX, chunkZ).contains(plugin);
                    if (!ours) {
                        continue;
                    }
                    held.add(new long[]{chunkX, chunkZ});
                }
            } catch (RuntimeException | LinkageError refused) {
                // 已经加上的都要能被释放，所以把「拿到哪些」原样返回；数量不足由 ticketGate 拦下。
                report.warning("建筑四晶复活：第 " + held.size() + " 个区块 ticket 没能加上（" + reason(refused)
                        + "），只持有已成功的那部分");
            }
            return held;
        }

        @Override
        public void releaseArena(List<long[]> held) {
            if (plugin == null) {
                return;
            }
            for (long[] chunk : held) {
                try {
                    world.removePluginChunkTicket((int) chunk[0], (int) chunk[1], plugin);
                } catch (RuntimeException refused) {
                    // 单块失败不打断释放；剩下的照常释放，插件禁用时服务端也会回收本插件的 ticket。
                    report.warning("建筑四晶复活：区块 (" + chunk[0] + ", " + chunk[1] + ") 的 ticket 释放失败（"
                            + reason(refused) + "），继续释放其余区块");
                }
            }
        }

        @Override
        public boolean owned(UUID crystal) {
            EnderCrystal entity = handles.get(crystal);
            return entity != null && Bukkit.isOwnedByCurrentRegion(entity);
        }

        @Override
        public Fight fight() {
            try {
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    return null;
                }
                // getRespawnPhase() 在 stage == null 时返回 NONE（CraftDragonBattle 的映射），
                // 所以「NONE」就是「没有一轮复活在跑」。
                return new Fight(battle.hasBeenPreviouslyKilled(),
                        battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE,
                        battle.getEnderDragon() == null);
            } catch (RuntimeException notReady) {
                return null;
            }
        }

        @Override
        public boolean start(List<UUID> crystals) {
            List<EnderCrystal> actual = new ArrayList<>(crystals.size());
            for (UUID id : crystals) {
                EnderCrystal crystal = handles.get(id);
                if (crystal == null || crystal.isDead() || !crystal.isValid()) {
                    // 绝不造替身：少一颗就整轮放弃，玩家那几颗原样留着。
                    return false;
                }
                actual.add(crystal);
            }
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle == null) {
                return false;
            }
            return battle.initiateRespawn(actual);
        }
    }

    /**
     * 区域调度：区块先异步就绪，再排到拥有它的区域线程上；磁盘与实体都不在这里碰。
     *
     * <p>不用 {@code addPluginChunkTicket}（忘 remove 就泄漏 ticket）；异步加载的临时 ticket 由服务端读完后
     * 自己释放。
     */
    private static final class WorldDispatch implements Dispatch {
        private final Plugin plugin;

        WorldDispatch(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean region(World world, int chunkX, int chunkZ, long delayTicks, Runnable task,
                              java.util.function.Consumer<String> onFailure) {
            if (!plugin.isEnabled()) {
                return false;
            }
            try {
                world.getChunkAtAsync(chunkX, chunkZ).thenRun(() -> {
                    if (!plugin.isEnabled()) {
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
                        onFailure.accept("区域任务被拒绝：" + reason(refused));
                    }
                }).exceptionally(error -> {
                    onFailure.accept("区块加载失败：" + reason(error));
                    return null;
                });
                return true;
            } catch (RuntimeException refused) {
                onFailure.accept("无法请求区块：" + reason(refused));
                return false;
            }
        }

        /**
         * 逐个异步加载给定的区块（{@link #requiredChunks} 那份最小可验证集），全部完成后回调 {@code ready}。
         *
         * <p>已加载的区块在 future 里立即完成，所以重试不重复付磁盘代价。这里**不**在回调里假设「还被同一个
         * region 拥有」：回调只负责重新排一次区域任务，由那一轮重新做 owner 核验。区块加载本身不写任何方块。
         */
        @Override
        public boolean prepare(World world, List<long[]> chunks, Runnable ready,
                               java.util.function.Consumer<String> onFailure) {
            if (!plugin.isEnabled()) {
                return false;
            }
            try {
                List<java.util.concurrent.CompletableFuture<?>> futures = new ArrayList<>(chunks.size());
                for (long[] chunk : chunks) {
                    futures.add(world.getChunkAtAsync((int) chunk[0], (int) chunk[1]));
                }
                java.util.concurrent.CompletableFuture
                        .allOf(futures.toArray(new java.util.concurrent.CompletableFuture<?>[0]))
                        .thenRun(() -> {
                            if (!plugin.isEnabled()) {
                                return;
                            }
                            try {
                                ready.run();
                            } catch (RuntimeException broken) {
                                onFailure.accept("预加载完成后的复检被拒绝：" + reason(broken));
                            }
                        })
                        .exceptionally(error -> {
                            onFailure.accept("阶段推进区区块预加载失败：" + reason(error));
                            return null;
                        });
                return true;
            } catch (RuntimeException refused) {
                onFailure.accept("无法请求阶段推进区区块：" + reason(refused));
                return false;
            }
        }
    }

    /** 日志：复用插件日志，不新增玩家语言键。 */
    private static final class PluginReport implements Report {
        private final Plugin plugin;

        PluginReport(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void info(String message) {
            plugin.getComponentLogger().info(message);
        }

        @Override
        public void warning(String message) {
            plugin.getComponentLogger().warn(message);
        }
    }
}
