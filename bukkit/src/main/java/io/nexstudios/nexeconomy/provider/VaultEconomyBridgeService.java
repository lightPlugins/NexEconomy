package io.nexstudios.nexeconomy.provider;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
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
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;

import java.nio.file.Path;

@Dependencies({
    LoggerService.class,
    PaperPluginService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyFlushService.class,
    EconomyRepository.class,
    FileReaderService.class
})
public final class VaultEconomyBridgeService implements Service {

  private final ServiceAccessor accessor;
  private final LoggerService logger;
  private final Plugin plugin;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;
  private final EconomyRepository repo;
  private final FileConfiguration settings;

  public VaultEconomyBridgeService(ServiceAccessor accessor) {
    this.accessor = accessor;
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.flush = accessor.getService(EconomyFlushService.class);
    this.repo = accessor.getService(EconomyRepository.class);

    FileReaderService fileReaderService = accessor.getService(FileReaderService.class);
    this.settings = fileReaderService.load(Path.of("settings.yml"), "settings.yml", true);

    registerIfPossible();
  }

  private void registerIfPossible() {

    boolean enabled = settings == null || settings.getBoolean("vault-bridge.enabled", true);
    if (!enabled) {
      logger.logger().info("Vault bridge disabled via settings.yml (vault-bridge.enabled=false). Skipping Vault economy registration.");
      return;
    }

    if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
      logger.logger().info("Vault not installed. Skipping Vault economy registration.");
      return;
    }

    String vaultCurrencyId = currencies.vaultCurrencyId();
    if (vaultCurrencyId == null) {
      logger.logger().warning("No vault currency configured. Skipping Vault economy registration.");
      return;
    }

    VaultEconomyProvider provider = new VaultEconomyProvider(currencies, cache, flush, repo);
    Bukkit.getServicesManager().register(Economy.class, provider, plugin, ServicePriority.Highest);

    logger.logger().info("Registered Vault Economy provider (currency=" + vaultCurrencyId + ", priority=Highest).");
  }
}