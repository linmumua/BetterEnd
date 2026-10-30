package dev.mumu.betterend.terrain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.config.BetterEndConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地面结构台地。这里锁住的是「结构既不悬空、也不埋进地形、也不被地形切断」所依赖的几件事：
 * 占地矩形就是原版模板真正铺开的范围；整块占地同一高度；结构上方被清空、下方有地基；
 * 边缘平滑接回地形而不是一道断崖；从任何一个相交的区块看都是同一块台地。
 */
class StructurePlatformTest {
    private static final long[] SEEDS = {1L, 42L, 12345L, -7L, 999999L};
    private static final String STRUCTURES =
            "plugin/src/main/resources/datapacks/echoes-end-structures/data/";

    @Test
    void kindsMatchTheShippedTemplatesAndStructureSets() throws IOException {
        // 尺寸、盐、频率任何一项和数据包不一致，台地就会和结构对不上——直接读数据包核对。
        for (StructurePlatform.Kind kind : StructurePlatform.KINDS) {
            int[] size = nbtSize(resolve(STRUCTURES + "echoes_of_the_end__structures_/structure/"
                    + kind.name() + ".nbt"));
            assertEquals(kind.sizeX(), size[0], kind.name() + " size x");
            assertEquals(kind.sizeY(), size[1], kind.name() + " size y");
            assertEquals(kind.sizeZ(), size[2], kind.name() + " size z");
            JsonObject set = JsonParser.parseString(Files.readString(resolve(STRUCTURES
                    + "mumu/worldgen/structure_set/" + kind.name() + ".json"), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("placement");
            assertEquals(kind.salt(), set.get("salt").getAsInt(), kind.name() + " salt");
            float frequency = set.has("frequency") ? set.get("frequency").getAsFloat() : 1f;
            assertEquals(kind.frequency(), frequency, 1e-6, kind.name() + " frequency");
        }
    }

    @Test
    void footprintIsTheRotatedTemplateAnchoredAtTheChunkCorner() {
        // 原版模板从候选区块西北角绕原点旋转铺开：四种旋转的矩形都必须包含这个角，尺寸互换正确。
        StructurePlatform.Kind temple = kind("end_enchanted_temple"); // 33 x 47，最不对称
        int[] seen = new int[4];
        for (long seed : SEEDS) {
            for (int c = -30; c <= 30; c += 3) {
                int[] box = StructurePlatform.footprint(seed, temple, c, -c);
                int r = StructurePlatform.rotation(seed, c, -c);
                seen[r]++;
                int w = box[2] - box[0] + 1;
                int d = box[3] - box[1] + 1;
                boolean swapped = r == 1 || r == 3;
                assertEquals(swapped ? 47 : 33, w, "width for rotation " + r);
                assertEquals(swapped ? 33 : 47, d, "depth for rotation " + r);
                int ox = c << 4;
                int oz = (-c) << 4;
                assertTrue(box[0] <= ox && ox <= box[2] && box[1] <= oz && oz <= box[3],
                        "chunk corner must be a corner of the footprint");
            }
        }
        for (int r = 0; r < 4; r++) {
            assertTrue(seen[r] > 0, "rotation " + r + " never drawn: the RNG mirror is off");
        }
    }

    @Test
    void platformIsFlatClearedAboveAndFoundedBelow() {
        int checked = 0;
        for (long seed : SEEDS) {
            IslandField field = BiomePlacement.bind(seed, BetterEndConfig.DEFAULTS).field();
            for (StructurePlatform.Spot spot : someSpots(field, seed)) {
                for (int x = spot.minX(); x <= spot.maxX(); x++) {
                    for (int z = spot.minZ(); z <= spot.maxZ(); z++) {
                        double[] column = shaped(field, seed, x, z);
                        int top = spot.top();
                        assertTrue(column[top] > 0, "platform top must be solid at " + x + "," + z);
                        for (int y = top + 1; y <= top + spot.height(); y++) {
                            assertTrue(column[y] <= 0,
                                    "structure volume must be clear at " + x + "," + y + "," + z);
                        }
                        for (int y = top - StructurePlatform.FOUNDATION_CORE + 1; y <= top; y++) {
                            assertTrue(column[y] > 0, "foundation missing under " + x + "," + z);
                        }
                    }
                }
                checked++;
            }
        }
        assertTrue(checked > 0, "no platform found to check");
    }

    @Test
    void shapedColumnAgreesWithReportedSurface() {
        // getBaseHeight 与尖塔用 surfaceTop，generateNoise 用 shapeColumn：同一列两者必须一致，
        // 否则结构投影到的高度和实际写出的地形不是同一个。
        int checked = 0;
        for (long seed : SEEDS) {
            IslandField field = BiomePlacement.bind(seed, BetterEndConfig.DEFAULTS).field();
            for (StructurePlatform.Spot spot : someSpots(field, seed)) {
                int reach = StructurePlatform.MARGIN + StructurePlatform.SKIRT + 2;
                for (int x = spot.minX() - reach; x <= spot.maxX() + reach; x += 3) {
                    for (int z = spot.minZ() - reach; z <= spot.maxZ() + reach; z += 3) {
                        StructurePlatform.Spot[] spots = StructurePlatform.spots(
                                field, seed, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                        int reported = StructurePlatform.surfaceTop(spots, field, x, z);
                        int actual = topOf(shaped(field, seed, x, z),
                                StructurePlatform.ceilingAt(spots, x, z));
                        assertEquals(reported, actual, "surface mismatch at " + x + "," + z);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 0, "no column checked");
    }

    @Test
    void skirtHasNoCliff() {
        // 「被地形切断」的那道断崖：台地边缘到自然地形之间是坡，不是一面墙。smoothstep 在
        // SKIRT+1 格内走完高差 h，最陡处约 1.5h/(SKIRT+1)，再留 1 格取整余量。
        int checked = 0;
        for (long seed : SEEDS) {
            IslandField field = BiomePlacement.bind(seed, BetterEndConfig.DEFAULTS).field();
            for (StructurePlatform.Spot spot : someSpots(field, seed)) {
                int z = (spot.minZ() + spot.maxZ()) / 2;
                StructurePlatform.Spot[] spots = StructurePlatform.spots(
                        field, seed, Math.floorDiv(spot.maxX(), 16), Math.floorDiv(z, 16));
                int prev = StructurePlatform.surfaceTop(spots, field, spot.maxX(), z);
                for (int x = spot.maxX() + 1; x <= spot.maxX() + StructurePlatform.MARGIN
                        + StructurePlatform.SKIRT; x++) {
                    spots = StructurePlatform.spots(field, seed, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                    int top = StructurePlatform.surfaceTop(spots, field, x, z);
                    if (top == Integer.MIN_VALUE || prev == Integer.MIN_VALUE
                            || Math.abs(field.topY(x, z) - spot.top()) > 20) {
                        break; // 裙边落进虚空或悬崖本身太高，不在这条断言范围
                    }
                    // 自然地形本身的落差（悬崖）不归台地管：只要求裙边造成的落差不比
                    // 「自然落差 + smoothstep 的最大斜率」更陡。
                    int h = Math.abs(field.topY(x, z) - spot.top());
                    int naturalStep = Math.abs(field.topY(x, z) - field.topY(x - 1, z));
                    int bound = naturalStep
                            + Math.max(2, (int) Math.ceil(1.5 * h / (StructurePlatform.SKIRT + 1)) + 1);
                    assertTrue(Math.abs(top - prev) <= bound,
                            "skirt cliff of " + (top - prev) + " at " + x + "," + z + " (h=" + h + ")");
                    prev = top;
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "no skirt column checked");
    }

    @Test
    void everyIntersectingChunkSeesTheSamePlatform() {
        // 占地向西/北伸进上一格时，那几个区块也必须查到这块台地，否则台地被切成一半。
        for (long seed : SEEDS) {
            IslandField field = BiomePlacement.bind(seed, BetterEndConfig.DEFAULTS).field();
            for (StructurePlatform.Spot spot : someSpots(field, seed)) {
                for (int cx = Math.floorDiv(spot.minX(), 16); cx <= Math.floorDiv(spot.maxX(), 16); cx++) {
                    for (int cz = Math.floorDiv(spot.minZ(), 16); cz <= Math.floorDiv(spot.maxZ(), 16); cz++) {
                        boolean found = false;
                        for (StructurePlatform.Spot s : StructurePlatform.spots(field, seed, cx, cz)) {
                            found |= s.equals(spot);
                        }
                        assertTrue(found, "chunk " + cx + "," + cz + " does not see " + spot);
                    }
                }
            }
        }
    }

    @Test
    void theSpotIsAPureFunctionOfSeedAndChunk() {
        for (long seed : SEEDS) {
            IslandField a = BiomePlacement.bind(seed, BetterEndConfig.DEFAULTS).field();
            StructurePlatform.Spot[] first = StructurePlatform.spots(a, seed, 3, -5);
            StructurePlatform.clearCache();
            IslandField b = BiomePlacement.bind(seed, BetterEndConfig.DEFAULTS).field();
            StructurePlatform.Spot[] second = StructurePlatform.spots(b, seed, 3, -5);
            assertEquals(first.length, second.length, "seed " + seed);
            for (int i = 0; i < first.length; i++) {
                assertEquals(first[i], second[i], "seed " + seed + " spot " + i);
            }
        }
    }

    @Test
    void frequencyFilterMatchesTheVanillaRate() {
        // 地牢塔 frequency 0.1：通过率必须在 0.1 附近，证明复刻的是同一个随机流而不是全放行。
        StructurePlatform.Kind tower = kind("end_dungeon_tower");
        int pass = 0;
        int total = 0;
        for (int cx = -200; cx < 200; cx += 7) {
            for (int cz = -200; cz < 200; cz += 7) {
                total++;
                if (StructurePlatform.passesFrequency(42L, tower, cx, cz)) pass++;
            }
        }
        double rate = (double) pass / total;
        assertTrue(rate > 0.07 && rate < 0.13, "frequency rate " + rate);
        assertTrue(StructurePlatform.passesFrequency(42L, kind("end_temple"), 5, 5));
    }

    @Test
    void voidLandingsAreRejectedNotPlatformed() {
        // 中心在虚空、占地大半是虚空的落点：没有台地，并且由守卫取消，不会有半截悬空的建筑。
        int rejected = 0;
        for (long seed : SEEDS) {
            IslandField field = BiomePlacement.bind(seed, BetterEndConfig.DEFAULTS).field();
            for (StructurePlatform.Kind kind : StructurePlatform.KINDS) {
                if (kind.frequency() < 1f) continue;
                RandomSpreadGrid grid = new RandomSpreadGrid(160, 16, kind.salt());
                for (int cell = -3; cell <= 3; cell++) {
                    long c = grid.candidateChunk(seed, cell * 160, -cell * 160);
                    int cx = RandomSpreadGrid.unpackX(c);
                    int cz = RandomSpreadGrid.unpackZ(c);
                    int[] box = StructurePlatform.footprint(seed, kind, cx, cz);
                    int voids = 0;
                    int samples = 0;
                    for (int x = box[0]; x <= box[2]; x += 2) {
                        for (int z = box[1]; z <= box[3]; z += 2) {
                            samples++;
                            if (field.topY(x, z) == Integer.MIN_VALUE) voids++;
                        }
                    }
                    boolean rejects = StructurePlatform.rejects(field, seed, kind.name(), cx, cz);
                    if (voids == samples) {
                        assertTrue(rejects, kind.name() + " over pure void must be rejected");
                    }
                    if (voids == 0) {
                        assertTrue(!rejects, kind.name() + " on solid ground must not be rejected");
                    }
                    if (rejects) rejected++;
                }
            }
        }
        assertTrue(rejected > 0, "no void landing seen; the End is mostly void, the sample is wrong");
    }

    // ------------------------------------------------------------------ helpers

    private static StructurePlatform.Kind kind(String name) {
        for (StructurePlatform.Kind k : StructurePlatform.KINDS) {
            if (k.name().equals(name)) return k;
        }
        throw new AssertionError(name);
    }

    /** 种子附近几格里能找到的台地（去重）。 */
    private static java.util.List<StructurePlatform.Spot> someSpots(IslandField field, long seed) {
        java.util.LinkedHashSet<StructurePlatform.Spot> out = new java.util.LinkedHashSet<>();
        for (StructurePlatform.Kind kind : StructurePlatform.KINDS) {
            RandomSpreadGrid grid = new RandomSpreadGrid(160, 16, kind.salt());
            for (int cell = -2; cell <= 2 && out.size() < 12; cell++) {
                long c = grid.candidateChunk(seed, cell * 160, cell * 160);
                for (StructurePlatform.Spot s : StructurePlatform.spots(field, seed,
                        RandomSpreadGrid.unpackX(c), RandomSpreadGrid.unpackZ(c))) {
                    out.add(s);
                }
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /** 与 generateNoise 同一套：原始密度列 + 台地整形，索引即世界 Y（minY = 0）。 */
    private static double[] shaped(IslandField field, long seed, int x, int z) {
        double[] column = new double[256];
        field.densityColumnInto(column, x, z, 0, 255);
        StructurePlatform.shapeColumn(column, 0,
                StructurePlatform.spots(field, seed, Math.floorDiv(x, 16), Math.floorDiv(z, 16)), x, z);
        return column;
    }

    private static int topOf(double[] column, int ceiling) {
        for (int y = Math.min(column.length - 1, ceiling); y >= 0; y--) {
            if (column[y] > 0) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static Path resolve(String relative) {
        for (Path at = Path.of("").toAbsolutePath(); at != null; at = at.getParent()) {
            Path probe = at.resolve(relative);
            if (Files.isRegularFile(probe)) return probe;
        }
        throw new AssertionError(relative + " not found");
    }

    /** 只读模板根 compound 的 {@code size}（TAG_List of TAG_Int）。 */
    private static int[] nbtSize(Path file) throws IOException {
        byte[] raw;
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            raw = in.readAllBytes();
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        assertEquals(10, in.readByte(), "root must be a compound");
        in.readUTF();
        int[] size = readSize(in);
        assertNotNull(size, "template has no size tag");
        return size;
    }

    private static int[] readSize(DataInputStream in) throws IOException {
        while (true) {
            byte type = in.readByte();
            if (type == 0) return null;
            String name = in.readUTF();
            if (type == 9 && name.equals("size")) {
                in.readByte();
                int n = in.readInt();
                int[] out = new int[n];
                for (int i = 0; i < n; i++) out[i] = in.readInt();
                return out;
            }
            skip(in, type);
        }
    }

    private static void skip(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1 -> in.skipBytes(1);
            case 2 -> in.skipBytes(2);
            case 3, 5 -> in.skipBytes(4);
            case 4, 6 -> in.skipBytes(8);
            case 7 -> in.skipBytes(in.readInt());
            case 8 -> in.readUTF();
            case 9 -> {
                byte et = in.readByte();
                int n = in.readInt();
                for (int i = 0; i < n; i++) skip(in, et);
            }
            case 10 -> {
                while (true) {
                    byte t = in.readByte();
                    if (t == 0) break;
                    in.readUTF();
                    skip(in, t);
                }
            }
            case 11 -> in.skipBytes(4 * in.readInt());
            case 12 -> in.skipBytes(8 * in.readInt());
            default -> throw new IOException("bad tag " + type);
        }
    }
}
