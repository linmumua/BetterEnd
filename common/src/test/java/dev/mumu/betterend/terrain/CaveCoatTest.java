package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.biome.CaveCoat;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties a broken cave coat would violate. The dangerous failures here are quiet ones: a
 * jade formula that collapses to a single stone still paints a cave, just a flat one; a shell that
 * ignores the open-air guard still paints caves, but also paints jadestone onto the outside of an
 * island where every player can see it; and an index arithmetic slip paints the right blocks in the
 * wrong places.
 */
class CaveCoatTest {
    private static final long[] SEEDS = {12345L, 42L, -7L};
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

    // --- the materials table ------------------------------------------------------------------

    /** Three of the six cave biomes paint nothing; that is the mod's intent, not a porting gap. */
    @Test
    void exactlyThreeCaveBiomesPaintAnything() {
        Set<BiomeSurface> painting = new HashSet<>();
        for (BiomeSurface biome : BiomeSurface.values()) {
            if (CaveCoat.paintsAnything(biome)) {
                painting.add(biome);
            }
        }
        assertEquals(Set.of(BiomeSurface.JADE_CAVE, BiomeSurface.LUSH_AURORA_CAVE,
                BiomeSurface.LUSH_SMARAGDANT_CAVE), painting);
    }

    /** The whole table, transcribed from the mod's six EndCaveBiome subclasses. */
    @Test
    void theMaterialsTableIsTheModsTable() {
        assertNull(CaveCoat.floor(BiomeSurface.JADE_CAVE), "jade keeps its jadestone shell as a floor");
        assertNull(CaveCoat.ceiling(BiomeSurface.JADE_CAVE));
        assertEquals("betterend:cave_moss", CaveCoat.floor(BiomeSurface.LUSH_AURORA_CAVE));
        assertEquals("betterend:cave_moss", CaveCoat.ceiling(BiomeSurface.LUSH_AURORA_CAVE));
        assertEquals("betterend:cave_moss", CaveCoat.floor(BiomeSurface.LUSH_SMARAGDANT_CAVE));
        assertNull(CaveCoat.ceiling(BiomeSurface.LUSH_SMARAGDANT_CAVE),
                "only the aurora lush cave overrides its ceiling");
        for (BiomeSurface empty : List.of(BiomeSurface.EMPTY_AURORA_CAVE, BiomeSurface.EMPTY_END_CAVE,
                BiomeSurface.EMPTY_SMARAGDANT_CAVE)) {
            assertNull(CaveCoat.floor(empty));
            assertNull(CaveCoat.ceiling(empty));
            assertNull(CaveCoat.wall(empty, 10, 20, 30));
        }
    }

    /** Only the jade cave has a wall material; every other biome must leave the rock alone. */
    @Test
    void onlyJadeHasAWall() {
        for (BiomeSurface biome : BiomeSurface.values()) {
            String wall = CaveCoat.wall(biome, 100, 20, 200);
            if (biome == BiomeSurface.JADE_CAVE) {
                assertNotNull(wall);
            } else {
                assertNull(wall, biome + " painted a wall");
            }
        }
    }

