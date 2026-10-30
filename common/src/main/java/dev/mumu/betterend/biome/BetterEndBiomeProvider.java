package dev.mumu.betterend.biome;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Paints BetterEnd's biomes with {@link BiomePlacement}, over the biomes {@link BiomeDatapack}
 * registers.
 * <p>
 * Every {@link Biome} is resolved once, eagerly, in the constructor, on whatever thread loads the
 * world. That is not a style choice: {@code CraftRegistry} backs its lookups with a plain
 * unsynchronized {@code HashMap} (CraftRegistry.java:151,193,213), so resolving a key lazily from
 * a worldgen worker would be a data race. After construction this object is immutable and every
 * method is a lookup in a final map, which is what {@code BiomeProvider}'s "must be completely
 * thread safe" contract (BiomeProvider.java:16-18) needs on Paper and on Folia alike.
 * <p>
 * If the datapack is missing - the plugin was dropped onto a running server, the pack failed to
 * load, someone disabled it - every unresolved biome falls back to a vanilla End biome and the
 * constructor logs the whole list once. {@link #getBiome} never throws, never returns null, and
 * never logs.
 */
public final class BetterEndBiomeProvider extends BiomeProvider {
    /**
     * Vanilla stand-ins, used only when a custom biome will not resolve. All three are END_BARRENS
     * on purpose: it is the only vanilla End biome with no placed features of its own, and
     * {@code shouldGenerateDecorations()} is now true (DustWastelandsGenerator.java), so
     * END_HIGHLANDS / SMALL_END_ISLANDS here would paste vanilla chorus plants and
     * {@code end_island_decorated} blobs over the whole world in the degraded no-datapack mode.
     * The map is kept per-category rather than collapsed to a constant so a later category can
     * differ without reopening this decision.
     */
    private static final Map<BiomeSurface.Category, Biome> FALLBACK = Map.of(
            BiomeSurface.Category.LAND, Biome.END_BARRENS,
            BiomeSurface.Category.SMALL_ISLAND, Biome.END_BARRENS,
            BiomeSurface.Category.CAVE, Biome.END_BARRENS);

    private final BiomePlacement placement;
    private final Map<BiomeSurface, Biome> resolved;
    private final List<Biome> declared;

    /**
     * Takes the placement rather than building one, so the chunk generator, this provider and the
     * tree populator share one island field, one land memo and one plane cache -- and so the only
     * thread that ever runs this constructor is the one that loads the world
     * (DustWastelandsGenerator.java:126-133). Never call it from generateNoise: the registry read
     * below is the race the class javadoc forbids.
     */
    public BetterEndBiomeProvider(BiomePlacement placement) {
        this.placement = placement;
        this.resolved = new EnumMap<>(BiomeSurface.class);

        Registry<Biome> registry = biomeRegistry();
        List<String> missing = new ArrayList<>();
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            Biome found = registry == null ? null : registry.get(NamespacedKey.fromString(biome.key()));
            if (found == null) {
                missing.add(biome.key());
                found = FALLBACK.get(biome.category());
            }
            resolved.put(biome, found);
        }
        if (!missing.isEmpty()) {
            Logger.getLogger("BetterEnd").warning(
                    "The BetterEnd biome datapack is not loaded, so " + missing.size() + " biomes fell back to"
                            + " vanilla End biomes: " + String.join(", ", missing)
                            + ". Datapack biomes only enter the registry at server startup, so restart the server"
                            + " with the plugin installed - enabling the pack on a running server will not help.");
        }

        // THE_END is painted over the vanilla core and the center ring, END_BARRENS over the
        // barrens ring (and it is the FALLBACK above for every category when the datapack did not
        // load), so both belong in the declared set alongside everything the pickers can produce.
        // Contract: getBiomes must name every biome getBiome can return.
        LinkedHashSet<Biome> unique = new LinkedHashSet<>(resolved.values());
        unique.add(Biome.THE_END);
        unique.add(Biome.END_BARRENS);
        this.declared = List.copyOf(unique);
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo world, int x, int y, int z) {
        // The vanilla rings, which BetterEnd does not paint: inside radius 1024 the mod's own
        // center / barrens pickers hold exactly one biome each and both are vanilla
        // (WoverEndBiomeSource.java:249-256,:358-360), and inside VanillaEndCore.CORE_RADIUS the
        // answer is the_end unconditionally so the vanilla spike ring cannot lose a pillar.
        // BiomePlacement.ring folds both together and is the same call
        // DustWastelandsGenerator.surfaceAt makes per block.
        BiomePlacement.Ring ring = placement.ring(x, z);
        if (ring != null) {
            return ring == BiomePlacement.Ring.CENTER ? Biome.THE_END : Biome.END_BARRENS;
        }
        return resolved.get(placement.at(x, y, z));
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo world) {
        return declared;
    }

    /**
     * Null rather than an exception when the registry is not up yet: Paper marks BIOME delayed
     * (PaperRegistries.java:129) and throws IllegalArgumentException until the datapack load has
     * built it. A provider constructed that early still has to hand back a usable world.
     */
    private static Registry<Biome> biomeRegistry() {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        } catch (IllegalStateException | IllegalArgumentException error) {
            return null;
        }
    }
}
