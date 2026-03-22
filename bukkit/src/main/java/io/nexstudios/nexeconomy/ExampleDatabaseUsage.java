package io.nexstudios.nexeconomy;

import io.nexstudios.databaseservice.bukkit.service.api.DatabaseAsyncService;
import io.nexstudios.nexlogic.bukkit.services.entity.EconomyBalanceEntity;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    LoggerService.class
})
public final class ExampleDatabaseUsage implements Service {

  private final DatabaseAsyncService dbAsync;
  private final LoggerService logger;

  public ExampleDatabaseUsage(ServiceAccessor serviceAccessor) {
    this.logger = serviceAccessor.getService(LoggerService.class);
    this.dbAsync = NexEconomyPlugin.getNexLogicService().findService(DatabaseAsyncService.class).orElseThrow();
  }

  public CompletableFuture<BigDecimal> setBalance(UUID playerUuid, String currency, BigDecimal newAmount) {
    // some console message
    logger.logger().info("Setting balance for " + playerUuid + " to " + newAmount);
    if (playerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid is null"));
    String cur = normalizeCurrency(currency);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));
    if (newAmount == null) return CompletableFuture.failedFuture(new IllegalArgumentException("newAmount is null"));

    return dbAsync.executeAsyncInTransaction(em -> {
      EconomyBalanceEntity row = find(em, playerUuid, cur, LockModeType.PESSIMISTIC_WRITE);

      if (row == null) {
        em.persist(EconomyBalanceEntity.builder()
            .playerUuid(playerUuid)
            .currency(cur)
            .amount(newAmount)
            .build());
        return newAmount;
      }

      row.setAmount(newAmount);
      em.merge(row);
      return newAmount;
    });
  }

  public CompletableFuture<BigDecimal> addBalance(UUID playerUuid, String currency, BigDecimal delta) {
    if (playerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid is null"));
    String cur = normalizeCurrency(currency);
    if (cur.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("currency is blank"));
    if (delta == null) return CompletableFuture.failedFuture(new IllegalArgumentException("delta is null"));

    return dbAsync.executeAsyncInTransaction(em -> {
      EconomyBalanceEntity row = find(em, playerUuid, cur, LockModeType.PESSIMISTIC_WRITE);

      BigDecimal current = row == null || row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
      BigDecimal newValue = current.add(delta);

      if (row == null) {
        em.persist(EconomyBalanceEntity.builder()
            .playerUuid(playerUuid)
            .currency(cur)
            .amount(newValue)
            .build());
        return newValue;
      }

      row.setAmount(newValue);
      em.merge(row);
      return newValue;
    });
  }

  private static EconomyBalanceEntity find(EntityManager em, UUID playerUuid, String currencyLower, LockModeType lockMode) {
    List<EconomyBalanceEntity> list = em.createQuery(
            "select b from EconomyBalanceEntity b where b.playerUuid = :uuid and b.currency = :cur",
            EconomyBalanceEntity.class
        )
        .setParameter("uuid", playerUuid)
        .setParameter("cur", currencyLower)
        .setMaxResults(1)
        .setLockMode(lockMode)
        .getResultList();

    return list.isEmpty() ? null : list.getFirst();
  }

  private static String normalizeCurrency(String currency) {
    return currency == null ? "" : currency.trim().toLowerCase();
  }
}