package dev.mumu.betterend.config;

import dev.mumu.betterend.AtomicFiles;
import dev.mumu.betterend.ResourcePaths;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Owns one packaged YAML file on disk: seeds it on first run, fills in keys a newer build added,
 * and records the {@code config-version} generation it belongs to.
 * <p>
 * The contract is the one every CraftEngine-family plugin here must honour, because it is what makes
 * an upgrade safe for an owner who has already edited their file:
 * <ul>
 *   <li><b>Missing keys only.</b> A key the bundled default has and the owner's file lacks is added
 *       with the bundled value. A key with an empty value ({@code key:} and nothing after it, i.e. a
 *       null) counts as absent, so the bundled default fills it in rather than leaving a null every
 *       reader then has to guard against. A key the owner set is never touched, even if the bundled
 *       default changed -- their value wins. A whole section the owner replaced with a scalar is left
 *       alone.</li>
 *   <li><b>Write only when something changed.</b> A file with every current key is read and left
 *       exactly as it is; we never rewrite it on a no-op.</li>
 *   <li><b>Validate before writing.</b> {@link #load(Consumer)} runs the caller's check over the merged
 *       values, and a rejection aborts the load with nothing written and no backup taken. So a value of
 *       the wrong type is reported, never half-applied, and the file stays what the owner wrote.</li>
 *   <li><b>Backup, then atomic replace.</b> Before a real change we copy the old file to
 *       {@code <name>.bak} through {@link AtomicFiles}, which stages a unique temp file beside the
 *       target and moves it into place, so a crash mid-write cannot truncate the file the server reads
 *       next boot, and no fixed staging name can be trampled by a concurrent writer.</li>
 *   <li><b>A file from a newer build is not backfilled.</b> When its {@code config-version} is higher
 *       than the bundled one, that file already knows more than this build does; merging this build's
 *       older defaults in could resurrect a key the newer build deleted, so nothing is merged and not a
 *       byte is written. Its values are still returned, so this server runs on them.</li>
 *   <li><b>An empty file is re-seeded from the pristine bytes.</b> A zero-byte -- or comments-only --
 *       file has no owner value to keep, so it is backed up (their comments survive there) and the
 *       packaged bytes are laid down as-is instead of a re-rendered dump.</li>
 *   <li><b>Comment loss is accepted.</b> A backfill renders from the parsed tree, so the header
 *       comments of the keys it rewrites are lost. That is why the no-op case writes nothing, and why
 *       the seed cases copy the pristine bytes instead of re-rendering them.</li>
 *   <li><b>A broken file is surfaced, not swallowed.</b> An unparseable file (or one {@link SafeYaml}
 *       refuses) throws; it is never silently replaced with the bundled default, and the owner's file
 *       is left on disk untouched so they can fix it.</li>
 *   <li><b>A symlink is refused, not followed.</b> A link planted where the config belongs -- even a
 *       dangling one, which a naive write would create at the far end -- would redirect the write
 *       outside the plugin's folder, so it throws instead and the link is left alone.</li>
 * </ul>
 * Plain Java plus snakeyaml, so it unit-tests without a server.
 */
public final class ConfigStore {
    /** The generation marker every packaged YAML file carries. */
    public static final String VERSION_KEY = "config-version";

    private final Path file;
    private final Supplier<InputStream> bundled;
    private final Logger log;
    private final String label;

    private ConfigStore(Path file, Supplier<InputStream> bundled, String label, Logger log) {
        this.file = file;
        this.bundled = bundled;
        this.label = label;
        this.log = log;
    }

    /**
     * @param file    the on-disk file ({@code plugins/BetterEnd/config.yml})
     * @param bundled a fresh stream of the jar's copy on each call; {@code null} for "absent"
     * @param label   the file's name, for log lines only
     */
    public static ConfigStore of(Path file, Supplier<InputStream> bundled, String label, Logger log) {
        return new ConfigStore(file, bundled, label, log);
    }

    /** What {@link #load()} resolved: the merged tree, its flattened view and the versions involved. */
    public record Loaded(Map<String, Object> tree, Map<String, Object> values, int bundledVersion, int diskVersion, boolean written) {
    }

    /**
     * Seeds, backfills and returns the merged config, without validating it.
     * <p>
     * Throws for a malformed or unreadable file rather than guessing: a caller that quietly fell back
     * to built-in defaults would generate the world against the wrong terrain and seam every existing
     * chunk. The file is never modified when loading fails.
     *
     * @throws IOException if the file or the bundled copy cannot be read
     */
    public Loaded load() throws IOException {
        return load(null);
    }

    /**
     * Seeds, backfills, <b>validates</b>, and only then writes -- the order that keeps a value the
     * plugin cannot use out of the owner's file.
     * <p>
     * The validator is what turns "the YAML parsed" into "the values can be used": it sees the merged
     * values in their flattened ({@code section.key}) form and is expected to throw -- typically a
     * {@link ConfigException} -- for a key of the wrong type or out of range. It runs <em>before</em>
     * the backup, so a rejected file is not even backed up: the bytes on disk stay exactly what the
     * owner wrote, which is what makes the reported key fixable.
     *
     * @param validator check over the merged values; {@code null} accepts anything
     * @throws IOException if the file or the bundled copy cannot be read, or the target is a symlink
     *                     or not a plain file
     */
    public Loaded load(Consumer<Map<String, Object>> validator) throws IOException {
        requireWritableTarget();
        byte[] bundledBytes = readAll(bundled);
        Map<String, Object> bundledTree = bundledBytes == null
                ? new LinkedHashMap<>()
                : SafeYaml.load(new ByteArrayInputStream(bundledBytes));
        int bundledVersion = version(bundledTree);

        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            // First run: lay down the pristine bytes so the shipped comments survive intact.
            if (bundledBytes == null) {
                throw new IOException(label + " 既不在磁盘上，也没有随插件打包");
            }
            Map<String, Object> values = SafeYaml.flatten(bundledTree);
            validate(validator, values);
            Files.createDirectories(file.getParent());
            AtomicFiles.replace(file, bundledBytes);
            log.info(label + " 不存在，已写入内置默认文件（config-version " + bundledVersion + "）。");
            return new Loaded(bundledTree, values, bundledVersion, bundledVersion, true);
        }

        Map<String, Object> diskTree = SafeYaml.load(file);
        int diskVersion = version(diskTree);

        if (bundledBytes != null && diskVersion > bundledVersion) {
            // Written by a newer BetterEnd, so its own defaults are the current ones. Merging this
            // build's older defaults back in could resurrect a key that build deleted, so nothing is
            // merged and not a byte is written; the values below are what this server runs on.
            log.warning(label + " 的 config-version 为 " + diskVersion + "，高于本构建的 "
                    + bundledVersion + "，该文件由更新的 BetterEnd 写入；未知的键会原样保留，但其中一些在这里可能被忽略。");
            Map<String, Object> values = SafeYaml.flatten(diskTree);
            validate(validator, values);
            return new Loaded(diskTree, values, bundledVersion, diskVersion, false);
        }

        if (bundledBytes != null && diskTree.isEmpty()) {
            // Zero bytes, or nothing but comments: no owner value to keep, so re-seed from the packaged
            // bytes (comments intact) after backing the old file up. The backup is not pointless even
            // for an empty tree -- a comments-only file has writing in it the owner may want back.
            Map<String, Object> values = SafeYaml.flatten(bundledTree);
            validate(validator, values);
            AtomicFiles.backup(file, backupFile());
            AtomicFiles.replace(file, bundledBytes);
            log.info(label + " 不含任何配置项，已备份为 " + backupFile().getFileName()
                    + " 并重新写入内置默认文件（config-version " + bundledVersion + "）。");
            return new Loaded(bundledTree, values, bundledVersion, diskVersion, true);
        }

        boolean changed = backfill(bundledTree, diskTree);
        if (diskVersion < bundledVersion) {
            diskTree.put(VERSION_KEY, bundledVersion);
            changed = true;
            log.info(label + "：config-version " + diskVersion + " -> " + bundledVersion
                    + "，已补全缺失的键，保留你自己的取值。");
        }
        Map<String, Object> values = SafeYaml.flatten(diskTree);
        // Before the backup, so a rejected value leaves no .bak either: the file was never going to be
        // touched, and a fresh .bak of an unchanged file is one more thing for the owner to explain.
        validate(validator, values);
        if (changed) {
            AtomicFiles.backup(file, backupFile());
            AtomicFiles.replace(file, SafeYaml.dump(diskTree).getBytes(StandardCharsets.UTF_8));
        }
        return new Loaded(diskTree, values, bundledVersion, diskVersion, changed);
    }

    /**
     * Refuses a target this store will not read or write: a symlink -- even a dangling one, which
     * {@code isRegularFile} would call "missing" and let a write create at the far end -- or an existing
     * non-regular file sitting where the config belongs.
     */
    private void requireWritableTarget() throws IOException {
        ResourcePaths.requireAncestors(file.toAbsolutePath().normalize().getParent());
        if (Files.isSymbolicLink(file)) {
            throw new IOException(label + " 是符号链接（symlink），拒绝读写：" + file
                    + "；请先把链接换成普通文件，否则写入会落到插件目录之外");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " 已存在但不是普通文件（可能是目录），拒绝读写：" + file);
        }
    }

    private static void validate(Consumer<Map<String, Object>> validator, Map<String, Object> values) {
        if (validator != null) {
            validator.accept(values);
        }
    }

    /**
     * Adds every key {@code defaults} has and {@code target} lacks, recursing into shared sections.
     * Returns whether anything was added. An existing value -- of any type, including a section the
     * owner replaced with a scalar -- is never overwritten. A null counts as absent, so a key left empty
     * gets the bundled default instead of staying a null; a bundled key that is itself null is skipped,
     * because writing it would read back as absent and make every load rewrite the file.
     */
    static boolean backfill(Map<String, Object> defaults, Map<String, Object> target) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object fallback = entry.getValue();
            Object existing = target.get(key);
            if (existing == null) {
                if (fallback == null) {
                    continue;
                }
                target.put(key, fallback);
                changed = true;
            } else if (fallback instanceof Map<?, ?> nestedDefaults && existing instanceof Map<?, ?> existingSection) {
                changed |= backfill(SafeYaml.normalise(nestedDefaults), mutate(existingSection, target, key));
            }
        }
        return changed;
    }

    /**
     * The nested map stored at {@code key}, normalised to String keys and written back so the
     * recursion mutates the same node the outer tree holds.
     */
    private static Map<String, Object> mutate(Map<?, ?> existing, Map<String, Object> parent, String key) {
        Map<String, Object> normalised = SafeYaml.normalise(existing);
        parent.put(key, normalised);
        return normalised;
    }

    static int version(Map<String, Object> tree) {
        Object raw = tree.get(VERSION_KEY);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** Where the pre-change copy lives: {@code config.yml} -> {@code config.yml.bak}. */
    private Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private static byte[] readAll(Supplier<InputStream> source) throws IOException {
        if (source == null) {
            return null;
        }
        try (InputStream in = source.get()) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }
}
