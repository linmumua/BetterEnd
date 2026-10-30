package dev.mumu.betterend.trees;

import dev.mumu.betterend.terrain.IslandField;

import java.util.Arrays;

/**
 * One chunk's {@code minecraft:count_on_every_layer} surfaces, walked once per column.
 * <p>
 * {@code TreePopulator} asks the same question - "where is the {@code layer}-th air-over-solid
 * surface of this column?" - up to {@link TreePopulator#MAX_LAYERS} times per species, and nine
 * species share every column of the chunk. Answering it the obvious way materialises a whole
 * {@code densityColumn} per ask, so one column could be built 16 * 9 times over. This walks each
 * column at most ONCE, keeps all sixteen answers, and so costs the chunk at most 256 column builds
 * however the species and the layers interleave.
 * <p>
 * <b>Deliberately not {@code FloraPlanner.ColumnScan}.</b> That one packs a column into two longs
 * over a fixed 0..127 window, which is right for flora - every plant it places lives in that band -
 * and wrong here twice: a tree's foot is the first AIR block, so a column topping out at
 * {@link IslandField#TOP_SOLID_Y} (127) puts its trunk at y 128, one row above anything that window
 * can hold; and the populator is handed the world's own {@code minY}/{@code maxY} and must keep
 * answering over exactly those bounds. This walk starts at {@code maxY - 1} and stops above
 * {@code minY}, which is what the pre-memo {@code TreePopulator.surfaceY} did.
 * <p>
 * Created inside {@code populate} and never held on an instance, in a static field or in a
 * {@code ThreadLocal}: Paper runs populators concurrently for many chunks, and a per-call object is
 * the only kind that needs no synchronisation. {@link #column} is one scratch reused across columns,
 * so a chunk pays for a single column array instead of one per ask.
 */
final class SurfaceScan {
    /** A chunk's column count per axis; the populator only ever asks about its own 16x16. */
    private static final int SIZE = 16;

    private final IslandField field;
    private final int originX;
    private final int originZ;
    private final int minY;
    private final int maxY;
    /** The density of the column being walked, reused for every column of the chunk. */
    private final double[] column;
    /** Surfaces per chunk-local column, built on first ask; null until then. */
    private final int[][] surfaces = new int[SIZE * SIZE][];
    private int built;

    /**
     * @param originX {@code chunkX << 4}
     * @param originZ {@code chunkZ << 4}
     * @param minY    the world's own {@code getMinHeight()}; the walk ends above it
     * @param maxY    the world's own {@code getMaxHeight()}; the walk starts at {@code maxY - 1}
     */
    SurfaceScan(IslandField field, int originX, int originZ, int minY, int maxY) {
        this.field = field;
        this.originX = originX;
        this.originZ = originZ;
        this.minY = minY;
        this.maxY = maxY;
        // Exactly the span densityColumnInto asks for: it fills [minY, maxY - 1] inclusive, i.e.
        // maxY - minY doubles, so the buffer is never short (it validates that) and never carries a
        // stale tail (it does NOT zero past the window). The old array form's own length expression,
        // too, so an inverted range still throws NegativeArraySizeException out of the same
        // allocation it always did. The last row the walk reads is maxY - 1 and the block under it
        // is maxY - 2, which is why a foot at y=128 survives a world that tops out at 320.
        this.column = new double[maxY - minY];
    }

    /**
     * The {@code layer}-th air-over-solid surface of a chunk-local column - the y a trunk's foot
     * occupies - or {@link Integer#MIN_VALUE} when the column has fewer than {@code layer + 1} of
     * them. A void column answers {@code MIN_VALUE} at every layer.
     * <p>
     * {@code localX}/{@code localZ} are 0..15: the populator draws them from {@code nextInt(16)} and
     * every column it asks about is inside its own chunk, which is what makes a 16x16 table exact
     * rather than a hash. {@code layer} is below {@link TreePopulator#MAX_LAYERS}.
     */
    int surfaceY(int localX, int localZ, int layer) {
        int slot = localZ * SIZE + localX;
        int[] found = surfaces[slot];
        if (found == null) {
            built++;
            found = surfaces[slot] = walk(originX + localX, originZ + localZ);
        }
        return found[layer];
    }

    /**
     * Columns this scan has built. For the tests - it is what pins "at most once per column" -
     * and deliberately a per-call counter rather than any kind of process-wide instrumentation.
     */
    int columnsBuilt() {
        return built;
    }

    /**
     * Every surface of one column, top down, in ONE pass: air at {@code y} over solid at
     * {@code y - 1} is a surface, and the walk stops after {@link TreePopulator#MAX_LAYERS} of them
     * because no caller can ask about a deeper layer.
     */
    private int[] walk(int x, int z) {
        field.densityColumnInto(column, x, z, minY, maxY - 1);
        int[] found = new int[TreePopulator.MAX_LAYERS];
        Arrays.fill(found, Integer.MIN_VALUE);
        int n = 0;
        for (int y = maxY - 1; y > minY && n < found.length; y--) {
            if (column[y - minY] > 0 || column[y - 1 - minY] <= 0) continue;
            found[n++] = y;
        }
        return found;
    }
}
