package dev.mumu.betterend.vanilla;

import io.papermc.paper.event.world.StructuresLocateEvent;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's decision, driven without a server: {@link StructureWorldGuard#shouldCancel} and
 * {@link StructureWorldGuard#locateTargets} are pure functions of the world id and the structure key
 * the events carry, because this module's test classpath can construct neither a {@code World} (which
 * an event needs) nor a real {@code Structure} (its instances come from a static registry only a
 * running server populates).
 *
 * <p>What is NOT tested here, and why: that {@code WorldInitEvent} fires before the spawn chunks
 * generate, and that {@code AsyncStructureSpawnEvent} is fired off the main thread, are Paper's
 * behaviour, cited in the class javadoc -- only a running server can check them. The locate handler's
 * <em>registration</em> (priority, {@code ignoreCancelled}) is asserted reflectively, but its dispatch
 * -- that the event is fired at locate entry, and that {@code setStructures}/{@code setCancelled} are
 * honoured -- is Paper's, verified against MU核心 {@code ChunkGenerator.findNearestMapStructure}.
 */
class StructureWorldGuardTest {
    private static final UUID MANAGED = UUID.randomUUID();
    private static final UUID OTHER_END = UUID.randomUUID();
    private static final Key END_CITY = Key.key("minecraft", "end_city");
    private static final Key DNT_CASTLE = Key.key("mumu", "end_castle");
    private static final Key DNT_SHIP = Key.key("mumu", "end_ship");

    @Test
    void cancelsTheVanillaEndCityInAManagedWorld() {
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        assertTrue(guard.shouldCancel(MANAGED, END_CITY));
    }

    @Test
    void anUnmanagedWorldKeepsItsEndCity() {
        // The whole reason this is a per-world guard and not a biome-tag edit: a second End
        // dimension on the same server is not this plugin's world and keeps the vanilla structure.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        assertFalse(guard.shouldCancel(OTHER_END, END_CITY));
    }

    @Test
    void noWorldIsManagedUntilItIsRegistered() {
        // A world that never fired WorldInit (or whose generator is not ours) is not registered, so
        // even the vanilla city is left alone there.
        assertFalse(new StructureWorldGuard().shouldCancel(MANAGED, END_CITY));
    }

    @Test
    void theDntCastleAndShipSurviveInAManagedWorld() {
        // Enabling the structure stage is what lets DnT generate at all, so the guard must never be
        // the thing that cancels it. Compared by key, so a datapack structure that reuses the
        // end_city structure type is still safe.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        assertFalse(guard.shouldCancel(MANAGED, DNT_CASTLE));
        assertFalse(guard.shouldCancel(MANAGED, DNT_SHIP));
    }

    @Test
    void matchesTheKeyWhateverImplementationCarriesIt() {
        // The event hands over whatever Structure#key() returns, and the guard must not depend on
        // that implementation's equals. A NamespacedKey is a different Key implementation and must
        // match just the same; the value is compared, not only the namespace.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        assertTrue(guard.shouldCancel(MANAGED, NamespacedKey.minecraft("end_city")));
        assertFalse(guard.shouldCancel(MANAGED, NamespacedKey.minecraft("end_ship")));
    }

    @Test
    void unloadForgetsTheWorld() {
        // WorldUnloadEvent drops the registration so the tracked set does not leak every world ever
        // loaded, and a replacement world of the same name re-decides at its own init.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        assertTrue(guard.shouldCancel(MANAGED, END_CITY));
        guard.untrack(MANAGED);
        assertFalse(guard.shouldCancel(MANAGED, END_CITY));
    }

    @Test
    void untrackingAnUntrackedWorldIsANoOp() {
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.untrack(OTHER_END);
        assertFalse(guard.shouldCancel(OTHER_END, END_CITY));
    }

    // --- the pre-locate filter ------------------------------------------------------------------
    //
    // These drive StructureWorldGuard#locateTargets, which is the loop StructureWorldGuard#onStructureLocate
    // runs -- the event itself cannot be built here (no World, and StructuresLocateEvent's constructor is
    // @ApiStatus.Internal), so the keys the event carries are handed over directly with a key identity
    // function. The three outcomes the handler derives from the result are pinned per case:
    //   kept == targets  -> handler returns, event untouched
    //   kept isEmpty     -> handler cancels, the search never starts
    //   otherwise        -> handler narrows the list, the search continues for what is left

