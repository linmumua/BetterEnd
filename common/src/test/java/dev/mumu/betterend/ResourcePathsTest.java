package dev.mumu.betterend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one path gate both pack writers go through, on a plain filesystem.
 * <p>
 * These are security promises, not formatting preferences: a bundled path (or a whitelisted manifest
 * path) that escapes the pack root, or that is redirected by a link somebody planted, would let a
 * resource install write outside {@code plugins/CraftEngine/resources}. Each rule is asserted
 * separately -- shape rejection, post-normalisation containment, and the symlink refusal -- because
 * they fail independently: keeping the shape rule alone would still follow a link, and keeping the
 * containment rule alone would still accept an absolute Windows path.
 * <p>
 * {@link ResourcePaths.Entry} matters for the same reason from the other side: a link or a directory
 * where a bundled file belongs must never be classified as "already there", or every boot would
 * silently skip that file while reporting the pack complete.
 */
class ResourcePathsTest {
    private static final String FILE = "configuration/blocks/basic.yml";

    @TempDir
    Path dir;

    // --- shape ---

    @Test
    void aPlainRelativePathIsAcceptedAndReturnedUnchanged() {
        assertEquals(FILE, ResourcePaths.requireRelative(FILE));
    }

    @Test
    void anAbsolutePathIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("/etc/passwd"));
    }

    @Test
    void aDriveLetterOrAlternateDataStreamIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("C:/Windows/win.ini"),
                "a Windows drive letter must not be read as a relative path");
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("pack.yml:stream"),
                "':' would address an NTFS alternate data stream rather than a file in the pack");
    }

    @Test
    void aBackslashPathIsRefusedRatherThanConverted() {
        // Converting would make "a\\..\\b.yml" normalise its way out of the pack root on Windows.
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("configuration\\blocks\\basic.yml"));
    }

    @Test
    void aDotDotSegmentIsRefusedEvenWhenItWouldLandBackInside() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("configuration/../basic.yml"));
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("../outside.yml"));
    }

    @Test
    void anEmptySegmentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("configuration//basic.yml"));
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative("configuration/"),
                "a trailing '/' names a directory, never a file to write");
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.requireRelative(""));
    }

    // --- resolution ---

    @Test
    void aSafePathResolvesInsideTheRoot() throws IOException {
        Path root = Files.createDirectories(dir.resolve("pack"));

        assertEquals(root.toAbsolutePath().resolve(FILE), ResourcePaths.resolve(root, FILE));
    }

    @Test
    void theRootItselfIsNotAFileToWrite() {
        assertThrows(IOException.class, () -> ResourcePaths.resolve(dir, "."),
                "'.' would name the pack directory itself, not a file in it");
    }

    @Test
    void anUnsafeShapeIsAnIoFailureAtResolveTimeToo() {
        // Callers count per-file outcomes from IOException; a shape error must arrive as one, not as an
        // unchecked exception that aborts the whole install.
        assertThrows(IOException.class, () -> ResourcePaths.resolve(dir, "../escape.yml"));
    }

    @Test
    void anAncestorThatIsAFileIsRefused() throws IOException {
        Files.writeString(dir.resolve("configuration"), "a file, not a directory");

        assertThrows(IOException.class, () -> ResourcePaths.resolve(dir, FILE));
    }

    @Test
    void aFileAboveTheDeclaredRootIsRejectedBeforeAnyWrite() throws IOException {
        Path parent = Files.writeString(dir.resolve("not-a-directory"), "owner data");
        assertThrows(IOException.class,
                () -> ResourcePaths.resolve(parent.resolve("pack"), FILE));
        assertEquals("owner data", Files.readString(parent));
    }

    @Test
    void aSymlinkedParentAboveTheDeclaredRootIsRefused() throws IOException {
        Path real = Files.createDirectories(dir.resolve("real-parent"));
        Path redirect = dir.resolve("redirect");
        link(redirect, real);
        assertThrows(IOException.class,
                () -> ResourcePaths.resolve(redirect.resolve("pack"), FILE));
        assertFalse(Files.exists(real.resolve("pack")));
    }

    @Test
    void aSymlinkedRootIsRefusedSoNothingIsWrittenThroughIt() throws IOException {
        Path real = Files.createDirectories(dir.resolve("real"));
        Path link = dir.resolve("pack");
        link(link, real);

        assertThrows(IOException.class, () -> ResourcePaths.resolve(link, FILE));
        assertFalse(Files.exists(real.resolve(FILE)), "the link must not have been followed");
    }

    @Test
    void aSymlinkedAncestorInsideThePackIsRefused() throws IOException {
        Path root = Files.createDirectories(dir.resolve("pack"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        link(root.resolve("configuration"), outside);

        assertThrows(IOException.class, () -> ResourcePaths.resolve(root, FILE),
                "a link planted at an ancestor must not redirect the write out of the pack");
        assertFalse(Files.exists(outside.resolve("blocks/basic.yml")));
    }

    @Test
    void aDanglingAncestorLinkIsRefusedToo() throws IOException {
        // Files.exists reports a dangling link as absent, so a check built on it would create the tree
        // elsewhere. This is the exact case that has to stay refused.
        Path root = Files.createDirectories(dir.resolve("pack"));
        link(root.resolve("configuration"), dir.resolve("nowhere"));

        assertThrows(IOException.class, () -> ResourcePaths.resolve(root, FILE));
    }

    // --- what is already at the destination ---

    @Test
    void entryAtClassifiesAbsentFileAndDirectory() throws IOException {
        Path root = Files.createDirectories(dir.resolve("pack"));

        assertEquals(ResourcePaths.Entry.ABSENT, ResourcePaths.entryAt(root.resolve("absent.yml")));
        assertEquals(ResourcePaths.Entry.REGULAR_FILE, ResourcePaths.entryAt(write(root.resolve("file.yml"))));
        assertEquals(ResourcePaths.Entry.DIRECTORY, ResourcePaths.entryAt(Files.createDirectories(root.resolve("sub"))));
    }

    @Test
    void aSymlinkIsNeverReportedAsAbsentOrAsARegularFile() throws IOException {
        Path root = Files.createDirectories(dir.resolve("pack"));
        Path outside = write(dir.resolve("outside.yml"));
        Path live = root.resolve("live.yml");
        Path dangling = root.resolve("dangling.yml");
        link(live, outside);
        link(dangling, dir.resolve("nowhere.yml"));

        assertEquals(ResourcePaths.Entry.SYMLINK, ResourcePaths.entryAt(live),
                "a link to a real file must not count as that file -- the write would land outside the pack");
        assertEquals(ResourcePaths.Entry.SYMLINK, ResourcePaths.entryAt(dangling),
                "a dangling link must not count as absent -- creating it would follow the link");
    }

    @Test
    void describeNamesEveryStateSoTheLogLineIsActionable() {
        for (ResourcePaths.Entry entry : ResourcePaths.Entry.values()) {
            assertFalse(ResourcePaths.describe(entry).isBlank(), entry + " must be describable");
        }
    }

    private static Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        return file;
    }

    /**
     * Creates a symbolic link, or skips the test where the platform refuses (Windows without the
     * symlink privilege / developer mode). A skip is honest here: the rule under test is real, only the
     * ability to set up the fixture is platform-dependent.
     */
    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException refused) {
            assumeTrue(false, "本机/本文件系统不允许创建符号链接，跳过（规则本身仍由代码保证）：" + refused);
        }
        assertTrue(Files.isSymbolicLink(link), "the fixture must actually be a link");
    }
}
