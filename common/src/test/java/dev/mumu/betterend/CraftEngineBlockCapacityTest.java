package dev.mumu.betterend;

import dev.mumu.betterend.biome.BiomeDatapack;
import net.momirealms.craftengine.core.plugin.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capacity guard's decision, tested without CraftEngine and without a server through
 * {@link CraftEngineBlockCapacity.CapacitySeam}. The two properties that matter most are that an
 * operator's larger value is never lowered, and that a write which does not take is reported as a
 * failure -- never as a silent, pretend success (a later CraftEngine would then size its arrays from
 * the old value and the shortage would surface only when a block is first needed).
 */
class CraftEngineBlockCapacityTest {
    /** CraftEngine 26.10 ships {@code block.serverside-blocks: 2000}; the too-small value this fixes. */
    private static final int CE_SHIPPED_DEFAULT = 2000;

    /**
     * Read before any test mutates the static, so "a fresh process refuses" is observable. No other test
     * class touches this field, and this class's {@code @BeforeAll} runs before its own tests, so the
     * captured value is the class's initialized (pre-pass) value.
     */
    private static String initialRefusal;

    @BeforeAll
    static void captureInitialState() throws Exception {
        initialRefusal = readRefusal(CraftEngineBlockCapacity.class);
    }

    @AfterEach
    void restoreFreshState() throws Exception {
        writeRefusal(CraftEngineBlockCapacity.class, initialRefusal);
    }

    @Test
    void aFreshProcessRefusesBeforeAnyCapacityPassHasRun() {
        assertNotNull(initialRefusal,
                "the guard starts non-null: enabling without a pass must refuse, not assume success");
    }

    @Test
    void raisesAnInsufficientCapacityToTheMinimumAndVerifiesTheReadBack() {
        FakeSeam seam = new FakeSeam().capacity(CE_SHIPPED_DEFAULT);
        List<Throwable> failures = new ArrayList<>();

        assertEquals(CraftEngineBlockCapacity.Result.EXPANDED,
                CraftEngineBlockCapacity.ensureCapacity(seam, failures::add));
        assertEquals(List.of(CraftEngineBlockCapacity.MINIMUM), seam.raised,
                "the guard must write the minimum exactly once");
        assertEquals(CraftEngineBlockCapacity.MINIMUM, seam.value, "the write must have taken");
        assertTrue(failures.isEmpty(), () -> "no failure was expected, got " + failures);
        assertNull(CraftEngineBlockCapacity.refusalReason(), "a verified expansion must not refuse");
    }

