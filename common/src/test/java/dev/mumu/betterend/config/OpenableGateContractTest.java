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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resource contract for the two auto-state / donor fixes in this change, read from the shipped
 * CraftEngine pack by expanding every {@code config_factory} the way CraftEngine does (each instance
 * produces one concrete copy of every blueprint block, with {@code ${var}} substituted).
 * <p>
 * WHY an expansion and not a grep: a blueprint block is written ONCE and instantiated N times, so its
 * {@code auto_state} counts N times -- and the old grep totals here were additionally inflated by
 * {@code auto_state:} text that only appears inside comments. Both mistakes are how "BE tripwire 89"
 * was arrived at when the expanded truth was 93 -- and is 87 since blue_vine_fur left the pack.
 * <p>
 * The two fixes this pins:
 * <ul>
 *   <li>pythadendron's fence gate donor moved from {@code crimson_fence_gate} (held by FarmersDelight's
 *       rope_fence_gate) to the free {@code warped_fence_gate}, while its door/trapdoor donor stays
 *       crimson. mossy_glowshroom's gate donor moved from {@code mangrove} -- its own replaced_type, and
 *       held on BOTH halves by FarmersDelight's crimson_fence_gate_cover -- to {@code oak_fence_gate},
 *       whose reclaimable half is unclaimed on the enabled pack set. No gate may pin the canonical
 *       powered=false half, or it would hijack the vanilla gate model;</li>
 *   <li>the two small mosses moved off {@code cave_vines} (whose pool is 48 and was overdrawn to 50)
 *       onto {@code sapling} (pool 8, two spare).</li>
 * </ul>
 * What this class does NOT claim: the donor table is only about the packs enabled on this server.
 * CrabbersDelight (absent here) and CE's own disabled default_assets would each take oak powered=true,
 * so "no collisions" is a statement about the current pack set, not about every future combination.
 */
class OpenableGateContractTest {
    /** {@code AutoStateGroup.java:57} + {@code mappings.yml}: the sapling group is 8 {@code stage=1} states. */
    private static final int SAPLING_POOL = 8;
    /**
     * {@code mappings.yml}: the cave_vines candidate set (age 0 and age 25 excluded). cave_vines
     * enumerates {@code berries} on every line, so its line count IS its state count.
     */
    private static final int CAVE_VINES_POOL = 48;
    /**
     * {@code mappings.yml}: the weeping_vines candidate set.
     *
     * <p>50, not 25. The file has 25 lines written as {@code weeping_vines[age=N]}, and each omits
     * {@code berries} -- so one line donates BOTH berry states. Counting lines understates the pool
     * by half, which is why an earlier version of this constant claimed the 50-slot cap the server
     * reports was unreachable.
     */
    private static final int WEEPING_VINES_POOL = 50;
    /** {@code mappings.yml}: twisting_vines, written the same way as weeping_vines -- 25 x 2 = 50. */
    private static final int TWISTING_VINES_POOL = 50;
    /** {@code mappings.yml}: {@code tripwire[...]} reclaimable states. LOWER=attached=true, HIGHER=attached=false. */
    private static final int TRIPWIRE_POOL = 126;
    /** FarmersDelight's tripwire draws (crops.yml / mushrooms.yml / wild_plants.yml): 10 lower + 20 higher. */
    private static final int FD_TRIPWIRE = 30;
    /**
     * CE default_assets' own tripwire draws, all straight blocks (no config_factory):
     * pebble 3 (2 lower + 1 higher) + ender_pearl_flower 2 (1+1) + magma_plant 2 (1+1) = 4 lower + 3 higher.
     */
    private static final int CE_DEFAULT_TRIPWIRE = 7;
    /** CE's own default_assets: one {@code default:palm_sapling} (configuration/blocks/tree.yml:196) draws sapling. */
    private static final int CE_DEFAULT_SAPLING = 1;
    /** FarmersDelight draws no weeping_vines (its groups: twisting 20, note_block, mushroom, trips, crops). */
    private static final int FD_WEEPING_VINES = 0;
    /** CE default_assets draws no weeping_vines (its groups: sapling, cactus, trips, note_block, sugar_cane, solid). */
    private static final int CE_DEFAULT_WEEPING = 0;

    private static final List<String> WOOD = List.of(
            "pythadendron", "lacugrove", "dragon_tree", "tenanea", "helix_tree",
            "lucernia", "mossy_glowshroom", "jellyshroom", "end_lotus", "umbrella_tree");

