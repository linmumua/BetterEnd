package dev.mumu.betterend;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Keeps world and entity work on the owner region on Folia and Paper. */
public final class RegionSchedulerAdapter {
    private RegionSchedulerAdapter() {
    }

    public static void run(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, location, ignored -> task.run());
    }

    public static void run(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    public static void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    /**
     * Runs {@code task} off every tick thread: Folia's async pool, which no region and no global
     * thread waits on. Used for file reads and writes a command asks for, so the tick thread that
     * dispatched the command is not blocked on disk.
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    /**
     * Delivers {@code task} on the thread that owns {@code entity}, or runs {@code retired} instead
     * when that entity is already gone -- the sender logged off before the reply was ready.
     *
     * @param retired what to do when the entity is retired; {@code null} means drop the reply
     */
    public static void reply(Plugin plugin, Entity entity, Runnable task, @Nullable Runnable retired) {
        entity.getScheduler().run(plugin, ignored -> task.run(), retired);
    }

    /**
     * Delivers {@code task} on the global thread, for a sender that no region owns: the console, a
     * remote console, or a command block. Block messages must not be sent from an arbitrary thread
     * either, so this is the safe side of the same choice.
     */
    public static void replyGlobal(Plugin plugin, @NotNull Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }
}
