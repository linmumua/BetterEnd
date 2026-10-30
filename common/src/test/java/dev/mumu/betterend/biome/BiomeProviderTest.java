package dev.mumu.betterend.biome;

import dev.mumu.betterend.terrain.IslandField;
import dev.mumu.betterend.vanilla.VanillaEndCore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link BiomePlacement}, not Bukkit. {@link BetterEndBiomeProvider} is a thin adapter over
 * this class precisely so the algorithm can be driven without a server: the common module's test
 * classpath deliberately carries no paper-api.
 */
class BiomeProviderTest {
    private static final long SEED = 0x5EEDL;
    /** Far enough out that every sample is outside the vanilla core disc. */
    private static final int ORIGIN = 8192;
    /**
     * 128 blocks: between three and four hex cells (row pitch 32, column pitch 36.95), and about
     * half a flood-fill blob, so consecutive samples are usually - but not always - independent.
     */
    private static final int STEP = 128;
    private static final int SAMPLES = 32;
    /** Well above the jittered cave ceiling of 48 +- 8. */
    private static final int SURFACE_Y = 100;
    private static final int CAVE_Y = 8;

    private final BiomePlacement placement = new BiomePlacement(SEED);

    private static List<int[]> grid() {
        List<int[]> out = new ArrayList<>(SAMPLES * SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            for (int j = 0; j < SAMPLES; j++) {
                out.add(new int[]{ORIGIN + i * STEP, ORIGIN + j * STEP});
            }
        }
        return out;
    }

    private List<int[]> landColumns() {
        return grid().stream().filter(p -> placement.isLand(p[0] >> 2, p[1] >> 2)).toList();
    }

    private static Map<BiomeSurface, Integer> tally(List<BiomeSurface> values) {
        Map<BiomeSurface, Integer> counts = new EnumMap<>(BiomeSurface.class);
        values.forEach(v -> counts.merge(v, 1, Integer::sum));
        return counts;
    }

    @Test
    void tableCoversAllTwentySevenBiomes() {
        assertEquals(27, BiomeSurface.values().length);
        assertEquals(18, BiomeSurface.values().length
                - (int) java.util.stream.Stream.of(BiomeSurface.values())
                .filter(b -> b.category() != BiomeSurface.Category.LAND).count());
        assertSame(BiomeSurface.DUST_WASTELANDS, BiomeSurface.byId("betterend:dust_wastelands"));
        assertSame(BiomeSurface.DUST_WASTELANDS, BiomeSurface.byId("dust_wastelands"));
        assertEquals(1.5, BiomeSurface.DUST_WASTELANDS.terrainHeight());
        // 23 of the 27 biome_data files omit terrainHeight; there is no absent case, they take the
        // codec default 0.1 (WoverBiomeDataImpl.java:25, WoverBiomeBuilder.java:243). This is the
        // fact the whole radial-intensity fix turns on: 0.1f is NOT < 0.1F, so those biomes keep
        // their relief while the three that declare 0.0 lose it.
        assertEquals(0.1, BiomeSurface.PAINTED_MOUNTAINS.terrainHeight());
        assertEquals(0.0, BiomeSurface.MEGALAKE.terrainHeight());
        assertEquals(0.0, BiomeSurface.SULPHUR_SPRINGS.terrainHeight());
    }

    @Test
    void onlyShippedBiomesAreSelectable() {
        List<BiomeSurface> selectable = BiomePlacement.selectable();
        assertEquals(27, selectable.size());
        assertTrue(selectable.stream().allMatch(BiomeSurface::shipped));
        // Every category must still have somewhere to go, or the picker indexes an empty pool.
        for (BiomeSurface.Category category : BiomeSurface.Category.values()) {
            assertTrue(selectable.stream().anyMatch(b -> b.category() == category),
                    "no shipped biome for " + category);
        }
    }

    /**
     * T2, and the reason the design blends nothing: the one remaining hard boundary
     * ({@code VanillaEndCore.CORE_RADIUS}) has no ground on it, so there is nothing to blend. This
     * replaces {@code centralRingIsTheVanillaThousandBlockDisc}, which pinned the 1024-block ring
     * that WAS the visible seam -- that ring cut straight across real islands, and deleting it is
     * the fix.
     * <p>
     * Stated falsifiably rather than as a claim: no solid block in any column within 16 blocks of
     * r=384, on three seeds and 360 azimuths, plus the two converse anchors (the pinned island
     * exists, and the whole spike ring reads as land). Goes red on a CORE_RADIUS moved out into the
     * island spill band (~874) or down onto the spawn island (measured reach 94 over 40 seeds), on
     * deletion of the pinned island (IslandField:461) or of the inner-void cull (IslandField:449),
     * and on a default medium-layer scale that grows the spawn island past the boundary.
     * <p>
     * Default config only, deliberately: the mod's central-island flag owns both the pinned island
     * and the cull and is not configurable here (always on), so there is no setting that would put
     * ordinary terrain at every radius and leave no boundary over void. The property is a property
     * of the terrain, not of this radius -- see VanillaEndCore's knob note.
     */
    @Test
    void theCoreBoundaryLiesInVoidAndTheSpawnIslandIsInsideIt() {
        int r = VanillaEndCore.CORE_RADIUS;
        for (long seed : new long[]{SEED, 1L, 777L}) {
            IslandField field = new IslandField(seed);
            BiomePlacement place = new BiomePlacement(seed, field);

            for (int i = 0; i < 360; i++) {
                double angle = 2 * Math.PI * i / 360;
                for (int radius = r - 16; radius <= r + 16; radius += 8) {
                    int x = (int) Math.round(radius * Math.cos(angle)) & ~3;
                    int z = (int) Math.round(radius * Math.sin(angle)) & ~3;
                    assertEquals(Integer.MIN_VALUE, field.topY(x, z, -64, 320),
                            "seed " + seed + ": solid ground at " + x + "," + z + ", within 16"
                                    + " blocks of the vanilla core boundary - the one hard biome"
                                    + " edge left would be visible there");
                }
                // The spike ring itself has to be land, or the pillars stand on nothing. The core
                // does not depend on this (it is unconditional), but a world where it fails has a
                // broken spawn island and a broken dragon fight with it.
                int sx = (int) Math.round(VanillaEndCore.SPIKE_RING_RADIUS * Math.cos(angle));
                int sz = (int) Math.round(VanillaEndCore.SPIKE_RING_RADIUS * Math.sin(angle));
                assertTrue(place.isLand(sx >> 2, sz >> 2),
                        "seed " + seed + ": the spike ring column " + sx + "," + sz + " is void");
            }
            assertNotEquals(Integer.MIN_VALUE, field.topY(0, 0, -64, 320),
                    "seed " + seed + ": the pinned central island is gone");
        }
    }

