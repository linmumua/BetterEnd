package dev.mumu.betterend.biome;

import dev.mumu.betterend.terrain.IslandField;
import dev.mumu.betterend.terrain.OpenSimplexNoise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldWeaver's {@code HexBiomeMap} + {@code HexBiomeChunk}, ported. This is what actually decides
 * which biome a block column gets, and it replaces the port's invented 128-block square lattice.
 * <p>
 * Two things stack. A rotated, noise-distorted HEXAGONAL lattice picks a cell
 * (HexBiomeMap.java:99-136); at the size BetterEnd runs (256, so {@code scale} 32) that is a row
 * pitch of 32 blocks, a column pitch of 36.95, odd rows staggered half a column, and the whole
 * lattice turned 0.4 rad = 22.918 degrees off the world axes -- NO cell edge is axis-aligned.
 * Then a 32x32 grid of those cells is grouped into ragged blobs by a seeded flood fill
 * (HexBiomeChunk.java:26-95), so one biome covers roughly 64 cells, about 275 blocks across.
 * <p>
 * There is NO smoothing, dithering or blending, and adding any would be less faithful, not more.
 * The mod's only boundary mechanism is edge-biome substitution (HexBiomeMap.java:56-77) and
 * BetterEnd defines no edge biomes ({@code edgeSize} 0, {@code edge} null everywhere), so
 * {@code getBiome} short-circuits at :66-68 and the boundary IS a hard per-cell switch. What makes
 * it read as organic is the cell SHAPE and SIZE plus the high-frequency distortion, not smoothing.
 * <p>
 * Immutable after construction apart from the chunk memo, which is a pure function of
 * (seed, cx, cz), so every method is safe from any number of worldgen or region threads.
 * <p>
 * ponytail: {@code MapStack} is not ported and must not be - the End builds four flat maps
 * (WoverEndBiomeSource.java:211-233); the stack is nether-only (WoverNetherBiomeSource.java:9,:120).
 */
final class HexBiomeMap {
    /** HexBiomeMap.java:20-24. Computed as {@code float}, exactly as written: a double
     *  {@code sin(0.4)} moves every cell boundary by a fraction of a block. */
    private static final float RAD_INNER = (float) Math.sqrt(3.0) * 0.5F;
    private static final float COEF = 0.25F * (float) Math.sqrt(3.0);
    private static final float COEF_HALF = COEF * 0.5F;
    private static final float SIN = (float) Math.sin(0.4);
    private static final float COS = (float) Math.cos(0.4);

    /** HexBiomeChunk.java:12-21. SIDE 32, SIZE 1024, MAX_SIDE 992, SIDE_OFFSET 5, SIDE_MASK 31. */
    private static final int SIDE = 32;
    private static final int SIDE_OFFSET = 5;
    private static final int SIDE_MASK = SIDE - 1;
    private static final int SIZE = SIDE * SIDE;
    private static final int MAX_SIDE = SIZE - SIDE;
    /** HexBiomeChunk.java:13,16,17 - 16 seed points on a 4x4 grid of 8x8 blocks of cells. */
    private static final int SIDE_PRE = 4;
    private static final int SIZE_PRE = SIDE_PRE * SIDE_PRE;
    private static final int SCALE_PRE = SIDE / SIDE_PRE;

    /**
     * HexBiomeChunk.java:154-170 - the six hex neighbours by row parity. Row parity is
     * {@code index & 1} because {@code index = (x << 5) | z}, so the low bit is z's.
     */
    private static final int[][] NEIGHBOURS = {
            {1, -1, SIDE, -SIDE, SIDE + 1, SIDE - 1},
            {1, -1, SIDE, -SIDE, -SIDE + 1, -SIDE - 1},
    };

    private final Picker picker;
    private final OpenSimplexNoise[] noises = new OpenSimplexNoise[2];
    private final int noiseIterations;
    private final float scale;
    private final int seed;

    /**
     * Memo of the 32x32 cell chunks. Pure function of (seed, cx, cz), so caching it cannot change
     * what generates or make anything depend on generation order.
     * <p>
     * get/put rather than {@code computeIfAbsent} for the same reason as everywhere else in this
     * package: a lost race just builds an identical chunk, and nothing that recurses can sneak in
     * later. ponytail: cleared wholesale past a cap rather than LRU-evicted (the mod clears at 127,
     * HexBiomeMap.java:49-53).
     */
    private final ConcurrentHashMap<Long, BiomeSurface[]> chunks = new ConcurrentHashMap<>();

