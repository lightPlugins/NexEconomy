package io.nexstudios.nexeconomy.service.bank.cache;

import io.nexstudios.nexeconomy.service.bank.menu.bank.BankDetailMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankLevelMenu;
import io.nexstudios.nexeconomy.service.bank.menu.bank.BankMemberMenu;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Dependencies({
    BankRepositoryService.class,
    BankAccountCacheService.class
})
public final class BankAccountPresenceService implements Service {

  private final BankRepositoryService repo;
  private final BankAccountCacheService cache;

  private final ConcurrentHashMap<UUID, Set<UUID>> accountsByPlayer = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, AtomicInteger> onlineRefsByAccount = new ConcurrentHashMap<>();

  public BankAccountPresenceService(ServiceAccessor accessor) {
    this.repo = accessor.getService(BankRepositoryService.class);
    this.cache = accessor.getService(BankAccountCacheService.class);
  }

  public void onPlayerJoin(UUID playerUuid) {
    if (playerUuid == null) return;

    repo.findBankAccountsForMember(playerUuid).thenCombine(repo.findBankAccountsOwnedBy(playerUuid), (memberRefs, ownedRefs) -> {
      HashSet<BankRepositoryService.BankAccountRef> allRefs = new HashSet<>();
      if (memberRefs != null) allRefs.addAll(memberRefs);
      if (ownedRefs != null) allRefs.addAll(ownedRefs);
      return allRefs;
    }).thenAccept(refs -> {
      if (refs == null || refs.isEmpty()) {
        accountsByPlayer.remove(playerUuid);
        return;
      }

      HashSet<UUID> accountIds = new HashSet<>(refs.size());

      for (BankRepositoryService.BankAccountRef ref : refs) {
        if (ref == null || ref.bankAccountId() == null) continue;

        accountIds.add(ref.bankAccountId());

        AtomicInteger counter = onlineRefsByAccount.computeIfAbsent(ref.bankAccountId(), ignored -> new AtomicInteger(0));
        int prev = counter.getAndIncrement();
        if (prev == 0) {
          if (cache != null && ref.bankIdLower() != null && ref.ownerUuid() != null) {
            cache.loadOrCreate(ref.bankIdLower(), ref.ownerUuid());
          }
        }
      }

      accountsByPlayer.put(playerUuid, Set.copyOf(accountIds));
    }).exceptionally(ex -> {
      accountsByPlayer.remove(playerUuid);
      return null;
    });
  }

  /**
   * Returns the number of "other banks" the player is a member of (ownerUuid != playerUuid),
   * but only if the player is currently tracked in the presence cache AND we can resolve
   * ownerUuid for all referenced accounts from the local BankAccountCacheService.
   *
   * If not tracked or not fully resolvable, returns OptionalInt.empty() so callers can fallback to DB.
   */
  public OptionalInt countOtherBankMembershipsIfTracked(UUID playerUuid) {
    if (playerUuid == null) return OptionalInt.empty();

    Player p = Bukkit.getPlayer(playerUuid);
    if (p == null || !p.isOnline()) return OptionalInt.empty();

    Set<UUID> accountIds = accountsByPlayer.get(playerUuid);
    if (accountIds == null || accountIds.isEmpty()) return OptionalInt.empty();

    int count = 0;

    for (UUID bankAccountId : accountIds) {
      if (bankAccountId == null) continue;

      BankAccountCacheService.View view = cache == null ? null : cache.get(bankAccountId);
      if (view == null || view.account() == null || view.account().getOwnerUuid() == null) {
        return OptionalInt.empty();
      }

      UUID owner = view.account().getOwnerUuid();
      if (!playerUuid.equals(owner)) {
        count++;
      }
    }

    return OptionalInt.of(count);
  }

  public void onPlayerQuit(UUID playerUuid) {
    if (playerUuid == null) return;

    Set<UUID> accounts = accountsByPlayer.remove(playerUuid);
    if (accounts == null || accounts.isEmpty()) return;

    for (UUID bankAccountId : accounts) {
      if (bankAccountId == null) continue;

      AtomicInteger counter = onlineRefsByAccount.get(bankAccountId);
      if (counter == null) continue;

      int next = counter.decrementAndGet();
      if (next <= 0) {
        onlineRefsByAccount.remove(bankAccountId, counter);
        if (cache != null) {
          cache.invalidate(bankAccountId);
        }
      }
    }
  }

  /**
   * Called when a member got added to an account (e.g. invite accepted).
   * Keeps the "player -> accounts" index consistent and caches the account if needed.
   */
  public void onMemberAdded(UUID bankAccountId, String bankIdLower, UUID ownerUuid, UUID memberUuid) {
    if (bankAccountId == null || memberUuid == null) return;

    Player p = Bukkit.getPlayer(memberUuid);
    if (p == null || !p.isOnline()) return;

    accountsByPlayer.compute(memberUuid, (k, existing) -> {
      Set<UUID> next = existing == null ? new HashSet<>() : new HashSet<>(existing);
      next.add(bankAccountId);
      return Set.copyOf(next);
    });

    AtomicInteger counter = onlineRefsByAccount.computeIfAbsent(bankAccountId, ignored -> new AtomicInteger(0));
    int prev = counter.getAndIncrement();
    if (prev == 0) {
      if (cache != null && bankIdLower != null && ownerUuid != null) {
        cache.loadOrCreate(bankIdLower, ownerUuid);
      }
    }
  }

  /**
   * Called when a member got removed from an account (leave/kick).
   */
  public void onMemberRemoved(UUID bankAccountId, UUID memberUuid) {
    if (bankAccountId == null || memberUuid == null) return;

    Player p = Bukkit.getPlayer(memberUuid);
    if (p == null || !p.isOnline()) return;

    accountsByPlayer.computeIfPresent(memberUuid, (k, existing) -> {
      if (existing.isEmpty()) return Set.of();
      Set<UUID> next = new HashSet<>(existing);
      next.remove(bankAccountId);
      return Set.copyOf(next);
    });

    AtomicInteger counter = onlineRefsByAccount.get(bankAccountId);
    if (counter != null) {
      int next = counter.decrementAndGet();
      if (next <= 0) {
        onlineRefsByAccount.remove(bankAccountId, counter);
      }
    }

    if (cache != null) {
      cache.invalidate(bankAccountId);
    }

    BankDetailMenu.refreshIfOpen(memberUuid);
    BankLevelMenu.refreshIfOpen(memberUuid);
    BankMemberMenu.refreshIfOpen(memberUuid);
  }

  public void onBankDeleted(UUID bankAccountId) {
    if (bankAccountId == null) return;

    onlineRefsByAccount.remove(bankAccountId);

    for (Map.Entry<UUID, Set<UUID>> entry : accountsByPlayer.entrySet()) {
      if (entry == null || entry.getKey() == null) continue;

      accountsByPlayer.computeIfPresent(entry.getKey(), (playerUuid, existing) -> {
        Set<UUID> next = new HashSet<>(existing);
        next.remove(bankAccountId);
        return Set.copyOf(next);
      });
    }
  }

  public Optional<Set<UUID>> bankAccountIdsIfTracked(UUID playerUuid) {
    if (playerUuid == null) return Optional.empty();

    Player p = Bukkit.getPlayer(playerUuid);
    if (p == null || !p.isOnline()) return Optional.empty();

    Set<UUID> ids = accountsByPlayer.get(playerUuid);
    if (ids == null || ids.isEmpty()) return Optional.empty();

    return Optional.of(Set.copyOf(ids));
  }
}