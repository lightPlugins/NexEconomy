package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.*;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.AmountSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.CurrencySuggestion;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.command.suggestions.PlayerSuggestion;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.economy.EconomyLocks;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.math.BigDecimal;

import java.util.Locale;
import java.util.UUID;

@CommandRoot(
    name = "pay",
    description = "NexEconomy admin command"
)
@Dependencies({
    ComponentService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyFlushService.class
})
public class EconomyPayCommand implements Service {

  private final ComponentService componentService;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flushService;

  public EconomyPayCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.flushService = accessor.getService(EconomyFlushService.class);
  }

  @Command(value = "<currency> <target> <amount>", permission = "nexeconomy.admin")
  public int pay(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) Player target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount
  ) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    if (target == null || !target.isOnline()) {
      player.sendMessage(componentService.builder(player, "general.player-not-found", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("player", "unknown")))
          .build());
      return 0;
    }

    if (player.getUniqueId().equals(target.getUniqueId())) {
      player.sendMessage(componentService.builder(player, "currency.payment.pay-yourself", "NotDefined", true).build());
      return 0;
    }

    String cur = normalize(currency);
    CurrencyDefinition def = currencies.currency(cur);
    if (def == null) {
      player.sendMessage(componentService.builder(player, "general.response-error", "NotDefined", true)
          .resolver(TagResolver.resolver(Placeholder.parsed("error", "Unknown currency")))
          .build());
      return 0;
    }

    if (!def.payable()) {
      player.sendMessage(componentService.builder(player, "currency.payment.pay-disabled", "NotDefined", true).build());
      return 0;
    }

    MantissaAmount parsed;
    if (def.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(amount);
      parsed = human == null ? null : MantissaAmount.of(human, 0);
    } else {
      parsed = AmountNotation.parseVirtualMantissaAmount(amount);
    }

    if (parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
      player.sendMessage(componentService.builder(player, "general.wrong-amount", "NotDefined", true).build());
      return 0;
    }

    cache.loadOrCreateOnline(player).thenCompose(senderEcon ->
        cache.loadOrCreateOnline(target).thenApply(targetEcon -> {
          TransferResult res = applyLocalTransfer(senderEcon, targetEcon, cur, parsed, def.maxBalance());
          if (!res.success()) return res;

          flushService.requestFlush(senderEcon);
          flushService.requestFlush(targetEcon);
          return res;
        })
    ).thenAccept(res -> {
      String requestedShown = AmountNotation.formatShort(parsed, def.fractionDigits());
      String paidShown = AmountNotation.formatShort(res.paid(), def.fractionDigits());

      if (!res.success()) {
        player.sendMessage(componentService.builder(player, "currency.payment.pay-failed", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", target.getName()),
                Placeholder.parsed("amount", requestedShown),
                Placeholder.parsed("currency", def.symbolPlural())
            ))
            .build());
        return;
      }

      // Target already at max => nothing transferred
      if (res.paid().compareTo(MantissaAmount.zero()) == 0) {
        String maxShown = AmountNotation.formatShort(MantissaAmount.of(def.maxBalance(), 0), def.fractionDigits());

        player.sendMessage(componentService.builder(player, "currency.max-balance.pay-blocked", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", target.getName()),
                Placeholder.parsed("currency", def.symbolPlural()),
                Placeholder.parsed("max", maxShown)
            ))
            .build());
        return;
      }

      // Partially transferred because max-balance would be exceeded
      if (res.capped()) {
        String maxShown = AmountNotation.formatShort(MantissaAmount.of(def.maxBalance(), 0), def.fractionDigits());

        player.sendMessage(componentService.builder(player, "currency.max-balance.pay-capped", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", target.getName()),
                Placeholder.parsed("currency", def.symbolPlural()),
                Placeholder.parsed("requested", requestedShown),
                Placeholder.parsed("paid", paidShown),
                Placeholder.parsed("max", maxShown)
            ))
            .build());

        target.sendMessage(componentService.builder(target, "currency.max-balance.pay-capped-target", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("player", player.getName()),
                Placeholder.parsed("currency", def.symbolPlural()),
                Placeholder.parsed("requested", requestedShown),
                Placeholder.parsed("paid", paidShown),
                Placeholder.parsed("max", maxShown)
            ))
            .build());
        return;
      }

      // Normal full transfer
      player.sendMessage(componentService.builder(player, "currency.payment.pay-success", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", target.getName()),
              Placeholder.parsed("amount", paidShown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());

      target.sendMessage(componentService.builder(target, "currency.payment.pay-success-target", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("player", player.getName()),
              Placeholder.parsed("amount", paidShown),
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

  private record TransferResult(boolean success, MantissaAmount paid, boolean capped) {
    static TransferResult failed() { return new TransferResult(false, MantissaAmount.zero(), false); }
    static TransferResult ok(MantissaAmount paid, boolean capped) { return new TransferResult(true, paid, capped); }
  }

  private static TransferResult applyLocalTransfer(
      EconomyPlayer sender,
      EconomyPlayer target,
      String currencyId,
      MantissaAmount requestedDelta,
      BigDecimal maxBalanceHuman
  ) {
    if (sender == null || target == null) return TransferResult.failed();
    if (currencyId == null || currencyId.isBlank()) return TransferResult.failed();
    if (requestedDelta == null || requestedDelta.isNegative()) return TransferResult.failed();

    UUID a = sender.uuid();
    UUID b = target.uuid();
    if (a == null || b == null) return TransferResult.failed();

    var first = a.compareTo(b) <= 0 ? a : b;
    var second = a.compareTo(b) <= 0 ? b : a;

    var l1 = EconomyLocks.lockFor(first);
    var l2 = EconomyLocks.lockFor(second);

    l1.lock();
    try {
      l2.lock();
      try {
        EconomyPlayer.BalanceEntry from = sender.getOrCreate(currencyId, MantissaAmount.zero());
        EconomyPlayer.BalanceEntry to = target.getOrCreate(currencyId, MantissaAmount.zero());

        MantissaAmount senderCurrent = from.amount() == null ? MantissaAmount.zero() : from.amount();
        if (senderCurrent.compareTo(requestedDelta) < 0) return TransferResult.failed();

        MantissaAmount targetCurrent = to.amount() == null ? MantissaAmount.zero() : to.amount();

        MantissaAmount delta = requestedDelta;
        boolean capped = false;

        // Apply max-balance (human) if configured (>= 0). -1 => unlimited
        if (maxBalanceHuman != null && maxBalanceHuman.compareTo(BigDecimal.ZERO) >= 0) {
          BigDecimal remaining = maxBalanceHuman.subtract(targetCurrent.toHuman());
          if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return TransferResult.ok(MantissaAmount.zero(), true);
          }

          BigDecimal reqHuman = requestedDelta.toHuman();
          BigDecimal allowedHuman = reqHuman.min(remaining);

          if (allowedHuman.compareTo(BigDecimal.ZERO) <= 0) {
            return TransferResult.ok(MantissaAmount.zero(), true);
          }

          MantissaAmount allowed = MantissaAmount.of(allowedHuman, 0);
          if (allowed.compareTo(requestedDelta) < 0) capped = true;
          delta = allowed;
        }

        if (delta.compareTo(MantissaAmount.zero()) == 0) {
          return TransferResult.ok(MantissaAmount.zero(), true);
        }

        from.subtract(delta);
        to.add(delta);
        return TransferResult.ok(delta, capped);
      } finally {
        l2.unlock();
      }
    } finally {
      l1.unlock();
    }
  }

  private static String normalize(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
  }
}