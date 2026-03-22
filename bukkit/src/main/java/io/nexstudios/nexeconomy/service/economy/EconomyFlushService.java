package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Dependencies({
    LoggerService.class,
    EconomyRepository.class,
    EconomyPlayerCacheService.class,
    PaperPluginService.class
})
public final class EconomyFlushService implements Service {

  private final LoggerService logger;
  private final Plugin plugin;
  private final EconomyRepository repo;
  private final EconomyPlayerCacheService cache;

  public EconomyFlushService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.repo = accessor.getService(EconomyRepository.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);

    // Periodic flush (async)
    Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAllDirty, 20L * 60L, 20L * 60L);
  }

  public void flushAllDirty() {
    for (EconomyPlayer p : cache.allOnlineCached()) {
      flushPlayerDirty(p);
    }
  }

  public void flushPlayerDirty(EconomyPlayer econ) {
    if (econ == null) return;

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

    if (toSave.isEmpty()) return;

    repo.upsertBulk(uuid, toSave).thenRun(() -> {
      // Clear dirty flags only if entry version didn't change while saving.
      for (var e : versions.entrySet()) {
        EconomyPlayer.BalanceEntry entry = econ.entry(e.getKey());
        if (entry == null) continue;
        entry.clearDirtyIfVersionMatches(e.getValue());
      }
    }).exceptionally(ex -> {
      logger.logger().warning("Failed to flush player " + uuid + ": " + ex.getMessage());
      return null;
    });
  }
}