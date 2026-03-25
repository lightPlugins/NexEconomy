package io.nexstudios.nexeconomy.service.bank;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankAccountEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankInviteEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.serviceregistry.di.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BankService extends Service {

  CompletableFuture<Optional<BankDefinition>> bank(String bankId);

  CompletableFuture<List<InviteLookupRow>> invites(UUID inviteeUuid);

  CompletableFuture<BankAccountEntity> getOrCreateAccount(String bankId, UUID ownerUuid);

  CompletableFuture<List<BankMemberEntity>> members(String bankId, UUID ownerUuid);

  CompletableFuture<BankInviteEntity> invite(String bankId, UUID ownerUuid, UUID actorUuid, UUID inviteeUuid, String roleId);

  CompletableFuture<Boolean> acceptInvite(String bankId, UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<MantissaAmount> balance(String bankId, UUID ownerUuid);

  CompletableFuture<MantissaAmount> deposit(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount);

  CompletableFuture<MantissaAmount> withdraw(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount);

  CompletableFuture<Boolean> acceptInviteFromOwner(UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<Boolean> denyInviteFromOwner(UUID ownerUuid, UUID inviteeUuid);

  void ensureMissingUnlockedBanksForAllOnline();

}