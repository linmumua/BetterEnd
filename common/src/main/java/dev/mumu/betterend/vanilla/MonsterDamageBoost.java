package dev.mumu.betterend.vanilla;

import dev.mumu.betterend.DustWastelandsGenerator;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;

import java.util.function.DoubleSupplier;

/**
 * Damage a player <em>takes</em> from a monster in a world this plugin generates is multiplied by a
 * configurable factor ({@code end-damage.monster-multiplier}, default 1.5). Damage a player
 * <em>deals</em> is untouched: this handler reads one event and never scales anything else.
 *
 * <p><b>Three gates, all required</b> (mirroring {@link DecoratedPotBreak}'s self-selecting style):
 * the victim is a {@link Player}, the world's generator is ours, and the damage source is a monster.
 *
 * <p><b>Which entities count as a monster: {@link Enemy}, not {@link org.bukkit.entity.Monster}.</b>
 * The two are not the same set on this API, and the difference is exactly the two cases worth getting
 * right in the End:
 * <ul>
 *   <li>{@code Monster extends Creature, Enemy} -- enderman, endermite, and every ordinary hostile
 *       mob (zombie, skeleton, creeper, blaze, wither skeleton, piglin, ...);</li>
 *   <li>{@code Shulker extends Golem, Colorable, Enemy} -- a shulker is NOT a {@code Monster}. Using
 *       {@code Monster} alone would leave the End's second most common shooter unboosted;</li>
 *   <li>{@code EnderDragon extends ComplexLivingEntity, Boss, Mob, Enemy} -- also not a
 *       {@code Monster};</li>
 *   <li>{@code Slime}, {@code MagmaCube}, {@code Ghast}, {@code Phantom}, {@code Hoglin} likewise
 *       extend {@code Enemy} without being {@code Monster}.</li>
 * </ul>
 * {@code Enemy} is that whole set and nothing else -- it is the marker Bukkit uses for "hostile
 * mob" -- so the predicate is "the source is an {@link Enemy}".
 *
 * <p><b>End entities that are deliberately NOT boosted</b>, so nobody has to rediscover this:
 * <ul>
 *   <li>the dragon's body and wings. A hit from those arrives with an {@code EnderDragonPart}
 *       (a {@link org.bukkit.entity.ComplexEntityPart}, not an {@code Enemy}) as the damager -- the
 *       part is its own entity, the dragon is only its parent. Tracing the parent would mean calling
 *       {@code ComplexEntityPart#getParent()}, whose CraftBukkit implementation reads
 *       {@code getHandle()} and therefore trips Folia's {@code TickThread.ensureTickThread} for the
 *       PART's region ({@code CraftComplexPart.java:24-27}) from a handler running for the
 *       PLAYER's. That is the same class of cross-region hazard that deadlocked this plugin's boot
 *       once already (see {@link VanillaEndCheck}), so it is not done for a cosmetic gain;</li>
 *   <li>the dragon's breath: an {@code AreaEffectCloud} with no attacking entity at all, so it never
 *       reaches this event with a damager to classify;</li>
 *   <li>{@code end_crystal} explosions: a block/crystal source, not a mob.</li>
 * </ul>
 * The dragon's fireball IS boosted: it is a {@code DragonFireball} whose shooter is the dragon,
 * which resolves through the projectile path below.
 *
 * <p><b>Projectiles are traced to their shooter.</b> A shulker's damage is a
 * {@code ShulkerBullet}, whose damager is a {@link Projectile} and whose
 * {@link Projectile#getShooter()} is the shulker -- scaling only {@code damager instanceof Enemy}
 * would make the multiplier do nothing for the mob whose main attack is ranged. One hop is taken
 * (projectile -> shooter), which is all vanilla needs: a projectile's shooter is a
 * {@link ProjectileSource}, and on this API every {@code LivingEntity} is one
 * ({@code LivingEntity extends ..., ProjectileSource}), while a {@code BlockProjectileSource}
 * (dispenser) is not an {@link Enemy} and therefore not a monster.
 *
 * <p><b>Why {@code setDamage(double)} and not per-modifier scaling.</b> That method does not merely
 * overwrite one number: {@code EntityDamageEvent#setDamage(double)} walks every
 * {@link org.bukkit.event.entity.EntityDamageEvent.DamageModifier} in enum order, re-applies that
 * modifier's vanilla function to the NEW raw value, and stores the difference before finally writing
 * {@code BASE} ({@code EntityDamageEvent.java:163-188}) -- the modifiers' functions are the ones the
 * server itself supplied ({@code CraftEventFactory#handleLivingEntityDamage}). Armour, resistance,
 * blocking and absorption are therefore recomputed against the 1.5x hit rather than applied at the
 * old scale or applied twice; {@code getFinalDamage()} is a plain sum of the modifiers
 * ({@code EntityDamageEvent.java:146-155}). One caveat worth stating rather than hiding: because
 * armour's reduction is a non-linear function of the incoming hit, the final number is not exactly
 * 1.5x the previous final damage -- it is 1.5x the raw hit with vanilla's reductions recomputed,
 * which is the honest reading of "takes 1.5x damage from monsters".
 *
 * <p><b>A multiplier of 1.0 touches nothing.</b> No {@code setDamage} call is made at all, so other
 * plugins' listeners see an event byte-identical to vanilla's -- a needless write would perturb any
 * later handler that keys off the raw value.
 *
 * <p><b>Hot reload.</b> The factor is read from an injected {@link DoubleSupplier} on every hit
 * rather than copied into a field, so {@code /betterend reload} can publish a new value and the very
 * next hit sees it. The supplier's backing field is {@code volatile} (see
 * {@code BetterEndPlugin}): the handler runs on whichever region thread owns the victim, and a
 * reload may be published from another thread.
 *
 * <p><b>Threading.</b> {@code EntityDamageByEntityEvent} is fired where the damage is applied, on
 * the thread that owns the victim, and every read below is a field read on that same entity: the
 * victim's world, the damager's type, and the projectile's shooter. No chunk is loaded, no other
 * region is queried, nothing is scheduled -- so this handler can neither deadlock nor be refused by
 * a region assertion. It also touches no block, so CraftEngine's material deception is irrelevant.
 *
 * <p>The decision itself is pure ({@link #decide}, {@link #monsterAttacker},
 * {@link #clampMultiplier}) so it unit-tests without a server.
 */
