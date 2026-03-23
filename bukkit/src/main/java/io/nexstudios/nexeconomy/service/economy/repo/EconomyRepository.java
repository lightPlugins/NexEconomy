package io.nexstudios.nexeconomy.service.economy.repo;

import io.nexstudios.nexlogic.bukkit.services.entity.EconomyBalanceEntity;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.nexstudios.databaseservice.bukkit.service.api.DatabaseAsyncService;
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

  /**
   * Runs a minimal DB query to verify the database connection and JPA pipeline.
   */
  public CompletableFuture<Void> ping() {
    return dbAsync.executeAsyncInTransaction(em -> {
      em.createQuery("select count(b) from EconomyBalanceEntity b", Long.class).getSingleResult();
    }).thenApply(ignored -> null);
  }

  public CompletableFuture<Long> countAccountsForCurrency(String currencyIdLower) {
    return countAccountsForCurrency(currencyIdLower, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public CompletableFuture<Long> countAccountsForCurrency(String currencyIdLower, EconomyBalanceEntity.EconomyAccountType accountType) {
    String cur = normalizeCurrency(currencyIdLower);
    if (cur.isBlank()) return CompletableFuture.completedFuture(0L);
    if (accountType == null) return CompletableFuture.completedFuture(0L);

    return dbAsync.executeAsyncInTransaction(em -> {
      Long count = em.createQuery(
              "select count(distinct b.playerUuid) from EconomyBalanceEntity b where b.currency = :cur and b.accountType = :type",
              Long.class
          )
          .setParameter("cur", cur)
          .setParameter("type", accountType)
          .getSingleResult();

      return count == null ? 0L : count;
    });
  }

  public CompletableFuture<Map<String, MantissaAmount>> loadBalances(UUID uuid, Set<String> currencyIdsLower) {
    return loadBalances(uuid, currencyIdsLower, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public CompletableFuture<Map<String, MantissaAmount>> loadBalances(
      UUID uuid,
      Set<String> currencyIdsLower,
      EconomyBalanceEntity.EconomyAccountType accountType
  ) {
    if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("uuid is null"));
    if (currencyIdsLower == null || currencyIdsLower.isEmpty()) return CompletableFuture.completedFuture(Map.of());
    if (accountType == null) return CompletableFuture.completedFuture(Map.of());

    Set<String> ids = new HashSet<>();
    for (String id : currencyIdsLower) {
      String n = normalizeCurrency(id);
      if (!n.isBlank()) ids.add(n);
    }
    if (ids.isEmpty()) return CompletableFuture.completedFuture(Map.of());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<EconomyBalanceEntity> rows = em.createQuery(
              "select b from EconomyBalanceEntity b where b.playerUuid = :uuid and b.currency in :curs and b.accountType = :type",
              EconomyBalanceEntity.class
          )
          .setParameter("uuid", uuid)
          .setParameter("curs", ids)
          .setParameter("type", accountType)
          .getResultList();

      Map<String, MantissaAmount> out = new HashMap<>();
      for (EconomyBalanceEntity row : rows) {
        if (row.getCurrency() == null) continue;

        MantissaAmount parsed = MantissaAmount.parseStorage(row.getAmount(), row.getAmountExp3());
        out.put(row.getCurrency(), parsed);
      }
      return out;
    });
  }

  public CompletableFuture<Void> upsertBulk(UUID uuid, Map<String, MantissaAmount> valuesLower) {
    return upsertBulk(uuid, valuesLower, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public CompletableFuture<Void> upsertBulk(
      UUID uuid,
      Map<String, MantissaAmount> valuesLower,
      EconomyBalanceEntity.EconomyAccountType accountType
  ) {
    if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("uuid is null"));
    if (valuesLower == null || valuesLower.isEmpty()) return CompletableFuture.completedFuture(null);
    if (accountType == null) return CompletableFuture.completedFuture(null);

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

        MantissaAmount.Storage storage = a.toStorage();

        List<EconomyBalanceEntity> list = em.createQuery(
                "select b from EconomyBalanceEntity b where b.playerUuid = :uuid and b.currency = :cur and b.accountType = :type",
                EconomyBalanceEntity.class
            )
            .setParameter("uuid", uuid)
            .setParameter("cur", currency)
            .setParameter("type", accountType)
            .setMaxResults(1)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();

        EconomyBalanceEntity row = list.isEmpty() ? null : list.getFirst();

        if (row == null) {
          em.persist(EconomyBalanceEntity.builder()
              .playerUuid(uuid)
              .currency(currency)
              .amount(storage.mantissaText())
              .amountExp3(storage.exp3())
              .accountType(accountType)
              .build());
          continue;
        }

        row.setAmount(storage.mantissaText());
        row.setAmountExp3(storage.exp3());
        row.setAccountType(accountType);
        em.merge(row);
      }
    }).exceptionally(ex -> {
      logger.logger().warning("Bulk upsert failed for " + uuid + ": " + ex.getMessage());
      throw new RuntimeException(ex);
    });
  }

  public CompletableFuture<List<TopBalanceRow>> topBalances(String currencyIdLower, int limit) {
    return topBalances(currencyIdLower, limit, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public CompletableFuture<List<TopBalanceRow>> topBalances(
      String currencyIdLower,
      int limit,
      EconomyBalanceEntity.EconomyAccountType accountType
  ) {
    String cur = normalizeCurrency(currencyIdLower);
    if (cur.isBlank()) return CompletableFuture.completedFuture(List.of());
    if (limit <= 0) return CompletableFuture.completedFuture(List.of());
    if (accountType == null) return CompletableFuture.completedFuture(List.of());

    final int window = Math.min(10_000, limit * 200);

    return dbAsync.executeAsyncInTransaction(em -> {
      List<EconomyBalanceEntity> rows = em.createQuery(
              "select b from EconomyBalanceEntity b where b.currency = :cur and b.accountType = :type",
              EconomyBalanceEntity.class
          )
          .setParameter("cur", cur)
          .setParameter("type", accountType)
          .setMaxResults(window)
          .getResultList();

      List<TopBalanceRow> out = new ArrayList<>(rows.size());
      for (EconomyBalanceEntity r : rows) {
        if (r.getPlayerUuid() == null) continue;
        MantissaAmount amount = MantissaAmount.parseStorage(r.getAmount(), r.getAmountExp3());
        out.add(new TopBalanceRow(r.getPlayerUuid(), amount));
      }

      out.sort((a, b) -> b.amount().compareTo(a.amount()));
      if (out.size() > limit) {
        return out.subList(0, limit);
      }
      return out;
    });
  }

  public record TopBalanceRow(UUID uuid, MantissaAmount amount) {}

  private static @NotNull String normalizeCurrency(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
  }
}