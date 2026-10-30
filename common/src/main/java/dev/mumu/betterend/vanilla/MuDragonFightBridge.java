package dev.mumu.betterend.vanilla;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEnderCrystal;
import org.bukkit.entity.EnderCrystal;
import org.jetbrains.annotations.Nullable;

/**
 * MU核心 26.3 原生末地龙战桥接：只做一件事——把<b>返回门锚点</b>（{@code EnderDragonFight#exitPortalLocation}）
 * 钉到本插件算出来的坐标，然后让原生 {@code EndPodiumFeature} 自己去写基岩圈/基岩柱/返回门/火把。
 *
 * <h2>为什么需要桥接</h2>
 * 中心祭坛的布局（{@link EndPodiumTemplate}）先算地形顶块 {@code S}，再定时钟楼与门环的 {@code gateY}；
 * 而原生门是「按高度图放」的，塔的钟柱又把高度图抬高二十几格。要让「门跟着建筑走」而不是反过来，
 * 就必须在生成前改写记录坐标，让原版核心落在 {@code gateY} 那一格。Bukkit 的
 * {@code DragonBattle} 没有写入口，所以只有这一处必须直接触碰 NMS。
 *
 * <h2>边界（本类绝不越界）</h2>
 * <ul>
 *   <li><b>对外只用 Bukkit 类型</b>：{@link Location} / {@link World} / {@link Material} / {@link Status}。
 *       NMS 类型只出现在方法体内，签名里一格都不露，调用方（{@code EndPodiumShell}）完全不认识 NMS；</li>
 *   <li><b>不反射</b>：直接编译期链接真实符号。符号或版本不对不会在写到一半时炸，而是由
 *       {@link #available()} 在动手前就判定不可用（{@code DISABLED_VERSION} / {@code DISABLED_LINKAGE}），
 *       并且一旦链接失败就<b>整体永久关停</b>，不会「这次失败下次再试」；</li>
 *   <li><b>不改龙</b>：不动 {@code dragonUUID}、{@code dragonKilled}、{@code hasPreviouslyKilledDragon}、
 *       {@code respawnStage}、{@code respawnCrystals}，也不清任何旧方块。旧门要清是调用方显式升级流程
 *       （{@code EndPodiumShell#reanchor}）的事，它自己先快照再清；</li>
 *   <li><b>写前全预检</b>：真实写入集（{@code EndPodiumFeature#place} 覆盖的范围）逐列核验「区块已加载 +
 *       当前区域拥有」。任一列不满足就带着 {@code CHUNK_NOT_LOADED} / {@code CHUNK_NOT_OWNED} 返回，
 *       <b>一格都不写</b>——这两个状态是调用方唯一允许有限重试的；</li>
 *   <li><b>写后回读</b>：字段必须等于目标；门平面按 {@link #kindAt} 分三类核对——<b>20 个门格</b>必须是
 *       {@code END_PORTAL}（{@code active}）或 {@code AIR}（{@code inactive}）、<b>16 个基岩圈格</b>必须是
 *       {@code BEDROCK}、<b>中心基岩柱</b>（门平面那一格 + 上方三格）必须是 {@code BEDROCK}。
 *       任何一格不符就是 {@code WRITE_MISMATCH}。原生写入不是一个事务，所以这里如实报告，不假装成功。</li>
 * </ul>
 *
 * <h2>{@code active} 只影响 20 个门格</h2>
 * {@code spawnExitPortal(true/false)} 共用同一份 {@code EndPodiumFeature}，区别仅在门心那一圈：
 * {@code active} 写 {@code END_PORTAL}，否则写 {@code AIR}。基岩圈与中心基岩柱两者完全一致。
 *
 * <p>中心柱是这里最容易写错的一格：原生在主循环<b>之后</b>无条件执行 {@code origin.above(0..3) → BEDROCK}，
 * {@code (0,0)} 因此永远不是门格。把它算进门格会让每一次真实新世界锚定都以 {@code WRITE_MISMATCH} 结束——
 * 这就是 {@link #kindAt} 被抽成纯函数、并由单测按原生写入顺序（先写门、再覆盖柱）钉住的原因。
 *
 * <h2>为什么要额外核验竞技场原点在 (0, ·, 0)</h2>
 * 原生 {@code findExitPortal} / {@code spawnExitPortal} 都围绕 {@code fight.origin} 计算落点。原点不在
 * 原点列时，我们写进去的坐标会被原生逻辑按偏移理解，等于静默改坐标，所以直接 {@code TARGET_REJECTED}。
 *
 * <h2>线程</h2>
 * {@link #anchor} 与 {@link #readFrame} 必须在<b>目标区块所属区域的线程</b>上调用（Folia 语义）：
 * 它们会读方块、调原生生成。{@link #read} / {@link #available()} 只读一个字段，任何线程都安全，
 * 也绝不抛异常——{@code survey} 在区域线程外就靠它。
 */
