package io.nexstudios.nexeconomy.provider.bank;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankAccountEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankInviteEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.serviceregistry.di.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * bank API for future commands, menus and holograms.
 *
 * <p>This interface is intentionally rich so UI layers can stay thin.
 */
@SuppressWarnings("unused")
public interface BankProvider extends Service {

  CompletableFuture<BankResponse<BankDefinition>> bank(String bankId);

  CompletableFuture<BankResponse<BankAccountEntity>> account(String bankId, UUID ownerUuid);

  CompletableFuture<BankResponse<List<BankRepositoryService.BankAccountRef>>> banks(UUID memberUuid);

  CompletableFuture<BankResponse<List<BankRepositoryService.BankAccountRef>>> otherBanks(UUID memberUuid);

  CompletableFuture<BankResponse<List<InviteLookupRow>>> invites(UUID inviteeUuid);

  CompletableFuture<BankResponse<List<BankMemberEntity>>> members(String bankId, UUID ownerUuid);

  CompletableFuture<BankResponse<List<BankMemberEntity>>> visibleMembers(String bankId, UUID ownerUuid, UUID viewerUuid);

  CompletableFuture<BankResponse<MantissaAmount>> balance(String bankId, UUID ownerUuid);

  CompletableFuture<BankResponse<MantissaAmount>> visibleBalance(String bankId, UUID ownerUuid, UUID viewerUuid);

  CompletableFuture<BankResponse<MantissaAmount>> deposit(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount);

  CompletableFuture<BankResponse<MantissaAmount>> withdraw(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount);

  CompletableFuture<BankResponse<BankInviteEntity>> invite(String bankId, UUID ownerUuid, UUID actorUuid, UUID inviteeUuid, String roleId);

  CompletableFuture<BankResponse<Boolean>> acceptInvite(String bankId, UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<BankResponse<Boolean>> acceptInviteFromOwner(UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<BankResponse<Boolean>> denyInvite(String bankId, UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<BankResponse<Boolean>> denyInviteFromOwner(UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<BankResponse<Boolean>> leave(String bankId, UUID ownerUuid, UUID memberUuid);

  CompletableFuture<BankResponse<Boolean>> kick(String bankId, UUID ownerUuid, UUID actorUuid, UUID memberUuid);

  CompletableFuture<BankResponse<Integer>> level(String bankId, UUID ownerUuid);

  CompletableFuture<BankResponse<Integer>> levelUp(String bankId, UUID ownerUuid, UUID actorUuid);

  CompletableFuture<BankResponse<Integer>> levelDown(String bankId, UUID ownerUuid, UUID actorUuid);

  CompletableFuture<BankResponse<Integer>> setLevel(String bankId, UUID ownerUuid, UUID actorUuid, int targetLevel);

  CompletableFuture<BankResponse<Boolean>> lock(String bankId, UUID ownerUuid, UUID actorUuid);

  CompletableFuture<BankResponse<Boolean>> unlock(String bankId, UUID ownerUuid, UUID actorUuid);

  CompletableFuture<BankResponse<Boolean>> isLocked(String bankId, UUID ownerUuid);

  CompletableFuture<BankResponse<List<BankTransactionEntity>>> transactions(String bankId, UUID ownerUuid, UUID viewerUuid, int limit);

  CompletableFuture<BankResponse<Boolean>> createBank(String bankId, UUID ownerUuid);

  CompletableFuture<BankResponse<Boolean>> deleteBank(String bankId, UUID actorUuid);
}


