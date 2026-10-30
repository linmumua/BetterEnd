package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.DustWastelandsGenerator;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Startup diagnosis for the vanilla End furniture. The plugin cannot place the pillars itself
 * (see {@link VanillaEndCore}), so the only thing it can do about a misconfigured or already-broken
 * world is say so, loudly, once.
 *
 * <p>{@code WorldInitEvent}, NOT {@code WorldLoadEvent}, and that is the whole point of the class:
 * {@code World#getGenerator} is already set when init fires ({@code MinecraftServer.java.patch:438}
 * reads {@code overworld.generator} two lines before firing it at :444) but nothing has generated
 * yet -- {@code setInitialSpawn} and {@code prepareLevel} both come after
 * ({@code CraftServer.java:1306} then :1310). {@code WorldLoadEvent} fires at the END of
 * {@code prepareLevel} ({@code MinecraftServer.java.patch:572}), by which point this boot may have
 * generated the origin itself, and the "your origin chunks already exist" warning would be a
 * permanent false positive that tells owners to delete chunks.
 *
 * <p><b>Nothing here may block, and on Folia that is a hard constraint rather than a preference.</b>
 * The sampler used to call {@link World#isChunkGenerated}, which on this server
 * ({@code CraftWorld.java:408-413}) is not a local read: when the caller is not the tick thread for
 * the chunk it re-queues the query onto that chunk's region thread and {@code join()}s it. During
 * world init the calling thread is the one that will later reach {@code prepareLevel}, and it owns no
 * region yet, so {@code RegionizedTaskQueue} drops the task instead of queueing it
 * ({@code RegionizedTaskQueue.java:121-127}, {@code ThreadedRegionizer.java:240-252}) -- the join
 * never returns, and the thread that had to add the world to the regionizer is the one stuck waiting.
 * That was a real, reproduced boot deadlock, stalling just after {@code FloraPopulator} and before
 * {@code Selecting spawn point for the_end}. So the sample is now a plain {@code java.nio} question
 * asked of the filesystem, and no scheduler is involved at any point.
 *
 * <p>Reads no blocks and no chunk data at all, only whether a region file exists and is non-empty.
 * It never opens a chunk, so CraftEngine's material deception does not apply, and it touches nothing
 * a region thread owns.
 */
public final class VanillaEndCheck implements Listener {
    /** Written to the world's own PDC, so the migration warning fires at most once per world. */
    private static final NamespacedKey SEEN = new NamespacedKey("betterend", "seen");

    private static final Logger LOGGER = Logger.getLogger("BetterEnd");

    /** Vanilla's chunk storage directory name, sibling of {@code data/} and {@code entities/}. */
    private static final String REGION_DIRECTORY = "region";

    @EventHandler
    public void onWorldInit(@NotNull WorldInitEvent event) {
        warnIfBroken(event.getWorld());
    }

    /**
     * Registered blind for every world and self-selecting: which worlds use this generator lives in
     * bukkit.yml, which is not ours to read, so the world itself is asked instead.
     */
    public static void warnIfBroken(@NotNull World world) {
        if (!(world.getGenerator() instanceof DustWastelandsGenerator)) {
            return;
        }
        // ponytail: the byte only reaches disk at the next world save (ServerLevel.saveLevelData
        // copies this container into PaperWorldPDC), so a crash between world init and the first
        // save replays firstBoot on the next start -- and by then this boot (which no longer blocks,
        // so it does reach the save) HAS generated the origin, so the migration warning below fires
        // once as a false alarm advising a chunk delete that is not needed. That is why this is a
        // warning and never a repair action.
        // Upgrade path if it ever bites: write the marker to a plugin-owned file instead.
        boolean firstBoot = !world.getPersistentDataContainer().has(SEEN, PersistentDataType.BYTE);
        if (firstBoot) {
            world.getPersistentDataContainer().set(SEEN, PersistentDataType.BYTE, (byte) 1);
        }
        // Short-circuits, in this order: a world that is not THE_END, or not on its first boot, never
        // touches the disk. The environment gate is not an optimisation -- the "delete the origin
        // chunks" advice below is only ever correct for an End, and a NORMAL world that has been
        // played always has a region file covering the core window, so probing one would pair the
        // (correct) "this is not THE_END" line with advice to delete 49x49 chunks of somebody's
        // overworld. Refusing to sample outside THE_END is what keeps the honest-failure promise
        // above: a missed warning costs a log line, a false one costs a world.
        //
        // World#getWorldFolder IS the dimension folder on this core, not the world root --
        // CraftWorld#getWorldPath (CraftWorld.java:1472-1473) returns
        // storageSource.getDimensionPath(dimension), the api's getWorldFolder is exactly that path
        // (paper-api World.java:3009-3011), DimensionType.getStorageFolder appends
        // dimensions/<namespace>/<path> for EVERY dimension, the overworld included
        // (DimensionType.java:115-117), and ChunkMap puts the region files in <that>/region
        // (ChunkMap.java:190). Do NOT join a dimensions/... segment here.
        String environment = world.getEnvironment().name();
        boolean originExisted = "THE_END".equals(environment)
                && firstBoot
                && originExisted(world.getWorldFolder().toPath());
        problems(world.getName(), environment, originExisted).forEach(LOGGER::warning);
    }

    /**
     * Whether the origin chunks were already written to disk before this boot, asked of the region
     * files rather than of a level.
     *
     * @param dimensionFolder {@code World#getWorldFolder()}; see {@link #warnIfBroken}
     * @return {@code true} only when at least one region file covering the core chunks exists and is
     *         non-empty; every failure path answers {@code false}, because a missed warning costs a
     *         log line while a false one tells an owner to delete chunks
     */
    static boolean originExisted(@NotNull Path dimensionFolder) {
        return originExisted(dimensionFolder, REAL_FILES);
    }

    /**
     * The {@link #coreRegionFiles} probe with its one I/O question injected. Exists only so the
     * {@link IOException} branch can be driven from a unit test without a broken filesystem.
     */
    static boolean originExisted(@NotNull Path dimensionFolder, @NotNull RegionFileSize sizeOf) {
        for (Path regionFile : coreRegionFiles(dimensionFolder)) {
            try {
                if (sizeOf.of(regionFile) > 0L) {
                    return true;
                }
            } catch (IOException e) {
                LOGGER.warning("Could not tell whether the origin chunks of " + dimensionFolder
                        + " already exist (" + regionFile.getFileName() + ": " + e
                        + "). Skipping the pre-existing-origin warning rather than reporting a"
                        + " migration that may not have happened.");
                return false;
            }
        }
        return false;
    }

    /**
     * Every region file that could hold a chunk of {@link VanillaEndCore}'s core window.
     *
     * <p>The window is chunk {@code coreChunkMin()}..{@code coreChunkMax()} (-24..24 at the current
     * radius), which straddles the region-file grid's origin corner: at 32 chunks per region file it
     * spans regions -1..0 on both axes, i.e. four files. Probing only {@code r.0.0.mca} would miss a
     * world whose origin exists solely on the -x/-z side.
     *
     * <p>{@code >> 5} is vanilla's own region mapping ({@code ChunkPos.java:139-145}) and floors for
     * negative chunks the same way; {@code / 32} would fold -24 onto region 0. The file name is
     * vanilla's too ({@code RegionFileStorage.java:61}): {@code r.<regionX>.<regionZ>.mca}.
     */
    static List<Path> coreRegionFiles(@NotNull Path dimensionFolder) {
        Path regionFolder = dimensionFolder.resolve(REGION_DIRECTORY);
        int minRegion = VanillaEndCore.coreChunkMin() >> 5;
        int maxRegion = VanillaEndCore.coreChunkMax() >> 5;
        List<Path> out = new ArrayList<>();
        for (int regionX = minRegion; regionX <= maxRegion; regionX++) {
            for (int regionZ = minRegion; regionZ <= maxRegion; regionZ++) {
                out.add(regionFolder.resolve("r." + regionX + "." + regionZ + ".mca"));
            }
        }
        return out;
    }

    /** The single I/O call {@link #originExisted} is built from; {@code 0} means "no such chunk data". */
    @FunctionalInterface
    interface RegionFileSize {
        /** @return the file's length in bytes, or {@code 0} when it is absent or not a regular file */
        long of(@NotNull Path regionFile) throws IOException;
    }

    private static final RegionFileSize REAL_FILES = regionFile ->
            // isRegularFile does not throw on a missing parent, so a world that has never saved
            // takes this branch instead of the IOException one -- absence is the normal case here.
            Files.isRegularFile(regionFile) ? Files.size(regionFile) : 0L;

    /**
     * The diagnosis itself, as text: pure, so it is unit-tested without a server.
     *
     * @param environment    {@code World.Environment.name()}; anything but {@code THE_END} is broken
     * @param originExisted  a region file covering the core chunks was already on disk BEFORE this
     *                       boot, i.e. sampled only for THE_END --
     *                       i.e. this is the first boot with BetterEnd on a world that already ran
     * @return one line per problem, empty for a healthy world
     */
    static List<String> problems(String world, String environment, boolean originExisted) {
        List<String> out = new ArrayList<>();
        if (!"THE_END".equals(environment)) {
            // CraftWorld.java:1994-1996 returns a DragonBattle only when ServerLevel has one, and
            // ServerLevel only builds one when the DIMENSION TYPE has hasEnderDragonFight() -- never
            // based on the chunk generator. A NORMAL world running this generator therefore has no
            // dragon fight, no exit portal and no arrival platform, and no plugin can add them.
            out.add("World '" + world + "' uses the BetterEnd generator but its"
                    + " environment is " + environment + ", not THE_END. It will have no"
                    + " ender dragon, no exit portal and no obsidian arrival platform. Those are"
                    + " owned by the dimension type and cannot be restored by a plugin - generate"
                    + " the End dimension itself with this generator instead.");
        }
        if (originExisted) {
            // Chunk decoration runs exactly once, when a chunk is first generated. Installing
            // BetterEnd does not revisit a chunk that is already in the region file, and
            // World#regenerateChunk throws UnsupportedOperationException on this version
            // (paper-api World.java:506-508).
            out.add("This is the first startup of world '" + world + "' with BetterEnd, and its"
                    + " origin chunks already exist. Whatever they contain is what they keep: chunks"
                    + " are decorated once, at generation, and this version cannot revisit them."
                    + " If the obsidian pillars are missing, stop the server and delete chunks "
                    + VanillaEndCore.coreChunkMin() + ".." + VanillaEndCore.coreChunkMax()
                    + " on both axes in this dimension, then let them regenerate. A world that"
                    + " already has its pillars can ignore this.");
        }
        return out;
    }
}
