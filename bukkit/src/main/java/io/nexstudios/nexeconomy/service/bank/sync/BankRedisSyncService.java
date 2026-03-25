package io.nexstudios.nexeconomy.service.bank.sync;

import io.nexstudios.serviceregistry.di.Service;

import java.util.UUID;

public interface BankRedisSyncService extends Service {

  void start();

  void publishInvalidateAccount(UUID bankAccountId);
}