package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.nexlogic.bukkit.services.entity.EconomyBalanceEntity;
import io.nexstudios.nexeconomy.service.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.definition.CurrencyType;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
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
    CurrencyRegistryService.class,
    EconomyRepository.class
})
public final class EconomyPlayerCacheService implements Service {

  private final CurrencyRegistryService currencies;
  private final EconomyRepository repo;

  private final ConcurrentHashMap<UUID, EconomyPlayer> onlineCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, CompletableFuture<EconomyPlayer>> inFlightLoads = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<UUID, EconomyPlayer> townyCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, CompletableFuture<EconomyPlayer>> townyInFlightLoads = new ConcurrentHashMap<>();

  public EconomyPlayerCacheService(ServiceAccessor accessor) {
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.repo = accessor.getService(EconomyRepository.class);
  }

  public EconomyPlayer getOnline(UUID uuid) {
    if (uuid == null) return null;
    return onlineCache.get(uuid);
  }

  public EconomyPlayer getTowny(UUID uuid) {
    if (uuid == null) return null;
    return townyCache.get(uuid);
  }

  public Collection<EconomyPlayer> allOnlineCached() {
    return onlineCache.values();
  }

  public void invalidate(UUID uuid) {
    invalidate(uuid, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public void invalidate(UUID uuid, EconomyBalanceEntity.EconomyAccountType accountType) {
    if (uuid == null) return;

    if (accountType == EconomyBalanceEntity.EconomyAccountType.TOWNY) {
      townyCache.remove(uuid);
      townyInFlightLoads.remove(uuid);
      return;
    }

    onlineCache.remove(uuid);
    inFlightLoads.remove(uuid);
  }

  public CompletableFuture<EconomyPlayer> loadOrCreateOnline(@NotNull Player player) {
    UUID uuid = player.getUniqueId();

    EconomyPlayer existing = onlineCache.get(uuid);
    if (existing != null) return CompletableFuture.completedFuture(existing);

    CompletableFuture<EconomyPlayer> inflight = inFlightLoads.get(uuid);
    if (inflight != null) return inflight;

    return inFlightLoads.computeIfAbsent(uuid, ignoredKey -> {
      Set<String> ids = currencies.currencyIds();

      CompletableFuture<EconomyPlayer> f = repo.loadBalances(uuid, ids, EconomyBalanceEntity.EconomyAccountType.PLAYER).thenApply(db -> {
        EconomyPlayer econ = new EconomyPlayer(uuid);

        for (String id : ids) {
          MantissaAmount fromDb = db.get(id);
          if (fromDb != null) {
            econ.getOrCreate(id, fromDb);
            continue;
          }

          CurrencyDefinition def = currencies.currency(id);
          MantissaAmount start = startAmount(def);
          econ.getOrCreate(id, start).set(start);
        }

        onlineCache.put(uuid, econ);
        return econ;
      });

      return f.whenComplete((result, error) -> inFlightLoads.remove(uuid));
    });
  }

  public CompletableFuture<EconomyPlayer> loadOrCreateTowny(@NotNull UUID uuid) {
    EconomyPlayer existing = townyCache.get(uuid);
    if (existing != null) return CompletableFuture.completedFuture(existing);

    CompletableFuture<EconomyPlayer> inflight = townyInFlightLoads.get(uuid);
    if (inflight != null) return inflight;

    return townyInFlightLoads.computeIfAbsent(uuid, ignoredKey -> {
      Set<String> ids = townyCurrencyIds();

      CompletableFuture<EconomyPlayer> f = repo.loadBalances(uuid, ids, EconomyBalanceEntity.EconomyAccountType.TOWNY).thenApply(db -> {
        EconomyPlayer econ = new EconomyPlayer(uuid);

        for (String id : ids) {
          MantissaAmount fromDb = db.get(id);
          if (fromDb != null) {
            econ.getOrCreate(id, fromDb);
            continue;
          }

          CurrencyDefinition def = currencies.currency(id);
          MantissaAmount start = startAmount(def);
          econ.getOrCreate(id, start).set(start);
        }

        townyCache.put(uuid, econ);
        return econ;
      });

      return f.whenComplete((result, error) -> townyInFlightLoads.remove(uuid));
    });
  }

  private Set<String> townyCurrencyIds() {
    // Towny accounts should only have Vault currencies, never virtual currencies like "gems".
    Set<String> out = new HashSet<>();
    for (String id : currencies.currencyIds()) {
      CurrencyDefinition def = currencies.currency(id);
      if (def != null && def.type() == CurrencyType.VAULT) {
        out.add(id);
      }
    }
    return out;
  }

  public void ensureMissingCurrenciesForAllOnline() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      loadOrCreateOnline(p).thenAccept(this::ensureMissingCurrencies);
    }
  }

  public void ensureMissingCurrencies(EconomyPlayer econ) {
    if (econ == null) return;
    Set<String> ids = currencies.currencyIds();
    for (String id : ids) {
      if (econ.entry(id) != null) continue;

      CurrencyDefinition def = currencies.currency(id);
      MantissaAmount start = startAmount(def);
      econ.getOrCreate(id, start).set(start);
    }
  }

  public EconomyPlayer remove(UUID uuid) {
    if (uuid == null) return null;
    return onlineCache.remove(uuid);
  }

  private static MantissaAmount startAmount(CurrencyDefinition def) {
    if (def == null) return MantissaAmount.zero();
    BigDecimal start = def.startBalance() == null ? BigDecimal.ZERO : def.startBalance();
    return MantissaAmount.of(start, 0);
  }
}