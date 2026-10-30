package dev.mumu.betterend.biome;

import dev.mumu.betterend.config.BetterEndConfig;
import dev.mumu.betterend.terrain.IslandField;
import dev.mumu.betterend.terrain.OpenSimplexNoise;
import dev.mumu.betterend.vanilla.VanillaEndCore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

/**
 * Where BetterEnd's biomes go, ported from the mod's PAULEVS path:
 * {@code EndLandBiomeDecider} (the whole land algorithm is its six-line {@code suggestType}) and
 * {@code EndCaveBiomeDecider} (a horizontal cave slab substituted into land columns).
 * <p>
 * Contains no Bukkit: {@link BetterEndBiomeProvider} is the thin adapter that turns the
 * {@link BiomeSurface} constants returned here into registry biomes, and the unit tests drive this
 * class directly. Immutable after construction - the only mutable field is a pure memo of a pure
 * function - so every method is safe to call from any number of worldgen or region threads.
 * <p>
 * There is no border blending anywhere, and there must not be: the mod's only edge mechanism is
 * edge-biome substitution and BetterEnd defines no edge biomes, so every boundary is a hard
 * per-cell switch (HexBiomeMap.java:56-77). What makes it read as organic is the cell shape and
 * size in {@link HexBiomeMap}, not smoothing.
 * <p>
 * Two vanilla regions exist and this class owns the outer one. Inside
 * {@code innerVoidRadiusSquared} (radius 1024) the mod paints vanilla center / barrens biomes,
 * which is what {@link #ring} answers; {@code VanillaEndCore.inCoreQuart} (radius 384) is the
 * inner one, which must stay {@code the_end} for the dragon fight whatever the terrain says.
 * {@link #at} deliberately knows about neither - every caller that PAINTS the world checks
 * {@link #ring} first.
 */
public final class BiomePlacement {
    /** WoverEndConfig.java:55 DEFAULT_CAVE_BIOMES_TOP_Y, with the 32 -> 56 -> 48 history its
     *  javadoc at WoverEndConfig.java:42-54 records. (:425/:430 still say 32; those are stale.) */
    private static final int CAVE_TOP_Y = 48;
    /** WoverEndConfig.java:59 DEFAULT_CAVE_BIOMES_TOP_JITTER = 8. Confirmed, exactly. */
    private static final double CAVE_TOP_JITTER = 8.0;
    /** EndCaveBiomeDecider.java:59,196 - the ceiling's noise frequency. */
    private static final double CAVE_JITTER_FREQUENCY = 0.03;
    /** EndCaveBiomeDecider.java:67,175-178 - period ~250 blocks, threshold picked for ~40-50% coverage. */
    private static final double CAVE_REGION_FREQUENCY = 0.004;
    private static final double CAVE_REGION_THRESHOLD = 0.1;

    /**
     * WoverEndConfig.java:104-108 - land, void, center and barrens biome size, all 256 blocks under
     * MINECRAFT_18 (the HEX + PAULEVS preset BetterEnd's land decider requires,
     * EndLandBiomeDecider.java:21-26). At 256 the hex lattice runs at scale 32: 32-block row pitch,
     * 36.95-block column pitch, rotated 22.918 degrees.
     */
    private static final int LAND_BIOME_SIZE = 256;
    /** WoverEndConfig.java:41 via :109 - caveBiomesSize 128, i.e. half the linear cell. */
    private static final int CAVE_BIOME_SIZE = 128;

    /**
     * Top of the column scan; the mod's isLand tests y in [0, maxHeight] on the 4-block lattice.
     * <p>
     * There is no matching STEP constant any more: the step used to live here and was applied by
     * scanning a materialised column every fourth entry, and it is now the island field's own
     * lattice row spacing, applied inside {@code IslandField.anySolidOnLattice}. The two are the
     * same number for a reason worth stating - see that method - so they cannot silently diverge.
     */
    private static final int LAND_SCAN_TOP = 128;

