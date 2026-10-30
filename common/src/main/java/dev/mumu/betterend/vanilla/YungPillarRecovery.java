package dev.mumu.betterend.vanilla;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「已有世界」的柱子恢复**纯逻辑**：给定足迹里读到的方块状态，判断这根柱现在是我们的（新布局）、
 * 旧布局（0.1.26 及以前）、还是原版的，并给出可执行性。
 *
 * <p>这一层不碰 Bukkit，所以能跑 JUnit；真正的读写、调度、备份在 {@code YungPillarLifecycle} 里。
 *
 * <h2>证据规则（不做采样、不靠材质猜所有权）</h2>
 * 每根柱按**完整足迹**比对，而不是抽样几个方块：
 * <ul>
 *   <li><b>新模板格</b>：{@link YungPillars} 蓝图的全部格（含座位与占位块解析结果）。</li>
 *   <li><b>旧模板格</b>：{@link YungPillars#legacyBlueprint}（锚点 {@code height+1-offset}）。</li>
 *   <li>旧/新两套锚点从不相等于同一个格，所以「一格属于哪套布局」是确定性的。</li>
 * </ul>
 * 判定：
 * <ol>
 *   <li>{@link Status#UNKNOWN_PLAYER}：足迹里有既不是目标、也不是我们/原版会写的方块 → 玩家改了，
 *       <b>一律不覆盖</b>；</li>
 *   <li>{@link Status#OURS}：新模板的所有非空气格全部命中；</li>
 *   <li>{@link Status#PARTIAL}：命中 ≥ 3/4（复活把柱体挖掉一部分但仍能认出是我们的）；</li>
 *   <li>{@link Status#LEGACY}：新模板命中不够，但**旧模板命中 ≥ 3/4**（已生成的 0.1.26 柱）
 *       —— 可以被显式 restore 迁移到 70/3；</li>
 *   <li>{@link Status#VANILLA}：新模板几乎没命中、旧模板也几乎没命中，而且原版柱芯（黑曜石圆柱）存在；</li>
 *   <li>{@link Status#NO_EVIDENCE}：以上都不成立（区块没长全、被炸成空气、或玩家彻底改建）。</li>
 * </ol>
 *
 * <p>{@link Status#VANILLA}/{@link Status#NO_EVIDENCE} **不参与** adopt：前者该走
 * {@code EndLayoutUpgrade} 的重新放置（有备份 + 显式 token），后者必须由人先看清楚。
 */
public final class YungPillarRecovery {

    /** 一根柱的现状分类。 */
    public enum Status {
        /** 新布局模板完整（所有非空气格命中）。 */
        OURS,
        /** 能认出是我们的，但有一部分格不在了（复活/爆炸留下缺口）。 */
        PARTIAL,
        /**
         * 旧布局柱（0.1.26 及以前：锚点 {@code height+1-offset}），足迹完整可认。
         *
         * <p>它可以被显式 restore 迁移到 70/3：按迁移蓝图（新模板 + 旧模板残件按地形
         * 还原）重铺，并提交新登记表。
         */
        LEGACY,
        /** 认不出新布局，原版柱芯在：这根柱就是原版的。 */
        VANILLA,
        /** 没有足够证据（区块不完整、被清空或玩家彻底改建）。 */
        NO_EVIDENCE,
        /** 足迹里有玩家放的方块：不许覆盖。 */
        UNKNOWN_PLAYER
    }

    /**
     * 一根柱的检查结果。
     *
     * @param templateCells 新模板的非空气格总数
     * @param matched       其中状态完全符合新模板的格数
     * @param missing       这些格里读不到（未加载）的数量
     * @param mismatched    既不等于目标、也不属于玩家方块的格数
     * @param foreign       玩家方块格数
     * @param legacyMatched 符合旧布局模板的格数（用来区分「旧柱」与「原版柱」）
     */
    public record PillarReport(int index, int centerX, int centerZ, int height, int tier,
                               Status status, int templateCells, int matched, int missing,
                               int mismatched, int foreign, int legacyMatched, int seatY,
                               int crystalY) {

        /** 能不能被 adopt 接管（只有「确定是我们的」两种状态可以）。 */
        public boolean adoptable() {
            return status == Status.OURS || status == Status.PARTIAL || status == Status.LEGACY;
        }

        /** 是不是需要「旧→新」迁移的旧布局柱（恢复时要用迁移蓝图）。 */
        public boolean legacy() {
            return status == Status.LEGACY;
        }
    }

    /**
     * 一次 preview 的结果。
     *
     * @param token 由世界 + 布局版本 + 可接管柱集合算出的确认令牌；{@code restore} 必须原样传回
     * @param note  给命令层的一句话（世界不可管、读取失败等）；空串表示没有额外说明
     */
    public record Preview(UUID worldId, int layout, List<PillarReport> reports, String token,
                          String note) {

        public List<Integer> adoptable() {
            List<Integer> out = new ArrayList<>();
            for (PillarReport report : reports) {
                if (report.adoptable()) {
                    out.add(report.index());
                }
            }
            return List.copyOf(out);
        }

        public boolean anythingToAdopt() {
            return !adoptable().isEmpty();
        }
    }

    /** 一次 apply 的结果。 */
    public record Outcome(boolean ok, String reason, List<Integer> adopted, List<String> details) {
    }

    private YungPillarRecovery() {
    }

    /**
     * 按完整足迹分类一根柱。
     *
     * @param vanillaCells 原版自己会写的格（{@link YungPillars#vanillaCells}）：这些格两边相同，
     *                     不参与所有权判定
     * @param current 足迹里读到的状态（key 见 {@link YungPillars#pack}）；缺键表示没读到
     * @param now     新布局蓝图
     * @param legacy  旧布局蓝图
     */
    public static PillarReport classify(int index, EndSpikes.Spike spike,
                                        YungPillars.Blueprint now, YungPillars.Blueprint legacy,
                                        Set<Long> vanillaCells, Map<Long, String> current) {
        // 旧布局在同一格会写什么：旧世界里占着那些格的是我们自己的旧模板，
        // 不能当成「玩家方块」。
        Map<Long, String> legacyStates = new java.util.HashMap<>(legacy.cells().size() * 2);
        for (YungPillars.Cell cell : legacy.cells()) {
            legacyStates.put(cell.key(), cell.state());
        }
        int templateCells = 0;
        int matched = 0;
        int missing = 0;
        int mismatched = 0;
        int foreign = 0;
        for (YungPillars.Cell cell : now.cells()) {
            if (cell.origin() != YungPillars.Origin.TEMPLATE
                    || "minecraft:air".equals(cell.state())) {
                // 玩家方块只看模板足迹（含空气格）：落在足迹里的非受管方块一样要拦。
                String occupied = current.get(cell.key());
                if (occupied != null && !cell.state().equals(occupied)
                        && !YungPillars.acceptable(occupied, cell.state(), null)
                        && !occupied.equals(legacyStates.get(cell.key()))) {
                    foreign++;
                }
                continue;
            }
            if (vanillaCells.contains(cell.key())) {
                // 原版自己也会写这一格（柱芯黑曜石圈内、或旧座/火）：两边都长这样，不能当所有权证据。
                continue;
            }
            templateCells++;
            String actual = current.get(cell.key());
            if (actual == null) {
                missing++;
            } else if (actual.equals(cell.state())) {
                matched++;
            } else if (YungPillars.acceptable(actual, cell.state(), null)
                    || actual.equals(legacyStates.get(cell.key()))) {
                mismatched++;
            } else {
                foreign++;
            }
        }
        // 旧布局命中数：同样只数「原版写不出来的格」，否则旧柱与原版柱分不开。
        int legacyTemplate = 0;
        int legacyMatched = 0;
        for (YungPillars.Cell cell : legacy.cells()) {
            if (cell.origin() != YungPillars.Origin.TEMPLATE
                    || "minecraft:air".equals(cell.state()) || vanillaCells.contains(cell.key())) {
                continue;
            }
            legacyTemplate++;
            String actual = current.get(cell.key());
            if (actual != null && actual.equals(cell.state())) {
                legacyMatched++;
            }
        }
        // 原版柱芯：蓝图里「原版清理格」中目标是空气、现在是黑曜石的格子（圆柱与笼子）。
        int vanillaObsidian = 0;
        for (YungPillars.Cell cell : now.cells()) {
            if (cell.origin() != YungPillars.Origin.VANILLA || !"minecraft:air".equals(cell.state())) {
                continue;
            }
            String actual = current.get(cell.key());
            if (actual != null && "minecraft:obsidian".equals(YungPillars.blockId(actual))) {
                vanillaObsidian++;
            }
        }

        Status status;
        if (foreign > 0) {
            status = Status.UNKNOWN_PLAYER;
        } else if (templateCells > 0 && matched == templateCells) {
            status = Status.OURS;
        } else if (matched >= Math.max(1, templateCells * 3 / 4)) {
            status = Status.PARTIAL;
        } else if (legacyTemplate > 0 && legacyMatched >= Math.max(1, legacyTemplate * 3 / 4)) {
            // 旧布局足迹完整：这是「已生成的 0.1.26 柱」，可以被显式迁移。
            status = Status.LEGACY;
        } else if (vanillaObsidian >= 8 && legacyMatched < Math.max(1, legacyTemplate / 4)) {
            status = Status.VANILLA;
        } else {
            status = Status.NO_EVIDENCE;
        }
        return new PillarReport(index, spike.centerX(), spike.centerZ(), spike.height(),
                YungPillars.tier(spike.height()), status, templateCells, matched, missing,
                mismatched, foreign, legacyMatched, now.layout().seatY(), now.layout().crystalY());
    }

    /**
     * 确认令牌：世界 + 种子 + 布局版本 + **每根柱的检查结果** + 已 built 集合的确定性散列。
     *
     * <p>把内容折进来是必须的：只绑「下标集合」时，preview 与 restore 之间玩家改了柱体、只要分类结果
     * 仍是同一组下标，旧 token 依然通过——那等于没有确认。现在足迹或登记表一变，token 就变。
     */
    public static String token(UUID worldId, long seed, int layout, List<PillarReport> reports,
                              java.util.Set<Integer> built) {
        StringBuilder text = new StringBuilder(256);
        text.append(worldId).append('|').append(seed).append('|').append(layout).append('|');
        List<PillarReport> sorted = new ArrayList<>(reports);
        sorted.sort(java.util.Comparator.comparingInt(PillarReport::index));
        for (PillarReport report : sorted) {
            text.append(report.index()).append(':').append(report.status()).append(':')
                    .append(report.templateCells()).append(':').append(report.matched()).append(':')
                    .append(report.missing()).append(':').append(report.mismatched()).append(':')
                    .append(report.foreign()).append(':').append(report.legacyMatched())
                    .append(',');
        }
        text.append('|');
        List<Integer> marks = new ArrayList<>(built);
        marks.sort(Integer::compareTo);
        for (Integer mark : marks) {
            text.append(mark).append(',');
        }
        return UUID.nameUUIDFromBytes(text.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * 生产确认令牌还绑定逐格状态与完整登记表：交换两个缺口、只换 UUID 都必须使旧令牌失效。
     */
    public static String token(UUID worldId, long seed, YungPillarStore.Registry registry,
                               List<PillarReport> reports, Map<Long, String> states) {
        StringBuilder text = new StringBuilder(token(worldId, seed, registry.layout(), reports,
                registry.built()));
        for (Map.Entry<Integer, UUID> entry : new java.util.TreeMap<>(registry.crystals()).entrySet()) {
            text.append('|').append(entry.getKey()).append('=').append(entry.getValue());
        }
        for (Map.Entry<Long, String> entry : new java.util.TreeMap<>(states).entrySet()) {
            text.append('|').append(entry.getKey()).append('=').append(entry.getValue());
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * 恢复要覆盖的**并集足迹**：新模板 + 旧模板 + 原版 21³ 盒。
     *
     * <p>备份与「未知方块不覆盖」的判定都基于它——只备份新蓝图会漏掉旧柱露在外面的部分。
     */
    public static Set<Long> unionKeys(EndSpikes.Spike spike, long seed,
                                      YungPillars.Blueprint now, YungPillars.Blueprint legacy) {
        return unionKeys(spike, seed, now, legacy, 0);
    }

    /**
     * {@link #unionKeys(EndSpikes.Spike, long, YungPillars.Blueprint, YungPillars.Blueprint)} 的完整版：
     * 额外覆盖 {@code [worldMinY, 0)} 的原版柱芯（MU 从 {@code level.getMinY()} 起写黑曜石），
     * 让备份与「未知方块」判定不留盲区。
     */
    public static Set<Long> unionKeys(EndSpikes.Spike spike, long seed,
                                      YungPillars.Blueprint now, YungPillars.Blueprint legacy,
                                      int worldMinY) {
        Set<Long> keys = new HashSet<>(now.cells().size() + legacy.cells().size() + 9261 * 2);
        for (YungPillars.Cell cell : now.cells()) {
            keys.add(cell.key());
        }
        for (YungPillars.Cell cell : legacy.cells()) {
            keys.add(cell.key());
        }
        YungPillars.Bounds box = YungPillars.vanillaBox(spike);
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    keys.add(YungPillars.pack(x, y, z));
                }
            }
        }
        // 原版柱芯在 y<0 的那一段（从世界底层起），与 placeSpike 的写入范围对齐。
        int deepFloor = Math.min(worldMinY, 0);
        if (deepFloor < 0) {
            int radius = spike.radius();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius + 1) {
                        continue;
                    }
                    for (int yPos = deepFloor; yPos < 0; yPos++) {
                        keys.add(YungPillars.pack(spike.centerX() + dx, yPos, spike.centerZ() + dz));
                    }
                }
            }
        }
        return keys;
    }
}
