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

/**
 * Admin command class for bank-related management actions.
 *
 * <p>All operations work exclusively on the cache of {@link EcoPlayer}.
 * If a player is not cached (i.e. not loaded), the action is aborted without
 * a fallback. Persistence and Redis synchronisation across servers are handled
 * internally by the respective container.</p>
 *
 * <p>Registered commands:</p>
 * <ul>
 *   <li>{@code /bank admin unlockbank <bank> <player>}</li>
 *   <li>{@code /bank admin lockbank <bank> <player>}</li>
 *   <li>{@code /bank admin lockplayer <player> <reason>}</li>
 *   <li>{@code /bank admin unlockplayer <player>}</li>
 * </ul>
 */
@CommandRoot(
    name = "bank",
    description = "Bank commands"
)
@Dependencies({
    ComponentService.class
})
public final class EconomyBankAdminCommand implements Service {

  private final ComponentService components;

  /**
   * Creates a new instance and injects the {@link ComponentService}.
   *
   * @param accessor DI accessor used to resolve services
   */
  public EconomyBankAdminCommand(ServiceAccessor accessor) {
    this.components = accessor.getService(ComponentService.class);
  }

  /**
   * Unlocks a bank for the specified player.
   *
   * <p>Silently aborts if the player is not online or not cached.
   * If the bank is already unlocked, an appropriate message is sent to the sender.</p>
   *
   * @param source     command source
   * @param bank       bank ID (will be normalised to lower-case)
   * @param playerName name of the target player (must be online)
   * @return {@code 1} on success, {@code 0} on abort
   */
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

  /**
   * Locks a bank for the specified player.
   *
   * <p>Silently aborts if the player is not online or not cached.
   * If the bank is already locked, an appropriate message is sent to the sender.</p>
   *
   * @param source     command source
   * @param bank       bank ID (will be normalised to lower-case)
   * @param playerName name of the target player (must be online)
   * @return {@code 1} on success, {@code 0} on abort
   */
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

  /**
   * Globally locks a player from all bank operations.
   *
   * <p>The player must be cached; if not, the action is aborted.
   * An optional reason can be provided and will be stored internally.</p>
   *
   * @param source command source
   * @param player name or UUID of the target player
   * @param reason optional lock reason (may be {@code null})
   * @return {@code 1} on success, {@code 0} on abort
   */
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

  /**
   * Removes the global bank lock from a player.
   *
   * <p>The player must be cached; if not, the action is aborted.
   * If the player is not locked, an appropriate message is sent to the sender.</p>
   *
   * @param source command source
   * @param player name or UUID of the target player
   * @return {@code 1} on success, {@code 0} on abort
   */
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

  /**
   * Returns the online {@link Player} with the exact given name.
   *
   * @param name player name (may be {@code null})
   * @return the online player, or {@code null} if not found
   */
  private static @Nullable Player resolveOnline(@Nullable String name) {
    if (name == null || name.isBlank()) return null;
    Player p = Bukkit.getPlayerExact(name.trim());
    return (p != null && p.isOnline()) ? p : null;
  }

  /**
   * Resolves a {@link UUID} from a player name or UUID string.
   *
   * <p>Resolution order:</p>
   * <ol>
   *   <li>Direct UUID parsing</li>
   *   <li>Online player by exact name</li>
   *   <li>Cached offline player</li>
   * </ol>
   *
   * @param raw raw input (name or UUID string, may be {@code null})
   * @return the resolved UUID, or {@code null} if not found
   */
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

  /**
   * Returns the display name of the player, or their UUID as a string if the name is unknown.
   *
   * @param uuid UUID of the player (may be {@code null})
   * @return player name or UUID string, never {@code null}
   */
  private static String nameOrUuid(UUID uuid) {
    if (uuid == null) return "unknown";
    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
    String name = off.getName();
    return (name == null || name.isBlank()) ? uuid.toString() : name;
  }

  /**
   * Normalises a string by trimming whitespace and converting to lower-case.
   *
   * @param s input string (may be {@code null})
   * @return normalised string, never {@code null}
   */
  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}
