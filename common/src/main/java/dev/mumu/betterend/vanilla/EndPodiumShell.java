package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.AtomicFiles;
import dev.mumu.betterend.DustWastelandsGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 把 YUNG 的 {@code tower_initial} 中心祭坛接到世界里：**服务器运行期**接线层。
 *
 * <h2>它只在这两个条件同时成立时动手</h2>
 * <ol>
 *   <li>世界是末地维度，而且生成器是本插件的 {@link DustWastelandsGenerator}（别人的末地不管）；</li>
 *   <li>{@code DragonBattle#getEndPortalLocation()} 已经非空。</li>
 * </ol>
 * 第二条是硬约束，不是保守：MU 26.3 的 {@code EnderDragonFight#spawnExitPortal} 在位置为空时用
 * <b>原点那一列的高度图</b>算返回门坐标。塔的钟柱正好在那一列上，先进 worldgen 就等于把高度图抬高
 * 二十几格，返回门会被放到塔顶。所以这里<b>不参与区块生成</b>（生成器里没有一行调用本类），只在
 * 门已经确定之后按门的位置施工——底面 {@code portalY-2}，XZ 居中于真实的门坐标。
 *
 * <h2>触发的都是真事件，没有常驻扫描</h2>
 * <ul>
 *   <li>{@link WorldLoadEvent}：世界加载（含插件启用前就已加载的世界，见 {@link #start()}）；</li>
 *   <li>{@link ChunkLoadEvent}：只认原点区块，返回门在那里；</li>
 *   <li>{@link EntitySpawnEvent}：末影龙生成；</li>
 *   <li>{@link EntityDeathEvent}：末影龙死亡 → 原版重建祭坛之后做一次有界局部修复（还没建成的世界
 *       则延迟再试一轮，因为击杀也是返回门坐标的来源之一）。</li>
 * </ul>
 * 每次触发都先抢 {@link Stage#IDLE}，所以同一次运行里重复事件不会重复施工；位置读不到就 20 tick 后
 * 再读，最多 5 次，之后退回 IDLE 等后续事件——不无限重试。完成/失败状态落在世界 PDC（{@code placed}），
 * 所以重启、普通区块加载都不会重铺一遍。
 *
 * <h2>两件「不做什么」</h2>
 * <ul>
 *   <li><b>不搬龙蛋</b>：{@code setDragonKilled} 用 {@code MOTION_BLOCKING} 高度图放蛋，塔顶因此变成
 *       蛋的落点；归位要写保护区（返回门/基岩柱/水晶净空），所以只记一条日志说明差异，见
 *       {@link #explainEggLanding}。</li>
 *   <li><b>不替玩家挡龙</b>：只把「本模板实心且当前状态仍等于模板」的格从 {@code EntityExplodeEvent}
 *       的破坏列表里剔除（{@link EndPodiumShield}），不取消事件、不改 {@code dragon_immune} 或
 *       gamerule、不禁玩家拆放。</li>
 * </ul>
 *
 * <h2>线程与区块</h2>
 * 所有世界读写都在「拥有该区块的区域线程」上，区块先用 {@code World#getChunkAtAsync} 就绪再排任务
 * （{@link #region} 是唯一入口，慢 IO 回来后的续跑与完成标记也走它）；不硬编码 Folia 的区域大小、
 * 不在区域线程上阻塞等别的区域，炸弹事件里跨区域的方块一律不读（{@code isOwnedByCurrentRegion}）。
 * 也不用 {@code addPluginChunkTicket}——那样一旦忘记移除就会泄掉 ticket；异步加载的临时 ticket 在读完
 * 后由服务端自己释放。施工规则本身在 {@link EndPodiumBuild} 里，那层不碰 Bukkit，能跑 JUnit。
 */
public final class EndPodiumShell implements Listener {

    /**
     * 末影龙死亡/重生后要等的 tick 数。
     *
     * <p>不是随手取的：{@code EnderDragon#tickDeath} 播完死亡动画（约 200 tick）才调
     * {@code EnderDragonFight#setDragonKilled}，那里才会 {@code spawnExitPortal(true)} 并放龙蛋。
     * 早于这个时刻去调查坛，看到的还是击杀前的世界，所以修复与「死亡后再触发」都要排在它之后。
     */
    static final long AFTER_DEATH_TICKS = 220L;

    /** 每个世界允许的 {@link Stage#FAILED} 自动重入次数（有界，不是常驻重试）。 */
    static final int MAX_REENTRIES = 2;

    /** 龙蛋的方块 id 与目标状态串（归位时用同一份，避免两处不一致）。 */
    private static final String DRAGON_EGG_ID = "minecraft:dragon_egg";
    private static final String DRAGON_EGG_STATE = "minecraft:dragon_egg";

    /** 一个世界的施工阶段。包私有：单测要直接驱动 {@link #deathAction} / {@link #markKey}。 */
    enum Stage {
        /** 还没开始；只有它能被事件抢到。 */
        IDLE,
        /** 布局已定、预检/快照/净空/核心/装饰进行中。 */
        BUILDING,
        /** 世界里已有完成标记，本进程内不再动它。 */
        PLACED,
        /** 本次运行内已中止（预检拒绝、快照失败、区块加载失败……），不再自动重试。 */
        FAILED
    }

    /** 一个受管世界的运行内状态；只在世界 PDC 的区域线程上改（`stopped` 例外，它是给卸载用的）。 */
    private static final class Run {
        final AtomicReference<Stage> stage = new AtomicReference<>(Stage.IDLE);
        final AtomicBoolean repairing = new AtomicBoolean();
        /** FAILED 之后允许的自动重入次数（有界：不是常驻重试）。 */
        final AtomicInteger reentries = new AtomicInteger();
        /** 龙蛋改放只做一次，别每次击杀都念叨。 */
        final AtomicBoolean eggMoved = new AtomicBoolean();
        volatile EndPodiumBuild build;
        /** 当前这个世界的布局；建成后重启也从 PDC 的布局标记恢复它（龙的保护名单要用）。 */
        volatile EndPodiumTemplate.Plan plan;
        /** 这份布局的持久化形式（版本 + 种子 + 门坐标）。 */
        volatile EndPodiumTemplate.Layout layout;
        /** {@link #plan} 对应的保护名单（模板实心格 → 目标状态串），懒构建一次。 */
        volatile Map<Long, String> shield;
        volatile boolean stopped;
    }

    /** 龙死亡后该做什么（生产路径的纯决策，单测直接调）。 */
    enum DeathAction {
        /** 整楼已建成：只做一次有界局部修复。 */
        REPAIR,
        /** 还没建成：这次击杀可能刚刚算出返回门坐标，延迟后再试一轮。 */
        RETRY_LATER
    }

    /**
     * 龙死亡的分支决策。
     *
     * <p>{@link Stage#PLACED} 之外（IDLE / BUILDING / FAILED）一律只做「延迟后再试」：
     * 击杀是原版重建祭坛的时刻（见 {@link #AFTER_DEATH_TICKS}），也可能第一次把区块拉起来。
     * 已经 FAILED 的世界再试一次也是安全的——预检会重新判定，真有问题仍然一格不写。
     */
    static DeathAction deathAction(Stage stage) {
        return stage == Stage.PLACED ? DeathAction.REPAIR : DeathAction.RETRY_LATER;
    }

    /** 世界 PDC 里的完成状态键（{@code placed}）。旧世界没有它，或布局版本不是 2，都不算已迁移。 */
    public static final String KEY_STATE = "end_podium_state";
    /** 世界 PDC 里的布局键：值 = {@link EndPodiumTemplate#encode}（版本 + 世界种子 + 门坐标）。 */
    public static final String KEY_LAYOUT = "end_podium_layout";

    /**
     * 施工状态键 → 世界 PDC 的 {@link NamespacedKey}。**未知键显式拒绝**：把任意字符串落到
     * {@code end_podium_layout} 上会静默写坏完成标记，宁可抛出来。
     */
    static NamespacedKey markKey(Plugin plugin, String key) {
        return switch (key) {
            case EndPodiumBuild.MARK_STATE -> new NamespacedKey(plugin, KEY_STATE);
            case EndPodiumBuild.MARK_LAYOUT -> new NamespacedKey(plugin, KEY_LAYOUT);
            default -> throw new IllegalArgumentException("未知的中心祭坛 PDC 键：" + key);
        };
    }

    private final Plugin plugin;
    private final Path dataDir;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    /**
     * 世界 PDC 的**只读**镜像缓存：PDC 底层是无锁 {@code HashMap}，只允许在全局线程上碰，所以启动时
     * （插件启用线程 = 全局线程）读一次缓存下来，运行期只在启动 {@link #start()} 里刷新。区域线程
     * 永远只读原子文件 + 这份缓存，不再直接读 PDC。
     */
    private final Map<UUID, String> pdcLayoutCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pdcPlacedCache = new ConcurrentHashMap<>();
    private final Report report = new Report();

    /**
     * @param plugin  插件实例（调度、日志、PDC 命名空间都用它）
     * @param dataDir 插件数据目录；原方块快照落在它下面的 {@code podium-backups/}
     */
    public EndPodiumShell(Plugin plugin, Path dataDir) {
        this.plugin = plugin;
        this.dataDir = dataDir;
    }

    /**
     * 启动：预热模板（缺模板/方块状态解析不了就在这里炸，而不是等区块长到中心），并给「插件启用前
     * 就已经加载」的世界补一次触发。按 {@code paper-plugin.yml} 的 STARTUP 加载顺序这一步通常是空表，
     * 但 {@code /reload} 之类会让它非空，所以照做一次，且只做一次——不是常驻扫描。
     */
    public void start() {
        EndPodiumTemplate.preload();
        for (World world : List.copyOf(Bukkit.getWorlds())) {
            cachePdc(world);
            trigger(world, "插件启用前已加载");
        }
    }

    /** 关闭：让所有在途回调原地失效，并丢掉运行内状态（完成标记在世界 PDC 里，不依赖内存）。 */
    public void shutdown() {
        for (Run run : runs.values()) {
            run.stopped = true;
            EndPodiumBuild build = run.build;
            if (build != null) {
                build.stop();
            }
        }
        runs.clear();
    }

    // ------------------------------------------------------------------ 事件

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        cachePdc(event.getWorld());
        trigger(event.getWorld(), "世界加载");
    }

    /**
     * 在**全局线程**上把世界 PDC 的两个标记读进缓存；运行期其余路径只读缓存与原子文件。
     * 世界卸载时一并丢掉（重载会重新读一次）。
     */
    private void cachePdc(World world) {
        if (!manages(world)) {
            return;
        }
        UUID id = world.getUID();
        try {
            String layout = pdcLayout(world);
            if (layout != null) {
                pdcLayoutCache.put(id, layout);
            }
            pdcPlacedCache.put(id, EndPodiumBuild.PLACED.equals(pdcState(world)));
        } catch (RuntimeException error) {
            report.warning("中心祭坛：读世界 PDC 镜像失败（不影响原子文件路径）：" + reason(error));
        }
    }

    /** 世界卸载：在途任务一律失效，避免它们在世界没了之后还去排下一批。 */
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID unloaded = event.getWorld().getUID();
        pdcLayoutCache.remove(unloaded);
        pdcPlacedCache.remove(unloaded);
        Run run = runs.remove(unloaded);
        if (run == null) {
            return;
        }
        run.stopped = true;
        EndPodiumBuild build = run.build;
        if (build != null) {
            build.stop();
        }
    }

    /** 只认返回门所在的原点区块；其余区块的加载与中心无关，不触发任何判定。 */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (chunk.getX() != 0 || chunk.getZ() != 0) {
            return;
        }
        trigger(event.getWorld(), "中心区块加载");
    }

    @EventHandler
    public void onDragonSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (!manages(world)) {
            return;
        }
        Run run = runs.get(world.getUID());
        if (run != null && deathAction(run.stage.get()) == DeathAction.REPAIR) {
            scheduleRepair(world, run, "末影龙重生");
            return;
        }
        trigger(world, "末影龙生成");
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (!manages(world)) {
            return;
        }
        Run run = runs.get(world.getUID());
        Stage stage = run == null ? Stage.IDLE : run.stage.get();
        if (deathAction(stage) == DeathAction.REPAIR) {
            scheduleRepair(world, run, "末影龙死亡");
            return;
        }
        // 还没建过（或这次没建完）：击杀是返回门坐标的来源之一，等原版走完死亡流程再试一轮。
        // 旧回调只能作用于它当初看到的那个 Run——世界卸载又重载会换一个新 Run，绝不能借用旧的再挂一份。
        Run captured = run;
        region(world, 0, 0, AFTER_DEATH_TICKS, () -> {
            Run current = runs.get(world.getUID());
            if (captured != null && (captured.stopped || current != captured)) {
                return;
            }
            if (captured == null && current != null) {
                return;
            }
            trigger(world, "末影龙死亡后返回门已确定");
        });
    }

    /**
     * 龙的破坏列表保护只在「正在施工」与「已建成」两个阶段生效。
     *
     * <p>这不是多余的保险，而是真的会误伤：预检因为玩家方块而中止（{@link Stage#FAILED}）的世界里，
     * 内存里仍然留着一份布局，缓存过的名单也还在——只看「有没有布局/名单」的话，玩家放在同坐标的
     * 方块就会被当成我们建的而免于龙拆。{@link Stage#IDLE}/{@link Stage#LOCATING} 同理：那时世界
     * 里一格都还不是我们放的。
     *
     * <p>生产路径（{@link #onDragonExplode}）与测试用的是同一个方法，不是两套规则。
     */
    static boolean shieldsDragon(Stage stage) {
        return stage == Stage.BUILDING || stage == Stage.PLACED;
    }

    /**
     * 逐格判定：阶段门禁 + 保护名单命中 + 当前状态仍等于模板。事件的每一次 {@code removeIf} 判定都走它。
     *
     * @param currentState 该格当前状态串；调用方必须先确认这一格属于当前区域（{@code isOwnedByCurrentRegion}）
     */
    static boolean shieldsDragon(Stage stage, Map<Long, String> shield, int x, int y, int z,
                                String currentState) {
        return shieldsDragon(stage) && EndPodiumShield.shields(shield, x, y, z, currentState);
    }

    /**
     * 末影龙撞墙：原版 {@code EnderDragon#checkWalls} 把龙身撞到的方块收进 {@code EntityExplodeEvent}
     * 的列表一起拆掉（{@code end_stone_bricks}/楼梯/台阶/砖墙/钟都不在 {@code DRAGON_IMMUNE} 里），
     * 刚建好的塔会被它自己啃出洞。
     *
     * <p>这里**只从方块列表里剔除「本模板实心、且当前方块类型仍等于模板」的格**：不取消事件、不改
     * {@code dragon_immune} 标签或 gamerule、不禁玩家拆/放、不补整楼。施工期已经写下的格自动被保护
     * （判据是当前状态），还没写的格是天然地形，本来就不该替它挡刀。
     *
     * <p>前两道门：世界没被跟踪/已停掉直接不管；阶段必须是 {@link Stage#BUILDING} 或
     * {@link Stage#PLACED}（见 {@link #shieldsDragon(Stage)}）——中止过的世界哪怕内存里还有布局，
     * 也不替玩家挡龙。
     *
     * <p>Folia：事件在龙所在区域的线程上跑，列表里可能混进别区域的方块。**跨区域的一律不读也不动**
     * （{@link Bukkit#isOwnedByCurrentRegion(org.bukkit.block.Block)}），不假设这几格同属一个区域。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDragonExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (!manages(world)) {
            return;
        }
        Run run = runs.get(world.getUID());
        if (run == null || run.stopped) {
            return;
        }
        Stage stage = run.stage.get();
        if (!shieldsDragon(stage)) {
            return; // 还没定位/已中止：世界里没有一格是我们的，别替玩家挡龙
        }
        Map<Long, String> shield = shieldOf(world, run);
        if (shield.isEmpty()) {
            return;
        }
        event.blockList().removeIf(block -> {
            if (!Bukkit.isOwnedByCurrentRegion(block)) {
                return false;
            }
            return shieldsDragon(stage, shield, block.getX(), block.getY(), block.getZ(),
                    block.getBlockData().getAsString());
        });
    }

    /** 保护名单：懒构建一次；当前阶段不该保护时就是空名单（缓存在手也不外泄给中止过的世界）。 */
    private Map<Long, String> shieldOf(World world, Run run) {
        if (!shieldsDragon(run.stage.get())) {
            return Map.of();
        }
        Map<Long, String> shield = run.shield;
        if (shield != null) {
            return shield;
        }
        EndPodiumTemplate.Plan plan = planOf(world, run);
        if (plan == null) {
            return Map.of();
        }
        Map<Long, String> built = EndPodiumShield.of(plan);
        run.shield = built;
        return built;
    }

    /**
     * 这个世界的布局：施工中直接用内存里那份；重启后从世界 PDC 的布局标记恢复（版本 + 种子 + 门坐标，
     * 是我们自己写成功的唯一证据，不猜、也不按地形重算）。
     *
     * <p>阶段不在 {@link #shieldsDragon(Stage)} 里就返回 {@code null}：中止过的世界内存中的布局
     * 不能再拿去保护玩家方块。
     */
    private EndPodiumTemplate.Plan planOf(World world, Run run) {
        if (!shieldsDragon(run.stage.get())) {
            return null;
        }
        EndPodiumTemplate.Plan plan = run.plan;
        if (plan != null) {
            return plan;
        }
        Optional<EndPodiumTemplate.Layout> stored = layout(world);
        if (stored.isEmpty()) {
            return null;
        }
        plan = stored.get().plan();
        run.layout = stored.get();
        run.plan = plan;
        return plan;
    }

    /**
     * 读本世界的持久化布局：**权威是插件目录里的原子文件**，PDC 只作兼容读取。
     *
     * <p>**只认版本 2**（{@link EndPodiumTemplate#decode} 会拒绝旧格式）：读到旧串就是「旧世界未迁移」，
     * 返回空并告警，绝不当成新布局继续用。
     */
    public Optional<EndPodiumTemplate.Layout> layout(World world) {
        if (!manages(world)) {
            return Optional.empty();
        }
        String raw = null;
        Path file = layoutFile(world);
        try {
            if (Files.isRegularFile(file)) {
                raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException error) {
            report.warning("中心祭坛：读布局文件 " + file + " 失败：" + reason(error) + "；改读 PDC 镜像。");
        }
        if (raw == null || raw.isEmpty()) {
            raw = pdcLayoutCache.get(world.getUID());
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(EndPodiumTemplate.decode(raw));
        } catch (IllegalArgumentException error) {
            report.warning("中心祭坛：布局标记 '" + raw + "' 不是本版支持的布局（"
                    + error.getMessage() + "），按「旧世界未迁移」处理；请走显式升级入口。");
            return Optional.empty();
        }
    }

    /**
     * 完成标记的读判定：**输入只有原子文件与启动缓存**，没有任何 PDC 通道。
     *
     * <p>这是本类所有运行期读标记的统一出口（{@code WorldMarker#read} 直接用它）。世界 PDC 底层是无锁
     * {@code HashMap}，只允许全局线程碰，所以区域线程上**永远不该**出现 PDC 读；把判定收成这个纯函数
     * 之后，「不触 PDC」就从约定变成了签名本身（{@code EndPodiumShellTest} 钉住）。
     *
     * @param placedFile   {@code <worldUUID>.placed} 是否存在
     * @param cachedPlaced 启动时缓存的 PDC 镜像是否为 placed；{@code null} 表示没有缓存
     */
    static String readStateMark(boolean placedFile, Boolean cachedPlaced) {
        return placedFile || Boolean.TRUE.equals(cachedPlaced) ? EndPodiumBuild.PLACED : null;
    }

    /**
     * 布局标记的读判定：文件内容优先，启动缓存兜底；两者都空就是「没有布局」。
     * 同样是纯函数，输入里没有 PDC（见 {@link #readStateMark}）。
     */
    static String readLayoutMark(String fileText, String cachedLayout) {
        if (fileText != null && !fileText.trim().isEmpty()) {
            return fileText.trim();
        }
        return cachedLayout == null || cachedLayout.trim().isEmpty() ? null : cachedLayout.trim();
    }

    /**
     * 「世界里已标记 placed，但没有可用的 v2 布局」——这是**旧版/损坏**的现场，不是新世界。
     *
     * <p>必须拒绝：照「没有布局」处理会按地形重算一套新锚点并写进文件，等于把一座已经交付过的楼
     * 悄悄换成另一套坐标，还挂着 placed 冒充完成。判据只看「placed 有 / 布局能不能解析」，
     * 与谁写的 placed 无关（旧的 {@code state=placed} + {@code layout="x,y,z"} 正是命中这一条）。
     */
    static boolean legacyPlacedWithoutLayout(boolean placed, String rawLayout) {
        if (!placed) {
            return false;
        }
        if (rawLayout == null || rawLayout.isBlank()) {
            return true;
        }
        try {
            EndPodiumTemplate.decode(rawLayout);
            return false;
        } catch (IllegalArgumentException legacyOrCorrupt) {
            return true;
        }
    }

    /** 世界里的布局原始串（文件优先、PDC 兼容镜像兜底）；没有就是 {@code null}。**不做解析判定**。 */
    private String layoutRaw(World world) {
        Path file = layoutFile(world);
        try {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (IOException error) {
            report.warning("中心祭坛：读布局文件 " + file + " 失败：" + reason(error));
        }
        return pdcLayoutCache.get(world.getUID());
    }

    /** 本世界是否已经建成（原子文件存在，或启动时缓存的 PDC 镜像为 placed）。 */
    private boolean placed(World world) {
        return Files.isRegularFile(stateFile(world))
                || Boolean.TRUE.equals(pdcPlacedCache.get(world.getUID()));
    }

    /**
     * 已建成的布局；**只有** 世界 PDC 标记为 {@code placed} 且布局版本匹配时才返回。
     *
     * <p>这是给建筑四晶复活用的唯一入口：构建中、已中止、旧版本布局、非受管世界一律空，
     * 调用方必须处理空值（不启动复活），不能拿「算出来的布局」替代已完工证据。
     */
    public Optional<EndPodiumTemplate.Plan> placedPlan(World world) {
        if (!manages(world) || !placed(world)) {
            return Optional.empty();
        }
        Optional<EndPodiumTemplate.Layout> stored = layout(world);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(stored.get().plan());
    }

    /**
     * 本世界**应该**建成什么样：纯地形场顶块 S → 楼板 → 门环。不写世界、不需要区块加载。
     *
     * <p>已持久化布局的世界直接返回那份（重启后固定布局，不因玩家改地形或配置热更重算）；
     * 其余情况按当前纯地形场算一遍，供预览与显式升级使用。
     */
    public Optional<EndPodiumTemplate.Plan> plannedPlan(World world) {
        if (!manages(world)) {
            return Optional.empty();
        }
        Optional<EndPodiumTemplate.Layout> stored = layout(world);
        if (stored.isPresent()) {
            return Optional.of(stored.get().plan());
        }
        int surfaceTop = surfaceTop(world, 0, 0);
        if (surfaceTop == Integer.MIN_VALUE) {
            return Optional.empty();
        }
        return Optional.of(EndPodiumTemplate.planAtSurface(surfaceTop));
    }

    // ------------------------------------------------------------------ 状态机

    private void trigger(World world, String why) {
        if (!manages(world)) {
            return;
        }
        Run run = runs.computeIfAbsent(world.getUID(), ignored -> new Run());
        if (run.stopped) {
            return;
        }
        if (!run.stage.compareAndSet(Stage.IDLE, Stage.BUILDING)
                && !reenter(run)) {
            return; // 本次运行里已经排在做了（FAILED 的有界重入见 reenter）
        }
        if (!region(world, 0, 0, 0L, () -> prepare(world, run, why),
                problem -> fail(run, problem))) {
            // 排不进去就别把状态卡在 BUILDING：退回去，下一次事件还能再试。
            if (run.stage.compareAndSet(Stage.BUILDING, Stage.IDLE)) {
                report.warning("中心祭坛：施工没能排进区域线程（" + why + "），退回等待后续事件。");
            }
        }
    }

    /**
     * 有界重入：上一次中止（{@link Stage#FAILED}）之后，允许后续世界/龙事件再试一次，但每世界最多
     * {@link #MAX_REENTRIES} 次——中止往往来自「区块还没就绪」这类外部条件，一律钉死 FAILED 会让一次
     * 偶发失败永久废掉这个世界的中心建筑；而无限重入就是常驻重试，也不对。
     */
    private static boolean reenter(Run run) {
        while (true) {
            if (run.stage.get() != Stage.FAILED) {
                return false;
            }
            if (run.reentries.get() >= MAX_REENTRIES) {
                return false;
            }
            if (run.stage.compareAndSet(Stage.FAILED, Stage.BUILDING)) {
                run.reentries.incrementAndGet();
                return true;
            }
        }
    }

    /**
     * 确定布局，然后开工。<b>顺序就是契约</b>：地形 → 建筑 → 功能槽。
     *
     * <ul>
     *   <li>世界 PDC 里已有版本 2 的布局 → 直接用它（重启/复活不再按地形重算，玩家改地形也不搬楼）；</li>
     *   <li>没有 → 取本插件纯地形场在中心列的顶块 {@code S}，算出 {@code floorY=S / baseY=S-5 /
     *       gateY=S-3}，并**立刻持久化**（含版本与种子），这样崩溃/重启也落在同一套坐标上；</li>
     *   <li>持久化布局的种子和当前世界种子不一致 → 拒绝动工（换了种子的世界不是同一座岛）。</li>
     * </ul>
     */
    private void prepare(World world, Run run, String why) {
        if (run.stopped) {
            return;
        }
        EndPodiumTemplate.Layout layout = run.layout != null ? run.layout : layout(world).orElse(null);
        if (legacyPlacedWithoutLayout(placed(world), layoutRaw(world))) {
            run.stage.set(Stage.FAILED);
            report.warning("中心祭坛：世界已经标记为 placed，却读不到本版（v2）布局——这是旧版/损坏的现场，"
                    + "不是新世界。按契约拒绝：不按地形重算、不写新布局、一格不动、不冒充完成；"
                    + "请走显式升级（EndPodiumShell#reanchor）核对旧楼后再迁移。");
            return;
        }
        if (layout == null) {
            // 世界里有标记但本版看不懂（旧 v1 / legacy "x,y,z" / 被改坏）：**绝不覆盖**。
            // 覆盖等于用新锚点凭空重算一座楼，旧楼和旧门会变成两套建筑，而且没有任何回滚证据。
            String raw = layoutRaw(world);
            if (raw != null && !raw.isBlank()) {
                run.stage.set(Stage.FAILED);
                report.warning("中心祭坛：世界里已有布局标记 '" + raw + "'，但不是本版支持的布局（旧版或损坏）。"
                        + "按契约绝不覆盖：一格未写、不落 placed；请走显式升级（EndPodiumShell#reanchor）核对后再迁移。");
                return;
            }
        }
        if (layout != null && layout.seed() != world.getSeed()) {
            run.stage.set(Stage.FAILED);
            report.warning("中心祭坛：持久化布局的种子 " + layout.seed() + " 与世界种子 "
                    + world.getSeed() + " 不一致，拒绝按旧布局施工（不搬楼、不重算）。");
            return;
        }
        if (layout == null) {
            int surfaceTop = surfaceTop(world, 0, 0);
            if (surfaceTop == Integer.MIN_VALUE) {
                run.stage.compareAndSet(Stage.BUILDING, Stage.IDLE);
                report.warning("中心祭坛：中心列 (" + 0 + ", " + 0 + ") 在当前纯地形场里是空的，"
                        + "算不出楼板高度，先不动工（等下一次区块/龙事件）。");
                return;
            }
            EndPodiumTemplate.Plan planned = EndPodiumTemplate.planAtSurface(surfaceTop);
            layout = new EndPodiumTemplate.Layout(EndPodiumTemplate.LAYOUT_VERSION, world.getSeed(),
                    planned.gateX(), planned.gateY(), planned.gateZ());
            // 锚点必须先落盘、再写任何建筑方块：MU 的 spawnExitPortal 只有在 exitPortalLocation 非空时
            // 才不按高度图算位置，所以「门环坐标已持久化」是写建筑的唯一前置条件（这里写的是几十字节的
            // 原子文件，不是大备份；大备份在施工层里走异步）。
            try {
                new WorldMarker(world).write(EndPodiumBuild.MARK_LAYOUT, EndPodiumTemplate.encode(layout));
            } catch (RuntimeException error) {
                run.stage.compareAndSet(Stage.BUILDING, Stage.IDLE);
                report.warning("中心祭坛：布局锚点落盘失败，一格未写，退回等待后续事件：" + reason(error));
                return;
            }
            report.info("中心祭坛：首次确定布局（" + why + "）：地形顶块 S=" + surfaceTop
                    + "，楼板 y=" + planned.floorY() + "，门环 y=" + planned.gateY()
                    + "，底面 y=" + planned.baseY() + "；已持久化 " + EndPodiumTemplate.encode(layout));
        }
        run.layout = layout;
        run.plan = layout.plan();
        run.shield = null; // 布局变了，旧名单作废
        startBuild(world, run, why);
    }

    private void startBuild(World world, Run run, String why) {
        EndPodiumTemplate.Plan plan = run.plan;
        report.info("中心祭坛开始施工（" + why + "）：门环 (" + plan.gateX() + ", " + plan.gateY() + ", "
                + plan.gateZ() + ")，楼板 y=" + plan.floorY() + "，净空 " + plan.clearance().size()
                + " 格、装饰 " + plan.decorations().size() + " 格、四晶座 " + plan.seats().size() + " 个");
        EndPodiumBuild build = new EndPodiumBuild(
                plan, new WorldArea(world),
                new WorldDispatch(world, reason -> fail(run, reason)),
                new WorldMarker(world),
                new WorldSaver(world.getUID()),
                new PodiumCore(world, plan),
                report,
                () -> run.stage.set(Stage.PLACED),
                () -> run.stage.set(Stage.FAILED),
                Math.floorDiv(plan.gateX(), 16), Math.floorDiv(plan.gateZ(), 16),
                EndPodiumTemplate.encode(run.layout));
        run.build = build;
        build.start();
    }

    /**
     * 原生功能核心：把门环平面钉到布局的 {@code gateY} 并让 MU 自己写基岩柱/基岩圈/返回门/火把。
     *
     * <p>三种情况分开处理，绝不用旧门倒推建筑：
     * <ul>
     *   <li>记录里没有门（新世界）→ {@code anchor(read=null, target, active=false)}，首杀一定是 inactive 核心；</li>
     *   <li>记录里就是目标坐标 → 只做「4 格基岩柱在场」校验，不重复生成；</li>
     *   <li>记录里是别的坐标（原版抢先生成过旧核心 / 旧世界）→ **拒绝**：自动迁移会留下双门，必须走
     *       {@link #reanchor(World)} 的显式升级（核验 + 备份 + 清旧 + 保状态）。</li>
     * </ul>
     * 可等状态（区块未就绪/暂不归属本区域）交给施工层有限重试，其余直接中止且不落 placed。
     */
    private final class PodiumCore implements EndPodiumBuild.Core {
        private final World world;
        private final EndPodiumTemplate.Plan plan;

        PodiumCore(World world, EndPodiumTemplate.Plan plan) {
            this.world = world;
            this.plan = plan;
        }

        @Override
        public EndPodiumBuild.Core.Result place() {
            if (!MuDragonFightBridge.available()) {
                return EndPodiumBuild.Core.Result.fail("MU 龙战桥接不可用："
                        + MuDragonFightBridge.unavailableReason());
            }
            Location target = new Location(world, plan.gateX(), plan.gateY(), plan.gateZ());
            Location recorded = MuDragonFightBridge.read(world);
            if (recorded != null && !sameBlock(recorded, target)) {
                return EndPodiumBuild.Core.Result.fail("世界记录里的返回门在 " + describe(recorded)
                        + "，本布局要求 " + describe(target) + "；自动迁移会留下双门，所以拒绝。"
                        + "旧核心请走显式升级：EndPodiumShell#reanchor");
            }
            if (recorded == null) {
                MuDragonFightBridge.Outcome outcome =
                        MuDragonFightBridge.anchor(world, null, target, false);
                if (!outcome.ok()) {
                    return switch (outcome.status()) {
                        case CHUNK_NOT_LOADED, CHUNK_NOT_OWNED ->
                                EndPodiumBuild.Core.Result.retry(outcome.status() + "：" + outcome.detail());
                        default -> EndPodiumBuild.Core.Result.fail(
                                outcome.status() + "：" + outcome.detail());
                    };
                }
            }
            String problem = EndPodiumBuild.podiumBodyProblem(plan, new WorldArea(world));
            return problem == null
                    ? EndPodiumBuild.Core.Result.done()
                    : EndPodiumBuild.Core.Result.fail(problem);
        }
    }

    /** 两个坐标是不是同一格方块（忽略 yaw/pitch/精确坐标）。 */
    static boolean sameBlock(Location a, Location b) {
        return a != null && b != null
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private static String describe(Location location) {
        return location == null ? "(无记录)"
                : "(" + location.getBlockX() + ", " + location.getBlockY() + ", "
                        + location.getBlockZ() + ")";
    }

    /**
     * 龙蛋归位：目标恒为「楼板上一格」（{@code floorY+1}，也就是中心柱顶上面那一格）。
     *
     * <p>只在**同一区域**（本方法总在中心区块的区域线程上跑）、**目标格是空气**且**支撑有效**
     * （下面那一格是 bedrock 柱顶）时才动手：先放新位置，再把原版那颗清掉，两笔在同一个区域任务里
     * 同步完成，中间没有异步空档。任何一条不满足就**保留原蛋并告警**——绝不先吞掉再异步补放。
     *
     * <p>原版 {@code setDragonKilled} 用 {@code MOTION_BLOCKING} 高度图放蛋，塔的中心柱顶之上还有钟楼，
     * 所以原蛋会落在塔顶那一列；我们只搬同一列上那颗，不扫全世界、不凭空造蛋。
     */
    private void relocateEgg(World world, Run run, EndPodiumTemplate.Plan plan) {
        int x = plan.gateX();
        int targetY = plan.floorY() + 1;
        int z = plan.gateZ();
        String support = EndPodiumTemplate.blockId(state(world, x, targetY - 1, z));
        String targetNow = state(world, x, targetY, z);
        int eggY = eggColumn(world, x, z, targetY, plan.maxY() + 8);
        if (eggY == Integer.MIN_VALUE) {
            return; // 还没有蛋（首次施工）或原版那颗不在这一列：不搬、不造
        }
        if (eggY == targetY) {
            return; // 已经在位
        }
        if (!isAir(targetNow) || !"minecraft:bedrock".equals(support)) {
            report.warning("中心祭坛：龙蛋没有归位——目标 (" + x + ", " + targetY + ", " + z + ") 是 "
                    + targetNow + "，支撑是 " + support + "（要求空气 + bedrock 柱顶）。原蛋保留在 ("
                    + x + ", " + eggY + ", " + z + ")，请人工处理。");
            return;
        }
        world.getBlockAt(x, targetY, z).setBlockData(YungPillars.bukkit(DRAGON_EGG_STATE), false);
        world.getBlockAt(x, eggY, z).setBlockData(YungPillars.bukkit("minecraft:air"), false);
        if (run.eggMoved.compareAndSet(false, true)) {
            report.info("中心祭坛：龙蛋已从塔顶 (" + x + ", " + eggY + ", " + z + ") 归位到柱顶上方 ("
                    + x + ", " + targetY + ", " + z + ")。");
        }
    }

    /** {@code (x, *, z)} 这一列上 {@code fromY..toY} 之间的龙蛋方块 y；没有就是 {@link Integer#MIN_VALUE}。 */
    private static int eggColumn(World world, int x, int z, int fromY, int toY) {
        for (int y = toY; y >= fromY; y--) {
            if (DRAGON_EGG_ID.equals(EndPodiumTemplate.blockId(state(world, x, y, z)))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 末影龙死亡/重生后的一次有界修复：原版重建祭坛只会动门环半径 3.5 的一小团，而按本锚点那团
     * 全在净空/家具里，所以 {@code plan.repairs()} 通常是空集——这里先把通道接好并只跑一次，绝不重建
     * 整栋建筑、也不恢复玩家在别处拆掉的方块。同一次任务里顺手把龙蛋归位。
     */
    private void scheduleRepair(World world, Run run, String why) {
        if (!run.repairing.compareAndSet(false, true)) {
            return;
        }
        if (!region(world, 0, 0, AFTER_DEATH_TICKS, () -> {
            try {
                // 世界卸载又重载会换一个新 Run：旧回调只对当初那个负责。
                if (run.stopped || runs.get(world.getUID()) != run) {
                    return;
                }
                EndPodiumTemplate.Plan plan = planOf(world, run);
                if (plan == null) {
                    return;
                }
                int filled = EndPodiumBuild.repair(plan.repairs(), new WorldArea(world));
                if (filled > 0) {
                    report.info("中心祭坛：" + why + "后补回 " + filled + " 格被清掉的模板外壳。");
                }
                relocateEgg(world, run, plan);
            } catch (RuntimeException error) {
                report.warning("中心祭坛：" + why + "后的局部修复失败：" + reason(error));
            } finally {
                run.repairing.set(false);
            }
        })) {
            run.repairing.set(false);
        }
    }

    /** 只读探针结果：显式升级入口（{@code EndLayoutUpgrade}）拿它做预览，本类不写世界。 */
    public record Survey(boolean managed, boolean placed, String layoutRaw,
                         EndPodiumTemplate.Plan planned,
                         Optional<EndPodiumTemplate.Layout> stored,
                         Optional<Location> recordedPortal,
                         int natural, int furniture, int unknown, int tileStates,
                         List<EndPodiumTemplate.Cell> conflicts) {
    }

    /**
     * 异步只读探针：按本版布局逐格看世界，把「天然地形 / 原版家具 / 玩家冲突」分开数。
     *
     * <p>不写一格、不做备份、不改阶段；{@code done} 在中心区块的区域线程结束后被回调（同一线程）。
     * 玩家冲突（{@code unknown}/{@code tileStates}/{@code conflicts}）就是升级预览里「需要人工处理」的依据。
     */
    public void survey(World world, Consumer<Survey> done) {
        if (!manages(world)) {
            done.accept(new Survey(false, false, null, null, Optional.empty(), Optional.empty(),
                    0, 0, 0, 0, List.of()));
            return;
        }
        String raw = layoutRaw(world);
        EndPodiumTemplate.Layout stored = null;
        if (raw != null) {
            try {
                stored = EndPodiumTemplate.decode(raw);
            } catch (IllegalArgumentException legacy) {
                // 旧格式/损坏：原样上报 raw（上面已告警），调用方据此拒绝，绝不当新布局用。
                stored = null;
            }
        }
        EndPodiumTemplate.Plan plan;
        if (stored != null) {
            plan = stored.plan();
        } else {
            int top = surfaceTop(world, 0, 0);
            plan = top == Integer.MIN_VALUE ? null : EndPodiumTemplate.planAtSurface(top);
        }
        if (plan == null) {
            done.accept(new Survey(true, placed(world), raw, null, Optional.of(stored),
                    Optional.empty(), 0, 0, 0, 0, List.of()));
            return;
        }
        // lambda 捕获要求 effectively final：上面的 plan/stored 在分支里被赋过值，这里固化成终态。
        final EndPodiumTemplate.Plan finalPlan = plan;
        final EndPodiumTemplate.Layout finalStored = stored;
        // 按区块分桶：每批只读**自己那个区块**的格。原来的写法在中心区块的区域线程上把四处区块
        // 一起读了，那正是审查里说的跨区读；survey 是只读探针，也不能开这个口子。
        Map<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> buckets = new java.util.TreeMap<>(
                (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> entry
                : finalPlan.clearanceByChunk().entrySet()) {
            buckets.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(entry.getValue());
        }
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> entry
                : finalPlan.byChunk().entrySet()) {
            buckets.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(entry.getValue());
        }
        java.util.concurrent.atomic.AtomicIntegerArray counts =
                new java.util.concurrent.atomic.AtomicIntegerArray(4);
        List<EndPodiumTemplate.Cell> conflicts = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger remaining = new AtomicInteger(buckets.size());
        java.util.concurrent.atomic.AtomicReference<Location> recorded =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (Map.Entry<EndPodiumTemplate.ChunkKey, List<EndPodiumTemplate.Cell>> entry
                : buckets.entrySet()) {
            int chunkX = entry.getKey().x();
            int chunkZ = entry.getKey().z();
            List<EndPodiumTemplate.Cell> cells = entry.getValue();
            boolean queued = region(world, chunkX, chunkZ, 0L, () -> {
                for (EndPodiumTemplate.Cell cell : cells) {
                    String now = state(world, cell.x(), cell.y(), cell.z());
                    String id = EndPodiumTemplate.blockId(now);
                    if (EndPodiumTemplate.isNaturalTerrain(id)) {
                        counts.incrementAndGet(0);
                    } else if (EndPodiumTemplate.isFunctionProtected(id)) {
                        counts.incrementAndGet(1);
                    } else {
                        counts.incrementAndGet(2);
                        if (world.getBlockAt(cell.x(), cell.y(), cell.z()).getState(false) instanceof TileState) {
                            counts.incrementAndGet(3);
                        }
                        if (conflicts.size() < 64) {
                            conflicts.add(cell);
                        }
                    }
                }
                if (chunkX == 0 && chunkZ == 0) {
                    try {
                        recorded.set(MuDragonFightBridge.read(world));
                    } catch (RuntimeException | LinkageError ignored) {
                        // 桥接不可用：survey 仍要给出方块层面的结论
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    finishSurvey(world, done, raw, finalStored, finalPlan, counts, conflicts, recorded.get());
                }
            }, problem -> {
                report.warning("中心祭坛：survey 读区块 " + chunkX + "," + chunkZ + " 失败：" + problem);
                if (remaining.decrementAndGet() == 0) {
                    finishSurvey(world, done, raw, finalStored, finalPlan, counts, conflicts, recorded.get());
                }
            });
            if (!queued) {
                if (remaining.decrementAndGet() == 0) {
                    finishSurvey(world, done, raw, finalStored, finalPlan, counts, conflicts, recorded.get());
                }
            }
        }
    }

    private void finishSurvey(World world, Consumer<Survey> done, String raw,
                              EndPodiumTemplate.Layout stored, EndPodiumTemplate.Plan plan,
                              java.util.concurrent.atomic.AtomicIntegerArray counts,
                              List<EndPodiumTemplate.Cell> conflicts, Location recorded) {
        List<EndPodiumTemplate.Cell> snapshot;
        synchronized (conflicts) {
            snapshot = List.copyOf(conflicts);
        }
        done.accept(new Survey(true, placed(world), raw, plan, Optional.of(stored),
                Optional.ofNullable(recorded), counts.get(0), counts.get(1), counts.get(2), counts.get(3),
                snapshot));
    }

    /**
     * **显式**受控重定位：把已经存在的原生核心（旧布局/原版抢先生成的那一颗）搬到本版布局的
     * {@code gateY}。只给显式升级入口用，自动流程绝不调用（自动流程遇到别的坐标直接拒绝，见
     * {@link PodiumCore}）。
     *
     * <p>顺序固定：核验来源 → 核验旧家具 → 压缩快照 → 清旧家具 → {@code anchor} 保状态迁移。
     * 任何一步不成立就整项停止、一格不动：不猜所有权、不留双门、不拿旧 PDC 冒充新布局。
     *
     * @return 请求是否已受理（真正的结果看日志；受理后仍在中心区块的区域线程上异步执行）
     */
    public boolean reanchor(World world) {
        if (!manages(world)) {
            return false;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle != null && battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            report.warning("中心祭坛：龙正在复活（阶段 " + battle.getRespawnPhase()
                    + "），拒绝迁移门锚点；等本轮复活结束再来。");
            return false;
        }
        boolean accepted = region(world, 0, 0, 0L, () -> {
            if (!MuDragonFightBridge.available()) {
                report.warning("中心祭坛：迁移失败——" + MuDragonFightBridge.unavailableReason());
                return;
            }
            int top = surfaceTop(world, 0, 0);
            if (top == Integer.MIN_VALUE) {
                report.warning("中心祭坛：迁移失败——中心列在当前地形场里是空的。");
                return;
            }
            EndPodiumTemplate.Plan target = EndPodiumTemplate.planAtSurface(top);
            Location recorded = MuDragonFightBridge.read(world);
            Location goal = new Location(world, target.gateX(), target.gateY(), target.gateZ());
            if (recorded == null) {
                report.info("中心祭坛：世界还没有记录任何返回门，不需要迁移（正常流程会直接生成 inactive 核心）。");
                return;
            }
            if (sameBlock(recorded, goal)) {
                report.info("中心祭坛：记录中的返回门已经是本版布局的 " + describe(goal) + "，不需要迁移。");
                return;
            }
            // 来源证据用**逐格材质核验**（下面那段），不调 readFrame：原生 findExitPortal 会同步遍历
            // origin ±8 的 289 个区块，跨区域调用等于把别的 region 拽住，Folia 上不该走。逐格核验已经
            // 足够——基岩柱/基岩圈/火把是原版家具独有的形状，玩家搭不出「恰好这 82 格都是原版材质」。
            EndPodiumTemplate.Plan old = EndPodiumTemplate.plan(
                    recorded.getBlockX(), recorded.getBlockY(), recorded.getBlockZ());
            List<EndPodiumBackup.Cell> oldCells = new ArrayList<>();
            for (EndPodiumTemplate.Cell cell : old.furniture()) {
                if (cell.x() == old.gateX() && cell.z() == old.gateZ()
                        && cell.y() == old.floorY() + 1) {
                    continue; // 龙蛋归位时单独处理，不在这里清
                }
                String now = state(world, cell.x(), cell.y(), cell.z());
                String id = EndPodiumTemplate.blockId(now);
                if (!EndPodiumTemplate.isFunctionProtected(id)) {
                    report.warning("中心祭坛：迁移失败——旧门家具格 (" + cell.x() + ", " + cell.y() + ", "
                            + cell.z() + ") 是 " + now + "，不是原版基岩/门/火把；无法证实来源，一格不动。");
                    return;
                }
                oldCells.add(new EndPodiumBackup.Cell(cell.x(), cell.y(), cell.z(), now));
            }
            Path backup;
            try {
                backup = new WorldSaver(world.getUID()).save(oldCells);
            } catch (Exception error) {
                report.warning("中心祭坛：迁移失败——旧门家具快照落盘失败：" + reason(error) + "；一格不动。");
                return;
            }
            report.info("中心祭坛：旧门家具已快照（" + oldCells.size() + " 格）：" + backup);
            for (EndPodiumBackup.Cell cell : oldCells) {
                world.getBlockAt(cell.x(), cell.y(), cell.z())
                        .setBlockData(YungPillars.bukkit("minecraft:air"), false);
            }
            boolean active = battle != null && battle.hasBeenPreviouslyKilled();
            MuDragonFightBridge.Outcome outcome = MuDragonFightBridge.anchor(world, recorded, goal, active);
            if (!outcome.ok()) {
                report.warning("中心祭坛：迁移失败——" + outcome.status() + "：" + outcome.detail()
                        + "；旧门已清、快照在 " + backup + "，请按快照人工恢复后再试。");
                return;
            }
            report.info("中心祭坛：门锚点已迁移 " + describe(recorded) + " → " + describe(goal)
                    + "（active=" + active + "，击杀记录与龙 UUID 保持原生不动）；可以把世界重新纳入施工。");
            Run run = runs.get(world.getUID());
            if (run != null) {
                run.layout = null;
                run.plan = null;
                run.shield = null;
                run.stage.set(Stage.IDLE);
            }
        }, null);
        if (!accepted) {
            report.warning("中心祭坛：迁移任务没能排进中心区块的区域线程。");
        }
        return accepted;
    }

    // ------------------------------------------------------------------ 世界侧

    /** 末地维度 + 本插件生成器；两者缺一就不碰。 */
    private static boolean manages(World world) {
        return world.getEnvironment() == World.Environment.THE_END
                && world.getGenerator() instanceof DustWastelandsGenerator;
    }

    /**
     * 调度本身失败（区块加载不出来、异步池已经关闭、世界卸载）：本次运行内不再重试
     * （{@link Stage#FAILED}），把原因写进日志——静默变 FAILED 等于什么线索都不留。
     * 已落盘的快照与已写下的格都留在原处，{@code placed} 绝不落。
     */
    private void fail(Run run, String reason) {
        if (run.stage.compareAndSet(Stage.BUILDING, Stage.FAILED)) {
            report.warning("中心祭坛施工中止：调度失败（" + reason + "）。快照与已写的格保留，不落 placed，不再自动重试。");
        }
        EndPodiumBuild build = run.build;
        if (build != null) {
            build.stop();
        }
    }

    /** 一格当前的状态串（调用方必须已经在拥有该格区域的线程上）。 */
    private static String state(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getBlockData().getAsString();
    }

    private static boolean isAir(String state) {
        return "minecraft:air".equals(EndPodiumTemplate.blockId(state))
                || "minecraft:cave_air".equals(EndPodiumTemplate.blockId(state))
                || "minecraft:void_air".equals(EndPodiumTemplate.blockId(state));
    }

    /**
     * 中心列的楼板高度 {@code S}：**本插件纯地形场**的最高实心 y（不含建筑、不含高度图、不含旧门）。
     *
     * <p>纯函数、不需要区块加载，所以首次生成时可以在区域线程上直接算；返回
     * {@link Integer#MIN_VALUE} 表示这一列是空的（不该在内岛中心发生）。
     */
    private static int surfaceTop(World world, int x, int z) {
        if (world.getGenerator() instanceof DustWastelandsGenerator generator) {
            return generator.islandTopY(world.getSeed(), x, z);
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 在拥有 {@code (chunkX, chunkZ)} 的区域线程上延迟执行 {@code task}；**区块先异步就绪**。
     *
     * <p>这是本类唯一的区域入口：批次、慢 IO 回来后的续跑、完成标记、局部修复全走它，所以「慢 IO 期间
     * 原点区块被卸载」不会让后续任务落空，也不必假定最初那次异步加载的临时 ticket 还在。不用
     * {@code addPluginChunkTicket}（忘记 remove 就泄漏 ticket），也绝不在这条路径上等待或 join 别的区域。
     *
     * @param onFailure 排不进去（世界已卸载、插件正在关闭、区块加载失败）时收到原因；{@code null} 表示
     *                  只记日志——调用方自己还有状态要收拾的话必须传，否则状态会卡在半路
     * @return 是否已经发出请求（异步阶段的失败只能通过 {@code onFailure} 得知）
     */
    private boolean region(World world, int chunkX, int chunkZ, long delayTicks, Runnable task,
                           Consumer<String> onFailure) {
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
                    refused(world, chunkX, chunkZ, "区域任务被拒绝：" + reason(refused), onFailure);
                }
            }).exceptionally(error -> {
                refused(world, chunkX, chunkZ, "区块加载失败：" + reason(error), onFailure);
                return null;
            });
            return true;
        } catch (RuntimeException refused) {
            refused(world, chunkX, chunkZ, "无法请求区块：" + reason(refused), onFailure);
            return false;
        }
    }

    /** 只记日志的四参版本：调用方没有半路状态要收拾时用。 */
    private boolean region(World world, int chunkX, int chunkZ, long delayTicks, Runnable task) {
        return region(world, chunkX, chunkZ, delayTicks, task, null);
    }

    /** 区域调度失败：先落一条日志（任何路径都要留痕），再交给调用方的收尾逻辑。 */
    private void refused(World world, int chunkX, int chunkZ, String why, Consumer<String> onFailure) {
        String text = "中心祭坛：" + world.getName() + " 区块 " + chunkX + "," + chunkZ + " " + why;
        report.warning(text);
        if (onFailure != null) {
            onFailure.accept(why);
        }
    }

    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    /** 生产环境的方块读写面：只在区域线程上被调用。 */
    private static final class WorldArea implements EndPodiumBuild.Area {
        private final World world;

        WorldArea(World world) {
            this.world = world;
        }

        @Override
        public String state(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getBlockData().getAsString();
        }

        @Override
        public void set(int x, int y, int z, String state) {
            // 按状态串缓存：模板只有三十几种状态，不必每格都解析一次。
            BlockData data = YungPillars.bukkit(state);
            // 第二个参数 false：不触发物理/邻居更新。塔是整段写下去的，让沙子类物理跑一遍只会帮倒忙。
            world.getBlockAt(x, y, z).setBlockData(data, false);
        }

        @Override
        public boolean tileState(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getState(false) instanceof TileState;
        }
    }

    /**
     * 调度实现：区块异步就绪 → 区域线程；磁盘走异步线程池。
     *
     * <p>{@link #region} 把「排不进去」当成失败上报给施工层：区块加载不出来、区域调度被拒绝、异步池
     * 已经关闭都会让状态机停在 BUILDING，必须让它有机会收口（记日志 + 停手），而不是静默卡死。
     */
    private final class WorldDispatch implements EndPodiumBuild.Dispatch {
        private final World world;
        private final Consumer<String> onFailure;

        WorldDispatch(World world, Consumer<String> onFailure) {
            this.world = world;
            this.onFailure = onFailure;
        }

        @Override
        public void region(int chunkX, int chunkZ, long delayTicks, Runnable task) {
            if (!plugin.isEnabled()) {
                return; // 关闭中：既不是施工失败，也没人再看结果
            }
            if (!EndPodiumShell.this.region(world, chunkX, chunkZ, delayTicks, task, onFailure)) {
                onFailure.accept("区域任务无法排入区块 " + chunkX + "," + chunkZ);
            }
        }

        @Override
        public void async(Runnable task) {
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
            } catch (RuntimeException refused) {
                onFailure.accept(reason(refused));
            }
        }
    }

    /**
     * 世界侧的「完成标记 + 布局」。
     *
     * <p>权威存储是**插件目录里的原子文件**（{@code podium-layout/<worldUUID>.layout} 与
     * {@code .placed}），不是世界 PDC：{@code CraftPersistentDataContainer} 底层是无锁的
     * {@code HashMap}，只允许在全局线程上改，而施工流程在区域线程上收尾——把「完成」写进 PDC 会有丢
     * 更新甚至结构损坏的风险，而完成标记恰恰是绝不能丢的那一条。所以这里只做两次**极小的**原子文件
     * 替换（几十字节，仅在收尾时发生），落盘不成功就当失败，不标完成。
     *
     * <p>同时仍然 best-effort 往世界 PDC 镜像一份（经全局线程调度），供旧版本/外部工具读取；
     * 镜像失败只记日志，不影响判定。
     */
    private final class WorldMarker implements EndPodiumBuild.Marker {
        private final World world;

        WorldMarker(World world) {
            this.world = world;
        }

        @Override
        public String read(String key) {
            try {
                boolean placedFile = Files.isRegularFile(stateFile(world));
                if (EndPodiumBuild.MARK_STATE.equals(key)) {
                    return readStateMark(placedFile, pdcPlacedCache.get(world.getUID()));
                }
                String fileText = Files.isRegularFile(layoutFile(world))
                        ? Files.readString(layoutFile(world), StandardCharsets.UTF_8).trim()
                        : null;
                return readLayoutMark(fileText, pdcLayoutCache.get(world.getUID()));
            } catch (IOException error) {
                report.warning("中心祭坛：读 " + key + " 标记失败：" + reason(error));
                return null;
            }
        }

        @Override
        public void write(String key, String value) {
            Path file = EndPodiumBuild.MARK_STATE.equals(key) ? stateFile(world) : layoutFile(world);
            try {
                Files.createDirectories(file.getParent());
                AtomicFiles.replace(file, value.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new IllegalStateException("写 " + file + " 失败：" + reason(error), error);
            }
            mirror(key, value);
        }

        /** PDC 兼容镜像：全局线程上的 best-effort，失败只告警（权威值已经在文件里）。 */
        private void mirror(String key, String value) {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                    try {
                        world.getPersistentDataContainer()
                                .set(markKey(plugin, key), PersistentDataType.STRING, value);
                        if (EndPodiumBuild.MARK_STATE.equals(key)) {
                            pdcPlacedCache.put(world.getUID(), EndPodiumBuild.PLACED.equals(value));
                        } else {
                            pdcLayoutCache.put(world.getUID(), value);
                        }
                    } catch (RuntimeException error) {
                        report.warning("中心祭坛：PDC 镜像写入失败（权威值在文件里）：" + reason(error));
                    }
                });
            } catch (RuntimeException refused) {
                report.warning("中心祭坛：PDC 镜像排不进全局线程（权威值在文件里）：" + reason(refused));
            }
        }
    }

    /** 布局文件：{@code plugins/BetterEnd/podium-layout/<worldUUID>.layout}。 */
    static Path layoutFile(Path dataDir, UUID worldId) {
        return dataDir.resolve("podium-layout").resolve(worldId + ".layout");
    }

    /** 完成标记文件：{@code plugins/BetterEnd/podium-layout/<worldUUID>.placed}（存在即完成）。 */
    static Path stateFile(Path dataDir, UUID worldId) {
        return dataDir.resolve("podium-layout").resolve(worldId + ".placed");
    }

    /**
     * 封存标记：{@code plugins/BetterEnd/podium-layout/<worldUUID>.sealed}，内容是封存用的 job id。
     * 存在即「旧楼已被显式清理并封存」，旧施工与四晶座都不得再按旧标记复活。
     */
    static Path sealedFile(Path dataDir, UUID worldId) {
        return dataDir.resolve("podium-layout").resolve(worldId + ".sealed");
    }

    private Path layoutFile(World world) {
        return layoutFile(dataDir, world.getUID());
    }

    private Path stateFile(World world) {
        return stateFile(dataDir, world.getUID());
    }

    /** PDC 里的布局镜像（旧路径/外部工具）；不再作为权威，只作兼容读取。 */
    private String pdcLayout(World world) {
        return world.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, KEY_LAYOUT), PersistentDataType.STRING);
    }

    private String pdcState(World world) {
        return world.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, KEY_STATE), PersistentDataType.STRING);
    }

    /** 原方块快照：每次尝试一个新的 job id，所以旧备份永远不会被覆盖。 */
    private final class WorldSaver implements EndPodiumBuild.Saver {
        private final UUID worldId;

        WorldSaver(UUID worldId) {
            this.worldId = worldId;
        }

        @Override
        public Path save(List<EndPodiumBackup.Cell> cells) throws Exception {
            UUID jobId = UUID.randomUUID();
            Path file = EndPodiumBackup.write(dataDir, worldId, jobId, cells);
            report.info("中心祭坛原方块快照已落盘（job " + jobId + "）：" + file
                    + "，共 " + cells.size() + " 格");
            return file;
        }
    }

    /**
     * 日志出口：复用插件日志，不新增玩家语言配置项——这些行是给管理员看的中文诊断，不是聊天消息。
     */
    private final class Report implements EndPodiumBuild.Report {
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
