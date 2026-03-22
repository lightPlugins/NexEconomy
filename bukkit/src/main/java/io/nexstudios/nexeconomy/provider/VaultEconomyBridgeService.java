package io.nexstudios.nexeconomy.provider;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

@Dependencies({
    LoggerService.class,
    PaperPluginService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class
})
public final class VaultEconomyBridgeService implements Service {

  private final LoggerService logger;
  private final Plugin plugin;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;

  public VaultEconomyBridgeService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);

    registerIfPossible();
  }

  private void registerIfPossible() {

    if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
      logger.logger().info("Vault not installed. Skipping Vault economy registration.");
      return;
    }

    String vaultCurrencyId = currencies.vaultCurrencyId();
    if (vaultCurrencyId == null) {
      logger.logger().warning("No vault currency configured. Skipping Vault economy registration.");
      return;
    }

    VaultEconomyProvider provider = new VaultEconomyProvider(currencies, cache);
    Bukkit.getServicesManager().register(Economy.class, provider, plugin, ServicePriority.Highest);

    logger.logger().info("Registered Vault Economy provider (currency=" + vaultCurrencyId + ", priority=Highest).");
  }
}