    /** The gate donor per family. Only pythadendron and mossy_glowshroom differ from the family's door/trapdoor donor. */
    private static final Map<String, String> GATE_DONOR = Map.ofEntries(
            Map.entry("pythadendron", "warped"),
            Map.entry("lacugrove", "spruce"),
            Map.entry("dragon_tree", "birch"),
            Map.entry("tenanea", "jungle"),
            Map.entry("helix_tree", "acacia"),
            Map.entry("lucernia", "dark_oak"),
            Map.entry("mossy_glowshroom", "oak"),
            Map.entry("jellyshroom", "cherry"),
            Map.entry("end_lotus", "bamboo"),
            Map.entry("umbrella_tree", "pale_oak"));

    /**
     * Every BetterEnd gate pins this template -- the {@code powered=true} (reclaimable) half. CE ships no
     * second fence_gate template, and this pack must not add one: {@code mappings.yml} maps
     * {@code <wood>_fence_gate[..,powered=true] -> <wood>_fence_gate[..,powered=false]}, so pinning
     * powered=false would write the gate model into the canonical vanilla blockstate that every vanilla
     * gate is remapped onto.
     */
    private static final String POWERED_TEMPLATE = "default:block_state/fence_gate";

    /**
     * Vanilla fence_gate types held by the OTHER packs that are actually enabled on this server, with the
     * {@code powered} half they hold. FarmersDelight: {@code rope_fence_gate} -> crimson (powered=true),
     * {@code crimson_fence_gate_cover} -> mangrove (BOTH halves, rope.yml:1573+).
     * <p>
     * oak is deliberately NOT listed: the only packs that would take it are CrabbersDelight (its palm gates
     * pin oak powered=true) and CE's own default_assets ({@code default:palm_fence_gate},
     * tree.yml:379, {@code fence_gate_base_block: ${replaced_door_type}_fence_gate} with
     * {@code replaced_door_type: oak}). CrabbersDelight is not installed here and default_assets is
     * switched OFF by its own pack.yml ({@code enable: false}), so on the enabled pack set
     * (betterend + farmersdelight + dnt_end_castle + customfishing + internal + default_templates +
     * remove_shulker_head) the 16 oak powered=true states are unclaimed. This map therefore describes
     * THIS pack set only: turning either of those two back on revives a collision on
     * mossy_glowshroom's gate, and that combination is not claimed to work.
     */
    private static final Map<String, Set<String>> FOREIGN_GATE_CLAIMS = Map.of(
            "crimson", Set.of("true"),
            "mangrove", Set.of("true", "false"));

    /**
     * The BE gates that knowingly share a donor with another enabled pack. Empty as of the move to oak:
     * mossy_glowshroom used to sit on mangrove, which FarmersDelight's crimson_fence_gate_cover holds on
     * both halves. It is pinned empty so that re-introducing a collision fails here and gets re-decided
     * rather than quietly shipped.
     */
    private static final Set<String> KNOWN_GATE_COLLISIONS = Set.of();

    // --- the tests -------------------------------------------------------------------------------

    @Test
    void theOpenableFamiliesKeepExactlyTheirIds() throws IOException {
        Map<String, Map<String, Object>> blocks = expandOpenable();
        Set<String> expected = new TreeSet<>();
        for (String f : WOOD) {
            expected.add("betterend:" + f + "_door");
            expected.add("betterend:" + f + "_trapdoor");
            expected.add("betterend:" + f + "_gate");
            expected.add("betterend:" + f + "_plate");
        }
        for (String m : List.of("terminite", "thallasium")) {
            expected.add("betterend:" + m + "_door");
            expected.add("betterend:" + m + "_trapdoor");
        }
        assertEquals(expected, new TreeSet<>(blocks.keySet()),
                "the B3 id set changed - a decoupling that renamed a block would break the world");
    }

