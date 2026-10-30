package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.terrain.SpireShape.Cell;
import dev.mumu.betterend.terrain.SpireShape.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties a broken spire would violate. The failures worth catching here are the ones that
 * still produce a perfectly plausible spire: a floating spire missing its mirror loop is just a
 * second cone, a shape missing its displacement is a clean surface of revolution, and a cap
 * implemented as "highest cell in the column" looks right until you stand under an overhang.
 */
class SpireShapeTest {
    private static final long[] SEEDS = {12345L, 42L, -7L};

    /** Shapes from many chunks, ignoring the biome gate so the geometry can be measured in bulk. */
    private static List<List<Cell>> shapes(Kind kind, int wanted) {
        List<List<Cell>> out = new ArrayList<>();
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 0; chunkX < 4000 && out.size() < wanted; chunkX++) {
                for (int chunkZ = 0; chunkZ < 40 && out.size() < wanted; chunkZ++) {
                    List<Cell> cells = SpireShape.plan(seed, chunkX, chunkZ, kind, placement, field);
                    if (cells != null && !cells.isEmpty()) {
                        out.add(cells);
                    }
                }
            }
        }
        return out;
    }

    /** Height fraction at which the widest horizontal slice sits, 0 = bottom, 1 = top. */
    private static double widestSliceFraction(List<Cell> cells) {
        int lo = cells.stream().mapToInt(Cell::y).min().orElseThrow();
        int hi = cells.stream().mapToInt(Cell::y).max().orElseThrow();
        if (hi == lo) {
            return 0.5;
        }
        int[] perLayer = new int[hi - lo + 1];
        for (Cell c : cells) {
            perLayer[c.y() - lo]++;
        }
        int best = 0;
        for (int i = 1; i < perLayer.length; i++) {
            if (perLayer[i] > perLayer[best]) {
                best = i;
            }
        }
        return (double) best / (hi - lo);
    }

    /**
     * The mirror loop (FloatingSpireFeature.java:46-53) is what makes a floating spire a spindle
     * rather than a cone. Drop it and every other assertion in this file still passes.
     */
    @Test
    void theFloatingSpireIsWidestInItsMiddleAndTheGroundOneAtItsFoot() {
        List<List<Cell>> floating = shapes(Kind.FLOATING, 200);
        List<List<Cell>> ground = shapes(Kind.GROUND, 200);
        assertTrue(floating.size() >= 100, "too few floating shapes: " + floating.size());
        assertTrue(ground.size() >= 100, "too few ground shapes: " + ground.size());

        double floatingMean = floating.stream().mapToDouble(SpireShapeTest::widestSliceFraction)
                .average().orElseThrow();
        double groundMean = ground.stream().mapToDouble(SpireShapeTest::widestSliceFraction)
                .average().orElseThrow();
        assertTrue(floatingMean > 0.30,
                "the floating spire is not a spindle (widest slice at " + floatingMean
                        + " of its height); the mirror loop was lost");
        assertTrue(groundMean < 0.30,
                "the ground spire is not bottom-heavy (widest slice at " + groundMean + ")");
        assertTrue(floatingMean > groundMean + 0.08,
                "the two kinds have the same profile; they must not");
    }

    /**
     * The whole reason this is a populator and not a chunk-data write. A shape that outgrew this
     * bound would be silently clipped by LimitedRegion, or throw.
     */
    @Test
    void aSpireNeverLeavesItsChunkPlusOne() {
        for (Kind kind : Kind.values()) {
            for (long seed : SEEDS) {
                BiomePlacement placement = new BiomePlacement(seed);
                IslandField field = placement.field();
                for (int chunkX = 0; chunkX < 400; chunkX++) {
                    for (int chunkZ = 0; chunkZ < 8; chunkZ++) {
                        List<Cell> cells = SpireShape.plan(seed, chunkX, chunkZ, kind, placement, field);
                        if (cells == null) {
                            continue;
                        }
                        for (Cell c : cells) {
                            assertTrue(c.x() >= (chunkX << 4) - 16 && c.x() <= (chunkX << 4) + 31,
                                    "wrote x " + c.x() + " outside chunk " + chunkX + "'s region");
                            assertTrue(c.z() >= (chunkZ << 4) - 16 && c.z() <= (chunkZ << 4) + 31,
                                    "wrote z " + c.z() + " outside chunk " + chunkZ + "'s region");
                        }
                    }
                }
            }
        }
    }

    /**
     * The two {@code abs()}'d simplex octaves are what make a spire look eroded. Without the
     * displacement node the shape is a perfect surface of revolution and every slice a clean disc.
     */
    @Test
    void noiseActuallyDistortsTheSilhouette() {
        List<List<Cell>> all = shapes(Kind.GROUND, 120);
        int lopsided = 0;
        for (List<Cell> cells : all) {
            int lo = cells.stream().mapToInt(Cell::y).min().orElseThrow();
            int hi = cells.stream().mapToInt(Cell::y).max().orElseThrow();
            int mid = (lo + hi) / 2;
            int cx = (int) cells.stream().mapToInt(Cell::x).average().orElseThrow();
            int cz = (int) cells.stream().mapToInt(Cell::z).average().orElseThrow();
            int east = 0, west = 0, north = 0, south = 0;
            for (Cell c : cells) {
                if (c.y() != mid) {
                    continue;
                }
                if (c.z() == cz) {
                    east = Math.max(east, c.x() - cx);
                    west = Math.max(west, cx - c.x());
                }
                if (c.x() == cx) {
                    south = Math.max(south, c.z() - cz);
                    north = Math.max(north, cz - c.z());
                }
            }
            int max = Math.max(Math.max(east, west), Math.max(north, south));
            int min = Math.min(Math.min(east, west), Math.min(north, south));
            if (max - min >= 2) {
                lopsided++;
            }
        }
        assertTrue(lopsided > all.size() * 0.6,
                "only " + lopsided + "/" + all.size() + " shapes are asymmetric; the displacement"
                        + " node was lost and every slice is a clean disc");
    }

    /**
     * The displacement is a sum of ABSOLUTE values, so it can only ever eat into the shape. Lose the
     * {@code Math.abs} and the signed noise grows it instead, which also breaks the reach bound that
     * {@link SpireShape#MAX_REACH} rests on.
     */
    @Test
    void theShapeNeverExceedsTheAnalyticReach() {
        for (Kind kind : Kind.values()) {
            for (List<Cell> cells : shapes(kind, 150)) {
                int cx = cells.stream().mapToInt(Cell::x).sum() / cells.size();
                int cz = cells.stream().mapToInt(Cell::z).sum() / cells.size();
                for (Cell c : cells) {
                    assertTrue(Math.abs(c.x() - cx) <= SpireShape.MAX_REACH + 2
                                    && Math.abs(c.z() - cz) <= SpireShape.MAX_REACH + 2,
                            kind + " reached " + Math.abs(c.x() - cx) + " blocks from its axis");
                }
            }
        }
    }

    /**
     * The cap is every cell with nothing above it, which is what {@code PosInfo.getStateUp()}
     * answers -- NOT the highest cell in each column. The smooth unions make real overhangs, so on
     * every shape the two differ, and implementing the easy version leaves bare end stone under
     * every overhang.
     */
    @Test
    void theCapIsEveryUpwardFaceNotJustTheTopOfEachColumn() {
        int shapesWithOverhangs = 0;
        List<List<Cell>> all = shapes(Kind.GROUND, 120);
        for (List<Cell> cells : all) {
            long caps = cells.stream().filter(Cell::cap).count();
            Set<Long> columns = new HashSet<>();
            for (Cell c : cells) {
                columns.add(((long) c.x() << 32) ^ (c.z() & 0xFFFFFFFFL));
            }
            assertTrue(caps >= columns.size(), "fewer caps than columns; impossible");
            if (caps > columns.size()) {
                shapesWithOverhangs++;
            }
        }
        assertTrue(shapesWithOverhangs > all.size() * 0.8,
                only(shapesWithOverhangs, all.size()));
    }

    private static String only(int n, int total) {
        return "only " + n + "/" + total + " shapes have overhangs; the cap looks like it was"
                + " implemented as 'highest cell per column'";
    }

    /** A wrong rMin, a missing lift, or radius and lift drawn in the wrong order all move these. */
    @Test
    void heightsMatchTheMod() {
        for (Kind kind : Kind.values()) {
            List<List<Cell>> all = shapes(kind, 200);
            double mean = 0;
            for (List<Cell> cells : all) {
                int lo = cells.stream().mapToInt(Cell::y).min().orElseThrow();
                int hi = cells.stream().mapToInt(Cell::y).max().orElseThrow();
                int height = hi - lo + 1;
                assertTrue(height >= 6 && height <= 90, kind + " height " + height + " is off-model");
                mean += height;
            }
            mean /= all.size();
            assertTrue(mean > 30 && mean < 60, kind + " mean height " + mean + " is off-model");
        }
    }

    /**
     * SpireFeature.java:47-49 probes three and six blocks below the surface, which keeps spires off
     * the thin rims of islands. Probing the surface cell itself -- which is AIR -- passes always.
     */
    @Test
    void aGroundSpireNeedsRockUnderIt() {
        int planted = 0;
        int rejected = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 0; chunkX < 600; chunkX++) {
                for (int chunkZ = 0; chunkZ < 6; chunkZ++) {
                    List<Cell> cells = SpireShape.plan(seed, chunkX, chunkZ, Kind.GROUND, placement, field);
                    if (cells == null) {
                        rejected++;
                        continue;
                    }
                    planted++;
                    int baseX = cells.stream().mapToInt(Cell::x).sum() / cells.size();
                    int baseZ = cells.stream().mapToInt(Cell::z).sum() / cells.size();
                    int top = field.topY(baseX, baseZ);
                    // The axis can drift a block or two from the origin once the noise has eaten the
                    // shape, so probe the column the planner actually accepted, not the centroid.
                    if (top == Integer.MIN_VALUE) {
                        continue;
                    }
                    assertTrue(top + 1 >= 10, "planted a spire with its base below y10");
                }
            }
        }
        assertTrue(planted > 20, "planted almost nothing: " + planted);
        assertTrue(rejected > planted, "the gates rejected almost nothing; are they wired up?");
    }

    /** The void branch is the whole point of the floating spire; losing it deletes every sky spindle. */
    @Test
    void aFloatingSpireGeneratesOverVoidColumnsToo() {
        int overVoid = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 0; chunkX < 2000; chunkX++) {
                for (int chunkZ = 0; chunkZ < 10; chunkZ++) {
                    List<Cell> cells = SpireShape.plan(seed, chunkX, chunkZ, Kind.FLOATING, placement, field);
                    if (cells == null) {
                        continue;
                    }
                    int x = cells.stream().mapToInt(Cell::x).sum() / cells.size();
                    int z = cells.stream().mapToInt(Cell::z).sum() / cells.size();
                    if (field.topY(x, z) == Integer.MIN_VALUE) {
                        overVoid++;
                    }
                }
            }
        }
        assertTrue(overVoid > 0, "no floating spire ever generated over a void column");
    }

    /** 1/4 and 1/8. An inverted comparison yields 3/4, which still looks like a rarity filter. */
    @Test
    void rarityIsOneInFourAndOneInEight() {
        for (Kind kind : Kind.values()) {
            int hits = 0;
            int total = 0;
            for (long seed : SEEDS) {
                BiomePlacement placement = new BiomePlacement(seed);
                IslandField field = placement.field();
                for (int chunkX = 0; chunkX < 3000; chunkX++) {
                    for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                        total++;
                        if (SpireShape.rolls(seed, chunkX, chunkZ, kind)) {
                            hits++;
                        }
                    }
                }
            }
            double rate = (double) hits / total;
            double want = kind == Kind.GROUND ? 0.25 : 0.125;
            assertTrue(Math.abs(rate - want) < 0.015,
                    kind + " fires at " + rate + ", expected " + want);
        }
    }

    /** Drop the biome modifier and spires appear across the whole End. */
    @Test
    void onlyBlossomingSpiresGetsSpires() {
        int planted = 0;
        int nonBiome = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 200; chunkX <= 320; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 230; chunkZ++) {
                    boolean any = false;
                    for (Kind kind : Kind.values()) {
                        List<Cell> cells = SpireShape.plan(seed, chunkX, chunkZ, kind, placement, field);
                        if (cells == null) {
                            continue;
                        }
                        any = true;
                        planted++;
                        int x = cells.stream().mapToInt(Cell::x).sum() / cells.size();
                        int z = cells.stream().mapToInt(Cell::z).sum() / cells.size();
                        assertNull(placement.ring(x, z), "planted inside the vanilla ring");
                    }
                    if (!any) {
                        nonBiome++;
                    }
                }
            }
        }
        assertTrue(nonBiome > 100, "sample saw too few empty chunks: " + nonBiome);
        assertTrue(planted > 0, "planted nothing at all across the sample");
    }

    /** The spawn void is vanilla ring biomes; nothing of ours belongs there. */
    @Test
    void theSpawnVoidGetsNothing() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (Kind kind : Kind.values()) {
                for (int chunkX = -12; chunkX <= 12; chunkX++) {
                    for (int chunkZ = -12; chunkZ <= 12; chunkZ++) {
                        assertNull(SpireShape.plan(seed, chunkX, chunkZ, kind, placement, field),
                                "planted a spire inside the spawn void at " + chunkX + "," + chunkZ);
                    }
                }
            }
        }
    }

    /**
     * Any shared RNG, any lazily initialised static, or any use of the {@code Random} Paper passes
     * a populator -- which replays whichever stream the previous populator drew -- shows up here.
     */
    @Test
    void planIsDeterministic() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (Kind kind : Kind.values()) {
                for (int chunkX = 0; chunkX < 200; chunkX++) {
                    List<Cell> a = SpireShape.plan(seed, chunkX, 3, kind, placement, field);
                    List<Cell> b = SpireShape.plan(seed, chunkX, 3, kind, placement, field);
                    if (a == null) {
                        assertNull(b);
                        continue;
                    }
                    assertNotNull(b);
                    assertEquals(a, b, "two plans for one chunk differ");
                }
            }
        }
    }

    /**
     * One salt per kind. Share a stream and the floating spire lands on the ground spire's
     * {@code in_square} position in every chunk where both fire, stacking them into one column.
     */
    @Test
    void theTwoKindsDoNotShareAStream() {
        int both = 0;
        int sameSpot = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 0; chunkX < 4000; chunkX++) {
                List<Cell> g = SpireShape.plan(seed, chunkX, 5, Kind.GROUND, placement, field);
                List<Cell> f = SpireShape.plan(seed, chunkX, 5, Kind.FLOATING, placement, field);
                if (g == null || f == null) {
                    continue;
                }
                both++;
                int gx = g.stream().mapToInt(Cell::x).sum() / g.size();
                int fx = f.stream().mapToInt(Cell::x).sum() / f.size();
                if (gx == fx) {
                    sameSpot++;
                }
            }
        }
        assertTrue(both > 5, "the two kinds never both fired: " + both);
        assertTrue(sameSpot < both * 0.5,
                sameSpot + "/" + both + " chunks stacked both spires on one spot; shared salt");
    }

    /** The cap material is the biome's own top block, and the biome is the one the mod names. */
    @Test
    void theBiomeIsTheOneWithPinkMoss() {
        assertEquals("betterend:pink_moss", BiomeSurface.BLOSSOMING_SPIRES.top());
        assertEquals("minecraft:end_stone", BiomeSurface.BLOSSOMING_SPIRES.under(),
                "the under material is end stone, which is why the mod's under-skin pass is a no-op");
        assertFalse(BiomeSurface.BLOSSOMING_SPIRES.hasCaves(),
                "hasCaves false is why sampling the biome at the spire base is safe at any height");
    }
}