    @Test
    void isDeterministicForASeedAndSensitiveToIt() {
        BiomePlacement twin = new BiomePlacement(SEED);
        BiomePlacement other = new BiomePlacement(SEED + 1);
        boolean differed = false;
        for (int[] p : grid()) {
            BiomeSurface mine = placement.at(p[0], SURFACE_Y, p[1]);
            assertSame(mine, twin.at(p[0], SURFACE_Y, p[1]),
                    "two placements built from the same seed disagreed");
            differed |= other.at(p[0], SURFACE_Y, p[1]) != mine;
        }
        assertTrue(differed, "changing the seed changed nothing - the world seed is being ignored");

        // T4. Generation order is not chunk order: Paper async workers ask in whatever sequence
        // they finish, and Folia asks per region. The land memo is the only mutable state on this
        // path, so this is what a capacity clear that can return a stale or partial value fails,
        // along with any lazy init added to the island field plane cache.
        List<int[]> shuffled = new ArrayList<>(grid());
        Collections.shuffle(shuffled, new java.util.Random(9));
        BiomePlacement sortedOrder = new BiomePlacement(SEED);
        BiomePlacement shuffledOrder = new BiomePlacement(SEED);
        for (int[] p : grid()) {
            sortedOrder.at(p[0], SURFACE_Y, p[1]);
        }
        for (int[] p : shuffled) {
            assertSame(sortedOrder.at(p[0], SURFACE_Y, p[1]), shuffledOrder.at(p[0], SURFACE_Y, p[1]),
                    "the answer depended on which column was asked first");
        }
        // And the same on ONE shared instance across threads, which is how it is really used.
        BiomePlacement shared = new BiomePlacement(SEED);
        assertEquals(shuffled.stream().map(p -> placement.at(p[0], SURFACE_Y, p[1])).toList(),
                shuffled.parallelStream().map(p -> shared.at(p[0], SURFACE_Y, p[1])).toList(),
                "concurrent callers on one shared placement disagreed");
    }

    /**
     * T5, contract 4: getBiomes must declare everything getBiome can return.
     * {@link BetterEndBiomeProvider} builds its resolved map from {@link BiomePlacement#selectable}
     * and its declared list from that map values, so a member added to a Pool but left out of
     * {@code selectable()} is the only way the declaration can go stale -- and that is what this
     * asserts, one level below the provider, because the common test classpath deliberately carries
     * no paper-api.
     * <p>
     * Not covered here: {@code Biome.THE_END}, which the provider adds unconditionally for the
     * vanilla core, and {@code Biome.END_BARRENS}, its no-datapack fallback. Both are literals in
     * the provider constructor; faking a Bukkit test for them would assert nothing.
     */
    @Test
    void everyResultIsADeclaredBiome() {
        List<BiomeSurface> selectable = BiomePlacement.selectable();
        for (long seed : new long[]{SEED, 1L, 777L}) {
            BiomePlacement place = new BiomePlacement(seed);
            for (int[] p : grid()) {
                for (int y : new int[]{CAVE_Y, SURFACE_Y}) {
                    BiomeSurface biome = place.at(p[0], y, p[1]);
                    assertTrue(selectable.contains(biome),
                            biome + " is not in the set the provider declares to Bukkit");
                }
            }
        }
    }

    @Test
    void voidColumnsGetSmallIslandBiomesAndLandColumnsDoNot() {
        List<int[]> land = landColumns();
        assertTrue(land.size() > 40, "the island field produced almost no land: " + land.size());
        assertTrue(land.size() < grid().size() - 40, "the island field produced almost no void");

        for (int[] p : grid()) {
            BiomeSurface biome = placement.at(p[0], SURFACE_Y, p[1]);
            boolean isLand = placement.isLand(p[0] >> 2, p[1] >> 2);
            assertEquals(isLand, biome.category() != BiomeSurface.Category.SMALL_ISLAND,
                    "land/void and the biome category disagreed at " + p[0] + "," + p[1]);
        }
    }

    @Test
    void noLandBiomeSwallowsTheMap() {
        List<BiomeSurface> picks = landColumns().stream()
                .map(p -> placement.at(p[0], SURFACE_Y, p[1]))
                .toList();
        Map<BiomeSurface, Integer> counts = tally(picks);
        List<BiomeSurface> pool = BiomePlacement.selectable().stream()
                .filter(b -> b.category() == BiomeSurface.Category.LAND)
                .toList();

        for (BiomeSurface biome : pool) {
            assertTrue(counts.getOrDefault(biome, 0) > 0,
                    biome + " never appeared in " + picks.size() + " land columns");
        }
        int most = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        // Five members with weights 1,1,1,1,0.5 - the fair share is 22%, so anything past 60% means
        // the picker has collapsed onto one cell value.
        assertTrue(most < picks.size() * 0.6,
                "one land biome took " + most + " of " + picks.size() + " columns: " + counts);

        // genChance has to actually reach the picker. neon_oasis is the only land biome that sets
        // it (0.5 against 1.0 for the other four), so it must land clearly below their average -
        // a picker that ignored the weights entirely would sit right on it.
        double fullWeight = pool.stream()
                .filter(b -> b != BiomeSurface.NEON_OASIS)
                .mapToInt(b -> counts.getOrDefault(b, 0))
                .average()
                .orElseThrow();
        assertTrue(counts.getOrDefault(BiomeSurface.NEON_OASIS, 0) < fullWeight * 0.8,
                "neon_oasis sets genChance 0.5 but drew " + counts.get(BiomeSurface.NEON_OASIS)
                        + " against a full-weight average of " + fullWeight + ": " + counts);
    }