    /**
     * HexBiomeMap.java:37-46. THREE {@code nextInt()} draws off {@code new Random(worldSeed)}, in
     * this order: two noise seeds and the chunk-hash seed. Every map built from one world seed
     * therefore shares one chunk-hash seed, which is why the four rings' blob GEOMETRY is identical
     * and only the picker differs (WoverEndBiomeSource.java:211-233 builds them all from
     * {@code newSeed}).
     *
     * @param size the ring's biome size in blocks: 256 for land/void/center/barrens, 128 for caves
     *             (WoverEndConfig.java:86-93 via :104-111)
     */
    HexBiomeMap(long worldSeed, int size, Picker picker) {
        this.picker = picker;
        // HexBiomeChunk.java:150-152 - scaleMap(size) = size / (SIDE >> 2) = size / 8.
        this.scale = size / (float) (SIDE >> 2);
        Random random = new Random(worldSeed);
        noises[0] = new OpenSimplexNoise(random.nextInt());
        noises[1] = new OpenSimplexNoise(random.nextInt());
        this.noiseIterations = (int) Math.min(Math.ceil(Math.log(scale) / Math.log(2)), 5);
        this.seed = random.nextInt();
    }

    /**
     * HexBiomeMap.java:99-136, verbatim, at BLOCK coordinates - the mod calls its map with
     * {@code QuartPos.toBlock(biomeX)} (WoverEndBiomeSource.java:302-304,:358-361).
     * <p>
     * {@code Math.floor}, not {@code MHelper.floor}: the hex path uses the correct floor. And
     * {@code pointX}/{@code pointZ} are narrowed to {@code float} before the hexagon test, which is
     * load-bearing - keeping them double shifts the boundary.
     */
    BiomeSurface at(int x, int z) {
        double px = x / scale * RAD_INNER;
        double pz = z / scale;
        // Rotate 0.4 rad, so no cell edge is parallel to a world axis.
        double dx = px * COS - pz * SIN;
        double dz = px * SIN + pz * COS;
        px = dx;
        pz = dz;

        // HexBiomeMap.java:107-108 - note the ARGUMENTS ARE SWAPPED on the second call.
        dx = getNoise(px, pz, 0) * 0.2F;
        dz = getNoise(pz, px, 1) * 0.2F;
        px += dx;
        pz += dz;

        int cellZ = (int) Math.floor(pz);
        boolean offset = (cellZ & 1) == 1;
        if (offset) {
            px += 0.5;
        }
        int cellX = (int) Math.floor(px);

        float pointX = (float) (px - cellX - 0.5);
        float pointZ = (float) (pz - cellZ - 0.5);

        if (Math.abs(pointZ) < 0.3333F) {
            return chunkBiome(cellX, cellZ);
        }
        if (insideHexagon(pointZ * RAD_INNER, pointX)) {
            return chunkBiome(cellX, cellZ);
        }
        cellX = pointX < 0 ? (offset ? cellX - 1 : cellX) : (offset ? cellX : cellX + 1);
        cellZ = pointZ < 0 ? cellZ - 1 : cellZ + 1;
        return chunkBiome(cellX, cellZ);
    }

    /** HexBiomeMap.java:153-157. */
    private static boolean insideHexagon(float x, float z) {
        double dx = Math.abs(x) / (float) 1.1555;
        double dy = Math.abs(z) / (float) 1.1555;
        return (dy <= COEF) && (COEF * dx + 0.25F * dy <= COEF_HALF);
    }

    /**
     * HexBiomeMap.java:159-167 - five octaves at frequency multipliers 1,2,3,4,5 (NOT powers of
     * two) and amplitudes 1/i, alternating between the two noise INSTANCES every octave. The sum of
     * amplitudes is 2.2833 and the caller scales by 0.2, so the distortion is bounded at about
     * +-0.457 CELL units. Inputs are in cell units, so at scale 32 the block frequencies are
     * 1/32 = 0.03125 (z) and RAD_INNER/32 = 0.02706 (x), times 1..5 - high frequency and low
     * amplitude, which BENDS a cell edge rather than translating it.
     */
    private double getNoise(double x, double z, int state) {
        double result = 0;
        for (int i = 1; i <= noiseIterations; i++) {
            OpenSimplexNoise noise = noises[state];
            state = (state + 1) & 1;
            result += noise.eval(x * i, z * i) / i;
        }
        return result;
    }

    /**
     * HexBiomeMap.java:138-151 - chunk decomposition plus the SEAM HIDER: a checkerboard of border
     * cells adopts the neighbouring chunk's cell 0, so the 32-cell chunk grid never reads as a
     * straight line. Not a blend; a substitution. {@code >>} is arithmetic, so negatives work.
     */
    private BiomeSurface chunkBiome(int x, int z) {
        int cx = x >> SIDE_OFFSET;
        int cz = z >> SIDE_OFFSET;
        if (((z >> 2) & 1) == 0 && (x & SIDE_MASK) == SIDE_MASK) {
            x = 0;
            cx += 1;
        } else if (((x >> 2) & 1) == 0 && (z & SIDE_MASK) == SIDE_MASK) {
            z = 0;
            cz += 1;
        }
        // HexBiomeChunk.java:124-126 - getBiome re-masks both coordinates with & 31.
        return chunk(cx, cz)[index(x & SIDE_MASK, z & SIDE_MASK)];
    }

