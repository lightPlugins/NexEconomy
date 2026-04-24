package io.nexstudios.nexeconomy.service.placeholder.impl;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.placeholder.AbstractCurrencyPlaceholder;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;

import java.time.Duration;

/**
 * Placeholder: {@code %nexeconomy:<currency>_symbol_plural%}
 *
 * <p>Returns the plural symbol of the currency (static value, long TTL).</p>
 */
public final class CurrencySymbolPluralPlaceholder extends AbstractCurrencyPlaceholder {

  public CurrencySymbolPluralPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def) {
    super(service, def);
    register(this);
  }

  @Override
  public String id() {
    return currencyId + "_symbol_plural";
  }

  @Override
  public Duration ttl() {
    return Duration.ofHours(24);
  }

  @Override
  public String resolve(PlaceholderResolveContext ctx) {
    return def.symbolPlural();
  }
}
