package io.nexstudios.nexeconomy.provider.bank;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexeconomy.service.bank.transaction.BankTransactionService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankAccountEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankInviteEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Default bank provider implementation.
 *
 * <p>It is intentionally thin and delegates all business logic to the bank services.
 */
@SuppressWarnings("unused")
@Dependencies({
    BankService.class,
    BankLevelService.class,
    BankRepositoryService.class,
    BankAccountCacheService.class,
    BankRedisSyncService.class,
    BankTransactionService.class
})
public final class DefaultBankProvider implements BankProvider, Service {

  private final BankService bankService;
  private final BankLevelService levelService;
  private final BankRepositoryService repo;
  private final BankAccountCacheService cache;
  private final BankRedisSyncService redisSync;
  private final BankTransactionService transactionService;

  public DefaultBankProvider(ServiceAccessor accessor) {
    this.bankService = accessor.getService(BankService.class);
    this.levelService = accessor.getService(BankLevelService.class);
    this.repo = accessor.getService(BankRepositoryService.class);
    this.cache = accessor.getService(BankAccountCacheService.class);
    this.redisSync = accessor.getService(BankRedisSyncService.class);
    this.transactionService = accessor.getService(BankTransactionService.class);
  }

  @Override
  public CompletableFuture<BankResponse<BankDefinition>> bank(String bankId) {
    String id = normalize(bankId);
    BankResponse.BankContext ctx = BankResponse.context(id, null, null, null, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", ctx, null));
    }

    return bankService.bank(id).handle((opt, ex) -> {
      if (ex != null) {
        Throwable root = rootCause(ex);
        BankResponse.Status status = mapStatus(root);
        return BankResponse.failure(status, messageFor(status, root), ctx, (BankDefinition) null);
      }

      BankDefinition def = opt.isEmpty() ? null : opt.orElse(null);
      if (def == null) {
        return BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", ctx, (BankDefinition) null);
      }
      return BankResponse.success("Bank loaded.", ctx, def);
    });
  }

  @Override
  public CompletableFuture<BankResponse<BankAccountEntity>> account(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext ctx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", ctx, null));
    }
    if (ownerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner UUID is null.", ctx, null));
    }

