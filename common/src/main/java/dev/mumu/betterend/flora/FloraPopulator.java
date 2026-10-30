package dev.mumu.betterend.flora;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.flora.FloraPlanner.ColumnScan;
import dev.mumu.betterend.terrain.IslandField;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * BetterEnd's flora, in a {@link BlockPopulator}.
 * <p>
 * Wire it in {@code DustWastelandsGenerator.getDefaultPopulators}, next to the other two:
 * <pre>{@code
 * new FloraPopulator(placement(seed), new FloraPalette())
 * }</pre>
 * The placement carries its own island field ({@code BiomePlacement.field()}), so passing it
 * passes the bound pair - one plane cache, one agreement about where the land is.
 * <p>
 * Follows {@code BetterEndPopulator} and {@code TreePopulator} exactly: no block is ever read back,
 * every write is clipped with {@code isInRegion} because {@code CraftLimitedRegion.setBlockData}
 * THROWS outside the region rather than no-opping, ids are resolved once at construction, and the
 * chunk random is derived from {@code (seed, chunkX, chunkZ)} rather than the argument Paper hands
 * every populator identically seeded. Immutable after construction; the only per-chunk state is a
 * {@link ColumnScan} that never escapes {@link #populate}.
 * <p>
 * Cost, per chunk, expected, over 27 live biomes: ~700 cells and ~360 writes on a median land
 * chunk; ~17,100 cells in blossoming_spires (96% of it the wall-plant box) and ~840 writes in
 * crystal_mountains. Zero in the void, in the vanilla rings, and in the ten biomes with no
 * vegetal_decoration step.
 */
public final class FloraPopulator extends BlockPopulator {
    private static final Logger LOG = Logger.getLogger(FloraPopulator.class.getName());

    private final BiomePlacement placement;
    private final IslandField field;
    private final FloraPalette palette;
    private final FloraPlanner.Surface surface;
    private final List<Flora.Row> live;

    public FloraPopulator(BiomePlacement placement, FloraPalette palette) {
        this.placement = placement;
        this.field = placement.field();
        this.palette = palette;
        this.live = List.copyOf(palette.live());
        // The same BiomeSurface.resolve call DustWastelandsGenerator's constructor makes, with the
        // same "does the pack define this id" predicate, so the two cannot disagree about which
        // block is on top of a segment - which is the whole point of recomputing it rather than
        // reading it back.
        BiomeSurface.Resolved[] resolved = new BiomeSurface.Resolved[BiomeSurface.values().length];
        List<String> broken = new ArrayList<>();
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            resolved[biome.ordinal()] = biome.resolve(palette::defined, broken::add);
        }
        this.surface = new FloraPlanner.Surface(placement, resolved);
        if (live.size() < Flora.ROWS.size()) {
            LOG.info("BetterEnd: flora active for " + live.size() + " of " + Flora.ROWS.size()
                    + " placed features; the rest are named above.");
        }
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX,
                         int chunkZ, @NotNull LimitedRegion region) {
        if (live.isEmpty()) {
            return;
        }
        int x0 = (chunkX << 4) - 16;
        int z0 = (chunkZ << 4) - 16;
        // The void gate, first and once, over the whole write zone. The End is mostly void and
        // this costs 64 lattice columns instead of a 48x48x128 scan; BetterEndPopulator.java:167
        // measures the same gate at +730% vs +37%.
        if (!field.anySolid(x0, z0, x0 + 47, z0 + 47, ColumnScan.LO, ColumnScan.HI)) {
            return;
        }
        ColumnScan scan = new ColumnScan(field, chunkX, chunkZ);
        Random rng = new Random(FloraPlanner.mix(worldInfo.getSeed(), chunkX, chunkZ));
        FloraPlanner.plan(live, scan, surface, placement, rng, chunkX, chunkZ,
                (x, y, z, row, idIndex, value) -> {
                    if (!region.isInRegion(x, y, z)) {
                        return;
                    }
                    BlockData data = palette.get(row, idIndex, value);
                    if (data != null) {
                        region.setBlockData(x, y, z, data);
                    }
                });
    }
}
