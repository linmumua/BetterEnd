package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties a broken carver would actually violate. Every assertion here is one that the
 * obvious wrong implementations fail: a carver that ignores the roof guard breaches the surface, one
 * that ignores the band ceiling hollows islands above y48, one that reseeds per target chunk gives a
 * different cavern from each side of a border, and one that drops the vertical squash carves spheres
 * instead of flattened lenses.
 */
class CaveCarverTest {
    private static final long[] SEEDS = {12345L, 42L, -7L};
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    /** Must match CaveCarver.SURFACE_ROOF; asserted indirectly by {@link #neverBreachesTheSurface()}. */
    private static final int SURFACE_ROOF = 5;
    private static final int BAND_CEILING = 48;

    /**
     * A cavern straddling a chunk border must be identical from both sides. A carver that seeded its
     * random from the TARGET chunk instead of the START chunk passes every other test here and fails
     * this one, leaving a step in the cave wall exactly on the border.
     */
    @Test
    void aCavernIsTheSameFromEitherSideOfAChunkBorder() {
        int agreed = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = -6; chunkX <= 6; chunkX++) {
                for (int chunkZ = -6; chunkZ <= 6; chunkZ++) {
                    CaveCarver.Plan left = CaveCarver.planFor(seed, placement, chunkX, chunkZ);
                    CaveCarver.Plan right = CaveCarver.planFor(seed, placement, chunkX + 1, chunkZ);
                    // The block column on the shared border, sampled from each chunk's own plan.
                    int x = ((chunkX + 1) << 4) - 1;
                    int xNext = (chunkX + 1) << 4;
                    for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z += 5) {
                        int topA = field.topY(x, z, MIN_Y, MAX_Y - 1);
                        int topB = field.topY(xNext, z, MIN_Y, MAX_Y - 1);
                        for (int y = 8; y <= BAND_CEILING; y += 3) {
                            assertEquals(left.carvedAt(x, y, z, topA), right.carvedAt(x, y, z, topA),
                                    "border column disagrees at " + x + "," + y + "," + z);
                            assertEquals(left.carvedAt(xNext, y, z, topB), right.carvedAt(xNext, y, z, topB),
                                    "border column disagrees at " + xNext + "," + y + "," + z);
                            agreed++;
                        }
                    }
                }
            }
        }
        assertTrue(agreed > 5000, "sample too small to mean anything: " + agreed);
    }

    /**
     * No cavern may come within {@link #SURFACE_ROOF} blocks of its column's top, or it opens a pit
     * to the sky. EndCaveCarver.java:57 is the whole reason that constant exists.
     */
    @Test
    void neverBreachesTheSurface() {
        int checked = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            // Far from the origin: the 1024-block spawn void holds only the pinned island, whose top
            // (~y74) sits above the band ceiling, so its roof band never intersects the carvable
            // range and sampling there counts nothing at all.
            for (int chunkX = 400; chunkX <= 460; chunkX += 3) {
                for (int chunkZ = 400; chunkZ <= 460; chunkZ += 3) {
                    CaveCarver.Plan plan = CaveCarver.planFor(seed, placement, chunkX, chunkZ);
                    if (plan.isEmpty()) continue;
                    for (int lx = 0; lx < 16; lx += 3) {
                        for (int lz = 0; lz < 16; lz += 3) {
                            int x = (chunkX << 4) + lx;
                            int z = (chunkZ << 4) + lz;
                            int top = field.topY(x, z, MIN_Y, MAX_Y - 1);
                            if (top == Integer.MIN_VALUE) continue;
                            // Only columns whose roof band reaches into the carvable range prove
                            // anything; the rest are vacuous and must not be counted.
                            int bandLow = top - SURFACE_ROOF;
                            if (bandLow > BAND_CEILING) continue;
                            for (int y = bandLow; y <= Math.min(top, BAND_CEILING); y++) {
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

    /** Nothing above the cave-biome band, ever -- that is what keeps floating islands whole. */
    @Test
    void neverCarvesAboveTheCaveBand() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = -8; chunkX <= 8; chunkX++) {
                for (int chunkZ = -8; chunkZ <= 8; chunkZ++) {
                    CaveCarver.Plan plan = CaveCarver.planFor(seed, placement, chunkX, chunkZ);
                    if (plan.isEmpty()) continue;
                    for (int lx = 0; lx < 16; lx += 5) {
                        for (int lz = 0; lz < 16; lz += 5) {
                            int x = (chunkX << 4) + lx;
                            int z = (chunkZ << 4) + lz;
                            int top = field.topY(x, z, MIN_Y, MAX_Y - 1);
                            for (int y = BAND_CEILING + 1; y < 120; y += 7) {
                                assertFalse(plan.carvedAt(x, y, z, top),
                                        "carved above the band at y" + y);
                            }
                        }
                    }
                }
            }
        }
    }

    /** A void column has no terrain to hollow, so it is never carved whatever the geometry says. */
    @Test
    void voidColumnsAreNeverCarved() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            CaveCarver.Plan plan = CaveCarver.planFor(seed, placement, 0, 0);
            for (int y = 8; y <= BAND_CEILING; y++) {
                assertFalse(plan.carvedAt(3, y, 7, Integer.MIN_VALUE), "carved a void column at y" + y);
            }
        }
    }

    /**
     * Caves must actually exist and be lens-shaped, not spheres. With squash 1.6 a cavern is 1.6x
     * wider than it is tall, so the carved extent measured horizontally through the centre must
     * exceed the vertical one by a clear margin. An implementation that dropped the squash -- the
     * easiest thing to lose in the port -- measures roughly equal and fails.
     */
    @Test
    void cavernsExistAndAreFlattened() {
        int cavernsSeen = 0;
        double ratioSum = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            // Far from the origin, like neverBreachesTheSurface: caverns exist only under the six
            // cave biomes, and the whole 1024-block spawn void is vanilla ring, so a sample around
            // the origin now finds exactly nothing -- see cavesOnlyExistUnderCaveBiomes.
            for (int chunkX = 200; chunkX <= 320 && cavernsSeen < 40; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 320 && cavernsSeen < 40; chunkZ++) {
                    CaveCarver.Plan plan = CaveCarver.planFor(seed, placement, chunkX, chunkZ);
                    if (plan.isEmpty()) continue;
                    int cx = (chunkX << 4) + 8;
                    int cz = (chunkZ << 4) + 8;
                    // Ignore the roof and terrain guards here: this measures pure cavern geometry.
                    int top = Integer.MAX_VALUE / 4;
                    int bestY = -1, bestWidth = 0;
                    for (int y = 8; y <= BAND_CEILING; y++) {
                        int width = 0;
                        for (int dx = -40; dx <= 40; dx++) {
                            if (plan.carvedAt(cx + dx, y, cz, top)) width++;
                        }
                        if (width > bestWidth) {
                            bestWidth = width;
                            bestY = y;
                        }
                    }
                    if (bestWidth < 8) continue;
                    int height = 0;
                    for (int y = 0; y <= BAND_CEILING; y++) {
                        if (plan.carvedAt(cx, y, cz, top)) height++;
                    }
                    if (height == 0) continue;
                    cavernsSeen++;
                    ratioSum += (double) bestWidth / height;
                    assertTrue(bestY > 0, "widest slice not found");
                }
            }
        }
        assertTrue(cavernsSeen >= 10, "found almost no caverns to measure: " + cavernsSeen);
        double meanRatio = ratioSum / cavernsSeen;
        assertTrue(meanRatio > 1.25,
                "caverns are not flattened; mean width/height " + meanRatio + " suggests the "
                        + "vertical squash of 1.6 was lost");
    }

    /**
     * The carver is attached to the six is_end_cave biomes and to nothing else: 6 of the mod's 27
     * biome jsons carry a non-empty "carvers" array and all six are caves. A carver with no biome
     * test passes every other assertion here and quietly digs caves under all 27, which is what an
     * earlier revision of this class did.
     */
    @Test
    void cavesOnlyExistUnderCaveBiomes() {
        int caveChunks = 0;
        int nonCaveChunks = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = 200; chunkX <= 260; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 260; chunkZ++) {
                    boolean anyStartIsCave = false;
                    for (int sx = chunkX - 3; sx <= chunkX + 3 && !anyStartIsCave; sx++) {
                        for (int sz = chunkZ - 3; sz <= chunkZ + 3 && !anyStartIsCave; sz++) {
                            anyStartIsCave = placement.allowsCarvers(sx << 4, sz << 4);
                        }
                    }
                    boolean planned = !CaveCarver.planFor(seed, placement, chunkX, chunkZ).isEmpty();
                    if (anyStartIsCave) {
                        caveChunks++;
                    } else {
                        nonCaveChunks++;
                        assertFalse(planned, "carved a chunk no cave-biome start chunk can reach, at "
                                + chunkX + "," + chunkZ);
                    }
                }
            }
        }
        assertTrue(nonCaveChunks > 100, "sample saw too few non-cave chunks: " + nonCaveChunks);
        assertTrue(caveChunks > 20, "sample saw too few cave chunks to be meaningful: " + caveChunks);
    }

    /** The spawn void is vanilla ring biomes, which list no carvers -- so it must be solid. */
    @Test
    void theSpawnVoidIsNeverCarved() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = -12; chunkX <= 12; chunkX++) {
                for (int chunkZ = -12; chunkZ <= 12; chunkZ++) {
                    assertTrue(CaveCarver.planFor(seed, placement, chunkX, chunkZ).isEmpty(),
                            "carved inside the spawn void at chunk " + chunkX + "," + chunkZ);
                }
            }
        }
    }

    /** Same seed, same caves -- two plans for one chunk must agree block for block. */
    @Test
    void planIsDeterministic() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = -3; chunkX <= 3; chunkX++) {
                for (int chunkZ = -3; chunkZ <= 3; chunkZ++) {
                    CaveCarver.Plan a = CaveCarver.planFor(seed, placement, chunkX, chunkZ);
                    CaveCarver.Plan b = CaveCarver.planFor(seed, placement, chunkX, chunkZ);
                    assertEquals(a.isEmpty(), b.isEmpty());
                    int top = 70;
                    for (int lx = 0; lx < 16; lx += 4) {
                        for (int lz = 0; lz < 16; lz += 4) {
                            int x = (chunkX << 4) + lx;
                            int z = (chunkZ << 4) + lz;
                            for (int y = 8; y <= 40; y += 4) {
                                assertEquals(a.carvedAt(x, y, z, top), b.carvedAt(x, y, z, top));
                            }
                        }
                    }
                }
            }
        }
    }
}