public final class MuDragonFightBridge {

    /** 只按这个版本编译链接（MU核心 26.3）。版本不符一律 {@link Status#DISABLED_VERSION}。 */
    private static final String SUPPORTED_MINECRAFT_VERSION = "26.3";

    /**
     * {@code EndPodiumFeature#place} 的真实写入半径：三维距离 {@code < 3.5}，故每轴最多 ±3。
     * 它的循环壳子是 {@code origin ±4 × y-1..y+32}，但那些格子全被距离判定滤掉，不能拿壳子做预检。
     */
    private static final int PODIUM_REACH = 3;

    /** 门心判定：{@code dy == 0} 时 {@code insideRim == closerThan(origin, 2.5)}，即 {@code dx²+dz² ≤ 6}。 */
    private static final int GATE_INNER_SQR = 6;

    /** 基岩圈外沿：{@code dy == 0} 时 {@code 6 < dx²+dz² ≤ 12}，共 16 格，与 active 无关。 */
    private static final int RIM_OUTER_SQR = 12;

    /**
     * 中心基岩柱：原生在主循环<b>之后</b>无条件执行 {@code origin.above(0..3) → BEDROCK}。
     * 其中 {@code y == origin.y} 那一格正好落在门平面上，所以它<b>不是</b>门格（不可能是 AIR/END_PORTAL）。
     * 漏掉这一点会让每一次真实新世界锚定都以 {@code WRITE_MISMATCH} 结束。
     */
    private static final int PILLAR_HEIGHT = 4;

    /** 门面模板的边长半径：{@code exitPortalPattern} 是 7×7，{@code getBlock(3,3,3)} 是门平面中心。 */
    private static final int PATTERN_REACH = 3;

    /**
     * {@code findExitPortal()} 第一阶段会遍历 {@code ChunkPos.containing(origin) ± 8}，即 17×17 个区块，
     * 并对每个区块调 {@code level.getChunk(x, z)}——那是<b>同步加载</b>。跨区域从区域线程里调它等于
     * 把别的 region 拽住，所以这 289 个区块必须全部「已加载 + 归当前区域」才允许调。
     */
    private static final int SCAN_CHUNK_RADIUS = 8;

    private static final Object PROBE_LOCK = new Object();

    /** 探测结果缓存；一旦是 DISABLED_* 就不再重试（「整体关」）。 */
    private static volatile Probe probe;

    private MuDragonFightBridge() {
    }

    // ------------------------------------------------------------------ 对外 API

    /** 状态码。除 {@code OK} 外全部是「这一次没成」，其中只有 {@code CHUNK_*} 允许调用方有限重试。 */
    public enum Status {
        /** 成功。 */
        OK,
        /** 服务端版本不是 MU核心 26.3，本类不适用于该服务端。 */
        DISABLED_VERSION,
        /** 真实 NMS 符号链接失败（类缺失/签名漂移/服务端 jar 不同），已整体永久关停。 */
        DISABLED_LINKAGE,
        /** 该世界没有 {@code EnderDragonFight}（不是末地，或维度未初始化）。 */
        NO_DRAGON_FIGHT,
        /** 记录值与调用方期望值不一致（CAS 失败），或门实物与记录不一致。 */
        EXPECTED_MISMATCH,
        /** 目标坐标/竞技场原点不符合前置条件，拒绝写。 */
        TARGET_REJECTED,
        /** 写区或扫描窗有区块没加载：调用方可以稍后重试。 */
        CHUNK_NOT_LOADED,
        /** 写区或扫描窗有区块不归当前区域：调用方可以搬到对应区域后重试。 */
        CHUNK_NOT_OWNED,
        /** 写完了但回读不符（原生写入不是原子的），如实报告，不假装成功。 */
        WRITE_MISMATCH,
        /** 核不到门实物（记录为空，或附近没有匹配的末地门图案）。 */
        FRAME_MISSING
    }

