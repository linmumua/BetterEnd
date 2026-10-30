package dev.mumu.betterend.biome;

import dev.mumu.betterend.CraftEngineBlockCapacity;
import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.datapack.DatapackRegistrar;
import io.papermc.paper.datapack.DiscoveredDatapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Hands the server the datapacks bundled inside the plugin jar: the BetterEnd biome pack, the DnT
 * End Castle pack and the Echoes end-structures pack.
 * <p>
 * This is the whole delivery mechanism, and it has to be a {@link PluginBootstrap}: biomes are a
 * worldgen registry, the worldgen registries are built and frozen inside {@code WorldLoader.load}
 * during {@code Main.main}, and plugins are not loaded until several steps later. The only hook
 * that fires early enough is {@code DATAPACK_DISCOVERY}, which Paper types on
 * {@link BootstrapContext} and nothing else (LifecycleEvents.java:42), and only a
 * {@code paper-plugin.yml} can declare a bootstrapper (PluginFileType.java:28-45).
 * <p>
 * All three packs are <em>mandatory</em>: without the biome pack the End generates with vanilla
 * biomes, without the castle pack nothing places the castle or its ship, and without the Echoes pack
 * the nine migrated end structures never appear. So a pack that fails to
 * register is not allowed to be a silent no-op. {@link #refusalReason()} carries that failure out of
 * this phase, and the main class must refuse to enable on a non-null answer:
 * <pre>{@code
 * String refusal = BiomeDatapack.refusalReason();
 * if (refusal != null) throw new IllegalStateException(refusal);
 * }</pre>
 * A static field is enough to cross the bootstrap/enable boundary because Paper loads both phases
 * through one classloader -- {@code PluginBootstrap} documents "Your bootstrap class will be on the
 * same classloader as your JavaPlugin" (PluginBootstrap.java), and {@code createPlugin} instantiates
 * the main class with {@code this.getClass().getClassLoader()}. The field is volatile: written on
 * the worldgen thread, read on the main thread.
 * <p>
 * The handler is registered unconditionally and is idempotent on purpose: this event fires on every
 * {@code PackRepository.reload()}, and a pack not re-discovered on every fire disappears from the
 * server (DatapackRegistrar.java:20-22). A one-shot guard would delete every pack on {@code /reload};
 * instead each fire recomputes {@link #refusal} from scratch. Installing the plugin on a running
 * server does nothing for biomes or structures until a full restart ({@code Datapack.setEnabled}
 * routes to {@code reloadResources}, which passes the registries through untouched); worlds created
 * later inherit the biomes for free, because the biome registry is server-global, not per-world.
 */
public final class BiomeDatapack implements PluginBootstrap {
    /** The file every pack must contain, and the entry we probe to find the pack's own directory. */
    private static final String PACK_META = "pack.mcmeta";

    /**
     * The packs shipped inside the jar, in discovery order. The biome pack lives under
     * {@code datapack}, the DnT castle pack under {@code datapacks/dnt-end-castle} and the Echoes
     * end-structures pack under {@code datapacks/echoes-end-structures}; all are produced into
     * {@code plugin/src/main/resources} and land on the classpath as written.
     */
    static final List<BundledPack> BUNDLED_PACKS = List.of(
            new BundledPack("/datapack", "biomes"),
            new BundledPack("/datapacks/dnt-end-castle", "dnt-end-castle"),
            new BundledPack("/datapacks/echoes-end-structures", "echoes-end-structures"));

    /** The state before any discovery pass has run; also the state a half-finished pass leaves behind. */
    private static final String NEVER_DISCOVERED =
            "数据包发现阶段（DATAPACK_DISCOVERY）从未完成：无法确认群系包、城堡包与末地结构包已注册，"
                    + "拒绝启用。请用一次完整的服务器启动加载本插件，而不是热加载或仅执行 /reload。";

    /**
     * {@code null} once a discovery pass registered every {@link #BUNDLED_PACKS pack}; otherwise the
     * operator-facing reason to refuse enabling. Starts non-null so that a plugin that somehow reaches
     * enable without a discovery pass (or whose pass died mid-way) refuses rather than pretends.
     */
    private static volatile String refusal = NEVER_DISCOVERED;

    @Override
    public void bootstrap(BootstrapContext context) {
        // Registered before our own discovery handler below, and on the SAME event but with a negative
        // priority, so it runs before CraftEngine's own DATAPACK_DISCOVERY handler (default priority 0)
        // -- the handler that sizes CraftEngine's block arrays. See CraftEngineBlockCapacity for why the
        // capacity has to be raised in that exact window, and why it is written reflectively. This is an
        // independent handler on purpose: it must not be folded into the pack-discovery handler below,
        // whose priority is the default and would therefore run too late.
        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.DATAPACK_DISCOVERY
                        .newHandler(event -> CraftEngineBlockCapacity.ensureCapacity(context.getLogger()))
                        .priority(CraftEngineBlockCapacity.PRIORITY));
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            // Reset first: whatever happens below, a pass that throws must leave the "unknown/failed"
            // state behind, never a stale success from an earlier pass.
            refusal = NEVER_DISCOVERED;
            List<String> failed = new ArrayList<>();
            for (BundledPack pack : BUNDLED_PACKS) {
                try {
                    if (!discover(context, event.registrar(), pack)) {
                        failed.add(pack.packId());
                    }
                } catch (Exception error) {
                    // Caught, recorded, and carried to enable -- not swallowed. One pack's failure must
                    // not stop the other pack from registering, and must not tear down the worldgen
                    // load this handler runs inside; the plugin failing to enable is the loud, safe end.
                    context.getLogger().error("注册数据包 " + pack.packId() + " 时出错。", error);
                    failed.add(pack.packId());
                }
            }
            refusal = failed.isEmpty() ? null
                    : "必需的配套数据包注册失败：" + String.join("、", failed)
                            + "。相关群系、城堡与末地结构将不会生成，请检查插件 jar 是否完整、"
                            + "pack.mcmeta 是否存在。";
        });
    }

    /**
     * Whether the plugin may enable, for the main class to consult from {@code onEnable}.
     * <p>
     * This merges two independent guards: this class's own discovery result ({@link #refusal}) and
     * {@link CraftEngineBlockCapacity}'s capacity result. They are kept in separate statics because this
     * handler resets {@link #refusal} to {@link #NEVER_DISCOVERED} on every discovery pass -- folding the
     * capacity failure in here would let that per-pass reset wipe it out. Either reason is enough to
     * refuse; both are reported when both are present.
     *
     * @return {@code null} only when both guards are satisfied; otherwise a Chinese, operator-facing
     *         reason to refuse enabling. Never silently {@code null} before a pass has run.
     */
    public static @Nullable String refusalReason() {
        String capacity = CraftEngineBlockCapacity.refusalReason();
        if (capacity == null) {
            return refusal;
        }
        return refusal == null ? capacity : refusal + " " + capacity;
    }

    /**
     * Discovers one pack and reports whether the server actually registered it.
     * <p>
     * A {@code null} from {@code discoverPack} is a real failure, not a no-op: Paper returns it when
     * the path is not a pack at all (its {@code detectPackResources} found no {@code pack.mcmeta}) or
     * when the pack metadata is unreadable (PaperDatapackRegistrar.java).
     */
    private static boolean discover(BootstrapContext context, DatapackRegistrar registrar, BundledPack pack)
            throws Exception {
        DiscoveredDatapack discovered = registrar.discoverPack(packDirectory(pack), pack.packId(),
                // Defaults to false, which would leave the pack out of every world.
                configurer -> configurer.autoEnableOnServerStart(true));
        if (discovered == null) {
            context.getLogger().error("数据包 " + pack.packId()
                    + " 被服务器拒绝（不是有效数据包），其内容不会加载。");
            return false;
        }
        // Its own try/catch: getCompatibility() is a diagnostic, and on Purpur 26.2 it
        // THROWS -- PaperDiscoveredDatapack does Compatibility.valueOf on a string the api enum has no
        // constant for ("UNKNOWN", when the server had to fall back on unreadable pack metadata).
        // Letting that escape aborted the whole handler and logged "Could not register" for a pack that
        // had in fact already registered. A broken diagnostic must never take out what it reports on.
        try {
            Datapack.Compatibility compatibility = discovered.getCompatibility();
            if (compatibility != Datapack.Compatibility.COMPATIBLE) {
                context.getLogger().warn("数据包 " + pack.packId() + " 在本服务器版本上报告为 "
                        + compatibility + "，其内容可能无法加载。");
            }
        } catch (RuntimeException ignored) {
            // Server reported a compatibility value this API build cannot name. The pack is registered
            // either way; nothing actionable to say.
        }
        return true;
    }

    /**
     * The pack directory's URI inside the running plugin.
     * <p>
     * Probed through {@value #PACK_META} rather than through the directory itself, because a
     * repackaged jar (shadowJar and friends) can carry no directory entry: {@code getResource(dir)}
     * then answers {@code null} while {@code getResource(dir + "/pack.mcmeta")} still resolves, and a
     * pack silently missing on such a jar is exactly the failure this class exists to prevent. The URI
     * is the shape Paper's {@code FileSystemUtil.safeGetPath} expects -- it opens the jar filesystem
     * from it -- and {@code URL.toURI} is safe here because {@code getResource} percent-encodes the
     * path, so spaces and non-ASCII directories survive.
     */
    static URI packDirectory(BundledPack pack) throws Exception {
        String probe = pack.resourcePath() + "/" + PACK_META;
        URL meta = BiomeDatapack.class.getResource(probe);
        if (meta == null) {
            throw new IllegalStateException("插件 jar 中找不到 " + probe + "，无法定位数据包目录。");
        }
        String uri = meta.toURI().toString();
        String suffix = "/" + PACK_META;
        if (!uri.endsWith(suffix)) {
            throw new IllegalStateException("从 " + uri + " 推导数据包目录失败。");
        }
        return URI.create(uri.substring(0, uri.length() - suffix.length()));
    }

    /**
     * One bundled datapack: the jar-absolute directory it lives in and the id Paper files it under
     * ({@code BetterEnd/<id>}). Validated on construction so a typo in {@link #BUNDLED_PACKS} fails
     * loudly here instead of silently registering nothing at server start.
     */
    record BundledPack(String resourcePath, String packId) {
        /** Paper's pack ids are resource locations: lowercase, and only {@code _.-} as separators. */
        private static final Pattern PACK_ID = Pattern.compile("[a-z0-9_.-]+");

        BundledPack {
            if (resourcePath == null || resourcePath.isBlank()
                    || !resourcePath.startsWith("/") || resourcePath.endsWith("/")) {
                throw new IllegalArgumentException(
                        "resourcePath must be a jar-absolute directory without a trailing slash: "
                                + resourcePath);
            }
            if (packId == null || !PACK_ID.matcher(packId).matches()) {
                throw new IllegalArgumentException(
                        "packId must match " + PACK_ID.pattern() + ": " + packId);
            }
        }
    }
}
