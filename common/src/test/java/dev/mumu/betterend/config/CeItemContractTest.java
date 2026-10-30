package dev.mumu.betterend.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Bundled CE quantities use supported item-data fields; style cleanup must not change player stacks. */
class CeItemContractTest {
    private static Path configuration() {
        for (Path at = Path.of("").toAbsolutePath(); at != null; at = at.getParent()) {
            Path root = at.resolve("plugin/src/main/resources/craftengine/betterend/configuration");
            if (Files.isDirectory(root)) return root;
        }
        throw new IllegalStateException("BetterEnd CE configuration not found");
    }

    private static Map<Path, Map<String, Object>> documents() throws IOException {
        Map<Path, Map<String, Object>> out = new LinkedHashMap<>();
        try (var paths = Files.walk(configuration())) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (path.toString().endsWith(".yml") || path.toString().endsWith(".yaml")) {
                    out.put(path, SafeYaml.load(path));
                }
            }
        }
        assertFalse(out.isEmpty(), "a missing bundled pack must not pass vacuously");
        return out;
    }

    @Test
    void stackComponentsNeverHideUnderItemSettings() throws IOException {
        for (var doc : documents().entrySet()) inspect(doc.getValue(), doc.getKey().toString());
    }

    private static void inspect(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("settings") instanceof Map<?, ?> settings) {
                assertFalse(settings.containsKey("max_stack_size"),
                        where + ": max_stack_size belongs to item data, not ItemSettings");
            }
            if (map.get("data") instanceof Map<?, ?> data) {
                checkStack(data.get("max_stack_size"), where);
                if (data.get("components") instanceof Map<?, ?> components) {
                    checkStack(components.get("minecraft:max_stack_size"), where);
                }
            }
            map.forEach((key, child) -> inspect(child, where + "/" + key));
        } else if (value instanceof Iterable<?> list) {
            for (Object child : list) inspect(child, where);
        }
    }

    private static void checkStack(Object value, String where) {
        if (value == null || value instanceof String text && text.contains("${")) return;
        assertInstanceOf(Number.class, value, where);
        Number count = (Number) value;
        assertEquals(count.intValue(), count.doubleValue(), where + ": stack size must be integral");
        assertTrue(count.intValue() >= 1 && count.intValue() <= 99, where + ": invalid stack size");
    }

    @Test
    void recipeResultsReferenceExistingItemsAndKeepPositiveQuantities() throws IOException {
        Map<Path, Map<String, Object>> docs = documents();
        Set<String> items = new TreeSet<>();
        for (var doc : docs.values()) {
            for (var section : doc.entrySet()) {
                if ((section.getKey().equals("items") || section.getKey().startsWith("items#"))
                        && section.getValue() instanceof Map<?, ?> map) {
                    for (Object key : map.keySet()) items.add(key.toString());
                } else if (section.getKey().startsWith("config_factory")
                        && section.getValue() instanceof Map<?, ?> factory
                        && factory.get("instances") instanceof Iterable<?> instances
                        && factory.get("blueprint") instanceof Map<?, ?> blueprint
                        && blueprint.get("items") instanceof Map<?, ?> generated) {
                    // Expand only factory identifier keys, not CE's runtime template/data semantics.
                    for (Object instance : instances) {
                        if (!(instance instanceof Map<?, ?> arguments)) continue;
                        for (Object key : generated.keySet()) {
                            String id = key.toString();
                            for (var argument : arguments.entrySet()) {
                                id = id.replace("${" + argument.getKey() + "}", String.valueOf(argument.getValue()));
                            }
                            assertFalse(id.contains("${"), "unresolved factory item identifier: " + id);
                            items.add(id);
                        }
                    }
                }
            }
        }
        assertFalse(items.isEmpty());
        int checked = 0;
        for (var doc : docs.entrySet()) {
            for (var section : doc.getValue().entrySet()) {
                if (!(section.getKey().equals("recipes") || section.getKey().startsWith("recipes#"))) continue;
                if (!(section.getValue() instanceof Map<?, ?> recipes)) continue;
                for (var recipe : recipes.entrySet()) {
                    if (!(recipe.getValue() instanceof Map<?, ?> body)
                            || !(body.get("result") instanceof Map<?, ?> result)) continue;
                    String where = doc.getKey() + ":" + recipe.getKey();
                    assertInstanceOf(String.class, result.get("id"), where);
                    String id = result.get("id").toString();
                    if (id.contains("${")) continue; // Factory expansion is separately covered, not emulated here.
                    if (id.startsWith("betterend:")) assertTrue(items.contains(id), where + " -> " + id);
                    Object quantity = result.containsKey("count") ? result.get("count") : 1;
                    assertInstanceOf(Number.class, quantity, where);
                    Number n = (Number) quantity;
                    assertEquals(n.intValue(), n.doubleValue(), where);
                    assertTrue(n.intValue() > 0, where + ": a recipe cannot create a zero/negative stack");
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "must inspect actual recipe results, not just an empty schema");
    }

    @Test
    void smithingTemplatesKeepTheirExistingStackablePaperBase() throws IOException {
        Map<String, Object> root = SafeYaml.load(configuration().resolve("items/B7-metals.yml"));
        Map<?, ?> templates = assertInstanceOf(Map.class, root.get("templates"));
        Map<?, ?> smithing = assertInstanceOf(Map.class, templates.get("betterend:b7/smithing_template"));
        assertEquals("paper", smithing.get("material"));
        Map<?, ?> data = assertInstanceOf(Map.class, smithing.get("data"));
        assertFalse(data.containsKey("max_stack_size"),
                "do not turn stackable smithing templates into unstackable equipment during normalization");
    }
}
