package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pot-break decision, driven without a server: {@link DecoratedPotBreak#applies} and
 * {@link DecoratedPotBreak#healAmount} are pure functions of the facts the event carries, because
 * the common module's test classpath can construct neither a {@code World} nor an event.
 *
 * <p>What is NOT tested here, and why: that {@code setDropItems(false)} reaches the pot's stored
 * item as well as its loot table, and that the break thread owns both the block and the player, are
 * properties of the server's block-break path, cited line by line in the class javadoc -- only a
 * running server can check them. The listener wiring is likewise Paper's, not ours. What the tests
 * do pin is every number this plugin contributes: which breaks it acts on, and exactly how much
 * health it gives back.
 */
class DecoratedPotBreakTest {

    @Test
    void ourWorldAndAPotIsActedOn() {
        assertTrue(DecoratedPotBreak.applies(true, true));
    }

    @Test
    void aWorldWithAnotherGeneratorIsIgnored() {
        // The scope is the world, not the block: a player-placed pot in a vanilla End is not ours to
        // change, and a vanilla island has no pots of ours either.
        assertFalse(DecoratedPotBreak.applies(false, true));
    }

    @Test
    void aBlockThatIsNotAPotIsIgnored() {
        // End Cities hold chests and shulkers in the same rooms as the pots; the effect is the
        // pot's, and only the pot's.
        assertFalse(DecoratedPotBreak.applies(true, false));
        assertFalse(DecoratedPotBreak.applies(false, false));
    }

    @Test
    void theRollNeverLeavesOneToThree() {
        Random random = new Random(20261017L);
        for (int i = 0; i < 10_000; i++) {
            int roll = DecoratedPotBreak.roll(random);
            assertTrue(roll >= DecoratedPotBreak.MIN_HEAL && roll <= DecoratedPotBreak.MAX_HEAL,
                    "roll " + roll + " outside 1..3");
        }
    }

    @Test
    void everyPointValueIsReachable() {
        // All three values must occur, not merely stay in range: a roll that always answered 1
        // would pass the range test above and still be wrong.
        Random random = new Random(20261017L);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(DecoratedPotBreak.roll(random));
        }
        assertEquals(Set.of(1, 2, 3), seen);
    }

    @Test
    void aHurtPlayerGetsTheWholeRoll() {
        // Health is the half-heart scale, so these are 1, 2 and 3 points of a 20-point bar.
        assertEquals(1.0, DecoratedPotBreak.healAmount(false, 5.0, 20.0, 1));
        assertEquals(2.0, DecoratedPotBreak.healAmount(false, 5.0, 20.0, 2));
        assertEquals(3.0, DecoratedPotBreak.healAmount(false, 5.0, 20.0, 3));
    }

    @Test
    void theRestoreIsTruncatedByTheRemainingHeadroom() {
        // 19.0 of 20.0 leaves room for one point only, whatever the roll said.
        assertEquals(1.0, DecoratedPotBreak.healAmount(false, 19.0, 20.0, 3));
        // A half-heart of headroom gives half a point, not a full one -- never past the maximum.
        assertEquals(0.5, DecoratedPotBreak.healAmount(false, 19.5, 20.0, 3));
    }

    @Test
    void aFullHealthPlayerIsNotHealedAtAll() {
        // Pinned, because it decides whether the handler calls heal() at all: a zero here means no
        // EntityRegainHealthEvent is fired for a break that could not have restored anything.
        assertEquals(0.0, DecoratedPotBreak.healAmount(false, 20.0, 20.0, 3));
    }

    @Test
    void healthAboveTheMaximumIsNotHealedEither() {
        // Another plugin may have raised health past the current attribute value; a negative restore
        // must never be computed from that.
        assertEquals(0.0, DecoratedPotBreak.healAmount(false, 20.5, 20.0, 3));
    }

    @Test
    void creativeIsNotHealed() {
        // Creative cannot take damage, so there is nothing to restore there; this is the value that
        // keeps a creative break from firing a health event per pot.
        assertEquals(0.0, DecoratedPotBreak.healAmount(true, 5.0, 20.0, 3));
    }
}
