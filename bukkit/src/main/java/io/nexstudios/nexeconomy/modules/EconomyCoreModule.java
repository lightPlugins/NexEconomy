package io.nexstudios.nexeconomy.modules;

import io.nexstudios.nexeconomy.provider.VaultEconomyBridgeService;
import io.nexstudios.nexeconomy.service.economy.*;
import io.nexstudios.nexeconomy.service.economy.listener.EconomyPlayerListener;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.migration.MigrationService;
import io.nexstudios.nexeconomy.service.migration.impl.VaultEconomyMigrationImporter;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.nexstudios.serviceregistry.di.ServiceModule;
import org.jetbrains.annotations.NotNull;

public final class EconomyCoreModule implements ServiceModule {

  @Override
  public void install(@NotNull ServiceAccessor services) {

    services.register(CurrencyRegistryService.class, CurrencyRegistryService.class);
    services.register(EconomyRepository.class, EconomyRepository.class);
    services.register(EconomyPlayerCacheService.class, EconomyPlayerCacheService.class);
    services.register(EconomyRedisSyncService.class, EconomyRedisSyncService.class);
    services.register(EconomyFlushService.class, EconomyFlushService.class);
    services.register(EconomyPlayerListener.class, EconomyPlayerListener.class);
    services.register(EconomyService.class, EconomyService.class);
    services.register(VaultEconomyBridgeService.class, VaultEconomyBridgeService.class);

    services.register(VaultEconomyMigrationImporter.class, VaultEconomyMigrationImporter.class);
    services.register(MigrationService.class, MigrationService.class);
    services.register(EconomyPlaceholderService.class, EconomyPlaceholderService.class);
  }
}