    @Test
    void everyGateRidesItsDeclaredDonorAndTheReclaimableHalf() throws IOException {
        Map<String, Map<String, Object>> blocks = expand();
        for (String f : WOOD) {
            Map<String, Object> state = stateOf(blocks, "betterend:" + f + "_gate");
            assertEquals(GATE_DONOR.get(f) + "_fence_gate", argumentsOf(state).get("fence_gate_base_block"),
                    f + " gate donor");
            // never the unpowered template: that would pin the canonical half (see POWERED_TEMPLATE).
            assertEquals(POWERED_TEMPLATE, String.valueOf(state.get("template")), f + " gate template");
            // the model/texture arguments must be untouched by the donor decoupling
            assertEquals("betterend:block/" + f + "_gate", argumentsOf(state).get("fence_gate_model"));
            assertEquals("betterend:block/" + f + "_planks", argumentsOf(state).get("fence_gate_texture"));
        }
    }

    @Test
    void noTwoBetterEndGatesShareADonor() {
        assertEquals(GATE_DONOR.size(), new TreeSet<>(GATE_DONOR.values()).size(),
                "two BE gates on one vanilla type would clobber each other's model binding");
    }

    @Test
    void noBetterEndGateSharesADonorWithTheEnabledPacks() {
        Set<String> colliding = new TreeSet<>();
        for (String f : WOOD) {
            Set<String> foreignHalves = FOREIGN_GATE_CLAIMS.get(GATE_DONOR.get(f));
            // every BE gate pins powered=true (asserted above); a foreign holder of that same half is a
            // genuine collision, a foreign holder of only the OTHER half is not.
            if (foreignHalves != null && foreignHalves.contains("true")) {
                colliding.add(f);
            }
        }
        assertEquals(KNOWN_GATE_COLLISIONS, colliding,
                "the set of BE gates sharing a donor with an ENABLED pack changed - re-decide the donor"
                        + " (this only covers the currently enabled pack set, say so if it changes)");
    }

    @Test
    void theTwoMossesMovedOffCaveVinesOntoDifferentFreeCarriers() throws IOException {
        Map<String, Map<String, Object>> blocks = expand();
        // cave_vines is 48/48 from B5 alone. Neither moss may stay there, and they may not BOTH take
        // sapling either: sapling's 8th slot is held by CE default_assets' palm_sapling, so a second
        // sapling draw would be BetterEnd 8 + CE 1 = 9 > 8.
        assertEquals("sapling", stateOf(blocks, "betterend:twisted_umbrella_moss").get("auto_state"),
                "twisted_umbrella_moss must take sapling's 7th BetterEnd slot (7 + CE palm_sapling 1 = 8)");
        assertEquals("weeping_vines", stateOf(blocks, "betterend:umbrella_moss").get("auto_state"),
                "umbrella_moss rides weeping_vines (9 hanging-plant states + 1 moss + 9 crop ages = 19 of 50)");
    }

    @Test
    void theMossesKeepTheirIdentityBehaviourAndLight() throws IOException {
        Map<String, Map<String, Object>> blocks = expand();
        Map<String, Integer> light = Map.of("betterend:twisted_umbrella_moss", 12, "betterend:umbrella_moss", 11);
        for (Map.Entry<String, Integer> e : light.entrySet()) {
            Map<String, Object> def = blocks.get(e.getKey());
            assertNotNull(def, e.getKey() + " disappeared");
            assertEquals(e.getValue(), mergesOf(def).get("luminance"), e.getKey() + " light_emission changed");
            List<Object> behaviours = asList(def.get("behavior"));
            if (behaviours.isEmpty()) behaviours = asList(def.get("behaviors"));
            assertTrue(behaviours.stream().anyMatch(b -> "bush_block".equals(asMap(b).get("type"))),
                    e.getKey() + " lost its bush_block placement");
            List<Object> models = asList(stateOf(blocks, e.getKey()).get("models"));
            assertEquals(16, models.size(), e.getKey() + " model variant list changed");
        }
    }

