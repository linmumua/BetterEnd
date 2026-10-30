package dev.mumu.betterend.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade contract a server owner actually feels: a newer build adds keys, never overwrites the
 * values they set, keeps a backup, and only touches the file when something really changed. The other
 * half is what it must NOT do: a file it refuses -- unparseable, from a newer build, rejected by the
 * caller's validator, or a symlink planted at its path -- is left byte for byte as the owner wrote it.
 * <p>
 * Uses only snakeyaml and {@code java.nio}, so it runs without a server or CraftEngine.
 */
class ConfigStoreTest {
    private static final Logger QUIET = Logger.getLogger("BetterEnd-config-test");

    @TempDir
    Path dir;

    private ConfigStore storeFor(String bundledYaml) {
        Path file = dir.resolve("config.yml");
        return ConfigStore.of(file, () -> new ByteArrayInputStream(bundledYaml.getBytes(StandardCharsets.UTF_8)),
                "config.yml", QUIET);
    }

    private static String bundled() {
        return """
                config-version: 2
                craftengine:
                  install-bundled-pack: true
                generator:
                  generate-central-island: true
                  layers:
                    big:
                      distance: 300.0
                """;
    }

    @Test
    void firstRunSeedsThePristineFileByteForByte() throws IOException {
        ConfigStore store = storeFor(bundled());
        ConfigStore.Loaded loaded = store.load();

        assertTrue(loaded.written());
        // The seed is the raw bundled text, comments and all -- not a re-rendered dump.
        assertEquals(bundled(), Files.readString(dir.resolve("config.yml")));
        assertEquals(2, loaded.bundledVersion());
        assertEquals(2, loaded.diskVersion());
        assertFalse(Files.exists(dir.resolve("config.yml.bak")), "a fresh seed needs no backup");
    }

    @Test
    void anAddedKeyIsFilledInAndTheOwnersValueIsKept() throws IOException {
        // The owner is on an older file: no config-version, a custom distance, and a whole new
        // section the plugin introduced that they have never seen.
        Files.writeString(dir.resolve("config.yml"), """
                craftengine:
                  install-bundled-pack: false
                generator:
                  layers:
                    big:
                      distance: 123.0
                """);

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertTrue(loaded.written());
        Map<String, Object> values = loaded.values();
        // the owner's choice survives
        assertEquals(false, values.get("craftengine.install-bundled-pack"));
        assertEquals(123.0, values.get("generator.layers.big.distance"));
        // the missing leaf is added with the bundled default
        assertEquals(true, values.get("generator.generate-central-island"));
        // and the version is lifted
        assertEquals(2, values.get("config-version"));
    }

    @Test
    void aFileDeletedBetweenRunsIsReleasedAgainOnTheNextLoad() throws IOException {
        // The reload case: an owner deleted config.yml while the server was up (or a botched edit
        // removed it). The next load must lay the bundled file back down, comments intact, rather
        // than throwing or silently running on defaults with no file to edit.
        storeFor(bundled()).load();
        Files.delete(dir.resolve("config.yml"));
        assertFalse(Files.exists(dir.resolve("config.yml")));

        ConfigStore.Loaded reloaded = storeFor(bundled()).load();

        assertTrue(reloaded.written(), "a missing file is a write, not a no-op");
        assertEquals(bundled(), Files.readString(dir.resolve("config.yml")),
                "the re-release is the pristine bundled text, not a re-rendered dump");
        assertEquals(2, reloaded.bundledVersion());
    }

