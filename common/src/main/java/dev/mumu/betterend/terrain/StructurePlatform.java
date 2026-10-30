package dev.mumu.betterend.terrain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author linmumua
 * @Desc 八个地面结构的落脚台地：按原版真实落点与旋转算出占地矩形，整平、补地基、向外渐变接回地形。
 * @date 2026-10-18
 *
 * <h2>为什么不再用固定圆盘</h2>
 * 旧实现以「候选区块 +8」为圆心画半径 32 的圆。但原版 jigsaw 的模板是从候选区块的
 * <b>西北角</b>按随机旋转向外铺的（{@code JigsawStructure.findGenerationPoint} 取
 * {@code chunkPos.getMinBlockX/Z}，{@code StructureTemplate.getBoundingBox} 绕原点旋转），
 * 33x47 的附魔神殿转 90° 后最远一角离圆心超过 40 格——伸出圆盘的部分直接埋进山体或悬在空中，
 * 圆盘边缘还是一道竖直的断崖。这里改为：
 * <ul>
 *   <li>按原版同一套随机数复算旋转，得到模板的<b>精确</b>占地矩形；</li>
 *   <li>台地高度取占地内自然地表的<b>中位数</b>，而不是单点，挖填量最小；</li>
 *   <li>占地外 {@link #MARGIN} 格同高，再用 {@link #SKIRT} 格平滑过渡回自然地表，不留断崖；</li>
 *   <li>地基只往下补有限深度（或接到下方现成的岛体），不再从台地一路实心到世界底；</li>
 *   <li>只清空结构自身高度以内的上方地形，不把头顶的浮岛开一个矩形天井；</li>
 *   <li>占地大半落在虚空的落点不造台地，结构由 {@code StructureWorldGuard} 直接取消，
 *       不会出现半截伸出岛边的建筑；</li>
 *   <li>带 {@code frequency} 的结构（地牢塔 0.1）按原版同一算法过滤，不再给九成不会生成的
 *       落点白白削出平地。</li>
 * </ul>
 */
public final class StructurePlatform {
    /** 占地矩形外与台地同高的保留边（方块）。 */
    public static final int MARGIN = 3;
    /** 保留边之外渐变回自然地表的宽度（方块）。 */
    public static final int SKIRT = 8;
    /** 地基在占地内的深度；向保留边外缘线性收到 {@link #FOUNDATION_EDGE}。 */
    static final int FOUNDATION_CORE = 12;
    static final int FOUNDATION_EDGE = 4;
    /** 台地下方这么深之内若有现成岛体，地基一直补到它，避免悬一块薄板。 */
    static final int BRIDGE_DEPTH = 40;
    /** 结构顶上再多清几格，避免屋顶贴着地形。 */
    static final int HEADROOM = 3;
    /** 占地采样中虚空列超过这个比例就放弃该落点。 */
    static final double MAX_VOID_FRACTION = 0.3;
    /** 占地采样步长（方块）。 */
    private static final int SAMPLE_STEP = 4;

    /* 每格宽度与保留边距（区块），九个 Echoes 结构统一 160/16 */
    private static final int SPACING = 160;
    private static final int SEPARATION = 16;
    private static final int OFFSET_RANGE = SPACING - SEPARATION;

    /**
     * 一个地面结构的放置参数。尺寸取自模板 NBT 的 {@code size}（x, y, z），盐与频率取自
     * {@code structure_set}；{@code StructurePlatformTest} 逐项对照数据包文件，改了任何一边都会报。
     */
    public record Kind(String name, int salt, float frequency, int sizeX, int sizeY, int sizeZ) {
    }

    static final Kind[] KINDS = {
            new Kind("crying_obsidian_tower", 1275595292, 1f, 9, 58, 9),
            new Kind("end_camp", 910165693, 1f, 15, 7, 13),
            new Kind("end_dungeon_tower", 1054309675, 0.1f, 23, 102, 23),
            new Kind("end_enchanted_temple", 479948289, 1f, 33, 37, 47),
            new Kind("end_pillar", 1068651703, 1f, 8, 16, 8),
            new Kind("end_shulker_tower", 1322633544, 1f, 15, 23, 14),
            new Kind("end_temple", 1143842385, 1f, 21, 18, 21),
            new Kind("end_well", 250943603, 1f, 5, 7, 5),
    };

    /** 占地矩形到台地影响范围外缘的距离。 */
    private static final int REACH = MARGIN + SKIRT;
    /** 跨格探测步长（区块）：最大占地 47 格 + {@link #REACH} 向上取整到区块。 */
    static final int PROBE_CHUNKS = (47 + REACH + 15) >> 4;

    private static final Map<Key, Spot> CACHE = new ConcurrentHashMap<>();
    /** 缓存里「这个格子没有台地」的占位。 */
    private static final Spot NONE = new Spot(0, 0, -1, -1, Integer.MIN_VALUE, 0);

    private record Key(IslandField field, long seed, int kind, long chunk) {
    }

    private StructurePlatform() {
    }

    /**
     * 一块台地。
     *
     * @param minX 占地矩形（模板旋转后的真实范围）
     * @param minZ 同上
     * @param maxX 同上（含）
     * @param maxZ 同上（含）
     * @param top 台地顶面 Y；结构的 y=0 层正好落在这一层（原版 ground level delta = 1）
     * @param height 结构模板高度，决定上方清空到哪
     */
    public record Spot(int minX, int minZ, int maxX, int maxZ, int top, int height) {
        /** 列到占地矩形的切比雪夫距离；在矩形内为 0。 */
        int distance(int x, int z) {
            int dx = Math.max(0, Math.max(minX - x, x - maxX));
            int dz = Math.max(0, Math.max(minZ - z, z - maxZ));
            return Math.max(dx, dz);
        }
    }

    /**
     * 与某区块相交的台地。
     *
     * @param field 岛体场，用来取占地内的自然地表高度
     * @param seed 世界种子
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @return 与本区块相交的台地，可能为空数组
     */
    public static Spot[] spots(IslandField field, long seed, int chunkX, int chunkZ) {
        // 候选偏移落在格内 [0, 144) 区块，格尾留 16 区块空档。模板从候选区块西北角向任意方向
        // 铺开，占地加影响范围最多向西/北伸出 47 + 11 = 58 格（不到 4 个区块），可能越过格边界
        // 落进上一格的尾部空档。所以除了本区块所在的格，还要查「往东/南 4 个区块」所在的格——
        // 那几格的台地才可能伸到这里。向东/南伸出的部分最多到偏移 143 + 4 区块，仍在本格内。
        Spot[] found = new Spot[KINDS.length * 4];
        int count = 0;
        for (int i = 0; i < KINDS.length; i++) {
            RandomSpreadGrid grid = new RandomSpreadGrid(SPACING, SEPARATION, KINDS[i].salt());
            long[] seen = new long[4];
            int seenCount = 0;
            probes:
            for (int probe = 0; probe < 4; probe++) {
                long candidate = grid.candidateChunk(seed,
                        chunkX + (probe & 1) * PROBE_CHUNKS, chunkZ + (probe >> 1) * PROBE_CHUNKS);
                for (int s = 0; s < seenCount; s++) {
                    if (seen[s] == candidate) continue probes;
                }
                seen[seenCount++] = candidate;
                final int kindIndex = i;
                Spot spot = CACHE.computeIfAbsent(new Key(field, seed, i, candidate),
                        k -> resolve(field, seed, KINDS[kindIndex], RandomSpreadGrid.unpackX(k.chunk()),
                                RandomSpreadGrid.unpackZ(k.chunk())));
                if (spot != NONE && touches(spot, chunkX, chunkZ)) {
                    found[count++] = spot;
                }
            }
        }
        Spot[] trimmed = new Spot[count];
        System.arraycopy(found, 0, trimmed, 0, count);
        return trimmed;
    }

    /**
     * 原版会不会在这里放下这个地面结构、而本插件判定它该被取消（落点大半是虚空）。
     * 供 {@code StructureWorldGuard} 在 {@code AsyncStructureSpawnEvent} 里调用。
     *
     * @param name 结构路径名（{@code mumu:end_temple} 的 {@code end_temple}）
     * @return 只有「是本类管理的地面结构、通过了频率过滤、但没有台地」时为 true
     */
    public static boolean rejects(IslandField field, long seed, String name, int chunkX, int chunkZ) {
        for (int i = 0; i < KINDS.length; i++) {
            Kind kind = KINDS[i];
            if (!kind.name().equals(name)) {
                continue;
            }
            if (!passesFrequency(seed, kind, chunkX, chunkZ)) {
                return false; // 原版自己就不会生成，轮不到这里
            }
            Spot spot = CACHE.computeIfAbsent(
                    new Key(field, seed, i, RandomSpreadGrid.pack(chunkX, chunkZ)),
                    k -> resolve(field, seed, kind, chunkX, chunkZ));
            return spot == NONE;
        }
        return false;
    }

    /**
     * 该列整形后的顶面：台地内是台地高度，裙边是台地与自然地表的平滑插值，其余是自然地表。
     * 与 {@link #shapeColumn} 对同一列给出同一个答案（{@code StructurePlatformTest} 逐列核对），
     * 所以 {@code getBaseHeight} 与尖塔落点看到的就是 {@code generateNoise} 真正写出来的地形。
     *
     * @return 顶面 Y，虚空为 {@link Integer#MIN_VALUE}
     */
    public static int surfaceTop(Spot[] spots, IslandField field, int x, int z) {
        Spot spot = nearest(spots, x, z);
        if (spot == null) {
            return field.topY(x, z);
        }
        int d = spot.distance(x, z);
        if (d <= MARGIN) {
            return spot.top();
        }
        // 裙边只认「结构高度以内」的自然顶面：更高处悬着的浮岛不算地表，也不会被削掉。
        int natural = field.topY(x, z, 0, ceiling(spot));
        if (natural == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE; // 裙边落在虚空：不往外伸平台
        }
        return blend(spot.top(), natural, d);
    }

    /**
     * 台地整形会动到的最高 Y：结构顶再加 {@link #HEADROOM}。
     * <p>
     * {@link #surfaceTop} 在台地影响范围内只回答这条线以下的地表——结构头顶更高处悬着的浮岛
     * 不改、也不算数。否则 {@code WORLD_SURFACE_WG} 投影会落到那座浮岛顶上，结构整个被搬上天。
     */
    static int ceiling(Spot spot) {
        return spot.top() + spot.height() + HEADROOM;
    }

    /** 列所属台地的整形上限；不在任何台地影响范围内返回 {@link Integer#MAX_VALUE}。 */
    public static int ceilingAt(Spot[] spots, int x, int z) {
        Spot spot = nearest(spots, x, z);
        return spot == null ? Integer.MAX_VALUE : ceiling(spot);
    }

    /**
     * 把一列密度就地整形成 {@link #surfaceTop} 描述的形状。
     * <p>
     * 只动「需要动」的那一段：台地内清空结构高度以内的上方、补有限深的地基；裙边只在自然顶面与
     * 目标顶面之间削或垫。更高的浮岛、更深的洞穴与下层岛体保持原样。
     *
     * @param column {@code [minY, minY + length - 1]} 的密度列，就地修改
     * @return 是否改动了该列
     */
    public static boolean shapeColumn(double[] column, int minY, Spot[] spots, int x, int z) {
        Spot spot = nearest(spots, x, z);
        if (spot == null) {
            return false;
        }
        int natural = topBelow(column, minY, ceiling(spot), minY);
        int d = spot.distance(x, z);
        int top = spot.top();
        if (d <= MARGIN) {
            set(column, minY, top + 1, ceiling(spot), -1.0);
            int depth = d == 0 ? FOUNDATION_CORE
                    : FOUNDATION_CORE - (FOUNDATION_CORE - FOUNDATION_EDGE) * d / MARGIN;
            int floor = top - depth + 1;
            // 下方不远处有现成岛体：地基一直接到它，免得在岛上方悬一块薄板。
            int below = topBelow(column, minY, floor - 1, top - BRIDGE_DEPTH);
            if (below != Integer.MIN_VALUE) {
                floor = below + 1;
            }
            set(column, minY, floor, top, 1.0);
            return true;
        }
        if (natural == Integer.MIN_VALUE) {
            return false;
        }
        int target = blend(top, natural, d);
        if (target > natural) {
            set(column, minY, natural + 1, target, 1.0);
        } else if (target < natural) {
            set(column, minY, target + 1, natural, -1.0);
            // 削到的这一格可能正好是洞穴/悬垂下的空气：补实，顶面才真的落在 target。
            set(column, minY, target, target, 1.0);
        }
        return target != natural;
    }

    /** 供测试断言用：一格里候选偏移的上界。 */
    static int offsetRange() {
        return OFFSET_RANGE;
    }

    /** 测试与外部调用清缓存用（例如换种子做大量采样时）。 */
    static void clearCache() {
        CACHE.clear();
    }

    // ------------------------------------------------------------------ 内部

    /** 计算候选区块上该结构的台地；放不下（频率未中、虚空过多）返回 {@link #NONE}。 */
    private static Spot resolve(IslandField field, long seed, Kind kind, int chunkX, int chunkZ) {
        if (!passesFrequency(seed, kind, chunkX, chunkZ)) {
            return NONE;
        }
        int[] box = footprint(seed, kind, chunkX, chunkZ);
        int samples = 0;
        int voids = 0;
        int[] tops = new int[((box[2] - box[0]) / SAMPLE_STEP + 2) * ((box[3] - box[1]) / SAMPLE_STEP + 2)];
        int n = 0;
        for (int x = box[0]; ; x = Math.min(x + SAMPLE_STEP, box[2])) {
            for (int z = box[1]; ; z = Math.min(z + SAMPLE_STEP, box[3])) {
                samples++;
                int t = field.topY(x, z);
                if (t == Integer.MIN_VALUE) {
                    voids++;
                } else {
                    tops[n++] = t;
                }
                if (z == box[3]) break;
            }
            if (x == box[2]) break;
        }
        if (n == 0 || voids > samples * MAX_VOID_FRACTION) {
            return NONE;
        }
        java.util.Arrays.sort(tops, 0, n);
        return new Spot(box[0], box[1], box[2], box[3], tops[n / 2], kind.sizeY());
    }

    /**
     * 原版模板的占地矩形 {@code {minX, minZ, maxX, maxZ}}。
     * <p>
     * 复算 {@code JigsawPlacement.addPieces} 的第一个随机数：结构随机源是
     * {@code setLargeFeatureSeed(seed, chunkX, chunkZ)}，常量 {@code start_height} 不消耗随机数，
     * 紧接着 {@code Rotation.getRandom} 即 {@code nextInt(4)}（NONE / CW90 / 180 / CCW90）。
     * 模板以候选区块西北角为原点、绕原点旋转（pivot 为零），与 {@code StructureTemplate.transform} 一致。
     */
    static int[] footprint(long seed, Kind kind, int chunkX, int chunkZ) {
        int rotation = rotation(seed, chunkX, chunkZ);
        int ox = chunkX << 4;
        int oz = chunkZ << 4;
        int ex = kind.sizeX() - 1;
        int ez = kind.sizeZ() - 1;
        return switch (rotation) {
            case 1 -> new int[]{ox - ez, oz, ox, oz + ex};          // CLOCKWISE_90: (x,z) -> (-z, x)
            case 2 -> new int[]{ox - ex, oz - ez, ox, oz};          // CLOCKWISE_180: (x,z) -> (-x, -z)
            case 3 -> new int[]{ox, oz - ex, ox + ez, oz};          // COUNTERCLOCKWISE_90: (x,z) -> (z, -x)
            default -> new int[]{ox, oz, ox + ex, oz + ez};         // NONE
        };
    }

    /** 原版 {@code Rotation.getRandom(context.random())} 的结果序号。 */
    static int rotation(long seed, int chunkX, int chunkZ) {
        long[] state = {scramble(seed)};
        long xScale = nextLong(state);
        long zScale = nextLong(state);
        state[0] = scramble((long) chunkX * xScale ^ (long) chunkZ * zScale ^ seed);
        // nextInt(4)：bound 是 2 的幂，走 (bound * next(31)) >> 31。
        return (int) ((4L * next(state, 31)) >> 31);
    }

    /**
     * 原版 {@code FrequencyReductionMethod.DEFAULT}：{@code setLargeFeatureWithSalt(seed, salt, x, z)}
     * 后 {@code nextFloat() < frequency}。注意原版把 salt 放在 x 参数位、区块坐标顺延，这里照抄。
     */
    static boolean passesFrequency(long seed, Kind kind, int chunkX, int chunkZ) {
        if (kind.frequency() >= 1f) {
            return true;
        }
        long[] state = {scramble((long) kind.salt() * 341873128712L
                + (long) chunkX * 132897987541L + seed + chunkZ)};
        return next(state, 24) * 5.9604645E-8F < kind.frequency();
    }

    private static Spot nearest(Spot[] spots, int x, int z) {
        Spot best = null;
        int bestD = Integer.MAX_VALUE;
        for (Spot spot : spots) {
            int d = spot.distance(x, z);
            if (d <= REACH && d < bestD) {
                best = spot;
                bestD = d;
            }
        }
        return best;
    }

    /** 裙边插值：smoothstep，保留边外缘取台地高度，裙边外缘取自然高度。 */
    private static int blend(int top, int natural, int d) {
        double t = (double) (d - MARGIN) / (SKIRT + 1);
        t = t * t * (3 - 2 * t);
        return (int) Math.round(top + (natural - top) * t);
    }

    private static boolean touches(Spot spot, int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        return minX - REACH <= spot.maxX() && minX + 15 + REACH >= spot.minX()
                && minZ - REACH <= spot.maxZ() && minZ + 15 + REACH >= spot.minZ();
    }

    /** {@code [lowest, from]} 里自上而下第一格实心的 Y。 */
    private static int topBelow(double[] column, int minY, int from, int lowest) {
        for (int y = Math.min(from, minY + column.length - 1); y >= Math.max(lowest, minY); y--) {
            if (column[y - minY] > 0) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static void set(double[] column, int minY, int from, int to, double value) {
        int lo = Math.max(from, minY);
        int hi = Math.min(to, minY + column.length - 1);
        for (int y = lo; y <= hi; y++) {
            double current = column[y - minY];
            if (value > 0 ? current <= 0 : current > 0) {
                column[y - minY] = value;
            }
        }
    }

    // vanilla LegacyRandomSource
    private static final long MULTIPLIER = 25214903917L;
    private static final long MASK = 281474976710655L;

    private static long scramble(long seed) {
        return (seed ^ MULTIPLIER) & MASK;
    }

    private static int next(long[] state, int bits) {
        state[0] = (state[0] * MULTIPLIER + 11L) & MASK;
        return (int) (state[0] >> (48 - bits));
    }

    private static long nextLong(long[] state) {
        int upper = next(state, 32);
        int lower = next(state, 32);
        return ((long) upper << 32) + lower;
    }
}
