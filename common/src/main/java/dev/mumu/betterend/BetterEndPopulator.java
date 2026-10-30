package dev.mumu.betterend;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.terrain.IslandField;
import dev.mumu.betterend.terrain.OpenSimplexNoise;
import dev.mumu.betterend.vanilla.EndSpikes;
import dev.mumu.betterend.vanilla.VanillaEndCore;
import dev.mumu.betterend.vanilla.YungPillars;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dust wastelands underground-ore step -- flavolite layers, thallasium and ender ore -- plus
 * {@link #repairCore}, which puts back the end stone vanilla's obsidian pillars carve out of the
 * island under them.
 * <p>
 * These live in a populator rather than in {@code generateNoise} because a flavolite layer is a
 * flood fill of radius 6 that has to cross chunk borders -- {@code ChunkData.setBlock} outside
 * 0..15 is a silent no-op, which is what turned them into half discs. {@code LimitedRegion} allows
 * writes one chunk out in every direction, and CraftEngine records them there just as it does in
 * the centre chunk (its FEATURE-stage hook injects the sections before the populators run).
 * <p>
 * Placement never reads a block back. A neighbour's populator may or may not have run yet, so a
 * block read is visit-order dependent, and CraftEngine's deceive-material would make it lie in any
 * case; every decision comes from {@link IslandField} and {@link BiomePlacement}, i.e. purely from
 * the seed and the position. The one exception is {@link #repairCore}'s {@code == AIR} test, which
 * is a read of a VANILLA block and is provably honest; see its javadoc.
 * Holds no mutable state: Paper runs this concurrently for many chunks.
 */
public final class BetterEndPopulator extends BlockPopulator {
    /** flavolite_layer.json radius 12.0; violecite_layer.json radius 15.0. Halved, as OreLayerFeature:32. */
    private static final float FLAVOLITE_RADIUS = 12.0f * 0.5f;
    private static final float VIOLECITE_RADIUS = 15.0f * 0.5f;
    /** Both layer jsons give the same y range, so it is shared rather than per-ore. */
    private static final int LAYER_MIN_Y = 16;
    private static final int LAYER_MAX_Y = 128;
    /**
     * OreLayerFeature.java:55 - SDFScale3D(1, 0.2, 1) of the sphere. A static field of the feature
     * class, shared by every ore_layer config, so it is deliberately NOT per-ore.
     */
    private static final float LAYER_SQUASH = 0.2f;

    /**
     * The {@code minecraft:biome} filter each ore feature carries, as the set of BetterEnd biomes
     * whose json lists it. {@code null} means every one of the 27 -- which is true of thallasium and
     * ender, and was wrongly assumed of flavolite until now.
     */
    static final Set<BiomeSurface> FLAVOLITE_BIOMES =
            EnumSet.of(BiomeSurface.DUST_WASTELANDS, BiomeSurface.NEON_OASIS);
    static final Set<BiomeSurface> VIOLECITE_BIOMES =
            EnumSet.of(BiomeSurface.CHORUS_FOREST, BiomeSurface.SHADOW_FOREST);
    static final Set<BiomeSurface> AMBER_BIOMES = EnumSet.of(BiomeSurface.AMBER_LAND);
    static final Set<BiomeSurface> DRAGON_BONE_BIOMES =
            EnumSet.of(BiomeSurface.DRAGON_GRAVEYARDS);

    private final DustWastelandsGenerator generator;
    /** One warp-noise field per world seed; OreLayerFeatureConfig.java:33-38 seeds it from the world seed. */
    private final Map<Long, OpenSimplexNoise> warp = new ConcurrentHashMap<>();

    BetterEndPopulator(DustWastelandsGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion region) {
        long seed = worldInfo.getSeed();
        IslandField field = generator.field(seed);
        // Hoisted: every ore write is additionally gated on the biome filling with plain end stone
        // (DustWastelandsGenerator.fillsWithEndStone), because that is the only block the mod's ore
        // features replace.
        BiomePlacement placement = generator.placement(seed);
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();

        // First, so this chunk's own ore writes below can never be clobbered by it.
        repairCore(region, field, generator.endStone, seed, chunkX, chunkZ, maxY - 1);
        // 原版那十根黑曜石柱换成 YUNG 的模板柱，同时保住原版水晶、基岩座与龙战判定。
        // 必须放在 repairCore 之后：repairCore 回填的是原版柱子从岛体里挖走的那一块，本趟再把原版
        // 柱芯 / 笼子按规则清掉并放上模板；顺序反过来，刚放好的模板会被当成「被挖掉的地形」再填一遍。
        YungPillars.build(region, field, generator.endStone, seed, chunkX, chunkZ);

        // OreLayerFeatureConfig.java:33-38 seeds the warp field from the world seed ALONE, so both
        // layer ores distort through the identical noise. Deliberately not salted per ore.
        OpenSimplexNoise warpNoise = warp.computeIfAbsent(seed, OpenSimplexNoise::new);
        oreLayers(region, field, generator, placement, random, warpNoise, originX, originZ,
                generator.config.flavoliteLayersPerChunk(), FLAVOLITE_RADIUS, generator.flavolite,
                FLAVOLITE_BIOMES, true);
        oreLayers(region, field, generator, placement, random, warpNoise, originX, originZ,
                generator.config.violeciteLayersPerChunk(), VIOLECITE_RADIUS, generator.violecite,
                VIOLECITE_BIOMES, false);

        // thallasium_ore.json: size 8; ender_ore.json: size 4. Both are uniform(above_bottom 8,
        // below_top 8) with in_square. The counts (24 and 12 in the mod) come from config.yml.
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.thallasiumVeinsPerChunk(), 8, generator.thallasiumOre, null, true);
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.enderVeinsPerChunk(), 4, generator.enderOre, null, true);
        // amber_ore.json: size 6, count 60, amber_land only. dragon_bone_ore.json: size 8 and
        // count 24 -- the same as thallasium -- in dragon_graveyards only.
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.amberVeinsPerChunk(), 6, generator.amberOre, AMBER_BIOMES, false);
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.dragonBoneVeinsPerChunk(), 8, generator.dragonBoneOre,
                DRAGON_BONE_BIOMES, false);
    }

    /**
     * Vanilla {@code EndSpikeFeature} clears the non-obsidian remainder of its square footprint to
     * air above y=65 - free in vanilla, where the island tops out at y~63, but our island is at
     * y=66..90 on the spike ring, so it deletes real end stone and leaves the reported gap where the
     * pillar meets the ground. The mod does not have this problem because SpikeFeatureMixin.java:188
     * cancels {@code placeSpike} outright and rebuilds the pillar, base structure included
     * (pillar_base_N.nbt ships {@code minecraft:end_stone}, planted 3 blocks under the surface,
     * :90-91). The port has no mixin, so it repairs afterwards instead.
     * <p>
     * Floor at 60 rather than vanilla's 65: {@code EndSpikeFeature.placeSpike} (:91-92, CFR decompile
     * of the running paper-26.2.jar) clears to air only where {@code pos.getY() > 65}, so 60 carries
     * five blocks of deliberate slack under the real rule. Raise it to 65 if the scan ever shows up
     * in a profile.
     */
    private static final int REPAIR_FLOOR = 60;

    /**
     * Re-asserts the terrain {@code generateNoise} wrote, wherever a vanilla decoration carved it
     * away inside the vanilla core. Runs over this chunk plus the +-1 ring: that is exactly the
     * radius a feature may write (CraftLimitedRegion buffer = 16), so every chunk repairs everything
     * any neighbour's spike could have carved and the pass needs no cross-chunk state and never has
     * to locate a pillar. Monotone AIR -> END_STONE only where {@link IslandField} says solid, so
     * the nine-fold overlap between neighbours costs nothing and a rerun is a no-op.
     * <p>
     * Order-independent even though it reads blocks: a neighbour's spike either has not decorated
     * yet, in which case its columns still hold intact terrain and fail the {@code == AIR} test, or
     * it has, in which case the carve and the obsidian are both visible. Every interleaving reaches
     * the same final state, and the spike's own chunk repairs it again regardless.
     * <p>
     * Places END STONE, never obsidian: the pillar itself is where vanilla put it, and end stone is
     * byte-identical to what the generator wrote here - inside the core
     * {@code DustWastelandsGenerator.surfaceAt} returns the vanilla surface, plain end stone with no
     * dust cap and no speckle, which is the second reason the {@code inCoreQuart} guard is in the
     * loop and not just an optimisation.
     * <p>
     * ponytail ceiling: outside a pillar's own footprint ({@link #inVanillaPillar}) this restores ANY
     * decoration-carved air inside the core, not just the spike's. Correct while
     * {@code minecraft:the_end}'s feature list is {@code end_spike} and nothing else, which is NOT
     * DETERMINABLE from this checkout. If a the_end feature that legitimately hollows terrain ever
     * appears, narrow this further to columns within {@link VanillaEndCore#SPIKE_MAX_RADIUS} of a
     * column that reads obsidian.
     * <p>
     * ponytail ceiling: the AIR read is honest only while craft-engine's
     * {@code block.deceive-bukkit-material.default} (config.yml:364-370, default "bricks") does not
     * read as air. CraftMagicNumbers.java:165-172 prepopulates BLOCK_MATERIAL for every vanilla
     * block and BukkitBlockManager.java:516-543 only overwrites its own DelegatingBlock keys, so
     * vanilla obsidian, bedrock and air read back truthfully. This is the ONLY block read in the
     * plugin, and it never asks about a CraftEngine block.
     *
     * @return blocks filled, for the test; a second run over the same region must return 0.
     */
    static int repairCore(LimitedRegion region, IslandField field, BlockData endStone, long seed,
                          int chunkX, int chunkZ, int maxY) {
        if (chunkX + 1 < VanillaEndCore.coreChunkMin() || chunkX - 1 > VanillaEndCore.coreChunkMax()
                || chunkZ + 1 < VanillaEndCore.coreChunkMin() || chunkZ - 1 > VanillaEndCore.coreChunkMax()) {
            return 0;
        }
        // Nothing above IslandField.TOP_SOLID_Y can ever be solid, so scanning to the world's build
        // height is ~130 rows per column of guaranteed air.
        maxY = Math.min(maxY, IslandField.TOP_SOLID_Y);
        int x0 = (chunkX - 1) << 4;
        int z0 = (chunkZ - 1) << 4;
        int x1 = ((chunkX + 2) << 4) - 1;
        int z1 = ((chunkZ + 2) << 4) - 1;
        // The core is a 384-block disc - 2401 chunks, of which the pinned island puts terrain in
        // 124. Without this gate the other ~2280 each pay a 2304-column scan to discover they are
        // void: measured over the whole core, +730% on generateNoise's own cost, against +37% with
        // it. The gate is exact, not a sample - see IslandField.anySolid.
        if (!field.anySolid(x0, z0, x1, z1, REPAIR_FLOOR, maxY)) return 0;
        // ONE column for the whole pass, filled in place by densityColumnInto: a chunk that does hold
        // terrain used to allocate one of these per column, up to 2304 per call for the +-1 ring. The
        // length is densityColumn's own expression after the clamp above, so the fill loop's walk
        // over column.length still covers exactly [REPAIR_FLOOR, maxY], and an inverted range still
        // throws the NegativeArraySizeException it always did - out of the gate above, which runs the
        // same allocation first. Deliberately allocated only past the gate: those ~2280 void chunks
        // must keep paying one compare, not 2304 columns.
        double[] column = new double[maxY - REPAIR_FLOOR + 1];
        // The ten spike circles, from the seed alone. Resolved once per call rather than per column:
        // the pillar test below is a pure geometric predicate, so it cannot depend on what a
        // neighbouring chunk's populator may or may not have written yet.
        List<EndSpikes.Spike> spikes = EndSpikes.forSeed(seed);
        int filled = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!VanillaEndCore.inCoreQuart(x, z)) continue;
                // Decided per COLUMN, before the buffer is even filled: a vanilla pillar owns its
                // whole column, so nothing below it may be filled either.
                if (inVanillaPillar(spikes, x, z)) continue;
                field.densityColumnInto(column, x, z, REPAIR_FLOOR, maxY);
                for (int i = 0; i < column.length; i++) {
                    if (column[i] <= 0) continue;
                    int y = REPAIR_FLOOR + i;
                    if (!region.isInRegion(x, y, z)) continue;
                    if (region.getType(x, y, z) != Material.AIR) continue;
                    region.setBlockData(x, y, z, endStone);
                    filled++;
                }
            }
        }
        return filled;
    }


    /**
     * The ten vanilla pillars' footprint circles, left strictly alone: {@code dx² + dz² ≤ r² + 1}
     * around a spike centre, with {@code r} from {@link EndSpikes}. Vanilla fills that circle with
     * obsidian, caps it with bedrock and puts the healing crystal on top, while the air this pass
     * exists to undo is the spike's square footprint MINUS that circle: the two sets are disjoint, so
     * a column inside the circle must never be filled.
     *
     * <p><b>Geometric, not read-based.</b> This used to ask the world whether the column held
     * obsidian or bedrock. That was exact while the pillar was vanilla's, and became wrong the moment
     * {@code YungPillars} started writing obsidian of its own: whether a neighbouring chunk's
     * populator had already placed a template block decided whether this chunk repaired a column, so
     * the answer depended on chunk generation order. Derived from the seed it is the same answer in
     * every chunk, on every run, and it covers the crystal's own column unconditionally -- including
     * on a world whose island is taller than the spike, where the old test only fired if vanilla's
     * shaft happened to fall inside the scan window.
     *
     * <p>Pure and cheap: ten integer comparisons per column, no block reads, no allocation.
     */
    private static boolean inVanillaPillar(List<EndSpikes.Spike> spikes, int x, int z) {
        for (EndSpikes.Spike spike : spikes) {
            int dx = x - spike.centerX();
            int dz = z - spike.centerZ();
            int radius = spike.radius();
            if (dx * dx + dz * dz <= radius * radius + 1) return true;
        }
        return false;
    }

    /**
     * OreLayerFeature: a sphere of radius 6 squashed to 0.2 in Y, its centre plane warped up to
     * +-8 blocks by 2D noise, flood-filled from the seed point through end stone only -- so a layer
     * stops at air and never jumps a gap, but it does cross chunk borders.
     */
    /**
     * One ore_layer feature's worth of layers for this chunk; {@code count} comes from config.yml.
     * <p>
     * OreLayerFeature.java:33-36 computes {@code r = floor(radius + 1)} and offsets by
     * {@code randRange(max(r-16, 0), min(31-r, 15))}; for both radii used here that range works out
     * to 0..15, so the offset needs no parameter.
     */
    private static void oreLayers(LimitedRegion region, IslandField field,
                                  DustWastelandsGenerator generator, BiomePlacement placement,
                                  Random random, OpenSimplexNoise warpNoise, int originX, int originZ,
                                  int count, float radius, BlockData block,
                                  Set<BiomeSurface> only, boolean vanillaBarrens) {
        if (block == null) return;
        for (int i = 0; i < count; i++) {
            int x = originX + randRange(random, 0, 15);
            int z = originZ + randRange(random, 0, 15);
            int y = randRange(random, LAYER_MIN_Y, LAYER_MAX_Y);
            oreLayer(region, field, generator, placement, warpNoise, radius, originX, originZ,
                    x, y, z, block, only, vanillaBarrens);
        }
    }

    private static void oreLayer(LimitedRegion region, IslandField field,
                                       DustWastelandsGenerator generator, BiomePlacement placement,
                                       OpenSimplexNoise warpNoise, float radius,
                                       int originX, int originZ, int centerX, int centerY, int centerZ,
                                       BlockData block, Set<BiomeSurface> only,
                                       boolean vanillaBarrens) {
        if (!inBiome(placement, only, vanillaBarrens, centerX, centerY, centerZ)) return;
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long start = key(centerX, centerY, centerZ);
        queue.add(new long[]{centerX, centerY, centerZ});
        seen.add(start);
        while (!queue.isEmpty()) {
            long[] at = queue.poll();
            int x = (int) at[0];
            int y = (int) at[1];
            int z = (int) at[2];
            if (!region.isInRegion(x, y, z)) continue;
            if (!inLayer(warpNoise, radius, originX, originZ, centerX, centerY, centerZ, x, y, z)) continue;
            if (!field.isEndStone(x, y, z)) continue;
            // Gates the WRITE, not the spread: the layer keeps the extent it has today and only
            // loses the blocks it would have put in umbralith. Whether bclib's fillRecursive
            // spreads through a block it cannot replace is NOT DETERMINABLE (SDF.java is not in
            // this checkout), so the shape is left alone rather than changed on a guess.
            if (generator.fillsWithEndStone(placement, x, y, z)) {
                region.setBlockData(x, y, z, block);
            }
            for (int[] step : NEIGHBOURS) {
                int nx = x + step[0];
                int ny = y + step[1];
                int nz = z + step[2];
                if (seen.add(key(nx, ny, nz))) queue.add(new long[]{nx, ny, nz});
            }
        }
    }

    /**
     * The mod's {@code minecraft:biome} placement filter, which all five ore features carry.
     * <p>
     * Two halves, both load-bearing.
     * <p>
     * <b>The rings.</b> {@link BiomePlacement#quartAt} answers as if the vanilla rings did not
     * exist, so a ring column has to be decided here or not at all. {@code minecraft:the_end}
     * carries only {@code wover:is_end/center} and lists no BetterEnd ore, so it gets nothing.
     * {@code minecraft:end_barrens} carries {@code wover:is_end/barrens}, and the mod's
     * {@code wover/worldgen/biome_modifications/default_ores.json} PREPENDS flavolite, thallasium
     * and ender into every non-betterend biome with that tag -- so those three do generate in the
     * barrens and the per-biome three do not. Without this the port wrote ore into the dragon
     * fight's own island, because {@code vanillaSurface}'s filler is end stone and
     * {@code fillsWithEndStone} therefore says yes there.
     * <p>
     * <b>The biome set.</b> Quart-resolved, because that is the biome the provider painted and the
     * player reads in F3 -- {@code DustWastelandsGenerator.surfaceAt} asks the identical question
     * the identical way.
     * <p>
     * Tested at the DRAWN ORIGIN only, never per block. {@code minecraft:biome} is a placement
     * filter on the position the earlier modifiers produced; the feature then spreads on its own
     * rules. Gating every block instead would shear blobs and layers flat against a biome border.
     *
     * @param only           the biomes whose json lists this ore, or null for all 27.
     * @param vanillaBarrens whether {@code default_ores.json} injects it into {@code end_barrens}.
     */
    static boolean inBiome(BiomePlacement placement, Set<BiomeSurface> only,
                                   boolean vanillaBarrens, int x, int y, int z) {
        BiomePlacement.Ring ring = placement.ring(x, z);
        if (ring != null) {
            return vanillaBarrens && ring == BiomePlacement.Ring.BARRENS;
        }
        return only == null || only.contains(placement.quartAt(x, y, z));
    }

    private static boolean inLayer(OpenSimplexNoise warpNoise, float radius,
                                   int originX, int originZ,
                                   int centerX, int centerY, int centerZ, int x, int y, int z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        // OreLayerFeature.java:40-45 - the noise is sampled at the local offset plus the CHUNK
        // ORIGIN, not plus the layer centre.
        double offset = warpNoise.eval((dx + originX) * 0.1, (dz + originZ) * 0.1) * 8;
        double dy = ((y - centerY) + offset) / LAYER_SQUASH;
        return dx * dx + dy * dy + dz * dz < radius * radius;
    }

    /**
     * ponytail: vanilla's {@code minecraft:ore} blob algorithm is NOT FOUND in the reference tree,
     * so {@code size} is spent as a short random walk through end stone. Swap in the real ellipsoid
     * sweep if a vein's shape ever matters.
     */
    private static void oreBlobs(LimitedRegion region, IslandField field,
                                 DustWastelandsGenerator generator, BiomePlacement placement,
                                 Random random, int originX, int originZ, int minY, int maxY,
                                 int count, int size, BlockData block,
                                 Set<BiomeSurface> only, boolean vanillaBarrens) {
        if (block == null) return;
        for (int i = 0; i < count; i++) {
            int x = originX + random.nextInt(16);
            int z = originZ + random.nextInt(16);
            int y = randRange(random, minY + 8, maxY - 9);
            // Drawn before the filter so a rejected blob still consumes its three draws, which
            // keeps each ore's stream independent of which biome the chunk happens to be.
            if (!inBiome(placement, only, vanillaBarrens, x, y, z)) continue;
            for (int step = 0; step < size; step++) {
                if (region.isInRegion(x, y, z) && field.isEndStone(x, y, z)
                        && generator.fillsWithEndStone(placement, x, y, z)) {
                    region.setBlockData(x, y, z, block);
                }
                int[] move = NEIGHBOURS[random.nextInt(NEIGHBOURS.length)];
                x += move[0];
                y += move[1];
                z += move[2];
            }
        }
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** MHelper.randRange(int, int, RandomSource) [bclib@1.21] - inclusive on both ends. */
    private static int randRange(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFFL);
    }
}
