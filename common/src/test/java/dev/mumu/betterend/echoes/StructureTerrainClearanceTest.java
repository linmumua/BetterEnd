package dev.mumu.betterend.echoes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mumu.betterend.terrain.IslandField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No shipped structure may sit inside the terrain.
 *
 * <p>The bug this pins: this generator writes its terrain directly, so neither
 * {@code terrain_adaptation} (which only a {@code NoiseBasedChunkGenerator} honours) nor a
 * single-column heightmap projection can keep a building out of the rock. Two things actually work,
 * and every structure must be in exactly one of them:
 *
 * <ul>
 *   <li>it <b>projects onto a platform</b> -- the eight ground structures carry
 *       {@code project_start_to_heightmap: WORLD_SURFACE_WG} with {@code start_height: 0}, and
 *       {@code StructurePlatform} flattens the terrain under their disc to one height, so the
 *       structure's own {@code y0} is that height plus one;</li>
 *   <li>or it <b>floats clear of the ceiling</b> -- its lowest possible block Y is above
 *       {@link IslandField#TOP_SOLID_Y}, which is the highest Y the density field can ever make
 *       solid. Nothing at or above it is terrain, so nothing can bury the structure.</li>
 * </ul>
 *
 * <p>What is NOT allowed, and what this file exists to catch, is a structure whose vertical range
 * straddles the ceiling. That was the shipped state of the DnT castle (span {@code start-64 ..
 * start+51} starting at 158, i.e. 94..210) and the DnT ship (81..206): their lower thirds landed
 * inside whatever island happened to be there.
 */
class StructureTerrainClearanceTest {
    private static final String ECHOES_RESOURCES =
            "plugin/src/main/resources/datapacks/echoes-end-structures/data/mumu/worldgen/structure/";
    /** DnT is generated into the build tree and packaged by {@code processTestResources}. */
    private static final String DNT = "datapacks/dnt-end-castle/data/mumu/worldgen/structure/";

    /** The eight ground structures: platform-hosted, so they must project and start at 0. */
    private static final List<String> PLATFORMED = List.of(
            "crying_obsidian_tower", "end_camp", "end_dungeon_tower", "end_enchanted_temple",
            "end_pillar", "end_shulker_tower", "end_temple", "end_well");

    /**
     * How far each floating structure reaches below its own origin. Measured from the template
     * NBT: the castle's jigsaw chain runs down to layer_-6 (-64), everything else only builds
     * upward from 0. A new structure needs its own number here, which is the point -- guessing a
     * blanket margin is how the original 158 got chosen.
     */
    private static final Map<String, Integer> FLOATING = Map.of(
            "end_warship", 0,   // payload occupies y 1..47
            "end_ship", 0,      // 1..47
            "end_castle", 64);  // -64..51

    @Test
    void everyGroundStructureProjectsOntoItsPlatform() throws IOException {
        for (String name : PLATFORMED) {
            JsonObject doc = echoes(name);
            assertEquals("WORLD_SURFACE_WG", doc.get("project_start_to_heightmap").getAsString(),
                    name + " must project so StructurePlatform's flat top is what it lands on");
            assertEquals(0, doc.getAsJsonObject("start_height").get("absolute").getAsInt(),
                    name + " must start at the projected height, not an offset above or below it");
        }
    }

    @Test
    void everyFloatingStructureClearsTheTerrainCeiling() throws IOException {
        for (Map.Entry<String, Integer> entry : FLOATING.entrySet()) {
            String name = entry.getKey();
            int reachBelow = entry.getValue();
            JsonObject doc = name.equals("end_warship") ? echoes(name) : dnt(name);
            JsonObject height = doc.getAsJsonObject("start_height");
            int lowestStart = height.getAsJsonObject("min_inclusive").get("absolute").getAsInt();
            int lowestBlock = lowestStart - reachBelow;
            assertTrue(lowestBlock > IslandField.TOP_SOLID_Y,
                    name + " reaches down to y " + lowestBlock + " but terrain is solid up to "
                            + IslandField.TOP_SOLID_Y + "; its lower layers would be buried");
        }
    }

    @Test
    void platformedAndFloatingAreDisjoint() throws IOException {
        // The two mechanisms must not be mixed: a document that projects AND carries a uniform
        // start_height would be projected somewhere and then offset from it, which is exactly how a
        // building ends up half inside a hill.
        for (String name : PLATFORMED) {
            JsonObject doc = echoes(name);
            assertTrue(!doc.has("project_start_to_heightmap") || !doc.getAsJsonObject("start_height").has("type"),
                    name + ": platform-hosted structures must not also carry a uniform height");
        }
        for (Map.Entry<String, Integer> entry : FLOATING.entrySet()) {
            String name = entry.getKey();
            JsonObject doc = name.equals("end_warship") ? echoes(name) : dnt(name);
            assertTrue(!doc.has("project_start_to_heightmap"),
                    name + ": a floating structure must not project onto the terrain it is clearing");
            assertEquals("minecraft:uniform", doc.getAsJsonObject("start_height").get("type").getAsString(),
                    name + " must pick its Y from a range, not a fixed height");
        }
    }

    @Test
    void theCastleKeepsAnAuthorSizedRange() throws IOException {
        // Raising the floor must not collapse the distribution to a point: the ship keeps its
        // 128..159 spread, and the castle keeps a two-value range rather than a single Y.
        JsonObject ship = dnt("end_ship").getAsJsonObject("start_height");
        assertEquals(128, ship.getAsJsonObject("min_inclusive").get("absolute").getAsInt());
        assertEquals(159, ship.getAsJsonObject("max_inclusive").get("absolute").getAsInt());

        JsonObject castle = dnt("end_castle").getAsJsonObject("start_height");
        int min = castle.getAsJsonObject("min_inclusive").get("absolute").getAsInt();
        int max = castle.getAsJsonObject("max_inclusive").get("absolute").getAsInt();
        assertTrue(max >= min, "start_height must not invert: " + min + ".." + max);
        assertEquals(1, max - min, "the castle keeps its author's two-value range");
    }

    /** The eight Echoes ground structures live in the source tree, so they are read from disk. */
    private static JsonObject echoes(String name) throws IOException {
        Path at = Path.of("").toAbsolutePath();
        Path file = null;
        for (Path candidate = at; candidate != null; candidate = candidate.getParent()) {
            Path probe = candidate.resolve(ECHOES_RESOURCES + name + ".json");
            if (Files.isRegularFile(probe)) {
                file = probe;
                break;
            }
        }
        assertNotNull(file, ECHOES_RESOURCES + name + ".json not found from " + at);
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    /** DnT is read from the packaged build output. */
    private static JsonObject dnt(String name) throws IOException {
        try (InputStream in = StructureTerrainClearanceTest.class.getClassLoader()
                .getResourceAsStream(DNT + name + ".json")) {
            assertNotNull(in, "missing packaged resource: " + DNT + name + ".json");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
