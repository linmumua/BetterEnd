package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ICE STARFIELD's {@code ice_star} feature -- the whole character of that biome -- as a pure planner.
 * <p>
 * Source: {@code Reference/BetterEnd/.../world/features/terrain/IceStarFeature.java} and its two
 * placed forms, {@code placed_feature/ice_star.json} (rarity 15, count 10..25, size 5..15) and
 * {@code ice_star_small.json} (rarity 8, count 7..12, size 3..5). Both are {@code raw_generation},
 * and {@code worldgen/biome/ice_starfield.json} is the only biome that lists either.
 *
 * <h2>What a star is</h2>
 * A union of {@code count} capped cones sharing one centre, aimed at a Fibonacci sphere lattice.
 * <p>
 * Each cone spans {@code y in [-size, +size]} -- {@code SDFCappedCone} is IQ's {@code sdCappedCone},
 * so it is {@code 2*size} long, NOT {@code size} (SDFCappedCone.java:32-33). Read it as {@code size}
 * tall and every star comes out half the right length. The wide end, radius
 * {@code 3 + (size-5)*0.2}, sits at {@code -size} and tapers linearly to a point at {@code +size};
 * the translate at IceStarFeature.java:50 then puts the base disc 0.5 behind the centre and the apex
 * {@code 2*size - 0.5} in front of it. That is both why the union is connected -- every cone
 * contains the star's centre -- and why a size-15 star reaches 29.5 blocks.
 *
 * <h2>Why the spikes point at (-x, y, -z)</h2>
 * {@code SDFRotation} rotates the QUERY point (SDFRotation.java:24-28), so the resulting shape is
 * the INVERSE rotation of the cone. The quaternion at IceStarFeature.java:56-57 is built to map
 * {@code YP -> point}, so the shape's axis is {@code R^-1(YP) = 2*(YP.point)*YP - point}, which for
 * a unit {@code point} is {@code (-x, y, -z)}: the lattice turned 180 degrees about Y. Visually
 * similar, arithmetically different, and the easiest thing in this port to get backwards.
 *
 * <h2>Why there is no downward spike</h2>
 * The lattice's last point is exactly {@code (0,-1,0)}, whose {@code acos(-1) = 3.14159} fails the
 * {@code angle < 3.14F} guard (IceStarFeature.java:55) and falls into {@code else if (angle > 1)}
 * (:58) -- a rotation about Y, which a Y-axisymmetric cone cannot see. The first point is exactly
 * {@code (0,1,0)} and is left unrotated. So every star has two coincident spikes pointing UP and
 * none pointing DOWN. That is the mod's shape, not a bug to fix.
 *
 * <h2>Why no quaternion is needed</h2>
 * Rotation preserves length, so for a spike axis {@code d} and a star-local offset {@code p} the
 * rotated point's y is {@code d.p} and its xz length is {@code sqrt(|p|^2 - (d.p)^2)}. A capped cone
 * depends on nothing else, so the evaluation below is exact and allocation-free.
 * <p>
 * Pure and Bukkit-free, so the geometry is unit-tested without a server.
 */
public final class IceStarPlanner {
    /** {@code MHelper.java:75} -- pi*(3 - sqrt 5), the golden angle the lattice steps by. */
    private static final double PHI = Math.PI * (3.0 - Math.sqrt(5.0));
    /** IceStarFeature.java:66 -- the star's centre is drawn in the air, with no heightmap call. */
    private static final int MIN_Y = 32;
    private static final int MAX_Y = 128;
    /** IceStarFeature.java:87 -- how much of the union's own distance goes into the colour blend. */
    private static final float SDF_WEIGHT = 0.4f;

    /** The two placed forms of the one feature. */
    public enum Kind {
        BIG(15, 10, 25, 5.0f, 15.0f, 0x6963657374617231L),
        SMALL(8, 7, 12, 3.0f, 5.0f, 0x6963657374617232L);

        private final int rarity;
        private final int minCount;
        private final int maxCount;
        private final float minSize;
        private final float maxSize;
        private final long salt;

        Kind(int rarity, int minCount, int maxCount, float minSize, float maxSize, long salt) {
            this.rarity = rarity;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.salt = salt;
        }
    }

    /** The four blocks a star is made of, outermost first, as the post-process orders them. */
    public enum Ice {
        ANCIENT("betterend:ancient_emerald_ice"),
        DENSE("betterend:dense_emerald_ice"),
        ICE("betterend:emerald_ice"),
        SNOW("betterend:dense_snow");

        private final String id;

