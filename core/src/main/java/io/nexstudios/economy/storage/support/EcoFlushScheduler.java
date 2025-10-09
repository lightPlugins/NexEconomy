package io.nexstudios.economy.storage.support;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.storage.InMemoryEcoService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.Duration;

/**
 * Periodically triggers cache flush to DB.
 */
public final class EcoFlushScheduler {

    private final Plugin plugin;
    private final InMemoryEcoService service;
    private int taskId = -1;

    public EcoFlushScheduler(Plugin plugin, InMemoryEcoService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void start(Duration every) {
        stop();
        long ticks = Math.max(1L, every.toMillis() / 50L);
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                int n = service.flushAllNow();
                if (n > 0) {
                    NexEconomy.nexusLogger.debug("Eco: periodic flush wrote " + n + " account(s).", 1);
                }
            } catch (Throwable t) {
                NexEconomy.nexusLogger.error("Eco: periodic flush failed: " + t.getMessage());
            }
        }, ticks, ticks).getTaskId();
        NexEconomy.nexusLogger.info("Eco: periodic flush started (every " + every.toMillis() + " ms).");
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
            NexEconomy.nexusLogger.info("Eco: periodic flush stopped.");
        }
    }
}