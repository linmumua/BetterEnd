package dev.mumu.betterend.flora;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.flora.Flora.Engine;
import dev.mumu.betterend.flora.Flora.Prop;
import dev.mumu.betterend.flora.Flora.Row;
import dev.mumu.betterend.flora.Flora.SoilSet;
import dev.mumu.betterend.terrain.IslandField;

import java.util.List;
import java.util.Random;

/**
 * The six flora placement loops, as pure functions of {@code (seed, chunkX, chunkZ)}.
 * <p>
 * Contains no Bukkit and no CraftEngine, deliberately: planning is separated from writing exactly
 * as {@code TreeShape.generate} is separated from {@code TreePopulator.placeTree}, so the density,
 * soil, support and determinism tests run with no server at all. {@link FloraPopulator} passes a
 * {@link PlantSink} that resolves through the palette and writes under {@code isInRegion}; the
 * tests pass a collecting sink.
 * <p>
 * <b>No block is ever read back.</b> Every terrain question goes to {@link ColumnScan}, which is
 * built from {@link IslandField#densityColumn}; every "what block is the plant standing on"
 * question goes to {@link Surface}, which recomputes what
 * {@code DustWastelandsGenerator.fillSegment} wrote rather than asking. CraftEngine's
 * {@code deceive-bukkit-material} makes a read lie, and a neighbour's populator may not have run.
 * <p>
 * Deviations from the mod, all deliberate, all documented at their site: the four-direction
 * shuffle array is local rather than the mod's shared static ({@code WallScatterFeature.java:18}),
 * a scatter point whose own cell is solid is skipped rather than buried
 * ({@link #groundPoint}), and the wall box is skipped wholesale when it holds no solid backing at
 * all ({@link #wallBox}).
 */
public final class FloraPlanner {
    private FloraPlanner() {
    }

    /** Nothing found. Matches {@code IslandField.topY}'s own miss value. */
    public static final int NONE = Integer.MIN_VALUE;

    /**
     * Hard cap on {@code count_on_every_layer}'s layer loop, copied from
     * {@code TreePopulator.MAX_LAYERS}. The loop already stops on the first round that finds no
     * surface; this is insurance against an unbounded loop on a worldgen thread.
     */
    private static final int MAX_LAYERS = 16;

    private static final float TWO_PI = (float) (Math.PI * 2);

    /** {@code BlocksHelper.makeHorizontal()} order (BlocksHelper.java:217-219). */
    private static final int[] DIR_X = {0, 1, 0, -1};
    private static final int[] DIR_Z = {-1, 0, 1, 0};
    private static final String[] DIR_NAME = {"north", "east", "south", "west"};

    /** Receives one planned block. {@code value} is null when the id carries no property. */
    @FunctionalInterface
    public interface PlantSink {
        void plant(int x, int y, int z, Row row, int idIndex, String value);
    }

    // --- the surface oracle -------------------------------------------------------------------

    /**
     * What block {@code DustWastelandsGenerator.fillSegment} wrote on top of a solid run, without
     * reading it back. Four correspondences, each one line of the generator:
     * <ul>
     * <li>{@code ring != null} means the vanilla rings, whose surface is plain end stone
     * ({@code DustWastelandsGenerator.surfaceAt:219-224});</li>
     * <li>the biome is sampled at the segment TOP, not at the block
     * ({@code fillSegment:202} calls {@code surfaceAt(placement, x, top, z)});</li>
     * <li>{@code cap = dusty ? dustDepth(x, z, height) : 1} and the floor block is {@code top}
     * ({@code fillSegment:203-207});</li>
     * <li>the alt speckle overwrites the top block only, gated on
     * {@code alt != null && cap > 0 && altSurface(x, z)} ({@code fillSegment:209-211}).</li>
     * </ul>
     * The {@code Resolved} table is built from the same {@code BiomeSurface.resolve} call the
     * generator makes, so a pack missing {@code amber_moss} degrades identically on both sides.
     * <p>
     * ponytail: this duplicates six lines of the generator. Ask its owner to widen
     * {@code surfaceAt} to package-private (or add a {@code String surfaceId(...)} beside
     * {@code fillsWithEndStone}) and delete this; {@code FloraPlacementTest} pins the arithmetic
     * until then.
     */
    public static final class Surface {
        private final BiomePlacement placement;
        private final IslandField field;
        private final BiomeSurface.Resolved[] resolved;

        public Surface(BiomePlacement placement, BiomeSurface.Resolved[] resolved) {
            this.placement = placement;
            this.field = placement.field();
            this.resolved = resolved;
        }

