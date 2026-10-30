package dev.mumu.betterend.vanilla;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.mumu.betterend.DustWastelandsGenerator;
import dev.mumu.betterend.terrain.IslandField;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 把 YUNG 柱模板的**运行期**那一半接进世界：把原版结构晶搬进模板的水晶洞，并在龙复活把柱子拆掉之后
 * 按同一份蓝图重铺。
 *
 * <h2>为什么需要这一层</h2>
 * 生成期的 populator 只写方块，写不出「挪走一颗实体」。而 MU 26.3 的
 * {@code EndSpikeFeature.placeSpike} 会在 {@code addFreshEntity} 之后才写旧座
 * {@code bedrock(cx,height,cz)} 与那格火 {@code fire(cx,height+1,cz)}（字节码核实），水晶因此出生在
 * 原版槽位 {@code height+1}；新布局的槽位是 {@code crystalY = 70+offset}（92..108），两者从不重合。
 * 复活时原版还会先 {@code removeBlock} 掉 {@code (cx±10, height±10, cz±10)} 的 21³、再无源
 * {@code explode(..., 5.0, BLOCK)}、最后按旧 H 重建整根原版柱——那套写入没有任何可取消的入口。
 *
 * <h2>识别官方结构晶（不搬玩家晶）</h2>
 * <b>主判据是实体自己的标记</b>：{@code EndCrystal.generatedByDragonFight}。本机 {@code
 * mucore-server-26.3.local-SNAPSHOT.jar} 字节码核实——全 jar 只有 {@code EndSpikeFeature.placeSpike}
 * 把它写成 {@code true}，{@code EndCrystalItem}（玩家放置）与 {@code EnderDragonFight} 都不写，
 * 所以「true ⟺ 原版柱结构晶」。经 {@code MuDragonFightBridge.isPillarCrystal(EnderCrystal)} 读取，
 * 只在实体 owner 线程上调用。辅助判据是「UUID 已在登记表里」（重铺过程中我们要找的就是同一颗晶）。
 *
 * <p>其余判据只是把「哪一根柱」和「这座世界是不是按新布局生成的」定下来，不参与所有权判定：
 * <ol>
 *   <li>末地维度 + 本插件生成器（{@link #manages});</li>
 *   <li>位置是原版槽位（柱心 XZ + {@code height+1}）或已经在新槽（{@link YungPillars#isVanillaCrystalSlot}）；</li>
 *   <li>阶段对：初次（{@code RespawnPhase.NONE} 且未击杀过）或复活中的
 *       {@code SUMMONING_PILLARS}（{@link #acceptsStage}）；</li>
 *   <li>下一 region tick 的二次核对：这根柱在登记表里「已按本布局建成」时只看登记表（复活时原版会把两处
 *       座位都拆掉）；第一次接手时要求新座 {@code bedrock(cx,69+offset,cz)} 存在。旧布局世界的座位在
 *       {@code height}，与 {@code 69+offset} 从不相等，所以旧世界会被判成「需要 EndLayoutUpgrade」并拒绝，
 *       不会自动搬晶。</li>
 * </ol>
 *
 * <p><b>玩家晶</b>：{@code generatedByDragonFight} 为 {@code false} 且 UUID 不在登记表里的一律不动——
 * 哪怕它就摆在与原版槽位完全相同的坐标上（判据是实体标记，不是位置、不是材质）。桥接方法缺失时本服务
 * **fail-closed**：一颗晶都不搬，只打一条 WARN，绝不用位置/材质去猜。
 *
 * <h2>边界：事件之后才动手</h2>
 * 实体事件在 {@code addFreshEntity} 里发出，而原版随后才写旧座与火，所以重铺排在**下一个 region tick**
 * （{@link #RECONCILE_DELAY_TICKS}）。这个 1 tick 是字节码里那条语句顺序的直接后果，不是随便等的。
 *
 * <h2>重铺的三段式（读 → 判 → 写），每段都按区块 1024 格分桶</h2>
 * <ol>
 *   <li><b>读</b>：逐桶在拥有该区块的区域线程上读当前状态（{@link Bukkit#isOwnedByCurrentRegion} 逐一确认）；</li>
 *   <li><b>判</b>：纯函数冲突判定（{@link YungPillars#acceptable}）。玩家在受管足迹里放了别的东西就整柱中止、
 *       报告、不动晶体；判定通过才把「会变的格」写成一份 gzip 快照落盘（{@link YungPillarStore}）；</li>
 *   <li><b>写</b>：再逐桶写，写前用同一套判定复核（读到写之间被改动也能拦住），然后
 *       {@code teleportAsync} 把**同一颗**晶体搬到新槽（beamTarget / generatedByDragonFight 等实体状态因此
 *       全部原样保留，不复制、不替补）。</li>
 * </ol>
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li>不取消爆炸事件、不改 gamerule、不替玩家挡龙；只在 {@code EntityExplodeEvent}/{@code BlockExplodeEvent}
 *       的方块列表里剔除「已按本布局建成、且当前状态仍等于蓝图」的格（{@link #shielded}）。原版那套直接
 *       {@code setBlock} 写入没有事件，靠的是事后重铺，不是靠拦事件冒充拦住。</li>
 *   <li>不做常驻扫描：只有实体事件、柱心区块加载、世界加载三种触发，每个柱一根一次；失败有次数上限
 *       （{@link #MAX_ATTEMPTS}），之后留日志等人看。</li>
 *   <li>不自动迁移旧布局世界：登记表里的布局版本不是 {@link YungPillarStore#LAYOUT} 时只报告，交给
 *       {@code EndLayoutUpgrade} 的显式 preview/apply。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 世界读写全部在拥有该区块的区域线程上；实体操作走实体自己的 scheduler（Folia 上实体随区域迁移，
 * region scheduler 不能替代实体 owner）；{@code teleportAsync} 也从实体 owner 发起；磁盘走异步池。
 * 世界卸载/插件关闭会让所有在途任务凭 {@code epoch} 原地失效。
 */
public final class YungPillarLifecycle implements Listener {

    /** 实体事件之后到重铺之间的 region tick 数（原因见类注释：原版的事件之后还写两格）。 */
    static final long RECONCILE_DELAY_TICKS = 1L;
    /** 二次核对没通过（区块还没写到、世界还在加载）时的重排间隔。 */
    static final long RETRY_DELAY_TICKS = 20L;
    /** 每根柱每轮最多重排几次；用完就留日志，不常驻扫描。 */
    static final int MAX_ATTEMPTS = 3;
    /** 单个区域任务最多写多少格（计划要求 ≤1024）。 */
    static final int MAX_CELLS_PER_TASK = 1024;
    /** 冲突报告里最多列几格。 */
    private static final int CONFLICT_SAMPLES = 5;
    /** {@code verify} 的「这根柱没有 built 记录」标记：不是重试能解决的，直接停下并留日志。 */
    static final String UNCLAIMED = "UNCLAIMED";
    /** 每根柱最多记几个待清除的旧晶 UUID；再多就丢最旧的并留日志，绝不无限增长。 */
    static final int STALE_LIMIT = 4;

    /** {@link #structureCrystal} 的静态查询入口；{@link #start()} 设置、{@link #close()} 清空。 */
    private static volatile YungPillarLifecycle active;

    private final Plugin plugin;
    private final Path dataDir;
    private final Runtime runtime;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 桥接不可用的诊断只念一次：fail-closed 不变，但不让运维以为「一片安静就是没问题」。 */
    private final AtomicBoolean bridgeWarned = new AtomicBoolean();

    /**
     * 生命周期对外部能力的最小端口：调度、方块状态读写、桥接标记、日志。
     *
     * <p>生产实现 {@link #PRODUCTION} 直接走 Bukkit / {@link MuDragonFightBridge} / 插件日志；
     * 单元测试注入一个同步实现，就能让 {@link #verifyAndMaybeClaim}→重铺→搬晶的**真实编排**在
     * 纯 JUnit 下跑通，而不必伪造整套 Bukkit。这不是测试专用的分支：生产路径同样经过这些方法。
     */
    interface Runtime {
        /** 插件是否已启用（关闭/失活时所有调度都得停手）。 */
        boolean enabled();

        /** 当前线程是否拥有该区块区域（写世界前的所有权核验）。 */
        boolean ownsRegion(World world, int chunkX, int chunkZ);

        /** 当前线程是否拥有该实体（读实体标记前）。 */
        boolean ownsEntity(org.bukkit.entity.Entity entity);

        /** 把任务排到拥有该区块的区域线程上；立即请求也至少下一 tick。 */
        void region(World world, int chunkX, int chunkZ, long delay, Runnable task);

        /** 把任务排到异步池（登记表/快照落盘）。 */
        void async(Runnable task);

        /** 状态串 → BlockData（写世界）。 */
        BlockData blockData(String state);

        /** 这颗实体是不是「原版柱放置」写下的官方晶（fail-closed）。 */
        boolean pillarCrystal(org.bukkit.entity.Entity entity);

        /** 龙战桥接是否可用。 */
        boolean bridgeAvailable();

        void info(String message);

        void warn(String message);

        /** 生产实现：全部落到真实 Bukkit / 桥接 / 插件日志。 */
        Runtime PRODUCTION = new Runtime() {
            @Override
            public boolean enabled() {
                return current != null && current.plugin.isEnabled();
            }

            @Override
            public boolean ownsRegion(World world, int chunkX, int chunkZ) {
                return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
            }

            @Override
            public boolean ownsEntity(org.bukkit.entity.Entity entity) {
                return Bukkit.isOwnedByCurrentRegion(entity);
            }

            @Override
            public void region(World world, int chunkX, int chunkZ, long delay, Runnable task) {
                Bukkit.getRegionScheduler().runDelayed(current.plugin, world, chunkX, chunkZ,
                        ignored -> task.run(), regionDelay(delay));
            }

            @Override
            public void async(Runnable task) {
                Bukkit.getAsyncScheduler().runNow(current.plugin, ignored -> task.run());
            }

            @Override
            public BlockData blockData(String state) {
                return YungPillars.bukkit(state);
            }

            @Override
            public boolean pillarCrystal(org.bukkit.entity.Entity entity) {
                return entity instanceof EnderCrystal crystal
                        && MuDragonFightBridge.isPillarCrystal(crystal);
            }

            @Override
            public boolean bridgeAvailable() {
                return MuDragonFightBridge.available();
            }

            @Override
            public void info(String message) {
                current.plugin.getComponentLogger().info(message);
            }

            @Override
            public void warn(String message) {
                current.plugin.getComponentLogger().warn(message);
            }
        };
    }

    /** 生产 {@link Runtime} 需要拿到当前插件实例（日志/调度都要它）。 */
    private static volatile YungPillarLifecycle current;

    /**
     * @param plugin  插件实例（调度、日志都用它）
     * @param dataDir 插件数据目录；柱晶登记表与重铺前快照落在它下面
     */
    public YungPillarLifecycle(Plugin plugin, Path dataDir) {
        this(plugin, dataDir, Runtime.PRODUCTION);
    }

    /** 测试用：注入同步 {@link Runtime}（生产路径与测试路径共用同一编排）。 */
    YungPillarLifecycle(Plugin plugin, Path dataDir, Runtime runtime) {
        this.plugin = plugin;
        this.dataDir = dataDir;
        this.runtime = runtime;
        current = this;
    }

    /** 一个受管世界的运行内状态。 */
    static final class Run {
        final UUID worldId;
        final long seed;
        final IslandField field;
        /** 世界卸载/插件关闭时自增，在途任务凭它原地失效。 */
        final AtomicLong epoch = new AtomicLong();
        /** 登记表；只在 {@link #registryLock} 上改。 */
        final Object registryLock = new Object();
        volatile YungPillarStore.Registry registry = YungPillarStore.Registry.empty();
        volatile boolean registryLoaded;
        /** 已登记、还没重铺完成的柱下标。 */
        final Set<Integer> pending = ConcurrentHashMap.newKeySet();
        /** 正在重铺的柱下标（每柱一次）。 */
        final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
        /** 已建成的柱 → 蓝图格状态（爆炸过滤用，懒构建）。 */
        final Map<Integer, Map<Long, String>> shields = new ConcurrentHashMap<>();
        /** 每根柱「登记了但实体找不到」的连续次数；超过上限就放弃这一轮，避免每次区块加载都白跑一趟。 */
        final Map<Integer, Integer> misses = new ConcurrentHashMap<>();
        /** 正在进行「无 built 时的模板回读认领」的柱（去重，避免事件叠加时重复读世界）。 */
        final Set<Integer> verifyInFlight = ConcurrentHashMap.newKeySet();
        /** 每根柱模板回读的累计尝试次数：超过 {@link #MAX_ATTEMPTS} 就停手，不永久全图扫描。 */
        final Map<Integer, Integer> verifyTries = new ConcurrentHashMap<>();
        /** 已经提示过「未认领」的柱：同一会话只念一次，不刷屏也不静默。 */
        final Set<Integer> warnedUnclaimed = ConcurrentHashMap.newKeySet();
        /** 待落盘的登记表快照（单飞写：见 {@code persistRegistry}）。 */
        final java.util.concurrent.atomic.AtomicReference<YungPillarStore.Registry> pendingRegistry =
                new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicBoolean writing = new AtomicBoolean();
        /** 每次改登记表自增；写盘循环据此判断「刚写的还是最新的」。 */
        final AtomicLong registryVersion = new AtomicLong();
        /** 待清除的旧官方晶（每柱 ≤ STALE_LIMIT 个）：只按 UUID、只在实体 owner 线程清。 */
        final Map<Integer, java.util.List<UUID>> stale = new ConcurrentHashMap<>();
        volatile boolean stopped;

        Run(UUID worldId, long seed, IslandField field) {
            this.worldId = worldId;
            this.seed = seed;
            this.field = field;
        }
    }

    // ------------------------------------------------------------------ 公开入口

    /**
     * 启动：预热模板（缺模板在这里炸），并给「插件启用前就已加载」的受管世界补一次登记表读取与有限对账。
     *
     * <p>不是常驻扫描：只读一次登记表，只对「已登记但没建成」的柱排一次任务。
     */
    public void start() {
        active = this;
        YungPillars.preload();
        for (World world : List.copyOf(Bukkit.getWorlds())) {
            adopt(world, "插件启用前已加载");
        }
    }

    /** 关闭：让所有在途回调原地失效，丢掉运行内状态（登记表在插件自己的文件里，不依赖内存）。 */
    public void close() {
        closed.set(true);
        for (Run run : runs.values()) {
            run.stopped = true;
            run.epoch.incrementAndGet();
        }
        runs.clear();
        if (active == this) {
            active = null;
        }
    }

    /** 末地维度 + 本插件生成器；两者缺一就不碰。 */
    public static boolean manages(World world) {
        return world != null
                && world.getEnvironment() == World.Environment.THE_END
                && world.getGenerator() instanceof DustWastelandsGenerator;
    }

    /**
     * 第 {@code spike} 根柱已登记的官方结构晶 UUID。
     *
     * <p>只读：来自插件自己的登记表（启动时读入、成功搬迁后写回）。**没有值就是「无所有权证据」**，
     * 调用方不得据此推断可以覆盖。
     */
    public static Optional<UUID> structureCrystal(World world, EndSpikes.Spike spike) {
        YungPillarLifecycle service = active;
        if (service == null || world == null || spike == null) {
            return Optional.empty();
        }
        Run run = service.runs.get(world.getUID());
        if (run == null) {
            return Optional.empty();
        }
        YungPillarStore.Registry registry = run.registry;
        // 登记只在「实体标记为真的官方晶」时发生，所以这里不再按世界布局版本一票否决：
        // 恢复接管会把布局版本一并升到当前值（{@code markBuilt} 里的 withLayout）。
        int index = spikeAt(EndSpikes.forSeed(world.getSeed()), spike.centerX(), spike.centerZ());
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry.crystal(index));
    }

    // ------------------------------------------------------------------ 事件

    /** 原版结构晶出生（初次生成与复活都走这里；复活还会再触发一次 AddToWorld）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalSpawn(EntitySpawnEvent event) {
        identify(event.getEntity(), "实体生成");
    }

    /** 实体进世界：区块重新加载时的同一颗晶也会走这里，靠 UUID 去重，不会重复搬。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCrystalAddToWorld(EntityAddToWorldEvent event) {
        identify(event.getEntity(), "实体进世界");
    }

    /** 世界加载：按需读登记表。 */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        adopt(event.getWorld(), "世界加载");
    }

    /** 世界卸载：在途任务一律失效。 */
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        Run run = runs.remove(event.getWorld().getUID());
        if (run == null) {
            return;
        }
        run.stopped = true;
        run.epoch.incrementAndGet();
    }

    /**
     * 柱心区块加载：有限对账已登记的晶；没有 {@code built} 时先做一次有界模板回读认领。
     *
     * <p>只认「柱心那一个区块」的加载，不扫区块、不扫实体表。这条入口让<b>已生成世界</b>与
     * <b>插件启用后重新加载的柱心</b>也能补上 {@code built}，不再只靠一次性的 {@code ChunkPopulateEvent}。
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!manages(world)) {
            return;
        }
        Run run = runs.get(world.getUID());
        if (run == null || run.stopped) {
            return;
        }
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(run.seed);
        for (int i = 0; i < spikes.size(); i++) {
            EndSpikes.Spike spike = spikes.get(i);
            if ((spike.centerX() >> 4) != chunkX || (spike.centerZ() >> 4) != chunkZ) {
                continue;
            }
            final int index = i;
            if (run.stale.containsKey(index)) {
                // 只重排旧晶清理，**绝不清 inFlight**：那会放行第二个并发的重铺。
                schedule(world, run, chunkX, chunkZ, 0L, () -> retireStale(world, run, index),
                        why -> warn("柱：第 " + index + " 根柱的旧晶清理重排失败：" + why));
            }
            if (!isBuilt(run, index)) {
                // 已生成世界 / 重启后重新加载的柱心：没有 built 时走一次有界模板回读，
                // 只有完整匹配当前模板才会落盘 built 再认领（不再只靠一次性的 Populate）。
                verifyAndMaybeClaim(world, run, index, "柱心区块加载");
                continue;
            }
            if (!run.pending.contains(index)) {
                continue;
            }
            reconcile(world, run, index, 0, "柱心区块加载");
        }
    }

    /**
     * 区块 decorate 完成（populator 之后）：给柱心那根柱做一次**回读**，通过才记 {@code built}。
     *
     * <p>为什么必须是这里：原版 {@code end_spike} 是 feature，跑在 {@code applyBiomeDecoration} 的前半段；
     * 我们的 populator 在同一方法的后半段（字节码偏移 9 与 25）。所以柱晶的
     * {@code EntitySpawnEvent} 一定发生在模板写下去之前，那时既不能靠座位基岩、也还不该写世界。
     * 生成真正完成的可靠时点是本事件——此时模板已经落地，回读确认后写下 {@code built}，
     * 之后（复活/重启/区块重载）才有资格自动重铺。
     */
    @EventHandler
    public void onChunkPopulate(ChunkPopulateEvent event) {
        World world = event.getWorld();
        if (closed.get() || !manages(world)) {
            return;
        }
        Run run = run(world);
        if (run.stopped) {
            return;
        }
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(run.seed);
        for (int i = 0; i < spikes.size(); i++) {
            EndSpikes.Spike spike = spikes.get(i);
            if ((spike.centerX() >> 4) != chunkX || (spike.centerZ() >> 4) != chunkZ) {
                continue;
            }
            final int index = i;
            if (isBuilt(run, index)) {
                continue;
            }
            // 生成是本轮唯一能重置尝试计数的地方：生成完成时的回读必须拿到一次干净的重试预算。
            run.verifyTries.remove(index);
            verifyAndMaybeClaim(world, run, index, "生成完成");
        }
    }

    /**
     * 无 {@code built} 时的**有界**模板回读认领：读整根当前模板足迹，只有全部命中才持久化当前布局
     * {@code built}，再认领那颗官方晶并对账。
     *
     * <p>三条入口共用它：生成完成（{@link #onChunkPopulate}）、柱心区块加载（{@link #onChunkLoad}）、
     * 官方晶出生/进世界且本柱无 built（{@link #identify}）。这样已生成世界、首次回读失败的柱、插件启用时
     * 已加载的柱心都能恢复，不再依赖一次性的 Populate 事件。
     *
     * <p><b>不猜所有权</b>：缺格（未加载）当作瞬时失败并按 {@link #MAX_ATTEMPTS} 有限重试；读全了但对不上
     * （旧布局 / 受损 / 玩家改动 / 无证据）当作稳定拒绝——只给出状态诊断，不自动接管。
     */
    void verifyAndMaybeClaim(World world, Run run, int index, String source) {
        if (closed.get() || run.stopped) {
            return;
        }
        if (!run.verifyInFlight.add(index)) {
            return; // 同一根柱已有一次回读在跑
        }
        EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
        YungPillars.Blueprint blueprint;
        try {
            blueprint = YungPillars.blueprint(spike, run.seed, run.field);
        } catch (RuntimeException error) {
            run.verifyInFlight.remove(index);
            warn("柱：" + world.getName() + " 第 " + index + " 根柱的模板回读蓝图失败：" + reason(error));
            return;
        }
        Map<Long, YungPillars.Cell> byKey = new LinkedHashMap<>(blueprint.cells().size() * 2);
        int expected = 0;
        for (YungPillars.Cell cell : blueprint.cells()) {
            byKey.put(cell.key(), cell);
            if (cell.origin() == YungPillars.Origin.TEMPLATE
                    && !"minecraft:air".equals(cell.state())) {
                expected++;
            }
        }
        java.util.Set<Long> vanilla = YungPillars.vanillaCells(spike, run.field);
        Map<Long, String> read = new ConcurrentHashMap<>(blueprint.cells().size() * 2);
        runWork(world, run, workOf(blueprint), work -> {
            for (long key : work.keys()) {
                read.put(key, stateAt(world, YungPillars.unpackX(key), YungPillars.unpackY(key),
                        YungPillars.unpackZ(key)));
            }
        }, () -> {
            run.verifyInFlight.remove(index);
            if (closed.get() || run.stopped) {
                return;
            }
            GeneratedVerdict verdict = evaluateTemplate(blueprint, read);
            String detail = verdict.firstBad();
            if (!verdict.complete()) {
                // 首次回读可能刚好撞上邻区块还没写完 / 尚未加载：有限重试，去重靠 verifyInFlight。
                // 次数用完仍不匹配才当稳定拒绝——旧布局 / 受损 / 玩家改动 / 无证据一律不自动接管，
                // 只给出分类原因（本版没有恢复指令）。stateAt 对未加载区块也会返回空气，所以这里不靠
                // 「缺读」区分瞬时/稳定，而是用尝试次数封顶，避免永久全图扫描。
                int tries = run.verifyTries.merge(index, 1, Integer::sum);
                if (tries < MAX_ATTEMPTS) {
                    schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4,
                            RETRY_DELAY_TICKS,
                            () -> verifyAndMaybeClaim(world, run, index, source + " 重试"),
                            problem -> warn("柱：第 " + index + " 根柱的回读重试排不进去：" + problem));
                    return;
                }
                YungPillarRecovery.Status status;
                try {
                    status = YungPillarRecovery.classify(index, spike, blueprint,
                            YungPillars.legacyBlueprint(spike, run.seed, run.field), vanilla, read)
                            .status();
                } catch (RuntimeException error) {
                    status = YungPillarRecovery.Status.NO_EVIDENCE;
                }
                warnUnclaimed(run, index, world, source + " 回读不匹配（" + verdict.matched() + "/"
                        + verdict.total() + "，状态 " + status + "，" + detail + "，已试 " + tries
                        + " 次）：不自动接管（本版没有柱恢复指令），这根柱保持现有方块，需人工处理");
                return;
            }
            run.verifyTries.remove(index);
            info("柱：" + world.getName() + " 第 " + index + " 根柱" + source
                    + "回读通过（" + verdict.matched() + "/" + verdict.total()
                    + "），落盘当前布局 built 后认领官方晶。");
            markBuilt(run, index, source + "回读通过", () -> claimGeneratedCrystal(world, run, index),
                    writeProblem -> warn("柱：" + world.getName() + " 第 " + index
                            + " 根柱的 built 落盘失败：" + writeProblem));
        }, problem -> {
            run.verifyInFlight.remove(index);
            warn("柱：" + world.getName() + " 第 " + index + " 根柱的模板回读中止：" + problem);
        });
    }

    /**
     * 初次生成完成后认领那根柱的官方晶。
     *
     * <p>生成期走的是 {@code WorldGenRegion.addFreshEntity}（只往 proto-chunk 里塞实体），**不发任何 Bukkit
     * 实体事件**；所以初次生成不能等 EntitySpawn/AddToWorld。这里改成一次有界实体查询，判据仍然只有
     * 原版标记 {@code generatedByDragonFight}——不按位置、不按材质。
     */
    private void claimGeneratedCrystal(World world, Run run, int index) {
        EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
        schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, 0L, () -> {
            EnderCrystal crystal = findPillarCrystal(world, spike);
            if (crystal == null) {
                run.pending.add(index);
                int misses = run.misses.merge(index, 1, Integer::sum);
                if (misses >= MAX_ATTEMPTS) {
                    info("柱：第 " + index + " 根柱生成完成后连续 " + misses
                            + " 次没查到官方晶；等下一次区块/实体事件再认领。");
                    return;
                }
                info("柱：第 " + index + " 根柱生成完成，但这一 tick 还没查到官方晶；"
                        + RETRY_DELAY_TICKS + " tick 后再试（第 " + misses + " 次）。");
                schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, RETRY_DELAY_TICKS,
                        () -> claimGeneratedCrystal(world, run, index),
                        problem -> warn("柱：第 " + index + " 根柱的初始晶重试排不进去：" + problem));
                return;
            }
            run.misses.remove(index);
            UUID current = crystal.getUniqueId();
            UUID previous;
            synchronized (run.registryLock) {
                previous = run.registry.crystal(index);
                if (!current.equals(previous)) {
                    run.registry = run.registry.withCrystal(index, current);
                    run.registryVersion.incrementAndGet();
                    persistRegistry(run);
                }
            }
            if (!current.equals(previous)) {
                info("柱：生成完成后登记第 " + index + " 根柱的官方晶：" + current);
                if (previous != null) {
                    rememberStale(run, index, previous);
                }
            }
            run.pending.add(index);
            reconcile(world, run, index, 0, "生成完成认领");
        }, reason -> warn("柱：第 " + index + " 根柱的初始晶认领排不进去：" + reason));
    }

    /** 记 {@code built} 并落盘（同柱重复调用是幂等的）。 */
    private void markBuilt(Run run, int index, String why, Runnable then, Consumer<String> fail) {
        YungPillarStore.Registry next;
        synchronized (run.registryLock) {
            // 认领成功即把登记表的布局版本升到当前值：否则旧世界永远被判「旧布局」，受控恢复只生效一次。
            next = run.registry.withCurrentBuilt(index);
            run.registry = next;
            run.registryVersion.incrementAndGet();
        }
        persistRegistry(run);
        long epoch = run.epoch.get();
        // 完成标志必须真正落盘才能继续；写盘失败就回滚内存标记并上报。
        try {
            runtime.async(() -> {
                try {
                    synchronized (run.registryLock) {
                        YungPillarStore.writeRegistry(dataDir, run.worldId, run.registry);
                    }
                } catch (Exception error) {
                    synchronized (run.registryLock) {
                        run.registry = run.registry.withoutBuilt(index);
                        run.registryVersion.incrementAndGet();
                    }
                    warn("柱：第 " + index + " 根柱的 built 写盘失败（" + reason(error)
                            + "），已回滚为未完成。");
                    // 回滚也要落盘：否则文件里说「已完成」、内存说「未完成」，重启后状态反转。
                    persistRegistry(run);
                    if (fail != null) {
                        fail.accept("登记表写盘失败：" + reason(error));
                    }
                    return;
                }
                // epoch 守卫：落盘成功但本轮已被世界卸载/重启顶替时，**不再继续认领/搬晶**，
                // 只把已经落盘的 built 留在文件里，等新一轮重新核验，避免旧回调污染新操作。
                if (closed.get() || run.stopped || run.epoch.get() != epoch) {
                    return;
                }
                run.warnedUnclaimed.remove(index);
                info("柱：第 " + index + " 根柱已认领（" + why + "）。");
                if (then != null) {
                    then.run();
                }
            });
        } catch (RuntimeException refused) {
            synchronized (run.registryLock) {
                run.registry = run.registry.withoutBuilt(index);
                run.registryVersion.incrementAndGet();
            }
            if (fail != null) {
                fail.accept("无法安排登记表写盘：" + reason(refused));
            }
        }
    }

    /** 龙的破坏列表：剔除本服务建成、且当前状态仍等于蓝图的格（不取消事件）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.blockList(), event.getEntity().getWorld());
    }

    /** 复活阶段的 21³ 无源爆炸（{@code explode(null, ...)} 走这个事件）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.blockList(), event.getBlock().getWorld());
    }

    private void filterExplosion(List<Block> blocks, World world) {
        if (blocks.isEmpty() || !manages(world)) {
            return;
        }
        Run run = runs.get(world.getUID());
        if (run == null || run.stopped || run.registry.built().isEmpty()) {
            return;
        }
        blocks.removeIf(block -> runtime.ownsRegion(block.getWorld(), block.getX() >> 4,
                block.getZ() >> 4) && shielded(run, block));
    }

    // ------------------------------------------------------------------ 识别

    /**
     * 纯判据：这颗晶是不是我们要管的官方柱晶。
     *
     * <p>主判据是原版柱放置写下的标记，辅助判据是「UUID 已在登记表里」（重铺中的同一颗晶）。
     * 两者都为假就是玩家晶——**哪怕坐标正好等于原版槽位**。
     */
    static boolean owns(boolean generatedByDragonFight, boolean registeredUuid) {
        return generatedByDragonFight || registeredUuid;
    }

    /**
     * 经桥接读取 {@code EndCrystal.generatedByDragonFight}。**只在实体 owner 线程上调用。**
     *
     * <p>桥接不可用 / 版本不符 / 类型链接失败时返回 {@code false}（fail-closed，一颗晶都不当成柱晶），
     * 绝不用位置或材质去猜。
     */
    static boolean pillarCrystal(org.bukkit.entity.Entity entity) {
        return entity instanceof EnderCrystal crystal
                && MuDragonFightBridge.isPillarCrystal(crystal);
    }

    /** 纯判定：这个复活阶段能不能认领结构晶。 */
    static boolean acceptsStage(DragonBattle.RespawnPhase phase, boolean previouslyKilled) {
        if (phase == null || phase == DragonBattle.RespawnPhase.NONE) {
            return !previouslyKilled;
        }
        return phase == DragonBattle.RespawnPhase.SUMMONING_PILLARS;
    }

    /**
     * 一颗官方柱晶现在能不能被接管/重新登记。
     *
     * <p>区分两条路径，<b>不要简单删阶段判据</b>：
     * <ul>
     *   <li><b>已登记同一颗 UUID</b>（重启 / 区块重载 / 上一轮遗留）：永远允许——它就是我们的晶体，
     *       不该因为复活阶段不是 {@code SUMMONING_PILLARS} 而被丢下；</li>
     *   <li><b>已 built 柱上新出现的官方晶</b>在 {@code NONE} 也允许：那是重启后重新加载、或第一次击杀后
     *       加载的柱晶，此时并没有正在进行中的复活，按阶段拒绝会造成「柱已建好却不搬晶」的死锁；</li>
     *   <li>其余（未 built 的柱、或复活进行到 START/PREPARING/SUMMONING_DRAGON 等中间阶段）沿用
     *       {@link #acceptsStage}：只有初次未击杀或 {@code SUMMONING_PILLARS} 才认领。</li>
     * </ul>
     */
    static boolean takeoverAllowed(boolean built, boolean registered,
                                   DragonBattle.RespawnPhase phase, boolean previouslyKilled) {
        if (registered) {
            return true;
        }
        if (built && (phase == null || phase == DragonBattle.RespawnPhase.NONE)) {
            return true;
        }
        return acceptsStage(phase, previouslyKilled);
    }

    /** 柱心匹配：返回下标，没匹配到返回 -1。 */
    static int spikeAt(List<EndSpikes.Spike> spikes, int centerX, int centerZ) {
        for (int i = 0; i < spikes.size(); i++) {
            EndSpikes.Spike spike = spikes.get(i);
            if (spike.centerX() == centerX && spike.centerZ() == centerZ) {
                return i;
            }
        }
        return -1;
    }

    /** 原版槽位匹配：返回下标，没匹配到返回 -1。 */
    static int crystalSlotIndex(List<EndSpikes.Spike> spikes, int blockX, int blockY, int blockZ) {
        for (int i = 0; i < spikes.size(); i++) {
            if (YungPillars.isVanillaCrystalSlot(spikes.get(i), blockX, blockY, blockZ)) {
                return i;
            }
        }
        return -1;
    }

    private void identify(org.bukkit.entity.Entity entity, String why) {
        if (closed.get() || !(entity instanceof EnderCrystal crystal)) {
            return;
        }
        World world = crystal.getWorld();
        if (!manages(world)) {
            return;
        }
        long seed = world.getSeed();
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(seed);
        Location at = crystal.getLocation();
        int bx = at.getBlockX();
        int by = at.getBlockY();
        int bz = at.getBlockZ();
        int index = crystalSlotIndex(spikes, bx, by, bz);
        if (index < 0) {
            index = targetSlotIndex(spikes, seed, bx, by, bz);
        }
        if (index < 0) {
            return; // 既不在原版槽位也不在目标槽：与本服务无关
        }
        Run run = run(world);
        if (run.stopped) {
            return;
        }
        loadRegistry(run);
        UUID registeredUuid;
        synchronized (run.registryLock) {
            registeredUuid = run.registry.crystal(index);
        }
        boolean registered = crystal.getUniqueId().equals(registeredUuid);
        if (!isBuilt(run, index)) {
            // 没有当前布局的 built：先做一次有界模板回读。通过则落盘 built、认领、对账；
            // 不通过（旧布局 / 受损 / 玩家改动 / 无证据）只报告状态，绝不自动重铺。
            // 这样「实体事件先于 Populate」的生成期、已生成世界、重启都不会再死锁。
            verifyAndMaybeClaim(world, run, index, why);
            return;
        }
        // 已有 built：所有权主判据是实体自己的 generatedByDragonFight（只在实体 owner 线程读），
        // 辅助判据是「UUID 已在登记表里」。两者都假就是玩家晶，坐标再像也不动。
        boolean official = runtime.pillarCrystal(crystal);
        if (!official && !registered) {
            warnBridgeIfDisabled(world, index);
        }
        if (!owns(official, registered)) {
            return;
        }
        if (!registered) {
            // built 柱上新出现的官方晶：仅在正确阶段认领；已登记 UUID / 重启加载由 takeoverAllowed 放行。
            if (!takeoverAllowed(true, false, respawnPhase(world), previouslyKilled(world))) {
                return;
            }
            UUID current = crystal.getUniqueId();
            synchronized (run.registryLock) {
                run.registry = run.registry.withCrystal(index, current);
                persistRegistry(run);
            }
            info("柱：登记第 " + index + " 根柱的官方结构晶（" + why + "）：" + current);
            if (registeredUuid != null) {
                // 顺序硬约束：只记下待清的旧 UUID，等「新晶搬运 + 回读 + built 落盘」之后才清除。
                rememberStale(run, index, registeredUuid);
            }
        }
        run.pending.add(index);
        reconcile(world, run, index, 0, why);
    }

    /** 桥接不可用时只念一次：fail-closed 不变，但要能解释「为什么一颗官方晶都不动」。 */
    private void warnBridgeIfDisabled(World world, int index) {
        if (runtime.bridgeAvailable() || !bridgeWarned.compareAndSet(false, true)) {
            return;
        }
        warn("柱：" + world.getName() + " 第 " + index + " 根柱的官方晶标记无法读取（龙战桥接不可用/版本不符）；"
                + "本服务保持 fail-closed，不搬任何晶体——请核对服务端版本与桥接 ABI。");
    }

    /** 同一根柱「未认领」只提示一次，避免刷屏，也不静默走开。 */
    private void warnUnclaimed(Run run, int index, World world, String because) {
        if (!run.warnedUnclaimed.add(index)) {
            return;
        }
        warn("柱：" + world.getName() + " 第 " + index + " 根柱未认领：" + because + "。");
    }

    /**
     * 这根柱是否「按<b>当前</b>布局建成」。
     *
     * <p>必须同时要求登记表的布局版本是当前值：旧布局登记表里的 {@code built} 是 0.1.26 柱的记录，
     * 不能无条件继承为新布局所有权——那些柱只能由显式迁移蓝图处理（本版没有对应命令）。
     */
    private static boolean isBuilt(Run run, int index) {
        YungPillarStore.Registry registry = run.registry;
        return registry.currentLayout() && registry.built().contains(index);
    }

    /**
     * 退役同一根柱**上一颗**官方晶。
     *
     * <p>先完成新实体接管（登记表已经指向新 UUID），再在旧实体**自己的 entity scheduler** 上核验
     * {@code generatedByDragonFight} 后清除：核验在 owner 线程做，玩家晶（标记为 false）永不清理。
     * 原版复活会先 {@code removeBlock} 21³ 再炸一发，旧晶在我们的柱体里常常活下来；不清就会一柱两晶、
     * 龙战计数 &gt; 10。
     */
    /** 记下待清除的旧官方晶 UUID（有界，超出丢最旧并留日志）。 */
    private void rememberStale(Run run, int index, UUID previous) {
        java.util.List<UUID> list = run.stale.computeIfAbsent(index,
                ignored -> java.util.Collections.synchronizedList(new ArrayList<>(STALE_LIMIT)));
        synchronized (list) {
            java.util.List<UUID> next = staleAfterAdd(list, previous, STALE_LIMIT);
            if (next.size() == list.size() && list.contains(previous)) {
                return;
            }
            if (!list.isEmpty() && !next.contains(list.get(0))) {
                warn("柱：第 " + index + " 根柱待清除的旧晶超过 " + STALE_LIMIT
                        + " 个，丢弃最旧记录 " + list.get(0) + "。");
            }
            list.clear();
            list.addAll(next);
        }
    }

    /**
     * 待清除旧晶列表的纯规则：去重、**先进先出**、最多 {@code limit} 个。
     *
     * <p>顺序很重要：两轮复活会产生两个旧 UUID，清除必须按「先旧后新」的顺序一个个来，
     * 不能把新一点的那颗当成最旧的提前清掉。
     */
    static java.util.List<UUID> staleAfterAdd(java.util.List<UUID> current, UUID add, int limit) {
        java.util.List<UUID> next = new ArrayList<>(current.size() + 1);
        for (UUID uuid : current) {
            if (!next.contains(uuid)) {
                next.add(uuid);
            }
        }
        if (!next.contains(add)) {
            next.add(add);
        }
        while (next.size() > Math.max(1, limit)) {
            next.remove(0);
        }
        return next;
    }

    /**
     * 清除已登记的旧官方晶：**只按 UUID、只在它自己的 entity scheduler 上、先核验标记**。
     *
     * <p>三种结果都不能当成「已删除」：实体不存在、{scheduler} 返回 {@code null}（尚未排入或已
     * retired）、核验不通过。这些情况都保留 stale 记录并在下一次事件重试（有界），不扫实体表。
     */
    private void retireStale(World world, Run run, int index) {
        java.util.List<UUID> list = run.stale.get(index);
        if (list == null || list.isEmpty()) {
            return;
        }
        UUID previous;
        synchronized (list) {
            previous = list.isEmpty() ? null : list.get(0);
        }
        if (previous == null) {
            run.stale.remove(index);
            return;
        }
        org.bukkit.entity.Entity entity = world.getEntity(previous);
        if (entity == null) {
            // 实体已经不在世界里（被玩家打掉/已被原版消耗）——注销登记。
            synchronized (list) {
                list.remove(previous);
            }
            if (list.isEmpty()) {
                run.stale.remove(index);
            }
            info("柱：第 " + index + " 根柱的旧晶 " + previous + " 已不存在，注销记录。");
            return;
        }
        try {
            if (entity.getScheduler().run(plugin, task -> {
                if (!entity.isValid()) {
                    synchronized (list) {
                        list.remove(previous);
                    }
                    return;
                }
                if (!(entity instanceof EnderCrystal old) || !old.getUniqueId().equals(previous)
                        || !runtime.pillarCrystal(old)) {
                    warn("柱：第 " + index + " 根柱的旧晶 " + previous
                            + " 核验不通过（标记为 false 或 UUID 已变），保留不动。");
                    return;
                }
                old.remove();
                synchronized (list) {
                    list.remove(previous);
                }
                info("柱：第 " + index + " 根柱的上一颗官方晶已清除（" + previous + "）。");
            }, () -> warn("柱：第 " + index + " 根柱的旧晶 " + previous
                    + " 清除时实体已退役，保留 stale 记录等下一次事件。")) == null) {
                warn("柱：第 " + index + " 根柱的旧晶 " + previous
                        + " 清除任务未能排入（retired），保留 stale 记录。");
            }
        } catch (RuntimeException refused) {
            warn("柱：第 " + index + " 根柱的旧晶清除排不进去（" + reason(refused)
                    + "），保留 stale 记录：" + previous);
        }
    }

    /** 已经在目标槽（例如重启后实体重新进世界）时按新槽位反查柱下标。 */
    static int targetSlotIndex(List<EndSpikes.Spike> spikes, long seed, int blockX, int blockY,
                               int blockZ) {
        for (int i = 0; i < spikes.size(); i++) {
            EndSpikes.Spike spike = spikes.get(i);
            YungPillars.Layout layout = YungPillars.layout(spike, seed);
            if (spike.centerX() == blockX && spike.centerZ() == blockZ
                    && layout.crystalY() == blockY) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 对账与重铺

    /**
     * 第 {@code index} 根柱的一次对账：先二次核对，再重铺，最后搬晶体。
     *
     * @param attempt 已重排次数（{@link #MAX_ATTEMPTS} 上限）
     */
    private void reconcile(World world, Run run, int index, int attempt, String why) {
        if (closed.get() || run.stopped) {
            return;
        }
        EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
        schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, RECONCILE_DELAY_TICKS,
                () -> {
                    if (closed.get() || run.stopped) {
                        return;
                    }
                    if (!run.inFlight.add(index)) {
                        return; // 这根柱已经在处理
                    }
                    UUID uuid;
                    synchronized (run.registryLock) {
                        uuid = run.registry.crystal(index);
                    }
                    if (uuid == null) {
                        run.inFlight.remove(index);
                        abandon(run, index);
                        return;
                    }
                    EnderCrystal crystal = findCrystal(world, uuid);
                    if (crystal == null) {
                        // 区块/实体还没加载：先留着 pending 等下一次事件；连找几次都没有就放弃这一轮
                        // （晶体被玩家打掉、或实体已被原版丢弃），别让每次区块加载都白跑。
                        run.inFlight.remove(index);
                        int misses = run.misses.merge(index, 1, Integer::sum);
                        if (misses > MAX_ATTEMPTS) {
                            abandon(run, index);
                            info("柱：" + world.getName() + " 第 " + index + " 根柱登记的晶体 "
                                    + uuid + " 连续 " + misses + " 次找不到，本轮放弃（等新的结构晶事件）。");
                        }
                        return;
                    }
                    run.misses.remove(index);
                    String problem = verify(world, run, index, spike, crystal);
                    if (problem != null) {
                        run.inFlight.remove(index);
                        if (UNCLAIMED.equals(problem)) {
                            abandon(run, index);
                            warnUnclaimed(run, index, world,
                                    "没有 built 记录，不自动重铺（本版没有柱恢复指令）"
                                            + "；这根柱保持现有方块，需人工处理");
                            return;
                        }
                        retry(world, run, index, attempt, why, problem);
                        return;
                    }
                    rebuild(world, run, index, spike, crystal, attempt, false);
                },
                reason -> retry(world, run, index, attempt, why, reason));
    }

    /**
     * 二次核对：晶体仍在原版槽位，且这座世界确实是按新布局生成的。返回 {@code null} 表示通过。
     *
     * <p>证据分两档，都要求「晶体就在原版结构槽位」这一条先成立：
     * <ol>
     *   <li><b>本服务已建成过这根柱</b>（登记表 {@code built} + 当前布局版本）：复活时原版的 21³ 清理会
     *       把两处座位基岩都拆掉，所以这时只认登记表，不再要求方块证据；</li>
     *   <li><b>第一次接手</b>：要求新座位 {@code bedrock(cx, 69+offset, cz)} 存在。旧布局世界的座位在
     *       {@code height}（两式从不相等），所以「只有旧座、没有新座」会被判成旧布局并拒绝，
     *       绝不自动迁移。</li>
     * </ol>
     */
    private String verify(World world, Run run, int index, EndSpikes.Spike spike,
                          EnderCrystal crystal) {
        if (!crystal.isValid()) {
            return "晶体已失效";
        }
        YungPillars.Layout layout = YungPillars.layout(spike, run.seed);
        Location at = crystal.getLocation();
        int bx = at.getBlockX();
        int by = at.getBlockY();
        int bz = at.getBlockZ();
        boolean atTarget = bx == spike.centerX() && bz == spike.centerZ() && by == layout.crystalY();
        if (!atTarget && !YungPillars.isVanillaCrystalSlot(spike, bx, by, bz)) {
            return "晶体不在原版结构槽位（" + bx + "," + by + "," + bz + "）";
        }
        if (!isBuilt(run, index)) {
            return UNCLAIMED;
        }
        return null;
    }

    private void retry(World world, Run run, int index, int attempt, String why, String problem) {
        if (attempt + 1 >= MAX_ATTEMPTS) {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱的对账放弃（" + why + "，" + problem
                    + "，已试 " + (attempt + 1) + " 次）；晶体留在原处，等下一次区块/实体事件再试。");
            return;
        }
        schedule(world, run, EndSpikes.forSeed(run.seed).get(index).centerX() >> 4,
                EndSpikes.forSeed(run.seed).get(index).centerZ() >> 4, RETRY_DELAY_TICKS,
                () -> reconcile(world, run, index, attempt + 1, why),
                reason -> warn("柱：" + world.getName() + " 第 " + index + " 根柱重排失败：" + reason));
    }

    /**
     * 三段式重铺（读 → 判 → 写 → 搬晶体）。
     *
     * <p>所有世界读写都在拥有该区块的区域线程上，且每个任务开工前用
     * {@link Bukkit#isOwnedByCurrentRegion(World, int, int)} 复核所有权。
     */
    private void rebuild(World world, Run run, int index, EndSpikes.Spike spike,
                         EnderCrystal crystal, int attempt, boolean adopt) {
        YungPillars.Blueprint blueprint;
        YungPillars.Blueprint legacy;
        java.util.Set<Long> union;
        try {
            YungPillars.checkWorldLimits(spike, run.seed, world.getMinHeight(), world.getMaxHeight());
            // 显式恢复（adopt）用迁移蓝图：新模板 + 旧模板残件按地形还原。
            // 自动重铺只用新蓝图：新布局世界里那些格本来就是地形。
            blueprint = adopt
                    ? YungPillars.migrationBlueprint(spike, run.seed, run.field,
                            world.getMinHeight())
                    : YungPillars.blueprint(spike, run.seed, run.field);
            legacy = YungPillars.legacyBlueprint(spike, run.seed, run.field);
            union = YungPillarRecovery.unionKeys(spike, run.seed,
                    YungPillars.blueprint(spike, run.seed, run.field), legacy,
                    world.getMinHeight());
        } catch (RuntimeException error) {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱的蓝图失败："
                    + reason(error));
            abandon(run, index);
            return;
        }
        YungPillars.Layout layout = blueprint.layout();
        Map<Long, YungPillars.Cell> byKey = new LinkedHashMap<>(blueprint.cells().size() * 2);
        for (YungPillars.Cell cell : blueprint.cells()) {
            byKey.put(cell.key(), cell);
        }
        List<Work> reads = workOf(union);
        List<Work> writes = workOf(blueprint);
        Map<Long, String> before = new ConcurrentHashMap<>(union.size() * 2);
        info("柱：" + world.getName() + " 第 " + index + " 根柱" + (adopt ? "恢复接管" : "重铺")
                + "开始（" + spike.centerX() + "," + spike.centerZ() + "）档位 "
                + layout.tier() + " 旋转 " + layout.rotation() + "，读 " + union.size()
                + " 格 / 写 " + blueprint.cells().size() + " 格，晶座 y="
                + layout.seatY() + " 晶体 y=" + layout.crystalY());

        runWork(world, run, reads, work -> {
            for (long key : work.keys()) {
                before.put(key, stateAt(world, YungPillars.unpackX(key), YungPillars.unpackY(key),
                        YungPillars.unpackZ(key)));
            }
        }, () -> {
            String conflict = firstConflict(blueprint, before);
            if (conflict != null) {
                warn("柱：" + world.getName() + " 第 " + index + " 根柱中止：受管足迹里有玩家改动（"
                        + conflict + "）。一格未写，晶体留在原处。");
                abandon(run, index);
                return;
            }
            snapshotAsync(world, run, index, union, before, () ->
                    runWork(world, run, writes, work -> {
                        for (long key : work.keys()) {
                            YungPillars.Cell cell = byKey.get(key);
                            if (cell == null) {
                                continue;
                            }
                            String current = stateAt(world, cell.x(), cell.y(), cell.z());
                            if (!YungPillars.shouldWrite(cell, current)) {
                                continue; // 洞穴是 CaveCarver 挖的，不是被擦掉的缺口
                            }
                            if (!YungPillars.acceptable(current, cell.state(), before.get(key))) {
                                throw new IllegalStateException("玩家改动冲突 " + cell.x() + ","
                                        + cell.y() + "," + cell.z() + "：" + current + " → "
                                        + cell.state());
                            }
                            world.getBlockAt(cell.x(), cell.y(), cell.z())
                                    .setBlockData(data(cell.state()), false);
                        }
                    }, () -> verifyWritten(world, run, index, spike, byKey, writes, crystal, attempt,
                            adopt),
                            problem -> {
                                warn("柱：" + world.getName() + " 第 " + index + " 根柱写入中止："
                                        + problem + "。快照保留，晶体留在原处。");
                                abandon(run, index);
                            }));
        }, problem -> {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱读取中止：" + problem);
            abandon(run, index);
        });
    }

    /**
     * 回读校验 + 搬晶：**只有逐格回读全部命中才记 {@code built}**。
     *
     * <p>写下去不等于读回来是那个值；任何不一致都中止并保留快照，
     * 绝不把半成品标成已修复。
     */
    private void verifyWritten(World world, Run run, int index, EndSpikes.Spike spike,
                               Map<Long, YungPillars.Cell> byKey, List<Work> writes,
                               EnderCrystal crystal, int attempt, boolean adopt) {
        runWork(world, run, writes, work -> {
            for (long key : work.keys()) {
                YungPillars.Cell cell = byKey.get(key);
                if (cell == null) {
                    continue;
                }
                String current = stateAt(world, cell.x(), cell.y(), cell.z());
                if (!YungPillars.shouldWrite(cell, current)) {
                    continue;
                }
                if (!current.equals(cell.state())) {
                    throw new IllegalStateException("回读不一致 " + cell.x() + "," + cell.y() + ","
                            + cell.z() + "：期望 " + cell.state() + " 实际 " + current);
                }
            }
        }, () -> {
            // 找晶体要在柱心区块的区域线程上（实体归它那个区域），不能在别的分桶线程上跨区读实体。
            schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, 0L, () -> {
                EnderCrystal target = crystal != null && crystal.isValid() ? crystal
                        : findPillarCrystal(world, spike);
                if (target == null) {
                    // 柱体已过回读、built 该记；只是这一轮没有可搬的官方晶：**柱体完成、晶体待接管**，
                    // 两者分开记账——保留 pending 并有限重试，绝不假报完整完成。
                    markBuilt(run, index, (adopt ? "恢复接管" : "重铺") + "回读通过（晶体待接管）",
                            () -> retryCrystal(world, run, index, attempt), problem -> {
                                warn("柱：" + index + " 的 built 落盘失败：" + problem);
                                abandon(run, index);
                            });
                    return;
                }
                moveCrystal(world, run, index, spike, target, YungPillars.layout(spike, run.seed),
                        attempt);
            }, problem -> {
                warn("柱：" + world.getName() + " 第 " + index + " 根柱找晶体任务排不进去：" + problem);
                abandon(run, index);
            });
        }, problem -> {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱回读失败：" + problem
                    + "。快照保留，不记 built。");
            abandon(run, index);
        });
    }

    /** 在柱子包围盒内找那颗「原版柱放置」写下的晶体（标记为 true 的才算）。 */
    private EnderCrystal findPillarCrystal(World world, EndSpikes.Spike spike) {
        YungPillars.Layout layout = YungPillars.layout(spike, runSeedOf(world));
        // 只查柱心那一列的两个候选高度（原版槽位 H+1、新座 crystalY），XZ 收紧到 col..col+1，
        // 保证整个包围盒落在柱心那一个区块里（实体坐标是 cx+0.5）。
        for (int y : new int[]{spike.height() + 1, layout.crystalY()}) {
            BoundingBox box = new BoundingBox(spike.centerX(), y - 0.5, spike.centerZ(),
                    spike.centerX() + 1.0, y + 1.5, spike.centerZ() + 1.0);
            for (org.bukkit.entity.Entity entity : world.getNearbyEntities(box)) {
                if (entity instanceof EnderCrystal candidate
                        && runtime.ownsEntity(candidate)
                        && runtime.pillarCrystal(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static long runSeedOf(World world) {
        return world.getSeed();
    }

    /** 把同一颗晶体搬到新槽；{@code teleportAsync} 返回 false 也算失败（不假装成功）。 */
    private void moveCrystal(World world, Run run, int index, EndSpikes.Spike spike,
                             EnderCrystal crystal, YungPillars.Layout layout, int attempt) {
        Location target = new Location(world, spike.centerX() + 0.5, layout.crystalY(),
                spike.centerZ() + 0.5, crystal.getLocation().getYaw(), crystal.getLocation().getPitch());
        try {
            if (crystal.getScheduler().run(plugin, task -> crystal.teleportAsync(target)
                    .thenAccept(ok -> {
                        if (!Boolean.TRUE.equals(ok)) {
                            warn("柱：" + world.getName() + " 第 " + index + " 根柱：teleportAsync 返回 "
                                    + ok + "，晶体仍在原处；本轮放弃，等下一次区块/实体事件再试。");
                            abandon(run, index);
                            retryMove(world, run, index, attempt, "teleportAsync 返回 " + ok);
                            return;
                        }
                        confirmMoved(world, run, index, spike, layout, crystal.getUniqueId(), attempt);
                    })
                    .exceptionally(error -> {
                        warn("柱：" + world.getName() + " 第 " + index + " 根柱搬运晶体异常："
                                + reason(error) + "；本轮放弃，等下一次区块/实体事件再试。");
                        abandon(run, index);
                        retryMove(world, run, index, attempt, "搬运晶体异常：" + reason(error));
                        return null;
                    }), () -> {
                        // 实体在任务开跑前退役：scheduler 的 retired 回调必须收尾，否则 inFlight 永远不释放、
                        // 重试彻底卡死。这里走同一条有界 retryMove，不常驻看门狗。
                        warn("柱：" + world.getName() + " 第 " + index + " 根柱：搬运任务排入前实体已退役，"
                                + "晶体留在原处，按有界重试。");
                        abandon(run, index);
                        retryMove(world, run, index, attempt, "实体任务退役");
                    }) == null) {
                warn("柱：" + world.getName() + " 第 " + index + " 根柱：实体调度被拒绝（实体已退役），"
                        + "晶体留在原处，等下一次区块事件再试。");
                abandon(run, index);
                retryMove(world, run, index, attempt, "实体调度被拒绝");
            }
        } catch (RuntimeException refused) {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱：无法排实体任务（" + reason(refused)
                    + "），晶体留在原处。");
            abandon(run, index);
            retryMove(world, run, index, attempt, "无法排实体任务：" + reason(refused));
        }
    }

    /**
     * 传送成功后确认再收尾：实体确实到了目标坐标、且晶座那一格净空可容纳晶体。
     *
     * <p>{@code teleportAsync} 返回 true 只代表传送调用没被拒。**净空核验必须分桶**：柱心常在
     * {@code cx±1 / cz±1} 跨 Z 区块（例如中心 (42,0)、(-42,-1)），在单个区块任务里直接读邻区块
     * 会越界。这里先 {@link #runWork} 按区块读净空格，全部读完后**再回实体 owner（柱心区块）**
     * 核验实体位置并收尾。任一条不成立都不声明完整完成，按 {@link #MAX_ATTEMPTS} 有限重试——
     * 不挖空旧槽、不临时改世界来掩盖搬迁失败。
     */
    private void confirmMoved(World world, Run run, int index, EndSpikes.Spike spike,
                              YungPillars.Layout layout, UUID uuid, int attempt) {
        java.util.Set<Long> keys = clearanceKeys(spike, layout);
        Map<Long, String> states = new ConcurrentHashMap<>(keys.size() * 2);
        runWork(world, run, workOf(keys), work -> {
            for (long key : work.keys()) {
                states.put(key, stateAt(world, YungPillars.unpackX(key), YungPillars.unpackY(key),
                        YungPillars.unpackZ(key)));
            }
        }, () -> schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, 0L, () -> {
            if (closed.get() || run.stopped) {
                return;
            }
            org.bukkit.entity.Entity entity = world.getEntity(uuid);
            if (!(entity instanceof EnderCrystal moved) || !moved.isValid()) {
                warn("柱：" + world.getName() + " 第 " + index + " 根柱：传送后没找到同一颗晶体（" + uuid
                        + "），不记完成。");
                abandon(run, index);
                retryMove(world, run, index, attempt, "传送后实体丢失");
                return;
            }
            Location at = moved.getLocation();
            boolean atTarget = at.getBlockX() == spike.centerX() && at.getBlockZ() == spike.centerZ()
                    && at.getBlockY() == layout.crystalY();
            if (!atTarget) {
                warn("柱：" + world.getName() + " 第 " + index + " 根柱：传送后位置不符（" + at.getBlockX()
                        + "," + at.getBlockY() + "," + at.getBlockZ() + "，期望 cx," + layout.crystalY()
                        + ",cz），不记完成。");
                abandon(run, index);
                retryMove(world, run, index, attempt, "传送后位置不符");
                return;
            }
            String notClear = crystalClearance(spike, layout, states);
            if (notClear != null) {
                warn("柱：" + world.getName() + " 第 " + index + " 根柱：晶座不净空（" + notClear
                        + "），不记完成、不挖方块；请核对蓝图净空。");
                abandon(run, index);
                retryMove(world, run, index, attempt, "晶座不净空：" + notClear);
                return;
            }
            complete(world, run, index, layout, uuid);
        }, problem -> {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱：传送确认任务排不进去：" + problem);
            abandon(run, index);
            retryMove(world, run, index, attempt, "传送确认排不进去");
        }), problem -> {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱：净空核验读取失败：" + problem);
            abandon(run, index);
            retryMove(world, run, index, attempt, "净空核验读取失败");
        });
    }

    /** 净空核验要读的格：晶座中心 + 四正交邻位，在 {@code crystalY..crystalY+1}。 */
    static java.util.Set<Long> clearanceKeys(EndSpikes.Spike spike, YungPillars.Layout layout) {
        int cy = layout.crystalY();
        int cx = spike.centerX();
        int cz = spike.centerZ();
        java.util.Set<Long> keys = new LinkedHashSet<>(9);
        for (int y = cy; y <= cy + 1; y++) {
            keys.add(YungPillars.pack(cx, y, cz));
            keys.add(YungPillars.pack(cx + 1, y, cz));
            keys.add(YungPillars.pack(cx - 1, y, cz));
            keys.add(YungPillars.pack(cx, y, cz + 1));
            keys.add(YungPillars.pack(cx, y, cz - 1));
        }
        return keys;
    }

    /**
     * 纯判定：用分桶读到的状态核验晶体落位前是否净空。返回 {@code null} 表示可以落位。
     *
     * <p>判据来自模板实测：{@code (cx, crystalY, cz)} 模板根本不写、世界值是空气；四个<b>正交</b>邻位在
     * {@code crystalY} 与 {@code crystalY+1} 也必须非实心。**不要求 3×3 全空**——guarded 的 1/4/7 档在
     * 四个对角有模板自带的铁栏杆（上游把晶体围起来的笼子），要求全空会误杀原版结构；也不把
     * {@code (cx, crystalY-1, cz)} 的座位基岩当障碍（晶体 AABB 从 {@code crystalY} 起）。
     */
    static String crystalClearance(EndSpikes.Spike spike, YungPillars.Layout layout,
                                   Map<Long, String> states) {
        for (long key : clearanceKeys(spike, layout)) {
            String state = states.get(key);
            if (state == null) {
                return "格 " + YungPillars.unpackX(key) + "," + YungPillars.unpackY(key) + ","
                        + YungPillars.unpackZ(key) + " 未读到";
            }
            if (!isEmptyState(state)) {
                return "格 " + YungPillars.unpackX(key) + "," + YungPillars.unpackY(key) + ","
                        + YungPillars.unpackZ(key) + " 为 " + YungPillars.blockId(state);
            }
        }
        return null;
    }

    /** 方块状态串是不是空气（{@code air} / {@code cave_air} / {@code void_air}）。 */
    static boolean isEmptyState(String state) {
        String id = YungPillars.blockId(state);
        return "minecraft:air".equals(id) || "minecraft:cave_air".equals(id)
                || "minecraft:void_air".equals(id);
    }

    /**
     * 这一轮不再处理这根柱：清掉 pending 与互斥标记，等下一次事件（区块加载/实体事件）重新触发。
     *
     * <p>**不改登记表**：晶体 UUID 仍然记着，所以下一轮还是同一颗晶体；失败留下的痕迹只在日志里。
     */
    private static void abandon(Run run, int index) {
        run.pending.remove(index);
        run.inFlight.remove(index);
    }

    /**
     * 搬晶失败后的有界重试：清掉 inFlight 之后按 {@link #MAX_ATTEMPTS} 再排一次对账。
     *
     * <p>重试仍然走 {@link #reconcile}，所以「登记表 + 实体标记」的判据会重新核一遍；次数用完就停手留日志，
     * 不常驻扫描。这条路径保证一根柱不会因为一次 teleportAsync 失败而在本会话里永久被跳过。
     */
    private void retryMove(World world, Run run, int index, int attempt, String problem) {
        if (attempt + 1 >= MAX_ATTEMPTS) {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱：搬晶连续失败（" + problem + "，已试 "
                    + (attempt + 1) + " 次）；晶体留在原处，等下一次区块/实体事件。");
            return;
        }
        EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
        schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, RETRY_DELAY_TICKS,
                () -> reconcile(world, run, index, attempt + 1, "搬晶重试"),
                reason -> warn("柱：" + world.getName() + " 第 " + index + " 根柱搬晶重排失败：" + reason));
    }

    /**
     * 「柱体完成、晶体待接管」的有限重试。
     *
     * <p>built 已经落盘（柱体是真修好了），但这一轮没找到可搬的官方晶。保留 pending 并按
     * {@link #MAX_ATTEMPTS} 再排一次对账；次数用完就明确记为「柱体完成、晶体待接管」并留日志，
     * 不假报完整成功、也不永久扫描。下一颗官方晶事件/区块事件仍可再次触发。
     */
    void retryCrystal(World world, Run run, int index, int attempt) {
        // 必须先释放互斥、保留 pending：下一轮 reconcile 会 inFlight.add(index)，不释放就永远 false、
        // 晶体待接管的重试直接死掉（review 抓到的 P1）。
        run.inFlight.remove(index);
        if (attempt + 1 >= MAX_ATTEMPTS) {
            run.pending.remove(index);
            warn("柱：" + world.getName() + " 第 " + index + " 根柱：柱体回读已通过并记 built，但连续 "
                    + (attempt + 1) + " 次没找到可搬的官方晶（标记为 false 的玩家晶不会动）；"
                    + "记为「柱体完成、晶体待接管」，等下一次结构晶事件。");
            return;
        }
        EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
        schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, RETRY_DELAY_TICKS,
                () -> reconcile(world, run, index, attempt + 1, "晶体待接管重试"),
                reason -> {
                    // 重排失败也要收尾：清掉 pending，避免一根柱永久卡在「待接管」又不重试。
                    run.pending.remove(index);
                    run.inFlight.remove(index);
                    warn("柱：" + world.getName() + " 第 " + index + " 根柱待接管重排失败：" + reason);
                });
    }

    /**
     * 一根柱收尾：晶体已搬到新槽 -> {@code built} 落盘 -> 才清旧晶。
     *
     * <p>顺序是硬约束：先删旧晶再搬，中途失败就是「柱上没晶」；{@code built} 没落盘就删，重启后会重跑整根。
     */
    private void complete(World world, Run run, int index, YungPillars.Layout layout,
                          UUID crystal) {
        markBuilt(run, index, "搬运完成", () -> {
            run.pending.remove(index);
            run.inFlight.remove(index);
            run.shields.remove(index);
            info("柱：" + world.getName() + " 第 " + index + " 根柱完成：晶体 " + crystal
                    + " 已搬到 (cx," + layout.crystalY() + ",cz) 的新晶座上方（同一实体）。");
            // then 跑在异步写盘线程上：读实体表必须回柱心区块的区域线程。
            EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
            schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, 0L,
                    () -> retireStale(world, run, index),
                    problem -> warn("柱：第 " + index + " 根柱的旧晶清理排不进去：" + problem));
        }, problem -> {
            warn("柱：" + world.getName() + " 第 " + index + " 根柱搬运成功但 built 落盘失败（" + problem
                    + "）；不记完成，等下一次区块/实体事件。");
            abandon(run, index);
        });
    }

    // ------------------------------------------------------------------ 分桶执行

    /** 一批「同一个区块、最多 1024 格」的读取/写入任务（键见 {@link YungPillars#pack}）。 */
    record Work(int chunkX, int chunkZ, List<Long> keys) {
    }

    /** 把一批坐标按区块分桶，每桶 ≤ {@link #MAX_CELLS_PER_TASK}。 */
    static List<Work> workOf(Iterable<Long> keys) {
        Map<Long, List<Long>> byChunk = new LinkedHashMap<>();
        for (long key : keys) {
            long chunk = ((long) (YungPillars.unpackX(key) >> 4) << 32)
                    | ((YungPillars.unpackZ(key) >> 4) & 0xffff_ffffL);
            byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>(64)).add(key);
        }
        List<Work> out = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> entry : byChunk.entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (long) entry.getKey();
            List<Long> all = entry.getValue();
            for (int start = 0; start < all.size(); start += MAX_CELLS_PER_TASK) {
                int end = Math.min(start + MAX_CELLS_PER_TASK, all.size());
                out.add(new Work(chunkX, chunkZ, List.copyOf(all.subList(start, end))));
            }
        }
        return List.copyOf(out);
    }

    /** 按蓝图的格分桶（写入阶段用）。 */
    static List<Work> workOf(YungPillars.Blueprint blueprint) {
        List<Long> keys = new ArrayList<>(blueprint.cells().size());
        for (YungPillars.Cell cell : blueprint.cells()) {
            keys.add(cell.key());
        }
        return workOf(keys);
    }

    /** 一批工作的处理方式（在拥有该区块的区域线程上调用）。 */
    @FunctionalInterface
    private interface WorkAction {
        void run(Work work);
    }

    /**
     * 把所有桶铺到各自区块的区域线程上跑完；最后一个完成时回调 {@code done}。
     *
     * <p>任一桶失败只报一次错并停手（不继续写别的桶），错误通过 {@code fail} 交给调用方收尾。
     */
    private void runWork(World world, Run run, List<Work> works, WorkAction action,
                         Runnable done, Consumer<String> fail) {
        if (works.isEmpty()) {
            done.run();
            return;
        }
        AtomicInteger remaining = new AtomicInteger(works.size());
        AtomicBoolean failed = new AtomicBoolean();
        for (Work work : works) {
            schedule(world, run, work.chunkX(), work.chunkZ(), 0L, () -> {
                if (failed.get()) {
                    return;
                }
                try {
                    action.run(work);
                } catch (RuntimeException error) {
                    if (failed.compareAndSet(false, true)) {
                        fail.accept(reason(error));
                    }
                    return;
                }
                if (remaining.decrementAndGet() == 0 && !failed.get()) {
                    done.run();
                }
            }, reason -> {
                if (failed.compareAndSet(false, true)) {
                    fail.accept(reason);
                }
            });
        }
    }

    /** Folia runDelayed 不接受零或负延迟；立即请求仍须排到下一 tick。 */
    static long regionDelay(long requested) {
        return Math.max(1L, requested);
    }

    /**
     * 把 {@code task} 排到拥有 {@code (chunkX, chunkZ)} 的区域线程上；区块先异步就绪。
     *
     * <p>在区域线程上开工前用 {@link Bukkit#isOwnedByCurrentRegion(World, int, int)} 逐一确认所有权：
     * 「排进去了」不等于「区域没变」，写方绝不跨区写。
     */
    private void schedule(World world, Run run, int chunkX, int chunkZ, long delay,
                          Runnable task, Consumer<String> fail) {
        if (closed.get() || run.stopped) {
            return; // teardown：状态本来就丢，不刷日志
        }
        if (!runtime.enabled()) {
            // 插件已失活而本轮还有在途步骤：必须通知调用方收尾（abandon），不能静默 return 卡住 inFlight。
            fail.accept("调度已停用（插件未启用），本轮收尾");
            return;
        }
        long epoch = run.epoch.get();
        // 过期 callback 只收尾一次：epoch 被顶替（且不是整轮 teardown）时通知调用方释放 inFlight，
        // 绝不让旧任务继续认领/写世界，也不让新操作被旧回调污染。
        AtomicBoolean expired = new AtomicBoolean();
        Runnable dropIfExpired = () -> {
            if (closed.get() || run.stopped) {
                return; // 世界卸载/关服：状态本来就丢，不刷日志
            }
            if (run.epoch.get() != epoch && expired.compareAndSet(false, true)) {
                fail.accept("调度已失效（epoch 变化），本轮收尾");
            }
        };
        try {
            world.getChunkAtAsync(chunkX, chunkZ).thenRun(() -> {
                if (closed.get() || run.stopped || !runtime.enabled() || run.epoch.get() != epoch) {
                    dropIfExpired.run();
                    return;
                }
                try {
                    runtime.region(world, chunkX, chunkZ, regionDelay(delay), () -> {
                        if (closed.get() || run.stopped || run.epoch.get() != epoch) {
                            dropIfExpired.run();
                            return;
                        }
                        if (!runtime.ownsRegion(world, chunkX, chunkZ)) {
                            fail.accept("区块 " + chunkX + "," + chunkZ + " 不属于当前区域，拒绝写入");
                            return;
                        }
                        task.run();
                    });
                } catch (RuntimeException refused) {
                    fail.accept("区域任务被拒绝：" + reason(refused));
                }
            }).exceptionally(error -> {
                fail.accept("区块加载失败：" + reason(error));
                return null;
            });
        } catch (RuntimeException refused) {
            fail.accept("无法请求区块：" + reason(refused));
        }
    }

    // ------------------------------------------------------------------ 世界侧工具

    private Run run(World world) {
        return runs.computeIfAbsent(world.getUID(), ignored -> {
            DustWastelandsGenerator generator = (DustWastelandsGenerator) world.getGenerator();
            long seed = world.getSeed();
            Run created = new Run(world.getUID(), seed, generator.field(seed));
            loadRegistry(created);
            return created;
        });
    }

    private void loadRegistry(Run run) {
        if (run.registryLoaded) {
            return;
        }
        synchronized (run.registryLock) {
            if (run.registryLoaded) {
                return;
            }
            try {
                run.registry = YungPillarStore.readRegistry(dataDir, run.worldId);
            } catch (Exception error) {
                warn("柱：读不了世界 " + run.worldId + " 的登记表（" + reason(error)
                        + "）；本次不搬迁任何晶体，请先修正该文件。");
                run.registry = YungPillarStore.Registry.empty();
            }
            run.registryLoaded = true;
            for (Map.Entry<Integer, UUID> entry : run.registry.crystals().entrySet()) {
                if (!isBuilt(run, entry.getKey())) {
                    run.pending.add(entry.getKey());
                }
            }
        }
    }

    /**
     * 登记表落盘：**单飞 + 永远写最新**。
     *
     * <p>两次修改（登记晶体、标记 built）可能挨得很近；如果把调用时刻的快照各自丢进异步池，
     * 落盘顺序就没有保证，旧快照会把 {@code built} 覆盖回去（重启后该柱会重新 pending、白跑一遍整根重铺）。
     * 这里改成「有一个写任务在跑时只更新待写快照，写完后自己再跑一轮」，所以最后一次落盘一定是最后的状态。
     */
    private void persistRegistry(Run run) {
        run.pendingRegistry.set(run.registry);
        if (!run.writing.compareAndSet(false, true)) {
            return;
        }
        scheduleRegistryWrite(run);
    }

    private void scheduleRegistryWrite(Run run) {
        try {
            runtime.async(() -> {
                while (true) {
                    long version = run.registryVersion.get();
                    try {
                        synchronized (run.registryLock) {
                            YungPillarStore.writeRegistry(dataDir, run.worldId, run.registry);
                            run.pendingRegistry.set(null);
                        }
                    } catch (Exception error) {
                        warn("柱：写登记表失败（" + reason(error) + "）：" + run.worldId);
                    }
                    if (run.registryVersion.get() == version) {
                        run.writing.set(false);
                        // 收尾竞争：置 false 之后可能刚好又有人改了登记表。
                        if (run.registryVersion.get() != version
                                && run.writing.compareAndSet(false, true)) {
                            continue;
                        }
                        return;
                    }
                }
            });
        } catch (RuntimeException refused) {
            run.writing.set(false);
            warn("柱：登记表写盘排不进异步池（" + reason(refused) + "）。");
        }
    }

    /**
     * 重铺前快照：把**并集足迹**（新模板 + 旧模板 + 原版 21³ 盒）的当前状态落盘，然后才允许写世界。
     *
     * <p>只备份新蓝图会漏掉旧柱露在外面的部分，所以这里按并集来。落盘失败就不写世界（一格未写）。
     */
    private void snapshotAsync(World world, Run run, int index, java.util.Set<Long> union,
                               Map<Long, String> before, Runnable then) {
        List<YungPillars.Cell> cells = new ArrayList<>(union.size());
        for (long key : union) {
            String current = before.get(key);
            if (current == null) {
                continue;
            }
            cells.add(new YungPillars.Cell(YungPillars.unpackX(key), YungPillars.unpackY(key),
                    YungPillars.unpackZ(key), current, YungPillars.Origin.VANILLA));
        }
        UUID jobId = UUID.randomUUID();
        EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
        try {
            runtime.async(() -> {
                try {
                    Path file = YungPillarStore.writeSnapshot(dataDir, run.worldId, jobId, cells);
                    info("柱：第 " + index + " 根柱的重铺前快照已落盘（" + cells.size()
                            + " 格）：" + file);
                } catch (Exception error) {
                    warn("柱：快照落盘失败（" + reason(error) + "），本次重铺中止，一格未写。");
                    abandon(run, index);
                    return;
                }
                schedule(world, run, spike.centerX() >> 4, spike.centerZ() >> 4, 0L, then,
                        reason -> {
                            warn("柱：快照之后的写入阶段排不进去：" + reason);
                            abandon(run, index);
                        });
            });
        } catch (RuntimeException refused) {
            warn("柱：快照任务排不进异步池（" + reason(refused) + "），本次重铺中止。");
            abandon(run, index);
        }
    }

    /**
     * {@link #verifyAndMaybeClaim} 的核心判定结果：当前布局模板是否逐格命中。
     *
     * @param total   模板里非空气的 TEMPLATE 格总数
     * @param matched 其中状态命中（或按 {@link YungPillars#shouldWrite} 允许跳过）的格数
     * @param firstBad 首个不符/缺读坐标的可读描述；全命中时为 {@code "无明细"}
     */
    record GeneratedVerdict(boolean complete, int matched, int total, String firstBad) {
    }

    /**
     * 纯判定：给定足迹里读到的状态，当前布局模板是否**逐格全部命中**。
     *
     * <p>这是 {@link #verifyAndMaybeClaim} 真正调用的核验步骤（不是另造的布尔函数）：只有完整匹配才允许
     * 落盘 {@code built} 并认领。旧布局、受损、玩家改动都会让某一格不符而整体不通过。
     */
    static GeneratedVerdict evaluateTemplate(YungPillars.Blueprint blueprint, Map<Long, String> states) {
        int total = 0;
        int matched = 0;
        String firstBad = null;
        for (YungPillars.Cell cell : blueprint.cells()) {
            if (cell.origin() != YungPillars.Origin.TEMPLATE
                    || "minecraft:air".equals(cell.state())) {
                continue;
            }
            total++;
            String actual = states.get(cell.key());
            if (actual == null) {
                if (firstBad == null) {
                    firstBad = "缺读 " + cell.x() + "," + cell.y() + "," + cell.z();
                }
            } else if (actual.equals(cell.state()) || !YungPillars.shouldWrite(cell, actual)) {
                matched++;
            } else if (firstBad == null) {
                firstBad = "首个不符 " + cell.x() + "," + cell.y() + "," + cell.z()
                        + " 期望 " + cell.state() + " 实际 " + actual;
            }
        }
        return new GeneratedVerdict(total > 0 && matched == total, matched, total,
                firstBad == null ? "无明细" : firstBad);
    }

    /** 纯函数：蓝图里「当前状态既不是目标、也不像我们/原版写的东西」的第一格。 */
    static String firstConflict(YungPillars.Blueprint blueprint, Map<Long, String> before) {
        StringBuilder samples = new StringBuilder();
        int found = 0;
        for (YungPillars.Cell cell : blueprint.cells()) {
            String current = before.get(cell.key());
            if (current == null) {
                appendSample(samples, found, cell.x(), cell.y(), cell.z(), "未读到");
                found++;
                if (found >= CONFLICT_SAMPLES) break;
                continue;
            }
            if (!YungPillars.acceptable(current, cell.state(), null)) {
                appendSample(samples, found, cell.x(), cell.y(), cell.z(), current);
                found++;
                if (found >= CONFLICT_SAMPLES) break;
            }
        }
        return found == 0 ? null : samples.toString();
    }

    private static void appendSample(StringBuilder out, int index, int x, int y, int z, String state) {
        if (index > 0) {
            out.append("；");
        }
        out.append(x).append(',').append(y).append(',').append(z).append(' ').append(state);
    }

    private String stateAt(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getBlockData().getAsString();
    }

    /** 状态串 → {@link BlockData}；模板状态只有几十种，缓存一次就够。 */
    private BlockData data(String state) {
        return runtime.blockData(state);
    }

    /**
     * 这一格是否属于「已建成、且当前状态仍等于蓝图」的受管柱体。
     *
     * <p>只对登记表里 {@code built} 的柱生效：还没建成的柱（预检中止、玩家冲突）不替玩家挡刀。
     */
    private boolean shielded(Run run, Block block) {
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(run.seed);
        for (int index = 0; index < spikes.size(); index++) {
            EndSpikes.Spike spike = spikes.get(index);
            if (Math.abs(block.getX() - spike.centerX()) > YungPillars.HALF_WIDTH
                    || Math.abs(block.getZ() - spike.centerZ()) > YungPillars.HALF_WIDTH) {
                continue;
            }
            if (!isBuilt(run, index)) {
                return false;
            }
            Map<Long, String> shield = shieldOf(run, index, spike);
            if (shield.isEmpty()) {
                return false;
            }
            String expected = shield.get(YungPillars.pack(block.getX(), block.getY(), block.getZ()));
            return expected != null && expected.equals(block.getBlockData().getAsString());
        }
        return false;
    }

    private Map<Long, String> shieldOf(Run run, int index, EndSpikes.Spike spike) {
        Map<Long, String> cached = run.shields.get(index);
        if (cached != null) {
            return cached;
        }
        try {
            YungPillars.Blueprint blueprint = YungPillars.blueprint(spike, run.seed, run.field);
            Map<Long, String> built = new ConcurrentHashMap<>(blueprint.templateCells() * 2);
            for (YungPillars.Cell cell : blueprint.cells()) {
                // 只保护「我们真的放下去的模板实心格」：地形修补格是天然末地石，龙的正常破坏不受影响。
                if (cell.origin() != YungPillars.Origin.TEMPLATE || "minecraft:air".equals(cell.state())) {
                    continue;
                }
                built.put(cell.key(), cell.state());
            }
            run.shields.put(index, built);
            return built;
        } catch (RuntimeException error) {
            warn("柱：第 " + index + " 根柱的爆炸保护名单构建失败：" + reason(error));
            run.shields.put(index, Map.of());
            return Map.of();
        }
    }

    private static EnderCrystal findCrystal(World world, UUID uuid) {
        org.bukkit.entity.Entity entity = world.getEntity(uuid);
        return entity instanceof EnderCrystal crystal ? crystal : null;
    }

    private static DragonBattle.RespawnPhase respawnPhase(World world) {
        try {
            DragonBattle battle = world.getEnderDragonBattle();
            return battle == null ? null : battle.getRespawnPhase();
        } catch (RuntimeException notReady) {
            return null;
        }
    }

    private static boolean previouslyKilled(World world) {
        try {
            DragonBattle battle = world.getEnderDragonBattle();
            return battle != null && battle.hasBeenPreviouslyKilled();
        } catch (RuntimeException notReady) {
            return false;
        }
    }

    /** 世界加载/启用时的一次性接收：读登记表，并对**已加载**的柱心区块补一次有界模板回读。 */
    private void adopt(World world, String why) {
        if (closed.get()) {
            return;
        }
        if (!manages(world)) {
            return;
        }
        Run run = run(world);
        loadRegistry(run);
        if (!run.pending.isEmpty()) {
            info("柱：" + world.getName() + "（" + why + "）有 " + run.pending.size()
                    + " 根柱待对账，等各自的区块加载或实体事件再处理。");
        }
        run.shields.clear();
        // 插件启用/BetterEnd 世界已加载时，柱心区块可能早已加载（不会再发 ChunkLoad）：
        // 只对**已经加载**的柱心区块补一次回读认领，绝不 getChunkAtAsync 强制加载未加载的区块。
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(run.seed);
        for (int index = 0; index < spikes.size(); index++) {
            if (isBuilt(run, index)) {
                continue;
            }
            EndSpikes.Spike spike = spikes.get(index);
            if (!world.isChunkLoaded(spike.centerX() >> 4, spike.centerZ() >> 4)) {
                continue;
            }
            verifyAndMaybeClaim(world, run, index, why);
        }
    }

    // ------------------------------------------------------------------ 已有世界的恢复（公开服务）

    /**
     * 只读诊断：逐柱给出「新布局完整 / 部分 / 原版 / 无证据 / 玩家改动」，并算一个确认令牌。
     *
     * <p>读的是**并集足迹**（新模板 + 旧模板 + 原版 21³ 盒），按区块分桶、每桶 ≤1024 格、在拥有该区块的
     * 区域线程上读；不写世界、不改登记表。回调在全局区域线程上送达（命令层再自己转投玩家）。
     */
    public void preview(World world, Consumer<YungPillarRecovery.Preview> done) {
        if (world == null || !manages(world)) {
            deliver(done, emptyPreview(world, "不是本插件的末地世界（需要末地维度 + BetterEnd 生成器）"));
            return;
        }
        Run run = run(world);
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(run.seed);
        List<YungPillars.Blueprint> nows = new ArrayList<>(spikes.size());
        List<YungPillars.Blueprint> legacies = new ArrayList<>(spikes.size());
        List<java.util.Set<Long>> vanillas = new ArrayList<>(spikes.size());
        java.util.Set<Long> all = new LinkedHashSet<>();
        try {
            for (EndSpikes.Spike spike : spikes) {
                YungPillars.Blueprint now = YungPillars.blueprint(spike, run.seed, run.field);
                YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(spike, run.seed, run.field);
                nows.add(now);
                legacies.add(legacy);
                vanillas.add(YungPillars.vanillaCells(spike, run.field));
                all.addAll(YungPillarRecovery.unionKeys(spike, run.seed, now, legacy,
                        world.getMinHeight()));
            }
        } catch (RuntimeException error) {
            deliver(done, emptyPreview(world, "蓝图失败：" + reason(error)));
            return;
        }
        Map<Long, String> read = new ConcurrentHashMap<>(all.size() * 2);
        runWork(world, run, workOf(all), work -> {
            for (long key : work.keys()) {
                read.put(key, stateAt(world, YungPillars.unpackX(key), YungPillars.unpackY(key),
                        YungPillars.unpackZ(key)));
            }
        }, () -> {
            List<YungPillarRecovery.PillarReport> reports = new ArrayList<>(spikes.size());
            for (int index = 0; index < spikes.size(); index++) {
                reports.add(YungPillarRecovery.classify(index, spikes.get(index), nows.get(index),
                        legacies.get(index), vanillas.get(index), read));
            }
            List<Integer> adoptable = new ArrayList<>();
            for (YungPillarRecovery.PillarReport report : reports) {
                if (report.adoptable()) {
                    adoptable.add(report.index());
                }
                info("柱 preview：" + previewLine(run, report));
            }
            YungPillarRecovery.Preview preview = new YungPillarRecovery.Preview(world.getUID(),
                    YungPillarStore.LAYOUT, List.copyOf(reports),
                    YungPillarRecovery.token(world.getUID(), run.seed, run.registry,
                            List.copyOf(reports), read), "");
            deliver(done, preview);
        }, problem -> deliver(done, emptyPreview(world, "读取失败：" + problem)));
    }

    /**
     * 只在原版复活空闲时受理恢复；龙战状态必须在中心区域线程读取。
     *
     * <p>本版没有命令入口：{@code /betterend upgrade restore} 已移除，柱体不会被任何指令接管。
     * 这里保留柱侧的执行能力（备份 → 重铺 → 回读 → 搬晶），但当前无调用方。
     */
    public void restore(World world, String token, Consumer<YungPillarRecovery.Outcome> done) {
        if (world == null || !manages(world)) {
            deliver(done, new YungPillarRecovery.Outcome(false, "不是受管末地世界", List.of(), List.of()));
            return;
        }
        Run run = run(world);
        schedule(world, run, 0, 0, 0L, () -> {
            if (respawnPhase(world) != DragonBattle.RespawnPhase.NONE) {
                deliver(done, new YungPillarRecovery.Outcome(false,
                        "原版复活正在进行或状态不可读；本次不启动柱体恢复", List.of(), List.of()));
                return;
            }
            restorePreview(world, token, done);
        }, problem -> deliver(done, new YungPillarRecovery.Outcome(false, problem, List.of(), List.of())));
    }

    /**
     * 显式恢复：按 preview 的令牌接管「有完整足迹证据」的柱——备份（并集足迹）→ 重铺 → 逐格回读 →
     * {@code built} 落盘 → 搬同一颗官方晶 → 清旧晶。
     *
     * <p>受理 {@code OURS}/{@code PARTIAL}/{@code LEGACY} 且没有玩家方块的柱：{@code LEGACY} 是
     * 「已生成的 0.1.26 旧布局柱」，用迁移蓝图（新模板 + 旧模板残件按地形还原）升到 70/3 并提交新登记表。
     * {@code VANILLA}（纯原版/被清空）与 {@code NO_EVIDENCE} 一律拒绝，原因写进
     * {@link YungPillarRecovery.Outcome#details()}。
     * 令牌按「世界 + 布局版本 + 可接管柱集合」重算并逐字比较，preview 之后足迹变了就拒绝。
     */
    private void restorePreview(World world, String token, Consumer<YungPillarRecovery.Outcome> done) {
        preview(world, preview -> {
            if (!preview.note().isEmpty()) {
                deliver(done, new YungPillarRecovery.Outcome(false, preview.note(), List.of(),
                        List.of()));
                return;
            }
            List<String> details = new ArrayList<>();
            for (YungPillarRecovery.PillarReport report : preview.reports()) {
                details.add(describe(report));
            }
            List<Integer> adoptable = preview.adoptable();
            if (adoptable.isEmpty()) {
                deliver(done, new YungPillarRecovery.Outcome(false,
                        "没有可恢复的柱：逐柱缺证（完全原版/空气）或足迹里有玩家方块；"
                                + "详情见 details",
                        List.of(), List.copyOf(details)));
                return;
            }
            String given = token == null ? "" : token.trim();
            if (!given.equals(preview.token())) {
                deliver(done, new YungPillarRecovery.Outcome(false,
                        "token 不匹配：请重新 preview 后使用新 token（preview 之后足迹变化会使旧 token 失效）",
                        List.of(), List.copyOf(details)));
                return;
            }
            Run run = run(world);
            List<Integer> queued = new ArrayList<>();
            for (int index : adoptable) {
                if (!run.inFlight.add(index)) {
                    details.add("#" + index + " 已有重铺任务，本次不重复启动");
                    continue;
                }
                EndSpikes.Spike spike = EndSpikes.forSeed(run.seed).get(index);
                run.pending.add(index);
                queued.add(index);
                rebuild(world, run, index, spike, null, 0, true);
            }
            info("柱：已受理恢复 " + queued.size() + " 根柱（token " + given + "），开始按批重铺。");
            deliver(done, new YungPillarRecovery.Outcome(!queued.isEmpty(),
                    "已受理 " + queued.size() + " 根柱，正在按批恢复；完成与否看日志与下一次 preview",
                    List.copyOf(queued), List.copyOf(details)));
        });
    }

    /** 一句话描述一根柱的检查结果（日志与命令层共用）。 */
    public static String describe(YungPillarRecovery.PillarReport report) {
        return "#" + report.index() + " (" + report.centerX() + "," + report.centerZ() + ") H="
                + report.height() + " tier=" + report.tier() + " " + report.status()
                + " 模板命中 " + report.matched() + "/" + report.templateCells()
                + " 缺读 " + report.missing() + " 不符 " + report.mismatched()
                + " 玩家方块 " + report.foreign() + " 旧布局命中 " + report.legacyMatched()
                + " 座位 y=" + report.seatY() + " 晶体 y=" + report.crystalY();
    }

    /**
     * preview 日志用：在 {@link #describe} 基础上补登记表实况（布局版本 / built / 登记晶 UUID / 待处理）。
     *
     * <p>用来核对云端实际装载的布局版本与登记文件，避免「preview 说能接管、但登记表其实是旧布局或没有 built」。
     */
    static String previewLine(Run run, YungPillarRecovery.PillarReport report) {
        YungPillarStore.Registry registry = run.registry;
        UUID crystal = registry.crystal(report.index());
        return describe(report) + " | 登记 layout=" + registry.layout()
                + " built=" + (registry.built().contains(report.index()) ? "是" : "否")
                + " 当前布局=" + (isBuilt(run, report.index()) ? "是" : "否")
                + " 登记晶=" + (crystal == null ? "无" : crystal)
                + " pending=" + (run.pending.contains(report.index()) ? "是" : "否");
    }

    private YungPillarRecovery.Preview emptyPreview(World world, String note) {
        UUID worldId = world == null ? new UUID(0L, 0L) : world.getUID();
        long seed = world == null ? 0L : world.getSeed();
        return new YungPillarRecovery.Preview(worldId, YungPillarStore.LAYOUT, List.of(),
                YungPillarRecovery.token(worldId, seed, YungPillarStore.LAYOUT, List.of(),
                        java.util.Set.of()), note);
    }

    private static java.util.Set<Integer> builtOf(Run run) {
        return java.util.Set.copyOf(run.registry.built());
    }

    private void deliver(Consumer<YungPillarRecovery.Preview> done,
                         YungPillarRecovery.Preview preview) {
        if (done == null) {
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> done.accept(preview));
        } catch (RuntimeException refused) {
            done.accept(preview);
        }
    }

    private void deliver(Consumer<YungPillarRecovery.Outcome> done,
                         YungPillarRecovery.Outcome outcome) {
        if (done == null) {
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> done.accept(outcome));
        } catch (RuntimeException refused) {
            done.accept(outcome);
        }
    }

    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    private void info(String message) {
        runtime.info(message);
    }

    private void warn(String message) {
        runtime.warn(message);
    }
}
