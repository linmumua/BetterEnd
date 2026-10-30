package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.config.BetterEndConfig;
import dev.mumu.betterend.config.LayerSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the properties of the island field that the Phase 4 fixes are about. No Bukkit, no mocks. */
class IslandFieldTest {
    private static final long[] SEEDS = {1L, 42L, 12345L, -7L, 999999L};
    /** Vanilla {@code minecraft:end} height: the hard air ceiling (TerrainGenerator.java:201). */
    private static final int MAX_HEIGHT = 128;

    /**
     * The BOUND pair, not a DEFAULT-sourced field: the re-entrant path
     * ({@code plane -> columnHeight -> terrainHeightAt -> at -> isLand -> densityColumn -> plane})
     * and the intensity-keyed plane cache both only exist there, and both are new mutable-state
     * surface. If this passes and {@link #sameSeedGivesTheSameTerrain} does not, the wiring is the
     * culprit.
     */
    @Test
    void aBoundFieldIsStillAPureFunctionOfTheSeed() {
        IslandField a = BiomePlacement.bind(42L, BetterEndConfig.DEFAULTS).field();
        IslandField b = BiomePlacement.bind(42L, BetterEndConfig.DEFAULTS).field();
        for (int x = 30000; x < 30256; x += 17) {
            for (int z = -20000; z < -19800; z += 19) {
                assertEquals(a.topY(x, z, 0, 255), b.topY(x, z, 0, 255), "topY at " + x + "," + z);
            }
        }
    }

    @Test
    void sameSeedGivesTheSameTerrain() {
        IslandField a = new IslandField(42L);
        IslandField b = new IslandField(42L);
        for (int x = 30000; x < 30512; x += 17) {
            for (int z = -20000; z < -19500; z += 19) {
                assertEquals(a.topY(x, z, 0, 255), b.topY(x, z, 0, 255), "topY at " + x + "," + z);
                for (int y = 20; y < 128; y += 11) {
                    assertEquals(a.isSolid(x, y, z), b.isSolid(x, y, z),
                            "isSolid at " + x + "," + y + "," + z);
                }
            }
        }
    }

    /**
     * The lattice memo is the only mutable state in the field, and Paper calls this from many
     * worldgen workers at once with no ordering between them. Same columns, shuffled order, four
     * threads, one shared instance: any answer that moves means two neighbouring chunks can
     * disagree about a block on their shared border depending on which generated first.
     */
    @Test
    void theLatticeMemoIsOrderIndependentAndThreadSafe() throws Exception {
        List<int[]> columns = new ArrayList<>();
        // Deliberately scattered, so the memo churns rather than serving one warm neighbourhood.
        for (int i = 0; i < 600; i++) {
            columns.add(new int[]{(i * 7919) % 40000 - 20000, (i * 104729) % 40000 - 20000});
        }
        // The bound pair, so the two caches that are now re-entered on one thread (the field's
        // intensity-keyed plane map and the placement's land memo) are the ones under test. Both
        // are get/put rather than computeIfAbsent precisely because of that re-entrancy.
        IslandField reference = BiomePlacement.bind(42L, BetterEndConfig.DEFAULTS).field();
        int[] expected = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            expected[i] = reference.topY(columns.get(i)[0], columns.get(i)[1], 0, 255);
        }

