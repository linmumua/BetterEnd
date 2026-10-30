package dev.mumu.betterend.vanilla;

import java.util.HashMap;
import java.util.Map;

/**
 * 末影龙撞墙拆方块时，仅保护受管模板实心格，且当前方块类型必须仍与模板一致。
 *
 * <p>MU 26.3 的 EnderDragon#checkWalls 经 EntityExplodeEvent 拆除未免疫的末地石砖、
 * 楼梯、台阶、墙和钟。调用方只过滤模板坐标，不取消事件、不改全局标签或 gamerule，
 * 不限制玩家拆放，也不重建建筑。保护区和模板空气格不进入名单。
 *
 * <p>按方块类型而非完整状态比较：墙连接、楼梯 shape 和钟的 powered 会自动变化，
 * 不应因此失去保护。玩家换成其它方块类型或拆成空气后不再保护；同坐标换回同类型
 * 仍受保护。这不是首次施工的所有权判定，首次预检依然只接受天然地形。
 *
 * <p>本类不读世界。调用方负责限定受管世界/施工或完成阶段，并在读取当前方块前
 * 检查 Folia 区域归属。
 */
final class EndPodiumShield {

    private EndPodiumShield() {
    }

    /**
     * 方块坐标打包成 {@code long}，与 Minecraft 自己的 {@code BlockPos#asLong} 同一套位宽
     * （x 高 26 位、z 中 26 位、y 低 12 位）。只用做 map 的键，不需要反解。
     */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * 从计划里取保护名单：坐标 → 目标状态串。只收实心格（模板空气格与保护区都不进名单）。
     */
    static Map<Long, String> of(EndPodiumTemplate.Plan plan) {
        Map<Long, String> shield = new HashMap<>(plan.writes().size());
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            if (cell.air()) {
                continue;
            }
            shield.put(pack(cell.x(), cell.y(), cell.z()), cell.state());
        }
        return Map.copyOf(shield);
    }

    /**
     * 这一格要不要从龙的破坏列表里剔除。
     *
     * @param currentState 这一格**当前**的方块状态串（调用方必须在自己的区域线程上读）
     */
    static boolean shields(Map<Long, String> shield, int x, int y, int z, String currentState) {
        String wanted = shield.get(pack(x, y, z));
        // 墙连接、楼梯 shape、钟的 powered 会因邻居/红石自行变化，不代表玩家替换了方块。
        return wanted != null && EndPodiumTemplate.blockId(wanted)
                .equals(EndPodiumTemplate.blockId(currentState));
    }
}
