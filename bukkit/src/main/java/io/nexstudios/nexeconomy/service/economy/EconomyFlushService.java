package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexlogic.bukkit.services.entity.EconomyBalanceEntity;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Dependencies({
    LoggerService.class,
    EconomyRepository.class,
    EconomyPlayerCacheService.class,
    PaperPluginService.class
})
public final class EconomyFlushService implements Service {

  private static final long DEBOUNCE_TICKS = 10L;

  private record FlushKey(UUID uuid, EconomyBalanceEntity.EconomyAccountType type) {}

  private final LoggerService logger;
  private final Plugin plugin;
  private final EconomyRepository repo;
  private final EconomyPlayerCacheService cache;
  private final EconomyRedisSyncService redisSync;

  private final ConcurrentHashMap<FlushKey, BukkitTask> scheduledFlushes = new ConcurrentHashMap<>();

  private volatile BukkitTask periodicTask;
  private volatile boolean started;

  public EconomyFlushService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.repo = accessor.getService(EconomyRepository.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.redisSync = accessor.getService(EconomyRedisSyncService.class);

    this.periodicTask = null;
    this.started = false;
  }

  /**
   * Must be called when the plugin is enabled (start phase).
   */
  public void start() {
    if (started) return;
    if (!plugin.isEnabled()) {
      logger.logger().warning("EconomyFlushService.start() called while plugin is disabled. Skipping scheduler start.");
      return;
    }

    this.periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
        plugin, this::flushAllDirty, 20L * 60L, 20L * 60L
    );
    this.started = true;
  }

  public void stop() {
    if (periodicTask != null) {
      periodicTask.cancel();
      periodicTask = null;
    }

    for (BukkitTask t : scheduledFlushes.values()) {
      try {
        t.cancel();
      } catch (Exception ignored) {
        // no-op
      }
    }
    scheduledFlushes.clear();

    started = false;
  }

  public void cancelScheduled(UUID uuid) {
    cancelScheduled(uuid, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public void cancelScheduled(UUID uuid, EconomyBalanceEntity.EconomyAccountType type) {
    if (uuid == null) return;
    FlushKey key = new FlushKey(uuid, type);

    BukkitTask t = scheduledFlushes.remove(key);
    if (t != null) {
      try {
        t.cancel();
      } catch (Exception ignored) {
        // no-op
      }
    }
  }

  public void requestFlush(EconomyPlayer econ) {
    requestFlushTyped(econ, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public void requestFlushTowny(EconomyPlayer econ) {
    requestFlushTyped(econ, EconomyBalanceEntity.EconomyAccountType.TOWNY);
  }

  private void requestFlushTyped(EconomyPlayer econ, EconomyBalanceEntity.EconomyAccountType type) {
    if (econ == null) return;

    if (!started || !plugin.isEnabled()) {
      // In onLoad we are allowed to construct services, but must not schedule tasks yet.
      return;
    }

    UUID uuid = econ.uuid();
    if (uuid == null) return;

    FlushKey key = new FlushKey(uuid, type);
    if (scheduledFlushes.containsKey(key)) return;

    BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
      scheduledFlushes.remove(key);

      flushAccountDirty(econ, type).thenRun(() -> {
        if (hasAnyDirtyEntry(econ)) {
          requestFlushTyped(econ, type);
        }
      });
    }, DEBOUNCE_TICKS);

    BukkitTask prev = scheduledFlushes.putIfAbsent(key, task);
    if (prev != null) {
      task.cancel();
    }
  }

  private static boolean hasAnyDirtyEntry(EconomyPlayer econ) {
    if (econ == null) return false;
    for (var e : econ.allEntriesView().values()) {
      if (e != null && e.dirty()) return true;
    }
    return false;
  }

  public void flushAllDirty() {
    for (EconomyPlayer p : cache.allOnlineCached()) {
      flushAccountDirty(p, EconomyBalanceEntity.EconomyAccountType.PLAYER);
    }
  }

  public CompletableFuture<Void> flushAllDirtyAndWait() {
    var futures = cache.allOnlineCached().stream()
        .map(p -> flushAccountDirty(p, EconomyBalanceEntity.EconomyAccountType.PLAYER))
        .toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures);
  }

  public CompletableFuture<Void> flushPlayerDirty(EconomyPlayer econ) {
    return flushAccountDirty(econ, EconomyBalanceEntity.EconomyAccountType.PLAYER);
  }

  public CompletableFuture<Void> flushTownyDirty(EconomyPlayer econ) {
    return flushAccountDirty(econ, EconomyBalanceEntity.EconomyAccountType.TOWNY);
  }

  private CompletableFuture<Void> flushAccountDirty(EconomyPlayer econ, EconomyBalanceEntity.EconomyAccountType type) {
    if (econ == null) return CompletableFuture.completedFuture(null);

    UUID uuid = econ.uuid();
    Map<String, MantissaAmount> toSave = new HashMap<>();
    Map<String, Long> versions = new HashMap<>();

    for (var e : econ.allEntriesView().entrySet()) {
      String currency = e.getKey();
      EconomyPlayer.BalanceEntry entry = e.getValue();
      if (entry == null || !entry.dirty()) continue;

      toSave.put(currency, entry.amount());
      versions.put(currency, entry.version());
    }

    if (toSave.isEmpty()) return CompletableFuture.completedFuture(null);

    return repo.upsertBulk(uuid, toSave, type).thenRun(() -> {
      for (var e : versions.entrySet()) {
        EconomyPlayer.BalanceEntry entry = econ.entry(e.getKey());
        if (entry == null) continue;
        entry.clearDirtyIfVersionMatches(e.getValue());
      }

      if (redisSync != null) {
        redisSync.publishInvalidateAccount(uuid, type);
      }
    }).exceptionally(ex -> {
      logger.logger().warning("Failed to flush account " + uuid + " (type=" + type + "): " + ex.getMessage());
      return null;
    });
  }
}