    /**
     * The four outcomes of the mod's category layer, for the two that are NOT BetterEnd biomes.
     * <p>
     * WoverEndBiomeSource.java:327-341 tags every quart inside {@code innerVoidRadiusSquared} as
     * IS_END_CENTER; EndLandBiomeDecider.java:47-55 then splits that by
     * {@code TerrainGenerator.isLand} into IS_END_CENTER (mapCenter) and IS_END_BARRENS
     * (mapBarrens). Both of those pickers hold exactly one biome under BetterEnd, and both are
     * VANILLA: BetterEnd registers nothing under either tag, the center picker gets
     * {@code minecraft:the_end} from the tag walk (VanillaBiomeDataProvider.java:44-48) and the
     * barrens picker is force-fed {@code minecraft:end_barrens} unconditionally
     * (WoverEndBiomeSource.java:249-256, because vanilla models end_barrens as a highlands
     * SUB-biome so the tag walk never adds it). One biome each means no lattice is needed at all.
     */
    public enum Ring { CENTER, BARRENS }

    private final long seed;
    private final IslandField field;
    private final OpenSimplexNoise caveCeiling;
    private final OpenSimplexNoise caveRegion;
    private final HexBiomeMap landMap;
    private final HexBiomeMap voidMap;
    private final HexBiomeMap caveMap;

    private static final HexBiomeMap.Picker LAND = picker(BiomeSurface.Category.LAND);
    private static final HexBiomeMap.Picker SMALL_ISLAND = picker(BiomeSurface.Category.SMALL_ISLAND);
    private static final HexBiomeMap.Picker CAVE = picker(BiomeSurface.Category.CAVE);

    private static HexBiomeMap.Picker picker(BiomeSurface.Category category) {
        return new HexBiomeMap.Picker(Stream.of(BiomeSurface.values())
                .filter(b -> b.category() == category && b.shipped())
                .toList());
    }

    /**
     * Memo of {@link #isLand}, which depends only on the quart column. Keeps a chunk's ~1500
     * getBiome calls from re-running the same 16 column scans once per y level.
     * <p>
     * DELIBERATELY get/put and NOT {@code computeIfAbsent}: under {@link #bind} the island field
     * re-enters this map on the same thread inside one call
     * ({@code plane -> columnHeight -> terrainHeightAt -> at -> isLand}), and
     * {@code ConcurrentHashMap.computeIfAbsent} throws or livelocks on a recursive update.
     * <p>
     * ponytail: cleared wholesale past a cap rather than LRU-evicted, exactly like IslandField's
     * plane cache; a lost race just recomputes the same boolean.
     */
    private final Map<Long, Boolean> landCache = new ConcurrentHashMap<>();

    /**
     * Everything about one block column that does NOT depend on y: whether the column is land, and
     * which map - land or void - named its biome. Immutable, so it can be published through
     * {@link #columnPicks} with no synchronisation.
     */
    private record ColumnPick(long key, boolean land, BiomeSurface surface) {
    }

    /** Power of two; {@link #columnPicks} is a fixed array of {@code 1 << this}. */
    static final int COLUMN_CACHE_BITS = 12;

    /**
     * Memo of {@link #columnPick}, and the single largest saving in this class.
     * <p>
     * {@link #at} is asked once per quart per Y LEVEL - vanilla's biome fill calls the provider
     * 4 x 4 x 96 = 1536 times for the 16 distinct columns of one chunk - and only its last line
     * depends on y ({@link #inCaveBand}'s {@code y > ceiling}). Everything above it is
     * {@code isLand} plus a {@link HexBiomeMap#at} lookup, and that lookup is TEN 2-D simplex
     * evaluations ({@code getNoise} twice, five octaves each). Recomputing it per Y level is 96
     * times the work for the identical answer.
     * <p>
     * It is also what makes {@link #terrainHeightAt} expensive, which is the amplification on
     * locate's {@code topY} path: {@code IslandField.computeColumnHeight} samples a 29-point disc
     * plus its centre, so ONE cold lattice column asks 30 of these, and adjacent lattice columns
     * share most of that 7x7-quart neighbourhood.
     * <p>
     * A fixed direct-mapped array, not the {@code ConcurrentHashMap} every other memo here uses,
     * for two reasons. It CANNOT grow - the bound is the array's own length, so a locate scan over
     * hundreds of thousands of columns cannot inflate it - and it does not box: a
     * {@code Map<Long, ...>} get allocates a {@code Long} per call, which at this call rate is
     * gigabytes of garbage for nothing.
     * <p>
     * Racing writers overwrite each other's slots; a racing reader either sees {@code null} or
     * sees a {@link ColumnPick} whose key does not match, and recomputes either way. No thread can
     * observe a half-built entry, because the record's fields are final, so this is safe from any
     * number of Folia region threads. A lost race costs one redundant, identical computation -
     * never a different answer.
     */
    private final ColumnPick[] columnPicks = new ColumnPick[1 << COLUMN_CACHE_BITS];

