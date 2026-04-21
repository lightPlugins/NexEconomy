package io.nexstudios.nexeconomy.service.economy.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexeconomy.domain.EcoPlayerRegistry;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

@Dependencies({
    EcoPlayerRegistry.class
})
public final class EconomyPlayerListener implements ServiceListener {

  private final EcoPlayerRegistry registry;

  public EconomyPlayerListener(ServiceAccessor accessor) {
    this.registry = accessor.getService(EcoPlayerRegistry.class);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    registry.load(e.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    registry.unload(e.getPlayer().getUniqueId());
  }
}