        /** The surface block id at {@code (x, segTop, z)} of a solid run {@code segHeight} tall. */
        public String at(int x, int z, int segTop, int segHeight) {
            if (placement.ring(x, z) != null) {
                return "minecraft:end_stone";
            }
            BiomeSurface.Resolved r = resolved[placement.quartAt(x, segTop, z).ordinal()];
            if (r == null) {
                // The same null arm DustWastelandsGenerator.surfaceAt:225-226 carries, and for the
                // same reason: quartAt can only return picker members today, but a future picker
                // change must degrade to end stone here rather than throw on a worldgen thread.
                return "minecraft:end_stone";
            }
            return block(r, field.dustDepth(x, z, segHeight), field.altSurface(x, z));
        }

        /**
         * The cap/alt arithmetic on its own, so a table-driven test can pin every branch. A
         * version that forgets {@code cap > 0} returns {@code top} for a one-block dusty segment
         * where the generator wrote the filler.
         */
        static String block(BiomeSurface.Resolved r, int dustDepth, boolean altOn) {
            int cap = r.dusty() ? dustDepth : 1;
            if (cap == 0) {
                return r.under();
            }
            if (r.alt() != null && altOn) {
                return r.alt();
            }
            return r.top();
        }
    }

    // --- the per-chunk solidity bitmap --------------------------------------------------------

    /**
     * One {@code long[2]} solidity bitmap per column of the 48x48 write zone, built lazily from
     * {@link IslandField#densityColumn} and shared by every row.
     * <p>
     * This is the one structural performance decision in the flora layer. Every terrain question
     * all six engines ask is a boolean about one block; a naive port calls {@code densityColumn}
     * per question, which is up to ~17,000 calls and ~17 MB of {@code double[]} garbage per chunk
     * in blossoming_spires. Here it is at most 2,304 columns (36 KB of {@code long}s) and every
     * question after the first is a bit test.
     * <p>
     * Not thread-safe and never escapes {@code populate}: one per chunk, on that chunk's thread.
     */
    public static final class ColumnScan {
        /** Terrain lives in {@code [0, TOP_SOLID_Y]}; nothing outside it is ever solid. */
        public static final int LO = 0;
        public static final int HI = IslandField.TOP_SOLID_Y;
        private static final int SIZE = 48;

        private final IslandField field;
        private final int x0;
        private final int z0;
        private final long[] bits = new long[SIZE * SIZE * 2];
        private final boolean[] built = new boolean[SIZE * SIZE];
        /** The last surface answer per column, keyed by the segment top it was asked about. */
        private final int[] soilTop = new int[SIZE * SIZE];
        private final String[] soilId = new String[SIZE * SIZE];

        public ColumnScan(IslandField field, int chunkX, int chunkZ) {
            this.field = field;
            // The bounds a BlockPopulator may write (CraftLimitedRegion.java:49,66-73), which is
            // also the mod's WriteZone: this chunk plus 16 west/north and 15 east/south.
            this.x0 = (chunkX << 4) - 16;
            this.z0 = (chunkZ << 4) - 16;
            java.util.Arrays.fill(soilTop, NONE);
        }

        public int minX() {
            return x0;
        }

        public int minZ() {
            return z0;
        }

        public int maxX() {
            return x0 + SIZE - 1;
        }

        public int maxZ() {
            return z0 + SIZE - 1;
        }

        /** How many columns this scan actually built; the flora layer's real terrain cost. */
        public int columnsBuilt() {
            int n = 0;
            for (boolean b : built) {
                if (b) {
                    n++;
                }
            }
            return n;
        }

        public boolean inZone(int x, int z) {
            return x >= x0 && x <= maxX() && z >= z0 && z <= maxZ();
        }

        public int clampX(int x) {
            return Math.max(x0, Math.min(maxX(), x));
        }

        public int clampZ(int z) {
            return Math.max(z0, Math.min(maxZ(), z));
        }

        private int index(int x, int z) {
            return (x - x0) * SIZE + (z - z0);
        }

        private void build(int i, int x, int z) {
            double[] column = field.densityColumn(x, z, LO, HI);
            long low = 0L;
            long high = 0L;
            for (int y = 0; y < column.length; y++) {
                if (column[y] > 0) {
                    if (y < 64) {
                        low |= 1L << y;
                    } else {
                        high |= 1L << (y - 64);
                    }
                }
            }
            bits[i * 2] = low;
            bits[i * 2 + 1] = high;
            built[i] = true;
        }

        private int column(int x, int z) {
            int i = index(x, z);
            if (!built[i]) {
                build(i, x, z);
            }
            return i;
        }

        public boolean solid(int x, int y, int z) {
            if (y < LO || y > HI || !inZone(x, z)) {
                return false;
            }
            int i = column(x, z);
            return y < 64 ? (bits[i * 2] >>> y & 1L) != 0 : (bits[i * 2 + 1] >>> (y - 64) & 1L) != 0;
        }

