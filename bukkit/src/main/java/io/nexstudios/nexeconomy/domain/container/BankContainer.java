package io.nexstudios.nexeconomy.domain.container;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankLockFlushService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Synchronous view over all bank accounts belonging to a player.
 * Backed by {@link BankAccountCacheService} – returns null/empty if a specific bank is not yet cached.
 * <p>
 * Lock / unlock operations follow the same cache-first pattern as {@link VaultContainer}:
 * the in-memory lock state is updated immediately; the DB write and Redis cross-server
 * invalidation are delegated to {@link BankLockFlushService} and happen asynchronously.
 */
public final class BankContainer {

  private final BankAccountCacheService cache;
  private final BankLockFlushService lockFlush;
  private final UUID ownerUuid;

  public BankContainer(BankAccountCacheService cache,
                       BankLockFlushService lockFlush,
                       UUID ownerUuid) {
    this.cache = cache;
    this.lockFlush = lockFlush;
    this.ownerUuid = ownerUuid;
  }

  // ─── Read (view / balance / members) ─────────────────────────────────────

  /**
   * Returns the full cached view for a specific bank, or {@code null} if not yet cached.
   */
  public @Nullable BankAccountCacheService.View view(String bankId) {
    if (bankId == null || bankId.isBlank()) return null;
    return cache.get(normalize(bankId), ownerUuid);
  }

  /** Returns the current bank balance, or {@code null} if not cached. */
  public @Nullable MantissaAmount balance(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    return v == null ? null : v.balance();
  }

  /** Returns the current member list, or {@code null} if not cached. */
  public @Nullable List<BankMemberEntity> members(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    return v == null ? null : v.members();
  }

  /**
   * Returns the cached recent transactions for this bank, or an empty list if not yet cached.
   * Transactions are loaded together with balance/members and limited to
   * {@link BankAccountCacheService#TX_CACHE_LIMIT} entries.
   */
  public List<BankTransactionEntity> transactions(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    if (v == null || v.transactions() == null) return List.of();
    return v.transactions();
  }

  /**
   * Returns the cached recent transactions for a bank owned by {@code bankOwnerUuid}.
   * Use this when the player is a <em>member</em> of the bank (not the owner) so the cache
   * is keyed under the bank owner's UUID.
   *
   * @param bankId       the bank identifier
   * @param bankOwnerUuid the UUID of the bank owner (may differ from this container's ownerUuid)
   * @return the cached transaction list, or an empty list if not yet cached
   */
  public List<BankTransactionEntity> transactions(String bankId, UUID bankOwnerUuid) {
    if (bankId == null || bankId.isBlank() || bankOwnerUuid == null) return List.of();
    BankAccountCacheService.View v = cache.get(normalize(bankId), bankOwnerUuid);
    if (v == null || v.transactions() == null) return List.of();
    return v.transactions();
  }

  /**
   * Updates the cached transaction list for the given bank.
   * Use this after a deposit/withdraw to keep the cache in sync without a full reload.
   */
  public void updateTransactions(String bankId, List<BankTransactionEntity> transactions) {
    UUID accountId = accountId(bankId);
    if (accountId == null) return;
    cache.updateTransactions(accountId, transactions);
  }

  /** Returns the current bank level, or 1 (default) if not cached. */
  public int level(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    if (v == null || v.account() == null) return 1;
    int level = v.account().getLevel();
    return level <= 0 ? 1 : level;
  }

  /** Returns the bank account UUID, or {@code null} if not cached. */
  public @Nullable UUID accountId(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    return v == null || v.account() == null ? null : v.account().getId();
  }

  /** Returns {@code true} if the bank data is currently present in cache. */
  public boolean isCached(String bankId) {
    return view(bankId) != null;
  }

  /**
   * Invalidates the cache entry for the given bank account ID.
   * Should be called after any write operation that changes bank state.
   */
  public void invalidate(UUID bankAccountId) {
    cache.invalidate(bankAccountId);
  }

