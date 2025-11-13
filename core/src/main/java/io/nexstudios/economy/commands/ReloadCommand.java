package io.nexstudios.economy.commands;

import io.nexstudios.economy.NexEconomy;
import io.nexstudios.nexus.libs.commands.BaseCommand;
import io.nexstudios.nexus.libs.commands.annotation.*;
import org.bukkit.command.CommandSender;

@CommandAlias("nexeconomy")
public class ReloadCommand extends BaseCommand {

    @Subcommand("reload")
    @CommandCompletion("@inventories")
    @CommandPermission("nexus.command.admin.reload")
    @Description("Reloads the plugin configuration and settings.")
    public void onReload(CommandSender sender) {

        NexEconomy.getInstance().onReload();
        NexEconomy.getInstance().messageSender.send(sender, "general.reload");

    }
}
