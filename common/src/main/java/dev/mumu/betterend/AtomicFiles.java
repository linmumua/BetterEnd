package dev.mumu.betterend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * The one way this plugin replaces a file it owns.
 * <p>
 * Every write that must not leave a half-written file behind -- {@code config.yml}, {@code messages.yml},
 * the CraftEngine resource pack -- goes through {@link #replace}: the new bytes are staged in a
 * <em>unique</em> temp file in the target's own directory and then moved over the target. Atomic
 * replacement is preferred; the non-atomic fallback is best effort, not a crash-durability guarantee.
 * Callers preserve a backup before replacing existing configuration. The random suffix matters: two writers racing on the same target (a reload
 * while a backup runs, say) can no longer trample each other's staging file the way a fixed
 * {@code .tmp} name allows.
 * <p>
 * Two shapes are refused rather than followed, because both turn "write our config" into "write somebody
 * else's file": a <b>symlink</b> -- including a dangling one, where a naive write would create a new file
 * at the link's destination, outside the plugin's folder -- and an existing <b>non-regular file</b> (a
 * directory, a device). This plugin's files are plain files in its own folder; anything else there is a
 * planted redirection, not a config.
 * <p>
 * Plain Java, no server, so it unit-tests on any OS.
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /**
     * Writes {@code content} to {@code target}, replacing it atomically where the filesystem allows.
     *
     * @param target  the file to write; its parent directory must already exist
     * @param content the complete new content, never a fragment
     * @throws IOException if the target is a symlink or not a plain file, or the write cannot land. The
     *                     non-atomic fallback has no crash-durability guarantee; callers retain backups.
     */
    public static void replace(Path target, byte[] content) throws IOException {
        Path name = target.getFileName();
        if (name == null) {
            throw new IOException("目标路径没有文件名字段，无法作为文件写入：" + target);
        }
        requireReplaceable(target);
        Path temp = target.resolveSibling(name + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            move(temp, target);
        } finally {
            // Best effort: after a successful move this is already gone; after a failed write the staging
            // file must not be left behind -- and a cleanup failure must never mask the real error.
            deleteQuietly(temp);
        }
    }

    /**
     * Copies {@code source} to {@code backup} through {@link #replace}, so the backup itself is never
     * half-written either.
     * <p>
     * The source is read only once it is known to be a plain file: a symlink planted at the config path
     * would otherwise have us copy the contents of a file outside the plugin's folder into the backup
     * that a later failed load restores from.
     *
     * @throws IOException if the source is a symlink, missing or not a plain file
     */
    public static void backup(Path source, Path backup) throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException("源文件是符号链接（symlink），拒绝备份：" + source);
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("源文件不存在或不是普通文件，拒绝备份：" + source);
        }
        replace(backup, Files.readAllBytes(source));
    }

    /**
     * Refuses a target this class will not write to, before any bytes move: a symlink (checked first, so
     * a dangling link is caught too -- {@code exists} alone would call it "missing" and create the file
     * at the far end), a missing parent directory, or an existing non-regular file.
     */
    private static void requireReplaceable(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("目标是符号链接（symlink），拒绝写入：" + target + "；请先把链接换成普通文件");
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        ResourcePaths.requireAncestors(parent);
        if (parent != null && !Files.isDirectory(parent)) {
            throw new IOException("目标的上级目录不存在或不是目录：" + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("目标已存在但不是普通文件（可能是目录），拒绝覆盖：" + target);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicRefused) {
            // Atomic replace where the filesystem supports it. Windows' MoveFileEx refuses the atomic
            // replace of an existing file, so that rejection is not an error -- it is the signal to fall
            // back to a plain replace, the best the platform offers. A cross-device ATOMIC_MOVE lands
            // here too, and the plain move then copies instead of renaming.
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover staging file must never mask the write error the caller needs to see.
        }
    }
}