    @Test
    void neverLowersAnOperatorsLargerValue() {
        FakeSeam seam = new FakeSeam().capacity(10_000);

        assertEquals(CraftEngineBlockCapacity.Result.SUFFICIENT,
                CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }));
        assertEquals(0, seam.raiseCalls, "a sufficient capacity must not be rewritten");
        assertEquals(10_000, seam.value, "the operator's larger value must be left exactly as it was");
        assertNull(CraftEngineBlockCapacity.refusalReason());
    }

    @Test
    void treatsACapacityExactlyAtTheMinimumAsSufficient() {
        FakeSeam seam = new FakeSeam().capacity(CraftEngineBlockCapacity.MINIMUM);

        assertEquals(CraftEngineBlockCapacity.Result.SUFFICIENT,
                CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }));
        assertEquals(0, seam.raiseCalls);
        assertNull(CraftEngineBlockCapacity.refusalReason());
    }

    @Test
    void aSufficientCapacityIsIndependentOfWhetherTheManagerIsBuilt() {
        // A manager built with an adequate capacity is the desired end state of a previous pass.
        FakeSeam seam = new FakeSeam().managerInitialized(true).capacity(CraftEngineBlockCapacity.MINIMUM);

        assertEquals(CraftEngineBlockCapacity.Result.SUFFICIENT,
                CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }));
        assertEquals(0, seam.raiseCalls);
        assertNull(CraftEngineBlockCapacity.refusalReason());
    }

    @Test
    void raisesANonPositiveCapacity() {
        // CraftEngine clamps a negative configured value to 0 (Config loadFullSettings). Either way it is
        // below the minimum and must be raised, not accepted.
        for (int value : new int[]{0, -7}) {
            FakeSeam seam = new FakeSeam().capacity(value);
            assertEquals(CraftEngineBlockCapacity.Result.EXPANDED,
                    CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }), "value=" + value);
            assertEquals(List.of(CraftEngineBlockCapacity.MINIMUM), seam.raised, "value=" + value);
        }
    }

    @Test
    void refusesWhenTheWriteDoesNotTake() {
        // The write is a no-op: the read-back still shows the old value. This must be a FAILURE, never a
        // pretend success -- this is the check that catches a wrong field or a broken class identity.
        FakeSeam seam = new FakeSeam().capacity(CE_SHIPPED_DEFAULT).writeIsNoOp();

        assertEquals(CraftEngineBlockCapacity.Result.FAILED,
                CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }));
        assertNotNull(CraftEngineBlockCapacity.refusalReason(),
                "an unverified write must refuse, not report success");
        assertTrue(CraftEngineBlockCapacity.refusalReason().contains(String.valueOf(CraftEngineBlockCapacity.MINIMUM)));
    }

    @Test
    void doesNotTouchCraftEngineOnceItsManagerIsBuilt() {
        // A manager already sized too small cannot be resized; writing the setting now would be a fake
        // expansion. The guard must not write, and must refuse with an actionable reason (a full restart).
        FakeSeam seam = new FakeSeam().managerInitialized(true).capacity(CE_SHIPPED_DEFAULT);

        assertEquals(CraftEngineBlockCapacity.Result.MANAGER_ALREADY_INITIALIZED,
                CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }));
        assertEquals(0, seam.raiseCalls, "a built manager must not be written to");
        assertEquals(CE_SHIPPED_DEFAULT, seam.value, "and it must not shrink the value either");
        assertNotNull(CraftEngineBlockCapacity.refusalReason());
        assertTrue(CraftEngineBlockCapacity.refusalReason().contains("重启"),
                "the refusal must tell the operator to restart");
    }

    @Test
    void repeatedPassesNeverRewriteAnExpandedCapacity() {
        // The handler fires on every discovery pass (including /reload); the second one must be a no-op.
        FakeSeam seam = new FakeSeam().capacity(CE_SHIPPED_DEFAULT);

        assertEquals(CraftEngineBlockCapacity.Result.EXPANDED,
                CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }));
        assertEquals(CraftEngineBlockCapacity.Result.SUFFICIENT,
                CraftEngineBlockCapacity.ensureCapacity(seam, error -> { }));
        assertEquals(1, seam.raiseCalls, "the capacity must be written exactly once across passes");
        assertEquals(CraftEngineBlockCapacity.MINIMUM, seam.value);
        assertNull(CraftEngineBlockCapacity.refusalReason());
    }

    @Test
    void propagatesAReadFailureAsRefusal() {
        FakeSeam seam = new FakeSeam().capacity(CE_SHIPPED_DEFAULT).capacityThrows(new IllegalStateException("no config"));
        List<Throwable> failures = new ArrayList<>();

        assertEquals(CraftEngineBlockCapacity.Result.FAILED,
                CraftEngineBlockCapacity.ensureCapacity(seam, failures::add));
        assertEquals(List.of("no config"), failures.stream().map(Throwable::getMessage).toList(),
                "the failure must be handed to the caller for logging, not swallowed");
        assertNotNull(CraftEngineBlockCapacity.refusalReason());
    }

    @Test
    void propagatesAManagerCheckFailureAsRefusal() {
        FakeSeam seam = new FakeSeam().capacity(CE_SHIPPED_DEFAULT)
                .managerCheckThrows(new IllegalStateException("CraftEngine has not been initialized"));
        List<Throwable> failures = new ArrayList<>();

        assertEquals(CraftEngineBlockCapacity.Result.FAILED,
                CraftEngineBlockCapacity.ensureCapacity(seam, failures::add));
        assertEquals(1, failures.size());
        assertNotNull(CraftEngineBlockCapacity.refusalReason());
    }

    @Test
    void propagatesAWriteFailureAsRefusal() {
        FakeSeam seam = new FakeSeam().capacity(CE_SHIPPED_DEFAULT).writeThrows();
        List<Throwable> failures = new ArrayList<>();

        assertEquals(CraftEngineBlockCapacity.Result.FAILED,
                CraftEngineBlockCapacity.ensureCapacity(seam, failures::add));
        assertEquals(1, failures.size());
        assertNotNull(CraftEngineBlockCapacity.refusalReason());
    }

    @Test
    void exposesTheCapacityFailureThroughTheBiomeDatapackGuardEvenAfterAPassReset() throws Exception {
        // The main class consults BiomeDatapack.refusalReason(), so a capacity failure must surface there.
        // The regression this guards: BiomeDatapack's discovery handler resets ITS OWN private refusal to
        // the non-null "never discovered" value at the start of every pass, and clears it on success. A
        // folded-in capacity failure would be wiped out by that reset; keeping the two guards in separate
        // statics is what makes the capacity failure survive. This test simulates that reset directly.
        FakeSeam seam = new FakeSeam().managerInitialized(true).capacity(CE_SHIPPED_DEFAULT);
        CraftEngineBlockCapacity.ensureCapacity(seam, error -> { });
        String capacityReason = CraftEngineBlockCapacity.refusalReason();
        assertNotNull(capacityReason);

        // Plain case: the datapack guard is untouched (in a fresh JVM, its own "never discovered" state).
        assertTrue(BiomeDatapack.refusalReason().contains(capacityReason),
                () -> "the merged reason must carry the capacity failure, got: " + BiomeDatapack.refusalReason());

        // Regression: drive BiomeDatapack's private refusal through both states a discovery pass leaves it
        // in -- reset to the non-null "unknown" value, and cleared to null on success -- and assert the
        // capacity failure is still exposed in BOTH. Restore it in finally so no other test is polluted.
        String realDatapackRefusal = readRefusal(BiomeDatapack.class);
        try {
            writeRefusal(BiomeDatapack.class, "重置：本轮数据包发现尚未完成");
            assertTrue(BiomeDatapack.refusalReason().contains(capacityReason),
                    "a per-pass reset of the datapack guard must not hide a capacity failure");

            writeRefusal(BiomeDatapack.class, null);
            assertTrue(BiomeDatapack.refusalReason().contains(capacityReason),
                    "a successful datapack pass must never hide a capacity failure");
        } finally {
            writeRefusal(BiomeDatapack.class, realDatapackRefusal);
        }
    }

    /**
     * Pins the field production writes against the REAL CraftEngine class -- through the SAME accessor
     * production uses ({@code ReflectionSeam.handle()}), so this asserts the production strategy rather
     * than a parallel one that could drift from it. A mock-based seam alone would pass even if the field
     * name or kind were wrong. A class literal loads the shipped class without initializing CraftEngine
     * and needs no server.
     * <p>
     * The accessor is a {@code VarHandle}, not {@code Config.class.getDeclaredField(...)}: the latter
     * resolves EVERY declared field's type, including one of relocated Adventure that the shipped
     * CraftEngine jar does NOT contain, so it throws {@code NoClassDefFoundError} here -- the very full
     * resolution the bootstrap-phase production path must avoid.
     * <ul>
     *   <li>a successful lookup means a field of that exact name with type {@code int} exists;</li>
     *   <li>one coordinate (the Config receiver) confirms a non-static field;</li>
     *   <li>a supported {@code SET} access mode confirms the field is non-final.</li>
     * </ul>
     * The read-back half is pinned through the static getter production reads.
     */
    @Test
    void craftEnginesCapacityFieldIsARealNonStaticNonFinalInt() throws Throwable {
        // The production accessor itself, so the test cannot pass against a different lookup than the one
        // the guard runs at bootstrap.
        VarHandle field = CraftEngineBlockCapacity.ReflectionSeam.handle();
        assertEquals(int.class, field.varType(), "the capacity field must be an int");
        assertEquals(1, field.coordinateTypes().size(),
                "one coordinate (the Config receiver) confirms a non-static field");
        assertTrue(field.isAccessModeSupported(VarHandle.AccessMode.SET),
                "the field must be non-final: production assigns it after construction");

        // The read-back half: the static getter production verifies the write through must exist as a static
        // int method (findStatic throws if the name, staticness or return type does not match).
        MethodHandle getter = MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup())
                .findStatic(Config.class, "serverSideBlocks", MethodType.methodType(int.class));
        assertNotNull(getter, "serverSideBlocks() must be a static getter returning int");
    }

    @Test
    void theMinimumIsAboveTheShippedDefaultAndWithinCraftEnginesCeiling() {
        assertTrue(CraftEngineBlockCapacity.MINIMUM > CE_SHIPPED_DEFAULT,
                "the minimum must exceed the default that this plugin exhausts");
        assertTrue(CraftEngineBlockCapacity.MINIMUM <= CraftEngineBlockCapacity.CEILING,
                "a value above CraftEngine's ceiling would be clamped, not applied");
    }

    @Test
    void thePriorityRunsBeforeCraftEnginesDefaultZero() {
        assertTrue(CraftEngineBlockCapacity.PRIORITY < 0,
                "Paper runs lower priorities first; CraftEngine's own handler is priority 0");
    }

    // --- reflection helpers: each guard's static is per-class, so tests reset them to stay order-independent ---

    private static String readRefusal(Class<?> owner) throws Exception {
        return (String) refusalField(owner).get(null);
    }

    private static void writeRefusal(Class<?> owner, String value) throws Exception {
        refusalField(owner).set(null, value);
    }

    private static Field refusalField(Class<?> owner) throws Exception {
        Field field = owner.getDeclaredField("refusal");
        field.setAccessible(true);
        return field;
    }

    /** A {@link CraftEngineBlockCapacity.CapacitySeam} whose behaviour each test sets exactly. */
    private static final class FakeSeam implements CraftEngineBlockCapacity.CapacitySeam {
        private boolean managerInitialized;
        private int value;
        private boolean writeIsNoOp;
        private boolean writeThrows;
        private RuntimeException capacityError;
        private RuntimeException managerError;
        private int raiseCalls;
        private List<Integer> raised = List.of();

        FakeSeam managerInitialized(boolean built) {
            this.managerInitialized = built;
            return this;
        }

        FakeSeam capacity(int value) {
            this.value = value;
            return this;
        }

        FakeSeam writeIsNoOp() {
            this.writeIsNoOp = true;
            return this;
        }

        FakeSeam writeThrows() {
            this.writeThrows = true;
            return this;
        }

        FakeSeam capacityThrows(RuntimeException error) {
            this.capacityError = error;
            return this;
        }

        FakeSeam managerCheckThrows(RuntimeException error) {
            this.managerError = error;
            return this;
        }

        @Override
        public boolean managerInitialized() {
            if (managerError != null) {
                throw managerError;
            }
            return managerInitialized;
        }

        @Override
        public int capacity() {
            if (capacityError != null) {
                throw capacityError;
            }
            return value;
        }

        @Override
        public void raise(int capacity) {
            raiseCalls++;
            if (raised.isEmpty()) {
                raised = new ArrayList<>();
            }
            raised.add(capacity);
            if (writeThrows) {
                throw new IllegalStateException("write rejected");
            }
            if (!writeIsNoOp) {
                value = Math.max(value, capacity);
            }
            // else: a no-op write, so the read-back below still sees the old value -> the guard must fail.
        }
    }
}