    @Test
    void aSearchForOnlyTheCityIsDroppedEntirely() {
        // /locate end_city: the whole request is the structure we would throw away, so the search must
        // be cancelled outright rather than run and come back empty.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        List<Key> kept = guard.locateTargets(MANAGED, List.of(END_CITY), Function.identity());
        assertTrue(kept.isEmpty(), "the only target was the city, so nothing may survive");
    }

    @Test
    void aMixedSearchKeepsItsOtherTargets() {
        // A datapack that tags end_city together with its own structures asks for both in one search;
        // the city is narrowed out and the rest is still looked for -- not cancelled.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        List<Key> kept = guard.locateTargets(MANAGED, List.of(END_CITY, DNT_CASTLE, DNT_SHIP), Function.identity());
        assertEquals(List.of(DNT_CASTLE, DNT_SHIP), kept);
    }

    @Test
    void aSearchInAnUnmanagedWorldIsUntouched() {
        // A second End dimension keeps its own cities, so its searches are none of this guard's
        // business: the handler must see the very same list instance and leave the event alone.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        List<Key> requested = List.of(END_CITY);
        assertSame(requested, guard.locateTargets(OTHER_END, requested, Function.identity()));
    }

    @Test
    void aSearchForSomethingElseIsUntouched() {
        // The common case by volume: every other /locate on the server passes through this handler and
        // must cost one comparison per target, no cancellation and no replacement list.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        List<Key> requested = List.of(DNT_CASTLE, DNT_SHIP);
        assertSame(requested, guard.locateTargets(MANAGED, requested, Function.identity()));
    }

    @Test
    void anUnloadedWorldSearchesFreelyAgain() {
        // Mirrors unloadForgetsTheWorld: the registration is the only state, so dropping it must also
        // stop the pre-locate filter from touching a same-named replacement world's searches.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        assertTrue(guard.locateTargets(MANAGED, List.of(END_CITY), Function.identity()).isEmpty());
        guard.untrack(MANAGED);
        List<Key> requested = List.of(END_CITY);
        assertSame(requested, guard.locateTargets(MANAGED, requested, Function.identity()));
    }

    @Test
    void theLocateFilterMatchesTheKeyWhateverImplementationCarriesIt() {
        // Same trap as matchesTheKeyWhateverImplementationCarriesIt, on the list path: the targets come
        // from CraftStructure.minecraftToBukkit and their keys need not be net.kyori's own Key class.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        assertTrue(guard.locateTargets(MANAGED, List.of(NamespacedKey.minecraft("end_city")), Function.identity()).isEmpty());
        List<Key> other = List.of(NamespacedKey.minecraft("end_ship"));
        assertSame(other, guard.locateTargets(MANAGED, other, Function.identity()));
    }

    @Test
    void anEmptyRequestIsNeverCancelled() {
        // An empty list is not "all of ours": cancelling it would turn a no-op search into a silent
        // failure, and the handler's kept==targets branch is what keeps that distinction.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        List<Key> requested = List.of();
        assertSame(requested, guard.locateTargets(MANAGED, requested, Function.identity()));
    }

    @Test
    void theLocateHandlerIsRegisteredAtHighestAndSkipsCancelledSearches() throws NoSuchMethodException {
        // The registration, not the body: highest priority so other plugins have already filtered the
        // target list before this guard narrows it, and ignoreCancelled so a search somebody else
        // cancelled is left cancelled instead of being rewritten. Reflected rather than dispatched --
        // firing an event needs a World, and this asserts the contract in the one place a refactor
        // would silently change it: the annotation.
        EventHandler handler = StructureWorldGuard.class
                .getMethod("onStructureLocate", StructuresLocateEvent.class)
                .getAnnotation(EventHandler.class);

        assertNotNull(handler, "onStructureLocate must stay a registered event handler");
        assertEquals(EventPriority.HIGHEST, handler.priority());
        assertTrue(handler.ignoreCancelled(), "a search already cancelled by another plugin stays cancelled");
    }

