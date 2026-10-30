package dev.mumu.betterend.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarantees {@link SafeYaml} exists to give: the four shapes the raw snakeyaml loader would
 * accept are refused here, and the tree it hands back is the plain String-keyed one the rest of the
 * config layer assumes.
 * <p>
 * Nothing here names a Bukkit or CraftEngine type, so it runs on the JUnit-only classpath.
 */
class SafeYamlTest {

    @Test
    void readsANestedMappingAndFlattensToDottedKeys() {
        Map<String, Object> tree = SafeYaml.load("""
                generator:
                  generate-central-island: true
                  layers:
                    big:
                      distance: 300.0
                features:
                  extra-veins-per-chunk: 24
                """);

        assertEquals(Map.of("generate-central-island", true,
                        "layers", Map.of("big", Map.of("distance", 300.0))),
                tree.get("generator"));

        Map<String, Object> flat = SafeYaml.flatten(tree);
        assertEquals(true, flat.get("generator.generate-central-island"));
        assertEquals(300.0, flat.get("generator.layers.big.distance"));
        assertEquals(24, flat.get("features.extra-veins-per-chunk"));
        // A section is not a leaf: it contributes only its children to the flat view.
        assertFalse(flat.containsKey("generator.layers.big"));
    }

    @Test
    void anEmptyDocumentIsAnEmptyMapping() {
        assertTrue(SafeYaml.load("").isEmpty());
        assertTrue(SafeYaml.load("# only a comment\n").isEmpty());
    }

    @Test
    void aDuplicateKeyIsRefusedRatherThanSilentlyLastWins() {
        // Two `layers:` blocks. The old Bukkit loader would quietly keep the second; a silent drop of
        // half the terrain config is exactly the bug this rejects.
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> SafeYaml.load("""
                        generator:
                          layers: 1
                          layers: 2
                        """));
        assertTrue(error.getMessage().toLowerCase().contains("duplicate"), error.getMessage());
    }

    @Test
    void anAliasBombIsRefused() {
        // A single anchor referenced far more than the ceiling allows: the classic
        // "billion laughs" expansion, stopped at the load rather than the OOM.
        StringBuilder document = new StringBuilder("a: &a [x, x, x, x, x, x, x, x, x]\n");
        for (int i = 0; i <= SafeYaml.MAX_ALIASES + 4; i++) {
            document.append("b").append(i).append(": *a\n");
        }
        assertThrows(RuntimeException.class, () -> SafeYaml.load(document.toString()));
    }

    @Test
    void aDeeplyNestedDocumentIsRefused() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < SafeYaml.MAX_DEPTH + 5; i++) {
            document.append("  ".repeat(i)).append("k").append(i).append(":\n");
        }
        document.append("  ".repeat(SafeYaml.MAX_DEPTH + 5)).append("leaf: 1\n");
        assertThrows(RuntimeException.class, () -> SafeYaml.load(document.toString()));
    }

    @Test
    void anOversizedDocumentIsRefused() {
        String huge = "k: " + "x".repeat(SafeYaml.MAX_CODE_POINTS + 1) + "\n";
        assertThrows(RuntimeException.class, () -> SafeYaml.load(huge));
    }

    @Test
    void aNonMappingRootIsRejectedByUsNotSilentlyCoerced() {
        assertThrows(IllegalArgumentException.class, () -> SafeYaml.load("- a\n- b\n"));
        assertThrows(IllegalArgumentException.class, () -> SafeYaml.load("just a scalar\n"));
    }

    @Test
    void aTaggedJavaObjectIsNotInstantiated() {
        // A safe constructor refuses arbitrary tags, so a hostile file cannot reach a constructor.
        assertThrows(RuntimeException.class,
                () -> SafeYaml.load("boom: !!java.net.URL [\"http://example.invalid\"]\n"));
    }

    @Test
    void roundTripsThroughDump() {
        Map<String, Object> tree = SafeYaml.load("a:\n  b: 1\n  c: text\n");
        Map<String, Object> again = SafeYaml.load(SafeYaml.dump(tree));
        assertEquals(tree, again);
    }

    @Test
    void aRecursiveValueAliasIsRefused() {
        // `&x [*x]` makes the sequence contain itself. setAllowRecursiveKeys only guards recursive
        // KEYS, so this value cycle is what normalise()'s identity check must catch -- otherwise
        // walking the tree recurses forever and takes the loader down with a StackOverflowError.
        assertThrows(RuntimeException.class, () -> SafeYaml.load("a: &x [*x]\n"));
    }

    @Test
    void dumpIsStableBlockStyleNotFlow() {
        String dumped = SafeYaml.dump(SafeYaml.load("a:\n  b: 1\n"));
        assertTrue(dumped.contains("a:"), dumped);
        assertTrue(dumped.contains("b: 1"), dumped);
        assertFalse(dumped.contains("{"), "dump must use block style, not flow: " + dumped);
    }
}