        IslandField shared = BiomePlacement.bind(42L, BetterEndConfig.DEFAULTS).field();
        ConcurrentHashMap<Integer, Integer> actual = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int thread = 0; thread < 4; thread++) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                order.add(i);
            }
            Collections.shuffle(order, new Random(thread));
            pool.execute(() -> {
                for (int i : order) {
                    int[] column = columns.get(i);
                    // merge to null on disagreement, so a wrong answer cannot be overwritten by a
                    // later right one.
                    actual.merge(i, shared.topY(column[0], column[1], 0, 255),
                            (a, b) -> a.equals(b) ? a : null);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "worker threads did not finish");
        for (int i = 0; i < columns.size(); i++) {
            assertEquals(expected[i], actual.get(i), "column " + columns.get(i)[0] + ","
                    + columns.get(i)[1] + " moved under concurrency");
        }
    }

    /** getBaseHeight rides on topY, so topY drifting from isSolid is the height map drifting from the terrain. */
    @Test
    void topYIsTheHighestSolidBlock() {
        IslandField field = new IslandField(42L);
        int solidColumns = 0;
        for (int x = 20000; x < 20256; x += 7) {
            for (int z = 20000; z < 20256; z += 7) {
                int top = field.topY(x, z, 0, 255);
                if (top == Integer.MIN_VALUE) {
                    for (int y = 0; y < 256; y++) {
                        assertTrue(!field.isSolid(x, y, z),
                                "void column is solid at " + x + "," + y + "," + z);
                    }
                    continue;
                }
                solidColumns++;
                assertTrue(field.isSolid(x, top, z), "topY is not solid at " + x + "," + z);
                for (int y = top + 1; y < 256; y++) {
                    assertTrue(!field.isSolid(x, y, z), "solid above topY at " + x + "," + y + "," + z);
                }
            }
        }
        assertTrue(solidColumns > 100, "sample found almost no terrain: " + solidColumns);
    }

    /**
     * {@code topY} reads the LATTICE rows and interpolates only the cell it stops in, while every
     * other caller reads a materialised column. The two must agree exactly: {@code getBaseHeight}
     * is the number structure placement, spawn and the height map are built on, so a one-block
     * drift moves a city.
     * <p>
     * The ranges are the ones that actually arrive -- the default {@code [0, TOP_SOLID_Y]}, the
     * world's own {@code [-64, 319]} that {@code DustWastelandsGenerator.getBaseHeight} asks for --
     * plus deliberately non-lattice-aligned, single-row and entirely-empty ones, which are where
     * the plane clamp and the last partial cell would break. The columns cover cell borders (a
     * multiple of 8 and one block either side of one), the spawn void, the inner-void rim and open
     * land.
     */
    @Test
    void topYAgreesWithTheFullColumnScan() {
        int[][] ranges = {
                {0, IslandField.TOP_SOLID_Y},
                {-64, IslandField.TOP_SOLID_Y},
                {-64, 319},
                {-7, 133},
                {-5, 4},
                {-64, -1},
                {IslandField.TOP_SOLID_Y + 1, 319},
                {IslandField.TOP_SOLID_Y, IslandField.TOP_SOLID_Y},
        };
        int found = 0;
        int empty = 0;
        for (long seed : SEEDS) {
            IslandField field = new IslandField(seed);
            for (int[] c : topYColumns()) {
                for (int[] range : ranges) {
                    int expected = topYByFullColumn(field, c[0], c[1], range[0], range[1]);
                    assertEquals(expected, field.topY(c[0], c[1], range[0], range[1]),
                            "topY drifted from the column scan at " + c[0] + "," + c[1] + " over ["
                                    + range[0] + ", " + range[1] + "] (seed " + seed + ")");
                    if (expected == Integer.MIN_VALUE) {
                        empty++;
                    } else {
                        found++;
                    }
                }
            }
        }
        assertTrue(found > 100, "the sample found almost no terrain, so it proves nothing: " + found);
        assertTrue(empty > 100, "the sample found almost no void, so it proves nothing: " + empty);
    }

    /**
     * The range's contract is the one the old implementation's array gave it, length
     * {@code maxY - minY + 1} included: a ZERO length ({@code maxY == minY - 1}) walked no row and
     * answered {@link Integer#MIN_VALUE}, and a NEGATIVE length threw
     * {@link NegativeArraySizeException} out of that same allocation. The lattice scan must keep
     * both -- a caller handing over an inverted range that starts quietly answering a height is a
     * silent behaviour change, not a rounding of an edge case.
     */
    @Test
    void topYKeepsTheRangesLengthContract() {
        IslandField field = new IslandField(42L);
        for (int[] c : new int[][]{{0, 0}, {1200, 1200}, {20000, 20000}}) {
            assertEquals(Integer.MIN_VALUE, field.topY(c[0], c[1], 5, 4),
                    "a zero-length range answered a height at " + c[0] + "," + c[1]);
            assertEquals(Integer.MIN_VALUE, field.topY(c[0], c[1], -64, -65),
                    "a zero-length range answered a height at " + c[0] + "," + c[1]);
            assertThrows(NegativeArraySizeException.class, () -> field.topY(c[0], c[1], 5, 3),
                    "a negative-length range stopped throwing at " + c[0] + "," + c[1]);
            assertThrows(NegativeArraySizeException.class, () -> field.topY(c[0], c[1], -64, -66),
                    "a negative-length range stopped throwing at " + c[0] + "," + c[1]);
        }
    }

    /**
     * The BOUND pair routes {@code topY} through the biome-driven per-column intensity, so this is
     * the re-entrant path ({@code plane -> columnHeight -> terrainHeightAt -> at -> isLand}) and the
     * shape the server actually runs. Kept to a handful of columns: a cold bound column is
     * milliseconds by design.
     */
    @Test
    void boundTopYAgreesWithTheFullColumnScan() {
        IslandField field = BiomePlacement.bind(12345L, BetterEndConfig.DEFAULTS).field();
        int[][] ranges = {
                {0, IslandField.TOP_SOLID_Y},
                {-64, 319},
                {-7, 133},
        };
        int found = 0;
        for (int[] c : new int[][]{{0, 0}, {96, 96}, {1200, 1200}, {-1300, 1400},
                {20000, 20000}, {20007, 20001}}) {
            for (int[] range : ranges) {
                int expected = topYByFullColumn(field, c[0], c[1], range[0], range[1]);
                assertEquals(expected, field.topY(c[0], c[1], range[0], range[1]),
                        "bound topY drifted at " + c[0] + "," + c[1] + " over ["
                                + range[0] + ", " + range[1] + "]");
                if (expected != Integer.MIN_VALUE) found++;
            }
        }
        assertTrue(found > 0, "the sample found no terrain at all, so it proves nothing");
    }

    /**
     * The point of the lattice scan: a height query must not materialise the range.
     * {@code getBaseHeight} asks this once per block column of a structure candidate, so a
     * 192-double array per call is 1.5 KB of garbage per column at a call rate with no ceiling.
     * <p>
     * Allocation, not wall clock: a timing threshold is flaky on a shared machine, and the byte
     * count is the thing the change is about. The bound is RELATIVE to the column path measured
     * the same way, so it encodes no JVM-specific record size: {@code topY} must stay far below a
     * quarter of what materialising the column costs. The plane cache still boxes its four keys per
     * call, exactly as before -- that is not this change's to fix.
     */
    @Test
    void topYDoesNotMaterialiseTheColumn() {
        com.sun.management.ThreadMXBean bean = allocatedBytesBean();
        IslandField field = new IslandField(42L);
        long perCall = bytesPerCall(bean, 4096,
                i -> field.topY(20000 + (i & 15), 20000 + (i >> 4), -64, IslandField.TOP_SOLID_Y));
        long columnPerCall = bytesPerCall(bean, 4096,
                i -> field.densityColumn(20000 + (i & 15), 20000 + (i >> 4), -64,
                        IslandField.TOP_SOLID_Y));
        assertTrue(columnPerCall > perCall * 4, "topY allocated " + perCall + " B per call against "
                + columnPerCall + " B for the full column: it is materialising the range again");
    }

    /**
     * The pre-change definition of {@link IslandField#topY(int, int, int, int)}, kept verbatim as
     * the oracle: the lattice scan has to reproduce this, so this is what it is compared against.
     */
    private static int topYByFullColumn(IslandField field, int x, int z, int minY, int maxY) {
        double[] column = field.densityColumn(x, z, minY, maxY);
        for (int i = column.length - 1; i >= 0; i--) {
            if (column[i] > 0) return minY + i;
        }
        return Integer.MIN_VALUE;
    }

    /** Sampled block columns: cell borders both sides, the spawn void, the rim, open land. */
    private static int[][] topYColumns() {
        List<int[]> out = new ArrayList<>();
        for (int x = -1050; x <= 1050; x += 150) {
            for (int z = -1050; z <= 1050; z += 150) {
                out.add(new int[]{x, z});
            }
        }
        for (int[] c : new int[][]{
                {0, 0}, {0, 1}, {1, 0}, {7, 7}, {8, 8}, {8, 7}, {9, 8}, {15, 16}, {16, 15},
                {-1, -1}, {-8, -8}, {-9, -9}, {-7, -8},
                {1024, 0}, {0, -1024}, {1028, 1028}, {-1024, 1024},
                {20000, 20000}, {20000, 20007}, {20007, 20000}, {19993, 20001}}) {
            out.add(c);
        }
        return out.toArray(new int[0][]);
    }

    /** Warm once, then measure: bytes the calling thread allocated per call of {@code work}. */
    private static long bytesPerCall(com.sun.management.ThreadMXBean bean, int calls,
                                     java.util.function.IntConsumer work) {
        for (int i = 0; i < calls; i++) {
            work.accept(i);
        }
        long id = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(id);
        for (int i = 0; i < calls; i++) {
            work.accept(i);
        }
        return (bean.getThreadAllocatedBytes(id) - before) / calls;
    }

    /** The per-thread allocation counter, or a loud failure: a silently skipped pin is not a pin. */
    private static com.sun.management.ThreadMXBean allocatedBytesBean() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(bean instanceof com.sun.management.ThreadMXBean,
                "this JVM does not expose per-thread allocation counts, so the allocation pin"
                        + " cannot run");
        com.sun.management.ThreadMXBean allocated = (com.sun.management.ThreadMXBean) bean;
        assertTrue(allocated.isThreadAllocatedMemorySupported(),
                "per-thread allocation counting is not supported on this JVM");
        allocated.setThreadAllocatedMemoryEnabled(true);
        return allocated;
    }

    /**
     * endstone_dust is a falling block: a segment capped all the way to its floor would generate as
     * a column of falling sand over air the moment the chunk ticks. The second count keeps this
     * honest - it fails if the sample happens to hold no segment thin enough for the clamp to bite,
     * which is exactly the case that would make the first assertion vacuous.
     */
    @Test
    void dustNeverFillsAWholeSegment() {
        IslandField field = new IslandField(12345L);
        int segments = 0;
        int clamped = 0;
        for (int x = 20000; x < 20128; x += 3) {
            for (int z = 20000; z < 20128; z += 3) {
                int unclamped = field.dustDepth(x, z, Integer.MAX_VALUE);
                for (int[] segment : segments(field, x, z)) {
                    int height = segment[1] - segment[0] + 1;
                    int dust = field.dustDepth(x, z, height);
                    assertTrue(dust >= 0, "negative dust depth at " + x + "," + z);
                    assertTrue(dust < height, "segment is all dust at " + x + "," + z
                            + " height " + height + " dust " + dust);
                    if (unclamped >= height) clamped++;
                    segments++;
                }
            }
        }
        assertTrue(segments > 100, "sample found almost no segments: " + segments);
        assertTrue(clamped > 10, "no segment was thin enough for the clamp to matter: " + clamped);
    }

    /**
     * What the ore populator asks instead of reading blocks back. It has to agree exactly with the
     * dust cap {@code DustWastelandsGenerator.fillSegment} writes, or ores replace dust and the
     * flavolite flood fill stops a block early.
     */
    @Test
    void endStoneAgreesWithTheDustCapTheGeneratorWrites() {
        IslandField field = new IslandField(42L);
        int endStone = 0;
        int dustBlocks = 0;
        for (int x = 20000; x < 20200; x += 7) {
            for (int z = 20000; z < 20200; z += 7) {
                for (int[] segment : segments(field, x, z)) {
                    int bottom = segment[0];
                    int top = segment[1];
                    int dust = field.dustDepth(x, z, top - bottom + 1);
                    for (int y = bottom; y <= top; y++) {
                        // DustWastelandsGenerator.fillSegment, verbatim.
                        boolean isDust = y >= top - dust + 1;
                        assertEquals(!isDust, field.isEndStone(x, y, z),
                                "isEndStone disagrees at " + x + "," + y + "," + z
                                        + " segment " + bottom + ".." + top + " dust " + dust);
                        if (isDust) {
                            dustBlocks++;
                        } else {
                            endStone++;
                        }
                    }
                }
            }
        }
        assertTrue(endStone > 1000 && dustBlocks > 100,
                "sample did not cover both cases: " + endStone + " end stone, " + dustBlocks + " dust");
    }

    /** TerrainGenerator.java:201 - density is forced to -1 at and above the noise height. */
    @Test
    void nothingIsSolidAtTheCeiling() {
        IslandField field = new IslandField(42L);
        for (int x = 20000; x < 20256; x += 11) {
            for (int z = 20000; z < 20256; z += 11) {
                for (int y = MAX_HEIGHT; y < 256; y += 3) {
                    assertTrue(!field.isSolid(x, y, z), "solid at the ceiling: " + x + "," + y + "," + z);
                }
            }
        }
    }

    /**
     * IslandLayer.java:93-98 - every island whose centre falls inside radius 1024 of (0,0) is
     * dropped, and only the central island is put back. Big islands reach ~165 blocks inward from
     * their centres, so the ring between the central island and ~850 blocks out must be empty.
     */
    @Test
    void theSpawnVoidIsEmpty() {
        for (long seed : SEEDS) {
            IslandField field = new IslandField(seed);
            for (int radius = 150; radius <= 850; radius += 25) {
                for (int degrees = 0; degrees < 360; degrees += 11) {
                    int x = (int) (Math.cos(Math.toRadians(degrees)) * radius);
                    int z = (int) (Math.sin(Math.toRadians(degrees)) * radius);
                    for (int y = 0; y < MAX_HEIGHT; y += 4) {
                        assertTrue(!field.isSolid(x, y, z), "seed " + seed
                                + " has terrain in the spawn void at " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    /**
     * The pinned island's shape with the radial map contributing NOTHING, which is exactly what
     * {@code terrainHeight 0.0} produces: {@code 0.0f < 0.1F} trips the early-out at
     * TerrainGenerator.java:252, {@code getAverageDepth} returns 0, and SDFRadialNoiseMap.java:20-22
     * returns a hard zero. megalake, megalake_grove and sulphur_springs are the three biomes that
     * declare it, and the port has never generated them flat until now.
     * <p>
     * The two numbers were measured before the per-column fix, when the port flattened the pinned
     * island on a false premise, and they are UNCHANGED - which is the point: this is the same
     * geometry, now pinned to a stated cause instead of a special case. The widest slice sits at
     * Y 64, not 70, because the cones bulge to radius 0.5 exactly at the island's own centre height
     * (IslandLayer.java:39-42). The top is Y 74 because the bare four-cone stack tops out at local
     * y 0.05, i.e. {@code 64 + 0.05 * 1.3 * 100 = 70.5}, plus the three inflating density octaves
     * (TerrainGenerator.java:195-198, total range [0, 0.07]).
     * <p>
     * Seed-independent, because with no radial map there is no noise on the cap at all.
     */
    @Test
    void theFlatBiomesGiveTheBareConeStack() {
        for (long seed : SEEDS) {
            IslandField field = new IslandField(seed, BetterEndConfig.DEFAULTS, (x, z) -> 0.0f);
            int[] solidPerY = solidPerY(field);
            int widest = widest(solidPerY);
            int highest = highest(solidPerY);
            assertTrue(solidPerY[widest] > 500, "seed " + seed + " has no central island");
            assertEquals(64, widest, "seed " + seed + " central island is widest at Y " + widest);
            assertEquals(74, highest, "seed " + seed + " flat island tops out at Y " + highest
                    + " - a terrainHeight of 0.0 must give the bare four-cone stack, cones to local"
                    + " y 0.05 plus the density octaves (IslandLayer.java:39-42,"
                    + " TerrainGenerator.java:195-198)");
        }
    }

    /**
     * The same island on the terrainHeight codec default, 0.1f (WoverBiomeDataImpl.java:25), which
     * is what 23 of BetterEnd's 27 biomes carry AND what {@code minecraft:the_end} carries
     * (VanillaBiomeDataProvider.java:44-47 registers it; the_end.json omits the field). The port
     * used to hard-zero this island's radial map on the belief that {@code the_end} had no
     * WoverBiomeData at all. It has one, {@code 0.1f < 0.1F} is false, the mod's early-out never
     * fires, and the pinned island gets the same 0.05 intensity as everywhere else.
     * <p>
     * So {@code topY(0, 0)} is NO LONGER 74 and is no longer seed-independent. Measured 75, 77, 76,
     * 77, 75 over SEEDS; the bound is derived, not fitted: the displacement is
     * {@code t * radialNoise * intensity * islandScale * layerScale} with {@code t <= 1} and
     * {@code |radialNoise| <= 1 + 0.5 + 0.2 = 1.7} (SDFRadialNoiseMap.java:37-42), so at most
     * {@code 1.7 * 0.05 * 1.3 * 100 = 11.05} blocks over the flat 74. The same formula at the old
     * hardcoded 0.75 gives 165 blocks, clipped by {@code FADE_OUT_START} - which is precisely the
     * "33-38 blocks of relief, peaks at Y 100-103" the previous version of this test recorded, so
     * the model is confirmed against a measured number.
     * <p>
     * {@code widest == 64} survives untouched: the widest slice is coneBottom's rim at local
     * {@code sy = 0}, and the noise patch stops at local radius {@code 0.5 / 1.05 = 0.476} (62
     * blocks) while the island rim is at 0.5 (65 blocks), so {@code radial} never reaches it.
     */
    @Test
    void theCentralIslandCarriesTheDefaultRelief() {
        int agreeing = 0;
        int first = -1;
        for (long seed : SEEDS) {
            IslandField field = new IslandField(seed, BetterEndConfig.DEFAULTS, TerrainHeights.DEFAULT);
            int[] solidPerY = solidPerY(field);
            int widest = widest(solidPerY);
            int highest = highest(solidPerY);
            assertEquals(64, widest, "seed " + seed + " central island is widest at Y " + widest);
            assertTrue(highest > 74, "seed " + seed + " central island tops out at Y " + highest
                    + " - the radial map is switched off at the origin again; the codec default"
                    + " 0.1f does NOT trip TerrainGenerator.java:252's < 0.1F early-out");
            assertTrue(highest <= 86, "seed " + seed + " central island tops out at Y " + highest
                    + " - more than the 11.05 blocks intensity 0.05 can displace, so an intensity"
                    + " larger than the codec default has survived somewhere");
            if (first == -1) first = highest;
            if (highest == first) agreeing++;
        }
        assertNotEquals(SEEDS.length, agreeing, "every seed gives the same top: the radial map's"
                + " noise is not reaching the pinned island, so its cap is still a bare cone");
    }

    /**
     * Literally "no hill bulges out of the middle", the player's actual complaint. Samples the
     * interior only, {@code r < 45}, so the rim slope cannot supply the spread. Two-sided on
     * purpose: the shipped 0.75 measured 33-38 blocks of relief with every seed's peak at Y 100-103
     * (clipped by {@code FADE_OUT_START}), while a lens with NO relief at all means the map has been
     * switched off, which is the false premise this fix reverted. Measured now: 7-8 blocks.
     */
    @Test
    void theCentralIslandIsNotASpire() {
        for (long seed : SEEDS) {
            IslandField field = new IslandField(seed, BetterEndConfig.DEFAULTS, TerrainHeights.DEFAULT);
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int x = -45; x <= 45; x += 3) {
                for (int z = -45; z <= 45; z += 3) {
                    if (x * x + z * z >= 45 * 45) continue;
                    int top = field.topY(x, z, 0, MAX_HEIGHT - 1);
                    if (top == Integer.MIN_VALUE) continue;
                    min = Math.min(min, top);
                    max = Math.max(max, top);
                }
            }
            assertTrue(max != Integer.MIN_VALUE, "seed " + seed + " has no central island");
            assertTrue(max - min > 0, "seed " + seed + " central island is a perfectly flat lens"
                    + " - the radial map contributes nothing at the origin");
            assertTrue(max - min <= 20, "seed " + seed + " central island relief is " + (max - min)
                    + " blocks (Y " + min + ".." + max + ") - a hardcoded intensity has survived");
            assertTrue(max < 100, "seed " + seed + " central island reaches Y " + max
                    + ", into the top-of-world fade");
        }
    }

    /**
     * The catch-all for both over-fix and under-fix, on a RANDOM island rather than the pinned one:
     * the medium island at (-6278, -6206) on seed 12345, scale 1.264.
     * <p>
     * The metric is total {@code |topY - topY(flat)|} over the footprint, not raw relief. Relief on
     * a random island is dominated by the cone rim and by neighbouring islands overlapping the
     * sample box - it measures 33-34 whatever the terrainHeight is - so an ordering assertion on it
     * would be vacuous. Displacement from the flat baseline is what the radial map actually does.
     * Measured: 0, 2818, 4556.
     * <p>
     * A hardcoded intensity - ANY hardcoded intensity - collapses all three to one number and fails
     * the ordering. A deleted {@code radial()} fails the second assertion.
     * <p>
     * The old {@code relief >= 20} magic number survives at the bottom, moved to the biome that
     * actually owns it: 20 blocks of relief was never a property of "a random island", it was
     * dust_wastelands' terrainHeight 1.5 (biome_data/dust_wastelands.json) applied to every island
     * in the world.
     */
    @Test
    void terrainHeightDrivesTheRadialMap() {
        long flat = displacementFromFlat(0.0f);
        long codecDefault = displacementFromFlat(0.1f);
        long dust = displacementFromFlat(1.5f);
        assertEquals(0, flat, "terrainHeight 0.0 must reproduce the flat baseline exactly");
        assertTrue(codecDefault > 0, "the codec default 0.1f displaced nothing: the radial map is"
                + " off, or the < 0.1F early-out was written as <=");
        assertTrue(dust > codecDefault, "dust_wastelands (1.5) displaced no more than the codec"
                + " default (" + dust + " vs " + codecDefault + "): the intensity is hardcoded");

        IslandField field = new IslandField(12345L, BetterEndConfig.DEFAULTS, (x, z) -> 1.5f);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = -6278 - 60; x <= -6278 + 60; x += 4) {
            for (int z = -6206 - 60; z <= -6206 + 60; z += 4) {
                int top = field.topY(x, z, 0, MAX_HEIGHT - 1);
                if (top == Integer.MIN_VALUE) continue;
                min = Math.min(min, top);
                max = Math.max(max, top);
            }
        }
        assertTrue(max != Integer.MIN_VALUE, "the known medium island is missing");
        assertTrue(max - min >= 20, "a dust_wastelands island is a pancake: relief " + (max - min)
                + " blocks (Y " + min + ".." + max + ")");
    }

    /** Total {@code |topY(h) - topY(0.0)|} over the known island's footprint on seed 12345. */
    private static long displacementFromFlat(float terrainHeight) {
        IslandField flat = new IslandField(12345L, BetterEndConfig.DEFAULTS, (x, z) -> 0.0f);
        IslandField field = new IslandField(12345L, BetterEndConfig.DEFAULTS, (x, z) -> terrainHeight);
        long total = 0;
        for (int x = -6338; x <= -6218; x += 2) {
            for (int z = -6266; z <= -6146; z += 2) {
                int a = flat.topY(x, z, 0, MAX_HEIGHT - 1);
                int b = field.topY(x, z, 0, MAX_HEIGHT - 1);
                if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE) continue;
                total += Math.abs(a - b);
            }
        }
        return total;
    }

    /**
     * TerrainGenerator.java:284-303 - {@code COEF[i] = dist / sum}, so the kernel is normalised and
     * a uniform source of value v returns exactly {@code v * 0.5} (the {@code * 0.5F} at
     * TerrainGenerator.java:188). Catches an unnormalised COEF, a wrong disc radius (the 29 points
     * with {@code length(x, z) / 3 <= 1}), and a centre sample wrongly given a non-zero weight.
     * <p>
     * The tolerance is float accumulation, not slack: the 29 terms are summed in the mod's own
     * order, so the port lands on the same bits (0.050000012f, 0.75000006f) the mod does.
     */
    @Test
    void columnHeightIsTheNormalisedKernel() {
        assertEquals(0.05f, field((x, z) -> 0.1f).columnHeight(0, 0), 1e-6f);
        assertEquals(0.75f, field((x, z) -> 1.5f).columnHeight(3, -7), 1e-6f);
    }

    /**
     * TerrainGenerator.java:251-254 - the early-out reads {@code getBiomeData(x, z)}, the CENTRE
     * sample, and nothing else. A cell whose centre quart declares 0.0 gets height 0; a cell one
     * over sees that same quart only as one of 29 weighted neighbours, so it keeps a NON-zero
     * height. Catches "early-out applied to every sample" and "early-out dropped".
     */
    @Test
    void theEarlyOutTestsOnlyTheCentreSample() {
        // The centre sample of cell (0, 0) is quart (0, 0) - TerrainGenerator.java:188, x << 1.
        IslandField field = field((x, z) -> (x == 0 && z == 0) ? 0.0f : 0.1f);
        assertEquals(0f, field.columnHeight(0, 0), 0f,
                "the centre quart declares 0.0, so the whole column must flatten");
        assertTrue(field.columnHeight(1, 0) > 0f,
                "one zero quart in the neighbourhood flattened a column whose own centre is 0.1");
        assertTrue(field.columnHeight(1, 0) < 0.05f,
                "the zero quart is inside the radius-3 disc, so it must pull the mean down");
    }

    /**
     * B4 in one assertion, and the reason the central island stopped being flat.
     * TerrainGenerator.java:252 is {@code biome.terrainHeight < 0.1F} and the codec default IS
     * 0.1f, so the test is false for 23 of 27 biomes and for {@code minecraft:the_end}. A
     * {@code <=} typo here silently flattens the entire End and every other test in this file
     * except {@link #theCentralIslandCarriesTheDefaultRelief} still passes.
     */
    @Test
    void theEarlyOutBoundaryIsStrict() {
        assertTrue(field(TerrainHeights.DEFAULT).columnHeight(0, 0) > 0f,
                "the codec default 0.1f tripped the < 0.1F early-out: the test is <=, not <");
    }

    /**
     * B3: IslandLayer.java:144 derives the patch radius from the SAME height,
     * {@code 0.5F / (1 + height)}, so a lower intensity spreads the noise over a WIDER patch. At
     * 0.05 the patch reaches local radius 0.476 of the island; at 0.75, only 0.286 - a ratio of
     * 1.67. Measured on the pinned island (scale 1.3, medium layer scale 100): the outermost column
     * whose top differs from the flat baseline sits at 87 blocks for the codec default and 56 for
     * dust_wastelands, ratio 1.55 (the smooth union with coneBottom carries the displacement a
     * little past the patch rim in both cases).
     * <p>
     * This is the one thing a fix that corrected only the intensity would get wrong: leave
     * {@code RADIAL_RADIUS} a constant and the two radii come out equal or inverted.
     */
    @Test
    void theRadiusIsDerivedFromTheIntensity() {
        int wide = patchRadius(0.1f);
        int narrow = patchRadius(1.5f);
        assertTrue(wide > 0 && narrow > 0, "the radial map displaced nothing at all");
        assertTrue(wide > narrow * 1.3, "the codec default's patch reaches " + wide
                + " blocks and dust_wastelands' reaches " + narrow + " - the patch radius is not"
                + " being derived from the intensity (IslandLayer.java:144)");
    }

    /** Farthest radius from the pinned island's centre at which terrainHeight {@code h} moves the top. */
    private static int patchRadius(float h) {
        IslandField flat = new IslandField(42L, BetterEndConfig.DEFAULTS, (x, z) -> 0.0f);
        IslandField field = new IslandField(42L, BetterEndConfig.DEFAULTS, (x, z) -> h);
        int far = -1;
        for (int x = -90; x <= 90; x++) {
            for (int z = -90; z <= 90; z++) {
                if (flat.topY(x, z, 0, MAX_HEIGHT - 1) == field.topY(x, z, 0, MAX_HEIGHT - 1)) continue;
                far = Math.max(far, (int) Math.round(Math.sqrt(x * x + z * z)));
            }
        }
        return far;
    }

    /**
     * The cycle break, pinned directly. {@code BiomePlacement.isLand} must take the island field's
     * biome-INDEPENDENT density path, exactly as {@code TerrainGenerator.isLand} calls the 3-arg
     * {@code IslandLayer.getDensity} (IslandLayer.java:138-140) while terrain fill calls the 4-arg
     * one. A height source that throws makes any accidental re-entry immediate and legible; a naive
     * implementation throws here or stack-overflows.
     */
    @Test
    void isLandNeverAsksForABiome() {
        IslandField field = new IslandField(42L, BetterEndConfig.DEFAULTS, (x, z) -> {
            throw new AssertionError("isLand asked for a biome at quart " + x + "," + z);
        });
        BiomePlacement placement = new BiomePlacement(42L, field);
        for (int quartX = -40; quartX < 40; quartX += 3) {
            for (int quartZ = 5000; quartZ < 5060; quartZ += 3) {
                placement.isLand(quartX, quartZ);
            }
        }
    }

    /**
     * The land test used to materialise a 129-row interpolated column and then read every fourth
     * entry; it now asks the field for exactly those rows, out of a 33-row plane instead of a
     * 50-row one. All three of those shortcuts are only correct if the lattice rows ARE the
     * interpolated values, which is the claim under test: at a {@code y} that is a multiple of
     * {@code SCALE_Y}, {@code ty} is exactly {@code 0.0}, so {@code a + (b - a) * ty} is {@code a}
     * to the last bit - not close, equal.
     * <p>
     * Checked at BOTH granularities, and the second one is the load-bearing half. The
     * whole-column assertion alone is nearly blind to a row misalignment: the field is smooth and
     * islands are tens of blocks thick, so shifting every sampled row by one lattice step still
     * answers "solid somewhere" for almost every column. Asking each single row in turn
     * ({@code minY == maxY}) turns that into an exact, per-row identity, so an off-by-one row
     * window fails immediately instead of quietly moving a coastline four blocks.
     * <p>
     * Covers five seeds, three configs that move where the islands are, negative and positive
     * coordinates, the inner-void core and its rim, and open land far from the origin.
     */
    @Test
    void theLatticeLandTestAgreesWithTheFullColumn() {
        BetterEndConfig[] configs = {BetterEndConfig.DEFAULTS, mediumOff(), coverage(1f)};
        int solid = 0;
        int empty = 0;
        for (long seed : SEEDS) {
            for (BetterEndConfig config : configs) {
                IslandField field = new IslandField(seed, config);
                // -600: the vanilla core disc and the spawn void. 900: the inner-void rim.
                // 20000: ordinary open land.
                for (int origin : new int[]{-600, 900, 20000}) {
                    for (int x = origin; x < origin + 600; x += 47) {
                        for (int z = origin; z < origin + 600; z += 47) {
                            double[] column = field.densityColumn(x, z, 0, MAX_HEIGHT,
                                    IslandField.LAND_INTENSITY);
                            boolean expected = false;
                            for (int i = 0; i < column.length; i += 4) {
                                if (column[i] > 0) {
                                    expected = true;
                                    break;
                                }
                            }
                            assertEquals(expected, field.anySolidOnLattice(x, z, 0, MAX_HEIGHT,
                                            IslandField.LAND_INTENSITY),
                                    "the lattice land test disagreed with the full column at "
                                            + x + "," + z + " (seed " + seed + ")");
                            if (expected) {
                                solid++;
                            } else {
                                empty++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(solid > 100 && empty > 100, "the sample only ever saw one answer: "
                + solid + " solid, " + empty + " empty");
    }

    /**
     * The per-row half of the claim above: row {@code y} of the land window must be exactly
     * {@code densityColumn(x, z, y, y)[0]}, for every lattice {@code y}, in the core, on the
     * inner-void rim, and out in open land.
     * <p>
     * Kept separate from {@link #theLatticeLandTestAgreesWithTheFullColumn} because this is the
     * assertion that catches a shifted row window, and a single failing row should name itself
     * rather than surface as a mismatched whole-column boolean.
     */
    @Test
    void everyLandWindowRowIsTheInterpolatedValueAtThatHeight() {
        int solidRows = 0;
        int emptyRows = 0;
        for (long seed : SEEDS) {
            IslandField field = new IslandField(seed);
            for (int[] column : new int[][]{{0, 0}, {120, -400}, {-900, 900}, {1024, 0}}) {
                for (int y = 0; y <= MAX_HEIGHT; y += 4) {
                    boolean expected = field.densityColumn(column[0], column[1], y, y,
                            IslandField.LAND_INTENSITY)[0] > 0;
                    assertEquals(expected, field.anySolidOnLattice(column[0], column[1], y, y,
                                    IslandField.LAND_INTENSITY),
                            "land window row " + y + " is not the density at y=" + y
                                    + " for " + column[0] + "," + column[1] + " (seed " + seed + ")");
                    if (expected) {
                        solidRows++;
                    } else {
                        emptyRows++;
                    }
                }
            }
            // A couple of columns inside real terrain, where every row boundary actually moves.
            for (int x = 20000; x < 20192; x += 37) {
                for (int z = 20000; z < 20192; z += 37) {
                    for (int y = 0; y <= MAX_HEIGHT; y += 4) {
                        assertEquals(field.densityColumn(x, z, y, y, IslandField.LAND_INTENSITY)[0] > 0,
                                field.anySolidOnLattice(x, z, y, y, IslandField.LAND_INTENSITY),
                                "land window row " + y + " is not the density at y=" + y
                                        + " for " + x + "," + z + " (seed " + seed + ")");
                    }
                }
            }
        }
        assertTrue(solidRows > 0, "no solid row was sampled, so this proves nothing");
        assertTrue(emptyRows > solidRows, "the sample is mostly solid, so a row shift could hide");
    }

    /**
     * The lattice land test reads a 33-row plane covering py 0..MAX_HEIGHT, so it must REFUSE a
     * range it has no rows for rather than silently reading another row's density. A silent
     * misread here is the one failure mode that would move terrain without failing anything else.
     */
    @Test
    void theLatticeLandTestRefusesRangesOutsideItsWindow() {
        IslandField field = new IslandField(42L);
        float intensity = IslandField.LAND_INTENSITY;
        // Below the window: the terrain path does scan there, the land test must not.
        assertThrows(IllegalArgumentException.class,
                () -> field.anySolidOnLattice(0, 0, -64, 0, intensity));
        // Above the window.
        assertThrows(IllegalArgumentException.class,
                () -> field.anySolidOnLattice(0, 0, 0, 132, intensity));
        // Not on the lattice: the equality with the interpolated column only holds there.
        assertThrows(IllegalArgumentException.class,
                () -> field.anySolidOnLattice(0, 0, 2, 126, intensity));
        // The per-column intensity is the cycle this path breaks, not a value it may take.
        assertThrows(IllegalArgumentException.class,
                () -> field.anySolidOnLattice(0, 0, 0, MAX_HEIGHT, Float.NaN));
    }

    /**
     * {@code DustWastelandsGenerator.getBaseHeight} used to scan the world's own ceiling and now
     * stops at {@link IslandField#TOP_SOLID_Y}. That is a claim about the field, not a preference:
     * if the clamp is exact the answer is the SAME number for every column, so this asserts
     * equality against the unclamped scan rather than a bound.
     * <p>
     * The upper end follows {@link #nothingIsSolidAtTheCeiling} downwards: that test pins "no
     * block at or above the ceiling is solid", this one pins "and therefore the absolute does not
     * move when the scan stops there". Together they are what makes the clamp safe to rely on for
     * structure heights, which is exactly the number a looser claim would silently shift.
     */
    @Test
    void theAbsoluteDoesNotMoveWhenTheScanStopsAtTheTopSolidRow() {
        int found = 0;
        for (long seed : SEEDS) {
            IslandField field = new IslandField(seed);
            for (int x = -600; x < 600; x += 61) {
                for (int z = -600; z < 600; z += 61) {
                    int full = field.topY(x, z, -64, 319);
                    assertEquals(full, field.topY(x, z, -64, IslandField.TOP_SOLID_Y),
                            "the absolute moved when the scan stopped at " + IslandField.TOP_SOLID_Y
                                    + " at " + x + "," + z + " (seed " + seed + ")");
                    if (full != Integer.MIN_VALUE) found++;
                }
            }
        }
        assertTrue(found > 10, "the sample found almost no terrain, so it proves nothing: " + found);
    }

    /**
     * The wiring is live, not merely compiling: a bound pair must generate, and a column whose
     * biome is dust_wastelands (terrainHeight 1.5) must produce different terrain from the same
     * column on a field that thinks every biome is at the codec default.
     * <p>
     * The scan finds a real dust_wastelands quart through the placement itself rather than
     * hardcoding one, so it keeps working when the biome lattice changes.
     */
    @Test
    void boundFieldAndPlacementShareOneCycleFreeGraph() {
        BiomePlacement placement = BiomePlacement.bind(12345L, BetterEndConfig.DEFAULTS);
        IslandField bound = placement.field();
        IslandField uniform = new IslandField(12345L, BetterEndConfig.DEFAULTS, TerrainHeights.DEFAULT);
        int found = 0;
        int differing = 0;
        for (int x = 20000; x < 21600 && found < 40; x += 16) {
            for (int z = 20000; z < 21600 && found < 40; z += 16) {
                if (placement.at(x, 64, z) != dev.mumu.betterend.biome.BiomeSurface.DUST_WASTELANDS) {
                    continue;
                }
                found++;
                if (bound.topY(x, z, 0, MAX_HEIGHT - 1) != uniform.topY(x, z, 0, MAX_HEIGHT - 1)) {
                    differing++;
                }
            }
        }
        assertTrue(found > 0, "the scan found no dust_wastelands column, so this proves nothing");
        assertTrue(differing > 0, "a dust_wastelands column generated identical terrain on a field"
                + " told every biome is 0.1f: the bound TerrainHeights is not being read");
    }

    /**
     * The radial intensity is a per-LATTICE-column value, so it must be asked for once per lattice
     * column, not once per block column. {@code columnHeight} produces the plane cache's key, so it
     * necessarily runs BEFORE that cache is consulted: without a memo of its own, one 16x16 chunk
     * asks the biome source 256 x 4 corners x 30 kernel samples = 30,720 times for the 9 lattice
     * columns it actually touches, and each of those asks runs a hex-map lookup plus a land test.
     * Measured, that was 11-12 ms of density per chunk outside the inner-void ring against 0.5 ms
     * with the memo - a 22x regression that no correctness test can see.
     * <p>
     * A 16-block chunk covers cells 0 and 1 (SCALE_XZ 8) and {@code column} also reads the corner
     * at {@code cell + 1}, so 3 x 3 = 9 lattice columns, each sampling the 29-point disc plus the
     * centre the early-out reads. 9 x 30 = 270 is the exact count; more means the memo is gone.
     */
    @Test
    void theBiomeHeightIsAskedOncePerLatticeColumn() {
        java.util.concurrent.atomic.AtomicInteger asks = new java.util.concurrent.atomic.AtomicInteger();
        IslandField field = field((x, z) -> {
            asks.incrementAndGet();
            return 0.1f;
        });
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                field.densityColumn(x, z, 0, MAX_HEIGHT - 1);
            }
        }
        assertEquals(270, asks.get(), "the per-column biome height is not memoised: one chunk of"
                + " density asked the biome source " + asks.get() + " times for 9 lattice columns");
    }

    /**
     * The buffer API is the array API, not a re-derivation of it: the same doubles, bit for bit.
     * That is why this compares raw bits instead of tolerances -- the claim is that both go through
     * ONE interpolation, so a second copy of the four-plane selection or of the mix order would show
     * up here as a last-bit disagreement even while every silhouette test still passes.
     * <p>
     * Both intensity paths are covered, and the forced ones are not decoration: {@code 0f} makes
     * {@code radial} return a hard zero, so it is a genuinely different arithmetic path, and
     * {@code PER_COLUMN} is the one that reaches the biome source. The ranges reach below the world
     * floor ({@code -64}), across it, and up to and past {@link IslandField#TOP_SOLID_Y}, where
     * {@link #planeIndex} clamps. The columns include both sides of a cell border, the spawn void,
     * the inner-void rim and open land far from the origin.
     */
    @Test
    void densityColumnIntoIsTheArrayApiBitForBit() {
        IslandField field = new IslandField(42L);
        float[] forced = {0f, IslandField.LAND_INTENSITY, 0.75f};
        int[][] ranges = {
                {0, MAX_HEIGHT - 1},
                {-64, 319},
                {-64, -1},
                {124, MAX_HEIGHT},
                {-7, 133},
        };
        int[][] columns = {
                {0, 0}, {8, 8}, {-1, -1}, {-9, -8}, {-7, -8},
                {1024, 0}, {-1024, 1024}, {20000, 20007}, {-1300, 1400},
        };
        for (int[] c : columns) {
            for (int[] range : ranges) {
                assertIntoMatchesArray(field, c[0], c[1], range[0], range[1], Float.NaN);
                for (float intensity : forced) {
                    assertIntoMatchesArray(field, c[0], c[1], range[0], range[1], intensity);
                }
            }
        }
    }

    /**
     * One column through both APIs, asserted to the last bit. {@code NaN} as {@code intensity}
     * means the {@link IslandField#densityColumn(int, int, int, int)} 4-arg form -- the
     * per-lattice-column biome intensity, which is a different method and not a different value.
     */
    private static void assertIntoMatchesArray(IslandField field, int x, int z, int minY, int maxY,
                                               float intensity) {
        boolean perColumn = Float.isNaN(intensity);
        double[] expected = perColumn
                ? field.densityColumn(x, z, minY, maxY)
                : field.densityColumn(x, z, minY, maxY, intensity);
        double[] actual = new double[expected.length];
        if (perColumn) {
            field.densityColumnInto(actual, x, z, minY, maxY);
        } else {
            field.densityColumnInto(actual, x, z, minY, maxY, intensity);
        }
        String where = x + "," + z + " over [" + minY + ", " + maxY + "] at intensity "
                + (perColumn ? "PER_COLUMN" : Float.toString(intensity));
        assertEquals(expected.length, actual.length, "length changed at " + where);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Double.doubleToRawLongBits(expected[i]),
                    Double.doubleToRawLongBits(actual[i]),
                    "the buffer form drifted from densityColumn at y=" + (minY + i) + " in " + where);
        }
    }

    /**
     * The buffer belongs to the caller and this API owns only its first {@code maxY - minY + 1}
     * slots. The tail must come back untouched -- in raw bits, so the sentinels are chosen to catch
     * the ways it could be quietly "cleaned": a {@code -0.0} that a zero-fill or a copy through
     * arithmetic would flatten, and a NaN with a non-canonical payload that any arithmetic touch
     * would canonicalise. That is also what makes a reused oversized buffer safe: the window is
     * {@code maxY - minY + 1} long by definition, so a stale tail is never read as a result.
     */
    @Test
    void densityColumnIntoLeavesTheTailAlone() {
        IslandField field = new IslandField(42L);
        int minY = 0;
        int maxY = MAX_HEIGHT - 1;
        int span = maxY - minY + 1;
        double[] buffer = new double[span + 64];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = 1234.5 + i;
        }
        buffer[span + 3] = -0.0;
        buffer[span + 5] = Double.longBitsToDouble(0x7ff8000000abcdefL);
        long[] before = new long[buffer.length];
        for (int i = 0; i < buffer.length; i++) {
            before[i] = Double.doubleToRawLongBits(buffer[i]);
        }

        field.densityColumnInto(buffer, 20000, 20000, minY, maxY);

        double[] expected = field.densityColumn(20000, 20000, minY, maxY);
        for (int i = 0; i < span; i++) {
            assertEquals(Double.doubleToRawLongBits(expected[i]),
                    Double.doubleToRawLongBits(buffer[i]), "the window at index " + i + " is wrong");
        }
        for (int i = span; i < buffer.length; i++) {
            assertEquals(before[i], Double.doubleToRawLongBits(buffer[i]),
                    "the API wrote past its window, at index " + i);
        }
    }

    /**
     * A short buffer and an inverted range are refused, not clamped and not silently truncated, and
     * the refusal lands BEFORE the first element is written -- a half-filled buffer would be the
     * worst outcome for a caller that has already started believing the array it is handed back.
     * <p>
     * The array form is asserted alongside because it keeps the opposite contract on purpose: it
     * allocates its own length, so a negative length still throws the
     * {@link NegativeArraySizeException} it always threw. A zero-length range is a no-op in both
     * forms, which is what the array form's empty array already meant.
     */
    @Test
    void densityColumnIntoRefusesAShortBufferAndAnInvertedRange() {
        IslandField field = new IslandField(42L);
        int span = MAX_HEIGHT;
        for (int length : new int[]{0, span - 1}) {
            double[] tooShort = new double[length];
            java.util.Arrays.fill(tooShort, 99.5);
            long[] before = new long[length];
            for (int i = 0; i < length; i++) before[i] = Double.doubleToRawLongBits(tooShort[i]);
            assertThrows(IllegalArgumentException.class,
                    () -> field.densityColumnInto(tooShort, 0, 0, 0, MAX_HEIGHT - 1),
                    "a " + length + "-long buffer was accepted for a " + span + "-long window");
            for (int i = 0; i < length; i++) {
                assertEquals(before[i], Double.doubleToRawLongBits(tooShort[i]),
                        "the refused call still wrote index " + i);
            }
        }
        assertEquals(span, field.densityColumn(0, 0, 0, MAX_HEIGHT - 1).length,
                "the window is not maxY - minY + 1, so the length above is not the tight one");

        double[] buffer = new double[span];
        assertThrows(IllegalArgumentException.class,
                () -> field.densityColumnInto(buffer, 0, 0, 5, 3), "an inverted range was accepted");
        assertThrows(IllegalArgumentException.class,
                () -> field.densityColumnInto(buffer, 0, 0, -64, -66),
                "an inverted range was accepted");
        assertThrows(NullPointerException.class,
                () -> field.densityColumnInto(null, 0, 0, 0, 1), "a null buffer was accepted");
        assertThrows(NegativeArraySizeException.class,
                () -> field.densityColumn(0, 0, 5, 3),
                "the array form stopped throwing for a negative length");

        java.util.Arrays.fill(buffer, 7.25);
        field.densityColumnInto(buffer, 0, 0, 5, 4);
        for (int i = 0; i < buffer.length; i++) {
            assertEquals(Double.doubleToRawLongBits(7.25), Double.doubleToRawLongBits(buffer[i]),
                    "a zero-length range wrote index " + i);
        }
    }

    /**
     * An oracle from a DIFFERENT code path, which is the point: {@link IslandField#topY} scans the
     * lattice rows top-down and interpolates only the cell it stops in, and it carries its own
     * weight setup and its own {@link #planeIndex} clamp. It is itself pinned against the full
     * column scan by {@link #topYAgreesWithTheFullColumnScan}, so agreeing with it here means the
     * buffer form is wrong in no way the array form is not, and no way that scan is not.
     * <p>
     * This is the question the populator actually asks -- "where is the top of this column" -- so it
     * is worth an oracle that does not share {@code fill}'s expressions.
     */
    @Test
    void densityColumnIntoAgreesWithTheLatticeScan() {
        IslandField field = new IslandField(42L);
        int[][] ranges = {
                {0, MAX_HEIGHT - 1},
                {-64, 319},
                {-7, 133},
        };
        int found = 0;
        for (int[] c : topYColumns()) {
            for (int[] range : ranges) {
                double[] column = new double[range[1] - range[0] + 1];
                field.densityColumnInto(column, c[0], c[1], range[0], range[1]);
                int top = Integer.MIN_VALUE;
                for (int i = column.length - 1; i >= 0; i--) {
                    if (column[i] > 0) {
                        top = range[0] + i;
                        break;
                    }
                }
                assertEquals(field.topY(c[0], c[1], range[0], range[1]), top,
                        "the buffer form and the lattice scan disagree at " + c[0] + "," + c[1]
                                + " over [" + range[0] + ", " + range[1] + "]");
                if (top != Integer.MIN_VALUE) found++;
            }
        }
        assertTrue(found > 0, "the sample found no terrain at all, so it proves nothing");
    }

    /**
     * The column-height memo is a fixed direct-mapped array now, so a slot can be holding another
     * column's entry. That must cost a recompute and never an answer. 8,192 distinct keys into
     * 4,096 slots makes a collision CERTAIN by pigeonhole rather than merely likely, and the
     * comparison is against a pristine field -- i.e. against what the pure function says with no
     * cache in the way -- so an entry served across keys cannot hide.
     * <p>
     * The height source VARIES per quart on purpose: a constant one would make every column answer
     * the same float, and then a cross-key bleed would be invisible to this test.
     */
    @Test
    void theColumnHeightCacheNeverServesAnotherColumn() {
        TerrainHeights varying = (x, z) ->
                0.1f + Math.floorMod(x * 73856093 ^ z * 19349663, 16) * 0.05f;
        IslandField warmed = field(varying);
        IslandField pristine = field(varying);
        for (int i = 0; i < 8192; i++) {
            warmed.columnHeight(10000 + i, 10000);
        }
        for (int[] cell : new int[][]{{0, 0}, {1, 0}, {0, 1}, {2, 2}, {-1, -1},
                {20500, -300}, {9999, 10000}, {10000, 10000}, {18191, 10000}}) {
            assertEquals(Float.floatToRawIntBits(pristine.columnHeight(cell[0], cell[1])),
                    Float.floatToRawIntBits(warmed.columnHeight(cell[0], cell[1])),
                    "the churned height cache answered column " + cell[0] + "," + cell[1]
                            + " with another column's height");
        }
    }

    /**
     * And the terrain agrees, bit for bit, after the memo has been overwritten many times over:
     * eviction is a performance event, never a value. Compared against the same field's own answer
     * from before the churn AND against a pristine field's, so both "the cache stopped changing the
     * answer" and "it did not drift from the cold path" are pinned.
     */
    @Test
    void theHeightCacheChurnDoesNotMoveTheTerrain() {
        IslandField field = new IslandField(42L);
        IslandField pristine = new IslandField(42L);
        int minY = -64;
        int maxY = 319;
        int span = maxY - minY + 1;
        int[][] columns = {{20000, 20000}, {19993, 20001}, {-1300, 1400}, {0, 0}};
        double[][] before = new double[columns.length][];
        for (int i = 0; i < columns.length; i++) {
            before[i] = new double[span];
            pristine.densityColumnInto(before[i], columns[i][0], columns[i][1], minY, maxY);
        }

        // Pigeonhole again: 8,192 distinct lattice columns through a 4,096-slot map.
        for (int i = 0; i < 8192; i++) {
            field.columnHeight(-40000 - i, 7777);
        }

        for (int i = 0; i < columns.length; i++) {
            double[] after = new double[span];
            field.densityColumnInto(after, columns[i][0], columns[i][1], minY, maxY);
            for (int y = minY; y <= maxY; y++) {
                assertEquals(Double.doubleToRawLongBits(before[i][y - minY]),
                        Double.doubleToRawLongBits(after[y - minY]),
                        "the terrain at " + columns[i][0] + "," + y + "," + columns[i][1]
                                + " moved after the height memo was churned");
            }
        }
    }

    /**
     * The companion of {@link #theBiomeHeightIsAskedOncePerLatticeColumn}: the exact 270-ask bound
     * must still hold for a chunk asked for AFTER the memo has been churned, which is the state the
     * server actually runs in. A 4,096-slot map holds the 9 lattice columns a 16x16 chunk touches
     * with room to spare, so the bound stays exactly 270 -- not "about", and not loosened here
     * either: this is the same count, in a harder state.
     */
    @Test
    void theHeightCacheStillMemoisesAChunkAfterChurn() {
        java.util.concurrent.atomic.AtomicInteger asks = new java.util.concurrent.atomic.AtomicInteger();
        IslandField field = field((x, z) -> {
            asks.incrementAndGet();
            return 0.1f;
        });
        for (int i = 0; i < 8192; i++) {
            field.columnHeight(50000 + i, -50000);
        }
        asks.set(0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                field.densityColumn(x, z, 0, MAX_HEIGHT - 1);
            }
        }
        assertEquals(270, asks.get(), "after the memo was churned, one chunk of density asked the"
                + " biome source " + asks.get() + " times for 9 lattice columns: the per-column memo"
                + " stopped paying off");
    }

    private static IslandField field(TerrainHeights heights) {
        return new IslandField(1L, BetterEndConfig.DEFAULTS, heights);
    }

    /** Solid samples per Y over the pinned island's footprint. */
    private static int[] solidPerY(IslandField field) {
        int[] out = new int[MAX_HEIGHT];
        for (int x = -96; x <= 96; x += 3) {
            for (int z = -96; z <= 96; z += 3) {
                double[] column = field.densityColumn(x, z, 0, MAX_HEIGHT - 1);
                for (int y = 0; y < MAX_HEIGHT; y++) {
                    if (column[y] > 0) out[y]++;
                }
            }
        }
        return out;
    }

    private static int widest(int[] solidPerY) {
        int widest = 0;
        for (int y = 0; y < solidPerY.length; y++) {
            if (solidPerY[y] > solidPerY[widest]) widest = y;
        }
        return widest;
    }

    private static int highest(int[] solidPerY) {
        int highest = -1;
        for (int y = 0; y < solidPerY.length; y++) {
            if (solidPerY[y] > 0) highest = y;
        }
        return highest;
    }

    /**
     * W2/B1: the three {@code coverage} keys in config.yml are the density THRESHOLD each layer's
     * simplex must clear, so raising coverage must raise the amount of land. Before the wiring this
     * was {@code Layer.COVERAGE}, one static constant: a broken implementation returns the same
     * count three times.
     */
    @Test
    void theCoverageKeysReachTheIslandField() {
        int none = solidColumns(new IslandField(42L, coverage(0f)));
        int mid = solidColumns(new IslandField(42L, BetterEndConfig.DEFAULTS));
        int all = solidColumns(new IslandField(42L, coverage(1f)));
        assertEquals(0, none, "coverage 0 still placed islands: " + none + " columns");
        assertTrue(mid > 0, "the default coverage placed no islands at all");
        assertTrue(all > mid, "coverage 1 (" + all + ") is not more land than 0.5 (" + mid + ")");
    }

    /**
     * W2/B2, the subtle one. The mod has TWO central-island flags (IslandLayer.java:88-101): the
     * GLOBAL {@code GeneratorOptions.hasCentralIsland()} gates the inner-void cull for EVERY layer,
     * and the PER-LAYER {@code options.hasCentralIsland} gates the pinned island insert on the
     * medium layer alone. The global one is not configurable here -- it is the mod's default, always
     * on -- so what this pins is the half that can still go wrong: the cull must be driven by the
     * GLOBAL flag, not by the per-layer one.
     * <p>
     * The medium layer is switched off here (coverage 0) so that anything found in the ring can
     * only have come from the big or small layer - neither of which sets the per-layer flag. An
     * implementation that gates the cull on the per-layer flag therefore fails this assertion, while
     * {@code theSpawnVoidIsEmpty} above, which lets the medium layer mask it, may still pass.
     */
    @Test
    void theInnerVoidCullIsGlobalAndTheInsertIsPerLayer() {
        for (long seed : SEEDS) {
            assertEquals(0, spawnVoidSolids(new IslandField(seed, mediumOff())),
                    "seed " + seed + ": big/small islands were not culled from the spawn void");
        }
    }

    /** DEFAULTS with every layer's coverage replaced. */
    private static BetterEndConfig coverage(float value) {
        return new BetterEndConfig(
                withCoverage(BetterEndConfig.DEFAULTS.bigIslands(), value),
                withCoverage(BetterEndConfig.DEFAULTS.mediumIslands(), value),
                withCoverage(BetterEndConfig.DEFAULTS.smallIslands(), value),
                6, 24, 12,
                BetterEndConfig.DEFAULTS.violeciteLayersPerChunk(),
                BetterEndConfig.DEFAULTS.amberVeinsPerChunk(),
                BetterEndConfig.DEFAULTS.dragonBoneVeinsPerChunk(),
                BetterEndConfig.DEFAULTS.advancementSampleTicks(),
                BetterEndConfig.DEFAULTS.monsterDamageMultiplier());
    }

    /** DEFAULTS with the medium layer's random islands off, so only big and small can place. */
    private static BetterEndConfig mediumOff() {
        return new BetterEndConfig(
                BetterEndConfig.DEFAULTS.bigIslands(),
                withCoverage(BetterEndConfig.DEFAULTS.mediumIslands(), 0f),
                BetterEndConfig.DEFAULTS.smallIslands(),
                6, 24, 12,
                BetterEndConfig.DEFAULTS.violeciteLayersPerChunk(),
                BetterEndConfig.DEFAULTS.amberVeinsPerChunk(),
                BetterEndConfig.DEFAULTS.dragonBoneVeinsPerChunk(),
                BetterEndConfig.DEFAULTS.advancementSampleTicks(),
                BetterEndConfig.DEFAULTS.monsterDamageMultiplier());
    }

    private static LayerSettings withCoverage(LayerSettings layer, float value) {
        return new LayerSettings(layer.distance(), layer.scale(), layer.averageHeight(),
                layer.heightVariation(), value);
    }

    /** Columns holding any terrain over a grid far enough out to be clear of the spawn void. */
    private static int solidColumns(IslandField field) {
        int count = 0;
        for (int x = 20000; x < 20512; x += 16) {
            for (int z = 20000; z < 20512; z += 16) {
                if (field.topY(x, z, 0, MAX_HEIGHT - 1) != Integer.MIN_VALUE) count++;
            }
        }
        return count;
    }

    /** Solid samples in the ring the inner-void cull is supposed to empty; see theSpawnVoidIsEmpty. */
    private static int spawnVoidSolids(IslandField field) {
        int count = 0;
        for (int radius = 150; radius <= 850; radius += 25) {
            for (int degrees = 0; degrees < 360; degrees += 11) {
                int x = (int) (Math.cos(Math.toRadians(degrees)) * radius);
                int z = (int) (Math.sin(Math.toRadians(degrees)) * radius);
                for (int y = 0; y < MAX_HEIGHT; y += 4) {
                    if (field.isSolid(x, y, z)) count++;
                }
            }
        }
        return count;
    }

    /** Every maximal run of solid blocks in the column, as {@code bottom, top} pairs. */
    private static List<int[]> segments(IslandField field, int x, int z) {
        double[] column = field.densityColumn(x, z, 0, 255);
        List<int[]> out = new ArrayList<>();
        int bottom = Integer.MIN_VALUE;
        for (int y = 0; y <= 256; y++) {
            boolean solid = y < 256 && column[y] > 0;
            if (solid && bottom == Integer.MIN_VALUE) {
                bottom = y;
            } else if (!solid && bottom != Integer.MIN_VALUE) {
                out.add(new int[]{bottom, y - 1});
                bottom = Integer.MIN_VALUE;
            }
        }
        return out;
    }
}
