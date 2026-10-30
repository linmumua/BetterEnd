package dev.mumu.betterend;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.Config;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;

/**
 * Raises CraftEngine's in-memory server-side block capacity before CraftEngine fixes it, so the block
 * manager it is about to build can hold every block BetterEnd registers.
 *
 * <h2>Why this exists</h2>
 * CraftEngine sizes a set of arrays once, at {@code new BukkitBlockManager(this)} inside
 * {@code BukkitCraftEngine.injectRegistries()}, from {@code Config.serverSideBlocks()} (CraftEngine
 * 26.10: {@code AbstractBlockManager}'s constructor allocates {@code new ...[customBlockCount]} from
 * that value, and {@code registerServerSideBlocks} sizes the same). It ships
 * {@code block.serverside-blocks: 2000}, which this plugin's block set exhausts: the allocation then
 * throws deep inside CraftEngine's configuration phase with no hint that the capacity is the cause.
 * CraftEngine's own handler for {@code DATAPACK_DISCOVERY} is registered with the default priority
 * (0, lower runs first), and that handler is what calls {@code injectRegistries()}. This class
 * registers a handler on the same event with a <em>negative</em> priority, so it runs first, while
 * {@code BukkitCraftEngine.blockManager()} is still {@code null} and before any array has been sized.
 *
 * <h2>Why the capacity is written reflectively</h2>
 * There is no CraftEngine API for this. {@code Config.settings()} (the in-memory {@code YamlDocument})
 * is public and has a {@code set(Route, Object)} writer, and {@code Config.loadFullSettings()} is
 * public -- but the assignment
 * {@code this.block$serverside_blocks = Math.min(config.getInt("block.serverside-blocks", 2000), 100_000)}
 * sits inside {@code if (this.firstTime)} (Config.java: block section, the {@code firstTime} block),
 * and {@code firstTime} is set to {@code false} at the end of the same method. CraftEngine's
 * bootstrapper already called {@code loadFullSettings()} once ({@code setUpConfigAndLocale}), so a
 * later {@code settings().set(...) + loadFullSettings()} would NOT re-read the key: it would be a
 * silent no-op that looks like a fix. There is likewise no public setter -- only the
 * {@code static int serverSideBlocks()} getter. The only channel left, and the smallest one, is a
 * bounded reflective write of that single {@code int} field, verified by reading it back through the
 * getter. The field name is checked to exist, and a read-back that does not reflect the write is
 * reported as a failure, never as success.
 * <p>
 * The access uses a {@code VarHandle} obtained by name and type, not
 * {@code Field.getDeclaredField(...)}: {@code getDeclaredField} resolves EVERY declared field's type,
 * and {@code Config} has a field of type
 * {@code net.momirealms.craftengine.libraries.adventure.text.Component} -- relocated Adventure that the
 * shipped CraftEngine jar does NOT contain (CraftEngine supplies it at runtime). Resolving it during the
 * bootstrap phase risks a {@code NoClassDefFoundError} for a class this plugin never needs; resolving
 * only the one named field avoids it entirely. See {@link ReflectionSeam#handle()}.
 *
 * <h2>What it touches</h2>
 * Nothing on disk: not CraftEngine's {@code config.yml}, not any other plugin, not a cache. It changes
 * one in-memory field. It never lowers the value -- an operator's larger setting is left alone -- so a
 * {@code /reload} (where CraftEngine's own {@code config.load()} re-runs against the same
 * {@code Config} instance, whose {@code firstTime} is already {@code false}) cannot shrink it.
 *
 * <h2>Failure is loud, not silent</h2>
 * {@link #refusalReason()} carries the outcome across the bootstrap/enable boundary (the same
 * classloader, see {@link dev.mumu.betterend.biome.BiomeDatapack}); a non-null value makes the main
 * class refuse to enable with an actionable reason, instead of letting the shortage surface only when
 * a block is first needed.
 */
public final class CraftEngineBlockCapacity {
    /**
     * The capacity BetterEnd needs allocated. BetterEnd registers on the order of a few thousand
     * server-side block states; 8192 leaves headroom while staying far below CraftEngine's own ceiling.
     */
    public static final int MINIMUM = 8192;

