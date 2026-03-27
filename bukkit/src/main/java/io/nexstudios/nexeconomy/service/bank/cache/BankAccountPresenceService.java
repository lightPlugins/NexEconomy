package io.nexstudios.nexeconomy.service.bank.cache;

import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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

    repo.findBankAccountsForMember(playerUuid).thenAccept(refs -> {
      if (refs == null || refs.isEmpty()) {
        accountsByPlayer.put(playerUuid, Set.of());
        return;
      }

      HashSet<UUID> accountIds = new HashSet<>(refs.size());

      for (BankRepositoryService.BankAccountRef ref : refs) {
        if (ref == null || ref.bankAccountId() == null) continue;

        accountIds.add(ref.bankAccountId());

        AtomicInteger counter = onlineRefsByAccount.computeIfAbsent(ref.bankAccountId(), ignored -> new AtomicInteger(0));
        int prev = counter.getAndIncrement();
        if (prev == 0) {
          // first online member => keep it cached
          if (cache != null && ref.bankIdLower() != null && ref.ownerUuid() != null) {
            cache.loadOrCreate(ref.bankIdLower(), ref.ownerUuid());
          }
        }
      }

      accountsByPlayer.put(playerUuid, Set.copyOf(accountIds));
    }).exceptionally(ex -> {
      accountsByPlayer.put(playerUuid, Set.of());
      return null;
    });
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
    if (counter == null) return;

    int next = counter.decrementAndGet();
    if (next <= 0) {
      onlineRefsByAccount.remove(bankAccountId, counter);
      if (cache != null) {
        cache.invalidate(bankAccountId);
      }
    }
  }
}