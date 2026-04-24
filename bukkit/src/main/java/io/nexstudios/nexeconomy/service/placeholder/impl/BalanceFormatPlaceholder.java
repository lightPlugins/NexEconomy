package io.nexstudios.nexeconomy.service.placeholder.impl;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.placeholder.AbstractCurrencyPlaceholder;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

/**
 * Placeholder: {@code %nexeconomy:<currency>_format%}
 *
 * <p>Returns the player's balance rendered into the currency's configured
 * {@code playerPlaceholder} template (supports {@code <amount>}, {@code <symbol>},
 * {@code <currency>}).</p>
 */
public final class BalanceFormatPlaceholder extends AbstractCurrencyPlaceholder {

  private final EconomyPlayerCacheService cache;

  public BalanceFormatPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def, EconomyPlayerCacheService cache) {
    super(service, def);
    this.cache = cache;
    register(this);
  }

  @Override
  public String id() {
    return currencyId + "_format";
  }

  @Override
  public Duration ttl() {
    return Duration.ofMillis(250);
  }

  @Override
  public String resolve(PlaceholderResolveContext ctx) {
    Player p = tryResolvePlayer(ctx);
    if (p == null) return "";
    MantissaAmount a = readOnlineAmount(cache, currencyId, p.getUniqueId());
    String amountShown = AmountNotation.formatShort(a, def.fractionDigits());
    String symbol = chooseSymbol(def, a);

    String template = def.playerPlaceholder() == null ? "" : def.playerPlaceholder();
    return applySimpleTemplate(template, Map.of(
        "amount", amountShown,
        "symbol", symbol,
        "currency", def.symbolPlural()
    ));
  }
}
