package io.nexstudios.nexeconomy.domain;

import io.nexstudios.nexeconomy.service.bank.BankBalanceFlushService;
import io.nexstudios.nexeconomy.service.bank.BankLockFlushService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.domain.container.BankContainer;
import io.nexstudios.nexeconomy.domain.container.VaultContainer;
import io.nexstudios.nexeconomy.domain.container.VirtualContainer;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of {@link EcoPlayer} instances.
 * <p>
 * On player join: loads economy state asynchronously and registers an {@link EcoPlayer}.
 * On player quit: flushes dirty balances and evicts the player.
 * <p>
 * Also exposes itself via {@link EcoPlayer#bindRegistry(EcoPlayerRegistry)} so that
 * the static {@link EcoPlayer#of(Player)} factory works without any DI injection at call sites.
 */
@Dependencies({
    EconomyPlayerCacheService.class,
    BankAccountCacheService.class,
    BankLockFlushService.class,
    BankBalanceFlushService.class,
    BankRegistryService.class,
    CurrencyRegistryService.class,
    EconomyFlushService.class
})
public final class EcoPlayerRegistry implements Service {

  private final EconomyPlayerCacheService econCache;
  private final BankAccountCacheService bankCache;
  private final BankLockFlushService bankLockFlush;
  private final BankBalanceFlushService bankBalanceFlush;
  private final BankRegistryService bankRegistry;
  private final CurrencyRegistryService currencies;
  private final EconomyFlushService flush;

  private final ConcurrentHashMap<UUID, EcoPlayer> players = new ConcurrentHashMap<>();

  public EcoPlayerRegistry(ServiceAccessor accessor) {
    this.econCache          = accessor.getService(EconomyPlayerCacheService.class);
    this.bankCache          = accessor.getService(BankAccountCacheService.class);
    this.bankLockFlush      = accessor.getService(BankLockFlushService.class);
    this.bankBalanceFlush   = accessor.getService(BankBalanceFlushService.class);
    this.bankRegistry       = accessor.getService(BankRegistryService.class);
    this.currencies         = accessor.getService(CurrencyRegistryService.class);
    this.flush              = accessor.getService(EconomyFlushService.class);

    // Bind static factory so EcoPlayer.of(player) works without injection
    EcoPlayer.bindRegistry(this);
  }

  // ─── Lifecycle ────────────────────────────────────────────────────────────

  /**
   * Loads economy data for the player and registers an {@link EcoPlayer}.
   * Safe to call multiple times – returns the existing instance if already loaded.
   *
   * @return a future that completes with the registered {@link EcoPlayer}
   */
  public CompletableFuture<EcoPlayer> load(Player player) {
    UUID uuid = player.getUniqueId();

    EcoPlayer existing = players.get(uuid);
    if (existing != null) return CompletableFuture.completedFuture(existing);

    return econCache.loadOrCreateOnline(player).thenApply(econState -> {
      EcoPlayer eco = new EcoPlayer(
          uuid,
          new VaultContainer(econState, currencies, flush),
          new VirtualContainer(econState, currencies, flush),
          new BankContainer(bankCache, bankLockFlush, bankBalanceFlush, bankRegistry, uuid)
      );
      players.put(uuid, eco);
      return eco;
    });
  }

  /**
   * Flushes dirty economy data and removes the player from the registry.
   * Should be called on player quit.
   */
  public void unload(UUID uuid) {
    players.remove(uuid);

    flush.cancelScheduled(uuid);

    // Flush dirty balances regardless of whether EcoPlayer was present
    var econState = econCache.remove(uuid);
    if (econState != null) {
      flush.flushPlayerDirty(econState);
    }
  }

  /**
   * Invalidates the cached {@link EcoPlayer} for the given UUID (triggered by Redis cross-server sync).
   * Removes the stale entry and immediately reloads if the player is still online on this server.
   */
  public void invalidate(UUID uuid) {
    if (uuid == null) return;
    players.remove(uuid);
    // econCache is already cleared by EconomyRedisSyncService before this is called
    Player p = Bukkit.getPlayer(uuid);
    if (p != null && p.isOnline()) {
      load(p); // reload fresh from DB into RAM + re-register EcoPlayer
    }
  }

  // ─── Lookup ───────────────────────────────────────────────────────────────

  /**
   * Returns the registered {@link EcoPlayer} for the given UUID, or {@code null} if not loaded.
   */
  public @Nullable EcoPlayer get(UUID uuid) {
    return uuid == null ? null : players.get(uuid);
  }

  /**
   * Ensures all online players have their currency entries populated.
   * Useful after a plugin reload that adds new currencies.
   */
  public void ensureMissingCurrenciesForAllOnline() {
    econCache.ensureMissingCurrenciesForAllOnline();
  }
}
