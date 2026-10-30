package dev.mumu.betterend.biome;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The mob-spawn shape each shipped biome declares. 26.3 replaced the old flat {@code minCount} /
 * {@code maxCount} pair with a single {@code count} {@code IntProvider}
 * ({@code MobSpawnSettings.SpawnerData(EntityType, IntProvider)}, {@code weight} staying flat beside
 * them), so a biome still on the old keys does not decode and its world loses every spawn setting.
 *
 * <p>The migration maps the old pair exactly as {@code MobSpawnSettings.Builder.addSpawn} did --
 * equal min and max become a bare constant, anything else a {@code minecraft:uniform} -- and this
 * test pins the result, not the source: a biome added later on the old keys fails here.
 */
class BiomeSpawnerCodecTest {
    /** The only keys a SpawnerData entry may carry once {@code weight} is folded in beside it. */
    private static final Set<String> ENTRY_KEYS = Set.of("type", "count", "weight");

    @Test
    void everySpawnerUsesThe26_3CountProvider() throws IOException {
        int entries = 0;
        for (Path file : biomeFiles()) {
            Object spawners = parse(file).get("spawners");
            if (!(spawners instanceof Map<?, ?> categories)) {
                continue;
            }
            for (Map.Entry<?, ?> category : categories.entrySet()) {
                for (Object raw : (List<?>) category.getValue()) {
                    Map<?, ?> entry = (Map<?, ?>) raw;
                    entries++;
                    assertEquals(ENTRY_KEYS, entry.keySet(),
                            file.getFileName() + " " + category.getKey() + ": a SpawnerData entry"
                                    + " must carry exactly type/count/weight, not minCount/maxCount");
                    assertCountIsAnIntProvider(entry.get("count"), file, category.getKey());
                }
            }
        }
        assertTrue(entries >= 20, "only " + entries + " spawner entries parsed; the pack is degenerate");
    }

    private static void assertCountIsAnIntProvider(Object count, Path file, Object category) {
        if (count instanceof Number number) {
            assertTrue(number.intValue() >= 0, file + " " + category + ": negative constant count");
            return;
        }
        if (count instanceof Map<?, ?> provider) {
            assertEquals("minecraft:uniform", provider.get("type"),
                    file + " " + category + ": the only provider these biomes need is uniform");
            int min = ((Number) provider.get("min_inclusive")).intValue();
            int max = ((Number) provider.get("max_inclusive")).intValue();
            assertTrue(min <= max, file + " " + category + ": min_inclusive above max_inclusive");
            return;
        }
        fail(file + " " + category + ": count is neither an int nor an IntProvider: " + count);
    }

    // --- the pack --------------------------------------------------------------------------------

    private static List<Path> biomeFiles() throws IOException {
        try (Stream<Path> walk = Files.list(biomeDir())) {
            List<Path> files = new ArrayList<>();
            walk.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(files::add);
            return files;
        }
    }

    /** JSON is a YAML subset, so the same SnakeYAML the tag tests use reads a biome. */
    private static Map<String, Object> parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    /** Same walk-up as {@code BetterEndConfigTest.configYml}: tests may run from either module. */
    private static Path biomeDir() {
        Path at = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && at != null; up++, at = at.getParent()) {
            Path candidate = at.resolve("plugin/src/main/resources/datapack/data/betterend/worldgen/biome");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("biome dir not found from " + Path.of("").toAbsolutePath());
    }
}
