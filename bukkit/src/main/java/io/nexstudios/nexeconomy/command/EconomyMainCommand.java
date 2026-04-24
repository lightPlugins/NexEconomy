package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.*;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.AmountSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.CurrencySuggestion;
import io.nexstudios.nexeconomy.command.suggestions.PlayerSuggestion;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.economy.leaderboard.EconomyLeaderboardService;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Main command class for player-facing and admin economy operations.
 *
 * <p>All balance-related operations work exclusively on the {@link EcoPlayer} cache.
 * If a player's data is not yet loaded, the action is aborted and a loading message
 * is displayed. Persistence and cross-server Redis synchronisation are handled by
 * the underlying containers ({@code vault} / {@code virtual}).</p>
 *
 * <p>Registered commands:</p>
 * <ul>
 *   <li>{@code /currency} – show own balance (default currency)</li>
 *   <li>{@code /currency balance [currency]}</li>
 *   <li>{@code /currency balance <currency> <target>}</li>
 *   <li>{@code /currency set <currency> <target> <amount> [silent]}</li>
 *   <li>{@code /currency add <currency> <target> <amount> [silent]}</li>
 *   <li>{@code /currency remove <currency> <target> <amount> [silent]}</li>
 *   <li>{@code /currency top <currency>}</li>
 * </ul>
 *
 * <p>Command aliases: {@code money}, {@code eco}.</p>
 */
@CommandRoot(
    name = "currency", aliases = {"money", "eco"},
    description = "Money commands"
)
@Dependencies({
    ComponentService.class,
    EconomyService.class,
    EconomyLeaderboardService.class,
})
public final class EconomyMainCommand implements Service {

  private final ComponentService componentService;
  private final EconomyService economy;
  private final Plugin plugin;
  private final EconomyLeaderboardService leaderboard;

  /**
   * Creates a new instance and injects all required services.
   *
   * @param accessor DI accessor used to resolve services
   */
  public EconomyMainCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.economy = accessor.getService(EconomyService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.leaderboard = accessor.getService(EconomyLeaderboardService.class);
  }

  /**
   * Root command handler – delegates to {@link #balance(NexPaperCommandSource)}.
   *
   * @param source command source
   * @return result of {@link #balance}
   */
  @Command(value = "", permission = "nexeconomy.use")
  public int root(NexPaperCommandSource source) {
    return balance(source);
  }

  /**
   * Displays the calling player's balance in the default currency.
   *
   * <p>Only usable by players. Aborts with an error message if no default
   * currency is configured.</p>
   *
   * @param source command source (must be a {@link Player})
   * @return {@code 1} on success, {@code 0} on abort
   */
  @Command(value = "balance", permission = "nexeconomy.use")
  public int balance(NexPaperCommandSource source) {
    if (!(source.sender() instanceof Player player)) {
      source.sender().sendMessage(componentService.builder(source.sender(), "general.player-only", "NotDefined", true).build());
      return 0;
    }

    String cur = economy.defaultCurrencyIdOrNull();
    if (cur == null) {
      sendError(source.sender(), "No currency configured");
      return 0;
    }
    return balanceCurrency(source, cur);
  }

