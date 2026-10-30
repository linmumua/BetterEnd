package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core disc is the only geometry this package owns, and its failure mode is silent: if it
 * stops covering the spike ring, pillars vanish one at a time and the End still looks intentional.
 * Every assertion here is one that a shrunken, mis-signed or off-by-one core would fail.
 *
 * <p>The core was a +-80 square until the seam fix; it is now a radius-384 circle, and it is the
 * only vanilla region left ({@code BiomePlacement.inCentralRing} is deleted). The edge assertions
 * below therefore pin a circle -- reverting to {@code Math.abs} passes every axis assertion and
 * fails the diagonal pair.
 *
 * <p>There is no test of pillar positions, heights or crystal placement because the plugin places
 * none of those -- vanilla does. See {@link VanillaEndCore} for why placing them here would break
 * the dragon fight.
 */
class VanillaEndCoreTest {

    /** One sample per degree, so the axis and diagonal extremes are all hit. */
    private static final int AZIMUTHS = 360;

    /** The spike ring, expanded by the widest pillar, as integer block coordinates. */
    private static Set<int[]> pillarFootprint() {
        Set<int[]> points = new LinkedHashSet<>();
        for (int i = 0; i < AZIMUTHS; i++) {
            double angle = 2 * Math.PI * i / AZIMUTHS;
            int cx = (int) Math.round(VanillaEndCore.SPIKE_RING_RADIUS * Math.cos(angle));
            int cz = (int) Math.round(VanillaEndCore.SPIKE_RING_RADIUS * Math.sin(angle));
            int r = VanillaEndCore.SPIKE_MAX_RADIUS;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    points.add(new int[]{cx + dx, cz + dz});
                }
            }
        }
        return points;
    }

    @Test
    void ringSampleIsNonDegenerate() {
        Set<int[]> points = pillarFootprint();
        assertTrue(points.size() > 1000, "footprint collapsed to " + points.size() + " points");

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] p : points) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxZ = Math.max(maxZ, p[1]);
        }
        // A ring, not a blob in one quadrant: it must straddle both axes and reach the radius.
        assertEquals(-(VanillaEndCore.SPIKE_RING_RADIUS + VanillaEndCore.SPIKE_MAX_RADIUS), minX);
        assertEquals(VanillaEndCore.SPIKE_RING_RADIUS + VanillaEndCore.SPIKE_MAX_RADIUS, maxX);
        assertEquals(minX, minZ);
        assertEquals(maxX, maxZ);
    }

    /**
     * T1. The quart form, not the raw one: {@code inCoreQuart} floors to the quart origin, which
     * moves a negative coordinate AWAY from the origin, so it is the strictly stronger assertion
     * and it is the evaluation every production caller actually makes.
     */
    @Test
    void everyPillarBlockIsInsideTheCore() {
        for (int[] p : pillarFootprint()) {
            assertTrue(VanillaEndCore.inCoreQuart(p[0], p[1]),
                    "pillar block " + p[0] + "," + p[1] + " falls outside the THE_END core disc,"
                            + " so that pillar would silently never generate");
        }
    }

    @Test
    void coreClearsTheRingWithRoomToSpare() {
        // Strictly larger, not exactly tight.
        assertTrue(VanillaEndCore.CORE_RADIUS
                        > VanillaEndCore.SPIKE_RING_RADIUS + VanillaEndCore.SPIKE_MAX_RADIUS,
                "core disc does not clear the spike ring at all");
        // 8x, not one chunk. Both constants are now SOURCED (EndSpikeFeature.java:53 and :186, via
        // EndSpikes, which EndSpikesTest cross-checks), so this is no longer insurance against 42
        // being wrong -- it is free headroom, the core sitting entirely in void. It still goes red
        // if anyone shrinks the disc back toward the 47-block correctness floor.
        assertTrue(VanillaEndCore.CORE_RADIUS
                        >= 8 * (VanillaEndCore.SPIKE_RING_RADIUS + VanillaEndCore.SPIKE_MAX_RADIUS),
                "the core disc must keep 8x clearance over the sourced spike footprint");
    }

    @Test
    void coreIsBoundedAndSymmetric() {
        int e = VanillaEndCore.CORE_RADIUS;
        assertTrue(VanillaEndCore.inCore(0, 0));
        // Inclusive edge, exclusive one block further: catches < vs <=.
        assertTrue(VanillaEndCore.inCore(e, 0));
        assertTrue(VanillaEndCore.inCore(0, -e));
        assertFalse(VanillaEndCore.inCore(e + 1, 0));
        assertFalse(VanillaEndCore.inCore(0, -(e + 1)));
        // The diagonal, at R/sqrt(2) ~= 271.5. This is the pair a Chebyshev/Euclidean mix-up fails
        // and the old square could not distinguish: inCore(e, e) used to be true, and under a
        // circle the corner sits at r=543, out in the island spill band.
        assertTrue(VanillaEndCore.inCore(271, 271));
        assertFalse(VanillaEndCore.inCore(272, 272));
        assertFalse(VanillaEndCore.inCore(e, e), "the core is a circle, not a square");
        // Non-degenerate: the core is not simply everywhere.
        assertFalse(VanillaEndCore.inCore(1024, 1024));

        for (int x = -e - 4; x <= e + 4; x += 3) {
            for (int z = -e - 4; z <= e + 4; z += 3) {
                boolean in = VanillaEndCore.inCore(x, z);
                assertEquals(in, VanillaEndCore.inCore(x, z), "not a pure function");
                assertEquals(in, VanillaEndCore.inCore(-x, z), "asymmetric in x");
                assertEquals(in, VanillaEndCore.inCore(x, -z), "asymmetric in z");
                assertEquals(in, VanillaEndCore.inCore(z, x), "not symmetric under axis swap");
            }
        }
    }

    @Test
    void chunkWindowCoversTheCoreExactly() {
        int e = VanillaEndCore.CORE_RADIUS;
        int min = VanillaEndCore.coreChunkMin();
        int max = VanillaEndCore.coreChunkMax();
        assertTrue(min < 0 && max > 0, "degenerate chunk window " + min + ".." + max);

        // Covers: every core block lands in a chunk inside the window. With the current extent a
        // multiple of 16, >> 4 and / 16 agree, so this does not discriminate them today -- it is
        // here for the extent that is not a multiple of 16, where / 16 rounds toward zero and
        // leaves the lowest negative strip uncovered.
        for (int x = -e; x <= e; x++) {
            assertTrue(x >> 4 >= min && x >> 4 <= max, "core block " + x + " outside chunks " + min + ".." + max);
        }
        // Tight: neither neighbouring chunk holds any core block, so the migration advice does not
        // tell owners to delete more of their world than it has to.
        for (int x = (min - 1) * 16; x < min * 16; x++) {
            assertFalse(VanillaEndCore.inCore(x, 0), "chunk " + (min - 1) + " holds core block " + x);
        }
        for (int x = (max + 1) * 16; x < (max + 2) * 16; x++) {
            assertFalse(VanillaEndCore.inCore(x, 0), "chunk " + (max + 1) + " holds core block " + x);
        }
    }

    /**
     * T3. Every block of a quart takes its quart origin's answer, so the surface path (called per
     * block column) and the biome provider (called per quart origin) can never disagree. The bug
     * this catches is a caller using raw {@code inCore}, which puts a 1-3 block strip of BetterEnd
     * surface inside a quart the provider named {@code the_end}. Includes the negative quadrants,
     * where a {@code / 4 * 4} implementation rounds toward zero and gets the answer wrong.
     */
    @Test
    void everyBlockOfAQuartGetsTheQuartsAnswer() {
        int r = VanillaEndCore.CORE_RADIUS;
        boolean sawIn = false;
        boolean sawOut = false;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int x = r - 8; x <= r + 8; x++) {
                    for (int z = -8; z <= 8; z++) {
                        int bx = sx * x;
                        int bz = sz * z;
                        boolean quart = VanillaEndCore.inCoreQuart(bx & ~3, bz & ~3);
                        assertEquals(quart, VanillaEndCore.inCoreQuart(bx, bz),
                                "block " + bx + "," + bz + " disagreed with its own quart origin");
                        assertEquals(VanillaEndCore.inCore(bx & ~3, bz & ~3), quart,
                                "inCoreQuart is not inCore at the quart origin");
                        sawIn |= quart;
                        sawOut |= !quart;
                    }
                }
            }
        }
        assertTrue(sawIn && sawOut,
                "the window never straddled the boundary, so the sample is degenerate");
    }
}
