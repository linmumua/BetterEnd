package dev.mumu.betterend;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two CraftEngine decisions this bridge adds, exercised with no CraftEngine and no server:
 * <ul>
 *   <li>switching CraftEngine off its deferred-resource-registration path, by editing only its
 *       in-memory setting -- the caller must be told honestly when that did not work; and</li>
 *   <li>deciding whether the pack's critical content is actually registered -- the difference
 *       between "CraftEngine is enabled" and "the world can be generated".</li>
 * </ul>
 * Both are pure functions over a small injected seam, so the WHY (never report success you did not
 * observe) is checkable without booting anything.
 */
class CraftEngineBridgeTest {

    /** A {@link CraftEngineBridge.DelaySeam} backed by a plain boolean, so the flip is observable. */
    private static final class FakeSeam implements CraftEngineBridge.DelaySeam {
        private boolean delayed;
        private int applyCalls;
        private final boolean applyTakesEffect;
        private final RuntimeException throwOnApply;
        private final RuntimeException throwOnRead;

        FakeSeam(boolean delayed) {
            this(delayed, true, null, null);
        }

        FakeSeam(boolean delayed, boolean applyTakesEffect, RuntimeException throwOnApply, RuntimeException throwOnRead) {
            this.delayed = delayed;
            this.applyTakesEffect = applyTakesEffect;
            this.throwOnApply = throwOnApply;
            this.throwOnRead = throwOnRead;
        }

        @Override
        public boolean delayConfigurationLoad() {
            if (throwOnRead != null) {
                throw throwOnRead;
            }
            return delayed;
        }

        @Override
        public void applyImmediate() {
            applyCalls++;
            if (throwOnApply != null) {
                throw throwOnApply;
            }
            if (applyTakesEffect) {
                delayed = false;
            }
        }
    }

    private static List<Throwable> failures() {
        return new ArrayList<>();
    }

