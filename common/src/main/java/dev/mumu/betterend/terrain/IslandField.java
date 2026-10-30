package dev.mumu.betterend.terrain;

import dev.mumu.betterend.config.BetterEndConfig;
import dev.mumu.betterend.config.LayerSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BetterEnd's End island terrain, ported from the mod's
 * {@code org.betterx.betterend.world.generator.TerrainGenerator} / {@code IslandLayer} /
 * {@code LayerOptions} and the bclib SDF primitives they compose.
 * <p>
 * Pure math: no Bukkit, no CraftEngine, nothing outside {@code java.*}. Immutable after
 * construction (the noise permutation tables are final and read-only), so one instance is
 * safe to call from every worldgen worker at once. Nothing here depends on generation order.
 * <p>
 * Every {@code SDF*} / {@code MHelper} / {@code SplineHelper} citation below is read off
 * {@code Reference/BCLib}, which is release/26.1, while WorldWeaver and BetterEnd are 26.2. These
 * are stable geometry internals with no version-gated code, but they are unverified against a
 * 26.2 BCLib.
 */
public final class IslandField {
    /**
     * Vanilla {@code minecraft:end} noise settings height. Load-bearing in three places at once -
     * island centre Y, the fade-out start and the hard air ceiling - so it is one constant, never
     * three, exactly as the mod carries one {@code maxHeight} variable for all three.
     * <p>
     * The VALUE is right; the old justification for it was not. The {@code /128} divisors in
     * GeneratorConfig.java:78,84,90 are not the noise height: LayerOptions.java:22-23,68-74 clamps
     * arguments 3 and 4 (averageHeight, heightVariation) to {@code [0, 1]}, so they are FRACTIONS;
     * LayerOptions.java:34-35 derives minY/maxY from them and IslandLayer.java:78 scales the result
     * by the runtime {@code maxHeight}. That runtime value is {@code noiseSettings.height()}
     * (TerrainGenerator.java:134,:221; NoiseBasedChunkGeneratorHeightMixin:87-93), which is in
     * neither BCLib nor WorldWeaver - it is vanilla data.
     * <p>
     * ponytail: unverified constant. It is derivable from the CFR decompile of
     * {@code paper-26.2.jar} this port already used for {@code EndSpikes}; read
     * {@code minecraft:end}'s noise-settings height off that rather than trusting this line.
     */
    private static final int MAX_HEIGHT = 128;
    /**
     * Highest Y that can ever be solid: {@link #density} hard-returns -1 at {@code py >= MAX_HEIGHT}
     * (:313 below), and a block only interpolates between lattice rows at or below its own cell, so
     * everything from MAX_HEIGHT up is air whatever the world's build height is. Public because a
     * caller scanning "the whole column" otherwise pays for 128 rows of guaranteed air.
     */
    public static final int TOP_SOLID_Y = MAX_HEIGHT - 1;
    /** TerrainGenerator.java:45-46 - the End's noise cell width and height. */
    private static final int SCALE_XZ = 8;
    private static final int SCALE_Y = 4;
    /** TerrainGenerator.java:165-166. */
    private static final float FADE_OUT_DIST = 27.0f;
    private static final float FADE_OUT_START = MAX_HEIGHT - (FADE_OUT_DIST + 1);
    /**
     * IslandLayer.java:93-98 - islands whose centre falls inside this radius of (0,0) are
     * dropped, which is the real spawn void. Confirmed exactly: WoverEndConfig.java:104
     * (MINECRAFT_18, the HEX + PAULEVS preset BetterEnd runs) takes MINECRAFT_17's value at
     * WoverEndConfig.java:86, {@code VANILLA.innerVoidRadiusSquared * 16 * 16}, and VANILLA's is
     * 4096 at WoverEndConfig.java:69. So {@code 4096 * 16 * 16} = 1,048,576 = radius 1024 blocks.
     */
    public static final long INNER_VOID_RADIUS_SQUARED = 4096L * 16L * 16L;
    /** WoverBiomeDataImpl.java:25 / WoverBiomeBuilder.java:243 - the terrainHeight codec default. */
    private static final float DEFAULT_TERRAIN_HEIGHT = 0.1f;
    /**
     * The radial intensity 23 of BetterEnd's 27 biomes produce, and the one the biome-INDEPENDENT
     * land test uses. TerrainGenerator.java:188 is {@code getAverageDepth(x << 1, z << 1) * 0.5F}
     * and {@link #COEF} sums to 1, so a uniform {@link #DEFAULT_TERRAIN_HEIGHT} source yields
     * exactly this. Public because {@code BiomePlacement.isLand} is the caller.
     * <p>
     * ponytail: the mod's own {@code isLand} reads {@code IslandLayer.getDensity(x, y, z)}
     * (IslandLayer.java:138-140), the overload that sets nothing, so it inherits whatever intensity
     * the last terrain column left on the shared static layer. 0.05 is the codec-default value that
     * almost always is, and the only choice that is deterministic. The alternative reading is 0.2
     * (SDFRadialNoiseMap's constructor default, IslandLayer.java:46-49), which only the very first
     * call after initNoise ever sees.
     */
    public static final float LAND_INTENSITY = DEFAULT_TERRAIN_HEIGHT * 0.5f;
    private static final float RADIAL_COS = (float) Math.cos(0.5);
    private static final float RADIAL_SIN = (float) Math.sin(0.5);

