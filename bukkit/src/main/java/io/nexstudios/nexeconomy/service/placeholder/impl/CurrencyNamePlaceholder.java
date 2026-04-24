package io.nexstudios.nexeconomy.service.placeholder.impl;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.placeholder.AbstractCurrencyPlaceholder;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;

import java.time.Duration;

/**
 * Placeholder: {@code %nexeconomy:<currency>_name%}
 *
 * <p>Returns the display name of the currency (static value, medium TTL).</p>
 */
public final class CurrencyNamePlaceholder extends AbstractCurrencyPlaceholder {

  public CurrencyNamePlaceholder(EconomyPlaceholderService service, CurrencyDefinition def) {
    super(service, def);
    register(this);
  }

  @Override
  public String id() {
    return currencyId + "_name";
  }

  @Override
  public Duration ttl() {
    return Duration.ofSeconds(10);
  }

  @Override
  public String resolve(PlaceholderResolveContext ctx) {
    return def.name();
  }
}

