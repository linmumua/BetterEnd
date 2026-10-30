package dev.mumu.betterend.biome;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The discovery half of the bootstrapper: which datapacks it hands the server, and the metadata each
 * one ships. The biome pack lives in this module's sibling (the plugin module) and the loader lives
 * here, so the classic bug is the two drifting apart -- this catches it the way
 * {@code BetterEndConfigTest} catches config.yml drift.
 *
 * <p>The DnT castle pack is produced into {@code plugin/src/main/resources/datapacks/dnt-end-castle}
 * by the build script, so a source-only checkout may not have it yet. The Echoes end-structures pack
 * is committed under {@code plugin/src/main/resources/datapacks/echoes-end-structures} (see
 * {@code scripts/prepare_echoes.py}), so it is always present. The metadata test therefore
 * validates every <em>present</em> declared pack and requires at least the biome pack to be there; it
 * never fails merely because the castle pack is absent. What it can and does prove without the files:
 * the declaration lists distinct, non-nested packs, and the biome pack targets the server's data
 * format.
 */
class BiomeDatapackTest {
    /** The server this plugin targets: MU core / Folia 26.3, whose version.json pins data 121.0. */
    private static final int[] TARGET_FORMAT = {121, 0};

    @Test
    void allBundledPacksAreDeclaredAndDistinct() {
        List<BiomeDatapack.BundledPack> packs = BiomeDatapack.BUNDLED_PACKS;
        assertEquals(3, packs.size(), () -> "the plugin ships exactly three datapacks: " + packs);

        Set<String> ids = new HashSet<>();
        for (BiomeDatapack.BundledPack pack : packs) {
            ids.add(pack.packId());
        }
        assertEquals(packs.size(), ids.size(), () -> "two packs must not share a Paper id: " + ids);

        Set<String> paths = new HashSet<>();
        for (BiomeDatapack.BundledPack pack : packs) {
            paths.add(pack.resourcePath());
        }
        assertEquals(packs.size(), paths.size(), () -> "two packs must not share a jar directory: " + paths);

        // Neither directory may sit inside the other. The trap is the STRING prefix: "/datapack" is a
        // textual prefix of "/datapacks/dnt-end-castle" but not a path prefix, so a naive startsWith()
        // would call these nested -- and a real nesting would go unnoticed by that same naive test.
        for (String outer : paths) {
            for (String inner : paths) {
                assertFalse(!inner.equals(outer) && inner.startsWith(outer + "/"),
                        () -> inner + " is nested inside " + outer + "; one pack would shadow the other");
            }
        }
    }

    /**
     * The Echoes pack is declared like any other: it ships only its own namespace, so nothing here
     * depends on which pack the server resolves last. Its presence in the list is what matters -- a
     * pack left out of {@code BUNDLED_PACKS} is never discovered and its nine structures never
     * generate, with no error anywhere.
     */
    @Test
    void theEchoesStructuresPackIsDeclared() {
        List<String> ids = BiomeDatapack.BUNDLED_PACKS.stream().map(BiomeDatapack.BundledPack::packId).toList();
        assertTrue(ids.contains("echoes-end-structures"),
                () -> "the Echoes structures pack must be discovered, order is: " + ids);
    }

    @Test
    void aMalformedPackDeclarationIsRejectedNotDiscoveredBlindly() {
        // The record validates its own inputs, so a typo in BUNDLED_PACKS fails loudly here instead
        // of at server start as a pack that quietly registered nothing.
        assertThrows(IllegalArgumentException.class,
                () -> new BiomeDatapack.BundledPack("datapack", "biomes"));
        assertThrows(IllegalArgumentException.class,
                () -> new BiomeDatapack.BundledPack("", "biomes"));
        assertThrows(IllegalArgumentException.class,
                () -> new BiomeDatapack.BundledPack("/datapack/", "biomes"));
        assertThrows(IllegalArgumentException.class,
                () -> new BiomeDatapack.BundledPack("/datapack", "Biomes"));
        assertThrows(IllegalArgumentException.class,
                () -> new BiomeDatapack.BundledPack("/datapack", "bi omes"));
        assertThrows(IllegalArgumentException.class,
                () -> new BiomeDatapack.BundledPack("/datapack", ""));
        // and the shape the plugin actually ships is accepted
        assertDoesNotThrow(
                () -> new BiomeDatapack.BundledPack("/datapacks/dnt-end-castle", "dnt-end-castle"));
    }