    /**
     * The two mosses' migration must stay inside its carriers, counted over EVERY known holder (BetterEnd +
     * FarmersDelight + CE default_assets). Nothing here claims an UNKNOWN pack will fit -- it pins BetterEnd's
     * own draw and what is provably already taken.
     */
    @Test
    void theMossMigrationStaysWithinItsCarrierPools() throws IOException {
        Map<String, Integer> used = autoStateUsage();
        int beCaveVines = used.getOrDefault("cave_vines", 0);
        int beSapling = used.getOrDefault("sapling", 0);
        int beWeeping = used.getOrDefault("weeping_vines", 0);

        // cave_vines: BetterEnd alone fills it (B5 48; both mosses left); no other known holder exists.
        assertEquals(CAVE_VINES_POOL, beCaveVines, "BetterEnd must not overdraw cave_vines");
        // sapling: BetterEnd 7 (6 plants + twisted_umbrella_moss) + CE default_assets palm_sapling 1 = 8.
        assertEquals(7, beSapling, "BetterEnd's own sapling draw (6 + twisted_umbrella_moss)");
        assertTrue(beSapling + CE_DEFAULT_SAPLING <= SAPLING_POOL,
                "BE sapling " + beSapling + " + CE default_assets " + CE_DEFAULT_SAPLING
                        + " must fit the " + SAPLING_POOL + "-state pool");
        // weeping_vines: 9 surviving hanging-plant states + umbrella_moss. The twelve crop ages all
        // moved to note_block: a live server reported weeping_vines at its 50 cap, and twisting_vines
        // at 50 too once three appearances were moved there, so no crop age rides a vine pool now.
        assertEquals(10, beWeeping, "BetterEnd's own weeping_vines draw (9 + umbrella_moss)");
        assertTrue(beWeeping + FD_WEEPING_VINES + CE_DEFAULT_WEEPING <= WEEPING_VINES_POOL,
                "BE weeping " + beWeeping + " + FD " + FD_WEEPING_VINES + " + CE " + CE_DEFAULT_WEEPING
                        + " must fit the " + WEEPING_VINES_POOL + "-state pool");
        // note_block: the twelve crop ages. 1300 donated states, so saturation is not a concern; the
        // assertion exists so a future edit that moves them back onto a 50-state vine pool fails here.
        int beNoteBlock = used.getOrDefault("note_block", 0);
        assertEquals(12, beNoteBlock, "BetterEnd's note_block draw (three crops x four ages)");
    }

    /**
     * The tripwire pool used to be a KNOWN, PRE-EXISTING oversubscription (BE 93 + FD 30 + CE 7 =
     * 130 over a 126-state pool). Deleting blue_vine_fur -- 6 of the 12 generic states -- brought the
     * known total back inside it, at 124 of 126.
     * <p>
     * The numbers stay pinned at their new values instead of the assertion being dropped, because the
     * headroom is now TWO states: any pack that adds a tripwire draw re-creates the oversubscription
     * silently, and the sum is the only place that shows up. This still does NOT claim unknown packs
     * fit - it records the known holders (BetterEnd + FarmersDelight + CE default_assets) and that
     * they are two states from the edge.
     */
    @Test
    void theSharedTripwirePoolIsNowTwoStatesInsideItsBudgetAndStaysPinned() throws IOException {
        Map<String, Integer> used = autoStateUsage();
        int beTripwire = used.getOrDefault("lower_tripwire", 0)
                + used.getOrDefault("higher_tripwire", 0)
                + used.getOrDefault("tripwire", 0);
        // BE lower 49 + higher 32 + generic 6 (amaranita_fur only; blue_vine_fur's 6 are gone) = 87
        assertEquals(87, beTripwire,
                "a plant moved onto/off tripwire; the deleted blue_vine_fur held 6 generic states");
        assertEquals(124, beTripwire + FD_TRIPWIRE + CE_DEFAULT_TRIPWIRE,
                "the known-holder tripwire total (BE 87 + FD 30 + CE default 7) changed; 124 leaves"
                        + " two states of headroom, so a wider margin means a holder left without"
                        + " re-deciding who may draw on the pool");
        assertTrue(beTripwire + FD_TRIPWIRE + CE_DEFAULT_TRIPWIRE <= TRIPWIRE_POOL,
                "the known holders no longer fit the " + TRIPWIRE_POOL + "-state tripwire pool; that is"
                        + " the pre-existing oversubscription and it must be re-decided, not absorbed");
    }

    /**
     * The guard against the reverted design: BetterEnd must never pin the CANONICAL fence_gate half.
     * {@code mappings.yml} maps every {@code <wood>_fence_gate[..,powered=true]} to {@code [..,powered=false]},
     * so powered=false is the state every vanilla gate is remapped onto; pinning it writes this pack's gate model
     * into the vanilla blockstate (AbstractBlockManager.java:940-955) and repaints every vanilla gate. Scans the
     * WHOLE configuration tree (blocks AND templates/items/... -- not just blocks/), so a reintroduced
     * {@code betterend:*unpowered} template, a reference to one, or a template whose appearances pin
     * powered=false are all caught.
     */
    @Test
    void noBetterEndResourcePinsTheCanonicalFenceGateHalf() throws IOException {
        List<Path> files = configurationFiles();
        assertTrue(files.size() > 10, "only " + files.size() + " configuration files found - the scan is degenerate");
        Set<String> offenders = new TreeSet<>();
        for (Path file : files) {
            collectCanonicalPins(load(file), offenders);
        }
        assertEquals(Set.of(), offenders,
                "a BetterEnd resource pins the canonical fence_gate half (powered=false) or names an "
                        + "unpowered template; that hijacks the vanilla gate model - do not re-add it");
    }