    /**
     * Diagnostic only: how many times a column had to be COMPUTED rather than served from
     * {@link #columnPicks}. Nothing in the generator reads it; the tests use it to say "96 Y levels
     * of one column cost one lookup" without a stopwatch. A {@code LongAdder} so that two region
     * threads cannot contend on the very path this class exists to shorten.
     */
    private final LongAdder columnComputations = new LongAdder();

    /** See {@link #columnComputations}. */
    int columnComputations() {
        return columnComputations.intValue();
    }

    /**
     * The one place terrain and biomes are tied together, and the only correct way to build either.
     * {@link IslandField} needs the biome's terrainHeight per column (TerrainGenerator.java:188,
     * :247-260) and this class needs the field, so one reference has to be handed over after the
     * other object exists.
     * <p>
     * Nothing here can recurse. The reference is written before the placement is published and
     * never again; {@link #isLand} takes the field's biome-INDEPENDENT density path
     * ({@link IslandField#LAND_INTENSITY}), so the chain
     * {@code plane -> columnHeight -> terrainHeightAt -> at -> isLand} terminates at a density that
     * asks for no biome. That is the mod's own split: {@code TerrainGenerator.isLand} uses
     * {@code IslandLayer.getDensity(x, y, z)} (IslandLayer.java:138-140) while terrain fill uses
     * the 4-arg overload (IslandLayer.java:142-146).
     * <p>
     * A static factory rather than a knot inside the chunk generator, because it is pure Java and
     * the unit tests have to be able to build the bound pair without Bukkit or CraftEngine.
     */
    public static BiomePlacement bind(long seed, BetterEndConfig config) {
        AtomicReference<BiomePlacement> ref = new AtomicReference<>();
        IslandField field = new IslandField(seed, config, (qx, qz) -> ref.get().terrainHeightAt(qx, qz));
        ref.set(new BiomePlacement(seed, field));
        return ref.get();
    }

    /** Builds its own island field, with every biome at the terrainHeight codec default. Prefer
     *  {@link #bind} so the field's radial map sees the biomes actually painted. */
    public BiomePlacement(long seed) {
        this(seed, new IslandField(seed));
    }

    /** The field this placement reads terrain from, so callers share one density cache with it. */
    public IslandField field() {
        return field;
    }

    /**
     * The biome's terrainHeight at this quart column, for {@code IslandField}'s radial intensity.
     * The other half of {@link #bind}.
     * <p>
     * {@code y = 64 = caveBiomesTopY(48) + caveBiomesTopJitter(8) + 8}, which is the mod's own
     * sample height (TerrainGenerator.java:265-277): sampling at y=0 lands INSIDE the cave band, and
     * a cave biome's terrainHeight is not meant to drive surface terrain -- the mod's own comment
     * records that doing so flattened every hasCaves island. So this and
     * {@code DustWastelandsGenerator.fillSegment}, which samples the biome at the segment top, CAN
     * disagree for a segment tall enough to cross y=48. That is the mod's behaviour, deliberately;
     * do not "fix" it by sampling at the block, which would put a cave biome's height into the
     * surface shape.
     * <p>
     * The vanilla core is {@code minecraft:the_end}, which HAS a WoverBiomeData
     * (VanillaBiomeDataProvider.java:44-47) whose the_end.json omits terrainHeight, so it takes the
     * codec default -- it is NOT a flat island, and the port's old central-island flattening was
     * built on the belief that it was.
     */
    public float terrainHeightAt(int quartX, int quartZ) {
        if (ring(quartX << 2, quartZ << 2) != null) {
            // WoverBiomeDataImpl.java:25 - neither the_end.json nor end_barrens.json sets
            // terrainHeight (VanillaBiomeDataProvider.java:38-48), so both take the codec default.
            return 0.1f;
        }
        return (float) at(quartX << 2, 64, quartZ << 2).terrainHeight();
    }