public final class MonsterDamageBoost implements Listener {
    /**
     * Lowest usable factor. 1.0 is "no change", which is also how an owner disables the feature;
     * anything below would make the End <em>safer</em> than vanilla, the opposite of what this
     * setting is for, so it is clamped up rather than honoured.
     */
    public static final double MIN_MULTIPLIER = 1.0;

    /**
     * Highest usable factor. Not a gameplay opinion -- a typo guard: {@code 15} for {@code 1.5}
     * would already be absurd, and a value in the hundreds (or an accidental exponent) turns one
     * skeleton arrow into an instant kill nobody can diagnose afterwards. Clamped, and the clamp is
     * logged by the config loader.
     */
    public static final double MAX_MULTIPLIER = 10.0;

    private final DoubleSupplier multiplier;

    /**
     * @param multiplier the live factor, read on every hit so a reload takes effect immediately;
     *                   fed from the plugin's volatile field
     */
    public MonsterDamageBoost(@NotNull DoubleSupplier multiplier) {
        this.multiplier = multiplier;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            // Not a player being hurt: mob-on-mob and player-on-mob damage is out of scope.
            return;
        }
        // Same gate as DecoratedPotBreak/VanillaEndCheck: which worlds use our generator lives in
        // bukkit.yml, which is not ours to read, so the world is asked instead. getGenerator() is a
        // field read on the world, not a chunk access.
        boolean betterEndWorld = event.getEntity().getWorld().getGenerator() instanceof DustWastelandsGenerator;

        Entity damager = event.getDamager();
        boolean projectile = damager instanceof Projectile;
        boolean shooterMonster = projectile && isMonster(((Projectile) damager).getShooter());

        Damage decision = decide(true, betterEndWorld,
                monsterAttacker(damager instanceof Enemy, projectile, shooterMonster),
                event.getDamage(), multiplier.getAsDouble());
        if (decision.modified()) {
            event.setDamage(decision.amount());
        }
    }

    /** Whether a projectile's shooter resolves to a monster; a block or a player is not one. */
    private static boolean isMonster(ProjectileSource shooter) {
        return shooter instanceof Enemy;
    }

    /** The outcome of {@link #decide}: whether the event should be written, and with what value. */
    public record Damage(boolean modified, double amount) {
    }

    /**
     * The whole decision, free of Bukkit.
     *
     * @param playerVictim    the hurt entity is a player
     * @param betterEndWorld  the victim's world uses this plugin's generator
     * @param monsterAttacker the damage source is a monster, directly or through a projectile; see
     *                        {@link #monsterAttacker}
     * @param damage          {@code event.getDamage()}, the raw {@code BASE} amount
     * @param multiplier      the configured factor, clamped by {@link #clampMultiplier}
     * @return {@code modified == false} (and {@code amount == damage}) whenever nothing should be
     *         written: any gate unmet, a factor that clamps to exactly 1.0, or a non-positive hit
     *         (scaling zero would just be a needless {@code setDamage(0)} on every cancelled-free
     *         zero-damage event, e.g. a mob brushing a player during its invulnerability window)
     */
    public static Damage decide(boolean playerVictim, boolean betterEndWorld, boolean monsterAttacker,
                                double damage, double multiplier) {
        double factor = clampMultiplier(multiplier);
        if (!playerVictim || !betterEndWorld || !monsterAttacker) {
            return new Damage(false, damage);
        }
        if (factor == MIN_MULTIPLIER || !(damage > 0.0)) {
            return new Damage(false, damage);
        }
        return new Damage(true, damage * factor);
    }

    /**
     * Whether the damage came from a monster.
     *
     * <p>A direct mob hit and a projectile fired by one are the same answer; a projectile fired by a
     * player (or by a dispenser, which is a {@code BlockProjectileSource}) is not.
     *
     * @param damagerMonster the damager itself is an {@link Enemy}
     * @param projectile     the damager is a {@link Projectile}
     * @param shooterMonster that projectile's shooter is an {@link Enemy} -- {@code false} whenever
     *                       the shooter is absent, a player or a block
     */
    public static boolean monsterAttacker(boolean damagerMonster, boolean projectile, boolean shooterMonster) {
        return damagerMonster || (projectile && shooterMonster);
    }

    /**
     * Clamps the configured factor into {@link #MIN_MULTIPLIER}..{@link #MAX_MULTIPLIER}.
     *
     * <p>Applied here as well as in the config loader on purpose: the loader is the source of
     * warnings, but a factor reaching this code from anywhere else (a future API, a test, a
     * reflection-based admin tool) must not be able to turn one hit into an instant kill or to
     * silently make the End safer. A non-finite value is the minimum, i.e. "no boost".
     *
     * @param multiplier the raw configured value
     * @return a finite factor inside the range
     */
    public static double clampMultiplier(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < MIN_MULTIPLIER) {
            return MIN_MULTIPLIER;
        }
        return Math.min(multiplier, MAX_MULTIPLIER);
    }
}
