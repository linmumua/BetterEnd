package dev.mumu.betterend.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every BetterEnd stone family is dragon-immune BY DESIGN in the mod -- {@code StoneMaterial.java:50}
 * is an unconditional {@code .addTags(BlockTags.DRAGON_IMMUNE)} on the whole family and
 * {@code Ore.java:72} does the same for every ore -- and the mod's generated
 * {@code data/minecraft/tags/block/dragon_immune.json} lists 125 BetterEnd ids because of it. Our
 * pack shipped ZERO, so the dragon smashed BetterEnd stone anywhere it flew.
 * <p>
 * The tag rides on the CE block, not on the donor vanilla state: the world stores CE's own generated
 * {@code craftengine:block_N} ({@code BukkitBlockManager.java:414-424}), {@code state.is(TagKey)}
 * resolves through {@code BlockStateBase.typeHolder()} to that block's registry holder
 * ({@code BlockBehaviour.java:986-988}), and {@code BukkitBlockManager.java:361-366} writes
 * {@code settings.tags} onto exactly that holder. So {@code note_block} not being in the tag is
 * irrelevant. This test guards the pack side of that: the ids, not the mechanism.
 * <p>
 * NOTE: a datapack {@code /reload} wipes CE's block tags ({@code MappedRegistry.refreshTagsInHolders}
 * rebinds every holder from datapack contents and CE has no re-apply hook), so immunity is gone
 * until {@code /ce reload}. Nothing testable here; it is an ops note.
 */
class DragonImmuneTagTest {
    private static final String IMMUNE = "minecraft:dragon_immune";

    /** The 7 families {@code StoneMaterial} builds, and therefore tags wholesale. */
    private static final List<String> FAMILIES = List.of(
            "flavolite", "violecite", "sulphuric_rock", "virid_jadestone",
            "azure_jadestone", "sandy_jadestone", "umbralith");

    /**
     * The mod's rule, re-derived rather than copied: {@code StoneMaterial.java:50} tags every shape
     * of every family; {@code Ore.java:72} tags every ore complex-material; and
     * {@code BlockTagProvider.java:129-134} adds a closed manual list of four. Intersected with the
     * shapes we actually define -- slabs, stairs, walls, buttons, plates and furnaces were skipped
     * for donor-state cost ({@code stone_sets.yml:3-4}, {@code B3-openable.yml:12-13}), and
     * {@code flavolite_runed_eternal} was never ported ({@code B8-storage-misc.yml:733}).
     */
    private static Set<String> modImmuneThatWeDefine() {
        Set<String> expected = new TreeSet<>();
        for (String family : FAMILIES) {
            for (String shape : List.of("", "_polished", "_tiles", "_brick", "_pillar",
                    "_lantern", "_pedestal", "_flower_pot")) {
                expected.add("betterend:" + family + shape);
            }
        }
        expected.add("betterend:eternal_pedestal");
        expected.add("betterend:flavolite_runed");
        expected.add("betterend:mossy_obsidian");
        expected.add("betterend:ender_ore");
        expected.add("betterend:thallasium_ore");
        return expected;
    }

    /**
     * Three blueprints mix immune and non-immune instances -- lanterns, pedestals and flower pots
     * each carry vanilla-stone variants alongside the BetterEnd families -- and one shared
     * {@code tags:} line cannot discriminate. Option B: tag them all, and pin the over-tag here so
     * it stays a decision rather than drift. Every one is a player-crafted decoration that never
     * generates in the End; {@code end_stone_lantern} and {@code end_stone_flower_pot} sit on
     * {@code minecraft:end_stone}, which vanilla already puts in the tag.
     * <p>
     * ponytail: exact is a per-instance {@code ${dragon}} argument, 37 new instance lines against
     * these 3 shared ones. Do it only if a decorative block being dragon-proof ever matters.
     */
    private static final Set<String> DELIBERATE_OVER_TAGS = new TreeSet<>(List.of(
            "betterend:andesite_lantern", "betterend:andesite_pedestal",
            "betterend:blackstone_lantern", "betterend:diorite_lantern",
            "betterend:diorite_pedestal", "betterend:end_stone_flower_pot",
            "betterend:end_stone_lantern", "betterend:granite_lantern",
            "betterend:granite_pedestal", "betterend:infusion_pedestal",
            "betterend:purple_lantern", "betterend:purple_pedestal",
            "betterend:quartz_lantern", "betterend:quartz_pedestal",
            "betterend:smaragdant_crystal_pedestal"));

    @Test
    void everyBlockTheModCallsDragonImmuneCarriesTheTag() throws IOException {
        Pack pack = Pack.read();
        assertTrue(pack.defined.size() > 300,
                "only " + pack.defined.size() + " block ids parsed - the expansion is degenerate");

        Set<String> expected = modImmuneThatWeDefine();
        for (String id : expected) {
            assertTrue(pack.defined.contains(id),
                    "the pack no longer defines " + id + "; update FAMILIES or the shape list");
        }
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(pack.immune);
        assertEquals(Set.of(), missing, "the dragon still smashes these");

        Set<String> extra = new TreeSet<>(pack.immune);
        extra.removeAll(expected);
        assertEquals(DELIBERATE_OVER_TAGS, extra,
                "an id is dragon-immune that neither the mod nor the recorded over-tag list allows");
    }

