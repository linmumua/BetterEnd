package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * BLOSSOMING SPIRES' two {@code raw_generation} features, as a pure function of
 * {@code (worldSeed, chunkX, chunkZ)}.
 * <p>
 * Source: {@code Reference/BetterEnd/.../world/features/terrain/SpireFeature.java} (120 lines) and
 * {@code FloatingSpireFeature.java} (98), placed by {@code placed_feature/spire.json}
 * (rarity_filter 4, in_square, biome) and {@code floating_spire.json} (rarity_filter 8). Both
 * configs are empty objects, so every number below is hard-coded in the feature class.
 *
 * <h2>The shape</h2>
 * BCLib's SDF library stacks spheres with {@code SDFSmoothUnion}, and the trick that makes a spire
 * is that {@code addSegment} (SpireFeature.java:102-107) puts each NEW sphere at the origin and
 * lifts everything already built ABOVE it. So the last sphere drawn is the BOTTOM one, and radii
 * that grow as {@code i * 1.3 + 2.5} therefore widen downward: a cone, half-buried in the ground.
 * <p>
 * The floating spire runs that loop again in reverse (FloatingSpireFeature.java:46-53), which turns
 * the cone into a spindle widest at its middle. Dropping that second loop is the single easiest
 * thing to lose in this port -- the result still looks like a spire, just the wrong one -- so
 * {@code SpireShapeTest} measures where the widest slice sits.
 * <p>
 * A displacement node then ADDS two {@code abs()}'d simplex octaves to the signed distance
 * (SpireFeature.java:60-70). Because they are absolute values the noise can only ever eat INTO the
 * shape, never grow it, which is what makes both early-outs in {@link #inside} exact rather than
 * approximate and what bounds the write extent analytically.
 *
 * <h2>Why a planner and not a term in {@link IslandField}</h2>
 * A spire is three to twelve thousand blocks written once per ~120 chunks. Folding it into the
 * density field would make every column query in the world pay for a candidate scan. This is the
 * {@link CaveCarver} trade in the other direction and for the same reason: the mod runs it as a
 * feature, after the fill.
 * <p>
 * Never reads a block. The mod's replace predicate (SpireFeature.java:109-118) admits end stones,
 * leaves and anything replaceable-or-plant -- in blossoming_spires that is every block that exists
 * there, air included -- so "fill the shape" is not an approximation of it, it is the same set.
 *
 * <h2>Cost</h2>
 * Measured over 19,600 chunks at seed 12345: 1.27% of chunks grow a spire, averaging ~4,000 blocks,
 * for <b>18.5 us/chunk amortised</b> -- about 3.5% of the carve-and-coat budget. The flood fill is
 * what keeps it there; sweeping the bounding box instead costs roughly six times as much, and the
 * two exact early-outs in {@link #inside} skip the simplex for every cell that is clearly outside or
 * clearly buried.
 */
public final class SpireShape {
    /** {@code placed_feature/spire.json} and {@code floating_spire.json} rarity_filter chances. */
    private static final int GROUND_RARITY = 4;
    private static final int FLOATING_RARITY = 8;
    /** SpireFeature.java:47 -- a spire never starts below this. */
    private static final int MIN_BASE_Y = 10;
    /**
     * SpireFeature.java:47-49 probes {@code END_STONES} three and six blocks down. That tag holds
     * every BetterEnd surface block, so it is a solidity test rather than a block-identity one and
     * {@link IslandField#isSolid} answers it exactly. Net effect: at least six blocks of rock under
     * the surface, which keeps spires off the thin rims of islands.
     */
    private static final int[] SUPPORT_PROBES = {3, 6};
    /**
     * The two displacement octaves, {@code |noise| * 3 + |noise| * 1.3}, so the most the surface can
     * be pushed inward. SpireFeature.java:61-69.
     */
    private static final float DISPLACEMENT_MAX = 3.0f + 1.3f;
    /**
     * Largest horizontal reach in blocks, analytically: the widest sphere is
     * {@code 6*1.3 + 2.5 + 1.5 = 11.8}, a smooth union can bulge the isosurface out by at most
     * {@code radius/4}, and the displacement only shrinks. Measured maximum over 3000 shapes is 11.
     * A {@code LimitedRegion} reaches 16 blocks past its chunk, so one chunk of margin is provably
     * enough and a chunk only ever plans its OWN spire.
     */
    public static final int MAX_REACH = 14;

    /** Which of the two features this is. The salt keeps their RNG streams independent. */
    public enum Kind {
        GROUND(GROUND_RARITY, 0x7370697265L),
        FLOATING(FLOATING_RARITY, 0x666c7370697260L);

        private final int rarity;
        private final long salt;

        Kind(int rarity, long salt) {
            this.rarity = rarity;
            this.salt = salt;
        }
    }

    /** One block the spire writes. {@code cap} marks an upward-facing face, which gets pink moss. */
    public record Cell(int x, int y, int z, boolean cap) {
    }

    private SpireShape() {
    }

    /**
     * Paper hands every populator an identically seeded {@code Random}, so the argument one must
     * never be used -- it replays whichever stream the previously registered populator drew. Same
     * derivation as {@code TreePopulator.mix}, with a per-kind salt so the two spires do not land on
     * top of each other.
     */
    private static long mix(long seed, int chunkX, int chunkZ, long salt) {
        long s = seed ^ salt;
        s = s * 6364136223846793005L + chunkX;
        s = s * 6364136223846793005L + chunkZ;
        s ^= s >>> 33;
        return s * 0xff51afd7ed558ccdL;
    }

    /**
     * Just the rarity roll, so a test can measure the 1-in-4 / 1-in-8 rate without the biome gate
     * skewing the sample. Same first draw {@link #plan} makes.
     */
    static boolean rolls(long worldSeed, int chunkX, int chunkZ, Kind kind) {
        return new Random(mix(worldSeed, chunkX, chunkZ, kind.salt)).nextFloat() < 1f / kind.rarity;
    }

    /**
     * The spire this chunk grows, or null.
     * <p>
     * The modifiers run in the json's order -- rarity, in_square, biome -- which is also cheapest
     * first: three quarters of chunks never reach the biome query.
     */
    /**
     * 该列生成后真正的顶面：结构台地内取台地高度，否则取自然高度。
     * <p>
     * 必须与 {@code generateNoise} 用同一套判定，否则尖塔会按「被削掉之前的自然表面」长在空里，
     * 或按「被垫平之前的谷底」埋进台地。台地按区块解析并缓存，所以这里是每区块一次定位、
     * 每列一次距离比较。
     *
     * @param worldSeed 世界种子
     * @param x 方块 X
     * @param z 方块 Z
     * @param chunkX 当前区块 X（台地按区块定位）
     * @param chunkZ 当前区块 Z
     * @param field 岛体场
     * @return 该列顶面 Y，虚空列返回 {@link Integer#MIN_VALUE}
     */
    static int surfaceTop(long worldSeed, int x, int z, int chunkX, int chunkZ, IslandField field) {
        StructurePlatform.Spot[] spots = StructurePlatform.spots(field, worldSeed, chunkX, chunkZ);
        return spots.length == 0 ? field.topY(x, z) : StructurePlatform.surfaceTop(spots, field, x, z);
    }

    public static List<Cell> plan(long worldSeed, int chunkX, int chunkZ, Kind kind,
                                  BiomePlacement placement, IslandField field) {
        Random rng = new Random(mix(worldSeed, chunkX, chunkZ, kind.salt));
        // minecraft:rarity_filter.
        if (rng.nextFloat() >= 1f / kind.rarity) {
            return null;
        }
        // minecraft:in_square.
        int x = (chunkX << 4) + rng.nextInt(16);
        int z = (chunkZ << 4) + rng.nextInt(16);

        int top = surfaceTop(worldSeed, x, z, chunkX, chunkZ, field);
        int baseY;
        if (kind == Kind.GROUND) {
            // SpireFeature.java:46-51. getPosOnSurfaceWG is the first AIR block above the column.
            if (top == Integer.MIN_VALUE) {
                return null;
            }
            baseY = top + 1;
            if (baseY < MIN_BASE_Y) {
                return null;
            }
            for (int probe : SUPPORT_PROBES) {
                if (!field.isSolid(x, baseY - probe, z)) {
                    return null;
                }
            }
        } else {
            // FloatingSpireFeature.java:35-41. No solidity gate at all: the whole point of the
            // second branch is that a floating spire hangs over void columns too.
            int minY = top == Integer.MIN_VALUE ? 0 : top + 1;
            baseY = minY > 57
                    ? (int) ((minY + rng.nextInt(minY + 1)) * 0.5f + 32)
                    : 64 + rng.nextInt(129);
        }

        // minecraft:biome. ring() first: inside radius 1024 at() names a biome never painted there.
        if (placement.ring(x, z) != null
                || placement.quartAt(x, baseY, z) != BiomeSurface.BLOSSOMING_SPIRES) {
            return null;
        }
        return build(rng, kind, x, baseY, z);
    }

    /**
     * The sphere stack, flattened.
     * <p>
     * The draw order is load-bearing and is the mod's: first sphere radius, then the segment count,
     * then per segment a radius and a lift -- the radius is evaluated as an ARGUMENT to
     * {@code addSegment} (SpireFeature.java:57) and the lift inside it (:104), so they alternate.
     * Getting that order wrong changes every shape while leaving the code looking right.
     */
    private static List<Cell> build(Random rng, Kind kind, int baseX, int baseY, int baseZ) {
        float[] radii;
        float[] lifts;
        // SpireFeature.java:53 / FloatingSpireFeature.java:43 -- MHelper.randRange(2, 3) is inclusive.
        float first = 2 + rng.nextInt(2);
        if (kind == Kind.GROUND) {
            int count = 3 + rng.nextInt(5);           // :54, randRange(3, 7) inclusive
            radii = new float[count + 1];
            lifts = new float[count + 1];
            radii[0] = first;
            for (int i = 0; i < count; i++) {
                push(rng, radii, lifts, i + 1, i);
            }
        } else {
            int count = 3 + rng.nextInt(3);           // FloatingSpireFeature.java:44, randRange(3, 5)
            radii = new float[2 * count];
            lifts = new float[2 * count];
            radii[0] = first;
            int at = 1;
            for (int i = 0; i < count; i++) {
                push(rng, radii, lifts, at++, i);
            }
            // The mirror loop, FloatingSpireFeature.java:50-53. This is what turns the cone into a
            // spindle; without it a floating spire is just a second ground spire hanging in the air.
            for (int i = count - 1; i > 0; i--) {
                push(rng, radii, lifts, at++, i);
            }
            radii = java.util.Arrays.copyOf(radii, at);
            lifts = java.util.Arrays.copyOf(lifts, at);
        }
        OpenSimplexNoise noise = new OpenSimplexNoise(rng.nextLong());   // :59 / :55

        // Translations compose, so cum[k] is how far sphere k sits above the origin. Sphere 0 is the
        // innermost and therefore the TOP; the last one is the bottom and sits at the origin.
        float[] cum = new float[radii.length];
        for (int k = radii.length - 2; k >= 0; k--) {
            cum[k] = cum[k + 1] + lifts[k + 1];
        }
        return flood(radii, cum, noise, baseX, baseY, baseZ);
    }

    private static void push(Random rng, float[] radii, float[] lifts, int at, int i) {
        float rMin = i * 1.3f + 2.5f;                       // SpireFeature.java:56
        radii[at] = rMin + rng.nextFloat() * 1.5f;          // :57, randRange(rMin, rMin + 1.5)
        lifts[at] = radii[at] + rng.nextFloat() * 0.25f * radii[at];   // :104
    }

    /**
     * The signed distance of the smooth-union chain, walked innermost (top) to outermost (bottom).
     * <p>
     * {@code SDFSmoothUnion} is {@code h = clamp(0.5 + 0.5*(b-a)/r); lerp(h, b, a) - r*h*(1-h)} with
     * {@code a} the new sphere and {@code b} the stack so far (SDFSmoothUnion.java:18-19).
     */
    private static float base(float[] radii, float[] cum, float x, float y, float z) {
        float d = length(x, y - cum[0], z) - radii[0];
        for (int k = 1; k < radii.length; k++) {
            float a = length(x, y - cum[k], z) - radii[k];
            float rr = radii[k] * 0.5f;
            float h = clamp(0.5f + 0.5f * (d - a) / rr);
            d = (d + h * (a - d)) - rr * h * (1f - h);
        }
        return d;
    }

    /**
     * Whether a point is inside the noise-eaten shape.
     * <p>
     * Both early-outs are exact, not heuristics: the displacement is a sum of absolute values, so it
     * lies in {@code [0, DISPLACEMENT_MAX]} and can only push the surface inward. They skip the
     * simplex evaluations for every cell that is clearly outside or clearly buried, which is most of
     * them.
     */
    private static boolean inside(float[] radii, float[] cum, OpenSimplexNoise noise,
                                  float x, float y, float z) {
        float d = base(radii, cum, x, y, z);
        if (d >= 0) {
            return false;
        }
        if (d < -DISPLACEMENT_MAX) {
            return true;
        }
        double displace = Math.abs(noise.eval(x * 0.1, y * 0.1, z * 0.1)) * 3.0
                + Math.abs(noise.eval(x * 0.3, y * 0.3 + 100, z * 0.3)) * 1.3;
        return d + (float) displace < 0;
    }

    /**
     * {@code SDF.fillRecursive} is a 6-connected flood from the origin cell, not a sweep of the
     * bounding box (SDF.java:54-118). Reproducing that matters twice over: it is five times cheaper,
     * and a noise lobe that the erosion has disconnected from the body is never written -- a box
     * sweep would leave those floating in the air.
     */
    private static List<Cell> flood(float[] radii, float[] cum, OpenSimplexNoise noise,
                                    int baseX, int baseY, int baseZ) {
        int reach = MAX_REACH;
        int span = 2 * reach + 1;
        // Vertical bounds from the geometry: the stack rises to cum[0] + radii[0] and the bottom
        // sphere hangs radii[last] below the origin. The displacement only ever shrinks that.
        int lo = -(int) Math.ceil(radii[radii.length - 1]) - 2;
        int hi = (int) Math.ceil(cum[0] + radii[0]) + 2;
        int height = hi - lo + 1;

        boolean[] visited = new boolean[span * span * height];
        boolean[] filled = new boolean[visited.length];
        Deque<int[]> queue = new ArrayDeque<>();
        // SDF.java:60,84 -- the origin seeds the flood and is marked visited before anything can
        // reach back to it, so the mod leaves a one-block air pocket at a spire's own centre. The
        // port fills it instead: it is a bug, not a shape.
        queue.add(new int[]{0, 0, 0});
        visited[index(0, 0, 0, reach, lo, span, height)] = true;
        filled[index(0, 0, 0, reach, lo, span, height)] = true;

        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] dir : dirs) {
                int dx = cell[0] + dir[0];
                int dy = cell[1] + dir[1];
                int dz = cell[2] + dir[2];
                if (dx < -reach || dx > reach || dz < -reach || dz > reach || dy < lo || dy > hi) {
                    continue;
                }
                int idx = index(dx, dy, dz, reach, lo, span, height);
                if (visited[idx]) {
                    continue;
                }
                visited[idx] = true;
                if (inside(radii, cum, noise, dx, dy, dz)) {
                    filled[idx] = true;
                    queue.add(new int[]{dx, dy, dz});
                }
            }
        }

        List<Cell> out = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = lo; dy <= hi; dy++) {
                    if (!filled[index(dx, dy, dz, reach, lo, span, height)]) {
                        continue;
                    }
                    // The cap is every cell with NO spire cell above it, which is what
                    // PosInfo.getStateUp() answers (it reads AIR for anything outside the write
                    // set). That is NOT "the highest cell in the column": the smooth unions make
                    // real overhangs, so on every measured shape the cap cells outnumber the
                    // occupied columns.
                    boolean cap = dy == hi
                            || !filled[index(dx, dy + 1, dz, reach, lo, span, height)];
                    out.add(new Cell(baseX + dx, baseY + dy, baseZ + dz, cap));
                }
            }
        }
        return out;
    }

    private static int index(int dx, int dy, int dz, int reach, int lo, int span, int height) {
        return ((dx + reach) * span + (dz + reach)) * height + (dy - lo);
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
