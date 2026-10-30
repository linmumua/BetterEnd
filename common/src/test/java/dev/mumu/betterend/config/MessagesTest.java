package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@code messages.yml} is allowed to do to startup.
 * <p>
 * WHY these assertions exist: the file is the one thing an operator edits by hand between upgrades, and
 * the mistake they make is an empty value -- {@code greeting:} with the text accidentally deleted. With a
 * {@code Map.copyOf} copy that single line used to throw an NPE while the messages were being loaded,
 * which reads as "the plugin is broken" to someone who only deleted a line. So the pin here is that an
 * empty value degrades to the same fallback an absent key gets, and that a non-empty one still renders
 * exactly as before: same MiniMessage tags, same placeholders, same fallback-to-key-name rule.
 */
class MessagesTest {
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void anEmptyValueIsToleratedAndFallsBackLikeAMissingKey() {
        // The real path: parsed from YAML, where `greeting:` with nothing after it is a null value.
        Messages messages = new Messages(SafeYaml.flatten(SafeYaml.load("greeting:\nother: hi\n")));

        assertEquals("fallback", messages.raw("greeting", "fallback"), "an empty value must fall back, not throw");
        assertEquals("greeting", plain(messages.get("greeting")),
                "an empty value renders as the key itself, exactly like an absent one");
        assertEquals("hi", messages.raw("other", "fallback"), "a filled key is unaffected");
    }

    @Test
    void aMapWithNullValuesCanBeHandedInWithoutThrowing() {
        // The regression itself: Map.copyOf rejected this, so construction was the failure point.
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattened.put("blank", null);
        flattened.put("filled", "text");

        Messages messages = new Messages(flattened);

        assertEquals("fallback", messages.raw("blank", "fallback"));
        assertEquals("text", messages.raw("filled", "fallback"));
    }

    @Test
    void miniMessageTagsStillRender() {
        Messages messages = new Messages(Map.of("line", "<green>已完成</green>"));

        assertEquals("<green>已完成</green>", messages.raw("line", "fallback"),
                "raw() hands out the template unparsed, so the owner sees what they wrote");
        assertEquals("已完成", plain(messages.get("line")), "the tag still applies when rendering");
    }

    @Test
    void placeholdersStillResolve() {
        Messages messages = new Messages(Map.of("released", "资源包 <pack> 已释放"));

        Component rendered = messages.get("released", Placeholder.unparsed("pack", "betterend"));

        assertEquals("资源包 betterend 已释放", plain(rendered));
    }

    @Test
    void aNonStringValueRendersAsItsText() {
        // A stray number in the file should read as that number, not as a ClassCastException at startup.
        Messages messages = new Messages(Map.of("limit", 5));

        assertEquals("5", messages.raw("limit", "fallback"));
        assertEquals("5", plain(messages.get("limit")));
    }

    @Test
    void theCallersMapIsCopiedSoLaterEditsCannotChangeWhatRenders() {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattened.put("line", "first");
        Messages messages = new Messages(flattened);

        flattened.put("line", "second");

        assertEquals("first", messages.raw("line", "fallback"), "the message must not shift under the caller");
    }
}