    @Test
    void aSettingAlreadyOffIsLeftAlone() {
        FakeSeam seam = new FakeSeam(false);
        List<Throwable> failures = failures();

        CraftEngineBridge.ImmediateLoad outcome = CraftEngineBridge.forceImmediateLoad(seam, failures::add);

        assertEquals(CraftEngineBridge.ImmediateLoad.ALREADY_IMMEDIATE, outcome);
        assertEquals(0, seam.applyCalls, "an already-immediate CraftEngine must not be touched");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aDefaultedSettingIsFlippedAndReadBackOff() {
        FakeSeam seam = new FakeSeam(true);
        List<Throwable> failures = failures();

        CraftEngineBridge.ImmediateLoad outcome = CraftEngineBridge.forceImmediateLoad(seam, failures::add);

        assertEquals(CraftEngineBridge.ImmediateLoad.SWITCHED, outcome);
        assertEquals(1, seam.applyCalls);
        assertFalse(seam.delayed, "the seam must observe the flag actually off afterwards");
        assertTrue(failures.isEmpty());
    }

    @Test
    void aFlipThatDidNotTakeEffectIsReportedAsFailureNotSuccess() {
        // The setter ran but the value is unchanged -- the dangerous case: silently "succeeding" here
        // would let the plugin later claim registration it does not have.
        FakeSeam seam = new FakeSeam(true, false, null, null);
        List<Throwable> failures = failures();

        CraftEngineBridge.ImmediateLoad outcome = CraftEngineBridge.forceImmediateLoad(seam, failures::add);

        assertEquals(CraftEngineBridge.ImmediateLoad.FAILED, outcome);
        assertTrue(failures.isEmpty(), "a silent no-op flip is not an exception");
    }

    @Test
    void anApplyThatThrowsIsAFailureAndIsSurfaced() {
        RuntimeException boom = new IllegalStateException("settings locked");
        FakeSeam seam = new FakeSeam(true, true, boom, null);
        List<Throwable> failures = failures();

        CraftEngineBridge.ImmediateLoad outcome = CraftEngineBridge.forceImmediateLoad(seam, failures::add);

        assertEquals(CraftEngineBridge.ImmediateLoad.FAILED, outcome);
        assertEquals(1, failures.size());
        assertSame(boom, failures.get(0));
    }

    @Test
    void aReadThatThrowsIsAFailureAndIsSurfaced() {
        RuntimeException boom = new IllegalStateException("CraftEngine not initialised");
        FakeSeam seam = new FakeSeam(true, true, null, boom);
        List<Throwable> failures = failures();

        CraftEngineBridge.ImmediateLoad outcome = CraftEngineBridge.forceImmediateLoad(seam, failures::add);

        assertEquals(CraftEngineBridge.ImmediateLoad.FAILED, outcome);
        assertEquals(1, failures.size());
        assertSame(boom, failures.get(0));
    }

    // --- readiness ---

    /** A {@link CraftEngineBridge.ContentRegistry} where only the given ids are registered. */
    private static CraftEngineBridge.ContentRegistry registryOf(Set<String> blocks, Set<String> items) {
        return new CraftEngineBridge.ContentRegistry() {
            @Override
            public boolean blockRegistered(String id) {
                return blocks.contains(id);
            }

            @Override
            public boolean itemRegistered(String id) {
                return items.contains(id);
            }
        };
    }

    private static final List<String> CRITICAL_BLOCKS = List.of(
            "betterend:flavolite", "betterend:thallasium_ore", "betterend:ender_ore", "betterend:endstone_dust");

    @Test
    void aFullyRegisteredPackIsReady() {
        CraftEngineBridge.ContentRegistry registry =
                registryOf(new HashSet<>(CRITICAL_BLOCKS), Set.of("betterend:endstone_dust"));

        assertTrue(CraftEngineBridge.missingCriticalContent(registry).isEmpty());
    }

    @Test
    void aMissingCriticalBlockIsReported() {
        // Everything but ender_ore: the world may not open, so readiness must fail with that id named.
        Set<String> blocks = new HashSet<>(CRITICAL_BLOCKS);
        blocks.remove("betterend:ender_ore");
        CraftEngineBridge.ContentRegistry registry = registryOf(blocks, Set.of("betterend:endstone_dust"));

        assertEquals(List.of("block betterend:ender_ore"), CraftEngineBridge.missingCriticalContent(registry));
    }

    @Test
    void aMissingCriticalItemIsReportedEvenWhenItsBlockIsPresent() {
        // An enabled CraftEngine whose pack parsed only the block is not registered -- the item must
        // resolve too, so readiness stays false and the world is refused rather than baked vanilla.
        CraftEngineBridge.ContentRegistry registry = registryOf(new HashSet<>(CRITICAL_BLOCKS), Set.of());

        assertEquals(List.of("item betterend:endstone_dust"), CraftEngineBridge.missingCriticalContent(registry));
    }

    @Test
    void anEmptyCraftEngineReportsEverythingMissing() {
        CraftEngineBridge.ContentRegistry registry = registryOf(Set.of(), Set.of());

        assertEquals(List.of(
                "block betterend:flavolite",
                "block betterend:thallasium_ore",
                "block betterend:ender_ore",
                "block betterend:endstone_dust",
                "item betterend:endstone_dust"), CraftEngineBridge.missingCriticalContent(registry));
    }

    // --- the report a reload shows to an operator ------------------------------------------------

    /**
     * The upgrade counts must survive into the report. They are how an operator learns that a pack
     * which added nothing still changed: three bundled defaults replaced, one file kept because they
     * edited it. Dropping them makes a corrected default that DID land look like nothing happened.
     */
    @Test
    void aReportCarriesTheUpgradeCountsAlongsideTheCompletionCounts() {
        CraftEngineBridge.PackReport report = CraftEngineBridge.packReport("betterend",
                new PackInstaller.Result(false, 4, 2, 0), new BundledPackUpgrades.Result(3, 1, 0));

        assertEquals("betterend", report.pack());
        assertFalse(report.firstInstall());
        assertEquals(4, report.released());
        assertEquals(2, report.skipped());
        assertEquals(0, report.failed());
        assertEquals(3, report.upgraded(), "the corrected defaults that replaced an old one are reported");
        assertEquals(1, report.kept(), "the file judged to be the owner's edit is reported");
    }

    @Test
    void aPackWithNoUpgradePassReportsZeroRatherThanBorrowingAnotherPacksCounts() {
        // The DnT End Castle pack is never run through the betterend whitelist, so its report must not
        // claim upgrade activity it never had.
        CraftEngineBridge.PackReport report = CraftEngineBridge.packReport(
                CraftEngineBridge.DNT_END_CASTLE_PACK, new PackInstaller.Result(true, 9, 0, 0), null);

        assertTrue(report.firstInstall());
        assertEquals(9, report.released());
        assertEquals(0, report.upgraded());
        assertEquals(0, report.kept());
    }

    @Test
    void theFiveArgumentShapeStillReportsNoUpgradeActivity() {
        // A caller written against the earlier record must keep compiling and get an honest zero.
        CraftEngineBridge.PackReport legacy = new CraftEngineBridge.PackReport("betterend", false, 1, 2, 0);

        assertEquals(1, legacy.released());
        assertEquals(2, legacy.skipped());
        assertEquals(0, legacy.upgraded());
        assertEquals(0, legacy.kept());
    }
}
