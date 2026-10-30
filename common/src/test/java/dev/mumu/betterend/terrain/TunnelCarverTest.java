package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties a broken tunnel carver would violate. The mod's carver has {@code getRange() == 0}
 * -- it never looks at a neighbouring chunk -- and that is only sound because the field it evaluates
 * is continuous across a chunk border. {@link #theDensityFieldIsContinuousAcrossAChunkBorder()} is
 * the assertion that holds it to that; the rest pin the guards and the shape.
 */
class TunnelCarverTest {
    private static final long[] SEEDS = {12345L, 42L, -7L};
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    /** Must match TunnelCarver.SURFACE_ROOF -- and it is 5, one LESS than CaveCarver's effective 6. */
    private static final int SURFACE_ROOF = 5;
    private static final int BAND_CEILING = 48;

    /**
     * Two neighbouring chunks share their corner cave factors: chunk A's {@code (x1+16)} corner IS
     * chunk B's {@code x1} corner. Evaluate the same block from both plans and the bilinear blend
     * must agree, or tunnels step at every chunk border -- the exact bug a range-0 carver cannot
     * survive. Swap the corner order in planFor and this is the test that fails.
     */
    @Test
    void theDensityFieldIsContinuousAcrossAChunkBorder() {
        int compared = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 200; chunkX <= 240; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 240; chunkZ++) {
                    TunnelCarver.Plan left = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    TunnelCarver.Plan right = TunnelCarver.planFor(seed, placement, chunkX + 1, chunkZ);
                    if (left.isEmpty() && right.isEmpty()) {
                        continue;
                    }
                    // The shared plane: block x1+16 is dx == 1 for the left plan and dx == 0 for
                    // the right one, so both must read the same pair of corner factors.
                    int x = (chunkX + 1) << 4;
                    for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z += 4) {
                        int top = field.topY(x, z, MIN_Y, MAX_Y - 1);
                        if (top == Integer.MIN_VALUE) {
                            continue;
                        }
                        for (int y = 4; y <= BAND_CEILING; y += 4) {
                            assertEquals(left.carvedAt(x, y, z, top), right.carvedAt(x, y, z, top),
                                    "the border plane disagrees at " + x + "," + y + "," + z);
                            compared++;
                        }
                    }
                }
            }
        }
        assertTrue(compared > 2000, "sample too small to mean anything: " + compared);
    }

    /**
     * Five blocks stay solid between a tunnel's ceiling and the column's top. Note FIVE, not the
     * round carver's six: the mod's two carvers really do differ by a block here, and copying
     * CaveCarver's expression across would thin every tunnel roof.
     */
    @Test
    void neverBreachesTheSurface() {
        int checked = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 200; chunkX <= 280; chunkX += 3) {
                for (int chunkZ = 200; chunkZ <= 280; chunkZ += 3) {
                    TunnelCarver.Plan plan = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    if (plan.isEmpty()) {
                        continue;
                    }
                    for (int lx = 0; lx < 16; lx += 3) {
                        for (int lz = 0; lz < 16; lz += 3) {
                            int x = (chunkX << 4) + lx;
                            int z = (chunkZ << 4) + lz;
                            int top = field.topY(x, z, MIN_Y, MAX_Y - 1);
                            if (top == Integer.MIN_VALUE) {
                                continue;
                            }
                            int bandLow = top - SURFACE_ROOF + 1;
                            if (bandLow > BAND_CEILING) {
                                continue;
                            }
                            for (int y = Math.max(bandLow, 1); y <= Math.min(top, BAND_CEILING); y++) {
                                assertFalse(plan.carvedAt(x, y, z, top),
                                        "carved inside the roof band at " + x + "," + y + "," + z
                                                + " (top " + top + ")");
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 500, "sample never reached a column whose roof band is carvable: " + checked);
    }

    /** Nothing above the cave band and nothing at y0, which is what keeps islands whole. */
    @Test
    void staysInsideTheCaveBand() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = 200; chunkX <= 230; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 230; chunkZ++) {
                    TunnelCarver.Plan plan = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    if (plan.isEmpty()) {
                        continue;
                    }
                    int x = (chunkX << 4) + 8;
                    int z = (chunkZ << 4) + 8;
                    assertFalse(plan.carvedAt(x, 0, z, 120), "carved the world floor at y0");
                    for (int y = BAND_CEILING + 1; y < 120; y += 3) {
                        assertFalse(plan.carvedAt(x, y, z, 120), "carved above the band at y" + y);
                    }
                }
            }
        }
    }

    /**
     * The tunnel carver is listed by the six is_end_cave biomes and by nothing else. With no cave
     * corner the bilinear density is 1.0, which fails the mod's {@code density < 0.5} test for every
     * block -- so an empty plan really must carve nothing, not merely be flagged empty.
     */
    @Test
    void aChunkWithNoCaveCornerCarvesNothing() {
        int emptyPlans = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = 200; chunkX <= 240; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 240; chunkZ++) {
                    TunnelCarver.Plan plan = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    if (!plan.isEmpty()) {
                        continue;
                    }
                    emptyPlans++;
                    int x = (chunkX << 4) + 8;
                    int z = (chunkZ << 4) + 8;
                    for (int y = 1; y <= BAND_CEILING; y += 4) {
                        assertFalse(plan.carvedAt(x, y, z, 120),
                                "an empty plan carved at " + x + "," + y + "," + z);
                    }
                }
            }
        }
        assertTrue(emptyPlans > 50, "sample saw too few empty plans: " + emptyPlans);
    }

    /** The whole 1024-block spawn void is vanilla ring biomes, which list no carvers. */
    @Test
    void theSpawnVoidIsNeverCarved() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = -12; chunkX <= 12; chunkX++) {
                for (int chunkZ = -12; chunkZ <= 12; chunkZ++) {
                    assertTrue(TunnelCarver.planFor(seed, placement, chunkX, chunkZ).isEmpty(),
                            "carved inside the spawn void at chunk " + chunkX + "," + chunkZ);
                }
            }
        }
    }

    /** A void column has no terrain to hollow, whatever the noise says. */
    @Test
    void voidColumnsAreNeverCarved() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            TunnelCarver.Plan plan = TunnelCarver.planFor(seed, placement, 210, 210);
            for (int y = 1; y <= BAND_CEILING; y++) {
                assertFalse(plan.carvedAt(3363, y, 3367, Integer.MIN_VALUE),
                        "carved a void column at y" + y);
            }
        }
    }

    /**
     * Tunnels must actually exist. A threshold typo, a lost noise or a sign flip leaves every other
     * assertion here passing over a world with no tunnels at all.
     */
    @Test
    void tunnelsActuallyCarveSomething() {
        int carved = 0;
        int columns = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 200; chunkX <= 280 && carved < 200; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 280 && carved < 200; chunkZ++) {
                    TunnelCarver.Plan plan = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    if (plan.isEmpty()) {
                        continue;
                    }
                    for (int lx = 0; lx < 16; lx += 4) {
                        for (int lz = 0; lz < 16; lz += 4) {
                            int x = (chunkX << 4) + lx;
                            int z = (chunkZ << 4) + lz;
                            int top = field.topY(x, z, MIN_Y, MAX_Y - 1);
                            if (top == Integer.MIN_VALUE) {
                                continue;
                            }
                            columns++;
                            for (int y = 1; y <= BAND_CEILING; y++) {
                                if (plan.carvedAt(x, y, z, top)) {
                                    carved++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(columns > 100, "sample found too little cave-biome terrain: " + columns);
        assertTrue(carved > 0, "no block anywhere was carved by the tunnel carver");
    }

    /** Same seed, same tunnels. */
    @Test
    void planIsDeterministic() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = 200; chunkX <= 206; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 206; chunkZ++) {
                    TunnelCarver.Plan a = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    TunnelCarver.Plan b = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    assertEquals(a.isEmpty(), b.isEmpty());
                    for (int lx = 0; lx < 16; lx += 4) {
                        for (int lz = 0; lz < 16; lz += 4) {
                            int x = (chunkX << 4) + lx;
                            int z = (chunkZ << 4) + lz;
                            for (int y = 1; y <= 40; y += 4) {
                                assertEquals(a.carvedAt(x, y, z, 70), b.carvedAt(x, y, z, 70));
                            }
                        }
                    }
                }
            }
        }
    }
}
