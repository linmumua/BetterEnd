package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One {@code /betterend reload}, from the command thread to the reply.
 *
 * <p><b>Why this is a class, and why it holds the seams.</b> The two properties that decide whether a
 * reload is safe cannot be checked by looking at the plugin: whether two reloads can overlap, and in
 * which order the results are published. Both are decided here, so both are unit-testable without a
 * server, a CraftEngine install or a scheduler -- the file reads, the pack step, the readiness probe
 * and the off-thread hand-off arrive as the interfaces below, and the production wiring in
 * {@code BetterEndPlugin} passes the real ones.
 *
 * <p><b>Single flight.</b> A reload reads and rewrites two YAML files and may write a whole pack
 * folder. Two at once would interleave those writes and could publish a snapshot assembled from half
 * of each file, so a second request while one is running is refused and told so. The gate is a CAS
 * that is released in a {@code finally} spanning the asynchronous body, and also on the two paths
 * where that body never starts -- scheduling itself throwing, and the plugin disabling mid-flight --
 * because a gate that could stay set would answer "already running" to every later reload forever.
 *
 * <p><b>Publication order.</b> Config and messages are read and validated BEFORE anything is
 * published; only then is the immutable {@link Live} triple (messages, damage multiplier, last read
 * values) swapped in with one volatile write, so a hit processed on a region thread sees either the
 * old triple or the new one and never a mixture. The pack step deliberately runs AFTER that
 * publication: a pack failure must not roll back a config change that had nothing to do with it, and
 * the report says what really happened rather than claiming a transaction that never existed.
 *
 * <p><b>Startup values are never replaced.</b> They are what the running generator was built from, so
 * they stay the baseline the restart-required list is computed against; a reload only ever updates
 * {@link Live#lastReadValues()} (what the next reload compares to report what it backfilled) and the
 * one value that is genuinely live.
 *
 * <p>Bukkit-free on purpose: everything platform-specific is injected, so the tests exercise this
 * class rather than a stand-in for it.
 */
public final class ReloadCoordinator {
    /**
     * The runtime values a successful reload may replace, as one immutable triple so they are read
     * together or not at all.
     *
     * @param messages             the text in force, replaced only when messages.yml parsed
     * @param monsterMultiplier    the one config value a reload applies to the live server
     * @param lastReadValues       the flattened values this reload read, i.e. the baseline the next
     *                             reload compares against when reporting what it backfilled
     */
    public record Live(Messages messages, double monsterMultiplier, Map<String, Object> lastReadValues) {
        public Live {
            Objects.requireNonNull(messages, "messages");
            Objects.requireNonNull(lastReadValues, "lastReadValues");
            lastReadValues = Collections.unmodifiableMap(new LinkedHashMap<>(lastReadValues));
        }
    }

    /** A validated config read, plus the two facts the report needs about the file itself. */
    public record ConfigRead(BetterEndConfig config, Map<String, Object> values, int bundledVersion, boolean released) {
        public ConfigRead {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(values, "values");
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /**
     * What the pack half of a reload did. A failure is a value, not an exception, because it is
     * reported as one line of a report that also carries the config outcome -- never a rollback.
     */
    public record PackResult(List<ReloadReport.PackLine> lines, String failure) {
        public PackResult {
            Objects.requireNonNull(lines, "lines");
            lines = List.copyOf(lines);
        }

        public static PackResult failed(String reason) {
            return new PackResult(List.of(), reason);
        }

        public static PackResult completed(List<ReloadReport.PackLine> lines) {
            return new PackResult(lines, null);
        }
    }

    /**
     * Reads, seeds and backfills config.yml, then parses it.
     *
     * @throws IOException     the file could not be read or written
     * @throws ConfigException the file parsed but a value is unusable
     */
    @FunctionalInterface
    public interface ConfigReader {
        ConfigRead read() throws IOException;
    }

    /** Reads, seeds and backfills messages.yml. Any failure keeps the running text in place. */
    @FunctionalInterface
    public interface MessagesReader {
        Messages read() throws IOException;
    }

    /** Re-completes the bundled packs; reports failure as a value. */
    @FunctionalInterface
    public interface PackCompleter {
        PackResult complete(BetterEndConfig config);
    }

    /** Whether the content the world cannot open without is registered right now. */
    @FunctionalInterface
    public interface ContentProbe {
        boolean ready();
    }

    /** Hands the body to an off-thread scheduler; throwing it means "nothing was scheduled". */
    @FunctionalInterface
    public interface AsyncRunner {
        void execute(Runnable work);
    }

    private final Map<String, Object> startupValues;
    private final AtomicReference<Live> live;
    private final ConfigReader configReader;
    private final MessagesReader messagesReader;
    private final PackCompleter packCompleter;
    private final ContentProbe contentProbe;
    private final AsyncRunner asyncRunner;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Only publication and shutdown take this short lock; no file I/O or scheduler waits run inside it. */
    private final Object lifecycle = new Object();

    /**
     * @param startupValues the snapshot the running server was built from; kept for the life of this
     *                      coordinator and never replaced by a reload
     * @param live          the runtime triple this coordinator publishes into, owned by the caller so
     *                      the hot readers can consult it
     */
    public ReloadCoordinator(Map<String, Object> startupValues,
                             AtomicReference<Live> live,
                             ConfigReader configReader,
                             MessagesReader messagesReader,
                             PackCompleter packCompleter,
                             ContentProbe contentProbe,
                             AsyncRunner asyncRunner) {
        this.startupValues = Collections.unmodifiableMap(new LinkedHashMap<>(startupValues));
        this.live = Objects.requireNonNull(live, "live");
        this.configReader = Objects.requireNonNull(configReader, "configReader");
        this.messagesReader = Objects.requireNonNull(messagesReader, "messagesReader");
        this.packCompleter = Objects.requireNonNull(packCompleter, "packCompleter");
        this.contentProbe = Objects.requireNonNull(contentProbe, "contentProbe");
        this.asyncRunner = Objects.requireNonNull(asyncRunner, "asyncRunner");
    }

    /**
     * Starts a reload, or refuses because one is already running.
     *
     * <p>Called on the command thread. Everything slow -- both file reads, the pack writes, the
     * readiness probe -- happens inside {@code asyncRunner}, never here, so no region or global thread
     * is blocked on I/O and no future is awaited: this method either schedules the work or reports why
     * it did not.
     *
     * @param reply receives the finished report exactly once, on the async thread; delivering it to a
     *              human is the caller's business (it owns the entity/global scheduling)
     */
    public void reload(Consumer<List<Component>> reply) {
        Objects.requireNonNull(reply, "reply");
        Live snapshot = live.get();
        if (closed.get()) {
            // Disabled between dispatch and this call: there is nobody to read for, and a scheduler
            // that would refuse the task anyway. Nothing runs and nothing is claimed.
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            reply.accept(List.of(snapshot.messages().get("command.reload.busy")));
            return;
        }
        try {
            asyncRunner.execute(() -> runOnAsync(reply));
        } catch (RuntimeException | LinkageError refused) {
            // Nothing was scheduled, so nothing else will ever release the gate -- and nothing was
            // read, so this is exactly the "stopped before applying anything" report, not a claim
            // about a reload that half ran.
            inFlight.set(false);
            reply.accept(ReloadReport.rejected(snapshot.messages(), false, reason(refused)));
        }
    }

    /** Closes publication before disabling queued tasks; already-started file I/O is not rolled back. */
    public void shutdown() {
        synchronized (lifecycle) {
            closed.set(true);
            inFlight.set(false);
        }
    }

    /** True while a reload is running. Exposed so a test can assert the gate is free again. */
    public boolean inFlight() {
        return inFlight.get();
    }

    private void runOnAsync(Consumer<List<Component>> reply) {
        List<Component> lines;
        try {
            if (closed.get()) return;
            try {
                lines = runStages();
            } catch (RuntimeException | LinkageError error) {
                System.getLogger(ReloadCoordinator.class.getName()).log(
                        System.Logger.Level.ERROR, "BetterEnd reload failed", error);
                lines = List.of(live.get().messages().get("command.reload.failed",
                        Placeholder.unparsed("reason", reason(error))));
            }
        } finally {
            inFlight.set(false);
        }
        if (closed.get() || lines.isEmpty()) return;
        try {
            reply.accept(lines);
        } catch (RuntimeException error) {
            System.getLogger(ReloadCoordinator.class.getName()).log(
                    System.Logger.Level.WARNING, "BetterEnd reload reply failed", error);
        }
    }

    /** The reload proper, in execution order. Never touches the caller's thread. */
    private List<Component> runStages() {
        if (closed.get()) return List.of();
        Live before = live.get();

        // Stage 1 -- config. Fatal, and first: an unusable file must leave every running value alone.
        ConfigRead read;
        try {
            read = configReader.read();
        } catch (ConfigException invalid) {
            return ReloadReport.rejected(before.messages(), true, reason(invalid));
        } catch (IOException unreadable) {
            return ReloadReport.rejected(before.messages(), false, reason(unreadable));
        }
        if (closed.get()) return List.of();

        // Stage 2 -- messages. Soft: a broken text file keeps the running text rather than stopping.
        Messages texts = before.messages();
        String messagesFailure = null;
        try {
            texts = messagesReader.read();
        } catch (IOException | RuntimeException broken) {
            messagesFailure = reason(broken);
        }

        List<String> added = ReloadDiff.addedKeys(before.lastReadValues(), read.values());
        // Against what the running server was BUILT from, not against the last read: a value changed
        // by an earlier reload still has not taken effect, so it must keep being reported.
        List<String> restart = ReloadDiff.restartOnlyChanges(startupValues, read.values(), ReloadDiff.HOT_KEYS);

        Live published = new Live(texts, read.config().monsterDamageMultiplier(), read.values());
        synchronized (lifecycle) {
            if (closed.get()) return List.of();
            live.set(published);
        }
        if (closed.get()) return List.of();

        // Stage 3 -- packs. After the publication on purpose: nothing here may undo stage 1 and 2,
        // and the report states this stage's outcome on its own line.
        PackResult packs = packCompleter.complete(read.config());
        if (closed.get()) return List.of();

        // Stage 4 -- whether the content the world cannot open without is actually registered now.
        // A pack write alone changes nothing until CraftEngine re-reads it, so this is what stops the
        // report from implying the change took effect.
        boolean ready = contentReady();

        return ReloadReport.lines(texts, new ReloadReport.Facts(
                read.released(),
                read.bundledVersion(),
                added,
                before.monsterMultiplier(),
                published.monsterMultiplier(),
                packs.lines(),
                packs.failure(),
                restart,
                messagesFailure == null,
                messagesFailure,
                ready));
    }

    /**
     * Whether the critical content is registered, as the pack stage leaves it. A probe that cannot
     * answer at all counts as "not ready": the report warns instead of claiming the content is live.
     */
    private boolean contentReady() {
        try {
            return contentProbe.ready();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** The diagnostic text of a failure, for a message's {@code <reason>} placeholder. */
    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }
}
