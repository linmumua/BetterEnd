package dev.mumu.betterend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write contract every packaged file (config, messages, the resource pack) depends on.
 * <p>
 * WHY the assertions look like this: a failed write that is reported as success is how a server boots on
 * a truncated config, and a write that follows a symlink is how a plugin rewrites a file it does not own.
 * So each test pins one of those harms rather than the mechanism: the old file must survive a failure
 * byte for byte, no staging file may be left behind, and a redirect planted at the target must be
 * refused with the target unchanged.
 */
class AtomicFilesTest {
    private static final byte[] CONTENT = "config-version: 2\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    void aNewFileIsWrittenWhole() throws IOException {
        Path target = dir.resolve("config.yml");

        AtomicFiles.replace(target, CONTENT);

        assertArrayEquals(CONTENT, Files.readAllBytes(target));
        assertNoStagingFiles();
    }

    @Test
    void anExistingFileIsReplacedAndNoStagingFileSurvives() throws IOException {
        Path target = dir.resolve("config.yml");
        Files.writeString(target, "old content that must not survive");

        AtomicFiles.replace(target, CONTENT);

        assertArrayEquals(CONTENT, Files.readAllBytes(target));
        assertNoStagingFiles();
    }

    @Test
    void aDirectoryWhereTheFileShouldBeIsRefused() throws IOException {
        Path target = dir.resolve("config.yml");
        Files.createDirectories(target);

        IOException error = assertThrows(IOException.class, () -> AtomicFiles.replace(target, CONTENT));

        assertTrue(error.getMessage().contains("config.yml"), "the error must name the path: " + error.getMessage());
        assertTrue(Files.isDirectory(target), "a refused write must leave the directory a directory");
        assertNoStagingFiles();
    }

    @Test
    void aSymlinkTargetIsRefusedAndTheLinkStaysALink() throws IOException {
        Path real = dir.resolve("real.yml");
        Files.writeString(real, "owner content");
        Path link = dir.resolve("config.yml");
        createSymlinkOrAbort(link, real);

        IOException error = assertThrows(IOException.class, () -> AtomicFiles.replace(link, CONTENT));

        assertTrue(error.getMessage().contains("symlink"), "the refusal must say why: " + error.getMessage());
        assertTrue(Files.isSymbolicLink(link), "the link itself must not be replaced by a file");
        assertEquals("owner content", Files.readString(real), "the link's target must be untouched");
        assertNoStagingFiles();
    }

    @Test
    void aDanglingSymlinkIsRefusedRatherThanWritingAtItsDestination() throws IOException {
        // The dangerous case: `Files.exists` says false, so a naive write would CREATE the file the link
        // points at -- outside the plugin's folder.
        Path missing = dir.resolve("missing.yml");
        Path link = dir.resolve("config.yml");
        createSymlinkOrAbort(link, missing);

        assertThrows(IOException.class, () -> AtomicFiles.replace(link, CONTENT));

        assertFalse(Files.exists(missing), "writing through a dangling link would create a file elsewhere");
        assertTrue(Files.isSymbolicLink(link));
        assertNoStagingFiles();
    }

    @Test
    void aBackupIsAWholeCopyOfTheSource() throws IOException {
        Path source = dir.resolve("config.yml");
        Files.writeString(source, "owner edits\nsecond line\n");
        Path backup = dir.resolve("config.yml.bak");

        AtomicFiles.backup(source, backup);

        assertEquals("owner edits\nsecond line\n", Files.readString(backup));
        assertEquals("owner edits\nsecond line\n", Files.readString(source), "a backup never consumes the source");
        assertNoStagingFiles();
    }

    @Test
    void aSymlinkIsNeverAcceptedAsABackupSource() throws IOException {
        Path elsewhere = dir.resolve("elsewhere.yml");
        Files.writeString(elsewhere, "not our file");
        Path source = dir.resolve("config.yml");
        createSymlinkOrAbort(source, elsewhere);
        Path backup = dir.resolve("config.yml.bak");

        assertThrows(IOException.class, () -> AtomicFiles.backup(source, backup));

        assertFalse(Files.exists(backup), "refusing the source must also mean no backup is written");
        assertNoStagingFiles();
    }

    @Test
    void aMissingParentDirectoryIsReportedAndNotInvented() {
        // The caller owns the directory layout (ConfigStore creates it for a first run). Inventing it here
        // would hide a typo in a path silently, so this refuses and says which path is wrong.
        Path target = dir.resolve("nope/config.yml");

        IOException error = assertThrows(IOException.class, () -> AtomicFiles.replace(target, CONTENT));

        assertTrue(error.getMessage().contains("config.yml"), "the error must name the path: " + error.getMessage());
        assertFalse(Files.exists(dir.resolve("nope")), "replace must not create the parent directory");
    }

    private void createSymlinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException refused) {
            // Windows without Developer Mode / SeCreateSymbolicLinkPrivilege cannot create a link at all.
            // Skipping quietly would hide a refusal path that only ever runs on a server, so the reason
            // is reported and this one case is marked aborted rather than green.
            Assumptions.abort("本机无法创建符号链接，" + getClass().getSimpleName() + " 的 symlink 用例未执行：" + refused);
        }
    }

    private void assertNoStagingFiles() throws IOException {
        try (Stream<Path> listing = Files.list(dir)) {
            List<String> leftovers = listing.map(p -> p.getFileName().toString())
                    .filter(name -> name.contains(".tmp-"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "staging files must be cleaned up, found: " + leftovers);
        }
    }
}
