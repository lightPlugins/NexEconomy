package io.nexstudios.nexeconomy.service.bank.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountPresenceService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

@Dependencies({
    BankRegistryService.class,
    BankService.class,
    BankAccountPresenceService.class
})
public final class BankPlayerListener implements ServiceListener {

  private final BankRegistryService bankRegistry;
  private final BankService bankService;
  private final BankAccountPresenceService presence;

  public BankPlayerListener(ServiceAccessor accessor) {
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.bankService = accessor.getService(BankService.class);
    this.presence = accessor.getService(BankAccountPresenceService.class);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    UUID uuid = p.getUniqueId();

    for (BankDefinition def : bankRegistry.banks()) {
      if (def == null) continue;
      if (!def.enabled()) continue;
      if (!def.unlockedByDefault()) continue;

      bankService.getOrCreateAccount(def.idLower(), uuid);
    }

    if (presence != null) {
      presence.onPlayerJoin(uuid);
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    UUID uuid = e.getPlayer().getUniqueId();
    if (presence != null) {
      presence.onPlayerQuit(uuid);
    }
  }
}