    /**
     * The jade banding. A wrong formula -- a lost noise term, a missing abs, a modulus over the
     * wrong value -- most often collapses the three stones to one or two, which still produces a
     * perfectly plausible-looking cave. So assert all three appear, and roughly evenly.
     */
    @Test
    void jadeUsesAllThreeStonesInRoughBalance() {
        Map<String, Integer> counts = new HashMap<>();
        for (int x = 0; x < 60; x++) {
            for (int z = 0; z < 60; z++) {
                for (int y = 0; y < 40; y += 2) {
                    String id = CaveCoat.wall(BiomeSurface.JADE_CAVE, x * 3, y, z * 3);
                    counts.merge(id, 1, Integer::sum);
                }
            }
        }
        assertEquals(Set.of("betterend:virid_jadestone", "betterend:azure_jadestone",
                "betterend:sandy_jadestone"), counts.keySet());
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double share = (double) entry.getValue() / total;
            assertTrue(share > 0.15 && share < 0.55,
                    entry.getKey() + " takes " + share + " of the wall; the bands are lopsided");
        }
    }

    /**
     * The bands must move with y -- that is what makes them bands -- and must also wander with x/z,
     * which is what the fine 0.2-frequency jitter is for. Drop the jitter and a column's banding
     * becomes identical to its neighbour's, giving flat horizontal slabs across the whole cave.
     */
    @Test
    void jadeBandsVaryWithHeightAndWander() {
        Set<String> downOneColumn = new HashSet<>();
        for (int y = 0; y < 48; y++) {
            downOneColumn.add(CaveCoat.wall(BiomeSurface.JADE_CAVE, 512, y, 512));
        }
        assertEquals(3, downOneColumn.size(), "a single column shows all three stones");

        int differing = 0;
        for (int x = 0; x < 200; x++) {
            for (int y = 4; y < 44; y += 7) {
                String here = CaveCoat.wall(BiomeSurface.JADE_CAVE, x, y, 0);
                String next = CaveCoat.wall(BiomeSurface.JADE_CAVE, x, y, 1);
                if (!here.equals(next)) {
                    differing++;
                }
            }
        }
        assertTrue(differing > 20,
                "adjacent columns band identically (" + differing + " differ); the jitter was lost");
    }

    /** Everything the coat can name must be resolvable at startup, or a face silently stays bare. */
    @Test
    void thePaletteCoversEveryIdTheCoatCanReturn() {
        Set<String> named = new HashSet<>();
        for (BiomeSurface biome : BiomeSurface.values()) {
            for (int y = 0; y < 50; y++) {
                String wall = CaveCoat.wall(biome, 77, y, 31);
                if (wall != null) {
                    named.add(wall);
                }
            }
            if (CaveCoat.floor(biome) != null) {
                named.add(CaveCoat.floor(biome));
            }
            if (CaveCoat.ceiling(biome) != null) {
                named.add(CaveCoat.ceiling(biome));
            }
        }
        assertTrue(CaveCoat.PALETTE.containsAll(named),
                "the coat can return ids the palette does not resolve: " + named);
    }

    // --- the sweep ----------------------------------------------------------------------------

    /** A chunk with no carved cave has nothing to coat, and must not pay for the sweep either. */
    @Test
    void chunksWithoutCavesPlanNothing() {
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = -6; chunkX <= 6; chunkX++) {
                for (int chunkZ = -6; chunkZ <= 6; chunkZ++) {
                    assertTrue(plan(seed, placement, chunkX, chunkZ).isEmpty(),
                            "planned a coat inside the spawn void at " + chunkX + "," + chunkZ);
                }
            }
        }
    }

    /**
     * The coat must never touch a block a player can see from outside a cave. This is the assertion
     * that fails if the open-air guard is dropped from either the frontier scan or the shell walk --
     * and dropping it from the shell alone still leaves the frontier clean, so both are covered by
     * checking every painted block.
     */
    @Test
    void neverPaintsABlockExposedToSkyOrVoid() {
        int painted = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 200; chunkX <= 320; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 240; chunkZ++) {
                    for (CaveCoatPlanner.Paint paint : plan(seed, placement, chunkX, chunkZ)) {
                        painted++;
                        assertTrue(field.isSolid(paint.x(), paint.y(), paint.z()),
                                "painted a block that is not rock at all");
                        assertFalse(exposed(field, paint.x(), paint.y(), paint.z()),
                                "painted " + paint.id() + " on a block exposed to sky or void at "
                                        + paint.x() + "," + paint.y() + "," + paint.z());
                    }
                    if (painted > 3000) {
                        assertTrue(painted > 3000);
                        return;
                    }
                }
            }
        }
        assertTrue(painted > 200, "the sweep painted almost nothing: " + painted);
    }

    /** Everything painted belongs to the chunk that planned it -- the margin is read-only. */
    @Test
    void paintsOnlyInsideItsOwnChunk() {
        int painted = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = 200; chunkX <= 260 && painted < 2000; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 230 && painted < 2000; chunkZ++) {
                    for (CaveCoatPlanner.Paint paint : plan(seed, placement, chunkX, chunkZ)) {
                        painted++;
                        assertEquals(chunkX, paint.x() >> 4, "painted outside its chunk in x");
                        assertEquals(chunkZ, paint.z() >> 4, "painted outside its chunk in z");
                        assertTrue(paint.y() >= 0 && paint.y() <= CaveCoatPlanner.BAND_TOP,
                                "painted outside the band at y" + paint.y());
                    }
                }
            }
        }
        assertTrue(painted > 200, "the sweep painted almost nothing: " + painted);
    }

    /**
     * The coat must actually cover caves. A sweep that silently classified every cave block as OPEN
     * would pass every safety assertion above by painting nothing at all.
     */
    @Test
    void theSweepActuallyCoatsCaves() {
        int painted = 0;
        Set<String> ids = new HashSet<>();
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            for (int chunkX = 200; chunkX <= 320 && painted < 500; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 320 && painted < 500; chunkZ++) {
                    for (CaveCoatPlanner.Paint paint : plan(seed, placement, chunkX, chunkZ)) {
                        painted++;
                        ids.add(paint.id());
                    }
                }
            }
        }
        assertTrue(painted > 100, "the sweep coated almost nothing: " + painted);
        assertTrue(CaveCoat.PALETTE.containsAll(ids), "painted an id outside the palette: " + ids);
    }

    /**
     * The sweep now answers both halves of a cave chunk -- what was carved and what gets coated --
     * from one classification, because evaluating {@code carvedAt} is the expensive part and doing
     * it twice was most of the cost. The carve mask it hands back must therefore be exactly what the
     * carvers themselves say, or the terrain quietly stops matching the carve plans.
     */
    @Test
    void theCarveMaskMatchesTheCarversExactly() {
        int compared = 0;
        int carved = 0;
        for (long seed : SEEDS) {
            BiomePlacement placement = new BiomePlacement(seed);
            IslandField field = placement.field();
            for (int chunkX = 200; chunkX <= 230; chunkX++) {
                for (int chunkZ = 200; chunkZ <= 215; chunkZ++) {
                    CaveCarver.Plan caves = CaveCarver.planFor(seed, placement, chunkX, chunkZ);
                    TunnelCarver.Plan tunnels = TunnelCarver.planFor(seed, placement, chunkX, chunkZ);
                    CaveCoatPlanner.Sweep sweep = CaveCoatPlanner.sweep(chunkX, chunkZ, placement,
                            field, caves, tunnels, (x, y, z) -> true);
                    if (sweep == null) {
                        continue;
                    }
                    for (int dx = 0; dx < 16; dx += 3) {
                        for (int dz = 0; dz < 16; dz += 3) {
                            int x = (chunkX << 4) + dx;
                            int z = (chunkZ << 4) + dz;
                            int top = field.topY(x, z);
                            for (int y = 0; y <= CaveCoatPlanner.BAND_TOP; y++) {
                                boolean expected = field.isSolid(x, y, z)
                                        && (caves.carvedAt(x, y, z, top) || tunnels.carvedAt(x, y, z, top));
                                assertEquals(expected, sweep.carved(dx, y, dz),
                                        "carve mask disagrees at " + x + "," + y + "," + z);
                                compared++;
                                if (expected) {
                                    carved++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(compared > 20000, "sample too small: " + compared);
        assertTrue(carved > 100, "the sample found almost no carved blocks: " + carved);
    }

    private static List<CaveCoatPlanner.Paint> plan(long seed, BiomePlacement placement,
                                                    int chunkX, int chunkZ) {
        CaveCoatPlanner.Sweep sweep = CaveCoatPlanner.sweep(chunkX, chunkZ, placement, placement.field(),
                CaveCarver.planFor(seed, placement, chunkX, chunkZ),
                TunnelCarver.planFor(seed, placement, chunkX, chunkZ),
                // Every shipped cave biome fills with plain end stone, so the generator's real
                // predicate is constant over the columns this sweep can reach.
                (x, y, z) -> true);
        return sweep == null ? List.of() : sweep.paints();
    }

    /** Rock with a non-solid neighbour: the outside of the world, however the coat got there. */
    private static boolean exposed(IslandField field, int x, int y, int z) {
        return !field.isSolid(x + 1, y, z) || !field.isSolid(x - 1, y, z)
                || !field.isSolid(x, y + 1, z) || !field.isSolid(x, y - 1, z)
                || !field.isSolid(x, y, z + 1) || !field.isSolid(x, y, z - 1);
    }
}
