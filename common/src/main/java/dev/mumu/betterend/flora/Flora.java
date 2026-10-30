package dev.mumu.betterend.flora;

import dev.mumu.betterend.biome.BiomeSurface;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * BetterEnd's flora placement table: one {@link Row} per placed_feature the flora layer writes,
 * every number a literal from {@code Reference/BetterEnd/src/main/generated/data/betterend/worldgen/}.
 * <p>
 * Pure data - no Bukkit, no CraftEngine, nothing outside {@code java.*} - for the same reason
 * {@link BiomeSurface} carries none: {@link FloraPlanner} and the unit tests read this table
 * without a server.
 * <p>
 * <b>The soil test is block-level and must stay that way.</b> {@code TreePopulator.biomeSoil}'s
 * shortcut ("the host biome's top block IS the soil, so the biome test subsumes the block test")
 * is correct for trees because all five tree hosts have {@code alt() == top()}. Three flora hosts
 * do not: megalake's alt is {@code endstone_dust} (not in {@link SoilSet#SOIL}, so ~half the biome
 * rejects umbrella_moss), neon_oasis is the inverse, and umbra_valley's top is {@code umbralith}
 * with {@code pallidium_full} as the alt - so inflexia and flammalix grow ONLY on the alt speckle.
 * Simplifying this back to a biome test carpets umbra_valley with plants the mod puts on half of it.
 * <p>
 * <b>{@code chance} is the {@code getChance()} METHOD, never the JSON field.</b>
 * {@code SinglePlantFeatureConfig.java:19,50} parses {@code chance} and nothing ever reads it;
 * {@code ScatterFeature.java:101} gates on {@code getChance()}, whose base returns 1
 * ({@code ScatterFeature.java:57-59}) and which only 8 classes override. In the whole ship set
 * exactly three rows carry a real chance: lanceleaf 5 ({@code LanceleafFeature.java:37-39}),
 * glow_pillar 10 ({@code GlowPillarFeature.java:38-40}) and filalux 10
 * ({@code SkyScatterFeature.java:18-20}). Reproducing the JSON instead would cut lantern_woods
 * ground writes roughly fourfold - a different world, not an optimisation.
 * <p>
 * <b>Not in this table, and why.</b> Nothing is dropped for effort; every one of these cannot
 * write a block today for a reason outside the flora layer.
 * <ul>
 * <li>16 water rows - charnia x7, bubble_coral x2, pond_anemone, end_lily x2, end_lotus,
 * end_lotus_leaf, hydralux, menger_sponge, and <b>flamaea</b>, which is a WATER plant
 * ({@code EndWaterPlantBlocks.java:272}, {@code SurvivesOnBlockTrait.withBlocks(Blocks.WATER)})
 * and not the ground plant the catalogue calls it. {@code UnderwaterPlantScatter.canSpawn} is
 * {@code state.is(Blocks.WATER)} with no fallback and nothing in this port writes water. Add
 * when lakes are ported.</li>
 * <li>9 {@code wall_plant_on_log_feature} rows - {@code WallPlantOnLogFeature.java:78-80} is
 * exactly {@code is(BlockTags.LOGS)} behind the plant, and terrain knows nothing about trees.
 * They belong in {@code TreePopulator}, which already enumerates every LOG/BARK position it
 * writes. Never approximate them as "any solid face": that carpets every chorus_forest cliff
 * with purple polypores.</li>
 * <li>5 cave rows plus the stalactite clusters - {@code IslandField} has no cave carver.</li>
 * <li>{@code crystal_moss_cover}, {@code neon_cactus}, {@code cave_pumpkin}, {@code end_lotus} -
 * ids absent from, or shape-incompatible with, the CraftEngine pack.</li>
 * <li>{@code amaranita_patch}, {@code gigantic_amaranita}, {@code jellyshroom} and the 5 bush rows
 * - tree-shaped (SDF spheres, 60-120 leaves, an outer-leaf shell). Give them to
 * {@code TreePopulator}.</li>
 * <li>{@code bonemeal_*} x4, {@code village_chorus}, {@code overworld_island},
 * {@code sulphur_hill} - declared by no biome; the bonemeal ones carry no placement modifiers
 * at all.</li>
 * </ul>
 * ponytail: those 16 water rows are named here rather than carried as {@code Engine.WATER} rows
 * with a branch that answers false - 16 dead rows and a dead engine buy nothing over this comment.
 * Add them as rows the day lakes land, with their radii from the placed_feature json.
 */
