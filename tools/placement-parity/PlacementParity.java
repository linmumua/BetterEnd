import dev.mumu.betterend.terrain.RandomSpreadGrid;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

/**
 * 把 {@link RandomSpreadGrid} 与服务端真实的 {@code RandomSpreadStructurePlacement} 逐点对拍。
 *
 * <p>台地方案的全部正确性都压在这一步上：如果本地镜像与 vanilla 的落点算出不同的区块，台地就会
 * 出现在结构不会生成的地方，而被埋的结构一个都不会少。所以这里不比对「差不多」，只比对相等。
 *
 * <p>用法（工作区根目录）：
 * <pre>
 * javac -cp "common/build/classes/java/main;E:/我的世界插件/测试服/versions/26.3/paper-26.3.jar" \
 *       -d build/parity tools/placement-parity/PlacementParity.java
 * java  -cp "build/parity;common/build/classes/java/main;E:/我的世界插件/测试服/versions/26.3/paper-26.3.jar" \
 *       PlacementParity
 * </pre>
 */
public final class PlacementParity {
    /* 九个 Echoes 结构的 placement：spacing/separation 全部 160/16，盐各不相同 */
    private static final int SPACING = 160;
    private static final int SEPARATION = 16;

    /* 与 datapack 的 structure_set 一一对应 */
    private static final int[] SALTS = {
            1275595292, 910165693, 1054309675, 479948289, 1068651703,
            1322633544, 1143842385, 1574654080, 250943603
    };

    /* DnT 飞船：spacing 40 / separation 10，salt 79584390（城堡是 concentric_rings，不是网格） */
    private static final int SHIP_SPACING = 40;
    private static final int SHIP_SEPARATION = 10;
    private static final int SHIP_SALT = 79584390;

    public static void main(String[] args) {
        // StructurePlacement's static initialiser registers defaults into BuiltInRegistries, which
        // refuses to run before the datafixer/bootstrap tables exist. The version must be pinned
        // first or DataFixers throws "Game version not set" -- this is what CraftServer does at boot.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        long[] seeds = {0L, 1L, -1L, 42L, 123456789L, -9876543210123L, Long.MIN_VALUE, Long.MAX_VALUE};
        int[] coords = {0, 1, -1, 15, 16, -16, 159, 160, -160, 1000, -1000, 123456, -123456};
        int mismatches = 0;
        int checked = 0;

        for (int salt : SALTS) {
            mismatches += compare(SPACING, SEPARATION, salt, seeds, coords);
            checked += seeds.length * coords.length * coords.length;
        }
        mismatches += compare(SHIP_SPACING, SHIP_SEPARATION, SHIP_SALT, seeds, coords);
        checked += seeds.length * coords.length * coords.length;

        System.out.println("checked=" + checked + " mismatches=" + mismatches);
        System.out.flush();
        // Paper's bootstrap redirects System.out, so the verdict also goes to a file the caller reads.
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/parity/verdict.txt"),
                    "checked=" + checked + " mismatches=" + mismatches
                            + (mismatches == 0 ? " RESULT: MATCH" : " RESULT: MISMATCH"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (mismatches != 0) {
            System.out.println("RESULT: MISMATCH");
            System.exit(1);
        }
        System.out.println("RESULT: MATCH -- 本地镜像与 vanilla 落点完全一致");
    }

    private static int compare(int spacing, int separation, int salt, long[] seeds, int[] coords) {
        RandomSpreadGrid mirror = new RandomSpreadGrid(spacing, separation, salt);
        RandomSpreadStructurePlacement vanilla = new RandomSpreadStructurePlacement(
                spacing, separation, RandomSpreadType.LINEAR, salt);
        int mismatches = 0;
        for (long seed : seeds) {
            for (int chunkX : coords) {
                for (int chunkZ : coords) {
                    ChunkPos expected = vanilla.getPotentialStructureChunk(seed, chunkX, chunkZ);
                    long actual = mirror.candidateChunk(seed, chunkX, chunkZ);
                    if (expected.x() != RandomSpreadGrid.unpackX(actual)
                            || expected.z() != RandomSpreadGrid.unpackZ(actual)) {
                        if (mismatches < 5) {
                            System.out.println("mismatch salt=" + salt + " seed=" + seed
                                    + " chunk=(" + chunkX + "," + chunkZ + ") vanilla=("
                                    + expected.x() + "," + expected.z() + ") mirror=("
                                    + RandomSpreadGrid.unpackX(actual) + ","
                                    + RandomSpreadGrid.unpackZ(actual) + ")");
                        }
                        mismatches++;
                    }
                }
            }
        }
        return mismatches;
    }
}
