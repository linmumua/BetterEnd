package dev.mumu.betterend.config;

/**
 * One island layer, the port's equivalent of the mod's
 * {@code org.betterx.betterend.world.generator.LayerOptions}.
 * <p>
 * Five configurable values, four derived ones. The mod has two constructors and only the JSON
 * one -- the config path -- clamps (LayerOptions.java:39-53); its 5-arg programmatic constructor
 * (LayerOptions.java:18-37) assigns raw. This mirrors the JSON one, because this IS the config
 * path: {@code IslandField.Layer} (IslandField.java:337-348) clamps nothing at all, and
 * {@code centerDist} divides by {@code distance}, so an unclamped {@code distance: 0} out of a
 * YAML file would hand the generator an infinity.
 * <p>
 * The compact constructor clamps, so a {@code LayerSettings} that exists is always in range;
 * {@link BetterEndConfig} clamps first only so it can name the offending key in a warning.
 * <p>
 * Plain Java: no Bukkit, nothing outside {@code java.*}. Immutable, so one instance is safe to
 * read from every worldgen worker at once.
 *
 * @param distance        cell size of the layer lattice, in blocks. 1..8192 (LayerOptions.java:56-58).
 * @param scale           island size multiplier. 0.1..1024 (LayerOptions.java:60-62).
 * @param averageHeight   island centre height as a fraction of 128. 0..1 (LayerOptions.java:68-70).
 * @param heightVariation half the height spread, same units. 0..1 (LayerOptions.java:72-74).
 * @param rawCoverage     0..1, higher means more islands. See {@link #coverage()}
 *                        (LayerOptions.java:64-66).
 */
public record LayerSettings(
        float distance,
        float scale,
        float averageHeight,
        float heightVariation,
        float rawCoverage
) {
    public LayerSettings {
        distance = clampDistance(distance);
        scale = clampScale(scale);
        averageHeight = clampUnit(averageHeight);
        heightVariation = clampUnit(heightVariation);
        rawCoverage = clampUnit(rawCoverage);
    }

    /** LayerOptions.java:56-58. The min of 1 is what keeps {@link #centerDist()} finite. */
    public static final float MIN_DISTANCE = 1f;
    public static final float MAX_DISTANCE = 8192f;
    /** LayerOptions.java:60-62. */
    public static final float MIN_SCALE = 0.1f;
    public static final float MAX_SCALE = 1024f;
    /** LayerOptions.java:68-74 -- average height, height variation and raw coverage all share 0..1. */
    public static final float MIN_UNIT = 0f;
    public static final float MAX_UNIT = 1f;

    public static float clampDistance(float value) {
        return Math.clamp(value, MIN_DISTANCE, MAX_DISTANCE);
    }

    public static float clampScale(float value) {
        return Math.clamp(value, MIN_SCALE, MAX_SCALE);
    }

    public static float clampUnit(float value) {
        return Math.clamp(value, MIN_UNIT, MAX_UNIT);
    }

    /**
     * LayerOptions.java:64-66 -- {@code 0.9999F - clamp(rawCoverage, 0, 1) * 2}, so -1.0001..0.9999.
     * <p>
     * The name is inverted from its meaning: this is the <em>threshold</em> the layer's simplex
     * density must exceed for a cell to carry an island (IslandLayer.java:80), so a HIGHER
     * {@link #rawCoverage} gives a LOWER threshold and MORE islands. Read straight into
     * {@code IslandField.Layer.coverage}, a per-layer field, not a shared constant.
     */
    public float coverage() {
        return 0.9999f - rawCoverage * 2;
    }

    /**
     * LayerOptions.java:34/50. Deliberately NOT clamped, exactly as the mod leaves it: an island
     * centre is drawn from {@code [minY, maxY] * 128}, so {@code averageHeight 0} with
     * {@code heightVariation 1} puts centres at y=-128 and nothing generates.
     */
    public float minY() {
        return averageHeight - heightVariation;
    }

    /**
     * LayerOptions.java:35/51. Also unclamped: a maxY above {@code FADE_OUT_START}
     * (IslandField.java:33) lands islands in the band where density is forced to -1, i.e. nowhere.
     */
    public float maxY() {
        return averageHeight + heightVariation;
    }

    /**
     * LayerOptions.java:36/52 -- {@code Mth.floor(1000 / distance)}. Consumed at
     * IslandField.java:373-375 as a cell count compared against a squared cell distance; the unit
     * mismatch is the mod's (IslandLayer.java:71) and is reproduced on purpose.
     */
    public long centerDist() {
        return (long) Math.floor(1000 / distance);
    }
}
