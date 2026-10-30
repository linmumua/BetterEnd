package dev.mumu.betterend.config;

import dev.mumu.betterend.vanilla.MonsterDamageBoost;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Everything a server owner can change, read ONCE at plugin load into an immutable snapshot.
 * <p>
 * Nothing here is ever consulted again during generation. {@code DustWastelandsGenerator} memoises
 * one {@code IslandField} per world seed for the life of the world (DustWastelandsGenerator.java:38,56)
 * and {@code IslandField} memoises its density lattice on top of that (IslandField.java:74), so a
 * value re-read mid-world would generate half a world against one field and half against another
 * while the cache kept serving the old one. That is why there is no reload command, and why this is
 * a record handed to the generator's constructor rather than a live view of the config file.
 * <p>
 * Plain Java on purpose -- no Bukkit imports, so it unit-tests without a server. The plugin wires
 * it up with method references:
 * <pre>{@code
 * ConfigStore.Loaded loaded = ConfigStore.of(...).load();
 * BetterEndConfig config = BetterEndConfig.load(loaded.values()::get, getLogger()::warning);
 * }</pre>
 * Two kinds of problem are treated differently, on purpose:
 * <ul>
 *   <li>A value that is out of range is clamped to the documented range and reported by the
 *       {@code warn} sink. The owner's numeric intent is kept as closely as the range allows, and the
 *       server keeps running -- this is the behaviour config.yml documents.</li>
 *   <li>A value of the wrong TYPE (or an unreadable source) is NOT guessed at. Silently using a
 *       default for a key the owner typo'd would bake the wrong terrain into the world, so those are
 *       collected and raised as a {@link ConfigException}, which stops the plugin enabling.</li>
 * </ul>
 *
 * @param bigIslands               TERRAIN.
 * @param mediumIslands            TERRAIN. The layer that carries the central island.
 * @param smallIslands             TERRAIN.
 * @param flavoliteLayersPerChunk  BetterEndPopulator.java:34 -- placed_feature count 6.
 * @param thallasiumVeinsPerChunk  BetterEndPopulator.java:70 -- placed_feature count 24.
 * @param enderVeinsPerChunk       BetterEndPopulator.java:71 -- placed_feature count 12.
 * @param violeciteLayersPerChunk  violecite_layer.json count 8, chorus_forest + shadow_forest only.
 * @param amberVeinsPerChunk       amber_ore.json count 60, amber_land only.
 * @param dragonBoneVeinsPerChunk  dragon_bone_ore.json count 24, dragon_graveyards only.
 * @param advancementSampleTicks   reserved tick interval for that future tab.
 * @param monsterDamageMultiplier  damage a player TAKES from a monster in our worlds is multiplied by
 *                                 this; 1.0 disables it. The ONLY key in this record that a reload
 *                                 can apply -- see {@code ReloadDiff}. The rest is read once, because
 *                                 the generator memoises its density field per world seed.
 */
