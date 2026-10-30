package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.DustWastelandsGenerator;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.TileState;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link EndLayoutUpgrade} 的**薄** Bukkit 适配层：真实世界读写、真实快照、真实命令输出。
 *
 * <h2>为什么单独一个文件</h2>
 * 纯服务（{@link EndLayoutUpgrade}）只认端口，可以在没有服务端的 JUnit 里跑；这一层把端口接到
 * {@code World}/{@code RegionScheduler}/{@code EndPodiumBackup} 上，所以它必须能读真实世界，也就必须
 * 在**拥有该区块的区域线程**上被调用。命令注册留在 {@code BetterEndPlugin}（由接线方负责），本类只提供
 * 两个可被命令直接调用的入口：
 * <ul>
 *   <li>{@link #preview}：只读，打印旧/新高度、范围、证据、冲突、备份位置与确认 token；</li>
 *   <li>{@link #apply}：token 必须与**此刻重新算出来的**预览完全一致，否则一格不动。</li>
 * </ul>
 *
 * <h2>输出语言</h2>
 * 这里只输出中文诊断行（控制台/命令执行者各一条 {@code Component}），**没有**往 {@code messages.yml}
 * 加键：旧世界迁移是管理员一次性操作，文案与「五行证据」绑在一起，拆成配置键只会让证据和文案漂移。
 * 需要本地化时由接线方加键，并把 {@link EndLayoutUpgrade#lines} 的行映射过去。
 *
 * <h2>当前能自动做到哪一步（诚实边界）</h2>
 * {@link #parts} 里只有 {@code pdc} 段是真实现的；{@code pillars} 与 {@code podium} 两段都是
 * {@link EndLayoutUpgrade.Section#blocked}，理由写在代码里。因此旧世界现在<b>必然</b>
 * {@code allowed=false}、{@link #apply} 必然拒绝 —— 这正是要的表达：当前截图世界没有可靠所有权证据，
 * 只能报「未修复」，不能拿一次自动化把玩家的方块当成自己的成品覆盖掉。
 */
public final class EndLayoutUpgradeCommand {

    /** 世界中心区块：中心楼与返回门都在它里面。 */
    private static final int CENTER_CHUNK = 0;

    private EndLayoutUpgradeCommand() {
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 只读预览：把结论逐行发给 {@code sender}，并于结尾给出确认 token 与「能不能执行」。
     *
     * @param sender 命令执行者或控制台；消息是纯文本中文
     */
    public static void preview(Plugin plugin, CommandSender sender, World world) {
        if (!manages(world)) {
            send(sender, "这个世界的生成器不是 BetterEnd，本插件不管它的中心楼与柱：未做任何读取。");
            return;
        }
        onCenterRegion(plugin, world, () -> {
            EndLayoutUpgrade.Preview preview;
            try {
                preview = EndLayoutUpgrade.preview(world(plugin, world), parts(world), backup(plugin, world));
            } catch (RuntimeException | LinkageError broken) {
                send(sender, "预览失败：" + reason(broken) + "；未做任何写入。");
                return;
            }
            for (String line : EndLayoutUpgrade.lines(preview)) {
                send(sender, line);
            }
            send(sender, preview.allowed()
                    ? "如果确认上面这些行，执行：/betterend upgrade apply " + preview.token()
                    : "本次不能执行；处理完上面的拒绝理由与冲突后重新预览。");
        }, sender);
    }

    /**
     * 执行迁移：token 必须与此刻重新算出来的预览一致（人确认的就是眼前这一份），
     * 且预览必须 {@code allowed}。任何一条不满足都**一格不写**并说明原因。
     *
     * @param token 上一次 {@link #preview} 打印出来的确认 token
     */
    public static void apply(Plugin plugin, CommandSender sender, World world, String token) {
        if (!manages(world)) {
            send(sender, "这个世界的生成器不是 BetterEnd，本插件不管它：未做任何改动。");
            return;
        }
        onCenterRegion(plugin, world, () -> {
            EndLayoutUpgrade.World probe = world(plugin, world);
            EndLayoutUpgrade.Parts parts = parts(world);
            EndLayoutUpgrade.Backup backup = backup(plugin, world);
            EndLayoutUpgrade.Preview now;
            try {
                now = EndLayoutUpgrade.preview(probe, parts, backup);
            } catch (RuntimeException | LinkageError broken) {
                send(sender, "执行前重算预览失败：" + reason(broken) + "；未做任何写入。");
                return;
            }
            EndLayoutUpgrade.Grant grant;
            try {
                grant = EndLayoutUpgrade.apply(probe, parts, now, token, backup);
            } catch (RuntimeException refused) {
                // token 不符 / 预览不允许 / 复活中 / 区块不归当前区域：都在这里，一格未写。
                send(sender, "拒绝执行：" + reason(refused));
                send(sender, "世界未做任何改动。请重新运行 preview 并按新的证据确认。");
                return;
            } catch (IOException failed) {
                send(sender, "备份失败，已中止：" + reason(failed) + "；世界一格未写。");
                return;
            }
            send(sender, "备份已落盘：" + grant.backupFile());
            send(sender, "开始按区块落笔：" + grant.batches().size() + " 批，每批最多 "
                    + EndLayoutUpgrade.BATCH_CELLS + " 格。");
            runBatches(plugin, sender, world, probe, grant, 0);
        }, sender);
    }

    // ------------------------------------------------------------------ 执行

    /**
     * {@code /betterend upgrade reanchor <token>}：<b>本版不支持中心楼重定位</b>。
     *
     * <p>本版不再构造 {@code EndPodiumShell}（中心楼施工、门锚点改写、龙蛋改放全部停用），因此没有可委托
     * 的重定位服务。这里不再用「服务为 null」这种泛化判断，也不重算预览假装还有授权：直接、无条件说明
     * 不支持，并且一格不写。{@code token} 只为保持命令形态而接收，不参与任何判断。
     */
    public static void reanchor(CommandSender sender, World world, String token) {
        if (!manages(world)) {
            send(sender, "这个世界的生成器不是 BetterEnd，本插件不管它的中心楼：未做任何改动。");
            return;
        }
        send(sender, "本版不支持中心楼重定位（/betterend upgrade reanchor）：中心楼施工与门锚点改写已在本版停用，"
                + "没有可委托的服务，也不会写世界。");
        send(sender, "若目标是清掉旧版中心楼装饰，请用 /betterend upgrade remove 预览；token 参数不会被使用。");
    }
    // ------------------------------------------------------------------ 通用服务入口（中央清理 / 柱恢复）

    /**
     * 一个「只读预览 + 确认执行」的服务端口。
     *
     * <p>中央旧楼清理与柱恢复共用它：命令层只负责世界选择、权限、token 传递与打印；**所有判定与写世界**
     * 都在服务实现里（后端由 central / pillar 提供）。这样命令层不需要知道它们的内部结构，也不会把
     * 「纯服务存在」误当成「已经接线」：端口未接上时 {@link #notWired} 会明确说不写世界。
     */
    public interface Service {
        /** 命令里的名字：{@code remove} / {@code restore}。 */
        String name();

        /**
         * 只读预览。
         *
         * @param done 在**服务自己的线程语义**下回调；命令层只打印，不再读世界
         */
        void preview(World world, Consumer<Preview> done);

        /**
         * 按 token 执行。
         *
         * @param done 回调结果；{@code accepted=false} 表示服务拒绝且一格未写
         */
        void apply(World world, String token, Consumer<Result> done);

        /**
         * 未接线的诚实占位：预览与执行都只说明原因，**任何情况下都不写世界**。
         *
         * <p>用它而不是 null，是为了让「命令已注册但后端未就绪」时玩家看到的是具体原因，
         * 而不是把空指针包装成「未知错误」。
         */
        static Service notWired(String name, String reason) {
            return new Service() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public void preview(World world, Consumer<Preview> done) {
                    done.accept(new Preview("UNWIRED", null, List.of(reason + "；本命令不会写世界。"), null));
                }

                @Override
                public void apply(World world, String token, Consumer<Result> done) {
                    done.accept(new Result(false, reason + "；一格未写", null, 0, 0, true));
                }
            };
        }
    }

    /**
     * 一次预览。
     *
     * @param status  服务给出的状态名（原样打印，不在这里翻译）
     * @param token   可执行时的确认令牌；{@code null}/空表示「不能执行」
     * @param lines   逐行输出（服务负责把拒绝理由写全）
     * @param snapshot 快照路径（可空）
     */
    public record Preview(String status, String token, List<String> lines, Path snapshot) {
        public Preview {
            lines = List.copyOf(lines);
        }

        public boolean executable() {
            return token != null && !token.isBlank();
        }
    }

    /**
     * 一次执行结果。
     *
     * @param accepted 服务是否受理（<b>不等于</b>「全部完成」，更不等于「零写」）
     * @param zeroWrite 只有在服务**预检阶段**就拒绝（token 不符、冲突、快照缺失、未受管……）时才为 true；
     *                  服务已经进入写阶段后的失败（{@code WRITE_FAILED}）必须为 false —— 那可能已经写了前几个桶
     */
    public record Result(boolean accepted, String detail, Path backup, int written, int kept, boolean zeroWrite) {

        /** 兼容 5 参调用：默认**不**声称零写（保守，宁可提示「可能已变更」）。 */
        public Result(boolean accepted, String detail, Path backup, int written, int kept) {
            this(accepted, detail, backup, written, kept, false);
        }
    }

    /**
     * 哪些**服务状态名**可以声称「一格未写」。
     *
     * <p>只用状态名（字符串）判定，不引用后端枚举：命令层不该为了一个判定跟后端的类版本绑死，
     * 也能在 common 里纯测。
     *
     * <p>白名单只放**确定发生在写之前**的核验失败：未受管、无布局、布局不可读、种子不符、快照缺失/
     * 不匹配、没有可清理项、token 被拒。以下都**不**声称零写：
     * <ul>
     *   <li>{@code SCHEDULE_FAILED} —— 清理可能已经写完前几个桶，后面的桶才调度不上；</li>
     *   <li>{@code CONFLICT} —— 写前重核中途发现冲突，前面的桶可能已经落笔；</li>
     *   <li>{@code WRITE_FAILED}、{@code OK} —— 显然不是零写。</li>
     * </ul>
     * 后端若将来在 {@code Result} 上显式给出 {@code zeroWrite}/实际写数，直接沿用它的值，不要用这张表猜。
     */
    public static boolean zeroWriteStatus(String statusName) {
        if (statusName == null) {
            return false;
        }
        return switch (statusName) {
            case "NOT_MANAGED", "NO_LAYOUT", "LAYOUT_UNREADABLE", "SEED_MISMATCH", "SNAPSHOT_MISSING",
                 "SNAPSHOT_MISMATCH", "NOTHING_TO_REMOVE", "TOKEN_REJECTED" -> true;
            default -> false;
        };
    }

    /**
     * 命令层编排：{@code /betterend upgrade <name>}（预览）与 {@code /betterend upgrade <name> confirm <token>}（执行）。
     *
     * <p>命令层只做三件事：选世界、把 token 原样交给服务、把结果原样打印。**不**自己判定「能不能清」，
     * 也**不**在服务返回失败时补写任何东西——所以不存在「假装写入」的路径。
     *
     * @param token {@code null} 表示预览
     */
    public static void service(Plugin plugin, Service service, CommandSender sender, World world, String token) {
        if (!manages(world)) {
            send(sender, "这个世界的生成器不是 BetterEnd，本插件不管它的中心楼与柱：未做任何读取。");
            return;
        }
        if (service == null) {
            send(sender, "服务未启用：功能未接线，本命令不会写世界。");
            return;
        }
        if (token == null || token.isBlank()) {
            try {
                service.preview(world, preview -> {
                    for (String line : preview.lines()) {
                        send(sender, line);
                    }
                    if (preview.snapshot() != null) {
                        send(sender, "本次快照会落在：" + preview.snapshot());
                    }
                    send(sender, preview.executable()
                            ? "如果确认上面这些行，执行：/betterend upgrade " + service.name()
                            + " confirm " + preview.token()
                            : "本次不能执行（状态 " + preview.status() + "）；处理完上面的理由后重新预览。");
                });
            } catch (RuntimeException | LinkageError broken) {
                send(sender, "预览失败：" + reason(broken) + "；未做任何写入。");
            }
            return;
        }
        try {
            service.apply(world, token, result -> {
                send(sender, "服务返回：" + result.detail());
                if (result.accepted()) {
                    if (result.backup() != null) {
                        send(sender, "撤回快照/备份：" + result.backup());
                    }
                    send(sender, "以上是服务的原样结果；本命令不额外宣称「已完成」。");
                } else if (result.zeroWrite()) {
                    send(sender, "这是预检拒绝：一格未写。按上面的原因处理后重新运行预览。");
                } else {
                    send(sender, "未完成：部分方块可能已变更。请依据撤回快照（若有）与服务器日志处理，"
                            + "不要把这当成零写，也不要重复确认同一 token。");
                }
            });
        } catch (RuntimeException | LinkageError broken) {
            send(sender, "执行被拒绝或失败：" + reason(broken)
                    + "；异常本身不代表零写，请按服务器日志与撤回快照核对，不要重复确认同一 token。");
        }
    }

    /**
     * 逐批调度：每一批都排到**拥有该区块的区域线程**上，前一批成功才排下一批；
     * 任何一批失败就停在这里并把原因发出去（不续建、不标完成）。
     */
    private static void runBatches(Plugin plugin, CommandSender sender, World world,
                                   EndLayoutUpgrade.World probe, EndLayoutUpgrade.Grant grant, int index) {
        List<EndLayoutUpgrade.Batch> batches = grant.batches();
        if (index >= batches.size()) {
            finish(plugin, sender, world, probe, grant);
            return;
        }
        EndLayoutUpgrade.Batch batch = batches.get(index);
        onRegion(plugin, world, batch.chunkX(), batch.chunkZ(), () -> {
            EndLayoutUpgrade.Result result = grant.write(batch, probe, writer(world));
            if (!result.ok()) {
                send(sender, "第 " + (index + 1) + "/" + batches.size() + " 批失败（这一批一格未写），已停止：");
                for (String note : result.notes()) {
                    send(sender, "  " + note);
                }
                send(sender, "备份仍在 " + grant.backupFile() + "；完成标记没有写入，世界保持未完成状态。");
                return;
            }
            runBatches(plugin, sender, world, probe, grant, index + 1);
        }, sender);
    }

    /** 全部批次成功之后才写完成标记，并核对龙战状态没被弄丢。 */
    private static void finish(Plugin plugin, CommandSender sender, World world,
                               EndLayoutUpgrade.World probe, EndLayoutUpgrade.Grant grant) {
        List<String> problems = grant.verifyPreserved(probe);
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                send(sender, "收尾核对失败：" + problem);
            }
            send(sender, "未写完成标记；请人工核对（备份 " + grant.backupFile() + "）。");
            return;
        }
        try {
            writeMarks(plugin, world, grant.marks());
        } catch (RuntimeException refused) {
            send(sender, "方块已全部写入，但完成标记写入失败：" + reason(refused)
                    + "；下次预览会把这个世界当成未迁移，请勿手动改 PDC。");
            return;
        }
        send(sender, "迁移完成：备份 " + grant.backupFile() + "；龙战状态（active / 击杀记录 / 龙 UUID）原样保留。");
    }

    /** 把 {@code key=value} 落到世界 PDC（键名与 {@link EndPodiumShell#markKey} 同源）。 */
    private static void writeMarks(Plugin plugin, World world, List<String> marks) {
        for (String mark : marks) {
            int split = mark.indexOf('=');
            if (split <= 0) {
                throw new IllegalArgumentException("完成标记格式不是 key=value：" + mark);
            }
            String key = mark.substring(0, split);
            String value = mark.substring(split + 1);
            NamespacedKey namespaced = EndPodiumShell.markKey(plugin, key);
            world.getPersistentDataContainer().set(namespaced, PersistentDataType.STRING, value);
        }
    }

    // ------------------------------------------------------------------ 端口实现

    /**
     * 真实世界的只读探针。
     *
     * <p>{@link #owns} 只调用 {@code isOwnedByCurrentRegion}、**一个方块都不读**，所以「未知块不动」
     * 这条规则在读世界内容之前就生效。
     */
    static EndLayoutUpgrade.World world(Plugin plugin, World bukkit) {
        return new EndLayoutUpgrade.World() {

            @Override
            public UUID worldId() {
                return bukkit.getUID();
            }

            @Override
            public long seed() {
                return bukkit.getSeed();
            }

            @Override
            public int minY() {
                return bukkit.getMinHeight();
            }

            @Override
            public int maxY() {
                return bukkit.getMaxHeight();
            }

            @Override
            public int surfaceTopY(int x, int z) {
                if (bukkit.getGenerator() instanceof DustWastelandsGenerator generator) {
                    return generator.islandTopY(bukkit.getSeed(), x, z);
                }
                throw new IllegalStateException("世界的生成器不是 BetterEnd，算不出纯地形顶块高度");
            }

            @Override
            public boolean owns(int chunkX, int chunkZ) {
                double x = (chunkX << 4) + 0.5;
                double z = (chunkZ << 4) + 0.5;
                return Bukkit.isOwnedByCurrentRegion(new Location(bukkit, x, bukkit.getMinHeight() + 1, z));
            }

            @Override
            public String state(int x, int y, int z) {
                return bukkit.getBlockAt(x, y, z).getBlockData().getAsString();
            }

            @Override
            public boolean tileState(int x, int y, int z) {
                return bukkit.getBlockAt(x, y, z).getState(false) instanceof TileState;
            }

            @Override
            public String mark(String key) {
                return bukkit.getPersistentDataContainer()
                        .get(EndPodiumShell.markKey(plugin, key), PersistentDataType.STRING);
            }

            /**
             * 原版结构晶的所有权登记：直接查 {@link YungPillarLifecycle} 的登记表。
             *
             * <p><b>空仍然是「没有所有权证据」</b>，不是「没有水晶」：旧 0.1.26 世界从来没有过登记表，
             * 所以十根柱全部按无证据处理，预览会逐根报出来并拒绝 apply，而不是靠材料/模板形状推断。
             * 柱生命周期重铺成功后会把同一颗实体的 UUID 写回登记表，那时这里自然就有值。
             */
            @Override
            public Optional<UUID> structureCrystal(int spikeIndex) {
                List<EndSpikes.Spike> spikes = EndSpikes.forSeed(bukkit.getSeed());
                if (spikeIndex < 0 || spikeIndex >= spikes.size()) {
                    return Optional.empty();
                }
                return YungPillarLifecycle.structureCrystal(bukkit, spikes.get(spikeIndex));
            }

            @Override
            public EndLayoutUpgrade.Fight fight() {
                DragonBattle battle = bukkit.getEnderDragonBattle();
                if (battle == null) {
                    return new EndLayoutUpgrade.Fight(false, false, Optional.empty(), Optional.empty());
                }
                Location portal = battle.getEndPortalLocation();
                Optional<EndLayoutUpgrade.Gate> gate = portal == null
                        ? Optional.empty()
                        : Optional.of(new EndLayoutUpgrade.Gate(portal.getBlockX(), portal.getBlockY(),
                        portal.getBlockZ()));
                Optional<UUID> dragon = battle.getEnderDragon() == null
                        ? Optional.empty()
                        : Optional.of(battle.getEnderDragon().getUniqueId());
                return new EndLayoutUpgrade.Fight(battle.hasBeenPreviouslyKilled(),
                        battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE, dragon, gate);
            }
        };
    }

    /** 真正的单格写入：不做物理/邻居更新，与中心祭坛施工同一条规则。 */
    static EndLayoutUpgrade.Writer writer(World bukkit) {
        return (x, y, z, state) -> bukkit.getBlockAt(x, y, z).setBlockData(YungPillars.bukkit(state), false);
    }

    /** 真正的快照：**只**能落在 {@link EndPodiumBackup} 的固定目录里，不接受调用方拼路径。 */
    static EndLayoutUpgrade.Backup backup(Plugin plugin, World bukkit) {
        Path dataDir = plugin.getDataFolder().toPath();
        UUID worldId = bukkit.getUID();
        return new EndLayoutUpgrade.Backup() {

            @Override
            public Path pathFor(UUID jobId) {
                try {
                    return EndPodiumBackup.pathFor(dataDir, worldId, jobId);
                } catch (IOException broken) {
                    throw new UncheckedIOException(broken);
                }
            }

            @Override
            public Path save(UUID jobId, List<EndPodiumBackup.Cell> cells) throws IOException {
                return EndPodiumBackup.write(dataDir, worldId, jobId, cells);
            }
        };
    }

    /**
     * 迁移内容三段。
     *
     * <ul>
     *   <li>{@code pdc}：真实现 —— {@code layout=v2:seed=..:gate=..} 与 {@code state=placed}；</li>
     *   <li>{@code pillars}：{@link EndLayoutUpgrade.Section#blocked} —— 柱侧还没有「目标格子 + 同一颗
     *       晶体的搬移」接口，只改高度不搬实体会造出「柱抬高了、水晶还埋在旧位置」的半套；</li>
     *   <li>{@code podium}：{@link EndLayoutUpgrade.Section#blocked} —— 旧楼整体重定位由
     *       {@link EndPodiumShell#reanchor(World)} 自己做（核验门家具 → 压缩快照 → 清旧 → 锚点搬迁），
     *       把它拆成本服务的格子只会造出第二套搬迁逻辑。</li>
     * </ul>
     */
    static EndLayoutUpgrade.Parts parts(World bukkit) {
        int top = bukkit.getGenerator() instanceof DustWastelandsGenerator generator
                ? generator.islandTopY(bukkit.getSeed(), 0, 0)
                : Integer.MIN_VALUE;
        List<String> marks = new ArrayList<>(2);
        if (top != Integer.MIN_VALUE) {
            EndPodiumTemplate.Layout layout = new EndPodiumTemplate.Layout(
                    EndPodiumTemplate.LAYOUT_VERSION, bukkit.getSeed(),
                    0, top - EndPodiumTemplate.GATE_BELOW_FLOOR, 0);
            marks.add(EndLayoutUpgrade.MARK_LAYOUT + "=" + layout.encode());
            marks.add(EndLayoutUpgrade.MARK_STATE + "=" + EndLayoutUpgrade.PLACED);
        }
        EndLayoutUpgrade.Section pdc = marks.isEmpty()
                ? EndLayoutUpgrade.Section.blocked("pdc", "中心列在纯地形场里是空的，算不出新布局串")
                : EndLayoutUpgrade.Section.of("pdc", List.of(), List.of(), marks);
        return EndLayoutUpgrade.Parts.of(
                EndLayoutUpgrade.Section.blocked("pillars",
                        "柱侧还没有「模板目标格子 + 同一颗原版结构晶实体搬移」的接口："
                                + "现在迁移只会把柱子抬高而把水晶留在旧位置，属于半套修复，拒绝。"),
                EndLayoutUpgrade.Section.blocked("podium",
                        "旧中心楼整体重定位由 EndPodiumShell.reanchor(World) 负责（门家具核验 → 压缩快照 → "
                                + "清旧家具 → 保 active/击杀/龙UUID 搬锚点），不在本服务的格子模型里：拒绝重复实现。"),
                pdc);
    }

    // ------------------------------------------------------------------ 调度与小工具

    /** 世界的生成器是不是本插件的：不是就什么都不读。 */
    static boolean manages(World world) {
        return world.getGenerator() instanceof DustWastelandsGenerator;
    }

    /** 中心区块就绪后，在拥有它的区域线程上执行。 */
    private static void onCenterRegion(Plugin plugin, World world, Runnable task, CommandSender sender) {
        onRegion(plugin, world, CENTER_CHUNK, CENTER_CHUNK, task, sender);
    }

    /**
     * 在拥有 {@code (chunkX, chunkZ)} 的区域线程上执行：先让区块异步就绪，再排区域任务。
     *
     * <p>不用 {@code addPluginChunkTicket}（忘记 remove 就泄漏），不在区域线程上等别的区域，
     * 也不假设「原点区块的异步加载」完成后目标区块还归同一个区域。
     */
    private static void onRegion(Plugin plugin, World world, int chunkX, int chunkZ,
                                 Runnable task, CommandSender sender) {
        world.getChunkAtAsync(chunkX, chunkZ).thenRun(() -> {
            if (!plugin.isEnabled()) {
                send(sender, "插件正在关闭，已中止（世界未做改动）。");
                return;
            }
            try {
                Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, ignored -> task.run());
            } catch (RuntimeException refused) {
                send(sender, "区域任务被拒绝（区块 " + chunkX + ", " + chunkZ + "）：" + reason(refused)
                        + "；这一步之后没有再写任何东西。");
            }
        }).exceptionally(error -> {
            send(sender, "区块加载失败（" + chunkX + ", " + chunkZ + "）：" + reason(error) + "；已中止。");
            return null;
        });
    }

    /**
     * 发一行中文诊断。
     *
     * <p>走 Adventure 的 {@code Audience} 而不是 {@code sendMessage(Component)} 重载：Paper 的
     * {@code CommandSender} 同时有 Bungee 兼容的 {@code sendMessage(BaseComponent...)}，直接传
     * {@code Component} 时编译器会去解析那个重载（并因此要求 {@code net.md_5.bungee} 在类路径上）。
     * 与 {@code BetterEndPlugin} 里的写法一致。
     */
    private static void send(CommandSender sender, String line) {
        ((net.kyori.adventure.audience.Audience) sender).sendMessage(Component.text(line));
    }

    private static String reason(Throwable error) {
        if (error == null) {
            return "未知原因";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