public final class Flora {
    private Flora() {
    }

    /** The five placement loops the mod's feature classes collapse to, plus one variant. */
    public enum Engine {
        /** {@code ScatterFeature.place:62-108} + {@code SinglePlantFeature}. */
        GROUND,
        /** GROUND, with {@code DoublePlantFeature.java:28-34}'s tall-inside-0.5r split. */
        DOUBLE,
        /** {@code WallScatterFeature.place:29-70} + {@code WallPlantFeature}. */
        WALL,
        /** {@code InvertedScatterFeature.place:36-74} + {@code SingleInvertedScatterFeature}. */
        CEILING,
        /** GROUND to a point, then a fixed vertical stamp - see {@code FloraPlanner}. */
        COLUMN,
        /** {@code SkyScatterFeature.java:12-60} + {@code FilaluxFeature}. */
        SKY
    }

    /**
     * The block property a written id carries, with every value the CraftEngine pack declares for
     * it. The palette enumerates these once at construction, so a value the pack does not define
     * disables its row instead of failing on the worldgen thread.
     * <p>
     * Three cosmetic properties the mod sets are absent from the pack and therefore dropped:
     * {@code BaseDoublePlantBlock.ROTATION} 0-3 on the two {@code *_tall} doubles and
     * {@code BlockProperties.ROTATION} on lanceleaf (both folded into the pack's weighted model
     * lists as y rotations, B4-flora-plants.yml:1180-1220), and {@code NATURAL} on the two lantern
     * caps (drops only, never worldgen).
     */
    public enum Prop {
        NONE(null),
        /** {@code BlockProperties.TRIPLE_SHAPE}; pack B5-flora-b.yml:1465-1474 and siblings. */
        TRIPLE("shape", "top", "middle", "bottom"),
        /** {@code BlockProperties.PENTA_SHAPE}; pack B4-flora-plants.yml:1305-1309. */
        PENTA("shape", "bottom", "pre_bottom", "middle", "pre_top", "top"),
        /** {@code EndBlockProperties.LumecornShape}; pack B5-flora-b.yml:2044-2056. */
        LUMECORN("shape", "bottom_big", "bottom_small", "middle",
                "light_bottom", "light_middle", "light_top_middle", "light_top"),
        /** {@code BaseWallPlantBlock.FACING}; pack {@code type: horizontal_direction}. */
        FACING_H("facing", "north", "east", "south", "west"),
        /** {@code BlockStateProperties.FACING}; pack {@code type: 6-direction}. */
        FACING_6("facing", "north", "east", "south", "west", "up", "down"),
        /** {@code BaseDoublePlantBlock.TOP}; pack {@code type: double_block_half}. */
        HALF("half", "lower", "upper"),
        /** {@code PottableCropBlock.AGE}, stamped to 3 by {@code SinglePlantFeature.java:50-52}. */
        AGE3("age", "3");

        private final String property;
        private final List<String> values;

        Prop(String property, String... values) {
            this.property = property;
            this.values = List.of(values);
        }

        /** The pack's property name, or {@code null} for {@link #NONE}. */
        public String property() {
            return property;
        }

        public List<String> stateValues() {
            return values;
        }
    }

