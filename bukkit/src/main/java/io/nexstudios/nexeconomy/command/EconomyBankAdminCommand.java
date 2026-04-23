package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.Arg;
import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.annotations.Suggest;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.BankSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.PlayerSuggestion;
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

@CommandRoot(
    name = "bank",
    description = "Bank commands"
)
@Dependencies({
    ComponentService.class
})
public final class EconomyBankAdminCommand implements Service {

  private final ComponentService components;

  public EconomyBankAdminCommand(ServiceAccessor accessor) {
    this.components = accessor.getService(ComponentService.class);
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  admin unlockbank <bank> <player>
  // ─────────────────────────────────────────────────────────────────────────

  @Command(value = "admin unlockbank <bank> <player>", permission = "nexeconomy.bank.admin.unlock-bank")
  public int unlockBank(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("player") @Suggest(PlayerSuggestion.class) String playerName
  ) {
    CommandSender sender = source.sender();
    if (sender == null) return 0;

    String bankIdLower = normalize(bank);
    if (bankIdLower.isBlank()) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock-bank.invalid-bank", "NotDefined", true).build());
      return 0;
    }

    Player target = resolveOnline(playerName);
    if (target == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock-bank.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", playerName == null ? "unknown" : playerName)))
          .build());
      return 0;
    }

    EcoPlayer eco = EcoPlayer.of(target);
    if (eco == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.not-loaded", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", target.getName())))
          .build());
      return 0;
    }

    Boolean cached = eco.banks().isUnlocked(bankIdLower);
    if (Boolean.TRUE.equals(cached)) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock-bank.already-unlocked", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankIdLower),
              Placeholder.parsed("player", target.getName())
          ))
          .build());
      return 0;
    }

    UUID actorUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
    eco.banks().unlock(bankIdLower, actorUuid);

    sender.sendMessage(components.builder(sender, "bank.admin.unlock-bank.success-sender", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("bank", bankIdLower),
            Placeholder.parsed("player", target.getName())
        ))
        .build());
    target.sendMessage(components.builder(target, "bank.admin.unlock-bank.success-target", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("bank", bankIdLower)))
        .build());
    return 1;
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  admin lockbank <bank> <player>
  // ─────────────────────────────────────────────────────────────────────────

  @Command(value = "admin lockbank <bank> <player>", permission = "nexeconomy.bank.admin.lock-bank")
  public int lockBank(
      NexPaperCommandSource source,
      @Arg("bank") @Suggest(BankSuggestion.class) String bank,
      @Arg("player") @Suggest(PlayerSuggestion.class) String playerName
  ) {
    CommandSender sender = source.sender();
    if (sender == null) return 0;

    String bankIdLower = normalize(bank);
    if (bankIdLower.isBlank()) {
      sender.sendMessage(components.builder(sender, "bank.admin.lock-bank.invalid-bank", "NotDefined", true).build());
      return 0;
    }

    Player target = resolveOnline(playerName);
    if (target == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.lock-bank.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", playerName == null ? "unknown" : playerName)))
          .build());
      return 0;
    }

    EcoPlayer eco = EcoPlayer.of(target);
    if (eco == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.not-loaded", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", target.getName())))
          .build());
      return 0;
    }

    Boolean cached = eco.banks().isUnlocked(bankIdLower);
    if (Boolean.FALSE.equals(cached)) {
      sender.sendMessage(components.builder(sender, "bank.admin.lock-bank.already-locked", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("bank", bankIdLower),
              Placeholder.parsed("player", target.getName())
          ))
          .build());
      return 0;
    }

    UUID actorUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
    eco.banks().lock(bankIdLower, actorUuid);

    sender.sendMessage(components.builder(sender, "bank.admin.lock-bank.success-sender", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("bank", bankIdLower),
            Placeholder.parsed("player", target.getName())
        ))
        .build());
    target.sendMessage(components.builder(target, "bank.admin.lock-bank.success-target", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("bank", bankIdLower)))
        .build());
    return 1;
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  admin lockplayer <player> <reason>
  // ─────────────────────────────────────────────────────────────────────────

  @Command(value = "admin lockplayer <player> <reason>", permission = "nexeconomy.bank.admin.lockplayer")
  public int lockPlayer(
      NexPaperCommandSource source,
      @Arg("player") @Suggest(PlayerSuggestion.class) String player,
      @Arg("reason") @Nullable String reason
  ) {
    CommandSender sender = source.sender();
    if (sender == null) return 0;

    UUID targetUuid = resolveUuid(player);
    if (targetUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.lock-player.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", player == null ? "unknown" : player)))
          .build());
      return 0;
    }

    EcoPlayer eco = EcoPlayer.of(targetUuid);
    if (eco == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.not-loaded", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", nameOrUuid(targetUuid))))
          .build());
      return 0;
    }

    Boolean cached = eco.banks().isPlayerLocked();
    if (Boolean.TRUE.equals(cached)) {
      sender.sendMessage(components.builder(sender, "bank.admin.lock-player.already-locked", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", nameOrUuid(targetUuid))))
          .build());
      return 0;
    }

    UUID actorUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
    eco.banks().lockPlayer(actorUuid, reason);

    sender.sendMessage(components.builder(sender, "bank.admin.lock-player.success-sender", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("player", nameOrUuid(targetUuid))))
        .build());
    return 1;
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  admin unlockplayer <player>
  // ─────────────────────────────────────────────────────────────────────────

  @Command(value = "admin unlockplayer <player>", permission = "nexeconomy.bank.admin.unlockplayer")
  public int unlockPlayer(
      NexPaperCommandSource source,
      @Arg("player") @Suggest(PlayerSuggestion.class) String player
  ) {
    CommandSender sender = source.sender();
    if (sender == null) return 0;

    UUID targetUuid = resolveUuid(player);
    if (targetUuid == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock-player.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", player == null ? "unknown" : player)))
          .build());
      return 0;
    }

    EcoPlayer eco = EcoPlayer.of(targetUuid);
    if (eco == null) {
      sender.sendMessage(components.builder(sender, "bank.admin.not-loaded", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", nameOrUuid(targetUuid))))
          .build());
      return 0;
    }

    Boolean cached = eco.banks().isPlayerLocked();
    if (Boolean.FALSE.equals(cached)) {
      sender.sendMessage(components.builder(sender, "bank.admin.unlock-player.not-locked", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", nameOrUuid(targetUuid))))
          .build());
      return 0;
    }

    UUID actorUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
    eco.banks().unlockPlayer(actorUuid);

    sender.sendMessage(components.builder(sender, "bank.admin.unlock-player.success-sender", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("player", nameOrUuid(targetUuid))))
        .build());
    return 1;
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private static @Nullable Player resolveOnline(@Nullable String name) {
    if (name == null || name.isBlank()) return null;
    Player p = Bukkit.getPlayerExact(name.trim());
    return (p != null && p.isOnline()) ? p : null;
  }

  private static @Nullable UUID resolveUuid(@Nullable String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isBlank()) return null;
    try { return UUID.fromString(s); } catch (Exception ignored) { }
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
