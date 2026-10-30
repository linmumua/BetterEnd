package dev.mumu.betterend.biome;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * BetterEnd's 27 biomes, transcribed from the mod's own per-biome data at
 * {@code Reference/BetterEnd/src/main/generated/data/betterend/wover/worldgen/biome_data/*.json}.
 * <p>
 * Pure data: no Bukkit, no CraftEngine, nothing outside {@code java.*}, so the terrain generator,
 * {@link BiomePlacement} and the unit tests can all read the same table. Every value an enum
 * constant carries is a literal from those json files; nothing here is derived or guessed.
 * <p>
 * {@code minecraft:end_stone} is spelled out rather than pulled from a constant because Java
 * forbids an enum constant's arguments from naming a static field of its own class.
 */
public enum BiomeSurface {
    // --- land: the 16 wover:is_end/land biomes plus chorus_forest (highland) and dust_wastelands
    // (midland). Under the PAULEVS generator the highland and midland tags are merged into one
    // pool (EndLandBiomeDecider.java:46-55, EndTags.IS_END_HIGH_OR_MIDLAND), so all 18 are one
    // category here and the ring distinction is dropped.
    AMBER_LAND("amber_land", Category.LAND,
            "betterend:amber_moss", "minecraft:end_stone", "betterend:amber_moss", true),
    BLOSSOMING_SPIRES("blossoming_spires", Category.LAND,
            "betterend:pink_moss", "minecraft:end_stone", "betterend:pink_moss", false),
    CHORUS_FOREST("chorus_forest", Category.LAND,
            "betterend:chorus_nylium", "minecraft:end_stone", "betterend:chorus_nylium", true),
    CRYSTAL_MOUNTAINS("crystal_mountains", Category.LAND,
            "betterend:crystal_moss", "minecraft:end_stone", "betterend:crystal_moss", true),
    DRAGON_GRAVEYARDS("dragon_graveyards", Category.LAND,
            "betterend:sangnum", "minecraft:end_stone", "betterend:sangnum", true, 0.1),
    DRY_SHRUBLAND("dry_shrubland", Category.LAND,
            "betterend:rutiscus", "minecraft:end_stone", "betterend:rutiscus", true),
    DUST_WASTELANDS("dust_wastelands", Category.LAND,
            "betterend:endstone_dust", "minecraft:end_stone", "betterend:endstone_dust",
            true, 1.0, 1.5, true),
    FOGGY_MUSHROOMLAND("foggy_mushroomland", Category.LAND,
            "betterend:end_moss", "minecraft:end_stone", "betterend:end_mycelium", true),
    GLOWING_GRASSLANDS("glowing_grasslands", Category.LAND,
            "betterend:end_moss", "minecraft:end_stone", "betterend:end_moss", true),
    LANTERN_WOODS("lantern_woods", Category.LAND,
            "betterend:rutiscus", "minecraft:end_stone", "betterend:rutiscus", true),
    MEGALAKE("megalake", Category.LAND,
            "betterend:end_moss", "minecraft:end_stone", "betterend:endstone_dust",
            true, 1.0, 0.0, true),
    MEGALAKE_GROVE("megalake_grove", Category.LAND,
            "betterend:end_moss", "minecraft:end_stone", "betterend:end_moss",
            true, 1.0, 0.0, true),
    NEON_OASIS("neon_oasis", Category.LAND,
            "betterend:endstone_dust", "minecraft:end_stone", "betterend:end_moss",
            true, 0.5, 0.1, true),
    PAINTED_MOUNTAINS("painted_mountains", Category.LAND,
            "betterend:endstone_dust", "minecraft:end_stone", "betterend:endstone_dust",
            true, 1.0, 0.1, true),
    SHADOW_FOREST("shadow_forest", Category.LAND,
            "betterend:shadow_grass", "minecraft:end_stone", "betterend:shadow_grass", true),
    // biome_data asks for betterend:brimstone[active=false]; the CraftEngine block is single-state
    // and that state IS the inactive one, so the property is dropped rather than approximated.
    SULPHUR_SPRINGS("sulphur_springs", Category.LAND,
            "betterend:brimstone", "minecraft:end_stone", "betterend:sulphuric_rock",
            false, 1.0, 0.0, true),
    UMBRA_VALLEY("umbra_valley", Category.LAND,
            "betterend:umbralith", "betterend:umbralith", "betterend:pallidium_full",
            true, 1.0, 0.1, true),
    UMBRELLA_JUNGLE("umbrella_jungle", Category.LAND,
            "betterend:jungle_moss", "minecraft:end_stone", "betterend:jungle_moss", true),

    // --- small island (wover:is_end/small_island): the mod's air/ package, placed as ordinary
    // void-column biomes rather than through a separate decider (EndBiomesProvider.java:84-104).
    // All three ship even though end_moss is missing from the CraftEngine pack. Not because their
    // columns are always void -- BiomePlacement.isLand samples only the quart's corner column and
    // only every 4th y (BiomePlacement.java:127-134), so a small-island quart CAN hold terrain and
    // CAN be asked for a surface block. It ships because {@link #resolve} degrades a missing top to
    // the filler and says so once, and because shipping only ice_starfield would give a
    // 0.01-weight biome 100% of the void.
    FLOWER_ISLETS("flower_islets", Category.SMALL_ISLAND,
            "betterend:end_moss", "minecraft:end_stone", "betterend:end_moss",
            false, 0.4, 0.1, true),
    ICE_STARFIELD("ice_starfield", Category.SMALL_ISLAND,
            "minecraft:end_stone", "minecraft:end_stone", "minecraft:end_stone",
            false, 0.01, 0.1, true),
    WATERFALL_PONDS("waterfall_ponds", Category.SMALL_ISLAND,
            "betterend:end_moss", "minecraft:end_stone", "betterend:end_moss",
            false, 0.13, 0.1, true),

    // --- cave (betterend:is_end_cave): none of the six sets genChance, so the pool is uniform.
    EMPTY_AURORA_CAVE("empty_aurora_cave", Category.CAVE,
            "minecraft:end_stone", "minecraft:end_stone", "minecraft:end_stone",
            false, 1.0, 0.1, true),
    EMPTY_END_CAVE("empty_end_cave", Category.CAVE,
            "minecraft:end_stone", "minecraft:end_stone", "minecraft:end_stone",
            false, 1.0, 0.1, true),
    EMPTY_SMARAGDANT_CAVE("empty_smaragdant_cave", Category.CAVE,
            "minecraft:end_stone", "minecraft:end_stone", "minecraft:end_stone",
            false, 1.0, 0.1, true),
    JADE_CAVE("jade_cave", Category.CAVE,
            "minecraft:end_stone", "minecraft:end_stone", "minecraft:end_stone",
            false, 1.0, 0.1, true),
    LUSH_AURORA_CAVE("lush_aurora_cave", Category.CAVE,
            "betterend:cave_moss", "minecraft:end_stone", "betterend:cave_moss", false),
    LUSH_SMARAGDANT_CAVE("lush_smaragdant_cave", Category.CAVE,
            "betterend:cave_moss", "minecraft:end_stone", "betterend:cave_moss", false);

    /** The ring the biome source hands to the picker, after BetterEnd's own deciders retag it. */
    public enum Category { LAND, SMALL_ISLAND, CAVE }

    /**
     * EndBiomes.java:34-36 - the three sub-biomes and their parents. A biome with a parent is NOT
     * top-level pickable (WoverBiomeData.java:476-478, enforced at WoverBiomeSourceImpl.java:99-103);
     * it reaches the world only through {@code HexBiomeMap.Picker.subBiome}, i.e. through the
     * 1-in-4 speckle pass at HexBiomeChunk.java:76-92 and only over its own parent's cells.
     * <p>
     * A static map rather than a constructor argument because Java forbids an enum constant's
     * arguments from naming another constant of the same enum.
     */
    private static final Map<BiomeSurface, BiomeSurface> PARENT = Map.of(
            MEGALAKE_GROVE, MEGALAKE,
            NEON_OASIS, DUST_WASTELANDS,
            PAINTED_MOUNTAINS, DUST_WASTELANDS);

    private static final Map<String, BiomeSurface> BY_ID = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(BiomeSurface::id, Function.identity()));

    private final String id;
    private final Category category;
    private final String top;
    private final String under;
    private final String alt;
    private final boolean hasCaves;
    private final double genChance;
    private final double terrainHeight;
    private final boolean shipped;

    BiomeSurface(String id, Category category, String top, String under, String alt, boolean hasCaves) {
        this(id, category, top, under, alt, hasCaves, 1.0);
    }

    BiomeSurface(String id, Category category, String top, String under, String alt, boolean hasCaves,
                 double genChance) {
        this(id, category, top, under, alt, hasCaves, genChance, 0.1, true);
    }

    BiomeSurface(String id, Category category, String top, String under, String alt, boolean hasCaves,
                 double genChance, double terrainHeight, boolean shipped) {
        this.id = id;
        this.category = category;
        this.top = top;
        this.under = under;
        this.alt = alt;
        this.hasCaves = hasCaves;
        this.genChance = genChance;
        this.terrainHeight = terrainHeight;
        this.shipped = shipped;
    }

    /** Path of the biome's registry key; the namespace is always {@code betterend}. */
    public String id() {
        return id;
    }

    /** {@code betterend:<id>} - the key the datapack registers and the provider looks up. */
    public String key() {
        return "betterend:" + id;
    }

    public Category category() {
        return category;
    }

    /**
     * The biome this one is a sub-biome of, or {@code null} if it is top-level. See {@link #PARENT}.
     */
    public BiomeSurface parent() {
        return PARENT.get(this);
    }

    /**
     * Surface block id, {@code namespace:path}. Resolve it through CraftEngine, not through Bukkit
     * {@code Material}: every {@code betterend:} id here is a CraftEngine block disguised as a
     * vanilla state.
     */
    public String top() {
        return top;
    }

    public String under() {
        return under;
    }

    /** The second block the mod's floor rule speckles the top layer with. */
    public String alt() {
        return alt;
    }

    /** Whether a cave biome may replace this one inside the cave band (EndCaveBiomeDecider.java:204-209). */
    public boolean hasCaves() {
        return hasCaves;
    }

    /** Relative pick weight inside {@link #category()}. 1.0 unless biome_data sets {@code genChance}. */
    public double genChance() {
        return genChance;
    }

    /**
     * What drives the island's radial noise map: {@code getAverageDepth(...) * 0.5F} at
     * TerrainGenerator.java:188, via {@code IslandField.columnHeight}. Only four of the 27
     * biome_data files set it - dust_wastelands 1.5, and megalake / megalake_grove /
     * sulphur_springs 0.0, which trip the {@code < 0.1F} early-out at TerrainGenerator.java:252 and
     * so are genuinely FLAT islands. The other 23 carry the codec default 0.1
     * (WoverBiomeDataImpl.java:25, WoverBiomeBuilder.java:243), spelled out in the rows above
     * because Java forbids an enum constant's arguments from naming a field of its own class.
     * <p>
     * A plain {@code double}, not an Optional: the codec default means there is no absent case.
     */
    public double terrainHeight() {
        return terrainHeight;
    }

    /**
     * Whether the bundled datapack registers this biome. All 27 ship as of the block conversion that
     * landed end_moss and pallidium_full -- every surface block the table names is now a defined
     * CraftEngine id. The flag stays because {@link BiomePlacement} leaves an unshipped biome out of
     * its pools, so a future biome can be held back until its datapack json and blocks both exist;
     * shipping one without a json is worse than not shipping it, because the provider then resolves
     * null and silently degrades that biome to a vanilla fallback.
     */
    public boolean shipped() {
        return shipped;
    }

    /**
     * The ids this biome's surface actually uses once the pack's gaps are worked around, and
     * whether it carries the dust band. Ids, not blocks, so the whole fallback rule stays pure
     * Java and unit-testable; {@code DustWastelandsGenerator} maps them to BlockData once.
     *
     * @param alt {@code null} means "no speckle" -- either the biome sets alt == top, or the pack
     *            does not define alt. The hot path then tests one reference instead of comparing
     *            two BlockData.
     */
    public record Resolved(String top, String under, String alt, boolean dusty) {
    }

    /**
     * Falls back around whatever the CraftEngine pack is missing: top -> under -> end stone, and
     * alt -> nothing. Never to another biome's signature block -- plain end stone under a named
     * biome reads as unfinished, {@code endstone_dust} under {@code umbra_valley} reads as wrong.
     * <p>
     * {@code dusty} is derived from the DECLARED top, not the resolved one: it is true for exactly
     * dust_wastelands, neon_oasis and painted_mountains, the biomes whose surface rule carries
     * {@code add_surface_depth} (surface_rules/dust_wastelands.json:23-38, neon_oasis.json).
     *
     * @param defined does the pack define this block id
     * @param broken  receives one {@code "<biome> <slot> <missing id> -> <used id>"} line per gap
     */
    public Resolved resolve(Predicate<String> defined, Consumer<String> broken) {
        String usedUnder = under;
        if (!defined.test(usedUnder)) {
            broken.accept(key() + " under " + under + " -> minecraft:end_stone");
            usedUnder = "minecraft:end_stone";
        }
        String usedTop = top;
        if (!defined.test(usedTop)) {
            broken.accept(key() + " top " + top + " -> " + usedUnder);
            usedTop = usedUnder;
        }
        String usedAlt = null;
        if (!alt.equals(top)) {
            if (defined.test(alt)) {
                usedAlt = alt;
            } else {
                broken.accept(key() + " alt " + alt + " -> " + usedTop);
            }
        }
        return new Resolved(usedTop, usedUnder, usedAlt, "betterend:endstone_dust".equals(top));
    }

    /** Lookup by {@link #id()} or by {@link #key()}; null when nothing matches. */
    public static BiomeSurface byId(String id) {
        return BY_ID.get(id.startsWith("betterend:") ? id.substring("betterend:".length()) : id);
    }
}
