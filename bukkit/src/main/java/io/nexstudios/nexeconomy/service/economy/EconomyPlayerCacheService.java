package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.nexeconomy.service.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Dependencies({
    LoggerService.class,
    CurrencyRegistryService.class,
    EconomyRepository.class
})
public final class EconomyPlayerCacheService implements Service {

  private final LoggerService logger;
  private final CurrencyRegistryService currencies;
  private final EconomyRepository repo;

  private final ConcurrentHashMap<UUID, EconomyPlayer> onlineCache = new ConcurrentHashMap<>();

  public EconomyPlayerCacheService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.repo = accessor.getService(EconomyRepository.class);
  }

  public EconomyPlayer getOnline(UUID uuid) {
    if (uuid == null) return null;
    return onlineCache.get(uuid);
  }

  public Collection<EconomyPlayer> allOnlineCached() {
    return onlineCache.values();
  }

  public CompletableFuture<EconomyPlayer> loadOrCreateOnline(@NotNull Player player) {
    UUID uuid = player.getUniqueId();
    EconomyPlayer existing = onlineCache.get(uuid);
    if (existing != null) return CompletableFuture.completedFuture(existing);

    Set<String> ids = currencies.currencyIds();
    return repo.loadBalances(uuid, ids).thenApply(db -> {
      EconomyPlayer econ = new EconomyPlayer(uuid);

      for (String id : ids) {
        MantissaAmount fromDb = db.get(id);
        if (fromDb != null) {
          econ.getOrCreate(id, fromDb);
          continue;
        }

        CurrencyDefinition def = currencies.currency(id);
        MantissaAmount start = startAmount(def);
        econ.getOrCreate(id, start).set(start); // mark dirty to persist missing currency
      }

      onlineCache.put(uuid, econ);
      return econ;
    });
  }

  public void ensureMissingCurrenciesForAllOnline() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      loadOrCreateOnline(p).thenAccept(econ -> ensureMissingCurrencies(econ));
    }
  }

  public void ensureMissingCurrencies(EconomyPlayer econ) {
    if (econ == null) return;
    Set<String> ids = currencies.currencyIds();
    for (String id : ids) {
      if (econ.entry(id) != null) continue;

      CurrencyDefinition def = currencies.currency(id);
      MantissaAmount start = startAmount(def);
      econ.getOrCreate(id, start).set(start); // dirty
    }
  }

  public EconomyPlayer remove(UUID uuid) {
    if (uuid == null) return null;
    return onlineCache.remove(uuid);
  }

  private static MantissaAmount startAmount(CurrencyDefinition def) {
    if (def == null) return MantissaAmount.zero();
    BigDecimal start = def.startBalance() == null ? BigDecimal.ZERO : def.startBalance();
    // start-balance is a human value; we store it at exp3=0 and normalize (canonicalization will shift if needed).
    return MantissaAmount.of(start, 0);
  }
}