    /** Proves the guard above is not vacuous: it must flag each violation shape, and clear a plain variants key. */
    @Test
    void theCanonicalHalfGuardActuallyCatchesAViolation() {
        Set<String> templatePin = new TreeSet<>();
        collectCanonicalPins(Map.of("state",
                "${fence_gate_base_block}[facing=east,in_wall=false,open=false,powered=false]"), templatePin);
        assertFalse(templatePin.isEmpty(), "the guard must flag a template appearance that pins powered=false");

        Set<String> concretePin = new TreeSet<>();
        collectCanonicalPins(Map.of("state", "oak_fence_gate[facing=east,in_wall=true,open=true,powered=false]"), concretePin);
        assertFalse(concretePin.isEmpty(), "the guard must flag a concrete powered=false pin");

        Set<String> templateKey = new TreeSet<>();
        collectCanonicalPins(Map.of("betterend:block_state/fence_gate_unpowered", Map.of()), templateKey);
        assertFalse(templateKey.isEmpty(), "the guard must flag an unpowered template KEY");

        Set<String> templateRef = new TreeSet<>();
        collectCanonicalPins(Map.of("template", "betterend:block_state/fence_gate_unpowered"), templateRef);
        assertFalse(templateRef.isEmpty(), "the guard must flag an unpowered template REFERENCE in a value");

        Set<String> clean = new TreeSet<>();
        collectCanonicalPins(Map.of("variants",
                Map.of("facing=east,in_wall=false,open=false,powered=false", Map.of())), clean);
        assertTrue(clean.isEmpty(), "a plain variants key is not a pinned state and must NOT be flagged");
    }