    /**
     * 一次锚定的结果。
     *
     * @param status 状态码，{@code OK} 以外的任何值都表示没有达到目标
     * @param detail 中文原因，直接进日志；{@code OK} 时是成功摘要
     * @param before 调用前的记录值；从没有记录过就是 {@code null}
     * @param after  写入并生成之后重读的字段值；失败时可能是 {@code null}（没走到写）
     */
    public record Outcome(Status status, String detail, @Nullable Location before, @Nullable Location after) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    /**
     * 世界里的门实物（{@code findExitPortal()} 找到的那一份）。
     *
     * @param status           {@code OK} 表示找到了；{@code EXPECTED_MISMATCH} 表示找到了但与记录不同格
     * @param detail           中文说明
     * @param center           门平面中心 = {@code match.getBlock(3,3,3).getPos()}，与原版记录同一约定
     * @param frontTopLeft     图案左上角 = {@code match.getFrontTopLeft()}，只用于日志与排错
     * @param width            图案宽（一般 7）
     * @param height           图案高
     * @param depth            图案深（一般 7）
     * @param matchesRecorded  {@code center} 与 {@link #read(World)} 逐格相等时为 {@code true}
     */
    public record Frame(
            Status status,
            String detail,
            @Nullable Location center,
            @Nullable Location frontTopLeft,
            int width,
            int height,
            int depth,
            boolean matchesRecorded) {
    }

    /** 本桥接是否可用。永不抛异常：版本不符、链接失败、服务端未就绪都只是 {@code false}。 */
    public static boolean available() {
        return probe().status() == Status.OK;
    }

    /** 不可用的原因（可用时返回一句说明）。给日志用，永不抛异常。 */
    public static String unavailableReason() {
        Probe current = probe();
        return current.status() == Status.OK ? current.detail() : current.status() + "：" + current.detail();
    }

    /**
     * 读世界记录里的返回门坐标。
     *
     * <p>只读一个字段，任何线程安全。<b>永不抛异常</b>：不可用、没有龙战、从没记录过都返回 {@code null}，
     * 调用方靠 {@code null} 判断「新世界」。这里不会触发 {@code findExitPortal()}，所以不会顺手写字段。
     */
    public static @Nullable Location read(World world) {
        if (world == null || probe().status() != Status.OK) {
            return null;
        }
        try {
            ServerLevel level = handleOf(world);
            if (level == null) {
                return null;
            }
            EnderDragonFight fight = level.getDragonFight();
            return fight == null ? null : toLocation(world, fight.exitPortalLocation);
        } catch (LinkageError linkage) {
            disable(linkage);
            return null;
        } catch (RuntimeException notReady) {
            return null; // 世界正在卸载/未就绪：当作「没有记录」，绝不把异常抛给施工线程
        }
    }

    /**
     * 核验世界里真实存在的门（{@code findExitPortal()} 找到的那一份）。
     *
     * <p>调用前必须已经确认 {@link #read(World)} 非空：{@code findExitPortal()} 在字段为空时会顺手把找到的
     * 坐标写进去（原版行为），那会绕过我们的 CAS。
     *
     * <p>两道预检都是为了「不把别的区域拽住」：先要 17×17 区块扫描窗全部已加载且归当前区域
     * （{@code findExitPortal()} 会同步加载它们），再要 7×7 门面本身归当前区域。
     * 只调 {@code isChunkLoaded} / {@code isOwnedByCurrentRegion}，不读方块。
     */
    public static Frame readFrame(World world) {
        Probe gate = probe();
        if (gate.status() != Status.OK) {
            return failedFrame(gate.status(), gate.detail());
        }
        if (world == null) {
            return failedFrame(Status.NO_DRAGON_FIGHT, "世界为 null");
        }
        try {
            ServerLevel level = handleOf(world);
            if (level == null) {
                return failedFrame(Status.NO_DRAGON_FIGHT, "世界不是 CraftWorld，取不到原生维度");
            }
            EnderDragonFight fight = level.getDragonFight();
            if (fight == null) {
                return failedFrame(Status.NO_DRAGON_FIGHT, "该维度没有 EnderDragonFight（不是末地）");
            }
            Location recorded = toLocation(world, fight.exitPortalLocation);
            if (recorded == null) {
                return failedFrame(Status.FRAME_MISSING,
                        "世界还没有记录返回门坐标，无法定位门平面（此时调 findExitPortal 会顺手写字段）");
            }
            int originX = fight.origin == null ? 0 : fight.origin.getX();
            int originZ = fight.origin == null ? 0 : fight.origin.getZ();
            Check scan = precheckScanWindow(world, originX, originZ);
            if (scan.status() != Status.OK) {
                return failedFrame(scan.status(), scan.detail());
            }
            Check face = precheckColumns(world, recorded.getBlockX(), recorded.getBlockZ());
            if (face.status() != Status.OK) {
                return failedFrame(face.status(), face.detail());
            }
            BlockPattern.BlockPatternMatch match = fight.findExitPortal();
            if (match == null) {
                return failedFrame(Status.FRAME_MISSING,
                        "扫描窗里核不到末地返回门图案（记录 " + describe(recorded) + " 附近没有完整实物）");
            }
            Location center = toLocation(world, match.getBlock(3, 3, 3).getPos());
            Location frontTopLeft = toLocation(world, match.getFrontTopLeft());
            boolean matches = sameBlock(center, recorded);
            return new Frame(
                    matches ? Status.OK : Status.EXPECTED_MISMATCH,
                    matches
                            ? "门实物在记录位置：中心 " + describe(center) + "，图案 " + match.getWidth() + "×"
                                    + match.getHeight() + "×" + match.getDepth()
                            : "门实物中心 " + describe(center) + " 与记录 " + describe(recorded) + " 不是同一格",
                    center,
                    frontTopLeft,
                    match.getWidth(),
                    match.getHeight(),
                    match.getDepth(),
                    matches);
        } catch (LinkageError linkage) {
            disable(linkage);
            return failedFrame(Status.DISABLED_LINKAGE, describe(linkage));
        } catch (RuntimeException error) {
            // 跨区域同步加载在 Folia 上会直接抛（TickThread 断言）。如实报告成「核不到」，绝不半途改世界。
            return failedFrame(Status.FRAME_MISSING, "查找门实物时抛异常：" + describe(error));
        }
    }