    private BiomeSurface[] chunk(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        BiomeSurface[] cached = chunks.get(key);
        if (cached != null) {
            return cached;
        }
        if (chunks.size() > 1 << 12) {
            chunks.clear();
        }
        BiomeSurface[] built = build(cx, cz);
        chunks.put(key, built);
        return built;
    }

    private static int index(int x, int z) {
        return (x << SIDE_OFFSET) | z;
    }

    /**
     * HexBiomeChunk.java:26-95 - four RNG phases in this exact order. Every phase consumes a draw
     * count that does not depend on WHICH biome comes back ({@code pick} is always one
     * {@code nextFloat}, {@code subBiome} always one {@code nextDouble}), which is why the land,
     * void, center and barrens maps land on byte-identical blob geometry and differ only in names.
     * <p>
     * HexBiomeMap.java:84 + MathHelper.java:72-76 for the chunk RNG. {@code LegacyRandomSource} is
     * java.util.Random's 48-bit LCG and its {@code nextInt(bound)} / {@code nextFloat()} /
     * {@code nextDouble()} are bit-identical, so java.util.Random is a valid substitute.
     */
    private BiomeSurface[] build(int cx, int cz) {
        Random random = new Random(IslandField.mixSeed(seed, cx, cz));
        BiomeSurface[][] buffers = new BiomeSurface[2][SIZE];

        // 1. Sixteen seed points, one per 8x8 block of cells, each stamped as a 7-cell rosette.
        for (int i = 0; i < SIZE_PRE; i++) {
            int px = (i >> 2) * SCALE_PRE + random.nextInt(SCALE_PRE);
            int pz = (i & (SIDE_PRE - 1)) * SCALE_PRE + random.nextInt(SCALE_PRE);
            circle(buffers[0], index(px, pz), picker.pick(random), null);
        }

        // 2. Flood fill. Every non-null interior cell copies itself across and spreads into ONE
        // random hex neighbour if that slot is still null. The buffers alternate and are never
        // cleared, so growth is monotone: 16 ragged blobs of roughly 64 cells each.
        boolean hasEmptyCells = true;
        int bufferIndex = 0;
        while (hasEmptyCells) {
            BiomeSurface[] in = buffers[bufferIndex];
            bufferIndex = (bufferIndex + 1) & 1;
            BiomeSurface[] out = buffers[bufferIndex];
            hasEmptyCells = false;
            for (int i = SIDE; i < MAX_SIDE; i++) {
                int z = i & SIDE_MASK;
                if (z == 0 || z == SIDE_MASK) {
                    continue;
                }
                if (in[i] != null) {
                    out[i] = in[i];
                    int side = i + NEIGHBOURS[z & 1][random.nextInt(6)];
                    if (side >= 0 && side < SIZE && out[side] == null) {
                        out[side] = in[i];
                    }
                } else {
                    hasEmptyCells = true;
                }
            }
        }

        // 3. Border copy: rows and columns 0 and 31 are overwritten from 2 and 29.
        BiomeSurface[] out = buffers[bufferIndex];
        int preN = SIDE_MASK - 2;
        for (int i = 0; i < SIDE; i++) {
            out[index(i, 0)] = out[index(i, 2)];
            out[index(0, i)] = out[index(2, i)];
            out[index(i, SIDE_MASK)] = out[index(i, preN)];
            out[index(SIDE_MASK, i)] = out[index(preN, i)];
        }

        // 4. Sub-biome speckle, in index order over all 1024 cells. The mask is the cell's OWN
        // biome, so the 7-cell rosette only overwrites neighbours holding the same parent.
        for (int i = 0; i < SIZE; i++) {
            if (out[i] == null) {
                out[i] = picker.pick(random);
            } else if (random.nextInt(4) == 0) {
                circle(out, i, picker.subBiome(out[i], random), out[i]);
            }
        }
        return out;
    }

    /** HexBiomeChunk.java:97-113 - centre plus all six neighbours, wherever the slot equals mask. */
    private static void circle(BiomeSurface[] buffer, int center, BiomeSurface biome, BiomeSurface mask) {
        if (buffer[center] == mask) {
            buffer[center] = biome;
        }
        for (int offset : NEIGHBOURS[center & 1]) {
            int i = center + offset;
            if (i >= 0 && i < SIZE && buffer[i] == mask) {
                buffer[i] = biome;
            }
        }
    }

