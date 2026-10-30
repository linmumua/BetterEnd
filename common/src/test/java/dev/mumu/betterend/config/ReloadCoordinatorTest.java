package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties {@code /betterend reload} cannot get wrong, exercised against the real
 * {@link ReloadCoordinator} with the platform seams stubbed.
 *
 * <p>WHY these and not "does it reload": every assertion below protects something an operator would
 * be hurt by if it regressed -- two overlapping reloads interleaving their file writes, a gate that
 * stays set so every later reload is refused, a config change rolled back by an unrelated pack
 * failure, a broken messages.yml silently replacing the running text, or a startup-only change
 * reported as applied because the baseline moved. The seams are thin on purpose so the tests run the
 * coordination itself, not a model of it.
 */
class ReloadCoordinatorTest {
    /** The real messages.yml, so wording assertions are about what the operator actually reads. */
    private static final Messages SHIPPED = shippedMessages();

    private static Messages shippedMessages() {
        Path at = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && at != null; up++, at = at.getParent()) {
            Path candidate = at.resolve("plugin/src/main/resources/messages.yml");
            if (Files.isRegularFile(candidate)) {
                try {
                    return new Messages(SafeYaml.flatten(SafeYaml.load(candidate)));
                } catch (IOException error) {
                    throw new IllegalStateException("messages.yml unreadable", error);
                }
            }
        }
        throw new IllegalStateException("plugin/src/main/resources/messages.yml not found");
    }

    private static final String MULTIPLIER = ReloadDiff.MONSTER_MULTIPLIER_KEY;
    private static final String TERRAIN = "features.flavolite-layers-per-chunk";

    // ---------------------------------------------------------------- fixtures

    private static BetterEndConfig config(double multiplier) {
        BetterEndConfig d = BetterEndConfig.DEFAULTS;
        return new BetterEndConfig(d.bigIslands(), d.mediumIslands(), d.smallIslands(),
                d.flavoliteLayersPerChunk(), d.thallasiumVeinsPerChunk(), d.enderVeinsPerChunk(),
                d.violeciteLayersPerChunk(), d.amberVeinsPerChunk(), d.dragonBoneVeinsPerChunk(),
                d.advancementSampleTicks(), multiplier);
    }

    private static Map<String, Object> values(Object multiplier, Object terrain) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(MULTIPLIER, multiplier);
        map.put(TERRAIN, terrain);
        return map;
    }

    private static ReloadCoordinator.ConfigRead read(double multiplier, Object terrain) {
        return new ReloadCoordinator.ConfigRead(config(multiplier), values(multiplier, terrain), 2, false);
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(line -> PlainTextComponentSerializer.plainText().serialize(line)).toList();
    }

    private static boolean anyContains(List<Component> lines, String needle) {
        return plain(lines).stream().anyMatch(line -> line.contains(needle));
    }

    /** Runs the body synchronously, on this thread. */
    private static ReloadCoordinator.AsyncRunner inline() {
        return Runnable::run;
    }

    /** Holds the body so a test can decide when -- or whether -- it runs. */
    private static final class Capturing implements ReloadCoordinator.AsyncRunner {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable work) {
            queued.add(work);
        }

        void runQueued() {
            List<Runnable> copy = List.copyOf(queued);
            queued.clear();
            copy.forEach(Runnable::run);
        }

        int queued() {
            return queued.size();
        }
    }

    private record Seams(ReloadCoordinator.ConfigReader configReader,
                         ReloadCoordinator.MessagesReader messagesReader,
                         ReloadCoordinator.PackCompleter packCompleter,
                         ReloadCoordinator.ContentProbe contentProbe) {
    }

    private static Seams reading(ReloadCoordinator.ConfigRead read) {
        return new Seams(() -> read, () -> SHIPPED,
                config -> ReloadCoordinator.PackResult.completed(List.of()), () -> true);
    }

    /** A coordinator whose startup baseline is the built world: multiplier 1.5, terrain 300. */
    private static ReloadCoordinator coordinator(Seams seams, ReloadCoordinator.AsyncRunner async,
                                                 AtomicReference<ReloadCoordinator.Live> live) {
        return new ReloadCoordinator(values(1.5, 300), live, seams.configReader(),
                seams.messagesReader(), seams.packCompleter(), seams.contentProbe(), async);
    }

    private static AtomicReference<ReloadCoordinator.Live> liveAt(double multiplier, Object terrain) {
        return new AtomicReference<>(new ReloadCoordinator.Live(SHIPPED, multiplier, values(multiplier, terrain)));
    }

    // ---------------------------------------------------------------- single flight

    @Test
    void aSecondRequestWhileOneIsRunningIsRefusedAndToldSo() {
        Capturing async = new Capturing();
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        ReloadCoordinator coordinator = coordinator(reading(read(2.0, 300)), async, live);

        List<List<Component>> firstReply = new ArrayList<>();
        coordinator.reload(firstReply::add);
        assertTrue(coordinator.inFlight(), "the gate must be held from the moment the work is scheduled");
        assertEquals(1, async.queued());

        List<List<Component>> secondReply = new ArrayList<>();
        coordinator.reload(secondReply::add);

        // Exactly one line, and none of the work ran twice.
        assertEquals(1, secondReply.size());
        assertEquals(1, async.queued(), "the refused request must not schedule anything");
        assertEquals(0, firstReply.size(), "the refused request must not answer for the running one");
        assertEquals(1.5, live.get().monsterMultiplier(), "nothing is published before the body runs");

        async.runQueued();
        assertEquals(1, firstReply.size(), "the original request still gets exactly one report");
        assertEquals(2.0, live.get().monsterMultiplier(), "and its publication happened once");
        assertFalse(coordinator.inFlight(), "the report is delivered with the gate already free");
    }

    @Test
    void aRefusedRequestDoesNotReportSuccess() {
        Capturing async = new Capturing();
        ReloadCoordinator coordinator = coordinator(reading(read(2.0, 300)), async, liveAt(1.5, 300));

        coordinator.reload(lines -> { });
        List<List<Component>> second = new ArrayList<>();
        coordinator.reload(second::add);

        List<String> lines = plain(second.get(0));
        assertEquals(1, lines.size(), "a refusal is one line, not a whole report: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains("reload 完成")),
                "a refusal must never claim the reload completed: " + lines);
        async.runQueued();
    }

    // ---------------------------------------------------------------- gate release

    @Test
    void aFailedReloadReleasesTheGateSoTheNextOneCanRun() {
        AtomicInteger attempt = new AtomicInteger();
        ReloadCoordinator.ConfigReader reader = () -> {
            if (attempt.getAndIncrement() == 0) {
                throw new ConfigException("'end-damage.monster-multiplier' 的值 abc 无效");
            }
            return read(2.0, 300);
        };
        Seams seams = new Seams(reader, () -> SHIPPED,
                config -> ReloadCoordinator.PackResult.completed(List.of()), () -> true);
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        ReloadCoordinator coordinator = coordinator(seams, inline(), live);

        List<List<Component>> replies = new ArrayList<>();
        coordinator.reload(replies::add);

        assertFalse(coordinator.inFlight(), "a rejected file must still release the gate");
        assertTrue(anyContains(replies.get(0), "config.yml 当前无效"), plain(replies.get(0)).toString());
        assertEquals(1.5, live.get().monsterMultiplier(), "a rejected file changes nothing");

        coordinator.reload(replies::add); // the retry that matters
        assertFalse(coordinator.inFlight());
        assertEquals(2, replies.size(), "the retry ran and reported");
        assertEquals(2.0, live.get().monsterMultiplier(), "the retry applied its value");
    }

    @Test
    void aSchedulingFailureReleasesTheGateAndIsReported() {
        ReloadCoordinator.AsyncRunner refusing = work -> {
            throw new IllegalStateException("Plugin attempted to register task while disabled");
        };
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        ReloadCoordinator coordinator = coordinator(reading(read(2.0, 300)), refusing, live);

        List<List<Component>> replies = new ArrayList<>();
        coordinator.reload(replies::add);

        assertFalse(coordinator.inFlight(), "nothing was scheduled, so nothing else could release it");
        assertEquals(1, replies.size(), "the operator is told the reload did not start");
        assertEquals(2, replies.get(0).size(), "a refusal has a header and a cause, never a success summary");
        assertTrue(anyContains(replies.get(0), "disabled"));
        assertFalse(anyContains(replies.get(0), "reload 完成"));
        assertEquals(1.5, live.get().monsterMultiplier(), "nothing was published");
    }

    @Test
    void shutdownReleasesTheGateAndStopsTakingWork() {
        Capturing async = new Capturing();
        ReloadCoordinator coordinator = coordinator(reading(read(2.0, 300)), async, liveAt(1.5, 300));

        coordinator.reload(lines -> { });
        assertTrue(coordinator.inFlight());
        int alreadyQueued = async.queued();

        coordinator.shutdown();
        assertFalse(coordinator.inFlight(), "disabling must not leave the gate set");

        List<List<Component>> afterShutdown = new ArrayList<>();
        coordinator.reload(afterShutdown::add);
        assertEquals(alreadyQueued, async.queued(), "a disabled plugin must not schedule file work");
        assertTrue(afterShutdown.isEmpty(), "a disabled plugin must not claim anything to anyone");
    }

    @Test
    void queuedWorkAfterShutdownNeverReadsOrPublishes() {
        Capturing async = new Capturing();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        ReloadCoordinator.Live before = live.get();
        Seams seams = new Seams(() -> { reads.incrementAndGet(); return read(2.0, 300); },
                () -> SHIPPED, cfg -> ReloadCoordinator.PackResult.completed(List.of()), () -> true);
        ReloadCoordinator coordinator = coordinator(seams, async, live);
        List<List<Component>> replies = new ArrayList<>();
        coordinator.reload(replies::add);
        coordinator.shutdown();
        async.runQueued();
        assertEquals(0, reads.get(), "cancelled queued work must not touch disk");
        assertSame(before, live.get());
        assertTrue(replies.isEmpty(), "a disabled plugin must not report success");
        assertFalse(coordinator.inFlight());
    }

    @Test
    void shutdownDuringReadsPreventsPublicationAndPackWrites() {
        AtomicReference<ReloadCoordinator> owner = new AtomicReference<>();
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        ReloadCoordinator.Live before = live.get();
        AtomicInteger packs = new AtomicInteger();
        Seams seams = new Seams(() -> read(2.0, 300), () -> {
            owner.get().shutdown();
            return SHIPPED;
        }, cfg -> { packs.incrementAndGet(); return ReloadCoordinator.PackResult.completed(List.of()); }, () -> true);
        ReloadCoordinator coordinator = coordinator(seams, inline(), live);
        owner.set(coordinator);
        List<List<Component>> replies = new ArrayList<>();
        coordinator.reload(replies::add);
        assertSame(before, live.get(), "shutdown is a publication boundary, not just a scheduler hint");
        assertEquals(0, packs.get());
        assertTrue(replies.isEmpty());
        assertFalse(coordinator.inFlight());
    }

    @Test
    void anUnexpectedPackFailureReportsPartialProgressAndAllowsRetry() {
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        AtomicInteger attempts = new AtomicInteger();
        Seams seams = new Seams(() -> read(2.0, 300), () -> SHIPPED, cfg -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("unexpected-pack-error");
        }, () -> true);
        ReloadCoordinator coordinator = coordinator(seams, inline(), live);
        List<List<Component>> replies = new ArrayList<>();
        coordinator.reload(replies::add);
        coordinator.reload(replies::add);
        assertEquals(2, attempts.get(), "the exception must not strand the single-flight gate");
        assertEquals(2.0, live.get().monsterMultiplier(), "a pack error does not roll back applied config");
        assertEquals(2, replies.size());
        assertTrue(anyContains(replies.get(0), "unexpected-pack-error"));
        assertFalse(anyContains(replies.get(0), "reload 完成"));
        assertFalse(coordinator.inFlight());
    }

    @Test
    void aThrowingReplyDoesNotStrandTheGate() {
        ReloadCoordinator coordinator = coordinator(reading(read(2.0, 300)), inline(), liveAt(1.5, 300));

        coordinator.reload(lines -> {
            throw new IllegalStateException("sender disconnected");
        });

        assertFalse(coordinator.inFlight(), "the report is the caller's problem; the gate is not");
    }

    // ---------------------------------------------------------------- publication order

    @Test
    void theConfigIsPublishedBeforeThePackStepRuns() {
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        List<Double> seenByPackStep = new ArrayList<>();
        Seams seams = new Seams(() -> read(2.0, 300), () -> SHIPPED, config -> {
            seenByPackStep.add(live.get().monsterMultiplier());
            return ReloadCoordinator.PackResult.completed(List.of());
        }, () -> true);

        coordinator(seams, inline(), live).reload(lines -> { });

        assertEquals(List.of(2.0), seenByPackStep,
                "the new value is already live when the pack step starts: a pack failure must not roll back");
    }

    @Test
    void aPackFailureLeavesTheConfigAppliedAndIsReportedAsItsOwnLine() {
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        Seams seams = new Seams(() -> read(2.0, 300), () -> SHIPPED,
                config -> ReloadCoordinator.PackResult.failed("磁盘已满"),
                () -> true);
        ReloadCoordinator coordinator = coordinator(seams, inline(), live);

        List<List<Component>> replies = new ArrayList<>();
        coordinator.reload(replies::add);

        assertEquals(2.0, live.get().monsterMultiplier(),
                "a pack failure is not a config failure, and no rollback is claimed or performed");
        List<String> lines = plain(replies.get(0));
        assertTrue(anyContains(replies.get(0), "补释放 CraftEngine 资源包失败"), lines.toString());
        assertTrue(lines.get(0).contains("开始重新读取"), "the report still opens as a reload: " + lines);
    }

    @Test
    void theLastReadBaselineMovesButTheStartupBaselineDoesNot() {
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        ReloadCoordinator[] holder = new ReloadCoordinator[1];
        List<List<Component>> replies = new ArrayList<>();

        // First reload: the owner changes a startup-only key.
        holder[0] = coordinator(reading(read(1.5, 400)), inline(), live);
        holder[0].reload(replies::add);
        assertTrue(anyContains(replies.get(0), "需要完整重启服务器"),
                "a changed terrain value must be named as restart-required");

        // Second reload: the file still says 400 -- unchanged since the last read, but still not what
        // the running server was built from, so it must STILL be reported.
        List<List<Component>> second = new ArrayList<>();
        holder[0].reload(second::add);
        assertTrue(anyContains(second.get(0), "需要完整重启服务器"),
                "the restart list is computed against startup values, not against the last read: "
                        + plain(second.get(0)));
        assertEquals(400, live.get().lastReadValues().get(TERRAIN),
                "the last-read baseline did move, so the next diff reports only genuine changes");
    }

    @Test
    void readingHappensOnTheAsyncSideNotOnTheCallingThread() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        Seams seams = new Seams(() -> {
            readerThread.set(Thread.currentThread());
            return read(2.0, 300);
        }, () -> SHIPPED, config -> ReloadCoordinator.PackResult.completed(List.of()), () -> true);

        // A real second thread, so this is not "inline" pretending to be off-thread.
        ReloadCoordinator.AsyncRunner offThread = work -> {
            Thread thread = new Thread(work, "betterend-async-test");
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        coordinator(seams, offThread, live).reload(lines -> { });

        assertNotNull(readerThread.get());
        assertFalse(readerThread.get() == caller,
                "config.yml is read off the command thread, so no region thread waits on disk");
    }

    // ---------------------------------------------------------------- messages

    @Test
    void aBrokenMessagesFileKeepsTheRunningTextAndSaysSo() {
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        Messages running = live.get().messages();
        Seams seams = new Seams(() -> read(2.0, 300), () -> {
            throw new IOException("messages.yml 无法解析");
        }, config -> ReloadCoordinator.PackResult.completed(List.of()), () -> true);

        List<List<Component>> replies = new ArrayList<>();
        coordinator(seams, inline(), live).reload(replies::add);

        assertSame(running, live.get().messages(),
                "text that could not be parsed must not replace the text in force");
        assertEquals(2.0, live.get().monsterMultiplier(),
                "the config half still applies: the stages are independent, not one transaction");

        String expected = PlainTextComponentSerializer.plainText().serialize(running.get(
                "command.reload.messages-failed", Placeholder.unparsed("reason", "messages.yml 无法解析")));
        assertTrue(plain(replies.get(0)).contains(expected), plain(replies.get(0)).toString());
    }

    @Test
    void aReloadedMessagesFileIsWhatTheReportIsWrittenIn() {
        Messages reloaded = new Messages(Map.of("command.reload.done", "<green>新的完成行</green>"));
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        Seams seams = new Seams(() -> read(2.0, 300), () -> reloaded,
                config -> ReloadCoordinator.PackResult.completed(List.of()), () -> true);

        List<List<Component>> replies = new ArrayList<>();
        coordinator(seams, inline(), live).reload(replies::add);

        assertSame(reloaded, live.get().messages());
        assertTrue(plain(replies.get(0)).contains("新的完成行"),
                "the reload speaks in the text it just loaded: " + plain(replies.get(0)));
    }

    // ---------------------------------------------------------------- content readiness

    @Test
    void contentThatIsNotRegisteredIsWarnedAboutRatherThanClaimedLive() {
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);
        Seams seams = new Seams(() -> read(2.0, 300), () -> SHIPPED,
                config -> ReloadCoordinator.PackResult.completed(List.of(new ReloadReport.PackLine("betterend", false, 3, 0))),
                () -> false);

        List<List<Component>> replies = new ArrayList<>();
        coordinator(seams, inline(), live).reload(replies::add);

        List<String> lines = plain(replies.get(0));
        assertTrue(anyContains(replies.get(0), "尚未在内存中注册"), lines.toString());
        assertFalse(lines.stream().anyMatch(line -> line.contains("reload 完成")),
                "content that is not live must not be signed off with 'done': " + lines);
    }

    @Test
    void aProbeThatCannotAnswerCountsAsNotReady() {
        Seams seams = new Seams(() -> read(2.0, 300), () -> SHIPPED,
                config -> ReloadCoordinator.PackResult.completed(List.of()),
                () -> {
                    throw new LinkageError("CraftEngine API unavailable");
                });

        List<List<Component>> replies = new ArrayList<>();
        coordinator(seams, inline(), liveAt(1.5, 300)).reload(replies::add);

        assertTrue(anyContains(replies.get(0), "尚未在内存中注册"), plain(replies.get(0)).toString());
    }

    @Test
    void aCleanReloadEndsOnTheDoneLine() {
        List<List<Component>> replies = new ArrayList<>();
        coordinator(reading(read(2.0, 300)), inline(), liveAt(1.5, 300)).reload(replies::add);

        List<String> lines = plain(replies.get(0));
        assertEquals(PlainTextComponentSerializer.plainText().serialize(SHIPPED.get("command.reload.done")),
                lines.get(lines.size() - 1), "a clean reload must end on the configured success summary");
    }

    // ---------------------------------------------------------------- rejected early

    @Test
    void anUnreadableFileStopsBeforeAnythingElseRuns() {
        AtomicBoolean packStepRan = new AtomicBoolean();
        Seams seams = new Seams(() -> {
            throw new IOException("config.yml 读不出来");
        }, () -> SHIPPED, config -> {
            packStepRan.set(true);
            return ReloadCoordinator.PackResult.completed(List.of());
        }, () -> true);
        AtomicReference<ReloadCoordinator.Live> live = liveAt(1.5, 300);

        List<List<Component>> replies = new ArrayList<>();
        coordinator(seams, inline(), live).reload(replies::add);

        assertFalse(packStepRan.get(), "an unreadable config must stop before the pack step");
        assertEquals(1.5, live.get().monsterMultiplier());
        List<String> lines = plain(replies.get(0));
        assertTrue(anyContains(replies.get(0), "重新读取 config.yml 失败"), lines.toString());
        assertFalse(lines.stream().anyMatch(line -> line.contains("reload 完成")),
                "nothing was reloaded, so the report must not end on 'done': " + lines);
    }
}
