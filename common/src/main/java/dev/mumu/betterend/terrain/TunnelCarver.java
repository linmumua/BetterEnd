package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;

import java.util.Random;

/**
 * The mod's tunnel carver, ported to a per-chunk pass. The second of BetterEnd's two carvers; the
 * first is {@link CaveCarver}, and the six cave biomes list both.
 * <p>
 * Source: {@code Reference/BetterEnd/.../world/carvers/EndTunnelCarver.java} and its configured form
 * {@code worldgen/configured_carver/tunnel_cave.json} -- probability 1.0, threshold 0.15. The other
 * four config fields ({@code replaceable}, {@code y}, {@code yScale}, {@code lava_level}) are never
 * read: the carver hard-codes the end-stone tag instead of {@code cfg.replaceable} and touches
 * {@code cfg} only for the probability and the threshold.
 * <p>
 * <b>Why this is far cheaper than {@link CaveCarver}.</b> The mod's {@code getRange()} is 0 because
 * the noise field is spatially continuous: a chunk reads only its own columns and the tunnels line
 * up across borders by construction. There is no neighbourhood scan and no per-start-chunk RNG --
 * probability is 1.0, so every chunk in a cave biome carves itself.
 * <p>
 * <b>Seed.</b> The mod draws its noise seed from the carving context's {@code RandomState}, a
 * positional factory keyed on {@code betterend:tunnel_cave_noise}, which a Bukkit generator cannot
 * reach. A salt off the world seed stands in, exactly as {@code BiomePlacement}'s cave noises do.
 * Everything downstream is the mod's verbatim -- {@link Random} IS {@code LegacyRandomSource}, and
 * the three {@code nextInt()} draws happen in the order h, v, d. So the tunnels have the mod's
 * character and a DIFFERENT layout for a given world seed, which is already true of {@link
 * CaveCarver}: no world this port generates was ever bit-compatible with the mod's.
 * <p>
 * Deterministic in the world seed alone, and pure in {@code (worldSeed, placement, chunkX, chunkZ)}.
 */
public final class TunnelCarver {
    /** {@code tunnel_cave.json} "threshold"; the legacy TunelCaveFeature hard-coded the same 0.15. */
    private static final float THRESHOLD = 0.15f;
    /**
     * The mod's {@code minGenY + 1}, and the End's {@code minGenY} is 0 -- {@link IslandField}'s
     * matching ceiling is 128. y = 0 is never carved, so an island can never be holed from below.
     */
    private static final int MIN_CARVE_Y = 1;
    /**
     * {@code WoverEndConfig.caveBiomesTopY}. The same 48 as {@code CaveCarver.BAND_CEILING} and
     * {@code BiomePlacement.CAVE_TOP_Y}: caves ARE the cave-biome band, so carving above it would
     * hollow the underside of an island sitting well above the band and leave a flat slab.
     */
    private static final int BAND_CEILING = 48;
    /**
     * The mod breaks its y loop when {@code 1 - clamp((top - y) * 0.1) > 0.5}, i.e. when
     * {@code (top - y) < 5}, so the highest carvable block is {@code top - 5} and five blocks stay
     * solid.
     * <p>
     * Deliberately NOT written as {@link CaveCarver}'s expression. The round carver rejects
     * {@code y > top - 1 - SURFACE_ROOF} and so keeps SIX blocks; same literal, one block apart.
     * Sharing one form between the two would thin every tunnel roof, or thicken every cavern's, by
     * a block.
     */
    private static final int SURFACE_ROOF = 5;
    /** Stands in for the mod's RandomState-derived seed; see the class javadoc. */
    private static final long NOISE_SALT = 0x7A11E15L;

    /** The three noise fields, in the mod's construction order. One triple per world. */
    private record Noises(long seed, OpenSimplexNoise h, OpenSimplexNoise v, OpenSimplexNoise d) {
    }

    /**
     * Built once per world, not once per chunk: unlike a cavern's, this field is global. Keyed on
     * the derived seed so a second world rebuilds it, and the mod caches it the same way for the
     * same reason. A lost race just rebuilds an identical triple.
     */
    private static volatile Noises cached;

    private TunnelCarver() {
    }

    private static Noises noises(long worldSeed) {
        long seed = worldSeed ^ NOISE_SALT;
        Noises local = cached;
        if (local != null && local.seed() == seed) {
            return local;
        }
        // Verbatim: one Random, three nextInt() draws, h then v then d. Reordering them is the
        // easiest way to lose the mod's tunnel character while every test still passes.
        Random random = new Random(seed);
        Noises built = new Noises(seed,
                new OpenSimplexNoise(random.nextInt()),
                new OpenSimplexNoise(random.nextInt()),
                new OpenSimplexNoise(random.nextInt()));
        cached = built;
        return built;
    }

