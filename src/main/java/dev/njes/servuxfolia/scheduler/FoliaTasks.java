package dev.njes.servuxfolia.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class FoliaTasks {
    private FoliaTasks() {
    }

    public static void region(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    public static void entity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        entity.getScheduler().execute(plugin, task, retired, 1L);
    }

    public static void async(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }
}