    @Test
    void everyPresentBundledPackTargetsThe26_3DataFormat() throws IOException {
        Path root = pluginResources();
        int present = 0;
        for (BiomeDatapack.BundledPack pack : BiomeDatapack.BUNDLED_PACKS) {
            Path dir = root.resolve(pack.resourcePath().substring(1)); // drop the leading '/'
            if (!Files.isDirectory(dir)) {
                continue; // e.g. the DnT pack before the build script has run
            }
            present++;
            Path meta = dir.resolve("pack.mcmeta");
            assertTrue(Files.isRegularFile(meta),
                    pack.resourcePath() + " declares a datapack but ships no pack.mcmeta");
            assertTargetsTheServer(parse(meta), pack.packId());
        }
        assertTrue(present > 0, "no declared datapack is on disk; the biome pack must be");
    }

    /**
     * The metadata contract: the 26.3 scheme is {@code min_format}/{@code max_format}, and the server
     * rejects a file that also carries the legacy single {@code pack_format}. Both bounds must admit
     * the data format the server runs.
     */
    private static void assertTargetsTheServer(Map<String, Object> meta, String id) {
        Object node = meta.get("pack");
        assertTrue(node instanceof Map, id + ": pack.mcmeta has no 'pack' object");
        Map<?, ?> pack = (Map<?, ?>) node;

        assertFalse(pack.containsKey("pack_format"),
                id + ": pack_format is the old scheme and must not be mixed with min/max_format");
        assertTrue(pack.containsKey("min_format"), id + ": pack.mcmeta has no min_format");
        assertTrue(pack.containsKey("max_format"), id + ": pack.mcmeta has no max_format");

        int[] min = decode(pack.get("min_format"), id, "min_format");
        int[] max = decode(pack.get("max_format"), id, "max_format");
        assertTrue(compare(min, TARGET_FORMAT) <= 0,
                id + ": min_format " + Arrays.toString(min) + " is above the server format");
        assertTrue(compare(max, TARGET_FORMAT) >= 0,
                id + ": max_format " + Arrays.toString(max) + " is below the server format");
    }

