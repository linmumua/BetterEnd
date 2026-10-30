package dev.mumu.betterend.trees;

import dev.mumu.betterend.terrain.IslandField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SurfaceScan} replaces the populator's per-ask {@code densityColumn} with one memoised walk
 * per column. The whole change is meant to be INVISIBLE - same surfaces, same trunk feet, same trees
 * - so the pin is an independent re-implementation of the formula it replaced, compared layer by
 * layer over real terrain.
 * <p>
 * No server: {@code IslandField} is pure math, and {@link TreePopulator#MAX_LAYERS} is a compile-time
 * constant, so nothing here touches CraftEngine or Bukkit.
 * <p>
 * The window is the End world's own {@code -64..320}, not {@code IslandField.TOP_SOLID_Y}. That is
 * the point: a foot is the first AIR block, so it sits one row above the highest solid one, and a
 * column topping out at y=127 puts its trunk at y=128 - outside the solid band and outside the fixed
 * 0..127 window {@code FloraPlanner.ColumnScan} packs its columns into.
 */
class SurfaceScanTest {
    /** {@code worldInfo.getMinHeight()} / {@code getMaxHeight()} of the End dimension. */
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

    /**
     * The pre-memo formula, character for character ({@code TreePopulator.surfaceY}): a fresh
     * column over {@code [minY, maxY - 1]} and a top-down walk that returns the {@code layer}-th
     * air-over-solid surface.
     */
    private static int oldSurfaceY(IslandField field, int x, int z, int minY, int maxY, int layer) {
        double[] column = field.densityColumn(x, z, minY, maxY - 1);
        int found = 0;
        for (int y = maxY - 1; y > minY; y--) {
            if (column[y - minY] > 0 || column[y - 1 - minY] <= 0) continue;
            if (found++ == layer) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Every layer of every sampled column answers exactly what the old per-ask scan answered. The
     * origin chunk is compared over all 256 of its columns, because that is where the pinned island
     * is; three chunks with negative origins are sampled more thinly and prove nothing depends on a
     * positive {@code chunkX << 4}.
     */
    @Test
    void everyLayerOfEveryColumnMatchesTheOldFormula() {
        IslandField field = new IslandField(42L);
        int compared = 0;
        int surfaces = 0;
        for (int[] chunk : new int[][]{{0, 0}, {-1, -1}, {2, -3}, {-4, 1}}) {
            SurfaceScan scan = new SurfaceScan(field, chunk[0] << 4, chunk[1] << 4, MIN_Y, MAX_Y);
            int step = chunk[0] == 0 && chunk[1] == 0 ? 1 : 5;
            for (int localX = 0; localX < 16; localX += step) {
                for (int localZ = 0; localZ < 16; localZ += step) {
                    int x = (chunk[0] << 4) + localX;
                    int z = (chunk[1] << 4) + localZ;
                    for (int layer = 0; layer < TreePopulator.MAX_LAYERS; layer++) {
                        int expected = oldSurfaceY(field, x, z, MIN_Y, MAX_Y, layer);
                        assertEquals(expected, scan.surfaceY(localX, localZ, layer),
                                "layer " + layer + " at " + x + "," + z);
                        compared++;
                        if (expected != Integer.MIN_VALUE) surfaces++;
                    }
                }
            }
        }
        assertTrue(compared > 4_000, "the sample compared only " + compared + " answers");
        // Non-degeneracy: comparing Integer.MIN_VALUE against Integer.MIN_VALUE 4,000 times would
        // satisfy every assertion above while proving nothing at all. The origin column is the
        // pinned island, and BetterEndPopulatorTest already pins that its top is above y=66 on this
        // seed, so at least that column answers a real surface - and the counter has to see it.
        assertTrue(oldSurfaceY(field, 0, 0, MIN_Y, MAX_Y, 0) > 66,
                "(0,0) holds no terrain on seed 42 - the comparison is vacuous");
        assertTrue(surfaces > 0, "the sample matched no real surface at all");
    }

    /**
     * The foot is one row ABOVE the highest solid block, so it falls outside the solid band. Driving
     * the window so its last row is exactly that foot pins two things at once: the walk reads its own
     * last row, and the scratch column covers {@code [minY, maxY - 1]} rather than
     * {@code [minY, maxY - 2]}. Clamp either end to the solid band - {@code TOP_SOLID_Y}, or
     * {@code ColumnScan}'s fixed 0..127 - and this column loses its foot altogether.
     */
    @Test
    void theFootSitsOneRowAboveTheHighestSolidBlock() {
        IslandField field = new IslandField(42L);
        SurfaceScan world = new SurfaceScan(field, 0, 0, MIN_Y, MAX_Y);
        int checked = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = field.topY(x, z, MIN_Y, MAX_Y);
                if (top == Integer.MIN_VALUE) continue;
                // maxY - 1 == top + 1: the foot is the last row this window can look at.
                SurfaceScan narrow = new SurfaceScan(field, 0, 0, MIN_Y, top + 2);
                assertEquals(top + 1, narrow.surfaceY(x, z, 0), "narrow window, foot at " + x + "," + z);
                assertEquals(top + 1, oldSurfaceY(field, x, z, MIN_Y, top + 2, 0));
                // And the world's own window agrees, so a column topping out at TOP_SOLID_Y (127)
                // gets y=128 - the trunk foot the mod's patches put one block above the surface.
                assertEquals(top + 1, world.surfaceY(x, z, 0), "world window, foot at " + x + "," + z);
                checked++;
            }
        }
        assertTrue(checked > 0, "no column of the origin chunk holds terrain");
    }

    /**
     * The reason this class exists: one {@code densityColumnInto} per column per chunk, whatever the
     * species count, the layer count or the ask order. Touched top layer first, which is the worst
     * order for a memo that only kept the deepest answer it had seen.
     */
    @Test
    void aColumnIsScannedOnceHoweverOftenItIsAsked() {
        IslandField field = new IslandField(42L);
        SurfaceScan scan = new SurfaceScan(field, 0, 0, MIN_Y, MAX_Y);
        long answers = 0;
        for (int round = 0; round < 3; round++) {
            for (int layer = TreePopulator.MAX_LAYERS - 1; layer >= 0; layer--) {
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        scan.surfaceY(localX, localZ, layer);
                        answers++;
                    }
                }
            }
        }
        assertEquals(3L * TreePopulator.MAX_LAYERS * 256, answers);
        assertEquals(256, scan.columnsBuilt(), "a chunk column was scanned more than once");
    }

    /**
     * A void column has no surface at any layer and says so at every layer, which is what makes the
     * populator's {@code any} flag end the loop on the first empty round. Chunks are probed until one
     * holds a void column and every void column found is checked, so this cannot pass by measuring
     * nothing without saying so.
     */
    @Test
    void aVoidColumnAnswersNothingAtEveryLayer() {
        IslandField field = new IslandField(42L);
        int checked = 0;
        for (int chunkZ = 100; chunkZ < 104 && checked == 0; chunkZ++) {
            SurfaceScan scan = new SurfaceScan(field, 100 << 4, chunkZ << 4, MIN_Y, MAX_Y);
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    if (field.topY((100 << 4) + localX, (chunkZ << 4) + localZ, MIN_Y, MAX_Y)
                            != Integer.MIN_VALUE) {
                        continue;
                    }
                    checked++;
                    for (int layer = 0; layer < TreePopulator.MAX_LAYERS; layer++) {
                        assertEquals(Integer.MIN_VALUE, scan.surfaceY(localX, localZ, layer),
                                "void column at " + localX + "," + localZ + " answered at layer " + layer);
                    }
                }
            }
        }
        assertTrue(checked > 0, "four chunks near x=1600 hold no void column at all");
    }
}
