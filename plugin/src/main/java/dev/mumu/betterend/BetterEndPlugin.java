package dev.mumu.betterend;

import com.mojang.brigadier.Command;
import dev.mumu.betterend.biome.BiomeDatapack;
import dev.mumu.betterend.config.BetterEndConfig;
import dev.mumu.betterend.config.ConfigStore;
import dev.mumu.betterend.config.HelpReport;
import dev.mumu.betterend.config.Messages;
import dev.mumu.betterend.config.ReloadCoordinator;
import dev.mumu.betterend.config.ReloadReport;
import dev.mumu.betterend.vanilla.DecoratedPotBreak;
import dev.mumu.betterend.vanilla.EndLayoutUpgradeCommand;
import dev.mumu.betterend.vanilla.EndPodiumRemoval;
import dev.mumu.betterend.vanilla.EndPodiumTemplate;
import dev.mumu.betterend.vanilla.MonsterDamageBoost;
import dev.mumu.betterend.vanilla.StructureWorldGuard;
import dev.mumu.betterend.vanilla.VanillaEndCheck;
import dev.mumu.betterend.vanilla.YungPillarLifecycle;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * BetterEnd on Folia 26.3. The only plugin prerequisite is CraftEngine.
 * <p>
 * The Java package is {@code dev.mumu.betterend}; the resource IDs the world content depends on stay
 * {@code betterend}/{@code nova_structures}/{@code dnt}, so no structure reference or item model breaks.
 * <p>
 * When something is wrong -- an invalid {@code config.yml}, CraftEngine absent, a CraftEngine pack
 * missing or only partly written -- the plugin does not enable. Being honest about what that means: a
 * disabled plugin (or a null generator) does NOT stop the server from generating. Paper/Folia falls
 * back to the vanilla generator for any world that named BetterEnd, which bakes vanilla terrain into
 * those region files. The only safe response is to stop the server and fix the problem before any such
 * world is generated, which is exactly what every log line and the enable failure below tells the
 * operator to do. This class never claims to have prevented generation, because it cannot.
 */
public final class BetterEndPlugin extends JavaPlugin {
    /** Re-reading the config and completing the packs; op by default, declared in paper-plugin.yml. */
    static final String RELOAD_PERMISSION = "betterend.command.reload";

    /**
     * 旧布局检查与迁移入口（{@code /betterend upgrade preview|apply}）；op by default，
     * 与 {@link #RELOAD_PERMISSION} 分开声明：迁移会写世界，重载不会，两者不该共用一个节点。
     */
    static final String UPGRADE_PERMISSION = "betterend.command.upgrade";

    private CraftEngineBridge craftEngine;
    private boolean packsInstalled;
    /** Null until {@link #loadConfig()} succeeds; a null config blocks enabling. */
    private BetterEndConfig config;
    /** Chinese MiniMessage text from messages.yml; never null after onLoad. */
    private Messages messages = new Messages(Map.of());
    /**
     * The flattened values the running server was actually BUILT from, captured once at load. Never
     * replaced by a reload: nothing a reload does can change what the live generator, populators and
     * biome registry were constructed with, so this is the honest baseline for "this change still has
     * not taken effect". Comparing against a reload-updated map instead would let a second reload
     * report "no restart needed" while the server kept running the old terrain.
     */
    private Map<String, Object> startupValues = Map.of();
    /** The only reloadable values; a single publication keeps text, damage and diff baseline together. */
    private final AtomicReference<ReloadCoordinator.Live> live = new AtomicReference<>(
            new ReloadCoordinator.Live(messages, BetterEndConfig.DEFAULTS.monsterDamageMultiplier(), Map.of()));
    /** Created only after startup checks pass; closed before disabling platform tasks. */
    private ReloadCoordinator reloads;
    /**
     * 外围高柱的受管生命周期（原版柱晶搬迁 + 重铺）；null 表示本进程没有启用它。
     *
     * <p>本轮明确<b>不</b>构造/注册 {@code EndPodiumShell} 与 {@code BuildingCrystalRespawn}：中心楼施工、
     * 建筑四晶座、门/蛋改放都不在本版启用范围内，原版半径 3 的复活路径保持原样。
     */
    private YungPillarLifecycle pillars;

