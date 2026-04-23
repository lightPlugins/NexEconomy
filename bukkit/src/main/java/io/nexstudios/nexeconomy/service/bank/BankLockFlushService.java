package io.nexstudios.nexeconomy.service.bank;

import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Centralises all asynchronous bank-lock DB writes and Redis cross-server invalidation.
 * <p>
 * The cache is mutated <em>before</em> calling any method here (optimistic / cache-first).
 * On DB failure the calling site should roll back the cache entry.
 * <p>
 * Mirrors the role of {@link io.nexstudios.nexeconomy.service.economy.EconomyFlushService}
 * for lock state, keeping {@link io.nexstudios.nexeconomy.domain.container.BankContainer}
 * free of any {@code CompletableFuture} or repository references.
 */
@Dependencies({
    LoggerService.class,
    BankRepositoryService.class,
    BankAccountCacheService.class,
    BankRedisSyncService.class
})
public final class BankLockFlushService implements Service {

  private final LoggerService logger;
  private final BankRepositoryService repo;
  private final BankAccountCacheService cache;
  private final BankRedisSyncService redisSync;

  public BankLockFlushService(ServiceAccessor accessor) {
    this.logger    = accessor.getService(LoggerService.class);
    this.repo      = accessor.getService(BankRepositoryService.class);
    this.cache     = accessor.getService(BankAccountCacheService.class);
    this.redisSync = accessor.getService(BankRedisSyncService.class);
  }

  // ─── Bank-level lock ─────────────────────────────────────────────────────

  /**
   * Persists a bank lock to the DB asynchronously, then invalidates the cache + Redis.
   * On failure, the in-memory lock state is cleared (rolled back to "unknown").
   *
   * @param bankIdLower   normalised bank identifier
   * @param ownerUuid     bank owner
   * @param actorUuid     who performed the lock
   * @param bankAccountId cached account UUID for cache / Redis invalidation (may be null)
   */
  public void requestBankLock(String bankIdLower, UUID ownerUuid, UUID actorUuid,
                               @Nullable UUID bankAccountId) {
    repo.lock(bankIdLower, ownerUuid, actorUuid).whenComplete((ok, ex) -> {
      if (ex != null) {
        logger.logger().warning("BankLockFlushService: lock failed for bank=" + bankIdLower
            + " owner=" + ownerUuid + ": " + ex.getMessage());
        cache.clearCachedUnlockState(bankIdLower, ownerUuid);
        return;
      }
      invalidateAndSync(bankIdLower, ownerUuid, bankAccountId);
    });
  }

  /**
   * Persists a bank unlock to the DB asynchronously, then invalidates the cache + Redis.
   * On failure, the in-memory lock state is cleared (rolled back to "unknown").
   */
  public void requestBankUnlock(String bankIdLower, UUID ownerUuid, UUID actorUuid,
                                 @Nullable UUID bankAccountId) {
    repo.unlock(bankIdLower, ownerUuid, actorUuid).whenComplete((ok, ex) -> {
      if (ex != null) {
        logger.logger().warning("BankLockFlushService: unlock failed for bank=" + bankIdLower
            + " owner=" + ownerUuid + ": " + ex.getMessage());
        cache.clearCachedUnlockState(bankIdLower, ownerUuid);
        return;
      }
      invalidateAndSync(bankIdLower, ownerUuid, bankAccountId);
    });
  }

  // ─── Player-level lock ────────────────────────────────────────────────────

  /**
   * Persists a player-level lock (all bank accounts) to the DB asynchronously.
   * On failure, the in-memory player lock state is cleared (rolled back to "unknown").
   */
  public void requestPlayerLock(UUID playerUuid, UUID actorUuid, @Nullable String reason) {
    String safeReason = reason != null ? reason : "";
    repo.lockPlayer(playerUuid, actorUuid, safeReason).whenComplete((ok, ex) -> {
      if (ex != null) {
        logger.logger().warning("BankLockFlushService: lockPlayer failed for player=" + playerUuid
            + ": " + ex.getMessage());
        cache.clearCachedPlayerLocked(playerUuid);
      }
    });
  }

  /**
   * Persists a player-level unlock to the DB asynchronously.
   * On failure, the in-memory player lock state is cleared (rolled back to "unknown").
   */
  public void requestPlayerUnlock(UUID playerUuid, UUID actorUuid) {
    repo.unlockPlayer(playerUuid, actorUuid).whenComplete((ok, ex) -> {
      if (ex != null) {
        logger.logger().warning("BankLockFlushService: unlockPlayer failed for player=" + playerUuid
            + ": " + ex.getMessage());
        cache.clearCachedPlayerLocked(playerUuid);
      }
    });
  }

  // ─── Internal ─────────────────────────────────────────────────────────────

  private void invalidateAndSync(String bankIdLower, UUID ownerUuid, @Nullable UUID bankAccountId) {
    if (bankAccountId != null) {
      cache.invalidate(bankAccountId);
      redisSync.publishInvalidateAccount(bankAccountId);
    } else {
      cache.invalidate(bankIdLower, ownerUuid);
    }
  }
}

