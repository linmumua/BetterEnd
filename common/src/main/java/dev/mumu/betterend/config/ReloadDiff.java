package dev.mumu.betterend.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a reload changes, computed from two flattened config snapshots -- no Bukkit, no I/O, so the
 * interesting half of {@code /betterend reload} is unit-testable.
 *
 * <p><b>Why this exists at all.</b> Most of {@code config.yml} is read once, at plugin load, into the
 * snapshot the world's generator was built from: the {@code generator:} layers feed a density field
 * that is memoised per world seed for the life of the world, the {@code features:} counts are baked
 * into the populators, and the biomes are frozen when the datapack registers. Re-reading those and
 * reporting "reloaded" would be a lie an operator only discovers as a cliff along a chunk border --
 * so the reload must say, by key, "this one changed and needs a restart". That list is what
 * {@link #restartOnlyChanges} produces.
 *
 * <p>Only the keys in {@link #HOT_KEYS} genuinely take effect on the running server: the damage
 * factor, which is consulted per hit. Everything else is startup-only. {@code config-version} is
 * bookkeeping and never reported, and the keys in {@link #OBSOLETE_KEYS} no longer exist at all --
 * they are ignored so an older config.yml that still carries them is not reported as unknown.
 */
public final class ReloadDiff {
    /**
     * The monster-damage factor, read through a supplier on every hit rather than baked into the
     * generator.
     */
    public static final String MONSTER_MULTIPLIER_KEY = "end-damage.monster-multiplier";

    /**
     * Keys that take effect without a restart -- only the damage factor, read again on every hit.
     * Everything else is startup-only.
     */
    public static final Set<String> HOT_KEYS = Set.of(MONSTER_MULTIPLIER_KEY);

    /**
     * Keys this build no longer reads at all. An older config.yml may still carry them; they are
     * ignored rather than reported as "changed and needs a restart", because nothing reads them any
     * more, so no restart could make them take effect.
     */
    public static final Set<String> OBSOLETE_KEYS = Set.of(
            "craftengine.install-bundled-pack",
            "craftengine-resources.auto-completion",
            "generator.generate-central-island",
            "advancements.enabled");

    private ReloadDiff() {
    }

    /**
     * Keys {@code after} has and {@code before} did not: what a backfill just added to the file.
     *
     * <p>These do not imply a restart. A key that was absent ran on its built-in default, and a
     * backfill writes that same default, so the running behaviour is unchanged by definition.
     *
     * @param before the values the running server is using
     * @param after  the values just read from disk
     * @return sorted dotted keys, empty when the file already had everything
     */
    public static List<String> addedKeys(Map<String, Object> before, Map<String, Object> after) {
        Set<String> added = new TreeSet<>(after.keySet());
        added.removeAll(before.keySet());
        added.removeAll(OBSOLETE_KEYS);
        return List.copyOf(added);
    }

    /**
     * Keys that are startup-only AND whose effective value differs between the two snapshots.
     *
     * <p>Errors every way it can, and deliberately: a key the owner changed is reported, and so is a
     * key they <em>deleted</em> -- the running snapshot still holds the old value while the new file
     * no longer supplies it, so unless that value happened to equal the default (unknowable here,
     * and cheap to over-report) the server would behave differently after a restart. Reporting a
     * needless restart costs one restart; staying silent about a terrain change costs a world.
     *
     * <p>{@code 300} and {@code 300.0} are the same value: snakeyaml hands back an Integer for the
     * first and a Double for the second, and a bare number edit that changed no meaning must not
     * produce a restart warning.
     *
     * @param before  the values the running server is using
     * @param after   the values just read from disk
     * @param hotKeys keys that apply live; excluded, as is {@code config-version}
     * @return sorted dotted keys that need a full restart to take effect
     */
    public static List<String> restartOnlyChanges(Map<String, Object> before, Map<String, Object> after,
                                                  Collection<String> hotKeys) {
        Set<String> changed = new TreeSet<>();
        Set<String> union = new TreeSet<>(before.keySet());
        union.addAll(after.keySet());
        for (String key : union) {
            if (ConfigStore.VERSION_KEY.equals(key) || hotKeys.contains(key) || OBSOLETE_KEYS.contains(key)) {
                continue;
            }
            if (!after.containsKey(key)) {
                // Deleted by the owner: the running snapshot still holds the old value, the new file
                // no longer supplies one, so the next start would use the built-in default. Report it.
                changed.add(key);
                continue;
            }
            if (!before.containsKey(key)) {
                // Backfilled by ConfigStore with the bundled default, which is what an absent key was
                // already running on -- see addedKeys(). Reporting every newly added key as "needs a
                // restart" is exactly the noise that teaches an operator to ignore the warning.
                continue;
            }
            if (!sameValue(before.get(key), after.get(key))) {
                changed.add(key);
            }
        }
        return List.copyOf(changed);
    }

    /** Numbers compare by value ({@code 300} vs {@code 300.0}); everything else by equals. */
    private static boolean sameValue(Object before, Object after) {
        if (before instanceof Number left && after instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        return Objects.equals(before, after);
    }
}
