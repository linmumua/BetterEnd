package dev.mumu.betterend.vanilla;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 中心祭坛（YUNG「Better End Island」的 {@code tower_initial}）的**纯模板/布局层**：只回答「哪一格
 * 应该是什么方块」，不读世界、不写世界、不认识调度与备份。
 *
 * <h2>模板本体</h2>
 * 固定提交 {@code 549ad39efd0761f7c4ca2584d24d5e8ecf922e24} 的
 * {@code Common/src/main/resources/data/betterendisland/structure/tower_initial.nbt} 原字节，放在
 * {@link #RESOURCE}（{@link #SHA256} 由 {@code EndPodiumTemplateTest} 钉住）。29×27×29、17,383 格：
 * 空气 13,589、末地石砖 2,909、黑曜石 343、砖墙 207、砖楼梯 154、砖台阶 115、灰混凝土 65、钟 1；
 * 无实体、无红混凝土。和 36 个柱模板不同，这份模板**把空气也存进了 {@code blocks}**，而且这些空气格
 * 是有语义的（「塔里必须是空的」），所以不能在读取阶段滤掉。
 *
 * <h2>锚点：以纯地形场顶块 S 为原点，门跟着建筑走</h2>
 * 顺序固定为<b>地形 → 建筑 → 功能槽</b>：
 * <ul>
 *   <li>{@code S} = 本插件纯地形场（{@code DustWastelandsGenerator} 的 {@code IslandField}）在中心列上
 *       的最高实心 y，也就是未来楼板所在的那一格：{@code floorY = S}；</li>
 *   <li>{@code baseY = S - 5} 是模板底面（局部 y=0），{@code gateY = baseY + 2 = S - 3} 是门环平面，
 *       {@code podiumTopY = baseY + 5 = S}；</li>
 *   <li>XZ 恒居中在原点：{@code baseX = gateX - 14}、{@code baseZ = gateZ - 14}（29 宽，中心下标 14）；</li>
 *   <li><b>不旋转</b>：{@code quarterTurns = 0}，模板自带朝向，转了反而会让山墙、楼梯和钟面朝错方向。</li>
 * </ul>
 * 旧实现反过来读 {@code exitPortalLocation} 当锚点，于是「门在哪，楼就在哪」——门是按高度图放的，塔的
 * 钟柱把高度图抬高二十几格，楼就飘到屋顶上。现在门环的 y 是算出来的，由
 * {@code MuDragonFightBridge#anchor} 显式钉到 {@code gateY}，龙死亡/复活/重启都复用同一份持久化布局。
 *
 * <h2>三层掩码：家具 / 净空 / 装饰</h2>
 * <ul>
 *   <li>{@link #isFurniture 家具}（{@link Plan#furniture()}）：4 格基岩柱、{@code gateY-1..gateY} 的
 *       基岩圈与返回门方块、柱顶上方 2 格的 4 支火把、龙蛋落点（柱顶上一格）。<b>既不写也不清</b>，
 *       它是「原生核心真的在这个坐标」的现场证据；</li>
 *   <li>{@link #inPit 门坑} + {@link #inWell 中心 7×7 井口} + {@link #isSeatHead 四晶座头部}
 *       （{@link Plan#clearance()}）：**必须真的清成 AIR**，施工第一阶段执行。旧版那圈「整盒零写入」
 *       保护把这里留给天然末地石，正好堵住下移后的功能槽，也让井口不可达；</li>
 *   <li>其余模板格（{@link Plan#decorations()}）：装饰与结构，第二阶段在原版核心生成之后落笔。</li>
 * </ul>
 * 井口半宽 3 同时是原版半径 3 晶体头部的位置，模板下一层（局部 y=4）保留的半径 3 砖圈则天然成为
 * 出入台阶：井口一格、砖圈一格、门平面各差一格，不需要另造方块，也不裁钟楼或屋顶。
 *
 * <h2>四晶座</h2>
 * 模板里半径 8 的四根 {@code gray_concrete} 柱（局部 y=0..5）就是晶座支撑，落到世界是
 * {@code (gateX±8, baseY..floorY, gateZ)} 与 {@code (gateX, baseY..floorY, gateZ±8)} 的基岩，晶体头部
 * 在 {@code floorY+1}（{@link #CRYSTAL_LOCAL_Y}）。这是四个<b>槽位</b>，不是八颗水晶。

 *
 * <h2>局部修复集合（用真服务端固化的范围，不猜）</h2>
 * 同一份反汇编给出 {@code EndPodiumFeature#place} 的真实写入范围：遍历
 * {@code betweenClosed(pos.x-4, pos.y-1, pos.z-4) … (pos.x+4, pos.y+32, pos.z+4)}，只处理
 * {@code closerThan(pos, 3.5)} 的格子（整数距离平方 {@code ≤ 12}，已用本机 jar 实测确认，
 * 半径 2.5 的内圈是 {@code ≤ 6}）。也就是说原版重生/重建只动
 * <b>{@code dy ∈ [-1, 32]} 且 {@code dx²+dy²+dz² ≤ 12}</b> 的一小团，而这一团的水平半径最多 3 格、
 * 下沿只到 {@code portalY-1} —— <b>全部落在保护区里</b>。所以 {@link Plan#repairs()} 在我们的锚点下
 * 是空集：塔不会再被原版祭坛重建擦掉，也就不需要「重填」。{@code DragonRespawnStage} 的其余阶段只
 * 碰 42 格外的柱子与水晶，不碰中心。这条结论把计划里「重建会清空上方建筑」的猜测纠正掉了，并且是
 * 可验证的（{@code EndPodiumTemplateTest} 逐格枚举两者）。
 *
 * <h2>预检谓词：只放行天然地形，不看「像不像目标」</h2>
 * 施工前逐格分类（{@link #classify}），<b>只看世界当前是什么</b>：{@link Verdict#NATURAL}
 * （天然末地石 / 空气）可以写；{@link Verdict#FUNCTION_PROTECTED}（基岩、返回门、龙蛋）跳过，
 * 绝不覆盖；其余一律 {@link Verdict#UNKNOWN} —— 未知玩家建筑、容器、别人放的末地石砖都在这一类，
 * 遇到就**阻止整个世界的改建**，而不是跳过那一格拼出残缺的塔。
 * <p>
 * 这里<b>故意没有</b>「当前方块和模板目标一致就算已完成」这一档。目标一致证明不了所有权：玩家恰好
 * 在塔的位置上摆了一圈 {@code end_stone_bricks}、或者一面同 id 但朝向不同的楼梯，都会被这种推断
 * 当成「本服务已经放下过」，于是施工直接覆盖掉玩家建筑。所有权只有一个来源——世界 PDC 的完成标记
 * （{@code EndPodiumManaged}）：有标记就整楼不建，没标记就只认天然地形。中途中止/重启留下的半成品
 * 保留备份、拒绝自动续建，也不需要「哪些格是本服务写的」这种逐格清单。
 * <p>
 * 特别地：{@code end_stone_bricks} <b>不算</b>天然地形（玩家和本插件都会放它），别把它当末地石。
 */
public final class EndPodiumTemplate {

    /** 模板名（{@link PillarTemplate#load(String)} 的白名单里与 36 个柱模板并列）。 */
    public static final String NAME = "tower_initial";
    /** jar 内的位置。 */
    public static final String RESOURCE = PillarTemplate.DIRECTORY + NAME + ".nbt";
    /** 固定提交原字节的 SHA-256（小写十六进制）。 */
    public static final String SHA256 = "36d155015ca55d1cbef81e3755a082255c37afafaf2c1e024288424d941ef7cf";

    /** 模板尺寸（{@code size[0..2]}）。 */
    public static final int SIZE_X = 29;
    public static final int SIZE_Y = 27;
    public static final int SIZE_Z = 29;
    /** 返回门所在的局部 XZ 下标（= 29/2）。 */
    public static final int CENTER_X = SIZE_X / 2;
    public static final int CENTER_Z = SIZE_Z / 2;
    /** 模板格数（含空气）与其中的空气格数。 */
    public static final int CELLS = 17_383;
    public static final int AIR_CELLS = 13_589;
    /** 灰混凝土标记数（每个都变成基岩）。 */
    public static final int MARKERS = 65;

    /** 与返回门平面（{@code portalY}，本文统称 {@code gateY}）对齐的模板局部 y。 */
    public static final int PORTAL_LOCAL_Y = 2;
    /** 楼板 / 中心基岩柱顶所在的模板局部 y（{@code floorY = gateY + 3}）。 */
    public static final int FLOOR_LOCAL_Y = 5;
    /** 四晶座的晶体头部所在的模板局部 y（{@code floorY + 1}）。 */
    public static final int CRYSTAL_LOCAL_Y = 6;
    /** 门平面比楼板低几格（{@code FLOOR_LOCAL_Y - PORTAL_LOCAL_Y}）。 */
    public static final int GATE_BELOW_FLOOR = FLOOR_LOCAL_Y - PORTAL_LOCAL_Y;
    /** 四晶座的半径（模板里半径 8 的 bedrock 支撑柱）。 */
    public static final int SEAT_RADIUS = 8;
    /** 井口半宽：中心 7×7，同时覆盖原版半径 3 的晶体头部位置。 */
    public static final int WELL_RADIUS = 3;

    /**
     * 落盘布局的格式版本。1 是「以原版返回门为锚点、整盒保护区」的旧格式（只有 {@code x,y,z}），
     * 2 起是「以纯地形场顶块 S 为锚点」并带世界种子；读到别的数字一律拒绝，不猜。
     */
    public static final int LAYOUT_VERSION = 2;

    /**
     * 原版 {@code EndPodiumFeature#place} 的遍历盒半宽（{@code pos.x-4..pos.x+4}、同 Z）。
     * 保留成常量是为了让「保护区 ⊇ 原版写入范围」这条不变量可被测试逐格验证。
     */
    public static final int VANILLA_BOX_RADIUS = 4;
    /** 原版遍历盒下沿偏移（{@code pos.y-1}）。 */
    public static final int VANILLA_BOX_BELOW = 1;
    /** 原版遍历盒上沿偏移（{@code pos.y+32}）。 */
    public static final int VANILLA_BOX_ABOVE = 32;
    /** {@code pos.closerThan(x, 3.5)} 的整数等价：距离平方上限（3.5² = 12.25）。 */
    public static final int VANILLA_RADIUS_D2 = 12;
    /** {@code pos.closerThan(x, 2.5)} 的整数等价：内圈上限（2.5² = 6.25）。 */
    public static final int VANILLA_INNER_D2 = 6;

    private static final String AIR = "minecraft:air";
    private static final String CAVE_AIR = "minecraft:cave_air";
    private static final String VOID_AIR = "minecraft:void_air";
    private static final String END_STONE = "minecraft:end_stone";
    private static final String BEDROCK = "minecraft:bedrock";
    private static final String END_PORTAL = "minecraft:end_portal";
    private static final String DRAGON_EGG = "minecraft:dragon_egg";
    private static final String TORCH = "minecraft:torch";
    private static final String WALL_TORCH = "minecraft:wall_torch";

    /** 可以被覆盖的天然地形（末地岛只有末地石与空气；{@code end_stone_bricks} 故意不在里面）。 */
    private static final Set<String> NATURAL = Set.of(AIR, CAVE_AIR, VOID_AIR, END_STONE);
    /**
     * 门的功能方块：永远不覆盖。基岩柱/基岩圈/返回门/龙蛋之外，还包括原版祭坛在柱顶上方 2 格放的
     * 4 支火把——预检遇到它们必须是「跳过」，而不是「未知方块，中止整个世界」。
     */
    private static final Set<String> FUNCTION_PROTECTED =
            Set.of(BEDROCK, END_PORTAL, DRAGON_EGG, TORCH, WALL_TORCH);

    private EndPodiumTemplate() {
    }

    /** 一格在计划里的处理方式。 */
    public enum Kind {
        /** 写这个方块状态。 */
        SOLID,
        /** 模板的普通空气格：只清理天然地形 / 旧原版建筑，不碰未知方块。 */
        AIR,
        /** 灰混凝土标记：写 {@code minecraft:bedrock}。 */
        BEDROCK
    }

    /**
     * 预检结论。没有「已经是目标方块」这一档：目标一致不等于本服务拥有（见类注释），
     * 同 id 的非天然方块一律落到 {@link #UNKNOWN}。
     */
    public enum Verdict {
        /** 天然末地石 / 空气，可以覆盖。 */
        NATURAL,
        /** 基岩 / 返回门 / 龙蛋：跳过，绝不覆盖。 */
        FUNCTION_PROTECTED,
        /** 其余一切（玩家建筑、容器、末地石砖、同 id 的楼梯……）：阻止整个世界改建。 */
        UNKNOWN
    }

    /** 计划里的一格：世界坐标 + 目标状态串 + 处理方式。 */
    public record Cell(int x, int y, int z, String state, Kind kind) {

        /** 是不是模板的普通空气格（这一格要「清空」，而不是「放方块」）。 */
        public boolean air() {
            return kind == Kind.AIR;
        }

        /** 这一格是不是灰标记变成的基岩。 */
        public boolean marker() {
            return kind == Kind.BEDROCK;
        }
    }

    /** 区块键（{@code floorDiv(16)}，负坐标也正确）。 */
    public record ChunkKey(int x, int z) {

        /** 方块坐标所属区块。 */
        public static ChunkKey of(int blockX, int blockZ) {
            return new ChunkKey(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        }
    }

    /**
     * 一个门环坐标下的完整计划（不可变）。
     *
     * <p>各集合的分工：
     * <ul>
     *   <li>{@link #cells()}：模板的全部 17,383 格（含家具与净空），用于备份体积、契约测试与诊断；</li>
     *   <li>{@link #clearance()}：必须清成 AIR 的格（门坑 + 7×7 井口 + 四晶座头部），施工第一阶段；</li>
     *   <li>{@link #decorations()}：装饰/结构格（模板原状态），施工第二阶段（原生核心已生成之后）；</li>
     *   <li>{@link #writes()}：{@code clearance} 后接 {@code decorations}，即真正要落的全部笔；</li>
     *   <li>{@link #furniture()}：原版门家具所在的模板格，永远不写也不清；</li>
     *   <li>{@link #repairs()}：原版祭坛重建会波及、且需要填回的格子（见类注释，本锚点下为空）。</li>
     * </ul>
     * {@code cells = writes + furniture}（按坐标划分，不重叠）。
     */
    public static final class Plan {

        private final int portalX;
        private final int portalY;
        private final int portalZ;
        private final List<Cell> cells;
        private final List<Cell> clearance;
        private final List<Cell> decorations;
        private final List<Cell> writes;
        private final List<Cell> skipped;
        private final List<Cell> repairs;
        private final List<Seat> seats;
        private final Map<ChunkKey, List<Cell>> decorationChunks;
        private final Map<ChunkKey, List<Cell>> clearanceChunks;

        private Plan(int portalX, int portalY, int portalZ, List<Cell> cells,
                     List<Cell> clearance, List<Cell> decorations, List<Cell> skipped,
                     List<Cell> repairs, List<Seat> seats) {
            this.portalX = portalX;
            this.portalY = portalY;
            this.portalZ = portalZ;
            this.cells = Collections.unmodifiableList(cells);
            this.clearance = Collections.unmodifiableList(clearance);
            this.decorations = Collections.unmodifiableList(decorations);
            this.skipped = Collections.unmodifiableList(skipped);
            this.repairs = Collections.unmodifiableList(repairs);
            this.seats = Collections.unmodifiableList(seats);
            List<Cell> all = new ArrayList<>(clearance.size() + decorations.size());
            all.addAll(clearance);
            all.addAll(decorations);
            this.writes = Collections.unmodifiableList(all);
            this.decorationChunks = bucket(decorations);
            this.clearanceChunks = bucket(clearance);
        }

        private static Map<ChunkKey, List<Cell>> bucket(List<Cell> writes) {
            Map<ChunkKey, List<Cell>> buckets = new TreeMap<>(
                    (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
            for (Cell cell : writes) {
                buckets.computeIfAbsent(ChunkKey.of(cell.x(), cell.z()), key -> new ArrayList<>())
                        .add(cell);
            }
            Map<ChunkKey, List<Cell>> frozen = new LinkedHashMap<>(buckets.size());
            for (Map.Entry<ChunkKey, List<Cell>> entry : buckets.entrySet()) {
                frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }
            return Collections.unmodifiableMap(frozen);
        }

        public int portalX() {
            return portalX;
        }

        public int portalY() {
            return portalY;
        }

        public int portalZ() {
            return portalZ;
        }

        /** 模板局部 (0,0,0) 所在的世界坐标。 */
        public int baseX() {
            return minX();
        }

        public int baseY() {
            return minY();
        }

        public int baseZ() {
            return minZ();
        }

        /** 施工体积（闭区间）。 */
        public int minX() {
            return portalX - CENTER_X;
        }

        public int maxX() {
            return portalX - CENTER_X + SIZE_X - 1;
        }

        public int minY() {
            return portalY - PORTAL_LOCAL_Y;
        }

        public int maxY() {
            return portalY - PORTAL_LOCAL_Y + SIZE_Y - 1;
        }

        public int minZ() {
            return portalZ - CENTER_Z;
        }

        public int maxZ() {
            return portalZ - CENTER_Z + SIZE_Z - 1;
        }

        /** 模板的全部格子（含保护区），顺序与 NBT 里的 {@code blocks} 一致。 */
        public List<Cell> cells() {
            return cells;
        }

        /** 门环平面（{@code gateY}）：原版 {@code exitPortalLocation} 与返回门方块所在的 y。 */
        public int gateY() {
            return portalY;
        }

        /** 门环平面的 X（中心建筑恒在原点，这里提供显式名字，避免和 {@code portalX} 混淆）。 */
        public int gateX() {
            return portalX;
        }

        /** 门环平面的 Z。 */
        public int gateZ() {
            return portalZ;
        }

        /** 楼板 / 中心基岩柱顶 = 纯地形场顶块 {@code S}；也是原版的晶体站立面。 */
        public int floorY() {
            return portalY + GATE_BELOW_FLOOR;
        }

        /** 楼板平面（与 {@link #floorY()} 同义，用名字区分「地面高度」与「建筑标高」）。 */
        public int podiumTopY() {
            return floorY();
        }

        /** 锚点来源：本插件纯地形场在中心列上的顶块高度。 */
        public int surfaceTopY() {
            return floorY();
        }

        /** 四晶座：半径 8 的四个 bedrock 支撑与它们的晶体头部。 */
        public List<Seat> seats() {
            return seats;
        }

        /** 中心 7×7 井口（{@code floorY} 层）：出入通道，同时是半径 3 晶体头部的净空。 */
        public List<Cell> well() {
            List<Cell> cells = new ArrayList<>();
            for (Cell cell : clearance) {
                if (cell.y() == floorY()
                        && Math.abs(cell.x() - portalX) <= WELL_RADIUS
                        && Math.abs(cell.z() - portalZ) <= WELL_RADIUS) {
                    cells.add(cell);
                }
            }
            return Collections.unmodifiableList(cells);
        }

        /** 必须为 AIR 的格（门坑 + 7×7 井口 + 四晶座头部）：施工的第一阶段。 */
        public List<Cell> clearance() {
            return clearance;
        }

        /** 装饰/结构格（模板原状态）：施工的第二阶段，落笔前必须已经生成原生核心。 */
        public List<Cell> decorations() {
            return decorations;
        }

        /** 原版门家具（基岩柱/基岩圈/返回门/火把/龙蛋落点）：本服务一格都不写，也不清。 */
        public List<Cell> furniture() {
            return skipped;
        }

        /** 要落笔的全部格子 = {@link #clearance()} 然后 {@link #decorations()}（执行顺序）。 */
        public List<Cell> writes() {
            return writes;
        }

        /** 落在原版家具上的模板格：永远不写。 */
        public List<Cell> skipped() {
            return skipped;
        }

        /** 原版祭坛重建会波及、需要填回的格子（当前锚点下为空，见类注释）。 */
        public List<Cell> repairs() {
            return repairs;
        }

        /** {@link #decorations()} 按区块分桶（键按 x、z 升序，桶内保持模板顺序）。 */
        public Map<ChunkKey, List<Cell>> byChunk() {
            return decorationChunks;
        }

        /** {@link #clearance()} 按区块分桶：施工第一阶段用。 */
        public Map<ChunkKey, List<Cell>> clearanceByChunk() {
            return clearanceChunks;
        }

        /**
         * 原生核心的写区会碰到的全部区块：净空、装饰与家具三处的区块键并集。
         *
         * <p>原生 {@code spawnExitPortal} 的写区是 {@code origin±4} 的 7×7×7，会落到 2×2 个区块上；
         * 调用前必须把这几块都异步准备一遍，不能只准备中心那一块。
         */
        public List<ChunkKey> coreChunks() {
            Map<ChunkKey, Boolean> keys = new TreeMap<>(
                    (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
            for (ChunkKey key : clearanceChunks.keySet()) {
                keys.put(key, Boolean.TRUE);
            }
            for (ChunkKey key : decorationChunks.keySet()) {
                keys.put(key, Boolean.TRUE);
            }
            for (Cell cell : cells) {
                if (isFurniture(portalX, portalY, portalZ, cell.x(), cell.y(), cell.z())) {
                    keys.put(ChunkKey.of(cell.x(), cell.z()), Boolean.TRUE);
                }
            }
            return List.copyOf(keys.keySet());
        }

        /** 返回门那一列（{@code dx = dz = 0}）上的全部格子：高度图风险的诊断入口。 */
        public List<Cell> centerColumn() {
            List<Cell> column = new ArrayList<>();
            for (Cell cell : cells) {
                if (cell.x() == portalX && cell.z() == portalZ) {
                    column.add(cell);
                }
            }
            return Collections.unmodifiableList(column);
        }

        /** 这一格是否落在原版门家具上（坐标无关，只看相对门平面的位置）：永不写、也不清。 */
        public boolean protects(int x, int y, int z) {
            return EndPodiumTemplate.isFurniture(portalX, portalY, portalZ, x, y, z);
        }
    }

    /** 一座晶座：{@code supportY} 是 {@code floorY} 上的 bedrock 支撑，晶体头部在它上面一格。 */
    public record Seat(int x, int z, int supportY) {

        /** 晶体实体应该占据的那一格（{@code floorY + 1}）。 */
        public int headY() {
            return supportY + 1;
        }
    }

    /**
     * 按**纯地形场顶块**算布局：中心恒在原点，楼板落在 {@code surfaceTopY} 上，门平面比它低 3 格。
     *
     * <p>这是唯一合法的「首次生成版」入口：{@code S} 来自 {@code DustWastelandsGenerator} 那份纯地形场，
     * 不读高度图、不读旧返回门、不读建筑。
     */
    public static Plan planAtSurface(int surfaceTopY) {
        return plan(0, surfaceTopY - GATE_BELOW_FLOOR, 0);
    }

    /**
     * 计算 {@code (gateX, gateY, gateZ)} 下的完整计划。
     *
     * @param gateX 门环平面（原版 {@code exitPortalLocation}）的 X
     * @param gateY 门环平面的 Y；楼板在 {@code gateY + 3}
     * @param gateZ 门环平面的 Z
     */
    public static Plan plan(int gateX, int gateY, int gateZ) {
        PillarTemplate template = PillarTemplate.load(NAME);
        int baseX = gateX - CENTER_X;
        int baseY = gateY - PORTAL_LOCAL_Y;
        int baseZ = gateZ - CENTER_Z;
        List<Cell> all = new ArrayList<>(template.count());
        List<Cell> clearance = new ArrayList<>(64);
        List<Cell> decorations = new ArrayList<>(template.count());
        List<Cell> skipped = new ArrayList<>();
        List<Cell> repairs = new ArrayList<>();
        for (int i = 0; i < template.count(); i++) {
            int x = baseX + template.x(i);
            int y = baseY + template.y(i);
            int z = baseZ + template.z(i);
            Cell cell = new Cell(x, y, z, cellState(template, i), cellKind(template, i));
            all.add(cell);
            if (isFurniture(gateX, gateY, gateZ, x, y, z)) {
                // 原版家具：基岩柱/基岩圈/返回门/火把/龙蛋落点。既不写也不清——它是「原生核心在这里」
                // 的证据，也是功能方块本身。
                skipped.add(cell);
                continue;
            }
            if (inPit(gateX, gateY, gateZ, x, y, z)
                    || inWell(gateX, gateY, gateZ, x, y, z)
                    || isSeatHead(gateX, gateY, gateZ, x, y, z)) {
                // 净空：门坑、7×7 井口、四晶座头部与半径 3 的晶体头部。**必须真的清**——旧版整盒
                // 零写入保护留下的天然末地石就是从这里堵住下移后的功能槽的。
                clearance.add(new Cell(x, y, z, AIR, Kind.AIR));
                continue;
            }
            decorations.add(cell);
            if (inVanillaPodiumWrite(gateX, gateY, gateZ, x, y, z)) {
                repairs.add(cell);
            }
        }
        int floorY = gateY + GATE_BELOW_FLOOR;
        List<Seat> seats = List.of(
                new Seat(gateX + SEAT_RADIUS, gateZ, floorY),
                new Seat(gateX - SEAT_RADIUS, gateZ, floorY),
                new Seat(gateX, gateZ + SEAT_RADIUS, floorY),
                new Seat(gateX, gateZ - SEAT_RADIUS, floorY));
        return new Plan(gateX, gateY, gateZ, all, clearance, decorations, skipped, repairs, seats);
    }

    /**
     * 这一格是不是原版 {@code EndPodiumFeature#place} 的家具：4 格基岩柱（{@code gateY..floorY}）、
     * 基岩圈与返回门方块（{@code gateY-1..gateY} 半径 3.5）、柱顶上方 2 格的 4 支火把、以及龙蛋落点
     * （柱顶上一格 {@code floorY+1}）。
     *
     * <p>这些格本服务**一格都不写，也不清**：基岩拆不掉，是「原生核心真的在这个坐标」的现场证据；
     * 门与蛋是功能方块。整盒零写入的旧保护已经被这份精确掩码取代——掩码外必须清，掩码内绝不动。
     */
    public static boolean isFurniture(int gateX, int gateY, int gateZ, int x, int y, int z) {
        int dx = x - gateX;
        int dy = y - gateY;
        int dz = z - gateZ;
        if (dx == 0 && dz == 0 && dy == GATE_BELOW_FLOOR + 1) {
            return true; // 龙蛋落点：柱顶上一格
        }
        if (dx == 0 && dz == 0 && dy >= 0 && dy <= GATE_BELOW_FLOOR) {
            return true; // 4 格基岩柱，柱顶就是楼板
        }
        int d2 = dx * dx + dy * dy + dz * dz;
        if (dy >= -VANILLA_BOX_BELOW && dy <= 0 && d2 <= VANILLA_RADIUS_D2) {
            return true; // 基岩圈（gateY-1..gateY）与返回门方块（gateY 的内圈）
        }
        return dy == 2 && Math.abs(dx) + Math.abs(dz) == 1; // 柱子正上方 2 格的 4 支火把
    }

    /** 门坑：原版祭坛那团 {@code d² ≤ 12} 的空间，下沿到 {@code gateY-1}，上沿到楼板。 */
    public static boolean inPit(int gateX, int gateY, int gateZ, int x, int y, int z) {
        int dx = x - gateX;
        int dy = y - gateY;
        int dz = z - gateZ;
        return dy >= -VANILLA_BOX_BELOW
                && dy <= GATE_BELOW_FLOOR
                && dx * dx + dy * dy + dz * dz <= VANILLA_RADIUS_D2;
    }

    /**
     * 中心 7×7 井口（楼板那一层）。半宽 {@link #WELL_RADIUS} 不是随手取的：
     * <ul>
     *   <li>半径 3 以内正是原版 {@code tryRespawn} 认的晶体位置，所以那条路径的晶体头部也在这里净空；</li>
     *   <li>井口下一层（模板局部 y=4）保留半径 3 的砖圈，天然形成一级台阶，向下两级就能到门平面，
     *       不需要另造方块，也不裁钟楼/屋顶。</li>
     * </ul>
     */
    public static boolean inWell(int gateX, int gateY, int gateZ, int x, int y, int z) {
        return y == gateY + GATE_BELOW_FLOOR
                && Math.abs(x - gateX) <= WELL_RADIUS
                && Math.abs(z - gateZ) <= WELL_RADIUS;
    }

    /** 四晶座的晶体头部（{@code floorY+1}，半径 8）：必须 AIR，否则放不下晶体。 */
    public static boolean isSeatHead(int gateX, int gateY, int gateZ, int x, int y, int z) {
        if (y != gateY + GATE_BELOW_FLOOR + 1) {
            return false;
        }
        int dx = x - gateX;
        int dz = z - gateZ;
        return (Math.abs(dx) == SEAT_RADIUS && dz == 0) || (dx == 0 && Math.abs(dz) == SEAT_RADIUS);
    }

    /** 落盘布局：版本 + 世界种子 + 门环坐标。重启只认这份，不重算地形、不读旧门。 */
    public record Layout(int version, long seed, int gateX, int gateY, int gateZ) {

        /** 这一份布局的方案（纯函数，不读世界）。 */
        public Plan plan() {
            return EndPodiumTemplate.plan(gateX, gateY, gateZ);
        }

        public String encode() {
            return EndPodiumTemplate.encode(this);
        }
    }

    /** 布局的稳定文本形式：{@code v2:seed=<long>:gate=<x>,<y>,<z>}。 */
    public static String encode(Layout layout) {
        return "v" + layout.version() + ":seed=" + layout.seed()
                + ":gate=" + layout.gateX() + "," + layout.gateY() + "," + layout.gateZ();
    }

    /**
     * 解析世界 PDC 里的布局串。**不认识的版本直接拒绝**：1 或没有版本号的是「以旧返回门为锚点」的
     * 旧世界，必须走显式升级，不能当成新布局继续用。
     *
     * @throws IllegalArgumentException 格式不认识或版本不是 {@link #LAYOUT_VERSION}
     */
    public static Layout decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("布局串不能为 null");
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("v")) {
            throw new IllegalArgumentException("布局串 '" + trimmed + "' 没有版本号（旧格式，需要显式升级）");
        }
        String[] parts = trimmed.split(":", -1);
        if (parts.length != 3 || !parts[1].startsWith("seed=") || !parts[2].startsWith("gate=")) {
            throw new IllegalArgumentException("布局串 '" + trimmed + "' 格式不认识");
        }
        int version;
        try {
            version = Integer.parseInt(parts[0].substring(1).trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("布局串 '" + trimmed + "' 的版本号不是数字");
        }
        if (version != LAYOUT_VERSION) {
            throw new IllegalArgumentException("布局版本 " + version + " 不是本版支持的 " + LAYOUT_VERSION
                    + "（旧世界需要显式升级），拒绝按它施工");
        }
        long seed;
        try {
            seed = Long.parseLong(parts[1].substring("seed=".length()).trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("布局串 '" + trimmed + "' 的种子不是数字");
        }
        String[] gate = parts[2].substring("gate=".length()).split(",", -1);
        if (gate.length != 3) {
            throw new IllegalArgumentException("布局串 '" + trimmed + "' 的门坐标不是三元组");
        }
        try {
            return new Layout(version, seed,
                    Integer.parseInt(gate[0].trim()),
                    Integer.parseInt(gate[1].trim()),
                    Integer.parseInt(gate[2].trim()));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("布局串 '" + trimmed + "' 的门坐标不是整数");
        }
    }

    /**
     * 这一格在不在原版 {@code EndPodiumFeature#place} 的写入范围内（返回门坐标相对）。
     *
     * <p>{@code betweenClosed(x-4, y-1, z-4) … (x+4, y+32, z+4)} 里只处理
     * {@code closerThan(pos, 3.5)}：整数距离平方 {@code ≤ 12}（见类注释，已用本机 MU jar 实测）。
     */
    public static boolean inVanillaPodiumWrite(int portalX, int portalY, int portalZ,
                                               int x, int y, int z) {
        int dx = x - portalX;
        int dy = y - portalY;
        int dz = z - portalZ;
        return Math.abs(dx) <= VANILLA_BOX_RADIUS
                && Math.abs(dz) <= VANILLA_BOX_RADIUS
                && dy >= -VANILLA_BOX_BELOW
                && dy <= VANILLA_BOX_ABOVE
                && dx * dx + dy * dy + dz * dz <= VANILLA_RADIUS_D2;
    }

    /**
     * 施工前的逐格预检。
     *
     * <p>只看世界里<b>现在</b>是什么，不看它像不像模板：天然末地石/空气可以写，门的功能方块跳过，
     * 其余一律 {@link Verdict#UNKNOWN}。**同 id、甚至逐字符相同的状态串也不构成「本服务已经放下
     * 过」的证据**——那种推断会把玩家自己摆的末地石砖当成自己的成品直接覆盖（见类注释）。
     *
     * @param currentState 世界里当前方块的状态串（{@code BlockData#getAsString()}，可带属性）
     */
    public static Verdict classify(String currentState) {
        String current = blockId(currentState);
        if (NATURAL.contains(current)) {
            return Verdict.NATURAL;
        }
        if (FUNCTION_PROTECTED.contains(current)) {
            return Verdict.FUNCTION_PROTECTED;
        }
        return Verdict.UNKNOWN;
    }

    /** 直接问「这一格能不能写」：只有 {@link Verdict#NATURAL} 可以。 */
    public static boolean canWrite(String currentState) {
        return classify(currentState) == Verdict.NATURAL;
    }

    /** 天然地形（可覆盖）：末地石与三种空气。{@code end_stone_bricks} 不在里面。 */
    public static boolean isNaturalTerrain(String blockId) {
        return NATURAL.contains(blockId(blockId));
    }

    /** 门的功能方块（永不覆盖）：基岩、返回门、龙蛋。 */
    public static boolean isFunctionProtected(String blockId) {
        return FUNCTION_PROTECTED.contains(blockId(blockId));
    }

    /** 去掉属性，只留方块 id：{@code minecraft:oak_stairs[facing=north]} → {@code minecraft:oak_stairs}。 */
    public static String blockId(String state) {
        if (state == null) {
            throw new IllegalArgumentException("方块状态不能为 null");
        }
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /**
     * 启动预热：把模板的 32 个 palette 状态（灰标记换成基岩）都交给方块状态解析器，外加空气与基岩。
     *
     * <p>和 {@link YungPillars#preload()} 一样，模板或方块状态有问题必须在启动时炸，而不是等区块长到
     * 中心才炸。运行层在自己的启动路径里调用本方法即可；生成器不参与。
     */
    public static void preload() {
        preload(YungPillars::bukkit);
    }

    static void preload(Consumer<String> parser) {
        PillarTemplate template = PillarTemplate.load(NAME);
        for (int i = 0; i < template.paletteSize(); i++) {
            parser.accept(paletteState(template, i));
        }
        parser.accept(AIR);
        parser.accept(BEDROCK);
    }

    /** palette 第 {@code i} 项最终要写的状态串（灰标记 → 基岩）。 */
    static String paletteState(PillarTemplate template, int i) {
        return switch (template.paletteKind(i)) {
            case SEAT -> BEDROCK;
            case SOLID -> template.paletteState(i);
            default -> throw new IllegalStateException(NAME + " 含有柱模板占位块 "
                    + template.paletteState(i) + "：中心模板只允许 gray_concrete 标记");
        };
    }

    private static String cellState(PillarTemplate template, int i) {
        return switch (template.kind(i)) {
            case SEAT -> BEDROCK;
            case SOLID -> template.state(i);
            default -> throw new IllegalStateException(NAME + " 含有柱模板占位块 "
                    + template.state(i) + "：中心模板只允许 gray_concrete 标记");
        };
    }

    private static Kind cellKind(PillarTemplate template, int i) {
        return switch (template.kind(i)) {
            case SEAT -> Kind.BEDROCK;
            case SOLID -> AIR.equals(blockId(template.state(i))) ? Kind.AIR : Kind.SOLID;
            default -> throw new IllegalStateException(NAME + " 含有柱模板占位块 "
                    + template.state(i) + "：中心模板只允许 gray_concrete 标记");
        };
    }
}
