package io.nexstudios.nexeconomy.service.bank;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Centralises all asynchronous bank-balance DB writes and Redis cross-server invalidation.
 * <p>
 * The cache is mutated <em>before</em> calling any method here (optimistic / cache-first).
 * On DB failure the cache entry is invalidated so the next read fetches the real value from DB.
 * <p>
 * Mirrors the role of {@link BankLockFlushService} for balance state, keeping
 * {@link io.nexstudios.nexeconomy.domain.container.BankContainer} free of any
 * {@code CompletableFuture} or repository references.
 */
@Dependencies({
    LoggerService.class,
    BankRepositoryService.class,
    BankAccountCacheService.class,
    BankRedisSyncService.class
})
public final class BankBalanceFlushService implements Service {

  private final LoggerService logger;
  private final BankRepositoryService repo;
  private final BankAccountCacheService cache;
  private final BankRedisSyncService redisSync;

  public BankBalanceFlushService(ServiceAccessor accessor) {
    this.logger    = accessor.getService(LoggerService.class);
    this.repo      = accessor.getService(BankRepositoryService.class);
    this.cache     = accessor.getService(BankAccountCacheService.class);
    this.redisSync = accessor.getService(BankRedisSyncService.class);
  }

  // ─── Balance mutations ────────────────────────────────────────────────────

  /**
   * Persists a balance set to the DB asynchronously.
   * On failure the cache entry is invalidated so the next read fetches real data from DB.
   *
   * @param bankAccountId the account whose balance is being set
   * @param newBalance    the new balance value
   */
  public void requestSet(UUID bankAccountId, MantissaAmount newBalance) {
    if (bankAccountId == null) return;
    repo.setBalance(bankAccountId, newBalance).whenComplete((result, ex) -> {
      if (ex != null) {
        logger.logger().warning("BankBalanceFlushService: setBalance failed for account="
            + bankAccountId + ": " + ex.getMessage());
        invalidateAndSync(bankAccountId);
      }
    });
  }

  /**
   * Persists a positive balance delta (deposit) to the DB asynchronously.
   * On failure the cache entry is invalidated so the next read fetches real data from DB.
   *
   * @param bankAccountId the account to credit
   * @param delta         amount to add (must be positive)
   */
  public void requestAdd(UUID bankAccountId, MantissaAmount delta) {
    if (bankAccountId == null) return;
    repo.applyBalanceDelta(bankAccountId, delta).whenComplete((result, ex) -> {
      if (ex != null) {
        logger.logger().warning("BankBalanceFlushService: add failed for account="
            + bankAccountId + ": " + ex.getMessage());
        invalidateAndSync(bankAccountId);
      }
    });
  }

  /**
   * Persists a negative balance delta (withdrawal) to the DB asynchronously.
   * On failure the cache entry is invalidated so the next read fetches real data from DB.
   *
   * @param bankAccountId the account to debit
   * @param delta         amount to remove (must be positive; negated internally)
   */
  public void requestRemove(UUID bankAccountId, MantissaAmount delta) {
    if (bankAccountId == null) return;
    MantissaAmount negated = delta != null
        ? MantissaAmount.of(delta.toHuman().negate(), 0)
        : MantissaAmount.zero();
    repo.applyBalanceDelta(bankAccountId, negated).whenComplete((result, ex) -> {
      if (ex != null) {
        logger.logger().warning("BankBalanceFlushService: remove failed for account="
            + bankAccountId + ": " + ex.getMessage());
        invalidateAndSync(bankAccountId);
      }
    });
  }

  /**
   * Persists a bank level update to the DB asynchronously.
   * On failure the cache entry is invalidated.
   *
   * @param bankAccountId the account whose level is being updated
   * @param newLevel      the new level value
   */
  public void requestLevelSet(UUID bankAccountId, int newLevel) {
    if (bankAccountId == null) return;
    repo.updateBankLevel(bankAccountId, newLevel).whenComplete((success, ex) -> {
      if (ex != null || !Boolean.TRUE.equals(success)) {
        logger.logger().warning("BankBalanceFlushService: updateBankLevel failed for account="
            + bankAccountId + (ex != null ? ": " + ex.getMessage() : " (returned false)"));
        invalidateAndSync(bankAccountId);
      }
    });
  }

  // ─── Transaction recording ────────────────────────────────────────────────

  /**
   * Prepends a transaction to the in-memory cache immediately, then persists it to the DB
   * asynchronously (fire-and-forget). Use this after any sync balance change so the
   * Transactions menu reflects the operation without a reload.
   */
  public void requestAppendTransaction(UUID bankAccountId,
                                        BankTransactionEntity.Type type,
                                        UUID actorUuid,
                                        @Nullable UUID targetUuid,
                                        MantissaAmount amount,
                                        @Nullable String meta) {
    if (bankAccountId == null) return;
    UUID safeTarget = targetUuid != null ? targetUuid : actorUuid;

    // Immediate cache update so the Transactions menu refreshes instantly
    BankTransactionEntity tx = new BankTransactionEntity();
    tx.setBankAccountId(bankAccountId);
    tx.setType(type);
    tx.setActorUuid(actorUuid);
    tx.setTargetUuid(safeTarget);
    MantissaAmount.Storage amountStorage = amount != null ? amount.toStorage() : MantissaAmount.zero().toStorage();
    tx.setAmountMantissa(amountStorage.mantissaText());
    tx.setAmountExp3(amountStorage.exp3());
    tx.setMeta(meta);
    tx.setCreatedAt(Instant.now());
    cache.prependTransaction(bankAccountId, tx);

    repo.appendTransaction(bankAccountId, type, actorUuid, safeTarget, amount, meta)
        .whenComplete((v, ex) -> {
          if (ex != null) {
            logger.logger().warning("BankBalanceFlushService: appendTransaction failed for account="
                + bankAccountId + ": " + ex.getMessage());
          }
        });
  }

  // ─── Internal ─────────────────────────────────────────────────────────────

  private void invalidateAndSync(UUID bankAccountId) {
    cache.invalidate(bankAccountId);
    redisSync.publishInvalidateAccount(bankAccountId);
  }
}

