package dev.mumu.betterend.vanilla;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 一个 Minecraft 结构方块 NBT（{@code .nbt}）文件的最小读取器：只取 {@code size}、{@code palette} /
 * {@code palettes} 与 {@code blocks}，其余标签按 TAG 规范跳过。
 *
 * <h2>为什么自己解析</h2>
 * 运行期没有 NMS（{@code nms} 模块已删，见 {@code settings.gradle.kts}），服务端也没有暴露
 * {@code StructureTemplate}；而模板是 36 个 gzip NBT。方案是只用 JDK 的
 * {@link GZIPInputStream} + {@link DataInputStream}：NBT 的结构足够小（定长大端数值 + u16 长度前缀），
 * 一个 150 行的读取器就够，且**不引入任何外部依赖**、不碰 NMS、不进世界。
 *
 * <h2>安全</h2>
 * <ul>
 *   <li>只接受调用方给的 {@link InputStream}（模板从 jar 内 {@code getResourceAsStream} 打开），
 *       本类不认路径，也没有任何「按名字拼路径」的入口——见 {@link PillarTemplate#load(String)}
 *       对模板名的白名单校验。</li>
 *   <li>每个长度/尺寸都带上限（{@link #MAX_ELEMENTS}、{@link #MAX_SIDE}）：损坏或恶意的文件抛
 *       {@link IOException}，不会先分配几个 GB。</li>
 *   <li>坐标、palette 下标一律校验，越界抛异常，不做「静默跳过」。</li>
 * </ul>
 *
 * <p>纯数据、无状态：一次读取得到的对象不可变，可跨线程共享。
 */
final class StructureNbt {

    /** 结构最大的水平边长；模板是 19，给到 63 已经很宽。 */
    static final int MAX_SIDE = 63;
    /** 结构最大高度；模板最高 69。 */
    static final int MAX_HEIGHT = 512;
    /** 任意列表 / 数组的元素上限（25k 格的实际用量远小于此）。 */
    private static final int MAX_ELEMENTS = 1 << 21;

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    final int sizeX;
    final int sizeY;
    final int sizeZ;
    /** palette 每一项的方块 id（例如 {@code minecraft:obsidian}），顺序即 NBT 里的 state 下标。 */
    final List<String> paletteNames;
    /** 与 {@link #paletteNames} 一一对应的方块属性；没有属性的项是空表。 */
    final List<Map<String, String>> paletteProperties;
    /** 非空气方块的位置（模板 NBT 不存空气，见 {@link PillarTemplate} 的说明）。 */
    final int[] xs;
    final int[] ys;
    final int[] zs;
    /** 每个方块对应的 palette 下标。 */
    final int[] states;

    private StructureNbt(int sizeX, int sizeY, int sizeZ, List<String> paletteNames,
                         List<Map<String, String>> paletteProperties,
                         int[] xs, int[] ys, int[] zs, int[] states) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.paletteNames = paletteNames;
        this.paletteProperties = paletteProperties;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.states = states;
    }

    /** 方块数（非空气）。 */
    int count() {
        return xs.length;
    }

    /**
     * 读取一个 gzip 压缩的结构文件。传入的流由本方法关闭。
     *
     * @throws IOException 不是合法的 gzip NBT，或缺 {@code size}/{@code palette}/{@code blocks}，
     *                     或坐标 / palette 下标越界
     */
    static StructureNbt read(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            int type = in.readUnsignedByte();
            if (type != TAG_COMPOUND) {
                throw new IOException("结构根标签不是 compound，而是 TAG " + type);
            }
            readString(in); // 根标签的名字，结构文件里是空串
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) readPayload(in, type);
            return extract(root);
        }
    }

    // ------------------------------------------------------------------ 提取

    private static StructureNbt extract(Map<String, Object> root) throws IOException {
        List<Object> size = asList(root.get("size"), "size");
        if (size.size() != 3) {
            throw new IOException("size 不是三元列表: " + size);
        }
        int sizeX = asInt(size.get(0), "size[0]");
        int sizeY = asInt(size.get(1), "size[1]");
        int sizeZ = asInt(size.get(2), "size[2]");
        if (sizeX < 1 || sizeX > MAX_SIDE || sizeZ < 1 || sizeZ > MAX_SIDE
                || sizeY < 1 || sizeY > MAX_HEIGHT) {
            throw new IOException("模板尺寸越界: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }

        // 单 palette 用 "palette"，多 palette 用 "palettes"；结构方块只写前者，这里两种都认。
        List<Object> palette = root.containsKey("palette")
                ? asList(root.get("palette"), "palette")
                : asList(first(asList(root.get("palettes"), "palettes"), "palettes[0]"), "palettes[0]");
        List<String> names = new ArrayList<>(palette.size());
        List<Map<String, String>> properties = new ArrayList<>(palette.size());
        for (Object entry : palette) {
            Map<String, Object> state = asCompound(entry, "palette 项");
            names.add(asString(state.get("Name"), "palette.Name"));
            Map<String, String> props = new LinkedHashMap<>();
            Object rawProps = state.get("Properties");
            if (rawProps != null) {
                for (Map.Entry<String, Object> e : asCompound(rawProps, "Properties").entrySet()) {
                    props.put(e.getKey(), asString(e.getValue(), "Properties." + e.getKey()));
                }
            }
            properties.add(Collections.unmodifiableMap(props));
        }
        if (names.isEmpty()) {
            throw new IOException("模板 palette 为空");
        }

        List<Object> blocks = asList(root.get("blocks"), "blocks");
        int[] xs = new int[blocks.size()];
        int[] ys = new int[blocks.size()];
        int[] zs = new int[blocks.size()];
        int[] states = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            Map<String, Object> block = asCompound(blocks.get(i), "blocks 项");
            List<Object> pos = asList(block.get("pos"), "pos");
            if (pos.size() != 3) {
                throw new IOException("pos 不是三元列表: " + pos);
            }
            int x = asInt(pos.get(0), "pos[0]");
            int y = asInt(pos.get(1), "pos[1]");
            int z = asInt(pos.get(2), "pos[2]");
            int state = asInt(block.get("state"), "state");
            if (x < 0 || x >= sizeX || z < 0 || z >= sizeZ || y < 0 || y >= sizeY) {
                throw new IOException("方块坐标越界: " + x + "," + y + "," + z + " 不在 "
                        + sizeX + "x" + sizeY + "x" + sizeZ + " 内");
            }
            if (state < 0 || state >= names.size()) {
                throw new IOException("palette 下标越界: " + state + " / " + names.size());
            }
            xs[i] = x;
            ys[i] = y;
            zs[i] = z;
            states[i] = state;
        }
        return new StructureNbt(sizeX, sizeY, sizeZ,
                Collections.unmodifiableList(names), Collections.unmodifiableList(properties),
                xs, ys, zs, states);
    }

    private static Object first(List<Object> list, String what) throws IOException {
        if (list.isEmpty()) {
            throw new IOException(what + " 为空");
        }
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value, String what) throws IOException {
        if (!(value instanceof List)) {
            throw new IOException(what + " 不是列表: " + value);
        }
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asCompound(Object value, String what) throws IOException {
        if (!(value instanceof Map)) {
            throw new IOException(what + " 不是 compound: " + value);
        }
        return (Map<String, Object>) value;
    }

    private static int asInt(Object value, String what) throws IOException {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Short || value instanceof Byte) {
            return ((Number) value).intValue();
        }
        throw new IOException(what + " 不是整数: " + value);
    }

    private static String asString(Object value, String what) throws IOException {
        if (!(value instanceof String text)) {
            throw new IOException(what + " 不是字符串: " + value);
        }
        return text;
    }

    // ------------------------------------------------------------------ NBT 原语

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 按 TAG 类型读一个 payload；只保留结构文件用得到的类型，其余按规范跳过。 */
    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_END:
                return null;
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                int length = checkLength(in.readInt(), "byte[]");
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                return bytes;
            }
            case TAG_STRING:
                return readString(in);
            case TAG_LIST: {
                int elementType = in.readUnsignedByte();
                int length = checkLength(in.readInt(), "list");
                List<Object> list = new ArrayList<>(Math.min(length, 4096));
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType));
                }
                return list;
            }
            case TAG_COMPOUND: {
                Map<String, Object> compound = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == TAG_END) {
                        return compound;
                    }
                    String name = readString(in);
                    compound.put(name, readPayload(in, childType));
                }
            }
            case TAG_INT_ARRAY: {
                int length = checkLength(in.readInt(), "int[]");
                int[] values = new int[length];
                for (int i = 0; i < length; i++) {
                    values[i] = in.readInt();
                }
                return values;
            }
            case TAG_LONG_ARRAY: {
                int length = checkLength(in.readInt(), "long[]");
                long[] values = new long[length];
                for (int i = 0; i < length; i++) {
                    values[i] = in.readLong();
                }
                return values;
            }
            default:
                throw new IOException("未知 NBT 标签类型: " + type);
        }
    }

    private static int checkLength(int length, String what) throws IOException {
        if (length < 0 || length > MAX_ELEMENTS) {
            throw new IOException(what + " 长度越界: " + length);
        }
        return length;
    }
}