public record BetterEndConfig(
        LayerSettings bigIslands,
        LayerSettings mediumIslands,
        LayerSettings smallIslands,
        int flavoliteLayersPerChunk,
        int thallasiumVeinsPerChunk,
        int enderVeinsPerChunk,
        int violeciteLayersPerChunk,
        int amberVeinsPerChunk,
        int dragonBoneVeinsPerChunk,
        int advancementSampleTicks,
        double monsterDamageMultiplier
) {
    /** Sanity ceiling: a flavolite layer is an unbounded flood fill, the priciest work per chunk. */
    public static final int MAX_FLAVOLITE_LAYERS = 64;
    /** Sanity ceiling on the two ore blob counts. 0 disables the ore. */
    public static final int MAX_VEINS = 256;
    /** Once a tick is as often as anyone could want; a minute is as rare as is still useful. */
    public static final int MIN_ADVANCEMENT_TICKS = 1;
    public static final int MAX_ADVANCEMENT_TICKS = 1200;
    /**
     * The defaults, and the only place they live. A key absent from the owner's file falls back to
     * the value here; {@link ConfigStore} is what puts a genuinely new key back into the file so the
     * owner can see it, without ever overwriting a value they set.
     * <p>
     * The three layers are GeneratorConfig.java:78,84,90 byte for byte; IslandField's constructor
     * builds its three Layers from them and hardcodes nothing.
     */
    public static final BetterEndConfig DEFAULTS = new BetterEndConfig(
            new LayerSettings(300f, 200f, 70f / 128, 10f / 128, 0.5f),
            new LayerSettings(150f, 100f, 70f / 128, 20f / 128, 0.5f),
            new LayerSettings(60f, 50f, 70f / 128, 30f / 128, 0.5f),
            6,
            24,
            12,
            8,
            60,
            24,
            20,
            1.5
    );

    /**
     * Reads the whole surface once.
     *
     * @param values a lookup by dotted path returning {@code null} for an absent key -- the flattened
     *               map {@link ConfigStore} produces. Never called after this method returns.
     * @param warn   receives one line per clamped, out-of-range value -- {@code Logger::warning}.
     * @return an immutable snapshot
     * @throws ConfigException if any present key has a value of the wrong type
     */
    public static BetterEndConfig load(Function<String, Object> values, Consumer<String> warn) {
        List<String> problems = new ArrayList<>();
        BetterEndConfig loaded = new BetterEndConfig(
                layer(values, problems, warn, "generator.layers.big", DEFAULTS.bigIslands),
                layer(values, problems, warn, "generator.layers.medium", DEFAULTS.mediumIslands),
                layer(values, problems, warn, "generator.layers.small", DEFAULTS.smallIslands),
                integer(values, problems, warn, "features.flavolite-layers-per-chunk",
                        DEFAULTS.flavoliteLayersPerChunk, 0, MAX_FLAVOLITE_LAYERS),
                integer(values, problems, warn, "features.thallasium-veins-per-chunk",
                        DEFAULTS.thallasiumVeinsPerChunk, 0, MAX_VEINS),
                integer(values, problems, warn, "features.ender-veins-per-chunk",
                        DEFAULTS.enderVeinsPerChunk, 0, MAX_VEINS),
                integer(values, problems, warn, "features.violecite-layers-per-chunk",
                        DEFAULTS.violeciteLayersPerChunk, 0, MAX_FLAVOLITE_LAYERS),
                integer(values, problems, warn, "features.amber-veins-per-chunk",
                        DEFAULTS.amberVeinsPerChunk, 0, MAX_VEINS),
                integer(values, problems, warn, "features.dragon-bone-veins-per-chunk",
                        DEFAULTS.dragonBoneVeinsPerChunk, 0, MAX_VEINS),
                integer(values, problems, warn, "advancements.sample-interval-ticks",
                        DEFAULTS.advancementSampleTicks,
                        MIN_ADVANCEMENT_TICKS, MAX_ADVANCEMENT_TICKS),
                // The range is the listener's own (MonsterDamageBoost.MIN/MAX_MULTIPLIER) rather than
                // a second copy of the same numbers: the config loader's job here is to warn about a
                // value the listener would clamp anyway, so both must agree by construction.
                decimal(values, problems, warn, ReloadDiff.MONSTER_MULTIPLIER_KEY,
                        DEFAULTS.monsterDamageMultiplier,
                        MonsterDamageBoost.MIN_MULTIPLIER, MonsterDamageBoost.MAX_MULTIPLIER)
        );
        if (!problems.isEmpty()) {
            throw new ConfigException("config.yml 有 " + problems.size()
                    + " 处无效配置，BetterEnd 将不启用；Paper/Folia 可能回退原版生成器，"
                    + "请停止服务器并修正后再生成世界（原文件保持不动）：\n  - "
                    + String.join("\n  - ", problems));
        }
        return loaded;
    }

    private static LayerSettings layer(Function<String, Object> values, List<String> problems,
                                       Consumer<String> warn, String path, LayerSettings fallback) {
        return new LayerSettings(
                number(values, problems, warn, path + ".distance", fallback.distance(),
                        LayerSettings.MIN_DISTANCE, LayerSettings.MAX_DISTANCE),
                number(values, problems, warn, path + ".scale", fallback.scale(),
                        LayerSettings.MIN_SCALE, LayerSettings.MAX_SCALE),
                number(values, problems, warn, path + ".average-height", fallback.averageHeight(),
                        LayerSettings.MIN_UNIT, LayerSettings.MAX_UNIT),
                number(values, problems, warn, path + ".height-variation", fallback.heightVariation(),
                        LayerSettings.MIN_UNIT, LayerSettings.MAX_UNIT),
                number(values, problems, warn, path + ".coverage", fallback.rawCoverage(),
                        LayerSettings.MIN_UNIT, LayerSettings.MAX_UNIT)
        );
    }

    /**
     * Reads one float. A present-but-wrong type is recorded as a problem (fatal); a readable number
     * outside the range is clamped and reported, in KernelTemplate.java:63's style: name the key, the
     * value found and what was accepted. The clamp is the same one {@link LayerSettings}' constructor
     * applies; doing it here too is what lets the message name the key.
     */
    private static float number(Function<String, Object> values, List<String> problems, Consumer<String> warn,
                                String key, float fallback, float min, float max) {
        Object raw = values.apply(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number found) || !Float.isFinite(found.floatValue())) {
            problem(problems, key, raw, "一个有限数字");
            return fallback;
        }
        float value = found.floatValue();
        float clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            warn(warn, key, value, min + ".." + max, clamped);
        }
        return clamped;
    }

    /**
     * Reads one double. Identical contract to {@link #number} -- a wrong type is fatal, an
     * out-of-range number is clamped and reported -- but kept at double precision because the
     * monster-damage factor is multiplied into a {@code double} damage figure, and rounding it to
     * float first would put an invisible error into every hit.
     */
    private static double decimal(Function<String, Object> values, List<String> problems, Consumer<String> warn,
                                  String key, double fallback, double min, double max) {
        Object raw = values.apply(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number found) || !Double.isFinite(found.doubleValue())) {
            problem(problems, key, raw, "一个有限数字");
            return fallback;
        }
        double value = found.doubleValue();
        double clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            warn(warn, key, value, min + ".." + max, clamped);
        }
        return clamped;
    }

    private static int integer(Function<String, Object> values, List<String> problems, Consumer<String> warn,
                               String key, int fallback, int min, int max) {
        Object raw = values.apply(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number found)
                || !Double.isFinite(found.doubleValue())
                || found.doubleValue() != Math.rint(found.doubleValue())) {
            problem(problems, key, raw, "一个整数 " + min + ".." + max);
            return fallback;
        }
        long value = found.longValue();
        int clamped = (int) Math.clamp(value, min, max);
        if (clamped != value) {
            warn(warn, key, value, min + ".." + max, clamped);
        }
        return clamped;
    }

    /** Records a present-but-unusable value; the caller raises them all together as a {@link ConfigException}. */
    private static void problem(List<String> problems, String key, Object found, String allowed) {
        problems.add("'" + key + "' 的值 " + describe(found) + " 无效，应为 " + allowed + "。");
    }

    private static String describe(Object found) {
        if (found instanceof String text) {
            return "\"" + text + "\"";
        }
        return String.valueOf(found);
    }

    /** One clamped, out-of-range value: names the key, what it was and what was used instead. */
    private static void warn(Consumer<String> warn, String key, Object found, String allowed, Object used) {
        warn.accept("config.yml: '" + key + "' was " + found + ", which is outside " + allowed
                + "; using " + used + " instead.");
    }
}