    public BiomePlacement(long seed, IslandField field) {
        this.seed = seed;
        this.field = field;
        // EndCaveBiomeDecider.java:134-137 - salted straight off the world seed with no shared RNG,
        // deliberately, so the cave layout reproduces independently of generation order.
        this.caveCeiling = new OpenSimplexNoise(seed ^ 0xCA7E15L);
        this.caveRegion = new OpenSimplexNoise(seed ^ 0x0CA5E9A7EL);
        // WoverEndBiomeSource.java:211-233 - every map is built from the SAME world seed, so all of
        // them share one chunk hash and therefore one blob geometry; only the picker differs. The
        // cave decider builds its own map the same way (EndCaveBiomeDecider) at half the size.
        this.landMap = new HexBiomeMap(seed, LAND_BIOME_SIZE, LAND);
        this.voidMap = new HexBiomeMap(seed, LAND_BIOME_SIZE, SMALL_ISLAND);
        this.caveMap = new HexBiomeMap(seed, CAVE_BIOME_SIZE, CAVE);
    }

    /**
     * The vanilla ring the mod paints inside radius 1024, or {@code null} outside it. Every caller
     * that writes biomes or blocks must consult this BEFORE {@link #at}, which knows only about
     * BetterEnd's own biomes.
     * <p>
     * {@code dist} is computed at the quart's block corner, as WoverEndBiomeSource.java:302-308
     * does. The {@code VanillaEndCore} disc is folded in as CENTER unconditionally rather than by
     * terrain: the vanilla spike ring has to land on {@code the_end} whatever the island density
     * says, or a void quart under a spike centre silently drops that pillar and the dragon heals
     * from nine crystals instead of ten.
     */
    public Ring ring(int x, int z) {
        int posX = (x >> 2) << 2;
        int posZ = (z >> 2) << 2;
        if ((long) posX * posX + (long) posZ * posZ > IslandField.INNER_VOID_RADIUS_SQUARED) {
            return null;
        }
        if (VanillaEndCore.inCoreQuart(x, z)) {
            return Ring.CENTER;
        }
        return isLand(x >> 2, z >> 2) ? Ring.CENTER : Ring.BARRENS;
    }

    /**
     * The mod's biome-independent land test: does any part of this quart column hold terrain.
     * EndLandBiomeDecider.java:47 - the one and only terrain read in the whole placement path, and
     * the reason land/void is a boolean rather than an erosion ring.
     * <p>
     * BIOME-INDEPENDENT is load-bearing, not incidental: this is what breaks the cycle
     * {@link #bind} would otherwise close. It reads the 5-arg {@code densityColumn}, which forces
     * {@link IslandField#LAND_INTENSITY} instead of asking what biome is here, exactly as the mod's
     * {@code isLand} calls the 3-arg {@code IslandLayer.getDensity} (IslandLayer.java:138-140).
     * Routing this back onto the 4-arg path is an immediate stack overflow.
     * <p>
     * ponytail: cleared wholesale past a cap rather than LRU-evicted, and get/put rather than
     * computeIfAbsent -- see {@link #landCache}.
     */
    public boolean isLand(int quartX, int quartZ) {
        long key = ((long) quartX << 32) | (quartZ & 0xFFFFFFFFL);
        Boolean cached = landCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (landCache.size() > 1 << 16) {
            landCache.clear();
        }
        // The step is the island field's own lattice row spacing, and that is EXACT rather than a
        // sampling shortcut: at a y that is a multiple of it the interpolation's ty is exactly
        // 0.0, so the lattice row IS the interpolated value. See IslandField.anySolidOnLattice.
        boolean land = field.anySolidOnLattice(quartX << 2, quartZ << 2, 0, LAND_SCAN_TOP,
                IslandField.LAND_INTENSITY);
        landCache.put(key, land);
        return land;
    }

