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
import io.nexstudios.nexeconomy.service.economy.leaderboard.EconomyLeaderboardService;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

  public EconomyMainCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.economy = accessor.getService(EconomyService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.leaderboard = accessor.getService(EconomyLeaderboardService.class);
  }

  @Command(value = "", permission = "nexeconomy.use")
  public int root(NexPaperCommandSource source) {
    return balance(source);
  }

  @Command(value = "balance", permission = "nexeconomy.use")
  public int balance(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    String cur = economy.defaultCurrencyIdOrNull();
    if (cur == null) {
      player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", "No currency configured")))
          .build());
      return 0;
    }

    return balanceCurrency(source, cur);
  }

  @Command(value = "balance <currency>", permission = "nexeconomy.use")
  public int balanceCurrency(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency
  ) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    CurrencyDefinition def = economy.requireCurrency(currency);
    if (def == null) {
      player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", "Unknown currency")))
          .build());
      return 0;
    }

    economy.balance(player, def.id()).thenAccept(amount -> {
      String shown = formatHuman(amount, def);
      player.sendMessage(componentService.builder(player, "currency.balance", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());
    }).exceptionally(ex -> {
      player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", ex.getMessage() == null ? "Unknown" : ex.getMessage())))
          .build());
      return null;
    });

    return 1;
  }

  @Command(value = "balance <currency> <target>", permission = "nexeconomy.admin")
  public int balanceOtherCurrency(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target
  ) {
    Player player = (Player) source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (player == null) return 0;
    if (targetPlayer == null || !targetPlayer.isOnline()) return 0;

    CurrencyDefinition def = economy.requireCurrency(currency);
    if (def == null) {
      player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", "Unknown currency")))
          .build());
      return 0;
    }

    economy.balance(targetPlayer, def.id()).thenAccept(amount -> {
      String shown = formatHuman(amount, def);
      player.sendMessage(componentService.builder(player, "currency.balance-other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", targetPlayer.getName()),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());
    }).exceptionally(ex -> {
      player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", ex.getMessage() == null ? "Unknown" : ex.getMessage())))
          .build());
      return null;
    });

    return 1;
  }

  @Command(value = "top <currency>", permission = "nexeconomy.use")
  public int top(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency
  ) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    CurrencyDefinition def = economy.requireCurrency(currency);
    if (def == null) {
      player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", "Unknown currency")))
          .build());
      return 0;
    }

    String cur = def.id();

    Optional<EconomyLeaderboardService.SnapshotView> viewOpt = leaderboard.getTop(cur, 10);
    if (viewOpt.isEmpty()) {
      // CRITICAL FIX: Send "loading" message instead of error, then wait for async loading
      player.sendMessage(componentService.builder(player, "currency.baltop.loading", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("currency", def.symbolPlural())))
          .build());

      // Wait for leaderboard to load asynchronously with timeout
      CompletableFuture<Optional<EconomyLeaderboardService.SnapshotView>> loadingFuture = 
          waitForLeaderboard(cur, 10, 5000); // 5 second timeout

      loadingFuture.thenAccept(viewOptAsync -> {
        if (viewOptAsync.isEmpty()) {
          player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
              .resolver(TagResolver.resolver(Placeholder.parsed("error", "Leaderboard still loading, please try again")))
              .build());
          return;
        }

        displayLeaderboard(player, viewOptAsync.get(), def);
      }).exceptionally(ex -> {
        player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
            .resolver(TagResolver.resolver(Placeholder.parsed("error", "Failed to load leaderboard")))
            .build());
        return null;
      });

      return 1;
    }

    // Display immediately if already loaded
    displayLeaderboard(player, viewOpt.get(), def);
    return 1;
  }

  private CompletableFuture<Optional<EconomyLeaderboardService.SnapshotView>> waitForLeaderboard(String currency, int limit, long timeoutMs) {
    long startTime = System.currentTimeMillis();
    CompletableFuture<Optional<EconomyLeaderboardService.SnapshotView>> future = new CompletableFuture<>();

    // Poll for leaderboard data with exponential backoff
    pollLeaderboard(currency, limit, startTime, timeoutMs, future);

    return future;
  }

  private void pollLeaderboard(String currency, int limit, long startTime, long timeoutMs, CompletableFuture<Optional<EconomyLeaderboardService.SnapshotView>> future) {
    long elapsed = System.currentTimeMillis() - startTime;

    Optional<EconomyLeaderboardService.SnapshotView> viewOpt = leaderboard.getTop(currency, limit);
    if (viewOpt.isPresent()) {
      // Data loaded, complete the future
      future.complete(viewOpt);
      return;
    }

    if (elapsed >= timeoutMs) {
      // Timeout reached, complete with empty
      future.complete(Optional.empty());
      return;
    }

    // Schedule next poll with exponential backoff (start at 50ms, cap at 500ms)
    long delay = Math.min(50 * (1 + elapsed / 500), 500);
    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
      pollLeaderboard(currency, limit, startTime, timeoutMs, future);
    }, delay / 50); // Convert to ticks (20 ticks = 1 second)
  }

  private void displayLeaderboard(Player player, EconomyLeaderboardService.SnapshotView view, CurrencyDefinition def) {
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
          player,
          "currency.baltop.header",
          "NotDefined",
          TagResolver.resolver(
              Placeholder.parsed("overall", overall),
              Placeholder.parsed("currency", def.symbolPlural())
          ),
          false
      ).forEach(player::sendMessage);

      int i = 1;
      if (rows != null) {
        for (EconomyLeaderboardService.Row row : rows) {
          if (row == null || row.uuid() == null) continue;

          OfflinePlayer off = Bukkit.getOfflinePlayer(row.uuid());
          String name = off.getName() == null ? row.uuid().toString() : off.getName();

          String shown = AmountNotation.formatShort(row.amount(), def.fractionDigits());

          player.sendMessage(componentService.builder(player, "currency.baltop.content", "NotDefined", false)
              .resolver(TagResolver.resolver(
                  Placeholder.parsed("number", String.valueOf(i)),
                  Placeholder.parsed("name", name),
                  Placeholder.parsed("amount", shown),
                  Placeholder.parsed("currency", def.symbolPlural())
              ))
              .build());
          i++;
        }
      }

      componentService.getComponents(player, "currency.baltop.footer", "NotDefined", false)
          .forEach(player::sendMessage);
    });
  }

  @Command(value = "set <currency> <target> <amount>", permission = "nexeconomy.admin")
  public int set(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount
  ) {
    Player player = (Player) source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (player == null) return 0;
    if (targetPlayer == null || !targetPlayer.isOnline()) return 0;

    CurrencyDefinition def = economy.requireCurrency(currency);

    MantissaAmount parsed;
    if (def != null && def.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(amount);
      parsed = human == null ? null : MantissaAmount.of(human, 0);
    } else {
      parsed = AmountNotation.parseVirtualMantissaAmount(amount);
    }

    if (def == null || parsed == null || parsed.isNegative()) {
      player.sendMessage(componentService.builder(player, "general.not-negative-number", "NotDefined", true).build());
      return 0;
    }

    economy.set(targetPlayer, def.id(), parsed).thenAccept(ok -> {
      if (!ok) return;

      String shown = AmountNotation.formatShort(parsed, def.fractionDigits());
      player.sendMessage(componentService.builder(player, "currency.set", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", targetPlayer.getName()),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());

      targetPlayer.sendMessage(componentService.builder(targetPlayer, "currency.set-other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("player", player.getName()),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());
    });

    return 1;
  }

  @Command(value = "add <currency> <target> <amount>", permission = "nexeconomy.admin")
  public int add(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount
  ) {
    Player player = (Player) source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (player == null) return 0;
    if (targetPlayer == null || !targetPlayer.isOnline()) return 0;

    CurrencyDefinition def = economy.requireCurrency(currency);

    MantissaAmount parsed;
    if (def != null && def.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(amount);
      parsed = human == null ? null : MantissaAmount.of(human, 0);
    } else {
      parsed = AmountNotation.parseVirtualMantissaAmount(amount);
    }

    if (def == null || parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
      player.sendMessage(componentService.builder(player, "general.wrong-amount", "NotDefined", true).build());
      return 0;
    }

    // Max-Balance enforcement: only add remaining until max is reached
    economy.balance(targetPlayer, def.id()).thenCompose(current -> {
      MantissaAmount allowed = capDeltaToMax(def, current, parsed);
      if (allowed.compareTo(MantissaAmount.zero()) == 0) {
        String maxShown = formatHuman(MantissaAmount.of(def.maxBalance(), 0), def);
        player.sendMessage(componentService.builder(player, "currency.max-balance.reached-admin", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", targetPlayer.getName()),
                Placeholder.parsed("currency", def.symbolPlural()),
                Placeholder.parsed("max", maxShown)
            ))
            .build());
        return java.util.concurrent.CompletableFuture.completedFuture(false);
      }

      return economy.add(targetPlayer, def.id(), allowed).thenApply(ok -> {
        if (!ok) return false;

        String requestedShown = AmountNotation.formatShort(parsed, def.fractionDigits());
        String addedShown = AmountNotation.formatShort(allowed, def.fractionDigits());

        if (allowed.compareTo(parsed) < 0) {
          String maxShown = formatHuman(MantissaAmount.of(def.maxBalance(), 0), def);
          player.sendMessage(componentService.builder(player, "currency.max-balance.capped-admin", "NotDefined", true)
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
                  Placeholder.parsed("player", player.getName()),
                  Placeholder.parsed("currency", def.symbolPlural()),
                  Placeholder.parsed("requested", requestedShown),
                  Placeholder.parsed("added", addedShown),
                  Placeholder.parsed("max", maxShown)
              ))
              .build());
          return true;
        }

        player.sendMessage(componentService.builder(player, "currency.deposit", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", targetPlayer.getName()),
                Placeholder.parsed("amount", addedShown),
                Placeholder.parsed("currency", def.symbolPlural())
            ))
            .build());

        targetPlayer.sendMessage(componentService.builder(targetPlayer, "currency.deposit-other", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", player.getName()),
                Placeholder.parsed("amount", addedShown),
                Placeholder.parsed("currency", def.symbolPlural())
            ))
            .build());
        return true;
      });
    });

    return 1;
  }

  private static MantissaAmount capDeltaToMax(CurrencyDefinition def, MantissaAmount current, MantissaAmount requested) {
    if (def == null) return requested;
    BigDecimal max = def.maxBalance();
    if (max == null) return requested;
    if (max.compareTo(BigDecimal.ZERO) < 0) return requested; // -1 => unlimited

    BigDecimal curHuman = (current == null ? MantissaAmount.zero() : current).toHuman();
    BigDecimal reqHuman = (requested == null ? MantissaAmount.zero() : requested).toHuman();

    BigDecimal remaining = max.subtract(curHuman);
    if (remaining.compareTo(BigDecimal.ZERO) <= 0) return MantissaAmount.zero();

    BigDecimal allowedHuman = reqHuman.min(remaining);
    if (allowedHuman.compareTo(BigDecimal.ZERO) <= 0) return MantissaAmount.zero();

    return MantissaAmount.of(allowedHuman, 0);
  }
  @Command(value = "remove <currency> <target> <amount>", permission = "nexeconomy.admin")
  public int remove(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) String target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount
  ) {
    Player player = (Player) source.sender();
    Player targetPlayer = Bukkit.getPlayerExact(target);
    if (player == null) return 0;
    if (targetPlayer == null || !targetPlayer.isOnline()) return 0;

    CurrencyDefinition def = economy.requireCurrency(currency);

    MantissaAmount parsed;
    if (def != null && def.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(amount);
      parsed = human == null ? null : MantissaAmount.of(human, 0);
    } else {
      parsed = AmountNotation.parseVirtualMantissaAmount(amount);
    }

    if (def == null || parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
      player.sendMessage(componentService.builder(player, "general.wrong-amount", "NotDefined", true).build());
      return 0;
    }

    economy.remove(targetPlayer, def.id(), parsed).thenAccept(ok -> {
      String shown = AmountNotation.formatShort(parsed, def.fractionDigits());

      if (!ok) {
        player.sendMessage(componentService.builder(player, "currency.payment.pay-failed", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", targetPlayer.getName()),
                Placeholder.parsed("amount", shown),
                Placeholder.parsed("currency", def.symbolPlural())
            ))
            .build());
        return;
      }

      player.sendMessage(componentService.builder(player, "currency.withdraw", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", targetPlayer.getName()),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());

      targetPlayer.sendMessage(componentService.builder(targetPlayer, "currency.withdraw-other", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("player", player.getName()),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());
    });

    return 1;
  }

  private static BigDecimal parseAmount(String raw) {
    if (raw == null) return null;
    try {
      return new BigDecimal(raw.trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String formatHuman(MantissaAmount amount, CurrencyDefinition def) {
    if (def == null) return "0";
    return AmountNotation.formatShort(amount, def.fractionDigits());
  }
}