    /**
     * MathHelper.java:72-76 {@code getSeed(seed, x, y)}, the position hash WorldWeaver seeds a
     * per-cell RNG with. Int arithmetic with sign-propagating shifts, reproduced literally.
     * <p>
     * Public and living here because it is the ONE copy: {@link Layer#islandSeed} is
     * IslandLayer.java:53-57, which is character-for-character this same hash, and the hex biome
     * map needs it for HexBiomeMap.java:84. A second copy would be a second thing to get wrong.
     */
    public static int mixSeed(int seed, int x, int z) {
        int h = seed + x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    /** Deepest dust cap {@link #dustDepth} can return; bounds the scan in {@link #isEndStone}. */
    private static final int MAX_DUST = 5;

    /**
     * Lattice rows the cached planes cover: {@code py} from -64 (the lowest world bottom Bukkit
     * can hand us) to {@link #MAX_HEIGHT} + one cell. Everything at or above MAX_HEIGHT is a hard
     * -1, so the top two rows are both -1 and {@link #densityColumn} can clamp any higher y onto
     * them and still interpolate the right answer.
     */
    private static final int PLANE_MIN_CELL_Y = -16;
    private static final int PLANE_ROWS = MAX_HEIGHT / SCALE_Y - PLANE_MIN_CELL_Y + 2;

    /**
     * The window a LAND test can read: py 0 through {@link #MAX_HEIGHT} on the lattice, i.e.
     * {@link #LAND_ROWS} rows starting at {@link #PLANE_MIN_CELL_Y} + 16. Nothing below py 0 is
     * ever asked (the biome's land test scans {@code [0, MAX_HEIGHT]}) and nothing at or above
     * MAX_HEIGHT is solid ({@link #density} hard-returns -1 there), so a plane for the land test
     * needs those 33 rows and not {@link #PLANE_ROWS} 50.
     */
    private static final int LAND_MIN_PY = 0;
    /** py 0, 4, ..., MAX_HEIGHT - the rows {@link #anySolidOnLattice} computes. */
    private static final int LAND_ROWS = MAX_HEIGHT / SCALE_Y + 1;

    /**
     * TerrainGenerator.java:284-303, reproduced literally: the radius-3 disc of QUART offsets and
     * their normalised weights. {@code dist = MHelper.length(x, z) / 3F}, kept where
     * {@code dist <= 1} - 29 points - then {@code COEF[i] = dist / sum}, so COEF sums to 1 and a
     * uniform source of value v returns exactly v.
     * <p>
     * The CENTRE point's weight is its own distance, i.e. ZERO. It does not contribute to the mean
     * at all; it reaches the answer only through the early-out in {@link #columnHeight}.
     */
    private static final int[] OFFS_X;
    private static final int[] OFFS_Z;
    private static final float[] COEF;

    static {
        float sum = 0;
        List<int[]> pos = new ArrayList<>();
        List<Float> coef = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                // MHelper.java:174-175 - length is (float) Math.sqrt(x*x + z*z).
                float dist = (float) Math.sqrt(x * x + z * z) / 3F;
                if (dist <= 1) {
                    sum += dist;
                    coef.add(dist);
                    pos.add(new int[]{x, z});
                }
            }
        }
        OFFS_X = new int[pos.size()];
        OFFS_Z = new int[pos.size()];
        COEF = new float[coef.size()];
        for (int i = 0; i < COEF.length; i++) {
            OFFS_X[i] = pos.get(i)[0];
            OFFS_Z[i] = pos.get(i)[1];
            COEF[i] = coef.get(i) / sum;
        }
    }

    /** {@link #column}'s marker for "ask every lattice column for its own biome height". */
    private static final float PER_COLUMN = Float.NaN;

    /**
     * Memo of the density lattice, keyed on the lattice column. {@link #density} is a pure function
     * of (cellX, cellZ, py), so caching it cannot change what generates or make anything depend on
     * generation order - it only stops all 256 columns of a chunk re-evaluating the same nine
     * lattice columns, each node of which costs ~30 simplex evaluations.
     * <p>
     * The key carries the column's radial intensity too, because that is now a per-column biome
     * value ({@link #columnHeight}). Where a column takes the common {@link #LAND_INTENSITY} the
     * terrain path and the land path share one entry, so the common case costs one computation.
     * <p>
     * ponytail: cleared wholesale past a cap rather than LRU-evicted, and a lost race just
     * recomputes an identical array. Swap in a real eviction policy only if a profile asks for it.
     */
    private final ConcurrentHashMap<PlaneKey, double[]> planes = new ConcurrentHashMap<>();

    /** {@link #planes}' key. The intensity goes in as raw bits, so NaN and -0.0 behave. */
    private record PlaneKey(int cellX, int cellZ, int intensityBits) {
    }

    /** Slots in {@link #landPlanes}; a power of two so the index is a shift. */
    private static final int LAND_CACHE_BITS = 12;

    /** One entry of {@link #landPlanes}: the key it was built for, and the rows it holds. */
    private record LandPlane(PlaneKey key, double[] rows) {
    }

    /**
     * Memo of the LAND-WINDOW plane, and it is deliberately NOT {@link #planes}.
     * <p>
     * {@link #planes} holds {@link #PLANE_ROWS} rows because the terrain path asks for y from the
     * world floor (Bukkit hands {@code generateNoise} a {@code minY} of -64), while the land test
     * asks only for py 0..MAX_HEIGHT -- {@link #LAND_ROWS} rows. Serving the land test out of
     * {@link #planes} therefore makes it pay for 17 rows it can never read, on the path a single
     * {@code /locate structure} walks 40,401 times, four fresh lattice columns each.
     * <p>
     * It also keeps the land test's working set OUT of {@link #planes}: a locate scan inserts no
     * full-size plane at all, so the 1&lt;&lt;16 plane cap (and the wholesale clear behind it) is
     * no longer inflated by structure probing.
     * <p>
     * A fixed direct-mapped array, like {@link BiomePlacement}'s column memo and for the same
     * reasons: the bound is the array's own length, so no scan can grow it, and no lookup boxes a
     * key. Racing writers overwrite slots; a racing reader sees either {@code null} or an entry
     * whose key does not match, and recomputes. Entries are immutable and published as whole
     * objects, so no thread can observe a half-built row array -- {@code ConcurrentHashMap} plus
     * get/put and NOT {@code computeIfAbsent} would also work, but the re-entrancy that forces
     * get/put in {@link #plane} cannot happen here ({@code landPlane} asks no biome), while the
     * boxing can and does.
     */
    private final LandPlane[] landPlanes = new LandPlane[1 << LAND_CACHE_BITS];

    /**
     * Memo of the biome-derived lattice height. Keep the existing associative cache: a 4096-slot
     * direct-mapped experiment preserved values but regressed warmed sparse queries in the A/B probe.
     * Recursive biome/land queries still require get/put rather than computeIfAbsent.
     */
    private final ConcurrentHashMap<Long, Float> columnHeights = new ConcurrentHashMap<>();

    private final Layer big;
    private final Layer medium;
    private final Layer small;
    private final OpenSimplexNoise noise1;
    private final OpenSimplexNoise noise2;
    private final OpenSimplexNoise surfaceNoise;
    private final OpenSimplexNoise altNoise;
    /**
     * Where the per-column radial intensity comes from. Final and read-only from here; the one
     * implementation that is not a constant reads a reference published before this object is -
     * see {@code DustWastelandsGenerator.bind}.
     */
    private final TerrainHeights heights;

    /** Defaults, for callers that have no config of their own (and for the unit tests). */
    public IslandField(long seed) {
        this(seed, BetterEndConfig.DEFAULTS);
    }

    /** Every biome at the terrainHeight codec default - see {@link TerrainHeights#DEFAULT}. */
    public IslandField(long seed, BetterEndConfig config) {
        this(seed, config, TerrainHeights.DEFAULT);
    }

    /** TerrainGenerator.java:122-127 - LegacyRandomSource is the java.util.Random algorithm. */
    public IslandField(long seed, BetterEndConfig config, TerrainHeights heights) {
        this.heights = heights;
        Random random = new Random(seed);
        // GeneratorConfig.java:78,84,90, now owned by config.yml. IslandLayer.java:99-101 - only the
        // medium layer carries the pinned central island, so THAT flag stays per-layer and hardcoded.
        big = new Layer(random.nextInt(), config.bigIslands(), false);
        medium = new Layer(random.nextInt(), config.mediumIslands(), true);
        small = new Layer(random.nextInt(), config.smallIslands(), false);
        noise1 = new OpenSimplexNoise(random.nextInt());
        noise2 = new OpenSimplexNoise(random.nextInt());
        // ponytail: vanilla's coherent surface-depth noise is NOT FOUND in the reference tree
        // (no decompiled SurfaceSystem), so the dust cap rides its own field off the same seed.
        surfaceNoise = new OpenSimplexNoise(random.nextInt());
        // Drawn LAST, deliberately: appending a nextInt() shifts no earlier draw, so every seed
        // generates byte-identical terrain to the build before the speckle existed.
        altNoise = new OpenSimplexNoise(random.nextInt());
    }

    /**
     * Where a biome's alt surface block replaces its top block, one block deep on the segment floor.
     * {@code wover:threshold_condition} with scale_x/scale_z 0.1 and threshold 0.0, the shape the
     * mod's own floor speckle takes in surface_rules/amber_land.json (and in 15 other rule files;
     * in every one of them both branches happen to name the same block, so the condition is inert
     * there).
     * <p>
     * ponytail: the rule's {@code roughness} uniform(-0.4, 0.4) jitter is dropped -- the same field
     * with a crisper edge. Add the jitter only if the speckle reads too geometric in game.
     * <p>
     * ponytail: sulphur_springs is the ONLY shipped biome whose alt actually differs from its top,
     * and its rule is not this one -- it is a {@code wover:switch_rule} over
     * {@code betterend:sulphuric_surf} alternating sulphuric_rock and brimstone
     * (surface_rules/sulphur_springs.json), whose noise is not in this checkout. A 50/50 coherent
     * field at the same scale stands in for it. neon_oasis' alt is a {@code betterend:split_noise}
     * switch, likewise unported and moot while end_moss is missing from the pack.
     */
    public boolean altSurface(int x, int z) {
        return altNoise.eval(x * 0.1, z * 0.1) > 0;
    }

    public boolean isSolid(int x, int y, int z) {
        return densityColumn(x, z, y, y)[0] > 0;
    }

    /**
     * Highest solid y in the column, or {@link Integer#MIN_VALUE} if it is void.
     * <p>
     * Prefer this to the explicit-range overload for a whole-column question. The field produces
     * terrain only in {@code [0, TOP_SOLID_Y]} -- the density fades to nothing well before either
     * end -- so widening the scan to the world's own {@code -64..319} cannot change the answer and
     * costs three times the density evaluations to prove it.
     */
    public int topY(int x, int z) {
        return topY(x, z, 0, TOP_SOLID_Y);
    }

    /**
     * Highest solid y in {@code [minY, maxY]}, or {@link Integer#MIN_VALUE} if the column is void.
     * <p>
     * Scans the LATTICE rows top-down and interpolates only the cell it stops in, instead of
     * materialising {@code maxY - minY + 1} doubles and walking them. Every height query for a block
     * column goes through here -- {@code DustWastelandsGenerator.getBaseHeight} calls it once per
     * column it is asked about -- so that array was pure garbage per call: 192 doubles for the
     * {@code [-64, 127]} window the server asks for, produced and dropped every time.
     * <p>
     * Skipping a cell is EXACT, not a sampling shortcut. For finite rows a cell's value at any
     * {@code y} is {@code a + (b - a) * ty} with {@code ty} in {@code [0, 1)} and {@code a},
     * {@code b} the two lattice rows the cell spans, i.e. a convex combination of those two rows: it
     * can never exceed {@code max(a, b)}, so two non-positive rows prove all four blocks empty. The
     * cell the scan does stop in is then read with {@link #column}'s own expression, at the same
     * {@link #planeIndex} rows, so this and a full {@link #densityColumn} scan agree to the last
     * bit. {@code IslandFieldTest.topYAgreesWithTheFullColumnScan} is the pin.
     * <p>
     * The RANGE keeps the contract the array it replaces gave it, length {@code maxY - minY + 1}
     * and all: a length of zero ({@code maxY == minY - 1}) walks no row and answers
     * {@link Integer#MIN_VALUE}, and a NEGATIVE length throws {@link NegativeArraySizeException} out
     * of that same allocation. {@code IslandFieldTest.topYKeepsTheRangesLengthContract} pins both,
     * because a caller handing over an inverted range must not silently start getting a height back.
     */
    public int topY(int x, int z, int minY, int maxY) {
        // The weight setup below is column()'s, by hand, exactly as anySolidOnLattice does it: both
        // paths go through mix(), so the arithmetic cannot drift, and an equivalence test pins this
        // one against the full column.
        int cellX = Math.floorDiv(x, SCALE_XZ);
        int cellZ = Math.floorDiv(z, SCALE_XZ);
        double tx = (double) (x - cellX * SCALE_XZ) / SCALE_XZ;
        double tz = (double) (z - cellZ * SCALE_XZ) / SCALE_XZ;
        double w00 = (1 - tx) * (1 - tz);
        double w10 = tx * (1 - tz);
        double w01 = (1 - tx) * tz;
        double w11 = tx * tz;

        // Same four planes, same intensity as column(x, z, minY, maxY, PER_COLUMN): the biome-driven
        // per-lattice-column intensity, not a forced one. Nothing about the cache changes here.
        double[] p00 = plane(cellX, cellZ);
        double[] p10 = plane(cellX + 1, cellZ);
        double[] p01 = plane(cellX, cellZ + 1);
        double[] p11 = plane(cellX + 1, cellZ + 1);

        // The four planes are fetched first because column() fetched them BEFORE allocating, so this
        // guard stands exactly where that allocation's throw used to come from. The length is that
        // array's, expression and all, so both degenerate ranges keep the answer they had.
        int length = maxY - minY + 1;
        if (length < 0) throw new NegativeArraySizeException(Integer.toString(length));
        if (length == 0) return Integer.MIN_VALUE;

        int bottom = Math.floorDiv(minY, SCALE_Y);
        for (int cellY = Math.floorDiv(maxY, SCALE_Y); cellY >= bottom; cellY--) {
            int i = planeIndex(cellY);
            double a = mix(p00, p10, p01, p11, i, w00, w10, w01, w11);
            double b = mix(p00, p10, p01, p11, i + 1, w00, w10, w01, w11);
            // Both rows non-positive proves the whole cell empty: for finite rows the four blocks are
            // a convex combination of them, so none can exceed max(a, b). A NaN row FAILS this test
            // rather than passing it, so its cell falls through to the per-y scan below and answers
            // exactly as the full column does (NaN > 0 is false).
            if (a <= 0 && b <= 0) continue;
            int base = cellY * SCALE_Y;
            for (int y = Math.min(base + SCALE_Y - 1, maxY); y >= Math.max(base, minY); y--) {
                double ty = (double) (y - base) / SCALE_Y;
                if (a + (b - a) * ty > 0) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Thickness of the {@code betterend:endstone_dust} cap on a solid segment {@code segmentHeight}
     * blocks tall. The mod's surface rules put dust over {@code 2 + surfaceDepth} blocks plus the top
     * block (surface_rules/dust_wastelands.json:23-38), but the ceiling rule (json:7-22) keeps the
     * underside end stone - so a segment is never dust all the way down, and neither is this. Dust
     * is a falling block; a segment capped to the floor would be a column of falling sand over air.
     * <p>
     * The READING is confirmed: dust_wastelands.json:27-30 is
     * {@code {add_surface_depth: true, offset: 2, surface_type: "floor"}}, and the ceiling rule at
     * json:11-14 confirms the underside stays end stone.
     * <p>
     * ponytail: only vanilla's {@code surfaceDepth} noise itself is still unknown (vanilla
     * SurfaceSystem, in neither BCLib nor WorldWeaver); the coherent 1..5 band below stands in for
     * that one term. Same CFR-decompile route as {@link #MAX_HEIGHT}.
     * <p>
     * Adjacent, out of scope here: only 2 of the 27 surface_rules files set
     * {@code add_surface_depth} (dust_wastelands, neon_oasis), while {@code BiomeSurface.resolve}'s
     * {@code dusty} flag keys on the top block and so also catches painted_mountains. That is a
     * BiomeSurface bug, not an IslandField one.
     */
    public int dustDepth(int x, int z, int segmentHeight) {
        double n = surfaceNoise.eval(x * 0.0625, z * 0.0625);
        int depth = 3 + (int) Math.round(n * 2.0);
        return Math.max(0, Math.min(depth, segmentHeight - 1));
    }

    /**
     * Solid, and below the deepest surface cap this column could carry. This is what feature
     * placement must ask instead of reading blocks back: a populator runs after its neighbours may
     * or may not have written into its chunk, so a block read is visit-order dependent, and
     * CraftEngine's deceive-material makes it lie anyway.
     * <p>
     * It is deliberately conservative and knows nothing about biomes, because it is pure terrain.
     * Since per-biome surfaces landed it is no longer the same predicate as "the generator wrote
     * minecraft:end_stone here" in two directions, and neither costs anything: a biome with no dust
     * band caps only its one floor block, so this excludes up to 4 blocks of filler that ARE end
     * stone; and umbra_valley's filler is umbralith, which this still calls solid-below-the-cap.
     * The ore path pairs it with {@code DustWastelandsGenerator.fillsWithEndStone} for the second
     * one, which is the half that would otherwise be visible.
     */
    public boolean isEndStone(int x, int y, int z) {
        int lo = y - (MAX_DUST + 1);
        double[] column = densityColumn(x, z, lo, y + MAX_DUST + 1);
        int here = y - lo;
        if (column[here] <= 0) return false;
        int top = Integer.MIN_VALUE;
        for (int i = here + 1; i < column.length; i++) {
            if (column[i] <= 0) {
                top = lo + i - 1;
                break;
            }
        }
        if (top == Integer.MIN_VALUE) return true; // deeper than any dust cap can reach
        int bottom = lo;
        for (int i = here; i >= 0; i--) {
            if (column[i] <= 0) {
                bottom = lo + i + 1;
                break;
            }
        }
        return y < top - dustDepth(x, z, top - bottom + 1) + 1;
    }

    /**
     * Per-block density for the column at {@code (x, z)} over {@code [minY, maxY]} inclusive;
     * {@code > 0} means solid. Indexed by {@code y - minY}.
     * <p>
     * The mod evaluates density only on an 8(x) x 4(y) x 8(z) lattice and lets vanilla's
     * NoiseInterpolator trilerp between the corners (TerrainGenerator.java:442-466). Sampling
     * per block instead would both change the silhouette and cost 128x more, so the lattice and
     * the interpolation are reproduced here.
     */
    public double[] densityColumn(int x, int z, int minY, int maxY) {
        return column(x, z, minY, maxY, PER_COLUMN);
    }

    /**
     * {@link #densityColumn} with the radial intensity FORCED, so the caller never asks what biome
     * is here. It exists for exactly one caller, {@code BiomePlacement.isLand}, and for exactly one
     * reason: the biome source asks this class where the land is, so a land test that read a biome
     * would close a cycle. The mod breaks it the same way -- {@code TerrainGenerator.isLand} calls
     * {@code IslandLayer.getDensity(x, y, z)} (IslandLayer.java:138-140), the overload that sets no
     * intensity, while terrain fill calls the 4-arg one (IslandLayer.java:142-146).
     * <p>
     * Do not "unify" this with the 4-arg form. It is the cycle break.
     */
    public double[] densityColumn(int x, int z, int minY, int maxY, float intensity) {
        return column(x, z, minY, maxY, intensity);
    }

    /**
     * {@link #densityColumn} into a caller-owned buffer: the {@code maxY - minY + 1} doubles that
     * array would hold are written to {@code out[0 .. maxY - minY]} instead, and nothing past that
     * is touched.
     * <p>
     * It exists because a pass that decorates a whole chunk asks for one column per block column it
     * touches, so the array form was pure garbage at a rate with no ceiling -- the same argument
     * that took the allocation out of {@link #topY}. One buffer, reused, is the fix.
     * <p>
     * The VALUE contract is the array form's exactly: {@code > 0} means solid, indexed by
     * {@code y - minY}, {@code [minY, maxY]} inclusive, at the per-lattice-column biome intensity.
     * The two cannot drift: both go through the same private {@link #fill}, which is the only copy
     * of the four-plane selection, the mix order and the interpolation.
     * {@code IslandFieldTest.densityColumnIntoIsTheArrayApiBitForBit} is the pin.
     * <p>
     * Unlike the array form this VALIDATES instead of allocating: an inverted range and a buffer
     * shorter than {@code maxY - minY + 1} both throw {@link IllegalArgumentException}, both before
     * a single element is written. The array form keeps throwing
     * {@link NegativeArraySizeException} out of its own allocation, because a caller handing over a
     * negative length there must keep getting exactly what it always got.
     * <p>
     * The tail past the window is left ALONE rather than zeroed, so a buffer sized for the widest
     * range a caller ever asks for keeps working for the narrower ones -- and a stale tail is not
     * ambiguous, because the window is {@code maxY - minY + 1} long by definition.
     */
    public void densityColumnInto(double[] out, int x, int z, int minY, int maxY) {
        densityColumnInto(out, x, z, minY, maxY, PER_COLUMN);
    }

    /**
     * {@link #densityColumnInto(double[], int, int, int, int)} with the radial intensity FORCED:
     * the buffer twin of the 5-arg {@link #densityColumn(int, int, int, int, float)}, for the same
     * caller and the same reason. Passing {@link #PER_COLUMN}'s NaN restores the biome-driven path,
     * so the two overloads are one behaviour, not two.
     */
    public void densityColumnInto(double[] out, int x, int z, int minY, int maxY, float intensity) {
        Objects.requireNonNull(out, "out");
        long span = (long) maxY - minY + 1;
        if (span < 0 || span > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("inverted range [" + minY + ", " + maxY + "]");
        }
        if (out.length < span) {
            throw new IllegalArgumentException("buffer holds " + out.length + " doubles, the range ["
                    + minY + ", " + maxY + "] needs " + span);
        }
        fill(out, x, z, minY, maxY, intensity);
    }

    /**
     * Is any lattice row solid in {@code [minY, maxY]}? Exactly the question
     * {@code BiomePlacement.isLand} asks, answered without materialising a column and without
     * computing the lattice rows that question can never read.
     * <p>
     * Three exact savings, and all of them are why this is not just {@code densityColumn} inlined.
     * <p>
     * FIRST, the three quarters of the rows between the lattice rows are skipped. Sampling
     * {@link #densityColumn} at a {@code y} with {@code y % SCALE_Y == 0} is BIT-IDENTICAL to
     * reading the interpolated column there: {@code ty = (y - cellY * SCALE_Y) / SCALE_Y} is
     * exactly {@code 0.0}, so {@code a + (b - a) * ty} is {@code a + 0.0}, and every value the
     * interpolation can mix (a plane row, or a weighted sum of four of them) is finite -- no
     * division, no overflow -- so that term is neither NaN nor a signed-zero flip. That is why a
     * caller stepping its scan by {@code SCALE_Y} is exact, not close.
     * <p>
     * SECOND, the plane behind this call is {@link #landPlane}: {@link #LAND_ROWS} rows starting at
     * py {@link #LAND_MIN_PY}, not the full {@link #PLANE_ROWS}. The land test never looks below
     * py 0 and nothing is solid at or above {@link #MAX_HEIGHT}, so 17 of {@link #plane}'s 50 rows
     * are pure waste here -- 33 rows against 50, on the one path {@code /locate} walks 40,401
     * times.
     * <p>
     * THIRD, the caller no longer materialises a column at all. {@code BiomePlacement.isLand}
     * used to build a 129-element array and only then scan it, so its {@code break} fired after
     * every row had already been paid for. Here the scan runs over rows that {@link #landPlane}
     * has already computed and returns at the first solid one, so the saving is the 129-element
     * allocation and up to 96 avoided row MIXES - not avoided {@code density} calls.
     * <p>
     * Be exact about that last point, because it is easy to overstate: {@code density} for all
     * four corners is evaluated up front, in {@link #landPlane}, before this loop starts. The
     * early-out therefore shortens the cheap part (four multiplies and four adds per row) and
     * never the expensive part (one {@code density} per row per corner). The row-window saving in
     * SECOND is the one that removes {@code density} work.
     * <p>
     * {@code intensity} must be FORCED, never {@link #PER_COLUMN}: asking a biome here would close
     * the cycle this path exists to break. See the 5-arg {@link #densityColumn}. The range must be
     * lattice-aligned and inside the land window -- it is the caller's own {@code [0, 128]}, and a
     * range outside it would want rows {@link #landPlane} does not hold.
     */
    public boolean anySolidOnLattice(int x, int z, int minY, int maxY, float intensity) {
        if (Float.isNaN(intensity)) {
            throw new IllegalArgumentException("anySolidOnLattice needs a forced intensity");
        }
        if (minY < LAND_MIN_PY || maxY > LAND_MIN_PY + (LAND_ROWS - 1) * SCALE_Y
                || minY % SCALE_Y != 0 || maxY % SCALE_Y != 0) {
            throw new IllegalArgumentException("anySolidOnLattice reads the land window only: ["
                    + LAND_MIN_PY + ", " + (LAND_MIN_PY + (LAND_ROWS - 1) * SCALE_Y)
                    + "] on SCALE_Y boundaries, asked for [" + minY + ", " + maxY + "]");
        }
        // The weight setup below is column()'s, by hand. Both paths go through mix() so the
        // arithmetic cannot drift; an equivalence test pins this one against the full column.
        int cellX = Math.floorDiv(x, SCALE_XZ);
        int cellZ = Math.floorDiv(z, SCALE_XZ);
        double tx = (double) (x - cellX * SCALE_XZ) / SCALE_XZ;
        double tz = (double) (z - cellZ * SCALE_XZ) / SCALE_XZ;
        double w00 = (1 - tx) * (1 - tz);
        double w10 = tx * (1 - tz);
        double w01 = (1 - tx) * tz;
        double w11 = tx * tz;

        double[] p00 = landPlane(cellX, cellZ, intensity);
        double[] p10 = landPlane(cellX + 1, cellZ, intensity);
        double[] p01 = landPlane(cellX, cellZ + 1, intensity);
        double[] p11 = landPlane(cellX + 1, cellZ + 1, intensity);

        for (int y = minY; y <= maxY; y += SCALE_Y) {
            if (mix(p00, p10, p01, p11, (y - LAND_MIN_PY) / SCALE_Y, w00, w10, w01, w11) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * One lattice row of the interpolation: the four corner planes mixed by the column's own
     * weights. THE one copy of that expression, because {@link #column} and
     * {@link #anySolidOnLattice} have to agree to the last bit - the four-term order is the
     * evaluation order the interpolation has always used, and re-associating it would move a cell
     * border.
     */
    private static double mix(double[] p00, double[] p10, double[] p01, double[] p11, int i,
                              double w00, double w10, double w01, double w11) {
        return w00 * p00[i] + w10 * p10[i] + w01 * p01[i] + w11 * p11[i];
    }

    /**
     * The plane row a lattice cell reads: its own offset into the window, clamped to the rows
     * {@link #plane} holds. Clamping only ever fires for a {@code y} outside a real world's height,
     * where the lattice is air.
     */
    private static int planeIndex(int cellY) {
        return Math.max(0, Math.min(PLANE_ROWS - 2, cellY - PLANE_MIN_CELL_Y));
    }

    /**
     * The array-returning wrapper around {@link #fill}: same length contract it always had, length
     * {@code maxY - minY + 1} out of that same allocation, so a negative length still throws
     * {@link NegativeArraySizeException} before anything else happens.
     */
    private double[] column(int x, int z, int minY, int maxY, float intensity) {
        double[] out = new double[maxY - minY + 1];
        fill(out, x, z, minY, maxY, intensity);
        return out;
    }

    /**
     * THE one interpolation: writes {@code maxY - minY + 1} doubles to {@code out[0 .. ]} for the
     * block column at {@code (x, z)}. {@link #densityColumn} and {@link #densityColumnInto} are
     * both this, which is why they agree to the last bit rather than by coincidence.
     * <p>
     * {@code intensity} is either a forced value or {@link #PER_COLUMN}; the four planes are picked
     * accordingly, in the corner order {@link #mix} expects. The caller guarantees the buffer is
     * long enough -- this is where the array form's old {@code new double[]} length came from.
     */
    private void fill(double[] out, int x, int z, int minY, int maxY, float intensity) {
        boolean perColumn = Float.isNaN(intensity);
        int cellX = Math.floorDiv(x, SCALE_XZ);
        int cellZ = Math.floorDiv(z, SCALE_XZ);
        double tx = (double) (x - cellX * SCALE_XZ) / SCALE_XZ;
        double tz = (double) (z - cellZ * SCALE_XZ) / SCALE_XZ;
        double w00 = (1 - tx) * (1 - tz);
        double w10 = tx * (1 - tz);
        double w01 = (1 - tx) * tz;
        double w11 = tx * tz;

        // IslandLayer.java:143 sets the intensity once per computeColumnDensity call and the mod
        // calls that with a cell-aligned position, so per-lattice-column is exactly right: each of
        // the four trilerp corners gets its own biome height.
        double[] p00 = perColumn ? plane(cellX, cellZ) : plane(cellX, cellZ, intensity);
        double[] p10 = perColumn ? plane(cellX + 1, cellZ) : plane(cellX + 1, cellZ, intensity);
        double[] p01 = perColumn ? plane(cellX, cellZ + 1) : plane(cellX, cellZ + 1, intensity);
        double[] p11 = perColumn
                ? plane(cellX + 1, cellZ + 1) : plane(cellX + 1, cellZ + 1, intensity);

        for (int y = minY; y <= maxY; y++) {
            int cellY = Math.floorDiv(y, SCALE_Y);
            double ty = (double) (y - cellY * SCALE_Y) / SCALE_Y;
            // Clamped only for a y outside every real world's height; the lattice is air there.
            int i = planeIndex(cellY);
            double a = mix(p00, p10, p01, p11, i, w00, w10, w01, w11);
            double b = mix(p00, p10, p01, p11, i + 1, w00, w10, w01, w11);
            out[y - minY] = a + (b - a) * ty;
        }
    }

    /**
     * True if any block in the inclusive block box {@code [x0..x1] x [z0..z1]} can be solid over
     * {@code [minY, maxY]}. EXACT, not a heuristic: {@link #densityColumn} interpolates in Y first
     * and mixes the four lattice corners with non-negative weights that sum to 1, so an interior
     * column's value at any Y is the convex combination of the four corner COLUMNS' values at that
     * same Y. All four corners empty over the band therefore means the whole cell is empty.
     * <p>
     * Costs {@code (span/8 + 2)^2} columns instead of {@code span^2} - the cheap gate that keeps a
     * whole-area scan off the ~2500 chunks of the vanilla core that hold no terrain at all.
     */
    public boolean anySolid(int x0, int z0, int x1, int z1, int minY, int maxY) {
        for (int x = Math.floorDiv(x0, SCALE_XZ) * SCALE_XZ; x <= x1 + SCALE_XZ; x += SCALE_XZ) {
            for (int z = Math.floorDiv(z0, SCALE_XZ) * SCALE_XZ; z <= z1 + SCALE_XZ; z += SCALE_XZ) {
                for (double d : densityColumn(x, z, minY, maxY)) {
                    if (d > 0) return true;
                }
            }
        }
        return false;
    }

    /** The density lattice for one column of cells, at that column's own biome-driven intensity. */
    private double[] plane(int cellX, int cellZ) {
        return plane(cellX, cellZ, columnHeight(cellX, cellZ));
    }

    /**
     * The density lattice for one column of cells, computed once per (cellX, cellZ, intensity).
     * <p>
     * DELIBERATELY get/put and NOT {@code computeIfAbsent}: {@link #columnHeight} above calls out
     * to the biome placement, which calls {@code isLand}, which calls {@link #densityColumn}, which
     * re-enters this map on the same thread inside one call.
     * {@code ConcurrentHashMap.computeIfAbsent} throws or livelocks on a recursive update; get/put
     * just recomputes an identical array in the rare losing race.
     */
    private double[] plane(int cellX, int cellZ, float intensity) {
        PlaneKey key = new PlaneKey(cellX, cellZ, Float.floatToIntBits(intensity));
        double[] cached = planes.get(key);
        if (cached != null) return cached;
        if (planes.size() > 1 << 16) planes.clear();
        Cell cell = cell(cellX, cellZ);
        double[] plane = new double[PLANE_ROWS];
        for (int i = 0; i < PLANE_ROWS; i++) {
            plane[i] = density(cell, (double) (PLANE_MIN_CELL_Y + i) * SCALE_Y, intensity);
        }
        planes.put(key, plane);
        return plane;
    }

    /**
     * The land-window plane for one lattice column: {@link #LAND_ROWS} rows of py 0..MAX_HEIGHT.
     * Values are identical to the corresponding rows of {@link #plane} -- both call
     * {@link #density} with the same {@code (cell, py, intensity)} -- only the row count differs.
     */
    private double[] landPlane(int cellX, int cellZ, float intensity) {
        PlaneKey key = new PlaneKey(cellX, cellZ, Float.floatToIntBits(intensity));
        int slot = (key.hashCode() * 0x9E3779B9) >>> (32 - LAND_CACHE_BITS);
        LandPlane cached = landPlanes[slot];
        if (cached != null && cached.key().equals(key)) return cached.rows();
        Cell cell = cell(cellX, cellZ);
        double[] rows = new double[LAND_ROWS];
        for (int i = 0; i < LAND_ROWS; i++) {
            rows[i] = density(cell, (double) (LAND_MIN_PY + i * SCALE_Y), intensity);
        }
        landPlanes[slot] = new LandPlane(key, rows);
        return rows;
    }

    /**
     * The radial intensity for one lattice column: TerrainGenerator.java:188,
     * {@code getAverageDepth(x << 1, z << 1) * 0.5F}, over TerrainGenerator.java:247-262. The mod's
     * {@code x} there is the noise CELL index, so the quart it samples is that index doubled.
     * <p>
     * Package-private for the unit tests, which pin the two things that are easy to get wrong.
     */
    float columnHeight(int cellX, int cellZ) {
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        Float cached = columnHeights.get(key);
        if (cached != null) return cached;
        if (columnHeights.size() > 1 << 16) columnHeights.clear();
        float height = computeColumnHeight(cellX, cellZ);
        columnHeights.put(key, height);
        return height;
    }

    private float computeColumnHeight(int cellX, int cellZ) {
        int qx = cellX << 1;
        int qz = cellZ << 1;
        // TerrainGenerator.java:251-254 - the early-out tests the CENTRE sample ONLY, and the test
        // is strictly less-than. 0.1f < 0.1F is FALSE, so the codec default flattens nothing; only
        // a biome that actively declares 0.0 (megalake, megalake_grove, sulphur_springs) trips it,
        // and those become genuinely flat islands, as they are in the mod.
        if (heights.at(qx, qz) < DEFAULT_TERRAIN_HEIGHT) return 0f;
        float depth = 0F;
        for (int i = 0; i < COEF.length; i++) {
            depth += heights.at(qx + OFFS_X[i], qz + OFFS_Z[i]) * COEF[i];
        }
        return depth * 0.5f;
    }

    // --- lattice cell -------------------------------------------------------------------------

    /** One noise-lattice column: the distorted sample position and the islands that reach it. */
    private record Cell(double px, double pz, Island[] big, Island[] medium, Island[] small) {
    }

    /** TerrainGenerator.java:171-182 - note the noise argument is the CELL INDEX, not the block. */
    private Cell cell(int cellX, int cellZ) {
        double distortion1 = noise1.eval(cellX * 0.1, cellZ * 0.1) * 20
                + noise2.eval(cellX * 0.2, cellZ * 0.2) * 10
                + noise1.eval(cellX * 0.4, cellZ * 0.4) * 5;
        double distortion2 = noise2.eval(cellX * 0.1, cellZ * 0.1) * 20
                + noise1.eval(cellX * 0.2, cellZ * 0.2) * 10
                + noise2.eval(cellX * 0.4, cellZ * 0.4) * 5;
        double px = (double) cellX * SCALE_XZ + distortion1;
        double pz = (double) cellZ * SCALE_XZ + distortion2;
        return new Cell(px, pz, big.islands(px, pz),
                medium.islands(px, pz), small.islands(px, pz));
    }

    /** TerrainGenerator.java:191-204 - the three layers, the inflating octaves, the top fade-out. */
    private double density(Cell cell, double py, float intensity) {
        // TerrainGenerator.java:201 overwrites the density with -1 here after computing it; the
        // result is the same and this is half the lattice.
        if (py >= MAX_HEIGHT) return -1;
        float dist = layerDensity(cell.big(), big, cell.px(), py, cell.pz(), intensity);
        if (dist <= 1) {
            dist = Math.max(dist, layerDensity(cell.medium(), medium, cell.px(), py, cell.pz(), intensity));
        }
        if (dist <= 1) {
            dist = Math.max(dist, layerDensity(cell.small(), small, cell.px(), py, cell.pz(), intensity));
        }

        if (dist > -0.5F) {
            // Each term is amp*noise + amp, so the sum lies in [0, 0.07]: these INFLATE the
            // surface, they can never carve it (TerrainGenerator.java:195-199).
            dist += (float) (noise1.eval(cell.px() * 0.01, py * 0.01, cell.pz() * 0.01) * 0.02 + 0.02);
            dist += (float) (noise2.eval(cell.px() * 0.05, py * 0.05, cell.pz() * 0.05) * 0.01 + 0.01);
            dist += (float) (noise1.eval(cell.px() * 0.1, py * 0.1, cell.pz() * 0.1) * 0.005 + 0.005);
        }

        if (py > FADE_OUT_START) {
            double t = (py - FADE_OUT_START) / FADE_OUT_DIST;
            return dist + t * (-1 - dist);
        }
        return dist;
    }

    /** IslandLayer.java:128-140 - density is the negated minimum signed distance over the layer. */
    private static float layerDensity(Island[] islands, Layer layer, double x, double y, double z,
                                      float intensity) {
        float distance = 10;
        for (Island island : islands) {
            float lx = (float) (x - island.x) / layer.scale;
            float ly = (float) (y - island.y) / layer.scale;
            float lz = (float) (z - island.z) / layer.scale;
            distance = Math.min(distance, islandSdf(lx, ly, lz, island, intensity));
        }
        return -distance;
    }

    // --- island SDF ---------------------------------------------------------------------------

    /**
     * The mod's island: four capped cones smooth-unioned into a lens, with the top cap displaced
     * by a radial simplex patch (IslandLayer.java:39-51). {@code SDFScale} divides the query by
     * the per-island scale and multiplies the distance back out (SDFScale.java:12-15).
     * <p>
     * Confirmed exact: SDFCappedCone.java:29-46, SDFSmoothUnion.java:15-20, and the four cones'
     * parameters against IslandLayer.java:39-42,154-158.
     */
    private static float islandSdf(float x, float y, float z, Island island, float intensity) {
        float s = island.scale;
        float sx = x / s;
        float sy = y / s;
        float sz = z / s;
        float qx = (float) Math.sqrt(sx * sx + sz * sz);
        // IslandLayer.java:39-42,154-158 - makeCone(rBottom, rTop, height, minY), hh = height/2,
        // translated to (0, minY + hh, 0), so the query y is shifted by -(minY + hh).
        float cone1 = cappedCone(qx, sy + 0.2f, 0f, 0.4f, 0.1f);
        float cone2 = cappedCone(qx, sy + 0.05f, 0.4f, 0.5f, 0.05f);
        float cone3 = cappedCone(qx, sy - 0.015f, 0.5f, 0.45f, 0.015f);
        float cone4 = cappedCone(qx, sy - 0.04f, 0.45f, 0f, 0.01f);
        float coneBottom = smoothUnion(cone1, cone2, 0.02f);
        float coneTop = smoothUnion(cone3, cone4, 0.02f) + radial(sx, sz, island, intensity);
        return smoothUnion(coneTop, coneBottom, 0.01f) * s;
    }

    /** bclib SDFCappedCone (iq's capped cone), evaluated in cylindrical (qx, y). */
    private static float cappedCone(float qx, float y, float radius1, float radius2, float height) {
        float k2x = radius2 - radius1;
        float k2y = 2 * height;
        float cax = qx - Math.min(qx, (y < 0f) ? radius1 : radius2);
        float cay = Math.abs(y) - height;
        float mlt = clamp(((radius2 - qx) * k2x + (height - y) * k2y) / (k2x * k2x + k2y * k2y), 0f, 1f);
        float cbx = qx - radius2 + k2x * mlt;
        float cby = y - height + k2y * mlt;
        float sign = (cbx < 0f && cay < 0f) ? -1f : 1f;
        return sign * (float) Math.sqrt(Math.min(cax * cax + cay * cay, cbx * cbx + cby * cby));
    }

    /** bclib SDFSmoothUnion - polynomial smin, {@code a} = sourceA, {@code b} = sourceB. */
    private static float smoothUnion(float a, float b, float k) {
        float h = clamp(0.5f + 0.5f * (b - a) / k, 0f, 1f);
        return (b + h * (a - b)) - k * h * (1f - h);
    }

    /**
     * SDFRadialNoiseMap.java:19-34, term for term: a simplex patch confined to the inner
     * {@code 0.5 / (1 + intensity)} of the island and faded linearly to zero at that rim,
     * displacing the top cap by up to {@code +-1.7 * intensity * scale} island units. Confirmed
     * against bclib -- the falloff, the 0.5-radian rotation, the {@code * 0.75} frequency and
     * {@link Layer#radialNoise} (SDFRadialNoiseMap.java:37-42) are all exact, and
     * SDFDisplacement.java:20 confirms the displacement is ADDED to the source.
     * <p>
     * There is no central-island special case, and there was never meant to be one. The premise for
     * the old {@code if (island.central) return 0f} was that {@code minecraft:the_end} has no
     * WoverBiomeData. It has one (VanillaBiomeDataProvider.java:44-47) and its json omits
     * terrainHeight, so it takes the 0.1f codec default, {@code 0.1f < 0.1F} is false, and the
     * mod's early-out never fires there: the central island gets the same 0.05 as everywhere else.
     * What DOES fall out of the general rule is the flattening the port never had -- the three
     * biomes that declare terrainHeight 0.0 return 0 from {@link #columnHeight} and land on the
     * hard zero below. The mod's rule is per-COLUMN; the port's old one was per-ISLAND.
     */
    private static float radial(float x, float z, Island island, float intensity) {
        // SDFRadialNoiseMap.java:20-22 - a hard zero, before the radius is even divided by.
        if (intensity == 0f) return 0f;
        // IslandLayer.java:144 - setRadius(0.5F / (1 + height)), derived from the same height.
        float radius = 0.5f / (1f + intensity);
        float px = x / radius;
        float pz = z / radius;
        float d2 = px * px + pz * pz;
        if (d2 > 1) return 0f;
        float t = 1f - (float) Math.sqrt(d2);
        float nx = px * RADIAL_COS - pz * RADIAL_SIN;
        float nz = pz * RADIAL_COS + px * RADIAL_SIN;
        return t * island.layer.radialNoise(nx * 0.75f + island.offsetX, nz * 0.75f + island.offsetZ)
                * intensity;
    }

    // --- layers and islands -------------------------------------------------------------------

    private static final class Layer {
        final int seed;
        final float distance;
        final float scale;
        final float minY;
        final float maxY;
        final long centerDist;
        /** IslandLayer.java:99-101 - the PER-LAYER flag: does this layer carry the pinned island. */
        final boolean hasCentralIsland;
        /** LayerOptions.java:64-66, via {@link LayerSettings#coverage()} - the density threshold. */
        final float coverage;
        private final OpenSimplexNoise density;
        private final OpenSimplexNoise noise;

        Layer(int seed, LayerSettings settings, boolean hasCentralIsland) {
            this.seed = seed;
            this.distance = settings.distance();
            this.scale = settings.scale();
            this.minY = settings.minY();
            this.maxY = settings.maxY();
            this.centerDist = settings.centerDist();
            this.coverage = settings.coverage();
            this.hasCentralIsland = hasCentralIsland;
            this.density = new OpenSimplexNoise(seed);
            this.noise = new OpenSimplexNoise(seed);
        }

        /**
         * SDFRadialNoiseMap.java:37-42, exactly - including the two {@code +1000} x offsets.
         * <p>
         * Two measured, deliberate precision divergences, both sub-block, both left alone. bclib
         * takes {@code double} arguments and forms {@code nx * 0.75 + offsetX} in double, while
         * {@link #radial} forms it in float; at the worst offset the mask allows (32767) that
         * quantises the noise input to about 1/380th of the patch, i.e. 0.03 blocks of
         * displacement at the codec-default intensity. And bclib casts each of the three octaves
         * to float before summing where this sums in double, worth about 1e-6 blocks. Widening
         * both would be strictly closer to upstream and strictly invisible.
         */
        float radialNoise(float x, float z) {
            return (float) (noise.eval(x, z)
                    + noise.eval(x * 3 + 1000, z * 3) * 0.5
                    + noise.eval(x * 9 + 1000, z * 9) * 0.2);
        }

        /**
         * IslandLayer.java:59-104 - the 3x3 cell neighbourhood around a distorted sample point.
         * <p>
         * The mod's GLOBAL {@code GeneratorOptions.hasCentralIsland()} is not a knob here: it is
         * always on, exactly as the mod ships it. That flag gates two different things at once, and
         * neither is to be confused with this layer's own {@link #hasCentralIsland}: the inner-void
         * cull, for EVERY layer, and (together with the per-layer flag) the pinned island insert.
         * {@code TerrainGenerator.config.centerBiomesSize} also narrows the cull to cells near the
         * origin in the mod (IslandLayer.java:89 gates on the CELL index against the block-valued
         * 256), so the cull is unconditional in x/z here - and that is provably equivalent, not
         * merely close: the window is +-15,360 blocks on the small layer and +-76,800 on the big
         * one, and the gated block only culls islands within 1024 blocks of the origin and inserts
         * the pinned island. Both are vacuous outside the window.
         */
        Island[] islands(double x, double z) {
            int ix = floor(x / distance);
            int iz = floor(z / distance);
            List<Island> out = new ArrayList<>(10);
            for (int pox = -1; pox < 2; pox++) {
                int px = pox + ix;
                for (int poz = -1; poz < 2; poz++) {
                    int pz = poz + iz;
                    // IslandLayer.java:71. Yes, a cell COUNT is compared against a squared cell
                    // distance - that is what the mod does, so it is what happens here.
                    if ((long) px * (long) px + (long) pz * (long) pz <= centerDist) continue;
                    Random random = new Random(islandSeed(px, pz));
                    // Draw order is X, Y, Z, and the coverage test comes AFTER all three, at the
                    // island's jittered world position (IslandLayer.java:76-80).
                    double posX = (px + random.nextFloat()) * distance;
                    // MHelper.java:88-90 randRange, scaled by maxHeight at IslandLayer.java:78.
                    double posY = (minY + random.nextFloat() * (maxY - minY)) * MAX_HEIGHT;
                    double posZ = (pz + random.nextFloat()) * distance;
                    if (density.eval(posX * 0.01, posZ * 0.01) <= coverage) continue;
                    int cx = (int) posX;
                    int cy = (int) posY;
                    int cz = (int) posZ;
                    // IslandLayer.java:89-98 - the real spawn void, in world coordinates. The
                    // cull sits inside the GLOBAL flag's if in the mod, which is always on here,
                    // so it culls EVERY layer. Gating it on the per-layer flag instead would leave
                    // big and small islands in the void.
                    if ((long) cx * cx + (long) cz * cz < INNER_VOID_RADIUS_SQUARED) {
                        continue;
                    }
                    // IslandLayer.java:112-113 - a fresh RNG seeded on the island's WORLD
                    // position (not the cell indices line 76 used), so the scale is an
                    // independent draw. One draw, all three axes.
                    float scale = new Random(islandSeed(cx, cz)).nextFloat() + 0.5f;
                    out.add(new Island(this, cx, cy, cz, scale));
                }
            }
            // IslandLayer.java:99-102 - the insert needs BOTH flags: the global one (it is nested
            // inside that same if, always on here) and the per-layer one, which only the medium
            // layer sets.
            // IslandLayer.java:101,110 - (0, 64, 0) at fixed scale 1.3. It carries no flag of its
            // own any more: the radial map is per-COLUMN now, so the pinned island is shaped by
            // whatever terrainHeight its columns report, exactly like every other island.
            if (hasCentralIsland) out.add(new Island(this, 0, 64, 0, 1.3f));
            return out.toArray(new Island[0]);
        }

        /** IslandLayer.java:53-57 - the same hash as {@link IslandField#mixSeed}. */
        int islandSeed(int x, int z) {
            return mixSeed(seed, x, z);
        }

        /**
         * MHelper.java:114-116, character for character - asymmetric about zero, so
         * {@code floor(-3.0) == -4}. Bug-compatible on purpose.
         */
        private static int floor(double x) {
            return x < 0 ? (int) (x - 1) : (int) x;
        }
    }

    private static final class Island {
        final Layer layer;
        final int x;
        final int y;
        final int z;
        final float scale;
        /**
         * SDFRadialNoiseMap.java:59-63, set at IslandLayer.java:117. Both halves of the old open
         * question turn out to be true and compatible: IslandLayer does pass the raw int, and the
         * {@code (short) (x & 32767)} mask lives inside {@code setOffset}. Byte-exact.
         */
        final float offsetX;
        final float offsetZ;

        Island(Layer layer, int x, int y, int z, float scale) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale;
            this.offsetX = (short) (x & 32767);
            this.offsetZ = (short) (z & 32767);
        }
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