    /**
     * 这颗晶是不是<b>原版柱放置</b>（{@code EndSpikeFeature#placeSpike}）写下的结构晶。
     *
     * <p>判据是原生标记 {@code EndCrystal#generatedByDragonFight}，不猜材料、不猜位置：全 jar 里只有
     * {@code EndSpikeFeature} 把它写成 {@code true}，{@code EndCrystalItem}（玩家放置）与
     * {@code EnderDragonFight}（复活用晶）都不写，所以 {@code true} ⟺ 原版柱结构晶。
     *
     * <p><b>只在实体 owner 线程上调用</b>：读的是 NMS 字段。不可用/版本不符/类型链接失败一律返回
     * {@code false}（fail-closed，一颗晶都不当成柱晶），绝不抛异常。
     */
    public static boolean isPillarCrystal(EnderCrystal crystal) {
        if (crystal == null || probe().status() != Status.OK) {
            return false;
        }
        try {
            return crystal instanceof CraftEnderCrystal craft && craft.getHandle().generatedByDragonFight;
        } catch (LinkageError linkage) {
            disable(linkage);
            return false;
        } catch (RuntimeException notReady) {
            return false;
        }
    }


    /**
     * 运行期查询「一个 region section 覆盖多少个区块边」（MU 26.3 是 16，不是 8）。
     *
     * <p>读的是 {@code ServerLevel#regioniser.regionSectionChunkSize}（{@code public final int}），
     * 不写死、不反射。集成层（复活/柱重建的区块调度）必须按这个值算 section 边界，写死任何一个常数
     * 都会在别的目标版本上算错分区。
     *
     * <p><b>返回 -1 表示不可启用</b>：桥接不可用（版本不符/链接失败）、世界不是 CraftWorld、取不到值、
     * 或取值非法（≤0）。调用方遇到 -1 必须按「无法确定分区」处理（安全拒绝或退回显式配置），
     * <b>绝不返回猜测值</b>，也绝不抛异常。
     */
    public static int regionSectionChunkSize(World world) {
        if (world == null || probe().status() != Status.OK) {
            return -1;
        }
        try {
            ServerLevel level = handleOf(world);
            if (level == null) {
                return -1;
            }
            int size = level.regioniser.regionSectionChunkSize;
            return size > 0 ? size : -1;
        } catch (LinkageError linkage) {
            disable(linkage);
            return -1;
        } catch (RuntimeException notReady) {
            return -1;
        }
    }


