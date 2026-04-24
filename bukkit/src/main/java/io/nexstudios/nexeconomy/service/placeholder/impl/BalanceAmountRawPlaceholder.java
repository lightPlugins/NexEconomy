package io.nexstudios.nexeconomy.service.placeholder.impl;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.placeholder.AbstractCurrencyPlaceholder;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Placeholder: {@code %nexeconomy:<currency>_amount_raw%}
 *
 * <p>Returns the player's raw (unformatted) balance as a plain decimal string.</p>
 */
public final class BalanceAmountRawPlaceholder extends AbstractCurrencyPlaceholder {

  private final EconomyPlayerCacheService cache;

  public BalanceAmountRawPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def, EconomyPlayerCacheService cache) {
    super(service, def);
    this.cache = cache;
    register(this);
  }

  @Override
  public String id() {
    return currencyId + "_amount_raw";
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
    return toRawHumanString(a);
  }
}