    @Test
    void aFileThatIsAlreadyCurrentIsNotRewritten() throws IOException {
        Files.writeString(dir.resolve("config.yml"), bundled());
        Path file = dir.resolve("config.yml");
        long before = Files.getLastModifiedTime(file).toMillis();

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertFalse(loaded.written(), "nothing to add means no write");
        assertFalse(Files.exists(dir.resolve("config.yml.bak")));
        assertEquals(before, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void aRealChangeLeavesABackupOfTheOldFile() throws IOException {
        String original = "craftengine:\n  install-bundled-pack: false\n";
        Files.writeString(dir.resolve("config.yml"), original);

        storeFor(bundled()).load();

        Path backup = dir.resolve("config.yml.bak");
        assertTrue(Files.exists(backup), "the pre-change file must be recoverable");
        assertEquals(original, Files.readString(backup));
    }

    @Test
    void aNewerFileIsReportedButNotDowngraded() throws IOException {
        // config-version 9 > bundled 2: a file from a newer build. Its values and its version stay.
        Files.writeString(dir.resolve("config.yml"), """
                config-version: 9
                craftengine:
                  install-bundled-pack: true
                generator:
                  generate-central-island: true
                  layers:
                    big:
                      distance: 300.0
                """);

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertEquals(9, loaded.diskVersion());
        assertEquals(9, loaded.values().get("config-version"), "a downgrade would lose forward keys");
    }

    @Test
    void aSectionTheOwnerReplacedWithAScalarIsLeftAlone() throws IOException {
        // `generator` is a scalar here; we must not turn it back into a section and lose their value.
        // Deliberately not `off`/`on`: YAML 1.1 reads those as booleans, which would muddy the point.
        Files.writeString(dir.resolve("config.yml"), "config-version: 2\ngenerator: custom-value\n");

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertEquals("custom-value", loaded.values().get("generator"));
        assertFalse(loaded.values().containsKey("generator.generate-central-island"));
    }

    @Test
    void aValueLeftEmptyIsTreatedAsMissingAndFilledIn() throws IOException {
        // `craftengine:` with nothing after it parses to a null. The owner set nothing, so the bundled
        // section must be filled in; leaving the null would hand every reader a value it must guard.
        Files.writeString(dir.resolve("config.yml"), """
                config-version: 2
                craftengine:
                generator:
                  generate-central-island: true
                  layers:
                    big:
                      distance: 300.0
                """);

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertTrue(loaded.written());
        assertEquals(true, loaded.values().get("craftengine.install-bundled-pack"));
        assertTrue(Files.readString(dir.resolve("config.yml")).contains("install-bundled-pack: true"),
                "the empty section must be replaced in the file, not left as a bare key");
        assertNoLeftoverStagingFiles();
    }

    @Test
    void unknownKeysAreKeptAndCostNothing() throws IOException {
        // A key this build knows nothing about is the owner's business. It must survive, and a file that
        // needs no backfill must not be rewritten just because of it.
        String original = """
                config-version: 2
                craftengine:
                  install-bundled-pack: true
                generator:
                  generate-central-island: true
                  layers:
                    big:
                      distance: 300.0
                my-own-section:
                  keep: me
                """;
        Files.writeString(dir.resolve("config.yml"), original);

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertFalse(loaded.written());
        assertEquals("me", loaded.values().get("my-own-section.keep"));
        assertEquals(original, Files.readString(dir.resolve("config.yml")),
                "an unknown key alone must not rewrite (or re-render) the file");
    }

    @Test
    void aFileFromANewerBuildIsNotBackfilledAndNotWritten() throws IOException {
        // config-version 9 came from a newer BetterEnd. Its defaults ARE the current ones, so merging this
        // older build's defaults in could resurrect a key the newer build deleted -- and the "missing" key
        // below is exactly the kind that this build would otherwise helpfully add.
        String original = """
                config-version: 9
                craftengine:
                  install-bundled-pack: false
                """;
        Files.writeString(dir.resolve("config.yml"), original);

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertFalse(loaded.written(), "a newer file must never be written by an older build");
        assertEquals(original, Files.readString(dir.resolve("config.yml")), "not a byte may change");
        assertFalse(Files.exists(dir.resolve("config.yml.bak")), "nothing is written, so nothing is backed up");
        assertEquals(false, loaded.values().get("craftengine.install-bundled-pack"),
                "the newer build's own value still drives this server");
        assertFalse(loaded.values().containsKey("generator.generate-central-island"),
                "the missing key must NOT be backfilled into a file that knows more than we do");
        assertNoLeftoverStagingFiles();
    }

    @Test
    void aValidatorFailureWritesNoFileAndTakesNoBackup() throws IOException {
        // The file needs backfilling (no config-version, no central-island key), so a write WOULD happen
        // if the validator ran late. It must run first: a value the plugin cannot use must not be
        // "repaired" into the file, and the owner must get their own bytes back to fix.
        String original = "craftengine:\n  install-bundled-pack: false\n";
        Files.writeString(dir.resolve("config.yml"), original);

        ConfigException rejected = assertThrows(ConfigException.class,
                () -> storeFor(bundled()).load(values -> {
                    throw new ConfigException("generator.layers.big.distance 必须是数字");
                }));

        assertTrue(rejected.getMessage().contains("generator.layers.big.distance"),
                "the reported key must be the one the owner has to fix");
        assertEquals(original, Files.readString(dir.resolve("config.yml")), "no backfill may land");
        assertFalse(Files.exists(dir.resolve("config.yml.bak")), "a rejected load must not even back up");
        assertNoLeftoverStagingFiles();
    }

    @Test
    void theValidatorSeesTheMergedValuesNotJustTheFile() throws IOException {
        // The check is on what this server will actually use: backfilled defaults included. A validator
        // run against the raw file would wave through a merge that ended up invalid.
        Files.writeString(dir.resolve("config.yml"), "config-version: 2\n");

        ConfigStore.Loaded loaded = storeFor(bundled()).load(values -> {
            if (!values.containsKey("generator.generate-central-island")) {
                throw new ConfigException("校验器没看到补全后的键");
            }
        });

        assertTrue(loaded.written());
        assertEquals(true, loaded.values().get("generator.generate-central-island"));
    }

    @Test
    void aZeroByteFileIsBackedUpThenSeededWithThePristineBytes() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "");

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertTrue(loaded.written(), "an empty file is a write, not a no-op");
        assertEquals(bundled(), Files.readString(file), "the seed is the packaged text, comments and all");
        assertTrue(Files.exists(dir.resolve("config.yml.bak")), "the old file is kept even when empty");
        assertEquals("", Files.readString(dir.resolve("config.yml.bak")));
        assertNoLeftoverStagingFiles();
    }

