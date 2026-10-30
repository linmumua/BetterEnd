package dev.mumu.betterend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact-hash upgrade rules, on a plain filesystem. These are the promises that make a corrected
 * bundled default safe to ship to an existing server: only a pristine old default is replaced, an
 * owner's edit is never touched and is called out for manual merge, nothing is backed up when there
 * is nothing to change, and a backup or write failure is reported rather than passed off as success.
 * <p>
 * A file may declare SEVERAL old defaults, because the same bundled file is not byte-identical across
 * delivered jars. The multi-hash tests below pin the two things that makes worth anything: a pristine
 * file of ANY declared generation is upgraded (otherwise that generation is stuck and mislabelled),
 * and a file matching NO declared generation is still never touched (otherwise the extra values would
 * have widened the whitelist into an overwrite).
 */
class BundledPackUpgradesTest {
    private static final Logger QUIET = Logger.getLogger("BetterEnd-upgrade-test");
    private static final String PATH = "configuration/blocks/basic.yml";
    private static final String ABSENT = "configuration/blocks/gone.yml";
    private static final String OLD_DEFAULT = "OLD DEFAULT\n";
    private static final String FIXED_DEFAULT = "FIXED DEFAULT\n";
    private static final String OWNER_EDIT = "MY OWN EDIT\n";

    @TempDir
    Path dir;

