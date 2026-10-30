package dev.mumu.betterend.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resource contract for the zh_cn half of the CraftEngine client localisation in
 * {@code configuration/langs/}.
 * <p>
 * WHY this exists: BetterEnd's blocks and items are renamed client-side, so a player whose client
 * runs zh_cn reads the raw English string from the resource pack unless a zh_cn translation is
 * baked in. The English side is the byte-for-byte copy of the mod's {@code en_us.json}; the Chinese
 * side is hand-maintained. Nothing at runtime notices a half-translated file -- CraftEngine simply
 * falls back to English per key -- so a dropped key, a typo'd language code or a mangled colour code
 * would only ever show up as "some items are English again" on a live server. These assertions are
 * the only place that can fail.
 * <p>
 * The three CraftEngine behaviours the assertions lean on (pinned commit b3079c5):
 * <ul>
 *   <li>{@code LangParser.parseSection} (TranslationManagerImpl.java:524-531) treats every key
 *       directly under a {@code lang#...} section as a language id and the section's leaves as
 *       {@code translationKey -> text}; sections from different files accumulate into one map per
 *       language instead of replacing it ({@code addClientTranslation}, :351-368). That is why one
 *       file per language per batch both works and mirrors the English layout;</li>
 *   <li>{@code addClientTranslation} only accepts a language id that is in
 *       {@code internal/lang/processed.json}, and otherwise falls through to a country lookup that
 *       matches nothing and drops the whole bundle SILENTLY (:358-366). {@code zh_cn} is in that
 *       list; {@code zh_CN} is not, so the spelling is a contract, not a style choice;</li>
 *   <li>{@code generateClientLang} (AbstractPackManager.java:3227-3255) writes one json per
 *       clientLangData key at {@code assets/minecraft/lang/<id>.json}, so the id also has to be the
 *       one the vanilla client actually asks for.</li>
 * </ul>
 */
class LangTranslationContractTest {
    /** Modern Minecraft locale ids are lower-case and underscore-separated; see processed.json. */
    private static final String ZH_CN = "zh_cn";
    /** The suffix the Chinese files add to every section id so they never reuse an English id. */
    private static final String SECTION_SUFFIX = "_zh_cn";
    /** The English half, always {@code <batch>_en_us.yml} / {@code en_us.yml}. */
    private static final String EN_SUFFIX = "_en_us.yml";

    /**
     * Keys whose Chinese text is legitimately identical to the English one: "BetterEnd" is the mod's
     * name and is not translated anywhere in this pack. Kept as an explicit list so that every OTHER
     * untranslated value fails -- a stale value is exactly the failure mode this test is for.
     */
    private static final Set<String> BRAND_VALUE_KEYS = Set.of(
            "advancements.betterend.root.title",
            "category.betterend.name");

    /** Scale guards: a silently emptied directory would otherwise make every assertion vacuously pass. */
    private static final int MIN_FILES_PER_LANGUAGE = 11;
    private static final int MIN_DISTINCT_KEYS = 600;

    // --- the contract -----------------------------------------------------------------------------

    @Test
    void everyEnglishLangFileHasATranslatedCounterpart() throws IOException {
        Map<String, Path> en = byBatch(enFiles());
        Map<String, Path> zh = byBatch(zhFiles());
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(zh.keySet()),
                "configuration/langs must hold the same batches in both languages; a batch present "
                        + "in only one language would show up as a partially English item list");
        assertEquals(MIN_FILES_PER_LANGUAGE, en.size(), "the lang file count changed");
    }

    @Test
    void everyEnglishKeyIsTranslatedUnderTheSameSection() throws IOException {
        Map<String, LangFile> en = parseAll(enFiles());
        Map<String, LangFile> zh = parseAll(zhFiles());
        List<String> problems = new ArrayList<>();
        for (String batch : en.keySet()) {
            LangFile e = en.get(batch);
            LangFile z = zh.get(batch);
            assertNotNull(z, batch + " has no zh_cn counterpart");
            Set<String> missing = new TreeSet<>(e.entries().keySet());
            missing.removeAll(z.entries().keySet());
            Set<String> extra = new TreeSet<>(z.entries().keySet());
            extra.removeAll(e.entries().keySet());
            // Both directions: a missing key stays English on the client, an extra key is a rename
            // that silently orphaned the English one.
            if (!missing.isEmpty()) problems.add(batch + " missing " + missing.size() + ": " + first(missing));
            if (!extra.isEmpty()) problems.add(batch + " extra " + extra.size() + ": " + first(extra));
        }
        assertEquals(List.of(), problems, "zh_cn does not cover the English keys exactly");
    }

    @Test
    void everyTranslatedValueIsNonBlankAndActuallyChinese() throws IOException {
        Map<String, LangFile> en = parseAll(enFiles());
        Map<String, LangFile> zh = parseAll(zhFiles());
        List<String> problems = new ArrayList<>();
        Set<String> untranslatedBrand = new TreeSet<>();
        for (String batch : zh.keySet()) {
            LangFile z = zh.get(batch);
            LangFile e = en.get(batch);
            for (Map.Entry<String, String> entry : z.entries().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                String source = e.entries().get(key);
                if (value == null || value.isBlank()) {
                    problems.add(batch + " blank value for " + key);
                    continue;
                }
                if (source != null && source.equals(value)) {
                    if (BRAND_VALUE_KEYS.contains(key)) untranslatedBrand.add(key);
                    else problems.add(batch + " still English: " + key + " = " + value);
                }
            }
        }
        assertEquals(List.of(), problems, "every key must carry a Chinese value");
        assertEquals(new TreeSet<>(BRAND_VALUE_KEYS), untranslatedBrand,
                "the brand-name whitelist and the keys that actually stay identical have diverged; "
                        + "either a value was left untranslated or a name really is a proper noun");
    }

    /**
     * {@code zh_CN}, {@code zh-CN} and {@code cn} all look right and are all dropped by
     * CraftEngine without a log line (see the class comment), so the spelling is asserted literally.
     */
    @Test
    void onlyTheZhCnLanguageKeyIsDeclared() throws IOException {
        for (LangFile file : parseAll(zhFiles()).values()) {
            String problem = languageKeyProblem(file.languageKeys());
            assertEquals(null, problem, file.path().getFileName() + ": " + problem);
        }
    }

    /**
     * Proves the language-code guard is not vacuous, without touching the shipped files: the exact
     * misspellings that CraftEngine swallows (TranslationManagerImpl.java:358-366) must be rejected,
     * and the one accepted spelling must pass.
     */
    @Test
    void theLanguageCodeGuardRejectsEveryLookAlike() {
        assertNotNull(languageKeyProblem(Set.of("zh_CN")), "zh_CN is not in processed.json");
        assertNotNull(languageKeyProblem(Set.of("zh-CN")), "zh-CN is not the locale id form");
        assertNotNull(languageKeyProblem(Set.of("cn")), "cn is not a Minecraft locale id");
        assertNotNull(languageKeyProblem(Set.of("zh_cn", "en_us")), "one section carries one language");
        assertNotNull(languageKeyProblem(Set.of()), "an empty section translates nothing");
        assertEquals(null, languageKeyProblem(Set.of(ZH_CN)), "zh_cn is the id the client asks for");
    }

    /**
     * A section id is the text before (and including) {@code #} plus a free suffix. Two files that
     * declare the SAME {@code lang#...} id would both be parsed, but the batch files' own convention
     * (see b1_en_us.yml:3-5) is that ids stay unique per file so the group can be pointed at by name.
     * This pins that convention for the Chinese half.
     */
    @Test
    void chineseSectionIdsDoNotCollideWithTheEnglishOnes() throws IOException {
        Set<String> en = allSectionIds(enFiles());
        Set<String> zh = allSectionIds(zhFiles());
        Set<String> shared = new TreeSet<>(en);
        shared.retainAll(zh);
        assertEquals(Set.of(), shared, "a zh_cn section reuses an English section id");
        for (String id : zh) {
            assertTrue(id.endsWith(SECTION_SUFFIX),
                    id + " should carry the " + SECTION_SUFFIX + " suffix");
        }
        Set<String> all = new TreeSet<>(en);
        all.addAll(zh);
        assertEquals(en.size() + zh.size(), all.size(), "two lang files declare the same section id");
    }

    /**
     * Legacy {@code §} colour codes are the part a translator is most likely to delete: CraftEngine
     * runs every value through legacy -> MiniMessage -> legacy (TranslationManagerImpl
     * .LangFormatter, :487-491), so a lost {@code §r} repaints the rest of the tooltip. The code
     * SEQUENCE is compared, not just the count, because reordering them is just as visible.
     */
    @Test
    void legacyColourCodeSequenceSurvivesTranslation() throws IOException {
        Map<String, LangFile> en = parseAll(enFiles());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, LangFile> batch : parseAll(zhFiles()).entrySet()) {
            LangFile e = en.get(batch.getKey());
            for (Map.Entry<String, String> entry : batch.getValue().entries().entrySet()) {
                String source = e.entries().get(entry.getKey());
                if (source == null) continue;
                List<String> expected = colourCodes(source);
                List<String> actual = colourCodes(entry.getValue());
                if (!expected.equals(actual)) {
                    problems.add(batch.getKey() + " " + entry.getKey() + " expected " + expected
                            + " but was " + actual);
                }
            }
        }
        assertEquals(List.of(), problems, "legacy colour codes must be carried over unchanged");
    }

    /**
     * No value may carry an unclosed MiniMessage tag. Nothing in this file set uses tags today (the
     * colouring is legacy-{@code §}), so this is a guard for the next edit rather than a fix.
     */
    @Test
    void noValueCarriesAnUnclosedMiniMessageTag() throws IOException {
        List<String> problems = new ArrayList<>();
        for (LangFile file : parseAll(zhFiles()).values()) {
            for (Map.Entry<String, String> entry : file.entries().entrySet()) {
                String unbalanced = unbalancedTag(entry.getValue());
                if (unbalanced != null) {
                    problems.add(file.path().getFileName() + " " + entry.getKey() + " -> " + unbalanced);
                }
            }
        }
        assertEquals(List.of(), problems, "unclosed MiniMessage tag in a zh_cn value");
    }

    /** Proves the guard above is not vacuous: it must catch an unclosed tag and clear a balanced pair. */
    @Test
    void theMiniMessageGuardActuallyCatchesAViolation() {
        assertNotNull(unbalancedTag("<white>灯笼"), "an opening tag with no closer must be flagged");
        assertNotNull(unbalancedTag("灯笼</white>"), "a closer with no opener must be flagged");
        assertNotNull(unbalancedTag("<white><i>灯笼</white></i>"), "mis-nesting must be flagged");
        assertEquals(null, unbalancedTag("<white><i>灯笼</i></white>"), "a balanced pair must pass");
        assertEquals(null, unbalancedTag("§b音乐唱片§r"), "legacy codes are not MiniMessage tags");
    }

    /** The scan must not silently pass because the directory moved or emptied. */
    @Test
    void theScanSeesTheWholeLanguageSet() throws IOException {
        Map<String, LangFile> zh = parseAll(zhFiles());
        Set<String> keys = new TreeSet<>();
        zh.values().forEach(f -> keys.addAll(f.entries().keySet()));
        assertTrue(keys.size() >= MIN_DISTINCT_KEYS,
                "only " + keys.size() + " distinct zh_cn keys found - the scan is degenerate");
        int sections = zh.values().stream().mapToInt(f -> f.sectionIds().size()).sum();
        assertEquals(21, sections, "the zh_cn section count changed (English has 21)");
    }

    // --- parsing -----------------------------------------------------------------------------------

    /** One lang file: its section ids, the language ids declared under them, and every leaf. */
    private record LangFile(Path path, List<String> sectionIds, Set<String> languageKeys,
                            Map<String, String> entries) {
    }

    private static Map<String, LangFile> parseAll(List<Path> files) throws IOException {
        Map<String, LangFile> out = new TreeMap<>();
        for (Path file : files) {
            out.put(batchOf(file), read(file));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static LangFile read(Path file) throws IOException {
        Map<String, Object> doc = load(file);
        assertNotNull(doc, file + " is empty");
        List<String> sectionIds = new ArrayList<>();
        Set<String> languageKeys = new TreeSet<>();
        Map<String, String> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Object> section : doc.entrySet()) {
            sectionIds.add(section.getKey());
            if (!(section.getValue() instanceof Map)) continue;
            for (Map.Entry<String, Object> lang : ((Map<String, Object>) section.getValue()).entrySet()) {
                languageKeys.add(lang.getKey());
                if (!(lang.getValue() instanceof Map)) continue;
                for (Map.Entry<String, Object> leaf : ((Map<String, Object>) lang.getValue()).entrySet()) {
                    entries.put(leaf.getKey(), String.valueOf(leaf.getValue()));
                }
            }
        }
        return new LangFile(file, List.copyOf(sectionIds), Set.copyOf(languageKeys), Map.copyOf(entries));
    }

    private static Set<String> allSectionIds(List<Path> files) throws IOException {
        Set<String> ids = new TreeSet<>();
        for (Path file : files) ids.addAll(read(file).sectionIds());
        return ids;
    }

    /**
     * Legacy colour codes as an ordered list, e.g. {@code ["b", "r"]} for {@code §b音乐唱片§r}. A
     * trailing {@code §} with nothing after it is kept as the empty code so it cannot slip through.
     */
    private static List<String> colourCodes(String value) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '\u00a7') continue;
            codes.add(i + 1 < value.length() ? String.valueOf(value.charAt(i + 1)) : "");
            i++;
        }
        return codes;
    }

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z!#][^<>]*)>");

    /**
     * The first unmatched MiniMessage tag in {@code value}, or {@code null}. Both {@code <} and
     * {@code >} counts are checked so a bare {@code >} cannot hide, and tag names must match.
     */
    private static String unbalancedTag(String value) {
        long opens = value.chars().filter(c -> c == '<').count();
        long closes = value.chars().filter(c -> c == '>').count();
        if (opens != closes) return "unbalanced '<'/'>' (" + opens + " vs " + closes + ")";
        Deque<String> stack = new ArrayDeque<>();
        Matcher m = TAG.matcher(value);
        while (m.find()) {
            String name = m.group(2).split("[:\\s]", 2)[0].toLowerCase();
            if (m.group(1).isEmpty()) {
                stack.push(name);
            } else if (stack.isEmpty() || !stack.pop().equals(name)) {
                return "closing </" + name + "> does not match the open tag";
            }
        }
        return stack.isEmpty() ? null : "unclosed <" + stack.peek() + ">";
    }

    /**
     * Why a section's declared language ids are wrong, or {@code null} when they are not. Pure so the
     * guard can be exercised on the misspellings that matter without editing a shipped file.
     */
    private static String languageKeyProblem(Set<String> languageKeys) {
        if (languageKeys.isEmpty()) return "declares no language";
        if (!languageKeys.equals(Set.of(ZH_CN))) {
            return "must declare exactly one language, spelled `" + ZH_CN + "`, but declared "
                    + new TreeSet<>(languageKeys)
                    + " - any other spelling is silently discarded by addClientTranslation";
        }
        return null;
    }

    private static String first(Set<String> values) {        return values.stream().limit(3).reduce((a, b) -> a + ", " + b).orElse("") + (values.size() > 3 ? ", ..." : "");
    }

    /** {@code b1_en_us.yml} -> {@code b1}; the unbatched {@code en_us.yml} / {@code zh_cn.yml} -> {@code ""}. */
    private static String batchOf(Path file) {
        return batchOf(file.getFileName().toString());
    }

    private static String batchOf(String name) {
        if (name.equals("en_us.yml") || name.equals(ZH_CN + ".yml")) return "";
        for (String suffix : List.of(EN_SUFFIX, "_" + ZH_CN + ".yml")) {
            if (name.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
        }
        throw new IllegalArgumentException("not a BetterEnd lang file name: " + name);
    }

    private static Map<String, Path> byBatch(List<Path> files) {
        Map<String, Path> out = new TreeMap<>();
        for (Path file : files) out.put(batchOf(file), file);
        return out;
    }

    // --- filesystem --------------------------------------------------------------------------------

    private static List<Path> enFiles() throws IOException {
        return langFiles(EN_SUFFIX);
    }

    private static List<Path> zhFiles() throws IOException {
        return langFiles("_" + ZH_CN + ".yml");
    }

    private static List<Path> langFiles(String suffix) throws IOException {
        Path dir = langsDir();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .forEach(files::add);
        }
        // The unbatched file is plain `en_us.yml` / `zh_cn.yml`, so it does not match the suffix.
        Path unbatched = dir.resolve(suffix.equals(EN_SUFFIX) ? "en_us.yml" : ZH_CN + ".yml");
        if (Files.exists(unbatched)) {
            files.add(unbatched);
            files.sort(null);
        }
        assertTrue(files.size() >= MIN_FILES_PER_LANGUAGE,
                "only " + files.size() + " '" + suffix + "' files under " + dir);
        return files;
    }

    private static Path langsDir() {
        return locate("plugin/src/main/resources/craftengine/betterend/configuration/langs");
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

    private static Map<String, Object> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }
}
