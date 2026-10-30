package dev.mumu.betterend;

import dev.mumu.betterend.config.SafeYaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lands an exact, opt-in upgrade of a few bundled CraftEngine resource files onto an existing pack,
 * without the blanket overwrite {@link PackInstaller} deliberately refuses.
 *
 * <h2>Why this exists</h2>
 * {@link PackInstaller} never overwrites a file that is already on disk (its "complete missing,
 * never-overwrite" contract). That is exactly right for an owner's edits, but it also means a
 * corrected bundled default cannot reach a server that already has the old one: the fix ships in the
 * jar and the server keeps the stale file. This class closes that gap for a <em>named</em> set of
 * files only, and refuses to touch anything a human might have changed.
 *
 * <h2>The rule</h2>
 * A file is upgraded only when ALL of these hold:
 * <ul>
 *   <li>it is listed in the {@link #MANIFEST_YAML manifest} with the SHA-256 of the OLD bundled
 *       default -- one hash per upstream generation, because the same file differs between
 *       delivered jars (e.g. B3-openable.yml is one byte sequence in {@code 0.1.0}..{@code 0.1.3}
 *       and another in {@code 0.1.4}..{@code 0.1.8});</li>
 *   <li>the bytes on disk still hash to ONE of those declared OLD values -- i.e. the file is a
 *       pristine, unedited copy of some delivered old default, not something the owner touched;</li>
 *   <li>the new bundled bytes differ from what is on disk (so an unchanged jar is a no-op).</li>
 * </ul>
 * When the disk bytes are neither any declared old default nor the new bundled default they are
 * treated as the owner's and left byte-for-byte alone, with a loud manual-merge warning. A file that
 * is absent, or already current, is simply skipped -- and in every skipped case no backup is taken.
 *
 * <h2>Backup, and failing honestly</h2>
 * The old default is copied under the BetterEnd plugin data folder
 * ({@code resource-backups/<pack>/<relative-path>}), never inside CraftEngine's own config tree, so
 * CraftEngine never re-parses a backup as a live resource. The backup is taken BEFORE the write: if
 * it fails, the file is not overwritten at all (a failure is reported, {@code failed>0}, never
 * swallowed as success), and the write itself is atomic so a crash cannot truncate the file the
 * server reads next boot. The backup is atomic too, and an existing backup is verified by hash
 * against the declared OLD defaults before it is reused -- a truncated (disk-full) or otherwise
 * unexpected backup can never masquerade as valid; a mismatch fails loud and keeps every original.
 * A backup that matches ANY declared old default is reused untouched and never overwritten, so an
 * upgrade that hops two generations at once cannot destroy the older backup.
 *
 * <h2>Every path goes through the same gate</h2>
 * The live file and the backup are both resolved through {@link ResourcePaths}, which refuses
 * traversal, absolute shapes and any symlinked ancestor, so a link planted inside the pack cannot
 * redirect this write elsewhere on the machine; the bytes land through {@link AtomicFiles#replace}.
 * A file that is a symbolic link (dangling or not) or a directory where a whitelisted file belongs is
 * a contradiction, not an "absent" file: it is reported as {@code failed} and reported loudly, never
 * silently skipped, because skipping it would leave the corrected default unapplied forever.
 *
 * <h2>The manifest</h2>
 * {@link #MANIFEST_YAML} is a YAML mapping from a relative path to either one old SHA-256
 * ({@code relative-path: sha256}, the original single-value form, still accepted) or a list of them
 * ({@code relative-path: [sha256, sha256]}), parsed through
 * {@link SafeYaml} (the same hostile-input settings every other config uses). The list form is what
 * lets ONE build upgrade every generation a server can still be running: the same file is not
 * byte-identical across delivered jars, so a single pinned hash would silently write nothing on the
 * other generations (and wrongly call those files owner edits). It lists only the nine files the
 * build actually rewrites, each with the hashes of the pre-fix files it replaces -- never the whole
 * pack. The caller scopes this whitelist to the {@code betterend} pack
 * only, so an entry can never be applied to the other bundled pack (whose paths could otherwise
 * collide). Plain {@code java.nio}, so all of it unit-tests without a server.
 */
public final class BundledPackUpgrades {

    /**
     * The upgrade whitelist: a YAML mapping from a '/'-separated path (relative to the pack root,
     * the same shape {@link PackInstaller.Source#paths()} uses) to the lowercase SHA-256 (a single
     * value, or a list) of every OLD bundled default this build intends to replace.
     * <p>
     * Nine files are listed -- the three block files corrected before this build, plus the six that
     * carried the seven vine ids removed in this build (B5/B6 blocks and their b5/b6 translations).
     * Every hash was measured inside the delivered jars ({@code unzip -p jars/BetterEnd-<version>.jar
     * craftengine/betterend/<path> | sha256sum}) rather than assumed, and measured PER GENERATION,
     * because those files are not byte-identical across versions. The whole set of distinct hashes
     * each file ever shipped with was collected, not sampled:
     * <ul>
     *   <li>B3-openable.yml: {@code a93ebc..} in 0.1.0..0.1.3 and {@code 00a6e4..} in
     *       0.1.4..0.1.8. Both are listed: this build still differs from both (0.1.9 rewrote the file
     *       again, and that generation is already current), so a server on either older generation
     *       must be upgraded rather than mislabelled as an owner edit.</li>
     *   <li>B4-flora-plants.yml: {@code ff329c..} in 0.1.0..0.1.3 and {@code bd4737..} in
     *       0.1.4..0.1.9. Both are listed because this build rewrites the file's auto-state pool
     *       comment after the vine removal: without the 0.1.4..0.1.9 value every 0.1.4+ server
     *       would sit on bytes the manifest cannot name and be kept as an owner edit.</li>
     *   <li>B4-flora-terrain.yml: {@code a711e3..} in 0.1.0..0.1.5 only (0.1.6+ already carry this
     *       build's bytes, so that generation is skipped as already current).</li>
     *   <li>B5-flora-b.yml ({@code 76a5ca..}), B6-lanterns-stone.yml ({@code 026c19..}),
     *       langs/b5_en_us.yml ({@code c5e0b4..}) and langs/b6_en_us.yml ({@code ee2fe2..}) were
     *       byte-identical in all ten delivered jars, so one value each covers every generation.</li>
     *   <li>langs/b5_zh_cn.yml ({@code bb84ab..}) and langs/b6_zh_cn.yml ({@code f773a4..}) only
     *       exist from 0.1.4 on; 0.1.0..0.1.3 shipped no Chinese translation for those batches, so
     *       there is nothing older to declare.</li>
     * </ul>
     * Each entry must be a file whose old default hashes are known; listing every bundled file would
     * let an unrelated edit overwrite a pristine file that happens to match. A malformed entry (a
     * value that is neither a 64-hex SHA-256 nor a non-empty list of them, or a path that escapes the
     * pack root) is refused rather than ignored, so a typo cannot silently disable an intended fix.
     */
    public static final String MANIFEST_YAML = """
            # 内置资源升级白名单（YAML 映射：资源相对路径 -> 允许替换的旧内置默认文件 SHA-256）。
            #
            # 值可以是一个哈希（旧的单值格式，仍完全支持），也可以是一个列表。列表用于同一个文件
            # 在多代已交付 jar 里字节不同的情况：磁盘上等于其中任意一个，才算“仍是旧内置默认”。
            #
            # 【2026-10-30 语义扩展：条目可以指向“本构建已不再随附”的文件】
            # 为了让每个配置文件夹只有一个 yml，18 个分散的 blocks 文件已合并成单文件
            # configuration/blocks/blocks.yml。合并后下面这 6 条 blocks 路径在本构建里**不存在**：
            #   configuration/blocks/{B3-openable,B4-flora-plants,B4-flora-terrain,
            #                         B4-flora-crops-seeds,B5-flora-b,B6-lanterns-stone}.yml
            # 它们留在白名单里的作用变成“磁盘上若仍是旧内置默认，就删掉这份残留”——
            # 因为这些 id 已经在 blocks.yml 里声明，两份并存会让 CE 重复声明同一批 id
            # （IdSectionConfigParser 是 byId.put，后来者静默胜出），且散落状态无法推理。
            # 只要磁盘内容不是任何一份旧内置默认，就判定为用户已改，一律保留不删。
            # langs/ 目录未合并（仍 22 个文件），那 4 条 lang 条目依旧走“旧版→新版覆盖”。
            #
            # 旧哈希全部由已交付 jar 实测（unzip -p ... | sha256sum），逐代取全集后去重，不是猜的：
            #   B3-openable.yml      a93ebc.. 取自 0.1.0..0.1.3；00a6e4.. 取自 0.1.4..0.1.8
            #                        （0.1.9 的 106cd4.. 就是本构建字节，无需列）。
            #   B4-flora-plants.yml  ff329c.. 取自 0.1.0..0.1.3；bd4737.. 取自 0.1.4..0.1.9
            #                        （本轮要改池占用注释，两代都得能识别为旧默认）。
            #   B4-flora-terrain.yml a711e3.. 取自 0.1.0..0.1.5（0.1.6+ 已是本构建字节）。
            #   B5-flora-b.yml       76a5ca..；B6-lanterns-stone.yml 026c19..
            #   langs/b5_en_us.yml   c5e0b4..；langs/b6_en_us.yml   ee2fe2..   均十代一致，单值即可。
            #   langs/b5_zh_cn.yml   bb84ab..；langs/b6_zh_cn.yml   f773a4..   0.1.4 起才有中文，
            #                        更早的 jar 里没有这个文件，不存在更旧的默认。
            "configuration/blocks/B3-openable.yml":
              - "a93ebc48ed3061f01e2a4827f545eb6ec1b159592aff368d4a22114f7aa1724b"
              - "00a6e45559cdbcf40608be1588be2aee0529a6607e644eb593e0c7532ad1089c"
            "configuration/blocks/B4-flora-plants.yml":
              - "ff329c5060ada3b06656b96cf47eef0cbf82981a1c7e81bfd279a335b9f04d94"
              - "bd4737afae65ab5adf4e5a0d16765477c186c400e6fa96183110c4ce023ac4c3"
            # B4-flora-crops-seeds.yml 在 0.1.10..0.1.16 是同一个字节（f2d4f6..）：那三块作物的
            # age_3 一直挂在 weeping_vines 上，而该组被旧藤蔓残留与其它插件占满（实测容量 50），
            # 导致 age_3 拿不到视觉状态。0.1.17 把这三处改挂 twisting_vines（容量 50、本项目用 4）。
            # 必须列入白名单，否则 PackInstaller 的「只补缺失、绝不覆盖」会让已有服务器一直留着
            # 旧文件，修复永远送不到。更早的 jar 没有这个文件，所以是单代单值。
            "configuration/blocks/B4-flora-crops-seeds.yml": "f2d4f6d97534c803acc7ab22b9938b0fc4e8b072e651f3451b665c23fc2468bb"
            "configuration/blocks/B4-flora-terrain.yml": "a711e375f4cea7b62a3aaa7621e2329a430b788934b4e491cf246ed160bbce02"
            "configuration/blocks/B5-flora-b.yml": "76a5ca03205738be285d03beb58ed773dd9acad82e9c908a6a105ec59c052b0a"
            "configuration/blocks/B6-lanterns-stone.yml": "026c1961f912173b4cbccecf1903868e9779ecf7423b209dcf563caf7c30b3a3"
            "configuration/langs/b5_en_us.yml": "c5e0b4d83192ea978eda0d8ff60ab005fd9e129bc6bb5a6c34f9d0f55feb0ac4"
            "configuration/langs/b5_zh_cn.yml": "bb84ab9450ac054fe6167f8cb0a708362d0fe34bff369363a7c55fa566717ce9"
            "configuration/langs/b6_en_us.yml": "ee2fe23afb9767d54a250f6a1137007fd840063e07422213847b6e1fb64afe9b"
            "configuration/langs/b6_zh_cn.yml": "f773a4720ccdeef90256e7c44ef1f26701dedfd3ba40e6e4267edcb0455ee32b"
            """;

    /**
     * One whitelisted file: its pack-relative path and every SHA-256 of a default it may replace.
     * More than one hash means the file differs between delivered generations, and the disk copy may
     * be any of them -- all of them are "pristine old default", none of them is an owner edit.
     */
    public record Entry(String relativePath, List<String> oldSha256s) {
        public Entry {
            oldSha256s = List.copyOf(oldSha256s);
            if (oldSha256s.isEmpty()) {
                throw new IllegalArgumentException("升级清单项 '" + relativePath + "' 至少需要一个旧哈希");
            }
        }

        /** Whether {@code sha256} is one of the declared old defaults -- i.e. the file is pristine. */
        public boolean matchesOld(String sha256) {
            return oldSha256s.contains(sha256);
        }
    }

    /** What an {@link #upgrade} pass did, per pack. {@code failed > 0} is the caller's signal to refuse to enable. */
    public record Result(int upgraded, int kept, int failed) {
    }

    private final List<Entry> entries;

    BundledPackUpgrades(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** The production whitelist, parsed from {@link #MANIFEST_YAML}. */
    public static BundledPackUpgrades production() {
        return fromYaml(MANIFEST_YAML);
    }

    /**
     * Parses a manifest mapping into entries, refusing anything whose old hashes are not valid
     * SHA-256 values (or a non-empty list of them) and refusing a path that would escape the pack
     * root. Uses {@link SafeYaml}, so a manifest is read under the same bounds (dup keys, aliases,
     * depth, size) as every other YAML here.
     *
     * @throws IllegalArgumentException if the document is not a mapping, a value is neither a 64-hex
     *                                  SHA-256 nor a non-empty list of them, or a path is absolute or
     *                                  contains a {@code ..} segment
     */
    static BundledPackUpgrades fromYaml(String yaml) {
        Map<String, Object> tree = SafeYaml.load(yaml);
        List<Entry> parsed = new ArrayList<>(tree.size());
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            parsed.add(new Entry(requireSafePath(entry.getKey()), requireSha256s(entry.getKey(), entry.getValue())));
        }
        return new BundledPackUpgrades(parsed);
    }

    /**
     * Runs one pass over an already-installed pack folder.
     *
     * @param target         the pack directory, e.g. {@code plugins/CraftEngine/resources/betterend}
     * @param source         the CURRENT build's bundled bytes, opened by pack-relative path
     * @param backupRoot     where the replaced OLD default is preserved, e.g.
     *                       {@code plugins/BetterEnd/resource-backups/betterend}
     * @return the counts; {@code failed > 0} means at least one intended upgrade did not land and the
     *         caller must not report success
     */
    public Result upgrade(Path target, PackInstaller.Source source, Path backupRoot, Logger log) {
        int upgraded = 0;
        int kept = 0;
        int failed = 0;
        // 被移除的废弃内置文件数。它和 upgraded 分开统计，但对外仍归入 Result.upgraded —— 两者
        // 都是「本插件自己那批文件从旧状态变成了新状态」，且都走了备份。这样 Result 的签名不必
        // 变动，PackReport / ReloadReport / CraftEngineBridge 都不用跟着改。
        int removed = 0;
        for (Entry entry : entries) {
            Path disk;
            try {
                disk = ResourcePaths.resolve(target, entry.relativePath());
            } catch (IOException unsafe) {
                failed++;
                log.warning("升级清单项 '" + entry.relativePath() + "' 的路径不安全，已拒绝：" + unsafe.getMessage());
                continue;
            }
            ResourcePaths.Entry state = ResourcePaths.entryAt(disk);
            if (state == ResourcePaths.Entry.ABSENT) {
                // Absent: PackInstaller's completion owns adding it; there is no old default to replace.
                continue;
            }
            if (state != ResourcePaths.Entry.REGULAR_FILE) {
                failed++;
                log.warning("升级清单项 '" + entry.relativePath() + "' 的目标 " + disk + " "
                        + ResourcePaths.describe(state) + "；既不覆盖也不删除，本次计为失败"
                        + "——若当成「不存在」跳过，内置修复会永远到不了这台服务器。");
                continue;
            }
            byte[] newBytes;
            byte[] diskBytes;
            String diskHash;
            try {
                diskBytes = Files.readAllBytes(disk);
                newBytes = readNew(source, entry.relativePath());
            } catch (IOException error) {
                failed++;
                log.log(Level.WARNING, "读取资源 '" + entry.relativePath() + "' 失败，未做任何改动", error);
                continue;
            }
            if (newBytes == null) {
                // 本构建**不再随附**这个文件了。目前唯一的来源是「多个分散的 blocks 文件被合并成
                // 单个 blocks.yml」——白名单仍然记着那些旧路径与其旧哈希，正好可以用「哈希是否等于
                // 旧内置默认」来判断磁盘上那份到底是不是本插件的原文件。
                //
                // 是旧内置默认 → 内容已并入合并后的文件，这份残留必须删掉，否则它会与新文件
                // 同时声明同一批 id（CE 的 IdSectionConfigParser 是 byId.put，后来者静默胜出），
                // 而且散落状态会让「改了哪份文件」变得不可推理。
                // 不是旧内置默认 → 用户改过，绝不删（与 replace 分支同一套「绝不覆盖用户改动」原则）。
                diskHash = sha256(diskBytes);
                if (!entry.matchesOld(diskHash)) {
                    kept++;
                    log.warning("资源 '" + entry.relativePath() + "' 在本构建中已不再随附，"
                            + "但磁盘上的内容既不是任何一份旧内置默认，判定为用户已修改，保留不动。"
                            + "如无自行改动，请手动删除它，避免与新合并文件重复声明方块。");
                    continue;
                }
                try {
                    backup(backupRoot, entry, diskBytes);
                    Files.delete(disk);
                    removed++;
                    log.info("已移除废弃的内置资源 '" + entry.relativePath()
                            + "'（其内容已并入本构建的合并文件），原文件已备份。");
                } catch (IOException error) {
                    failed++;
                    log.log(Level.WARNING, "移除废弃的内置资源 '" + entry.relativePath() + "' 失败", error);
                }
                continue;
            }
            diskHash = sha256(diskBytes);
            if (diskHash.equals(sha256(newBytes))) {
                // Already the new default: nothing to write, nothing to back up (idempotent re-boot).
                continue;
            }
            if (!entry.matchesOld(diskHash)) {
                kept++;
                log.warning("资源 '" + entry.relativePath() + "' 的内容既不是任何一份旧内置默认也不是新内置版本，"
                        + "判定为用户已修改，绝不覆盖；新内置修复未应用，请人工合并：把新版本中该文件合并到你的改动里。");
                continue;
            }
            try {
                backup(backupRoot, entry, diskBytes);
                AtomicFiles.replace(disk, newBytes);
                upgraded++;
            } catch (IOException error) {
                failed++;
                log.log(Level.WARNING, "升级资源 '" + entry.relativePath() + "' 失败（已放弃覆盖，磁盘文件保持原样）", error);
            }
        }
        if (upgraded > 0 || removed > 0 || kept > 0 || failed > 0) {
            log.info("CraftEngine 资源包内置升级：替换 " + upgraded + "，移除废弃 " + removed
                    + "，保留（用户已改）" + kept + "，失败 " + failed + "。");
        }
        // removed 并入 upgraded：两者都是「本插件的文件从旧状态变成新状态」，且都做过备份。
        return new Result(upgraded + removed, kept, failed);
    }

    /**
     * Preserves the bytes just read from the live file at {@code backup} before it is replaced, and
     * never trusts an existing backup by its mere presence.
     * <p>
     * The path is resolved through {@link ResourcePaths} like the live file, so neither a traversal nor
     * a symlinked backup directory can send the copy somewhere else. A regular file already at the
     * backup path is read back and must hash to one of this file's declared OLD defaults: a correct
     * prior backup is reused untouched -- including one taken from a DIFFERENT generation than the
     * bytes now on disk, so hopping two versions never destroys the older, still-valid backup -- and a
     * backup whose content is anything else is a contradiction we refuse to paper over: the method
     * throws so nothing is overwritten and every original file is kept. Anything that is not a regular
     * file (a link, a directory) at the backup path is refused for the same reason.
     */
    private void backup(Path backupRoot, Entry entry, byte[] diskBytes) throws IOException {
        Path backup = ResourcePaths.resolve(backupRoot, entry.relativePath());
        ResourcePaths.Entry state = ResourcePaths.entryAt(backup);
        if (state == ResourcePaths.Entry.SYMLINK || state == ResourcePaths.Entry.DIRECTORY
                || state == ResourcePaths.Entry.OTHER) {
            throw new IOException("备份位置 " + backup + " " + ResourcePaths.describe(state)
                    + "；拒绝写入备份（绝不顺着链接写到别处，也绝不覆盖目录），全部原文件保持不动。");
        }
        if (state == ResourcePaths.Entry.REGULAR_FILE) {
            String existing = sha256(Files.readAllBytes(backup));
            if (entry.matchesOld(existing)) {
                return; // a good backup of one of the old defaults is already preserved
            }
            throw new IOException("备份 " + backup + " 已存在，但其内容（SHA-256 " + existing
                    + "）与该文件的旧默认（" + String.join(" / ", entry.oldSha256s())
                    + "）不符；拒绝覆盖，全部原文件保持不动。");
        }
        Files.createDirectories(backup.getParent());
        AtomicFiles.replace(backup, diskBytes);
    }

    private static byte[] readNew(PackInstaller.Source source, String relativePath) throws IOException {
        try (InputStream input = source.open(relativePath)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    /** Lowercase hex SHA-256 -- the exact form the manifest values are normalised to. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 在运行环境中不可用", impossible);
        }
    }

    /**
     * Reads a manifest value as the list of old hashes it declares: a bare string (the original
     * single-value form) becomes a one-element list, a sequence becomes its elements. Anything else --
     * a number, a nested mapping, an empty sequence, an element that is not 64-hex -- is refused
     * rather than skipped, so a typo cannot silently disable an intended fix.
     */
    private static List<String> requireSha256s(String path, Object value) {
        if (value instanceof String hash) {
            return List.of(requireSha256(path, hash));
        }
        if (value instanceof List<?> hashes) {
            if (hashes.isEmpty()) {
                throw new IllegalArgumentException(
                        "升级清单项 '" + path + "' 的旧哈希列表不得为空；不需要升级就不要列出该文件");
            }
            List<String> out = new ArrayList<>(hashes.size());
            for (Object hash : hashes) {
                if (!(hash instanceof String text)) {
                    throw new IllegalArgumentException("升级清单项 '" + path + "' 的旧哈希列表元素必须是文本，实际为 " + hash);
                }
                String normalised = requireSha256(path, text);
                if (!out.contains(normalised)) {
                    out.add(normalised);
                }
            }
            return List.copyOf(out);
        }
        throw new IllegalArgumentException(
                "升级清单项 '" + path + "' 的旧哈希必须是 64 位十六进制 SHA-256，或这类哈希的列表，实际为 " + value);
    }

    private static String requireSha256(String path, String hash) {
        if (hash.matches("[0-9a-fA-F]{64}")) {
            return hash.toLowerCase(Locale.ROOT);
        }
        throw new IllegalArgumentException(
                "升级清单项 '" + path + "' 的旧哈希必须是 64 位十六进制 SHA-256，实际为 " + hash);
    }

    /** Refuses a manifest path that is not a plain pack-relative '/'-separated path -- see the gate. */
    private static String requireSafePath(String path) {
        try {
            return ResourcePaths.requireRelative(path);
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException("升级清单项的路径必须是包内相对路径（" + refused.getMessage() + "）", refused);
        }
    }
}
