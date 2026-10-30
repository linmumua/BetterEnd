package dev.mumu.betterend.echoes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the Echoes of the End (Structures) pack, produced by
 * {@code scripts/prepare_echoes.py} into
 * {@code plugin/src/main/resources/datapacks/echoes-end-structures}.
 *
 * <p>The pack is three independent things that each fail silently at runtime, so each gets a test:
 * a jigsaw structure whose {@code biomes} cannot match would never place; a structure_set whose
 * spacing/salt collide would stack all nine structures into one cell; and a template_pool pointing at
 * a missing NBT would log a dangling reference per placement. None of these produce an error the
 * server would surface, which is why they are pinned here.
 *
 * <p>Two namespaces, on purpose. The <em>public</em> ids ({@code mumu:end_well} -- what a player
 * types after {@code /locate}) live in {@link #PUBLIC_NAMESPACE}; the template pools and the NBT
 * payloads stay in the source's {@link #TEMPLATE_NAMESPACE}, because moving a pool means moving the
 * NBT and the NBT must never be re-serialised. The same nine documents are shipped a second time
 * under the legacy ids, with <em>no</em> structure_set: a saved chunk stores its structure starts
 * under the structure id string, so dropping the old ids would strand every world that already
 * generated one. Readable, never placeable -- see {@link #legacyIdsOwnNoPlacement()}.
 */
class EchoesPackContractTest {
    private static final String PACK = "datapacks/echoes-end-structures/";

    /**
     * The namespace the template pools and the structure NBT payloads stay in: the source's, kept
     * verbatim. Moving a pool would mean moving the NBT, and the NBT must never be re-serialised.
     */
    private static final String TEMPLATE_NAMESPACE = "echoes_of_the_end__structures_";

    /** The namespace the public structure ids live in -- the ids a player types after /locate. */
    private static final String PUBLIC_NAMESPACE = "mumu";

    /** The nine structures the source ships; pinned so a source change cannot slip past the tests. */
    private static final List<String> STRUCTURES = List.of(
            "crying_obsidian_tower", "end_camp", "end_dungeon_tower", "end_enchanted_temple",
            "end_pillar", "end_shulker_tower", "end_temple", "end_warship", "end_well");

    /** The one structure whose NBT name differs from its structure/template_pool name. */
    private static final String WARSHIP_NBT = "end_warship1";

    /** The placement every structure was unified to (see the script's arithmetic). */
    private static final int SPACING = 160;
    private static final int SEPARATION = 16;

    /**
     * The one structure thinned out on purpose.
     *
     * <p>{@code random_spread} accepts a candidate with this probability, so the eight structures
     * that leave {@code frequency} unset keep the default 1.0 and stay as dense as each other, while
     * the tower becomes five times rarer than any of them -- the whole point of the exception. The
     * value is a ratio, not a location: {@code spacing}, {@code separation} and every salt are
     * untouched, so the grid cells (and therefore everything a saved world already generated) do not
     * move.
     */
    private static final String RAREST_STRUCTURE = "end_dungeon_tower";
    private static final double RAREST_FREQUENCY = 0.1;

    /**
     * The nine salts, taken from the source jar and pinned here.
     * <p>
     * The ids moved to {@code mumu} but the placement must not: {@code random_spread} picks its
     * candidate per cell from (salt, cell), so a changed salt relocates every structure in a world
     * that was generated before the rename -- fresh terrain in the old grid cells, but a second,
     * detached copy of a structure that already exists elsewhere. Pinning the numbers is the only
     * check that catches a regeneration that quietly re-salted.
     */
    private static final Map<String, Integer> SOURCE_SALTS = Map.of(
            "crying_obsidian_tower", 1275595292,
            "end_camp", 910165693,
            "end_dungeon_tower", 1054309675,
            "end_enchanted_temple", 479948289,
            "end_pillar", 1068651703,
            "end_shulker_tower", 1322633544,
            "end_temple", 1143842385,
            "end_warship", 1574654080,
            "end_well", 250943603);

    /** The biome tag the nine were repointed at; the whole point of the migration. */
    private static final String BIOME_TAG = "#minecraft:is_end";

    /** The authoring marker every pool must ignore, and the only value allowed in blocks[]. */
    private static final String STRUCTURE_BLOCK = "minecraft:structure_block";

    // --- files present and parseable -----------------------------------------------------------

    @Test
    void everyStructureShipsAllFourFileKinds() {
        for (String name : STRUCTURES) {
            assertNotNull(read(NBT(name)), name + ".nbt is missing");
            assertNotNull(read(structure(name)), name + " worldgen/structure is missing");
            assertNotNull(read(structureSet(name)), name + " worldgen/structure_set is missing");
            assertNotNull(read(templatePool(name)), name + " worldgen/template_pool is missing");
            // The legacy id is a fifth file, not a fourth: the public one above is what places.
            assertNotNull(read(legacyStructure(name)),
                    name + " legacy worldgen/structure is missing; saved chunks key their structure"
                            + " starts by id and would log an unknown start");
        }
    }

    @Test
    void everyJsonDocumentParsesAndEveryNbtIsACompressedCompound() throws IOException {
        for (String name : STRUCTURES) {
            // A document Gson rejects is a worldgen registry error at load time.
            json(structure(name));
            json(legacyStructure(name));
            json(structureSet(name));
            json(templatePool(name));
            assertNbtRootIsCompound(NBT(name));
        }
    }

    @Test
    void noNbtReferencesAThirdPartyLootTable() throws IOException {
        // Chest/barrel content lives inside the NBT, not in a loot_table file, so a stale reference
        // would only show up as a server-side error at placement time. Every authored table must be
        // vanilla (the source uses chests/end_city_treasure and chests/simple_dungeon).
        int checked = 0;
        for (String name : STRUCTURES) {
            String payload = new String(gunzip(NBT(name)), StandardCharsets.ISO_8859_1);
            Set<String> tables = lootTablesIn(payload);
            checked += tables.size();
            for (String table : tables) {
                assertTrue(table.startsWith("minecraft:"),
                        name + " references a non-vanilla loot table: " + table);
            }
            assertFalse(payload.contains(TEMPLATE_NAMESPACE),
                    name + " must not reference the echoes namespace anywhere inside the NBT");
            assertFalse(payload.contains(PUBLIC_NAMESPACE + ":"),
                    name + " must not reference the public namespace inside the NBT: the ids moved,"
                            + " the payload did not");
        }
        // Without this the test would also pass if the scan stopped matching anything at all.
        assertTrue(checked > 0, "no loot table was found in any structure; the scan is broken");
    }

    @Test
    void packMetaIsThe26_3Format() {
        JsonObject pack = json("pack.mcmeta").getAsJsonObject("pack");
        assertEquals(List.of(121, 0), ints(pack.get("min_format")), "min_format");
        assertEquals(List.of(121, 0), ints(pack.get("max_format")), "max_format");
        assertFalse(pack.has("pack_format"), "pack_format is the pre-26.x scheme");
    }

    // --- the biome fix --------------------------------------------------------------------------

    @Test
    void noStructureStillHardcodesTheVanillaEndBiomes() {
        // The source listed end_barrens/end_highlands/end_midlands. BetterEnd's generator paints the
        // 27 betterend:* biomes and vanilla's three essentially never appear, so a hardcoded list
        // means the structure is registered and never placed -- the exact failure this migration fixes.
        Set<String> vanillaOnly = Set.of(
                "minecraft:end_barrens", "minecraft:end_highlands", "minecraft:end_midlands");
        for (String name : STRUCTURES) {
            for (String path : List.of(structure(name), legacyStructure(name))) {
                JsonElement biomes = json(path).get("biomes");
                assertNotNull(biomes, path + " has no biomes field");
                assertTrue(biomes.isJsonPrimitive(), path + " must reference a tag, not a biome list");
                assertEquals(BIOME_TAG, biomes.getAsString(), path + " must use " + BIOME_TAG);
                assertFalse(vanillaOnly.contains(biomes.getAsString()),
                        path + " still names a vanilla End biome");
            }
        }
    }

    // --- the id move ----------------------------------------------------------------------------

    /**
     * The public document and the legacy document are the same structure; only the id differs, and
     * the id is the file path.
     * <p>
     * Both must point at the source's template pool: a {@code start_pool} rewritten to the public
     * namespace would reference a pool that is not shipped (pools stay in
     * {@link #TEMPLATE_NAMESPACE}), and an unresolvable {@code start_pool} is an unbound worldgen
     * value -- the server refuses to start. Asserting byte equality of the two documents is the
     * cheapest way to prove no field drifted between them.
     */
    @Test
    void bothNamespacesDefineTheSameStructureAndKeepTheSourceTemplatePool() {
        for (String name : STRUCTURES) {
            JsonObject publicDocument = json(structure(name));
            JsonObject legacyDocument = json(legacyStructure(name));
            assertEquals(legacyDocument, publicDocument,
                    name + ": the public and legacy documents must differ only in their id,"
                            + " which is the directory they sit in");
            assertEquals(TEMPLATE_NAMESPACE + ":" + name,
                    publicDocument.get("start_pool").getAsString(),
                    name + " must start from the source's template pool, not a renamed one");
        }
    }

    /** The id a world records in a saved chunk must resolve, and must not place anything new. */
    @Test
    void legacyIdsOwnNoPlacement() {
        for (String name : STRUCTURES) {
            // A legacy structure_set would place a second copy of a structure that already exists.
            assertNull(read("data/" + TEMPLATE_NAMESPACE + "/worldgen/structure_set/" + name + ".json"),
                    name + " must not have a legacy structure_set: the public id is the one that"
                            + " places, and two sets for one structure double every placement");
        }
    }

    @Test
    void theBiomeTagWeReferenceReallyNamesTheShippedBiomes() throws IOException {
        // #minecraft:is_end only works here because the biome pack adds all 27 betterend:* biomes to
        // it. Proves the two packs still agree without duplicating the list into the echoes pack.
        JsonArray values = jsonAt("datapack/data/minecraft/tags/worldgen/biome/is_end.json")
                .getAsJsonArray("values");
        Set<String> tagged = new TreeSet<>();
        for (JsonElement value : values) {
            tagged.add(value.getAsString());
        }
        Set<String> shipped = new TreeSet<>();
        try (Stream<Path> walk = Files.list(pluginResources()
                .resolve("datapack/data/betterend/worldgen/biome"))) {
            walk.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> shipped.add("betterend:" + path.getFileName().toString()
                            .replace(".json", "")));
        }
        assertEquals(27, shipped.size(), () -> "expected 27 shipped biomes, found " + shipped);
        assertEquals(shipped, tagged, BIOME_TAG + " must name exactly the biomes BetterEnd ships");
    }

    // --- the placement fix ---------------------------------------------------------------------

    @Test
    void everyStructureUsesTheSameSpacingAndSeparation() {
        // "All nine equally likely" is only true if the grids are the same size; the source ranged
        // 120..220 with separations 2..29, i.e. wildly different odds per structure. The one
        // deliberate exception is the tower's frequency, asserted below -- it changes how often a
        // cell is accepted, never where the cells are.
        for (String name : STRUCTURES) {
            JsonObject placement = json(structureSet(name)).getAsJsonObject("placement");
            assertEquals("minecraft:random_spread", placement.get("type").getAsString(),
                    name + " must stay on random_spread");
            assertEquals(SPACING, placement.get("spacing").getAsInt(), name + " spacing");
            assertEquals(SEPARATION, placement.get("separation").getAsInt(), name + " separation");
            if (RAREST_STRUCTURE.equals(name)) {
                assertEquals(RAREST_FREQUENCY, placement.get("frequency").getAsDouble(), 1e-9,
                        name + " must be the rarest structure, by the pinned ratio");
            } else {
                assertFalse(placement.has("frequency"),
                        name + " must keep the default frequency 1.0, or its odds stop matching the others");
            }
        }
    }

    @Test
    void exactlyOneStructureIsThinnedOut() {
        // Guards both halves: a second frequency would silently thin a structure nobody asked for,
        // and dropping the tower's would silently make it as common as the rest again.
        List<String> thinned = new ArrayList<>();
        for (String name : STRUCTURES) {
            if (json(structureSet(name)).getAsJsonObject("placement").has("frequency")) {
                thinned.add(name);
            }
        }
        assertEquals(List.of(RAREST_STRUCTURE), thinned);
    }

    @Test
    void separationStaysBelowSpacing() {
        // random_spread puts the candidate at an offset inside [0, spacing - separation); if the two
        // are equal the window is empty and the placement degenerates instead of spreading.
        for (String name : STRUCTURES) {
            JsonObject placement = json(structureSet(name)).getAsJsonObject("placement");
            assertTrue(placement.get("separation").getAsInt() < placement.get("spacing").getAsInt(),
                    name + ": separation must be strictly below spacing");
        }
    }

    @Test
    void everyStructureHasItsOwnSalt() {
        // Nine identical spacing grids are fine only because the salt decorrelates them; a shared salt
        // would drop two structures into the same cell of the same grid forever.
        Set<Integer> salts = new LinkedHashSet<>();
        for (String name : STRUCTURES) {
            salts.add(json(structureSet(name)).getAsJsonObject("placement").get("salt").getAsInt());
        }
        assertEquals(STRUCTURES.size(), salts.size(), () -> "duplicate salts: " + salts);
    }

    /** The rename must not move any structure: every salt is still the source's own. */
    @Test
    void everySaltIsStillTheSourceSalt() {
        for (String name : STRUCTURES) {
            int salt = json(structureSet(name)).getAsJsonObject("placement").get("salt").getAsInt();
            assertEquals((int) SOURCE_SALTS.get(name), salt,
                    name + " changed salt: the id moved, the placement must not -- a new salt"
                            + " relocates the structure in worlds generated before the rename");
        }
    }

    // --- reference closure ---------------------------------------------------------------------

    @Test
    void everyTemplatePoolLocationResolvesToAShippedNbt() {
        for (String name : STRUCTURES) {
            JsonArray elements = json(templatePool(name)).getAsJsonArray("elements");
            assertFalse(elements.isEmpty(), name + " template pool has no elements");
            List<String> referenced = new ArrayList<>();
            for (JsonElement element : elements) {
                JsonObject body = element.getAsJsonObject().getAsJsonObject("element");
                String location = body.get("location").getAsString();
                referenced.add(location);
                assertEquals(TEMPLATE_NAMESPACE + ":" + nbtName(name), location,
                        name + " must point at its own template");
                assertEquals("rigid", body.get("projection").getAsString(),
                        location + " must stay rigid; terrain_fitting projection is not used here");
                assertNotNull(bytes("data/" + TEMPLATE_NAMESPACE + "/structure/"
                                + location.split(":", 2)[1] + ".nbt"),
                        name + " references a template that is not shipped: " + location);
            }
            assertEquals(1, referenced.size(), name + " must reference exactly one template");
        }
    }

    /**
     * The only placement in the pack references the public id.
     * <p>
     * This is the other half of {@code everyTemplatePoolLocationResolvesToAShippedNbt}: the pools
     * stayed in the source namespace while the ids a player sees moved to {@code mumu}, so a set
     * still naming {@code echoes_of_the_end__structures_:<name>} would register a structure no one
     * can locate and place nothing at all -- silently, since an unused registration is not an error.
     */
    @Test
    void everyStructureSetReferencesThePublicId() {
        int checked = 0;
        for (String name : STRUCTURES) {
            JsonArray entries = json(structureSet(name)).getAsJsonArray("structures");
            assertEquals(1, entries.size(), name + " must place exactly one structure");
            JsonObject entry = entries.get(0).getAsJsonObject();
            assertEquals(PUBLIC_NAMESPACE + ":" + name, entry.get("structure").getAsString(),
                    name + " structure_set must reference the public id");
            assertEquals(1, entry.get("weight").getAsInt(), name + " weight");
            checked++;
        }
        assertEquals(STRUCTURES.size(), checked, "every structure_set must be scanned");
    }

    @Test
    void everyTemplatePoolIgnoresTheAuthoringStructureBlocks() {
        // The authoring structure_block marker would otherwise be pasted into the world.
        for (String name : STRUCTURES) {
            JsonArray processors = json(templatePool(name))
                    .getAsJsonArray("elements").get(0).getAsJsonObject()
                    .getAsJsonObject("element").getAsJsonObject("processors")
                    .getAsJsonArray("processors");
            boolean ignoresStructureBlock = false;
            for (JsonElement processor : processors) {
                JsonObject body = processor.getAsJsonObject();
                if (!"minecraft:block_ignore".equals(body.get("processor_type").getAsString())) {
                    continue;
                }
                for (JsonElement block : body.getAsJsonArray("blocks")) {
                    // A plain resource id, NOT {"Name": ...}: see
                    // everyBlockIgnoreBlockIsA263BlockStateId.
                    if (block.isJsonPrimitive()
                            && STRUCTURE_BLOCK.equals(block.getAsString())) {
                        ignoresStructureBlock = true;
                    }
                }
            }
            assertTrue(ignoresStructureBlock, name + " must ignore " + STRUCTURE_BLOCK);
        }
    }

    /**
     * No shipped document may use the pre-1.20 block-state palette spelling.
     * <p>
     * WHY this is a hard failure and not a style nit: 26.3's {@code BlockState.CODEC} is
     * {@code either(BLOCK.byNameCodec(), FULL_CODEC)} — a plain string id, or
     * {@code {"id": ..., "properties": ...}} ({@code StateHolder.codec}'s
     * {@code dispatch("id", ...)}). The legacy {@code {"Name": ...}} shape is neither, so a single
     * occurrence inside {@code block_ignore.blocks} fails the whole
     * {@code minecraft:worldgen/template_pool} registry entry and startup aborts with
     * {@code Unbound values in registry}. 0.1.6 shipped exactly that and would not boot.
     * <p>
     * Scanned structurally over the parsed documents (the pool bodies, the structure documents and
     * the structure_set documents) rather than grepped: the structure {@code .nbt} payloads key
     * their block palettes with {@code Name}/{@code Properties} and MUST keep them, so a text scan
     * over the pack would be both wrong and noisy. A {@code Name} key inside a worldgen JSON,
     * however, is always this bug.
     */
    @Test
    void noWorldgenDocumentUsesTheLegacyBlockStateSpelling() {
        int scanned = 0;
        List<String> offenders = new ArrayList<>();
        for (String name : STRUCTURES) {
            // Four documents per structure: the pool (where the defect lived), the public structure,
            // the legacy structure and the public structure_set. The legacy copy is scanned too --
            // it is a worldgen document like any other, and "it is only for old saves" is not a
            // reason to ship a spelling that aborts startup.
            for (String path : List.of(templatePool(name), structure(name),
                    legacyStructure(name), structureSet(name))) {
                scanned++;
                collectLegacyBlockStateKeys(json(path), path, "", offenders);
            }
        }
        // Without this the assertion would also pass if the walk stopped matching anything.
        assertEquals(STRUCTURES.size() * 4, scanned, "every worldgen document must be scanned");
        assertEquals(List.of(), offenders,
                "legacy {\"Name\": ...} / {\"Properties\": ...} block-state spelling found in 26.3"
                        + " worldgen JSON: it fails BlockState.CODEC and aborts startup");
    }

    /** Proves the walk above can actually fail: a legacy document must be reported. */
    @Test
    void theLegacyBlockStateScanIsFalsifiable() {
        JsonObject legacy = JsonParser.parseString(
                "{\"elements\":[{\"weight\":1,\"element\":{\"element_type\":"
                        + "\"minecraft:single_pool_element\",\"processors\":{\"processors\":"
                        + "[{\"processor_type\":\"minecraft:block_ignore\",\"blocks\":"
                        + "[{\"Name\":\"minecraft:structure_block\"}]}]}}}]}").getAsJsonObject();
        List<String> offenders = new ArrayList<>();
        collectLegacyBlockStateKeys(legacy, "fixture", "", offenders);
        assertEquals(List.of("fixture -> elements[0] -> element -> processors -> processors[0]"
                        + " -> blocks[0] -> Name"), offenders,
                "the legacy-spelling scan must report a {\"Name\": ...} block state; if it does not,"
                        + " noWorldgenDocumentUsesTheLegacyBlockStateSpelling is vacuous");

        List<String> propertiesOffenders = new ArrayList<>();
        collectLegacyBlockStateKeys(JsonParser.parseString(
                        "{\"blocks\":[{\"Name\":\"minecraft:oak_log\","
                                + "\"Properties\":{\"axis\":\"y\"}}]}").getAsJsonObject(),
                "fixture2", "", propertiesOffenders);
        assertEquals(2, propertiesOffenders.size(),
                "both Name and Properties must be reported, got " + propertiesOffenders);
    }

    /**
     * Every {@code block_ignore.blocks} element must be a 26.3 block-state id.
     * <p>
     * This is the narrow assertion for the field that actually broke. Plain strings are required
     * (not merely accepted): a default-state block is exactly what the string form expresses, and
     * accepting an object here would let the legacy shape creep back through a "looks structured"
     * review. The exact value is pinned because the marker is {@code minecraft:structure_block} and
     * nothing else — a typo would silently paste the authoring marker into the world.
     */
    @Test
    void everyBlockIgnoreBlockIsA263BlockStateId() {
        int checked = 0;
        for (String name : STRUCTURES) {
            JsonArray processors = json(templatePool(name))
                    .getAsJsonArray("elements").get(0).getAsJsonObject()
                    .getAsJsonObject("element").getAsJsonObject("processors")
                    .getAsJsonArray("processors");
            for (JsonElement processor : processors) {
                JsonObject body = processor.getAsJsonObject();
                if (!"minecraft:block_ignore".equals(body.get("processor_type").getAsString())) {
                    continue;
                }
                JsonArray blocks = body.getAsJsonArray("blocks");
                assertFalse(blocks.isEmpty(), name + " block_ignore has an empty blocks list");
                for (JsonElement block : blocks) {
                    assertTrue(block.isJsonPrimitive() && block.getAsJsonPrimitive().isString(),
                            name + " block_ignore.blocks entry must be a plain string id, found "
                                    + block + " (both BlockState.CODEC branches reject an object"
                                    + " without an 'id' key)");
                    assertEquals(STRUCTURE_BLOCK, block.getAsString(),
                            name + " must ignore exactly " + STRUCTURE_BLOCK);
                    checked++;
                }
            }
        }
        assertEquals(STRUCTURES.size(), checked,
                "expected one ignored block state per structure, checked " + checked);
    }

    /** Walk a parsed document, recording the path of every legacy block-state key. */
    private static void collectLegacyBlockStateKeys(
            Object node, String where, String path, List<String> offenders) {
        if (node instanceof JsonObject object) {
            for (String key : List.of("Name", "Properties")) {
                if (object.has(key)) {
                    offenders.add(where + " -> " + (path.isEmpty() ? key : path + " -> " + key));
                }
            }
            for (String key : object.keySet()) {
                collectLegacyBlockStateKeys(object.get(key), where,
                        path.isEmpty() ? key : path + " -> " + key, offenders);
            }
        } else if (node instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                collectLegacyBlockStateKeys(array.get(i), where, path + "[" + i + "]", offenders);
            }
        }
    }

    // --- what must not be shipped --------------------------------------------------------------

    @Test
    void theEmptyMcreatorBiomeTagsAreNotShipped() {
        // values:[] with replace:false is a no-op; shipping it only invites the reading that this pack
        // edits vanilla tags. It is MCreator output, not content.
        for (String tag : List.of("is_beach", "is_jungle", "is_taiga")) {
            assertNull(read("data/minecraft/tags/worldgen/biome/" + tag + ".json"),
                    "empty vanilla tag must not be shipped: " + tag);
        }
    }

    /**
     * Exactly two namespaces, and exactly the files they are supposed to hold.
     * <p>
     * This pack is the mod's structures and nothing else. The castle's density is a DnT-pipeline
     * migration rule ({@code migrate.rule_end_castle_placement}), so no file here may reach into
     * another pack's namespace -- an override file would depend on runtime pack precedence.
     * <p>
     * Asserted as an inventory (every shipped path, compared to the expected set) rather than as a
     * "no foreign namespace" check, because the failure this catches is a <em>leftover</em>: the nine
     * legacy {@code structure_set} documents that the id move retired. A stale one registers no
     * structure the player can see, but it does place a second copy of a structure that already
     * exists. A directory-listing check would pass; a path inventory cannot.
     */
    @Test
    void thePackCarriesExactlyTheTwoNamespacesAndNothingElse() throws IOException {
        Set<String> namespaces = new TreeSet<>();
        Path data = pluginResources().resolve(PACK + "data");
        try (Stream<Path> walk = Files.list(data)) {
            walk.filter(Files::isDirectory)
                    .forEach(path -> namespaces.add(path.getFileName().toString()));
        }
        assertEquals(Set.of(TEMPLATE_NAMESPACE, PUBLIC_NAMESPACE), namespaces);

        Set<String> expected = new TreeSet<>();
        for (String name : STRUCTURES) {
            // Public: the ids /locate exposes, and the only placement.
            expected.add(structure(name));
            expected.add(structureSet(name));
            // Legacy: same nine structures under the old id, readable so saved chunks resolve.
            expected.add(legacyStructure(name));
            // Source namespace: the pools and the payloads, both untouched.
            expected.add(templatePool(name));
            expected.add(NBT(name));
        }
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(data)) {
            walk.filter(Files::isRegularFile)
                    .map(path -> data.relativize(path).toString().replace('\\', '/'))
                    .forEach(path -> actual.add("data/" + path));
        }
        assertEquals(expected, actual,
                "the pack must ship exactly the 9 public structures, the 9 public structure_sets,"
                        + " the 9 legacy structures, the 9 template pools and the 9 NBT payloads");
    }

    // --- helpers -------------------------------------------------------------------------------

    private static String nbtName(String structure) {
        return "end_warship".equals(structure) ? WARSHIP_NBT : structure;
    }

    private static String NBT(String structure) {
        return "data/" + TEMPLATE_NAMESPACE + "/structure/" + nbtName(structure) + ".nbt";
    }

    /** The public structure document -- the id a player locates and places. */
    private static String structure(String name) {
        return "data/" + PUBLIC_NAMESPACE + "/worldgen/structure/" + name + ".json";
    }

    /** The same structure under the source's id, kept so a saved chunk's start still resolves. */
    private static String legacyStructure(String name) {
        return "data/" + TEMPLATE_NAMESPACE + "/worldgen/structure/" + name + ".json";
    }

    private static String structureSet(String name) {
        return "data/" + PUBLIC_NAMESPACE + "/worldgen/structure_set/" + name + ".json";
    }

    private static String templatePool(String name) {
        return "data/" + TEMPLATE_NAMESPACE + "/worldgen/template_pool/" + name + ".json";
    }

    /** Reads a path relative to the echoes pack root. */
    private static byte[] bytes(String relative) {
        return raw(PACK + relative);
    }

    /** Reads a path relative to {@code plugin/src/main/resources}, for files outside this pack. */
    private static byte[] raw(String relative) {
        Path file = pluginResources().resolve(relative);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException error) {
            throw new AssertionError("failed reading " + file, error);
        }
    }

    private static String read(String relative) {
        byte[] data = bytes(relative);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    private static JsonObject json(String relative) {
        return jsonAt(PACK + relative);
    }

    /** Parses a JSON document outside this pack, e.g. the biome pack's own tag. */
    private static JsonObject jsonAt(String relativeToPluginResources) {
        byte[] data = raw(relativeToPluginResources);
        assertNotNull(data, "missing resource: " + relativeToPluginResources);
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<Integer> ints(JsonElement element) {
        List<Integer> values = new ArrayList<>();
        for (JsonElement part : element.getAsJsonArray()) {
            values.add(part.getAsInt());
        }
        return values;
    }

    /**
     * The NBT is shipped as bytes and must stay a gzip-compressed TAG_Compound: the server's structure
     * loader reads it with {@code NbtIo.readCompressed}, and a plain (uncompressed) or truncated file
     * is a structure that fails to load after the world has already been created.
     */
    private void assertNbtRootIsCompound(String relative) throws IOException {
        byte[] raw = bytes(relative);
        assertNotNull(raw, "missing resource: " + relative);
        assertEquals(0x1f, raw[0] & 0xff, relative + ": not gzip (bad magic)");
        assertEquals(0x8b, raw[1] & 0xff, relative + ": not gzip (bad magic)");
        byte[] payload = gunzip(relative);
        assertTrue(payload.length > 1, relative + ": empty NBT payload");
        assertEquals(10, payload[0], relative + ": root tag must be TAG_Compound");
        assertEquals(0, payload[payload.length - 1], relative + ": NBT must end with TAG_End");
    }

    /** The decompressed NBT payload of a shipped structure file. */
    private static byte[] gunzip(String relative) throws IOException {
        byte[] raw = bytes(relative);
        assertNotNull(raw, "missing resource: " + relative);
        try (GZIPInputStream stream = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** Every {@code <namespace>:chests/<path>} resource id literally present in an NBT payload. */
    private static Set<String> lootTablesIn(String payload) {
        Set<String> tables = new TreeSet<>();
        Matcher matcher = Pattern.compile("[a-z0-9_.-]+:chests/[a-z0-9_/]+").matcher(payload);
        while (matcher.find()) {
            tables.add(matcher.group());
        }
        return tables;
    }

    /** Same walk-up as the other contract tests: the test may run from either module's directory. */
    private static Path pluginResources() {
        Path at = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && at != null; up++, at = at.getParent()) {
            Path candidate = at.resolve("plugin/src/main/resources");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "plugin/src/main/resources not found from " + Path.of("").toAbsolutePath());
    }
}
