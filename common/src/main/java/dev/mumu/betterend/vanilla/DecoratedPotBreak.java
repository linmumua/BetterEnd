package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.DustWastelandsGenerator;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Vanilla decorated pots in the worlds <em>this</em> plugin generates drop nothing when a player
 * breaks them, and heal that player instead.
 *
 * <p>End City structures carry 51 {@code minecraft:decorated_pot} blocks, and those are ordinary
 * vanilla pots: nothing in the structure NBT distinguishes one the generator placed from one a
 * player put down, so the effect is scoped the only way a server can scope it -- by world. A pot in
 * a BetterEnd world always behaves this way, including a pot a player placed there; that was
 * accepted when the decision to scope by dimension was made.
 *
 * <p>Self-selecting {@link Listener} with the same gate as {@link VanillaEndCheck}: which worlds use
 * our generator lives in bukkit.yml, which is not ours to read, so the world is asked instead
 * ({@code CraftWorld#getGenerator} is a plain field read, no chunk access). The generator check is
 * used here rather than {@link StructureWorldGuard}'s registered-UUID set because that set only
 * answers the same question one event later and would make this listener depend on another
 * listener's field having been populated first.
 *
 * <p><b>Why {@code setDropItems(false)} really does suppress everything.</b> A decorated pot breaks
 * into three things and only the first is a loot-table entry:
 * <ol>
 *   <li>the pot itself and, when cracked, the four sherds -- the block's loot table
 *       ({@code loot_table/blocks/decorated_pot.json}: {@code minecraft:dynamic} {@code
 *       minecraft:sherds} or the pot item with {@code pot_decorations} copied). Reached through
 *       {@code Block#playerDestroy(..., includeDrops, ...)}, and {@code ServerPlayerGameMode
 *       .destroyBlock} passes {@code event.isDropItems()} straight into that flag
 *       ({@code ServerPlayerGameMode.java:446},{@code Block.java:538});</li>
 *   <li>the item stored inside the pot. This is NOT in the loot table and NOT part of {@code
 *       playerDestroy}: {@code DecoratedPotBlockEntity} is a {@link org.bukkit.block.Container},
 *       so {@code BlockEntity#preRemoveSideEffects} ({@code BlockEntity.java:259-263}) drops its
 *       contents through {@code Containers.dropContents}. That hook fires from {@code
 *       LevelChunk#setBlockState} ({@code LevelChunk.java:414-419}) during {@code
 *       Level#removeBlock} ({@code ServerPlayerGameMode.java:428}), i.e. inside the
 *       {@code captureDrops} window that {@code ServerPlayerGameMode.java:425} opens four lines
 *       earlier and that {@code Block#popResource} appends to instead of the world
 *       ({@code Block.java:480-492}). That list is only delivered to the world by
 *       {@code CraftEventFactory.handleBlockDropItemEvent} when {@code event.isDropItems()} is
 *       true ({@code ServerPlayerGameMode.java:451-455}), so with the flag off the contents are
 *       captured and discarded with the rest. No extra block-entity clearing is needed -- but the
 *       item would have escaped had the drop been suppressed only in {@code playerDestroy}.</li>
 * </ol>
 * <p>Creative mode matters for the second path: {@code player.preventsBlockDrops()} skips the loot
 * half entirely ({@code ServerPlayerGameMode.java:440}), yet the block-entity half still runs, so
 * the drops flag is cleared in every game mode here. Only the healing is skipped in creative.
 * Breaking a pot in creative fires this event like any other break -- the event is constructed at
 * {@code ServerPlayerGameMode.java:374} with no game-mode guard.
 *
 * <p>The jump from right-clicking a pot to the pot's own loot table is worth recording, because it
 * looks like a bug: sherds drop only when the pot is {@code cracked}, and {@code cracked} is set by
 * {@code DecoratedPotBlock#playerWillDestroy} when the tool is in
 * {@code ItemTags.BREAKS_DECORATED_POTS} ({@code DecoratedPotBlock.java:188-197}) -- which is every
 * tool that can break the pot at all. So a normal break drops sherds, not the pot.
 *
 * <p><b>Threading.</b> {@code BlockBreakEvent} is fired from {@code ServerPlayerGameMode
 * .destroyBlock} before any of the vanilla work below it, and that same method then calls
 * {@code player.awardStat} and {@code player.causeFoodExhaustion} on the calling thread
 * ({@code Block.java:536-537}). On Folia that means the thread already owns both the block and the
 * player -- {@code CraftLivingEntity#getHandle} asserts exactly that
 * ({@code TickThread.ensureTickThread(this.entity, ...)}) -- so {@code getHealth}/{@code heal} from
 * a handler on this event are as region-local as vanilla's own block-break code. Nothing below
 * reads another block, loads a chunk or schedules across regions.
 *
 * <p>The decision itself is pure ({@link #applies}, {@link #healAmount}) and the roll's source is
 * injected, so both are unit-tested without a server.
 */
public final class DecoratedPotBreak implements Listener {
    /** Lowest health restored, in health points (health is measured in half-hearts: 20.0 is full). */
    static final int MIN_HEAL = 1;

    /** Highest health restored, in health points. Inclusive, like {@link #MIN_HEAL}. */
    static final int MAX_HEAL = 3;

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (!applies(event.getBlock().getWorld().getGenerator() instanceof DustWastelandsGenerator,
                event.getBlock().getType() == Material.DECORATED_POT)) {
            return;
        }
        event.setDropItems(false);

        Player player = event.getPlayer();
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            // Not reachable for a player; refusing to guess beats restoring an unbounded amount.
            return;
        }
        double amount = healAmount(player.getGameMode() == GameMode.CREATIVE,
                player.getHealth(), maxHealth.getValue(), roll(ThreadLocalRandom.current()));
        if (amount > 0.0) {
            // heal(), not setHealth(): it fires EntityRegainHealthEvent so other plugins can cancel
            // or rescale the restore, and CraftLivingEntity#setHealth additionally rejects values
            // above the attribute's current value with an IllegalArgumentException.
            player.heal(amount, EntityRegainHealthEvent.RegainReason.CUSTOM);
        }
    }

    /**
     * Whether this listener acts at all on a break: only on a decorated pot, and only in a world our
     * generator owns.
     *
     * <p>There is no way to tell a generator-placed pot from a player-placed one -- a structure NBT
     * block is a plain {@code minecraft:decorated_pot} -- so this gate is the whole story.
     *
     * @param betterEndWorld {@code World#getGenerator() instanceof DustWastelandsGenerator}
     * @param decoratedPot   the broken block's material is {@code minecraft:decorated_pot}
     */
    static boolean applies(boolean betterEndWorld, boolean decoratedPot) {
        return betterEndWorld && decoratedPot;
    }

    /**
     * How much health to restore, in health points, clamped so it can never push past the maximum.
     *
     * @param creative      the breaker is in creative, who is not healed at all -- there is nothing
     *                      to restore in a game mode that cannot take damage, and a zero here is
     *                      what keeps the creative path from firing a health event per pot
     * @param currentHealth the breaker's health now, on the 0..maxHealth half-heart scale
     * @param maxHealth     {@code Attribute.MAX_HEALTH}'s current value, potion modifiers included
     * @param roll          {@link #MIN_HEAL}..{@link #MAX_HEAL} inclusive; see {@link #roll}
     * @return {@code 0.0} when nothing should be restored, otherwise 1.0..3.0 truncated by the
     *         remaining headroom (a player at max-0.5 gains 0.5, one at max gains nothing)
     */
    static double healAmount(boolean creative, double currentHealth, double maxHealth, int roll) {
        if (creative) {
            return 0.0;
        }
        double headroom = maxHealth - currentHealth;
        // Also covers currentHealth > maxHealth (another plugin's over-heal): no negative restore.
        if (headroom <= 0.0) {
            return 0.0;
        }
        return Math.min(roll, headroom);
    }

    /**
     * The 1..3 draw. Separate from {@link #healAmount} so the range can be tested with an injected
     * source; the listener feeds it {@link ThreadLocalRandom} because on Folia a break lands on
     * whichever region thread owns the pot, and a shared {@link Random} would serialise them.
     */
    static int roll(@NotNull Random random) {
        return MIN_HEAL + random.nextInt(MAX_HEAL - MIN_HEAL + 1);
    }
}
