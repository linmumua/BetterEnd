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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The install rules, exercised on a plain filesystem -- no CraftEngine, no server. These are the
 * promises an existing server depends on: a first install is atomic, an existing folder is only ever
 * completed, auto-completion can be switched off, and a failure is reported rather than hidden.
 */
class PackInstallerTest {
    private static final Logger QUIET = Logger.getLogger("BetterEnd-pack-test");

    @TempDir
    Path dir;

    @Test
    void firstInstallWritesEveryFileAndLeavesNoStaging() throws IOException {
        Path target = dir.resolve("betterend");

        PackInstaller.Result result = installer().install(target, source(Map.of(
                "pack.yml", "pack",
                "configuration/blocks/basic.yml", "block")));

        assertTrue(result.firstInstall());
        assertEquals(2, result.released());
        assertEquals(0, result.failed());
        assertEquals("pack", Files.readString(target.resolve("pack.yml")));
        assertEquals("block", Files.readString(target.resolve("configuration/blocks/basic.yml")));
        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(p -> p.getFileName().toString().contains(".tmp-")),
                    "a successful first install must not leave a staging directory");
        }
    }

    @Test
    void anExistingFolderIsCompletedWithoutOverwriting() throws IOException {
        Path target = dir.resolve("betterend");
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.yml"), "USER EDIT");

        PackInstaller.Result result = installer().install(target, source(Map.of(
                "keep.yml", "BUNDLED",
                "new.yml", "NEW")));

        assertFalse(result.firstInstall());
        assertEquals(1, result.released());
        assertEquals(1, result.skipped());
        assertEquals("USER EDIT", Files.readString(target.resolve("keep.yml")),
                "an owner's file must never be overwritten");
        assertEquals("NEW", Files.readString(target.resolve("new.yml")),
                "a genuinely missing file is added");
    }

    @Test
    void aFailedFirstInstallLeavesNoTargetDirectory() throws IOException {
        Path target = dir.resolve("betterend");

        PackInstaller.Result result = installer().install(target, brokenSource());

        assertTrue(result.firstInstall());
        assertEquals(1, result.failed());
        assertFalse(Files.exists(target),
                "a first install with a failed file must leave no half-written pack behind");
    }

    @Test
    void aFailedCompletionIsReported() throws IOException {
        Path target = dir.resolve("betterend");
        Files.createDirectories(target);

        PackInstaller.Result result = installer().install(target, brokenSource());

        assertEquals(1, result.failed());
        assertTrue(result.released() >= 1, "the good file is still written");
        // The failure is carried in the report so the caller can refuse to enable on it.
        assertTrue(result.failed() > 0);
    }

    /**
     * The upgrade path this batch depends on, driven by the REAL bundled files: a server already
     * running an older pack has the seed batch's three new files missing and its own edited copy of
     * {@code configuration/langs/b4_en_us.yml}. Completion must add exactly the three and leave the
     * owner's file byte-for-byte alone - the alternative, a {@code -SNAPSHOT} update that overwrites,
     * is what silently reverts an owner's blocked-state fix. A second run must add nothing: the first
     * one already closed the gap, and re-releasing would double-write on every boot.
     * <p>
     * The blocks side of the batch is no longer its own file: {@code configuration/blocks/} is merged
     * into a single {@code blocks.yml}, which is what the three-file gap now names.
     */
    @Test
    void anExistingPackGainsTheNewSeedFilesAndKeepsTheOwnersEdits() throws IOException {
        Path pack = locate("plugin/src/main/resources/craftengine/betterend");
        Path target = dir.resolve("betterend");
        Files.createDirectories(target.resolve(OWNER_EDITED).getParent());
        String edited = "# owner edit: this block was re-pointed at a state their pack already had\n";
        Files.writeString(target.resolve(OWNER_EDITED), edited);

        PackInstaller.Source bundled = bundled(pack);
        PackInstaller.Result result = installer().install(target, bundled);

        assertFalse(result.firstInstall(), "an existing folder is completed, not reinstalled");
        assertEquals(3, result.released(), "the three new seed-batch files are the whole gap");
        assertEquals(1, result.skipped(), "the owner's copy of the crops file is not replaced");
        assertEquals(0, result.failed());

        for (String file : NEW_SEED_FILES) {
            assertEquals(Files.readString(pack.resolve(file)), Files.readString(target.resolve(file)),
                    file + " must land byte-for-byte as bundled");
        }
        assertEquals(edited, Files.readString(target.resolve(OWNER_EDITED)),
                "an owner's edit must survive the upgrade");

        PackInstaller.Result again = installer().install(target, bundled);
        assertEquals(0, again.released(), "a second pass has nothing left to add");
        assertEquals(4, again.skipped(), "all four files are already there");
        assertEquals(0, again.failed());
    }

    /**
     * A bundled path that escapes the pack root must fail loudly instead of being resolved against the
     * target. Without the shared gate, {@code target.resolve("../escaped.yml")} writes a file beside
     * the pack -- outside the only directory this class is allowed to touch.
     */
    @Test
    void aBundleEntryThatTraversesOutOfThePackFailsAndWritesNothing() throws IOException {
        Path target = dir.resolve("betterend");
        Files.createDirectories(target);

        PackInstaller.Result result = installer().install(target, source(Map.of(
                "../escaped.yml", "ESCAPED",
                "ok.yml", "OK")));

        assertEquals(1, result.failed(), "the traversal entry is a failure, not a silent skip");
        assertEquals(1, result.released(), "the safe entry still lands");
        assertFalse(Files.exists(dir.resolve("escaped.yml")),
                "nothing may be written outside the pack directory");
        assertEquals("OK", Files.readString(target.resolve("ok.yml")));
    }

    /**
     * A link sitting where a bundled file belongs is a contradiction: writing through it would land
     * outside the pack, and skipping it would drop that file on every boot while reporting the pack
     * complete. It must be a failure.
     */
    @Test
    void aSymlinkWhereABundledFileBelongsIsAFailureNotASkip() throws IOException {
        Path target = dir.resolve("betterend");
        Files.createDirectories(target);
        Path outside = dir.resolve("outside.yml");
        Files.writeString(outside, "USER FILE");
        link(target.resolve("keep.yml"), outside);

        PackInstaller.Result result = installer().install(target, source(Map.of("keep.yml", "BUNDLED")));

        assertEquals(1, result.failed());
        assertEquals(0, result.skipped(), "a link is never 'already present'");
        assertEquals("USER FILE", Files.readString(outside), "the write must not follow the link");
        assertTrue(Files.isSymbolicLink(target.resolve("keep.yml")), "the owner's link is left in place");
    }

    @Test
    void aDanglingSymlinkWhereABundledFileBelongsIsAFailureNotANewFile() throws IOException {
        Path target = dir.resolve("betterend");
        Files.createDirectories(target);
        link(target.resolve("keep.yml"), dir.resolve("nowhere.yml"));

        PackInstaller.Result result = installer().install(target, source(Map.of("keep.yml", "BUNDLED")));

        assertEquals(1, result.failed());
        assertEquals(0, result.skipped());
        assertTrue(Files.isSymbolicLink(target.resolve("keep.yml")),
                "a dangling link must not be replaced by a real file created through it");
        assertFalse(Files.exists(dir.resolve("nowhere.yml")), "the link target must not be created either");
    }

    @Test
    void aDirectoryWhereABundledFileBelongsIsAFailureNotASkip() throws IOException {
        Path target = dir.resolve("betterend");
        Files.createDirectories(target.resolve("keep.yml"));

        PackInstaller.Result result = installer().install(target, source(Map.of("keep.yml", "BUNDLED")));

        assertEquals(1, result.failed());
        assertEquals(0, result.skipped());
        assertTrue(Files.isDirectory(target.resolve("keep.yml")), "the directory is neither overwritten nor deleted");
    }

    @Test
    void aSymlinkAsThePackDirectoryIsRefusedRatherThanWrittenThrough() throws IOException {
        Path real = dir.resolve("real-pack");
        Files.createDirectories(real);
        Path target = dir.resolve("betterend");
        link(target, real);

        PackInstaller.Result result = installer().install(target, source(Map.of("pack.yml", "p")));

        assertEquals(1, result.failed(), "installing into a link would put the pack in an arbitrary place");
        assertFalse(result.firstInstall());
        assertFalse(Files.exists(real.resolve("pack.yml")), "nothing may be written through the link");
    }

    @Test
    void aDanglingSymlinkAsThePackDirectoryIsRefusedRatherThanReplaced() throws IOException {
        Path target = dir.resolve("betterend");
        link(target, dir.resolve("missing-pack"));

        PackInstaller.Result result = installer().install(target, source(Map.of("pack.yml", "p")));

        assertEquals(1, result.failed());
        assertTrue(Files.isSymbolicLink(target), "the owner's link must not be replaced by a real directory");
        assertFalse(Files.exists(dir.resolve("missing-pack")));
    }

    @Test
    void aSuccessfulInstallLeavesNoTemporaryFileBehind() throws IOException {
        Path target = dir.resolve("betterend");

        installer().install(target, source(Map.of("pack.yml", "pack")));
        installer().install(target, source(Map.of("pack.yml", "pack", "new.yml", "NEW")));

        try (var walk = Files.walk(dir)) {
            assertEquals(List.of(), walk.map(p -> p.getFileName().toString())
                            .filter(name -> name.contains(".tmp"))
                            .toList(),
                    "an atomic write that succeeded must not leave its staging file behind");
        }
    }

    @Test
    void aFailedFirstInstallLeavesNoStagingDirectoryEither() throws IOException {
        Path target = dir.resolve("betterend");

        installer().install(target, brokenSource());

        assertFalse(Files.exists(target));
        try (var listing = Files.list(dir)) {
            assertEquals(List.of(), listing.map(p -> p.getFileName().toString())
                            .filter(name -> name.contains(".tmp"))
                            .toList(),
                    "the abandoned staging directory must be cleaned up, or the next boot finds it");
        }
    }

    /** The seed batch's three additions, relative to the pack root. */
    private static final List<String> NEW_SEED_FILES = List.of(
            "configuration/blocks/blocks.yml",
            "configuration/langs/b4_seeds_en_us.yml",
            "configuration/langs/b4_seeds_zh_cn.yml");

    /**
     * The bundled file the modelled existing server has edited. It has to be a file this build really
     * ships (the source below offers it) that is already present on disk, so completion must skip it.
     */
    private static final String OWNER_EDITED = "configuration/langs/b4_en_us.yml";

    /**
     * A {@link PackInstaller.Source} over the real bundled pack: the four files this upgrade is
     * about, read from disk so the test cannot pass against a hand-written stand-in.
     */
    private static PackInstaller.Source bundled(Path pack) {
        List<String> files = new ArrayList<>(NEW_SEED_FILES);
        files.add(OWNER_EDITED);
        return new PackInstaller.Source() {
            @Override
            public Set<String> paths() {
                return new LinkedHashSet<>(files);
            }

            @Override
            public InputStream open(String relativePath) throws IOException {
                return Files.newInputStream(pack.resolve(relativePath));
            }
        };
    }

    /** Walk up from the working dir until the repo-relative path is underfoot (tests run per module). */
    private static Path locate(String relative) {
        Path at = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && at != null; i++, at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }

    private PackInstaller installer() {
        return new PackInstaller("betterend", QUIET);
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

    /** An in-memory source; a null value makes {@code open} fail, modelling an unreadable file. */
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

    private static PackInstaller.Source brokenSource() {
        return new PackInstaller.Source() {
            @Override
            public Set<String> paths() {
                return Set.of("ok.yml", "bad.yml");
            }

            @Override
            public InputStream open(String relativePath) {
                if (relativePath.equals("bad.yml")) {
                    return null; // the "missing bundled resource" path
                }
                return new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