    /**
     * The land/void pick for one block column, memoised on {@link #columnPicks}. Everything it
     * computes is a pure function of {@code (x, z)}.
     */
    private ColumnPick columnPick(int x, int z) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        int slot = (int) (key * 0x9E3779B97F4A7C15L >>> (64 - COLUMN_CACHE_BITS));
        ColumnPick cached = columnPicks[slot];
        if (cached != null && cached.key() == key) {
            return cached;
        }
        columnComputations.increment();
        boolean land = isLand(x >> 2, z >> 2);
        ColumnPick built = new ColumnPick(key, land, land ? landMap.at(x, z) : voidMap.at(x, z));
        columnPicks[slot] = built;
        return built;
    }

    /**
     * The BetterEnd biome for a block position, as if the vanilla rings did not exist. Callers must
     * check {@link #ring} first - inside radius 1024 the answer is a vanilla biome this class does
     * not name. Pure, deterministic, and independent of call order.
     * <p>
     * y enters in exactly one place, {@link #inCaveBand}'s {@code y > ceiling}, which is why
     * everything above it goes through {@link #columnPick}. Reading a memo instead of recomputing
     * cannot change the answer: the memo's value is a pure function of the column, and a stored
     * entry only ever answers for its own key.
     */
    public BiomeSurface at(int x, int y, int z) {
        ColumnPick pick = columnPick(x, z);
        if (!pick.land()) {
            return pick.surface();
        }
        return inCaveBand(pick.surface(), x, y, z) ? caveMap.at(x, z) : pick.surface();
    }

    /**
     * {@link #at} for the quart CONTAINING this block, aligned exactly as the biome source aligns
     * it before calling the provider ({@code CustomWorldChunkManager.java:34}, QuartPos.toBlock).
     * <p>
     * The alignment is load-bearing even though {@link #at} quarters x and z itself: the cave band
     * reads raw x, y, z, so without it two block columns of one quart could disagree about the
     * cave slab, and the block written would answer a different question from the biome the player
     * reads off F3.
     */
    public BiomeSurface quartAt(int x, int y, int z) {
        return at(x & ~3, y & ~3, z & ~3);
    }

    /**
     * Whether a carver may start in this chunk, answered the way vanilla answers it.
     * <p>
     * {@code NoiseBasedChunkGenerator.applyCarvers} reads a start chunk's carver list from the biome
     * at its own {@code (minBlockX, 0, minBlockZ)}, and both of BetterEnd's carvers are listed by
     * the six {@code is_end_cave} biomes and by nothing else -- 6 of the 27 biome jsons carry a
     * non-empty {@code "carvers"} array, and all six are caves. A start chunk in any other biome
     * never instantiates a carver at all, so no cave begins there.
     * <p>
     * Sampled at y=0, which is the same corner and the same height the mod's own tunnel carver uses
     * for its cave-factor blend, and which is inside the cave band by construction (the band runs
     * from the world floor up to {@link #CAVE_TOP_Y}) -- so a cave-region column answers with its
     * cave biome here, never with the land biome sitting above it.
     * <p>
     * The vanilla rings are excluded outright: they are painted {@code minecraft:the_end} and
     * {@code minecraft:end_barrens}, neither of which lists a carver.
     */
    public boolean allowsCarvers(int x, int z) {
        return caveBiomeAt(x, z) != null;
    }

    /**
     * The cave biome owning this column, or null when it is not a cave column at all.
     * <p>
     * Sampled at y = 0, and that is not a choice the caller gets to make: the carvers and the cave
     * coat have to agree about which columns are caves, and one sampling height is the only way to
     * guarantee it. The height is free anyway -- {@link #inCaveBand} depends on y only through
     * {@code y > ceiling}, and the jittered ceiling is 48 +/- 8 and so never below 40, which is why
     * the mod's own coat feature sampling at y = 16 lands on the identical answer.
     */
    public BiomeSurface caveBiomeAt(int x, int z) {
        if (ring(x, z) != null) {
            return null;
        }
        BiomeSurface biome = at(x, 0, z);
        return biome.category() == BiomeSurface.Category.CAVE ? biome : null;
    }

    /**
     * EndCaveBiomeDecider.java:146-165 - all four conditions, in the mod's short-circuit order.
     * A cave biome replaces a land pick only below a noise-jittered ceiling, only where a coarse
     * region noise clears its threshold, and only if the land biome itself allows caves. Center,
     * small-island and barrens columns never get caves at any y.
     */
    private boolean inCaveBand(BiomeSurface land, int x, int y, int z) {
        if (!land.hasCaves()) {
            return false;
        }
        double ceiling = CAVE_TOP_Y
                + caveCeiling.eval(x * CAVE_JITTER_FREQUENCY, z * CAVE_JITTER_FREQUENCY) * CAVE_TOP_JITTER;
        if (y > ceiling) {
            return false;
        }
        return caveRegion.eval(x * CAVE_REGION_FREQUENCY, z * CAVE_REGION_FREQUENCY) > CAVE_REGION_THRESHOLD;
    }

    /**
     * Every biome {@link #at} can return, in enum order. Never empty. Includes the three sub-biomes
     * (megalake_grove, neon_oasis, painted_mountains), which are not top-level pickable but do reach
     * the world through their parent's speckle pass.
     */
    public static List<BiomeSurface> selectable() {
        return Stream.of(LAND, SMALL_ISLAND, CAVE)
                .flatMap(picker -> picker.members().stream())
                .sorted()
                .toList();
    }
}