  /**
   * Displays the calling player's balance for the given currency.
   *
   * <p>Only usable by players. Aborts if the currency is unknown or the
   * player's data is not yet cached.</p>
   *
   * @param source   command source (must be a {@link Player})
   * @param currency currency ID
   * @return {@code 1} on success, {@code 0} on abort
   */
  @Command(value = "balance <currency>", permission = "nexeconomy.use")
  public int balanceCurrency(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency
  ) {
    if (!(source.sender() instanceof Player player)) {
      source.sender().sendMessage(componentService.builder(source.sender(), "general.player-only", "NotDefined", true).build());
      return 0;
    }

    CurrencyDefinition def = economy.requireCurrency(currency);
    if (def == null) { sendError(source.sender(), "Unknown currency"); return 0; }

    EcoPlayer eco = EcoPlayer.of(player);
    if (eco == null) { sendLoading(source.sender()); return 1; }

    MantissaAmount amount = def.type() == CurrencyType.VAULT
        ? eco.vault().balance(def.id())
        : eco.virtual().balance(def.id());

    source.sender().sendMessage(componentService.builder(source.sender(), "currency.balance", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("amount", formatAmount(amount, def)),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    return 1;
  }

  /**
   * Displays another online player's balance for the given currency (admin only).
   *
   * @param source   command source
   * @param currency currency ID
   * @param target   name of the target player (must be online)
   * @return {@code 1} on success, {@code 0} on abort
   */
  @Command(value = "balance <currency> <target>", permission = "nexeconomy.admin")
  public int balanceOtherCurrency(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target
  ) {
    CommandSender sender = source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (targetPlayer == null || !targetPlayer.isOnline()) {
      sender.sendMessage(componentService.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", target)))
          .build());
      return 0;
    }

    CurrencyDefinition def = economy.requireCurrency(currency);
    if (def == null) { sendError(sender, "Unknown currency"); return 0; }

    EcoPlayer eco = EcoPlayer.of(targetPlayer);
    if (eco == null) { sendLoading(sender); return 1; }

    MantissaAmount amount = def.type() == CurrencyType.VAULT
        ? eco.vault().balance(def.id())
        : eco.virtual().balance(def.id());

    sender.sendMessage(componentService.builder(sender, "currency.balance-other", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("target", targetPlayer.getName()),
            Placeholder.parsed("amount", formatAmount(amount, def)),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    return 1;
  }

  /**
   * Sets a player's balance for the given currency to an exact amount.
   *
   * <p>Negative amounts are rejected. If the optional {@code silent} flag is
   * {@code true}, no confirmation messages are sent to either party.</p>
   *
   * @param source   command source
   * @param currency currency ID
   * @param target   name of the target player (must be online)
   * @param amount   new balance amount (human-readable notation)
   * @param silent   when {@code true}, suppresses all feedback messages
   * @return {@code 1} on success, {@code 0} on abort
   */
  @Command(value = "set <currency> <target> <amount> [silent]", permission = "nexeconomy.admin")
  public int set(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount,
      @OptionalArg("silent") boolean silent
  ) {
    CommandSender sender = source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (targetPlayer == null || !targetPlayer.isOnline()) {
      sender.sendMessage(componentService.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", target)))
          .build());
      return 0;
    }

    CurrencyDefinition def = economy.requireCurrency(currency);
    MantissaAmount parsed = parseAmount(def, amount);

    if (def == null || parsed == null || parsed.isNegative()) {
      sender.sendMessage(componentService.builder(sender, "general.not-negative-number", "NotDefined", true).build());
      return 0;
    }

    EcoPlayer eco = EcoPlayer.of(targetPlayer);
    if (eco == null) { sendLoading(sender); return 1; }

    if (def.type() == CurrencyType.VAULT) {
      eco.vault().set(def.id(), parsed);
    } else {
      eco.virtual().set(def.id(), parsed);
    }

    // prevents messages if command is in silent optional mode
    if(silent) {
      return 1;
    }

    String shown = AmountNotation.formatShort(parsed, def.fractionDigits());
    sender.sendMessage(componentService.builder(sender, "currency.set", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("target", targetPlayer.getName()),
            Placeholder.parsed("amount", shown),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    targetPlayer.sendMessage(componentService.builder(targetPlayer, "currency.set-other", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("player", sender.getName()),
            Placeholder.parsed("amount", shown),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    return 1;
  }

  /**
   * Adds an amount to a player's balance for the given currency.
   *
   * <p>Zero and negative amounts are rejected. If the resulting balance would
   * exceed the currency's maximum, the addition is capped and informational
   * messages are sent. If {@code silent} is {@code true}, success messages
   * are suppressed (cap warnings are still shown).</p>
   *
   * @param source   command source
   * @param currency currency ID
   * @param target   name of the target player (must be online)
   * @param amount   amount to add (human-readable notation)
   * @param silent   when {@code true}, suppresses success feedback messages
   * @return {@code 1} on success, {@code 0} on abort
   */
  @Command(value = "add <currency> <target> <amount> [silent]", permission = "nexeconomy.admin")
  public int add(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount,
      @OptionalArg("silent") boolean silent
  ) {
    CommandSender sender = source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (targetPlayer == null || !targetPlayer.isOnline()) {
      sender.sendMessage(componentService.builder(sender, "general.player-not-found", "NotDefined", true)
              .resolver(TagResolver.resolver(Placeholder.parsed("player", target)))
          .build());
      return 0;
    }

    CurrencyDefinition def = economy.requireCurrency(currency);
    MantissaAmount parsed = parseAmount(def, amount);

    if (def == null || parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
      sender.sendMessage(componentService.builder(sender, "general.wrong-amount", "NotDefined", true).build());
      return 0;
    }

    EcoPlayer eco = EcoPlayer.of(targetPlayer);
    if (eco == null) { sendLoading(sender); return 1; }

    MantissaAmount added = def.type() == CurrencyType.VAULT
        ? eco.vault().add(def.id(), parsed)
        : eco.virtual().add(def.id(), parsed);

    String requestedShown = AmountNotation.formatShort(parsed, def.fractionDigits());
    String addedShown = AmountNotation.formatShort(added, def.fractionDigits());

    if (added.compareTo(MantissaAmount.zero()) == 0) {
      String maxShown = def.maxBalance() != null ? formatAmount(MantissaAmount.of(def.maxBalance(), 0), def) : "∞";
      sender.sendMessage(componentService.builder(sender, "currency.max-balance.reached-admin", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", targetPlayer.getName()),
              Placeholder.parsed("currency", def.symbolPlural()),
              Placeholder.parsed("max", maxShown)
          ))
          .build());
      return 1;
    }

    if (added.compareTo(parsed) < 0) {
      String maxShown = def.maxBalance() != null ? formatAmount(MantissaAmount.of(def.maxBalance(), 0), def) : "∞";
      sender.sendMessage(componentService.builder(sender, "currency.max-balance.capped-admin", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", targetPlayer.getName()),
              Placeholder.parsed("currency", def.symbolPlural()),
              Placeholder.parsed("requested", requestedShown),
              Placeholder.parsed("added", addedShown),
              Placeholder.parsed("max", maxShown)
          ))
          .build());
      targetPlayer.sendMessage(componentService.builder(targetPlayer, "currency.max-balance.capped-target", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("player", sender.getName()),
              Placeholder.parsed("currency", def.symbolPlural()),
              Placeholder.parsed("requested", requestedShown),
              Placeholder.parsed("added", addedShown),
              Placeholder.parsed("max", maxShown)
          ))
          .build());
      return 1;
    }

    // prevents messages if command is in silent optional mode
    if(silent) {
      return 1;
    }

    sender.sendMessage(componentService.builder(sender, "currency.deposit", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("target", targetPlayer.getName()),
            Placeholder.parsed("amount", addedShown),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    targetPlayer.sendMessage(componentService.builder(targetPlayer, "currency.deposit-other", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("target", sender.getName()),
            Placeholder.parsed("amount", addedShown),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    return 1;
  }

  /**
   * Removes an amount from a player's balance for the given currency.
   *
   * <p>Zero and negative amounts are rejected. If the player has insufficient
   * funds the operation fails and a failure message is sent to the sender.
   * If {@code silent} is {@code true}, success messages are suppressed.</p>
   *
   * @param source   command source
   * @param currency currency ID
   * @param target   name of the target player (must be online)
   * @param amount   amount to remove (human-readable notation)
   * @param silent   when {@code true}, suppresses success feedback messages
   * @return {@code 1} on success, {@code 0} on abort
   */
  @Command(value = "remove <currency> <target> <amount> [silent]", permission = "nexeconomy.admin")
  public int remove(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount,
      @OptionalArg("silent") boolean silent
  ) {
    CommandSender sender = source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (targetPlayer == null || !targetPlayer.isOnline()) {
      sender.sendMessage(componentService.builder(sender, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", target)))
          .build());
      return 0;
    }

    CurrencyDefinition def = economy.requireCurrency(currency);
    MantissaAmount parsed = parseAmount(def, amount);

    if (def == null || parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
      sender.sendMessage(componentService.builder(sender, "general.wrong-amount", "NotDefined", true).build());
      return 0;
    }

    EcoPlayer eco = EcoPlayer.of(targetPlayer);
    if (eco == null) { sendLoading(sender); return 1; }

    String shown = AmountNotation.formatShort(parsed, def.fractionDigits());

    boolean ok = def.type() == CurrencyType.VAULT
        ? eco.vault().remove(def.id(), parsed)
        : eco.virtual().remove(def.id(), parsed);

    if (!ok) {
      sender.sendMessage(componentService.builder(sender, "currency.payment.pay-failed", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", targetPlayer.getName()),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());
      return 1;
    }

    // prevents messages if command is in silent optional mode
    if(silent) {
      return 1;
    }

    sender.sendMessage(componentService.builder(sender, "currency.withdraw", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("target", targetPlayer.getName()),
            Placeholder.parsed("amount", shown),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    targetPlayer.sendMessage(componentService.builder(targetPlayer, "currency.withdraw-other", "NotDefined", true)
        .resolver(TagResolver.resolver(
            Placeholder.parsed("player", sender.getName()),
            Placeholder.parsed("amount", shown),
            Placeholder.parsed("currency", def.symbolPlural())
        ))
        .build());
    return 1;
  }

  /**
   * Displays the balance leaderboard for the given currency (top 10).
   *
   * <p>If the leaderboard snapshot is not yet available, a loading message is
   * shown and the command polls asynchronously for up to 5 seconds before
   * giving up.</p>
   *
   * @param source   command source
   * @param currency currency ID
   * @return {@code 1} on success, {@code 0} on abort
   */
  @Command(value = "top <currency>", permission = "nexeconomy.use")
  public int top(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency
  ) {
    CommandSender sender = source.sender();

    CurrencyDefinition def = economy.requireCurrency(currency);
    if (def == null) { sendError(sender, "Unknown currency"); return 0; }

    String cur = def.id();
    Optional<EconomyLeaderboardService.SnapshotView> viewOpt = leaderboard.getTop(cur, 10);

    if (viewOpt.isEmpty()) {
      sender.sendMessage(componentService.builder(sender, "currency.baltop.loading", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("currency", def.symbolPlural())))
          .build());

      waitForLeaderboard(cur, 10, 5000).thenAccept(viewOptAsync -> {
        if (viewOptAsync.isEmpty()) {
          sendError(sender, "Leaderboard still loading, please try again");
          return;
        }
        displayLeaderboard(sender, viewOptAsync.get(), def);
      }).exceptionally(ex -> {
        sendError(sender, "Failed to load leaderboard");
        return null;
      });
      return 1;
    }

    displayLeaderboard(sender, viewOpt.get(), def);
    return 1;
  }

  /**
   * Sends a "data still loading" error message to the given sender.
   *
   * @param sender recipient of the message
   */
  private void sendLoading(CommandSender sender) {
    sendError(sender, "Your economy data is still loading, please try again in a moment.");
  }

  /**
   * Sends a generic error message with the given text to the sender.
   *
   * @param sender  recipient of the message
   * @param message plain-text error detail
   */
  private void sendError(CommandSender sender, String message) {
    sender.sendMessage(componentService.builder(sender, "general.response-error", "NotDefined", true)
        .resolver(TagResolver.resolver(Placeholder.parsed("error", message)))
        .build());
  }

  /**
   * Formats a {@link MantissaAmount} to a short human-readable string.
   *
   * @param amount amount to format
   * @param def    currency definition providing the fraction-digit setting
   * @return formatted amount string, or {@code "0"} if {@code def} is {@code null}
   */
  private static String formatAmount(MantissaAmount amount, CurrencyDefinition def) {
    if (def == null) return "0";
    return AmountNotation.formatShort(amount, def.fractionDigits());
  }

  /**
   * Parses a raw amount string into a {@link MantissaAmount} respecting the
   * currency type (Vault vs. virtual).
   *
   * @param def currency definition (may be {@code null})
   * @param raw raw input string (may be {@code null})
   * @return parsed amount, or {@code null} if input is invalid
   */
  private static MantissaAmount parseAmount(CurrencyDefinition def, String raw) {
    if (def == null || raw == null) return null;
    if (def.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(raw);
      return human == null ? null : MantissaAmount.of(human, 0);
    }
    return AmountNotation.parseVirtualMantissaAmount(raw);
  }

  /**
   * Returns a {@link CompletableFuture} that resolves once the leaderboard
   * snapshot for the given currency becomes available, or times out.
   *
   * @param currency  currency ID
   * @param limit     maximum number of leaderboard entries to retrieve
   * @param timeoutMs maximum time to wait in milliseconds
   * @return future completing with the snapshot view, or {@link Optional#empty()} on timeout
   */
  private CompletableFuture<Optional<EconomyLeaderboardService.SnapshotView>> waitForLeaderboard(
      String currency, int limit, long timeoutMs
  ) {
    long startTime = System.currentTimeMillis();
    CompletableFuture<Optional<EconomyLeaderboardService.SnapshotView>> future = new CompletableFuture<>();
    pollLeaderboard(currency, limit, startTime, timeoutMs, future);
    return future;
  }

  /**
   * Recursively polls the leaderboard at increasing intervals until the snapshot
   * is available or the timeout is exceeded.
   *
   * @param currency  currency ID
   * @param limit     maximum number of leaderboard entries to retrieve
   * @param startTime timestamp (ms) when polling started
   * @param timeoutMs maximum total polling duration in milliseconds
   * @param future    future to complete once data is available or timed out
   */
  private void pollLeaderboard(
      String currency, int limit, long startTime, long timeoutMs,
      CompletableFuture<Optional<EconomyLeaderboardService.SnapshotView>> future
  ) {
    long elapsed = System.currentTimeMillis() - startTime;
    Optional<EconomyLeaderboardService.SnapshotView> viewOpt = leaderboard.getTop(currency, limit);

    if (viewOpt.isPresent()) {
      future.complete(viewOpt);
      return;
    }
    if (elapsed >= timeoutMs) {
      future.complete(Optional.empty());
      return;
    }

    long delay = Math.min(50 * (1 + elapsed / 500), 500);
    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
        () -> pollLeaderboard(currency, limit, startTime, timeoutMs, future),
        delay / 50);
  }

  /**
   * Renders the leaderboard snapshot to the given sender on the main thread.
   *
   * <p>Displays a header, one line per ranked entry, and a footer using the
   * configured language keys.</p>
   *
   * @param sender recipient of the leaderboard output
   * @param view   leaderboard snapshot to display
   * @param def    currency definition used for formatting amounts
   */
  private void displayLeaderboard(CommandSender sender, EconomyLeaderboardService.SnapshotView view, CurrencyDefinition def) {
    var rows = view.top();

    String overall = (rows == null || rows.isEmpty())
        ? "0"
        : AmountNotation.formatShort(
            rows.stream()
                .filter(r -> r != null && r.amount() != null)
                .map(EconomyLeaderboardService.Row::amount)
                .reduce(MantissaAmount.zero(), MantissaAmount::add),
            def.fractionDigits());

    Bukkit.getScheduler().runTask(plugin, () -> {
      componentService.getComponents(
          sender, "currency.baltop.header", "NotDefined",
          TagResolver.resolver(
              Placeholder.parsed("overall", overall),
              Placeholder.parsed("currency", def.symbolPlural())
          ),
          false
      ).forEach(sender::sendMessage);

      int i = 1;
      if (rows != null) {
        for (EconomyLeaderboardService.Row row : rows) {
          if (row == null || row.uuid() == null) continue;
          OfflinePlayer off = Bukkit.getOfflinePlayer(row.uuid());
          String name = off.getName() == null ? row.uuid().toString() : off.getName();
          sender.sendMessage(componentService.builder(sender, "currency.baltop.content", "NotDefined", false)
              .resolver(TagResolver.resolver(
                  Placeholder.parsed("number", String.valueOf(i)),
                  Placeholder.parsed("name", name),
                  Placeholder.parsed("amount", AmountNotation.formatShort(row.amount(), def.fractionDigits())),
                  Placeholder.parsed("currency", def.symbolPlural())
              ))
              .build());
          i++;
        }
      }
      componentService.getComponents(sender, "currency.baltop.footer", "NotDefined", false)
          .forEach(sender::sendMessage);
    });
  }
}