    @Test
    void voidColumnsStayVoidBiomesAtEveryDepth() {
        // The cave slab is substituted into land picks only; center, small-island and barrens
        // columns never get caves at any y (EndCaveBiomeDecider.java:36-37,186-191).
        List<int[]> voids = grid().stream()
                .filter(p -> !placement.isLand(p[0] >> 2, p[1] >> 2))
                .toList();
        assertTrue(voids.size() > 40, "no void columns to test: " + voids.size());
        for (int[] p : voids) {
            for (int y = -32; y <= 96; y += 8) {
                assertEquals(BiomeSurface.Category.SMALL_ISLAND, placement.at(p[0], y, p[1]).category(),
                        "a void column stopped being a small-island biome at y=" + y);
            }
        }
    }

    @Test
    void theVoidIsNotOneBiome() {
        // Shipping only ice_starfield would hand a 0.01-weight biome every void column in the
        // world; flower_islets (0.4) has to be the one that dominates.
        Map<BiomeSurface, Integer> counts = tally(grid().stream()
                .filter(p -> !placement.isLand(p[0] >> 2, p[1] >> 2))
                .map(p -> placement.at(p[0], SURFACE_Y, p[1]))
                .toList());
        assertTrue(counts.size() > 1, "every void column got the same biome: " + counts);
        assertTrue(counts.getOrDefault(BiomeSurface.FLOWER_ISLETS, 0)
                        > counts.getOrDefault(BiomeSurface.ICE_STARFIELD, 0),
                "ice_starfield (0.01) outdrew flower_islets (0.4): " + counts);
    }

    @Test
    void cavesReplaceLandOnlyInsideTheBandAndOnlyWhereTheBiomeAllowsThem() {
        List<int[]> land = landColumns();
        int caves = 0;
        for (int[] p : land) {
            BiomeSurface surface = placement.at(p[0], SURFACE_Y, p[1]);
            assertNotEquals(BiomeSurface.Category.CAVE, surface.category(),
                    "a cave biome appeared at y=" + SURFACE_Y + ", far above the 48 +- 8 ceiling");

            BiomeSurface deep = placement.at(p[0], CAVE_Y, p[1]);
            if (deep.category() == BiomeSurface.Category.CAVE) {
                caves++;
                // The cave slab is substituted into the land pick, so the land biome underneath it
                // must be one that allows caves (EndCaveBiomeDecider.java:204-209).
                assertTrue(surface.hasCaves(),
                        "caves were carved under " + surface + ", which sets has_caves: false");
            } else {
                assertSame(surface, deep, "the land pick must not depend on y");
            }
        }
        // The region noise clears its threshold over roughly 40-50% of the world, and only under
        // cave-bearing biomes: neither 0 nor "everywhere" is a working implementation.
        assertTrue(caves > land.size() * 0.05, "no land column had caves at y=" + CAVE_Y);
        assertTrue(caves < land.size() * 0.95, "every land column had caves at y=" + CAVE_Y);
    }

    @Test
    void sulphurSpringsIsNeverUndercutByACaveBiome() {
        assertFalse(BiomeSurface.SULPHUR_SPRINGS.hasCaves());
        List<int[]> land = landColumns();
        for (int[] p : land) {
            if (placement.at(p[0], SURFACE_Y, p[1]) == BiomeSurface.SULPHUR_SPRINGS) {
                for (int y = -32; y <= 64; y += 8) {
                    assertSame(BiomeSurface.SULPHUR_SPRINGS, placement.at(p[0], y, p[1]));
                }
            }
        }
    }

    /**
     * Everything {@link BiomePlacement#at} computes except the cave-ceiling comparison depends
     * only on the block COLUMN, so one column must be resolved once however many Y levels ask.
     * <p>
     * This is not a micro-optimisation. Vanilla's structure probe walks the dimension quartY by
     * quartY, so one candidate asks the provider 65 times with the same (x, z); `end_city` is
     * cancelled by StructureWorldGuard, so `/locate structure minecraft:end_city` never finds a
     * city and therefore scans all 40,401 candidates every time. Before the column memo each of
     * those 65 asks re-ran the hex-map lookup, which is ten 2-D simplex evaluations.
     * <p>
     * The assertion is the memo's own miss count, i.e. a work count rather than a stopwatch, and
     * it is exactly falsifiable: without the memo it is 96 per column, with it exactly 1.
     */
    @Test
    void oneColumnIsResolvedOnceForEveryYLevelThatAsks() {
        BiomePlacement place = new BiomePlacement(SEED);
        List<int[]> points = grid();
        for (int[] p : points) {
            // The End's whole build height, at the quantized step the structure probe uses.
            for (int y = -64; y < 320; y += 4) {
                place.at(p[0], y, p[1]);
            }
        }
        assertEquals(points.size(), place.columnComputations(),
                "at() re-resolved the column per Y level: " + place.columnComputations()
                        + " resolutions for " + points.size() + " columns x 96 Y levels");
    }

