package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monster-damage decision, tested without a server.
 * <p>
 * WHY these matter: a wrong answer here is silent. A missed gate makes the End safer (or harsher)
 * than the owner asked for with nothing in the log to explain it; a factor applied at 1.0 rewrites
 * every damage event for no reason and perturbs other plugins' listeners; and a projectile not traced
 * to its shooter would leave a shulker's only attack unboosted while the config claims otherwise.
 */
class MonsterDamageBoostTest {
    private static final boolean PLAYER_VICTIM = true;
    private static final boolean OUR_WORLD = true;
    private static final boolean MONSTER = true;
    private static final boolean NOT_MONSTER = false;
    private static final boolean PROJECTILE = true;
    private static final boolean DIRECT = false;
    private static final boolean SHOOTER_MONSTER = true;
    private static final boolean SHOOTER_NOT_MONSTER = false;

    // --- the three gates ----------------------------------------------------------------------

    @Test
    void aNonPlayerVictimIsNeverScaled() {
        MonsterDamageBoost.Damage result =
                MonsterDamageBoost.decide(false, OUR_WORLD, MONSTER, 8.0, 1.5);

        assertFalse(result.modified(), "mob-on-mob and player-on-mob damage is out of scope");
        assertEquals(8.0, result.amount());
    }

    @Test
    void aWorldWeDoNotGenerateIsNeverScaled() {
        MonsterDamageBoost.Damage result =
                MonsterDamageBoost.decide(PLAYER_VICTIM, false, MONSTER, 8.0, 1.5);

        assertFalse(result.modified(), "another plugin's End is not ours to rescale");
        assertEquals(8.0, result.amount());
    }

    @Test
    void aNonMonsterSourceIsNeverScaled() {
        // Falling, fire, another player: no damager to classify, or a player who is not an Enemy.
        MonsterDamageBoost.Damage result =
                MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, NOT_MONSTER, 8.0, 1.5);

        assertFalse(result.modified());
        assertEquals(8.0, result.amount());
    }

    @Test
    void aMonsterHitOnAPlayerInOurWorldIsScaled() {
        MonsterDamageBoost.Damage result =
                MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, MONSTER, 8.0, 1.5);

        assertTrue(result.modified());
        assertEquals(12.0, result.amount(), 1e-9);
    }

    @Test
    void aZeroOrNegativeHitIsLeftAlone() {
        // Scaling 0 would be a needless setDamage(0) on every zero-damage event.
        assertFalse(MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, MONSTER, 0.0, 1.5).modified());
        assertFalse(MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, MONSTER, -1.0, 1.5).modified());
    }

    // --- the factor --------------------------------------------------------------------------

    @Test
    void onePointZeroMeansHandsOff() {
        // The important half: not merely "the same number", but "no setDamage call at all".
        MonsterDamageBoost.Damage result =
                MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, MONSTER, 8.0, 1.0);

        assertFalse(result.modified(), "a 1.0 factor must not rewrite anyone's event");
        assertEquals(8.0, result.amount());
    }

    @Test
    void aFactorBelowOneIsClampedToOffRatherThanMakingTheEndSafer() {
        // 0.5 would mean "the End hurts less", the opposite of what this setting is for.
        MonsterDamageBoost.Damage result =
                MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, MONSTER, 8.0, 0.5);

        assertFalse(result.modified());
        assertEquals(8.0, result.amount());
        assertEquals(MonsterDamageBoost.MIN_MULTIPLIER, MonsterDamageBoost.clampMultiplier(0.5));
    }

    @Test
    void anAbsurdFactorIsClampedToTheCeiling() {
        MonsterDamageBoost.Damage result =
                MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, MONSTER, 8.0, 1000.0);

        assertTrue(result.modified());
        assertEquals(8.0 * MonsterDamageBoost.MAX_MULTIPLIER, result.amount(), 1e-9);
        assertEquals(MonsterDamageBoost.MAX_MULTIPLIER, MonsterDamageBoost.clampMultiplier(1000.0));
    }

    @Test
    void aNonFiniteFactorIsTreatedAsOff() {
        // NaN would poison every hit it touched (and turn every comparison false).
        assertEquals(MonsterDamageBoost.MIN_MULTIPLIER, MonsterDamageBoost.clampMultiplier(Double.NaN));
        assertEquals(MonsterDamageBoost.MIN_MULTIPLIER,
                MonsterDamageBoost.clampMultiplier(Double.POSITIVE_INFINITY));
        assertFalse(MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD, MONSTER, 8.0, Double.NaN).modified());
    }

    @Test
    void theShippedDefaultIsOneAndAHalf() {
        // Ties the listener's clamp range to the feature's documented behaviour: 1.5 is in range and
        // is what a boosted hit is multiplied by, not merely retained.
        assertEquals(1.5, MonsterDamageBoost.clampMultiplier(1.5));
    }

    // --- projectiles -------------------------------------------------------------------------

    @Test
    void aProjectileFiredByAMonsterCountsAsThatMonster() {
        // A shulker's ONLY attack is a ShulkerBullet: without this the multiplier would do nothing
        // for the mob it matters most for.
        assertTrue(MonsterDamageBoost.monsterAttacker(NOT_MONSTER, PROJECTILE, SHOOTER_MONSTER));

        MonsterDamageBoost.Damage result = MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD,
                MonsterDamageBoost.monsterAttacker(NOT_MONSTER, PROJECTILE, SHOOTER_MONSTER), 10.0, 1.5);
        assertTrue(result.modified());
        assertEquals(15.0, result.amount(), 1e-9);
    }

    @Test
    void aProjectileFiredByAPlayerIsNotAMonster() {
        // Player-against-nothing tricks (a self-shot arrow, a trident) must not be boosted.
        assertFalse(MonsterDamageBoost.monsterAttacker(NOT_MONSTER, PROJECTILE, SHOOTER_NOT_MONSTER));
        assertFalse(MonsterDamageBoost.decide(PLAYER_VICTIM, OUR_WORLD,
                MonsterDamageBoost.monsterAttacker(NOT_MONSTER, PROJECTILE, SHOOTER_NOT_MONSTER),
                10.0, 1.5).modified());
    }

    @Test
    void aDirectMonsterHitNeedsNoProjectile() {
        assertTrue(MonsterDamageBoost.monsterAttacker(MONSTER, DIRECT, SHOOTER_NOT_MONSTER));
        assertFalse(MonsterDamageBoost.monsterAttacker(NOT_MONSTER, DIRECT, SHOOTER_NOT_MONSTER));
        // A monster only reaches the shooter path through a projectile; the flag combination cannot
        // manufacture a monster out of a non-projectile.
        assertFalse(MonsterDamageBoost.monsterAttacker(NOT_MONSTER, DIRECT, SHOOTER_MONSTER));
    }

    // --- the values the document promises -----------------------------------------------------

    @Test
    void theClampRangeBracketsTheDefaultWithoutAllowingHarm() {
        assertTrue(MonsterDamageBoost.MIN_MULTIPLIER >= 1.0,
                "any factor below 1.0 would turn this into a damage reduction");
        assertTrue(MonsterDamageBoost.MAX_MULTIPLIER > 1.5,
                "the documented default must be reachable inside the range");
    }
}