    /**
     * CraftEngine's own ceiling: {@code loadFullSettings} reads
     * {@code Math.min(config.getInt("block.serverside-blocks", 2000), 100_000)}, so a value above this
     * would be clamped on the next full settings load. {@link #MINIMUM} must stay at or below it.
     */
    static final int CEILING = 100_000;

    /**
     * Runs before CraftEngine's own {@code DATAPACK_DISCOVERY} handler. Paper orders handlers by
     * ascending priority (its {@code Comparator} compares priority, equal is 0, and
     * {@code monitor()} sorts last), and CraftEngine registers its handler with the default priority
     * of 0 -- so anything negative runs first.
     */
    public static final int PRIORITY = -100;

    /** The single CraftEngine field that sizes the block manager's arrays. */
    static final String CAPACITY_FIELD = "block$serverside_blocks";

    /**
     * The state before any pass has run, and the state a pass that dies mid-way leaves behind. Also the
     * state when CraftEngine is absent, so an operator sees a reason rather than a silent skip.
     */
    private static final String NEVER_RAN =
            "BetterEnd 的运行期容量守护从未完成：无法确认 CraftEngine 的 block.serverside-blocks 容量足以容纳"
                    + " BetterEnd 的方块，拒绝启用。请用一次完整的服务器启动加载本插件，而不是热加载或仅执行 /reload。";

    /**
     * The manager already exists at a size too small to change. Reported when the guard reaches the
     * manager only after it was built (for example a late /reload, or a manager another handler built
     * first); the only fix is a full restart with a larger configured capacity.
     */
    static String managerTooSmall() {
        return "CraftEngine 的方块管理器已经用不足的容量初始化，无法在不重启的情况下扩容：BetterEnd 需要"
                + " block.serverside-blocks ≥ " + MINIMUM + "。请在 plugins/CraftEngine/config.yml 把 "
                + "block.serverside-blocks 设为 " + MINIMUM + "（或更大），然后完整重启服务器。";
    }

    /** The write or the read-back failed; the operator must set the value in CraftEngine's file instead. */
    static String cannotRaise() {
        return "BetterEnd 无法把 CraftEngine 的 block.serverside-blocks 提升到 " + MINIMUM + "（原因见上方日志）。"
                + "请在 plugins/CraftEngine/config.yml 手动把 block.serverside-blocks 设为 " + MINIMUM
                + "（或更大），然后完整重启服务器。";
    }

    /**
     * {@code null} once a pass verified the capacity is enough; otherwise the operator-facing reason to
     * refuse enabling. Volatile: written on the worldgen thread, read on the main thread. Starts
     * non-null so a plugin that reaches enable without a pass (or whose pass died) refuses rather than
     * pretends.
     */
    private static volatile String refusal = NEVER_RAN;

    private CraftEngineBlockCapacity() {
    }

    /**
     * Whether the plugin may enable, from this guard's point of view. The main class refuses on a
     * non-null answer (see {@code BiomeDatapack.refusalReason()}, which merges this with the datapack
     * guard's own reason).
     */
    public static @Nullable String refusalReason() {
        return refusal;
    }

    /**
     * The handler body, wired from {@code BiomeDatapack.bootstrap} with {@link #PRIORITY}. Reads and,
     * if needed, raises CraftEngine's capacity through the real CraftEngine seam.
     */
    public static void ensureCapacity(ComponentLogger logger) {
        ensureCapacity(new ReflectionSeam(),
                error -> logger.warn("BetterEnd：无法调整 CraftEngine 的内存方块容量，详见下方异常。", error));
    }

    /**
     * The decision behind {@link #ensureCapacity(ComponentLogger)}, with no CraftEngine dependency, so
     * it is unit-tested without a running server. Idempotent and non-shrinking: a pass on an
     * already-sufficient capacity changes nothing, and a capacity already large enough is never
     * rewritten.
     *
     * @return the outcome, also recorded in {@link #refusalReason()}
     */
    static Result ensureCapacity(CapacitySeam seam, Consumer<Throwable> onFailure) {
        // Guard first: whatever happens below, a pass that throws leaves the "unknown" state behind,
        // never a stale success from an earlier pass.
        refusal = NEVER_RAN;
        Result result = decide(seam, onFailure);
        refusal = switch (result) {
            case SUFFICIENT, EXPANDED -> null;
            case MANAGER_ALREADY_INITIALIZED -> managerTooSmall();
            case FAILED -> cannotRaise();
        };
        return result;
    }

