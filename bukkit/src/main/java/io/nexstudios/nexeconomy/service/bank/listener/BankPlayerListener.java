package io.nexstudios.nexeconomy.service.bank.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

@Dependencies({
    BankRegistryService.class,
    BankService.class
})
public final class BankPlayerListener implements ServiceListener {

  private final BankRegistryService bankRegistry;
  private final BankService bankService;

  public BankPlayerListener(ServiceAccessor accessor) {
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.bankService = accessor.getService(BankService.class);
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
  }
}