package fr.ekaii.litematica.paste;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Folia detection + scheduler shim. All region-bound mutations go through
 * this class so the plugin can run unchanged on both vanilla Paper and
 * Folia.
 *
 * <p>Detection is performed once at class-init via reflection on
 * {@code io.papermc.paper.threadedregions.RegionizedServer}.
 *
 * <p>Patterns inherited from axiom-folia (see
 * {@code com.moulberry.axiom.operations.SetBlockBufferOperation} and
 * {@code RequestChunksOperation}). The Folia v0.1.8 regionfile-corruption
 * fix (May 2026 — creaclone r.5.11.mca / r.6.10.mca etc.) requires that
 * every per-chunk mutation hop onto that chunk's owning region scheduler
 * before touching its sections or block-entities map.
 */
public final class FoliaCompat {

    private static final boolean IS_FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    private FoliaCompat() {
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * Schedules {@code task} on the region owning chunk {@code (cx, cz)} of
     * {@code world}. On vanilla Paper this falls back to the global Bukkit
     * scheduler.
     */
    public static CompletableFuture<Void> runOnRegion(Plugin plugin, World world, int cx, int cz, Runnable task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable wrapped = () -> {
            try {
                task.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        };
        if (IS_FOLIA) {
            try {
                Bukkit.getRegionScheduler().run(plugin, world, cx, cz, scheduledTask -> wrapped.run());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, wrapped);
        }
        return done;
    }

    /**
     * Schedules {@code task} on the region currently owning {@code entity}.
     */
    public static CompletableFuture<Void> runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable wrapped = () -> {
            try {
                task.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        };
        if (IS_FOLIA) {
            try {
                entity.getScheduler().run(plugin, scheduledTask -> wrapped.run(), null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, wrapped);
        }
        return done;
    }

    /**
     * Schedules {@code task} on Folia's global region (or the main thread on
     * vanilla Paper).
     */
    public static CompletableFuture<Void> runGlobal(Plugin plugin, Runnable task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable wrapped = () -> {
            try {
                task.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        };
        if (IS_FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> wrapped.run());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, wrapped);
        }
        return done;
    }

    /**
     * Schedules {@code task} on the async scheduler (Folia) or a Bukkit
     * async worker (Paper).
     */
    public static CompletableFuture<Void> runAsync(Plugin plugin, Runnable task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable wrapped = () -> {
            try {
                task.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        };
        if (IS_FOLIA) {
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> wrapped.run());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapped);
        }
        return done;
    }

    /**
     * Async chunk load. Implemented via Paper's {@link World#getChunkAtAsync}
     * which is the Paper-API form that works identically on Folia.
     *
     * <p>Critical: per axiom-folia
     * ({@code RequestChunksOperation} line 121-139), even
     * {@code getChunkAtAsync} cannot be called from a region tick thread on
     * Folia ("Cannot asynchronously load chunks") — callers must already be
     * on the async scheduler (or another off-region context) when invoking
     * this method.
     */
    public static CompletableFuture<Chunk> loadChunkAsync(World world, int cx, int cz) {
        return world.getChunkAtAsync(cx, cz);
    }
}