    /** The pure decision: read the capacity, raise it only when the manager is not yet built. */
    private static Result decide(CapacitySeam seam, Consumer<Throwable> onFailure) {
        try {
            boolean managerBuilt = seam.managerInitialized();
            int current = seam.capacity();
            if (current >= MINIMUM) {
                // Nothing to do, and never shrink: an operator's larger value stays as it is.
                return Result.SUFFICIENT;
            }
            if (managerBuilt) {
                // The arrays are already fixed at the too-small size; raising the setting now is a no-op.
                return Result.MANAGER_ALREADY_INITIALIZED;
            }
            seam.raise(MINIMUM);
            // Read back through CraftEngine's own getter: a write that did not take must not be reported
            // as success. This is what catches a wrong field, a broken class identity, or a refused write.
            return seam.capacity() >= MINIMUM ? Result.EXPANDED : Result.FAILED;
        } catch (RuntimeException | LinkageError error) {
            onFailure.accept(error);
            return Result.FAILED;
        }
    }

    /** What {@link #ensureCapacity(CapacitySeam, Consumer)} decided. */
    enum Result {
        /** Already at or above {@link #MINIMUM}: nothing written. */
        SUFFICIENT,
        /** Was below {@link #MINIMUM}, raised, and read back at or above it. */
        EXPANDED,
        /** Below {@link #MINIMUM} but the manager is already built, so it cannot be resized. */
        MANAGER_ALREADY_INITIALIZED,
        /** The read, the write, or the read-back failed. */
        FAILED
    }

    /**
     * The three CraftEngine operations the guard needs, as a seam so the decision is unit-tested
     * without CraftEngine.
     */
    interface CapacitySeam {
        /** Whether CraftEngine has already built its block manager (nothing can resize it afterwards). */
        boolean managerInitialized();

        /** The capacity CraftEngine would size a manager with right now. */
        int capacity();

        /** Raise the in-memory capacity. Throws (unchecked) if the write cannot be made to take. */
        void raise(int capacity);
    }

    /** The real CraftEngine seam. */
    static final class ReflectionSeam implements CapacitySeam {
        @Override
        public boolean managerInitialized() {
            // CraftEngine.instance() throws IllegalStateException (it does not return null) when
            // CraftEngine is not up; the caller catches that as a failure.
            return CraftEngine.instance().blockManager() != null;
        }

        @Override
        public int capacity() {
            return Config.serverSideBlocks();
        }

        @Override
        public void raise(int capacity) {
            // Any failure here (a wrong field kind, CraftEngine absent, a refused write) propagates to the
            // decision, which records a refusal rather than reporting success.
            handle().set(Config.instance(), capacity);
        }

        /**
         * The VarHandle for CraftEngine's capacity field, obtained by exact name and type.
         * <p>
         * {@code MethodHandles.Lookup.findVarHandle} resolves only this one named field, whereas
         * {@code Config.class.getDeclaredField(...)} resolves every declared field's type -- including a
         * field of type {@code net.momirealms.craftengine.libraries.adventure.text.Component}, relocated
         * Adventure that the shipped CraftEngine jar does NOT contain (CraftEngine supplies it at
         * runtime). {@code getDeclaredField} therefore throws {@code NoClassDefFoundError} in a JVM
         * without CraftEngine's runtime libraries, which a bootstrap-phase caller must not risk.
         * <p>
         * The lookup also pins the contract production depends on: it succeeds only if a field of that
         * exact name with type {@code int} exists; {@code findVarHandle} is for NON-static fields (a
         * static field needs {@code findStaticVarHandle}); and the returned handle's {@code SET} access
         * mode is supported only for a non-final field, which production needs since it assigns the field
         * after construction.
         */
        static VarHandle handle() {
            try {
                return MethodHandles.privateLookupIn(Config.class, MethodHandles.lookup())
                        .findVarHandle(Config.class, CAPACITY_FIELD, int.class);
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalStateException(
                        "无法获取 CraftEngine 的 " + CAPACITY_FIELD + " 字段句柄，拒绝假扩容。", error);
            }
        }
    }
}
