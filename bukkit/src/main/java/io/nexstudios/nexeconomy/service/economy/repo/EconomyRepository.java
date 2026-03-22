package io.nexstudios.nexeconomy.service.economy.repo;

import io.nexstudios.databaseservice.bukkit.service.api.DatabaseAsyncService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexlogic.bukkit.services.entity.EconomyBalanceEntity;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import jakarta.persistence.LockModeType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    LoggerService.class
})
public final class EconomyRepository implements Service {

  private final LoggerService logger;
  private final DatabaseAsyncService dbAsync;

  public EconomyRepository(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.dbAsync = NexEconomyPlugin.getNexLogicService()
        .findService(DatabaseAsyncService.class)
        .orElseThrow(() -> new IllegalStateException("DatabaseAsyncService not available via NexLogic"));
  }

  public CompletableFuture<Map<String, MantissaAmount>> loadBalances(UUID uuid, Set<String> currencyIdsLower) {
    if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("uuid is null"));
    if (currencyIdsLower == null || currencyIdsLower.isEmpty()) return CompletableFuture.completedFuture(Map.of());

    Set<String> ids = new HashSet<>();
    for (String id : currencyIdsLower) {
      String n = normalizeCurrency(id);
      if (!n.isBlank()) ids.add(n);
    }
    if (ids.isEmpty()) return CompletableFuture.completedFuture(Map.of());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<EconomyBalanceEntity> rows = em.createQuery(
              "select b from EconomyBalanceEntity b where b.playerUuid = :uuid and b.currency in :curs",
              EconomyBalanceEntity.class
          )
          .setParameter("uuid", uuid)
          .setParameter("curs", ids)
          .getResultList();

      Map<String, MantissaAmount> out = new HashMap<>();
      for (EconomyBalanceEntity row : rows) {
        if (row.getCurrency() == null) continue;
        out.put(row.getCurrency(), MantissaAmount.of(row.getAmountMantissa(), row.getAmountExp3()));
      }
      return out;
    });
  }

  public CompletableFuture<Void> upsertBulk(UUID uuid, Map<String, MantissaAmount> valuesLower) {
    if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("uuid is null"));
    if (valuesLower == null || valuesLower.isEmpty()) return CompletableFuture.completedFuture(null);

    Map<String, MantissaAmount> cleaned = new HashMap<>();
    for (var e : valuesLower.entrySet()) {
      String cur = normalizeCurrency(e.getKey());
      if (cur.isBlank()) continue;
      MantissaAmount a = e.getValue() == null ? MantissaAmount.zero() : MantissaAmount.normalize(e.getValue());
      cleaned.put(cur, a);
    }
    if (cleaned.isEmpty()) return CompletableFuture.completedFuture(null);

    return dbAsync.executeAsyncInTransaction(em -> {
      for (var e : cleaned.entrySet()) {
        String currency = e.getKey();
        MantissaAmount a = e.getValue();

        List<EconomyBalanceEntity> list = em.createQuery(
                "select b from EconomyBalanceEntity b where b.playerUuid = :uuid and b.currency = :cur",
                EconomyBalanceEntity.class
            )
            .setParameter("uuid", uuid)
            .setParameter("cur", currency)
            .setMaxResults(1)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();

        EconomyBalanceEntity row = list.isEmpty() ? null : list.getFirst();

        if (row == null) {
          em.persist(EconomyBalanceEntity.builder()
              .playerUuid(uuid)
              .currency(currency)
              .amountMantissa(a.mantissa())
              .amountExp3(a.exp3())
              .build());
          continue;
        }

        row.setAmountMantissa(a.mantissa());
        row.setAmountExp3(a.exp3());
        em.merge(row);
      }
    }).exceptionally(ex -> {
      logger.logger().warning("Bulk upsert failed for " + uuid + ": " + ex.getMessage());
      throw new RuntimeException(ex);
    });
  }

  public CompletableFuture<List<TopBalanceRow>> topBalances(String currencyIdLower, int limit) {
    String cur = normalizeCurrency(currencyIdLower);
    if (cur.isBlank()) return CompletableFuture.completedFuture(List.of());
    if (limit <= 0) return CompletableFuture.completedFuture(List.of());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<EconomyBalanceEntity> rows = em.createQuery(
              "select b from EconomyBalanceEntity b " +
                  "where b.currency = :cur " +
                  "order by b.amountExp3 desc, b.amountMantissa desc",
              EconomyBalanceEntity.class
          )
          .setParameter("cur", cur)
          .setMaxResults(limit)
          .getResultList();

      List<TopBalanceRow> out = new ArrayList<>(rows.size());
      for (EconomyBalanceEntity r : rows) {
        if (r.getPlayerUuid() == null) continue;
        out.add(new TopBalanceRow(r.getPlayerUuid(), MantissaAmount.of(r.getAmountMantissa(), r.getAmountExp3())));
      }
      return out;
    });
  }

  public record TopBalanceRow(UUID uuid, MantissaAmount amount) {}

  private static @NotNull String normalizeCurrency(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
  }
}