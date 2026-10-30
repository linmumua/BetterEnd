package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The mod's round cave carver, ported to a per-chunk pass.
 * <p>
 * Source: {@code Reference/BetterEnd/.../world/carvers/EndCaveCarver.java} and its configured form
 * {@code worldgen/configured_carver/round_cave.json} -- probability 0.4, radius uniform 10..30,
 * centre y uniform 8..56, vertical_squash 1.6.
 * <p>
 * <b>Why a per-chunk pass and not a term in {@link IslandField}.</b> Folding the caves into the
 * density field would keep {@code isSolid} / {@code topY} / {@code isEndStone} automatically honest,
 * but a column query would then have to test every start chunk within reach -- 7x7 = 49 candidates,
 * of which ~40% pass the probability roll -- and evaluate a 3D simplex per candidate per y inside the
 * band. That is several times the whole chunk's current noise budget. Carving after the fill costs
 * work proportional to the carved volume instead, which is what the mod does
 * ({@code WorldCarver} runs once per chunk, after {@code fillFromNoise}).
 * <p>
 * The one thing that buys is also its one cost: {@link IslandField#isEndStone} still reports solid
 * inside a carved cavern, so {@code BetterEndPopulator}'s ore blobs can place a few blocks into cave
 * air. Surface things are unaffected -- {@link #SURFACE_ROOF} guarantees a cavern never comes within
 * five blocks of the column top, so {@code topY}, the surface cap and every plant and tree are
 * exactly as they were.
 * <p>
 * Caves only exist under the six cave biomes, because that is where the mod attaches this carver
 * (see {@link BiomePlacement#allowsCarvers}). An earlier revision of this class carved under all 27
 * biomes, which was a whole class of caves the mod does not generate.
 * <p>
 * Deterministic in the world seed alone: every cave is derived from
 * {@link IslandField#mixSeed(int, int, int)} of its own start chunk, so the same chunk carves
 * identically no matter which thread or which neighbour asks first, and no cross-chunk state exists.
 */
public final class CaveCarver {
    /** {@code round_cave.json} "probability". EndCaveCarver.java:45 rolls this per start chunk. */
    private static final float PROBABILITY = 0.4f;
    /** {@code round_cave.json} "radius": minecraft:uniform 10..30 inclusive. */
    private static final int RADIUS_MIN = 10;
    private static final int RADIUS_MAX = 30;
    /** {@code round_cave.json} "y": minecraft:uniform absolute 8..56 inclusive. */
    private static final int CENTRE_Y_MIN = 8;
    private static final int CENTRE_Y_MAX = 56;
    /** {@code round_cave.json} "vertical_squash". Multiplies the (y - centreY) term before the sphere test. */
    private static final float SQUASH = 1.6f;
    /** EndCaveCarver.java:100 -- the widest the noise-distorted sphere can reach past its radius. */
    private static final int NOISE_MARGIN = 5;
    /**
     * EndCaveCarver.java:57 -- solid blocks kept between a cavern's ceiling and the column's surface.
     * Without it a large cavern carves through an island top and opens a pit to the sky.
     */
    private static final int SURFACE_ROOF = 5;
    /**
     * EndCaveCarver.java:73 -- {@code WoverEndConfig.caveBiomesTopY}, the top of the vertical
     * cave-biome band. Caves ARE that band, so carving above it would hollow the underside of a
     * floating island sitting well above the band and leave a flat slab.
     */
    private static final int BAND_CEILING = 48;
    /**
     * EndCaveCarver.java:62 {@code getRange()}. A start chunk this many chunks away can still reach
     * the chunk being carved: reach <= 30 + 5 = 35 blocks, which is 2.19 chunks, so 3 is the mod's
     * own (conservative) bound and is reproduced rather than tightened.
     */
    private static final int RANGE = 3;
    /** EndCaveCarver.java:127 -- the literal salt the cavern's shape noise is seeded with. */
    private static final int NOISE_SALT = 534;

    private CaveCarver() {
    }

    /** One cavern: centre, radius and the shape noise seeded from its own centre. */
    private record Cavern(int centreX, int centreY, int centreZ, int radius, OpenSimplexNoise noise) {
        /** EndCaveCarver.java:129-130. */
        double highRadius() {
            return radius * 0.75;
        }

        double noiseRadius() {
            return radius * 0.25;
        }
    }

    /**
     * Every cavern whose footprint can reach the given chunk.
     * <p>
     * The four draws happen unconditionally and in the mod's order (x, z, y, radius) so that a start
     * chunk yields the same cavern regardless of which target chunk asks -- EndCaveCarver.java:90-98
     * makes the same point about vanilla visiting one start chunk once per target chunk.
     */
    private static List<Cavern> cavernsNear(long worldSeed, BiomePlacement placement,
                                            int chunkX, int chunkZ) {
        List<Cavern> out = new ArrayList<>();
        for (int sx = chunkX - RANGE; sx <= chunkX + RANGE; sx++) {
            for (int sz = chunkZ - RANGE; sz <= chunkZ + RANGE; sz++) {
                // round_cave is attached to the six is_end_cave biomes and to nothing else, so a
                // start chunk elsewhere never rolls a cavern. Tested per START chunk, not per
                // block: a cavern is meant to spill out of its own biome, which is what RANGE is
                // for. Placed before the draws deliberately -- this RNG is seeded per start chunk
                // and shared with nothing, so skipping it cannot shift another chunk's geometry.
                if (!placement.allowsCarvers(sx << 4, sz << 4)) {
                    continue;
                }
                Random random = new Random(IslandField.mixSeed((int) worldSeed, sx, sz));
                if (random.nextFloat() > PROBABILITY) continue;
                int centreX = (sx << 4) + random.nextInt(16);
                int centreZ = (sz << 4) + random.nextInt(16);
                int centreY = CENTRE_Y_MIN + random.nextInt(CENTRE_Y_MAX - CENTRE_Y_MIN + 1);
                int radius = RADIUS_MIN + random.nextInt(RADIUS_MAX - RADIUS_MIN + 1);
                int reach = radius + NOISE_MARGIN;
                int minBX = chunkX << 4;
                int minBZ = chunkZ << 4;
                // EndCaveCarver.java:116-120, the O(1) box rejection.
                if (centreX + reach < minBX || centreX - reach > minBX + 15
                        || centreZ + reach < minBZ || centreZ - reach > minBZ + 15) {
                    continue;
                }
                out.add(new Cavern(centreX, centreY, centreZ, radius,
                        new OpenSimplexNoise(IslandField.mixSeed(NOISE_SALT, centreX, centreZ))));
            }
        }
        return out;
    }

    /**
     * True where a cavern hollows this block, ignoring whether terrain is there at all.
     * <p>
     * {@code surfaceTop} is the column's highest solid y ({@link IslandField#topY}); pass
     * {@link Integer#MIN_VALUE} for a void column, which can never be carved.
     */
    private static boolean carved(List<Cavern> caverns, int x, int y, int z, int surfaceTop) {
        if (y > BAND_CEILING) return false;
        if (surfaceTop == Integer.MIN_VALUE) return false;
        // EndCaveCarver.java:161-163 -- keep a solid roof so the cavern cannot breach the island top.
        if (y > surfaceTop - 1 - SURFACE_ROOF) return false;
        for (Cavern c : caverns) {
            int dx = x - c.centreX();
            int dz = z - c.centreZ();
            int reach = c.radius() + NOISE_MARGIN;
            if (Math.abs(dx) > reach || Math.abs(dz) > reach) continue;
            // Same rejection on the vertical axis, which the mod does not need because it walks a
            // cavern's own bounding box rather than answering per block. Provably cannot change the
            // outcome: the test below is dx*dx + dz*dz + ysq < r*r with r = noise*0.25R + 0.75R and
            // noise in [-1, 1], so r <= R < reach; a ysq already >= reach*reach can never pass it.
            // Worth having because everything after this line is a 3D simplex evaluation.
            if (Math.abs((y - c.centreY()) * SQUASH) > reach) continue;
            // EndCaveCarver.java:165-168. The squash multiplies the vertical distance BEFORE squaring,
            // and the int cast reproduces MHelper.sqr's truncation on the already-squared value.
            int ysq = (int) sqr((y - c.centreY()) * SQUASH);
            double r = c.noise().eval(x * 0.1, y * 0.1, z * 0.1) * c.noiseRadius() + c.highRadius();
            if ((double) (dx * dx + dz * dz) + ysq < r * r) return true;
        }
        return false;
    }

    private static double sqr(double v) {
        return v * v;
    }

    /**
     * A chunk's carve plan: the caverns reaching it, resolved once so a caller can ask about many
     * blocks without rebuilding the 7x7 scan. Immutable and safe to share across threads.
     */
    public static final class Plan {
        private final List<Cavern> caverns;

        private Plan(List<Cavern> caverns) {
            this.caverns = caverns;
        }

        /** True if no cavern reaches this chunk at all, so the caller can skip the whole pass. */
        public boolean isEmpty() {
            return caverns.isEmpty();
        }

        /** @see CaveCarver#carved(List, int, int, int, int) */
        public boolean carvedAt(int x, int y, int z, int surfaceTop) {
            return carved(caverns, x, y, z, surfaceTop);
        }

        /** The lowest and highest world y any of these caverns can touch, for loop bounds. */
        public int lowestY(int worldMinY) {
            int lowest = Integer.MAX_VALUE;
            for (Cavern c : caverns) {
                lowest = Math.min(lowest, c.centreY() - (int) ((c.radius() + NOISE_MARGIN) / SQUASH) - 1);
            }
            return caverns.isEmpty() ? worldMinY : Math.max(lowest, worldMinY);
        }

        /** @see #lowestY(int) */
        public int highestY() {
            int highest = Integer.MIN_VALUE;
            for (Cavern c : caverns) {
                highest = Math.max(highest, c.centreY() + (int) ((c.radius() + NOISE_MARGIN) / SQUASH) + 1);
            }
            return Math.min(highest, BAND_CEILING);
        }
    }

    /** Builds the carve plan for one chunk. Pure in {@code (worldSeed, placement, chunkX, chunkZ)}. */
    public static Plan planFor(long worldSeed, BiomePlacement placement, int chunkX, int chunkZ) {
        return new Plan(cavernsNear(worldSeed, placement, chunkX, chunkZ));
    }
}