    /** 中央旧楼清理服务（central 提供）：构造一次复用，关闭时 shutdown。 */
    private EndPodiumRemoval removal;
    /** 命令层端口：{@code /betterend upgrade remove}（后端 = {@link #removal}）。 */
    private EndLayoutUpgradeCommand.Service removeService;

    @Override
    public void onLoad() {
        // Text first, so every later line can be a message rather than a bare string.
        messages = loadMessages();
        live.set(new ReloadCoordinator.Live(messages, BetterEndConfig.DEFAULTS.monsterDamageMultiplier(), Map.of()));
        // The config the generator needs. If it is invalid we stop here; the honest consequence is in
        // the log line (Paper falls back to the vanilla generator), not a claim we prevented anything.
        if (!loadConfig()) {
            return;
        }

        craftEngine = new CraftEngineBridge(this);
        if (craftEngine.plugin() == null) {
            getComponentLogger().error(texts().get("craftengine.missing"));
            return;
        }
        // Make CraftEngine register its pack during its own enable instead of on the first server
        // tick. CraftEngine enables before us (paper-plugin.yml declares load: BEFORE) and this runs
        // in our onLoad, so the flag is flipped before the engine reads it; it edits CraftEngine's
        // in-memory settings only, never its config.yml. See CraftEngineBridge for the full chain.
        reportImmediateLoad(craftEngine.forceImmediateResourceLoad());
        try {
            List<CraftEngineBridge.PackReport> reports = craftEngine.installBundledPacks();
            // Belt and braces: the bridge already throws on failure, but never enable on a report that
            // says some files did not land.
            for (CraftEngineBridge.PackReport report : reports) {
                if (report.failed() > 0) {
                    throw new IOException("资源包 '" + report.pack() + "' 有 " + report.failed() + " 个文件写入失败");
                }
            }
            packsInstalled = true;
        } catch (IOException error) {
            getComponentLogger().error(texts().get("craftengine.install-failed"), error);
        }
    }

    @Override
    public void onEnable() {
        // Throwing is Paper's supported bail-out; disablePlugin(this) from inside onEnable closes our
        // own classloader mid-callback and still fires PluginEnableEvent.
        String datapackFailure = BiomeDatapack.refusalReason();
        if (datapackFailure != null) {
            throw new IllegalStateException(datapackFailure
                    + " BetterEnd 不可用；Paper/Folia 可能回退原版生成器，请停止服务器并修正后再生成世界。");
        }
        if (config == null) {
            throw new IllegalStateException("config.yml 无效，BetterEnd 不可用；Paper/Folia 可能回退原版生成器，请停止服务器并修正 config.yml 后再生成世界。");
        }
        if (craftEngine == null || !craftEngine.available()) {
            throw new IllegalStateException("CraftEngine 未启用，BetterEnd 不可用；Paper/Folia 可能回退原版生成器，请停止服务器并修正后再生成世界。");
        }
        if (!packsInstalled) {
            throw new IllegalStateException("CraftEngine 资源包未安装成功，BetterEnd 不可用；Paper/Folia 可能回退原版生成器，请停止服务器并修正后再生成世界。");
        }
        // CraftEngine is enabled before us, and onLoad selected its immediate branch, so its pack is
        // registered by now. Verify the content the world cannot open without actually resolves --
        // an ENABLED CraftEngine whose pack did not register is exactly what isEnabled() (and
        // isFullyLoaded, false until the second tick even on the immediate path) cannot tell you.
        if (!craftEngine.contentReady()) {
            String missing = describeMissingContent();
            getComponentLogger().error(texts().get("craftengine.not-ready",
                    Placeholder.unparsed("missing", missing)));
            throw new IllegalStateException("CraftEngine 尚未注册世界生成必需的方块/物品（" + missing
                    + "），BetterEnd 不可用；Paper/Folia 可能回退原版生成器，请停止服务器并修正后再生成世界。");
        }

        reloads = new ReloadCoordinator(startupValues, live,
                this::readConfig, this::readMessages, this::completePacks,
                craftEngine::contentReady, task -> RegionSchedulerAdapter.runAsync(this, task));
        // paper-plugin.yml declares load: STARTUP, and DedicatedServer enables STARTUP plugins before
        // loadLevel(), so onEnable runs before any world exists: no Bukkit.getWorlds() sweep is needed
        // and no world can be missed, the primary End included. Both listeners self-select on the
        // generator, so registering them blind is correct.
        getServer().getPluginManager().registerEvents(new VanillaEndCheck(), this);
        getServer().getPluginManager().registerEvents(new StructureWorldGuard(), this);
        getServer().getPluginManager().registerEvents(new DecoratedPotBreak(), this);
        // The region owning a hit sees one immutable runtime snapshot, never a half-reloaded state.
        getServer().getPluginManager().registerEvents(
                new MonsterDamageBoost(() -> live.get().monsterMultiplier()), this);
        // 本版**不**构造/注册 EndPodiumShell：中心楼施工、门锚点改写与龙蛋改放都随之关闭，原版祭坛与
        // 半径 3 的四晶复活路径保持原样（EndPodiumShell 只被显式升级入口当作“本版不支持”的对象提及）。
        //
        // 柱生命周期保留：它搬的是原版 SUMMONING_PILLARS 重建出来的柱晶，与中心楼无关，自选世界与生成器。
        pillars = new YungPillarLifecycle(this, getDataFolder().toPath());
        getServer().getPluginManager().registerEvents(pillars, this);
        pillars.start();

        // 中央旧楼清理（central 的服务）：它自己排区域任务、自己重算 token、自己判断冲突；
        // 命令层只把 token 原样递进去并打印结果，不判定、不补写。
        removal = new EndPodiumRemoval(this, getDataFolder().toPath());
        removeService = removalService(removal);

        registerCommands();

        getComponentLogger().info(texts().get("console.enabled"));
    }