    /**
     * A plant's soil, named after the {@code betterend:survives_on/*} tag it is transcribed from
     * ({@code Reference/BetterEnd/src/main/generated/data/betterend/tags/block/survives_on/}).
     * <p>
     * {@link #ANY} is the empty set and means "no block test": the wall, ceiling and sky
     * engines have their own support rule, and {@code small_jellyshroom} is a
     * {@code BaseAttachedBlock} whose floor form needs only a solid block under it.
     */
    public enum SoilSet {
        ANY(),
        /** survives_on/amber_moss.json */
        AMBER_MOSS("betterend:amber_moss"),
        /** survives_on/chorus_nylium.json */
        CHORUS_NYLIUM("betterend:chorus_nylium"),
        /** EndPlantBlocks.java:144, {@code withBlocks(CRYSTAL_MOSS)} - no tag. */
        CRYSTAL_MOSS("betterend:crystal_moss"),
        /** EndPlantBlocks.java:164, {@code withBlocks(PINK_MOSS)} - no tag. */
        PINK_MOSS("betterend:pink_moss"),
        /** survives_on/shadow_grass.json */
        SHADOW_GRASS("betterend:shadow_grass"),
        /** survives_on/end_bone.json */
        END_BONE("betterend:mossy_dragon_bone", "betterend:mossy_obsidian", "betterend:sangnum"),
        /** survives_on/pallidium.json */
        PALLIDIUM("betterend:pallidium_full", "betterend:pallidium_heavy",
                "betterend:pallidium_thin", "betterend:pallidium_tiny"),
        /** survives_on/rutiscus.json */
        RUTISCUS("betterend:pallidium_full", "betterend:pallidium_heavy",
                "betterend:pallidium_thin", "betterend:pallidium_tiny",
                "betterend:rutiscus", "betterend:sangnum"),
        /** survives_on/end_moss.json */
        END_MOSS("betterend:end_moss", "betterend:pallidium_full", "betterend:pallidium_heavy",
                "betterend:pallidium_thin", "betterend:pallidium_tiny", "betterend:sangnum"),
        /** survives_on/moss_or_mycelium.json */
        MOSS_OR_MYCELIUM("betterend:end_moss", "betterend:end_mycelium",
                "betterend:pallidium_full", "betterend:pallidium_heavy",
                "betterend:pallidium_thin", "betterend:pallidium_tiny", "betterend:sangnum"),
        /** survives_on/jungle_moss_or_mycelium.json */
        JUNGLE_MOSS_OR_MYCELIUM("betterend:end_moss", "betterend:end_mycelium",
                "betterend:jungle_moss", "betterend:pallidium_full", "betterend:pallidium_heavy",
                "betterend:pallidium_thin", "betterend:pallidium_tiny", "betterend:sangnum"),
        /**
         * {@code wover:surfaces/soil} (data/wover/tags/block/surfaces/soil.json). NOT the narrow
         * survives_on tag: umbrella_moss (EndPlantBlocks.java:74-76) and jungle_grass
         * (EndPlantBlocks.java:223-225) are deliberately {@code CommonBlockTags.SOIL}, each with a
         * source comment saying so. Only their {@code _tall} partners take the narrow tag.
         */
        SOIL("betterend:amber_moss", "betterend:cave_moss", "betterend:chorus_nylium",
                "betterend:crystal_moss", "betterend:end_moss", "betterend:end_mycelium",
                "betterend:jungle_moss", "betterend:pallidium_full", "betterend:pallidium_heavy",
                "betterend:pallidium_thin", "betterend:pallidium_tiny", "betterend:pink_moss",
                "betterend:rutiscus", "betterend:sangnum", "betterend:shadow_grass"),
        /**
         * {@code wover:surfaces/end/stones} (data/wover/tags/block/surfaces/end/stones.json), the
         * tag {@code Lumecorn.java:28} and {@code LargeAmaranitaFeature.java:28} test directly. It
         * holds every BetterEnd surface block, so in practice it accepts any column with a surface;
         * spelled out rather than short-circuited to "always true" so a future biome whose top is
         * outside the tag is rejected without anyone having to remember this.
         */
        END_STONES("betterend:amber_moss", "betterend:amber_ore", "betterend:azure_jadestone",
                "betterend:brimstone", "betterend:cave_moss", "betterend:chorus_nylium",
                "betterend:crystal_moss", "betterend:end_moss", "betterend:end_mycelium",
                "betterend:ender_ore", "betterend:endstone_dust", "betterend:flavolite",
                "betterend:jungle_moss", "betterend:pallidium_full", "betterend:pallidium_heavy",
                "betterend:pallidium_thin", "betterend:pallidium_tiny", "betterend:pink_moss",
                "betterend:rutiscus", "betterend:sandy_jadestone", "betterend:sangnum",
                "betterend:shadow_grass", "betterend:sulphuric_rock", "betterend:thallasium_ore",
                "betterend:umbralith", "betterend:violecite", "betterend:virid_jadestone");

        private final Set<String> blocks;

        SoilSet(String... blocks) {
            this.blocks = Set.of(blocks);
        }

        public Set<String> blocks() {
            return blocks;
        }

        /** {@link #ANY} accepts anything; every other set is an exact block-id membership test. */
        public boolean accepts(String blockId) {
            return blocks.isEmpty() || blocks.contains(blockId);
        }
    }

