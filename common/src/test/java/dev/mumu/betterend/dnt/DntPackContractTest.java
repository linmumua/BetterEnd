package dev.mumu.betterend.dnt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the DnT "End Castle" packs produced by
 * {@code scripts/prepare_dnt.py} and placed on the test classpath by
 * {@code :common:processTestResources} / {@code :plugin:prepareDntResources}.
 *
 * <p>These assert the two things the migration is responsible for: the packs
 * declare the 26.3 formats, and their references close (every
 * {@code nova_structures:} / {@code mumu:} reference resolves inside the produced
 * pack).
 */
class DntPackContractTest {

    private static final String DATAPACK = "datapacks/dnt-end-castle/";
    private static final String CE = "craftengine/dnt_end_castle/";
    private static final String MANIFEST = "dnt-manifest.json";

    private static String read(String path) {
        InputStream stream = DntPackContractTest.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, read);
            }
            return out.toString();
        } catch (Exception e) {
            throw new AssertionError("failed reading " + path, e);
        }
    }

    private static JsonObject json(String path) {
        String text = read(path);
        assertNotNull(text, "missing resource: " + path);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static List<Integer> ints(JsonElement element) {
        List<Integer> values = new ArrayList<>();
        for (JsonElement part : element.getAsJsonArray()) {
            values.add(part.getAsInt());
        }
        return values;
    }

    @Test
    void datapackDeclaresDataFormat121() {
        JsonObject pack = json(DATAPACK + "pack.mcmeta").getAsJsonObject("pack");
        assertEquals(List.of(121, 0), ints(pack.get("min_format")), "min_format");
        assertEquals(List.of(121, 0), ints(pack.get("max_format")), "max_format");
        assertFalse(pack.has("supported_formats"), "supported_formats must be absent above format 81");
    }

    @Test
    void craftEnginePackIsNamespacedDnt() {
        String yml = read(CE + "pack.yml");
        assertNotNull(yml, "missing CE pack.yml");
        assertTrue(yml.contains("namespace: dnt"), "CE pack namespace");
        assertTrue(yml.contains("enable: true"), "CE pack enabled");
    }

    @Test
    void craftEnginePackRootHoldsOnlyPackFiles() {
        // The CE bridge copies craftengine/dnt_end_castle/** verbatim into the
        // CraftEngine resources dir, so no build-only artefacts may live there.
        assertNull(read(CE + "manifest.json"), "manifest must not be inside the CE pack");
        assertNull(read(CE + "CREDITS.txt"), "credits must not be inside the CE pack");
    }

    @Test
    void manifestRecordsTargetAndPassesValidation() {
        JsonObject manifest = json(MANIFEST);
        assertEquals("26.3", manifest.getAsJsonObject("target").get("minecraft").getAsString());
        assertEquals(List.of(121, 0), ints(manifest.getAsJsonObject("target").get("data_pack_format")));
        assertEquals(List.of(97, 1), ints(manifest.getAsJsonObject("target").get("resource_pack_format")));
        assertEquals(0, manifest.getAsJsonObject("validation").getAsJsonArray("problems").size(),
                "generation must be validation-clean");
        assertEquals("22d0040e162688d750bbfb122026f9881df42bd5e26f63cb0b5b1f6047c07e98",
                manifest.getAsJsonObject("source").get("sha256").getAsString());
    }

    @Test
    void phantomBossVanishRestoredVerbatim() {
        String fn = read(DATAPACK + "data/nova_structures/function/phantom_boss_vanish.mcfunction");
        assertEquals("particle minecraft:smoke ~ ~ ~ 2 1 2 0 200 normal", fn == null ? null : fn.trim());
    }

    @Test
    void noVanillaNamespaceItemModelOverrides() {
        // The dnt leaf models replace the source pack's assets/minecraft/items/*.json
        // dispatchers, so no minecraft-namespace item model may be shipped. The
        // original custom_model_data stays on the stacks but is no longer needed.
        assertNull(read(CE + "resourcepack/assets/minecraft/items/trial_key.json"),
                "vanilla item model must not be overridden");
        assertNull(read(CE + "resourcepack/assets/minecraft/items/filled_map.json"),
                "vanilla filled_map model must not be overridden");
    }

    @Test
    void generatedItemModelDefinitionPointsAtDntModel() {
        JsonObject body = json(CE + "resourcepack/assets/dnt/items/keys/end_castle_key.json");
        assertEquals("dnt:item/keys/end_castle_key", body.getAsJsonObject("model").get("model").getAsString());
    }

    @Test
    void keyLootTableUses26xDiscriminatorsAndItemModel() {
        JsonObject table = json(DATAPACK + "data/nova_structures/loot_table/keys/end_castle_key.json");
        assertFalse(table.has("functions"), "legacy 'functions' key must be gone");
        JsonObject entry = table.getAsJsonArray("pools").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject modifier = entry.getAsJsonArray("modifier").get(0).getAsJsonObject();
        assertEquals("minecraft:set_components", modifier.get("type").getAsString(), "discriminator is 'type'");
        JsonObject components = modifier.getAsJsonObject("components");
        assertEquals("dnt:keys/end_castle_key", components.get("minecraft:item_model").getAsString());
        // the source custom_model_data value is retained verbatim
        assertEquals(721.0, components.getAsJsonObject("minecraft:custom_model_data")
                .getAsJsonArray("floats").get(0).getAsDouble(), 0.0);
    }

    @Test
    void bareSourceEntityTypeIsRenamedAndPlayerIsWrapped() {
        // Real file: advancement/phantom_boss_make_fall.json uses a bare
        // EntityPredicate at conditions.damage.source_entity AND a bare
        // EntityPredicate at conditions.player (entity_hurt_player trigger).
        JsonObject conditions = json(DATAPACK + "data/nova_structures/advancement/phantom_boss_make_fall.json")
                .getAsJsonObject("criteria").getAsJsonObject("a").getAsJsonObject("conditions");

        JsonObject sourceEntity = conditions.getAsJsonObject("damage").getAsJsonObject("source_entity");
        assertEquals("minecraft:phantom", sourceEntity.get("minecraft:entity_type").getAsString(),
                "bare EntityPredicate .type must become minecraft:entity_type");
        assertFalse(sourceEntity.has("type"), "legacy bare 'type' must be gone");

        JsonObject player = conditions.getAsJsonObject("player");
        assertEquals("minecraft:entity_properties", player.get("type").getAsString(),
                "player must be wrapped as a loot condition for 26.3");
        assertEquals("this", player.get("entity").getAsString());
        assertTrue(player.has("predicate"), "wrapped condition must carry the predicate");
    }

    @Test
    void enchantmentConditionsUseTypeDiscriminator() {
        JsonObject gravity = json(DATAPACK + "data/nova_structures/enchantment/gravity.json");
        JsonObject requirements = gravity.getAsJsonObject("effects")
                .getAsJsonArray("minecraft:post_attack").get(0).getAsJsonObject()
                .getAsJsonObject("requirements");
        assertEquals("minecraft:entity_properties", requirements.get("type").getAsString());
        assertFalse(requirements.has("condition"), "legacy 'condition' discriminator must be gone");
    }

    @Test
    void entityPredicateUsesNamespacedEntityType() {
        JsonObject wax = json(DATAPACK + "data/nova_structures/enchantment/wax_wings.json");
        // movement/flags/periodic_tick must all be registered sub-predicates
        JsonObject predicate = wax.getAsJsonObject("effects")
                .getAsJsonArray("minecraft:tick").get(0).getAsJsonObject()
                .getAsJsonObject("requirements").getAsJsonObject("predicate");
        for (String key : predicate.keySet()) {
            String name = key.contains(":") ? key : "minecraft:" + key;
            assertTrue(List.of("minecraft:location", "minecraft:movement", "minecraft:flags",
                            "minecraft:periodic_tick", "minecraft:nbt", "minecraft:entity_type",
                            "minecraft:effects", "minecraft:equipment", "minecraft:distance").contains(name),
                    "unexpected entity sub-predicate: " + name);
        }
    }

    @Test
    void waxWingsTimeCheckGainsOverworldClock() {
        // 26.3 TimeCheck reads WorldClock.CODEC.fieldOf("clock") (mandatory); the
        // legacy condition carried none. vanilla's DayTimeToClockFix maps the old
        // DayTime value to the minecraft:overworld clock.
        JsonObject wax = json(DATAPACK + "data/nova_structures/enchantment/wax_wings.json");
        JsonObject timeCheck = findByField(wax, "type", "minecraft:time_check");
        assertNotNull(timeCheck, "wax_wings must carry a time_check condition");
        assertEquals("minecraft:overworld", timeCheck.get("clock").getAsString(),
                "time_check must gain the overworld clock");
        // period/value are preserved verbatim
        assertEquals(24000, timeCheck.get("period").getAsInt());
        JsonObject value = timeCheck.getAsJsonObject("value");
        assertEquals(0, value.get("min").getAsInt());
        assertEquals(12000, value.get("max").getAsInt());
    }

    @Test
    void configuredFeatureRelocatedToWorldgenFeature() {
        // Registries.FEATURE is "worldgen/feature" in 26.3 (not configured_feature),
        // and FileToIdConverter uses the registry path as the folder.
        assertNull(read(DATAPACK + "data/nova_structures/worldgen/configured_feature/chorus_plant.json"),
                "legacy worldgen/configured_feature must not be shipped");
        JsonObject feature = json(DATAPACK + "data/nova_structures/worldgen/feature/chorus_plant.json");
        assertEquals("minecraft:chorus_plant", feature.get("type").getAsString());
        assertTrue(feature.getAsJsonObject("config").isEmpty(), "config stays {}");
    }

    @Test
    void placedFeatureFeatureChainResolvesToFeatureDir() {
        // placed_feature root "feature" -> worldgen/feature
        JsonObject placed = json(DATAPACK + "data/nova_structures/worldgen/placed_feature/chorus_plant.json");
        assertEquals("nova_structures:chorus_plant", placed.get("feature").getAsString());
        assertNotNull(read(DATAPACK + "data/nova_structures/worldgen/feature/chorus_plant.json"),
                "placed feature's feature must resolve to worldgen/feature/chorus_plant.json");
    }

    @Test
    void featurePoolElementChainResolvesToPlacedFeature() {
        // template_pool feature_pool_element "feature" -> worldgen/placed_feature
        JsonObject pool = json(DATAPACK + "data/nova_structures/worldgen/template_pool/end_castle/chorus_plant.json");
        JsonObject element = findByField(pool, "element_type", "minecraft:feature_pool_element");
        assertNotNull(element, "template pool must carry a feature_pool_element");
        assertEquals("nova_structures:chorus_plant", element.get("feature").getAsString());
        assertNotNull(read(DATAPACK + "data/nova_structures/worldgen/placed_feature/chorus_plant.json"),
                "feature_pool_element feature must resolve to worldgen/placed_feature/chorus_plant.json");
    }

    @Test
    void manifestRecordsRelocationWithFinalOutputPath() {
        JsonObject rules = json(MANIFEST).getAsJsonObject("migration").getAsJsonObject("rules");
        assertTrue(rules.has("worldgen.configured_feature_to_feature"),
                "relocation must be recorded in the manifest");
        JsonArray files = rules.getAsJsonObject("worldgen.configured_feature_to_feature").getAsJsonArray("files");
        assertEquals(1, files.size());
        assertEquals("data/nova_structures/worldgen/feature/chorus_plant.json", files.get(0).getAsString(),
                "manifest must record the final output path");
    }

    @Test
    void everyPackOwnedReferenceResolves() {
        // Walk the loot data and require each pack-owned reference to exist. `mumu:` is this
        // project's own namespace for the two republished structures, so it is checked the same way.
        List<String> dangling = new ArrayList<>();
        for (String path : List.of(
                "data/nova_structures/loot_table/keys/all_keys.json",
                "data/nova_structures/loot_table/chests/end_castle/greater_loot.json",
                "data/nova_structures/loot_table/pots/end_castle/pot_end_castle.json",
                // the castle map: its exploration_map destination is a structure reference
                "data/nova_structures/loot_table/maps/end_castle.json")) {
            JsonObject doc = json(DATAPACK + path);
            collectDangling(doc, path, dangling);
        }
        assertTrue(dangling.isEmpty(), "dangling references: " + dangling);
    }

    @Test
    void lootModifierRootIsExplicitSequence() {
        // Registries.ITEM_MODIFIER loads with LootItemFunctions.TYPED_CODEC (a
        // dispatch on "type"), so a bare-array root is illegal; the empty
        // nova_structures loot_modifier ships as an explicit no-op sequence.
        JsonObject doc = json(DATAPACK + "data/nova_structures/item_modifier/loot_modifier.json");
        assertEquals("minecraft:sequence", doc.get("type").getAsString(),
                "item_modifier 根必须包裹为显式 sequence");
        assertTrue(doc.getAsJsonArray("functions").isEmpty(), "空 item_modifier 是 no-op sequence");
    }

    @Test
    void vaultDamageProviderCarriesUniformType() {
        // 26.3 ContextFloatProviders.DIRECT_CODEC dispatches, so a {min,max} object is
        // a UniformGenerator and needs a "type". The inverted source range is kept
        // verbatim (never swapped or clamped).
        JsonObject damage = providerField(
                "data/nova_structures/loot_table/chests/end_castle/vault_brigattine.json", 4, 0, "damage");
        assertEquals("minecraft:uniform", damage.get("type").getAsString(),
                "缺 type 的 {min,max} 提供器必须补 minecraft:uniform");
        assertEquals(0.01, damage.get("min").getAsDouble(), 0.0);
        assertEquals(0.0, damage.get("max").getAsDouble(), 0.0);
    }

    @Test
    void spawnerAmplifierProviderCarriesUniformType() {
        JsonObject amplifier = providerField(
                "data/nova_structures/loot_table/spawners/end_lighthouse/normal.json", 0, 5, "amplifier");
        assertEquals("minecraft:uniform", amplifier.get("type").getAsString());
        assertEquals(3, amplifier.get("min").getAsInt());
        assertEquals(5, amplifier.get("max").getAsInt());
    }

    @Test
    void endCastleMapDropsRetiredMapColorComponent() {
        // 26.3 removed the map_color component (MapColorComponentRemovalFix, data
        // version 5008); loot JSON bypasses the DFU, so it must already be gone here
        // while item_name and the map target/decoration stay untouched.
        JsonObject entry = json(DATAPACK + "data/nova_structures/loot_table/maps/end_castle.json")
                .getAsJsonArray("pools").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonArray modifier = entry.getAsJsonArray("modifier");
        JsonObject exploration = modifier.get(0).getAsJsonObject();
        assertEquals("minecraft:exploration_map", exploration.get("type").getAsString());
        // destination is RegistryCodecs.holderSet(Registries.STRUCTURE): a plain id is a *direct*
        // holder (one named structure), and HolderSetCodec only treats a "#"-prefixed value as a tag.
        // The legacy definition is never placed, so a kept legacy id would make this map locate
        // nothing at all.
        assertEquals("mumu:end_castle", exploration.get("destination").getAsString(),
                "map destination 必须指向真正会生成的结构");
        JsonObject components = modifier.get(1).getAsJsonObject().getAsJsonObject("components");
        assertFalse(components.has("minecraft:map_color"), "已移除的 map_color 组件必须被丢弃");
        assertFalse(components.has("map_color"), "无命名空间的 map_color 组件同样必须被丢弃");
        assertTrue(components.has("minecraft:item_name"), "item_name 必须保留");
    }

    /**
     * The two DnT structures are republished under {@code mumu:} — one id each.
     *
     * <p>WHY one id and not two: a structure's id is its file path under
     * {@code worldgen/structure}, so shipping the author's definition beside the {@code mumu:} one
     * registers the same structure twice. {@code /locate structure} completes from the registry
     * rather than from the placement tables, so both spellings showed up in the completion list and
     * every structure looked duplicated. The author's definition is therefore moved rather than
     * copied.
     *
     * <p>The trade is deliberate: a world generated before the rename keys its saved chunk structure
     * starts by the old id, so those starts no longer resolve (vanilla logs "Unknown structure id"
     * and drops them; the blocks themselves stay). Only the {@code mumu:} id generates, which is
     * unchanged.
     */
    @Test
    void eachStructureShipsExactlyOneDefinitionUnderMumu() {
        for (String name : List.of("end_castle", "end_ship")) {
            assertNotNull(json(DATAPACK + "data/mumu/worldgen/structure/" + name + ".json"),
                    name + ": mumu 定义必须随包发出");
            assertNull(read(DATAPACK + "data/nova_structures/worldgen/structure/" + name + ".json"),
                    name + ": 作者命名空间的旧 definition 不得随包发出，否则 /locate 会出现两份");
        }
    }

    @Test
    void noLegacyPlacementSurvives() {
        // A leftover legacy structure_set would generate a second castle in the same cell: the
        // concentric_rings placement is deterministic per salt, so both grids pick the same cells.
        for (String name : List.of("end_castle", "end_ship")) {
            assertNull(read(DATAPACK + "data/nova_structures/worldgen/structure_set/" + name + ".json"),
                    name + ": 旧 placement 不得随包发出");
            JsonObject moved = json(DATAPACK + "data/mumu/worldgen/structure_set/" + name + ".json");
            assertEquals("mumu:" + name,
                    moved.getAsJsonArray("structures").get(0).getAsJsonObject().get("structure").getAsString(),
                    name + " structure_set 必须放置 mumu id");
        }
    }

    @Test
    void castleDensityRegression() {
        // The project's deliberate values, now addressed through the moved path: count 219 -> 112
        // halves the castles per world, distance 32 -> 48 puts ring 1 at 1920 blocks (outside
        // BetterEnd's 1024-block central void). salt/spread/preferred_biomes stay the author's.
        JsonObject placement = json(DATAPACK + "data/mumu/worldgen/structure_set/end_castle.json")
                .getAsJsonObject("placement");
        assertEquals("minecraft:concentric_rings", placement.get("type").getAsString());
        assertEquals(112, placement.get("count").getAsInt(), "count");
        assertEquals(48, placement.get("distance").getAsInt(), "distance");
        assertEquals(0, placement.get("salt").getAsInt(), "salt 是作者的");
        assertEquals(3, placement.get("spread").getAsInt(), "spread 是作者的");
        assertEquals("#nova_structures:collections/end_voids", placement.get("preferred_biomes").getAsString());
    }

    @Test
    void shipPlacementIsUntouched() {
        // The castle rule is scoped by exact path; a leak would have retuned the ship too.
        JsonObject placement = json(DATAPACK + "data/mumu/worldgen/structure_set/end_ship.json")
                .getAsJsonObject("placement");
        assertEquals("minecraft:random_spread", placement.get("type").getAsString());
        assertEquals(40, placement.get("spacing").getAsInt());
        assertEquals(10, placement.get("separation").getAsInt());
        assertEquals(79584390, placement.get("salt").getAsInt());
        assertEquals(0.25, placement.get("frequency").getAsDouble(), 0.0);
        assertEquals("triangular", placement.get("spread_type").getAsString());
    }

    @Test
    void structureTagsNameThePlacedId() {
        // The pack's own structure tag and vanilla's eye-of-ender tag both list structures in
        // `values`; a stale legacy id would name a structure that never generates. (The castle map
        // does NOT go through this tag -- see endCastleMapDropsRetiredMapColorComponent.)
        JsonObject castleTag = json(DATAPACK + "data/nova_structures/tags/worldgen/structure/end_castle.json");
        assertEquals("mumu:end_castle", castleTag.getAsJsonArray("values").get(0).getAsString());

        JsonObject eyes = json(DATAPACK + "data/minecraft/tags/worldgen/structure/eye_of_ender_located.json");
        assertEquals("mumu:end_castle", eyes.getAsJsonArray("values").get(0).getAsString(),
                "末影之眼必须指向真正会生成的结构");
    }

    @Test
    void castleMapIsTheOnlyMapAndItsDestinationIsTypedAsAStructure() {
        // Regression for the corrected reading of ExplorationMapFunction: 26.3 declares
        // `RegistryCodecs.holderSet(Registries.STRUCTURE).fieldOf("destination")`, so the plain string
        // is a direct holder, not a tag name. Both the value and the fact that it resolves are
        // asserted: the map is the one thing whose failure mode is silent (it simply finds nothing).
        JsonObject modifier = json(DATAPACK + "data/nova_structures/loot_table/maps/end_castle.json")
                .getAsJsonArray("pools").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonArray("modifier").get(0).getAsJsonObject();
        String destination = modifier.get("destination").getAsString();
        assertFalse(destination.startsWith("#"), "direct holder 不能带 # 前缀（那是 tag 分支）");
        assertEquals("mumu:end_castle", destination);
        assertNotNull(read(DATAPACK + "data/mumu/worldgen/structure/end_castle.json"),
                "direct destination 必须解析到真正会生成的结构定义");
    }

    @Test
    void findAdvancementsMatchThePlacedId() {
        // LocationPredicate.structures is a HolderSet<Structure>; a stale id here would make the
        // advancement silently unreachable.
        for (String name : List.of("end_castle", "end_ship")) {
            JsonObject location = json(DATAPACK + "data/nova_structures/advancement/end/find_" + name + ".json")
                    .getAsJsonObject("criteria").getAsJsonObject(name)
                    .getAsJsonObject("conditions").getAsJsonObject("player")
                    .getAsJsonObject("predicate").getAsJsonObject("location");
            assertEquals("mumu:" + name, location.get("structures").getAsString(), name);
        }
    }

    @Test
    void manifestRecordsTheNamespaceMove() {
        JsonObject rules = json(MANIFEST).getAsJsonObject("migration").getAsJsonObject("rules");
        // 2 = both structures, for each of: the definition move, the structure_set file move,
        // the entry rewrite, the structure-tag values and the advancement predicate.
        assertFalse(rules.has("structure.public_namespace_alias"),
                "不再写第二份 definition：mumu 那份就是唯一的定义");
        assertEquals(2, rules.getAsJsonObject("structure.public_namespace_definition_path")
                .get("occurrences").getAsInt());
        assertEquals(2, rules.getAsJsonObject("structure.public_namespace_structure_set_path")
                .get("occurrences").getAsInt());
        assertEquals(2, rules.getAsJsonObject("structure.public_namespace_structure_tag")
                .get("occurrences").getAsInt());
        assertEquals(2, rules.getAsJsonObject("structure.public_namespace_advancement")
                .get("occurrences").getAsInt());
        // 1 = the castle map: the ship has no map loot entry.
        assertEquals(1, rules.getAsJsonObject("structure.public_namespace_map_destination")
                .get("occurrences").getAsInt());
    }

    /** The {@code field} number-provider object of {@code pools[p].entries[e].modifier[0]}. */
    private static JsonObject providerField(String path, int pool, int entryIndex, String field) {
        JsonObject entry = json(DATAPACK + path).getAsJsonArray("pools").get(pool).getAsJsonObject()
                .getAsJsonArray("entries").get(entryIndex).getAsJsonObject();
        return entry.getAsJsonArray("modifier").get(0).getAsJsonObject().getAsJsonObject(field);
    }

    private void collectDangling(JsonElement element, String path, List<String> dangling) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && isPackOwned(value.getAsString())) {
                    String kind = referenceKind(entry.getKey(), element.getAsJsonObject());
                    if (kind != null) {
                        assertTrue(resourceExists(kind, value.getAsString()),
                                path + " -> " + kind + " " + value.getAsString());
                    }
                }
                collectDangling(value, path, dangling);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectDangling(item, path, dangling);
            }
        }
    }

    private static JsonObject findByField(JsonElement element, String key, String value) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonElement field = obj.get(key);
            if (field != null && field.isJsonPrimitive() && value.equals(field.getAsString())) {
                return obj;
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonObject found = findByField(entry.getValue(), key, value);
                if (found != null) {
                    return found;
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                JsonObject found = findByField(item, key, value);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Whether {@code id} belongs to a namespace this pack owns (and so must resolve inside it). */
    private static boolean isPackOwned(String id) {
        return id.startsWith("nova_structures:") || id.startsWith("mumu:");
    }

    private static String referenceKind(String key, JsonObject owner) {
        if (!owner.has("type")) {
            return null;
        }
        String type = owner.get("type").getAsString();
        if ("value".equals(key) && "minecraft:loot_table".equals(type)) {
            return "loot_table";
        }
        if ("function".equals(key) && "minecraft:run_function".equals(type)) {
            return "function";
        }
        if ("name".equals(key) && "minecraft:reference".equals(type)) {
            return "item_modifier";
        }
        // ExplorationMapFunction.destination is RegistryCodecs.holderSet(Registries.STRUCTURE): a
        // plain id is a direct holder, so it is a structure reference and must resolve. A
        // "#"-prefixed value is the codec's tag branch and is not a structure id at all.
        if ("destination".equals(key) && "minecraft:exploration_map".equals(type)) {
            return "worldgen/structure";
        }
        return null;
    }

    private static boolean resourceExists(String kind, String id) {
        String[] split = id.split(":", 2);
        String file = DATAPACK + "data/" + split[0] + "/" + kind + "/" + split[1]
                + ("function".equals(kind) ? ".mcfunction" : ".json");
        return read(file) != null;
    }
}
