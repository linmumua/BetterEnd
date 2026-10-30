package dev.mumu.betterend;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.yaml.route.Route;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Resolves the bundled CraftEngine packs to a {@link PackInstaller.Source} and installs them under
 * {@code plugins/CraftEngine/resources/} so the server owns them as ordinary packs.
 * <p>
 * Two packs ship with the plugin and BOTH are required:
 * <ul>
 *   <li>{@value #BETTEREND_PACK} -- the terrain/blocks/items/resource pack;</li>
 *   <li>{@value #DNT_END_CASTLE_PACK} -- the DnT End Castle content, kept separate so its
 *       {@code minecraft} item/model overrides cannot collide with the main pack's.</li>
 * </ul>
 * A build that is missing either one, or that fails to write one, is not a working build: this class
 * throws rather than letting the plugin come up with a half-installed pack. The single source of
 * truth for "how a pack is written" is {@link PackInstaller}, which is unit-tested without a server.
 *
 * <h2>When this runs, and why onLoad is early enough to write the packs</h2>
 * The pack install is called from {@code BetterEndPlugin.onLoad}. Paper's plugin lifecycle runs a
 * separate bootstrap phase BEFORE any plugin's {@code onLoad}: CraftEngine's bootstrapper
 * ({@code PaperCraftEngineBootstrap.bootstrap}) builds the engine and calls
 * {@code BukkitCraftEngine.setUpConfigAndLocale}, which reads {@code config.yml} into memory -- it
 * appends no packs, but it does create the data folder and the live settings this bridge later edits.
 * Then every plugin's {@code onLoad} runs, and only after that does any {@code onEnable} run. So the
 * files written here are on disk before CraftEngine's enable scans them. The declared
 * {@code load: BEFORE} on our CraftEngine dependency does NOT merely order {@code onLoad}: Paper
 * resolves it (via {@code LoadOrderTree}/{@code PaperPluginMeta.getLoadAfterPlugins}) into a load
 * order in which CraftEngine loads, and therefore enables, BEFORE BetterEnd, and
 * {@code CraftServer.enablePlugins} enables plugins in exactly that array order. Both facts matter --
 * see {@link #forceImmediateResourceLoad()}.
 *
 * <h2>Closing the deferred-registration gap</h2>
 * CraftEngine ships {@code misc.delay-configuration-load: true}. With that default its enable path
 * does NOT register blocks/items synchronously: it hands the work to the first server tick, which is
 * already past the spawn chunks -- a chunk generated in between bakes plain end stone forever, and
 * the generator's {@code require(...)} then throws. Because CraftEngine enables before us (above), we
 * flip that one in-memory setting to false during {@code onLoad}; CraftEngine then takes its OWN
 * immediate branch and registers resources during its enable, still before any world is created. This
 * touches no file and issues no delayed task of ours -- it only makes CraftEngine use the branch it
 * already has. That is {@link #forceImmediateResourceLoad()}, and {@link #contentReady()} is the
 * verified result.
 */
public final class CraftEngineBridge {
    /** Pack directory names under {@code plugins/CraftEngine/resources}. */
    public static final String BETTEREND_PACK = "betterend";
    public static final String DNT_END_CASTLE_PACK = "dnt_end_castle";

    /** Both are required; a missing one is a broken build, not a degraded one. */
    private static final List<String> REQUIRED_PACKS = List.of(BETTEREND_PACK, DNT_END_CASTLE_PACK);
    private static final String RESOURCE_PREFIX = "craftengine/";
    private static final String CRAFT_ENGINE = "CraftEngine";

    /**
     * The CraftEngine setting, at its dotted path inside {@code plugins/CraftEngine/config.yml}, that
     * defers resource registration to the first server tick when true. CraftEngine ships it as true;
     * the immediate branch that this bridge selects is the engine's own.
     */
    private static final String DELAY_CONFIGURATION_LOAD = "misc.delay-configuration-load";

    /**
     * The block ids the world cannot open without, mirroring {@code DustWastelandsGenerator.require}
     * -- the one set a chunk generated before registration bakes as plain end stone. Checked here so
     * the plugin can tell "CraftEngine registered its pack" from "CraftEngine is merely enabled".
     */
    private static final List<String> CRITICAL_BLOCK_IDS = List.of(
            "betterend:flavolite", "betterend:thallasium_ore", "betterend:ender_ore", "betterend:endstone_dust");

    /**
     * The one critical id also queried through the item registry: {@code endstone_dust} is the surface
     * block every dusty biome is capped with and ships a matching block item, so it must resolve as
     * BOTH a block and an item before the pack counts as registered.
     */
    private static final String CRITICAL_ITEM_ID = "betterend:endstone_dust";

    private final JavaPlugin plugin;

    public CraftEngineBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** The CraftEngine plugin, or {@code null} when it is not installed. Present != enabled. */
    public Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin(CRAFT_ENGINE);
    }

    /** True only when CraftEngine is present AND enabled -- the gate for actually enabling. */
    public boolean available() {
        Plugin craftEngine = plugin();
        return craftEngine != null && craftEngine.isEnabled();
    }

    /** What {@link #forceImmediateResourceLoad()} did. */
    public enum ImmediateLoad {
        /** CraftEngine already had deferred loading off -- nothing to change. */
        ALREADY_IMMEDIATE,
        /** The in-memory flag was flipped and re-read as off; CraftEngine will register on enable. */
        SWITCHED,
        /** The setting could not be observed or flipped; the caller must not assume registration. */
        FAILED
    }

    /**
     * The two CraftEngine reads/writes the immediate-load switch needs, as a seam so the decision is
     * unit-tested without a running server.
     */
    public interface DelaySeam {
        /** CraftEngine's current {@code misc.delay-configuration-load}. */
        boolean delayConfigurationLoad();

        /** Flip the in-memory setting to false and refresh the engine's cached value. */
        void applyImmediate();
    }

    /**
     * Switches CraftEngine to its immediate (non-deferred) resource registration, so blocks and items
     * exist by the end of CraftEngine's enable instead of on the first tick.
     * <p>
     * This edits CraftEngine's in-memory settings only -- {@code plugins/CraftEngine/config.yml} is
     * never written, so the operator's file is untouched. Because CraftEngine enables before BetterEnd
     * and this runs in our {@code onLoad}, the flip lands before the engine reads the flag. If it
     * cannot be flipped, the outcome is {@link ImmediateLoad#FAILED}: the caller must treat content as
     * unregistered rather than pretend it succeeded. The pure decision lives in
     * {@link #forceImmediateLoad(DelaySeam, Consumer)} so it can be tested without CraftEngine.
     */
    public ImmediateLoad forceImmediateResourceLoad() {
        if (plugin() == null) {
            return ImmediateLoad.FAILED;
        }
        try {
            return forceImmediateLoad(new DelaySeam() {
                @Override
                public boolean delayConfigurationLoad() {
                    return Config.delayConfigurationLoad();
                }

                @Override
                public void applyImmediate() {
                    Config config = CraftEngine.instance().config();
                    config.settings().set(Route.dotted(DELAY_CONFIGURATION_LOAD), Boolean.FALSE);
                    config.loadFullSettings();
                }
            }, error -> plugin.getLogger().log(Level.WARNING,
                    "Could not switch CraftEngine to immediate resource loading", error));
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING,
                    "CraftEngine API unavailable while switching to immediate resource loading", error);
            return ImmediateLoad.FAILED;
        }
    }

    /**
     * The decision behind {@link #forceImmediateResourceLoad()}, with no CraftEngine dependency: read
     * the flag; if it is already off do nothing; otherwise flip it and read it back, so a flip that
     * silently did not take effect is reported as {@link ImmediateLoad#FAILED}, not success.
     */
    static ImmediateLoad forceImmediateLoad(DelaySeam seam, Consumer<Throwable> onFailure) {
        try {
            if (!seam.delayConfigurationLoad()) {
                return ImmediateLoad.ALREADY_IMMEDIATE;
            }
            seam.applyImmediate();
            return seam.delayConfigurationLoad() ? ImmediateLoad.FAILED : ImmediateLoad.SWITCHED;
        } catch (RuntimeException | LinkageError error) {
            onFailure.accept(error);
            return ImmediateLoad.FAILED;
        }
    }

    /**
     * The CraftEngine registries the readiness check reads, as a seam so the check is unit-tested
     * without a running server.
     */
    public interface ContentRegistry {
        boolean blockRegistered(String id);

        boolean itemRegistered(String id);
    }

    /**
     * The critical content ids CraftEngine has NOT registered yet, empty when the pack is live.
     * <p>
     * This is what distinguishes "registered" from {@code Plugin.isEnabled}: an enabled CraftEngine
     * with {@code misc.delay-configuration-load} still true (or a pack that failed to parse) answers
     * nothing for these ids, and a world generated then would bake plain end stone. The list is the
     * three blocks {@code DustWastelandsGenerator.require} throws on plus {@code endstone_dust} as
     * both a block and an item; it is resolved per call, never cached, so a later CraftEngine reload
     * is observed.
     */
    public List<String> missingCriticalContent() {
        return missingCriticalContent(new ContentRegistry() {
            @Override
            public boolean blockRegistered(String id) {
                return CraftEngineBlocks.byId(Key.of(id)) != null;
            }

            @Override
            public boolean itemRegistered(String id) {
                return CraftEngineItems.byId(Key.of(id)) != null;
            }
        });
    }

    /** The pure readiness decision behind {@link #missingCriticalContent()}. */
    static List<String> missingCriticalContent(ContentRegistry registry) {
        List<String> missing = new ArrayList<>();
        for (String id : CRITICAL_BLOCK_IDS) {
            if (!registry.blockRegistered(id)) {
                missing.add("block " + id);
            }
        }
        if (!registry.itemRegistered(CRITICAL_ITEM_ID)) {
            missing.add("item " + CRITICAL_ITEM_ID);
        }
        return missing;
    }

    /**
     * True when every critical id above is registered. A false here is a real "not registered", never
     * a timing guess: it is evaluated on live CraftEngine state, not on {@code isFullyLoaded} (which
     * stays false until the second tick even on the immediate path).
     */
    public boolean contentReady() {
        if (plugin() == null) {
            return false;
        }
        try {
            return missingCriticalContent().isEmpty();
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING,
                    "CraftEngine API unavailable while checking registered content", error);
            return false;
        }
    }

    /**
     * The outcome of installing one pack.
     * <p>
     * {@code released}/{@code skipped}/{@code failed} are {@link PackInstaller}'s completion counts;
     * {@code upgraded}/{@code kept} are {@link BundledPackUpgrades}'s exact-hash upgrade counts, so the
     * reload report can say "3 files replaced, 1 kept because you edited it" instead of hiding the
     * difference between a fresh file and a corrected default that replaced an old one. Pack folders
     * that have no upgrade pass ({@value #DNT_END_CASTLE_PACK}) always report {@code 0/0}.
     * <p>
     * The five-argument constructor is kept so a caller written against the earlier shape still
     * compiles; it reports no upgrade activity, which is the honest default for a caller that did not
     * ask for it.
     */
    public record PackReport(String pack, boolean firstInstall, int released, int skipped, int failed,
                             int upgraded, int kept) {
        /** The pre-upgrade-report shape, for callers that only know about the completion counts. */
        public PackReport(String pack, boolean firstInstall, int released, int skipped, int failed) {
            this(pack, firstInstall, released, skipped, failed, 0, 0);
        }
    }

    /**
     * Composes one pack's report from its install pass and (when the pack has one) its upgrade pass --
     * pure, so the field mapping is unit-tested without a server or a jar.
     *
     * @param upgrade the upgrade pass's counts, or {@code null} for a pack with no upgrade pass
     */
    static PackReport packReport(String pack, PackInstaller.Result install, BundledPackUpgrades.Result upgrade) {
        return new PackReport(pack, install.firstInstall(), install.released(), install.skipped(),
                install.failed(), upgrade == null ? 0 : upgrade.upgraded(), upgrade == null ? 0 : upgrade.kept());
    }

    /**
     * Installs every required pack, in order.
     *
     * @return one report per pack, all with {@code failed == 0}
     * @throws IOException if CraftEngine is absent, a required pack is not bundled in this build, or
     *                     any file could not be written -- the caller must treat all of these as "the
     *                     packs are not installed" and refuse to enable
     */
    public List<PackReport> installBundledPacks() throws IOException {
        Plugin craftEngine = plugin();
        if (craftEngine == null) {
            throw new IOException("CraftEngine 未安装");
        }
        Path resources = craftEngine.getDataFolder().toPath().resolve("resources");
        List<PackReport> reports = new ArrayList<>(REQUIRED_PACKS.size());
        for (String pack : REQUIRED_PACKS) {
            reports.add(installPack(resources, pack));
        }
        return reports;
    }

    private PackReport installPack(Path resourcesRoot, String pack) throws IOException {
        String resourceRoot = RESOURCE_PREFIX + pack;
        Map<String, String> entries = enumerate(resourceRoot);
        if (entries.isEmpty()) {
            throw new IOException("本构建未内置 CraftEngine 资源包 '" + resourceRoot
                    + "'；两个资源包都必须内置，无法启用。");
        }
        PackInstaller.Source source = new JarPackSource(resourceRoot, entries);
        Path target = resourcesRoot.resolve(pack);
        PackInstaller.Result result = new PackInstaller(pack, plugin.getLogger())
                .install(target, source);
        if (result.failed() > 0) {
            throw new IOException("CraftEngine 资源包 '" + pack + "' 安装失败（" + result.failed()
                    + " 个文件），拒绝以不完整的资源包启用。");
        }
        // The never-overwrite contract above means a corrected bundled default cannot reach a server
        // that already has the old one. Land the named, exact-hash upgrades -- only pristine old
        // defaults, never a file the owner edited; back the replaced file up under BetterEnd's own
        // data folder. Backups live here, not in CraftEngine's config tree, so they are never
        // re-parsed as live resources.
        // The production whitelist is betterend's own: applying it to the DnT End Castle pack could
        // collide on a same-named relative path, so only BETTEREND_PACK is upgraded.
        BundledPackUpgrades.Result upgrade = null;
        if (BETTEREND_PACK.equals(pack)) {
            upgrade = BundledPackUpgrades.production().upgrade(
                    target, source,
                    plugin.getDataFolder().toPath().resolve("resource-backups").resolve(pack),
                    plugin.getLogger());
            if (upgrade.failed() > 0) {
                throw new IOException("CraftEngine 资源包 '" + pack + "' 的内置资源升级失败（" + upgrade.failed()
                        + " 个文件），拒绝以可能仍是旧默认的资源启用。");
            }
        }
        // The upgrade has landed by now, so its counts are real: report them instead of dropping them,
        // which is what let the reload report claim "0 新增" for a pack whose defaults were replaced.
        return packReport(pack, result, upgrade);
    }

    /** A pack whose bytes live inside the plugin jar, opened by full resource name. */
    private final class JarPackSource implements PackInstaller.Source {
        private final String resourceRoot;
        private final Map<String, String> entries;

        private JarPackSource(String resourceRoot, Map<String, String> entries) {
            this.resourceRoot = resourceRoot;
            this.entries = entries;
        }

        @Override
        public Set<String> paths() {
            return entries.keySet();
        }

        @Override
        public InputStream open(String relativePath) {
            return plugin.getResource(resourceRoot + "/" + relativePath);
        }
    }

    /**
     * Lists a bundled resource directory as {@code relativePath -> fullResourceName}.
     * <p>
     * Handles both shapes the plugin can run in -- an exploded {@code file:} directory (IDE/dev) and a
     * {@code jar:} archive (production) -- and, because a jar need not carry explicit directory
     * entries, falls back to scanning the owning archive directly when the directory URL is absent.
     * An empty result means "this build does not bundle the pack".
     */
    private Map<String, String> enumerate(String resourceRoot) throws IOException {
        String prefix = resourceRoot + "/";
        Map<String, String> found = new LinkedHashMap<>();
        URL url = plugin.getClass().getClassLoader().getResource(resourceRoot);
        if (url != null && "file".equals(url.getProtocol())) {
            Path sourceRoot = toPath(url);
            if (Files.isDirectory(sourceRoot)) {
                try (var walk = Files.walk(sourceRoot)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
                        found.put(relative, prefix + relative);
                    }
                }
            }
            return found;
        }
        try (JarFile jar = openOwningJar(url)) {
            if (jar == null) {
                return found;
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }
                found.put(name.substring(prefix.length()), name);
            }
        }
        return found;
    }

    private JarFile openOwningJar(URL directoryUrl) throws IOException {
        if (directoryUrl != null && "jar".equals(directoryUrl.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) directoryUrl.openConnection();
            connection.setUseCaches(false);
            return connection.getJarFile();
        }
        CodeSource source = CraftEngineBridge.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            return null;
        }
        URL location = source.getLocation();
        if (location == null || !"file".equals(location.getProtocol())) {
            return null;
        }
        Path archive = toPath(location);
        return Files.isRegularFile(archive) ? new JarFile(archive.toFile()) : null;
    }

    private static Path toPath(URL url) throws IOException {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException error) {
            throw new IOException("Invalid URL " + url, error);
        }
    }
}
