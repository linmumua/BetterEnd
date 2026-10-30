package dev.mumu.betterend.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code surface_spreading_block} removal -- a stop-bleed fix, not an optimisation.
 * <p>
 * WHY the behaviour had to go: its {@code randomTick} reaches {@code canPropagate}
 * ({@code SurfaceSpreadingBlockBehavior.java:114}, CE commit
 * {@code b3079c5ac170fff2cb2a4c4dad184771a236cd1b}), which calls the index-named bridge
 * {@code FluidStateProxy.INSTANCE.is$0(fluidState, FluidTagsProxy.WATER)}. Folia/Paper 26.3's
 * {@code FluidState} gained five {@code is(...)} overloads (TagKey / HolderSet / Fluid / Holder /
 * ResourceKey -- the {@code TypedInstance} interface, MU核心 {@code FluidState.java:164-184}), and
 * CE's {@code is$0} binds to the {@code is(Fluid)} one, so a water {@code TagKey} is cast to
 * {@code Fluid} and every random tick throws {@code ClassCastException: TagKey cannot be cast to
 * Fluid}. The behaviour is also ONE-WAY harmful: its decay branch (source 56-58, writes
 * {@code base_block} = {@code minecraft:end_stone}) works, while its spread branch (source 64-87)
 * always dies in {@code canPropagate} -- so moss could only ever be eaten away, never grow back.
 * <p>
 * WHAT this pins, and why each part can actually fail:
 * <ul>
 *   <li>no bundled config declares the behaviour again (a regenerated/merged pack file that
 *       reintroduces it puts the exception flood straight back);</li>
 *   <li>the 15 covered blocks and their 11 untouched {@code *_path} siblings are all still defined,
 *       with the same {@code auto_state}/{@code loot}/settings -- a botched edit that deletes a block
 *       or its appearance would otherwise only show up as a missing block at runtime;</li>
 *   <li>{@code is_randomly_ticking: true} stays ON, deliberately: with the behaviour gone the block
 *       no longer implements {@code RandomTickBlock}, so CE falls back to
 *       {@code BlockSettings.isRandomlyTicking()} and schedules vanilla random ticks whose handler is
 *       the base {@code BlockBehavior.randomTick} -- a no-op {@code return} (javap), dispatched by
 *       {@code BlockGenerator$RandomTickInterceptor} inside {@code try/catch(Throwable)} that only
 *       logs "Failed to run randomTick". So it is a harmless idle pass, and keeping it means
 *       restoring the behaviour upstream needs no settings change.</li>
 * </ul>
 */
class SurfaceSpreadingContractTest {
    private static final String BEHAVIOR = "surface_spreading_block";

    /** The per-source banner the merge left above the {@code blocks:} entries of the old terrain file. */
    private static final String TERRAIN_SOURCE = "B4-flora-terrain.yml";

    /** The 15 blocks that carried the behaviour: moss / nylium / "pallidium" ground covers. */
    private static final List<String> COVERED = List.of(
            "amber_moss", "cave_moss", "chorus_nylium", "crystal_moss", "end_moss", "end_mycelium",
            "jungle_moss", "pink_moss", "rutiscus", "sangnum", "shadow_grass",
            "pallidium_full", "pallidium_heavy", "pallidium_thin", "pallidium_tiny");

    /** The 11 dirt-path variants in the same file -- never behaviour-bearing, must survive untouched. */
    private static final List<String> PATHS = List.of(
            "amber_moss_path", "cave_moss_path", "chorus_nylium_path", "crystal_moss_path",
            "end_moss_path", "end_mycelium_path", "jungle_moss_path", "pink_moss_path",
            "rutiscus_path", "sangnum_path", "shadow_grass_path");

