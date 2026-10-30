package dev.mumu.betterend.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a reload is allowed to claim it changed.
 * <p>
 * WHY these matter: the failure this class exists to prevent is an operator changing a terrain value,
 * reading "reloaded", and only learning it never took effect at the next chunk border -- where the
 * answer is a seam nothing repairs. So the assertions are about honesty, not convenience: a changed
 * startup-only key must be named, a value the owner deleted must be named, and a key a newer build
 * merely backfilled must NOT be, because that would train the operator to ignore the warning.
 */
class ReloadDiffTest {
    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // --- keys a backfill added -----------------------------------------------------------------

    @Test
    void aBackfilledKeyIsReportedAsAdded() {
        Map<String, Object> before = values("config-version", 1);
        Map<String, Object> after = values("config-version", 2,
                ReloadDiff.MONSTER_MULTIPLIER_KEY, 1.5);

        assertEquals(List.of(ReloadDiff.MONSTER_MULTIPLIER_KEY), ReloadDiff.addedKeys(before, after));
    }

    @Test
    void anUnchangedFileAddsNothing() {
        Map<String, Object> same = values("generator.layers.big.distance", 300.0);

        assertEquals(List.of(), ReloadDiff.addedKeys(same, values("generator.layers.big.distance", 300.0)));
    }

    // --- keys that need a restart --------------------------------------------------------------

    @Test
    void aChangedTerrainValueNeedsARestart() {
        // The whole point of this test: this is the value that bakes a cliff into the world.
        Map<String, Object> before = values("generator.layers.big.distance", 300.0, "features.amber-veins-per-chunk", 60);
        Map<String, Object> after = values("generator.layers.big.distance", 500.0, "features.amber-veins-per-chunk", 60);

        assertEquals(List.of("generator.layers.big.distance"),
                ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
    }

    @Test
    void theHotKeyIsNeverReportedAsNeedingARestart() {
        // It is the one key a reload really applies; calling it "restart required" would be a lie in
        // the opposite direction and would make the honest warning above look unreliable.
        Map<String, Object> before = values(ReloadDiff.MONSTER_MULTIPLIER_KEY, 1.5);
        Map<String, Object> after = values(ReloadDiff.MONSTER_MULTIPLIER_KEY, 3.0);

        assertEquals(List.of(), ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
    }

    @Test
    void aBookkeepingVersionBumpIsNotAChange() {
        Map<String, Object> before = values("config-version", 1);
        Map<String, Object> after = values("config-version", 2);

        assertEquals(List.of(), ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
    }

    @Test
    void aBackfilledKeyIsNotReportedAsNeedingARestart() {
        // It was running on its default and the backfill wrote that same default: behaviour is
        // unchanged, so demanding a restart for it would be noise an operator learns to ignore.
        Map<String, Object> before = values("config-version", 1);
        Map<String, Object> after = values("config-version", 2, "features.amber-veins-per-chunk", 60);

        assertEquals(List.of(), ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
    }

    @Test
    void anObsoleteKeyLeftInAnOldFileIsNotReportedAtAll() {
        // The key no longer exists here, so neither "changed" nor "needs a restart" is true of it;
        // an older config.yml that still carries it must reload clean instead of nagging forever.
        Map<String, Object> before = values("craftengine.install-bundled-pack", true,
                "craftengine-resources.auto-completion", true,
                "generator.generate-central-island", true,
                "advancements.enabled", true);
        Map<String, Object> after = values("craftengine.install-bundled-pack", false,
                "craftengine-resources.auto-completion", false,
                "generator.generate-central-island", false,
                "advancements.enabled", false);

        assertEquals(List.of(), ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
        assertEquals(List.of(), ReloadDiff.addedKeys(values(), after));
    }

    @Test
    void aDeletedKeyIsReportedBecauseTheRunningValueWouldSurviveUntilRestart() {
        // Removed from the file: the snapshot the generator holds still has 300.0 while the next boot
        // would use the built-in default. Over-reporting costs one restart; silence costs a world.
        Map<String, Object> before = values("generator.layers.big.distance", 300.0);
        Map<String, Object> after = values("config-version", 2);

        assertEquals(List.of("generator.layers.big.distance"),
                ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
    }

    @Test
    void anIntegerAndAFloatOfTheSameValueAreTheSameValue() {
        // snakeyaml hands back Integer for `300` and Double for `300.0`; an edit that changed no
        // meaning must not produce a restart warning.
        Map<String, Object> before = values("generator.layers.big.distance", 300);
        Map<String, Object> after = values("generator.layers.big.distance", 300.0);

        assertEquals(List.of(), ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
        assertEquals(List.of(), ReloadDiff.addedKeys(before, after));
    }

    @Test
    void severalChangesComeBackSortedAndComplete() {
        Map<String, Object> before = values(
                "advancements.sample-interval-ticks", 20,
                "generator.layers.big.scale", 200.0,
                "features.amber-veins-per-chunk", 60);
        Map<String, Object> after = values(
                "advancements.sample-interval-ticks", 40,
                "generator.layers.big.scale", 250.0,
                "features.amber-veins-per-chunk", 60);

        assertEquals(List.of("advancements.sample-interval-ticks", "generator.layers.big.scale"),
                ReloadDiff.restartOnlyChanges(before, after, ReloadDiff.HOT_KEYS));
    }
}
