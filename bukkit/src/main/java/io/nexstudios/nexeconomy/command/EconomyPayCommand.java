package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.*;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.AmountSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.CurrencySuggestion;
import io.nexstudios.nexeconomy.command.suggestions.PlayerSuggestion;
import io.nexstudios.nexeconomy.service.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.definition.AmountNotation;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.Locale;

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

    MantissaAmount parsed = AmountNotation.parseToMantissaAmount(amount);
    if (parsed == null || parsed.isNegative() || parsed.compareTo(MantissaAmount.zero()) == 0) {
      player.sendMessage(componentService.builder(player, "general.wrong-amount", "NotDefined", true).build());
      return 0;
    }

    cache.loadOrCreateOnline(player).thenCompose(senderEcon ->
        cache.loadOrCreateOnline(target).thenApply(targetEcon -> {
          boolean ok = applyLocalTransfer(senderEcon, targetEcon, cur, parsed);
          if (!ok) return false;

          flushService.flushPlayerDirty(senderEcon);
          flushService.flushPlayerDirty(targetEcon);
          return true;
        })
    ).thenAccept(success -> {
      String shown = AmountNotation.formatShort(parsed, def.fractionDigits());

      if (!success) {
        player.sendMessage(componentService.builder(player, "currency.payment.pay-failed", "NotDefined", true)
            .resolver(TagResolver.resolver(
                Placeholder.parsed("target", target.getName()),
                Placeholder.parsed("amount", shown),
                Placeholder.parsed("currency", def.symbolPlural())
            ))
            .build());
        return;
      }

      player.sendMessage(componentService.builder(player, "currency.payment.pay-success", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("target", target.getName()),
              Placeholder.parsed("amount", shown),
              Placeholder.parsed("currency", def.symbolPlural())
          ))
          .build());

      target.sendMessage(componentService.builder(target, "currency.payment.pay-success-target", "NotDefined", true)
          .resolver(TagResolver.resolver(
              Placeholder.parsed("player", player.getName()),
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

  private static boolean applyLocalTransfer(EconomyPlayer sender, EconomyPlayer target, String currencyId, MantissaAmount delta) {
    if (sender == null || target == null) return false;
    if (currencyId == null || currencyId.isBlank()) return false;
    if (delta == null || delta.isNegative()) return false;

    EconomyPlayer.BalanceEntry from = sender.getOrCreate(currencyId, MantissaAmount.zero());
    EconomyPlayer.BalanceEntry to = target.getOrCreate(currencyId, MantissaAmount.zero());

    MantissaAmount current = from.amount() == null ? MantissaAmount.zero() : from.amount();
    if (current.compareTo(delta) < 0) return false;

    from.subtract(delta);
    to.add(delta);
    return true;
  }

  private static String normalize(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
  }
}