package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines {@code /betterend reload} prints, composed from what the reload actually did.
 *
 * <p><b>Why this is a class of its own.</b> A reload is only useful if its report is trustworthy: the
 * one thing it must never do is imply that a startup-only value took effect. That is a property of the
 * <em>wording</em>, so the wording is built here -- from facts, in execution order -- instead of being
 * interleaved with the file I/O in the plugin. Written this way the contract is unit-testable: "a
 * changed terrain key is reported as needing a restart", "an unchanged multiplier is not announced as
 * applied", "a failed pack step is visible rather than silent".
 *
 * <p>Every sentence still comes from {@code messages.yml} (see {@link Messages}); this class only
 * chooses which keys are used and in what order. Bukkit-free apart from Adventure's component types.
 */
public final class ReloadReport {
    /**
     * One pack's outcome: {@code firstInstall} distinguishes a fresh release from a top-up.
     *
     * <p>{@code released}/{@code skipped} count the missing files written and the existing files left
     * alone. {@code upgraded}/{@code kept} add the second axis: a file this build ships a newer version
     * of was either replaced ({@code upgraded}) or preserved because the owner had edited it
     * ({@code kept}). The four-argument constructor is for callers that do not know an upgrade from a
     * top-up yet, and counts zero rather than inventing a number.
     */
    public record PackLine(String pack, boolean firstInstall, int released, int skipped,
                           int upgraded, int kept) {
        /** A line from a caller that has no upgrade information: no file was replaced or preserved. */
        public PackLine(String pack, boolean firstInstall, int released, int skipped) {
            this(pack, firstInstall, released, skipped, 0, 0);
        }
    }

    /**
     * What a reload found and did. Deliberately facts rather than prose, and deliberately complete:
     * every field is something the operator is entitled to see.
     *
     * @param configReleased       config.yml was missing and has just been written back out
     * @param bundledVersion       the {@code config-version} of the file that was re-released
     * @param addedKeys            keys this reload backfilled into the file
     * @param previousMultiplier   the monster-damage factor before this reload
     * @param appliedMultiplier    the factor now in force
     * @param packLines            per-pack outcomes, empty when no pack step ran
     * @param packFailure          why the pack step failed, or {@code null}
     * @param restartKeys          startup-only keys that changed and still need a full restart
     * @param messagesReloaded     {@code messages.yml} was re-read and the new wording is in force
     * @param messagesFailure      why {@code messages.yml} could not be re-read, or {@code null}; the
     *                             previous wording stays in force
     * @param ceContentReady       CraftEngine currently has the blocks and items world generation needs
     *                             registered in memory. Files on disk are not that: a resource write is
     *                             neither a CraftEngine re-read nor a change to generated chunks.
     */
    public record Facts(
            boolean configReleased,
            int bundledVersion,
            List<String> addedKeys,
            double previousMultiplier,
            double appliedMultiplier,
            List<PackLine> packLines,
            String packFailure,
            List<String> restartKeys,
            boolean messagesReloaded,
            String messagesFailure,
            boolean ceContentReady
    ) {
        /**
         * The facts as they stood before the message file and CraftEngine readiness were part of the
         * report: no {@code messages.yml} line, and content assumed ready. Kept so the existing callers
         * and tests that know nothing about those two steps still compile and still read honestly.
         */
        public Facts(boolean configReleased, int bundledVersion, List<String> addedKeys,
                     double previousMultiplier, double appliedMultiplier,
                     List<PackLine> packLines, String packFailure, List<String> restartKeys) {
            this(configReleased, bundledVersion, addedKeys, previousMultiplier, appliedMultiplier,
                    packLines, packFailure, restartKeys, false, null, true);
        }
    }

    private ReloadReport() {
    }

