package io.nexstudios.economy.commands;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.currency.NexCurrencyType;
import io.nexstudios.economy.storage.InMemoryEcoService;
import io.nexstudios.nexus.bukkit.NexusPlugin;
import io.nexstudios.nexus.bukkit.redis.NexusRedisApi;
import io.nexstudios.nexus.libs.commands.BaseCommand;
import io.nexstudios.nexus.libs.commands.annotation.*;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.UUID;

@CommandAlias("nexeconomy")
public class MainCommand extends BaseCommand {

    @Subcommand("reload")
    @CommandCompletion("@inventories")
    @CommandPermission("nexus.command.admin.reload")
    @Description("Reloads the plugin configuration and settings.")
    public void onReload(CommandSender sender) {

        NexEconomy.getInstance().onReload();
        NexEconomy.getInstance().messageSender.send(sender, "general.reload");

    }

    @Subcommand("reset")
    @CommandCompletion("@ecoAllPlayers")
    @CommandPermission("nexeconomy.admin.reset")
    @Syntax("<player>")
    @Description("Deletes all economy data for a player and recreates fresh accounts.")
    public void onResetPlayer(CommandSender sender, String targetName) {

        TagResolver resolver = TagResolver.resolver(Placeholder.parsed("player", targetName));

        if (targetName.isBlank()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.player-not-found", resolver);
            return;
        }

        boolean redisActive = NexusRedisApi.isServicePresent()
                && NexusRedisApi.isConnected()
                && NexusPlugin.getInstance().isCrossServerEnabled();

        // Resolve player name: first online, then known offline players (case-insensitive)
        OfflinePlayer target = resolveKnownPlayerByName(targetName);

        if (target == null) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.player-not-found", resolver);
            return;
        }

        // If Redis is not active, only allow resetting players that are currently
        // online on this server. This prevents wiping data for players that might
        // be active on another server.
        if (!redisActive && !target.isOnline()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error", resolver);
            return;
        }

        NexEconomy plugin = NexEconomy.getInstance();
        InMemoryEcoService eco = plugin.getEcoService();
        Collection<NexCurrency> currencies = plugin.getNexEcoFactory().getCurrencies();
        UUID playerId = target.getUniqueId();

        NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-player-start", resolver);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int created = eco.resetPlayer(playerId, currencies);

            // If Redis sync is active, broadcast a player reset so other servers clear their caches.
            if (redisActive && plugin.getEconomyRedisSync() != null) {
                plugin.getEconomyRedisSync().publishPlayerReset(playerId);
            }

            NexEconomy.nexusLogger.info("Admin reset for player " + playerId + " created " + created + " account(s).");
            NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-player-complete", resolver);

            // Notify player on this server if online
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online != null && online.isOnline()) {
                    NexEconomy.getInstance().getMessageSender().send(online, "global.reset-target");
                }
            });
        });
    }

    @Subcommand("resetall")
    @CommandPermission("nexeconomy.admin.resetall")
    @Description("Deletes all economy data for all players and recreates accounts for online players.")
    public void onResetAll(CommandSender sender) {
        NexEconomy plugin = NexEconomy.getInstance();

        boolean redisActive = NexusRedisApi.isServicePresent()
                && NexusRedisApi.isConnected();

        if (!redisActive && NexusPlugin.getInstance().isCrossServerEnabled()) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        InMemoryEcoService eco = plugin.getEcoService();
        Collection<NexCurrency> currencies = plugin.getNexEcoFactory().getCurrencies();

        NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-global");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int created = eco.resetAllPlayers(currencies);

            // Redis is active here by design; broadcast a global reset so other
            // servers clear their caches.
            if (plugin.getEconomyRedisSync() != null) {
                plugin.getEconomyRedisSync().publishGlobalReset();
            }

            NexEconomy.nexusLogger.info("Admin global reset created " + created + " account(s) for online players.");
            NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-global-complete");

            // Notify all online players on this server
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    NexEconomy.getInstance().getMessageSender().send(p, "global.reset-target");
                }
            });
        });
    }


    @Subcommand("sync currencies")
    @CommandPermission("nexeconomy.admin.sync")
    @Description("Synchronizes all virtual currency definitions to other servers via Redis.")
    public void onSyncCurrencies(CommandSender sender) {
        NexEconomy plugin = NexEconomy.getInstance();

        boolean crossServerEnabled = NexusPlugin.getInstance().isCrossServerEnabled();
        boolean redisActive = NexusRedisApi.isServicePresent() && NexusRedisApi.isConnected();

        if (!crossServerEnabled || !redisActive || plugin.getEconomyRedisSync() == null) {
            plugin.getMessageSender().send(sender, "general.cross-server-error");
            return;
        }

        plugin.getMessageSender().send(sender, "general.sync-currencies");

        var ecoSync = plugin.getEconomyRedisSync();
        var factory = plugin.getNexEcoFactory();

        int count = 0;
        for (NexCurrency currency : factory.getCurrencies()) {
            if (currency.getCurrencyType() != NexCurrencyType.VIRTUAL) continue;
            String key = factory.keyOf(currency);
            ecoSync.publishCurrencyDefinition(currency, key);
            count++;
        }

        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("amount", String.valueOf(count))
        );

        plugin.getMessageSender().send(sender, "general.sync-currencies.complete", resolver);
    }


    @Nullable
    private OfflinePlayer resolveKnownPlayerByName(String name) {
        if (name == null || name.isBlank()) return null;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                return op;
            }
        }

        return null;
    }
}