    /**
     * One placed_feature.
     *
     * @param name        the placed_feature file name; log lines and the COLUMN switch key off it
     * @param hosts       the biomes whose {@code vegetal_decoration} array names it
     * @param maxPerLayer {@code count_on_every_layer}'s {@code uniform.max_inclusive}
     * @param radius      {@code config.radius}; 0 for the two features that carry no config
     * @param chance      {@code getChance()} - see the class javadoc, NOT {@code config.chance}
     * @param soil        the primary plant's {@code SurvivesOnBlockTrait}
     * @param soilTall    the DOUBLE engine's tall partner's soil. umbrella_moss is the one row
     *                    where it differs from {@code soil}.
     * @param ids         block ids without the {@code betterend:} prefix; index 0 is the primary
     * @param props       the property each id carries, parallel to {@code ids}
     */
    public record Row(String name, Engine engine, Set<BiomeSurface> hosts,
                      int maxPerLayer, int radius, int chance,
                      SoilSet soil, SoilSet soilTall,
                      List<String> ids, List<Prop> props) {
    }

    private static Set<BiomeSurface> of(BiomeSurface... biomes) {
        return Collections.unmodifiableSet(EnumSet.copyOf(List.of(biomes)));
    }

    private static List<Prop> p(Prop... props) {
        return List.of(props);
    }

    private static final List<Prop> PLAIN = p(Prop.NONE);