    /** A format is an int ({@code [major, 0]}) or a {@code [major, minor]} pair (PackMetadataSection). */
    private static int[] decode(Object node, String id, String key) {
        if (node instanceof Number number) {
            return new int[]{number.intValue(), 0};
        }
        if (node instanceof List<?> list && list.size() == 2
                && list.get(0) instanceof Number major && list.get(1) instanceof Number minor) {
            return new int[]{major.intValue(), minor.intValue()};
        }
        fail(id + ": " + key + " is neither an int nor a [major, minor] pair: " + node);
        return null;
    }

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }

    /** JSON is a YAML subset, so the same SnakeYAML the tag tests use reads pack.mcmeta. */
    private static Map<String, Object> parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    /** Same walk-up as {@code BetterEndConfigTest.configYml}: tests may run from either module. */
    private static Path pluginResources() {
        Path at = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && at != null; up++, at = at.getParent()) {
            Path candidate = at.resolve("plugin/src/main/resources");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "plugin/src/main/resources not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    void refusesToEnableBeforeADiscoveryPassHasRun() {
        // The plugin must not pretend to be ready when DATAPACK_DISCOVERY never fired. This test JVM
        // has never bootstrapped, which is exactly the "no pass ran" state onEnable must refuse.
        assertNotNull(BiomeDatapack.refusalReason(),
                "a fresh process has no discovery result; onEnable must refuse, not assume success");
    }

    @Test
    void thePackDirectoryIsFoundThroughPackMetaNotTheDirectoryEntry() throws Exception {
        // The jar-directory fix: locate a pack by a file it must contain, so a jar with no directory
        // entry (getResource(dir) == null) still resolves. The fixture is a plain classpath directory
        // here; the jar case needs a real jar and was checked against one out of band.
        URI directory =
                BiomeDatapack.packDirectory(new BiomeDatapack.BundledPack("/packdir-fixture", "fixture"));
        Path path = Path.of(directory);
        assertTrue(Files.isDirectory(path), () -> directory + " did not resolve to a directory");
        assertTrue(Files.isRegularFile(path.resolve("pack.mcmeta")),
                () -> directory + " does not contain pack.mcmeta");
    }

    @Test
    void aPackAbsentFromTheJarFailsWithAnOperatorReadableReason() {
        // Not swallowed into a silent no-op: a missing pack throws, and the message is the Chinese the
        // operator sees in the resulting enable failure.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BiomeDatapack.packDirectory(new BiomeDatapack.BundledPack("/no-such-pack", "gone")));
        assertTrue(error.getMessage().contains("/no-such-pack/pack.mcmeta"), error.getMessage());
    }

    /**
     * The descriptor contract behind the capacity fix: CraftEngine must be declared as BOTH a server and a
     * bootstrap dependency, each loading BEFORE this plugin.
     * <p>
     * The server entry is what orders CraftEngine to enable first and joins its classes for the plugin
     * phase. The bootstrap entry is the one that matters for the bootstrapper: Paper builds the bootstrap
     * phase's dependency graph from the bootstrap section only (BootstrapProviderStorage.createDependencyTree
     * -> BootstrapMetaDependencyTree, whose edges come solely from PaperPluginMeta.getBootstrapDependencies),
     * so without it BiomeDatapack's bootstrapper could not resolve CraftEngine's Config and the capacity
     * write would fail its read-back. {@code load: BEFORE} makes CraftEngine's bootstrapper load its Config
     * before ours runs. A future edit that drops either entry would silently reintroduce the exhaustion,
     * which is exactly what this test refuses to let pass.
     */
    @Test
    void declaresCraftEngineAsBothServerAndBootstrapDependency() throws IOException {
        Path descriptor = pluginResources().resolve("paper-plugin.yml");
        assertTrue(Files.isRegularFile(descriptor), descriptor + " is missing");
        Map<String, Object> root = parse(descriptor);
        assertTrue(root.containsKey("bootstrapper"), "paper-plugin.yml must declare the bootstrapper");

        // SnakeYAML unquotes `true`/`false` by default, so the values deserialize as booleans.
        assertDependency(root, "server", "CraftEngine");
        assertDependency(root, "bootstrap", "CraftEngine");
    }

    private static void assertDependency(Map<String, Object> root, String lifecycle, String name) {
        Object dependencies = root.get("dependencies");
        assertTrue(dependencies instanceof Map, "paper-plugin.yml has no 'dependencies' section");
        Object lifecycles = ((Map<?, ?>) dependencies).get(lifecycle);
        assertTrue(lifecycles instanceof Map, "paper-plugin.yml has no '" + lifecycle + "' dependency section");
        Object entry = ((Map<?, ?>) lifecycles).get(name);
        assertTrue(entry instanceof Map, lifecycle + " does not declare the " + name + " dependency");
        Map<?, ?> dependency = (Map<?, ?>) entry;
        assertEquals("BEFORE", dependency.get("load"),
                lifecycle + "." + name + " must load BEFORE this plugin");
        assertEquals(Boolean.TRUE, dependency.get("required"),
                lifecycle + "." + name + " must be required");
        assertEquals(Boolean.TRUE, dependency.get("join-classpath"),
                lifecycle + "." + name + " must join the classpath");
    }
}
