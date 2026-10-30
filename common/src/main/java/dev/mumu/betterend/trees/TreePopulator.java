package dev.mumu.betterend.trees;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.terrain.IslandField;
import dev.mumu.betterend.trees.TreeShape.Part;
import dev.mumu.betterend.trees.TreeShape.Pos;
import dev.mumu.betterend.trees.TreeShape.Species;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BetterEnd's trees, in a {@link BlockPopulator}.
 * <p>
 * They cannot be CraftEngine {@code placed_feature}s, and the reason is NOT the decoration gate:
 * CE runs its own features outside {@code shouldGenerateDecorations()} altogether
 * (InjectedCustomChunkGenerator.java:177-216), and that flag is true now in any case. The reason is
 * that every BetterEnd tree's configured feature is a custom Java type with an empty config, and CE
 * parses pack features with the vanilla {@code ConfiguredFeature} codec and THROWS on failure
 * (BukkitWorldManager.java:802-804) - a {@code {"type":"betterend:lacugrove"}} is a hard pack-load
 * error, not a skipped entry. CE's feature placement is also seeded per chunk from
 * {@code RandomSupport.generateUniqueSeed()}, i.e. non-deterministic. BlockPopulators, by contrast,
 * run unconditionally inside the patched decoration method (ChunkGenerator.java.patch:113-124).
 * <p>
 * Follows BetterEndPopulator exactly: no block is ever read back (CraftEngine's
 * {@code deceive-bukkit-material} makes reads lie, and a neighbour's populator may not have run),
 * every write is clipped with {@code isInRegion}, and every terrain decision comes from
 * {@link IslandField}. Holds no mutable state beyond two caches; Paper runs this concurrently for
 * many chunks and Folia per region.
 * <p>
 * <b>All nine species resolve.</b> An earlier revision of this javadoc claimed the five wood species
 * were dormant for want of {@code <family>_bark} / {@code _log}, on the strength of
 * B1-wood-families.yml instantiating only four families. That was wrong: B1 adds the four NEW
 * families (mossy_glowshroom, end_lotus, umbrella_tree, jellyshroom), while the five original wood
 * families come from the separate {@code config_factory#wood_sets} in wood_sets.yml, whose instances
 * list is pythadendron / lacugrove / dragon_tree / tenanea / helix_tree / lucernia. All fifteen ids
 * (bark, log, leaves for each of the five) were verified present against a full factory expansion of
 * every blocks/*.yml. Concluding "absent" from one file is the recurring mistake on this pack --
 * expand every factory and template before believing an id is missing.
 * The mushrooms need no leaves and every id they do need resolves, so they generate the moment
 * this populator is registered -- all three of their host biomes ship (BiomeSurface.java:34-35,
 * :41-42, :70-71). The "host biomes are unshipped" note that used to stand here cited
 * BiomeSurface.java:198-200 and was stale: all 27 ship (BiomeSurface.java:216-218).
 */
public final class TreePopulator extends BlockPopulator {
    private static final Logger LOG = Logger.getLogger(TreePopulator.class.getName());

    /**
     * "Does a tree of this species accept the block at {@code (x, y, z)} as its ground?"
     * <p>
     * Ten of the eleven mod species want {@code BlockTags.NYLIUM} (pythadendron wants
     * {@code CHORUS_NYLIUM} exactly, dragon helix also takes end moss and end stone), and the
     * placed feature is additionally filtered to the one biome that declares it
     * ({@code minecraft:biome}, per position). {@link IslandField} exposes no surface-block oracle
     * beyond its dust/end-stone split, so this is the seam with the biome subsystem: the developer
     * passes an implementation that answers from the biome and surface tables.
     * <p>
     * {@link #biomeSoil} is the real implementation. Never approximate this as "any solid block" -
     * that would carpet the dust wastelands with trees the mod would never place there.
     */
    @FunctionalInterface
    public interface Soil {
        boolean isTreeSoil(Species species, int x, int y, int z);
    }

    /** The honest default: no biome subsystem, so no species has any ground to stand on. */
    public static final Soil NO_SOIL = (species, x, y, z) -> false;

    /**
     * The one biome whose feature list declares each species, read out of the mod's own data:
     * {@code worldgen/biome/megalake_grove.json} names {@code betterend:lacugrove},
     * {@code shadow_forest.json} names {@code dragon_tree}, {@code lantern_woods.json}
     * {@code lucernia}, {@code blossoming_spires.json} {@code tenanea},
     * {@code chorus_forest.json} {@code pythadendron_tree},
     * {@code foggy_mushroomland.json:58} {@code mossy_glowshroom},
     * {@code dragon_graveyards.json:61} {@code gigantic_amaranita} and
     * {@code umbrella_jungle.json:57-58} BOTH {@code umbrella_tree} and {@code jellyshroom} -- the
     * only host that carries two species, and so the only chunk where two mushrooms compete for the
     * write budget.
     */
    private static final Map<Species, BiomeSurface> HOST = Map.of(
            Species.LACUGROVE, BiomeSurface.MEGALAKE_GROVE,
            Species.DRAGON_TREE, BiomeSurface.SHADOW_FOREST,
            Species.LUCERNIA, BiomeSurface.LANTERN_WOODS,
            Species.TENANEA, BiomeSurface.BLOSSOMING_SPIRES,
            Species.PYTHADENDRON, BiomeSurface.CHORUS_FOREST,
            Species.JELLYSHROOM, BiomeSurface.UMBRELLA_JUNGLE,
            Species.UMBRELLA_TREE, BiomeSurface.UMBRELLA_JUNGLE,
            Species.GIGANTIC_AMARANITA, BiomeSurface.DRAGON_GRAVEYARDS,
            Species.MOSSY_GLOWSHROOM, BiomeSurface.FOGGY_MUSHROOMLAND);

    /**
     * The real oracle. Every tree's placed_feature carries {@code minecraft:biome} and nothing else
     * beyond its count (placed_feature/lacugrove.json), so a species may only stand where its host
     * biome is painted -- and that biome's own surface block IS the nylium / moss / chorus nylium
     * the mod's ground check wants (BiomeSurface top()), so the biome test subsumes the block test
     * without ever reading a block back.
     * <p>
     * The vanilla core is excluded because BetterEnd paints nothing there
     * ({@code BiomePlacement.ring}, radius 1024 plus the 384-block core inside it);
     * {@code BiomePlacement.at} must not be asked about either, because the mod paints vanilla
     * center / barrens biomes there and neither hosts a tree.
     * <p>
     * Every host ships (BiomeSurface.java:216-218), the mushrooms included, so this answers true
     * wherever one of the nine is painted outside the vanilla ring.
     */
    public static Soil biomeSoil(BiomePlacement placement) {
        // quartAt, not at: the caller passes the solid block under the trunk, which is a segment's
        // top block, so this is the identical call DustWastelandsGenerator.surfaceAt made when it
        // wrote that block. Raw at() would let a tree stand on one biome's surface block while
        // being told it is standing in another, within 3 blocks of any quart edge.
        return (species, x, y, z) -> placement.ring(x, z) == null
                && placement.quartAt(x, y, z) == HOST.get(species);
    }

    /**
     * Hard cap on {@code count_on_every_layer}'s layer loop. The End's stacked islands give a column
     * a handful of surfaces at most, and the loop already stops on the first round that finds none.
     * <p>
     * ponytail: insurance against an unbounded loop on a worldgen thread, not a real limit.
     * <p>
     * Package-private because {@link SurfaceScan} sizes its per-column answer on it: the two have to
     * be the same number, or a layer this loop asks about would index past the walk's own cap.
     */
    static final int MAX_LAYERS = 16;

    private final Soil soil;
    private final Map<Species, Map<Part, BlockData>> palettes = new EnumMap<>(Species.class);
    /**
     * The chunk generator's own field, passed in rather than rebuilt here: a second one would be
     * built from {@code BetterEndConfig.DEFAULTS} and would disagree with the configured terrain
     * everywhere the owner changed a layer key, as well as doubling the plane cache
     * (IslandField.java:74).
     */
    private final IslandField field;

    /**
     * Resolves every id a species can emit through CraftEngine once, here. A species with even one
     * unresolved id is dropped for the life of the populator with a single log line - never
     * substituted with another block, and never thrown: an unresolved id must cost that one species,
     * not the world.
     * <p>
     * {@link Species#ids} is per species rather than {@code <family>_bark/_log/_leaves} because the
     * mushrooms do not follow that convention: none has leaves, amaranita has neither bark nor log,
     * and jellyshroom's cap is {@code jellyshroom_cap_purple}.
     */
    public TreePopulator(Soil soil, IslandField field) {
        this.soil = soil;
        this.field = field;
        for (Species species : Species.values()) {
            Map<Part, BlockData> palette = new EnumMap<>(Part.class);
            StringBuilder missing = new StringBuilder();
            for (Map.Entry<Part, String> entry : species.ids.entrySet()) {
                BlockData data = resolve(entry.getValue());
                if (data == null) missing.append("betterend:").append(entry.getValue()).append(' ');
                else palette.put(entry.getKey(), data);
            }
            if (missing.length() > 0) {
                LOG.log(Level.INFO, "BetterEnd: {0} trees disabled, CraftEngine has no {1}",
                        new Object[]{species.family, missing.toString().trim()});
                continue;
            }
            palettes.put(species, palette);
        }
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion region) {
        if (palettes.isEmpty()) return;
        long seed = worldInfo.getSeed();
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();

        // Paper builds a fresh, identically seeded Random for EVERY populator
        // (ChunkGenerator.java.patch:113-124), so the `random` argument replays BetterEndPopulator's
        // ore stream block for block. Derive our own from seed + chunk coords instead: still
        // deterministic and chunk-visit-order independent, and no longer coupled to registration order.
        Random rng = new Random(mix(seed, chunkX, chunkZ));
        // One memoised column scan for the whole chunk, shared by all nine species: every ask is
        // about the same terrain, and the object lives on this stack frame only, so concurrent
        // populators still share nothing. Columns are scanned on first ask, so a chunk whose
        // species all place nothing builds none.
        SurfaceScan surfaces = new SurfaceScan(field, chunkX << 4, chunkZ << 4, minY, maxY);

        for (Species species : Species.values()) {
            if (palettes.containsKey(species)) {
                placeSpecies(species, region, field, rng, chunkX, chunkZ, surfaces);
            }
        }
    }

    /**
     * {@code minecraft:count_on_every_layer(uniform(0, max))} - the only count modifier any tree's
     * placed feature carries. Each round draws {@code count} positions in the chunk and places on the
     * layer-th air-over-solid surface of each column, so stacked End islands get trees on every
     * island in the column and not just the top one. The loop stops on the first round that lands
     * nothing.
     * <p>
     * The two draws per attempt are the OLD ones in the OLD order - {@code nextInt(16)} for the x
     * offset, then for the z - and nothing else is drawn here, so the stream, and with it every tree
     * this populator has ever grown, is unchanged by the memo. Only the surface lookup behind them
     * moved into {@link SurfaceScan}, which answers the same y the per-ask scan did.
     * <p>
     * ponytail: the exact vanilla semantics are NOT FOUND - Paper-26.1.2 ships only patches and there
     * is no decompiled {@code CountOnEveryLayerPlacement} in either reference tree. Re-check the
     * per-round count draw against a real 26.x jar before trusting the density.
     */
    private void placeSpecies(Species species, LimitedRegion region, IslandField field, Random rng,
                              int chunkX, int chunkZ, SurfaceScan surfaces) {
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        for (int layer = 0; layer < MAX_LAYERS; layer++) {
            int count = rng.nextInt(species.maxPerLayer + 1);
            boolean any = false;
            for (int i = 0; i < count; i++) {
                int localX = rng.nextInt(16);
                int localZ = rng.nextInt(16);
                int x = originX + localX;
                int z = originZ + localZ;
                int y = surfaces.surfaceY(localX, localZ, layer);
                if (y == Integer.MIN_VALUE) continue;
                any = true;
                if (soil.isTreeSoil(species, x, y - 1, z)) {
                    placeTree(species, region, field, rng, x, y, z, chunkX, chunkZ);
                }
            }
            if (!any) return;
        }
    }

    /**
     * Trees reach further than the 16 blocks of room a populator is guaranteed (a trunk at chunk-local
     * 0 may write 16 blocks west but 31 east; CraftLimitedRegion.java:49, :66-73). The mod's own
     * answer is two-layered and is reproduced here: {@link TreeShape} shrinks each canopy to its
     * headroom first (EndTreeHelper.fitBallRadius) so the wall has little left to cut, and clips what
     * still crosses it - and every write below is guarded again by {@code isInRegion}, because
     * {@code CraftLimitedRegion.setBlockData} THROWS outside the region rather than no-opping
     * (CraftLimitedRegion.java:210-211) and Y is not clipped by the shape at all.
     */
    private void placeTree(Species species, LimitedRegion region, IslandField field, Random rng,
                           int x, int y, int z, int chunkX, int chunkZ) {
        Map<Part, BlockData> palette = palettes.get(species);
        int lowX = (chunkX << 4) - 16 - x;
        int highX = (chunkX << 4) + 31 - x;
        int lowZ = (chunkZ << 4) - 16 - z;
        int highZ = (chunkZ << 4) + 31 - z;
        Map<Pos, Part> tree = TreeShape.generate(species, rng.nextLong(), lowX, highX, lowZ, highZ,
                p -> field.isEndStone(x + p.x(), y + p.y(), z + p.z()));
        for (Map.Entry<Pos, Part> entry : tree.entrySet()) {
            Pos p = entry.getKey();
            int bx = x + p.x();
            int by = y + p.y();
            int bz = z + p.z();
            if (!region.isInRegion(bx, by, bz)) continue;
            BlockData data = palette.get(entry.getValue());
            if (data == null) continue; // a part outside Species.ids; unreachable, not worth throwing
            region.setBlockData(bx, by, bz, data);
        }
    }

    /** SplitMix-style avalanche of the seed and the chunk coords; nothing depends on visit order. */
    private static long mix(long seed, int chunkX, int chunkZ) {
        long s = seed ^ 0x7472656573L; // "trees"
        s = s * 6364136223846793005L + chunkX;
        s = s * 6364136223846793005L + chunkZ;
        s ^= s >>> 33;
        return s * 0xff51afd7ed558ccdL;
    }

    /**
     * An unresolved id must cost one species, never the world - so a CraftEngine that is not ready
     * (pack not loaded yet, plugin disabled) has to read as "missing", not as a thrown exception on
     * the world-load thread. DustWastelandsGenerator.java:149-155 deliberately throws for the terrain
     * blocks it cannot do without; trees can do without.
     * <p>
     * {@code <id>[<property>=<value>]} selects a non-default state - the two the mushrooms need are
     * {@code mossy_glowshroom_cap[transition=true]} (B1-wood-families.yml:322-326) and
     * {@code amaranita_fur[facing=...]} (B5-flora-b.yml:1339-1348). Both properties exist in the
     * pack; a missing one reads as a missing id and disables the species rather than silently
     * shipping the default state.
     */
    private static BlockData resolve(String spec) {
        int bracket = spec.indexOf('[');
        String name = bracket < 0 ? spec : spec.substring(0, bracket);
        try {
            BlockDefinition definition = CraftEngineBlocks.byId(Key.of("betterend:" + name));
            if (definition == null) return null;
            ImmutableBlockState state = definition.defaultState();
            if (bracket >= 0) {
                String[] pair = spec.substring(bracket + 1, spec.length() - 1).split("=", 2);
                Property<?> property = definition.getProperty(pair[0]);
                if (property == null) return null;
                // BooleanProperty.valueByName / EnumProperty.valueByName return null rather than
                // throwing for a name the property does not have; without this the NPE inside
                // ImmutableBlockState.withInternal would reach the catch below as a stack trace
                // rather than as the plain "missing id" line contract 5 asks for.
                Object value = property.valueByName(pair[1]);
                if (value == null) return null;
                state = ImmutableBlockState.with(state, property, value);
            }
            return CraftEngineBlocks.getBukkitBlockData(state);
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "BetterEnd: CraftEngine could not resolve betterend:" + spec, e);
            return null;
        }
    }
}
