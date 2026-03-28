package io.nexstudios.nexeconomy.service.bank.transaction;

import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.serviceregistry.di.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BankTransactionService extends Service {

  CompletableFuture<List<BankTransactionEntity>> transactionsVisibleTo(
      String bankIdLower,
      UUID ownerUuid,
      UUID viewerUuid,
      int limit
  );
}