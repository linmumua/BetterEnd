package dev.mumu.betterend;

import dev.mumu.betterend.config.SafeYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production upgrade whitelist, checked against the files it will actually run against -- no
 * server, no CraftEngine. Two things must stay true for the exact-hash upgrade to be worth anything:
 * the {@link BundledPackUpgrades#MANIFEST_YAML manifest} names exactly the files this build rewrites,
 * each with the hash of the pre-fix bytes (or a server already on that old default is never
 * upgraded), and the shipped bytes for those files really do differ from every hash it declares (or
 * the entry is a no-op that silently never fires). The old hashes are pinned as literals, measured
 * inside the delivered jars rather than assumed, per generation where the file differs between them.
 * <p>
 * Two multi-generation files are the interesting cases. B3-openable.yml carries one byte sequence in
 * 0.1.0..0.1.3 and another in 0.1.4..0.1.8 (0.1.9 rewrote it once more, and that generation is
 * already current), so BOTH older generations must be listed or those servers are stuck and
 * mislabelled as owner edits. B4-flora-plants.yml was corrected in 0.1.4 and is corrected AGAIN in
 * this build (its auto-state pool comment after the vine ids are removed), so both the 0.1.0..0.1.3
 * and the 0.1.4..0.1.9 defaults are listed -- otherwise every 0.1.4+ server's pristine default would
 * be called a user edit and never receive the recount. The single-value string form is kept for the
 * files whose bytes never changed across the delivered jars, which is also what proves the original
 * manifest shape is still accepted.
 * <p>
 * Six of the nine entries exist so the seven removed vine ids (blue_vine, bulb_vine, dense_vine,
 * jungle_vine, twisted_vine plus blue_vine_fur and blue_vine_lantern) actually leave an installed
 * pack: the blocks that declared them and the b5/b6 translations that named them are pristine old
 * defaults on every server running 0.1.0..0.1.9, and only a declared hash gets them replaced. What
 * those files must no longer CONTAIN after the upgrade is asserted by the flora / openable / language
 * contract tests; this test only owns the reachability of the upgrade itself.
 */
class BundledPackUpgradesContractTest {
    private static final String B3 = "configuration/blocks/B3-openable.yml";
    private static final String B4_PLANTS = "configuration/blocks/B4-flora-plants.yml";
    private static final String B4_TERRAIN = "configuration/blocks/B4-flora-terrain.yml";
    private static final String B4_CROPS = "configuration/blocks/B4-flora-crops-seeds.yml";
    /** SHA-256 of the 0.1.10..0.1.16 crops file, before the three age_3 appearances moved pools. */
    private static final String OLD_B4_CROPS = "f2d4f6d97534c803acc7ab22b9938b0fc4e8b072e651f3451b665c23fc2468bb";
    private static final String B5 = "configuration/blocks/B5-flora-b.yml";
    private static final String B6 = "configuration/blocks/B6-lanterns-stone.yml";
    private static final String LANG_B5_EN = "configuration/langs/b5_en_us.yml";
    private static final String LANG_B5_ZH = "configuration/langs/b5_zh_cn.yml";
    private static final String LANG_B6_EN = "configuration/langs/b6_en_us.yml";
    private static final String LANG_B6_ZH = "configuration/langs/b6_zh_cn.yml";

    /** Every whitelisted path, and nothing else -- the whole set is asserted, never a subset. */
    private static final Set<String> WHITELISTED = Set.of(
            B3, B4_CROPS, B4_PLANTS, B4_TERRAIN, B5, B6, LANG_B5_EN, LANG_B5_ZH, LANG_B6_EN, LANG_B6_ZH);

    /**
     * 本构建**已不再随附**的白名单条目：18 个分散的 blocks 文件被合并成单文件 {@code blocks.yml}。
     * <p>
     * 它们留在白名单里的作用变了：不再是「用新版覆盖旧版」，而是「磁盘上如果还是旧内置默认，
     * 就删掉这份残留」。因为合并后这些 id 已经在 {@code blocks.yml} 里声明，两份并存会让 CE
     * 重复声明同一批 id（{@code IdSectionConfigParser} 是 {@code byId.put}，后来者静默胜出）。
     * <p>
     * 4 个 lang 条目**不在此列** —— langs 目录保持 22 个文件，仍然随附。
     */
    private static final Set<String> REMOVED_FROM_BUILD = Set.of(B3, B4_CROPS, B4_PLANTS, B4_TERRAIN, B5, B6);

    /** 仍然随附、靠「旧版→新版」覆盖生效的白名单条目。 */
    private static final Set<String> STILL_SHIPPED = Set.of(LANG_B5_EN, LANG_B5_ZH, LANG_B6_EN, LANG_B6_ZH);

    /**
     * SHA-256 of the 0.1.0..0.1.3 (pre-fix) B3 bytes. Measured in all four delivered jars:
     * {@code unzip -p jars/BetterEnd-0.1.{0,1,2,3}-folia26.3.jar
     * craftengine/betterend/configuration/blocks/B3-openable.yml | sha256sum} agrees.
     */
    private static final String OLD_B3_013 = "a93ebc48ed3061f01e2a4827f545eb6ec1b159592aff368d4a22114f7aa1724b";
    /**
     * SHA-256 of the 0.1.4..0.1.8 B3 bytes -- the FIRST correction, which a later build rewrote
     * again (0.1.9). Measured inside all five delivered jars (they agree byte for byte), so a server
     * installed from any of them is still on a pristine old default and must be upgraded, not called
     * an edit. 0.1.9's own B3 hash is deliberately NOT listed: it is the shipped bytes, the
     * "already current" branch covers it, and declaring it would only disable the whole entry.
     */
    private static final String OLD_B3_014_018 = "00a6e45559cdbcf40608be1588be2aee0529a6607e644eb593e0c7532ad1089c";
    /** SHA-256 of the 0.1.0..0.1.3 (pre-fix) B4-flora-plants bytes. */
    private static final String OLD_B4_PLANTS_013 = "ff329c5060ada3b06656b96cf47eef0cbf82981a1c7e81bfd279a335b9f04d94";
    /**
     * SHA-256 of the 0.1.4..0.1.9 B4-flora-plants bytes -- what every currently installed server
     * holds. This build rewrites the file again (the auto-state pool comment after the vine ids are
     * removed), so this value must be listed exactly like an older generation: dropping it would make
     * the CURRENT bundled default look like an owner edit on every 0.1.4+ server. Measured on all six
     * of those jars, which agree byte for byte.
     */
    private static final String OLD_B4_PLANTS_014_019 = "bd4737afae65ab5adf4e5a0d16765477c186c400e6fa96183110c4ce023ac4c3";
    /**
     * SHA-256 of the pre-fix B4-flora-terrain bytes. Provenance was checked by unpacking the
     * delivered artifacts, not assumed: 0.1.0 through 0.1.5 all carry this exact byte sequence
     * ({@code unzip -p jars/BetterEnd-0.1.{0..5}-folia26.3.jar
     * craftengine/betterend/configuration/blocks/B4-flora-terrain.yml | sha256sum} agrees on all six),
     * so one pinned hash covers every version a server can still be running on those bytes; 0.1.6+
     * already ship the corrected file and need no entry. This test deliberately does NOT open the jar:
     * a future build of the same version number would overwrite it with the fixed bytes and turn the
     * assertion into a lie.
     */
    private static final String OLD_B4_TERRAIN_0105 = "a711e375f4cea7b62a3aaa7621e2329a430b788934b4e491cf246ed160bbce02";
    /** SHA-256 of the B5-flora-b.yml bytes -- identical in all ten delivered jars (0.1.0..0.1.9). */
    private static final String OLD_B5 = "76a5ca03205738be285d03beb58ed773dd9acad82e9c908a6a105ec59c052b0a";
    /** SHA-256 of the B6-lanterns-stone.yml bytes -- identical in all ten delivered jars. */
    private static final String OLD_B6 = "026c1961f912173b4cbccecf1903868e9779ecf7423b209dcf563caf7c30b3a3";
    /** SHA-256 of langs/b5_en_us.yml -- identical in all ten delivered jars. */
    private static final String OLD_LANG_B5_EN = "c5e0b4d83192ea978eda0d8ff60ab005fd9e129bc6bb5a6c34f9d0f55feb0ac4";
    /** SHA-256 of langs/b6_en_us.yml -- identical in all ten delivered jars. */
    private static final String OLD_LANG_B6_EN = "ee2fe23afb9767d54a250f6a1137007fd840063e07422213847b6e1fb64afe9b";
    /**
     * SHA-256 of langs/b5_zh_cn.yml in 0.1.4..0.1.9. This file does not exist in 0.1.0..0.1.3 (those
     * jars ship no Chinese translation for batch B5), so no older default exists to declare -- a
     * server that never had the file simply gets it from the completion pass, not from this upgrade.
     */
    private static final String OLD_LANG_B5_ZH = "bb84ab9450ac054fe6167f8cb0a708362d0fe34bff369363a7c55fa566717ce9";
    /** SHA-256 of langs/b6_zh_cn.yml in 0.1.4..0.1.9 (absent from 0.1.0..0.1.3, same as b5_zh_cn). */
    private static final String OLD_LANG_B6_ZH = "f773a4720ccdeef90256e7c44ef1f26701dedfd3ba40e6e4267edcb0455ee32b";

    @Test
    void theProductionManifestListsExactlyTheNineWhitelistedFilesWithEveryDeliveredOldDefault() {
        // Parsed the same way production() parses it, so a malformed entry (bad hash, escaping path)
        // fails this test rather than only blowing up at server boot.
        assertNotNull(BundledPackUpgrades.production(), "the production manifest must parse");

        Map<String, Object> manifest = SafeYaml.load(BundledPackUpgrades.MANIFEST_YAML);
        assertEquals(WHITELISTED, manifest.keySet(),
                "the whitelist must name exactly the files this build rewrites, and only them:"
                        + " the earlier corrections, the six files that carried the removed vine ids,"
                        + " and the crops file whose three age_3 appearances moved out of the full"
                        + " weeping_vines pool");

        // Multi-generation files: EVERY generation that is still an old default, oldest first. A
        // missing generation means those servers are stuck on the old bytes and called owner edits.
        assertEquals(List.of(OLD_B3_013, OLD_B3_014_018), manifest.get(B3),
                "B3 must accept BOTH older delivered generations as pristine old defaults, oldest first");
        assertEquals(List.of(OLD_B4_PLANTS_013, OLD_B4_PLANTS_014_019), manifest.get(B4_PLANTS),
                "B4-flora-plants is rewritten again, so it must accept the 0.1.0..0.1.3 default AND the"
                        + " 0.1.4..0.1.9 default every installed server currently holds");

        // Single-generation files keep the plain string form -- which is also the proof that the
        // original manifest shape is still accepted by the parser.
        assertEquals(OLD_B4_TERRAIN_0105, manifest.get(B4_TERRAIN),
                "B4-flora-terrain keeps the single-value string form (0.1.6+ already carry the fixed bytes)");
        assertEquals(OLD_B4_CROPS, manifest.get(B4_CROPS),
                "B4-flora-crops-seeds must accept the 0.1.10..0.1.16 default, which is what every"
                        + " server holding the three stuck age_3 appearances has on disk");
        assertEquals(OLD_B5, manifest.get(B5),
                "B5-flora-b was byte-identical in every delivered jar, so one value covers all generations");
        assertEquals(OLD_B6, manifest.get(B6),
                "B6-lanterns-stone was byte-identical in every delivered jar, so one value covers all");
        assertEquals(OLD_LANG_B5_EN, manifest.get(LANG_B5_EN),
                "b5_en_us was byte-identical in every delivered jar, so one value covers all generations");
        assertEquals(OLD_LANG_B6_EN, manifest.get(LANG_B6_EN),
                "b6_en_us was byte-identical in every delivered jar, so one value covers all generations");
        assertEquals(OLD_LANG_B5_ZH, manifest.get(LANG_B5_ZH),
                "b5_zh_cn first shipped in 0.1.4, so its only old default is that generation");
        assertEquals(OLD_LANG_B6_ZH, manifest.get(LANG_B6_ZH),
                "b6_zh_cn first shipped in 0.1.4, so its only old default is that generation");

        assertTrue(manifest.keySet().stream().allMatch(path -> path.startsWith("configuration/")),
                "all nine files belong to betterend's own config tree, never the other pack's");
    }

    @Test
    void everyWhitelistedFileShipsBytesThatDifferFromEveryOldDefaultItDeclares() throws IOException {
        Map<String, Object> manifest = SafeYaml.load(BundledPackUpgrades.MANIFEST_YAML);
        Path pack = up("plugin/src/main/resources/craftengine/betterend");
        for (String path : manifest.keySet()) {
            Path shipped = pack.resolve(path);
            if (REMOVED_FROM_BUILD.contains(path)) {
                // 已合并：本构建里不存在这个文件，白名单条目转为「删除旧残留」。
                assertFalse(Files.exists(shipped),
                        "'" + path + "' 已合并进 blocks.yml，本构建不应再随附它（否则会重复声明方块）："
                                + shipped);
                assertFalse(declaredHashes(manifest.get(path)).isEmpty(),
                        "'" + path + "' 是「删除旧残留」条目，必须保留旧哈希，否则无法判断磁盘上那份是否本插件原文件");
                continue;
            }
            assertTrue(Files.isRegularFile(shipped), "the whitelisted source file must be bundled: " + shipped);
            assertFalse(declaredHashes(manifest.get(path)).contains(sha256Of(shipped)),
                    "'" + path + "' is whitelisted to replace an old default, so the shipped bytes must"
                            + " actually differ from every hash it declares -- otherwise the entry falls into"
                            + " the 'already current' branch, never fires, and the correction never reaches a"
                            + " server that still runs the old bytes");
        }
    }

    @Test
    void everyDeclaredHashIsALowercaseSha256ThatNamesExactlyOneWhitelistedFile() {
        Map<String, Object> manifest = SafeYaml.load(BundledPackUpgrades.MANIFEST_YAML);
        Map<String, String> ownerOf = new HashMap<>();
        for (Map.Entry<String, Object> entry : manifest.entrySet()) {
            List<String> hashes = declaredHashes(entry.getValue());
            assertFalse(hashes.isEmpty(), "'" + entry.getKey() + "' must declare at least one old default");
            assertEquals(new HashSet<>(hashes).size(), hashes.size(),
                    "'" + entry.getKey() + "' must not declare the same old default twice");
            for (String hash : hashes) {
                assertTrue(hash.matches("[0-9a-f]{64}"),
                        "'" + entry.getKey() + "' declares an old default that is not a lowercase SHA-256: "
                                + hash + " -- a value the parser would refuse at server boot");
                String previous = ownerOf.put(hash, entry.getKey());
                assertNull(previous, "SHA-256 " + hash + " is declared for both '" + previous + "' and '"
                        + entry.getKey() + "'; a pasted hash would whitelist the wrong file for one of them");
            }
        }
    }

    /** The manifest value as a list: a bare string (single-value form) or a YAML sequence. */
    private static List<String> declaredHashes(Object value) {
        if (value instanceof String hash) {
            return List.of(hash);
        }
        assertTrue(value instanceof List<?>, "an old default is a SHA-256 string or a list of them, not " + value);
        return ((List<?>) value).stream().map(String.class::cast).toList();
    }

    private static String sha256Of(Path file) throws IOException {
        return BundledPackUpgrades.sha256(Files.readAllBytes(file));
    }

    /** Same walk-up as the other contract tests: the test may run from either module's directory. */
    private static Path up(String relative) {
        Path at = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && at != null; i++, at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