    /**
     * One chunk's carve plan: the shared noise triple plus this chunk's four corner cave factors.
     * Immutable and safe to share across threads.
     */
    public static final class Plan {
        private final Noises noises;
        private final int minBlockX;
        private final int minBlockZ;
        /** The corners at (x1,z1), (x1+16,z1), (x1,z1+16), (x1+16,z1+16), in the mod's order. */
        private final float a;
        private final float b;
        private final float c;
        private final float d;

        private Plan(Noises noises, int minBlockX, int minBlockZ, float a, float b, float c, float d) {
            this.noises = noises;
            this.minBlockX = minBlockX;
            this.minBlockZ = minBlockZ;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }

        /** No corner is a cave biome, so nothing in this chunk can carve. */
        public boolean isEmpty() {
            return a == 0f && b == 0f && c == 0f && d == 0f;
        }

        /**
         * True where a tunnel hollows this block. {@code surfaceTop} is the column's highest solid y
         * ({@link IslandField#topY}); pass {@link Integer#MIN_VALUE} for a void column.
         * <p>
         * A flattened form of the mod's loop. Its {@code break} on the gradient guard is equivalent
         * to a rejection here because gradient is monotone in y: once it exceeds 0.5 it does so for
         * every larger y too. That rejection also subsumes the loop's own {@code y < top} bound.
         */
        public boolean carvedAt(int x, int y, int z, int surfaceTop) {
            if (surfaceTop == Integer.MIN_VALUE) {
                return false;
            }
            if (y < MIN_CARVE_Y || y > BAND_CEILING) {
                return false;
            }
            // The bilinear blend tapers tunnels to nothing at a cave biome's border. It is written
            // in world coordinates but is identical to the mod's local lx/16, lz/16.
            float dx = (x - minBlockX) / 16f;
            float dz = (z - minBlockZ) / 16f;
            float da = a + dx * (b - a);
            float db = c + dx * (d - c);
            float density = 1f - (da + dz * (db - da));
            if (density >= 0.5f) {
                return false;
            }
            if (surfaceTop - y < SURFACE_ROOF) {
                return false;
            }
            // gradient is both the loop's break condition AND a term in the sum below, which is why
            // it is computed rather than folded away into the check above.
            float gradient = 1f - clamp((surfaceTop - y) * 0.1f, 0f, 1f);
            float val = Math.abs((float) noises.h().eval(x * 0.02, y * 0.01, z * 0.02));
            // Math.sin, not Mth.sin: the mod's is a 65536-entry lookup table accurate to ~1e-4,
            // and the exact function is the more faithful of the two to what that table approximates.
            float vert = (float) Math.sin((y + (float) noises.v().eval(x * 0.01, z * 0.01) * 20f) * 0.1f) * 0.9f;
            float dist = (float) noises.d().eval(x * 0.1, y * 0.1, z * 0.1) * 0.12f;
            val = (val + vert * vert + dist) + density + gradient;
            // The mod's end-stone tag test is the caller's job -- every block the generator writes
            // below the surface is end stone -- and its isWaterNear guard is not ported because the
            // port's terrain places no fluid underground at all.
            return val < THRESHOLD;
        }

        /** The lowest and highest world y a tunnel can touch, for loop bounds. */
        public int lowestY(int worldMinY) {
            return Math.max(MIN_CARVE_Y, worldMinY);
        }

        /** @see #lowestY(int) */
        public int highestY() {
            return BAND_CEILING;
        }
    }

    /** Builds the carve plan for one chunk. Pure in {@code (worldSeed, placement, chunkX, chunkZ)}. */
    public static Plan planFor(long worldSeed, BiomePlacement placement, int chunkX, int chunkZ) {
        int x1 = chunkX << 4;
        int z1 = chunkZ << 4;
        // The corners are shared with the neighbouring chunks by construction: this chunk's (x1+16)
        // corner IS the next chunk's x1 corner, which is what makes the density field continuous
        // across a border and is why the mod's getRange() can be 0.
        return new Plan(noises(worldSeed), x1, z1,
                caveFactor(placement, x1, z1),
                caveFactor(placement, x1 + 16, z1),
                caveFactor(placement, x1, z1 + 16),
                caveFactor(placement, x1 + 16, z1 + 16));
    }

    /**
     * 1.0 where the biome carries {@code #betterend:is_end_cave}, else 0.0 -- the same gate that
     * decides whether vanilla runs the End's carvers in a chunk at all, which is why both carvers
     * share {@link BiomePlacement#allowsCarvers}.
     * <p>
     * Sampled at y = 0, as the mod does, which always lands inside the cave band: the band's
     * jittered ceiling is 48 +/- 8 and so is never below 40.
     */
    private static float caveFactor(BiomePlacement placement, int x, int z) {
        return placement.allowsCarvers(x, z) ? 1f : 0f;
    }

    /** {@code Mth.clamp}. */
    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
