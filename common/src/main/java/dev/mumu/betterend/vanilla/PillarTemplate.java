package dev.mumu.betterend.vanilla;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 一个 YUNG「Better End Island」柱模板：{@code pillar_guarded_N.nbt} 之类的 gzip 结构文件，读成
 * 「不可变的稀疏方块表 + 一个可旋转的 palette」。
 *
 * <h2>为什么是稀疏表</h2>
 * 上游模板不存空气（19×69×19 的 24909 格里只有 1341-2906 个非空气方块），所以「模板要写什么」就是
 * 这张表本身，而不是「整块矩形照抄」。这正好是移植需要的语义：我们的世界里模板前后左右都是岛体与
 * 龙战结构，绝不能像 {@code StructureTemplate#placeInWorld} 那样把空气也写下去。
 *
 * <h2>放置语义（照抄 {@code BetterSpikeFeature.placeTemplate}）</h2>
 * <ul>
 *   <li>模板以柱心为原点：{@code corner = center - (sizeX/2, 0, sizeZ/2)}；</li>
 *   <li>旋转支点 {@code (9,0,9)}（= {@code sizeX/2, 0, sizeZ/2}，本类按尺寸推导，并要求水平两轴
 *       相等且为奇数，否则直接报错——上游把 9 写死在 mixin 里，非 19 宽的模板本来就不成立）；</li>
 *   <li>旋转 {@code StructurePlaceSettings#setRotation}，即只绕支点转，不改变包围盒（正方形）；</li>
 *   <li>方块状态按 {@code BlockState#rotate} 的等价规则旋转，见 {@link State#rotated(int)}。</li>
 * </ul>
 *
 * <h2>模板名</h2>
 * 只认 {@code pillar_(guarded|broken|initial|bottom)_1..9} 和中心祭坛的 {@code tower_initial}
 * （见 {@link EndPodiumTemplate}），并且只从 jar 内的固定目录 {@link #DIRECTORY} 用
 * {@code getResourceAsStream} 打开：本类没有「按调用方给的路径读文件」的入口，所以不存在读取未授权
 * 路径的可能。
 *
 * <h2>空气格</h2>
 * 柱模板不存空气，所以 36 个柱模板的格表就是「要写的方块」。中心模板不同：{@code tower_initial}
 * 把 13,589 个空气格也存进了 {@code blocks}，这些格子是**有语义的**（「这一格必须是空的」），由
 * {@link EndPodiumTemplate} 按 {@link Kind#SOLID} + {@code minecraft:air} 识别，不要在这里滤掉。
 */
final class PillarTemplate {

    /** 模板在 jar 里的唯一目录（{@code common/src/main/resources/structures/yung_end_island/}）。 */
    static final String DIRECTORY = "/structures/yung_end_island/";

    /** 模板名白名单；{@link #load(String)} 之外没有任何入口能读到别的文件。 */
    private static final Pattern NAME =
            Pattern.compile("pillar_(?:guarded|broken|initial|bottom)_[1-9]|tower_initial");

    /**
     * 官方处理器会替换掉的占位方块（{@code BetterSpikeFeature.PROCESSORS}），外加唯一的座位标记。
     * <p>
     * {@code ORANGE_TERRACOTTA}/{@code MAGENTA_TERRACOTTA}/{@code PURPLE_CONCRETE} 是模板自己的
     * 占位色块，{@code GRAY_CONCRETE} 是「这一格原本该是那个基岩」的标记；四者都**不允许**作为最终
     * 方块留在世界里——{@code PillarTemplateTest} 会逐个模板核对每个占位块都被认出来了。
     */
    enum Kind {
        /** 直接写出去（含中心模板的空气格：{@code SOLID} + {@code minecraft:air}）。 */
        SOLID,
        /** orange_terracotta：obsidian，30% crying_obsidian。 */
        ORANGE,
        /** magenta_terracotta：80% 空气，10% crying_obsidian，10% obsidian。 */
        MAGENTA,
        /** purple_concrete：obsidian。 */
        PURPLE,
        /**
         * gray_concrete：基岩标记。柱模板里它是「柱子最顶上的水晶座」（只有 broken/guarded 才有）；
         * 中心模板里它是 65 个「这一格必须是基岩」的标记（见 {@link EndPodiumTemplate}）。两处产出
         * 都是 {@code minecraft:bedrock}，但含义不同，所以中心模板不把它当成凳子。
         */
        SEAT
    }

    private static final String ORANGE = "minecraft:orange_terracotta";
    private static final String MAGENTA = "minecraft:magenta_terracotta";
    private static final String PURPLE = "minecraft:purple_concrete";
    private static final String GRAY = "minecraft:gray_concrete";

    private static final Map<String, PillarTemplate> LOADED = new ConcurrentHashMap<>();

    final String name;
    final int sizeX;
    final int sizeY;
    final int sizeZ;
    /** 每个 palette 下标的方块状态字符串（已按本实例的旋转量转好）。 */
    final String[] state;
    /** 每个 palette 下标的处理方式。 */
    final Kind[] kind;
    final int[] xs;
    final int[] ys;
    final int[] zs;
    final int[] paletteIndex;
    /** 四分之一圈（顺时针）的旋转量，0..3。 */
    final int quarterTurns;
    /** 本实例 palette 的原始状态（未旋转的那份在 {@code quarterTurns == 0} 的实例上）。 */
    private final List<State> palette;
    private final Map<Integer, PillarTemplate> rotations = new ConcurrentHashMap<>();

    private PillarTemplate(String name, int sizeX, int sizeY, int sizeZ, List<State> palette,
                           int[] xs, int[] ys, int[] zs, int[] paletteIndex, int quarterTurns) {
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.paletteIndex = paletteIndex;
        this.quarterTurns = quarterTurns;
        this.state = new String[palette.size()];
        this.kind = new Kind[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            state[i] = palette.get(i).asString();
            kind[i] = kindOf(palette.get(i).block());
        }
    }

    /** 格数（{@code blocks} 的条数；柱模板不含空气，中心模板含 13,589 个空气格）。 */
    int count() {
        return xs.length;
    }

    /** 第 {@code i} 个方块的本地 x（旋转后）。 */
    int x(int i) {
        return xs[i];
    }

    /** 第 {@code i} 个方块的本地 y（旋转不改变 y）。 */
    int y(int i) {
        return ys[i];
    }

    /** 第 {@code i} 个方块的本地 z（旋转后）。 */
    int z(int i) {
        return zs[i];
    }

    /** 第 {@code i} 个方块的处理方式。 */
    Kind kind(int i) {
        return kind[paletteIndex[i]];
    }

    /** 第 {@code i} 个方块要写的状态串（{@link Kind#SOLID} 时有效）。 */
    String state(int i) {
        return state[paletteIndex[i]];
    }

    /** 第 {@code i} 个方块的状态对象（已按本实例的旋转量转好）；测试用它核对旋转是否自洽。 */
    State stateOf(int i) {
        return palette.get(paletteIndex[i]);
    }

    /** palette 项数（启动预热时按 palette 解析，不必逐格）。 */
    int paletteSize() {
        return state.length;
    }

    /** 第 {@code i} 个 palette 项的状态串（已按本实例的旋转量转好）。 */
    String paletteState(int i) {
        return state[i];
    }

    /** 第 {@code i} 个 palette 项的处理方式。 */
    Kind paletteKind(int i) {
        return kind[i];
    }

    /**
     * 本模板顺时针旋转 {@code quarterTurns} 个 90° 后的版本（缓存，不可变）。
     *
     * <p>{@code Rotation.CLOCKWISE_90} 把 {@code (dx, dz)} 映成 {@code (-dz, dx)}，与
     * {@code BlockPos#rotate} 一致；支点取模板中心，所以正方形模板转完后坐标集合不变。
     */
    PillarTemplate rotated(int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0) {
            return this;
        }
        return rotations.computeIfAbsent(turns, this::rotate);
    }

    private PillarTemplate rotate(int turns) {
        int pivotX = sizeX / 2;
        int pivotZ = sizeZ / 2;
        int[] rx = new int[xs.length];
        int[] rz = new int[zs.length];
        for (int i = 0; i < xs.length; i++) {
            int dx = xs[i] - pivotX;
            int dz = zs[i] - pivotZ;
            for (int t = 0; t < turns; t++) {
                int next = dx;
                dx = -dz;
                dz = next;
            }
            rx[i] = pivotX + dx;
            rz[i] = pivotZ + dz;
        }
        // palette 只有十几个项，转动它比逐格转方块状态便宜得多，也保证同一 palette 下标永远对应
        // 同一个状态串（YungPillars 因此可以按 state 串缓存 Bukkit BlockData）。
        List<State> rotatedPalette = new ArrayList<>(palette.size());
        for (State raw : palette) {
            rotatedPalette.add(raw.rotated(turns));
        }
        return new PillarTemplate(name, sizeX, sizeY, sizeZ, List.copyOf(rotatedPalette),
                rx, ys.clone(), rz, paletteIndex.clone(), turns);
    }

    /**
     * 从 jar 内固定目录读一个模板（按名字缓存）。名字不在白名单里就抛
     * {@link IllegalArgumentException}，文件缺失抛 {@link IllegalStateException}：模板是世界的组成
     * 部分，缺一个必须立刻暴露，不能静默跳过一根柱子。
     */
    static PillarTemplate load(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("不是柱模板名: " + name);
        }
        PillarTemplate template = LOADED.get(name);
        if (template != null) {
            return template;
        }
        return LOADED.computeIfAbsent(name, PillarTemplate::read);
    }

    private static PillarTemplate read(String name) {
        String resource = DIRECTORY + name + ".nbt";
        try (InputStream in = PillarTemplate.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("jar 内缺少柱模板 " + resource);
            }
            StructureNbt nbt = StructureNbt.read(in);
            if (nbt.sizeX != nbt.sizeZ || (nbt.sizeX & 1) == 0) {
                throw new IllegalStateException(name + " 的水平尺寸必须是相等的奇数（上游支点固定为 9）: "
                        + nbt.sizeX + "x" + nbt.sizeZ);
            }
            List<State> palette = new ArrayList<>(nbt.paletteNames.size());
            for (int i = 0; i < nbt.paletteNames.size(); i++) {
                palette.add(new State(nbt.paletteNames.get(i), nbt.paletteProperties.get(i)));
            }
            return new PillarTemplate(name, nbt.sizeX, nbt.sizeY, nbt.sizeZ, List.copyOf(palette),
                    nbt.xs, nbt.ys, nbt.zs, nbt.states, 0);
        } catch (IOException e) {
            throw new IllegalStateException("读不了柱模板 " + resource, e);
        }
    }

    private static Kind kindOf(String block) {
        return switch (block) {
            case ORANGE -> Kind.ORANGE;
            case MAGENTA -> Kind.MAGENTA;
            case PURPLE -> Kind.PURPLE;
            case GRAY -> Kind.SEAT;
            default -> Kind.SOLID;
        };
    }

    /**
     * 一个方块状态：id + 属性表。
     *
     * <p>{@link #rotated(int)} 只承认 Minecraft 自己会随旋转改变的属性，其余属性分成两类：明确不动的
     * （{@link #INVARIANT}）和**未知**的。未知属性直接抛 {@link IllegalArgumentException}——宁可让新模板
     * 加进来时报错，也不要把它悄悄按原样贴下去（那会得到一堵连错方向的墙）。
     */
    record State(String block, Map<String, String> properties) {

        private static final String[] HORIZONTAL = {"north", "east", "south", "west"};

        /**
         * 旋转不会改变的属性名。{@code type} 是台阶的上/下/双层，{@code half} 是楼梯上下半，
         * {@code shape} 由邻居决定（Minecraft 的 {@code StairBlock#rotate} 也只转 facing）。
         */
        private static final java.util.Set<String> INVARIANT = java.util.Set.of(
                "up", "waterlogged", "type", "half", "shape", "open", "powered", "lit",
                "persistent", "distance", "snowy", "conditional", "berries", "age", "level",
                "stage", "occupied", "part", "unstable", "attached", "disarmed", "extended",
                "in_wall", "eye", "east_up", "west_up", "north_up", "south_up");

        String asString() {
            if (properties.isEmpty()) {
                return block;
            }
            StringBuilder out = new StringBuilder(block).append('[');
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return out.append(']').toString();
        }

        /** 顺时针旋转 {@code quarterTurns} 个 90° 后的状态（{@code BlockState#rotate} 的等价物）。 */
        State rotated(int quarterTurns) {
            int turns = Math.floorMod(quarterTurns, 4);
            if (turns == 0 || properties.isEmpty()) {
                return this;
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                int direction = horizontalIndex(key);
                if (direction >= 0) {
                    out.put(HORIZONTAL[(direction + turns) & 3], value);
                    continue;
                }
                switch (key) {
                    case "facing" -> out.put(key, rotateDirection(value, turns));
                    case "axis" -> out.put(key, rotateAxis(value, turns));
                    case "rotation" -> out.put(key, Integer.toString(
                            (Integer.parseInt(value) + 4 * turns) & 15));
                    case "hinge" -> out.put(key, (turns & 1) == 0 ? value : swapHinge(value));
                    default -> {
                        if (!INVARIANT.contains(key)) {
                            throw new IllegalArgumentException(
                                    "柱模板旋转不支持属性 " + key + "=" + value + "（" + block + "）");
                        }
                        out.put(key, value);
                    }
                }
            }
            return new State(block, Collections.unmodifiableMap(out));
        }

        private static int horizontalIndex(String key) {
            for (int i = 0; i < HORIZONTAL.length; i++) {
                if (HORIZONTAL[i].equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        private static String rotateDirection(String value, int turns) {
            if ("up".equals(value) || "down".equals(value)) {
                return value;
            }
            int index = horizontalIndex(value);
            if (index < 0) {
                throw new IllegalArgumentException("不是方向值: " + value);
            }
            return HORIZONTAL[(index + turns) & 3];
        }

        private static String rotateAxis(String value, int turns) {
            if ((turns & 1) == 0 || "y".equals(value)) {
                return value;
            }
            return switch (value) {
                case "x" -> "z";
                case "z" -> "x";
                default -> throw new IllegalArgumentException("不是轴向值: " + value);
            };
        }

        private static String swapHinge(String value) {
            return switch (value) {
                case "left" -> "right";
                case "right" -> "left";
                default -> throw new IllegalArgumentException("不是铰链值: " + value);
            };
        }
    }
}
