package dev.mumu.betterend;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.terrain.IceStarPlanner;
import dev.mumu.betterend.terrain.IslandField;
import dev.mumu.betterend.terrain.SpireShape;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Random;

/**
 * The mod's {@code raw_generation} step: the large terrain shapes that are not part of the density
 * field but still count as landscape rather than decoration.
 * <p>
 * Four features across two biomes, and no biome has both:
 * <ul>
 *   <li>blossoming_spires -- {@code spire} (rarity 1/4) and {@code floating_spire} (1/8), the cone
 *       planted in the ground and the spindle hanging above it. See {@link SpireShape}.</li>
 *   <li>ice_starfield -- {@code ice_star} (1/15) and {@code ice_star_small} (1/8), unions of capped
 *       cones hanging in the air. See {@link IceStarPlanner}.</li>
 * </ul>
 * All four are one class because they are one generation step and because a second
 * {@code BlockPopulator} would mean a second {@code CraftLimitedRegion} per chunk and no ordering
 * guarantee against this one -- the point {@code BetterEndPopulator} already makes about keeping
 * related writes in a single pass.
 * <p>
 * <b>Why a populator and not {@code generateNoise}.</b> Both shapes cross chunk borders -- a spire
 * reaches 14 blocks from its origin, a big ice star 30 -- and {@code ChunkData.setBlock} outside
 * 0..15 is a SILENT no-op, which is what once turned the flavolite layers into half discs. A
 * {@code LimitedRegion} reaches 16 blocks past the chunk in every direction, which is exactly the
 * 3x3 write zone the mod clips these same features to, so each chunk plans only its OWN shapes and
 * needs no neighbour scan.
 * <p>
 * Registered before the ore and flora populators, matching the mod's step order.
 * <p>
 * Holds no mutable state: Paper runs this concurrently for many chunks.
 */
public final class RawGenerationPopulator extends BlockPopulator {
    private final DustWastelandsGenerator generator;

    RawGenerationPopulator(DustWastelandsGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion region) {
        long seed = worldInfo.getSeed();
        BiomePlacement placement = generator.placement(seed);
        spires(region, placement, seed, chunkX, chunkZ);
        iceStars(region, placement, seed, chunkX, chunkZ);
    }

    private void spires(LimitedRegion region, BiomePlacement placement, long seed,
                        int chunkX, int chunkZ) {
        IslandField field = placement.field();
        BlockData body = generator.endStone;
        BlockData cap = generator.spireCap();
        for (SpireShape.Kind kind : SpireShape.Kind.values()) {
            List<SpireShape.Cell> cells = SpireShape.plan(seed, chunkX, chunkZ, kind, placement, field);
            if (cells == null) {
                continue;
            }
            for (SpireShape.Cell cell : cells) {
                set(region, cell.x(), cell.y(), cell.z(), cell.cap() ? cap : body);
            }
        }
    }

    private void iceStars(LimitedRegion region, BiomePlacement placement, long seed,
                          int chunkX, int chunkZ) {
        for (IceStarPlanner.Kind kind : IceStarPlanner.Kind.values()) {
            IceStarPlanner.Star star = IceStarPlanner.plan(seed, chunkX, chunkZ, kind, placement);
            if (star == null) {
                continue;
            }
            for (IceStarPlanner.Cell cell : star.cells()) {
                set(region, cell.x(), cell.y(), cell.z(), generator.iceBlock(cell.ice()));
            }
        }
    }

    /**
     * {@code CraftLimitedRegion.setBlockData} THROWS outside the region rather than no-opping, and
     * both shapes can reach past the world height, so the guard is not optional. A null block is an
     * id the pack has not shipped; that cell is simply left alone.
     */
    private static void set(LimitedRegion region, int x, int y, int z, BlockData block) {
        if (block != null && region.isInRegion(x, y, z)) {
            region.setBlockData(x, y, z, block);
        }
    }
}