    @Test
    void theLocateSuggestionHandlerCannotResurrectACancelledList() throws NoSuchMethodException {
        // Same contract as the locate handler above, for the same reason: the rewrite must see every
        // other plugin's filtering first, and must never hand back a list somebody else cancelled.
        EventHandler handler = StructureWorldGuard.class
                .getMethod("onStructureSuggestions",
                        com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent.class)
                .getAnnotation(EventHandler.class);

        assertNotNull(handler, "onStructureSuggestions must stay a registered event handler");
        assertEquals(EventPriority.HIGHEST, handler.priority());
        assertTrue(handler.ignoreCancelled(), "a suggestion list already cancelled by another plugin stays cancelled");
    }

    @Test
    void onlyTheStructureArgumentOfLocateIsRewritten() {
        // Token-level, not contains: /execute wraps other commands, /locate biome is a different
        // argument type, and a second argument means the player has moved past the id.
        assertTrue(StructureWorldGuard.isStructureArgumentOfLocate("locate structure "));
        assertTrue(StructureWorldGuard.isStructureArgumentOfLocate("locate structure nova_"));
        assertTrue(StructureWorldGuard.isStructureArgumentOfLocate("/locate structure "));
        assertTrue(StructureWorldGuard.isStructureArgumentOfLocate("minecraft:locate structure e"));

        assertFalse(StructureWorldGuard.isStructureArgumentOfLocate("locate"));
        assertFalse(StructureWorldGuard.isStructureArgumentOfLocate("locate structure"));
        assertFalse(StructureWorldGuard.isStructureArgumentOfLocate("locate biome "));
        assertFalse(StructureWorldGuard.isStructureArgumentOfLocate("locate poi "));
        assertFalse(StructureWorldGuard.isStructureArgumentOfLocate("execute run locate structure "));
        assertFalse(StructureWorldGuard.isStructureArgumentOfLocate("locate structure end_city "));
        assertFalse(StructureWorldGuard.isStructureArgumentOfLocate("place structure "));
    }

    @Test
    void legacySpellingsCollapseOntoTheirPublicId() {
        List<Suggestion> rewritten = StructureWorldGuard.canonicalSuggestions(
                "locate structure ", StringRange.at(17),
                List.of(suggestion("nova_structures:end_castle"), suggestion("nova_structures:end_ship")));

        assertNotNull(rewritten, "two legacy aliases must be rewritten");
        assertEquals(List.of("mumu:end_castle", "mumu:end_ship"), texts(rewritten));
    }

    @Test
    void anAliasAndItsPublicIdAppearOnce() {
        // The registry holds both spellings, so a short buffer yields each structure twice.
        List<Suggestion> rewritten = StructureWorldGuard.canonicalSuggestions(
                "locate structure e", StringRange.at(17),
                List.of(suggestion("mumu:end_castle"),
                        suggestion("nova_structures:end_castle"),
                        suggestion("mumu:end_well")));

        assertNotNull(rewritten);
        assertEquals(List.of("mumu:end_castle", "mumu:end_well"), texts(rewritten));
    }

    @Test
    void theRealSpellingKeepsItsOwnTooltip() {
        // A rewritten alias is a copy, so when the registry's own entry is also present the tooltip
        // a player sees must be that one -- at the position the alias occupied.
        StructureWorldGuard guard = new StructureWorldGuard();
        com.mojang.brigadier.Message aliasTooltip = () -> "alias";
        com.mojang.brigadier.Message realTooltip = () -> "real";
        Suggestion alias = new Suggestion(StringRange.at(17), "nova_structures:end_ship", aliasTooltip);
        Suggestion real = new Suggestion(StringRange.at(17), "mumu:end_ship", realTooltip);

        List<Suggestion> rewritten = StructureWorldGuard.canonicalSuggestions(
                "locate structure ", StringRange.at(17), List.of(alias, real));

        assertNotNull(rewritten);
        assertEquals(1, rewritten.size());
        assertEquals("mumu:end_ship", rewritten.get(0).getText());
        assertEquals("real", rewritten.get(0).getTooltip().getString(),
                "the registry's own entry must win over the alias copy");
        assertNotNull(guard);
    }