    @Test
    void aCommentsOnlyFileKeepsItsTextInTheBackup() throws IOException {
        // The reason the empty case still backs up: its tree is empty, but the owner may have written
        // notes there by hand, and a bare "it was empty" rule would throw them away unread.
        String notes = "# 我自己的备注\n# 别删这一行\n";
        Files.writeString(dir.resolve("config.yml"), notes);

        ConfigStore.Loaded loaded = storeFor(bundled()).load();

        assertTrue(loaded.written());
        assertEquals(bundled(), Files.readString(dir.resolve("config.yml")));
        assertEquals(notes, Files.readString(dir.resolve("config.yml.bak")), "注释必须留在备份里");
    }

    @Test
    void aBrokenFileIsLeftByteForByteAndNoBackupIsTaken() throws IOException {
        Path file = dir.resolve("config.yml");
        String unclosed = "craftengine: [unclosed\n";
        Files.writeString(file, unclosed);

        assertThrows(RuntimeException.class, () -> storeFor(bundled()).load());
        assertEquals(unclosed, Files.readString(file), "a broken file must never be replaced by the default");
        assertFalse(Files.exists(dir.resolve("config.yml.bak")));
        assertNoLeftoverStagingFiles();

        // A document that is a list rather than a mapping is refused too, for the same reason.
        String listy = "- a\n- b\n";
        Files.writeString(file, listy);
        assertThrows(IllegalArgumentException.class, () -> storeFor(bundled()).load());
        assertEquals(listy, Files.readString(file));
    }

    @Test
    void anOlderHashAndTheOwnersValuesAreNotReverted() throws IOException {
        // A build that ships a new hash must not overwrite the one already in the file: that hash is the
        // owner's "unchanged since I looked at it" marker, and silently re-baselining it on every upgrade
        // is how a changed-value warning gets lost.
        String bundledWithHash = """
                config-version: 2
                craftengine:
                  install-bundled-pack: true
                config-hash: shipped-new
                generator:
                  generate-central-island: true
                """;
        String original = """
                config-version: 2
                craftengine:
                  install-bundled-pack: false
                config-hash: owner-old
                generator:
                  generate-central-island: true
                """;
        Files.writeString(dir.resolve("config.yml"), original);

        ConfigStore.Loaded loaded = storeFor(bundledWithHash).load();

        assertFalse(loaded.written());
        assertEquals("owner-old", loaded.values().get("config-hash"), "an older hash must not be replaced");
        assertEquals(false, loaded.values().get("craftengine.install-bundled-pack"));
        assertEquals(original, Files.readString(dir.resolve("config.yml")), "nothing changed, so nothing rewrote");
    }