    /**
     * Registers {@code /betterend} through Paper's lifecycle API, which is the only way a
     * {@code paper-plugin.yml} plugin can own a command: {@code PaperPluginMeta} declares no
     * {@code commands} field at all (name, main, bootstrapper, loader, provides, version, description,
     * authors, contributors, website, prefix, load, api-version, folia-supported, dependencies -- that
     * is the whole surface), so a {@code commands:} block in the descriptor would be silently ignored
     * by the Configurate object mapper that reads it. {@code LifecycleEvents.COMMANDS} is documented
     * for exactly this.
     * <p>
     * The permission is checked by Brigadier's {@code requires}, so an unauthorised sender does not
     * see the command at all; each node ({@code betterend.command.reload} /
     * {@code betterend.command.upgrade}) is declared in paper-plugin.yml with {@code default: op} so it
     * is a real, assignable permission rather than one that falls through to the server's
     * {@code permissions.default-permission} setting.
     */
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(
                    Commands.literal("betterend")
                            .requires(source -> anyPermission(source.getSender()))
                            .executes(context -> {
                                showHelp(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.literal("help").executes(context -> {
                                showHelp(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                            .then(Commands.literal("reload")
                                    .requires(source -> source.getSender().hasPermission(RELOAD_PERMISSION))
                                    .executes(context -> {
                                        reload(context.getSource().getSender());
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("upgrade")
                                    .requires(source -> source.getSender().hasPermission(UPGRADE_PERMISSION))
                                    .executes(context -> {
                                        upgradeHelp(context.getSource().getSender());
                                        return Command.SINGLE_SUCCESS;
                                    })
                                    .then(Commands.literal("preview").executes(context -> {
                                        upgradePreview(context.getSource().getSender());
                                        return Command.SINGLE_SUCCESS;
                                    }))
                                    .then(Commands.literal("apply")
                                            .then(Commands.argument("token",
                                                            com.mojang.brigadier.arguments.StringArgumentType.word())
                                                    .executes(context -> {
                                                        upgradeApply(context.getSource().getSender(),
                                                                com.mojang.brigadier.arguments.StringArgumentType
                                                                        .getString(context, "token"));
                                                        return Command.SINGLE_SUCCESS;
                                                    })))
                                    .then(Commands.literal("reanchor")
                                            .then(Commands.argument("token",
                                                            com.mojang.brigadier.arguments.StringArgumentType.word())
                                                    .executes(context -> {
                                                        upgradeReanchor(context.getSource().getSender(),
                                                                com.mojang.brigadier.arguments.StringArgumentType
                                                                        .getString(context, "token"));
                                                        return Command.SINGLE_SUCCESS;
                                                    })))
                                    .then(Commands.literal("remove")
                                            .executes(context -> {
                                                upgradeService(removeService,
                                                        context.getSource().getSender(), null);
                                                return Command.SINGLE_SUCCESS;
                                            })
                                            .then(Commands.literal("confirm")
                                                    .then(Commands.argument("token",
                                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                                            .word())
                                                            .executes(context -> {
                                                                upgradeService(removeService,
                                                                        context.getSource().getSender(),
                                                                        com.mojang.brigadier.arguments.StringArgumentType
                                                                                .getString(context, "token"));
                                                                return Command.SINGLE_SUCCESS;
                                                            })))))
                            .build(), texts().raw("command.description", "BetterEnd"), List.of());
        });
    }

    /**
     * 根命令 {@code /betterend} 的可见性：{@code reload} 与 {@code upgrade} 各有独立节点，
     * 任一即可让根出现在补全里；每个子命令再各自 {@code requires} 自己的节点。
     * 两个节点都在 paper-plugin.yml 里以 {@code default: op} 声明，与这里逐字对应。
     */
    private static boolean anyPermission(CommandSender sender) {
        return sender.hasPermission(RELOAD_PERMISSION) || sender.hasPermission(UPGRADE_PERMISSION);
    }

    /** {@code /betterend upgrade}：本版实际可用与不可用的入口，逐条说清（不含任何未实现的暗示）。 */
    private void upgradeHelp(CommandSender sender) {
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.header"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.preview"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.remove-preview"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.remove-confirm"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.unsupported"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.reason"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.reason-altar"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.reason-filled"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.reason-manual"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.reason-manual-cont"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.doc-ref"));
        ((Audience) sender).sendMessage(texts().get("command.upgrade-help.eta"));
    }

    /**
     * 只读预览。
     *
     * <p>世界取命令执行者所在的那个（玩家/实体）；控制台取第一个由本插件生成的末地世界。
     * 真正的区块调度与线程判定都在 {@link EndLayoutUpgradeCommand} 里，这里只负责选世界与权限。
     */
    private void upgradePreview(CommandSender sender) {
        World world = upgradeWorld(sender);
        if (world == null) {
            ((Audience) sender).sendMessage(Component.text(
                    "找不到由 BetterEnd 生成的末地世界；请在末地执行，或让控制台在末地世界存在后执行。"));
            return;
        }
        EndLayoutUpgradeCommand.preview(this, sender, world);
    }

    /** 执行迁移：token 由预览给出；证据不完整时 {@link EndLayoutUpgradeCommand} 会拒绝且不改世界。 */
    private void upgradeApply(CommandSender sender, String token) {
        World world = upgradeWorld(sender);
        if (world == null) {
            ((Audience) sender).sendMessage(Component.text(
                    "找不到由 BetterEnd 生成的末地世界；请在末地执行，或让控制台在末地世界存在后执行。"));
            return;
        }
        EndLayoutUpgradeCommand.apply(this, sender, world, token);
    }

    /**
     * 中心楼受控重定位：授权门槛（预览 allowed、同一 token、龙没在复活）全部在
     * {@link EndLayoutUpgradeCommand#reanchor} 里校验；当前证据不足时它会明确说「功能未接线、不会写世界」，
     * 不会伪造一次迁移。
     */
    private void upgradeReanchor(CommandSender sender, String token) {
        World world = upgradeWorld(sender);
        if (world == null) {
            ((Audience) sender).sendMessage(Component.text(
                    "找不到由 BetterEnd 生成的末地世界；请在末地执行，或让控制台在末地世界存在后执行。"));
            return;
        }
        EndLayoutUpgradeCommand.reanchor(sender, world, token);
    }

    /**
     * {@code /betterend upgrade remove [confirm <token>]}：命令层只做世界选择与转交。
     *
     * @param token {@code null} 表示预览
     */
    private void upgradeService(EndLayoutUpgradeCommand.Service service, CommandSender sender, @Nullable String token) {
        World world = upgradeWorld(sender);
        if (world == null) {
            ((Audience) sender).sendMessage(Component.text(
                    "找不到由 BetterEnd 生成的末地世界；请在末地执行，或让控制台在末地世界存在后执行。"));
            return;
        }
        EndLayoutUpgradeCommand.service(this, service, sender, world, token);
    }

    /**
     * 把 central 的 {@link EndPodiumRemoval} 适配成命令层端口。
     *
     * <p>命令层不判定「能不能清」：{@code Status.OK} 之外一律按拒绝显示，token 只有服务给出非空值时
     * 才提示 confirm；执行结果原样转述，失败时明确「世界未做任何改动」。
     */
    private EndLayoutUpgradeCommand.Service removalService(EndPodiumRemoval backend) {
        return new EndLayoutUpgradeCommand.Service() {
            @Override
            public String name() {
                return "remove";
            }

            @Override
            public void preview(World world, java.util.function.Consumer<EndLayoutUpgradeCommand.Preview> done) {
                backend.preview(world, preview -> {
                    List<String> lines = new ArrayList<>();
                    lines.add("中央旧楼清理预览：" + preview.status() + " —— " + preview.detail());
                    lines.add("门环 (" + preview.gateX() + ", " + preview.gateY() + ", " + preview.gateZ()
                            + ")，种子 " + preview.seed());
                    lines.add("模板装饰 " + preview.decorations() + " 格；可回滚 " + preview.restorable()
                            + " 格；玩家改动 " + preview.playerChanged() + " 格；保留门家具 "
                            + preview.furnitureKept() + " 格；保留净空 " + preview.clearanceKept() + " 格");
                    int shown = 0;
                    for (EndPodiumTemplate.Cell cell : preview.samples()) {
                        if (shown++ >= 5) {
                            lines.add("  …样本共 " + preview.samples().size() + " 格，只列前 5 格");
                            break;
                        }
                        lines.add("  样本 (" + cell.x() + ", " + cell.y() + ", " + cell.z() + ") → "
                                + cell.state());
                    }
                    done.accept(new EndLayoutUpgradeCommand.Preview(preview.status().name(),
                            preview.ok() ? preview.token() : null, lines, preview.snapshot()));
                });
            }

            @Override
            public void apply(World world, String token,
                              java.util.function.Consumer<EndLayoutUpgradeCommand.Result> done) {
                backend.remove(world, token, result ->
                        done.accept(new EndLayoutUpgradeCommand.Result(result.ok(),
                                result.status() + " —— " + result.detail()
                                        + "（回填 " + result.restored() + " 格，保留 " + result.kept() + " 格）",
                                result.archived(), result.restored(), result.kept(),
                                EndLayoutUpgradeCommand.zeroWriteStatus(result.status().name()))));
            }
        };
    }

    /** 命令作用的世界：执行者自己的世界优先，控制台退到「本插件生成的末地」。 */
    @Nullable
    private World upgradeWorld(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return entity.getWorld();
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.THE_END
                    && world.getGenerator() instanceof DustWastelandsGenerator) {
                return world;
            }
        }
        return null;
    }

    @Override
    public void onDisable() {
        // 本版只启用了柱生命周期；中心楼与建筑四晶座在 onEnable 里就没被构造，所以这里没有它们要关。
        // 关它不会回收实体、不改龙战状态（水晶与阶段都是服务端/玩家的）。
        if (pillars != null) {
            pillars.close();
            pillars = null;
        }
        // 清理服务也要先停排任务，再让平台取消任务队列。
        if (removal != null) {
            removal.shutdown();
            removal = null;
        }
        removeService = null;
        if (reloads != null) reloads.shutdown();
        Bukkit.getAsyncScheduler().cancelTasks(this);
        packsInstalled = false;
    }

    /**
     * Seeds config.yml from the jar on first run, adds keys a newer build introduced without touching
     * values the owner set, and reads the result.
     *
     * @return true when a usable config is now in {@link #config}; false when the file is unusable, in
     *         which case the plugin must not enable
     */
    private boolean loadConfig() {
        try {
            ReloadCoordinator.ConfigRead read = readConfig();
            config = read.config();
            startupValues = frozen(read.values());
            live.set(new ReloadCoordinator.Live(messages, config.monsterDamageMultiplier(), startupValues));
            return true;
        } catch (IOException | RuntimeException error) {
            getComponentLogger().error(texts().get("config.invalid"), error);
            config = null;
            return false;
        }
    }

    /** Starts file work off the caller's tick thread; the coordinator owns single-flight and publication. */
    private void reload(@NotNull CommandSender sender) {
        if (reloads == null) {
            ((Audience) sender).sendMessage(texts().get("command.reload.failed",
                    Placeholder.unparsed("reason", "重载服务未初始化")));
            return;
        }
        // Capture a command block's position on its owner thread, never from the asynchronous callback.
        Location source = sender instanceof BlockCommandSender block ? block.getBlock().getLocation() : null;
        reloads.reload(lines -> reply(sender, source, lines));
    }

    /** Parses the merged values before ConfigStore is allowed to back up or replace the user's file. */
    private ReloadCoordinator.ConfigRead readConfig() throws IOException {
        Path file = getDataFolder().toPath().resolve("config.yml");
        boolean absent = !Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        AtomicReference<BetterEndConfig> parsed = new AtomicReference<>();
        ConfigStore.Loaded loaded = ConfigStore.of(file, () -> getResource("config.yml"),
                "config.yml", getLogger()).load(values -> parsed.set(
                        BetterEndConfig.load(values::get, getLogger()::warning)));
        return new ReloadCoordinator.ConfigRead(parsed.get(), loaded.values(), loaded.bundledVersion(), absent);
    }

    /** File completion and hash-qualified upgrades only: this never triggers a server-wide CE reload. */
    private ReloadCoordinator.PackResult completePacks(@NotNull BetterEndConfig fresh) {
        if (craftEngine == null) return ReloadCoordinator.PackResult.failed("CraftEngine 未启用");
        try {
            List<ReloadReport.PackLine> lines = new ArrayList<>();
            for (CraftEngineBridge.PackReport report : craftEngine.installBundledPacks()) {
                lines.add(new ReloadReport.PackLine(report.pack(), report.firstInstall(),
                        report.released(), report.skipped(), report.upgraded(), report.kept()));
            }
            return ReloadCoordinator.PackResult.completed(lines);
        } catch (IOException | RuntimeException error) {
            return ReloadCoordinator.PackResult.failed(reason(error));
        }
    }

    /** Help is a local reply, not an administrative action to echo into the server log. */
    private void showHelp(CommandSender sender) {
        HelpReport.lines(texts()).forEach(line -> ((Audience) sender).sendMessage(line));
    }

    /** Routes one finished batch to its owning scheduler; a disconnected player's report stays in the log. */
    private void reply(CommandSender sender, @Nullable Location source, List<Component> lines) {
        boolean console = sender instanceof ConsoleCommandSender;
        if (!console) lines.forEach(line -> getComponentLogger().info(line));
        if (!isEnabled()) return;
        Runnable delivery = () -> {
            if (isEnabled()) lines.forEach(line -> ((Audience) sender).sendMessage(line));
        };
        try {
            if (sender instanceof Entity entity) {
                RegionSchedulerAdapter.reply(this, entity, delivery, null);
            } else if (source != null) {
                RegionSchedulerAdapter.run(this, source, delivery);
            } else {
                RegionSchedulerAdapter.replyGlobal(this, delivery);
            }
        } catch (RuntimeException refused) {
            if (console) lines.forEach(line -> getComponentLogger().info(line));
            getLogger().log(Level.WARNING, "重载报告无法投递，结果已保留到控制台。", refused);
        }
    }

    private Messages texts() {
        return live.get().messages();
    }

    /** An unmodifiable snapshot that tolerates YAML null leaves. */
    private static Map<String, Object> frozen(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    /** Used by both startup and reload; a reload failure is handled without discarding the live text. */
    private Messages readMessages() throws IOException {
        return new Messages(ConfigStore.of(getDataFolder().toPath().resolve("messages.yml"),
                () -> getResource("messages.yml"), "messages.yml", getLogger()).load().values());
    }

    private Messages loadMessages() {
        try {
            return readMessages();
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.WARNING, "messages.yml 无法读取，将直接以键名作为文本输出。", error);
            return new Messages(Map.of());
        }
    }

    /** Reports what {@link CraftEngineBridge#forceImmediateResourceLoad()} did, in the operator's words. */
    private void reportImmediateLoad(CraftEngineBridge.ImmediateLoad outcome) {
        switch (outcome) {
            case SWITCHED -> getComponentLogger().info(texts().get("craftengine.immediate-load"));
            case ALREADY_IMMEDIATE -> getComponentLogger().info(texts().get("craftengine.immediate-load-already"));
            case FAILED -> getComponentLogger().error(texts().get("craftengine.immediate-load-failed"));
        }
    }

    /** The unregistered critical ids, for a failure message; a broken CraftEngine API degrades to a note. */
    private String describeMissingContent() {
        try {
            return String.join(", ", craftEngine.missingCriticalContent());
        } catch (RuntimeException | LinkageError error) {
            return "(CraftEngine API 不可用：" + error.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Opt-in Bukkit generator; world owners select it with {@code generator=BetterEnd:betterend}.
     * <p>
     * Returning {@code null} does NOT stop generation: Paper/Folia falls back to the vanilla generator
     * for this world, which will save vanilla terrain into its region files. Every branch below says so
     * plainly -- "stop the server and fix it" -- and never claims the world was refused.
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (config == null) {
            // loadConfig() already reported the cause; returning null means the vanilla generator runs.
            getLogger().severe("BetterEnd 不可用：config.yml 无效。Paper/Folia 可能为世界 '" + worldName
                    + "' 回退原版生成器，请停止服务器并修正 config.yml 后再生成世界。");
            return null;
        }
        if (id != null && !id.isEmpty() && !id.equalsIgnoreCase("betterend")) {
            getLogger().severe("未知的 BetterEnd 生成器 id '" + id + "'（世界 '" + worldName
                    + "'），只接受 'betterend'。BetterEnd 不可用，Paper/Folia 可能回退原版生成器，"
                    + "请停止服务器并修正后再生成世界。");
            return null;
        }
        // The same readiness gate as onEnable: this is the moment a world's chunks can first be baked,
        // so refuse it here too with the honest reason rather than let Paper quietly run vanilla.
        if (craftEngine != null && !craftEngine.contentReady()) {
            getLogger().severe("BetterEnd 未就绪：CraftEngine 尚未注册世界生成必需的方块/物品（"
                    + describeMissingContent() + "）。世界 '" + worldName + "' 可能回退原版生成器，"
                    + "请停止服务器并修正后再生成世界。");
            return null;
        }
        try {
            return new DustWastelandsGenerator(config);
        } catch (IllegalStateException | LinkageError error) {
            // Returning null means Paper/Folia runs the vanilla generator for this world -- vanilla
            // terrain, not BetterEnd terrain. Say that, and say how to fix it. Deferred loading is NOT
            // the cause here: onLoad already selected CraftEngine's immediate branch and the readiness
            // gate above already passed, so a block the constructor still cannot resolve is a pack
            // problem, not a timing one.
            getLogger().severe("BetterEnd 未就绪（" + error.getMessage() + "）：世界 '" + worldName
                    + "' 可能回退原版生成器，请停止服务器并修正后再生成世界；"
                    + "这通常意味着 CraftEngine 资源包未提供该方块，请检查 plugins/CraftEngine/ 下的资源包与 CraftEngine 日志。");
            return null;
        }
    }
}
