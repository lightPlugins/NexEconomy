package io.nexstudios.economy;

import io.nexstudios.economy.storage.InMemoryEcoService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Loads accounts on join if not present in cache and flushes on quit.
 */
public record EcoPlayerListener(InMemoryEcoService eco) implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var playerId = e.getPlayer().getUniqueId();

        // Load from DB only if not in cache
        int loaded = eco.loadAllForPlayerIfAbsent(playerId);

        // Ensure accounts for all known currencies (creates missing accounts with start balance in cache)
        var allCurrencies = NexEconomy.getInstance().getNexEcoFactory().getCurrencies();
        int created = eco.ensureAccountsForPlayer(playerId, allCurrencies);

        // Immediately persist newly created accounts (so they exist in DB right after join)
        if (created > 0) {
            eco.flushPlayerNow(playerId);
        }

        if (loaded > 0 || created > 0) {
            NexEconomy.nexusLogger.info("Eco: join preload for " + e.getPlayer().getName() + " loaded=" + loaded + " created=" + created + ".");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Synchronous flush for this player
        eco.flushPlayerNow(e.getPlayer().getUniqueId());
    }
}