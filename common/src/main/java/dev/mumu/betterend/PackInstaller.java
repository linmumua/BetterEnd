package dev.mumu.betterend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Releases one bundled pack directory onto disk. Pure {@code java.nio} on purpose -- no Bukkit, no
 * CraftEngine -- so the "first atomic install / complete-missing / never-overwrite" rules can be
 * unit-tested without a server.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>First install is atomic.</b> The whole pack is written into a sibling staging directory
 *       and then moved into place, so a crash (or a failed file) mid-copy leaves no target directory
 *       at all -- the next boot retries cleanly instead of finding a half-written pack.</li>
 *   <li><b>An existing directory is completed, never overwritten.</b> Files already present are left
 *       byte-for-byte alone; only missing files are added. This mirrors CraftEngine's own resource
 *       auto-completion and means a {@code -SNAPSHOT} update cannot clobber an owner's edits.</li>
 *   <li><b>Every write is path-checked and atomic.</b> Each relative path goes through
 *       {@link ResourcePaths#resolve}, which refuses traversal, absolute shapes and any symlinked
 *       ancestor, and the bytes land through {@link AtomicFiles#replace} so no partial file is ever
 *       visible. The path rules live in one place and are shared with {@link BundledPackUpgrades}.</li>
 *   <li><b>Only a regular file counts as "already there".</b> A symbolic link (dangling or not), a
 *       directory or anything else sitting where a bundled file belongs is reported as
 *       {@code failed}, never as {@code skipped} -- counting it as present would silently drop the
 *       file on every boot while telling the caller the pack is complete.</li>
 *   <li><b>Nothing the owner put there is deleted.</b> The only path this class ever removes is its
 *       own freshly-named staging directory; a user file, a link or a directory at a destination is
 *       refused, never cleaned up.</li>
 *   <li><b>Failures are reported, not hidden.</b> Every outcome is a count in the {@link Result}; a
 *       non-zero {@code failed} is the caller's signal to refuse to enable.</li>
 * </ul>
 */
public final class PackInstaller {
    /** The bytes of a bundled pack, keyed by '/'-separated relative path. */
    public interface Source {
        /** Relative paths, '/'-separated, e.g. {@code configuration/blocks/basic.yml}. */
        Set<String> paths();

        /** Opens one of {@link #paths()}; the caller closes it. */
        InputStream open(String relativePath) throws IOException;
    }

    /** {@code firstInstall} distinguishes a fresh atomic release from a completion of an existing folder. */
    public record Result(boolean firstInstall, int released, int skipped, int failed) {
    }

    private final String label;
    private final Logger log;

    public PackInstaller(String label, Logger log) {
        this.label = label;
        this.log = log;
    }

    /**
     * @param target the pack directory, e.g. {@code plugins/CraftEngine/resources/betterend}
     * @param source the bundled files
     */
    public Result install(Path target, Source source) throws IOException {
        ResourcePaths.Entry existing = ResourcePaths.entryAt(target);
        if (existing == ResourcePaths.Entry.DIRECTORY) {
            return complete(target, source);
        }
        if (existing != ResourcePaths.Entry.ABSENT) {
            // A link (even a dangling one), a plain file, or anything else at the pack path. Refusing is
            // the only honest answer: treating it as "absent" would let the atomic move replace whatever
            // the owner put there, and treating it as "present" would report a pack that never landed.
            log.warning("CraftEngine 资源包 '" + label + "' 的目标位置 " + target + " "
                    + ResourcePaths.describe(existing) + "，不是资源包目录；本次不写入任何文件，也不会删除该路径，"
                    + "请先自行处理它后重启。");
            return new Result(false, 0, 0, 1);
        }
        return firstInstall(target, source);
    }

    private Result firstInstall(Path target, Source source) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        deleteQuietly(staging);
        Counts counts;
        try {
            counts = copyInto(source, staging, false);
            if (counts.failed > 0) {
                deleteQuietly(staging);
                log.warning("CraftEngine 资源包 '" + label + "' 首次安装时有 " + counts.failed
                        + " 个文件失败，已放弃本次安装（未写入目标目录，下次启动重试）。");
                return new Result(true, counts.released, counts.skipped, counts.failed);
            }
            Files.createDirectories(target.getParent());
            move(staging, target);
        } catch (IOException error) {
            deleteQuietly(staging);
            throw error;
        }
        log.info("CraftEngine 资源包 '" + label + "' 首次安装完成，写入 " + counts.released + " 个文件。");
        return new Result(true, counts.released, counts.skipped, counts.failed);
    }

    private Result complete(Path target, Source source) throws IOException {
        Counts counts = copyInto(source, target, true);
        log.info("CraftEngine 资源包 '" + label + "' 已存在，仅补缺失：新增 " + counts.released
                + "，跳过 " + counts.skipped + "，失败 " + counts.failed + "。");
        return new Result(false, counts.released, counts.skipped, counts.failed);
    }

    private Counts copyInto(Source source, Path root, boolean skipExisting) {
        int released = 0;
        int skipped = 0;
        int failed = 0;
        for (String relative : source.paths()) {
            Path destination;
            try {
                destination = ResourcePaths.resolve(root, relative);
            } catch (IOException unsafe) {
                failed++;
                log.warning("内置资源 '" + relative + "' 的路径不安全，已拒绝写入：" + unsafe.getMessage());
                continue;
            }
            ResourcePaths.Entry state = ResourcePaths.entryAt(destination);
            if (state == ResourcePaths.Entry.SYMLINK || state == ResourcePaths.Entry.DIRECTORY
                    || state == ResourcePaths.Entry.OTHER) {
                failed++;
                log.warning("内置资源 '" + relative + "' 的目标 " + destination + " "
                        + ResourcePaths.describe(state) + "；既不覆盖也不删除，本次计为失败——"
                        + "若当成「已存在」跳过，这个文件会每次启动都被静默丢掉，资源包其实不完整。");
                continue;
            }
            if (skipExisting && state == ResourcePaths.Entry.REGULAR_FILE) {
                skipped++;
                continue;
            }
            try {
                write(source, relative, destination);
                released++;
            } catch (IOException error) {
                failed++;
                log.log(Level.WARNING, "写入 " + relative + " 失败", error);
            }
        }
        return new Counts(released, skipped, failed);
    }

    /** Reads the bundled bytes first, then lands them through the one shared atomic writer. */
    private static void write(Source source, String relative, Path destination) throws IOException {
        byte[] content;
        try (InputStream input = source.open(relative)) {
            if (input == null) {
                throw new IOException("缺少内置资源 " + relative);
            }
            content = input.readAllBytes();
        }
        Files.createDirectories(destination.getParent());
        AtomicFiles.replace(destination, content);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicRefused) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: the staging dir is disposable
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private record Counts(int released, int skipped, int failed) {
    }
}