        Ice(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** One block the star writes. */
    public record Cell(int x, int y, int z, Ice ice) {
    }

    /**
     * A planned star: its centre, the size it was drawn at, and its blocks.
     * <p>
     * The centre is not recoverable from the cells -- a star has two coincident up-spikes and none
     * pointing down, so its centroid sits well above its actual centre -- and every statement about
     * reach is relative to the centre, so the plan carries it.
     */
    public record Star(int x, int y, int z, float size, List<Cell> cells) {
    }

    private IceStarPlanner() {
    }

    /**
     * The star this chunk grows, or null.
     * <p>
     * Draw order is the mod's and is load-bearing: rarity, then size, then count, then the centre.
     * Vanilla's {@code in_square} draws are consumed and thrown away by the feature itself
     * (IceStarFeature.java:64-66), and since this stream is the port's own there is nothing to gain
     * by replaying them.
     */
    public static Star plan(long worldSeed, int chunkX, int chunkZ, Kind kind,
                            BiomePlacement placement) {
        Random rng = new Random(IslandField.mixSeed((int) (worldSeed ^ kind.salt), chunkX, chunkZ));
        if (rng.nextFloat() >= 1f / kind.rarity) {
            return null;
        }
        float size = kind.minSize + rng.nextFloat() * (kind.maxSize - kind.minSize);
        int count = kind.minCount + rng.nextInt(kind.maxCount - kind.minCount + 1);
        int cx = (chunkX << 4) + rng.nextInt(16);
        int cy = MIN_Y + rng.nextInt(MAX_Y - MIN_Y + 1);
        int cz = (chunkZ << 4) + rng.nextInt(16);

        // minecraft:biome, once, at the star's own centre. ring() first, for the reason
        // DustWastelandsGenerator.surfaceAt records: quartAt answers as if the rings did not exist.
        if (placement.ring(cx, cz) != null
                || placement.quartAt(cx, cy, cz) != BiomeSurface.ICE_STARFIELD) {
            return null;
        }
        return new Star(cx, cy, cz, size, build(rng, size, count, cx, cy, cz));
    }

    /** Just the rarity roll, so a test can measure the rate without the biome gate skewing it. */
    static boolean rolls(long worldSeed, int chunkX, int chunkZ, Kind kind) {
        return new Random(IslandField.mixSeed((int) (worldSeed ^ kind.salt), chunkX, chunkZ))
                .nextFloat() < 1f / kind.rarity;
    }

    /** The spike axes: the Fibonacci lattice, turned 180 degrees about Y. See the class javadoc. */
    static float[][] axes(int count) {
        float[][] out = new float[count][3];
        float max = count - 1;
        for (int i = 0; i < count; i++) {
            double y = 1.0 - (i / max) * 2.0;
            double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = PHI * i;
            double x = Math.cos(theta) * radius;
            double z = Math.sin(theta) * radius;
            double angle = Math.acos(Math.max(-1.0, Math.min(1.0, y)));
            // IceStarFeature.java:55-60. Both poles end up pointing straight up: the first because
            // it is never rotated, the last because acos(-1) misses the < 3.14 guard and the
            // fallback rotates about Y, which an axisymmetric cone cannot see.
            if (angle <= 0.01 || angle >= 3.14) {
                out[i] = new float[]{0f, 1f, 0f};
            } else {
                out[i] = new float[]{(float) -x, (float) y, (float) -z};
            }
        }
        return out;
    }

    private static List<Cell> build(Random rng, float size, int count, int cx, int cy, int cz) {
        float[][] axes = axes(count);
        float radius1 = 3f + (size - 5f) * 0.2f;
        // IceStarFeature.java:68-71 -- the colour thresholds. Note the size < 7 branch: size*5
        // exceeds the whole star, so a small star is pure ice and never grows a snow shell.
        float ancientRadius = size * 0.7f;
        float denseRadius = size * 0.9f;
        float iceRadius = size < 7f ? size * 5f : size * 1.3f;
        float randScale = size * 0.3f;

        int reach = (int) Math.ceil(2f * size - 0.5f) + 1;
        List<Cell> out = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    float distance = union(axes, radius1, size, dx, dy, dz);
                    if (distance >= 0) {
                        continue;
                    }
                    // IceStarFeature.java:84-95 -- the colour is a fuzzed radius, not the raw one:
                    // the union's own distance pulls the bands inward near a spike's axis and the
                    // uniform term feathers every boundary so the shells interlock.
                    float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float d = length + SDF_WEIGHT * distance + rng.nextFloat() * randScale;
                    Ice ice;
                    if (d < ancientRadius) {
                        ice = Ice.ANCIENT;
                    } else if (d < denseRadius) {
                        ice = Ice.DENSE;
                    } else if (d < iceRadius) {
                        ice = Ice.ICE;
                    } else {
                        ice = Ice.SNOW;
                    }
                    out.add(new Cell(cx + dx, cy + dy, cz + dz, ice));
                }
            }
        }
        return out;
    }

    /** {@code SDFUnion} is a plain min over the cones (SDFUnion.java:6-12). */
    private static float union(float[][] axes, float radius1, float size, float px, float py, float pz) {
        float best = Float.MAX_VALUE;
        float lengthSquared = px * px + py * py + pz * pz;
        for (float[] axis : axes) {
            float t = axis[0] * px + axis[1] * py + axis[2] * pz;
            float qx = (float) Math.sqrt(Math.max(0f, lengthSquared - t * t));
            best = Math.min(best, cone(qx, t - (size - 0.5f), radius1, size));
        }
        return best;
    }

    /**
     * IQ's {@code sdCappedCone} with the far radius zero, transcribed from SDFCappedCone.java:28-43.
     * {@code yl} is the point's height along the spike after the translate has been undone.
     */
    private static float cone(float qx, float yl, float r1, float size) {
        float k2x = -r1;
        float k2y = 2f * size;
        float cax = qx - Math.min(qx, yl < 0f ? r1 : 0f);
        float cay = Math.abs(yl) - size;
        float mlt = (-qx * k2x + (size - yl) * k2y) / (k2x * k2x + k2y * k2y);
        mlt = mlt < 0f ? 0f : Math.min(mlt, 1f);
        float cbx = qx + k2x * mlt;
        float cby = yl - size + k2y * mlt;
        float sign = (cbx < 0f && cay < 0f) ? -1f : 1f;
        return sign * (float) Math.sqrt(Math.min(cax * cax + cay * cay, cbx * cbx + cby * cby));
    }
}
