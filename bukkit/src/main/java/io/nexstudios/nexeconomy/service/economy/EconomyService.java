package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.nexeconomy.service.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    LoggerService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyFlushService.class
})
public final class EconomyService implements Service {

  private final LoggerService logger;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;

  public EconomyService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.flush = accessor.getService(EconomyFlushService.class);
  }

  public CompletableFuture<MantissaAmount> balance(@NotNull Player player, @NotNull String currencyId) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    return cache.loadOrCreateOnline(player).thenApply(econ -> {
      EconomyPlayer.BalanceEntry entry = econ.entry(cur);
      return entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();
    });
  }

  public CompletableFuture<Boolean> set(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount amount) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    MantissaAmount value = MantissaAmount.normalize(amount);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      econ.getOrCreate(cur, MantissaAmount.zero()).set(value);
      flush.flushPlayerDirty(econ);
      return true;
    });
  }

  public CompletableFuture<Boolean> add(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    MantissaAmount d = MantissaAmount.normalize(delta);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      econ.getOrCreate(cur, MantissaAmount.zero()).add(d);
      flush.flushPlayerDirty(econ);
      return true;
    });
  }

  public CompletableFuture<Boolean> remove(@NotNull Player target, @NotNull String currencyId, @NotNull MantissaAmount delta) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));

    MantissaAmount d = MantissaAmount.normalize(delta);

    return cache.loadOrCreateOnline(target).thenApply(econ -> {
      EconomyPlayer.BalanceEntry entry = econ.getOrCreate(cur, MantissaAmount.zero());
      MantissaAmount current = entry.amount() == null ? MantissaAmount.zero() : entry.amount();

      if (current.compareTo(d) < 0) return false;

      entry.subtract(d);
      flush.flushPlayerDirty(econ);
      return true;
    });
  }

  public CurrencyDefinition requireCurrency(String currencyId) {
    String cur = normalize(currencyId);
    if (cur.isBlank()) return null;
    return currencies.currency(cur);
  }

  public String defaultCurrencyIdOrNull() {
    String vaultId = currencies.vaultCurrencyId();
    if (vaultId != null) return vaultId;

    return currencies.currencies().stream().findFirst().map(CurrencyDefinition::id).orElse(null);
  }

  private static String normalize(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
  }
}