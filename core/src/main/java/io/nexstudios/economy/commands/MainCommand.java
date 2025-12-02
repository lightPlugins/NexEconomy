package io.nexstudios.economy.commands;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.economy.currency.NexCurrency;
import io.nexstudios.economy.storage.InMemoryEcoService;
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

        // Eigene Namensauflösung: erst online, dann bekannte Offline-Spieler (case-insensitive)
        OfflinePlayer target = resolveKnownPlayerByName(targetName);

        // Hier ist target tatsächlich nullable -> Prüfung ist sinnvoll
        if (target == null) {
            NexEconomy.getInstance().getMessageSender().send(sender, "general.player-not-found", resolver);
            return;
        }

        NexEconomy plugin = NexEconomy.getInstance();
        InMemoryEcoService eco = plugin.getEcoService();
        Collection<NexCurrency> currencies = plugin.getNexEcoFactory().getCurrencies();
        UUID playerId = target.getUniqueId();

        NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-player-start", resolver);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int created = eco.resetPlayer(playerId, currencies);
            NexEconomy.nexusLogger.info("Admin reset for player " + playerId + " created " + created + " account(s).");
            NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-player-complete", resolver);
        });
    }

    @Subcommand("resetall")
    @CommandPermission("nexeconomy.admin.resetall")
    @Description("Deletes all economy data for all players and recreates accounts for online players.")
    public void onResetAll(CommandSender sender) {
        NexEconomy plugin = NexEconomy.getInstance();
        InMemoryEcoService eco = plugin.getEcoService();
        Collection<NexCurrency> currencies = plugin.getNexEcoFactory().getCurrencies();

        NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-global");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int created = eco.resetAllPlayers(currencies);
            NexEconomy.nexusLogger.info("Admin global reset created " + created + " account(s) for online players.");
            NexEconomy.getInstance().getMessageSender().send(sender, "general.reset-global-complete");
        });
    }

    @Nullable
    private OfflinePlayer resolveKnownPlayerByName(String name) {
        if (name == null || name.isBlank()) return null;

        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            p.getName();
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
