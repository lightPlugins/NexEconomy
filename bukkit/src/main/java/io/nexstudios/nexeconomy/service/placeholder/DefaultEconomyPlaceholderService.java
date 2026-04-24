package io.nexstudios.nexeconomy.service.placeholder;

import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.leaderboard.EconomyLeaderboardService;
import io.nexstudios.nexeconomy.service.placeholder.impl.*;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexlogic.common.services.placeholder.PlaceholderService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;

@Dependencies({
    LoggerService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyLeaderboardService.class
})
public final class DefaultEconomyPlaceholderService implements EconomyPlaceholderService, AutoCloseable {

  private static final String OWNER = "nexeconomy";
  private static final String NAMESPACE = "nexeconomy";

  private final LoggerService logger;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyLeaderboardService leaderboard;

  private final PlaceholderService placeholders;

  /** Factories that create and register all placeholders for a given currency. */
  private final List<BiConsumer<EconomyPlaceholderService, CurrencyDefinition>> factories;

  public DefaultEconomyPlaceholderService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.leaderboard = accessor.getService(EconomyLeaderboardService.class);
    this.placeholders = resolvePlaceholderServiceOrThrow();

    this.factories = buildFactories();
  }

  /**
   * Registers all placeholders for every known currency.
   * Must be called explicitly after construction, before the service is used.
   */
  @Override
  public void start() {
    registerAll();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Forwards the placeholder to the underlying {@link PlaceholderService} using
   * the fixed {@code nexeconomy} owner and namespace.</p>
   */
  @Override
  public void register(EconomyPlaceholder placeholder) {
    placeholders.register(
        OWNER,
        NAMESPACE,
        placeholder.id(),
        ctx -> {
          try {
            String out = placeholder.resolve(ctx);
            return out == null ? "" : out;
          } catch (Exception ex) {
            return "";
          }
        },
        placeholder.ttl() == null ? Duration.ZERO : placeholder.ttl()
    );
  }

  public void reload() {
    unregisterAll();
    registerAll();
  }

  @Override
  public void close() {
    unregisterAll();
  }

  private void registerAll() {
    for (CurrencyDefinition def : currencies.currencies()) {
      if (def == null) continue;
      registerCurrency(def);
    }
    logger.logger().info("Registered NexLogic placeholders for " + currencies.currencies().size() + " currencies.");
  }

  /**
   * Creates all placeholder instances for a single currency by invoking each factory.
   * To add a new placeholder type, register a new entry in {@link #buildFactories()}.
   */
  private void registerCurrency(CurrencyDefinition def) {
    factories.forEach(factory -> factory.accept(this, def));
  }

  /**
   * Defines all placeholder types that are created per currency.
   * Each entry is a factory lambda that creates and self-registers one placeholder instance.
   */
  private List<BiConsumer<EconomyPlaceholderService, CurrencyDefinition>> buildFactories() {
    return List.of(
        // Player-balance placeholders
        (svc, def) -> new BalanceAmountRawPlaceholder(svc, def, cache),
        (svc, def) -> new BalanceAmountPlaceholder(svc, def, cache),
        (svc, def) -> new BalanceFormatPlaceholder(svc, def, cache),

        // Static currency information
        CurrencySymbolSingularPlaceholder::new,
        CurrencySymbolPluralPlaceholder::new,
        CurrencyNamePlaceholder::new,

        // Leaderboard – top 1..10
        (svc, def) -> registerTopPlaceholders(svc, def, 10),

        // Leaderboard – calling player's own rank
        (svc, def) -> new TopPlayerPlaceholder(svc, def, cache, leaderboard)
    );
  }

  private void registerTopPlaceholders(EconomyPlaceholderService svc, CurrencyDefinition def, int count) {
    for (int i = 1; i <= count; i++) {
      new TopPlaceholder(svc, def, leaderboard, i);
    }
  }

  private void unregisterAll() {
    try {
      placeholders.unregisterOwner(OWNER);
    } catch (Exception ignored) {
      // no-op
    }
  }

  private PlaceholderService resolvePlaceholderServiceOrThrow() {
    var accessor = NexEconomyPlugin.getNexLogicService();
    if (accessor == null) {
      throw new IllegalStateException("NexLogic service accessor is not initialized.");
    }
    return accessor.getService(PlaceholderService.class);
  }
}