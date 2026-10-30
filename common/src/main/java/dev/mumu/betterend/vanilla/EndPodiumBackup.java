package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.ResourcePaths;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The recoverable snapshot of exactly the blocks one中心祭坛 construction round is about to overwrite.
 *
 * <h2>Why a format of our own, and why not NBT</h2>
 * A snapshot here has one job: after a failed multi-batch write, answer "what was in these cells before
 * we touched anything" without a world present -- a plain JVM must be able to read it back for a test or
 * a manual recovery. That is a list of {@code (x, y, z) -> 方块状态字符串}, nothing else; structure NBT
 * would drag in a size, a palette, entities and a whole reader that this never uses, and calling a
 * self-invented byte layout {@code .nbt} would be a lie about who can open it. So the file is
 * {@code .snapshot.gz}: a GZIP member (which gives truncation detection through its CRC/trailer for
 * free) wrapping a fixed header and a per-cell record described in {@link #VERSION}.
 *
 * <h2>What this class is not</h2>
 * It never touches a {@code World}, a {@code Block} or an entity -- the caller reads the cells on the
 * thread that owns them and hands over plain values, and all file I/O here may run on any thread the
 * runtime schedules it on. It is also not a transaction framework: it stores the cells one round was
 * told to save, and restoring them is the caller's own region-scheduled work.
 *
 * <h2>Rules the file layout has to hold</h2>
 * <ul>
 *   <li><b>Fixed location only.</b> {@code <pluginData>/podium-backups/<worldUUID>/<jobUUID>.snapshot.gz}
 *       -- never a caller-supplied path. A backup that could be named by anything but its own world and
 *       job ids is a backup that can be pointed at somebody else's file.</li>
 *   <li><b>No symlink, no escape.</b> Neither the base directory's ancestors nor any directory this
 *       class creates may be a symbolic link, and the resolved file must still sit under the plugin's
 *       data directory (see {@link ResourcePaths}).</li>
 *   <li><b>An old backup is never replaced.</b> A second snapshot for the same job id fails and leaves
 *       the first byte-identical: losing the only copy of the original blocks is worse than a failed
 *       job. That is also why {@code AtomicFiles.replace} -- which replaces by design, correctly, for
 *       config files -- is not used for the landing move here.</li>
 *   <li><b>Half a snapshot never appears at the final path.</b> The bytes are staged in a uniquely named
 *       temp file in the target's own directory and only moved onto the final name after the stream has
 *       been closed successfully, so a failed write leaves the temp file (deleted best effort) and no
 *       {@code .snapshot.gz} that a later recovery would read as complete.</li>
 * </ul>
 *
 * <h2>Only block states</h2>
 * {@link Cell#state()} is the canonical {@code BlockData} string of a plain block
 * ({@code minecraft:stone_bricks}, {@code minecraft:oak_stairs[facing=north,...]}). Block entities --
 * containers, signs, the {@code minecraft:end_portal} block entity in the protected zone, or anything
 * else whose contents live outside the block state -- are not representable here by design: the runtime
 * stops the job when it finds one instead of backing up an empty shell. This class therefore never asks
 * Bukkit to parse the string; it only checks the text is sane, and the shape of a real state string is
 * the runtime's contract.
 */
public final class EndPodiumBackup {

    /** The directory under the plugin data directory that holds every snapshot. */
    public static final String DIRECTORY_NAME = "podium-backups";

    /** The suffix that marks a snapshot, chosen so nobody mistakes it for a vanilla structure file. */
    public static final String FILE_SUFFIX = ".snapshot.gz";

    /** The only layout version this class writes and reads; a different number is refused, not guessed. */
    public static final int VERSION = 1;

    /** {@code BEPODIUM} -- names the writer, so a stray file cannot pass as a snapshot. */
    private static final byte[] MAGIC = {'B', 'E', 'P', 'O', 'D', 'I', 'U', 'M'};

    /**
     * Upper bound on cells in one snapshot, checked on write <em>and</em> on read before anything is
     * allocated. The centre tower is 29×27×29 = 17,383 cells; this leaves room for a round that covers
     * several structure pieces while still refusing a header that would make a malformed file ask for
     * unbounded memory.
     */
    static final int MAX_CELLS = 262_144;

    /**
     * Upper bound on one canonical state string, in UTF-8 bytes. Real states are well under 200 bytes;
     * the cap exists so a corrupted length field is refused before it becomes an allocation.
     */
    static final int MAX_STATE_BYTES = 1024;

    private EndPodiumBackup() {
    }

    /**
     * One backed-up cell: where it is and what block state it held.
     *
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @param state the canonical {@code BlockData} string, e.g. {@code minecraft:stone_bricks}
     */
    public record Cell(int x, int y, int z, String state) {
        public Cell {
            if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000) {
                throw new IllegalArgumentException("格子坐标 (" + x + ", " + y + ", " + z
                        + ") 的水平范围超出合理界限 ±30000000，拒绝写入备份");
            }
            if (y < -2048 || y > 2047) {
                throw new IllegalArgumentException("格子坐标 (" + x + ", " + y + ", " + z
                        + ") 的高度超出合理界限 -2048..2047，拒绝写入备份");
            }
            if (state == null || state.isEmpty()) {
                throw new IllegalArgumentException("格子 (" + x + ", " + y + ", " + z + ") 的方块状态为空");
            }
            for (int i = 0; i < state.length(); i++) {
                char c = state.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException("格子 (" + x + ", " + y + ", " + z
                            + ") 的方块状态含控制字符，不是规范化的方块状态字符串：" + (int) c);
                }
            }
            int bytes = state.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_STATE_BYTES) {
                throw new IllegalArgumentException("格子 (" + x + ", " + y + ", " + z + ") 的方块状态长度 "
                        + bytes + " 字节，超过上限 " + MAX_STATE_BYTES + "：" + state);
            }
        }
    }

    /**
     * A whole snapshot, immutable. {@link #cells()} is a copy the caller cannot modify, and its order is
     * the write order, so two reads of the same file are equal.
     *
     * @param worldId the world UUID the cells belong to
     * @param jobId   the construction round (job) UUID this snapshot was taken for
     * @param cells   the original state of every cell the round may touch
     */
    public record Snapshot(UUID worldId, UUID jobId, List<Cell> cells) {
        public Snapshot {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(cells, "cells");
            cells = List.copyOf(cells);
        }
    }

    /**
     * The one path a snapshot may live at, for {@code pluginDataDir} / {@code worldId} / {@code jobId}.
     *
     * <p>Public because the runtime has to ask "is there already a snapshot for this job?" before it
     * starts writing blocks; nothing outside this class may build the path itself.
     *
     * @throws IOException if the layout ever fails to resolve inside {@code pluginDataDir}. That cannot
     *                     happen with two UUIDs, and the check stays because a future refactor that let a
     *                     caller-supplied name in here would otherwise escape silently.
     */
    public static Path pathFor(Path pluginDataDir, UUID worldId, UUID jobId) throws IOException {
        Objects.requireNonNull(pluginDataDir, "pluginDataDir");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(jobId, "jobId");
        Path base = pluginDataDir.toAbsolutePath().normalize();
        Path file = base.resolve(DIRECTORY_NAME)
                .resolve(worldId.toString())
                .resolve(jobId + FILE_SUFFIX)
                .normalize();
        if (!file.startsWith(base)) {
            throw new IOException("备份路径解析后越出插件数据目录 " + base + "（得到 " + file + "），拒绝写入");
        }
        return file;
    }

    /**
     * Saves {@code cells} as this job's snapshot and returns the file it landed at.
     *
     * <p>Every cell is validated before any byte or directory is created, so an invalid cell leaves no
     * trace at all. The bytes then go into a uniquely named temp file in the target's own directory and
     * are moved onto the final name only after the stream closed cleanly -- either the whole snapshot is
     * there under its final name, or the call throws and the world must not be touched.
     *
     * @return the absolute path of the written snapshot
     * @throws IllegalArgumentException if a cell is null, a state is empty or oversized, or two cells
     *                                  share a coordinate (a duplicate would make the restore ambiguous)
     * @throws IOException              if the parent directory cannot be created safely, an old backup
     *                                  already sits at the target (it is never replaced), a symlink is in
     *                                  the way, or the write fails. No partial file is left at the target.
     */
    public static Path write(Path pluginDataDir, UUID worldId, UUID jobId, List<Cell> cells) throws IOException {
        Objects.requireNonNull(cells, "cells");
        Path target = pathFor(pluginDataDir, worldId, jobId);
        // Validation first: a rejected cell list must not even create the world's backup directory.
        requireValidCells(cells);
        createDirectories(pluginDataDir.toAbsolutePath().normalize(), target.getParent());
        requireNewTarget(target);
        writeNew(target, worldId, jobId, cells);
        return target;
    }

    /**
     * Reads a snapshot file.
     *
     * <p>Refuses a symlink and a non-regular file before opening anything: a link planted here would
     * otherwise let a "recovery" read a file this plugin never wrote. Every structural problem -- bad
     * magic, another layout version, a truncated stream, a count that does not match the data, a
     * duplicate coordinate, trailing bytes -- is an {@link IOException}, never a half-filled
     * {@link Snapshot}: a caller that got a snapshot back may restore from it.
     */
    public static Snapshot read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath().normalize();
        requireReadable(absolute);
        try (InputStream raw = Files.newInputStream(absolute);
             DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            return decode(in, absolute);
        } catch (EOFException truncated) {
            throw new IOException("备份文件被截断，读不到它声明的内容：" + absolute, truncated);
        } catch (CharacterCodingException malformed) {
            throw new IOException("备份文件含非法 UTF-8 方块状态：" + absolute, malformed);
        }
    }

    /**
     * Reads the snapshot of {@code jobId} in {@code worldId}, resolving it through {@link #pathFor} so the
     * caller cannot aim a recovery at a path of its choosing -- the boundary is the plugin's data
     * directory, not whatever a command or config produced.
     */
    public static Snapshot read(Path pluginDataDir, UUID worldId, UUID jobId) throws IOException {
        return read(pathFor(pluginDataDir, worldId, jobId));
    }

    /** Rejects null cells, blank or oversized states (via {@link Cell}) and duplicate coordinates. */
    private static void requireValidCells(List<Cell> cells) {
        if (cells.size() > MAX_CELLS) {
            throw new IllegalArgumentException("本次快照有 " + cells.size() + " 个格子，超过上限 " + MAX_CELLS);
        }
        Set<Coord> seen = new HashSet<>(cells.size() * 2);
        int index = 0;
        for (Cell cell : cells) {
            index++;
            if (cell == null) {
                throw new IllegalArgumentException("第 " + index + " 个格子为 null");
            }
            if (!seen.add(new Coord(cell.x(), cell.y(), cell.z()))) {
                throw new IllegalArgumentException("第 " + index + " 个格子的坐标 ("
                        + cell.x() + ", " + cell.y() + ", " + cell.z()
                        + ") 与前面的格子重复；重复坐标会让恢复无法判断该写回哪一个");
            }
        }
    }

    /**
     * Creates every missing directory between {@code baseDir} and {@code dir} one level at a time,
     * refusing a symlink or a non-directory where a directory belongs.
     *
     * <p>{@code Files#createDirectories} is not used because it neither reports nor refuses a link
     * halfway down the chain: it would happily create {@code podium-backups/<world>} <em>through</em> a
     * link the owner left pointing somewhere else. Each level is therefore checked with
     * {@code NOFOLLOW_LINKS} before it is created, and the base directory's ancestors are checked by
     * {@link ResourcePaths#requireAncestors}.
     */
    private static void createDirectories(Path baseDir, Path dir) throws IOException {
        if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("插件数据目录不存在或不是目录，无法保存备份：" + baseDir);
        }
        ResourcePaths.requireAncestors(baseDir);
        Path walk = baseDir;
        for (Path segment : baseDir.relativize(dir)) {
            walk = walk.resolve(segment);
            if (Files.isSymbolicLink(walk)) {
                throw new IOException("备份目录 " + walk + " 是符号链接；拒绝顺着链接把备份写到插件目录之外");
            }
            if (Files.exists(walk, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(walk, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("备份目录 " + walk + " 已存在但不是目录；拒绝写入");
                }
                continue;
            }
            Files.createDirectory(walk);
        }
    }

    /** Refuses anything already at the target: an old backup is evidence, not something to overwrite. */
    private static void requireNewTarget(Path target) throws IOException {
        ResourcePaths.Entry entry = ResourcePaths.entryAt(target);
        switch (entry) {
            case ABSENT -> {
                // the only state a snapshot may be created from
            }
            case REGULAR_FILE -> throw new IOException("该任务的备份已存在，绝不覆盖旧备份：" + target);
            case SYMLINK -> throw new IOException("备份目标是符号链接，拒绝写入：" + target);
            default -> throw new IOException("备份目标" + ResourcePaths.describe(entry) + "，拒绝写入：" + target);
        }
    }

    /** Stages the stream in a unique temp file beside the target, then lands it under its final name. */
    private static void writeNew(Path target, UUID worldId, UUID jobId, List<Cell> cells) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (OutputStream raw = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
                encode(out, worldId, jobId, cells);
            }
            land(temp, target);
        } finally {
            // Best effort, exactly like AtomicFiles: a leftover staging file must never mask the error
            // the caller has to see, and after a successful move there is nothing left to delete.
            deleteQuietly(temp);
        }
    }

    /**
     * Moves the finished temp file onto the final name, refusing an existing target.
     *
     * <p>Deliberately <em>without</em> {@code ATOMIC_MOVE}: on POSIX that flag maps to {@code rename()},
     * which silently replaces the destination, and the one thing this method must never do is throw away
     * the previous snapshot. The plain move is specified to fail with
     * {@code FileAlreadyExistsException} when something is there, and because the temp file lives in the
     * target's own directory the move is a rename on every filesystem this plugin runs on -- so a crash
     * cannot leave a half-written snapshot under the final name either.
     */
    private static void land(Path temp, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("该任务的备份已存在，绝不覆盖旧备份：" + target);
        }
        Files.move(temp, target);
    }

    /** Writes the whole {@link #VERSION} 1 layout. Caller owns the stream's closing. */
    private static void encode(DataOutputStream out, UUID worldId, UUID jobId, List<Cell> cells) throws IOException {
        out.write(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(worldId.getMostSignificantBits());
        out.writeLong(worldId.getLeastSignificantBits());
        out.writeLong(jobId.getMostSignificantBits());
        out.writeLong(jobId.getLeastSignificantBits());
        out.writeInt(cells.size());
        for (Cell cell : cells) {
            out.writeInt(cell.x());
            out.writeInt(cell.y());
            out.writeInt(cell.z());
            byte[] state = cell.state().getBytes(StandardCharsets.UTF_8);
            out.writeInt(state.length);
            out.write(state);
        }
    }

    /** Reads the {@link #VERSION} 1 layout, refusing every inconsistency instead of repairing it. */
    private static Snapshot decode(DataInputStream in, Path file) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new IOException("不是本插件的祭坛备份（文件头不匹配），拒绝按备份读取：" + file);
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("不支持的备份格式版本 " + version + "，本插件只读版本 " + VERSION + "：" + file);
        }
        UUID worldId = new UUID(in.readLong(), in.readLong());
        UUID jobId = new UUID(in.readLong(), in.readLong());
        int count = in.readInt();
        if (count < 0 || count > MAX_CELLS) {
            throw new IOException("备份声明的格数 " + count + " 不在 0.." + MAX_CELLS + " 之内：" + file);
        }
        List<Cell> cells = new ArrayList<>(Math.min(count, 16_384));
        Set<Coord> seen = new HashSet<>(Math.min(count, 16_384) * 2);
        for (int index = 1; index <= count; index++) {
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            int length = in.readInt();
            if (length <= 0 || length > MAX_STATE_BYTES) {
                throw new IOException("第 " + index + " 格的方块状态长度 " + length
                        + " 不在 1.." + MAX_STATE_BYTES + " 之内：" + file);
            }
            byte[] encoded = new byte[length];
            in.readFully(encoded);
            String state = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
            Cell cell;
            try {
                cell = new Cell(x, y, z, state);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("第 " + index + " 格不合法（" + invalid.getMessage() + "）：" + file, invalid);
            }
            if (!seen.add(new Coord(x, y, z))) {
                throw new IOException("备份含重复坐标 (" + x + ", " + y + ", " + z + ")，恢复无法判断写回哪一个："
                        + file);
            }
            cells.add(cell);
        }
        if (in.read() != -1) {
            throw new IOException("备份在 " + count + " 个格子之后还有多余数据（可能被追加或拼接）：" + file);
        }
        return new Snapshot(worldId, jobId, cells);
    }

    /** Refuses a symlink or a non-regular file before it is opened. */
    private static void requireReadable(Path file) throws IOException {
        ResourcePaths.requireAncestors(file.getParent());
        if (Files.isSymbolicLink(file)) {
            throw new IOException("备份文件是符号链接，拒绝读取：" + file);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("备份文件不存在或不是普通文件：" + file);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Never mask the failure the caller needs to see.
        }
    }

    /** A coordinate, for duplicate detection only: equals/hashCode over the three ints. */
    private record Coord(int x, int y, int z) {
    }
}
