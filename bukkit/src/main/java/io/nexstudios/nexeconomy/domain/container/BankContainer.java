package io.nexstudios.nexeconomy.domain.container;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankBalanceFlushService;
import io.nexstudios.nexeconomy.service.bank.BankLockFlushService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
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
  private final BankBalanceFlushService balanceFlush;
  private final BankRegistryService bankRegistry;
  private final UUID ownerUuid;

  public BankContainer(BankAccountCacheService cache,
                       BankLockFlushService lockFlush,
                       BankBalanceFlushService balanceFlush,
                       BankRegistryService bankRegistry,
                       UUID ownerUuid) {
    this.cache = cache;
    this.lockFlush = lockFlush;
    this.balanceFlush = balanceFlush;
    this.bankRegistry = bankRegistry;
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

  // ─── Balance mutations (sync cache-first + async DB + Redis via BankBalanceFlushService) ──

  /**
   * Sets the balance for the given bank to {@code amount}.
   * The in-memory cache is updated immediately; the DB write happens asynchronously.
   * On DB failure the cache entry is invalidated so the next read fetches the real value.
   *
   * @param bankId bank identifier
   * @param amount new balance; {@code null} is treated as zero
   */
  public void set(String bankId, @Nullable MantissaAmount amount) {
    String id = normalize(bankId);
    UUID accountId = accountId(id);
    if (accountId == null) return;

    MantissaAmount safe = amount != null ? amount : MantissaAmount.zero();
    cache.updateBalance(accountId, safe);
    balanceFlush.requestSet(accountId, safe);
  }

  /**
   * Adds {@code delta} to the balance of the given bank.
   * The in-memory cache is updated immediately; the DB write happens asynchronously.
   * On DB failure the cache entry is invalidated.
   *
   * @param bankId bank identifier
   * @param delta  amount to add (must be positive)
   * @return {@code true} if the bank was cached and the add was applied; {@code false} if not cached
   */
  public boolean add(String bankId, MantissaAmount delta) {
    String id = normalize(bankId);
    UUID accountId = accountId(id);
    if (accountId == null) return false;

    MantissaAmount d = delta != null ? delta : MantissaAmount.zero();
    MantissaAmount current = balance(id);
    MantissaAmount next = current != null
        ? MantissaAmount.of(current.toHuman().add(d.toHuman()), 0)
        : d;
    cache.updateBalance(accountId, next);
    balanceFlush.requestAdd(accountId, d);
    return true;
  }

  /**
   * Removes {@code delta} from the balance of the given bank if sufficient funds exist.
   * The in-memory cache is updated immediately; the DB write happens asynchronously.
   * On DB failure the cache entry is invalidated.
   *
   * @param bankId bank identifier
   * @param delta  amount to remove (must be positive)
   * @return {@code true} if sufficient funds existed and the removal was applied; {@code false} otherwise
   */
  public boolean remove(String bankId, MantissaAmount delta) {
    String id = normalize(bankId);
    UUID accountId = accountId(id);
    if (accountId == null) return false;

    MantissaAmount d = delta != null ? delta : MantissaAmount.zero();
    MantissaAmount current = balance(id);
    if (current == null || current.compareTo(d) < 0) return false;

    MantissaAmount next = MantissaAmount.of(current.toHuman().subtract(d.toHuman()), 0);
    cache.updateBalance(accountId, next);
    balanceFlush.requestRemove(accountId, d);
    return true;
  }

  /**
   * Upgrades the bank level to {@code targetLevel}.
   * The in-memory cache is updated immediately (optimistic); the DB write happens asynchronously.
   * On DB failure the cache entry is invalidated so the next read fetches the real value.
   * <p>
   * Does nothing if the bank is not currently cached or {@code targetLevel} is not greater than
   * the current cached level.
   *
   * @param bankId      bank identifier
   * @param targetLevel the new level to set
   * @return {@code true} if the bank was cached and the level was optimistically applied
   */
  public boolean upgradeLevel(String bankId, int targetLevel) {
    String id = normalize(bankId);
    UUID accountId = accountId(id);
    if (accountId == null) return false;

    int current = level(id);
    if (targetLevel <= current) return false;

    cache.updateAccountLevel(accountId, targetLevel);
    balanceFlush.requestLevelSet(accountId, targetLevel);
    return true;
  }

  // ─── Lock-state reads (from in-memory cache)

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

  /**
   * Records a transaction for the given bank immediately in the cache and asynchronously in the DB.
   * Use this after any sync balance change (deposit/withdraw) to keep the Transactions menu current.
   *
   * @param bankId      bank identifier
   * @param type        transaction type
   * @param actorUuid   who performed the action
   * @param amount      amount involved
   */
  public void recordTransaction(String bankId, BankTransactionEntity.Type type,
                                 UUID actorUuid, MantissaAmount amount) {
    UUID accountId = accountId(normalize(bankId));
    if (accountId == null) return;
    balanceFlush.requestAppendTransaction(accountId, type, actorUuid, actorUuid, amount, null);
  }

  // ─── Bank definition helpers ──────────────────────────────────────────────

  /**
   * Returns the {@link BankDefinition} for the given bank ID, or {@code null} if not configured.
   *
   * @param bankId bank identifier
   */
  public @Nullable BankDefinition definition(String bankId) {
    return bankRegistry.bank(normalize(bankId)).orElse(null);
  }

  /**
   * Returns the maximum level defined for the given bank, or 1 if no levels are configured.
   *
   * @param bankId bank identifier
   */
  public int maxLevel(String bankId) {
    BankDefinition def = bankRegistry.bank(normalize(bankId)).orElse(null);
    if (def == null || def.levels() == null || def.levels().isEmpty()) return 1;
    return def.levels().stream().mapToInt(BankDefinition.LevelDefinition::level).max().orElse(1);
  }

  /**
   * Returns the upgrade cost for the given bank and target level, read directly from the bank
   * definition. Returns {@link MantissaAmount#zero()} if the level or bank is not configured.
   *
   * @param bankId      bank identifier
   * @param targetLevel level whose cost should be returned
   */
  public MantissaAmount upgradeCost(String bankId, int targetLevel) {
    BankDefinition def = bankRegistry.bank(normalize(bankId)).orElse(null);
    if (def == null || def.levels() == null) return MantissaAmount.zero();
    for (BankDefinition.LevelDefinition lvl : def.levels()) {
      if (lvl.level() == targetLevel) {
        String raw = lvl.upgradeCostRaw();
        MantissaAmount amount = AmountNotation.parseVirtualMantissaAmount(raw);
        if (amount != null) return amount;
        BigDecimal vault = AmountNotation.parseVaultHuman(raw);
        if (vault != null) return MantissaAmount.of(vault, 0);
        return MantissaAmount.zero();
      }
    }
    return MantissaAmount.zero();
  }

  /**
   * Returns the currency ID used for upgrade costs for this bank, or an empty string if not found.
   *
   * @param bankId bank identifier
   */
  public String upgradeCurrencyId(String bankId) {
    BankDefinition def = bankRegistry.bank(normalize(bankId)).orElse(null);
    return def != null && def.currencyIdLower() != null ? def.currencyIdLower() : "";
  }

  // ─── Internal ─────────────────────────────────────────────────────────────

  private static String normalize(String s) {
    return s.trim().toLowerCase(Locale.ROOT);
  }
}
