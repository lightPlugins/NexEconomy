package io.nexstudios.nexeconomy.service.bank.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexeconomy.provider.bank.BankProviderService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountPresenceService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    BankRegistryService.class,
    BankProviderService.class,
    BankAccountPresenceService.class
})
public final class BankPlayerListener implements ServiceListener {

  private final BankRegistryService bankRegistry;
  private final BankProviderService bankProvider;
  private final BankAccountPresenceService presence;

  public BankPlayerListener(ServiceAccessor accessor) {
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.bankProvider = accessor.getService(BankProviderService.class);
    this.presence = accessor.getService(BankAccountPresenceService.class);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    UUID uuid = p.getUniqueId();

    List<CompletableFuture<?>> initialAccounts = new ArrayList<>();

    for (BankDefinition def : bankRegistry.banks()) {
      if (def == null) continue;
      if (!def.enabled()) continue;
      if (!def.unlockedByDefault()) continue;

      initialAccounts.add(bankProvider.createBank(def.idLower(), uuid));
    }

    if (presence != null) {
      CompletableFuture
          .allOf(initialAccounts.toArray(CompletableFuture[]::new))
          .handle((ignored, error) -> null)
          .thenRun(() -> presence.onPlayerJoin(uuid));
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