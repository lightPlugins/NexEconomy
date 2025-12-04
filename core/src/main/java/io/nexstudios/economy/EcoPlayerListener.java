package io.nexstudios.economy;

import io.nexstudios.economy.storage.InMemoryEcoService;
import io.nexstudios.nexus.bukkit.redis.NexusRedisApi;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Loads accounts on join if not present in cache and flushes on quit.
 * Additionally, when Redis is active, it notifies other servers so they can
 * preload the player's accounts into their own caches.
 */
public record EcoPlayerListener(InMemoryEcoService eco) implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var player = e.getPlayer();
        var playerId = player.getUniqueId();
        var playerName = player.getName();

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(
                NexEconomy.getInstance(),
                () -> {
                    int loaded = eco.reloadAllForPlayer(playerId);

                    var allCurrencies = NexEconomy.getInstance().getNexEcoFactory().getCurrencies();
                    int created = eco.ensureAccountsForPlayer(playerId, allCurrencies);

                    if (created > 0) {
                        eco.flushPlayerNow(playerId);
                    }

                    if (loaded > 0 || created > 0) {
                        NexEconomy.nexusLogger.info(
                                "Eco: join preload for " + playerName
                                        + " loaded=" + loaded + " created=" + created + "."
                        );
                    }

                    // Notify other servers via Redis (if available and connected)
                    if (NexusRedisApi.isServicePresent() && NexusRedisApi.isConnected()) {
                        var sync = NexEconomy.getInstance().getEconomyRedisSync();
                        if (sync != null) {
                            sync.publishPlayerPreload(playerId);
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        var playerId = e.getPlayer().getUniqueId();
        // Async DB save
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(
                NexEconomy.getInstance(),
                () -> eco.flushPlayerNow(playerId)
        );
    }
}