    @Test
    void aPristineOldDefaultIsReplacedAndTheOldFileIsBackedUp() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);
        Path backups = dir.resolve("data/resource-backups/betterend");

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(1, result.upgraded());
        assertEquals(0, result.kept());
        assertEquals(0, result.failed());
        assertEquals(FIXED_DEFAULT, Files.readString(file), "the corrected default lands");
        assertEquals(OLD_DEFAULT, Files.readString(backups.resolve(PATH)),
                "the replaced old default is preserved for rollback / manual merge");
    }

    @Test
    void aUserModifiedFileIsNeverOverwrittenAndIsFlaggedForManualMerge() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OWNER_EDIT);
        Path backups = dir.resolve("data/resource-backups/betterend");

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(0, result.upgraded());
        assertEquals(1, result.kept());
        assertEquals(0, result.failed());
        assertEquals(OWNER_EDIT, Files.readString(file), "an owner's edit must never be overwritten");
        assertFalse(Files.exists(backups.resolve(PATH)), "a file we did not touch must not be backed up");
    }

    @Test
    void nothingIsBackedUpWhenTheFileIsAlreadyCurrentOrAbsent() throws IOException {
        Path target = dir.resolve("betterend");
        write(target, PATH, FIXED_DEFAULT); // already the new default
        Path backups = dir.resolve("data/resource-backups/betterend");

        BundledPackUpgrades.Result result = whitelist(PATH, ABSENT)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT, ABSENT, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(0, result.upgraded());
        assertEquals(0, result.kept());
        assertEquals(0, result.failed());
        assertFalse(Files.exists(backups), "no update needed means no backup directory at all");
    }

    @Test
    void aFailedBackupLeavesTheOldFileInPlace() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);
        // A regular file where the backup tree's first directory would go: createDirectories must fail.
        Path blocker = dir.resolve("blocked");
        Files.writeString(blocker, "not a directory");

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), blocker.resolve("sub"), QUIET);

        assertEquals(1, result.failed());
        assertEquals(0, result.upgraded());
        assertEquals(OLD_DEFAULT, Files.readString(file),
                "without a backup the file must not be overwritten -- the backup is the safety net");
    }

    /**
     * A whitelisted path that is not a regular file is a contradiction, and it must be reported as a
     * failure rather than skipped. The distinction matters: {@code PackInstaller} fills in files that
     * are absent, so "skipped" here means "someone already has this", and a directory (or a link) is
     * not a copy of the old default -- the correction silently never reaches that server.
     */
    @Test
    void aDirectoryWhereTheWhitelistedFileBelongsIsAFailureNotASkip() throws IOException {
        Path target = dir.resolve("betterend");
        Files.createDirectories(target.resolve(PATH));

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)),
                        dir.resolve("data/resource-backups/betterend"), QUIET);

        assertEquals(1, result.failed(), "a directory is not an installed file and must not be skipped");
        assertEquals(0, result.upgraded());
        assertEquals(0, result.kept());
        assertTrue(Files.isDirectory(target.resolve(PATH)), "the directory is neither replaced nor deleted");
    }

    @Test
    void aSymlinkWhereTheWhitelistedFileBelongsIsAFailureNotASkip() throws IOException {
        Path target = dir.resolve("betterend");
        Path outside = dir.resolve("outside.yml");
        Files.writeString(outside, OLD_DEFAULT);
        link(target.resolve(PATH), outside);

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)),
                        dir.resolve("data/resource-backups/betterend"), QUIET);

        assertEquals(1, result.failed(), "writing through the link would replace a file outside the pack");
        assertEquals(0, result.upgraded());
        assertEquals(OLD_DEFAULT, Files.readString(outside), "the linked file is left byte-for-byte alone");
    }

    /**
     * The backup is the safety net, so a backup path that is a link must be refused rather than
     * followed: the "preserved" copy would land somewhere else entirely and the live file would then be
     * overwritten with no usable backup of the old default.
     */
    @Test
    void aSymlinkAtTheBackupPathIsRefusedAndTheLiveFileIsKept() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);
        Path backups = dir.resolve("data/resource-backups/betterend");
        Path elsewhere = dir.resolve("elsewhere.yml");
        Files.writeString(elsewhere, "not a backup");
        link(backups.resolve(PATH), elsewhere);

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(1, result.failed());
        assertEquals(0, result.upgraded());
        assertEquals(OLD_DEFAULT, Files.readString(file), "without a trustworthy backup the file is not replaced");
        assertEquals("not a backup", Files.readString(elsewhere), "the linked file is never written through");
    }

    @Test
    void aSuccessfulUpgradeLeavesNoTemporaryFileBehind() throws IOException {
        Path target = dir.resolve("betterend");
        write(target, PATH, OLD_DEFAULT);
        Path backups = dir.resolve("data/resource-backups/betterend");

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(1, result.upgraded());
        try (var walk = Files.walk(dir)) {
            assertEquals(List.of(), walk.map(p -> p.getFileName().toString())
                            .filter(name -> name.contains(".tmp"))
                            .toList(),
                    "both the backup and the live write are atomic, so neither may leave a staging file");
        }
    }

    @Test
    void aValidPriorBackupIsReusedUntouched() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);
        Path backups = dir.resolve("data/resource-backups/betterend");
        Path backup = write(backups, PATH, OLD_DEFAULT);
        // A correct prior backup must be reused as-is -- we must not even need to write it. Read-only
        // makes an accidental overwrite fail, so a green result proves we left it alone.
        backup.toFile().setWritable(false);
        try {
            BundledPackUpgrades.Result result = whitelist(PATH)
                    .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

            assertEquals(1, result.upgraded());
            assertEquals(0, result.failed());
            assertEquals(FIXED_DEFAULT, Files.readString(file));
            assertEquals(OLD_DEFAULT, Files.readString(backup), "the correct prior backup is left untouched");
        } finally {
            backup.toFile().setWritable(true);
        }
    }

    @Test
    void aCorruptPriorBackupIsRefusedAndNothingIsTouched() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);
        Path backups = dir.resolve("data/resource-backups/betterend");
        // A truncated / unexpected backup: existing must not be trusted as valid.
        Path backup = write(backups, PATH, "HALF-WRITTEN GARBAGE");

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(1, result.failed(), "a backup that does not match the declared old default is refused");
        assertEquals(OLD_DEFAULT, Files.readString(file), "the live file must not be overwritten");
        assertEquals("HALF-WRITTEN GARBAGE", Files.readString(backup),
                "the unexpected backup is preserved, never clobbered with a fresh copy");
    }

    @Test
    void aWhitelistedFileAbsentFromTheBuildHasItsPristineOldDefaultRemoved() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of()), dir.resolve("data/resource-backups/betterend"), QUIET);

        // 新语义：本构建不再随附该文件（分散的 blocks 文件已合并成单文件），
        // 磁盘上这份是「已知旧内置默认」，说明是本插件的原文件，应当移除而不是报失败。
        assertEquals(0, result.failed(), "缺席不应再计为失败，而是「删除旧残留」");
        assertEquals(1, result.upgraded(), "移除废弃内置文件计入 upgraded（与替换同属「本插件文件状态推进」）");
        assertFalse(Files.exists(file), "旧内置默认应被删除，避免与新合并文件重复声明方块");
        assertTrue(Files.isRegularFile(dir.resolve("data/resource-backups/betterend").resolve(PATH)),
                "删除前必须先把原文件备份下来");
    }

    @Test
    void aWhitelistedFileAbsentFromTheBuildKeepsAnOwnerEditedCopy() throws IOException {
        Path target = dir.resolve("betterend");
        String ownerBytes = "owner edited this file\n";
        Path file = write(target, PATH, ownerBytes);

        BundledPackUpgrades.Result result = whitelist(PATH)
                .upgrade(target, source(Map.of()), dir.resolve("data/resource-backups/betterend"), QUIET);

        assertEquals(0, result.failed(), "用户改动不是失败");
        assertEquals(1, result.kept(), "内容不是任何旧内置默认 → 判定用户已改，保留不删");
        assertEquals(ownerBytes, Files.readString(file), "绝不删除用户的文件");
    }

    @Test
    void aHashThatIsNotSha256IsRefusedRatherThanIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> BundledPackUpgrades.fromYaml(PATH + ": not-a-hash\n"));
    }

    @Test
    void aBadHashInsideAListIsRefusedRatherThanIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> BundledPackUpgrades.fromYaml(PATH + ":\n  - " + sha256Of(OLD_DEFAULT) + "\n  - not-a-hash\n"),
                "a typo in the second generation must not silently narrow the whitelist");
    }

    @Test
    void aNonTextHashListElementIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> BundledPackUpgrades.fromYaml(PATH + ":\n  - " + sha256Of(OLD_DEFAULT) + "\n  - 42\n"));
    }

    @Test
    void anEmptyHashListIsRefusedBecauseItWouldMeanNothing() {
        assertThrows(IllegalArgumentException.class,
                () -> BundledPackUpgrades.fromYaml(PATH + ": []\n"),
                "an entry that allows no old default is a broken entry, not an intentional no-op");
    }

    // --- two delivered generations of the same file ----------------------------------------------

    @Test
    void everyDeclaredOldGenerationIsReplacedRatherThanCalledAnOwnerEdit() throws IOException {
        // The reason the value may be a list: the same bundled file is not byte-identical across
        // delivered jars, so a server installed from the OTHER generation would otherwise be told its
        // pristine file is an owner edit, and the fix would never land.
        String olderDefault = "OLD DEFAULT 0.1.3\n";
        String newerDefault = "OLD DEFAULT 0.1.4\n";
        int caseIndex = 0;
        for (String generation : List.of(olderDefault, newerDefault)) {
            // One isolated pack + backup tree per generation, so neither case can see the other's backup.
            Path root = dir.resolve("case" + caseIndex++);
            Path target = root.resolve("betterend");
            Path file = write(target, PATH, generation);
            Path backups = root.resolve("resource-backups/betterend");

            BundledPackUpgrades.Result result = whitelistBoth(olderDefault, newerDefault)
                    .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

            assertEquals(1, result.upgraded(), "a pristine file of ANY declared generation is upgraded");
            assertEquals(0, result.kept(), "neither generation is an owner edit");
            assertEquals(FIXED_DEFAULT, Files.readString(file));
            assertEquals(generation, Files.readString(backups.resolve(PATH)),
                    "the bytes actually replaced are the ones preserved");
        }
    }

    @Test
    void aFileMatchingNoDeclaredGenerationIsStillTreatedAsTheOwners() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OWNER_EDIT);
        Path backups = dir.resolve("data/resource-backups/betterend");

        BundledPackUpgrades.Result result = whitelistBoth("OLD A\n", "OLD B\n")
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(0, result.upgraded());
        assertEquals(1, result.kept(), "a list of allowed old defaults must not weaken the owner-edit rule");
        assertEquals(OWNER_EDIT, Files.readString(file), "an owner's edit must never be overwritten");
        assertFalse(Files.exists(backups.resolve(PATH)));
    }

    @Test
    void anOlderBackupIsReusedAndNeverOverwrittenByTheNewerGeneration() throws IOException {
        // A server that upgrades across two generations may already hold a backup taken from the
        // OLDER one. That backup is still a valid record of a shipped default, so it must survive.
        String olderDefault = "OLD DEFAULT 0.1.3\n";
        String newerDefault = "OLD DEFAULT 0.1.4\n";
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, newerDefault);
        Path backups = dir.resolve("data/resource-backups/betterend");
        Path backup = write(backups, PATH, olderDefault);
        backup.toFile().setWritable(false); // an accidental overwrite would fail and show up red
        try {
            BundledPackUpgrades.Result result = whitelistBoth(olderDefault, newerDefault)
                    .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

            assertEquals(1, result.upgraded());
            assertEquals(0, result.failed());
            assertEquals(olderDefault, Files.readString(backup), "the older backup is left untouched");
        } finally {
            backup.toFile().setWritable(true);
        }
    }

    @Test
    void aSecondRunIsANoOpAndLeavesTheFirstBackupAlone() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);
        Path backups = dir.resolve("data/resource-backups/betterend");
        BundledPackUpgrades upgrades = whitelist(PATH);

        upgrades.upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);
        BundledPackUpgrades.Result second =
                upgrades.upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(new BundledPackUpgrades.Result(0, 0, 0), second,
                "re-booting on the upgraded file must not upgrade, warn about an owner edit, or fail");
        assertEquals(FIXED_DEFAULT, Files.readString(file));
        assertEquals(OLD_DEFAULT, Files.readString(backups.resolve(PATH)), "the backup keeps the replaced bytes");
    }

    @Test
    void aSingleHashStringIsStillReadAsAOneElementList() throws IOException {
        // The original manifest shape must keep working: an old jar's manifest, or a hand-edited one,
        // may still carry a bare string.
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);
        Path backups = dir.resolve("data/resource-backups/betterend");

        BundledPackUpgrades.Result result = BundledPackUpgrades
                .fromYaml("\"" + PATH + "\": \"" + sha256Of(OLD_DEFAULT) + "\"\n")
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)), backups, QUIET);

        assertEquals(1, result.upgraded());
        assertEquals(FIXED_DEFAULT, Files.readString(file));
    }

    @Test
    void aPathThatEscapesThePackRootIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> BundledPackUpgrades.fromYaml("../outside.yml: " + "0".repeat(64) + "\n"));
    }

    @Test
    void anAbsolutePathIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> BundledPackUpgrades.fromYaml("/etc/passwd: " + "0".repeat(64) + "\n"));
    }

    @Test
    void anEmptyManifestIsAnEmptyUpgrade() throws IOException {
        Path target = dir.resolve("betterend");
        Path file = write(target, PATH, OLD_DEFAULT);

        BundledPackUpgrades.Result result = BundledPackUpgrades.fromYaml("# nothing listed yet\n")
                .upgrade(target, source(Map.of(PATH, FIXED_DEFAULT)),
                        dir.resolve("data/resource-backups/betterend"), QUIET);

        assertEquals(0, result.upgraded());
        assertEquals(0, result.failed());
        assertEquals(OLD_DEFAULT, Files.readString(file), "an empty whitelist upgrades nothing");
    }

    // --- helpers ---

    /** A whitelist listing every given path with the OLD default's hash. */
    private static BundledPackUpgrades whitelist(String... paths) {
        StringBuilder yaml = new StringBuilder();
        for (String path : paths) {
            yaml.append('"').append(path).append("\": \"").append(sha256Of(OLD_DEFAULT)).append("\"\n");
        }
        return BundledPackUpgrades.fromYaml(yaml.toString());
    }

    /** A whitelist listing {@link #PATH} as pristine if it matches ANY of the given old defaults. */
    private static BundledPackUpgrades whitelistBoth(String... oldDefaults) {
        StringBuilder yaml = new StringBuilder("\"" + PATH + "\":\n");
        for (String oldDefault : oldDefaults) {
            yaml.append("  - \"").append(sha256Of(oldDefault)).append("\"\n");
        }
        return BundledPackUpgrades.fromYaml(yaml.toString());
    }

    private static String sha256Of(String content) {
        return BundledPackUpgrades.sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path write(Path target, String relativePath, String content) throws IOException {
        Path file = target.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /**
     * Creates a symbolic link, or skips the test where the platform refuses (Windows without the
     * symlink privilege / developer mode). Only the fixture is platform-dependent; the rule it
     * exercises is not.
     */
    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException refused) {
            Assumptions.assumeTrue(false, "本机/本文件系统不允许创建符号链接，跳过：" + refused);
        }
        assertTrue(Files.isSymbolicLink(link), "the fixture must actually be a link");
    }

    /** An in-memory source; a null value makes {@code open} return null, modelling an absent file. */
    private static PackInstaller.Source source(Map<String, String> files) {
        Map<String, String> copy = new LinkedHashMap<>(files);
        return new PackInstaller.Source() {
            @Override
            public Set<String> paths() {
                return copy.keySet();
            }

            @Override
            public InputStream open(String relativePath) {
                String content = copy.get(relativePath);
                return content == null ? null
                        : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