    // Every row below: hosts from worldgen/biome/<host>.json's vegetal_decoration array; radius and
    // maxPerLayer from worldgen/placed_feature/<name>.json (config.radius and the
    // count_on_every_layer uniform's max_inclusive); soil from the block's SurvivesOnBlockTrait in
    // registry/block/End*Blocks.java, expanded through tags/block/survives_on/*.json.
    /** The table, in the order the planner walks it. Immutable; nothing here is built lazily. */
    public static final List<Row> ROWS = List.of(
            // --- GROUND, ScatterFeature + SinglePlantFeature ---------------------------------
            g("aeridium", of(BiomeSurface.DRY_SHRUBLAND, BiomeSurface.FLOWER_ISLETS,
                    BiomeSurface.LANTERN_WOODS), 5, 5, SoilSet.RUTISCUS),
            g("amber_grass", of(BiomeSurface.AMBER_LAND), 7, 6, SoilSet.AMBER_MOSS),
            // B4-flora-crops-seeds.yml supplies the three crops with age 0-3; natural
            // placement uses the mature state, just like the source SinglePlantFeature.
            crop("amber_root", of(BiomeSurface.AMBER_LAND), 1, 5, SoilSet.AMBER_MOSS,
                    "amber_root_seed"),
            g("blooming_cooksonia", of(BiomeSurface.FLOWER_ISLETS, BiomeSurface.GLOWING_GRASSLANDS,
                    BiomeSurface.WATERFALL_PONDS), 5, 5, SoilSet.END_MOSS),
            crop("blossom_berry", of(BiomeSurface.BLOSSOMING_SPIRES), 3, 4, SoilSet.PINK_MOSS,
                    "blossom_berry_seed"),
            g("bolux_mushroom", of(BiomeSurface.LANTERN_WOODS), 2, 5, SoilSet.RUTISCUS),
            g("bushy_grass", of(BiomeSurface.BLOSSOMING_SPIRES), 10, 8, SoilSet.PINK_MOSS),
            // Same block as the row above, a second placement at a different radius/density
            // (placed_feature/bushy_grass_wg.json), so the row name is NOT the block id.
            row("bushy_grass_wg", Engine.GROUND, of(BiomeSurface.BLOSSOMING_SPIRES), 8, 5,
                    SoilSet.PINK_MOSS, List.of("bushy_grass"), PLAIN),
            g("chorus_grass", of(BiomeSurface.CHORUS_FOREST), 3, 4, SoilSet.CHORUS_NYLIUM),
            crop("chorus_mushroom", of(BiomeSurface.CHORUS_FOREST), 1, 3, SoilSet.CHORUS_NYLIUM,
                    "chorus_mushroom_seed"),
            g("clawfern", of(BiomeSurface.DRAGON_GRAVEYARDS), 5, 5, SoilSet.END_BONE),
            g("creeping_moss", of(BiomeSurface.FOGGY_MUSHROOMLAND, BiomeSurface.MEGALAKE,
                            BiomeSurface.MEGALAKE_GROVE, BiomeSurface.NEON_OASIS,
                            BiomeSurface.WATERFALL_PONDS),
                    3, 5, SoilSet.MOSS_OR_MYCELIUM),
            row("creeping_moss_rare", Engine.GROUND, of(BiomeSurface.FLOWER_ISLETS,
                            BiomeSurface.GLOWING_GRASSLANDS), 2, 3, SoilSet.MOSS_OR_MYCELIUM,
                    List.of("creeping_moss"), PLAIN),
            g("crystal_grass", of(BiomeSurface.CRYSTAL_MOUNTAINS), 20, 8, SoilSet.CRYSTAL_MOSS),
            g("flammalix", of(BiomeSurface.UMBRA_VALLEY), 5, 3, SoilSet.PALLIDIUM),
            g("fracturn", of(BiomeSurface.FLOWER_ISLETS, BiomeSurface.GLOWING_GRASSLANDS),
                    5, 5, SoilSet.END_MOSS),
            g("globulagus", of(BiomeSurface.DRAGON_GRAVEYARDS, BiomeSurface.WATERFALL_PONDS),
                    6, 5, SoilSet.END_BONE),
            g("inflexia", of(BiomeSurface.UMBRA_VALLEY), 16, 7, SoilSet.PALLIDIUM),
            g("jungle_grass", of(BiomeSurface.UMBRELLA_JUNGLE), 6, 7, SoilSet.SOIL),
            g("lamellarium", of(BiomeSurface.DRY_SHRUBLAND, BiomeSurface.FLOWER_ISLETS,
                    BiomeSurface.LANTERN_WOODS), 6, 5, SoilSet.RUTISCUS),
            g("lutebus", of(BiomeSurface.DRY_SHRUBLAND, BiomeSurface.FLOWER_ISLETS),
                    5, 5, SoilSet.RUTISCUS),
            g("murkweed", of(BiomeSurface.SHADOW_FOREST), 2, 3, SoilSet.SHADOW_GRASS),
            g("needlegrass", of(BiomeSurface.SHADOW_FOREST), 1, 3, SoilSet.SHADOW_GRASS),
            g("orango", of(BiomeSurface.DRY_SHRUBLAND), 6, 5, SoilSet.RUTISCUS),
            g("salteago", of(BiomeSurface.FLOWER_ISLETS, BiomeSurface.GLOWING_GRASSLANDS,
                    BiomeSurface.WATERFALL_PONDS), 5, 5, SoilSet.END_MOSS),
            // The only PottableCropBlock in the ship set: SinglePlantFeature.java:50-52 stamps
            // age=3 rather than the random age a BasePlantWithAgeBlock gets.
            crop("shadow_berry", of(BiomeSurface.SHADOW_FOREST), 1, 2, SoilSet.SHADOW_GRASS,
                    "shadow_berry"),
            g("shadow_plant", of(BiomeSurface.SHADOW_FOREST), 5, 6, SoilSet.SHADOW_GRASS),
            row("small_amaranita", Engine.GROUND, of(BiomeSurface.DRAGON_GRAVEYARDS), 4, 5,
                    SoilSet.END_BONE, List.of("small_amaranita_mushroom"), PLAIN),
            // BaseAttachedBlock, so the floor form is facing=up and needs only a solid block under
            // it (BaseAttachedBlock.java:51-54, canSupportCenter).
            row("small_jellyshroom_floor", Engine.GROUND, of(BiomeSurface.UMBRELLA_JUNGLE), 2, 5,
                    SoilSet.ANY, List.of("small_jellyshroom"), p(Prop.FACING_6)),
            row("twisted_umbrella_moss_rare", Engine.GROUND, of(BiomeSurface.FLOWER_ISLETS,
                            BiomeSurface.GLOWING_GRASSLANDS), 2, 3,
                    SoilSet.JUNGLE_MOSS_OR_MYCELIUM, List.of("twisted_umbrella_moss"), PLAIN),
            row("umbrella_moss_rare", Engine.GROUND, of(BiomeSurface.GLOWING_GRASSLANDS), 2, 3,
                    SoilSet.SOIL, List.of("umbrella_moss"), PLAIN),
            g("vaiolush_fern", of(BiomeSurface.FLOWER_ISLETS, BiomeSurface.GLOWING_GRASSLANDS),
                    5, 5, SoilSet.END_MOSS),

            // --- DOUBLE, DoublePlantFeature: ids[0] short, ids[1] tall ------------------------
            new Row("umbrella_moss", Engine.DOUBLE, of(BiomeSurface.FLOWER_ISLETS,
                    BiomeSurface.FOGGY_MUSHROOMLAND, BiomeSurface.MEGALAKE,
                    BiomeSurface.MEGALAKE_GROVE, BiomeSurface.NEON_OASIS,
                    BiomeSurface.WATERFALL_PONDS), 3, 5, 1,
                    SoilSet.SOIL, SoilSet.JUNGLE_MOSS_OR_MYCELIUM,
                    List.of("umbrella_moss", "umbrella_moss_tall"), p(Prop.NONE, Prop.HALF)),
            row("twisted_umbrella_moss", Engine.DOUBLE, of(BiomeSurface.UMBRELLA_JUNGLE), 3, 6,
                    SoilSet.JUNGLE_MOSS_OR_MYCELIUM,
                    List.of("twisted_umbrella_moss", "twisted_umbrella_moss_tall"),
                    p(Prop.NONE, Prop.HALF)),

            // --- WALL, WallScatterFeature + WallPlantFeature (stone backing only) -------------
            wall("bulb_moss", of(BiomeSurface.AMBER_LAND), 1, 6),
            wall("cyan_moss", of(BiomeSurface.FOGGY_MUSHROOMLAND, BiomeSurface.UMBRELLA_JUNGLE),
                    15, 3),
            wall("ruscus", of(BiomeSurface.LANTERN_WOODS), 10, 6),
            row("small_jellyshroom_wall", Engine.WALL, of(BiomeSurface.UMBRELLA_JUNGLE), 4, 4,
                    SoilSet.ANY, List.of("small_jellyshroom"), p(Prop.FACING_6)),
            wall("tail_moss", of(BiomeSurface.CHORUS_FOREST, BiomeSurface.SHADOW_FOREST), 15, 3),
            wall("twisted_moss", of(BiomeSurface.BLOSSOMING_SPIRES), 15, 6),

            // --- CEILING, SingleInvertedScatterFeature ----------------------------------------
            row("small_jellyshroom_ceil", Engine.CEILING, of(BiomeSurface.UMBRELLA_JUNGLE), 8, 8,
                    SoilSet.ANY, List.of("small_jellyshroom"), p(Prop.FACING_6)),

            // --- COLUMN, the 4 vertical stamps; see FloraPlanner.column -----------------------
            new Row("lanceleaf", Engine.COLUMN, of(BiomeSurface.AMBER_LAND), 2, 7, 5,
                    SoilSet.AMBER_MOSS, SoilSet.AMBER_MOSS, List.of("lanceleaf"), p(Prop.PENTA)),
            new Row("glow_pillar", Engine.COLUMN, of(BiomeSurface.AMBER_LAND,
                    BiomeSurface.FLOWER_ISLETS), 1, 9, 10, SoilSet.AMBER_MOSS, SoilSet.AMBER_MOSS,
                    List.of("glowing_pillar_roots", "glowing_pillar_luminophor",
                            "glowing_pillar_leaves"), p(Prop.TRIPLE, Prop.NONE, Prop.FACING_6)),
            // No config, so no disc: the count_on_every_layer position IS the plant position.
            row("lumecorn", Engine.COLUMN, of(BiomeSurface.GLOWING_GRASSLANDS), 5, 0,
                    SoilSet.END_STONES, List.of("lumecorn"), p(Prop.LUMECORN)),
            row("large_amaranita", Engine.COLUMN, of(BiomeSurface.DRAGON_GRAVEYARDS), 5, 0,
                    SoilSet.END_STONES, List.of("large_amaranita_mushroom"), p(Prop.TRIPLE)),

            // --- SKY, SkyScatterFeature + FilaluxFeature --------------------------------------
            new Row("filalux", Engine.SKY, of(BiomeSurface.LANTERN_WOODS), 1, 10, 10,
                    SoilSet.ANY, SoilSet.ANY,
                    List.of("filalux", "filalux_lantern", "filalux_wings"),
                    p(Prop.TRIPLE, Prop.NONE, Prop.FACING_6)));

    private static Row g(String name, Set<BiomeSurface> hosts, int max, int radius, SoilSet soil) {
        return row(name, Engine.GROUND, hosts, max, radius, soil, List.of(name), PLAIN);
    }

    private static Row crop(String name, Set<BiomeSurface> hosts, int max, int radius,
                            SoilSet soil, String blockId) {
        return row(name, Engine.GROUND, hosts, max, radius, soil, List.of(blockId), p(Prop.AGE3));
    }

    private static Row wall(String name, Set<BiomeSurface> hosts, int max, int radius) {
        return row(name, Engine.WALL, hosts, max, radius, SoilSet.ANY, List.of(name),
                p(Prop.FACING_H));
    }

    private static Row row(String name, Engine engine, Set<BiomeSurface> hosts, int max, int radius,
                           SoilSet soil, List<String> ids, List<Prop> props) {
        return new Row(name, engine, hosts, max, radius, 1, soil, soil, ids, props);
    }
}
