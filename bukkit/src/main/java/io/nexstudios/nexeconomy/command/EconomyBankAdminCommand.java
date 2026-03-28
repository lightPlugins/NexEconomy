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
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

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

  @Command(value = "admin lock <bank> <player>", permission = "nexeconomy.bank.admin.lock")
  public int lock(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("player") @Suggest(PlayerSuggestion.class) String playerName
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    String bankIdLower = normalize(bank);
    if (bankIdLower.isBlank()) {
      sender.sendMessage(components.builder(sender, "bank.admin.lock.invalid-bank", "NotDefined", true).build());
      return 0;
    }

    Player target = (playerName == null || playerName.isBlank()) ? null : Bukkit.getPlayerExact(playerName.trim());
    if (target == null || !target.isOnline()) {
      sender.sendMessage(components.builder(sender, "bank.admin.lock.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", playerName == null ? "unknown" : playerName)))
          .build());
      return 0;
    }

    bankService.lockBankForPlayer(bankIdLower, target.getUniqueId(), sender.getUniqueId()).thenAccept(changed -> {
      if (Boolean.TRUE.equals(changed)) {
        sender.sendMessage(components.builder(sender, "bank.admin.lock.success-sender", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("bank", bankIdLower),
                Placeholder.parsed("player", target.getName())
            ))
            .build());

        target.sendMessage(components.builder(target, "bank.admin.lock.success-target", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("bank", bankIdLower)))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.admin.lock.already-locked", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankIdLower),
              Placeholder.parsed("player", target.getName())
          ))
          .build());
    }).exceptionally(ex -> {
      String msg = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
          ? "Unknown"
          : ex.getMessage();

      sender.sendMessage(components.builder(sender, "bank.admin.lock.failed", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", msg)))
          .build());
      return null;
    });

    return 1;
  }

  @Command(value = "admin lockaccounts <player> [reason]", permission = "nexeconomy.bank.admin.lockaccounts")
  public int lockAccounts(
      NexPaperCommandSource source,
      @Arg("player") String player,
      @Arg("reason") String reason
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID targetUuid = resolveUuid(player);
    if (targetUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.lockaccounts.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", player == null ? "unknown" : player)))
          .build());
      return 0;
    }

    bankService.lockAllBankAccountsForPlayer(targetUuid, sender.getUniqueId(), reason).thenAccept(changed -> {
      if (Boolean.TRUE.equals(changed)) {
        sender.sendMessage(components.builder(sender, "bank.admin.lockaccounts.success-sender", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("player", nameOrUuid(targetUuid))
            ))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.admin.lockaccounts.already-locked", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("player", nameOrUuid(targetUuid))
          ))
          .build());
    }).exceptionally(ex -> {
      String msg = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
          ? "Unknown"
          : ex.getMessage();

      sender.sendMessage(components.builder(sender, "bank.admin.lockaccounts.failed", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", msg)))
          .build());
      return null;
    });

    return 1;
  }

  @Command(value = "admin unlock-accounts <player>", permission = "nexeconomy.bank.admin.unlock-accounts")
  public int unlockAccounts(
      NexPaperCommandSource source,
      @Arg("player") String player
  ) {
    Player sender = (Player) source.sender();
    if (sender == null) return 0;

    UUID targetUuid = resolveUuid(player);
    if (targetUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock-accounts.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", player == null ? "unknown" : player)))
          .build());
      return 0;
    }

    bankService.unlockAllBankAccountsForPlayer(targetUuid, sender.getUniqueId()).thenAccept(changed -> {
      if (Boolean.TRUE.equals(changed)) {
        sender.sendMessage(components.builder(sender, "bank.admin.unlock-accounts.success-sender", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("player", nameOrUuid(targetUuid))
            ))
            .build());
        return;
      }

      sender.sendMessage(components.builder(sender, "bank.admin.unlock-accounts.not-locked", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("player", nameOrUuid(targetUuid))
          ))
          .build());
    }).exceptionally(ex -> {
      String msg = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
          ? "Unknown"
          : ex.getMessage();

      sender.sendMessage(components.builder(sender, "bank.admin.unlock-accounts.failed", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", msg)))
          .build());
      return null;
    });

    return 1;
  }

  private static UUID resolveUuid(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isBlank()) return null;

    try {
      return UUID.fromString(s);
    } catch (Exception ignored) {
      // not a UUID
    }

    Player online = Bukkit.getPlayerExact(s);
    if (online != null && online.isOnline()) return online.getUniqueId();

    OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(s);
    return cached == null ? null : cached.getUniqueId();
  }

  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) return "unknown";
    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
    String name = off.getName();
    return (name == null || name.isBlank()) ? uuid.toString() : name;
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}