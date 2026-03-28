package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Arg;
import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.annotations.Suggest;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.BankSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.PlayerSuggestion;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

@CommandRoot(
    name = "bank",
    description = "Bank admin commands"
)
@Dependencies({
    BankService.class,
    ComponentService.class
})
public final class EconomyBankAdminCommand implements Service {

  private final BankService bankService;
  private final ComponentService components;

  public EconomyBankAdminCommand(ServiceAccessor accessor) {
    this.bankService = accessor.getService(BankService.class);
    this.components = accessor.getService(ComponentService.class);
  }

  @Command(value = "admin unlock <bank> <player>", permission = "nexeconomy.bank.admin.unlock")
  public int unlock(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("player") @Suggest(PlayerSuggestion.class) String playerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankIdLower = normalize(bank);
    if (bankIdLower.isBlank()) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock.invalid-bank", "NotDefined", true).build());
      return 0;
    }

    Player target = (playerName == null || playerName.isBlank()) ? null : Bukkit.getPlayerExact(playerName.trim());
    if (target == null || !target.isOnline()) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", playerName == null ? "unknown" : playerName)))
          .build());
      return 0;
    }

    bankService.unlockBankForPlayer(bankIdLower, target.getUniqueId(), sender.getUniqueId()).thenAccept(created -> {
      if (Boolean.TRUE.equals(created)) {
        sender.sendMessage(components.builder(sender, "bank.admin.unlock.success-sender", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("bank", bankIdLower),
                Placeholder.parsed("player", target.getName())
            ))
            .build());

        target.sendMessage(components.builder(target, "bank.admin.unlock.success-target", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("bank", bankIdLower)))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.admin.unlock.already-unlocked", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankIdLower),
              Placeholder.parsed("player", target.getName())
          ))
          .build());
    }).exceptionally(ex -> {
      String msg = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
          ? "Unknown"
          : ex.getMessage();

      sender.sendMessage(components.builder(sender, "bank.admin.unlock.failed", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", msg)))
          .build());
      return null;
    });

    return 1;
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}