    /**
     * The over-tag is a shared-blueprint side effect, not a licence. {@code amber_ore} is the one id
     * that looks like it should be immune and is not: {@code EndOreBlocks.java:88} defines it with
     * {@code EndBlocks.defineBlock}, NOT as an {@code Ore} complex-material, so {@code Ore.java:72}
     * never runs on it and {@code BlockTagProvider.java:118} only puts it in {@code addEndGround}.
     */
    @Test
    void blocksTheModLeavesBreakableStayBreakable() throws IOException {
        Pack pack = Pack.read();
        for (String id : List.of("betterend:amber_ore", "betterend:endstone_dust",
                "betterend:dragon_bone_block", "betterend:thallasium_block")) {
            assertTrue(pack.defined.contains(id), "the pack no longer defines " + id);
            assertTrue(!pack.immune.contains(id), id + " is dragon-immune and the mod says it is not");
        }
        assertTrue(pack.immune.size() < pack.defined.size() / 2,
                "over half the pack is dragon-immune - a tag leaked onto a shared template");
    }

    // --- the pack --------------------------------------------------------------------------------

    /**
     * Expands every {@code config_factory} blueprint against its own {@code instances} list and
     * collects the block ids and their tags. Only the id keys need {@code ${var}} substitution: the
     * dragon tag is always written inline in the block's own {@code settings}, never through an
     * instance argument.
     */
    private record Pack(Set<String> defined, Set<String> immune) {
        private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)}");

        static Pack read() throws IOException {
            Set<String> defined = new LinkedHashSet<>();
            Set<String> immune = new LinkedHashSet<>();
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.list(blocksDir())) {
                walk.filter(p -> p.toString().endsWith(".yml")).sorted().forEach(files::add);
            }
            // blocks/ 已由 18 个文件合并成单个 blocks.yml，所以「找到多少个文件」不再是扫描完整性的
            // 指标；退化守卫在下面：每个期望 id 都必须被解析到，且 defined.size() > 300。
            assertTrue(!files.isEmpty(), "no block config file found in " + blocksDir());
            for (Path file : files) {
                Map<String, Object> doc;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    doc = new Yaml().load(reader);
                }
                if (doc == null) continue;
                for (Map.Entry<String, Object> top : doc.entrySet()) {
                    if (top.getKey().equals("blocks")) {
                        collect(asMap(top.getValue()), Map.of(), defined, immune);
                    } else if (top.getKey().startsWith("config_factory")) {
                        Map<String, Object> factory = asMap(top.getValue());
                        Map<String, Object> blocks =
                                asMap(asMap(factory.get("blueprint")).get("blocks"));
                        for (Object instance : asList(factory.get("instances"))) {
                            collect(blocks, asMap(instance), defined, immune);
                        }
                    }
                }
            }
            return new Pack(defined, immune);
        }

        private static void collect(Map<String, Object> blocks, Map<String, Object> instance,
                                    Set<String> defined, Set<String> immune) {
            for (Map.Entry<String, Object> block : blocks.entrySet()) {
                String id = substitute(block.getKey(), instance);
                defined.add(id);
                if (tagsOf(asMap(block.getValue()).get("settings")).contains(IMMUNE)) immune.add(id);
            }
        }

        /**
         * The tags a block declares: inline under {@code settings}, or under {@code merges} /
         * {@code overrides}, which is where a block on a shared template adds to the template's own
         * list ({@code deepMergeMaps} concatenates, {@code TemplateManagerImpl.java:385-388}).
         */
        private static List<String> tagsOf(Object settings) {
            Map<String, Object> map = asMap(settings);
            List<String> tags = new ArrayList<>();
            for (Object node : List.of(map, asMap(map.get("merges")), asMap(map.get("overrides")))) {
                for (Object tag : asList(asMap(node).get("tags"))) tags.add(String.valueOf(tag));
            }
            return tags;
        }

        private static String substitute(String key, Map<String, Object> instance) {
            Matcher matcher = VAR.matcher(key);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                Object value = instance.get(matcher.group(1));
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement(value == null ? matcher.group() : String.valueOf(value)));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object value) {
            return value instanceof Map ? (Map<String, Object>) value : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static List<Object> asList(Object value) {
            return value instanceof List ? (List<Object>) value : List.of();
        }

        /** Same walk-up as {@code BetterEndConfigTest.configYml}: tests may run from either module. */
        private static Path blocksDir() {
            Path at = Path.of("").toAbsolutePath();
            for (int up = 0; up < 5 && at != null; up++, at = at.getParent()) {
                Path candidate = at.resolve(
                        "plugin/src/main/resources/craftengine/betterend/configuration/blocks");
                if (Files.isDirectory(candidate)) return candidate;
            }
            throw new IllegalStateException("pack blocks/ not found from " + Path.of("").toAbsolutePath());
        }
    }
}
