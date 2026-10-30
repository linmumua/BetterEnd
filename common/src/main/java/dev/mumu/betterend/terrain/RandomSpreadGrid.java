package dev.mumu.betterend.terrain;

/**
 * @author linmumua
 * @Desc 复现 vanilla {@code minecraft:random_spread} 的「某个区块属于哪一格、该格的候选落点在哪个区块」，用于在写地形之前定位结构台地。
 * @date 2026-10-18
 */
public final class RandomSpreadGrid {
    /* 乘数：vanilla LegacyRandomSource 的线性同余常量 */
    private static final long MULTIPLIER = 25214903917L;
    /* 增量：同上 */
    private static final long INCREMENT = 11L;
    /* 48 位状态掩码：同上 */
    private static final long MASK = 281474976710655L;

    /* 每格宽度（区块数） */
    private final int spacing;
    /* 格内保留边距（区块数） */
    private final int separation;
    /* 该结构的盐值：同一格网格上不同结构靠它去相关 */
    private final int salt;

    /**
     * 类方法的详细使用说明
     * @param spacing 每格宽度，单位是区块，必须为正
     * @param separation 格内保留边距，单位是区块，必须落在 {@code [0, spacing)}
     * @param salt 该结构的盐值
     * @throws IllegalArgumentException 参数越界时抛出
     */
    public RandomSpreadGrid(int spacing, int separation, int salt) {
        if (spacing <= 0) {
            throw new IllegalArgumentException("spacing must be positive: " + spacing);
        }
        if (separation < 0 || separation >= spacing) {
            throw new IllegalArgumentException(
                    "separation must be in [0, spacing): " + separation + " / " + spacing);
        }
        this.spacing = spacing;
        this.separation = separation;
        this.salt = salt;
    }

    /**
     * 类方法的详细使用说明
     * @param seed 世界种子
     * @param chunkX 查询区块的 X
     * @param chunkZ 查询区块的 Z
     * @return 该区块所属格的候选落点，打包成 {@code ChunkPos} 的 long 形式
     */
    public long candidateChunk(long seed, int chunkX, int chunkZ) {
        int cellX = Math.floorDiv(chunkX, spacing);
        int cellZ = Math.floorDiv(chunkZ, spacing);
        long[] state = {
                // setLargeFeatureWithSalt：先按 (格坐标, seed, salt) 组合，再按 LegacyRandomSource 播种
                ((long) cellX * 341873128712L + (long) cellZ * 132897987541L + seed + (long) salt
                        ^ MULTIPLIER) & MASK
        };
        int bound = spacing - separation;
        int offsetX = nextInt(state, bound);
        int offsetZ = nextInt(state, bound);
        return pack(cellX * spacing + offsetX, cellZ * spacing + offsetZ);
    }

    /** 与 {@code ChunkPos.asLong} 一致：高位 X、低位 Z。 */
    public static long pack(int chunkX, int chunkZ) {
        return (long) chunkX & 4294967295L | ((long) chunkZ & 4294967295L) << 32;
    }

    /** 与 {@code ChunkPos.getX} 一致。 */
    public static int unpackX(long packed) {
        return (int) packed;
    }

    /** 与 {@code ChunkPos.getZ} 一致。 */
    public static int unpackZ(long packed) {
        return (int) (packed >> 32);
    }

    /** {@code LegacyRandomSource.next(int)}：右移取高位，状态先前进。 */
    private static int next(long[] state, int bits) {
        state[0] = (state[0] * MULTIPLIER + INCREMENT) & MASK;
        return (int) (state[0] >>> (48 - bits));
    }

    /** {@code BitRandomSource.nextInt(int)}：2 的幂走乘法分支，否则拒绝采样。 */
    private static int nextInt(long[] state, int bound) {
        if ((bound & -bound) == bound) {
            return (int) ((long) bound * (long) next(state, 31) >> 31);
        }
        int bits;
        int value;
        do {
            bits = next(state, 31);
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);
        return value;
    }
}
