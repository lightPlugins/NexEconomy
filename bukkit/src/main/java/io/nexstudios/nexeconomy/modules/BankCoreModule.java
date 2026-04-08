package io.nexstudios.nexeconomy.modules;

import io.nexstudios.nexeconomy.provider.bank.BankProviderService;
import io.nexstudios.nexeconomy.provider.bank.DefaultBankProviderService;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.DefaultBankService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountPresenceService;
import io.nexstudios.nexeconomy.service.bank.interest.BankInterestService;
import io.nexstudios.nexeconomy.service.bank.interest.DefaultBankInterestService;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexeconomy.service.bank.level.DefaultBankLevelService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.registry.DefaultBankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.DefaultBankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexeconomy.service.bank.sync.DefaultBankRedisSyncServiceService;
import io.nexstudios.nexeconomy.service.bank.transaction.BankTransactionService;
import io.nexstudios.nexeconomy.service.bank.transaction.DefaultBankTransactionService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.nexstudios.serviceregistry.di.ServiceModule;
import org.jetbrains.annotations.NotNull;

public final class BankCoreModule implements ServiceModule {

  @Override
  public void install(@NotNull ServiceAccessor services) {
    // Register base services first (no internal dependencies)
    services.register(BankRegistryService.class, DefaultBankRegistryService.class);
    services.register(BankRepositoryService.class, DefaultBankRepositoryService.class);

    // Register cache services before cache-dependent services
    services.register(BankAccountCacheService.class, BankAccountCacheService.class);
    services.register(BankAccountPresenceService.class, BankAccountPresenceService.class);

    // Register BankLevelService after the cache is available
    services.register(BankLevelService.class, DefaultBankLevelService.class);

    // Register sync and transaction services
    services.register(BankRedisSyncService.class, DefaultBankRedisSyncServiceService.class);
    services.register(BankTransactionService.class, DefaultBankTransactionService.class);

    // Register interest service
    services.register(BankInterestService.class, DefaultBankInterestService.class);

    // Register main bank service last (depends on everything else)
    services.register(BankService.class, DefaultBankService.class);

    // Register the public bank API for later use in commands/menus/holograms
    services.register(BankProviderService.class, DefaultBankProviderService.class);
  }
}