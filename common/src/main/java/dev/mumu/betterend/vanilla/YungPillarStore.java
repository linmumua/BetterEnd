package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.AtomicFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 柱体服务的两样持久化数据，都是**插件自己的文件**，不写世界 PDC。
 *
 * <p>为什么不写 PDC：世界 PDC 底层是 {@code SavedData} 的 {@code Map}，从区域线程写它在 Folia 上
 * 没有所有权保证，而柱晶登记表要跨重启保存。这里改成「每个世界一个文本文件 + 原子替换」
 * （{@link AtomicFiles#replace}），写盘走异步线程，读只在启动/首次用到时做一次。
 *
 * <h2>文件内容</h2>
 * <pre>
 * betterend-pillars 1          # 文件格式版本
 * layout 2                     # 柱布局版本：2 = upperBase70/lowerBase3
 * crystal 0 0f0e...            # 第 0 根柱的官方结构晶 UUID
 * built 0                      # 第 0 根柱已按本布局重铺过
 * </pre>
 * 快照是同一套「行首关键字」的 gzip 文本，逐格记录重铺前的状态：
 * <pre>
 * betterend-pillar-snapshot 1
 * world &lt;uuid&gt;
 * job &lt;uuid&gt;
 * cell &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;blockState&gt;
 * </pre>
 *
 * <p>纯 Java、无 Bukkit，所以能跑 JUnit：格式错了要能明确报错，而不是静默丢登记表。
 */
public final class YungPillarStore {

    /** 数据格式版本；读到更大的版本一律拒绝（不猜未来格式）。 */
    public static final int FORMAT = 1;
    /** 当前柱布局版本：{@code upperBaseY = 70}、{@code lowerBaseY = 3}。 */
    public static final int LAYOUT = 2;

    static final String REGISTRY_NAME = "pillars";
    static final String SNAPSHOT_DIR = "pillar-snapshots";
    private static final String REGISTRY_HEADER = "betterend-pillars";
    private static final String SNAPSHOT_HEADER = "betterend-pillar-snapshot";

    private YungPillarStore() {
    }

    /** 一个世界的柱体登记表：官方结构晶 UUID、以及哪些柱已按当前布局重铺过。 */
    public record Registry(int layout, Map<Integer, UUID> crystals, Set<Integer> built) {

        public static Registry empty() {
            return new Registry(LAYOUT, Map.of(), Set.of());
        }

        /** 第 {@code index} 根柱登记的官方结构晶。 */
        public UUID crystal(int index) {
            return crystals.get(index);
        }

        /** 登记（或替换）第 {@code index} 根柱的官方结构晶。 */
        public Registry withCrystal(int index, UUID uuid) {
            Map<Integer, UUID> next = new LinkedHashMap<>(crystals);
            next.put(index, uuid);
            return new Registry(layout, Map.copyOf(next), built);
        }

        /** 标记第 {@code index} 根柱已按本布局重铺完成（或已被显式接管）。 */
        public Registry withBuilt(int index) {
            Set<Integer> next = new LinkedHashSet<>(built);
            next.add(index);
            return new Registry(layout, crystals, Set.copyOf(next));
        }

        /**
         * 把登记表的布局版本升到 {@code layout}。
         *
         * <p>恢复/接管成功后必须调用：否则文件里还是 {@code layout=1}（或解析出的 0），
         * 世界会被永久判成旧布局，受控恢复只生效一次。
         */
        public Registry withLayout(int layout) {
            return this.layout == layout ? this : new Registry(layout, crystals, built);
        }

        /** 升级后只保留本版实际回读成功的柱，不能把旧布局的 built 一起冒认成新版。 */
        public Registry withCurrentBuilt(int index) {
            return (currentLayout() ? this : new Registry(LAYOUT, crystals, Set.of()))
                    .withBuilt(index);
        }

        /** 撤销 {@code built} 标记（写盘失败时回滚内存，绝不留下「假完成」）。 */
        public Registry withoutBuilt(int index) {
            if (!built.contains(index)) {
                return this;
            }
            Set<Integer> next = new LinkedHashSet<>(built);
            next.remove(index);
            return new Registry(layout, crystals, Set.copyOf(next));
        }

        /** 这份登记表是不是当前布局写下的（旧布局的登记表不能当成「已修复」的证据）。 */
        public boolean currentLayout() {
            return layout == LAYOUT;
        }
    }

    /** 重铺前的一整份原方块快照。 */
    public record Snapshot(UUID worldId, UUID jobId, List<YungPillars.Cell> cells) {
    }

    /** 一个世界的登记表文件路径。 */
    public static Path registryFile(Path dataDir, UUID worldId) {
        return dataDir.resolve(REGISTRY_NAME).resolve(worldId + ".txt");
    }

    /** 登记表文件的字节内容（原子替换用）。 */
    public static byte[] registryBytes(Registry registry) {
        StringBuilder out = new StringBuilder(256);
        out.append(REGISTRY_HEADER).append(' ').append(FORMAT).append('\n');
        out.append("layout ").append(registry.layout()).append('\n');
        for (Map.Entry<Integer, UUID> entry : registry.crystals().entrySet()) {
            out.append("crystal ").append(entry.getKey()).append(' ').append(entry.getValue())
                    .append('\n');
        }
        for (Integer index : registry.built()) {
            out.append("built ").append(index).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 写登记表（原子替换）。父目录不存在会先创建。 */
    public static Path writeRegistry(Path dataDir, UUID worldId, Registry registry) throws IOException {
        Path file = registryFile(dataDir, worldId);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        AtomicFiles.replace(file, registryBytes(registry));
        return file;
    }

    /**
     * 读登记表；文件不存在就是空表（首次运行），内容损坏一律抛 {@link IOException}。
     *
     * <p>损坏拒绝而不是「当作空表」：空表意味着丢掉「这根柱的晶体是我们的」这一证据，接着重铺就会
     * 把玩家放的晶体也搬走——宁可让运维看到明确错误。
     */
    public static Registry readRegistry(Path dataDir, UUID worldId) throws IOException {
        Path file = registryFile(dataDir, worldId);
        if (!Files.exists(file)) {
            return Registry.empty();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return parseRegistry(lines, file);
    }

    static Registry parseRegistry(List<String> lines, Path source) throws IOException {
        if (lines.isEmpty() || !lines.get(0).startsWith(REGISTRY_HEADER + " ")) {
            throw new IOException("不是柱体登记表：" + source);
        }
        int format = number(lines.get(0).substring(REGISTRY_HEADER.length() + 1), source);
        if (format > FORMAT) {
            throw new IOException("柱体登记表格式 " + format + " 比本插件支持的 " + FORMAT + " 更新：" + source);
        }
        int layout = 0;
        Map<Integer, UUID> crystals = new LinkedHashMap<>();
        Set<Integer> built = new LinkedHashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "layout" -> {
                    require(parts.length == 2, line, source);
                    layout = number(parts[1], source);
                }
                case "crystal" -> {
                    require(parts.length == 3, line, source);
                    crystals.put(number(parts[1], source), uuid(parts[2], source));
                }
                case "built" -> {
                    require(parts.length == 2, line, source);
                    built.add(number(parts[1], source));
                }
                default -> throw new IOException("未知的登记表行 '" + line + "'：" + source);
            }
        }
        return new Registry(layout, Map.copyOf(crystals), Set.copyOf(built));
    }

    /** 快照文件路径；每次重铺一个新的 job id，所以旧快照永远不会被覆盖。 */
    public static Path snapshotFile(Path dataDir, UUID worldId, UUID jobId) {
        return dataDir.resolve(SNAPSHOT_DIR).resolve(worldId + "-" + jobId + ".snapshot.gz");
    }

    /** 把快照写成 gzip 文本（原子替换）。 */
    public static Path writeSnapshot(Path dataDir, UUID worldId, UUID jobId,
                                     List<YungPillars.Cell> cells) throws IOException {
        Path file = snapshotFile(dataDir, worldId, jobId);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            StringBuilder header = new StringBuilder(128);
            header.append(SNAPSHOT_HEADER).append(' ').append(FORMAT).append('\n');
            header.append("world ").append(worldId).append('\n');
            header.append("job ").append(jobId).append('\n');
            gzip.write(header.toString().getBytes(StandardCharsets.UTF_8));
            for (YungPillars.Cell cell : cells) {
                String line = "cell " + cell.x() + " " + cell.y() + " " + cell.z() + " "
                        + cell.state() + "\n";
                gzip.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        AtomicFiles.replace(file, buffer.toByteArray());
        return file;
    }

    /** 读回一份快照。 */
    public static Snapshot readSnapshot(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            String text = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            return parseSnapshot(text.split("\n", -1), file);
        }
    }

    static Snapshot parseSnapshot(String[] lines, Path source) throws IOException {
        if (lines.length == 0 || !lines[0].startsWith(SNAPSHOT_HEADER + " ")) {
            throw new IOException("不是柱体快照：" + source);
        }
        int format = number(lines[0].substring(SNAPSHOT_HEADER.length() + 1), source);
        if (format > FORMAT) {
            throw new IOException("柱体快照格式 " + format + " 比本插件支持的 " + FORMAT + " 更新：" + source);
        }
        UUID worldId = null;
        UUID jobId = null;
        List<YungPillars.Cell> cells = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "world" -> {
                    require(parts.length == 2, line, source);
                    worldId = uuid(parts[1], source);
                }
                case "job" -> {
                    require(parts.length == 2, line, source);
                    jobId = uuid(parts[1], source);
                }
                case "cell" -> {
                    require(parts.length == 5, line, source);
                    cells.add(new YungPillars.Cell(number(parts[1], source), number(parts[2], source),
                            number(parts[3], source), parts[4], YungPillars.Origin.TEMPLATE));
                }
                default -> throw new IOException("未知的快照行 '" + line + "'：" + source);
            }
        }
        if (worldId == null || jobId == null) {
            throw new IOException("快照缺少 world/job 头：" + source);
        }
        return new Snapshot(worldId, jobId, List.copyOf(cells));
    }

    private static void require(boolean condition, String line, Path source) throws IOException {
        if (!condition) {
            throw new IOException("登记表/快照行列数不对 '" + line + "'：" + source);
        }
    }

    private static int number(String text, Path source) throws IOException {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException error) {
            throw new IOException("'" + text + "' 不是整数：" + source, error);
        }
    }

    private static UUID uuid(String text, Path source) throws IOException {
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("'" + text + "' 不是 UUID：" + source, error);
        }
    }
}
