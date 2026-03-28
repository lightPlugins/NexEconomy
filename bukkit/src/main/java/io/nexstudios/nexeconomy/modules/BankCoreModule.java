package io.nexstudios.nexeconomy.modules;

import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.DefaultBankService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountPresenceService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.registry.DefaultBankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.DefaultBankRepositoryServiceService;
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

    services.register(BankRegistryService.class, DefaultBankRegistryService.class);
    services.register(BankRepositoryService.class, DefaultBankRepositoryServiceService.class);
    services.register(BankAccountCacheService.class, BankAccountCacheService.class);
    services.register(BankAccountPresenceService.class, BankAccountPresenceService.class);
    services.register(BankRedisSyncService.class, DefaultBankRedisSyncServiceService.class);
    services.register(BankTransactionService.class, DefaultBankTransactionService.class);
    services.register(BankService.class, DefaultBankService.class);
  }
}