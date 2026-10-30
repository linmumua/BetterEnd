package dev.mumu.betterend.biome;

import dev.mumu.betterend.terrain.OpenSimplexNoise;

import java.util.List;

/**
 * What a cave biome paints onto a carved cave face: the wall shell, the floor and the ceiling.
 * <p>
 * The mod keeps these on its {@code EndCaveBiome} subclasses rather than in the biome json, and its
 * own {@code CaveSurfaceCoatFeature} javadoc explains why: {@code JadeCaveBiome.getWall} picks from
 * three jadestones with two noise fields <i>per position</i>, which no datapack
 * {@code BlockStateProvider} can express. Flattening it to one stone or a dumb random mix is exactly
 * how the jade caves lose their banding.
 * <p>
 * Only three of the six cave biomes paint anything at all. The three {@code empty_*} caves override
 * nothing, so their walls, floors and ceilings stay the end stone the carver exposed -- that is the
 * mod's intent, not a gap in this port.
 * <table>
 *   <caption>The whole table</caption>
 *   <tr><th>biome</th><th>wall</th><th>floor</th><th>ceiling</th></tr>
 *   <tr><td>jade_cave</td><td>virid / azure / sandy jadestone by noise</td><td>-</td><td>-</td></tr>
 *   <tr><td>lush_aurora_cave</td><td>-</td><td>cave_moss</td><td>cave_moss</td></tr>
 *   <tr><td>lush_smaragdant_cave</td><td>-</td><td>cave_moss</td><td>-</td></tr>
 *   <tr><td>empty_aurora_cave</td><td>-</td><td>-</td><td>-</td></tr>
 *   <tr><td>empty_end_cave</td><td>-</td><td>-</td><td>-</td></tr>
 *   <tr><td>empty_smaragdant_cave</td><td>-</td><td>-</td><td>-</td></tr>
 * </table>
 * <p>
 * Pure Java, returning CraftEngine ids as strings exactly as {@link BiomeSurface} does, so the
 * banding is unit-tested without a server.
 */
public final class CaveCoat {
    /**
     * {@code config/cave_surface_coat.json} "shell_depth": how many blocks of wall material are
     * painted inward from a cave face.
     */
    public static final int SHELL_DEPTH = 5;

    /**
     * {@code JadeCaveBiome.Biome}'s two noises. Seeded from string hashes and NOT from the world
     * seed, exactly as the mod seeds them -- so the jade banding is the same in every world, which
     * is the mod's behaviour and not an oversight to "fix".
     */
    private static final OpenSimplexNoise JADE_WALL = new OpenSimplexNoise("jade_cave".hashCode());
    private static final OpenSimplexNoise JADE_DEPTH = new OpenSimplexNoise("depth_noise".hashCode());
    /** {@code JadeCaveBiome.JADE[0..2]}, in the mod's index order. */
    private static final String[] JADE = {
            "betterend:virid_jadestone", "betterend:azure_jadestone", "betterend:sandy_jadestone"};

    private static final String CAVE_MOSS = "betterend:cave_moss";

    /**
     * Every id this class can ever return, so the generator can resolve them once at startup rather
     * than reaching into CraftEngine from inside a populator.
     */
    public static final List<String> PALETTE = List.of(JADE[0], JADE[1], JADE[2], CAVE_MOSS);

    private CaveCoat() {
    }

    /**
     * The wall block {@code shellDepth} layers inward from the face, or null to leave the rock be.
     * <p>
     * {@code JadeCaveBiome.getWall} verbatim: a coarse depth noise scales the y coordinate, a fine
     * noise jitters it, and the floor of the product picks one of three jadestones. The jitter is
     * what makes the bands wander instead of lying in flat slabs, and the 0.02 / 0.2 frequency split
     * is what makes them read as strata rather than as noise.
     */
    public static String wall(BiomeSurface biome, int x, int y, int z) {
        if (biome != BiomeSurface.JADE_CAVE) {
            return null;
        }
        double depth = JADE_DEPTH.eval(x * 0.02, z * 0.02) * 0.2 + 0.5;
        int index = (int) Math.floor((y + JADE_WALL.eval(x * 0.2, z * 0.2) * 1.5) * depth + 0.5);
        return JADE[Math.abs(index) % 3];
    }

    /**
     * The block a cave floor becomes, or null to leave it be.
     * <p>
     * The mod applies the biome's {@code getTopMaterial()} but skips it when it is plain end stone,
     * because repainting the rock with itself would only cost a write -- and, for jade, would undo
     * the jadestone shell painted a step earlier.
     */
    public static String floor(BiomeSurface biome) {
        return switch (biome) {
            case LUSH_AURORA_CAVE, LUSH_SMARAGDANT_CAVE -> CAVE_MOSS;
            default -> null;
        };
    }

    /** The block a cave ceiling becomes, or null to leave it be. */
    public static String ceiling(BiomeSurface biome) {
        return biome == BiomeSurface.LUSH_AURORA_CAVE ? CAVE_MOSS : null;
    }

    /**
     * Whether this biome would paint anything at all. The sweep is expensive and most cave chunks
     * belong to a biome that paints nothing, so this is the gate that decides whether it runs.
     */
    public static boolean paintsAnything(BiomeSurface biome) {
        return biome == BiomeSurface.JADE_CAVE || floor(biome) != null || ceiling(biome) != null;
    }
}
