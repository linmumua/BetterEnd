package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's diagnosis, driven without a server: {@link VanillaEndCheck#problems} is deliberately
 * a pure function of the three facts {@code warnIfBroken} samples off the {@code World}, because
 * the common module's test classpath carries no paper-api runtime.
 *
 * <p>The origin sample is likewise tested at its seam ({@link VanillaEndCheck#originExisted}) rather
 * than through a {@code World}: the whole reason it no longer calls
 * {@code World#isChunkGenerated} is that the level-mediated query blocks on Folia's region queue
 * during world init, so what matters here is that the decision is a function of the region files
 * alone -- present, non-empty, absent, or unreadable.
 *
 * <p>What is NOT tested here, and why: that {@code WorldInitEvent} fires before {@code prepareLevel}
 * and that the world PDC is writable at that point are Paper's behaviour, not ours -- they are
 * cited in the class javadoc and only a running server can check them.
 */
class VanillaEndCheckTest {

    @Test
    void aHealthyEndIsSilent() {
        // Also the fresh-world case, which is the whole reason the check hangs off WorldInitEvent
        // and a first-boot marker: a world this boot generates itself reports originExisted false,
        // so it is not diagnosed as a broken migration and told to delete chunks that are fine.
        assertEquals(List.of(), VanillaEndCheck.problems("world_the_end", "THE_END", false));
    }

    @Test
    void aNonEndWorldIsReported() {
        List<String> problems = VanillaEndCheck.problems("overworld", "NORMAL", false);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("'overworld'"), problems.toString());
        assertTrue(problems.getFirst().contains("NORMAL"), "the message must name what it found");
        assertTrue(problems.getFirst().contains("THE_END"), "the message must name what it wanted");
        assertEquals(1, VanillaEndCheck.problems("nether", "NETHER", false).size());
        assertEquals(1, VanillaEndCheck.problems("custom", "CUSTOM", false).size());
    }

    @Test
    void aPreexistingOriginIsReportedWithTheChunksToDelete() {
        List<String> problems = VanillaEndCheck.problems("world_the_end", "THE_END", true);
        assertEquals(1, problems.size(), problems.toString());
        String message = problems.getFirst();
        assertTrue(message.contains("'world_the_end'"), message);
        // The migration advice is only useful if it names the exact chunk window; an off-by-one
        // leaves a pillar in a stale chunk.
        assertTrue(message.contains(VanillaEndCore.coreChunkMin() + ".." + VanillaEndCore.coreChunkMax()),
                message);
    }

    @Test
    void bothConditionsAreReportedTogether() {
        // The old code returned early in the middle of the checks, so the second one could never be
        // seen once the first fired.
        List<String> problems = VanillaEndCheck.problems("broken", "NORMAL", true);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().allMatch(line -> line.contains("'broken'")), problems.toString());
        assertEquals(2, problems.stream().distinct().count(), "the same line twice is not two problems");
    }

    // --- the origin sample: region files, never a chunk query ------------------------------------

    @Test
    void aFreshDimensionHasNoOrigin(@TempDir Path dimensionFolder) throws IOException {
        // The normal case for a new world: nothing written yet. Must be false, or every first boot
        // tells the owner to delete the chunks it is about to generate.
        Files.createDirectories(dimensionFolder.resolve("region"));
        assertFalse(VanillaEndCheck.originExisted(dimensionFolder));
    }

    @Test
    void aMissingDimensionFolderHasNoOrigin(@TempDir Path worldFolder) {
        // A world whose End dimension was never entered has no folder at all. Absence is not an
        // error: isRegularFile answers false instead of throwing, so this never logs.
        assertFalse(VanillaEndCheck.originExisted(worldFolder.resolve("never_entered")));
    }

    @Test
    void aRegionFileWithDataIsAPreexistingOrigin(@TempDir Path dimensionFolder) throws IOException {
        writeRegionFile(dimensionFolder, 0, 0, 8192);
        assertTrue(VanillaEndCheck.originExisted(dimensionFolder));
    }

    @Test
    void anEmptyRegionFileIsNotAPreexistingOrigin(@TempDir Path dimensionFolder) throws IOException {
        // size == 0 is what a crash between region-file creation and the first chunk write leaves
        // behind. Reporting it would send the owner to delete chunks that hold nothing.
        writeRegionFile(dimensionFolder, 0, 0, 0);
        assertFalse(VanillaEndCheck.originExisted(dimensionFolder));
    }

    @Test
    void anOriginOnlyOnTheNegativeSideStillCounts(@TempDir Path dimensionFolder) throws IOException {
        // The core window is -24..24 chunks, so it reaches into region -1 on both axes. Probing only
        // r.0.0.mca would miss a world whose origin was generated on the -x/-z side first.
        writeRegionFile(dimensionFolder, -1, -1, 8192);
        assertTrue(VanillaEndCheck.originExisted(dimensionFolder));
        // Probed again on purpose: the sample must not consume or cache anything, because it is
        // taken once per world init and nothing may depend on call order.
        assertTrue(VanillaEndCheck.originExisted(dimensionFolder), "the probe must not consume state");
    }

    @Test
    void aRegionFileOutsideTheCoreWindowIsIgnored(@TempDir Path dimensionFolder) throws IOException {
        // Chunk 32 is region 1, eight chunks past the last core chunk. An End the player merely
        // visited far away says nothing about whether the origin exists, and warning there would
        // advise deleting chunks that this boot is about to generate correctly.
        writeRegionFile(dimensionFolder, 1, 0, 8192);
        writeRegionFile(dimensionFolder, 0, 1, 8192);
        assertFalse(VanillaEndCheck.originExisted(dimensionFolder));
    }

    @Test
    void theProbeCoversEveryRegionFileTheCoreWindowTouches(@TempDir Path dimensionFolder) {
        // CORE_RADIUS 384 is wider than one 512-block region file on each side of the origin, so the
        // window straddles the region grid's corner: exactly regions -1..0 on both axes. Pinned by
        // name because a wrong range here is invisible until an owner deletes the wrong chunks.
        Set<String> expected = Set.of(
                "r.-1.-1.mca", "r.-1.0.mca", "r.0.-1.mca", "r.0.0.mca");
        Set<String> actual = VanillaEndCheck.coreRegionFiles(dimensionFolder).stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    @Test
    void theMigrationAdviceIsOnlyEverGivenForAnEnd() {
        // The guard runs on every world using this generator, and a NORMAL world that has been played
        // ALWAYS has a region file covering the core window. If the probe were taken there, the
        // (correct) "this is not THE_END" line would arrive paired with advice to delete 49x49 chunks
        // of somebody's overworld -- which is why warnIfBroken gates the probe on THE_END and passes
        // false here. This pins the contract that pairing can never happen.
        List<String> problems = VanillaEndCheck.problems("overworld", "NORMAL", false);
        assertEquals(1, problems.size(), problems.toString());
        assertFalse(problems.getFirst().contains("delete chunks"),
                "a non-End world must never be told to delete its origin chunks: " + problems);
    }

    @Test
    void anUnreadableRegionFileIsTreatedAsNoOrigin(@TempDir Path dimensionFolder) throws IOException {
        // The failure mode that matters: a diagnosis must never fail a boot. An IOException answers
        // false (no warning) rather than propagating, and must not be read as "the origin exists".
        writeRegionFile(dimensionFolder, 0, 0, 8192);
        boolean answer = VanillaEndCheck.originExisted(dimensionFolder, regionFile -> {
            throw new IOException("simulated read failure");
        });
        assertFalse(answer);
    }

    /** Creates {@code region/r.<x>.<z>.mca} with {@code bytes} bytes, exactly as vanilla lays it out. */
    private static void writeRegionFile(Path dimensionFolder, int regionX, int regionZ, int bytes)
            throws IOException {
        Path regionFolder = Files.createDirectories(dimensionFolder.resolve("region"));
        Files.write(regionFolder.resolve("r." + regionX + "." + regionZ + ".mca"), new byte[bytes]);
    }
}
