package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndPodiumShield}：末影龙撞墙时的保护名单。
 *
 * <p>这一组钉的是「谁该被保护、谁绝不该」，因为多保护一格就是替玩家挡了它本来有权拆的方块：
 * <ul>
 *   <li>只保护模板实心格；模板的空气格（门窗净空）永远不保护；</li>
 *   <li>只保护当前方块类型仍与模板相同的格：换成其它类型/拆成空气后不再保护；</li>
 *   <li>保护区（返回门/基岩柱/龙蛋）本来就不在名单里；</li>
 *   <li>名单外的坐标（别的柱子、玩家建筑）一概不碰。</li>
 * </ul>
 */
class EndPodiumShieldTest {

    private static final int PORTAL_X = 0;
    private static final int PORTAL_Y = 65;
    private static final int PORTAL_Z = 0;

    private final EndPodiumTemplate.Plan plan =
            EndPodiumTemplate.plan(PORTAL_X, PORTAL_Y, PORTAL_Z);
    private final Map<Long, String> shield = EndPodiumShield.of(plan);

    @Test
    void shieldHoldsExactlyTheSolidTemplateCells() {
        long solid = plan.writes().stream().filter(cell -> !cell.air()).count();
        assertEquals(solid, shield.size(), "名单应当一格不多一格不少地覆盖模板实心格");
        assertTrue(solid > 1_000, "29×27×29 的塔实心格不该这么少：" + solid);
        // 名单里不该出现模板的空气格
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            if (!cell.air()) {
                continue;
            }
            assertFalse(shield.containsKey(EndPodiumShield.pack(cell.x(), cell.y(), cell.z())),
                    "模板空气格不该进名单：" + cell.x() + "," + cell.y() + "," + cell.z());
        }
    }

    @Test
    void aCellThatStillMatchesTheTemplateIsShielded() {
        EndPodiumTemplate.Cell cell = solidCell();
        assertTrue(EndPodiumShield.shields(shield, cell.x(), cell.y(), cell.z(), cell.state()),
                "按模板放好的格必须受保护");
    }

    @Test
    void aCellThePlayerChangedIsNotShielded() {
        EndPodiumTemplate.Cell cell = solidCell();
        assertFalse(EndPodiumShield.shields(shield, cell.x(), cell.y(), cell.z(), "minecraft:air"),
                "玩家拆成空气的格不该再保护");
        assertFalse(EndPodiumShield.shields(shield, cell.x(), cell.y(), cell.z(), "minecraft:oak_planks"),
                "玩家换成别的方块的格不该再保护");
    }

    @Test
    void neighborAndRedstoneStateChangesDoNotDropDragonProtection() {
        Map<String, String> changed = Map.of(
                "minecraft:end_stone_brick_wall",
                "minecraft:end_stone_brick_wall[east=low,north=none,south=tall,up=true,waterlogged=false,west=none]",
                "minecraft:end_stone_brick_stairs",
                "minecraft:end_stone_brick_stairs[facing=north,half=bottom,shape=outer_left,waterlogged=false]",
                "minecraft:bell", "minecraft:bell[attachment=ceiling,facing=south,powered=true]");
        for (Map.Entry<String, String> entry : changed.entrySet()) {
            EndPodiumTemplate.Cell cell = plan.writes().stream()
                    .filter(value -> EndPodiumTemplate.blockId(value.state()).equals(entry.getKey()))
                    .findFirst().orElseThrow();
            assertTrue(EndPodiumShield.shields(shield, cell.x(), cell.y(), cell.z(), entry.getValue()),
                    "邻居连接或红石改变状态不该让龙吃掉模板方块：" + entry.getKey());
            assertFalse(EndPodiumShield.shields(shield, cell.x(), cell.y(), cell.z(), "minecraft:oak_planks"),
                    "保护仍不得扩展到玩家换入的其它方块类型");
        }
    }

    @Test
    void templateAirCellsAreNeverShielded() {
        EndPodiumTemplate.Cell air = plan.writes().stream().filter(EndPodiumTemplate.Cell::air)
                .findFirst().orElseThrow();
        assertFalse(EndPodiumShield.shields(shield, air.x(), air.y(), air.z(), "minecraft:air"),
                "门窗净空不该被保护");
    }

    @Test
    void theProtectionZoneIsNeverShielded() {
        // 返回门坐标上的基岩柱：本插件从不写它，也就不该替原版挡龙。
        for (int dy = 0; dy < EndPodiumBuild.PILLAR_HEIGHT; dy++) {
            assertFalse(EndPodiumShield.shields(shield, PORTAL_X, PORTAL_Y + dy, PORTAL_Z,
                    "minecraft:bedrock"), "保护区的基岩柱不该进名单");
        }
    }

    @Test
    void coordinatesOutsideThePlanAreNotShielded() {
        assertFalse(EndPodiumShield.shields(shield, 1_000, 65, 1_000, "minecraft:end_stone_bricks"));
        assertFalse(EndPodiumShield.shields(shield, PORTAL_X, 200, PORTAL_Z, "minecraft:end_stone_bricks"));
    }

    @Test
    void packingKeepsDistinctCellsApart() {
        Map<Long, String> seen = new HashMap<>();
        for (EndPodiumTemplate.Cell cell : plan.writes()) {
            long key = EndPodiumShield.pack(cell.x(), cell.y(), cell.z());
            String previous = seen.put(key, cell.x() + "," + cell.y() + "," + cell.z());
            assertEquals(null, previous, "坐标打包撞车了：" + cell.x() + "," + cell.y() + "," + cell.z());
        }
    }

    private EndPodiumTemplate.Cell solidCell() {
        return plan.writes().stream().filter(cell -> !cell.air()).findFirst().orElseThrow();
    }
}
