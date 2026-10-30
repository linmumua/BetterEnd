package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndPodiumShell} 里那些不碰服务端就能测的决策：死亡分支、读不到位置后的回退、PDC 键的白名单。
 *
 * <p>这些判定都在生产路径上：写错了的后果分别是「龙死了也不建楼」「位置读不到就再也不试」
 * 「任意字符串写坏完成标记」，所以单独钉一次。世界、事件、调度仍然要靠实机验收，这里不假装覆盖了它们。
 */
class EndPodiumShellTest {

    /** 只有整楼已建成才做局部修复；其余阶段（含失败）一律延迟后再试一轮。 */
    @Test
    void dragonDeathRepairsOnlyOnceTheTowerIsPlaced() {
        for (EndPodiumShell.Stage stage : EndPodiumShell.Stage.values()) {
            EndPodiumShell.DeathAction expected = stage == EndPodiumShell.Stage.PLACED
                    ? EndPodiumShell.DeathAction.REPAIR
                    : EndPodiumShell.DeathAction.RETRY_LATER;
            assertEquals(expected, EndPodiumShell.deathAction(stage), "阶段 " + stage);
        }
    }

    /** 死亡后的延迟必须晚于原版死亡动画（约 200 tick 才进 setDragonKilled）。 */
    @Test
    void deathDelayWaitsForTheVanillaDeathAnimation() {
        assertTrue(EndPodiumShell.AFTER_DEATH_TICKS >= 200,
                "延迟 " + EndPodiumShell.AFTER_DEATH_TICKS + " tick 太早，那时原版还没重建祭坛/放龙蛋");
    }

    /**
     * 锚点不再依赖「等原版门坐标」：布局由纯地形场算出来，门是功能槽。阶段机因此只剩
     * IDLE / BUILDING / PLACED / FAILED 四档，且 {@code LOCATING} 这个「等门」档位必须真的消失
     * ——它留着就会有人把「读不到门」当成施工前置条件，正是把楼搬到屋顶的那条老路。
     */
    @Test
    void theOldWaitingForPortalStageIsGone() {
        assertEquals(4, EndPodiumShell.Stage.values().length);
        for (EndPodiumShell.Stage stage : EndPodiumShell.Stage.values()) {
            assertNotEquals("LOCATING", stage.name());
        }
    }

    /** 只有 IDLE 能被事件抢到：施工中/已完成/已中止都不重复开始。 */
    @Test
    void onlyIdleCanBeClaimedByAnEvent() {
        for (EndPodiumShell.Stage stage : EndPodiumShell.Stage.values()) {
            AtomicReference<EndPodiumShell.Stage> current = new AtomicReference<>(stage);
            boolean claimed = current.compareAndSet(EndPodiumShell.Stage.IDLE,
                    EndPodiumShell.Stage.BUILDING);
            assertEquals(stage == EndPodiumShell.Stage.IDLE, claimed, "阶段 " + stage);
            assertEquals(claimed ? EndPodiumShell.Stage.BUILDING : stage, current.get());
        }
    }