    /**
     * The whole report, in execution order: what was released, what was backfilled, what was applied,
     * what was re-completed, and -- last, so it is the line the operator reads -- what still needs a
     * restart.
     */
    public static List<Component> lines(Messages messages, Facts facts) {
        List<Component> out = new ArrayList<>();
        out.add(messages.get("command.reload.header"));

        if (facts.configReleased()) {
            out.add(messages.get("command.reload.config-released",
                    Placeholder.unparsed("version", String.valueOf(facts.bundledVersion()))));
        }

        if (facts.addedKeys().isEmpty()) {
            out.add(messages.get("command.reload.keys-none"));
        } else {
            out.add(messages.get("command.reload.keys-added", keys(facts.addedKeys())));
        }

        // The second file a reload re-reads, on its own line so a failure cannot be read as "the
        // wording was reloaded too": the previous text stays in force, and this line is the only place
        // that says so. A failure wins over a success -- both cannot be true.
        if (facts.messagesFailure() != null) {
            out.add(messages.get("command.reload.messages-failed",
                    Placeholder.unparsed("reason", facts.messagesFailure())));
        } else if (facts.messagesReloaded()) {
            out.add(messages.get("command.reload.messages-reloaded"));
        }

        if (facts.appliedMultiplier() == facts.previousMultiplier()) {
            out.add(messages.get("command.reload.multiplier-unchanged",
                    Placeholder.unparsed("value", String.valueOf(facts.appliedMultiplier()))));
        } else {
            out.add(messages.get("command.reload.multiplier",
                    Placeholder.unparsed("old", String.valueOf(facts.previousMultiplier())),
                    Placeholder.unparsed("new", String.valueOf(facts.appliedMultiplier()))));
        }

        out.addAll(packLines(messages, facts));

        // Written files are not read files: this plugin can put a pack on disk, and only CraftEngine
        // can read it, only a restart can rebuild the world. Derived from what the pack step actually
        // wrote, so the summary below can never hand out a clean bill of health over that gap.
        boolean ceFilesWritten = facts.packLines().stream()
                .anyMatch(line -> line.released() > 0 || line.upgraded() > 0);
        if (ceFilesWritten) {
            out.add(messages.get("command.reload.ce-reload-required"));
        }
        if (!facts.ceContentReady()) {
            out.add(messages.get("command.reload.ce-not-ready"));
        }

        boolean failed = facts.packFailure() != null || facts.messagesFailure() != null
                || !facts.ceContentReady();
        if (!facts.restartKeys().isEmpty()) {
            out.add(messages.get("command.reload.restart-required", keys(facts.restartKeys())));
        } else if (!ceFilesWritten && !failed) {
            // Only when nothing above can need one. A pack write does need CraftEngine to re-read it,
            // and "nothing needs a restart" printed under a not-ready CraftEngine is exactly the
            // reassurance that teaches an operator to stop reading this report.
            out.add(messages.get("command.reload.restart-none"));
        }

        if (!failed) {
            // A failure already had its own line; following it with "reload complete" would be the
            // small lie that makes every other line worth less.
            out.add(messages.get("command.reload.done"));
        }
        return out;
    }

    /** The pack half: one line per pack, or why it failed. */
    private static List<Component> packLines(Messages messages, Facts facts) {
        List<Component> out = new ArrayList<>();
        if (facts.packFailure() != null) {
            // Reported even when some packs landed: a partial release is not a success.
            out.add(messages.get("command.reload.packs-failed",
                    Placeholder.unparsed("reason", facts.packFailure())));
            return out;
        }
        for (PackLine line : facts.packLines()) {
            if (line.firstInstall()) {
                out.add(messages.get("command.reload.pack-installed",
                        Placeholder.unparsed("pack", line.pack()),
                        Placeholder.unparsed("released", String.valueOf(line.released()))));
            } else {
                out.add(messages.get("command.reload.pack-completed",
                        Placeholder.unparsed("pack", line.pack()),
                        Placeholder.unparsed("released", String.valueOf(line.released())),
                        Placeholder.unparsed("skipped", String.valueOf(line.skipped()))));
            }
            // Only when one actually happened: a "0 files upgraded" line on every reload is noise.
            if (line.upgraded() > 0) {
                out.add(messages.get("command.reload.pack-upgraded",
                        Placeholder.unparsed("pack", line.pack()),
                        Placeholder.unparsed("count", String.valueOf(line.upgraded()))));
            }
            if (line.kept() > 0) {
                // The owner's own edits were left alone -- worth saying, because it is the one outcome
                // where a file on disk does NOT match this build.
                out.add(messages.get("command.reload.pack-kept",
                        Placeholder.unparsed("pack", line.pack()),
                        Placeholder.unparsed("count", String.valueOf(line.kept()))));
            }
        }
        return out;
    }

    /**
     * A report that stops before applying anything: the file could not be read, or it is invalid.
     *
     * <p>Only these two lines, because that is the whole truth -- nothing was released, nothing was
     * backfilled, no value changed. Saying "reload complete" here would be the small lie that teaches
     * an operator to stop reading the output.
     */
    public static List<Component> rejected(Messages messages, boolean invalid, String reason) {
        return List.of(
                messages.get("command.reload.header"),
                messages.get(invalid ? "command.reload.invalid" : "command.reload.io-error",
                        Placeholder.unparsed("reason", reason)));
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver keys(List<String> keys) {
        return Placeholder.unparsed("keys", String.join(", ", keys));
    }
}
