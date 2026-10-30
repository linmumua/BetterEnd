package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.terrain.IslandField;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用 YUNG「Better End Island」的 36 个柱模板替换掉原版十根黑曜石柱，同时**保住**原版的水晶、座与
 * 龙战判定。
 *
 * <h2>规则来源</h2>
 * 全部照抄 {@code tools/yung-better-end-island/src/BetterSpikeFeature.java}（分支 26.1.2，commit
 * 549ad39，LGPL-3.0，许可证随模板放在
 * {@code structures/yung_end_island/LICENSE-YUNG-Better-End-Island.txt}）：
 * <ul>
 *   <li>档位 {@code tier = min((height - 73) / 3, 9)}：{@code height} 是原版柱高 76..103，所以档位
 *       1..10，而第 10 档没有模板，按 9 取。</li>
 *   <li>上段模板 {@code pillar_(guarded|broken)_tier}、下段 {@code pillar_bottom_tier}。</li>
 *   <li>四向旋转：{@code new Random(seed ^ centerX ^ centerZ).nextInt(4)}，按
 *       {@code NONE / CLOCKWISE_90 / CLOCKWISE_180 / COUNTERCLOCKWISE_90} 解释。</li>
 *   <li>占位方块：orange_terracotta → obsidian + 30% crying_obsidian；magenta_terracotta → 80% 空气 +
 *       10% crying_obsidian + 10% obsidian；purple_concrete → obsidian。</li>
 *   <li>{@code IEndSpike#setCrystalYOffsetFromPillarHeight}：水晶相对「上段锚点」的高度
 *       {@link #CRYSTAL_OFFSET}。</li>
 * </ul>
 *
 * <h2>锚点：{@code upperBaseY = 70} / {@code lowerBaseY = 3}</h2>
 * 上游 {@code BetterSpikeFeature.placeSpike} 在 BetterEnd 分支里把上段模板的 y=0 层钉在
 * {@code topY = 70}、下段钉在 {@code centerPos.below(67)}（即 3），水晶高度由模板槽位决定
 * （{@code crystalY = topY + offset}），基岩座在 {@code crystalY - 1}。这里逐条照抄，不再为了迁就原版
 * 水晶高度而整根压低：
 * <ul>
 *   <li>上段锚点 {@link #UPPER_BASE_Y} = 70；下段锚点 {@link #LOWER_BASE_Y} = 70 - 67 = 3；</li>
 *   <li>模板 {@code gray_concrete} 座位标记在本地 {@code (9, offset - 1, 9)} ⇒ 世界
 *       {@code seatY = 70 + offset - 1}，写成 {@code minecraft:bedrock}；</li>
 *   <li>模板水晶洞（本地 {@code (9, offset, 9)}，模板里是空的）⇒ 世界
 *       {@code crystalY = 70 + offset}（92..108 档位全表见 {@link #CRYSTAL_OFFSET}）。</li>
 * </ul>
 *
 * <h3>旧布局（0.1.26 及以前，仅作迁移基准保留）</h3>
 * 旧版为了把原版水晶留在 {@code height + 1}，用 {@code topAnchorY = spike.height() + 1 - offset}、
 * {@code bottomAnchorY = topAnchorY - 67}，于是 {@code seatY = spike.height()}、
 * {@code crystalY = spike.height() + 1}，整根柱子比上游低 {@code offset - 1 - (height - 70)} 格，
 * 肩台埋进岛体。**新公式不再使用它**；{@code EndLayoutUpgrade} 需要旧基准时自行按这组式子算，
 * 不要由新公式反推。
 *
 * <h2>分块写入（Folia / LimitedRegion）</h2>
 * <b>每次覆盖到柱位的 populator 都把自己可达范围内的模板整段重放一遍</b>，而不是「只写自己区块的格」。
 * 后者看起来更干净，但它证明不了收敛：区块 C 的 populator 跑完之后，邻区块 D 的装饰步骤仍可能再擦掉
 * C 里的一格（原版 {@code EndSpikeFeature} 的方块写入半径是 1 个区块），而 C 的 populator 不会再跑。
 * <ul>
 *   <li><b>为什么重放能收敛</b>：对任意一次擦除，擦它的是某个区块 Z 的装饰步骤；Minecraft 的执行顺序
 *       保证同一区块的装饰一定在它自己的 populator 之前，而 Z 的 populator 的可达范围（Z ± 1 区块）
 *       包含被擦的格（装饰的写入半径也是 1 区块，两个「±1 区块」互为对称）。于是「最后一次擦除」之后
 *       一定还有一次覆盖到该格的 populator，把纯函数算出的值重新写回去。最后一次写它的 populator 是
 *       最后跑的，所以最终结果与区块生成顺序无关。</li>
 *   <li><b>所以写入是幂等的</b>：每一格的值都是 {@code (seed, 柱位, 格)} 的纯函数，被不同区块写多少遍
 *       都是同一个值，重复重放不花钱也不漂移（{@code YungPillarsTest} 用「先擦除、再由相邻区块重放」
 *       以及「九个区块任意顺序」两种方式钉住）。</li>
 *   <li>越界由 {@code isInRegion} 先挡：{@code CraftLimitedRegion} 的 buffer 是 16（本区块 ±1 环），
 *       所以「可达范围」就是它说了算，不自己推算。</li>
 *   <li>全程<b>不读方块</b>：判定只用 {@link IslandField}（地形）与原版的已知足迹，所以既不受
 *       CraftEngine {@code deceive-bukkit-material} 影响，也不需要「先读后写」的顺序保证。</li>
 * </ul>
 *
 * <h2>原版柱芯与笼子的清理</h2>
 * 模板要替代整根柱子，所以原版用黑曜石填出来的那根圆柱（半径 {@code radius}、
 * {@code y = 0..height-1}）与 guarded 柱的 5×5 铁栏杆笼子必须按规则清掉，否则会在模板的空隙里
 * 露出来，变成「叠了两栋建筑」。清理范围严格等于原版自己写过的那一组格子（
 * {@code EndSpikeUtils.removeVanillaPillars} 的同一组集合），规则也同样纯粹：地形说实心就还回
 * end stone，说虚空就写空气。
 *
 * <p>新锚点不再让模板座位落在原版基岩座上，所以原版那一格旧座（{@code (cx, height, cz)} 的
 * {@code bedrock}）与它上面那格火（{@code (cx, height+1, cz)}，{@code EndSpikeFeature} 在
 * {@code addFreshEntity} 之后才写，见 {@code placeSpike} 尾段）也必须一起按地形规则收尾，
 * 否则会在新柱体内部或柱顶留下一块原版基岩与一团火。原版水晶实体不在这里处理——它由
 * {@code YungPillarLifecycle} 在实体事件后搬进模板的水晶洞。
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li>不生成水晶、不碰龙战代码：模板只保证水晶洞那一格是空的，水晶实体由
 *       {@code YungPillarLifecycle} 用 {@code teleportAsync} 搬进新槽（同一实体，不重建、不复制）；</li>
 *   <li>不用 {@code pillar_initial_*}：初始生成在 YUNG 里是「没有水晶、之后由它的龙战代码补」的流程，
 *       本插件没有那套补水晶逻辑，用 initial 会把原版水晶留在模板的水晶洞之外。36 个模板仍然全部加载
 *       并校验（缺一个就启动失败），只是 initial 这 9 个不参与放置。</li>
 *   <li>不做击杀次数追踪：{@code ObsidianProcessor} 只在击杀次数 &gt; 0 时把 obsidian 概率性地换成
 *       crying_obsidian，第一轮 {@code kills = 0} 时概率正好是 0，所以本轮不实现。</li>
 * </ul>
 */
public final class YungPillars {

    /** 模板上半宽（19 宽 ⇒ 9），用来做「本区块要不要干活」的快筛。 */
    static final int HALF_WIDTH = 9;
    /**
     * 上段模板 y=0 层落在哪一格 —— 上游 {@code BetterSpikeFeature.placeSpike} 的
     * {@code int topY = BetterEndIslandCommon.betterEnd ? 70 : 60}，这里取 BetterEnd 的 70。
     */
    public static final int UPPER_BASE_Y = 70;
    /** YUNG {@code centerPos.below(67)}：下段锚点 = 上段锚点 - 67。 */
    public static final int BOTTOM_DROP = 67;
    /** 下段模板 y=0 层落在哪一格（{@link #UPPER_BASE_Y} - {@link #BOTTOM_DROP} = 3）。 */
    public static final int LOWER_BASE_Y = UPPER_BASE_Y - BOTTOM_DROP;
    /** 上段模板高度（{@code size[1]}），下段 67。测试逐模板钉住。 */
    public static final int TOP_HEIGHT = 69;
    public static final int BOTTOM_HEIGHT = 67;
    /** 原版柱脚所在维度最低层（终结维度 {@code min_y = 0}）；更低的格子由 {@code isInRegion} 挡掉。 */
    static final int VANILLA_FLOOR = 0;

    /**
     * {@code IEndSpike#setCrystalYOffsetFromPillarHeight} 的档位表，下标 = 档位，0 号位不用。
     * 原版水晶因此落在 {@code height + 1}：锚点 {@code height + 1 - offset} 再加 {@code offset}。
     */
    private static final int[] CRYSTAL_OFFSET = {0, 22, 22, 26, 27, 27, 32, 32, 32, 38};

    /** 原版 guarded 柱的铁栏杆笼子：5×5 的外圈，4 格高，顶上再加一层盖。 */
    private static final int CAGE_RADIUS = 2;
    private static final int CAGE_HEIGHT = 3;

    private static final String OBSIDIAN = "minecraft:obsidian";
    private static final String CRYING_OBSIDIAN = "minecraft:crying_obsidian";
    private static final String BEDROCK = "minecraft:bedrock";
    private static final String AIR = "minecraft:air";
    private static final String END_STONE = "minecraft:end_stone";
    private static final String CAVE_AIR = "minecraft:cave_air";
    private static final String FIRE = "minecraft:fire";
    private static final String IRON_BARS = "minecraft:iron_bars";

    /**
     * 「这一格只可能是我们或原版写的」方块集合，按方块 id 比较（状态属性不影响归属）。
     *
     * <p>重铺时对每一格先做这道判定：当前状态既不是快照、也不是目标、又不在这个集合里，说明玩家在
     * 受管足迹里放了东西——立即中止并报告，**不凭材料猜所有权**。
     */
    private static final java.util.Set<String> MANAGED_IDS = java.util.Set.of(
            AIR, CAVE_AIR, OBSIDIAN, CRYING_OBSIDIAN, BEDROCK, FIRE, IRON_BARS, END_STONE);

    /** 状态串 → Bukkit BlockData。palette 只有十几种状态，全局缓存一次就够。 */
    private static final Map<String, BlockData> DATA = new ConcurrentHashMap<>();

    private YungPillars() {
    }

    /** 把状态串解析成 {@link BlockData}（生产环境用；测试注入自己的实现，见 {@link StateFactory}）。 */
    @FunctionalInterface
    public interface StateFactory {
        BlockData of(String blockState);
    }

    /** 生产用的解析器：{@code Bukkit.createBlockData}，按状态串缓存。 */
    static BlockData bukkit(String blockState) {
        return DATA.computeIfAbsent(blockState, Bukkit::createBlockData);
    }

    /**
     * 启动预热：把 36 个模板读进内存，并把每个 palette 状态在四向旋转下的样子都解析成
     * {@link BlockData}。
     *
     * <p>为什么在启动时做：模板是世界的组成部分，缺一个必须让世界拒绝生成，而不是等某个区块
     * 刚好长到柱位上才抛异常（那时区块已经被烘焙了一半）；{@code Bukkit.createBlockData} 也只在
     * 主线程安全。调用点是 {@code DustWastelandsGenerator} 的构造器——它已经在做同一件事
     * （CraftEngine 方块解析 + 缺块就抛 {@link IllegalStateException}），并且就在
     * {@code getDefaultGenerator} 里、世界开始生成之前。
     *
     * @throws IllegalStateException 模板缺失或读不动（与缺方块同样的「拒绝这个世界」语义）
     */
    public static void preload() {
        for (int tier = 1; tier <= 9; tier++) {
            for (String type : new String[]{"guarded", "broken", "initial"}) {
                warm(PillarTemplate.load("pillar_" + type + "_" + tier));
            }
            warm(PillarTemplate.load("pillar_bottom_" + tier));
        }
        // 占位块与清理会用到的四个产出块，一并先解析。
        for (String state : new String[]{OBSIDIAN, CRYING_OBSIDIAN, BEDROCK, AIR}) {
            bukkit(state);
        }
    }

    private static void warm(PillarTemplate template) {
        for (int turns = 0; turns < 4; turns++) {
            PillarTemplate rotated = template.rotated(turns);
            for (int i = 0; i < rotated.paletteSize(); i++) {
                if (rotated.paletteKind(i) != PillarTemplate.Kind.SOLID) continue;
                bukkit(rotated.paletteState(i));
            }
        }
    }

    /**
     * 一根柱子这一轮怎么放：档位、模板族、上下段锚点、旋转量。
     *
     * @param tier         1..9，决定模板号与水晶高度偏移
     * @param offset       水晶相对上段锚点的高度（{@link #CRYSTAL_OFFSET}）
     * @param type         {@code guarded} 或 {@code broken}（重生外观，见类注释）
     * @param topAnchorY   上段模板 y=0 层落在哪一格
     * @param bottomAnchorY 下段模板 y=0 层落在哪一格
     * @param rotation     顺时针四分之一圈数，0..3
     */
    public record Layout(int tier, int offset, String type, int topAnchorY, int bottomAnchorY,
                         int rotation) {
        /** 模板座位标记 / 新基岩座所在的 Y（{@code crystalY - 1}）。 */
        public int seatY() {
            return topAnchorY + offset - 1;
        }

        /** 模板水晶洞所在的 Y：官方结构晶必须被搬到这一格（{@code topAnchorY + offset}）。 */
        public int crystalY() {
            return topAnchorY + offset;
        }

        /** 上段模板名（{@code pillar_(guarded|broken)_tier}）。 */
        public String topName() {
            return "pillar_" + type + "_" + tier;
        }

        /** 下段模板名（{@code pillar_bottom_tier}）。 */
        public String bottomName() {
            return "pillar_bottom_" + tier;
        }
    }

    /**
     * {@code (spike.getHeight() - 73) / 3}，第 10 档没有模板，按 9 取（上游 {@code chooseTemplates}）。
     */
    static int tier(int height) {
        return Math.min((height - 73) / 3, 9);
    }

    /** 水晶相对上段锚点的高度。 */
    static int crystalOffset(int tier) {
        if (tier < 1 || tier >= CRYSTAL_OFFSET.length) {
            throw new IllegalArgumentException("档位越界: " + tier);
        }
        return CRYSTAL_OFFSET[tier];
    }

    /**
     * 重生外观的模板族：原版 guarded 柱（铁栏杆笼子）用 {@code guarded}，其余用 {@code broken}。
     * <p>
     * YUNG 在非初次生成时是 20% 随机取 guarded；这里跟着原版的 guarded 标志走，因为「笼子」在原版
     * 世界里本来就是那两根半径 2 的柱子才有，跟着它才不会出现「本该有笼子的位置没笼子」。
     */
    static String type(EndSpikes.Spike spike) {
        return spike.guarded() ? "guarded" : "broken";
    }

    /**
     * 四向旋转：{@code new Random(seed ^ centerX ^ centerZ).nextInt(4)}。
     * <p>
     * YUNG 用的是 {@code RandomSource.create(seed ^ x ^ z)}（{@code LegacyRandomSource}），与
     * {@link Random} 的 48 位 LCG 逐位相同（理由见 {@link EndSpikes} 的类注释），所以这里不需要任何
     * NMS 类型。柱心是种子无关的常量，所以同一根柱子在每个区块、每次重启都得到同一个旋转。
     */
    static int rotation(long seed, int centerX, int centerZ) {
        return new Random(seed ^ (long) centerX ^ (long) centerZ).nextInt(4);
    }

    /**
     * 一根柱子的完整放置参数（新布局：{@link #UPPER_BASE_Y} / {@link #LOWER_BASE_Y}）。
     *
     * <p>公开给 {@code EndLayoutUpgrade} 之类的只读调用方；档位、水晶偏移与四向旋转全部照抄上游，
     * 只有锚点是本插件自己的常量。
     */
    public static Layout layout(EndSpikes.Spike spike, long seed) {
        int tier = tier(spike.height());
        int offset = crystalOffset(tier);
        return new Layout(tier, offset, type(spike), UPPER_BASE_Y, LOWER_BASE_Y,
                rotation(seed, spike.centerX(), spike.centerZ()));
    }

    /** 某个种子下十根柱子的布局（顺序与 {@link EndSpikes#forSeed} 一致）。 */
    public static List<Layout> layouts(long seed) {
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(seed);
        List<Layout> out = new ArrayList<>(spikes.size());
        for (EndSpikes.Spike spike : spikes) {
            out.add(layout(spike, seed));
        }
        return List.copyOf(out);
    }

    /**
     * 把本区块可达范围内、属于柱位的方块写进世界（每格都可由任意可达区块重放，见类注释）。
     *
     * @param region 本区块的可写范围（本区块 + ±1 环）；超出 {@code isInRegion} 的格子不写
     * @param field  岛体场，用来判断「这一格原本是地形还是虚空」
     * @param endStone 还地形用的末地石（生产环境是 {@code generator.endStone}）
     * @return 实际写入的方块数（供测试断言；同一个 region 再跑一遍不会改变任何一格的值）
     */
    public static int build(LimitedRegion region, IslandField field, BlockData endStone,
                            long seed, int chunkX, int chunkZ) {
        return build(region, field, endStone, seed, chunkX, chunkZ, YungPillars::bukkit);
    }

    /** {@link #build(LimitedRegion, IslandField, BlockData, long, int, int)} 的可注入版本。 */
    static int build(LimitedRegion region, IslandField field, BlockData endStone,
                     long seed, int chunkX, int chunkZ, StateFactory states) {
        // 十根柱心都在半径 42 的环上，最远写到的格离柱心 9 格，所以只有 [-(42+9)>>4 - 1, ...] 这些
        // 区块可能有关。这一层让 ~99% 的区块只花两次比较，不再做十次种子洗牌。
        int reach = ((VanillaEndCore.SPIKE_RING_RADIUS + HALF_WIDTH) >> 4) + 2;
        if (Math.abs(chunkX) > reach || Math.abs(chunkZ) > reach) {
            return 0;
        }
        int written = 0;
        for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
            // 模板 19 宽 ⇒ 只会碰到柱心所在的区块及其 ±1 环。
            if (Math.abs((spike.centerX() >> 4) - chunkX) > 1
                    || Math.abs((spike.centerZ() >> 4) - chunkZ) > 1) {
                continue;
            }
            written += buildSpike(region, field, endStone, seed, spike, states);
        }
        return written;
    }

    private static int buildSpike(LimitedRegion region, IslandField field, BlockData endStone,
                                  long seed, EndSpikes.Spike spike, StateFactory states) {
        Layout layout = layout(spike, seed);
        PillarTemplate top = PillarTemplate.load("pillar_" + layout.type() + "_" + layout.tier())
                .rotated(layout.rotation());
        PillarTemplate bottom = PillarTemplate.load("pillar_bottom_" + layout.tier())
                .rotated(layout.rotation());
        // 先清原版，再放模板：两者重叠的格子由模板最终决定，所以清理与放置都不需要知道对方写过什么，
        // 也就不需要任何「本格已写过」的内存状态。
        int written = clearVanilla(region, field, endStone, states, spike);
        written += place(region, field, top, spike.centerX(), layout.topAnchorY(), spike.centerZ(),
                seed, states);
        written += place(region, field, bottom, spike.centerX(), layout.bottomAnchorY(),
                spike.centerZ(), seed, states);
        return written;
    }

    /** 一格：坐标 + 目标状态 + 足迹来源。{@link #key()} 是运行期对账用的紧凑键。 */
    public record Cell(int x, int y, int z, String state, Origin origin) {
        public long key() {
            return pack(x, y, z);
        }
    }

    /** 这一格是谁的足迹。 */
    public enum Origin {
        /** 模板内容（含座位与占位块解析后的最终状态）。 */
        TEMPLATE,
        /** 原版写过的格：柱芯、笼子、旧基岩座、那格火。还原规则是「地形实心→末地石，虚空→空气」。 */
        VANILLA,
        /**
         * 地形修补格：本布局足迹内、地形实心、既不是模板也不是原版清理格的那些格。
         *
         * <p>原版复活会先 {@code removeBlock} 掉 {@code (cx±10, height±10, cz±10)}、再在柱心炸一发
         * 半径约 5 的爆炸、最后按旧 H 重建时又把 {@code y > 65} 的方框清成空气。这些都会在岛面上留下
         * 无法自愈的洞，所以按 {@link IslandField} 把「地形说实心」的格补回末地石。
         * 写的时候**跳过 {@code cave_air}**：那是 {@code CaveCarver} 挖的洞，不是被擦掉的缺口
         * （与 {@code BetterEndPopulator.repairCore} 的判据一致）。
         */
        RESTORE
    }

    /** 逐格回调：生成期把 {@code LimitedRegion} 当接收者，运行期把 {@link Blueprint} 当接收者。 */
    interface CellSink {
        void cell(int x, int y, int z, PillarTemplate.Kind kind, String state);
    }

    /**
     * 枚举一个模板在世界里的全部格（原点按上游 {@code cornerPos = center - size/2}）。
     *
     * <p>生成期与运行期共用：模板写入只有这一个来源，避免「生成时算一套、修柱时算另一套」。
     */
    static void forEachTemplateCell(PillarTemplate template, int centerX, int anchorY, int centerZ,
                                    CellSink sink) {
        int originX = centerX - template.sizeX / 2;
        int originZ = centerZ - template.sizeZ / 2;
        for (int i = 0; i < template.count(); i++) {
            sink.cell(originX + template.x(i), anchorY + template.y(i), originZ + template.z(i),
                    template.kind(i), template.state(i));
        }
    }

    /**
     * 枚举原版在柱位自己写过的全部格（{@code removeVanillaPillars} 的同一组集合 + 旧座 + 火）。
     *
     * <ul>
     *   <li>柱芯：半径 {@code radius} 的圆（{@code dx²+dz² ≤ r²+1}），{@code y = 0..height-1}；</li>
     *   <li>guarded 柱的 5×5 铁栏杆笼子：{@code y = height..height+3} 的外圈与顶盖；</li>
     *   <li>旧基岩座 {@code (cx, height, cz)} 与它上面那格火 {@code (cx, height+1, cz)} —— 原版在
     *       {@code addFreshEntity} 之后才写这两格（{@code EndSpikeFeature.placeSpike} 尾段）。新锚点不再
     *       让模板座位落在旧座上，所以它们必须一起收尾。</li>
     * </ul>
     *
     * <p>目标状态纯由 {@link IslandField} 决定（地形实心→末地石，虚空→空气），全程不读方块。
     */
    static void forEachVanillaCell(EndSpikes.Spike spike, IslandField field, CellSink sink) {
        int r = spike.radius();
        int height = spike.height();
        // 每列只算一次密度（repairCore 的同一套做法）：原版柱身有 21..81 列，逐格 isSolid 会重复
        // 求同一个列的噪声。
        int top = Math.min(height - 1, IslandField.TOP_SOLID_Y);
        double[] column = top >= VANILLA_FLOOR ? new double[top - VANILLA_FLOOR + 1] : null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) {
                    continue;
                }
                int x = spike.centerX() + dx;
                int z = spike.centerZ() + dz;
                if (column != null) {
                    field.densityColumnInto(column, x, z, VANILLA_FLOOR, top);
                }
                for (int y = VANILLA_FLOOR; y < height; y++) {
                    boolean solid = column != null && y <= top && column[y - VANILLA_FLOOR] > 0;
                    sink.cell(x, y, z, PillarTemplate.Kind.SOLID, solid ? END_STONE : AIR);
                }
            }
        }
        if (spike.guarded()) {
            for (int dx = -CAGE_RADIUS; dx <= CAGE_RADIUS; dx++) {
                for (int dz = -CAGE_RADIUS; dz <= CAGE_RADIUS; dz++) {
                    for (int dy = 0; dy <= CAGE_HEIGHT; dy++) {
                        if (Math.abs(dx) != CAGE_RADIUS && Math.abs(dz) != CAGE_RADIUS
                                && dy != CAGE_HEIGHT) {
                            continue;
                        }
                        int x = spike.centerX() + dx;
                        int z = spike.centerZ() + dz;
                        int y = height + dy;
                        sink.cell(x, y, z, PillarTemplate.Kind.SOLID,
                                field.isSolid(x, y, z) ? END_STONE : AIR);
                    }
                }
            }
        }
        int x = spike.centerX();
        int z = spike.centerZ();
        for (int y = height; y <= height + 1; y++) {
            sink.cell(x, y, z, PillarTemplate.Kind.SOLID,
                    field.isSolid(x, y, z) ? END_STONE : AIR);
        }
    }

    /**
     * 写一个模板。原点按上游 {@code cornerPos = center - size/2}，本地坐标见 {@link PillarTemplate}。
     *
     * <p>写的是「本 populator 可达的整段模板」，不是「只写自己区块的格」：可达范围由
     * {@code isInRegion} 决定，理由见类注释的收敛说明。
     */
    private static int place(LimitedRegion region, IslandField field, PillarTemplate template,
                             int centerX, int anchorY, int centerZ, long seed,
                             StateFactory states) {
        int[] written = {0};
        forEachTemplateCell(template, centerX, anchorY, centerZ, (x, y, z, kind, state) -> {
            if (!region.isInRegion(x, y, z)) {
                return;
            }
            written[0] += write(region, field, states, kind, state, x, y, z, seed);
        });
        return written[0];
    }

    /**
     * 写一格模板内容，返回 1 表示确实写了。
     *
     * <p>唯一的偏差在 magenta（模板空气）：地形说实心的格**不写**。生成期那一格要么是刚写好的岛体
     * （照抄模板会把岛体沿 19×19 足迹挖出一条螺旋沟），要么是洞穴（{@code CaveCarver} 已写下的
     * {@code cave_air}，这里不读方块所以看不见它，跳过正好保住洞穴）。
     */
    private static int write(LimitedRegion region, IslandField field, StateFactory states,
                             PillarTemplate.Kind kind, String state, int x, int y, int z, long seed) {
        String target = resolve(kind, state, seed, x, y, z);
        if (kind == PillarTemplate.Kind.MAGENTA && AIR.equals(target) && field.isSolid(x, y, z)) {
            return 0;
        }
        region.setBlockData(x, y, z, states.of(target));
        return 1;
    }

    /**
     * 占位块 → 最终状态（{@code BetterSpikeFeature.PROCESSORS} 的同一组规则，确定性版本）。
     *
     * <p>生成期与运行期共用：所以「重生后重铺」与「初次生成」写出的每一格都必然相同。
     */
    static String resolve(PillarTemplate.Kind kind, String state, long seed, int x, int y, int z) {
        return switch (kind) {
            // gray_concrete 是「这一格是基岩座」的标记。
            case SEAT -> BEDROCK;
            case PURPLE -> OBSIDIAN;
            // BlockReplaceProcessor：obsidian 打底，30% 哭泣黑曜石。
            case ORANGE -> crying(seed, x, y, z, 0.3f) ? CRYING_OBSIDIAN : OBSIDIAN;
            // BlockReplaceProcessor：80% 空气，10% 哭泣黑曜石，10% 黑曜石。
            case MAGENTA -> {
                float roll = roll(seed, x, y, z);
                yield roll < 0.1f ? CRYING_OBSIDIAN : roll < 0.2f ? OBSIDIAN : AIR;
            }
            default -> state;
        };
    }

    /**
     * 清掉原版在模板足迹里留下的柱芯、笼子、旧座与火（集合见 {@link #forEachVanillaCell}）。
     *
     * <p>每一格写成什么与「原来那块是黑曜石还是基岩」无关（全程不读方块）：地形说实心就还回末地石，
     * 说虚空就写空气。这样不会在岛体里留下圆柱形空洞，也不会在虚空里补出一根柱子。
     */
    private static int clearVanilla(LimitedRegion region, IslandField field, BlockData endStone,
                                    StateFactory states, EndSpikes.Spike spike) {
        int[] written = {0};
        forEachVanillaCell(spike, field, (x, y, z, kind, state) -> {
            if (!region.isInRegion(x, y, z)) {
                return;
            }
            region.setBlockData(x, y, z, END_STONE.equals(state) ? endStone : states.of(state));
            written[0]++;
        });
        return written[0];
    }

    // ------------------------------------------------------------------ 运行期蓝图与预检

    /** 一根柱子的内容包围盒（闭区间）。 */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /** 一批「同一个区块、最多 N 格」的写入；运行期按它分桶排区域任务。 */
    public record Batch(int chunkX, int chunkZ, List<Cell> cells) {
        public int size() {
            return cells.size();
        }
    }

    /**
     * 一根柱子的完整重建蓝图：模板格 + 原版残留格，**状态已经全部算好**（占位块解析完毕、原版格按地形
     * 还原），所以「初次生成」与「重生后重铺」写出的每一格必然相同，重铺天然幂等。
     *
     * <p>两类格不重叠：原版残留格枚举时会跳过模板已经占用的坐标，所以一趟写完，不需要「先清后放」的
     * 顺序，也不会有重复写。
     */
    public static final class Blueprint {
        private final Layout layout;
        private final List<Cell> cells;
        private final Bounds bounds;
        private final int templateCells;
        private final int migrationCells;

        private Blueprint(Layout layout, List<Cell> cells, Bounds bounds, int templateCells) {
            this(layout, cells, bounds, templateCells, 0);
        }

        private Blueprint(Layout layout, List<Cell> cells, Bounds bounds, int templateCells,
                          int migrationCells) {
            this.layout = layout;
            this.cells = cells;
            this.bounds = bounds;
            this.templateCells = templateCells;
            this.migrationCells = migrationCells;
        }

        /** 迁移残件格数（只有 {@link #migrationBlueprint} 会非零）。 */
        public int migrationCells() {
            return migrationCells;
        }

        public Layout layout() {
            return layout;
        }

        /** 全部待写格：原版残留格在前、模板格在后，无重复。 */
        public List<Cell> cells() {
            return cells;
        }

        /** 全部待写格的包围盒（含地下段）。 */
        public Bounds bounds() {
            return bounds;
        }

        /** 模板格数（不含原版残留格），供测试与日志核对。 */
        public int templateCells() {
            return templateCells;
        }

        /**
         * 按「区块内最多 {@code maxCells} 格」分桶；桶的顺序稳定（先按区块首次出现顺序，再按 y 递增的
         * 原始顺序切分），所以同一份蓝图两次分桶结果逐格相同。
         *
         * @throws IllegalArgumentException {@code maxCells <= 0}
         */
        public List<Batch> batches(int maxCells) {
            if (maxCells <= 0) {
                throw new IllegalArgumentException("maxCells 必须为正: " + maxCells);
            }
            Map<Long, List<Cell>> byChunk = new LinkedHashMap<>();
            for (Cell cell : cells) {
                long key = ((long) (cell.x() >> 4) << 32) | ((cell.z() >> 4) & 0xFFFFFFFFL);
                byChunk.computeIfAbsent(key, ignored -> new ArrayList<>(64)).add(cell);
            }
            List<Batch> out = new ArrayList<>();
            for (Map.Entry<Long, List<Cell>> entry : byChunk.entrySet()) {
                int chunkX = (int) (entry.getKey() >> 32);
                int chunkZ = (int) (long) entry.getKey();
                List<Cell> all = entry.getValue();
                for (int start = 0; start < all.size(); start += maxCells) {
                    int end = Math.min(start + maxCells, all.size());
                    out.add(new Batch(chunkX, chunkZ, List.copyOf(all.subList(start, end))));
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * 算出一根柱子的重建蓝图。
     *
     * @param field 岛体场（纯种子函数），用来决定原版格还原成末地石还是空气
     */
    public static Blueprint blueprint(EndSpikes.Spike spike, long seed, IslandField field) {
        Layout layout = layout(spike, seed);
        return blueprint(spike, seed, field, layout.topAnchorY(), layout.bottomAnchorY());
    }

    /**
     * 指定上下段锚点算蓝图（{@link #blueprint(EndSpikes.Spike, long, IslandField)} 的通用版）。
     *
     * <p>旧布局（0.1.26 及以前）的锚点是 {@code topAnchorY = height + 1 - offset}、
     * {@code bottomAnchorY = topAnchorY - 67}，见 {@link #legacyBlueprint}：恢复入口要用它算「旧模板足迹」，
     * 才能把「旧柱 + 新柱 + 原版 21³ 盒」的并集一起备份、一起核验。
     */
    public static Blueprint blueprint(EndSpikes.Spike spike, long seed, IslandField field,
                                      int topAnchorY, int bottomAnchorY) {
        Layout layout = new Layout(tier(spike.height()), crystalOffset(tier(spike.height())),
                type(spike), topAnchorY, bottomAnchorY,
                rotation(seed, spike.centerX(), spike.centerZ()));
        PillarTemplate top = PillarTemplate.load(layout.topName()).rotated(layout.rotation());
        PillarTemplate bottom = PillarTemplate.load(layout.bottomName()).rotated(layout.rotation());
        Map<Long, Cell> template = new LinkedHashMap<>();
        CellSink collect = (x, y, z, kind, state) -> template.put(pack(x, y, z),
                new Cell(x, y, z, resolve(kind, state, seed, x, y, z), Origin.TEMPLATE));
        forEachTemplateCell(top, spike.centerX(), layout.topAnchorY(), spike.centerZ(), collect);
        forEachTemplateCell(bottom, spike.centerX(), layout.bottomAnchorY(), spike.centerZ(), collect);

        Map<Long, Cell> leftover = new LinkedHashMap<>();
        forEachVanillaCell(spike, field, (x, y, z, kind, state) -> {
            long key = pack(x, y, z);
            if (template.containsKey(key)) {
                return;
            }
            leftover.put(key, new Cell(x, y, z, state, Origin.VANILLA));
        });

        Map<Long, Cell> restore = restoreCells(spike, field, template, leftover);

        List<Cell> cells = new ArrayList<>(leftover.size() + restore.size() + template.size());
        cells.addAll(leftover.values());
        cells.addAll(restore.values());
        cells.addAll(template.values());
        return new Blueprint(layout, List.copyOf(cells), boundsOf(cells), template.size());
    }

    /**
     * 地形修补格：本布局足迹内、地形实心、既不是模板也不是原版清理格的那些格。
     *
     * <p>范围与「真正可能被挖掉的区域」一致：XZ {@code ±10}（复活时 21³ 清理盒的半径；原版方框清理的
     * {@code ±radius} 也在这个范围内，因为 {@code radius ≤ 5}），y 从 {@code 66}（原版
     * {@code placeSpike} 开始把方框清成空气的高度）到 {@code height+10}（清理盒上沿）。
     * 纯 {@link IslandField} 判定，不读方块。
     */
    private static Map<Long, Cell> restoreCells(EndSpikes.Spike spike, IslandField field,
                                                Map<Long, Cell> template,
                                                Map<Long, Cell> leftover) {
        Map<Long, Cell> restore = new LinkedHashMap<>();
        int minY = 66;
        int maxY = Math.min(spike.height() + 10, IslandField.TOP_SOLID_Y);
        if (maxY < minY) {
            return restore;
        }
        double[] column = new double[maxY - minY + 1];
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                int x = spike.centerX() + dx;
                int z = spike.centerZ() + dz;
                field.densityColumnInto(column, x, z, minY, maxY);
                for (int i = 0; i < column.length; i++) {
                    if (column[i] <= 0) {
                        continue;
                    }
                    int y = minY + i;
                    long key = pack(x, y, z);
                    if (template.containsKey(key) || leftover.containsKey(key)) {
                        continue;
                    }
                    restore.put(key, new Cell(x, y, z, END_STONE, Origin.RESTORE));
                }
            }
        }
        return restore;
    }

    /**
     * 旧布局（0.1.26 及以前）的蓝图：锚点 {@code height+1-offset} / {@code -67}，模板字节与档位旋转不变。
     *
     * <p>只用于「已有世界的恢复」：算旧柱足迹、做对比与备份，不参与生成。
     */
    public static Blueprint legacyBlueprint(EndSpikes.Spike spike, long seed, IslandField field) {
        int offset = crystalOffset(tier(spike.height()));
        int oldTop = spike.height() + 1 - offset;
        return blueprint(spike, seed, field, oldTop, oldTop - BOTTOM_DROP);
    }

    /**
     * 原版自己会在这些格里写方块（柱芯圆柱、笼子、旧座、那格火）的坐标集合。
     *
     * <p>恢复判定要用它把「模板格」分成两类：<b>原版不可能写出来的格</b>（半径外、或 {@code y ≥ height}）
     * 才是所有权证据；落在原版写入集合里的模板格（柱芯那一圈黑曜石）在两边都相同，不能当证据。
     */
    public static java.util.Set<Long> vanillaCells(EndSpikes.Spike spike, IslandField field) {
        java.util.Set<Long> keys = new java.util.HashSet<>();
        forEachVanillaCell(spike, field, (x, y, z, kind, state) -> keys.add(pack(x, y, z)));
        return keys;
    }

    /** 原版复活时 {@code removeBlock} 掉的 21³ 盒：{@code (cx±10, height±10, cz±10)}。 */
    public static Bounds vanillaBox(EndSpikes.Spike spike) {
        return new Bounds(spike.centerX() - 10, spike.height() - 10, spike.centerZ() - 10,
                spike.centerX() + 10, spike.height() + 10, spike.centerZ() + 10);
    }

    /**
     * 迁移蓝图：新模板 + **旧布局模板的残件**（按地形还原）。
     *
     * <p>只给「已有世界的显式恢复」用：0.1.26 的柱子锚点在
     * {@code height+1-offset}，只写新蓝图会把旧柱体留在里面。这里把「旧蓝图有、新蓝图
     * 没有、且不属于原版写入集合」的格按地形补回去（实心→末地石，虚空→空气）；
     * 它们是 {@link Origin#RESTORE}，写的时候会跳过 {@code cave_air}。
     *
     * <p>自动重铺（生成 / 复活）**不**用这份蓝图：健康的新布局世界里那些格本来
     * 就是地形，拉进来只会让玩家改动误伤整根柱。
     */
    public static Blueprint migrationBlueprint(EndSpikes.Spike spike, long seed, IslandField field) {
        return migrationBlueprint(spike, seed, field, VANILLA_FLOOR);
    }

    /**
     * {@link #migrationBlueprint(EndSpikes.Spike, long, IslandField)} 的完整版：额外覆盖
     * {@code [worldMinY, 0)} 这段原版柱芯（MU 的 {@code placeSpike} 从 {@code level.getMinY()} 起写黑曜石）。
     *
     * <p>只进**迁移**写集（{@link Origin#RESTORE}，按地形还原），不进自动重铺的蓝图：把 81×64 格拉进
     * 每一轮冲突判定/回读是纯浪费。
     */
    public static Blueprint migrationBlueprint(EndSpikes.Spike spike, long seed, IslandField field,
                                               int worldMinY) {
        Blueprint now = blueprint(spike, seed, field);
        Blueprint legacy = legacyBlueprint(spike, seed, field);
        Map<Long, Cell> cells = new LinkedHashMap<>(now.cells().size() * 2);
        for (Cell cell : now.cells()) {
            cells.put(cell.key(), cell);
        }
        int added = 0;
        for (Cell cell : legacy.cells()) {
            long key = cell.key();
            if (cells.containsKey(key)) {
                continue;
            }
            String target = field.isSolid(cell.x(), cell.y(), cell.z()) ? END_STONE : AIR;
            cells.put(key, new Cell(cell.x(), cell.y(), cell.z(), target, Origin.RESTORE));
            added++;
        }
        // 原版柱芯在 y<0 的那一段（MU placeSpike 从 worldMinY 起写），只进迁移写集。
        int deepFloor = Math.min(worldMinY, VANILLA_FLOOR);
        if (deepFloor < VANILLA_FLOOR) {
            int radius = spike.radius();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius + 1) {
                        continue;
                    }
                    int x = spike.centerX() + dx;
                    int z = spike.centerZ() + dz;
                    for (int yPos = deepFloor; yPos < VANILLA_FLOOR; yPos++) {
                        long key = pack(x, yPos, z);
                        if (cells.containsKey(key)) {
                            continue;
                        }
                        String target = field.isSolid(x, yPos, z) ? END_STONE : AIR;
                        cells.put(key, new Cell(x, yPos, z, target, Origin.RESTORE));
                        added++;
                    }
                }
            }
        }
        List<Cell> all = new ArrayList<>(cells.values());
        return new Blueprint(now.layout(), List.copyOf(all), boundsOf(all), now.templateCells(), added);
    }

    /** 模板内容的包围盒（不含原版残留格、不含解析为空气的格）。 */
    public static Bounds footprint(EndSpikes.Spike spike, long seed) {
        Layout layout = layout(spike, seed);
        List<Cell> content = new ArrayList<>();
        forEachTemplateCell(PillarTemplate.load(layout.topName()).rotated(layout.rotation()),
                spike.centerX(), layout.topAnchorY(), spike.centerZ(),
                (x, y, z, kind, state) -> {
                    if (!AIR.equals(resolve(kind, state, seed, x, y, z))) {
                        content.add(new Cell(x, y, z, state, Origin.TEMPLATE));
                    }
                });
        forEachTemplateCell(PillarTemplate.load(layout.bottomName()).rotated(layout.rotation()),
                spike.centerX(), layout.bottomAnchorY(), spike.centerZ(),
                (x, y, z, kind, state) -> {
                    if (!AIR.equals(resolve(kind, state, seed, x, y, z))) {
                        content.add(new Cell(x, y, z, state, Origin.TEMPLATE));
                    }
                });
        return boundsOf(content);
    }

    /** 模板**全部**格（含解析为空气的格）的最低世界 Y。 */
    public static int lowestTemplateY(EndSpikes.Spike spike, long seed) {
        int[] min = {Integer.MAX_VALUE};
        forEachSegment(spike, seed, (template, anchorY) ->
                forEachTemplateCell(template, 0, anchorY, 0,
                        (x, y, z, kind, state) -> min[0] = Math.min(min[0], y)));
        return min[0];
    }

    /** 模板**全部**格（含解析为空气的格）的最高世界 Y —— 「真实冠高」，不是用 sizeY 推的。 */
    public static int highestTemplateY(EndSpikes.Spike spike, long seed) {
        int[] max = {Integer.MIN_VALUE};
        forEachSegment(spike, seed, (template, anchorY) ->
                forEachTemplateCell(template, 0, anchorY, 0,
                        (x, y, z, kind, state) -> max[0] = Math.max(max[0], y)));
        return max[0];
    }

    /** 一根柱子的两段（已旋转）及其世界锚点。 */
    private interface SegmentSink {
        void segment(PillarTemplate template, int anchorY);
    }

    private static void forEachSegment(EndSpikes.Spike spike, long seed, SegmentSink sink) {
        Layout layout = layout(spike, seed);
        sink.segment(PillarTemplate.load(layout.topName()).rotated(layout.rotation()),
                layout.topAnchorY());
        sink.segment(PillarTemplate.load(layout.bottomName()).rotated(layout.rotation()),
                layout.bottomAnchorY());
    }

    /**
     * 世界底线/天花板预检：任何一格模板内容落到 {@code [worldMinY, worldMaxY]} 之外都算失败。
     *
     * <p>**不允许静默截断**：{@code LimitedRegion}/{@code isInRegion} 与区块边界只决定「谁写」，不决定
     * 「能不能少写」。真越界就是这次施工失败，保留备份并报告。
     */
    public static boolean withinWorld(EndSpikes.Spike spike, long seed, int worldMinY, int worldMaxY) {
        return lowestTemplateY(spike, seed) >= worldMinY && highestTemplateY(spike, seed) <= worldMaxY;
    }

    /** @throws IllegalStateException 模板内容越出世界底线或天花板（带上具体 Y 与柱心） */
    public static void checkWorldLimits(EndSpikes.Spike spike, long seed, int worldMinY, int worldMaxY) {
        int min = lowestTemplateY(spike, seed);
        int max = highestTemplateY(spike, seed);
        if (min < worldMinY || max > worldMaxY) {
            throw new IllegalStateException("柱 (" + spike.centerX() + "," + spike.centerZ() + ") 的模板内容"
                    + " 落在世界高度 [" + worldMinY + "," + worldMaxY + "] 之外：" + min + ".." + max
                    + "；拒绝静默截断");
        }
    }

    /**
     * 这一格是不是原版结构晶的槽位：柱心 XZ + {@code height + 1}。
     *
     * <p>这是识别「官方结构晶」的第一道几何判据；玩家放的晶不会正好落在柱心这一列的这个高度，而且
     * 原版那一格在我们重铺后会被清掉，所以二次核对（下一 tick）还能把它排除。
     */
    public static boolean isVanillaCrystalSlot(EndSpikes.Spike spike, int blockX, int blockY, int blockZ) {
        return blockX == spike.centerX() && blockZ == spike.centerZ() && blockY == spike.height() + 1;
    }

    /**
     * 这一格要不要写：地形修补格遇到 {@code cave_air} 就跳过。
     *
     * <p>理由是保住洞穴：{@code CaveCarver} 在 y&lt;128 挖出的 {@code cave_air} 是设计的一部分，
     * 而原版清理与爆炸留下的是普通 {@code air}。判据与 {@code BetterEndPopulator.repairCore} 一致。
     */
    public static boolean shouldWrite(Cell cell, String current) {
        return !(cell.origin() == Origin.RESTORE && CAVE_AIR.equals(blockId(current)));
    }

    /** 状态串 → 方块 id（去掉 {@code [属性...]}）。 */
    public static String blockId(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /**
     * 这一格能不能覆盖：当前状态等于目标、等于快照、或者本来就是「我们/原版会写在这里」的方块。
     *
     * <p>三条之外就是玩家在受管足迹里放的东西 —— 重铺必须立即中止并报告，不能顺手清掉。
     *
     * @param snapshot 重铺前的快照状态；{@code null} 表示没有（例如第一遍判定时快照就是他读到的值）
     */
    public static boolean acceptable(String current, String target, String snapshot) {
        return current.equals(target)
                || (snapshot != null && current.equals(snapshot))
                || MANAGED_IDS.contains(blockId(current));
    }

    private static Bounds boundsOf(List<Cell> cells) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Cell cell : cells) {
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
            maxZ = Math.max(maxZ, cell.z());
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 坐标 → 紧凑键（与测试、快照、对账表共用一套编码）。 */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFFL);
    }

    public static int unpackX(long key) {
        return (int) (key >> 42);
    }

    public static int unpackY(long key) {
        return (int) (key & 0xFFFFFL) << 12 >> 12;
    }

    public static int unpackZ(long key) {
        return (int) (key >> 20 & 0x3FFFFF) << 10 >> 10;
    }

    private static boolean crying(long seed, int x, int y, int z, float probability) {
        return roll(seed, x, y, z) < probability;
    }

    /**
     * 一格一次的确定性抽样，落在 {@code [0, 1)}。
     *
     * <p>YUNG 用的是 {@code StructurePlaceSettings#getRandom(BlockPos)}，它的种子构造在本地这份源码里
     * 读不到；但这里要的只是「同一个位置每次都是同一个结果、整体比例对得上」，所以用位置与种子的哈希
     * （SplitMix64 尾部混合，和原来换皮用的 {@code speckled} 同一套路）。用 populator 自己的
     * {@link Random} 是不行的：它按区块播种，同一格被不同区块算一遍会得到不同答案。
     */
    static float roll(long seed, int x, int y, int z) {
        long h = seed;
        h = h * 6364136223846793005L + 1442695040888963407L + x;
        h = h * 6364136223846793005L + 1442695040888963407L + y;
        h = h * 6364136223846793005L + 1442695040888963407L + z;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        // 高 24 位 → [0, 1)，分辨率 1/16777216，对 30%/10% 这种比例足够。
        return (h >>> 40) * 0x1.0p-24f;
    }
}