    /** PDC 键只认两个；未知键显式拒绝，别把任意字符串静默落到布局标记上。 */
    @Test
    void unknownPersistenceKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EndPodiumShell.markKey(null, "state-of-the-world"));
        assertThrows(IllegalArgumentException.class, () -> EndPodiumShell.markKey(null, ""));
    }

    /**
     * 读标记只走「原子文件 + 启动缓存」：这两个纯函数的签名里**没有 PDC 通道**，所以区域线程上
     * 不可能顺手读到世界 PDC（底层是无锁 HashMap，只允许全局线程碰）。这条把约定变成可编译期检查的
     * 事实，而不是注释。
     */
    @Test
    void markerReadsTakeOnlyFileAndStartupCache() {
        assertNull(EndPodiumShell.readStateMark(false, null), "既没文件也没缓存 = 没标记");
        assertNull(EndPodiumShell.readStateMark(false, Boolean.FALSE), "缓存 false 不算 placed");
        assertEquals(EndPodiumBuild.PLACED, EndPodiumShell.readStateMark(true, null), "文件在就算 placed");
        assertEquals(EndPodiumBuild.PLACED, EndPodiumShell.readStateMark(false, Boolean.TRUE),
                "启动缓存里的旧 placed 也算（读路径不碰 PDC）");

        assertNull(EndPodiumShell.readLayoutMark(null, null));
        assertNull(EndPodiumShell.readLayoutMark("   ", ""));
        assertEquals("v2:seed=1:gate=0,61,0",
                EndPodiumShell.readLayoutMark(" v2:seed=1:gate=0,61,0 ", null), "文件优先且去空白");
        assertEquals("v2:seed=1:gate=0,61,0",
                EndPodiumShell.readLayoutMark(null, "v2:seed=1:gate=0,61,0"), "没有文件才吃缓存");
        assertEquals("v2:seed=2:gate=0,63,0",
                EndPodiumShell.readLayoutMark("v2:seed=2:gate=0,63,0", "v2:seed=1:gate=0,61,0"),
                "文件与缓存不一致时以文件为准");
    }

    /**
     * 「placed 有、布局读不到/读不懂」= 旧版或损坏的现场，必须拒绝：不能当成新世界按地形重算一套锚点
     * 再写进文件（那会把已经交付的楼悄悄换成另一套坐标，还挂着 placed 冒充完成）。
     * 旧的 {@code state=placed} + {@code layout="x,y,z"} 正好命中这一条。
     */
    @Test
    void placedWithoutAUsableLayoutIsTreatedAsUnprovenLegacy() {
        String v2 = "v2:seed=1:gate=0,61,0";
        assertTrue(EndPodiumShell.legacyPlacedWithoutLayout(true, null), "placed 但没有布局");
        assertTrue(EndPodiumShell.legacyPlacedWithoutLayout(true, "  "), "placed 但布局是空白");
        assertTrue(EndPodiumShell.legacyPlacedWithoutLayout(true, "0,64,0"), "旧格式 x,y,z");
        assertTrue(EndPodiumShell.legacyPlacedWithoutLayout(true, "v1:seed=1:gate=0,64,0"), "v1 布局");
        assertFalse(EndPodiumShell.legacyPlacedWithoutLayout(true, v2), "placed + v2 布局是正常完成");
        assertFalse(EndPodiumShell.legacyPlacedWithoutLayout(false, null), "没 placed 就是新世界");
    }

    /**
     * 龙拆楼保护的门禁：只有 {@link EndPodiumShell.Stage#BUILDING} 与
     * {@link EndPodiumShell.Stage#PLACED} 才保护。
     *
     * <p>关键的是 {@link EndPodiumShell.Stage#FAILED}：预检因玩家方块中止之后，内存里仍然留着一份
     * 布局、名单也可能已经缓存过，所以「有名单就保护」会让玩家放在同坐标的方块免于龙拆。这里用的
     * {@code shieldsDragon(stage, shield, ...)} 就是 {@code onDragonExplode} 每次 {@code removeIf}
     * 调用的同一个方法，不是另写的副本。
     */
    @Test
    void dragonShieldAppliesOnlyWhileBuildingOrPlaced() {
        EndPodiumTemplate.Plan plan = EndPodiumTemplate.plan(0, 65, 0);
        EndPodiumTemplate.Cell cell = plan.writes().stream().filter(candidate -> !candidate.air())
                .findFirst().orElseThrow();
        Map<Long, String> shield = EndPodiumShield.of(plan);
        assertTrue(shield.size() > 1_000, "名单不该是空的，否则这条用例证明不了阶段门禁");

        for (EndPodiumShell.Stage stage : EndPodiumShell.Stage.values()) {
            boolean expected = stage == EndPodiumShell.Stage.BUILDING
                    || stage == EndPodiumShell.Stage.PLACED;
            assertEquals(expected, EndPodiumShell.shieldsDragon(stage), "阶段 " + stage);
            assertEquals(expected, EndPodiumShell.shieldsDragon(stage, shield,
                    cell.x(), cell.y(), cell.z(), cell.state()), "阶段 " + stage);
        }

        for (EndPodiumShell.Stage stage : List.of(EndPodiumShell.Stage.IDLE,
                EndPodiumShell.Stage.FAILED)) {
            assertFalse(EndPodiumShell.shieldsDragon(stage, shield,
                    cell.x(), cell.y(), cell.z(), cell.state()),
                    "阶段 " + stage + " 手里有名单也不许保护");
        }

        // 阶段没问题，但玩家把这格改掉了：仍然不保护。
        assertFalse(EndPodiumShell.shieldsDragon(EndPodiumShell.Stage.PLACED, shield,
                        cell.x(), cell.y(), cell.z(), "minecraft:air"),
                "玩家拆掉的格不该继续受保护");
    }
}