    @Test
    void aRealBackfillLeavesNoStagingFileBehind() throws IOException {
        // The old fixed `<name>.tmp` is gone in favour of a unique staging name; that name must not leak.
        Files.writeString(dir.resolve("config.yml"), "craftengine:\n  install-bundled-pack: false\n");

        storeFor(bundled()).load();

        assertNoLeftoverStagingFiles();
    }

    @Test
    void aDirectoryWhereTheConfigBelongsIsRefused() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.createDirectories(file);

        IOException error = assertThrows(IOException.class, () -> storeFor(bundled()).load());

        assertTrue(error.getMessage().contains("config.yml"), "the error must name the path: " + error.getMessage());
        assertTrue(Files.isDirectory(file), "a refused load must not replace the directory");
        assertEquals(0, countEntries(file), "and must not write anything inside it");
    }

    @Test
    void aSymlinkWhereTheConfigBelongsIsRefusedAndItsTargetUntouched() throws IOException {
        Path real = dir.resolve("shared.yml");
        String content = "craftengine:\n  install-bundled-pack: false\n";
        Files.writeString(real, content);
        createSymlinkOrAbort(dir.resolve("config.yml"), real);

        IOException error = assertThrows(IOException.class, () -> storeFor(bundled()).load());

        assertTrue(error.getMessage().contains("symlink"), "the refusal must say what it refused: " + error.getMessage());
        assertTrue(Files.isSymbolicLink(dir.resolve("config.yml")), "the link must stay a link");
        assertEquals(content, Files.readString(real), "writing through the link would rewrite a file we do not own");
        assertFalse(Files.exists(dir.resolve("config.yml.bak")));
        assertNoLeftoverStagingFiles();
    }

    @Test
    void aDanglingSymlinkIsRefusedRatherThanSeededAtItsDestination() throws IOException {
        // The subtle one: `isRegularFile` says false, so the old code took the "missing file" path and
        // created the file the link points at -- outside the plugin's folder.
        Path missing = dir.resolve("nowhere.yml");
        createSymlinkOrAbort(dir.resolve("config.yml"), missing);

        assertThrows(IOException.class, () -> storeFor(bundled()).load());

        assertFalse(Files.exists(missing), "拒绝时必须不跟随链接，更不能在链接指向处创建文件");
        assertTrue(Files.isSymbolicLink(dir.resolve("config.yml")));
    }

    @Test
    void whenTheJarDoesNotBundleTheFileOnlyWhatIsOnDiskIsRead() throws IOException {
        // Bundled absent (null supplier) but the file is there: read it, touch nothing.
        Files.writeString(dir.resolve("config.yml"), "craftengine:\n  install-bundled-pack: true\n");
        Path file = dir.resolve("config.yml");
        ConfigStore store = ConfigStore.of(file, () -> null, "config.yml", QUIET);

        ConfigStore.Loaded loaded = store.load();

        assertEquals(false, loaded.written());
        assertEquals(true, loaded.values().get("craftengine.install-bundled-pack"));
    }

    @Test
    void versionToleratesStringsAndGarbage() {
        assertEquals(3, ConfigStore.version(SafeYaml.load("config-version: 3\n")));
        assertEquals(4, ConfigStore.version(SafeYaml.load("config-version: '4'\n")));
        assertEquals(0, ConfigStore.version(SafeYaml.load("config-version: soon\n")));
        assertEquals(0, ConfigStore.version(SafeYaml.load("other: 1\n")));
    }

    /** No {@code <name>.tmp-<uuid>} may survive a load, successful or failed. */
    private void assertNoLeftoverStagingFiles() throws IOException {
        try (Stream<Path> listing = Files.list(dir)) {
            List<String> leftovers = listing.map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(".tmp-"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "原子写入的临时文件必须清理干净，残留：" + leftovers);
        }
    }

    private static long countEntries(Path directory) throws IOException {
        try (Stream<Path> listing = Files.list(directory)) {
            return listing.count();
        }
    }

    private static void createSymlinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException refused) {
            // Windows without Developer Mode / SeCreateSymbolicLinkPrivilege cannot create a link at all.
            // Skipping quietly would hide a refusal path that only ever runs on a server, so the reason is
            // reported and this one case is marked aborted rather than green.
            Assumptions.abort("本机无法创建符号链接，symlink 用例未执行：" + refused);
        }
    }
}
