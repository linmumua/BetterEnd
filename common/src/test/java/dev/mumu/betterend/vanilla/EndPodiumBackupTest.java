package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery contract the centre-tower construction depends on.
 * <p>
 * WHY the assertions look like this: a snapshot exists for exactly one situation -- the world was left
 * half-built and the original blocks have to come back. So the tests pin the harms rather than the
 * mechanism: a snapshot that reads back different from what was saved is a wrong rollback, a second
 * snapshot silently replacing the first destroys the only copy of the original terrain, and a file that
 * is read as complete when it is truncated or missing cells is a rollback that half-works while looking
 * finished. Each test therefore asserts an outcome a caller would notice, not a byte offset.
 */
class EndPodiumBackupTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID JOB = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path dataDir;

    @Test
    void aRoundTripReturnsTheSameSnapshot() throws IOException {
        List<EndPodiumBackup.Cell> cells = List.of(
                new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"),
                new EndPodiumBackup.Cell(-1, 60, 3, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"),
                new EndPodiumBackup.Cell(12, -60, -12, "minecraft:air"));

        Path file = EndPodiumBackup.write(dataDir, WORLD, JOB, cells);
        EndPodiumBackup.Snapshot read = EndPodiumBackup.read(file);

        assertEquals(WORLD, read.worldId(), "world identity must survive: a backup restored into another world is a wrecked world");
        assertEquals(JOB, read.jobId(), "job identity must survive: it is how a recovery finds the round it belongs to");
        assertEquals(cells, read.cells(), "the cells and their order must come back exactly as saved");
    }

    @Test
    void theSnapshotSitsAtTheFixedPathAndIsCompressed() throws IOException {
        Path file = EndPodiumBackup.write(dataDir, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock")));

        Path expected = dataDir.resolve(EndPodiumBackup.DIRECTORY_NAME)
                .resolve(WORLD.toString())
                .resolve(JOB + EndPodiumBackup.FILE_SUFFIX);
        assertEquals(expected.toAbsolutePath().normalize(), file.toAbsolutePath().normalize(),
                "a backup path is derived from world + job only, never from a caller-supplied name");
        assertTrue(file.getFileName().toString().endsWith(".snapshot.gz"),
                "the name must not pretend to be an NBT structure file");
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(0x1F, bytes[0] & 0xFF, "a GZIP member is what the suffix promises");
        assertEquals(0x8B, bytes[1] & 0xFF);
    }

    @Test
    void anEmptySnapshotIsReadableAgain() throws IOException {
        // Not a case the runtime plans, but write/read must agree on it rather than one side refusing.
        Path file = EndPodiumBackup.write(dataDir, WORLD, JOB, List.of());

        EndPodiumBackup.Snapshot read = EndPodiumBackup.read(dataDir, WORLD, JOB);

        assertEquals(List.of(), read.cells());
        assertEquals(JOB, read.jobId());
        assertEquals(file, EndPodiumBackup.pathFor(dataDir, WORLD, JOB),
                "the bounded read must resolve to the very file write returned");
    }

    @Test
    void aSnapshotIsImmutable() {
        List<EndPodiumBackup.Cell> mutable = new ArrayList<>();
        mutable.add(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"));

        EndPodiumBackup.Snapshot snapshot = new EndPodiumBackup.Snapshot(WORLD, JOB, mutable);
        mutable.clear();

        assertEquals(1, snapshot.cells().size(),
                "the snapshot must own its cells: a caller editing its list afterwards would change a rollback");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.cells().add(new EndPodiumBackup.Cell(1, 60, 0, "minecraft:air")));
    }

    @Test
    void aSecondWriteForTheSameJobNeverReplacesTheFirstBackup() throws IOException {
        List<EndPodiumBackup.Cell> first = List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"));
        Path file = EndPodiumBackup.write(dataDir, WORLD, JOB, first);
        byte[] original = Files.readAllBytes(file);

        IOException error = assertThrows(IOException.class, () -> EndPodiumBackup.write(dataDir, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:stone"))));

        assertTrue(error.getMessage().contains("已存在"), "the refusal must say why: " + error.getMessage());
        assertArrayEquals(original, Files.readAllBytes(file),
                "the first snapshot is the only copy of the original blocks; it must stay byte-identical");
        assertEquals(first, EndPodiumBackup.read(file).cells(), "and it must still be the original, not a blend");
        assertNoStagingFiles();
    }

    @Test
    void aDirectoryWhereTheSnapshotBelongsIsRefused() throws IOException {
        Path file = EndPodiumBackup.pathFor(dataDir, WORLD, JOB);
        Files.createDirectories(file);

        assertThrows(IOException.class, () -> EndPodiumBackup.write(dataDir, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"))));

        assertTrue(Files.isDirectory(file), "a refused write must leave the directory a directory");
    }

    @Test
    void duplicateCoordinatesAreRefusedBeforeAnythingIsCreated() {
        List<EndPodiumBackup.Cell> duplicating = List.of(
                new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"),
                new EndPodiumBackup.Cell(0, 60, 0, "minecraft:stone"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EndPodiumBackup.write(dataDir, WORLD, JOB, duplicating));

        assertTrue(error.getMessage().contains("重复"), "the refusal must name the cause: " + error.getMessage());
        assertFalse(Files.exists(dataDir.resolve(EndPodiumBackup.DIRECTORY_NAME)),
                "a rejected cell list must not even create the backup directory");
    }

    @Test
    void anEmptyBlockStateIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new EndPodiumBackup.Cell(0, 60, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new EndPodiumBackup.Cell(0, 60, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new EndPodiumBackup.Cell(0, 60, 0, "minecraft:air\n/x"));
    }

    @Test
    void aFileThatIsNotASnapshotIsRefused() throws IOException {
        Path file = dataDir.resolve("not-a-snapshot.snapshot.gz");
        Files.writeString(file, "this is a text file, not a GZIP member");

        assertThrows(IOException.class, () -> EndPodiumBackup.read(file));
    }

    @Test
    void aSnapshotsHeaderAndVersionAreChecked() throws IOException {
        Path wrongMagic = writeRaw("wrong-magic.snapshot.gz",
                craft("NOTOURS!".getBytes(StandardCharsets.UTF_8), EndPodiumBackup.VERSION, 1,
                        List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"))));
        IOException magicError = assertThrows(IOException.class, () -> EndPodiumBackup.read(wrongMagic));
        assertTrue(magicError.getMessage().contains("文件头"), "the refusal must distinguish another format: "
                + magicError.getMessage());

        Path unknownVersion = writeRaw("version-99.snapshot.gz",
                craft(magic(), 99, 1, List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"))));
        IOException versionError = assertThrows(IOException.class, () -> EndPodiumBackup.read(unknownVersion));
        assertTrue(versionError.getMessage().contains("版本"), "the refusal must name the version: "
                + versionError.getMessage());
    }

    @Test
    void aTruncatedSnapshotIsNeverReadAsComplete() throws IOException {
        Path whole = EndPodiumBackup.write(dataDir, WORLD, JOB, List.of(
                new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"),
                new EndPodiumBackup.Cell(1, 60, 0, "minecraft:end_stone_bricks")));
        byte[] bytes = Files.readAllBytes(whole);

        Path cut = dataDir.resolve("cut.snapshot.gz");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length - 12));

        assertThrows(IOException.class, () -> EndPodiumBackup.read(cut),
                "a truncated file must fail loudly; a half rollback that looks complete is the harm");
    }

    @Test
    void aCountLargerThanTheDataIsRefused() throws IOException {
        Path file = writeRaw("short.snapshot.gz", craft(magic(), EndPodiumBackup.VERSION, 3,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"))));

        assertThrows(IOException.class, () -> EndPodiumBackup.read(file));
    }

    @Test
    void aDuplicateCoordinateInsideTheFileIsRefused() throws IOException {
        EndPodiumBackup.Cell same = new EndPodiumBackup.Cell(4, 61, 4, "minecraft:bedrock");
        Path file = writeRaw("duplicate.snapshot.gz",
                craft(magic(), EndPodiumBackup.VERSION, 2, List.of(same, same)));

        IOException error = assertThrows(IOException.class, () -> EndPodiumBackup.read(file));

        assertTrue(error.getMessage().contains("重复坐标"),
                "a restore cannot pick one of two states for a cell: " + error.getMessage());
    }

    @Test
    void dataAppendedToASnapshotIsRefused() throws IOException {
        Path whole = EndPodiumBackup.write(dataDir, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock")));
        ByteArrayOutputStream appended = new ByteArrayOutputStream();
        appended.write(Files.readAllBytes(whole));
        appended.write(extraMember());
        Path file = dataDir.resolve("appended.snapshot.gz");
        Files.write(file, appended.toByteArray());

        IOException error = assertThrows(IOException.class, () -> EndPodiumBackup.read(file));

        assertTrue(error.getMessage().contains("多余") || error.getMessage().contains("截断"),
                "appended data must not be silently ignored: " + error.getMessage());
    }

    @Test
    void aStateLengthBeyondTheLimitIsRefusedWithoutAllocating() throws IOException {
        Path file = dataDir.resolve("huge-state.snapshot.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.write(magic());
            out.writeInt(EndPodiumBackup.VERSION);
            writeIds(out);
            out.writeInt(1);
            out.writeInt(0);
            out.writeInt(60);
            out.writeInt(0);
            out.writeInt(Integer.MAX_VALUE);
        }

        IOException error = assertThrows(IOException.class, () -> EndPodiumBackup.read(file));

        assertTrue(error.getMessage().contains("长度"), "the refusal must name the bad length: " + error.getMessage());
    }

    @Test
    void aSnapshottedPathWithParentSegmentsStillLandsInsideTheDataDirectory() throws IOException {
        // The plugin data directory arrives from the server and can be handed over with a trailing
        // segment; normalisation must not let the fixed layout walk out of it.
        Path noisy = dataDir.resolve("some").resolve("..");

        Path file = EndPodiumBackup.write(noisy, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock")));

        assertTrue(file.toAbsolutePath().normalize().startsWith(dataDir.toAbsolutePath().normalize()),
                "a snapshot must stay inside the plugin data directory, got " + file);
        assertEquals(JOB, EndPodiumBackup.read(dataDir, WORLD, JOB).jobId(),
                "and the bounded read must find it at the same canonical location");
    }

    @Test
    void theBoundedReadOnlyLooksInsideTheDataDirectory() throws IOException {
        // A valid snapshot living somewhere else must not be reachable through the world/job API: the
        // boundary is the plugin's data folder, not whatever a command or config produced.
        Path elsewhere = Files.createDirectories(dataDir.resolve("elsewhere"));
        Path stray = EndPodiumBackup.write(elsewhere, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock")));

        assertThrows(IOException.class, () -> EndPodiumBackup.read(dataDir, WORLD, JOB));

        assertEquals(JOB, EndPodiumBackup.read(stray).jobId(),
                "the plain path overload still reads it, which is what a manual recovery from a copied file needs");
    }

    @Test
    void aSymlinkedBackupDirectoryIsRefused() throws IOException {
        Path outside = Files.createDirectories(dataDir.resolve("outside"));
        Path root = dataDir.resolve(EndPodiumBackup.DIRECTORY_NAME);
        createSymlinkOrAbort(root, outside);

        IOException error = assertThrows(IOException.class, () -> EndPodiumBackup.write(dataDir, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock"))));

        assertTrue(error.getMessage().contains("symbolic") || error.getMessage().contains("符号链接"),
                "the refusal must say why: " + error.getMessage());
        try (Stream<Path> listing = Files.list(outside)) {
            assertEquals(List.of(), listing.toList(), "nothing may be written through the link");
        }
    }

    @Test
    void aSymlinkedSnapshotFileIsRefusedOnRead() throws IOException {
        Path real = EndPodiumBackup.write(dataDir, WORLD, JOB,
                List.of(new EndPodiumBackup.Cell(0, 60, 0, "minecraft:bedrock")));
        Path link = dataDir.resolve("link.snapshot.gz");
        createSymlinkOrAbort(link, real);

        IOException error = assertThrows(IOException.class, () -> EndPodiumBackup.read(link));

        assertTrue(error.getMessage().contains("符号链接"), "the refusal must say why: " + error.getMessage());
        assertTrue(Files.isSymbolicLink(link), "the link itself must not be consumed");
    }

    private Path writeRaw(String name, byte[] bytes) throws IOException {
        Path file = dataDir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static byte[] magic() {
        return "BEPODIUM".getBytes(StandardCharsets.UTF_8);
    }

    /** Builds a snapshot byte-for-byte with a caller-chosen magic, version and declared count. */
    private static byte[] craft(byte[] magic, int version, int declaredCount, List<EndPodiumBackup.Cell> cells)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buffer))) {
            out.write(magic);
            out.writeInt(version);
            writeIds(out);
            out.writeInt(declaredCount);
            for (EndPodiumBackup.Cell cell : cells) {
                out.writeInt(cell.x());
                out.writeInt(cell.y());
                out.writeInt(cell.z());
                byte[] state = cell.state().getBytes(StandardCharsets.UTF_8);
                out.writeInt(state.length);
                out.write(state);
            }
        }
        return buffer.toByteArray();
    }

    private static void writeIds(DataOutputStream out) throws IOException {
        out.writeLong(WORLD.getMostSignificantBits());
        out.writeLong(WORLD.getLeastSignificantBits());
        out.writeLong(JOB.getMostSignificantBits());
        out.writeLong(JOB.getLeastSignificantBits());
    }

    /** A second, valid GZIP member -- the shape "somebody appended a file" takes. */
    private static byte[] extraMember() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buffer))) {
            out.write(0);
        }
        return buffer.toByteArray();
    }

    private void createSymlinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException refused) {
            // Windows without Developer Mode / SeCreateSymbolicLinkPrivilege cannot create a link at all.
            // Reporting the case green would claim coverage that never ran, so it is aborted out loud.
            Assumptions.abort("本机无法创建符号链接，" + getClass().getSimpleName() + " 的 symlink 用例未执行：" + refused);
        }
    }

    private void assertNoStagingFiles() throws IOException {
        try (Stream<Path> listing = Files.walk(dataDir)) {
            List<String> leftovers = listing.map(p -> p.getFileName().toString())
                    .filter(name -> name.contains(".tmp-"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "staging files must be cleaned up, found: " + leftovers);
        }
    }
}
