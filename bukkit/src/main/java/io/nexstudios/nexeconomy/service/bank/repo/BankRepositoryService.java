package io.nexstudios.nexeconomy.service.bank.repo;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankAccountEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankInviteEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankWithdrawUsageEntity;
import io.nexstudios.serviceregistry.di.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BankRepositoryService extends Service {

  CompletableFuture<List<InviteLookupRow>> findInvitesForInviteeFromOwner(UUID inviteeUuid, UUID ownerUuid);

  CompletableFuture<List<InviteLookupRow>> findInvitesForInvitee(UUID inviteeUuid);

  CompletableFuture<Optional<BankAccountEntity>> findAccount(String bankIdLower, UUID ownerUuid);

  CompletableFuture<BankAccountEntity> createAccountIfMissing(String bankIdLower, UUID ownerUuid);

  CompletableFuture<MantissaAmount> loadBalance(UUID bankAccountId);

  CompletableFuture<MantissaAmount> setBalance(UUID bankAccountId, MantissaAmount newBalance);

  CompletableFuture<MantissaAmount> applyBalanceDelta(UUID bankAccountId, MantissaAmount delta);

  CompletableFuture<List<BankMemberEntity>> listMembers(UUID bankAccountId);

  CompletableFuture<Optional<BankMemberEntity>> findMember(UUID bankAccountId, UUID memberUuid);

  CompletableFuture<BankMemberEntity> upsertMember(UUID bankAccountId, UUID memberUuid, UUID addedByUuid, String roleIdLower);

  CompletableFuture<Boolean> deleteMember(UUID bankAccountId, UUID memberUuid);

  CompletableFuture<Boolean> isMemberOfAnyOtherAccount(UUID memberUuid, UUID excludeOwnedByUuid);

  CompletableFuture<Optional<BankInviteEntity>> findInvite(UUID bankAccountId, UUID inviteeUuid);

  CompletableFuture<BankInviteEntity> upsertInvite(UUID bankAccountId, UUID inviteeUuid, UUID invitedByUuid, String roleIdLower);

  CompletableFuture<Boolean> deleteInvite(UUID bankAccountId, UUID inviteeUuid);

  CompletableFuture<MantissaAmount> addWithdrawUsage(
      UUID bankAccountId,
      UUID memberUuid,
      BankWithdrawUsageEntity.WindowType windowType,
      long windowStartEpochSeconds,
      MantissaAmount delta
  );

  CompletableFuture<Void> appendTransaction(
      UUID bankAccountId,
      BankTransactionEntity.Type type,
      UUID actorUuid,
      UUID targetUuid,
      MantissaAmount amount,
      String meta
  );

  CompletableFuture<List<BankTransactionEntity>> listRecentTransactions(UUID bankAccountId, int limit);


  CompletableFuture<List<UUID>> findOwnerUuidsForMember(UUID memberUuid);

  CompletableFuture<List<UUID>> findOwnerUuidsForMember(String bankIdLower, UUID memberUuid);

  record BankAccountRef(UUID bankAccountId, String bankIdLower, UUID ownerUuid) {}
  CompletableFuture<List<BankAccountRef>> findBankAccountsForMember(UUID memberUuid);

  CompletableFuture<Long> countOtherBankMemberships(UUID memberUuid);
}