package dev.mumu.betterend;

import dev.mumu.betterend.terrain.IslandField;
import dev.mumu.betterend.vanilla.EndSpikes;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import dev.mumu.betterend.config.BetterEndConfig;
import org.bukkit.generator.LimitedRegion;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.biome.BiomePlacement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BetterEndPopulator#repairCore} - the fix for "where the obsidian pillars meet the end stone
 * it is not filled in". Vanilla's EndSpikeFeature clears the non-obsidian remainder of its square
 * footprint to air, which is free on the vanilla island (top y~63) and deletes real end stone on
 * ours (y=66..90 on the spike ring).
 * <p>
 * No server: {@code Material.END_STONE.createBlockData()} needs one, so the block argument is passed
 * as null and {@code repairCore} treats it as opaque. The region is one {@link Proxy} over
 * {@link LimitedRegion} backed by a map - fewer lines than a hand-written stub, and no new
 * dependency.
 */
class BetterEndPopulatorTest {
    private static final int MAX_Y = 127;

    private static final long SEED = 42L;

    /**
     * 原版十根柱位里的一根。几何柱心保护只认「种子算出来的这十根」，所以用例必须站在真的柱位上：
     * 拿 (0,0) 当柱心曾经能过，是因为旧实现靠「这一列有没有黑曜石」猜，而那是错的判据。
     */
    private static EndSpikes.Spike spike() {
        return EndSpikes.forSeed(SEED).get(0);
    }

    /**
     * Only the carved columns come back, and only where the terrain says solid. A version that
     * fills the whole footprint writes into the void columns and overshoots; one that fills to a
     * fixed Y undershoots.
     */
    @Test
    void repairFillsExactlyTheCarve() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = spike();
        FakeRegion region = new FakeRegion(field, spike.centerX() >> 4, spike.centerZ() >> 4);
        Set<Long> carved = carveSpikeFootprint(region, field, spike);

        int filled = BetterEndPopulator.repairCore(region.region, field, null, SEED,
                spike.centerX() >> 4, spike.centerZ() >> 4, MAX_Y);