    /**
     * 把返回门锚点钉到 {@code target}，并让原生核心在那一格生成。
     *
     * <p>顺序固定：可用性 → 龙战对象 → 竞技场原点 → <b>CAS</b>（{@code expectedCurrent} 必须等于当前记录）
     * → 写区逐列预检 → 写字段 + {@code setDirty} + {@code spawnExitPortal(active)} → 回读。
     * 任何一步不成立都在那一步返回，<b>不写一格</b>。
     *
     * <p>{@code expectedCurrent == null} 的含义是「世界必须还没有记录」（新世界首建）；
     * 记录已经有了就 {@code EXPECTED_MISMATCH}，绝不覆盖别人的门。
     *
     * <p>{@code inactive} 与 {@code active} 的区别<b>只有</b> 20 个门格：{@code AIR} 对 {@code END_PORTAL}；
     * 基岩柱与基岩圈两者都写，所以首杀前的核心也是完整的基岩台。
     *
     * <p>必须在该区块所属区域的线程上调（会读方块、会调原生生成）。
     */
    public static Outcome anchor(World world, @Nullable Location expectedCurrent, Location target, boolean active) {
        Probe gate = probe();
        Location recorded = null;
        if (gate.status() != Status.OK) {
            return new Outcome(gate.status(), gate.detail(), null, null);
        }
        if (world == null) {
            return new Outcome(Status.NO_DRAGON_FIGHT, "世界为 null", null, null);
        }
        if (target == null) {
            return new Outcome(Status.TARGET_REJECTED, "目标坐标为 null", null, null);
        }
        if (target.getWorld() != null && !world.equals(target.getWorld())) {
            return new Outcome(Status.TARGET_REJECTED, "目标坐标不属于该世界", null, null);
        }
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int targetY = target.getBlockY();
        String badTarget = coordinateProblem(target.getBlockX(), targetY, target.getBlockZ(), minY, maxY);
        if (badTarget != null) {
            return new Outcome(Status.TARGET_REJECTED, badTarget, null, null);
        }
        try {
            ServerLevel level = handleOf(world);
            if (level == null) {
                return new Outcome(Status.NO_DRAGON_FIGHT, "世界不是 CraftWorld，取不到原生维度", null, null);
            }
            EnderDragonFight fight = level.getDragonFight();
            if (fight == null) {
                return new Outcome(Status.NO_DRAGON_FIGHT, "该维度没有 EnderDragonFight（不是末地）", null, null);
            }
            int originX = fight.origin == null ? 0 : fight.origin.getX();
            int originY = fight.origin == null ? 0 : fight.origin.getY();
            int originZ = fight.origin == null ? 0 : fight.origin.getZ();
            if (originX != 0 || originZ != 0) {
                return new Outcome(Status.TARGET_REJECTED,
                        "竞技场原点在 (" + originX + ", " + originY + ", " + originZ + ")，原生 findExitPortal/"
                                + "spawnExitPortal 都按该原点换算坐标，只支持 (0, ·, 0)", null, null);
            }
            recorded = toLocation(world, fight.exitPortalLocation);

            // CAS：期望值必须与记录逐格相等才写。null 表示「必须还没有记录」。
            if (expectedCurrent == null) {
                if (recorded != null) {
                    return new Outcome(Status.EXPECTED_MISMATCH,
                            "调用方按「还没有记录」处理，世界记录却是 " + describe(recorded), recorded, null);
                }
            } else if (!sameBlock(recorded, expectedCurrent)) {
                return new Outcome(Status.EXPECTED_MISMATCH,
                        "记录中的返回门是 " + describe(recorded) + "，与期望的 " + describe(expectedCurrent)
                                + " 不是同一格", recorded, null);
            }

            // 写区（EndPodiumFeature#place 的真实写入集，7×7 列 × y±3）逐列核验 loaded / owned。
            Check area = precheckColumns(world, target.getBlockX(), target.getBlockZ());
            if (area.status() != Status.OK) {
                return new Outcome(area.status(), area.detail() + "（门心 " + describe(target) + "，一格未写）",
                        recorded, null);
            }

            // 写：只碰 exitPortalLocation / dirty 标记，然后走<b>已批准的原生路径</b>
            // (spawnExitPortal → EndPodiumFeature.place)。方块写入与回读都在上面那 7×7 全 owner
            // 预检通过的范围内；跨区块合并由 Folia regioniser 负责，任务重试由调用方负责。
            // 龙 UUID、击杀记录、复活阶段一概不碰。
            fight.exitPortalLocation = new BlockPos(target.getBlockX(), targetY, target.getBlockZ());
            fight.setDirty();
            fight.spawnExitPortal(active);

            Location after = toLocation(world, fight.exitPortalLocation);
            if (!sameBlock(after, target)) {
                return new Outcome(Status.WRITE_MISMATCH,
                        "写入后字段读回 " + describe(after) + "，不是目标 " + describe(target)
                                + "（原生写入不是原子的，已发生的改动留在原处）", recorded, after);
            }
            int baseX = target.getBlockX();
            int baseY = targetY;
            int baseZ = target.getBlockZ();
            FrameCheck frame = verifyGatePlane(active,
                    (dx, dy, dz) -> world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz).getType());
            if (!frame.ok()) {
                return new Outcome(Status.WRITE_MISMATCH,
                        "门平面成品不符（active=" + active + "）：" + String.join("，", frame.problems()), recorded, after);
            }
            return new Outcome(Status.OK,
                    "门锚点 " + describe(recorded) + " → " + describe(after) + "：门格 " + frame.gateCells() + " 格为 "
                            + gateMaterial(active) + "、基岩圈 " + frame.rimCells() + " 格、中心柱 "
                            + frame.pillarCells() + " 格（active=" + active + "）；"
                            + "龙 UUID / 击杀记录 / 复活阶段未改动",
                    recorded, after);
        } catch (LinkageError linkage) {
            disable(linkage);
            return new Outcome(Status.DISABLED_LINKAGE, describe(linkage), recorded, null);
        } catch (RuntimeException error) {
            return new Outcome(Status.WRITE_MISMATCH,
                    "写入过程中抛异常：" + describe(error) + "（原生写入不是原子的，已发生的改动留在原处）",
                    recorded, null);
        }
    }

    // ------------------------------------------------------------------ 门平面成品判定（纯逻辑，不建世界）

    /** 门平面（{@code y == 门心 y}）上每一格的期望种类；只按偏移量判定，不读世界。 */
    enum Kind {
        /** 7×7 的四角：原生距离判定把它们滤掉了，门平面这一格不该被碰。 */
        NONE,
        /** 门格：{@code active} 写 {@code END_PORTAL}，否则写 {@code AIR}。共 20 格。 */
        GATE,
        /** 基岩圈：{@code 6 < dx²+dz² ≤ 12}，永远是 {@code BEDROCK}。共 16 格。 */
        RIM,
        /** 中心基岩柱顶：{@code (0,0)}，原生柱在上面无条件覆盖，只能是 {@code BEDROCK}。共 1 格。 */
        PILLAR
    }

    /**
     * 门平面一格的期望种类。
     *
     * <p>这是本类唯一「可能悄悄写错」的规则，所以做成纯函数单测：原生主循环先按距离写门格/基岩圈，
     * <b>之后</b>才把中心柱 {@code origin.above(0..3)} 覆盖成基岩，因此 {@code (0,0)} 永远是 {@code BEDROCK}。
     * 把它当门格会让每一次真实新世界锚定都以 {@code WRITE_MISMATCH} 结束。
     *
     * <p>格子数：门格 20 + 基岩圈 16 + 中心 1 + 四角 12 = 49（7×7）。
     */
    static Kind kindAt(int dx, int dz) {
        int distanceSqr = dx * dx + dz * dz;
        if (distanceSqr > RIM_OUTER_SQR) {
            return Kind.NONE;
        }
        if (distanceSqr == 0) {
            return Kind.PILLAR;
        }
        return distanceSqr <= GATE_INNER_SQR ? Kind.GATE : Kind.RIM;
    }

    /** 门格的期望材质：{@code active} 给返回门，否则给空气（其余格子与 active 无关）。 */
    static Material gateMaterial(boolean active) {
        return active ? Material.END_PORTAL : Material.AIR;
    }

    /**
     * 按「相对门心的偏移」读一格材质（{@code dy} 相对门平面）。
     *
     * <p>抽成接口是为了让测试<b>不建世界</b>也能按原生顺序模拟：先写 20 门格 + 基岩圈，再用中心柱覆盖，
     * 然后断言判定确实要求 20/1/16，而不是只盯一个 {@code Outcome}。
     */
    interface MaterialLookup {
        Material at(int dx, int dy, int dz);
    }

    /**
     * 门平面成品判定的一次汇总。
     *
     * @param gateCells   实际核对过的门格数（应为 20）
     * @param rimCells    实际核对过的基岩圈格数（应为 16）
     * @param pillarCells 实际核对过的中心柱格数（门平面那格 + 上方三格，应为 4）
     * @param problems    不符项（最多保留 4 条，够定位即可）
     */
    record FrameCheck(int gateCells, int rimCells, int pillarCells, List<String> problems) {
        boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * 核对门平面成品：20 门格 + 16 基岩圈 + 中心柱（含门平面那一格）。
     *
     * <p>只读，纯逻辑；调用方必须已经在拥有门心区域的线程上（{@link #anchor} 里已通过写前预检保证）。
     */
    static FrameCheck verifyGatePlane(boolean active, MaterialLookup lookup) {
        List<String> problems = new ArrayList<>();
        int gateCells = 0;
        int rimCells = 0;
        for (int dx = -PATTERN_REACH; dx <= PATTERN_REACH; dx++) {
            for (int dz = -PATTERN_REACH; dz <= PATTERN_REACH; dz++) {
                Kind kind = kindAt(dx, dz);
                if (kind == Kind.NONE) {
                    continue;
                }
                Material want = kind == Kind.GATE ? gateMaterial(active) : Material.BEDROCK;
                Material got = lookup.at(dx, 0, dz);
                if (kind == Kind.GATE) {
                    gateCells++;
                } else if (kind == Kind.RIM) {
                    rimCells++;
                }
                if (got != want && problems.size() < 4) {
                    problems.add(kind + "(" + dx + ", 0, " + dz + ")=" + got + "，期望 " + want);
                }
            }
        }
        int pillarCells = 0;
        for (int dy = 0; dy < PILLAR_HEIGHT; dy++) {
            pillarCells++;
            Material got = lookup.at(0, dy, 0);
            if (got != Material.BEDROCK && problems.size() < 4) {
                problems.add("中心柱(0, " + dy + ", 0)=" + got + "，期望 BEDROCK");
            }
        }
        return new FrameCheck(gateCells, rimCells, pillarCells, problems);
    }

    /**
     * 目标坐标的前置条件：只支持原点列（{@code x == z == 0}）且四周留白。
     *
     * <p>{@code x/z} 必须是 0，不是「随便哪里都行」：原生 {@code findExitPortal} /
     * {@code spawnExitPortal} 全部围绕 {@code fight.origin} 换算，坐标一偏就等于在别处写门。
     * 纯函数，便于在没有世界的情况下单测。
     *
     * @return 不合规时返回中文原因，合规返回 {@code null}
     */
    static @Nullable String coordinateProblem(int x, int y, int z, int minY, int maxY) {
        if (x != 0 || z != 0) {
            return "目标 (" + x + ", " + y + ", " + z + ") 不在原点列 (0, ·, 0)：原生门逻辑按竞技场原点换算坐标";
        }
        // 原生 EndPodiumFeature 会写到 origin.y-3..origin.y+3，两端都要留在世界高度内。
        if (y - PODIUM_REACH <= minY || y + PODIUM_REACH >= maxY) {
            return "目标 y=" + y + " 太靠近世界上下边界（" + minY + ".." + maxY + "）";
        }
        return null;
    }

    // ------------------------------------------------------------------ 预检

    /** 一次预检的结论：{@code OK} 之外只可能是 CHUNK_NOT_LOADED / CHUNK_NOT_OWNED。 */
    private record Check(Status status, String detail) {
        static final Check OK = new Check(Status.OK, "写区全部已加载且归当前区域");
    }

    /**
     * 逐列核验「区块已加载 + 当前线程拥有该列」，覆盖 {@code x/z ∈ [center-3, center+3]} 的 7×7 个方块列。
     *
     * <p>只调 {@code isChunkLoaded} / {@code isOwnedByCurrentRegion}，<b>一个方块都不读</b>，
     * 所以可以在动手之前先把最坏情况筛掉。区域是 XZ 维度上的分组，y 不参与，故每列核一次即可。
     */
    private static Check precheckColumns(World world, int centerX, int centerZ) {
        int sampleY = world.getMinHeight();
        for (int x = centerX - PODIUM_REACH; x <= centerX + PODIUM_REACH; x++) {
            for (int z = centerZ - PODIUM_REACH; z <= centerZ + PODIUM_REACH; z++) {
                Check chunk = precheckChunk(world, x >> 4, z >> 4, sampleY);
                if (chunk.status() != Status.OK) {
                    return chunk;
                }
            }
        }
        return Check.OK;
    }

    /**
     * 核验 {@code findExitPortal()} 的 17×17 区块扫描窗：它会对窗内每个区块调 {@code level.getChunk}，
     * 那是同步加载，跨区域调用会把别的 region 拽住。所以要求整窗「已加载 + 归当前区域」，否则拒绝。
     *
     * <p>后果是：Folia 上一个区域装不下 17×17 区块时，{@code readFrame} 会稳定返回
     * {@code CHUNK_NOT_OWNED}——这是<b>故意</b>的，宁可让显式升级流程拒绝，也不去同步加载别人的区块。
     */
    private static Check precheckScanWindow(World world, int originX, int originZ) {
        int sampleY = world.getMinHeight();
        int centerChunkX = originX >> 4;
        int centerChunkZ = originZ >> 4;
        for (int cx = centerChunkX - SCAN_CHUNK_RADIUS; cx <= centerChunkX + SCAN_CHUNK_RADIUS; cx++) {
            for (int cz = centerChunkZ - SCAN_CHUNK_RADIUS; cz <= centerChunkZ + SCAN_CHUNK_RADIUS; cz++) {
                Check chunk = precheckChunk(world, cx, cz, sampleY);
                if (chunk.status() != Status.OK) {
                    return chunk;
                }
            }
        }
        return Check.OK;
    }

    /** 单个区块的「已加载 + 归当前区域」核验；只读区块状态，不读方块内容。 */
    private static Check precheckChunk(World world, int chunkX, int chunkZ, int sampleY) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return new Check(Status.CHUNK_NOT_LOADED, "区块 (" + chunkX + ", " + chunkZ + ") 还没加载");
        }
        if (!Bukkit.isOwnedByCurrentRegion(new Location(world, (chunkX << 4) + 8.5D, sampleY, (chunkZ << 4) + 8.5D))) {
            return new Check(Status.CHUNK_NOT_OWNED, "区块 (" + chunkX + ", " + chunkZ + ") 不归当前区域");
        }
        return Check.OK;
    }

    // ------------------------------------------------------------------ 探测 / 链接

    /** 探测结果。{@code OK} 之外都表示不可用；缓存后不再重试。 */
    private record Probe(Status status, String detail) {
    }

    private static Probe probe() {
        Probe current = probe;
        if (current != null) {
            return current;
        }
        synchronized (PROBE_LOCK) {
            current = probe;
            if (current == null) {
                current = computeProbe();
                probe = current;
            }
        }
        return current;
    }

    /**
     * 版本锁 + 链接探测。
     *
     * <p>顺序是刻意的：先看版本（纯 Bukkit API，任何线程都能问），再看符号。符号探测只取类字面量并
     * 读它们的名字——{@code .class} 不触发静态初始化，不会在探测阶段把原生龙战的 codec 建起来。
     */
    private static Probe computeProbe() {
        String version;
        try {
            version = Bukkit.getMinecraftVersion();
        } catch (LinkageError linkage) {
            return new Probe(Status.DISABLED_LINKAGE, "Bukkit API 链接失败：" + describe(linkage));
        } catch (RuntimeException notReady) {
            // 服务端还没起来（测试进程 / bootstrapper 早期）：当作不适用，绝不抛。
            return new Probe(Status.DISABLED_VERSION, "读不到服务端版本（" + describe(notReady) + "）");
        }
        if (!SUPPORTED_MINECRAFT_VERSION.equals(version)) {
            return new Probe(Status.DISABLED_VERSION,
                    "服务端 Minecraft 版本是 " + version + "，本桥接只按 " + SUPPORTED_MINECRAFT_VERSION + " 编译链接");
        }
        try {
            ServerLevel.class.getName();
            EnderDragonFight.class.getName();
            BlockPos.class.getName();
            BlockPattern.BlockPatternMatch.class.getName();
            CraftWorld.class.getName();
            return new Probe(Status.OK, "MU " + version + " 原生末地龙战可用");
        } catch (LinkageError linkage) {
            return new Probe(Status.DISABLED_LINKAGE, "真实 NMS 符号链接失败：" + describe(linkage));
        }
    }

    /** 真实符号一旦链接失败就整体永久关停，避免「一次半成功」的状态。 */
    private static void disable(LinkageError linkage) {
        synchronized (PROBE_LOCK) {
            Probe current = probe;
            if (current == null || current.status() == Status.OK) {
                probe = new Probe(Status.DISABLED_LINKAGE, "真实 NMS 符号链接失败：" + describe(linkage));
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static @Nullable ServerLevel handleOf(World world) {
        return world instanceof CraftWorld craft ? craft.getHandle() : null;
    }

    private static @Nullable Location toLocation(World world, @Nullable BlockPos pos) {
        return pos == null ? null : new Location(world, pos.getX(), pos.getY(), pos.getZ());
    }

    /** 同一格方块（忽略世界对象身份以外的浮点差与 yaw/pitch）。 */
    private static boolean sameBlock(@Nullable Location a, @Nullable Location b) {
        return a != null && b != null
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private static Frame failedFrame(Status status, String detail) {
        return new Frame(status, detail, null, null, 0, 0, 0, false);
    }

    private static String describe(@Nullable Location location) {
        return location == null
                ? "(无记录)"
                : "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }
}