    @Test
    void lookalikesAndOtherArgumentsAreLeftAlone() {
        // None of these is a published id: a prefix match would have rewritten the first three.
        List<Suggestion> untouched = StructureWorldGuard.canonicalSuggestions(
                "locate structure ", StringRange.at(17),
                List.of(suggestion("nova_structures:end_castle/start"),
                        suggestion("nova_structures:end_castle_2"),
                        suggestion("nova_structures:end_shipwreck"),
                        suggestion("echoes_of_the_end__structures_:end_well_2")));
        assertNull(untouched, "nothing matched, so the event must be left untouched");

        assertNull(StructureWorldGuard.canonicalSuggestions(
                "locate biome ", StringRange.at(13),
                List.of(suggestion("nova_structures:end_castle"))),
                "a different argument type must not be rewritten");
    }

    @Test
    void everyPublishedStructureHasItsAliasMapped() {
        // The eleven ids from scripts/dntlib/structure_ids.py, so a pack change that adds a structure
        // without a mapping is a failing test rather than a duplicate left in the completion list.
        List<String> aliases = List.of(
                "echoes_of_the_end__structures_:crying_obsidian_tower",
                "echoes_of_the_end__structures_:end_camp",
                "echoes_of_the_end__structures_:end_dungeon_tower",
                "echoes_of_the_end__structures_:end_enchanted_temple",
                "echoes_of_the_end__structures_:end_pillar",
                "echoes_of_the_end__structures_:end_shulker_tower",
                "echoes_of_the_end__structures_:end_temple",
                "echoes_of_the_end__structures_:end_warship",
                "echoes_of_the_end__structures_:end_well",
                "nova_structures:end_castle",
                "nova_structures:end_ship");

        List<Suggestion> all = new java.util.ArrayList<>();
        for (String alias : aliases) {
            all.add(suggestion(alias));
        }
        List<Suggestion> rewritten = StructureWorldGuard.canonicalSuggestions(
                "locate structure ", StringRange.at(17), all);

        assertNotNull(rewritten);
        assertEquals(11, rewritten.size());
        for (Suggestion suggestion : rewritten) {
            assertTrue(suggestion.getText().startsWith("mumu:"), suggestion.getText());
        }
    }

    private static Suggestion suggestion(String text) {
        return new Suggestion(StringRange.at(17), text);
    }

    private static List<String> texts(List<Suggestion> suggestions) {
        List<String> texts = new java.util.ArrayList<>(suggestions.size());
        for (Suggestion suggestion : suggestions) {
            texts.add(suggestion.getText());
        }
        return texts;
    }

    @Test
    void registrationIsASetNotACounter() {
        // A double track must still be cleared by a single untrack, or a repeated WorldInit followed
        // by one unload would leave the world silently managed.
        StructureWorldGuard guard = new StructureWorldGuard();
        guard.track(MANAGED);
        guard.track(MANAGED);
        guard.untrack(MANAGED);
        assertFalse(guard.shouldCancel(MANAGED, END_CITY));
    }

    @Test
    void concurrentTrackingAndDecidingDoesNotThrow() throws InterruptedException {
        // WorldInit/unload run on the main thread while worldgen workers call shouldCancel, so the
        // backing set must survive concurrent add/remove/contains. Each worker owns its own id, so
        // the end state is deterministic even though the interleaving is not.
        StructureWorldGuard guard = new StructureWorldGuard();
        int threads = 8;
        UUID[] ids = new UUID[threads];
        for (int i = 0; i < threads; i++) {
            ids[i] = UUID.randomUUID();
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            UUID id = ids[i];
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 10_000; n++) {
                        guard.track(id);
                        guard.shouldCancel(id, END_CITY);
                        guard.untrack(id);
                        guard.shouldCancel(UUID.randomUUID(), END_CITY);
                    }
                    guard.track(id);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }

        assertNull(failure.get(), () -> "a worker failed: " + failure.get());
        for (UUID id : ids) {
            assertTrue(guard.shouldCancel(id, END_CITY), "lost the registration for " + id);
        }
    }
}
