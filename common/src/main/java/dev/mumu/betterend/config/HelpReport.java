package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;

/**
 * The lines {@code /betterend} and {@code /betterend help} print: the command list, as clickable text.
 *
 * <p><b>Why this is a class of its own.</b> Help is the one screen with no state behind it, which makes
 * it the easiest place for hard-coded strings to drift away from {@code messages.yml}. Here it is a
 * fixed, ordered list rendered from {@code command.help.*}: a bare {@code /betterend} and an explicit
 * {@code help} render identically, because two different usage screens teach nothing.
 *
 * <p><b>Why the click only suggests.</b> {@code /betterend reload} touches the configuration and the
 * pack folder, so the row binds {@code suggestCommand} in code, separately from the visual template.
 * Clicking fills the chat input and leaves the decision -- and the enter key -- to the operator.
 *
 * <p>Bukkit-free apart from Adventure's component types; the caller decides who receives the lines,
 * and whether the console gets a copy.
 */
public final class HelpReport {
    /** The one command this build exposes. The row's click target is checked against it by tests. */
    public static final String RELOAD_COMMAND = "/betterend reload";

    private HelpReport() {
    }

    /**
     * The whole help screen: a header, one row per command, a footer.
     *
     * @param messages the plugin's text; a missing key renders as the key name and never throws
     * @return exactly three lines, in order
     */
    public static List<Component> lines(Messages messages) {
        return List.of(
                messages.get("command.help.header"),
                messages.get("command.help.row",
                        Placeholder.unparsed("command", RELOAD_COMMAND))
                        .clickEvent(ClickEvent.suggestCommand(RELOAD_COMMAND)),
                messages.get("command.help.footer"));
    }
}