        Set<Long> expected = solidPart(field, carved);
        assertEquals(expected, new HashSet<>(region.written), "repaired blocks are not the carve");
        assertEquals(expected.size(), filled, "reported fill count disagrees with the writes");
    }

    /** Restarts and the nine-fold overlap between neighbouring chunks' rings must both be free. */
    @Test
    void repairIsIdempotent() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = spike();
        int chunkX = spike.centerX() >> 4;
        int chunkZ = spike.centerZ() >> 4;
        FakeRegion region = new FakeRegion(field, chunkX, chunkZ);
        carveSpikeFootprint(region, field, spike);
        BetterEndPopulator.repairCore(region.region, field, null, SEED, chunkX, chunkZ, MAX_Y);
        int first = region.written.size();
        assertTrue(first > 0, "nothing was repaired on the first pass");

        assertEquals(0, BetterEndPopulator.repairCore(region.region, field, null, SEED,
                        chunkX, chunkZ, MAX_Y),
                "the second pass wrote blocks again");
        assertEquals(first, region.written.size(), "the second pass changed the write set");
    }

    /**
     * The single most dangerous wrong implementation: "write END_STONE wherever the field says
     * solid" deletes the pillar it was meant to meet, and the dragon fight reads those pillars.
     */
    @Test
    void repairNeverOverwritesThePillar() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = spike();
        int cx = spike.centerX();
        int cz = spike.centerZ();
        int r = spike.radius();
        FakeRegion region = new FakeRegion(field, cx >> 4, cz >> 4);
        Set<Long> carved = carveSpikeFootprint(region, field, spike);
        Set<Long> circle = new HashSet<>();
        // The shaft: vanilla's own circle, over the whole height the repair scans.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                for (int y = 60; y <= MAX_Y; y++) {
                    long k = key(cx + dx, y, cz + dz);
                    region.blocks.put(k, Material.OBSIDIAN);
                    circle.add(k);
                }
            }
        }

        BetterEndPopulator.repairCore(region.region, field, null, SEED, cx >> 4, cz >> 4, MAX_Y);

        for (long k : region.written) {
            assertTrue(!circle.contains(k), "the repair overwrote the pillar at "
                    + x(k) + "," + y(k) + "," + z(k));
        }
        assertEquals(solidPart(field, carved), new HashSet<>(region.written),
                "the repair wrote something other than the carve");
    }

    /**
     * A version keyed on "air below the vanilla island top" instead of on {@link IslandField} builds
     * a pillar of end stone in the void. (100, 0) is the vanilla arrival-platform column, and it is
     * void on every seed measured.
     */
    @Test
    void repairNeverFillsTheVoid() {
        IslandField field = new IslandField(SEED);
        assertEquals(Integer.MIN_VALUE, field.topY(100, 0, 0, MAX_Y), "(100,0) is not void any more");
        FakeRegion region = new FakeRegion(field, 6, 0);
        for (int y = 60; y <= MAX_Y; y++) {
            region.blocks.put(key(100, y, 0), Material.AIR);
        }
        assertEquals(0, BetterEndPopulator.repairCore(region.region, field, null, SEED, 6, 0, MAX_Y),
                "the repair built terrain in the void");
    }

    /**
     * Outside the core the surface is a BetterEnd biome's, not plain end stone, and nothing carves
     * it. A missing {@code inCoreQuart} guard would rewrite dust-wastelands surfaces world-wide.
     */
    @Test
    void repairStaysInsideTheCore() {
        IslandField field = new IslandField(SEED);
        int chunkX = 1000 >> 4;
        FakeRegion region = new FakeRegion(field, chunkX, 0);
        for (int y = 60; y <= MAX_Y; y++) {
            region.blocks.put(key(1000, y, 0), Material.AIR);
        }
        assertEquals(0, BetterEndPopulator.repairCore(region.region, field, null, SEED,
                        chunkX, 0, MAX_Y),
                "the repair reached outside VanillaEndCore.CORE_RADIUS");
    }

    /**
     * A spike centred in one chunk carves into its neighbours, and a neighbour may already have been
     * populated. The +-1 ring is what makes the pass order-independent.
     */
    @Test
    void repairReachesTheNeighbourChunk() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = spike();
        // 柱心所在区块的隔壁：region 站在这边，凹痕在那边。
        int chunkX = (spike.centerX() >> 4) - 1;
        int chunkZ = spike.centerZ() >> 4;
        FakeRegion region = new FakeRegion(field, chunkX, chunkZ);
        Set<Long> carved = carveSpikeFootprint(region, field, spike);

        BetterEndPopulator.repairCore(region.region, field, null, SEED, chunkX, chunkZ, MAX_Y);

        assertEquals(solidPart(field, carved), new HashSet<>(region.written),
                "the repair did not reach into the neighbouring chunk");
    }

    /**
     * The dragon fight, on a world whose island is taller than the spike. Vanilla caps the obsidian
     * shaft with bedrock and puts the healing crystal on top; if the island is taller than that cap,
     * the column above the cap reads solid to {@link IslandField} and a repair that trusted the
     * terrain would fill the crystal in. The guard has to be geometric (the seed's spike circles),
     * which is also what makes it independent of what neighbouring chunks have written so far.
     *
     * <p>Checked on every one of the ten spikes, because "the centre column" is not special: the
     * whole circle belongs to the pillar.
     */
    @Test
    void repairNeverBuriesTheCrystalSeat() {
        IslandField field = new IslandField(SEED);
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            int cx = spike.centerX();
            int cz = spike.centerZ();
            int r = spike.radius();
            FakeRegion region = new FakeRegion(field, cx >> 4, cz >> 4);
            int top = field.topY(cx, cz, 0, MAX_Y);
            int cap = top - 2; // a spike whose bedrock cap sits BELOW our surface
            assertTrue(cap > 60, "the island is too low to model a short spike at " + cx + "," + cz);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    for (int y = 60; y < cap; y++) {
                        region.blocks.put(key(cx + dx, y, cz + dz), Material.OBSIDIAN);
                    }
                    region.blocks.put(key(cx + dx, cap, cz + dz), Material.BEDROCK);
                    for (int y = cap + 1; y <= MAX_Y; y++) {
                        region.blocks.put(key(cx + dx, y, cz + dz), Material.AIR);
                    }
                }
            }

            BetterEndPopulator.repairCore(region.region, field, null, SEED, cx >> 4, cz >> 4, MAX_Y);

            for (long k : region.written) {
                int dx = x(k) - cx;
                int dz = z(k) - cz;
                assertTrue(dx * dx + dz * dz > r * r + 1,
                        "the repair filled the crystal's own circle at " + x(k) + "," + y(k) + ","
                                + z(k) + " (spike " + cx + "," + cz + ")");
            }
        }
    }

    /**
     * {@link IslandField#anySolid} is the cost gate, and it must be exact, not a sampling heuristic:
     * a chunk it wrongly calls void is a chunk that never gets repaired. Checked against a per-block
     * scan over the pinned island's rim, where solid and void interleave.
     */
    @Test
    void theVoidGateAgreesWithTheBlockScan() {
        IslandField field = new IslandField(SEED);
        for (int chunkX = -8; chunkX <= 8; chunkX++) {
            for (int chunkZ = -8; chunkZ <= 8; chunkZ += 4) {
                int x0 = (chunkX - 1) << 4;
                int z0 = (chunkZ - 1) << 4;
                int x1 = ((chunkX + 2) << 4) - 1;
                int z1 = ((chunkZ + 2) << 4) - 1;
                boolean scan = false;
                for (int x = x0; x <= x1 && !scan; x++) {
                    for (int z = z0; z <= z1; z++) {
                        if (field.topY(x, z, 60, MAX_Y) != Integer.MIN_VALUE) {
                            scan = true;
                            break;
                        }
                    }
                }
                assertEquals(scan, field.anySolid(x0, z0, x1, z1, 60, MAX_Y),
                        "gate disagrees with the block scan at chunk " + chunkX + "," + chunkZ);
            }
        }
    }

    /**
     * The square footprint minus the pillar's obsidian circle, forced to air from y=66 up - the
     * shape vanilla's air-clear branch leaves behind on an island that is taller than y=65.
     */
    private static Set<Long> carveSpikeFootprint(FakeRegion region, IslandField field,
                                                 EndSpikes.Spike spike) {
        int centerX = spike.centerX();
        int centerZ = spike.centerZ();
        int r = spike.radius();
        int top = field.topY(centerX, centerZ, 0, MAX_Y);
        assertTrue(top > 66, "the island is not above y=66 at " + centerX + "," + centerZ
                + " (topY " + top + ") - there would be nothing for a spike to carve");
        Set<Long> carved = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r * r + 1) continue; // the obsidian circle, not carved
                for (int y = 66; y <= top; y++) {
                    long k = key(centerX + dx, y, centerZ + dz);
                    region.blocks.put(k, Material.AIR);
                    carved.add(k);
                }
            }
        }
        return carved;
    }

    /** The carved blocks the terrain says were real end stone - exactly what must come back. */
    private static Set<Long> solidPart(IslandField field, Set<Long> carved) {
        Set<Long> out = new HashSet<>();
        for (long k : carved) {
            if (field.isSolid(x(k), y(k), z(k))) out.add(k);
        }
        assertTrue(!out.isEmpty(), "the synthetic carve removed no solid terrain");
        return out;
    }

    // --- the fake region ------------------------------------------------------------------------

    // --- the per-biome ore gate ---------------------------------------------------------------

    /**
     * {@code minecraft:the_end} carries only {@code wover:is_end/center} and lists no BetterEnd ore
     * at all, so nothing may generate there -- that is the dragon fight's own island. The port wrote
     * ore into it until the ring test existed, because {@code vanillaSurface}'s filler IS end stone
     * and {@code fillsWithEndStone} therefore says yes.
     */
    @org.junit.jupiter.api.Test
    void noOreGeneratesInTheVanillaCore() {
        BiomePlacement placement = new BiomePlacement(12345L);
        int checked = 0;
        for (int x = -300; x <= 300; x += 17) {
            for (int z = -300; z <= 300; z += 17) {
                if (placement.ring(x, z) != BiomePlacement.Ring.CENTER) {
                    continue;
                }
                checked++;
                assertFalse(BetterEndPopulator.inBiome(placement, null, true, x, 40, z),
                        "thallasium in the vanilla core at " + x + "," + z);
                assertFalse(BetterEndPopulator.inBiome(placement,
                        BetterEndPopulator.AMBER_BIOMES, false, x, 40, z));
            }
        }
        assertTrue(checked > 50, "sample never reached the core: " + checked);
    }

    /**
     * The barrens is the opposite case and the one a naive "no ore in any ring" fix gets wrong:
     * {@code wover/worldgen/biome_modifications/default_ores.json} PREPENDS flavolite, thallasium
     * and ender into every non-betterend biome tagged {@code wover:is_end/barrens}, which
     * {@code minecraft:end_barrens} is. The three per-biome ores are not in that list.
     */
    @org.junit.jupiter.api.Test
    void theBarrensGetsTheThreeDefaultOresAndNoOthers() {
        BiomePlacement placement = new BiomePlacement(12345L);
        int checked = 0;
        for (int x = -1100; x <= 1100; x += 13) {
            for (int z = -1100; z <= 1100; z += 13) {
                if (placement.ring(x, z) != BiomePlacement.Ring.BARRENS) {
                    continue;
                }
                checked++;
                assertTrue(BetterEndPopulator.inBiome(placement, null, true, x, 40, z),
                        "thallasium missing from the barrens at " + x + "," + z);
                assertTrue(BetterEndPopulator.inBiome(placement,
                        BetterEndPopulator.FLAVOLITE_BIOMES, true, x, 40, z),
                        "flavolite missing from the barrens, which the mod injects it into");
                assertFalse(BetterEndPopulator.inBiome(placement,
                        BetterEndPopulator.VIOLECITE_BIOMES, false, x, 40, z),
                        "violecite reached the barrens, which does not list it");
            }
        }
        assertTrue(checked > 50, "sample never reached the barrens: " + checked);
    }

    /**
     * Outside the rings an ore appears only where its own biome json lists it. Flavolite is the one
     * that was actually wrong: the port placed it in all 27 biomes and the mod lists it in two.
     */
    @org.junit.jupiter.api.Test
    void eachOreIsConfinedToTheBiomesThatListIt() {
        BiomePlacement placement = new BiomePlacement(12345L);
        int sampled = 0;
        int flavoliteHits = 0;
        for (int x = 4000; x < 4600; x += 4) {
            for (int z = 4000; z < 4600; z += 4) {
                if (placement.ring(x, z) != null) {
                    continue;
                }
                sampled++;
                BiomeSurface biome = placement.quartAt(x, 40, z);
                assertEquals(BetterEndPopulator.FLAVOLITE_BIOMES.contains(biome),
                        BetterEndPopulator.inBiome(placement,
                                BetterEndPopulator.FLAVOLITE_BIOMES, true, x, 40, z));
                assertEquals(BetterEndPopulator.AMBER_BIOMES.contains(biome),
                        BetterEndPopulator.inBiome(placement,
                                BetterEndPopulator.AMBER_BIOMES, false, x, 40, z));
                assertEquals(BetterEndPopulator.DRAGON_BONE_BIOMES.contains(biome),
                        BetterEndPopulator.inBiome(placement,
                                BetterEndPopulator.DRAGON_BONE_BIOMES, false, x, 40, z));
                // null means every BetterEnd biome, which is what thallasium and ender are.
                assertTrue(BetterEndPopulator.inBiome(placement, null, true, x, 40, z));
                if (BetterEndPopulator.FLAVOLITE_BIOMES.contains(biome)) {
                    flavoliteHits++;
                }
            }
        }
        assertTrue(sampled > 5000, "sample too small: " + sampled);
        // The whole point of the change: flavolite is now a minority of columns, not all of them.
        assertTrue(flavoliteHits > 0, "the sample never hit dust_wastelands or neon_oasis");
        assertTrue(flavoliteHits < sampled / 2,
                "flavolite still covers " + flavoliteHits + "/" + sampled + " columns");
    }

    /** A LimitedRegion whose blocks default to whatever {@link IslandField} says generated there. */
    private static final class FakeRegion {
        final Map<Long, Material> blocks = new HashMap<>();
        final List<Long> written = new ArrayList<>();
        /** 每次写入之前那一格是什么方块，顺序与 {@link #written} 一一对应。 */
        final List<Material> priorTypes = new ArrayList<>();
        final LimitedRegion region;

        FakeRegion(IslandField field, int chunkX, int chunkZ) {
            region = (LimitedRegion) Proxy.newProxyInstance(
                    LimitedRegion.class.getClassLoader(),
                    new Class<?>[]{LimitedRegion.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        // CraftLimitedRegion buffer = 16: this chunk plus the +-1 ring.
                        case "isInRegion" -> Math.abs((((Integer) args[0]) >> 4) - chunkX) <= 1
                                && Math.abs((((Integer) args[2]) >> 4) - chunkZ) <= 1;
                        case "getType" -> {
                            long k = key((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                            yield blocks.computeIfAbsent(k, kk -> field.isSolid(x(kk), y(kk), z(kk))
                                    ? Material.END_STONE : Material.AIR);
                        }
                        case "setBlockData" -> {
                            long k = key((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                            // 写之前的那一格，用来钉住「只写空气」：地形、矿脉、换皮结果都不能被盖掉。
                            Material existing = blocks.get(k);
                            if (existing == null) {
                                existing = field.isSolid(x(k), y(k), z(k))
                                        ? Material.END_STONE : Material.AIR;
                            }
                            priorTypes.add(existing);
                            blocks.put(k, Material.END_STONE);
                            written.add(k);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "FakeRegion";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** 22 bits of x and z, 20 of y - the populator's own packing, ample for these coordinates. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFFL);
    }

    private static int x(long k) {
        return (int) (k >> 42);
    }

    private static int z(long k) {
        return (int) ((k >> 20) & 0x3FFFFF) << 10 >> 10;
    }

    private static int y(long k) {
        return (int) (k & 0xFFFFF);
    }
}
