package io.nexstudios.nexeconomy.domain.container;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountPresenceService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Synchronous view over all bank accounts belonging to a player.
 * Backed by {@link BankAccountCacheService} – returns null/empty if a specific bank is not yet cached.
 * <p>
 * Banks are loaded into the cache automatically when the owner (or any member) connects,
 * managed by {@link BankAccountPresenceService}.
 */
public final class BankContainer {

  private final BankAccountCacheService cache;
  private final UUID ownerUuid;

  public BankContainer(BankAccountCacheService cache, UUID ownerUuid) {
    this.cache = cache;
    this.ownerUuid = ownerUuid;
  }

  /**
   * Returns the full cached view for a specific bank, or {@code null} if not yet cached.
   * A null result is safe – it simply means the bank data is still being loaded asynchronously.
   */
  public @Nullable BankAccountCacheService.View view(String bankId) {
    if (bankId == null || bankId.isBlank()) return null;
    return cache.get(normalize(bankId), ownerUuid);
  }

  /**
   * Returns the current bank balance, or {@code null} if not cached.
   */
  public @Nullable MantissaAmount balance(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    return v == null ? null : v.balance();
  }

  /**
   * Returns the current member list, or an empty list if not cached.
   */
  public @Nullable List<BankMemberEntity> members(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    return v == null ? null : v.members();
  }

  /**
   * Returns the current bank level, or 1 (default) if not cached.
   */
  public int level(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    if (v == null || v.account() == null) return 1;
    int level = v.account().getLevel();
    return level <= 0 ? 1 : level;
  }

  /**
   * Returns the bank account UUID, or {@code null} if not cached.
   */
  public @Nullable UUID accountId(String bankId) {
    BankAccountCacheService.View v = view(bankId);
    return v == null || v.account() == null ? null : v.account().getId();
  }

  /**
   * Returns true if the bank data is currently present in cache.
   */
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

  private static String normalize(String s) {
    return s.trim().toLowerCase(Locale.ROOT);
  }
}


