package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /betterend reload} is allowed to claim it did.
 * <p>
 * WHY this is worth pinning: the dangerous failure is not a missing line, it is a reassuring one. An
 * operator who changes a terrain value, reads "reload complete", and only discovers the change never
 * applied at the next chunk border has been told something false by this class. So the assertions
 * below are about honesty: a startup-only change must be named as needing a restart, an unchanged
 * multiplier must not be announced as applied, and a failed pack release must not be followed by a
 * line implying success.
 */
class ReloadReportTest {
    private static final Messages MESSAGES = shippedMessages();

    /** The real messages.yml, so the assertions test the text the operator actually reads. */
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

    private static ReloadReport.Facts facts(List<String> added, double previous, double applied,
                                            List<ReloadReport.PackLine> packLines,
                                            String packFailure, List<String> restart) {
        return new ReloadReport.Facts(false, 2, added, previous, applied,
                packLines, packFailure, restart);
    }

    private static List<String> text(List<Component> lines) {
        List<String> out = new ArrayList<>();
        lines.forEach(line -> out.add(PlainTextComponentSerializer.plainText().serialize(line)));
        return out;
    }

    private static List<String> onlyText(ReloadReport.Facts facts) {
        return text(ReloadReport.lines(MESSAGES, facts));
    }

    // --- the claim that matters most -----------------------------------------------------------

    @Test
    void aChangedStartupOnlyKeyIsNamedAsNeedingARestart() {
        List<String> lines = onlyText(facts(List.of(), 1.5, 1.5,
                List.of(new ReloadReport.PackLine("betterend", false, 0, 57)),
                null, List.of("generator.layers.big.distance")));

        String last = lines.get(lines.size() - 2);
        assertTrue(last.contains("generator.layers.big.distance"), last);
        assertTrue(last.contains("重启"), "must say a restart is required: " + last);
        assertFalse(lines.getLast().contains("已生效"), "the closing line must not claim success");
    }

    @Test
    void nothingChangedIsReportedAsNoRestartNeeded() {
        List<String> lines = onlyText(facts(List.of(), 1.5, 1.5,
                List.of(new ReloadReport.PackLine("betterend", false, 0, 57)), null, List.of()));

        assertTrue(lines.stream().anyMatch(line -> line.contains("没有需要重启")), lines.toString());
    }

    // --- the one change it really applies ------------------------------------------------------

    @Test
    void aChangedMultiplierIsAnnouncedWithBothValues() {
        List<String> lines = onlyText(facts(List.of(), 1.5, 2.0,
                List.of(new ReloadReport.PackLine("betterend", false, 0, 57)), null, List.of()));

        String line = lines.stream().filter(text -> text.contains("倍率")).findFirst().orElseThrow();
        assertTrue(line.contains("1.5"), line);
        assertTrue(line.contains("2.0"), line);
        assertTrue(line.contains("已生效"), line);
    }

    @Test
    void anUnchangedMultiplierIsNotAnnouncedAsApplied() {
        // "Applied 1.5" when nothing moved would train the operator to distrust every other line.
        List<String> lines = onlyText(facts(List.of(), 1.5, 1.5,
                List.of(new ReloadReport.PackLine("betterend", false, 0, 57)), null, List.of()));

        String line = lines.stream().filter(text -> text.contains("倍率")).findFirst().orElseThrow();
        assertTrue(line.contains("未变"), line);
        assertFalse(line.contains("已生效"), "nothing was applied here: " + line);
    }

    // --- the config file itself ----------------------------------------------------------------

    @Test
    void aReReleasedFileSaysSoAndNamesTheGeneration() {
        ReloadReport.Facts reReleased = new ReloadReport.Facts(true, 7, List.of(), 1.5, 1.5,
                List.of(new ReloadReport.PackLine("betterend", false, 0, 57)), null, List.of());

        String line = onlyText(reReleased).stream()
                .filter(text -> text.contains("不存在")).findFirst().orElseThrow();
        assertTrue(line.contains("7"), line);
    }

    @Test
    void backfilledKeysAreListed() {
        List<String> lines = onlyText(facts(List.of("end-damage.monster-multiplier"), 1.5, 1.5,
                List.of(new ReloadReport.PackLine("betterend", false, 0, 57)), null, List.of()));

        String line = lines.stream().filter(text -> text.contains("补全")).findFirst().orElseThrow();
        assertTrue(line.contains("end-damage.monster-multiplier"), line);
    }

    // --- the pack half -------------------------------------------------------------------------

    @Test
    void aFirstInstallAndATopUpReadDifferently() {
        List<ReloadReport.PackLine> packs = List.of(
                new ReloadReport.PackLine("betterend", true, 120, 0),
                new ReloadReport.PackLine("dnt_end_castle", false, 3, 90));

        List<String> lines = onlyText(facts(List.of(), 1.5, 1.5, packs, null, List.of()));

        String install = lines.stream().filter(line -> line.contains("betterend")).findFirst().orElseThrow();
        String topUp = lines.stream().filter(line -> line.contains("dnt_end_castle")).findFirst().orElseThrow();
        assertTrue(install.contains("120"), install);
        assertTrue(topUp.contains("3"), topUp);
        assertTrue(topUp.contains("90"), topUp);
    }

    @Test
    void aPackFailureIsReportedInsteadOfADoneLineThatImpliesSuccess() {
        List<String> lines = onlyText(facts(List.of(), 1.5, 1.5, List.of(),
                "资源包 'betterend' 有 2 个文件写入失败", List.of()));

        assertTrue(lines.stream().anyMatch(line -> line.contains("写入失败")), lines.toString());
        assertFalse(lines.stream().anyMatch(line -> line.contains("重新写出")),
                "a failed reload must not claim the file was released");
    }

    // --- the early-exit reports ----------------------------------------------------------------

    @Test
    void anInvalidFileReportsThatNothingWasApplied() {
        List<String> lines = text(ReloadReport.rejected(MESSAGES, true, "'x' 的值无效"));

        assertEquals(2, lines.size(), "header plus the one honest line: " + lines);
        assertTrue(lines.getLast().contains("未应用任何改动"), lines.getLast());
        assertTrue(lines.getLast().contains("'x' 的值无效"), lines.getLast());
    }

    @Test
    void anUnreadableFileReportsThatNothingChanged() {
        List<String> lines = text(ReloadReport.rejected(MESSAGES, false, "拒绝访问"));

        assertTrue(lines.getLast().contains("保持不变"), lines.getLast());
        assertTrue(lines.getLast().contains("拒绝访问"), lines.getLast());
    }

    /** A tiny guard: these tests would pass vacuously against an empty message file. */
    @Test
    void theShippedMessageFileActuallySuppliesTheKeys() {
        for (String key : List.of("command.reload.header", "command.reload.done",
                "command.reload.restart-required", "command.reload.multiplier",
                "command.reload.busy", "command.reload.failed", "command.reload.ce-not-ready",
                "command.reload.messages-reloaded", "command.reload.ce-reload-required",
                "command.help.header", "command.help.row", "command.help.footer")) {
            assertFalse(MESSAGES.raw(key, "").isEmpty(), key + " missing from messages.yml");
        }
    }
}
