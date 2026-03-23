package io.nexstudios.nexeconomy.service.economy.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

@Dependencies({
    EconomyPlayerCacheService.class,
    EconomyFlushService.class
})
public final class EconomyPlayerListener implements ServiceListener {

  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;

  public EconomyPlayerListener(ServiceAccessor accessor) {
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.flush = accessor.getService(EconomyFlushService.class);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    cache.loadOrCreateOnline(e.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    var uuid = e.getPlayer().getUniqueId();

    flush.cancelScheduled(uuid);

    var econ = cache.remove(uuid);
    if (econ != null) {
      flush.flushPlayerDirty(econ);
    }
  }
}