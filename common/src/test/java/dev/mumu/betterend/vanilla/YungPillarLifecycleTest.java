package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.terrain.IslandField;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link YungPillarLifecycle} 生命周期回归：让**生产编排真实入口**
 * {@link YungPillarLifecycle#verifyAndMaybeClaim} 与 {@link YungPillarLifecycle#retryCrystal} 跑完整链路
 * （无 built → 模板核验 → 落盘 built → 认领登记 → 分桶重铺 → 传送 → 位置/净空确认 → 完成）。
 *
 * <p>做法是给生命周期注入一个**同步 {@link YungPillarLifecycle.Runtime}**（生产实现走 Bukkit，测试实现立即执行），
 * 再用 {@link Proxy} 伪造 {@code World}/{@code Block}/{@code EnderCrystal}，登记表/快照走真实的
 * {@link YungPillarStore}（临时目录）。**没有**在测试里手动 {@code markBuilt} 或手动写登记表：这些都由生产
 * {@code verifyAndMaybeClaim}/{@code reconcile} 自己完成，所以旧死锁一旦回归，这些用例会红。
 *
 * <p>未覆盖（需真实 Folia 服务端）：真正的跨线程区域迁移、实体 owner 变更、NMS 桥接真实标记。
 */
class YungPillarLifecycleTest {

    private static final long SEED = 424242L;
    private static final UUID WORLD_ID = new UUID(0x5151L, 0x2323L);

    // ------------------------------------------------------------------ 生产编排：happy path

    /**
     * 无 built + 完整模板：真实编排读模板 → 落盘 built → 认领官方晶 → 重铺 → 传送 → 确认 → 完成。
     * 这是修复「复活吞柱 / 水晶埋柱」主链路的核心回归。
     */
    @Test
    void noBuiltCompleteTemplateIsClaimedRebuiltAndCrystalMoved() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.populateFullTemplate();
            EndSpikes.Spike spike = fixture.spike;
            YungPillars.Layout layout = YungPillars.layout(spike, SEED);

            fixture.lifecycle.verifyAndMaybeClaim(fixture.world, fixture.run, 0, "测试");

            // 晶体被搬到新槽（同一颗实体），登记表落盘 built。
            assertEquals(spike.centerX(), fixture.crystal.location.getBlockX(), "晶体 X 应到柱心");
            assertEquals(spike.centerZ(), fixture.crystal.location.getBlockZ(), "晶体 Z 应到柱心");
            assertEquals(layout.crystalY(), fixture.crystal.location.getBlockY(), "晶体 Y 应到新晶座");
            assertTrue(fixture.registryBuilt(0), "完整模板应落盘 built");
            assertEquals(fixture.crystal.uuid, fixture.registryCrystal(0), "应登记同一颗官方晶");
            assertTrue(fixture.run.pending.isEmpty() && fixture.run.inFlight.isEmpty(),
                    "完成后不应残留 pending/inFlight");
        } finally {
            fixture.close();
        }
    }

    /** 首次回读撞上未写完（一格暂时是空气），有限重试后补全 → 仍成功落盘并搬晶。 */
    @Test
    void firstReadbackTemporarilyFailsThenSucceeds() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.populateFullTemplate();
            YungPillars.Cell victim = fixture.firstSolidTemplateCell();
            fixture.crippleOnce(victim.key());

            fixture.lifecycle.verifyAndMaybeClaim(fixture.world, fixture.run, 0, "测试");

            assertTrue(fixture.registryBuilt(0), "首败后有限重试应补全并落盘 built");
            YungPillars.Layout layout = YungPillars.layout(fixture.spike, SEED);
            assertEquals(layout.crystalY(), fixture.crystal.location.getBlockY(),
                    "重试成功后晶体应搬到新晶座");
        } finally {
            fixture.close();
        }
    }

    /** 传送失败：不假装成功；晶体留在原处，按 {@link YungPillarLifecycle#MAX_ATTEMPTS} 有界重试后停手。 */
    @Test
    void teleportFailureKeepsCrystalAndStopsAfterBoundedRetries() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.populateFullTemplate();
            fixture.crystal.teleportOk = false;
            EndSpikes.Spike spike = fixture.spike;
            int vanillaY = spike.height() + 1;

            fixture.lifecycle.verifyAndMaybeClaim(fixture.world, fixture.run, 0, "测试");

            assertEquals(vanillaY, fixture.crystal.location.getBlockY(),
                    "传送失败时晶体必须留在原版槽位，不能假装搬走");
            assertTrue(fixture.run.inFlight.isEmpty(), "有界重试结束后必须释放 inFlight，不能永久卡死");
        } finally {
            fixture.close();
        }
    }

    /** epoch 失效：读任务被丢弃后不写世界、不落 built，也不污染新一轮。 */
    @Test
    void expiredEpochDropsTaskWithoutWriting() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.populateFullTemplate();
            fixture.runtime.defer = true; // 把区域任务挂起，模拟「排队后世界被顶替」
            fixture.lifecycle.verifyAndMaybeClaim(fixture.world, fixture.run, 0, "测试");
            fixture.run.epoch.incrementAndGet(); // 顶替当前轮
            fixture.runtime.drainAll();          // 旧回调此时才执行，必须原地失效

            assertFalse(fixture.registryBuilt(0), "epoch 失效的读任务不应落盘 built");
            assertTrue(fixture.run.verifyInFlight.isEmpty(), "失效回调必须收尾释放 verifyInFlight");
        } finally {
            fixture.close();
        }
    }

    /**
     * retryCrystal 释放互斥的回归：review 抓到的 P1——不释放 inFlight 时，下一轮 reconcile 的
     * {@code inFlight.add} 恒为 false，重试永远不执行。这里直接以「已 built、inFlight 仍占用、晶体待搬」
     * 的状态调生产 {@link YungPillarLifecycle#retryCrystal}，成功（晶体被搬走）就证明互斥被正确释放。
     */
    @Test
    void retryCrystalReleasesMutexSoRetryCanRun() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.populateFullTemplate();
            UUID uuid = fixture.crystal.uuid;
            // 模拟 verifyWritten(target==null) 之后：已 built、pending 仍在、inFlight 仍被占用。
            fixture.run.registry = YungPillarStore.Registry.empty()
                    .withCurrentBuilt(0).withCrystal(0, uuid);
            fixture.run.pending.add(0);
            fixture.run.inFlight.add(0);

            fixture.lifecycle.retryCrystal(fixture.world, fixture.run, 0, 0);

            YungPillars.Layout layout = YungPillars.layout(fixture.spike, SEED);
            assertEquals(layout.crystalY(), fixture.crystal.location.getBlockY(),
                    "释放互斥后重试应真正执行并把晶体搬到新晶座");
        } finally {
            fixture.close();
        }
    }

    // ------------------------------------------------------------------ 纯判据（生产实际调用）

    /** 阶段判据不能简单删，但重启/第二次击杀后已 built 柱在 NONE 必须能重新接管。 */
    @Test
    void restartReclaimAllowedButNewBirthStillNeedsStage() {
        DragonBattle.RespawnPhase none = DragonBattle.RespawnPhase.NONE;
        assertTrue(YungPillarLifecycle.takeoverAllowed(true, true, none, true));
        assertTrue(YungPillarLifecycle.takeoverAllowed(false, true, none, true));
        assertTrue(YungPillarLifecycle.takeoverAllowed(true, false, none, true),
                "已 built 柱在 NONE 重新加载的官方晶必须能接管");
        assertTrue(YungPillarLifecycle.takeoverAllowed(true, false,
                DragonBattle.RespawnPhase.SUMMONING_PILLARS, true));
        assertFalse(YungPillarLifecycle.takeoverAllowed(true, false,
                DragonBattle.RespawnPhase.START, true));
        assertFalse(YungPillarLifecycle.takeoverAllowed(false, false, none, true),
                "未 built 的新出生在 NONE（击杀过）不能自动接管");
    }

    /** 受损模板不通过核验并给出首差；旧布局套新模板必不完整。 */
    @Test
    void damagedAndLegacyTemplatesAreRejected() throws Exception {
        Fixture fixture = new Fixture();
        try {
            YungPillars.Blueprint blueprint = YungPillars.blueprint(fixture.spike, SEED, fixture.field);
            Map<Long, String> full = new HashMap<>();
            for (YungPillars.Cell cell : blueprint.cells()) {
                full.put(cell.key(), cell.state());
            }
            assertTrue(YungPillarLifecycle.evaluateTemplate(blueprint, full).complete());

            YungPillars.Cell victim = fixture.firstSolidTemplateCell();
            Map<Long, String> damaged = new HashMap<>(full);
            damaged.put(victim.key(), "minecraft:air");
            YungPillarLifecycle.GeneratedVerdict broken =
                    YungPillarLifecycle.evaluateTemplate(blueprint, damaged);
            assertFalse(broken.complete());
            assertTrue(broken.firstBad().contains("首个不符"), broken.firstBad());

            YungPillars.Blueprint legacy = YungPillars.legacyBlueprint(fixture.spike, SEED, fixture.field);
            Map<Long, String> legacyStates = new HashMap<>();
            for (YungPillars.Cell cell : legacy.cells()) {
                legacyStates.put(cell.key(), cell.state());
            }
            assertFalse(YungPillarLifecycle.evaluateTemplate(blueprint, legacyStates).complete(),
                    "旧布局足迹不能冒充当前模板");
        } finally {
            fixture.close();
        }
    }

    // ------------------------------------------------------------------ 测试夹具

    /** 一个受管世界的可注入替身：代理 World/Block/EnderCrystal + 同步 Runtime + 真实登记表落盘。 */
    private static final class Fixture implements AutoCloseable {
        final Path dataDir;
        final long seed = SEED;
        final IslandField field = new IslandField(SEED);
        final EndSpikes.Spike spike = EndSpikes.forSeed(SEED).get(0);
        final Map<Long, String> blocks = new HashMap<>();
        final Map<Long, Integer> cripple = new HashMap<>();
        final List<String> logs = new ArrayList<>();

        final FakeCrystal crystal;
        final World world;
        final YungPillarLifecycle.Run run;
        final FakeRuntime runtime = new FakeRuntime();
        final YungPillarLifecycle lifecycle;

        Fixture() throws Exception {
            dataDir = Files.createTempDirectory("pillar-orchestration-test");
            world = (World) proxy(World.class, new WorldHandler(this));
            crystal = new FakeCrystal(this);
            run = new YungPillarLifecycle.Run(WORLD_ID, seed, field);
            runtime.world = world;
            runtime.crystal = crystal;
            lifecycle = new YungPillarLifecycle(null, dataDir, runtime);
        }

        void populateFullTemplate() {
            for (YungPillars.Cell cell : YungPillars.blueprint(spike, SEED, field).cells()) {
                blocks.put(cell.key(), cell.state());
            }
        }

        YungPillars.Cell firstSolidTemplateCell() {
            for (YungPillars.Cell cell : YungPillars.blueprint(spike, SEED, field).cells()) {
                if (cell.origin() == YungPillars.Origin.TEMPLATE
                        && !"minecraft:air".equals(cell.state())) {
                    return cell;
                }
            }
            throw new AssertionError("蓝图里没有非空模板格");
        }

        void crippleOnce(long key) {
            cripple.put(key, 1);
        }

        boolean registryBuilt(int index) throws Exception {
            return YungPillarStore.readRegistry(dataDir, WORLD_ID).built().contains(index);
        }

        UUID registryCrystal(int index) throws Exception {
            return YungPillarStore.readRegistry(dataDir, WORLD_ID).crystal(index);
        }

        /** 读一格：cripple 先消耗一次空气（模拟未写完），否则返回真实/默认空气。 */
        String read(int x, int y, int z) {
            long key = YungPillars.pack(x, y, z);
            Integer pending = cripple.get(key);
            if (pending != null && pending > 0) {
                cripple.put(key, pending - 1);
                return "minecraft:air";
            }
            return blocks.getOrDefault(key, "minecraft:air");
        }

        @Override
        public void close() throws Exception {
            try (var stream = Files.walk(dataDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                        // 清理失败不影响断言
                    }
                });
            }
        }
    }

    /** 同步 Runtime：区域/异步任务立即执行；defer=true 时把区域任务排队以便测 epoch 失效。 */
    private static final class FakeRuntime implements YungPillarLifecycle.Runtime {
        boolean defer;
        final Deque<Runnable> queue = new ArrayDeque<>();
        World world;
        FakeCrystal crystal;

        void drainAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean ownsRegion(World ignored, int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public boolean ownsEntity(Entity ignored) {
            return true;
        }

        @Override
        public void region(World ignored, int chunkX, int chunkZ, long delay, Runnable task) {
            if (defer) {
                queue.add(task);
            } else {
                task.run();
            }
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public BlockData blockData(String state) {
            return (BlockData) proxy(BlockData.class, (p, m, a) -> switch (m.getName()) {
                case "getAsString", "toString" -> state;
                default -> defaultValue(m);
            });
        }

        @Override
        public boolean pillarCrystal(Entity entity) {
            return entity == crystal.proxy;
        }

        @Override
        public boolean bridgeAvailable() {
            return true;
        }

        @Override
        public void info(String message) {
            // 测试不关心正常日志
        }

        @Override
        public void warn(String message) {
            // 测试不关心告警文本，只关心编排状态
        }
    }

    private static final class WorldHandler implements InvocationHandler {
        private final Fixture f;

        WorldHandler(Fixture f) {
            this.f = f;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getSeed" -> f.seed;
                case "getName" -> "test-end";
                case "getMinHeight" -> 0;
                case "getMaxHeight" -> 320;
                case "getBlockAt" -> {
                    int x = (int) args[0];
                    int y = (int) args[1];
                    int z = (int) args[2];
                    yield blockAt(f, proxy, x, y, z);
                }
                case "getChunkAtAsync" -> CompletableFuture.completedFuture(null);
                case "isChunkLoaded" -> true;
                case "getEntity" -> {
                    UUID id = (UUID) args[0];
                    if (f.crystal != null && f.crystal.uuid.equals(id)) {
                        yield f.crystal.proxy;
                    }
                    yield null;
                }
                case "getNearbyEntities" -> {
                    BoundingBox box = (BoundingBox) args[0];
                    List<Entity> out = new ArrayList<>();
                    FakeCrystal c = f.crystal;
                    if (c != null && c.visible
                            && box.contains(c.location.getX(), c.location.getY(), c.location.getZ())) {
                        out.add((Entity) c.proxy);
                    }
                    yield out;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakeWorld";
                default -> defaultValue(method);
            };
        }
    }

    private static Block blockAt(Fixture f, Object worldProxy, int x, int y, int z) {
        return (Block) proxy(Block.class, (p, m, a) -> switch (m.getName()) {
            case "getBlockData" -> f.runtime.blockData(f.read(x, y, z));
            case "setBlockData" -> {
                BlockData data = (BlockData) a[0];
                f.blocks.put(YungPillars.pack(x, y, z), data.getAsString());
                yield null;
            }
            case "isEmpty" -> YungPillarLifecycle.isEmptyState(f.read(x, y, z));
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getWorld" -> worldProxy;
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            case "toString" -> "FakeBlock(" + x + "," + y + "," + z + ")";
            default -> defaultValue(m);
        });
    }

    /** 一颗可由测试驱动位置/合法性的官方柱晶。 */
    private static final class FakeCrystal {
        final UUID uuid = UUID.randomUUID();
        boolean valid = true;
        boolean visible = true;
        boolean teleportOk = true;
        Location location;
        Object proxy;

        FakeCrystal(Fixture f) {
            location = new Location(f.world, f.spike.centerX() + 0.5, f.spike.height() + 1,
                    f.spike.centerZ() + 0.5);
            proxy = proxy(EnderCrystal.class, (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> uuid;
                case "getWorld" -> f.world;
                case "getLocation" -> location;
                case "isValid" -> valid;
                case "getScheduler" -> entityScheduler();
                case "teleportAsync" -> {
                    if (teleportOk) {
                        location = (Location) a[0];
                    }
                    yield CompletableFuture.completedFuture(teleportOk);
                }
                case "remove" -> {
                    valid = false;
                    yield null;
                }
                case "equals" -> p == a[0];
                case "hashCode" -> System.identityHashCode(p);
                case "toString" -> "FakeCrystal";
                default -> defaultValue(m);
            });
        }

        private static Object entityScheduler() {
            Object scheduledTask = proxy(scheduledTaskType(), (p, m, a) -> defaultValue(m));
            Class<?> schedulerType = schedulerType();
            return proxy(schedulerType, (p, m, a) -> {
                if ("run".equals(m.getName())) {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<Object> task =
                            (java.util.function.Consumer<Object>) a[1];
                    task.accept(scheduledTask);
                    return scheduledTask;
                }
                return defaultValue(m);
            });
        }

        @SuppressWarnings("unchecked")
        private static Class<?> schedulerType() {
            try {
                return Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
                        .asSubclass(Object.class);
            } catch (ClassNotFoundException missing) {
                return Object.class;
            }
        }

        @SuppressWarnings("unchecked")
        private static Class<?> scheduledTaskType() {
            try {
                return Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask")
                        .asSubclass(Object.class);
            } catch (ClassNotFoundException missing) {
                return Object.class;
            }
        }
    }

    // ------------------------------------------------------------------ Proxy 工具

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        try {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        } catch (Throwable error) {
            throw new IllegalStateException("无法代理 " + type.getName() + "：" + error, error);
        }
    }

    private static Object defaultValue(Method method) {
        Class<?> ret = method.getReturnType();
        if (!ret.isPrimitive()) {
            return null;
        }
        if (ret == boolean.class) {
            return false;
        }
        if (ret == void.class) {
            return null;
        }
        if (ret == long.class) {
            return 0L;
        }
        if (ret == double.class) {
            return 0.0d;
        }
        if (ret == float.class) {
            return 0.0f;
        }
        return 0;
    }
}
