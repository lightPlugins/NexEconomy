package io.nexstudios.nexeconomy.command;

import io.nexstudios.commandservice.service.commands.annotations.*;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.command.suggestions.AmountSuggestion;
import io.nexstudios.nexeconomy.command.suggestions.CurrencySuggestion;
import io.nexstudios.nexeconomy.command.suggestions.PlayerSuggestion;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Locale;

@CommandRoot(
    name = "pay",
    description = "NexEconomy pay command"
)
@Dependencies({
    ComponentService.class,
    CurrencyRegistryService.class,
    EconomyService.class
})
public class EconomyPayCommand implements Service {

  private final ComponentService componentService;
  private final CurrencyRegistryService currencies;
  private final EconomyService economy;

  public EconomyPayCommand(ServiceAccessor accessor) {
    this.componentService = accessor.getService(ComponentService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.economy = accessor.getService(EconomyService.class);
  }

  @Command(value = "<currency> <target> <amount>", permission = "nexeconomy.use")
  public int pay(
      NexPaperCommandSource source,
      @Arg("currency") @Suggest(CurrencySuggestion.class) String currency,
      @Arg("target") @Suggest(PlayerSuggestion.class) Player target,
      @Arg("amount") @Suggest(AmountSuggestion.class) String amount
  ) {
    if (!(source.sender() instanceof Player player)) {
      source.sender().sendMessage(componentService.builder(source.sender(), "general.player-only", "NotDefined", true).build());
      return 0;
    }

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

    economy.transfer(player, target, cur, parsed).thenAccept(res -> {
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

      // Target already at max-balance – nothing transferred
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

      // Partially capped
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

      // Full transfer
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


  private static String normalize(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
  }
}