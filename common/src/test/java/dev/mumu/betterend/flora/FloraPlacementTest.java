package dev.mumu.betterend.flora;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.config.BetterEndConfig;
import dev.mumu.betterend.config.SafeYaml;
import dev.mumu.betterend.flora.Flora.Engine;
import dev.mumu.betterend.flora.Flora.Row;
import dev.mumu.betterend.flora.FloraPlanner.ColumnScan;
import dev.mumu.betterend.terrain.IslandField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flora layer, with no server: {@link FloraPlanner} names nothing outside {@code java.*} and
 * the two pure worldgen classes, so every assertion below runs on the real {@link IslandField} and
 * the real {@link BiomePlacement} without Bukkit or CraftEngine.
 * <p>
 * T9 and T10 also read the shipped pack's YAML off disk, through the project's own
 * {@link SafeYaml}: T9 pins the vine removal's two halves (the table here, the block definitions
 * there), and T10 closes the whole of {@link Flora#ROWS} against the {@code blocks/*.yml} catalogue -
 * every id a row writes, every property value it stamps, and the four crop ages the seed batch
 * restores. That is still no CraftEngine - the YAML is parsed, not loaded - but those tests do need
 * the repo checkout underfoot, unlike the terrain ones.
 * <p>
 * The sample is built once (a few seconds of real terrain) and shared, because six of the seven
 * terrain tests below are statements about the same set of planned blocks.
 */
class FloraPlacementTest {
    private static final long SEED = 42L;

    private record Placed(int x, int y, int z, Row row, int idIndex, String value) {
    }

    /** One planned chunk: its blocks, its dominant biome, and how often each row fired. */
    private record Chunk(int chunkX, int chunkZ, BiomeSurface biome, List<Placed> blocks,
                         Map<String, Integer> placements) {
    }

    // --- the sample ---------------------------------------------------------------------------

    private static final class Sample {
        final BiomePlacement placement = BiomePlacement.bind(SEED, BetterEndConfig.DEFAULTS);
        final IslandField field = placement.field();
        final FloraPlanner.Surface surface = surfaceOracle(placement);
        final List<Chunk> chunks = new ArrayList<>();

        Sample(int wanted) {
            // A coarse grid, keeping only the chunks the void gate lets through - the same gate
            // FloraPopulator applies before allocating anything. The 8-chunk stride is half a
            // 256-block biome cell, so consecutive samples land in different biomes and the whole
            // land pool shows up; the bounded box keeps IslandField's plane memo from exploding.
            outer:
            for (int cx = -160; cx <= 160; cx += 8) {
                for (int cz = -160; cz <= 160; cz += 8) {
                    Chunk chunk = plan(this, cx, cz);
                    if (chunk != null) {
                        chunks.add(chunk);
                        if (chunks.size() >= wanted) {
                            break outer;
                        }
                    }
                }
            }
        }
    }

    private static final Sample SAMPLE = new Sample(400);
    private static final BiomeSurface.Resolved[] RESOLVED = resolved();

    private static BiomeSurface.Resolved[] resolved() {
        BiomeSurface.Resolved[] table = new BiomeSurface.Resolved[BiomeSurface.values().length];
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            table[biome.ordinal()] = biome.resolve(id -> true, broken -> {
            });
        }
        return table;
    }

    /** The pack defines everything, so nothing degrades and every row is exercised. */
    private static FloraPlanner.Surface surfaceOracle(BiomePlacement placement) {
        BiomeSurface.Resolved[] resolved = new BiomeSurface.Resolved[BiomeSurface.values().length];
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            resolved[biome.ordinal()] = biome.resolve(id -> true, broken -> {
            });
        }
        return new FloraPlanner.Surface(placement, resolved);
    }

    /** @return null when the void gate rejects the chunk, exactly as {@code populate} would. */
    private static Chunk plan(Sample sample, int chunkX, int chunkZ) {
        int x0 = (chunkX << 4) - 16;
        int z0 = (chunkZ << 4) - 16;
        if (!sample.field.anySolid(x0, z0, x0 + 47, z0 + 47, ColumnScan.LO, ColumnScan.HI)) {
            return null;
        }
        List<Placed> blocks = new ArrayList<>();
        Map<String, Integer> placements = new HashMap<>();
        ColumnScan scan = new ColumnScan(sample.field, chunkX, chunkZ);
        Random rng = new Random(FloraPlanner.mix(SEED, chunkX, chunkZ));
        FloraPlanner.plan(Flora.ROWS, scan, sample.surface, sample.placement, rng, chunkX, chunkZ,
                (x, y, z, row, idIndex, value) -> blocks.add(new Placed(x, y, z, row, idIndex, value)));
        for (Placed p : blocks) {
            placements.merge(p.row().name(), 1, Integer::sum);
        }
        int centreX = (chunkX << 4) + 8;
        int centreZ = (chunkZ << 4) + 8;
        int top = sample.field.topY(centreX, centreZ, ColumnScan.LO, ColumnScan.HI);
        BiomeSurface biome = top == FloraPlanner.NONE || sample.placement.ring(centreX, centreZ) != null
                ? null : sample.placement.quartAt(centreX, top, centreZ);
        return new Chunk(chunkX, chunkZ, biome, blocks, placements);
    }

    private static ColumnScan scanFor(Chunk chunk) {
        return new ColumnScan(SAMPLE.field, chunk.chunkX(), chunk.chunkZ());
    }

    // --- T1 density ---------------------------------------------------------------------------

    /**
     * {@code count_on_every_layer} re-samples its count every layer round and stops on the first
     * round that finds no surface, so a single-surface End column yields E[placements] = N/2 with
     * two rounds. The two ways to get this wrong are both catastrophic and both caught here: a
     * count drawn ONCE outside the round loop halves the density, and a loop that runs until
     * {@code count == 0} instead of until a round lands nothing gives {@code N(N+1)/2} - for
     * crystal_grass (N=20) that is 210 placements a chunk instead of 10, a 21x carpet.
     * <p>
     * The bands are wide because a real chunk is not a flat plane: void columns inside the write
     * zone, biome edges and the soil test all cut below the analytic figure. They are narrow
     * enough that either failure mode above lands far outside.
     */
    @Test
    void densityMatchesCountOnEveryLayer() {
        assertDensity("crystal_grass", BiomeSurface.CRYSTAL_MOUNTAINS);
        assertDensity("inflexia", BiomeSurface.UMBRA_VALLEY);
        assertDensity("twisted_moss", BiomeSurface.BLOSSOMING_SPIRES);
    }

    /**
     * {@code plan} returns the number of feature placements it dispatched, so passing a single-row
     * list measures that row alone - no instrumentation, no counter interface.
     */
    private void assertDensity(String rowName, BiomeSurface host) {
        Row row = Flora.ROWS.stream().filter(r -> r.name().equals(rowName)).findFirst().orElseThrow();
        List<Row> only = List.of(row);
        int chunks = 0;
        int placements = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            if (chunk.biome() != host) {
                continue;
            }
            chunks++;
            ColumnScan scan = scanFor(chunk);
            Random rng = new Random(FloraPlanner.mix(SEED, chunk.chunkX(), chunk.chunkZ()));
            placements += FloraPlanner.plan(only, scan, SAMPLE.surface, SAMPLE.placement, rng,
                    chunk.chunkX(), chunk.chunkZ(), (x, y, z, r, i, v) -> {
                    });
        }
        assertTrue(chunks >= 4, rowName + ": only " + chunks + " sampled chunks host " + host);
        double mean = (double) placements / chunks;
        double analytic = row.maxPerLayer() / 2.0;
        // The band is generous upward because a stacked End column really does carry layer-1 and
        // layer-2 surfaces, which is the whole point of count_on_every_layer, and downward because
        // part of a sampled chunk's 48x48 zone is void or another biome.
        assertTrue(mean > analytic * 0.6 && mean < analytic * 3.0,
                rowName + " placed " + mean + " features/chunk in " + chunks + " " + host
                        + " chunks; count_on_every_layer(uniform(0," + row.maxPerLayer()
                        + ")) predicts " + analytic + ". Half of it means the count is drawn once"
                        + " outside the layer loop; " + (row.maxPerLayer() + 1) / 2.0 * analytic
                        + " means the loop runs until count==0 instead of until a round lands"
                        + " nothing.");
    }

    // --- T2 soil ------------------------------------------------------------------------------

    /**
     * Every ground plant stands on a block its {@code survives_on} tag accepts. The
     * {@code TreePopulator.biomeSoil} shortcut passes every other test here and fails this one in
     * umbra_valley (inflexia on umbralith, whose {@code alt} is the pallidium it actually needs),
     * megalake (umbrella_moss on the endstone_dust speckle) and neon_oasis (the inverse).
     */
    @Test
    void everyGroundPlantStandsOnItsOwnSoil() {
        int checked = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            ColumnScan scan = scanFor(chunk);
            for (Placed p : chunk.blocks()) {
                boolean tall = p.row().engine() == Engine.DOUBLE && "lower".equals(p.value());
                boolean shortDouble = p.row().engine() == Engine.DOUBLE && p.value() == null;
                if (p.row().engine() != Engine.GROUND && !tall && !shortDouble) {
                    continue;
                }
                Flora.SoilSet soil = tall ? p.row().soilTall() : p.row().soil();
                if (soil == Flora.SoilSet.ANY) {
                    continue;
                }
                int ground = p.y() - 1;
                int bottom = scan.segmentBottom(p.x(), p.z(), ground);
                String id = SAMPLE.surface.at(p.x(), p.z(), ground, ground - bottom + 1);
                assertTrue(soil.accepts(id), p.row().name() + " grew on " + id
                        + " at " + p.x() + "," + p.y() + "," + p.z() + "; " + soil + " forbids it");
                checked++;
            }
        }
        assertTrue(checked > 200, "only " + checked + " ground plants to check the soil rule on");
    }

    // --- T3 support ---------------------------------------------------------------------------

    /**
     * Nothing floats and nothing is buried. A port that keeps {@code ScatterFeature}'s raw
     * {@code downRay} semantics writes plants inside hillsides; one that uses the column's global
     * top instead of the banded search floats them above overhangs; one that inverts the ceiling
     * test silently drops every ceiling plant.
     */
    @Test
    void nothingFloatsAndNothingIsBuried() {
        int walls = 0;
        int ceilings = 0;
        int columns = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            ColumnScan scan = scanFor(chunk);
            Set<Long> planned = new HashSet<>();
            for (Placed p : chunk.blocks()) {
                planned.add(cell(p.x(), p.y(), p.z()));
            }
            for (Placed p : chunk.blocks()) {
                // A COLUMN stamp's body and its cap must rest on terrain or on the block below it
                // in its own stamp; only the fur/leaf ring (idIndex 2) hangs off the side.
                if (p.row().engine() == Engine.COLUMN && p.idIndex() == 2) {
                    // A stamp's fur/leaf ring takes the four horizontals and UP, never DOWN: that
                    // cell always holds the stalk this stamp just wrote, which the mod skips
                    // because isEmptyBlock sees it. ColumnScan holds only terrain, so an air()
                    // test alone put a DOWN leaf through glow_pillar's top roots block - and half
                    // of all glow pillars are one block tall, so that was the whole plant.
                    assertFalse("down".equals(p.value()), p.row().name()
                            + " planned a DOWN ring block at " + p.x() + "," + p.y() + "," + p.z()
                            + "; that cell is its own stalk");
                }
                if (p.row().engine() == Engine.COLUMN && p.idIndex() <= 1) {
                    columns++;
                    assertTrue(scan.solid(p.x(), p.y() - 1, p.z())
                                    || planned.contains(cell(p.x(), p.y() - 1, p.z())),
                            p.row().name() + " floats: nothing under " + p.x() + ","
                                    + p.y() + "," + p.z());
                }
                // The one cell the mod itself does not guarantee is a double plant's upper half.
                if (!"upper".equals(p.value())) {
                    assertFalse(scan.solid(p.x(), p.y(), p.z()),
                            p.row().name() + " was written inside terrain at "
                                    + p.x() + "," + p.y() + "," + p.z());
                }
                switch (p.row().engine()) {
                    case GROUND -> assertTrue(scan.solid(p.x(), p.y() - 1, p.z()),
                            p.row().name() + " floats at " + p.x() + "," + p.y() + "," + p.z());
                    case DOUBLE -> {
                        if (!"upper".equals(p.value())) {
                            assertTrue(scan.solid(p.x(), p.y() - 1, p.z()),
                                    p.row().name() + " floats at " + p.y());
                        }
                    }
                    case WALL -> {
                        walls++;
                        int dx = "east".equals(p.value()) ? 1 : "west".equals(p.value()) ? -1 : 0;
                        int dz = "south".equals(p.value()) ? 1 : "north".equals(p.value()) ? -1 : 0;
                        assertTrue(scan.solid(p.x() - dx, p.y(), p.z() - dz),
                                p.row().name() + " clings to air, facing=" + p.value());
                    }
                    case CEILING -> {
                        ceilings++;
                        assertTrue(scan.solid(p.x(), p.y() + 1, p.z()),
                                p.row().name() + " hangs from air at " + p.y());
                    }
                    default -> {
                    }
                }
            }
        }
        assertTrue(walls > 0, "no wall plant was ever placed - the box scan is dead");
        // Sparse by nature: small_jellyshroom_ceil is the only CEILING row and only 11 umbrella_jungle
        // chunks are in the sample, so this held 4 blocks on seed 42 after the vines were dropped.
        // It is a "the scan runs at all" check, not a density figure - a red here means the ceiling
        // scan stopped firing, and the sample needs widening only if the terrain itself changed.
        assertTrue(ceilings > 0, "no ceiling plant - the ceiling scan is dead");
        assertTrue(columns > 0, "no column stamp was ever placed");
    }

    private static long cell(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) z << 12) ^ y;
    }

    // --- T4 surface agreement -----------------------------------------------------------------

    /**
     * The cap/alt arithmetic, every branch. A version that drops the {@code cap > 0} guard returns
     * the top block for a one-block dusty segment where {@code fillSegment:203-211} wrote the
     * filler; one that applies the alt to the whole cap band instead of the top block speckles
     * five blocks deep.
     */
    @Test
    void surfaceArithmeticMatchesTheGenerator() {
        BiomeSurface.Resolved dusty = new BiomeSurface.Resolved(
                "betterend:endstone_dust", "minecraft:end_stone", "betterend:end_moss", true);
        BiomeSurface.Resolved plain = new BiomeSurface.Resolved(
                "betterend:umbralith", "betterend:umbralith", "betterend:pallidium_full", false);
        BiomeSurface.Resolved noAlt = new BiomeSurface.Resolved(
                "betterend:sangnum", "minecraft:end_stone", null, false);

        assertEquals("minecraft:end_stone", FloraPlanner.Surface.block(dusty, 0, false),
                "a one-block dusty segment has no cap, so its floor is the filler");
        assertEquals("minecraft:end_stone", FloraPlanner.Surface.block(dusty, 0, true),
                "no cap means no speckle either");
        assertEquals("betterend:endstone_dust", FloraPlanner.Surface.block(dusty, 5, false));
        assertEquals("betterend:end_moss", FloraPlanner.Surface.block(dusty, 5, true));
        assertEquals("betterend:umbralith", FloraPlanner.Surface.block(plain, 0, false),
                "a non-dusty biome caps one block whatever dustDepth says");
        assertEquals("betterend:pallidium_full", FloraPlanner.Surface.block(plain, 0, true));
        assertEquals("betterend:sangnum", FloraPlanner.Surface.block(noAlt, 3, true),
                "a null alt must never be returned");
    }

    /**
     * The oracle must return what {@code DustWastelandsGenerator} actually wrote - not just be
     * self-consistent. The loop below is transcribed from {@code generateNoise:161-179} +
     * {@code fillSegment:196-208} + {@code surfaceAt:219-226}, NOT from {@link FloraPlanner}:
     * cut the density column into segments the generator's way, apply its cap and speckle rules,
     * and compare block ids. A {@code Surface} that sampled the biome at the plant instead of the
     * segment top, passed the wrong segment height to {@code dustDepth}, dropped the ring arm or
     * applied the alt below the top block fails here and passes every other test in this file,
     * because every other test asks the same oracle the planner asked.
     */
    @Test
    void theSurfaceOracleIsWhatTheGeneratorWrote() {
        Random rng = new Random(11);
        int compared = 0;
        int rings = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            int x = rng.nextInt(4000) - 2000;
            int z = rng.nextInt(4000) - 2000;
            int maxY = 256;
            double[] column = SAMPLE.field.densityColumn(x, z, 0, maxY - 1);
            int bottom = Integer.MIN_VALUE;
            for (int y = 0; y <= maxY; y++) {
                boolean solid = y < maxY && column[y] > 0;
                if (solid) {
                    if (bottom == Integer.MIN_VALUE) {
                        bottom = y;
                    }
                    continue;
                }
                if (bottom == Integer.MIN_VALUE) {
                    continue;
                }
                int top = y - 1;
                String expected;
                if (SAMPLE.placement.ring(x, z) != null) {
                    expected = "minecraft:end_stone";
                    rings++;
                } else {
                    BiomeSurface.Resolved r = RESOLVED[
                            SAMPLE.placement.quartAt(x, top, z).ordinal()];
                    int cap = r.dusty() ? SAMPLE.field.dustDepth(x, z, top - bottom + 1) : 1;
                    expected = top >= top - cap + 1 ? r.top() : r.under();
                    if (r.alt() != null && cap > 0 && SAMPLE.field.altSurface(x, z)) {
                        expected = r.alt();
                    }
                }
                assertEquals(expected, SAMPLE.surface.at(x, z, top, top - bottom + 1),
                        "the flora oracle disagrees with fillSegment at " + x + "," + top + "," + z);
                seen.add(expected);
                compared++;
                bottom = Integer.MIN_VALUE;
            }
        }
        assertTrue(compared > 2000, "only " + compared + " segments compared");
        assertTrue(rings > 0, "no ring column sampled - the vanilla-ring arm is untested");
        assertTrue(seen.size() >= 8, "only " + seen.size()
                + " distinct surface blocks exercised; the sample is degenerate");
    }

    /**
     * Every COLUMN row must have a stamp in {@code FloraPlanner.column}'s switch, whose default
     * arm throws - on the worldgen thread, for a row someone adds to the table later.
     */
    @Test
    void everyColumnRowHasAStamp() {
        assertEquals(Set.of("glow_pillar", "lanceleaf", "lumecorn", "large_amaranita"),
                Flora.ROWS.stream().filter(r -> r.engine() == Engine.COLUMN)
                        .map(Row::name).collect(java.util.stream.Collectors.toSet()),
                "a COLUMN row without a stamp throws IllegalStateException from generation");
    }

    /**
     * The bitmap must agree with {@link IslandField#topY} exactly. An off-by-one in the bit walk,
     * or a band clamped differently from the field's, buries or floats the whole world.
     */
    @Test
    void theBitmapAgreesWithTheField() {
        Random rng = new Random(7);
        int checked = 0;
        for (int i = 0; i < 60; i++) {
            int chunkX = rng.nextInt(400) - 200;
            int chunkZ = rng.nextInt(400) - 200;
            ColumnScan scan = new ColumnScan(SAMPLE.field, chunkX, chunkZ);
            for (int j = 0; j < 170; j++) {
                int x = scan.minX() + rng.nextInt(48);
                int z = scan.minZ() + rng.nextInt(48);
                assertEquals(SAMPLE.field.topY(x, z, ColumnScan.LO, ColumnScan.HI),
                        scan.top(x, z), "bitmap top disagrees at " + x + "," + z);
                if (checked % 20 == 0) {
                    // Every twentieth column block for block, plus the segment walk the soil test
                    // depends on: top() alone can agree while every bit under it is shifted.
                    double[] column = SAMPLE.field.densityColumn(x, z, ColumnScan.LO, ColumnScan.HI);
                    for (int y = ColumnScan.LO; y <= ColumnScan.HI; y++) {
                        assertEquals(column[y - ColumnScan.LO] > 0, scan.solid(x, y, z),
                                "bitmap disagrees with the field at " + x + "," + y + "," + z);
                    }
                    int top = scan.top(x, z);
                    if (top != FloraPlanner.NONE) {
                        int expected = top;
                        while (expected > ColumnScan.LO && column[expected - 1 - ColumnScan.LO] > 0) {
                            expected--;
                        }
                        assertEquals(expected, scan.segmentBottom(x, z, top),
                                "segment bottom disagrees at " + x + "," + z);
                    }
                }
                checked++;
            }
        }
        assertTrue(checked >= 10000, "only " + checked + " columns compared");
    }

    // --- T5 determinism -----------------------------------------------------------------------

    /**
     * Replanning a chunk, and planning eight chunks on four threads in a shuffled order, must both
     * reproduce the single-threaded result block for block. The mod's shared static
     * {@code Direction[] DIR} shuffle ({@code WallScatterFeature.java:18,95-102}) is exactly the
     * trap this catches; so is any lazy init or unsalted use of the populator's {@code Random}.
     */
    @Test
    void planningIsDeterministicAndThreadSafe() throws Exception {
        List<Chunk> targets = SAMPLE.chunks.subList(0, Math.min(8, SAMPLE.chunks.size()));
        List<String> expected = new ArrayList<>();
        for (Chunk chunk : targets) {
            expected.add(digest(plan(SAMPLE, chunk.chunkX(), chunk.chunkZ())));
        }

        List<Chunk> shuffled = new ArrayList<>(targets);
        Collections.shuffle(shuffled, new Random(3));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Map<String, Future<String>> results = new HashMap<>();
            for (Chunk chunk : shuffled) {
                results.put(chunk.chunkX() + ":" + chunk.chunkZ(),
                        pool.submit(() -> digest(plan(SAMPLE, chunk.chunkX(), chunk.chunkZ()))));
            }
            for (int i = 0; i < targets.size(); i++) {
                Chunk chunk = targets.get(i);
                assertEquals(expected.get(i),
                        results.get(chunk.chunkX() + ":" + chunk.chunkZ()).get(),
                        "chunk " + chunk.chunkX() + "," + chunk.chunkZ() + " differs across threads");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        }
    }

    private static String digest(Chunk chunk) {
        StringBuilder out = new StringBuilder();
        for (Placed p : chunk.blocks()) {
            out.append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(' ')
                    .append(p.row().name()).append('/').append(p.idIndex()).append('=')
                    .append(p.value()).append('\n');
        }
        return out.toString();
    }

    // --- T6 write zone ------------------------------------------------------------------------

    /**
     * Every planned block sits inside the 48x48 a populator may write
     * ({@code CraftLimitedRegion.java:49,66-73}). Without the clip, a radius-12 disc or a
     * radius-6 box makes {@code setBlockData} THROW - it does not no-op - and kills the worldgen
     * worker. Y is not clipped here; {@code FloraPopulator} guards every write with
     * {@code isInRegion}, which is what covers it.
     */
    @Test
    void nothingIsPlannedOutsideTheWriteZone() {
        for (Chunk chunk : SAMPLE.chunks) {
            int x0 = (chunk.chunkX() << 4) - 16;
            int z0 = (chunk.chunkZ() << 4) - 16;
            for (Placed p : chunk.blocks()) {
                assertTrue(p.x() >= x0 && p.x() <= x0 + 47 && p.z() >= z0 && p.z() <= z0 + 47,
                        p.row().name() + " planned at " + p.x() + "," + p.z()
                                + ", outside chunk " + chunk.chunkX() + "," + chunk.chunkZ());
            }
        }
    }

    // --- T7 non-degenerate --------------------------------------------------------------------

    /**
     * The sample that passes every test above and is still worthless: one engine silently never
     * fires, or every write is the same block. Six distinct host biomes and a spread of ids is
     * what "a player walking the End sees each biome's signature flora" reduces to.
     */
    @Test
    void theWorldIsNotOneBlockRepeated() {
        Map<String, Integer> perId = new HashMap<>();
        Set<BiomeSurface> hosts = new HashSet<>();
        Set<Engine> engines = new HashSet<>();
        int total = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            for (Placed p : chunk.blocks()) {
                perId.merge(p.row().ids().get(p.idIndex()), 1, Integer::sum);
                engines.add(p.row().engine());
                total++;
            }
            if (!chunk.blocks().isEmpty() && chunk.biome() != null) {
                // The biome that actually produced flora, not the six a fired row declares.
                hosts.add(chunk.biome());
            }
        }
        assertTrue(total > 1000, "only " + total + " blocks planned over " + SAMPLE.chunks.size()
                + " land chunks - the flora layer is barely alive");
        assertTrue(perId.size() >= 12, "only " + perId.size() + " distinct block ids written");
        for (Map.Entry<String, Integer> e : perId.entrySet()) {
            assertTrue(e.getValue() <= total * 0.6,
                    e.getKey() + " is " + (100 * e.getValue() / total) + "% of all writes");
        }
        assertTrue(hosts.size() >= 6, "only " + hosts.size() + " host biomes produced flora");
        assertTrue(engines.contains(Engine.GROUND), "no GROUND plant");
        assertTrue(engines.contains(Engine.WALL), "no WALL plant");
        // CEILING is now the only hung-block engine: VINE went with its four rows (blue_vine was a
        // COLUMN stamp, not a VINE one), so this assertion can no longer be satisfied by a vine.
        assertTrue(engines.contains(Engine.CEILING), "no ceiling plant fired");
    }

    /** Every emitted property value must be one the pack declares, or the palette cannot map it. */
    @Test
    void everyEmittedStateIsDeclared() {
        for (Chunk chunk : SAMPLE.chunks) {
            for (Placed p : chunk.blocks()) {
                Flora.Prop prop = FloraPlanner.propOf(p.row(), p.idIndex());
                if (prop == Flora.Prop.NONE) {
                    assertEquals(null, p.value(),
                            p.row().name() + " emitted a value for a property-less block");
                } else {
                    assertTrue(prop.stateValues().contains(p.value()),
                            p.row().name() + " emitted " + prop.property() + "=" + p.value()
                                    + ", which " + prop + " does not declare");
                }
            }
        }
    }

    // --- T8 void and core ---------------------------------------------------------------------

    /**
     * A void chunk and a vanilla-core chunk both plan nothing: the first because the terrain has
     * no surface at all, the second because {@code BiomePlacement.ring} is non-null there and
     * BetterEnd paints no biome inside radius 1024. A missing void gate builds gardens in the
     * emptiness; a missing ring gate carpets the dragon's island in moss.
     */
    @Test
    void theVoidAndTheVanillaCoreStayEmpty() {
        assertEquals(FloraPlanner.NONE, SAMPLE.field.topY(100, 0, ColumnScan.LO, ColumnScan.HI),
                "(100,0) is not void any more; pick another column");
        Chunk core = plan(SAMPLE, 0, 0);
        assertTrue(core == null || core.blocks().isEmpty(),
                "flora was planned inside the vanilla end core");

        int voidChunks = 0;
        for (int cx = -400; cx <= 400 && voidChunks < 8; cx += 37) {
            int x0 = (cx << 4) - 16;
            if (SAMPLE.field.anySolid(x0, -16, x0 + 47, 31, ColumnScan.LO, ColumnScan.HI)) {
                continue;
            }
            voidChunks++;
            Chunk chunk = plan(SAMPLE, cx, 0);
            assertTrue(chunk == null, "the void gate let chunk " + cx + ",0 through");
        }
        assertTrue(voidChunks > 0, "no void chunk found to test the gate on");
    }

    /**
     * The flora layer's terrain cost, bounded. Every question all six engines ask is a boolean
     * about one block, so {@link ColumnScan} builds each column's solidity bitmap once from
     * {@code densityColumn} and answers the rest with bit tests; a port that called
     * {@code densityColumn} per question would build thousands of columns a chunk instead.
     * {@code generateNoise} itself builds 256 (one per block column), which is the yardstick.
     */
    @Test
    void theTerrainCostStaysNearTheGenerator() {
        long columns = 0;
        int chunks = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            ColumnScan scan = scanFor(chunk);
            Random rng = new Random(FloraPlanner.mix(SEED, chunk.chunkX(), chunk.chunkZ()));
            FloraPlanner.plan(Flora.ROWS, scan, SAMPLE.surface, SAMPLE.placement, rng,
                    chunk.chunkX(), chunk.chunkZ(), (x, y, z, r, i, v) -> {
                    });
            columns += scan.columnsBuilt();
            chunks++;
        }
        double mean = (double) columns / chunks;
        System.out.printf("flora builds %.1f density columns per land chunk"
                + " (generateNoise builds 256, the scan's own zone holds 2304)%n", mean);
        assertTrue(mean < 512, "flora builds " + mean + " density columns a chunk, more than twice"
                + " generateNoise's 256 - the per-column bitmap memo is not doing its job");
    }

    // --- T9 the seven vine ids that were dropped -----------------------------------------------

    /** The vine ids taken out of both the table and the pack, without their {@code betterend:}. */
    private static final Set<String> REMOVED_VINES = Set.of("blue_vine", "blue_vine_fur",
            "blue_vine_lantern", "bulb_vine", "dense_vine", "jungle_vine", "twisted_vine");

    /**
     * The removal is two-sided and both sides are pinned here. A row left behind plans a block the
     * pack no longer defines (the palette disables it and logs a dead feature forever); a block left
     * behind is an id nothing can ever place. Neither half on its own is a complete deletion, and a
     * future re-add has to revisit both - which is exactly what this fails on.
     */
    @Test
    void theVinesAreGoneFromBothTheTableAndThePack() throws IOException {
        for (Row row : Flora.ROWS) {
            assertFalse(REMOVED_VINES.contains(row.name()), "Flora still plans " + row.name());
            for (String id : row.ids()) {
                assertFalse(REMOVED_VINES.contains(id), row.name() + " still writes " + id);
            }
        }
        for (Chunk chunk : SAMPLE.chunks) {
            for (Placed p : chunk.blocks()) {
                String id = p.row().ids().get(p.idIndex());
                assertFalse(REMOVED_VINES.contains(id), p.row().name() + " planned removed vine "
                        + id + " at " + p.x() + "," + p.y() + "," + p.z());
            }
        }
        Set<String> defined = packBlockIds();
        for (String id : REMOVED_VINES) {
            assertFalse(defined.contains("betterend:" + id), "betterend:" + id
                    + " is still defined by the pack - delete the block, not only the row");
        }
    }

    /**
     * The bill for deleting vines is not the other hanging plants: filalux, magnula and rubinea draw
     * from the same {@code weeping_vines} pool, {@code amaranita_fur} shares blue_vine_fur's
     * {@code tripwire} pool, and all of them live in the same two files - a too-wide edit is the
     * failure mode to catch. filalux is checked as a ROW the flora layer still writes; the rest only
     * have to still exist in the pack (nothing in this layer plants them).
     */
    @Test
    void theHangingPlantsThatStayAreNotCollateral() throws IOException {
        Row filalux = Flora.ROWS.stream().filter(r -> r.name().equals("filalux")).findFirst()
                .orElseThrow(() -> new AssertionError("the filalux sky row disappeared with the vines"));
        assertEquals(Engine.SKY, filalux.engine(), "filalux must stay a SKY row");
        assertEquals(List.of("filalux", "filalux_lantern", "filalux_wings"), filalux.ids(),
                "filalux's three ids are the lantern and its wings - dropping one leaves a broken cap");
        assertEquals(Set.of(BiomeSurface.LANTERN_WOODS), filalux.hosts(),
                "filalux is lantern_woods' sky plant and lost its host");
        Set<String> defined = packBlockIds();
        for (String id : List.of("filalux", "filalux_lantern", "filalux_wings",
                "magnula", "rubinea", "amaranita_fur")) {
            assertTrue(defined.contains("betterend:" + id),
                    "betterend:" + id + " was removed along with the vines");
        }
    }

    // --- T10 the crops the table plans, and the pack that must define them ---------------------

    /**
     * One crop the flora layer plants: the {@link Flora} row, the seed block that row writes, the
     * food a ripe plant drops, and the soil its {@code bush_block} accepts.
     */
    private record Crop(String row, String seed, String food, String soil) {
    }

    /**
     * The four {@code PottableCropBlock} rows. Spelled out rather than derived from {@link Flora#ROWS}:
     * three of them shipped with no block definition at all (the server logged {@code 43/46}, one
     * "no BlockDefinition" per seed, and the palette dropped the crop from its biome), and the
     * cheapest wrong fix is to delete the row - the table would then close against the pack while the
     * crop stops growing anywhere. A red here means the table lost a crop; a red in
     * {@link #theRestoredSeedCropsAreDefinedByThePack} means the pack lost the block.
     */
    private static final List<Crop> CROPS = List.of(
            new Crop("amber_root", "amber_root_seed", "betterend:amber_root_raw",
                    "betterend:amber_moss"),
            new Crop("blossom_berry", "blossom_berry_seed", "betterend:blossom_berry",
                    "betterend:pink_moss"),
            new Crop("chorus_mushroom", "chorus_mushroom_seed", "betterend:chorus_mushroom_raw",
                    "betterend:chorus_nylium"));

    /**
     * The reported failure, pinned on both sides. The row has to still be in the table AND the id it
     * writes has to be a block, an item and a translated name in the pack: {@code FloraPlanner} plans
     * the block either way, so only the pack side can catch a missing definition - and only a live
     * server ever noticed, one log line per row, with the crop quietly gone from its biome.
     */
    @Test
    void theRestoredSeedCropsAreDefinedByThePack() throws IOException {
        Set<String> planned = Flora.ROWS.stream()
                .filter(row -> row.props().contains(Flora.Prop.AGE3))
                .map(row -> row.ids().get(0))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("amber_root_seed", "blossom_berry_seed", "chorus_mushroom_seed",
                        "shadow_berry"), planned,
                "the AGE3 rows are the four crops; deleting one is not how a missing block is fixed");

        Catalogue pack = catalogue();
        Set<String> en = translations("en_us");
        Set<String> zh = translations("zh_cn");
        for (Crop crop : CROPS) {
            String id = "betterend:" + crop.seed();
            Map<String, Object> block = pack.blocks().get(id);
            assertNotNull(block, crop.row() + " plans " + id + ", which no blocks/*.yml defines -"
                    + " CraftEngine logs 'no BlockDefinition' and disables the row for good");
            Map<String, Object> item = pack.items().get(id);
            assertNotNull(item, id + " has no item definition, so the seed cannot be held or sown");
            Map<String, Object> sows = behaviour(item, "block_item");
            assertNotNull(sows, id + " is a seed: its item has to be a block_item");
            assertEquals(id, String.valueOf(sows.get("block")), id + " item must place its block");
            List<String> keys = itemNameKeys(item);
            assertFalse(keys.isEmpty(), id + " names itself with no <lang:...> key");
            for (String key : keys) {
                assertTrue(en.contains(key), "en_us is missing " + key);
                assertTrue(zh.contains(key), "zh_cn is missing " + key);
            }
        }
    }

    /**
     * Every crop row must declare all four ages, and every stage must name a model the pack ships.
     * {@code SinglePlantFeature.java:50-52} stamps {@code age=3} at generation, so a block without
     * that value is dropped by the palette - while a missing 0..2 makes a sown crop jump straight to
     * ripe, and a variant whose model json is absent loads as nothing at all. The three legacy ages
     * are pinned by key, not by count, so "four stages" cannot be met by three stages plus a typo.
     */
    @Test
    void everyCropRowShipsFourAgesWithModelsThatExist() throws IOException {
        Catalogue pack = catalogue();
        for (Row row : Flora.ROWS) {
            if (!row.props().contains(Flora.Prop.AGE3)) {
                continue;
            }
            String id = "betterend:" + row.ids().get(0);
            Map<String, Object> block = pack.blocks().get(id);
            assertNotNull(block, row.name() + ": no block definition for " + id);
            Set<String> ages = propertyValues(block, "age");
            for (String age : List.of("0", "1", "2", "3")) {
                assertTrue(ages.contains(age), row.name() + ": " + id + " declares no age=" + age
                        + " (declared " + ages + "); worldgen stamps 3 and the rest are growth");
            }
            Map<String, Object> variants = sub(block, "state", "variants");
            assertEquals(Set.of("age=0", "age=1", "age=2", "age=3"), variants.keySet(),
                    row.name() + ": a four-age crop has exactly these variants");
            Map<String, Object> appearances = sub(block, "state", "appearances");
            for (Map.Entry<String, Object> variant : variants.entrySet()) {
                assertTrue(variant.getValue() instanceof Map<?, ?>,
                        row.name() + " " + variant.getKey() + " must be a mapping");
                Map<String, Object> choice = asMap((Map<?, ?>) variant.getValue());
                Object named = appearances.get(String.valueOf(choice.get("appearance")));
                assertNotNull(named, row.name() + " " + variant.getKey() + " points at appearance '"
                        + choice.get("appearance") + "', which the block does not declare");
                assertTrue(named instanceof Map<?, ?>, row.name() + " " + variant.getKey()
                        + " names an appearance that is not a mapping");
                List<String> models = models(asMap((Map<?, ?>) named));
                assertFalse(models.isEmpty(), row.name() + " " + variant.getKey() + " renders nothing");
                for (String model : models) {
                    assertModelIsShipped(model);
                }
            }
        }
    }

    /**
     * The three halves of a working crop besides its state: what it may be sown on, that it grows on
     * its own, and that harvesting it returns both the food and a seed. A wrong soil is invisible
     * until a player tries to plant one; a missing {@code crop_block} leaves an inert block; a
     * {@code seed_crop} whose {@code crop_item} names nothing drops an empty hand.
     */
    @Test
    void everyCropSowsOnItsOwnSoilAndDropsBothHalves() throws IOException {
        Catalogue pack = catalogue();
        for (Crop crop : CROPS) {
            String id = "betterend:" + crop.seed();
            Map<String, Object> block = pack.blocks().get(id);
            assertNotNull(block, id + " has no block definition");
            Map<String, Object> bush = behaviour(block, "bush_block");
            assertNotNull(bush, id + " must be a bush_block, or nothing constrains its soil");
            assertEquals(List.of(crop.soil()), bush.get("bottom_blocks"),
                    id + " must be sown on " + crop.soil() + " and nothing else");
            assertNotNull(behaviour(block, "crop_block"), id + " has no crop_block, so it never grows");
            assertEquals("true", String.valueOf(sub(block, "settings", "merges").get("is_randomly_ticking")),
                    id + " must be randomly ticking, or a sown seed never advances");
            Map<String, Object> loot = sub(block, "loot");
            assertEquals("default:loot_table/seed_crop", String.valueOf(loot.get("template")),
                    id + " must drop through the seed_crop table");
            Map<String, Object> arguments = sub(loot, "arguments");
            assertEquals(crop.food(), String.valueOf(arguments.get("crop_item")), id + " crop_item");
            assertEquals(id, String.valueOf(arguments.get("crop_seed")), id + " crop_seed");
            assertEquals("3", String.valueOf(arguments.get("ripe_age")),
                    id + " ripens at the age worldgen stamps");
            assertTrue(pack.items().containsKey(crop.food()), id + " drops " + crop.food()
                    + ", which no items section defines");
        }
    }

    /**
     * {@code VisualBlockStateAllocator} hands every {@code auto_state} appearance one real vanilla
     * state out of a pool this pack shares with the packs installed beside it (FarmersDelight,
     * EndsDelight, BrewinAndChewin).
     *
     * <p>A group's real capacity is NOT its line count in {@code mappings.yml}: a line written as
     * {@code weeping_vines[age=N]} omits {@code berries}, so one line donates both berry states
     * (25 x 2 = 50). Counting lines is what made an earlier version of this test believe a reported
     * cap of 50 was impossible.
     *
     * <p>Even with the correct capacity, the crops could not stay on a vine pool: a live server
     * reported {@code weeping_vines} at its 50 cap, and after three appearances moved to
     * {@code twisting_vines} it reported that one at 50 as well. Both are small pools shared with
     * packs and cached visual states this plugin cannot see. All twelve crop ages therefore ride
     * {@code note_block} (1300 states; the carrier CraftEngine's own default_assets uses for
     * arbitrary custom blocks). The tripwire family remains the one a "just reuse the old state"
     * edit would restore, and it fails the load outright.
     */
    @Test
    void theCropStatesStayOffTheExhaustedVinePools() throws IOException {
        Catalogue pack = catalogue();
        int claimed = 0;
        int onTwisting = 0;
        for (Map<String, Object> definition : pack.blocks().values()) {
            Map<String, Object> state = sub(definition, "state");
            // Plain blocks such as umbrella_moss put auto_state directly on state.
            if ("weeping_vines".equals(donor(state))) {
                claimed++;
            }
            if ("twisting_vines".equals(donor(state))) {
                onTwisting++;
            }
            for (Object appearance : sub(state, "appearances").values()) {
                if (appearance instanceof Map<?, ?> body) {
                    if ("weeping_vines".equals(donor(asMap(body)))) {
                        claimed++;
                    }
                    if ("twisting_vines".equals(donor(asMap(body)))) {
                        onTwisting++;
                    }
                }
            }
        }
        assertTrue(claimed <= WEEPING_VINES_POOL, "the pack now claims " + claimed
                + " weeping_vines states but the pool holds " + WEEPING_VINES_POOL
                + "; the load fails on the first state past the end");
        assertTrue(onTwisting <= TWISTING_VINES_POOL, "the pack now claims " + onTwisting
                + " twisting_vines states but the pool holds " + TWISTING_VINES_POOL);

        int fromCrops = 0;
        for (Crop crop : CROPS) {
            String id = "betterend:" + crop.seed();
            Map<String, Object> planted = pack.blocks().get(id);
            assertNotNull(planted, id + " has no block definition");
            Map<String, Object> appearances = sub(planted, "state", "appearances");
            assertEquals(4, appearances.size(), id + ": one appearance per age, no more");
            for (Map.Entry<String, Object> appearance : appearances.entrySet()) {
                Object raw = appearance.getValue();
                assertTrue(raw instanceof Map<?, ?>, id + " " + appearance.getKey()
                        + " must be a mapping of state/model, not a bare donor name");
                String borrowed = donor(asMap((Map<?, ?>) raw));
                // Every age rides note_block: the vine pools the crops used to share are reported at
                // their 50 cap on a live server (both weeping_vines and, after moving three entries
                // there, twisting_vines). note_block donates 1300 states and is the carrier
                // CraftEngine's own default_assets uses for arbitrary custom blocks.
                assertEquals("note_block", borrowed, id + " " + appearance.getKey()
                        + " must take a note_block state; the vine and tripwire families are the"
                        + " exhausted ones, and reusing a deferred seed's old state fails the load");
                fromCrops++;
            }
        }
        assertEquals(CROPS.size() * 4, fromCrops, "three crops x four ages, one state each");
        // onTwisting counts the WHOLE pack, so the four amaranita states on twisting_vines are
        // expected; what must be zero is any crop age landing there.
        assertEquals(4, onTwisting, "only the four amaranita states may draw twisting_vines");
        assertTrue(claimed <= WEEPING_VINES_POOL, "the pack claims " + claimed
                + " weeping_vines states but the pool holds " + WEEPING_VINES_POOL);
        assertTrue(onTwisting <= TWISTING_VINES_POOL, "the pack claims " + onTwisting
                + " twisting_vines states but the pool holds " + TWISTING_VINES_POOL);
    }

    /**
     * The whole table, closed against the pack: every id a row writes must be a block, and every
     * property value it emits must be one that block declares. This is the assertion the reported
     * failure needed - {@link Flora#ROWS} and {@code blocks/*.yml} are two halves of one contract,
     * and a row naming a block nobody defines is invisible to every other test in this file because
     * the planner keeps planning it; only CraftEngine notices, on a live server, one line per row.
     */
    @Test
    void everyFloraRowIsClosedByTheShippedPack() throws IOException {
        Catalogue pack = catalogue();
        for (Row row : Flora.ROWS) {
            assertEquals(row.ids().size(), row.props().size(),
                    row.name() + ": ids and props are read in parallel, index i to id i");
            for (int i = 0; i < row.ids().size(); i++) {
                String id = "betterend:" + row.ids().get(i);
                Map<String, Object> block = pack.blocks().get(id);
                assertNotNull(block, row.name() + " plans " + id + ", which no blocks/*.yml defines");
                Flora.Prop prop = row.props().get(i);
                if (prop == Flora.Prop.NONE) {
                    continue;
                }
                Set<String> declared = propertyValues(block, prop.property());
                for (String value : prop.stateValues()) {
                    assertTrue(declared.contains(value), row.name() + " writes " + id + " "
                            + prop.property() + "=" + value + ", but the block declares "
                            + prop.property() + "=" + declared);
                }
            }
            for (Flora.SoilSet soil : List.of(row.soil(), row.soilTall())) {
                for (String id : soil.blocks()) {
                    assertTrue(pack.blocks().containsKey(id), row.name() + " accepts " + id
                            + " as soil, but no blocks/*.yml defines it");
                }
            }
        }
    }

    /** {@code weeping_vines}' state pool; the hanging plants already hold 10 of them. */
    private static final int WEEPING_VINES_POOL = 50;
    /** {@code mappings.yml}: twisting_vines is written like weeping_vines -- 25 lines x 2 berries. */
    private static final int TWISTING_VINES_POOL = 50;

    // --- the shipped pack, parsed --------------------------------------------------------------

    private static final String BLOCKS_DIR =
            "plugin/src/main/resources/craftengine/betterend/configuration/blocks";
    private static final String LANGS_DIR =
            "plugin/src/main/resources/craftengine/betterend/configuration/langs";
    private static final String RESOURCEPACK =
            "plugin/src/main/resources/craftengine/betterend/resourcepack";

    /** The shipped {@code blocks/*.yml} contents: block id to definition, item id to definition. */
    private record Catalogue(Map<String, Map<String, Object>> blocks,
                             Map<String, Map<String, Object>> items) {
    }

    /**
     * Parses every {@code blocks/*.yml} through the project's own loader ({@link SafeYaml}, so
     * SafeConstructor and duplicate keys refused), expanding config factories instance by instance.
     * The expansion is not optional: {@code stone_sets} alone generates the seven stone families the
     * soil sets name, so a plain {@code blocks:} scan reports them missing and the closure test would
     * be a false red - the "factory exception" has to be resolved, not filtered out.
     */
    private static Catalogue catalogue() throws IOException {
        Path dir = locate(BLOCKS_DIR);
        Map<String, Map<String, Object>> blocks = new LinkedHashMap<>();
        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : list.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                for (Map.Entry<String, Object> top : SafeYaml.load(file).entrySet()) {
                    if (!(top.getValue() instanceof Map<?, ?> raw)) {
                        continue; // pack.yml's scalars and the like: no section here
                    }
                    Map<String, Object> section = asMap(raw);
                    if (isSection(top.getKey(), "block")) {
                        collect(section, blocks);
                    } else if (isSection(top.getKey(), "item")) {
                        collect(section, items);
                    } else if (top.getKey().startsWith("config_factory#")) {
                        expand(file, section, blocks, items);
                    }
                }
            }
        }
        assertTrue(blocks.size() > 100, "only " + blocks.size() + " block ids parsed from " + dir
                + " - the pack root resolved wrong and every absence check would pass for the"
                + " wrong reason");
        return new Catalogue(blocks, items);
    }

    private static boolean isSection(String key, String name) {
        return key.equals(name) || key.equals(name + "s")
                || key.startsWith(name + "#") || key.startsWith(name + "s#");
    }

    private static void collect(Map<String, Object> section,
                                Map<String, Map<String, Object>> into) {
        for (Map.Entry<String, Object> entry : section.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> definition) {
                into.put(entry.getKey(), asMap(definition));
            }
        }
    }

    /** One {@code instances:} x {@code blueprint:} expansion, with {@code ${name}} substituted. */
    private static void expand(Path file, Map<String, Object> factory,
                               Map<String, Map<String, Object>> blocks,
                               Map<String, Map<String, Object>> items) {
        if (!(factory.get("instances") instanceof List<?> instances)
                || !(factory.get("blueprint") instanceof Map<?, ?> blueprint)) {
            return; // not the instances/blueprint shape; it owns no ids this scan can name
        }
        for (Object instance : instances) {
            if (!(instance instanceof Map<?, ?> arguments)) {
                throw new AssertionError(file + ": factory instance is not a mapping: " + instance);
            }
            for (Map.Entry<String, Object> top : substitute(blueprint, asMap(arguments), file).entrySet()) {
                if (!(top.getValue() instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> section = asMap(raw);
                if (isSection(top.getKey(), "block")) {
                    collect(section, blocks);
                } else if (isSection(top.getKey(), "item")) {
                    collect(section, items);
                }
            }
        }
    }

    private static Map<String, Object> substitute(Map<?, ?> node, Map<String, Object> arguments,
                                                  Path file) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            out.put(fill(String.valueOf(entry.getKey()), arguments, file),
                    filled(entry.getValue(), arguments, file));
        }
        return out;
    }

    private static Object filled(Object node, Map<String, Object> arguments, Path file) {
        if (node instanceof Map<?, ?> map) {
            return substitute(map, arguments, file);
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(filled(element, arguments, file));
            }
            return out;
        }
        return node instanceof String text ? fill(text, arguments, file) : node;
    }

    /** Replaces {@code ${name}}; a placeholder the instance does not fill is a pack bug, not a skip. */
    private static String fill(String text, Map<String, Object> arguments, Path file) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!arguments.containsKey(name)) {
                throw new AssertionError(file + ": placeholder ${" + name
                        + "} has no value in the instance for \"" + text + "\"");
            }
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(String.valueOf(arguments.get(name))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern LANG_KEY = Pattern.compile("<lang:([^>]+)>");

    /** Every value a block's property declares, by its {@code type:}; empty when it has none. */
    private static Set<String> propertyValues(Map<String, Object> block, String property) {
        Object declaration = sub(block, "state", "properties").get(property);
        if (!(declaration instanceof Map<?, ?> raw)) {
            return Set.of();
        }
        Map<String, Object> declared = asMap(raw);
        if (declared.get("values") instanceof List<?> values) {
            Set<String> out = new LinkedHashSet<>();
            values.forEach(value -> out.add(String.valueOf(value)));
            return out;
        }
        String type = String.valueOf(declared.get("type"));
        switch (type) {
            case "int": {
                int min = ((Number) declared.getOrDefault("min", 0)).intValue();
                int max = ((Number) declared.getOrDefault("max", 0)).intValue();
                Set<String> out = new LinkedHashSet<>();
                for (int value = min; value <= max; value++) {
                    out.add(String.valueOf(value));
                }
                return out;
            }
            case "6-direction":
                return Set.of("north", "east", "south", "west", "up", "down");
            case "4-direction":
            case "horizontal_direction":
                return Set.of("north", "east", "south", "west");
            case "double_block_half":
                return Set.of("lower", "upper");
            case "boolean":
                return Set.of("true", "false");
            case "axis":
                return Set.of("x", "y", "z");
            default:
                throw new AssertionError("unknown property type '" + type + "' for " + property
                        + "; teach propertyValues() its value set rather than skipping the check");
        }
    }

    /** Every behaviour a definition declares; CraftEngine accepts one mapping or a list of them. */
    private static Map<String, Object> behaviour(Map<String, Object> definition, String type) {
        Object raw = definition.get("behavior");
        List<Object> declared = new ArrayList<>();
        if (raw instanceof Map<?, ?> one) {
            declared.add(one);
        } else if (raw instanceof List<?> list) {
            declared.addAll(list);
        }
        for (Object candidate : declared) {
            if (candidate instanceof Map<?, ?> mapping
                    && type.equals(String.valueOf(asMap(mapping).get("type")))) {
                return asMap(mapping);
            }
        }
        return null;
    }

    /** Every model an appearance draws; the pack uses both {@code model:} and {@code models:}. */
    private static List<String> models(Map<String, Object> appearance) {
        if (appearance.get("models") instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object entry : list) {
                out.add(entry instanceof Map<?, ?> weighted
                        ? String.valueOf(asMap(weighted).get("path")) : String.valueOf(entry));
            }
            return out;
        }
        return appearance.get("model") instanceof String model ? List.of(model) : List.of();
    }

    /**
     * The vanilla state an appearance borrows: one full blockstate string for {@code auto_state},
     * the same for the explicit {@code state:}. Null when the appearance only draws its own model.
     */
    private static String donor(Map<String, Object> appearance) {
        Object auto = appearance.get("auto_state");
        if (auto != null) {
            return String.valueOf(auto);
        }
        Object state = appearance.get("state");
        return state == null ? null : String.valueOf(state);
    }

    /** The {@code <lang:...>} keys an item's {@code data.item_name} renders its name through. */
    private static List<String> itemNameKeys(Map<String, Object> item) {
        Object name = sub(item, "data").get("item_name");
        if (name == null) {
            return List.of();
        }
        Matcher matcher = LANG_KEY.matcher(String.valueOf(name));
        List<String> keys = new ArrayList<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /** Every translation key the shipped {@code langs/*.yml} files carry for one locale. */
    private static Set<String> translations(String locale) throws IOException {
        Path dir = locate(LANGS_DIR);
        Set<String> keys = new HashSet<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : list.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                for (Map.Entry<String, Object> section : SafeYaml.load(file).entrySet()) {
                    if (!section.getKey().startsWith("lang#")
                            || !(section.getValue() instanceof Map<?, ?> locales)) {
                        continue;
                    }
                    if (asMap(locales).get(locale) instanceof Map<?, ?> table) {
                        keys.addAll(asMap(table).keySet());
                    }
                }
            }
        }
        assertTrue(keys.size() > 100, "only " + keys.size() + " " + locale
                + " keys parsed from " + dir + " - a missing translation would go unnoticed");
        return keys;
    }

    /** Fails when the pack does not ship the model json an appearance names. */
    private static void assertModelIsShipped(String model) {
        String[] split = model.split(":", 2);
        String namespace = split.length == 2 ? split[0] : "minecraft";
        String path = split.length == 2 ? split[1] : model;
        String relative = RESOURCEPACK + "/assets/" + namespace + "/models/" + path + ".json";
        Path file = find(relative);
        assertNotNull(file, model + " is not shipped (no " + relative
                + "); the state loads with a missing model and renders nothing");
    }

    /** The nested mapping at {@code path}, or an empty one - never null, so reads chain. */
    private static Map<String, Object> sub(Map<String, Object> node, String... path) {
        Map<String, Object> at = node;
        for (String step : path) {
            if (!(at.get(step) instanceof Map<?, ?> next)) {
                return Map.of();
            }
            at = asMap(next);
        }
        return at;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw; // SafeYaml normalises every key to String
    }

    /** Every block id the shipped CraftEngine pack defines, factories expanded - see {@link #catalogue}. */
    private static Set<String> packBlockIds() throws IOException {
        return new HashSet<>(catalogue().blocks().keySet());
    }

    /** Walk up from the working dir until the repo-relative path is underfoot (tests run per module). */
    private static Path locate(String relative) {
        Path found = find(relative);
        if (found == null) {
            throw new IllegalStateException(relative + " not found from "
                    + Path.of("").toAbsolutePath());
        }
        return found;
    }

    private static Path find(String relative) {
        Path at = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && at != null; i++, at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A diagnostic, not an assertion: the per-chunk cost the report quotes. */
    @Test
    void reportTheMeasuredCost() {
        Map<BiomeSurface, int[]> perBiome = new HashMap<>();
        for (Chunk chunk : SAMPLE.chunks) {
            if (chunk.biome() == null) {
                continue;
            }
            int[] acc = perBiome.computeIfAbsent(chunk.biome(), b -> new int[2]);
            acc[0]++;
            acc[1] += chunk.blocks().size();
        }
        StringBuilder out = new StringBuilder("flora writes per land chunk, seed " + SEED + ":\n");
        perBiome.entrySet().stream()
                .sorted((a, b) -> Double.compare((double) b.getValue()[1] / b.getValue()[0],
                        (double) a.getValue()[1] / a.getValue()[0]))
                .forEach(e -> out.append(String.format("  %-22s %5.1f  (%d chunks)%n",
                        e.getKey().id(), (double) e.getValue()[1] / e.getValue()[0],
                        e.getValue()[0])));
        out.append("rows that fired, blocks over ").append(SAMPLE.chunks.size())
                .append(" land chunks:\n");
        Map<String, Integer> perRow = new HashMap<>();
        for (Chunk chunk : SAMPLE.chunks) {
            chunk.placements().forEach((name, n) -> perRow.merge(name, n, Integer::sum));
        }
        perRow.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.append(String.format("  %-26s %6d%n", e.getKey(), e.getValue())));
        System.out.println(out);
        assertFalse(perBiome.isEmpty(), "the sample holds no biome-attributable chunk");
    }
}
