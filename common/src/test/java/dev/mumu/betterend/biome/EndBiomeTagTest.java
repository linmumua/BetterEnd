package dev.mumu.betterend.biome;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The biome tags the pack ships, checked against the biomes it actually ships rather than against a
 * copied name list. Two ways this drifts and both are silent at runtime: a biome added to
 * {@code worldgen/biome} but not to {@code minecraft:is_end} becomes an End island with no End biome,
 * and a name in the tag that has no file is a biome no world can load.
 *
 * <p>The union with the DnT castle pack's own {@code is_end} is <em>not</em> asserted here: that pack
 * is build-produced into another directory and is not part of this module's tree. It does not matter
 * for coverage -- this pack already lists all 27 biomes with {@code "replace": false}, so the union is
 * complete unless a later pack sets {@code "replace": true}, which {@link #ourEndTagMerges()} pins our
 * half of.
 */
class EndBiomeTagTest {
    private static final String END_TAG = "minecraft:is_end";

    @Test
    void everyShippedBiomeIsInTheEndTag() throws IOException {
        Set<String> biomes = shippedBiomes();
        Set<String> tagged = new TreeSet<>(tagValues("is_end"));

        assertEquals(27, biomes.size(), () -> "expected 27 shipped biomes, found: " + biomes);
        assertEquals(biomes, tagged,
                END_TAG + " must name exactly the biomes the pack ships");
    }

    @Test
    void everyEndCityBiomeIsAnEndBiome() throws IOException {
        // has_structure/end_city is what makes a city eligible at all, so it has to sit inside the set
        // the city's own structure is filtered by -- a stray name there is a city that never spawns.
        Set<String> isEnd = new TreeSet<>(tagValues("is_end"));
        Set<String> endCity = new TreeSet<>(tagValues("has_structure/end_city"));

        assertFalse(endCity.isEmpty(), "has_structure/end_city must name the biomes a city may spawn in");
        Set<String> strays = new TreeSet<>(endCity);
        strays.removeAll(isEnd);
        assertEquals(Set.of(), strays, "has_structure/end_city names biomes " + END_TAG + " does not");
    }

    @Test
    void ourEndTagMerges() throws IOException {
        // "replace": false is what keeps this pack additive: the tag is built from every pack that
        // ships one, so our 27 biomes join a DnT or vanilla list instead of erasing it. Flipping this
        // to true would wipe the other packs' entries, which is the one way the union could shrink.
        Map<String, Object> tag = parse(tagFile("is_end"));
        assertFalse(Boolean.TRUE.equals(tag.get("replace")),
                END_TAG + " sets \"replace\": true, which erases every other pack's entries");
    }

    // --- the pack ------------------------------------------------------------------------------

    private static Set<String> shippedBiomes() throws IOException {
        Path dir = datapack().resolve("data/betterend/worldgen/biome");
        try (Stream<Path> walk = Files.list(dir)) {
            Set<String> ids = new TreeSet<>();
            walk.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> ids.add("betterend:" + strip(path.getFileName().toString())));
            return ids;
        }
    }

    private static List<String> tagValues(String name) throws IOException {
        Map<String, Object> tag = parse(tagFile(name));
        return ((List<?>) tag.get("values")).stream().map(String::valueOf).toList();
    }

    private static Path tagFile(String name) {
        return datapack().resolve("data/minecraft/tags/worldgen/biome/" + name + ".json");
    }

    private static String strip(String fileName) {
        return fileName.substring(0, fileName.length() - ".json".length());
    }

    /** JSON is a YAML subset, so the same SnakeYAML the config test uses reads a tag. */
    private static Map<String, Object> parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    /** Same walk-up as {@code BetterEndConfigTest.configYml}: tests may run from either module. */
    private static Path datapack() {
        Path at = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && at != null; up++, at = at.getParent()) {
            Path candidate = at.resolve("plugin/src/main/resources/datapack");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "plugin/src/main/resources/datapack not found from " + Path.of("").toAbsolutePath());
    }
}