        public boolean air(int x, int y, int z) {
            return !solid(x, y, z);
        }

        /** Highest solid y in the column, or {@link #NONE}. Equals {@code IslandField.topY}. */
        public int top(int x, int z) {
            if (!inZone(x, z)) {
                return NONE;
            }
            int i = column(x, z);
            if (bits[i * 2 + 1] != 0) {
                return 127 - Long.numberOfLeadingZeros(bits[i * 2 + 1]);
            }
            if (bits[i * 2] != 0) {
                return 63 - Long.numberOfLeadingZeros(bits[i * 2]);
            }
            return NONE;
        }

        /**
         * The {@code layer}-th air-over-solid surface walking down the column, i.e. the block a
         * plant's foot occupies. Identical to {@code trees.SurfaceScan.walk}, which is what
         * {@code minecraft:count_on_every_layer} hands the feature.
         */
        public int surfaceY(int x, int z, int layer) {
            if (!inZone(x, z)) {
                return NONE;
            }
            int found = 0;
            for (int y = HI; y > LO; y--) {
                if (solid(x, y, z) || !solid(x, y - 1, z)) {
                    continue;
                }
                if (found++ == layer) {
                    return y;
                }
            }
            return NONE;
        }

        /** Bottom of the solid run whose top block is {@code segTop}. */
        public int segmentBottom(int x, int z, int segTop) {
            int y = segTop;
            while (y > LO && solid(x, y - 1, z)) {
                y--;
            }
            return y;
        }

        /**
         * The surface block id on top of the solid run whose top block is {@code segTop},
         * memoised per column.
         * <p>
         * This is the flora layer's hottest query by a wide margin: every accepted scatter point
         * of every GROUND row asks it, which is ~1,000 times a chunk in crystal_mountains, and
         * each miss costs a {@code quartAt} (HexBiomeMap plus up to two cave-noise evaluations)
         * and two more simplex evaluations for the dust cap and the alt speckle. Almost every one
         * of those hits the same column's top segment, so a one-entry-per-column memo keyed on
         * {@code segTop} - exact, never approximate, and dropped with the chunk - removes them.
         */
        String surfaceId(Surface surface, int x, int z, int segTop) {
            int i = index(x, z);
            if (soilTop[i] == segTop) {
                return soilId[i];
            }
            String id = surface.at(x, z, segTop, segTop - segmentBottom(x, z, segTop) + 1);
            soilTop[i] = segTop;
            soilId[i] = id;
            return id;
        }

        /**
         * Highest solid y in {@code [lo, hi]}, or {@link #NONE}. This is
         * {@code ScatterFeature.getGroundPlant} exactly: its ray starts at {@code centre + 5},
         * {@code downRay} counts consecutive air below and {@code down > 10} rejects, so the
         * accepted solid is the highest one in {@code [centre - 6, centre + 4]}.
         */
        public int topInBand(int x, int z, int lo, int hi) {
            for (int y = Math.min(hi, HI); y >= lo; y--) {
                if (solid(x, y, z)) {
                    return y;
                }
            }
            return NONE;
        }

        /**
         * {@code BlocksHelper.upRay:137-143} - consecutive air blocks above {@code y}, counting
         * {@code j = 1 .. maxDist - 1}.
         */
        public int upRay(int x, int y, int z, int maxDist) {
            int length = 0;
            for (int j = 1; j < maxDist && air(x, y + j, z); j++) {
                length++;
            }
            return length;
        }

        /** {@code BlocksHelper.downRay:145-151}, the mirror of {@link #upRay}. */
        public int downRay(int x, int y, int z, int maxDist) {
            int length = 0;
            for (int j = 1; j < maxDist && air(x, y - j, z); j++) {
                length++;
            }
            return length;
        }