    return bankService.getOrCreateAccount(id, ownerUuid).thenApply(acc -> {
      BankResponse.BankContext fullCtx = BankResponse.context(
          id,
          acc == null ? null : acc.getId(),
          ownerUuid,
          null,
          null,
          null,
          null,
          acc == null ? null : normalizeLevel(acc.getLevel()),
          acc == null ? null : normalizeLevel(acc.getLevel()),
          null,
          null,
          null
      );
      return BankResponse.success("Bank account loaded.", fullCtx, acc);
    }).exceptionally(ex -> failure(ex, ctx, null));
  }

  @Override
  public CompletableFuture<BankResponse<List<BankRepositoryService.BankAccountRef>>> banks(UUID memberUuid) {
    BankResponse.BankContext ctx = BankResponse.context(null, null, null, memberUuid, null, null, null, null, null, null, null, null);
    if (memberUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Member UUID is null.", ctx, List.of()));
    }

    return bankService.allBanks(memberUuid).thenApply(list -> BankResponse.success("Banks loaded.", ctx, safeList(list))).exceptionally(ex -> failure(ex, ctx, List.of()));
  }

  @Override
  public CompletableFuture<BankResponse<List<BankRepositoryService.BankAccountRef>>> otherBanks(UUID memberUuid) {
    BankResponse.BankContext ctx = BankResponse.context(null, null, null, memberUuid, null, null, null, null, null, null, null, null);
    if (memberUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Member UUID is null.", ctx, List.of()));
    }

    return bankService.otherBanks(memberUuid).thenApply(list -> BankResponse.success("Banks loaded.", ctx, safeList(list))).exceptionally(ex -> failure(ex, ctx, List.of()));
  }

  @Override
  public CompletableFuture<BankResponse<List<InviteLookupRow>>> invites(UUID inviteeUuid) {
    BankResponse.BankContext ctx = BankResponse.context(null, null, null, null, inviteeUuid, null, null, null, null, null, null, null);
    if (inviteeUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Invitee UUID is null.", ctx, List.of()));
    }

    return bankService.invites(inviteeUuid).thenApply(list -> BankResponse.success("Invites loaded.", ctx, safeList(list))).exceptionally(ex -> failure(ex, ctx, List.of()));
  }

  @Override
  public CompletableFuture<BankResponse<List<BankMemberEntity>>> members(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, List.of()));
    }
    if (ownerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner UUID is null.", baseCtx, List.of()));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        bankService.members(id, ownerUuid).thenApply(list -> {
          BankResponse.BankContext ctx = context(id, acc, ownerUuid, null, null, null, null, null, null, null, null, null);
          return BankResponse.success("Members loaded.", ctx, safeList(list));
        })
    ).exceptionally(ex -> failure(ex, baseCtx, List.of()));
  }

  @Override
  public CompletableFuture<BankResponse<List<BankMemberEntity>>> visibleMembers(String bankId, UUID ownerUuid, UUID viewerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, viewerUuid, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, List.of()));
    }
    if (ownerUuid == null || viewerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or viewer UUID is null.", baseCtx, List.of()));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        bankService.membersVisibleTo(id, ownerUuid, viewerUuid).thenApply(list -> {
          BankResponse.BankContext ctx = context(id, acc, ownerUuid, viewerUuid, null, null, null, null, null, null, null, null);
          return BankResponse.success("Visible members loaded.", ctx, safeList(list));
        })
    ).exceptionally(ex -> failure(ex, baseCtx, List.of()));
  }

  @Override
  public CompletableFuture<BankResponse<MantissaAmount>> balance(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, MantissaAmount.zero()));
    }
    if (ownerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner UUID is null.", baseCtx, MantissaAmount.zero()));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        bankService.balance(id, ownerUuid).thenApply(balance -> {
          MantissaAmount bal = normalizeAmount(balance);
          BankResponse.BankContext ctx = context(id, acc, ownerUuid, null, null, null, null, null, null, null, bal, null);
          return BankResponse.success("Balance loaded.", ctx, bal);
        })
    ).exceptionally(ex -> failure(ex, baseCtx, MantissaAmount.zero()));
  }

  @Override
  public CompletableFuture<BankResponse<MantissaAmount>> visibleBalance(String bankId, UUID ownerUuid, UUID viewerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, viewerUuid, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, MantissaAmount.zero()));
    }
    if (ownerUuid == null || viewerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or viewer UUID is null.", baseCtx, MantissaAmount.zero()));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        bankService.balanceVisibleTo(id, ownerUuid, viewerUuid).thenApply(balance -> {
          MantissaAmount bal = normalizeAmount(balance);
          BankResponse.BankContext ctx = context(id, acc, ownerUuid, viewerUuid, null, null, null, null, null, null, bal, null);
          return BankResponse.success("Visible balance loaded.", ctx, bal);
        })
    ).exceptionally(ex -> failure(ex, baseCtx, MantissaAmount.zero()));
  }

  @Override
  public CompletableFuture<BankResponse<MantissaAmount>> deposit(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount) {
    String id = normalize(bankId);
    MantissaAmount requested = normalizeAmount(amount);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, null, null, null, null, null, requested, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, MantissaAmount.zero()));
    }
    if (ownerUuid == null || actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or actor UUID is null.", baseCtx, MantissaAmount.zero()));
    }
    if (requested.compareTo(MantissaAmount.zero()) <= 0) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_AMOUNT, "Amount must be greater than zero.", baseCtx, MantissaAmount.zero()));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        bankService.deposit(id, ownerUuid, actorUuid, requested).thenCompose(applied ->
            bankService.balance(id, ownerUuid).thenApply(balance -> {
              MantissaAmount appliedAmount = normalizeAmount(applied);
              MantissaAmount currentBalance = normalizeAmount(balance);
              BankResponse.BankContext ctx = context(id, acc, ownerUuid, actorUuid, null, null, null, null, null, appliedAmount, currentBalance, null);
              return BankResponse.success("Deposit completed.", ctx, appliedAmount);
            })
        )
    ).exceptionally(ex -> failure(ex, baseCtx, MantissaAmount.zero()));
  }

  @Override
  public CompletableFuture<BankResponse<MantissaAmount>> withdraw(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount) {
    String id = normalize(bankId);
    MantissaAmount requested = normalizeAmount(amount);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, null, null, null, null, null, requested, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, MantissaAmount.zero()));
    }
    if (ownerUuid == null || actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or actor UUID is null.", baseCtx, MantissaAmount.zero()));
    }
    if (requested.compareTo(MantissaAmount.zero()) <= 0) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_AMOUNT, "Amount must be greater than zero.", baseCtx, MantissaAmount.zero()));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        bankService.withdraw(id, ownerUuid, actorUuid, requested).thenCompose(withdrawn ->
            bankService.balance(id, ownerUuid).thenApply(balance -> {
              MantissaAmount appliedAmount = normalizeAmount(withdrawn);
              MantissaAmount currentBalance = normalizeAmount(balance);
              BankResponse.BankContext ctx = context(id, acc, ownerUuid, actorUuid, null, null, null, null, null, appliedAmount, currentBalance, null);
              return BankResponse.success("Withdraw completed.", ctx, appliedAmount);
            })
        )
    ).exceptionally(ex -> failure(ex, baseCtx, MantissaAmount.zero()));
  }

  @Override
  public CompletableFuture<BankResponse<BankInviteEntity>> invite(String bankId, UUID ownerUuid, UUID actorUuid, UUID inviteeUuid, String roleId) {
    String id = normalize(bankId);
    String normalizedRole = normalize(roleId);
    if (normalizedRole.isBlank()) {
      normalizedRole = "member";
    }

    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, inviteeUuid, normalizedRole, null, null, null, null, null, null);
    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, null));
    }
    if (ownerUuid == null || actorUuid == null || inviteeUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner, actor or invitee UUID is null.", baseCtx, null));
    }

    return bankService.invite(id, ownerUuid, actorUuid, inviteeUuid, normalizedRole)
        .thenApply(inv -> BankResponse.success("Invite sent.", baseCtx, inv))
        .exceptionally(ex -> failure(ex, baseCtx, null));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> acceptInvite(String bankId, UUID ownerUuid, UUID inviteeUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, null, inviteeUuid, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null || inviteeUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or invitee UUID is null.", baseCtx, Boolean.FALSE));
    }

    return bankService.acceptInvite(id, ownerUuid, inviteeUuid)
        .thenApply(ok -> ok
            ? BankResponse.success("Invite accepted.", baseCtx, Boolean.TRUE)
            : BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE)
        )
        .exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> acceptInviteFromOwner(UUID ownerUuid, UUID inviteeUuid) {
    BankResponse.BankContext baseCtx = BankResponse.context(null, null, ownerUuid, null, inviteeUuid, null, null, null, null, null, null, null);
    if (ownerUuid == null || inviteeUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or invitee UUID is null.", baseCtx, Boolean.FALSE));
    }

    return bankService.acceptInviteFromOwner(ownerUuid, inviteeUuid)
        .thenApply(ok -> ok
            ? BankResponse.success("Invite accepted.", baseCtx, Boolean.TRUE)
            : BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE)
        )
        .exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> denyInvite(String bankId, UUID ownerUuid, UUID inviteeUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, null, inviteeUuid, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null || inviteeUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or invitee UUID is null.", baseCtx, Boolean.FALSE));
    }

    return repo.findInvitesForInviteeFromOwner(inviteeUuid, ownerUuid).thenCompose(rows -> {
      InviteLookupRow match = null;
      for (InviteLookupRow row : rows == null ? List.<InviteLookupRow>of() : rows) {
        if (id.equals(normalize(row.bankIdLower()))) {
          match = row;
          break;
        }
      }

      if (match == null) {
        return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE));
      }

      InviteLookupRow finalMatch = match;
      BankResponse.BankContext ctx = BankResponse.context(
          finalMatch.bankIdLower(),
          finalMatch.bankAccountId(),
          ownerUuid,
          null,
          inviteeUuid,
          finalMatch.roleIdLower(),
          null,
          null,
          null,
          null,
          null,
          null
      );

      return repo.deleteInvite(finalMatch.bankAccountId(), inviteeUuid).thenApply(deleted ->
          Boolean.TRUE.equals(deleted)
              ? BankResponse.success("Invite denied.", ctx, Boolean.TRUE)
              : BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", ctx, Boolean.FALSE)
      );
    }).exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> denyInviteFromOwner(UUID ownerUuid, UUID inviteeUuid) {
    BankResponse.BankContext baseCtx = BankResponse.context(null, null, ownerUuid, null, inviteeUuid, null, null, null, null, null, null, null);
    if (ownerUuid == null || inviteeUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or invitee UUID is null.", baseCtx, Boolean.FALSE));
    }

    return bankService.denyInviteFromOwner(ownerUuid, inviteeUuid)
        .thenApply(ok -> ok
            ? BankResponse.success("Invite denied.", baseCtx, Boolean.TRUE)
            : BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE)
        )
        .exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> leave(String bankId, UUID ownerUuid, UUID memberUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, null, memberUuid, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null || memberUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or member UUID is null.", baseCtx, Boolean.FALSE));
    }

    return bankService.leave(id, ownerUuid, memberUuid)
        .thenApply(ok -> ok
            ? BankResponse.success("Left the bank.", baseCtx, Boolean.TRUE)
            : BankResponse.failure(BankResponse.Status.NOT_MEMBER, "Member not found.", baseCtx, Boolean.FALSE)
        )
        .exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> kick(String bankId, UUID ownerUuid, UUID actorUuid, UUID memberUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, memberUuid, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null || actorUuid == null || memberUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner, actor or member UUID is null.", baseCtx, Boolean.FALSE));
    }
    if (Objects.equals(actorUuid, memberUuid)) {
      return leave(id, ownerUuid, memberUuid);
    }

    return bankService.bank(id).thenCompose(defOpt -> {
      BankDefinition def = defOpt.orElse(null);
      if (def == null) {
        return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", baseCtx, Boolean.FALSE));
      }

      BankDefinition.MemberSystem ms = def.memberSystem();
      if (ms == null || !ms.enabled() || ms.rolesByIdLower() == null) {
        return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.BANK_UNAVAILABLE, "Member system is disabled.", baseCtx, Boolean.FALSE));
      }

      return resolveAccount(id, ownerUuid).thenCompose(acc ->
          bankService.members(id, ownerUuid).thenCompose(list -> {
            BankMemberEntity actorMember = findMember(list, actorUuid);
            BankMemberEntity targetMember = findMember(list, memberUuid);

            if (actorMember == null) {
              return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.NOT_MEMBER, "Actor is not a member.", baseCtx, Boolean.FALSE));
            }
            if (targetMember == null) {
              return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.NOT_MEMBER, "Target member not found.", baseCtx, Boolean.FALSE));
            }
            if (Objects.equals(targetMember.getMemberUuid(), ownerUuid)) {
              return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.NOT_OWNER, "The owner cannot be kicked.", baseCtx, Boolean.FALSE));
            }

            BankDefinition.RoleDefinition actorRole = ms.rolesByIdLower().get(normalize(actorMember.getRoleIdLower()));
            BankDefinition.RoleDefinition targetRole = ms.rolesByIdLower().get(normalize(targetMember.getRoleIdLower()));
            if (actorRole == null || targetRole == null) {
              return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.ROLE_NOT_FOUND, "Role not found.", baseCtx, Boolean.FALSE));
            }
            if (!actorRole.canKick()) {
              return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.NO_PERMISSION, "You are not allowed to kick members.", baseCtx, Boolean.FALSE));
            }
            if (actorRole.priority() <= targetRole.priority()) {
              return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.NO_PERMISSION, "You cannot kick this member.", baseCtx, Boolean.FALSE));
            }

            BankResponse.BankContext ctx = BankResponse.context(
                id,
                acc == null ? null : acc.getId(),
                ownerUuid,
                actorUuid,
                memberUuid,
                targetMember.getRoleIdLower(),
                null,
                null,
                null,
                null,
                null,
                null
            );

            UUID accountId = acc == null ? null : acc.getId();
            if (accountId == null) {
              return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.BANK_UNAVAILABLE, "Bank account is unavailable.", ctx, Boolean.FALSE));
            }

            return repo.deleteMember(accountId, memberUuid).thenCompose(deleted ->
                repo.deleteInvite(accountId, memberUuid).exceptionally(ignore -> false).thenApply(ignore -> Boolean.TRUE.equals(deleted))
            ).thenApply(done -> {
              if (!done) {
                return BankResponse.failure(BankResponse.Status.NOT_MEMBER, "Target member not found.", ctx, Boolean.FALSE);
              }

              invalidate(accountId);
              return BankResponse.success("Member kicked.", ctx, Boolean.TRUE);
            });
          })
      );
    }).exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Integer>> level(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, 1));
    }
    if (ownerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner UUID is null.", baseCtx, 1));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        loadFreshLevel(acc.getId()).thenCompose(currentLevel ->
            balance(id, ownerUuid).thenApply(balanceResp -> {
              int maxLevel = levelService.getMaxLevel(id);
              int targetLevel = currentLevel >= maxLevel ? maxLevel : currentLevel + 1;
              MantissaAmount cost = targetLevel > currentLevel ? levelService.getUpgradeCost(id, targetLevel) : MantissaAmount.zero();
              MantissaAmount maxBalance = levelService.getMaxBalance(id, currentLevel);
              BankResponse.BankContext ctx = BankResponse.context(
                  id,
                  acc.getId(),
                  ownerUuid,
                  null,
                  null,
                  null,
                  currentLevel,
                  currentLevel,
                  targetLevel,
                  cost,
                  balanceResp == null ? null : normalizeAmount(balanceResp.payload()),
                  maxBalance
              );
              return BankResponse.success("Bank level loaded.", ctx, currentLevel);
            })
        )
    ).exceptionally(ex -> failure(ex, baseCtx, 1));
  }

  @Override
  public CompletableFuture<BankResponse<Integer>> levelUp(String bankId, UUID ownerUuid, UUID actorUuid) {
    return resolveTargetLevel(bankId, ownerUuid, actorUuid, true);
  }

  @Override
  public CompletableFuture<BankResponse<Integer>> levelDown(String bankId, UUID ownerUuid, UUID actorUuid) {
    return resolveTargetLevel(bankId, ownerUuid, actorUuid, false);
  }

  @Override
  public CompletableFuture<BankResponse<Integer>> setLevel(String bankId, UUID ownerUuid, UUID actorUuid, int targetLevel) {
    return changeLevel(bankId, ownerUuid, actorUuid, targetLevel);
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> lock(String bankId, UUID ownerUuid, UUID actorUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null || actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or actor UUID is null.", baseCtx, Boolean.FALSE));
    }

    return isLocked(id, ownerUuid).thenCompose(lockState -> {
      if (!lockState.isSuccess()) {
        return completed(lockState);
      }

      if (Boolean.TRUE.equals(lockState.payload())) {
        return completed(BankResponse.failure(BankResponse.Status.ALREADY_LOCKED, "The bank is already locked.", lockState.context(), Boolean.FALSE));
      }

      return bankService.lockBankForPlayer(id, ownerUuid, actorUuid).thenApply(ok -> {
        if (!Boolean.TRUE.equals(ok)) {
          return BankResponse.failure(BankResponse.Status.ALREADY_UNLOCKED, "The bank cannot be locked.", baseCtx, Boolean.FALSE);
        }
        return BankResponse.success("Bank locked.", baseCtx, Boolean.TRUE);
      });
    }).exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> unlock(String bankId, UUID ownerUuid, UUID actorUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null || actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or actor UUID is null.", baseCtx, Boolean.FALSE));
    }

    return isLocked(id, ownerUuid).thenCompose(lockState -> {
      if (!lockState.isSuccess()) {
        return completed(lockState);
      }

      if (!Boolean.TRUE.equals(lockState.payload())) {
        return completed(BankResponse.failure(BankResponse.Status.ALREADY_UNLOCKED, "The bank is already unlocked.", lockState.context(), Boolean.FALSE));
      }

      return bankService.unlockBankForPlayer(id, ownerUuid, actorUuid).thenApply(ok -> {
        if (!Boolean.TRUE.equals(ok)) {
          return BankResponse.failure(BankResponse.Status.ALREADY_UNLOCKED, "The bank cannot be unlocked.", baseCtx, Boolean.FALSE);
        }
        return BankResponse.success("Bank unlocked.", baseCtx, Boolean.TRUE);
      });
    }).exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> isLocked(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner UUID is null.", baseCtx, Boolean.FALSE));
    }

    return bankService.bank(id).thenCompose(defOpt -> {
      BankDefinition def = defOpt.orElse(null);
      if (def == null) {
        return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", baseCtx, Boolean.FALSE));
      }
      if (def.unlockedByDefault()) {
        return CompletableFuture.completedFuture(BankResponse.success("Bank is unlocked by default.", baseCtx, Boolean.FALSE));
      }

      return repo.isUnlocked(id, ownerUuid).thenApply(unlocked -> {
        boolean locked = !Boolean.TRUE.equals(unlocked);
        BankResponse.BankContext ctx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);
        return BankResponse.success(locked ? "Bank is locked." : "Bank is unlocked.", ctx, locked);
      });
    }).exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<List<BankTransactionEntity>>> transactions(String bankId, UUID ownerUuid, UUID viewerUuid, int limit) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, viewerUuid, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, List.of()));
    }
    if (ownerUuid == null || viewerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or viewer UUID is null.", baseCtx, List.of()));
    }
    if (limit <= 0) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Limit must be greater than zero.", baseCtx, List.of()));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        transactionService.transactionsVisibleTo(id, ownerUuid, viewerUuid, limit).thenApply(list -> {
          BankResponse.BankContext ctx = context(id, acc, ownerUuid, viewerUuid, null, null, null, null, null, null, null, null);
          return BankResponse.success("Transactions loaded.", ctx, safeList(list));
        })
    ).exceptionally(ex -> failure(ex, baseCtx, List.of()));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> createBank(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext ctx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);
    return completed(BankResponse.notImplemented("Bank creation is not implemented yet."));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> deleteBank(String bankId, UUID actorUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext ctx = BankResponse.context(id, null, null, actorUuid, null, null, null, null, null, null, null, null);
    return completed(BankResponse.notImplemented("Bank deletion is not implemented yet."));
  }

  private CompletableFuture<BankResponse<Integer>> resolveTargetLevel(String bankId, UUID ownerUuid, UUID actorUuid, boolean up) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, 1));
    }
    if (ownerUuid == null || actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or actor UUID is null.", baseCtx, 1));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        loadFreshLevel(acc.getId()).thenCompose(currentLevel -> {
          int targetLevel = up ? currentLevel + 1 : currentLevel - 1;
          return changeLevel(id, ownerUuid, actorUuid, targetLevel, currentLevel, acc.getId());
        })
    ).exceptionally(ex -> failure(ex, baseCtx, 1));
  }

  private CompletableFuture<BankResponse<Integer>> changeLevel(String bankId, UUID ownerUuid, UUID actorUuid, int targetLevel) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, null, null, null, null, targetLevel, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, 1));
    }
    if (ownerUuid == null || actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or actor UUID is null.", baseCtx, 1));
    }

    return resolveAccount(id, ownerUuid).thenCompose(acc ->
        loadFreshLevel(acc.getId()).thenCompose(currentLevel ->
            changeLevel(id, ownerUuid, actorUuid, targetLevel, currentLevel, acc.getId())
        )
    ).exceptionally(ex -> failure(ex, baseCtx, 1));
  }

  private CompletableFuture<BankResponse<Integer>> changeLevel(String bankId, UUID ownerUuid, UUID actorUuid, int targetLevel, int currentLevel, UUID bankAccountId) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null, null, currentLevel, currentLevel, targetLevel, null, null, null);

    if (targetLevel < 1) {
      return completed(BankResponse.failure(BankResponse.Status.MIN_LEVEL_REACHED, "Level cannot be lower than 1.", baseCtx, currentLevel));
    }
    if (currentLevel == targetLevel) {
      return completed(BankResponse.failure(BankResponse.Status.ALREADY_AT_LEVEL, "The bank is already at this level.", baseCtx, currentLevel));
    }

    return bankService.bank(id).thenCompose(defOpt -> {
      BankDefinition def = defOpt.orElse(null);
      if (def == null) {
        return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", baseCtx, currentLevel));
      }

      BankDefinition.LevelDefinition targetDef = findLevel(def, targetLevel);
      int maxLevel = levelService.getMaxLevel(id);
      if (targetLevel > maxLevel) {
        return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.MAX_LEVEL_REACHED, "The maximum level has been reached.", baseCtx, currentLevel));
      }

      Player actor = actorUuid == null ? null : Bukkit.getPlayer(actorUuid);
      if (targetLevel > currentLevel && targetDef != null && isNotBlank(targetDef.permission())) {
        if (actor == null || !actor.hasPermission(targetDef.permission())) {
          return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.NO_PERMISSION, "You do not have permission for this level.", baseCtx, currentLevel));
        }
      }

      MantissaAmount cost = targetLevel > currentLevel ? normalizeAmount(levelService.getUpgradeCost(id, targetLevel)) : MantissaAmount.zero();
      MantissaAmount maxBalance = normalizeAmount(levelService.getMaxBalance(id, targetLevel));

      return bankService.balance(id, ownerUuid).thenCompose(balance -> {
        MantissaAmount currentBalance = normalizeAmount(balance);

        return repo.updateBankLevel(bankAccountId, targetLevel).thenApply(updated -> {
          if (!Boolean.TRUE.equals(updated)) {
            return BankResponse.failure(BankResponse.Status.INTERNAL_ERROR, "Failed to update bank level.", baseCtx, currentLevel);
          }

          invalidate(bankAccountId);

              BankResponse.BankContext ctx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null,
                  targetDef == null ? null : targetDef.permission(), currentLevel, targetLevel, targetLevel, cost, currentBalance, maxBalance);

          String message = targetLevel > currentLevel ? "Bank level upgraded." : "Bank level downgraded.";
          return BankResponse.success(message, ctx, targetLevel);
        });
      });
    }).exceptionally(ex -> failure(ex, baseCtx, currentLevel));
  }

  private CompletableFuture<BankAccountEntity> resolveAccount(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    if (id.isBlank()) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    }
    if (ownerUuid == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    }
    return bankService.getOrCreateAccount(id, ownerUuid);
  }

  private CompletableFuture<Integer> loadFreshLevel(UUID bankAccountId) {
    if (bankAccountId == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    }

    return repo.findBankAccountById(bankAccountId).thenApply(opt -> {
      BankAccountEntity acc = opt.orElse(null);
      Integer level = acc == null ? null : acc.getLevel();
      return normalizeLevel(level);
    });
  }

  private void invalidate(UUID bankAccountId) {
    if (bankAccountId == null) {
      return;
    }
    if (cache != null) {
      cache.invalidate(bankAccountId);
    }
    if (redisSync != null) {
      redisSync.publishInvalidateAccount(bankAccountId);
    }
  }

  private static BankDefinition.LevelDefinition findLevel(BankDefinition def, int level) {
    if (def == null || def.levels() == null) {
      return null;
    }

    for (BankDefinition.LevelDefinition ld : def.levels()) {
      if (ld != null && ld.level() == level) {
        return ld;
      }
    }
    return null;
  }

  private static BankMemberEntity findMember(List<BankMemberEntity> members, UUID uuid) {
    if (uuid == null || members == null || members.isEmpty()) {
      return null;
    }

    for (BankMemberEntity member : members) {
      if (member == null) continue;
      if (uuid.equals(member.getMemberUuid())) {
        return member;
      }
    }

    return null;
  }

  private static BankResponse.BankContext context(
      String bankId,
      BankAccountEntity account,
      UUID ownerUuid,
      UUID actorUuid,
      UUID targetUuid,
      String roleId,
      Integer previousLevel,
      Integer currentLevel,
      Integer targetLevel,
      MantissaAmount amount,
      MantissaAmount balance,
      MantissaAmount limit
  ) {
    return BankResponse.context(
        bankId,
        account == null ? null : account.getId(),
        ownerUuid,
        actorUuid,
        targetUuid,
        roleId,
        previousLevel,
        currentLevel,
        targetLevel,
        amount,
        balance,
        limit
    );
  }

  private static <T> CompletableFuture<T> completed(T value) {
    return CompletableFuture.completedFuture(value);
  }

  private static <T> T safeList(T value) {
    return value;
  }

  private static MantissaAmount normalizeAmount(MantissaAmount amount) {
    return amount == null ? MantissaAmount.zero() : MantissaAmount.normalize(amount);
  }

  private static int normalizeLevel(Integer level) {
    return level == null || level <= 0 ? 1 : level;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isNotBlank(String value) {
    return value != null && !value.trim().isBlank();
  }

  private static <T> BankResponse<T> failure(Throwable ex, BankResponse.BankContext context, T payload) {
    Throwable root = rootCause(ex);
    BankResponse.Status status = mapStatus(root);
    String message = messageFor(status, root);
    return BankResponse.failure(status, message, context, payload);
  }

  private static Throwable rootCause(Throwable ex) {
    Throwable current = ex;
    for (int i = 0; i < 16 && current != null; i++) {
      Throwable next = current.getCause();
      if (next == null || next == current) {
        break;
      }
      current = next;
    }
    return current == null ? ex : current;
  }

  private static BankResponse.Status mapStatus(Throwable ex) {
    if (ex instanceof IllegalArgumentException) {
      return BankResponse.Status.INVALID_ARGUMENT;
    }

    String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("bank not available") || message.contains("bank not found")) return BankResponse.Status.BANK_NOT_FOUND;
    if (message.contains("bank disabled")) return BankResponse.Status.BANK_DISABLED;
    if (message.contains("member system disabled")) return BankResponse.Status.BANK_UNAVAILABLE;
    if (message.contains("bank locked") || message.contains("bank accounts locked")) return BankResponse.Status.BANK_LOCKED;
    if (message.contains("not a member")) return BankResponse.Status.NOT_MEMBER;
    if (message.contains("no permission")) return BankResponse.Status.NO_PERMISSION;
    if (message.contains("already a member")) return BankResponse.Status.ALREADY_MEMBER;
    if (message.contains("already invited")) return BankResponse.Status.ALREADY_INVITED;
    if (message.contains("invitee_member_limit_reached") || message.contains("member limit reached")) return BankResponse.Status.MEMBER_LIMIT_REACHED;
    if (message.contains("unknown role") || message.contains("role not found")) return BankResponse.Status.ROLE_NOT_FOUND;
    if (message.contains("cannot invite owner") || message.contains("owner_cannot_leave")) return BankResponse.Status.NOT_OWNER;
    if (message.contains("invite expired")) return BankResponse.Status.INVITE_EXPIRED;
    if (message.contains("invite not found")) return BankResponse.Status.INVITE_NOT_FOUND;
    if (message.contains("invalid amount")) return BankResponse.Status.INVALID_AMOUNT;
    if (message.contains("insufficient funds") || message.contains("bank empty")) return BankResponse.Status.INSUFFICIENT_FUNDS;
    if (message.contains("already at level")) return BankResponse.Status.ALREADY_AT_LEVEL;
    if (message.contains("maximum level") || message.contains("max level")) return BankResponse.Status.MAX_LEVEL_REACHED;
    if (message.contains("minimum level") || message.contains("level cannot be lower than 1")) return BankResponse.Status.MIN_LEVEL_REACHED;
    if (message.contains("level too high")) return BankResponse.Status.LEVEL_TOO_HIGH;
    if (message.contains("level too low")) return BankResponse.Status.LEVEL_TOO_LOW;
    if (message.contains("already locked")) return BankResponse.Status.ALREADY_LOCKED;
    if (message.contains("already unlocked")) return BankResponse.Status.ALREADY_UNLOCKED;
    if (message.contains("currency not configured")) return BankResponse.Status.CURRENCY_NOT_CONFIGURED;
    if (message.contains("not implemented")) return BankResponse.Status.NOT_IMPLEMENTED;
    return BankResponse.Status.INTERNAL_ERROR;
  }

  private static String messageFor(BankResponse.Status status, Throwable ex) {
    return switch (status) {
      case INVALID_ARGUMENT -> "Invalid argument.";
      case INVALID_AMOUNT -> "Amount must be greater than zero.";
      case BANK_NOT_FOUND -> "Bank not found.";
      case BANK_DISABLED -> "Bank is disabled.";
      case BANK_UNAVAILABLE -> "Bank is unavailable.";
      case BANK_LOCKED -> "Bank is locked.";
      case NOT_OWNER -> "You are not the owner.";
      case NOT_MEMBER -> "You are not a member of this bank.";
      case NO_PERMISSION -> "You do not have permission.";
      case ALREADY_MEMBER -> "The player is already a member.";
      case ALREADY_INVITED -> "The player is already invited.";
      case INVITE_NOT_FOUND -> "Invite not found.";
      case INVITE_EXPIRED -> "Invite expired.";
      case MEMBER_LIMIT_REACHED -> "Member limit reached.";
      case ROLE_NOT_FOUND -> "Role not found.";
      case ALREADY_AT_LEVEL -> "The bank is already at this level.";
      case MAX_LEVEL_REACHED -> "The maximum level has been reached.";
      case MIN_LEVEL_REACHED -> "The minimum level has been reached.";
      case LEVEL_TOO_HIGH -> "The target level is too high.";
      case LEVEL_TOO_LOW -> "The target level is too low.";
      case INSUFFICIENT_FUNDS -> "Insufficient funds.";
      case CURRENCY_NOT_CONFIGURED -> "Currency is not configured.";
      case ALREADY_LOCKED -> "The bank is already locked.";
      case ALREADY_UNLOCKED -> "The bank is already unlocked.";
      case NOT_IMPLEMENTED -> "This feature is not implemented yet.";
      case FAILURE, INTERNAL_ERROR -> {
        String msg = ex == null ? null : ex.getMessage();
        yield (msg == null || msg.isBlank()) ? "Internal error." : msg;
      }
      case SUCCESS -> "Success.";
    };
  }
}