    /**
     * WoverBiomePicker.rebuild (WoverBiomePicker.java:178-221) plus the sub-biome list a
     * PickableBiome carries (WoverBiomePicker.java:269-276).
     * <p>
     * The ORDER is load-bearing: WoverBiomePicker.java:186-189 sorts by
     * {@code biomeKey.identifier().toString()}, the namespaced id, because that is the only order
     * that is a pure function of the biome set - it is what fixes each biome's weight sub-range and
     * therefore what makes a seed reproduce. Enum declaration order would not.
     * <p>
     * A biome with a parent is NOT top-level pickable (WoverBiomeData.java:476-478, enforced at
     * WoverBiomeSourceImpl.java:99-103); it reaches the world only through its parent's sub-biome
     * list, which is seeded with the parent ITSELF at the parent's own genChance.
     * <p>
     * ponytail: the mod's land picker also carries vanilla {@code minecraft:end_highlands}
     * (genChance 0.20, VanillaBiomeDataProvider.java:25-30) and its void picker
     * {@code minecraft:small_end_islands} (0.01, :50-54). Both are left out here: the port models no
     * vanilla surface rules for them, and {@code shouldGenerateDecorations()} is true, so an
     * end_highlands cell would paste vanilla chorus plants into the middle of BetterEnd terrain.
     * Their absence rescales every other weight by the same factor and changes nothing else.
     */
    static final class Picker {
        private final BiomeSurface[] members;
        /** Float running sum, as SearchTree builds it (RandomizedWeightedList.java:780-784). */
        private final float[] cumulative;
        /** Double sum, cast to float only at the draw (RandomizedWeightedList.java:746). */
        private final double total;
        private final Map<BiomeSurface, Sub> subs;

        private record Sub(BiomeSurface[] values, double[] cumulative, double total) {
        }

        Picker(List<BiomeSurface> pool) {
            List<BiomeSurface> top = pool.stream()
                    .filter(b -> b.parent() == null)
                    .sorted(Comparator.comparing(BiomeSurface::key))
                    .toList();
            this.members = top.toArray(new BiomeSurface[0]);
            this.cumulative = new float[members.length];
            float running = 0;
            double sum = 0;
            for (int i = 0; i < members.length; i++) {
                running += (float) members[i].genChance();
                cumulative[i] = running;
                sum += members[i].genChance();
            }
            this.total = sum;

            // EVERY member of the pool, not just the top-level ones: the speckle pass reads
            // outBuffer[index].getSubBiome (HexBiomeChunk.java:86) and by then a cell can already
            // hold a sub-biome. A sub-biome's own list is just itself (WoverBiomePicker.java:271-272
            // seeds it, and nothing is a child of a child), so it draws and returns itself - but it
            // still DRAWS, which is what keeps the rings' RNG streams in lockstep.
            this.subs = new EnumMap<>(BiomeSurface.class);
            for (BiomeSurface parent : pool) {
                List<BiomeSurface> values = new ArrayList<>();
                // WoverBiomePicker.java:271-272 - the parent goes in FIRST, at its own genChance.
                values.add(parent);
                pool.stream()
                        .filter(b -> b.parent() == parent)
                        .sorted(Comparator.comparing(BiomeSurface::key))
                        .forEach(values::add);
                double[] cum = new double[values.size()];
                double runningSub = 0;
                for (int i = 0; i < values.size(); i++) {
                    runningSub += values.get(i).genChance();
                    cum[i] = runningSub;
                }
                subs.put(parent, new Sub(values.toArray(new BiomeSurface[0]), cum, runningSub));
            }
        }

        /**
         * RandomizedWeightedList.java:745-747,826 - {@code root.get(nextFloat() * (float) total)}
         * with {@code value < separator ? min : max}, which is a linear cumulative scan in FLOAT
         * arithmetic over the id-sorted list. One {@code nextFloat}, always.
         */
        BiomeSurface pick(Random random) {
            float value = random.nextFloat() * (float) total;
            for (int i = 0; i < cumulative.length; i++) {
                if (value < cumulative[i]) {
                    return members[i];
                }
            }
            return members[members.length - 1];
        }

        /**
         * RandomizedWeightedList.java:135-152 via WoverBiomePicker.java:306-308 - the sub-biome list
         * is NOT a search tree, so this is one {@code nextDouble} and a DOUBLE cumulative scan. A
         * biome with no children still draws (and still returns itself), which is what keeps the
         * four rings' RNG streams in lockstep.
         */
        BiomeSurface subBiome(BiomeSurface parent, Random random) {
            Sub sub = subs.get(parent);
            double value = random.nextDouble() * sub.total();
            for (int i = 0; i < sub.cumulative().length; i++) {
                if (value < sub.cumulative()[i]) {
                    return sub.values()[i];
                }
            }
            return sub.values()[sub.values().length - 1];
        }

        /** Every biome this picker can return: the top-level members AND their sub-biomes. */
        List<BiomeSurface> members() {
            return List.copyOf(subs.keySet());
        }
    }
}
