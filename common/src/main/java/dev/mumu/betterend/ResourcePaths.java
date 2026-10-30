package dev.mumu.betterend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The single gate every bundled-pack write goes through: which pack-relative paths may be written, and
 * what may already be sitting at the destination.
 *
 * <h2>Why this exists as its own class</h2>
 * {@link PackInstaller} and {@link BundledPackUpgrades} both turn a '/'-separated relative path into a
 * file on disk, and both used to do their own ad-hoc checks ({@code contains("..")}, {@code
 * resolve(dir)}, …). Two copies of a security rule drift, and each one misses what the other catches.
 * The rules are therefore stated once here, and both callers must go through {@link #resolve} before
 * writing.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Shapes that cannot name a file inside the pack are refused.</b> An absolute path, a drive
 *       letter or NTFS alternate data stream (any {@code :}), a Windows separator ({@code \}), an
 *       empty segment ({@code a//b}, {@code a/}) and {@code ..} are all rejected by
 *       {@link #requireRelative} with a message naming the offending path. Backslashes are refused
 *       rather than silently converted: on Windows {@code a\..\b} would otherwise normalise its way
 *       out of the pack root, and a build-authored path has no reason to carry one.</li>
 *   <li><b>The result must still be inside the root after normalisation.</b> {@link #resolve} resolves
 *       and normalises, then re-checks containment against the real root, so no combination of
 *       segments can escape even if a future rule above is relaxed.</li>
 *   <li><b>No symlink is ever followed out of the pack.</b> Neither the root itself nor any existing
 *       ancestor of the target may be a symbolic link or a non-directory. Without this a link planted
 *       inside the pack (or a {@code resources} folder that is one) would redirect a write to an
 *       arbitrary path on the machine. A DANGLING link is caught too -- {@link Files#exists} reports it
 *       as absent, so {@link Files#isSymbolicLink} is what the check is built on.</li>
 * </ul>
 *
 * <h2>What may already be at the destination</h2>
 * {@link #entryAt} classifies the target <em>without following a final link</em>. Callers use it to
 * keep two different outcomes apart: an existing regular file is the ordinary "already there" case
 * (skipped when only missing files are completed), while a symbolic link -- dangling or not -- or a
 * directory where a file belongs is a contradiction that must be reported as <em>failed</em>. Counting
 * those as "already present, skipped" would silently drop a bundled file on every boot and let the
 * plugin believe the pack is complete.
 *
 * <p>Pure {@code java.nio}, so all of it unit-tests without a server.
 */
public final class ResourcePaths {

    /** What already exists at a path. Deliberately nofollow: a link is never reported as its target. */
    public enum Entry {
        /** Nothing at all -- not even a dangling link. The only state a write may create from. */
        ABSENT,
        /** An ordinary file. The one state that counts as "already present". */
        REGULAR_FILE,
        /** A directory. Never a file, so a bundled file cannot be written over it. */
        DIRECTORY,
        /** A symbolic link, including one pointing nowhere. */
        SYMLINK,
        /** Something else -- a fifo, socket or device. Never written through. */
        OTHER
    }

    private ResourcePaths() {
    }

    /**
     * Refuses a path that cannot name a file inside a pack, and returns it unchanged.
     *
     * @param path a '/'-separated path relative to the pack root, e.g.
     *             {@code configuration/blocks/basic.yml}
     * @return the same path, so the call can be used inline
     * @throws IllegalArgumentException if the path is empty, absolute, contains {@code :} or
     *                                  {@code \}, has an empty segment, or contains {@code ..}; the
     *                                  message names the path and the rule it broke
     */
    public static String requireRelative(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("资源路径不得为空");
        }
        if (path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("资源路径必须用 '/' 分隔，不得包含反斜杠：" + path);
        }
        if (path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("资源路径不得包含冒号（盘符或 NTFS 数据流）：" + path);
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("资源路径必须是包内相对路径，不得是绝对路径：" + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("资源路径不得包含空段（'//' 或结尾 '/'）：" + path);
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("资源路径不得包含 '..'（会越出资源包根目录）：" + path);
            }
        }
        return path;
    }

    /**
     * Turns a pack-relative path into the absolute target to write, refusing anything that is not
     * provably inside {@code root} and free of links.
     *
     * @param root         the pack directory the path is relative to
     * @param relativePath the '/'-separated relative path, as produced by
     *                     {@link PackInstaller.Source#paths()} or a manifest entry
     * @return the absolute, normalised target path (its leaf may or may not exist yet)
     * @throws IOException if the path is unsafe ({@link #requireRelative}), if it resolves outside
     *                     {@code root}, or if {@code root} or any existing ancestor of the target is a
     *                     symbolic link or a non-directory. A caller counting per-file outcomes turns
     *                     this into a {@code failed}, never into a silent skip.
     */
    public static Path resolve(Path root, String relativePath) throws IOException {
        String relative;
        try {
            relative = requireRelative(relativePath);
        } catch (IllegalArgumentException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (resolved.equals(base) || !resolved.startsWith(base)) {
            throw new IOException("资源路径 '" + relativePath + "' 解析后越出资源包根目录 " + base
                    + "（得到 " + resolved + "），拒绝写入");
        }
        requireDirectoryChain(base, resolved.getParent());
        return resolved;
    }

    /**
     * Classifies what is at {@code path} without following a final symbolic link. A link whose target
     * is missing is {@link Entry#SYMLINK}, not {@link Entry#ABSENT}: a write must never create a file
     * by following a link the owner left behind.
     */
    public static Entry entryAt(Path path) {
        if (Files.isSymbolicLink(path)) {
            return Entry.SYMLINK;
        }
        if (!Files.exists(path)) {
            return Entry.ABSENT;
        }
        if (Files.isRegularFile(path)) {
            return Entry.REGULAR_FILE;
        }
        if (Files.isDirectory(path)) {
            return Entry.DIRECTORY;
        }
        return Entry.OTHER;
    }

    /** A short Chinese description of an entry state, for a log line that has to be actionable. */
    public static String describe(Entry entry) {
        return switch (entry) {
            case ABSENT -> "不存在";
            case REGULAR_FILE -> "是一个普通文件";
            case DIRECTORY -> "是一个目录";
            case SYMLINK -> "是符号链接";
            case OTHER -> "既不是普通文件也不是目录";
        };
    }

    /**
     * Rejects a root that is itself a link, and any existing ancestor between it and {@link
     * Path#getParent() the first parent of the target}. Components that do not exist yet are fine --
     * they will be created as real directories, which is exactly what we want.
     */
    private static void requireDirectoryChain(Path base, Path deepest) throws IOException {
        requireAncestors(base);
        if (deepest == null || deepest.equals(base)) {
            return;
        }
        Path walk = base;
        for (Path segment : base.relativize(deepest)) {
            walk = walk.resolve(segment);
            requireRealDirectory(walk, "目标路径的上级");
        }
    }

    /** Rejects links above the declared root too, before callers create or read anything below it. */
    public static void requireAncestors(Path directory) throws IOException {
        for (Path at = directory == null ? null : directory.toAbsolutePath().normalize();
             at != null; at = at.getParent()) {
            requireRealDirectory(at, "目标路径的目录层级");
        }
    }

    private static void requireRealDirectory(Path path, String what) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(what + " " + path + " 是符号链接；拒绝写入，绝不顺着链接写到资源包之外");
        }
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new IOException(what + " " + path + " 存在但不是目录；拒绝写入");
        }
    }
}