        /**
         * True if any block of the inclusive box is solid. The cheap gate that keeps the wall
         * engine's {@code (2r+1)^3} scan - 96% of the whole flora layer's worst-case cost - off a
         * box with no backing block in it at all.
         */
        public boolean anySolidInBox(int bx0, int bz0, int bx1, int bz1, int ylo, int yhi) {
            int lo = Math.max(LO, ylo);
            int hi = Math.min(HI, yhi);
            if (lo > hi) {
                return false;
            }
            long maskLow = mask(lo, Math.min(hi, 63), 0);
            long maskHigh = mask(Math.max(lo, 64), hi, 64);
            for (int x = Math.max(bx0, x0); x <= Math.min(bx1, maxX()); x++) {
                for (int z = Math.max(bz0, z0); z <= Math.min(bz1, maxZ()); z++) {
                    int i = column(x, z);
                    if ((bits[i * 2] & maskLow) != 0 || (bits[i * 2 + 1] & maskHigh) != 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static long mask(int lo, int hi, int base) {
            if (lo > hi) {
                return 0L;
            }
            int a = lo - base;
            int b = hi - base;
            return (b == 63 ? -1L : (1L << (b + 1)) - 1) & ~((1L << a) - 1);
        }
    }

    // --- the plan -----------------------------------------------------------------------------

    /**
     * Plans every row's blocks for one chunk. Deterministic: a pure function of {@code rng}'s
     * seed and the chunk coordinates, with no static mutable state, no lazy init and no shared
     * {@code Random}.
     */
    public static int plan(List<Row> rows, ColumnScan scan, Surface surface,
                           BiomePlacement placement, Random rng, int chunkX, int chunkZ,
                           PlantSink sink) {
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        int placements = 0;
        for (Row row : rows) {
            placements += layers(row, scan, surface, placement, rng, originX, originZ, sink);
        }
        return placements;
    }

    /**
     * {@code minecraft:count_on_every_layer(uniform(0, max))}, the only count modifier any flora
     * row carries, followed by the {@code minecraft:biome} filter. Vanilla re-samples the count
     * every layer round and stops on the first round in which no position found a layer-{@code i}
     * surface - the structure {@code TreePopulator.placeSpecies:185-204} already implements.
     * <p>
     * ponytail: the exact vanilla semantics are NOT FOUND - Paper ships only patches and neither
     * reference tree holds a decompiled {@code CountOnEveryLayerPlacement}. The whole density
     * budget scales linearly with this reading; re-check it against a real 26.x jar.
     */
    private static int layers(Row row, ColumnScan scan, Surface surface, BiomePlacement placement,
                              Random rng, int originX, int originZ, PlantSink sink) {
        int placements = 0;
        for (int layer = 0; layer < MAX_LAYERS; layer++) {
            int count = rng.nextInt(row.maxPerLayer() + 1);
            boolean any = false;
            for (int i = 0; i < count; i++) {
                int cx = originX + rng.nextInt(16);
                int cz = originZ + rng.nextInt(16);
                int cy = scan.surfaceY(cx, cz, layer);
                if (cy == NONE) {
                    continue;
                }
                // Set BEFORE the filters: the layer round ends when the PLACEMENT modifier found
                // no surface, not when the feature declined to build there.
                any = true;
                // quartAt, not at, and at cy-1: the identical call fillSegment:202 made when it
                // wrote the block under the plant. Raw at() would let a plant stand on one biome's
                // surface while being told it is in another, within 3 blocks of a quart edge.
                // ring() first, because inside radius 1024 at() names a biome that is not painted.
                if (placement.ring(cx, cz) != null
                        || !row.hosts().contains(placement.quartAt(cx, cy - 1, cz))) {
                    continue;
                }
                placements++;
                dispatch(row, scan, surface, rng, cx, cy, cz, sink);
            }
            if (!any) {
                return placements;
            }
        }
        return placements;
    }

    private static void dispatch(Row row, ColumnScan scan, Surface surface, Random rng,
                                 int cx, int cy, int cz, PlantSink sink) {
        switch (row.engine()) {
            case GROUND, DOUBLE, COLUMN -> {
                // ScatterFeature.canSpawn:39-41. Its END_STONES half is inert - the tag holds all
                // 27 BetterEnd surface blocks - so only the y >= 5 half survives.
                if (cy >= 5) {
                    groundDisc(row, scan, surface, rng, cx, cy, cz, sink);
                }
            }
            case WALL -> wallBox(row, scan, rng, cx, cz, sink);
            case CEILING -> ceilingScatter(row, scan, rng, cx, cy, cz, sink);
            case SKY -> skyDisc(row, scan, rng, cx, cz, sink);
        }
    }

    // --- GROUND / DOUBLE / COLUMN -------------------------------------------------------------

    /** {@code ScatterFeature.place:74-104}. */
    private static void groundDisc(Row row, ColumnScan scan, Surface surface, Random rng,
                                   int cx, int cy, int cz, PlantSink sink) {
        if (row.radius() == 0) {
            // lumecorn and large_amaranita carry no config, so there is no disc at all: the
            // count_on_every_layer position IS the plant position (Lumecorn.java:26-28).
            groundPoint(row, scan, surface, rng, cx, cy, cz, cx, cz, 0f, sink);
            return;
        }
        float r = randRange(row.radius() * 0.5f, row.radius(), rng);
        int count = floor(r * r * randRange(1.5f, 3f, rng));
        for (int i = 0; i < count; i++) {
            float pr = r * (float) Math.sqrt(rng.nextFloat());
            float theta = rng.nextFloat() * TWO_PI;
            int px = cx + floor(pr * (float) Math.cos(theta));
            int pz = cz + floor(pr * (float) Math.sin(theta));
            if (!scan.inZone(px, pz)) {
                continue;
            }
            groundPoint(row, scan, surface, rng, cx, cy, cz, px, pz, r, sink);
        }
    }

    private static void groundPoint(Row row, ColumnScan scan, Surface surface, Random rng,
                                    int cx, int cy, int cz, int px, int pz, float r,
                                    PlantSink sink) {
        int ground = row.radius() == 0 ? cy - 1 : scan.topInBand(px, pz, cy - 6, cy + 4);
        if (ground == NONE || scan.solid(px, ground + 1, pz)) {
            // Deliberate deviation: the mod never checks the plant's own cell, so a scatter point
            // landing inside a hillside gets a plant buried in rock. Skipping costs nothing and is
            // the same query.
            return;
        }
        // canGenerate runs before the chance roll (ScatterFeature.java:94-101) and DOUBLE draws
        // from the random inside it, so the draw order below is load-bearing.
        boolean tall = false;
        SoilSet soil = row.soil();
        if (row.engine() == Engine.DOUBLE) {
            // DoublePlantFeature.java:28-34 - r is the DRAWN radius, not row.radius().
            tall = discFraction(cx, cz, px, pz, r, rng) < 0.5f;
            soil = tall ? row.soilTall() : row.soil();
        }
        if (!soil.accepts(surfaceId(scan, surface, px, pz, ground))) {
            return;
        }
        if (row.chance() >= 2 && rng.nextInt(row.chance()) != 0) {
            return;
        }
        int y = ground + 1;
        switch (row.engine()) {
            case DOUBLE -> {
                if (tall) {
                    sink.plant(px, y, pz, row, 1, "lower");
                    sink.plant(px, y + 1, pz, row, 1, "upper");
                } else {
                    sink.plant(px, y, pz, row, 0, null);
                }
            }
            case COLUMN -> column(row, scan, rng, px, y, pz, sink);
            default -> sink.plant(px, y, pz, row, 0, groundValue(row));
        }
    }

    /** {@code MHelper.length(dx, dz) / radius * 0.6F + random.nextFloat() * 0.4F}. */
    private static float discFraction(int cx, int cz, int px, int pz, float r, Random rng) {
        float dx = cx - px;
        float dz = cz - pz;
        return (float) Math.sqrt(dx * dx + dz * dz) / r * 0.6f + rng.nextFloat() * 0.4f;
    }

    /**
     * The one constant property a GROUND row's block carries. Only two rows have one: the crops
     * take {@code age=3} ({@code SinglePlantFeature.java:50-52}) and small_jellyshroom's floor
     * form takes {@code facing=up} ({@code SingleInvertedScatterFeature} does the same with DOWN).
     */
    private static String groundValue(Row row) {
        return switch (row.props().get(0)) {
            case AGE3 -> "3";
            case FACING_6 -> "up";
            default -> null;
        };
    }

    private static String surfaceId(ColumnScan scan, Surface surface, int x, int z, int segTop) {
        return scan.surfaceId(surface, x, z, segTop);
    }

    // --- COLUMN ------------------------------------------------------------------------------

    /**
     * The four vertical stamps. A {@code switch} on the row name rather than a descriptor
     * language, deliberately: the four do not share a shape. lumecorn branches on a coin flip and
     * decrements its own height inside the branch ({@code Lumecorn.java:57-75}); glow_pillar
     * collapses to a single {@code shape=middle} block at height 1
     * ({@code GlowingPillarSeedBlock.java:38-39}); lanceleaf takes a five-value shape. A descriptor
     * covering all of them needs ~10 fields and three escape hatches - more code than this, and
     * harder to check.
     * <p>
     * ponytail: no stamp DSL. Add one at a fifth column plant.
     */
    private static void column(Row row, ColumnScan scan, Random rng, int x, int y, int z,
                               PlantSink sink) {
        switch (row.name()) {
            case "glow_pillar" -> glowPillar(row, scan, rng, x, y, z, sink);
            case "lanceleaf" -> lanceleaf(row, scan, rng, x, y, z, sink);
            case "lumecorn" -> lumecorn(row, scan, rng, x, y, z, sink);
            case "large_amaranita" -> largeAmaranita(row, scan, rng, x, y, z, sink);
            default -> throw new IllegalStateException("no column stamp for " + row.name());
        }
    }

    /**
     * The fur/leaf ring around a stamp's cap: the four horizontals and the cell above, never the
     * one below.
     * <p>
     * <b>Never below.</b> glow_pillar iterates all six of {@code BlocksHelper.DIRECTIONS}
     * ({@code GlowingPillarSeedBlock.java:51}), but it has already written its roots into the cell
     * under the luminophor, so the mod's {@code world.isEmptyBlock} answers false there and no
     * DOWN leaf is ever placed. {@link ColumnScan} knows only terrain - the flora this stamp just
     * planned is not in it - so an air() test here would say true and bury the pillar's own top
     * roots block under a leaf. Half of all glow pillars are one block tall, so that lost the
     * whole stalk. glow_pillar is the only stamp that uses this ring.
     * <p>
     * Each cell is tested for the write zone as well as for air: outside the zone
     * {@link ColumnScan#solid} answers false, so air() alone would plan a block the region cannot
     * take. {@code FloraPopulator}'s {@code isInRegion} would drop it silently, but "nothing is
     * planned outside the zone" is the planner's own contract and what the tests pin.
     * <p>
     * ponytail: two stamps of the same row landing within a block of each other can still have
     * one's ring overwrite the other's stalk, because nothing tracks planned cells. It needs a
     * per-chunk occupancy set; the mod is write-order dependent there too. Add one if adjacent
     * pillars ever look wrong.
     */
    private static void ring(Row row, ColumnScan scan, int x, int y, int z, PlantSink sink) {
        for (int d = 0; d < 4; d++) {
            int nx = x + DIR_X[d];
            int nz = z + DIR_Z[d];
            if (scan.inZone(nx, nz) && scan.air(nx, y, nz)) {
                sink.plant(nx, y, nz, row, 2, DIR_NAME[d]);
            }
        }
        if (scan.air(x, y + 1, z)) {
            sink.plant(x, y + 1, z, row, 2, "up");
        }
    }

    /**
     * {@code GlowingPillarSeedBlock.growAdult:29-70}. Two of the mod's writes are not reproduced
     * and neither is a deviation: the extra "one more up" leaf at :61-69 targets the cell the
     * ring already filled with UP, and the DOWN member of its six-direction loop always lands on
     * the roots block written three lines earlier, which {@code isEmptyBlock} rejects. See
     * {@link #ring}.
     */
    private static void glowPillar(Row row, ColumnScan scan, Random rng, int x, int y, int z,
                                   PlantSink sink) {
        int height = randRange(1, 2, rng);
        if (scan.upRay(x, y, z, height + 2) < height) {
            return;
        }
        int ly;
        if (height < 2) {
            sink.plant(x, y, z, row, 0, "middle");
            ly = y + 1;
        } else {
            sink.plant(x, y, z, row, 0, "bottom");
            sink.plant(x, y + 1, z, row, 0, "top");
            ly = y + 2;
        }
        sink.plant(x, ly, z, row, 1, null);
        ring(row, scan, x, ly, z, sink);
    }

    /** {@code LanceleafSeedBlock.growAdult:26-58}; the mod's ROTATION 0-3 is not in the pack. */
    private static void lanceleaf(Row row, ColumnScan scan, Random rng, int x, int y, int z,
                                  PlantSink sink) {
        int height = randRange(4, 6, rng);
        if (scan.upRay(x, y, z, height + 2) < height + 1) {
            return;
        }
        sink.plant(x, y, z, row, 0, "bottom");
        sink.plant(x, y + 1, z, row, 0, "pre_bottom");
        int at = y + 1;
        for (int i = 2; i < height - 2; i++) {
            sink.plant(x, ++at, z, row, 0, "middle");
        }
        sink.plant(x, ++at, z, row, 0, "pre_top");
        sink.plant(x, ++at, z, row, 0, "top");
    }

    /** {@code bushes/Lumecorn.place:30-82}. */
    private static void lumecorn(Row row, ColumnScan scan, Random rng, int x, int y, int z,
                                 PlantSink sink) {
        int height = randRange(4, 7, rng);
        for (int i = 1; i < height; i++) {
            if (scan.solid(x, y + i, z)) {
                return;
            }
        }
        if (height == 4) {
            sink.plant(x, y, z, row, 0, "bottom_small");
            sink.plant(x, y + 1, z, row, 0, "light_bottom");
            sink.plant(x, y + 2, z, row, 0, "light_top_middle");
            sink.plant(x, y + 3, z, row, 0, "light_top");
            return;
        }
        int at = y;
        if (rng.nextBoolean()) {
            sink.plant(x, at, z, row, 0, "bottom_small");
        } else {
            sink.plant(x, at, z, row, 0, "bottom_big");
            sink.plant(x, ++at, z, row, 0, "middle");
            height--;
        }
        sink.plant(x, ++at, z, row, 0, "light_bottom");
        for (int i = 4; i < height; i++) {
            sink.plant(x, ++at, z, row, 0, "light_middle");
        }
        sink.plant(x, ++at, z, row, 0, "light_top_middle");
        sink.plant(x, ++at, z, row, 0, "light_top");
    }

    /** {@code bushes/LargeAmaranitaFeature.place:31-55}. */
    private static void largeAmaranita(Row row, ColumnScan scan, Random rng, int x, int y, int z,
                                       PlantSink sink) {
        int height = randRange(2, 3, rng);
        for (int i = 1; i < height; i++) {
            if (scan.solid(x, y + i, z)) {
                return;
            }
        }
        sink.plant(x, y, z, row, 0, "bottom");
        if (height > 2) {
            sink.plant(x, y + 1, z, row, 0, "middle");
        }
        sink.plant(x, y + height - 1, z, row, 0, "top");
    }

    // --- WALL --------------------------------------------------------------------------------

    /**
     * {@code WallScatterFeature.place:29-70}: one random y in the column's
     * {@code [upRay-from-0, WORLD_SURFACE]} band, then a {@code (2r+1)^3} box with one cell in
     * four sampled, each needing air plus a horizontally supported face.
     * <p>
     * This is the single most expensive thing in the flora layer - twisted_moss at radius 6 is
     * 2,197 cells x ~7.5 placements a chunk in blossoming_spires - so it gets the two mitigations
     * that matter: the zone clamp is hoisted out of the loops (as the mod does at
     * {@code WallScatterFeature.java:44-47}) and a box holding no solid block at all is skipped
     * whole. The skip is write-neutral (nothing can back a plant there) and shifts the random
     * stream, which stays deterministic.
     */
    private static void wallBox(Row row, ColumnScan scan, Random rng, int cx, int cz,
                                PlantSink sink) {
        int top = scan.top(cx, cz);
        int maxY = top == NONE ? 0 : top + 1; // Heightmap.WORLD_SURFACE is the first air above
        int minY = scan.upRay(cx, ColumnScan.LO, cz, Math.max(maxY, 1));
        if (maxY < 10 || maxY < minY) {
            return;
        }
        int py = randRange(minY, maxY, rng);
        int radius = row.radius();
        int minX = scan.clampX(cx - radius);
        int maxX = scan.clampX(cx + radius);
        int minZ = scan.clampZ(cz - radius);
        int maxZ = scan.clampZ(cz + radius);
        if (!scan.anySolidInBox(minX, minZ, maxX, maxZ, py - radius, py + radius)) {
            return;
        }
        // Local, not the mod's shared static Direction[] (WallScatterFeature.java:18,95-102),
        // which is a data race on Paper's async worldgen workers. Four elements.
        int[] dirs = {0, 1, 2, 3};
        for (int x = minX; x <= maxX; x++) {
            for (int y = py - radius; y <= py + radius; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (rng.nextInt(4) != 0 || scan.solid(x, y, z)) {
                        continue;
                    }
                    shuffle(dirs, rng);
                    for (int d : dirs) {
                        // BaseWallPlantBlock.canSurvive:50-54 - the block behind the facing.
                        if (scan.solid(x - DIR_X[d], y, z - DIR_Z[d])) {
                            sink.plant(x, y, z, row, 0, DIR_NAME[d]);
                            break;
                        }
                    }
                }
            }
        }
    }

    private static void shuffle(int[] dirs, Random rng) {
        for (int i = 0; i < 4; i++) {
            int j = rng.nextInt(4);
            int d = dirs[i];
            dirs[i] = dirs[j];
            dirs[j] = d;
        }
    }

    // --- CEILING -----------------------------------------------------------------------------

    /**
     * {@code InvertedScatterFeature.place:36-74}. The subtlety: {@code center} is the feature
     * origin and is never reassigned, so every scatter point starts at {@code origin.y - 7}
     * ({@code :59}) whichever ceiling triggered the round - the ceiling scan is a count
     * multiplier, not a per-ceiling y.
     */
    private static void ceilingScatter(Row row, ColumnScan scan, Random rng, int cx, int cy,
                                       int cz, PlantSink sink) {
        int top = scan.top(cx, cz);
        int maxY = top == NONE ? 0 : top + 1;
        int minY = scan.upRay(cx, ColumnScan.LO, cz, Math.max(maxY, 1));
        for (int y = maxY; y > minY; y--) {
            if (scan.solid(cx, y, cz) || !scan.solid(cx, y + 1, cz)) {
                continue;
            }
            float r = randRange(row.radius() * 0.5f, row.radius(), rng);
            int count = floor(r * r * randRange(0.5f, 1.5f, rng));
            for (int i = 0; i < count; i++) {
                float pr = r * (float) Math.sqrt(rng.nextFloat());
                float theta = rng.nextFloat() * TWO_PI;
                int px = cx + floor(pr * (float) Math.cos(theta));
                int pz = cz + floor(pr * (float) Math.sin(theta));
                if (!scan.inZone(px, pz)) {
                    continue;
                }
                int up = scan.upRay(px, cy - 7, pz, 16);
                if (up > 14) {
                    continue;
                }
                int py = cy - 7 + up;
                // The cell itself must be replaceable and a solid block above it:
                // SingleInvertedScatterFeature.java:111-119 (facing=DOWN canSurvive).
                if (scan.solid(px, py, pz) || !scan.solid(px, py + 1, pz)) {
                    continue;
                }
                sink.plant(px, py, pz, row, 0, "down");
            }
        }
    }

    // --- SKY ---------------------------------------------------------------------------------

    /**
     * {@code SkyScatterFeature.java:41-59} + {@code FilaluxFeature.generate:21-45}: a centre at a
     * random y with no ground test at all, then points that need their own cell and four
     * horizontal neighbours empty with clearance above and below.
     * <p>
     * ponytail: the mod draws the centre y and the per-point jitter from {@code world.getRandom()}
     * rather than the feature random ({@code SkyScatterFeature.java:54,58}). There is no such
     * second stream here, so both come from the chunk random. Same distribution, different
     * sequence.
     */
    private static void skyDisc(Row row, ColumnScan scan, Random rng, int cx, int cz,
                                PlantSink sink) {
        int centerY = randRange(32, 192, rng);
        float r = randRange(row.radius() * 0.5f, row.radius(), rng);
        int count = floor(r * r * randRange(1.5f, 3f, rng));
        for (int i = 0; i < count; i++) {
            float pr = r * (float) Math.sqrt(rng.nextFloat());
            float theta = rng.nextFloat() * TWO_PI;
            int px = cx + floor(pr * (float) Math.cos(theta));
            int pz = cz + floor(pr * (float) Math.sin(theta));
            if (!scan.inZone(px, pz)) {
                continue;
            }
            // yOffset 5, then getGroundPlant's randRange(-5, 5) jitter.
            int py = centerY + 5 + randRange(-5, 5, rng);
            if (scan.solid(px, py, pz)) {
                continue;
            }
            boolean clear = true;
            for (int d = 0; d < 4 && clear; d++) {
                clear = scan.air(px + DIR_X[d], py, pz + DIR_Z[d]);
            }
            // maxD = yOffset + 2 = 7, maxV = yOffset - 2 = 3.
            if (!clear || scan.upRay(px, py, pz, 7) <= 3 || scan.downRay(px, py, pz, 7) <= 3) {
                continue;
            }
            if (rng.nextInt(row.chance()) != 0) {
                continue;
            }
            sink.plant(px, py, pz, row, 1, null);
            sink.plant(px, py + 1, pz, row, 2, "up");
            for (int d = 0; d < 4; d++) {
                // canGenerate already proved these four are empty; only the zone still binds.
                if (scan.inZone(px + DIR_X[d], pz + DIR_Z[d])) {
                    sink.plant(px + DIR_X[d], py, pz + DIR_Z[d], row, 2, DIR_NAME[d]);
                }
            }
            int length = randRange(1, 3, rng);
            for (int j = 1; j <= length; j++) {
                String shape = length > 1 ? "top" : "bottom";
                if (j > 1) {
                    shape = j == length ? "bottom" : "middle";
                }
                sink.plant(px, py - j, pz, row, 0, shape);
            }
        }
    }

    // --- MHelper ------------------------------------------------------------------------------

    /** {@code MHelper.randRange(float, float, RandomSource):88-90}. */
    private static float randRange(float min, float max, Random rng) {
        return min + rng.nextFloat() * (max - min);
    }

    /** {@code MHelper.randRange(int, int, RandomSource):80-82}. */
    private static int randRange(int min, int max, Random rng) {
        return min + rng.nextInt(max - min + 1);
    }

    /** {@code MHelper.floor(double):114-116}. */
    private static int floor(double x) {
        return x < 0 ? (int) (x - 1) : (int) x;
    }

    /** SplitMix-style avalanche; salt differs from {@code TreePopulator}'s, which is load-bearing:
     *  Paper hands every populator an identically seeded Random (ChunkGenerator.java.patch:113-124),
     *  so a shared salt would replay another populator's stream. */
    public static long mix(long seed, int chunkX, int chunkZ) {
        long s = seed ^ 0x666c6f7261L; // "flora"
        s = s * 6364136223846793005L + chunkX;
        s = s * 6364136223846793005L + chunkZ;
        s ^= s >>> 33;
        return s * 0xff51afd7ed558ccdL;
    }

    /** Exposed for the tests, which assert every emitted property value is one the pack declares. */
    public static Prop propOf(Row row, int idIndex) {
        return row.props().get(idIndex);
    }
}
