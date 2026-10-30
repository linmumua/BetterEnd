package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndSpikes} is a port of vanilla's {@code EndSpikeFeature.SpikeCacheLoader.load}, and the
 * whole pillar replacement ({@link YungPillars}) is aimed at the coordinates it returns. If it
 * drifts, the templates land on empty air and nobody notices, so this pins both the fixed part (the
 * ten centres, which take no random input at all) and the seed-dependent part (which size lands on
 * which centre).
 */
class EndSpikesTest {

    /**
     * {@code EndSpikeFeature.java:183-184} reads no random, so these are the same on every seed.
     * A version that fed the seed into the azimuth would move them.
     */
    private static final int[][] CENTRES = {
            {42, 0}, {33, 24}, {12, 39}, {-13, 39}, {-34, 24},
            {-42, -1}, {-34, -25}, {-13, -40}, {12, -40}, {33, -25}
    };

    @Test
    void theTenCentresAreSeedIndependent() {
        for (long seed : new long[]{42L, 0L, -1L, 123456789L, Long.MIN_VALUE}) {
            List<EndSpikes.Spike> spikes = EndSpikes.forSeed(seed);
            assertEquals(EndSpikes.COUNT, spikes.size(), "wrong spike count on seed " + seed);
            for (int i = 0; i < EndSpikes.COUNT; i++) {
                assertEquals(CENTRES[i][0], spikes.get(i).centerX(), "centreX " + i + " seed " + seed);
                assertEquals(CENTRES[i][1], spikes.get(i).centerZ(), "centreZ " + i + " seed " + seed);
            }
        }
    }

    /**
     * A regression pin on the shuffle, not an independent derivation: computed by running the
     * algorithm read off {@code Util.java:1015-1023} and {@code SingleThreadedRandomSource.java:38-51}.
     * Without it an implementation that skipped the shuffle entirely would still pass every
     * structural test below, because 0..9 in order is also a permutation.
     */
    @Test
    void theSizePermutationMatchesVanillasShuffle() {
        assertEquals(Arrays.toString(new int[]{8, 9, 3, 2, 1, 4, 5, 6, 7, 0}), sizes(42L));
        assertEquals(Arrays.toString(new int[]{6, 5, 1, 7, 3, 9, 0, 2, 4, 8}), sizes(0L));
        assertEquals(Arrays.toString(new int[]{9, 1, 6, 2, 3, 8, 5, 7, 0, 4}), sizes(-1L));
        assertEquals(Arrays.toString(new int[]{3, 1, 7, 8, 6, 4, 2, 9, 0, 5}), sizes(123456789L));
    }

    /** Different seeds must give different assignments, or the pin above is pinning a constant. */
    @Test
    void theAssignmentActuallyMovesWithTheSeed() {
        Set<String> distinct = new LinkedHashSet<>();
        for (long seed = 0; seed < 64; seed++) distinct.add(sizes(seed));
        assertTrue(distinct.size() > 30,
                "only " + distinct.size() + " distinct size assignments over 64 seeds");
    }

    /**
     * radius, height and guarded are three views of one draw ({@code EndSpikeFeature.java:186-188}),
     * so they can be checked against each other rather than pinned separately. This is what catches
     * the {@code SPIKE_MAX_RADIUS = 3} bug: {@code 2 + 9/3} is 5.
     */
    @Test
    void radiusHeightAndGuardedAgreeWithTheSize() {
        for (long seed = 0; seed < 32; seed++) {
            Set<Integer> seen = new LinkedHashSet<>();
            int guarded = 0;
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                seen.add(spike.size());
                assertEquals(2 + spike.size() / 3, spike.radius(), "radius on seed " + seed);
                assertEquals(76 + spike.size() * 3, spike.height(), "height on seed " + seed);
                assertEquals(spike.size() == 1 || spike.size() == 2, spike.guarded());
                if (spike.guarded()) {
                    guarded++;
                    // Both guarded sizes are below 3, so every cage in a vanilla world is on a
                    // radius-2 spike. {@link YungPillars} relies on this: the 5x5 cage it clears is
                    // exactly vanilla's, and the guarded template is the one with its own cage.
                    assertEquals(2, spike.radius(), "a guarded spike is not radius 2 on seed " + seed);
                    assertTrue(spike.height() == 79 || spike.height() == 82,
                            "guarded height " + spike.height() + " on seed " + seed);
                }
            }
            assertEquals(10, seen.size(), "sizes are not a permutation of 0..9 on seed " + seed);
            assertEquals(2, guarded, "not exactly two guarded spikes on seed " + seed);
            assertEquals(0, java.util.Collections.min(seen));
            assertEquals(9, java.util.Collections.max(seen));
        }
    }

    /** The constants the core disc is sized against, now sourced rather than inferred. */
    @Test
    void theRangesMatchTheCoreConstants() {
        int maxRadius = 0;
        int maxReach = 0;
        for (long seed = 0; seed < 32; seed++) {
            for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
                maxRadius = Math.max(maxRadius, spike.radius());
                maxReach = Math.max(maxReach, Math.max(
                        Math.abs(spike.centerX()) + spike.radius(),
                        Math.abs(spike.centerZ()) + spike.radius()));
                assertTrue(VanillaEndCore.inCoreQuart(spike.centerX(), spike.centerZ()),
                        "spike centre outside the vanilla core");
            }
        }
        assertEquals(VanillaEndCore.SPIKE_MAX_RADIUS, maxRadius,
                "SPIKE_MAX_RADIUS disagrees with EndSpikeFeature.java:186");
        assertTrue(maxReach <= VanillaEndCore.SPIKE_RING_RADIUS + VanillaEndCore.SPIKE_MAX_RADIUS,
                "a spike reaches past SPIKE_RING_RADIUS + SPIKE_MAX_RADIUS");
    }

    private static String sizes(long seed) {
        List<Integer> out = new ArrayList<>();
        for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) out.add(spike.size());
        int[] array = new int[out.size()];
        for (int i = 0; i < array.length; i++) array[i] = out.get(i);
        return Arrays.toString(array);
    }
}
