package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Help must remain readable while a click only fills chat, never runs an administrative reload. */
class HelpReportTest {
    @Test
    void theVisualTemplateCannotChooseAnExecutableReload() {
        Messages messages = new Messages(Map.of(
                "command.help.header", "<aqua>BetterEnd</aqua>",
                "command.help.row", "<white><command></white> <gray>重新读取配置</gray>",
                "command.help.footer", "点击只填入命令"));
        List<Component> lines = HelpReport.lines(messages);
        assertEquals(3, lines.size());
        assertTrue(plain(lines.get(1)).contains("/betterend reload"));
        assertEquals(ClickEvent.suggestCommand(HelpReport.RELOAD_COMMAND), lines.get(1).clickEvent());
        lines.forEach(HelpReportTest::assertNoRunCommand);
        assertNull(lines.getFirst().clickEvent());
        assertNull(lines.getLast().clickEvent());
    }

    @Test
    void missingTextStillHasAVisibleKeyAndASafeSuggestion() {
        List<Component> lines = HelpReport.lines(new Messages(Map.of()));
        assertEquals("command.help.header", plain(lines.getFirst()));
        assertEquals("command.help.row", plain(lines.get(1)));
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, lines.get(1).clickEvent().action());
        lines.forEach(HelpReportTest::assertNoRunCommand);
    }

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    private static void assertNoRunCommand(Component component) {
        if (component.clickEvent() != null) {
            assertNotEquals(ClickEvent.Action.RUN_COMMAND, component.clickEvent().action());
        }
        component.children().forEach(HelpReportTest::assertNoRunCommand);
    }
}
