package dev.mumu.betterend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The plugin's own text, read from {@code messages.yml} and rendered as MiniMessage.
 * <p>
 * Every string the plugin emits to a human -- console lines and, later, in-game text -- goes through
 * here so the wording lives in one translatable file instead of being scattered through the code.
 * The file is Chinese by default; keys that are missing fall back to the key name itself, which is
 * ugly but never throws, so a half-translated file degrades instead of breaking startup.
 * <p>
 * The map handed in is the flattened {@code key.path -> value} view produced by
 * {@link ConfigStore}/{@link SafeYaml}; values are expected to be MiniMessage strings.
 * <p>
 * A key a translator left blank ({@code greeting:} with nothing after it) parses to a <b>null value</b>,
 * and a null value is treated exactly like an absent one: it falls back rather than throwing. That is
 * why the copy below is an unmodifiable {@link LinkedHashMap} and not {@code Map.copyOf}, which rejects
 * nulls with an NPE -- one empty line in {@code messages.yml} would otherwise stop the plugin.
 */
public final class Messages {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, Object> raw;

    public Messages(Map<String, Object> flattened) {
        // Copied, so a caller mutating its map later cannot change what renders; unmodifiable, so nothing
        // downstream can; insertion-ordered, so the file's own order survives for anything that walks it.
        this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(flattened));
    }

    /** The raw template text for a key, or {@code fallback} when the key is absent or left empty. */
    public String raw(String key, String fallback) {
        Object value = raw.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * Renders a key as a MiniMessage component. An unknown key renders as the key itself, so a
     * missing translation is visible in the console rather than silent.
     *
     * @param resolvers placeholders, e.g. {@code Placeholder.unparsed("pack", name)}
     */
    public Component get(String key, TagResolver... resolvers) {
        return MINI.deserialize(raw(key, key), resolvers);
    }
}
