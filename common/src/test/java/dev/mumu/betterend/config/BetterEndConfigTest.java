package dev.mumu.betterend.config;

import dev.mumu.betterend.vanilla.MonsterDamageBoost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped config.yml lives in the plugin module and the loader lives here, so the classic bug
 * is the two drifting apart. {@link #theShippedConfigYmlIsExactlyTheDefaults()} is the test that
 * catches it; the rest pin the mod's clamps and derivations.
 */
class BetterEndConfigTest {
    /** Every key the loader reads, and the only keys config.yml may contain. */
    private static final Set<String> KEYS = Set.of(
            "config-version",
            "generator.layers.big.distance",
            "generator.layers.big.scale",
            "generator.layers.big.average-height",
            "generator.layers.big.height-variation",
            "generator.layers.big.coverage",
            "generator.layers.medium.distance",
            "generator.layers.medium.scale",
            "generator.layers.medium.average-height",
            "generator.layers.medium.height-variation",
            "generator.layers.medium.coverage",
            "generator.layers.small.distance",
            "generator.layers.small.scale",
            "generator.layers.small.average-height",
            "generator.layers.small.height-variation",
            "generator.layers.small.coverage",
            "features.flavolite-layers-per-chunk",
            "features.thallasium-veins-per-chunk",
            "features.ender-veins-per-chunk",
            "features.violecite-layers-per-chunk",
            "features.amber-veins-per-chunk",
            "features.dragon-bone-veins-per-chunk",
            "advancements.sample-interval-ticks",
            "end-damage.monster-multiplier"
    );

    // --- the drift check ----------------------------------------------------------------------

    @Test
    void theShippedConfigYmlIsExactlyTheDefaults() throws IOException {
        Map<String, Object> shipped = parse(configYml());

        assertEquals(KEYS, shipped.keySet(),
                "config.yml and the loader disagree about which keys exist");

        List<String> warnings = new ArrayList<>();
        BetterEndConfig loaded = BetterEndConfig.load(shipped::get, warnings::add);

        assertEquals(List.of(), warnings, "the shipped config.yml must load without a single warning");
        assertEquals(BetterEndConfig.DEFAULTS, loaded,
                "a value in config.yml drifted from BetterEndConfig.DEFAULTS");
    }

    @Test
    void theDefaultsAreTheModsAndAreNotAllTheSame() {
        BetterEndConfig defaults = BetterEndConfig.DEFAULTS;
        // GeneratorConfig.java:78,84,90 - LayerOptions(distance, scale, averageHeight, heightVariation).
        assertEquals(300f, defaults.bigIslands().distance());
        assertEquals(200f, defaults.bigIslands().scale());
        assertEquals(150f, defaults.mediumIslands().distance());
        assertEquals(100f, defaults.mediumIslands().scale());
        assertEquals(60f, defaults.smallIslands().distance());
        assertEquals(50f, defaults.smallIslands().scale());
        // All three share averageHeight 70/128; only the variation differs.
        assertEquals(70f / 128, defaults.bigIslands().averageHeight());
        assertEquals(70f / 128, defaults.mediumIslands().averageHeight());
        assertEquals(70f / 128, defaults.smallIslands().averageHeight());
        assertEquals(10f / 128, defaults.bigIslands().heightVariation());
        assertEquals(20f / 128, defaults.mediumIslands().heightVariation());
        assertEquals(30f / 128, defaults.smallIslands().heightVariation());
        // Non-degenerate: three genuinely different layers, not the same object three times.
        assertNotEquals(defaults.bigIslands(), defaults.mediumIslands());
        assertNotEquals(defaults.mediumIslands(), defaults.smallIslands());
    }

    // --- the mod's clamps ---------------------------------------------------------------------

    @Test
    void everyLayerValueIsClampedExactlyAsTheModClampsIt() {
        // LayerOptions.java:56-74. Each pair is (what the file said, what the mod allows).
        assertEquals(1f, layer("distance", 0f).distance(), "distance min is 1 (LayerOptions.java:56-58)");
        assertEquals(1f, layer("distance", -5000f).distance());
        assertEquals(8192f, layer("distance", 99999f).distance());
        assertEquals(0.1f, layer("scale", 0.01f).scale(), "scale min is 0.1 (LayerOptions.java:60-62)");
        assertEquals(1024f, layer("scale", 4000f).scale());
        assertEquals(0f, layer("average-height", -1f).averageHeight());
        assertEquals(1f, layer("average-height", 2f).averageHeight());
        assertEquals(0f, layer("height-variation", -0.5f).heightVariation());
        assertEquals(1f, layer("height-variation", 7f).heightVariation());
        assertEquals(0f, layer("coverage", -1f).rawCoverage());
        assertEquals(1f, layer("coverage", 5f).rawCoverage());

        // In range: passed through untouched, so the clamp is not just pinning everything.
        assertEquals(777f, layer("distance", 777f).distance());
        assertEquals(0.25f, layer("coverage", 0.25f).rawCoverage());
    }

    @Test
    void aClampedValueNamesTheKeyTheValueAndTheRange() {
        List<String> warnings = new ArrayList<>();
        BetterEndConfig.load(Map.<String, Object>of("generator.layers.big.distance", 0.0)::get, warnings::add);

        assertEquals(1, warnings.size(), warnings.toString());
        String message = warnings.getFirst();
        assertTrue(message.contains("generator.layers.big.distance"), message);
        assertTrue(message.contains("0.0"), message);
        assertTrue(message.contains("1.0..8192.0"), message);
    }

    @Test
    void distanceZeroCannotPoisonTheDerivedCenterDist() {
        // LayerOptions.java:36/52 divides by distance. Unclamped, `distance: 0` is an infinity
        // and every island in the layer is culled forever.
        assertEquals(1000L, layer("distance", 0f).centerDist());
    }

    @Test
    void theConstructorClampsToo() {
        // The record is the invariant, not just the loader: nothing can build an out-of-range layer.
        LayerSettings hostile = new LayerSettings(0f, -3f, 12f, -12f, 99f);
        assertEquals(1f, hostile.distance());
        assertEquals(0.1f, hostile.scale());
        assertEquals(1f, hostile.averageHeight());
        assertEquals(0f, hostile.heightVariation());
        assertEquals(1f, hostile.rawCoverage());
    }

    @Test
    void featureCountsAreClampedAndCanBeDisabled() {
        assertEquals(0, load("features.thallasium-veins-per-chunk", -4).thallasiumVeinsPerChunk());
        assertEquals(BetterEndConfig.MAX_VEINS,
                load("features.ender-veins-per-chunk", 10_000).enderVeinsPerChunk());
        assertEquals(BetterEndConfig.MAX_FLAVOLITE_LAYERS,
                load("features.flavolite-layers-per-chunk", 999).flavoliteLayersPerChunk());
        assertEquals(3, load("features.flavolite-layers-per-chunk", 3).flavoliteLayersPerChunk());
    }

    @Test
    void theMonsterDamageMultiplierIsClampedAtBothEnds() {
        // Below 1.0 would make the End SAFER, which is the opposite of what the setting is for; a
        // four-digit value would one-shot players with no clue in the log. Both are clamped, not
        // honoured, and the clamp is reported so the owner sees the difference.
        assertEquals(MonsterDamageBoost.MIN_MULTIPLIER,
                load(ReloadDiff.MONSTER_MULTIPLIER_KEY, 0.25).monsterDamageMultiplier());
        assertEquals(MonsterDamageBoost.MAX_MULTIPLIER,
                load(ReloadDiff.MONSTER_MULTIPLIER_KEY, 9999.0).monsterDamageMultiplier());
        // In range: passed through, so the clamp is not just pinning everything.
        assertEquals(2.25, load(ReloadDiff.MONSTER_MULTIPLIER_KEY, 2.25).monsterDamageMultiplier());
        // The shipped default.
        assertEquals(1.5, BetterEndConfig.DEFAULTS.monsterDamageMultiplier());
    }

    @Test
    void anOutOfRangeMultiplierIsReportedByName() {
        List<String> warnings = new ArrayList<>();
        BetterEndConfig.load(Map.<String, Object>of(ReloadDiff.MONSTER_MULTIPLIER_KEY, 0.5)::get, warnings::add);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains(ReloadDiff.MONSTER_MULTIPLIER_KEY), warnings.getFirst());
    }

    @Test
    void aMultiplierOfTheWrongTypeIsFatalLikeEveryOtherKey() {
        // Guessing a default for a typo'd value would silently pick a damage scale the owner never
        // asked for -- the same reasoning as every other key in this record.
        Map<String, Object> broken = Map.of(ReloadDiff.MONSTER_MULTIPLIER_KEY, "1.5x");

        assertThrows(ConfigException.class, () -> BetterEndConfig.load(broken::get, warning -> { }));
    }

    // --- the mod's derivations ----------------------------------------------------------------

    @Test
    void derivedValuesMatchTheModsArithmeticForTheThreeShippedLayers() {
        BetterEndConfig defaults = BetterEndConfig.DEFAULTS;

        // LayerOptions.java:36/52 - Mth.floor(1000 / distance).
        assertEquals(3L, defaults.bigIslands().centerDist());
        assertEquals(6L, defaults.mediumIslands().centerDist());
        assertEquals(16L, defaults.smallIslands().centerDist());

        // LayerOptions.java:34-35/50-51 - averageHeight -+ heightVariation, unclamped.
        // Against MAX_HEIGHT 128 that is y 60..80, 50..90 and 40..100: islands average y=70,
        // which is a different thing from the ONE island the mod pins at y=64
        // (IslandLayer.java:100-101, ported at IslandField.java:396).
        assertEquals(60f / 128, defaults.bigIslands().minY(), 1e-6f);
        assertEquals(80f / 128, defaults.bigIslands().maxY(), 1e-6f);
        assertEquals(50f / 128, defaults.mediumIslands().minY(), 1e-6f);
        assertEquals(90f / 128, defaults.mediumIslands().maxY(), 1e-6f);
        assertEquals(40f / 128, defaults.smallIslands().minY(), 1e-6f);
        assertEquals(100f / 128, defaults.smallIslands().maxY(), 1e-6f);

        // LayerOptions.java:64-66, and IslandField.java:333's hardcoded COVERAGE.
        assertEquals(0.9999f - 0.5f * 2, defaults.bigIslands().coverage());
    }

    @Test
    void coverageIsAThresholdSoHigherMeansMoreIslands() {
        // LayerOptions.java:64-66 - 0.9999 - v*2. The name is inverted from the meaning; a bad
        // implementation that "helpfully" forwards coverage unchanged fails right here.
        float wideOpen = new LayerSettings(300f, 200f, 0.5f, 0.1f, 1f).coverage();
        float shutTight = new LayerSettings(300f, 200f, 0.5f, 0.1f, 0f).coverage();
        assertEquals(-1.0001f, wideOpen, 1e-6f);
        assertEquals(0.9999f, shutTight, 1e-6f);
        assertTrue(wideOpen < shutTight, "a higher raw coverage must lower the island threshold");
    }

    // --- a broken file must never stop the server ---------------------------------------------

    @Test
    void anEmptyConfigYieldsTheDefaultsSilently() {
        List<String> warnings = new ArrayList<>();
        BetterEndConfig loaded = BetterEndConfig.load(key -> null, warnings::add);

        assertEquals(BetterEndConfig.DEFAULTS, loaded);
        assertEquals(List.of(), warnings, "an absent key is not a problem worth logging");
    }

    @Test
    void wrongTypesAreRejectedAndNameEveryKey() {
        // A present-but-unusable value must NOT silently become a default: that default would be a
        // different terrain field, and the world would be carved along a seam nothing repairs.
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("advancements.sample-interval-ticks", "every tick");
        broken.put("generator.layers.small.scale", Map.of("nested", "map"));
        broken.put("generator.layers.small.coverage", Double.NaN);
        broken.put("features.ender-veins-per-chunk", 2.5);

        ConfigException error = assertThrows(ConfigException.class,
                () -> BetterEndConfig.load(broken::get, warning -> { }));

        for (String key : List.of("advancements.sample-interval-ticks",
                "generator.layers.small.scale", "generator.layers.small.coverage",
                "features.ender-veins-per-chunk")) {
            assertTrue(error.getMessage().contains(key), key + " missing from: " + error.getMessage());
        }
    }

    @Test
    void aLookupThatThrowsIsNotSwallowed() {
        // A corrupt section must surface, not masquerade as "use the defaults for everything".
        assertThrows(IllegalStateException.class, () -> BetterEndConfig.load(key -> {
            throw new IllegalStateException("corrupt section");
        }, warning -> { }));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static LayerSettings layer(String key, float value) {
        return load("generator.layers.big." + key, value).bigIslands();
    }

    private static BetterEndConfig load(String key, Object value) {
        Map<String, Object> single = new LinkedHashMap<>();
        single.put(key, value);
        return BetterEndConfig.load(single::get, warning -> { });
    }

    private static Path configYml() {
        Path at = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && at != null; up++, at = at.getParent()) {
            Path candidate = at.resolve("plugin/src/main/resources/config.yml");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("plugin/src/main/resources/config.yml not found from "
                + Path.of("").toAbsolutePath());
    }

    /**
     * Just enough YAML for our own file: nested maps of scalars, {@code #} comments, no lists, no
     * quotes, no anchors. Deliberately not a YAML library -- the test classpath is JUnit only, and
     * a hand parser that chokes on anything fancy is a feature: config.yml must stay this plain.
     */
    private static Map<String, Object> parse(Path file) throws IOException {
        Map<String, Object> flat = new LinkedHashMap<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (String line : Files.readAllLines(file)) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            if (line.isBlank()) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.strip();
            int colon = trimmed.indexOf(':');
            assertTrue(colon > 0, "not a 'key: value' line: " + line);

            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                path.pop();
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            if (value.isEmpty()) {
                indents.push(indent);
                path.push(key);
                continue;
            }
            List<String> parts = new ArrayList<>(path.stream().toList());
            java.util.Collections.reverse(parts);
            parts.add(key);
            assertFalse(flat.containsKey(String.join(".", parts)), "duplicate key " + key);
            flat.put(String.join(".", parts), scalar(value));
        }
        return flat;
    }

    private static Object scalar(String value) {
        if (value.equals("true") || value.equals("false")) return Boolean.valueOf(value);
        if (value.contains(".")) return Double.valueOf(value);
        return Integer.valueOf(value);
    }
}
