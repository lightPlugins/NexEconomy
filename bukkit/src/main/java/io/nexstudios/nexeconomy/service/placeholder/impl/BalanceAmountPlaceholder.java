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

/**
 * Placeholder: {@code %nexeconomy:<currency>_amount%}
 *
 * <p>Returns the player's balance formatted with the currency's fraction-digit setting.</p>
 */
public final class BalanceAmountPlaceholder extends AbstractCurrencyPlaceholder {

  private final EconomyPlayerCacheService cache;

  public BalanceAmountPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def, EconomyPlayerCacheService cache) {
    super(service, def);
    this.cache = cache;
    register(this);
  }

  @Override
  public String id() {
    return currencyId + "_amount";
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
    return AmountNotation.formatShort(a, def.fractionDigits());
  }
}

