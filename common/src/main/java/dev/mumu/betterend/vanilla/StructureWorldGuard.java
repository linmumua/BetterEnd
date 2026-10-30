package dev.mumu.betterend.vanilla;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.mumu.betterend.DustWastelandsGenerator;
import io.papermc.paper.event.world.StructuresLocateEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureSpawnEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.structure.Structure;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Keeps vanilla End Cities out of the worlds <em>this</em> plugin generates, without editing the
 * biome tags every other End world also reads.
 *
 * <p>Enabling the structure stage (see {@link DustWastelandsGenerator#shouldGenerateStructures})
 * is what lets the bundled DnT castle and its ship generate, but it also re-enables the vanilla
 * {@code minecraft:end_city}: the padded biomes carry {@code has_structure/end_city}, so a workable
 * city would spawn on every BetterEnd island. Upstream avoided that by refusing to generate any
 * structure at all, which also threw the castle away -- not an option now. The honest levers are the
 * per-spawn event -- {@link AsyncStructureSpawnEvent} fires while the generator decides where a
 * structure goes, and cancelling it drops that one structure in that one world -- and, in front of
 * it, {@link StructuresLocateEvent}, so a <em>search</em> for a city this guard would never keep is
 * not run either. Nothing global is touched, so a second End dimension on the same server keeps its
 * own cities.
 *
 * <p>The guard is a self-selecting {@link Listener}, exactly like {@link VanillaEndCheck} beside it:
 * which worlds use this generator is a fact of the world, not of the plugin, so the world is asked
 * rather than a config list maintained. {@code WorldInitEvent} is the hook because
 * {@code World#getGenerator} is already set when it fires and nothing has generated yet -- see
 * {@code VanillaEndCheck}'s class javadoc for the {@code MinecraftServer}/{@code CraftServer} line
 * references -- so the spawn-chunk sweep of a fresh world is covered, not just the chunks a player
 * later walks into.
 *
 * <p>{@link AsyncStructureSpawnEvent} is fired from a worldgen worker, off the main thread, so the
 * handler does the only thing that is safe there: two comparisons against state registered earlier.
 * It reads no blocks, loads no chunk and touches no entity or player. {@link #managedWorlds} is a
 * concurrent set for that reason -- written on the world-init/unload thread, read on every worker.
 * The locate hook runs on whichever thread performs the search instead (the command thread for
 * {@code /locate}) and does exactly the same comparisons over the requested list; it allocates that
 * list's replacement and nothing else.
 */
public final class StructureWorldGuard implements Listener {
    /**
     * The one structure suppressed, addressed by the structure's own key rather than by
     * {@code Structure#getStructureType}. The type is the shared engine type -- the DnT castle and
     * ship are both a plain {@code jigsaw} -- so it names a class of structures, not one; only the
     * key singles out the vanilla city while leaving the castle alone.
     */
    private static final Key VANILLA_END_CITY = Key.key("minecraft", "end_city");

    /**
     * The Echoes pack still ships each structure twice: the author's definition stays registered
     * because a world generated before the rename resolves its saved structure starts against it.
     * {@code /locate structure} completes from the registry, not from the placement tables, so those
     * nine aliases show up next to the nine {@code mumu:} ids and every structure looks duplicated.
     * This table maps each alias to the id a player is meant to type;
     * {@link #canonicalSuggestions} applies it to the suggestion list only.
     *
     * <p>Built here rather than read from {@code structure_ids.py}: that module is the builder's
     * source of truth for what is <em>shipped</em>, and a plugin that parsed it at runtime would be
     * a data file away from a silent behaviour change. A structure added there without an entry here
     * simply keeps its legacy spelling in the completion list -- wrong, but harmless.
     *
     * <p>The two {@code nova_structures} entries are inert: the DnT pipeline now moves those
     * definitions into {@code mumu:} rather than duplicating them, so those ids are never
     * registered. They stay because the table is keyed by namespace and a future pack change could
     * bring a legacy id back; dropping them would only make that regression silent.
     */
    private static final Map<String, String> LEGACY_STRUCTURE_IDS = legacyStructureIds();

    private static Map<String, String> legacyStructureIds() {
        Map<String, String> ids = new HashMap<>(16);
        for (String name : List.of("crying_obsidian_tower", "end_camp", "end_dungeon_tower",
                "end_enchanted_temple", "end_pillar", "end_shulker_tower", "end_temple",
                "end_warship", "end_well")) {
            ids.put("echoes_of_the_end__structures_:" + name, "mumu:" + name);
        }
        ids.put("nova_structures:end_castle", "mumu:end_castle");
        ids.put("nova_structures:end_ship", "mumu:end_ship");
        return Map.copyOf(ids);
    }

    /**
     * The worlds whose generator is {@link DustWastelandsGenerator}, keyed by UUID (stable across a
     * rename, and read without allocating on the worldgen thread). One entry per managed world.
     */
    private final Set<UUID> managedWorlds = ConcurrentHashMap.newKeySet();

    /** Self-registers a world whose generator is ours; anything else is left alone. */
    @EventHandler
    public void onWorldInit(@NotNull WorldInitEvent event) {
        if (event.getWorld().getGenerator() instanceof DustWastelandsGenerator) {
            track(event.getWorld());
        }
    }

    /**
     * Drops a world's registration. Without this a world unloaded and replaced by a vanilla End of
     * the same name would keep its cancellation; with it, the replacement world re-decides at its own
     * {@code WorldInitEvent}.
     */
    @EventHandler
    public void onWorldUnload(@NotNull WorldUnloadEvent event) {
        untrack(event.getWorld());
    }

    /**
     * {@code Structure#key()} -- and its {@code getKey()} sibling -- is deprecated-for-removal since
     * 1.20.4, but it is the only per-structure identity the event still exposes on 26.3. The
     * alternative, {@link org.bukkit.generator.structure.Structure#getStructureType()}, names an engine type shared by every jigsaw
     * structure and cannot single out the vanilla city. Suppressed, not hidden: the choice is
     * deliberate and pinned to the target API version.
     */
    @SuppressWarnings("removal")
    @EventHandler
    public void onStructureSpawn(@NotNull AsyncStructureSpawnEvent event) {
        UUID world = event.getWorld().getUID();
        Key key = event.getStructure().key();
        if (shouldCancel(world, key)) {
            event.setCancelled(true);
            return;
        }
        // A ground structure whose footprint is mostly void got no platform (StructurePlatform
        // refuses it), so letting it spawn would leave half a building hanging off the island edge.
        // Pure arithmetic on the seed and the source chunk: no block read, safe on a worldgen worker.
        if (managedWorlds.contains(world) && "mumu".equals(key.namespace())
                && event.getWorld().getGenerator() instanceof DustWastelandsGenerator generator
                && generator.rejectsGroundStructure(event.getWorld().getSeed(), key.value(),
                        event.getChunkX(), event.getChunkZ())) {
            event.setCancelled(true);
        }
    }

    /**
     * The same suppression, applied <em>before</em> a search runs instead of after it produced a
     * structure. {@code StructuresLocateEvent} fires at {@code ChunkGenerator.findNearestMapStructure}
     * entry, ahead of every placement scan, storage read and synchronous chunk load a locate would
     * otherwise trigger -- so dropping the target here is what keeps {@code /locate end_city} from
     * sweeping a whole radius for a structure this guard would throw away anyway.
     *
     * <p>Three outcomes, all driven by {@link #locateTargets}: nothing of ours was requested (the
     * event is left untouched), a mixed request that keeps its other targets (the list is narrowed
     * and the search proceeds), or a request that was only ever the city (cancelled, so the search
     * never starts). The spawn guard above stays the authority: this is a fast path in front of it,
     * not a replacement -- a city placed by any other route is still dropped there.
     *
     * <p>{@link EventPriority#HIGHEST} and {@code ignoreCancelled}: highest so another plugin that
     * filters targets at a normal priority has already had its say and this guard filters what is
     * actually left, rather than being overwritten by a later {@code setStructures}; the cost of the
     * opposite order is a target list that no longer matches what was vetoed. {@code ignoreCancelled}
     * is the other half -- a search somebody else cancelled stays cancelled, this handler never
     * un-cancels one by reading the list and rewriting it.
     */
    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureLocate(@NotNull StructuresLocateEvent event) {
        List<Structure> requested = event.getStructures();
        List<Structure> kept = locateTargets(event.getWorld().getUID(), requested, Structure::key);
        if (kept == requested) {
            return; // nothing of ours in the request: the search is none of this guard's business
        }
        if (kept.isEmpty()) {
            event.setCancelled(true); // it was only ever the city, so there is nothing left to look for
            return;
        }
        event.setStructures(kept); // a mixed request: narrow it, keep searching for the rest
    }

    /**
     * Rewrites the legacy spellings out of the {@code /locate structure} completion list, so the
     * eleven structures appear once each under {@code mumu:}. Display only: the registry still holds
     * the legacy ids and {@code /locate structure nova_structures:end_castle} still parses and still
     * reports "not found" (nothing is placed under that id), which is exactly the behaviour an old
     * save needs.
     *
     * <p>This is the only public hook that sees the suggestion list the server is about to send.
     * {@code AsyncTabCompleteEvent} fires earlier but its completions are what Paper replaces the
     * official ones with, so filtering there would mean recomputing registry completions by hand;
     * {@code AsyncPlayerSendCommandsEvent} only carries the command tree, and a structure id is a
     * runtime registry value rather than a tree node.
     *
     * <p>{@link EventPriority#HIGHEST} and {@code ignoreCancelled} for the same reasons as the locate
     * filter above: filter what other plugins left, never resurrect a suggestion list they cancelled.
     * The handler reads the buffer and the list and nothing else -- no world, no registry, no player
     * state -- because the event is asynchronous whenever another plugin marks
     * {@code AsyncTabCompleteEvent} as handled.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureSuggestions(@NotNull AsyncPlayerSendSuggestionsEvent event) {
        Suggestions original = event.getSuggestions();
        List<Suggestion> canonical = canonicalSuggestions(event.getBuffer(), original.getRange(),
                original.getList());
        if (canonical != null) {
            event.setSuggestions(new Suggestions(original.getRange(), canonical));
        }
    }

    /** Registers {@code world} as plugin-managed. Idempotent; safe to call more than once. */
    public void track(@NotNull World world) {
        track(world.getUID());
    }

    /** Removes {@code world}. Idempotent; removing a world that was never tracked is a no-op. */
    public void untrack(@NotNull World world) {
        untrack(world.getUID());
    }

    void track(UUID world) {
        managedWorlds.add(world);
    }

    void untrack(UUID world) {
        managedWorlds.remove(world);
    }

    /**
     * The whole decision, as a pure function of the two facts the event carries, so it is unit-tested
     * without a server: cancel only the vanilla End City, and only in a world registered above.
     * Every other structure (the DnT castle and ship included) and every other world answers false.
     */
    boolean shouldCancel(UUID world, Key structure) {
        // Compared by namespace and value, not by Key#equals: the key arrives from the server and an
        // implementation that compared by identity would silently stop cancelling. The DnT keys
        // (mumu:end_castle / end_ship) are simply not equal to this one.
        return managedWorlds.contains(world)
                && VANILLA_END_CITY.namespace().equals(structure.namespace())
                && VANILLA_END_CITY.value().equals(structure.value());
    }

    /**
     * The pre-locate half of the same decision: {@code targets} with every one {@link #shouldCancel}
     * suppresses removed, in the order given. No new rule lives here -- the predicate is the one
     * above, so a structure this guard lets through a spawn can never be dropped from a search.
     *
     * <p>Generic over the element for one reason: the event carries {@code List<Structure>} while this
     * module's tests can build neither a {@code World} (needed to construct the event at all) nor a
     * real {@code Structure} (its instances come from a static registry that only a running server
     * populates). Without this the handler's own policy would be untestable. The handler passes
     * {@code Structure::key} and the test passes {@code Function.identity()} over {@code List<Key>};
     * both reach this same loop. What that boundary cannot cover is therefore only the mapping from
     * {@code Structure} to its key -- {@code CraftStructure#key()} itself -- not the filtering rule.
     *
     * <p>Returns {@code targets} itself -- not a copy -- when nothing was removed, which is how the
     * handler distinguishes "none of ours" from "all of ours" without a second pass.
     */
    <T> List<T> locateTargets(UUID world, List<T> targets, Function<T, Key> keyOf) {
        List<T> kept = new ArrayList<>(targets.size());
        for (T target : targets) {
            if (!shouldCancel(world, keyOf.apply(target))) {
                kept.add(target);
            }
        }
        return kept.size() == targets.size() ? targets : kept;
    }

    /**
     * The suggestion rewrite as a pure function of the buffer and the list, so it is unit-tested
     * without a server: {@code AsyncPlayerSendSuggestionsEvent} cannot even be constructed off the
     * server thread ({@code PlayerEvent} asks {@code Bukkit.isPrimaryThread()}).
     *
     * <p>Returns {@code null} -- not the input -- when nothing changed, which is how the handler
     * knows to leave the event untouched instead of replacing the list with an equivalent copy. The
     * case that matters is a completion list that already matched some other way: replacing it would
     * still be correct here but would hand every unrelated keystroke a fresh {@link Suggestions}.
     *
     * <p>Deduplication is by text, keeping the first occurrence's position: a buffer of only
     * {@code nova_} yields one {@code mumu:end_castle}, and a buffer of {@code e} yields it once even
     * though the registry supplies both spellings. A suggestion that was NOT rewritten is preferred
     * to a rewritten one at the same position, so the tooltip a player sees is the registry's own
     * rather than one copied from the alias.
     */
    static List<Suggestion> canonicalSuggestions(String buffer, StringRange range,
                                                 List<Suggestion> suggestions) {
        if (!isStructureArgumentOfLocate(buffer)) {
            return null;
        }
        Map<String, Suggestion> byText = new LinkedHashMap<>(suggestions.size() + 4);
        Set<String> rewritten = new HashSet<>();
        boolean changed = false;
        for (Suggestion suggestion : suggestions) {
            String text = suggestion.getText();
            String canonical = LEGACY_STRUCTURE_IDS.get(text);
            boolean rewrite = canonical != null;
            // Any alias at all changes the outcome: it is either renamed or dropped as a duplicate.
            // The drop case is why this cannot be derived from the loop's own map writes below.
            changed |= rewrite;
            String target = rewrite ? canonical : text;
            Suggestion candidate = rewrite
                    ? new Suggestion(range, target, suggestion.getTooltip())
                    : suggestion;
            Suggestion previous = byText.get(target);
            if (previous == null) {
                byText.put(target, candidate);
                if (rewrite) {
                    rewritten.add(target);
                }
            } else if (rewritten.contains(target) && !rewrite) {
                // The real spelling showed up after its alias: keep the registry's own entry.
                byText.put(target, candidate);
                rewritten.remove(target);
            }
        }
        if (!changed) {
            return null;
        }
        return new ArrayList<>(byText.values());
    }

    /**
     * Whether {@code buffer} is at the one structure argument of {@code /locate}, and nothing else.
     *
     * <p>Token-level rather than {@code contains}: {@code /execute run locate structure ...} and
     * {@code /locate biome ...} must both be left alone, and a second argument means the player has
     * moved past the id. The slash is stripped because the buffer is the raw packet
     * {@code command}, which vanilla sends without one but other clients are not required to.
     */
    static boolean isStructureArgumentOfLocate(String buffer) {
        String rest = buffer.startsWith("/") ? buffer.substring(1) : buffer;
        rest = rest.stripLeading();
        int split = rest.indexOf(' ');
        if (split < 0) {
            return false; // still typing the command name itself
        }
        String command = rest.substring(0, split);
        if (!command.equals("locate") && !command.equals("minecraft:locate")) {
            return false;
        }
        rest = rest.substring(split).stripLeading();
        split = rest.indexOf(' ');
        String target = split < 0 ? rest : rest.substring(0, split);
        if (!target.equals("structure")) {
            return false;
        }
        if (split < 0) {
            return false; // still typing the literal, the argument has not started
        }
        rest = rest.substring(split).stripLeading();
        return rest.indexOf(' ') < 0 && rest.indexOf('\t') < 0;
    }
}