  /**
   * Returns all currently cached bank views for this player (snapshot, may be incomplete
   * if some banks are still loading).
   */
  public List<BankAccountCacheService.View> allCached() {
    return List.copyOf(cache.snapshotByOwner(ownerUuid));
  }

  /**
   * Returns all cached bank views where this player is a non-owner member (snapshot).
   * May be incomplete if some accounts have not been loaded into cache yet.
   */
  public List<BankAccountCacheService.View> memberBankViews() {
    return List.copyOf(cache.snapshotAsMember(ownerUuid));
  }

  // ─── Lock-state reads (from in-memory cache) ─────────────────────────────

  /**
   * Returns the cached unlock state of a specific bank.
   * {@code true} = unlocked, {@code false} = locked, {@code null} = not yet cached.
   */
  public @Nullable Boolean isUnlocked(String bankId) {
    if (bankId == null || bankId.isBlank()) return null;
    return cache.getCachedUnlockState(normalize(bankId), ownerUuid);
  }

  /**
   * Returns the cached player-level lock state.
   * {@code true} = all bank accounts locked, {@code false} = not locked, {@code null} = not yet cached.
   */
  public @Nullable Boolean isPlayerLocked() {
    return cache.getCachedPlayerLocked(ownerUuid);
  }

  // ─── Lock mutations (sync cache-first + async DB + Redis via BankLockFlushService) ──

  /**
   * Locks the given bank for this player.
   * In-memory state is updated immediately; DB + Redis happen asynchronously via {@link BankLockFlushService}.
   *
   * @param bankId    bank identifier
   * @param actorUuid who is performing the lock; falls back to ownerUuid if null
   */
  public void lock(String bankId, @Nullable UUID actorUuid) {
    String id = normalize(bankId);
    UUID actor = actorUuid != null ? actorUuid : ownerUuid;
    UUID bankAccountId = accountId(id);

    cache.setCachedUnlockState(id, ownerUuid, false);
    lockFlush.requestBankLock(id, ownerUuid, actor, bankAccountId);
  }

  /**
   * Unlocks the given bank for this player.
   * In-memory state is updated immediately; DB + Redis happen asynchronously via {@link BankLockFlushService}.
   *
   * @param bankId    bank identifier
   * @param actorUuid who is performing the unlock; falls back to ownerUuid if null
   */
  public void unlock(String bankId, @Nullable UUID actorUuid) {
    String id = normalize(bankId);
    UUID actor = actorUuid != null ? actorUuid : ownerUuid;
    UUID bankAccountId = accountId(id);

    cache.setCachedUnlockState(id, ownerUuid, true);
    lockFlush.requestBankUnlock(id, ownerUuid, actor, bankAccountId);
  }

  /**
   * Locks ALL bank accounts for this player (player-level lock).
   * In-memory state is updated immediately; DB happens asynchronously via {@link BankLockFlushService}.
   *
   * @param actorUuid who is performing the lock; falls back to ownerUuid if null
   * @param reason    optional reason string
   */
  public void lockPlayer(@Nullable UUID actorUuid, @Nullable String reason) {
    UUID actor = actorUuid != null ? actorUuid : ownerUuid;
    cache.setCachedPlayerLocked(ownerUuid, true);
    lockFlush.requestPlayerLock(ownerUuid, actor, reason);
  }

  /**
   * Unlocks ALL bank accounts for this player (player-level unlock).
   * In-memory state is updated immediately; DB happens asynchronously via {@link BankLockFlushService}.
   *
   * @param actorUuid who is performing the unlock; falls back to ownerUuid if null
   */
  public void unlockPlayer(@Nullable UUID actorUuid) {
    UUID actor = actorUuid != null ? actorUuid : ownerUuid;
    cache.setCachedPlayerLocked(ownerUuid, false);
    lockFlush.requestPlayerUnlock(ownerUuid, actor);
  }

  // ─── Internal ─────────────────────────────────────────────────────────────

  private static String normalize(String s) {
    return s.trim().toLowerCase(Locale.ROOT);
  }
}
