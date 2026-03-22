package io.nexstudios.nexeconomy.service.economy.listener;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

@Dependencies({
    EconomyPlayerCacheService.class,
    EconomyFlushService.class,
    PaperPluginService.class
})
public final class EconomyPlayerListener implements Service, Listener {

  private final Plugin plugin;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;

  public EconomyPlayerListener(ServiceAccessor accessor) {
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.flush = accessor.getService(EconomyFlushService.class);

    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    cache.loadOrCreateOnline(e.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    var econ = cache.remove(e.getPlayer().getUniqueId());
    if (econ != null) {
      flush.flushPlayerDirty(econ);
    }
  }
}