    private static void collectCanonicalPins(Object node, Set<String> into) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String k) {
                    if (k.contains("unpowered")) into.add(k);
                    collectCanonicalPins(k, into);
                }
                collectCanonicalPins(e.getValue(), into);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) collectCanonicalPins(v, into);
        } else if (node instanceof String s) {
            if (s.contains("unpowered")) into.add(s);
            // A PINNED state is `...[..,powered=false]`; a variants KEY (`..,powered=false`) has no `]`.
            // Match both the concrete form (oak_fence_gate[..]) and the template form (${fence_gate_base_block}[..]).
            if (s.contains("powered=false]") && s.contains("fence_gate")) into.add(s);
        }
    }

    // --- config_factory expansion ----------------------------------------------------------------

    /** Every concrete block id the pack defines, mapped to its fully-substituted definition. */
    private static Map<String, Map<String, Object>> expand() throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Path file : blockFiles()) {
            append(out, file);
        }
        assertTrue(out.size() > 100, "the expansion is degenerate: only " + out.size() + " blocks");
        return out;
    }

    /**
     * The openable family -- doors, trapdoors, fence gates, pressure plates -- as the pack ships it.
     * <p>
     * This used to be one file, {@code blocks/B3-openable.yml}, selected by filename; the blocks folder
     * is now the single merged {@code blocks.yml}, so the family is selected by its id suffix instead.
     * The suffix selection is what preserves the test's meaning: exactly the {@link #WOOD} families'
     * door/trapdoor/gate/plate plus the two metal door/trapdoor pairs, and nothing else.
     */
    private static Map<String, Map<String, Object>> expandOpenable() throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : expand().entrySet()) {
            if (OPENABLE_ID.matcher(e.getKey()).find()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The four openable id suffixes; {@code _door} also matches {@code _trapdoor}'s tail, hence {@code $}. */
    private static final Pattern OPENABLE_ID = Pattern.compile("_(door|trapdoor|gate|plate)$");

    /** Expanded {@code auto_state} counts per group across the whole pack. */
    private static Map<String, Integer> autoStateUsage() throws IOException {
        Map<String, Integer> used = new LinkedHashMap<>();
        for (Map<String, Object> def : expand().values()) {
            collectAutoState(def, used);
        }
        return used;
    }

    private static void append(Map<String, Map<String, Object>> out, Path file) throws IOException {
        Map<String, Object> doc = load(file);
        if (doc == null) return;
        for (Map.Entry<String, Object> top : doc.entrySet()) {
            String key = top.getKey();
            if (key.equals("block") || key.equals("blocks")
                    || key.startsWith("block#") || key.startsWith("blocks#")) {
                for (Map.Entry<String, Object> b : asMap(top.getValue()).entrySet()) {
                    out.put(b.getKey(), asMap(b.getValue()));
                }
            } else if (key.startsWith("config_factory")) {
                Map<String, Object> factory = asMap(top.getValue());
                Map<String, Object> blueprint = asMap(asMap(factory.get("blueprint")).get("blocks"));
                for (Object instance : asList(factory.get("instances"))) {
                    Map<String, Object> inst = asMap(instance);
                    for (Map.Entry<String, Object> b : blueprint.entrySet()) {
                        out.put((String) substitute(b.getKey(), inst), asMap(substitute(b.getValue(), inst)));
                    }
                }
            }
        }
    }

    private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)}");

    @SuppressWarnings("unchecked")
    private static Object substitute(Object node, Map<String, Object> instance) {
        if (node instanceof String s) {
            Matcher m = VAR.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                Object v = instance.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : String.valueOf(v)));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                // SnakeYAML resolves a bare `true:`/`false:` key to a Boolean; CE treats every key as text.
                copy.put(String.valueOf(substitute(e.getKey(), instance)), substitute(e.getValue(), instance));
            }
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object v : list) copy.add(substitute(v, instance));
            return copy;
        }
        return node;
    }

    /** Count one {@code auto_state} request per occurrence anywhere under a block's {@code state}/{@code states}. */
    private static void collectAutoState(Object node, Map<String, Integer> into) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("auto_state".equals(e.getKey()) && e.getValue() instanceof String g) {
                    into.merge(g, 1, Integer::sum);
                } else {
                    collectAutoState(e.getValue(), into);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) collectAutoState(v, into);
        }
    }

    // --- small helpers ---------------------------------------------------------------------------

    /** A block's state section; CraftEngine accepts both {@code state:} and {@code states:}. */
    private static Map<String, Object> stateOf(Map<String, Map<String, Object>> blocks, String id) {
        Map<String, Object> def = blocks.get(id);
        assertNotNull(def, id + " is not defined by the pack");
        Object state = def.containsKey("state") ? def.get("state") : def.get("states");
        assertNotNull(state, id + " has no state section");
        return asMap(state);
    }

    private static Map<String, Object> argumentsOf(Map<String, Object> state) {
        return asMap(state.get("arguments"));
    }

    private static Map<String, Object> mergesOf(Map<String, Object> def) {
        return asMap(asMap(def.get("settings")).get("merges"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static Map<String, Object> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    /**
     * Every block config file in {@code blocks/}. The folder used to hold ~18 batch files and now holds the
     * single merged {@code blocks.yml}, so the file count carries no meaning any more. Both shapes expand
     * through the same path: the merge kept the top-level keys intact, and {@link #append} walks them --
     * {@code config_factory#...} sections are instantiated, {@code blocks:} / {@code blocks#...:} map entries
     * are taken as-is, and the merged file's {@code items:} / {@code items#...:} / {@code recipes:} sections
     * are not blocks and are skipped there. Degeneracy is guarded by the expansion size in {@link #expand()}.
     */
    private static List<Path> blockFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.list(blocksDir())) {
            walk.filter(p -> p.toString().endsWith(".yml")).sorted().forEach(files::add);
        }
        assertFalse(files.isEmpty(), "no block config files found in " + blocksDir());
        return files;
    }

    /** Every YAML under the pack's whole configuration tree (blocks AND templates/items/langs/...). */
    private static List<Path> configurationFiles() throws IOException {
        Path root = locate("plugin/src/main/resources/craftengine/betterend/configuration");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yml"))
                    .sorted().forEach(files::add);
        }
        return files;
    }

    private static Path blocksDir() {
        return locate("plugin/src/main/resources/craftengine/betterend/configuration/blocks");
    }

    /** Walk up from the working dir until the repo-relative path is underfoot (tests run per module). */
    private static Path locate(String relative) {
        Path at = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && at != null; i++, at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