    @Test
    void noBundledConfigDeclaresTheSurfaceSpreadingBehavior() throws IOException {
        List<Path> files = configurationFiles();
        assertTrue(files.size() > 20, "only " + files.size() + " config files found -- wrong root?");
        // Parsed, not grepped: the removal is documented in comments that legitimately contain the
        // string, and a text scan would either false-fail on them or miss a non-obvious YAML shape.
        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            collectBehaviorTypes(load(file), file.getFileName().toString(), offenders);
        }
        assertEquals(List.of(), offenders,
                "surface_spreading_block throws ClassCastException on every random tick (CE is$0 resolves"
                        + " to FluidState.is(Fluid)); it must not be declared anywhere in the pack");
    }

    @Test
    void everyCoveredBlockKeepsItsAppearanceAndThePathSiblingsSurvive() throws IOException {
        Map<String, Object> blocks = terrainBlocks();
        Set<String> expected = new TreeSet<>();
        COVERED.forEach(id -> expected.add("betterend:" + id));
        PATHS.forEach(id -> expected.add("betterend:" + id));
        assertEquals(expected, new TreeSet<>(blocks.keySet()),
                "the removal must delete only behavior: sections -- no block (moss or *_path) may vanish");

        for (String id : COVERED) {
            Map<String, Object> block = asMap(blocks.get("betterend:" + id));
            assertFalse(block.containsKey("behavior"),
                    id + " must not carry a behavior: section: an empty list is legal for CE (it falls"
                            + " back to BukkitBlockBehavior), but leaving the key behind invites the"
                            + " behaviour to be re-added by a careless merge");
            Map<String, Object> state = asMap(block.get("state"));
            assertEquals("solid", state.get("auto_state"), id + " must stay a solid auto_state block");
            assertFalse(asList(state.get("models")).isEmpty(), id + " must keep its models");
            assertEquals("default:loot_table/self", asMap(block.get("loot")).get("template"),
                    id + " must keep dropping itself");
            Map<String, Object> merges = asMap(asMap(block.get("settings")).get("merges"));
            assertEquals(Double.valueOf(0.5), merges.get("hardness"), id + " hardness must not change");
            assertEquals(Double.valueOf(0.5), merges.get("resistance"), id + " resistance must not change");
            assertEquals("plant", merges.get("map_color"), id + " map_color must not change");
            assertTrue(asList(merges.get("tags")).contains("minecraft:mineable/pickaxe"),
                    id + " must stay pickaxe-mineable");
        }
    }

    @Test
    void randomlyTickingStaysOnBecauseTheLostHandlerIsAHarmlessNoOp() throws IOException {
        Map<String, Object> blocks = terrainBlocks();
        for (String id : COVERED) {
            Map<String, Object> settings = asMap(asMap(blocks.get("betterend:" + id)).get("settings"));
            Map<String, Object> merges = asMap(settings.get("merges"));
            assertEquals(Boolean.TRUE, merges.get("is_randomly_ticking"),
                    id + ": is_randomly_ticking is kept ON on purpose. Without a RandomTickBlock"
                            + " behavior CE reads this setting (BukkitBlockManager's random-tick"
                            + " registration) and the dispatched handler is the no-op base"
                            + " BlockBehavior.randomTick, so the tick is idle, not an error. Turning it"
                            + " off would mean re-editing every block again when upstream fixes is$0");
        }
    }

    /** Walks a parsed document for any string equal to the behaviour type, wherever it sits. */
    private static void collectBehaviorTypes(Object node, String where, List<String> offenders) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectBehaviorTypes(entry.getValue(), where + " -> " + entry.getKey(), offenders);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectBehaviorTypes(list.get(i), where + "[" + i + "]", offenders);
            }
        } else if (BEHAVIOR.equals(node)) {
            offenders.add(where);
        }
    }

    private static Map<String, Object> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> tree = new Yaml().load(reader);
            assertNotNull(tree, "unexpected empty document: " + file);
            return tree;
        }
    }

    /**
     * The single merged block file -- {@code B4-flora-terrain.yml} no longer exists, its entries were
     * folded into this one.
     */
    private static Path terrainFile() {
        return locate("plugin/src/main/resources/craftengine/betterend/configuration/blocks")
                .resolve("blocks.yml");
    }

    /**
     * The {@code blocks:} entries that came from the retired {@code B4-flora-terrain.yml}, read back
     * out of the merged {@code blocks.yml}.
     * <p>
     * The merge kept a per-source banner ({@code # --------- 来源：B4-flora-terrain.yml ---------})
     * above each group, so the old file's exact membership is still recoverable and the set-equality
     * assertion below keeps its original teeth. Filtering by "does the id look like a moss" instead
     * would have made that assertion compare the list against itself.
     */
    private static Map<String, Object> terrainBlocks() throws IOException {
        Path file = terrainFile();
        Map<String, Object> all = asMap(load(file).get("blocks"));
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String id : terrainSectionIds(file)) {
            Object block = all.get(id);
            assertNotNull(block, id + " sits under the " + TERRAIN_SOURCE + " banner in " + file
                    + " but has no entry in the top-level blocks: mapping");
            picked.put(id, block);
        }
        return picked;
    }

    /** Top-level {@code blocks:} keys lying under the {@code B4-flora-terrain.yml} section banner. */
    private static Set<String> terrainSectionIds(Path file) throws IOException {
        Set<String> ids = new TreeSet<>();
        boolean inBlocks = false;
        boolean inSection = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.equals("blocks:")) {
                inBlocks = true;
                continue;
            }
            if (!inBlocks || line.isBlank()) continue;
            String stripped = line.stripLeading();
            if (stripped.startsWith("#")) {
                // Comment at any indent -- inside blocks: the merge also left column-0 prose here.
                // Only a "来源：" banner starts a section, so the prose cannot open one by accident.
                if (stripped.contains("来源：")) inSection = stripped.contains(TERRAIN_SOURCE);
                continue;
            }
            if (line.startsWith("   ")) continue;        // nested content of the current entry
            if (!line.startsWith("  ")) {                // a top-level sibling ends the blocks: map
                inBlocks = false;
                continue;
            }
            if (inSection) {
                String key = line.trim();
                ids.add(key.substring(0, key.length() - 1));  // drop the trailing ':'
            }
        }
        return ids;
    }

    /** Every YAML under the pack's whole configuration tree, not just blocks/. */
    private static List<Path> configurationFiles() throws IOException {
        Path root = locate("plugin/src/main/resources/craftengine/betterend/configuration");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    /** Walk up from the working dir until the repo-relative path is underfoot (tests run per module). */
    private static Path locate(String relative) {
        Path at = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && at != null; i++, at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }
}