    /**
     * The column memo is a FIXED array, which is what bounds it - and means a later column
     * overwrites an earlier one sharing its slot. Replacement has to be invisible: a slot that
     * answered for the wrong key would make a biome depend on visit order, which is the one thing
     * every memo in this package is allowed to do only because it cannot.
     * <p>
     * The sweep is deliberately larger than the memo and the second pass runs in the opposite
     * order, so most entries have been overwritten by the time they are asked again.
     * <p>
     * Two assertions, and the COUNT is the one that catches a wrong-key hit. Comparing values
     * alone does not: a slot that answers for whatever it happens to hold is wrong
     * DETERMINISTICALLY, so a warm pass, a reversed warm pass and a cold pass can all reproduce
     * the same wrong answers and agree with each other. Counting cannot be fooled that way - a
     * hit that should have been a miss means some column was never computed at all, so the
     * resolution count drops below the number of columns asked. Measured: the count is exactly
     * the sweep size here, because every column is new.
     */
    @Test
    void replacingACachedColumnChangesNoAnswer() {
        int capacity = 1 << BiomePlacement.COLUMN_CACHE_BITS;
        int side = (int) Math.ceil(Math.sqrt(capacity)) + 8;
        List<int[]> points = new ArrayList<>();
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                points.add(new int[]{ORIGIN + i, ORIGIN + j});
            }
        }
        assertTrue(points.size() > capacity, "the sweep is " + points.size()
                + " columns, which fits inside the memo's " + capacity + ": nothing was replaced");

        BiomePlacement warm = new BiomePlacement(SEED);
        List<BiomeSurface> first = new ArrayList<>();
        for (int[] p : points) {
            first.add(warm.at(p[0], CAVE_Y, p[1]));
        }
        assertEquals(points.size(), warm.columnComputations(),
                "a column was served from another column's slot: " + warm.columnComputations()
                        + " resolutions for " + points.size() + " distinct columns");

        List<BiomeSurface> again = new ArrayList<>();
        for (int i = points.size() - 1; i >= 0; i--) {
            again.add(0, warm.at(points.get(i)[0], CAVE_Y, points.get(i)[1]));
        }
        assertEquals(first, again, "the column memo answered differently after its slots were reused");

        BiomePlacement cold = new BiomePlacement(SEED);
        List<BiomeSurface> fresh = new ArrayList<>();
        for (int[] p : points) {
            fresh.add(cold.at(p[0], CAVE_Y, p[1]));
        }
        assertEquals(first, fresh, "a warm placement disagreed with a cold one");
    }

    /**
     * The memo hoists everything that does not depend on y, so this pins the one thing that does:
     * {@link BiomePlacement#inCaveBand}'s {@code y > ceiling}. For a land column the answer must
     * be one contiguous cave slab at the bottom and the land pick above it - never interleaved -
     * which a memo that cached the y-dependent tail, or that hoisted the ceiling comparison out of
     * the per-y path, would break.
     */
    @Test
    void theCaveSlabIsOneContiguousBandUnderAConstantLandPick() {
        int caveSamples = 0;
        for (int[] p : landColumns()) {
            BiomeSurface land = placement.at(p[0], SURFACE_Y, p[1]);
            boolean seenLand = false;
            for (int y = -64; y <= SURFACE_Y; y += 2) {
                BiomeSurface here = placement.at(p[0], y, p[1]);
                if (here == land) {
                    seenLand = true;
                    continue;
                }
                assertSame(BiomeSurface.Category.CAVE, here.category(),
                        "a non-cave biome replaced " + land + " at y=" + y + ": " + here);
                assertFalse(seenLand, "the cave slab under " + land + " is not contiguous: the land"
                        + " pick already appeared above, then " + here + " at y=" + y);
                caveSamples++;
            }
        }
        assertTrue(caveSamples > 0, "no land column carried a cave in the sampled band, so this"
                + " proves nothing");
    }

    @Test
    void concurrentCallersSeeTheSameAnswers() {
        List<int[]> points = grid();
        List<BiomeSurface> serial = points.stream()
                .map(p -> placement.at(p[0], CAVE_Y, p[1]))
                .toList();
        BiomePlacement fresh = new BiomePlacement(SEED);
        List<BiomeSurface> parallel = IntStream.range(0, points.size())
                .parallel()
                .mapToObj(i -> fresh.at(points.get(i)[0], CAVE_Y, points.get(i)[1]))
                .toList();
        assertEquals(serial, parallel, "the land memo is not thread safe");
        assertEquals(EnumSet.copyOf(serial), EnumSet.copyOf(parallel));
    }

    // --- surfaces (Gap 1) ----------------------------------------------------------------------

    /**
     * Every surface block the live CraftEngine pack defines. All 17 are present since the block
     * conversion; the four degraded biomes (flower_islets, waterfall_ponds, neon_oasis,
     * umbra_valley) now resolve their real surface instead of falling back.
     */
    private static final Set<String> PACK = Set.of("minecraft:end_stone", "betterend:endstone_dust", "betterend:brimstone",
            "betterend:sulphuric_rock", "betterend:umbralith", "betterend:end_moss",
            "betterend:end_mycelium", "betterend:pallidium_full", "betterend:amber_moss",
            "betterend:pink_moss", "betterend:chorus_nylium", "betterend:crystal_moss",
            "betterend:sangnum", "betterend:rutiscus", "betterend:shadow_grass",
            "betterend:jungle_moss", "betterend:cave_moss");

    private static Predicate<String> defines(String... ids) {
        return Set.of(ids)::contains;
    }

    /**
     * The tripwire that makes {@link #PACK} a fact rather than a claim. {@link #PACK} is a literal,
     * so on its own every surface test below only asserts that the fallback rule is self-consistent
     * -- a pack conversion landing {@code end_moss} would silently repaint flower_islets and
     * waterfall_ponds and leave the whole suite green. This reads the live pack instead.
     * <p>
     * Deliberately a name scan over the block configs, not a CraftEngine parse: the point is to go
     * red on ANY pack change that touches one of these seven names, so the degradation list above
     * is re-read by a human. The five present ids are declared two different ways today -- literally
     * ({@code betterend:brimstone:}) and through the stone_sets {@code stone_type} template -- and a
     * bare name scan is the one rule that covers both.
     */
    @Test
    void theHardcodedPackSetStillMatchesTheShippedCraftEnginePack() throws IOException {
        Path blocks = up("plugin/src/main/resources/craftengine/betterend/configuration/blocks");
        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.list(blocks)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                text.append(Files.readString(file)).append(" ");
            }
        }
        assertTrue(text.length() > 1000, "the pack configs did not load from " + blocks);

        Set<String> declared = BiomePlacement.selectable().stream()
                .flatMap(b -> Stream.of(b.top(), b.under(), b.alt()))
                .collect(java.util.stream.Collectors.toSet());
        for (String id : declared) {
            if (id.startsWith("minecraft:")) continue;
            boolean inPack = text.indexOf(id.substring("betterend:".length())) >= 0;
            assertEquals(PACK.contains(id), inPack,
                    id + " is " + (inPack ? "now IN" : "no longer in") + " the CraftEngine pack;"
                    + " update PACK and re-check the degradation list in"
                    + " shippedBiomesNeedExactlyTheseBlocks - the world changes with it");
        }
    }

    /** The pack lives in the plugin module; walk up until the repo root is underfoot. */
    private static Path up(String relative) {
        Path at = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && at != null; i++, at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    void dustBandOnlyWhereTopIsDust() {
        // The add_surface_depth band appears in exactly two generated rules (dust_wastelands.json:23-38,
        // neon_oasis.json); painted_mountains is rendered from its biome_data, which is dust/dust too.
        Set<BiomeSurface> dusty = BiomePlacement.selectable().stream()
                .filter(b -> b.resolve(PACK::contains, ignored -> {
                }).dusty())
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(BiomeSurface.class)));
        assertEquals(EnumSet.of(BiomeSurface.DUST_WASTELANDS, BiomeSurface.NEON_OASIS,
                BiomeSurface.PAINTED_MOUNTAINS), dusty);
        // Derived from the DECLARED top, so a biome whose top falls back still keeps its own band.
        assertTrue(BiomeSurface.NEON_OASIS.resolve(defines("minecraft:end_stone"), ignored -> {
        }).dusty());
        assertFalse(BiomeSurface.UMBRA_VALLEY.resolve(PACK::contains, ignored -> {
        }).dusty());
    }

    @Test
    void shippedBiomesNeedExactlyTheseBlocks() {
        Set<String> ids = BiomePlacement.selectable().stream()
                .flatMap(b -> Stream.of(b.top(), b.under(), b.alt()))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("minecraft:end_stone", "betterend:endstone_dust", "betterend:brimstone",
            "betterend:sulphuric_rock", "betterend:umbralith", "betterend:end_moss",
            "betterend:end_mycelium", "betterend:pallidium_full", "betterend:amber_moss",
            "betterend:pink_moss", "betterend:chorus_nylium", "betterend:crystal_moss",
            "betterend:sangnum", "betterend:rutiscus", "betterend:shadow_grass",
            "betterend:jungle_moss", "betterend:cave_moss"), ids);

        // The two the pack does not ship are reachable only as these four slots. This pins the
        // degradation list: a pack conversion that lands end_moss turns this red instead of
        // silently repainting the world.
        // Nothing degrades any more: the pack defines every id the table names. Kept as an
        // assertion rather than deleted -- it is what goes red if a surface block is ever removed
        // from the pack, or if a new biome names one that was never added.
        List<String> broken = new ArrayList<>();
        BiomePlacement.selectable().forEach(b -> b.resolve(PACK::contains, broken::add));
        assertEquals(List.of(), broken.stream().sorted().toList());
    }

    @Test
    void everyShippedBiomeResolvesToAPackBlockAndNeverToAnotherBiomesSignature() {
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            BiomeSurface.Resolved r = biome.resolve(PACK::contains, ignored -> {
            });
            assertTrue(PACK.contains(r.top()), biome + " top " + r.top() + " is not in the pack");
            assertTrue(PACK.contains(r.under()), biome + " under " + r.under() + " is not in the pack");
            assertTrue(r.alt() == null || PACK.contains(r.alt()), biome + " alt " + r.alt());
            assertNotEquals(r.top(), r.alt(), biome + " speckles its top block with itself");
        }
        // The four rows that carry the whole point of the change. umbra_valley and flower_islets
        // used to degrade here; the block conversion landed pallidium_full and end_moss, so they
        // now resolve their real surface. Pinned so a pack regression is visible.
        assertEquals(new BiomeSurface.Resolved("betterend:umbralith", "betterend:umbralith",
                        "betterend:pallidium_full", false),
                BiomeSurface.UMBRA_VALLEY.resolve(PACK::contains, ignored -> {
                }));
        assertEquals(new BiomeSurface.Resolved("betterend:brimstone", "minecraft:end_stone",
                        "betterend:sulphuric_rock", false),
                BiomeSurface.SULPHUR_SPRINGS.resolve(PACK::contains, ignored -> {
                }));
        assertEquals(new BiomeSurface.Resolved("betterend:endstone_dust", "minecraft:end_stone", null, true),
                BiomeSurface.DUST_WASTELANDS.resolve(PACK::contains, ignored -> {
                }));
        // flower_islets: end_moss now exists, so its floor is moss over end stone. alt == top, so
        // resolve() drops the speckle rather than speckling a block with itself.
        assertEquals(new BiomeSurface.Resolved("betterend:end_moss", "minecraft:end_stone", null, false),
                BiomeSurface.FLOWER_ISLETS.resolve(PACK::contains, ignored -> {
                }));
    }

    @Test
    void anEmptyPackDegradesToEndStoneInsteadOfThrowing() {
        List<String> broken = new ArrayList<>();
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            // Nothing at all defined, not even end stone: the fallback still has to terminate.
            BiomeSurface.Resolved r = biome.resolve(id -> false, broken::add);
            assertEquals("minecraft:end_stone", r.under(), biome + " did not fall back to end stone");
            assertEquals("minecraft:end_stone", r.top(), biome + " did not fall back to end stone");
            assertNull(r.alt(), biome + " kept a speckle block the pack does not define");
        }
        // Every gap is named, with the biome and the missing id, so the one log line is actionable.
        assertEquals(2 * BiomePlacement.selectable().size() + (int) BiomePlacement.selectable().stream()
                .filter(b -> !b.alt().equals(b.top())).count(), broken.size());
        assertTrue(broken.stream().allMatch(line -> line.startsWith("betterend:") && line.contains(" -> ")),
                broken.toString());
    }

    /**
     * The ore gate. Every BetterEnd ore feature replaces {@code minecraft:end_stone} by EXACT block
     * match and nothing else (placed_feature/thallasium_ore.json and ender_ore.json carry
     * {@code minecraft:block_match} on end_stone; OreLayerFeature.java:58 is
     * {@code state.is(Blocks.END_STONE)} - NOT the end_stones tag), so BetterEndPopulator skips any
     * biome whose filler is something else. Ship a second such biome and this goes red, which is
     * the moment someone has to look at DustWastelandsGenerator.fillsWithEndStone again.
     */
    @Test
    void umbraValleyIsTheOnlyBiomeThatFillsWithSomethingOtherThanEndStone() {
        assertEquals(List.of(BiomeSurface.UMBRA_VALLEY), BiomePlacement.selectable().stream()
                .filter(b -> !"minecraft:end_stone".equals(b.resolve(PACK::contains, ignored -> {
                }).under()))
                .toList());
        // And it degrades the right way: a pack with no umbralith fills it with end stone, so the
        // ore gate opens again rather than silently emptying the biome of ores.
        assertEquals("minecraft:end_stone",
                BiomeSurface.UMBRA_VALLEY.resolve(defines("minecraft:end_stone"), ignored -> {
                }).under());
    }

    // --- the determinism the generator writes blocks from --------------------------------------

    /**
     * The seam test, run over the triple {@code fillSegment} actually writes from:
     * {@code (x, segment top, z)}, not a fixed y. The generator asks
     * {@code surfaceAt(placement, x, top, z)} once per solid run, and {@code top} varies column by
     * column, so this is the only sample that exercises the y-quart boundary the way generation
     * does.
     * <p>
     * x=640 is the border between chunks 39 and 40 at z=2276, chosen because all 32 columns either
     * side are solid and they carry two different biomes (shadow_forest and lantern_woods) - a
     * border with no terrain or no biome change would prove nothing, so both are asserted.
     * <p>
     * Moved from (960, 2311), which held two biomes on the old 128-block square lattice and holds
     * one on the hex map: the flood fill groups roughly 64 cells into one blob, so a same-biome run
     * now averages 106 blocks and a 32-block window usually sits inside a single blob. The old
     * coordinate would have made the last assertion fail, not the determinism the test is for.
     */
    @Test
    void chunkBorderColumnsAgreeWhicheverChunkGeneratesFirst() {
        int border = 40 * 16;
        int z = 2276;

        // Two independent placements, warmed in opposite orders: the land memo and the plane memo
        // are the only mutable state on this path, and this is what would expose either of them
        // answering differently depending on which chunk populated it first.
        List<BiomeSurface> rightFirst = new ArrayList<>();
        List<BiomeSurface> leftFirst = new ArrayList<>();
        walk(rightFirst, border, border + 16, z);
        walk(rightFirst, border - 16, border, z);
        walk(leftFirst, border - 16, border, z);
        walk(leftFirst, border, border + 16, z);

        assertEquals(rightFirst.subList(16, 32), leftFirst.subList(0, 16), "the left chunk changed");
        assertEquals(rightFirst.subList(0, 16), leftFirst.subList(16, 32), "the right chunk changed");

        assertEquals(32, leftFirst.size(), "a column had no terrain, so the sample is degenerate");
        assertTrue(EnumSet.copyOf(leftFirst).size() > 1,
                "every column got the same biome, so a seam could not have shown: " + leftFirst);
    }

    // --- the hex lattice (HexBiomeMap / HexBiomeChunk) -----------------------------------------

    /** The land pool as {@link BiomePlacement} builds it. */
    private static HexBiomeMap.Picker picker(BiomeSurface.Category category) {
        return new HexBiomeMap.Picker(Stream.of(BiomeSurface.values())
                .filter(b -> b.category() == category && b.shipped())
                .toList());
    }

    private static HexBiomeMap map(BiomeSurface.Category category, int size) {
        return new HexBiomeMap(SEED, size, picker(category));
    }

    /**
     * The whole point of Bug 2. The lattice is turned 0.4 rad = 22.918 degrees off the world axes
     * (HexBiomeMap.java:23-24,102-105) and then bent by five octaves of noise, so no cell edge can
     * be parallel to x or z. The square lattice this replaced produced boundaries that ran dead
     * straight for a whole cell, 128 blocks, which is what a player reads as "abrupt".
     * <p>
     * Measured: the longest run of a boundary staying at a fixed x (or z) is 14-15 blocks, and that
     * is the staircase of a diagonal edge, not a straight line. 40 is a threshold no rotated
     * lattice can reach and no axis-aligned one can avoid.
     */
    @Test
    void noCellEdgeIsAxisAligned() {
        HexBiomeMap land = map(BiomeSurface.Category.LAND, 256);
        int span = 384;
        BiomeSurface[][] grid = new BiomeSurface[span + 1][span + 1];
        for (int i = 0; i <= span; i++) {
            for (int j = 0; j <= span; j++) {
                grid[i][j] = land.at(ORIGIN + i, ORIGIN + j);
            }
        }
        int longest = 0;
        for (int i = 0; i < span; i++) {
            int run = 0;
            for (int j = 0; j <= span; j++) {
                run = grid[i][j] != grid[i + 1][j] ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
        }
        for (int j = 0; j < span; j++) {
            int run = 0;
            for (int i = 0; i <= span; i++) {
                run = grid[i][j] != grid[i][j + 1] ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
        }
        assertTrue(longest > 0, "the sample found no biome boundary at all");
        assertTrue(longest < 40, "a biome boundary runs straight along a world axis for " + longest
                + " blocks - the lattice is not rotated");
    }

    /**
     * The lattice is only half the fix. HexBiomeChunk.java:41-65 floods 16 seed points across a
     * 32x32 grid of cells, so one biome covers roughly 64 cells - about 275 blocks - not one
     * 32x37 cell. A port that got the hexagons right and skipped the flood fill would give a
     * mosaic that changes biome every 35 blocks, which is worse than the square lattice it
     * replaced, and every other test here would still pass.
     * <p>
     * Measured mean same-biome run along x: 106 blocks, against a 36.95-block column pitch.
     */
    @Test
    void blobsAreBiggerThanCells() {
        HexBiomeMap land = map(BiomeSurface.Category.LAND, 256);
        long total = 0;
        long runs = 0;
        for (int j = 0; j <= 384; j += 8) {
            BiomeSurface previous = land.at(ORIGIN, ORIGIN + j);
            int run = 1;
            for (int i = 1; i <= 1024; i++) {
                BiomeSurface here = land.at(ORIGIN + i, ORIGIN + j);
                if (here == previous) {
                    run++;
                } else {
                    total += run;
                    runs++;
                    run = 1;
                }
                previous = here;
            }
            total += run;
            runs++;
        }
        double mean = (double) total / runs;
        assertTrue(runs > 100, "too few runs to mean anything: " + runs);
        assertTrue(mean > 60, "the mean same-biome run is " + mean + " blocks, which is one hex cell"
                + " (row pitch 32, column pitch 36.95) - the flood fill is missing");
    }

    /**
     * WoverEndBiomeSource.java:211-233 builds every ring's map from the SAME world seed, and every
     * RNG phase in HexBiomeChunk consumes a draw count that does not depend on which biome comes
     * back, so all the same-size maps land on byte-identical blob geometry and differ only in which
     * biome each blob is named. Salting them apart - which the old {@code Pool.pick} did, and which
     * is the obvious thing to do - would break that.
     * <p>
     * Geometry is not directly observable, so this asserts it through the boundaries: 99% of the
     * cave-pool map's boundaries and 97% of the void pool's fall on exactly a land-map boundary.
     * The residue is name collisions, where two adjacent blobs happened to draw the same land
     * biome. Independent geometry would push the overlap to nearly nothing.
     */
    @Test
    void theRingMapsShareOneBlobGeometry() {
        HexBiomeMap land = map(BiomeSurface.Category.LAND, 256);
        HexBiomeMap caves = map(BiomeSurface.Category.CAVE, 256);
        HexBiomeMap voids = map(BiomeSurface.Category.SMALL_ISLAND, 256);
        int caveTotal = 0;
        int caveShared = 0;
        int voidTotal = 0;
        int voidShared = 0;
        for (int z = 0; z < 4096; z += 137) {
            Set<Integer> landEdges = edges(land, z);
            Set<Integer> caveEdges = edges(caves, z);
            Set<Integer> voidEdges = edges(voids, z);
            caveTotal += caveEdges.size();
            voidTotal += voidEdges.size();
            caveShared += (int) caveEdges.stream().filter(landEdges::contains).count();
            voidShared += (int) voidEdges.stream().filter(landEdges::contains).count();
        }
        assertTrue(caveTotal > 200 && voidTotal > 100,
                "too few boundaries to compare: " + caveTotal + ", " + voidTotal);
        assertTrue(caveShared > caveTotal * 0.95, caveShared + " of " + caveTotal
                + " cave-pool boundaries fall on a land boundary - the maps do not share geometry");
        assertTrue(voidShared > voidTotal * 0.9, voidShared + " of " + voidTotal
                + " void boundaries fall on a land boundary - the maps do not share geometry");
    }

    /** Block x of every biome change along one z line. */
    private static Set<Integer> edges(HexBiomeMap map, int z) {
        Set<Integer> out = new TreeSet<>();
        BiomeSurface previous = map.at(0, z);
        for (int x = 1; x <= 4096; x++) {
            BiomeSurface here = map.at(x, z);
            if (here != previous) out.add(x);
            previous = here;
        }
        return out;
    }

    /**
     * WoverBiomeData.java:476-478 / WoverBiomeSourceImpl.java:99-103 - a biome with a parent is not
     * top-level pickable. megalake_grove, neon_oasis and painted_mountains (EndBiomes.java:34-36)
     * therefore never name a blob; they reach the world only through the 1-in-4 speckle pass
     * (HexBiomeChunk.java:76-92), and only over their own parent's cells. Before this they competed
     * at full weight in the top-level pool, which misweighted every other land biome as well.
     * <p>
     * The second half is the non-vacuity: they must still reach the world. dust_wastelands' list is
     * {self 1.0, neon_oasis 0.5, painted_mountains 1.0}; a biome with no children draws anyway and
     * gets itself back, which is what keeps the rings' RNG streams in lockstep.
     */
    @Test
    void subBiomesAreNotTopLevelButDoReachTheWorld() {
        HexBiomeMap.Picker land = picker(BiomeSurface.Category.LAND);
        EnumSet<BiomeSurface> topLevel = EnumSet.noneOf(BiomeSurface.class);
        Random random = new Random(1);
        for (int i = 0; i < 100000; i++) {
            topLevel.add(land.pick(random));
        }
        assertEquals(15, topLevel.size(), "the top-level land pool is " + topLevel);
        for (BiomeSurface sub : List.of(BiomeSurface.MEGALAKE_GROVE, BiomeSurface.NEON_OASIS,
                BiomeSurface.PAINTED_MOUNTAINS)) {
            assertFalse(topLevel.contains(sub), sub + " was picked as a top-level blob biome");
            assertNotNull(sub.parent(), sub + " has no parent");
        }

        EnumSet<BiomeSurface> dust = EnumSet.noneOf(BiomeSurface.class);
        for (int i = 0; i < 10000; i++) {
            dust.add(land.subBiome(BiomeSurface.DUST_WASTELANDS, random));
        }
        assertEquals(EnumSet.of(BiomeSurface.DUST_WASTELANDS, BiomeSurface.NEON_OASIS,
                BiomeSurface.PAINTED_MOUNTAINS), dust);
        assertSame(BiomeSurface.AMBER_LAND, land.subBiome(BiomeSurface.AMBER_LAND, random),
                "a biome with no children must draw and get itself back");

        // And they do land in the world: the speckle is the only way any of the three can appear.
        HexBiomeMap map = map(BiomeSurface.Category.LAND, 256);
        EnumSet<BiomeSurface> painted = EnumSet.noneOf(BiomeSurface.class);
        for (int x = 0; x < 4096; x += 8) {
            for (int z = 0; z < 4096; z += 8) {
                painted.add(map.at(ORIGIN + x, ORIGIN + z));
            }
        }
        assertTrue(painted.containsAll(List.of(BiomeSurface.MEGALAKE_GROVE, BiomeSurface.NEON_OASIS,
                BiomeSurface.PAINTED_MOUNTAINS)), "no sub-biome reached the world: " + painted);
    }

    /**
     * The category layer, WoverEndBiomeSource.java:327-341 + EndLandBiomeDecider.java:47-55. Inside
     * {@code innerVoidRadiusSquared} (radius 1024) the mod paints vanilla center or barrens, never a
     * BetterEnd biome; the port used to place small-island biomes there. Outside it, no ring at all.
     * <p>
     * The barrens half is the one that was missing. The center half was there under a different
     * name, the 384-block {@code VanillaEndCore} disc, which {@link BiomePlacement#ring} now folds
     * in unconditionally so the dragon's spike ring cannot lose a pillar to a void quart.
     */
    @Test
    void theInnerVoidRadiusIsAVanillaRing() {
        assertNull(placement.ring(ORIGIN, ORIGIN), "a ring 8192 blocks out");
        assertSame(BiomePlacement.Ring.CENTER, placement.ring(0, 0), "the pinned island is center");
        assertSame(BiomePlacement.Ring.CENTER, placement.ring(300, 0),
                "the whole 384-block core must be center whatever the terrain says");

        int barrens = 0;
        int center = 0;
        for (int radius = 500; radius <= 1000; radius += 25) {
            for (int degrees = 0; degrees < 360; degrees += 11) {
                int x = (int) (Math.cos(Math.toRadians(degrees)) * radius);
                int z = (int) (Math.sin(Math.toRadians(degrees)) * radius);
                BiomePlacement.Ring ring = placement.ring(x, z);
                assertNotNull(ring, "no ring at " + x + "," + z + ", radius " + radius);
                if (ring == BiomePlacement.Ring.BARRENS) barrens++; else center++;
            }
        }
        // The inner-void cull (IslandLayer.java:93-98) drops every island CENTRED within 1024
        // blocks, so this annulus is almost entirely void and therefore almost entirely barrens -
        // which is exactly the biome the mod paints there and the port never did. "Almost": a big
        // island centred just outside 1024 reaches about 165 blocks inward, and that rim is the
        // only terrain left in here (4 of 693 samples on this seed).
        assertTrue(barrens > 0, "the barrens ring is empty");
        assertTrue(center * 50 < barrens, center + " of " + (center + barrens) + " samples in the"
                + " culled annulus hold terrain - the inner-void cull is not running");

        // Just outside 1024 the ring stops and BetterEnd's own biomes take over again.
        assertNull(placement.ring(1100, 0));
        assertNull(placement.ring(0, -1100));
    }

    /** One fresh field and placement per call, sampled exactly as {@code fillSegment} does. */
    private static void walk(List<BiomeSurface> out, int fromX, int toX, int z) {
        IslandField field = new IslandField(SEED);
        BiomePlacement placement = new BiomePlacement(SEED, field);
        for (int x = fromX; x < toX; x++) {
            int top = field.topY(x, z, 0, 255);
            if (top != Integer.MIN_VALUE) {
                out.add(placement.quartAt(x, top, z));
            }
        }
    }

    @Test
    void quartAtAnswersTheSameQuestionTheBiomeProviderPaints() {
        // CustomWorldChunkManager.java:34 hands the provider QuartPos.toBlock coordinates; the block
        // the generator writes must be the answer to that identical call, or F3 and the ground
        // disagree at every biome boundary.
        for (int[] p : grid()) {
            for (int y : new int[]{CAVE_Y, SURFACE_Y}) {
                BiomeSurface painted = placement.at((p[0] >> 2) << 2, (y >> 2) << 2, (p[1] >> 2) << 2);
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        assertSame(painted, placement.quartAt(p[0] + dx, y, p[1] + dz));
                    }
                }
            }
        }
        // Negative coordinates floor-align, they do not round toward zero.
        assertSame(placement.at(-4, 0, -4), placement.quartAt(-1, 3, -1));

        // And the alignment is load-bearing, not decoration: the cave band reads raw x, y, z, so
        // for ~1% of deep columns an unaligned lookup answers something else - which is exactly the
        // disagreement between the block written and the biome named that this method removes.
        boolean alignmentMatters = false;
        for (int x = ORIGIN; x < ORIGIN + 512; x++) {
            for (int z : new int[]{ORIGIN, ORIGIN + 63}) {
                alignmentMatters |= placement.at(x, CAVE_Y, z) != placement.quartAt(x, CAVE_Y, z);
            }
        }
        assertTrue(alignmentMatters, "quart alignment changed nothing - the sample is degenerate");
    }
}
