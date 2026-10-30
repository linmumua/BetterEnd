package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.terrain.IceStarPlanner.Cell;
import dev.mumu.betterend.terrain.IceStarPlanner.Ice;
import dev.mumu.betterend.terrain.IceStarPlanner.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties a broken ice star would violate. Three of these catch bugs that still produce a
 * convincing star: a capped cone read as {@code size} tall instead of {@code 2*size} halves every
 * spike, a spike axis taken straight from the Fibonacci point instead of its mirror turns the whole
 * star 180 degrees about Y, and "fixing" the doubled up-spike adds a downward one the mod never has.
 */
class IceStarPlannerTest {
    private static final long[] SEEDS = {12345L, 42L, -7L};

    private static List<IceStarPlanner.Star> stars(Kind kind, int wanted) {
        List<IceStarPlanner.Star> out = new ArrayList<>();
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = 0; chunkX < 90000 && out.size() < wanted; chunkX++) {
                IceStarPlanner.Star star = IceStarPlanner.plan(seed, chunkX, 7, kind, placement);
                if (star != null && !star.cells().isEmpty()) {
                    out.add(star);
                }
            }
        }
        return out;
    }

    /** Chebyshev reach of a star furthest block from its own drawn centre. */
    private static int reach(IceStarPlanner.Star star) {
        int max = 0;
        for (Cell c : star.cells()) {
            max = Math.max(max, Math.max(Math.abs(c.x() - star.x()),
                    Math.max(Math.abs(c.y() - star.y()), Math.abs(c.z() - star.z()))));
        }
        return max;
    }

    /**
     * {@code SDFCappedCone} spans {@code y in [-height, +height]}, so a spike is {@code 2*size} long
     * and reaches {@code 2*size - 0.5} past the centre. Reading it as {@code size} tall is the
     * easiest arithmetic slip here, and it halves every star while leaving it looking like a star.
     */
    @Test
    void aSpikeIsTwiceTheConfiguredSizeLong() {
        int seen = 0;
        for (IceStarPlanner.Star star : stars(Kind.BIG, 60)) {
            seen++;
            int reach = reach(star);
            // A spike runs from 0.5 behind the centre to 2*size - 0.5 in front of it, so the
            // furthest block sits just under 2*size away. Read the cone as `size` tall and every
            // one of these lands at half the distance, on a star that still looks like a star.
            assertTrue(reach <= Math.ceil(2 * star.size()),
                    "a size-" + star.size() + " star reached " + reach + ", further than 2*size");
            assertTrue(reach >= star.size(),
                    "a size-" + star.size() + " star only reached " + reach + "; the cone was read"
                            + " as size tall instead of 2*size");
        }
        assertTrue(seen >= 20, "too few big stars to measure: " + seen);

        for (IceStarPlanner.Star star : stars(Kind.SMALL, 40)) {
            assertTrue(reach(star) <= Math.ceil(2 * star.size()),
                    "a small star reached " + reach(star) + " at size " + star.size());
        }
    }

    /**
     * The first lattice point is exactly {@code (0,1,0)} and the last exactly {@code (0,-1,0)}; the
     * mod's two angle guards send BOTH to straight up, so a star has two coincident up-spikes and no
     * down-spike. Tidying that into a proper antipodal pair is a real change to the silhouette.
     */
    @Test
    void thereAreTwoUpSpikesAndNoDownSpike() {
        for (int count : new int[]{7, 10, 12, 18, 25}) {
            float[][] axes = IceStarPlanner.axes(count);
            assertEquals(count, axes.length);
            int up = 0;
            int down = 0;
            for (float[] axis : axes) {
                if (axis[1] > 0.999f && Math.abs(axis[0]) < 1e-4 && Math.abs(axis[2]) < 1e-4) {
                    up++;
                }
                if (axis[1] < -0.999f) {
                    down++;
                }
            }
            assertEquals(2, up, "count " + count + " should have exactly two straight-up spikes");
            assertEquals(0, down, "count " + count + " grew a downward spike the mod does not have");
        }
    }

    /**
     * {@code SDFRotation} rotates the query point, so the shape is the INVERSE rotation and the
     * spikes aim at {@code (-x, y, -z)}. Taking the Fibonacci point directly gives a star rotated
     * 180 degrees about Y -- same silhouette from above, every spike in the wrong place.
     */
    @Test
    void spikeAxesAreTheLatticeMirroredInXZ() {
        int count = 12;
        float[][] axes = IceStarPlanner.axes(count);
        double phi = Math.PI * (3.0 - Math.sqrt(5.0));
        int checked = 0;
        for (int i = 0; i < count; i++) {
            double y = 1.0 - (i / (double) (count - 1)) * 2.0;
            double angle = Math.acos(Math.max(-1.0, Math.min(1.0, y)));
            if (angle <= 0.01 || angle >= 3.14) {
                continue;
            }
            double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double x = Math.cos(phi * i) * r;
            double z = Math.sin(phi * i) * r;
            assertEquals(-x, axes[i][0], 1e-5, "spike " + i + " x is not mirrored");
            assertEquals(y, axes[i][1], 1e-5, "spike " + i + " y must not be mirrored");
            assertEquals(-z, axes[i][2], 1e-5, "spike " + i + " z is not mirrored");
            checked++;
        }
        assertTrue(checked >= count - 2, "almost every spike should be a mirrored lattice point");
        // Every axis is a unit vector, or the rotation-free cone evaluation is not equivalent.
        for (float[] axis : axes) {
            double len = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
            assertEquals(1.0, len, 1e-5, "a spike axis is not a unit vector");
        }
    }

    /**
     * A small star's {@code iceRadius} is {@code size * 5}, which exceeds the whole star, so it can
     * never grow the outer snow shell. Using the big formula for both makes small stars snowballs.
     */
    @Test
    void smallStarsAreSolidIceAndBigOnesHaveASnowShell() {
        Set<Ice> small = EnumSet.noneOf(Ice.class);
        for (IceStarPlanner.Star star : stars(Kind.SMALL, 25)) {
            for (Cell c : star.cells()) {
                small.add(c.ice());
            }
        }
        assertTrue(!small.isEmpty(), "no small star was planned at all");
        assertTrue(!small.contains(Ice.SNOW),
                "a small star grew a snow shell; iceRadius must be size*5 below size 7");

        Set<Ice> big = EnumSet.noneOf(Ice.class);
        for (IceStarPlanner.Star star : stars(Kind.BIG, 25)) {
            for (Cell c : star.cells()) {
                big.add(c.ice());
            }
        }
        assertTrue(big.contains(Ice.SNOW), "no big star grew the outer dense_snow shell");
        assertTrue(big.contains(Ice.ANCIENT), "no big star has an ancient emerald ice core");
    }

    /** Every star must be one connected lump, which is what the base disc straddling the centre buys. */
    @Test
    void theStarCentreIsAlwaysInsideTheStar() {
        for (Kind kind : Kind.values()) {
            for (IceStarPlanner.Star star : stars(kind, 20)) {
                boolean centre = star.cells().stream().anyMatch(
                        c -> c.x() == star.x() && c.y() == star.y() && c.z() == star.z());
                assertTrue(centre, kind + " star has a hollow centre; the cones do not overlap");
            }
        }
    }

    /** 1/15 and 1/8. An inverted comparison gives 14/15, which still looks like a rarity filter. */
    @Test
    void rarityIsOneInFifteenAndOneInEight() {
        for (Kind kind : Kind.values()) {
            int hits = 0;
            int total = 0;
            for (long seed : SEEDS) {
                for (int chunkX = 0; chunkX < 4000; chunkX++) {
                    for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                        total++;
                        if (IceStarPlanner.rolls(seed, chunkX, chunkZ, kind)) {
                            hits++;
                        }
                    }
                }
            }
            double rate = (double) hits / total;
            double want = kind == Kind.BIG ? 1.0 / 15 : 1.0 / 8;
            assertTrue(Math.abs(rate - want) < 0.012, kind + " fires at " + rate + ", want " + want);
        }
    }

    /** The star hangs in the air: the mod never calls a heightmap, and the biome is a void one. */
    @Test
    void starsHangBetweenY32AndY128PlusTheirReach() {
        for (Kind kind : Kind.values()) {
            for (IceStarPlanner.Star star : stars(kind, 20)) {
                int lo = star.cells().stream().mapToInt(Cell::y).min().orElseThrow();
                int hi = star.cells().stream().mapToInt(Cell::y).max().orElseThrow();
                assertTrue(lo > 0, kind + " reached y" + lo + ", below the world floor");
                assertTrue(hi < 128 + 32, kind + " reached y" + hi + ", far above the draw range");
            }
        }
    }

    /** Nothing of ours belongs in the vanilla ring, and only ice_starfield lists this feature. */
    @Test
    void theSpawnVoidGetsNothing() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (Kind kind : Kind.values()) {
                for (int chunkX = -12; chunkX <= 12; chunkX++) {
                    for (int chunkZ = -12; chunkZ <= 12; chunkZ++) {
                        assertNull(IceStarPlanner.plan(seed, chunkX, chunkZ, kind, placement),
                                "planted an ice star in the spawn void at " + chunkX + "," + chunkZ);
                    }
                }
            }
        }
    }

    /** Same seed, same star. */
    @Test
    void planIsDeterministic() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (Kind kind : Kind.values()) {
                for (int chunkX = 0; chunkX < 3000; chunkX += 7) {
                    IceStarPlanner.Star a = IceStarPlanner.plan(seed, chunkX, 7, kind, placement);
                    IceStarPlanner.Star b = IceStarPlanner.plan(seed, chunkX, 7, kind, placement);
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
}
