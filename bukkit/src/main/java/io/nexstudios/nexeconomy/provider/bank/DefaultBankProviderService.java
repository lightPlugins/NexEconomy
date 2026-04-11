package io.nexstudios.nexeconomy.provider.bank;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.BankService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.level.BankLevelService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
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
    BankRegistryService.class,
    BankLevelService.class,
    BankRepositoryService.class,
    BankAccountCacheService.class,
    CurrencyRegistryService.class,
    EconomyService.class,
    EconomyPlayerCacheService.class,
    BankRedisSyncService.class
})
public final class DefaultBankProviderService implements BankProviderService, Service {

  private final BankService bankService;
  private final BankRegistryService bankRegistry;
  private final BankLevelService levelService;
  private final BankRepositoryService repo;
  private final BankAccountCacheService cache;
  private final CurrencyRegistryService currencies;
  private final EconomyService economy;
  private final EconomyPlayerCacheService economyCache;
  private final BankRedisSyncService redisSync;

  public DefaultBankProviderService(ServiceAccessor accessor) {
    this.bankService = accessor.getService(BankService.class);
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.levelService = accessor.getService(BankLevelService.class);
    this.repo = accessor.getService(BankRepositoryService.class);
    this.cache = accessor.getService(BankAccountCacheService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.economy = accessor.getService(EconomyService.class);
    this.economyCache = accessor.getService(EconomyPlayerCacheService.class);
    this.redisSync = accessor.getService(BankRedisSyncService.class);
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
        return BankResponse.failure(status, messageFor(status, root), ctx, null);
      }

      BankDefinition def = opt.isEmpty() ? null : opt.orElse(null);
      if (def == null) {
        return BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", ctx, null);
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

    return loadAccess(id, ownerUuid)
        .thenApply(access -> BankResponse.success(
            "Bank account loaded.",
            accessContext(id, access, ownerUuid, null, null, null, null, null, null, null, null),
            access.account()
        ))
        .exceptionally(ex -> failure(ex, ctx, null));
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

    return loadAccess(id, ownerUuid)
        .thenApply(access -> BankResponse.success(
            "Members loaded.",
            accessContext(id, access, ownerUuid, null, null, null, null, null, null, null, null),
            access.members()
        ))
        .exceptionally(ex -> failure(ex, baseCtx, List.of()));
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

    return loadAccess(id, ownerUuid).thenCompose(access -> {
      if (!Objects.equals(ownerUuid, viewerUuid)) {
        BankMemberEntity viewerMember = findMember(access.members(), viewerUuid);
        if (viewerMember == null) {
          return completed(BankResponse.failure(BankResponse.Status.NOT_MEMBER, "You are not a member of this bank.", baseCtx, List.<BankMemberEntity>of()));
        }
      }

      BankResponse<List<BankMemberEntity>> response = BankResponse.success(
          "Visible members loaded.",
          accessContext(id, access, ownerUuid, viewerUuid, null, null, null, null, null, null, null),
          access.members()
      );
      return completed(response);
    }).exceptionally(ex -> {
      Throwable root = rootCause(ex);
      BankResponse.Status status = mapStatus(root);
      return BankResponse.failure(status, messageFor(status, root), baseCtx, List.of());
    });
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

    return loadAccess(id, ownerUuid)
        .thenApply(access -> {
          MantissaAmount bal = access.balance();
          return BankResponse.success(
              "Balance loaded.",
              accessContext(id, access, ownerUuid, null, null, null, null, null, bal, null, null),
              bal
          );
        })
        .exceptionally(ex -> failure(ex, baseCtx, MantissaAmount.zero()));
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

    return loadAccess(id, ownerUuid).thenCompose(access -> {
      if (!Objects.equals(ownerUuid, viewerUuid)) {
        BankMemberEntity viewerMember = findMember(access.members(), viewerUuid);
        if (viewerMember == null) {
          return completed(BankResponse.failure(BankResponse.Status.NOT_MEMBER, "You are not a member of this bank.", baseCtx, MantissaAmount.zero()));
        }
      }

      MantissaAmount bal = access.balance();
      return completed(BankResponse.success(
          "Visible balance loaded.",
          accessContext(id, access, ownerUuid, viewerUuid, null, null, null, null, bal, null, null),
          bal
      ));
    }).exceptionally(ex -> failure(ex, baseCtx, MantissaAmount.zero()));
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

    return bankService.deposit(id, ownerUuid, actorUuid, requested).thenCompose(applied ->
        bankService.balance(id, ownerUuid).thenApply(balance -> {
          MantissaAmount appliedAmount = normalizeAmount(applied);
          MantissaAmount currentBalance = normalizeAmount(balance);
          BankAccountCacheService.View view = cache == null ? null : cache.get(id, ownerUuid);
          BankResponse.BankContext ctx = context(
              id,
              view == null ? null : view.account(),
              ownerUuid,
              actorUuid,
              null,
              null,
              null,
              null,
              null,
              appliedAmount,
              currentBalance,
              null
          );
          return BankResponse.success("Deposit completed.", ctx, appliedAmount);
        })
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

    return bankService.withdraw(id, ownerUuid, actorUuid, requested).thenCompose(withdrawn ->
        bankService.balance(id, ownerUuid).thenApply(balance -> {
          MantissaAmount appliedAmount = normalizeAmount(withdrawn);
          MantissaAmount currentBalance = normalizeAmount(balance);
          BankAccountCacheService.View view = cache == null ? null : cache.get(id, ownerUuid);
          BankResponse.BankContext ctx = context(
              id,
              view == null ? null : view.account(),
              ownerUuid,
              actorUuid,
              null,
              null,
              null,
              null,
              null,
              appliedAmount,
              currentBalance,
              null
          );
          return BankResponse.success("Withdraw completed.", ctx, appliedAmount);
        })
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

    return repo.findInvitesForInviteeFromOwner(inviteeUuid, ownerUuid).thenCompose(rows -> {
      InviteLookupRow selected = selectPreferredInvite(rows, true);
      if (selected == null) {
        return completed(BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE));
      }

      if (isExpired(selected)) {
        return repo.deleteInvite(selected.bankAccountId(), inviteeUuid).thenApply(ignored ->
            BankResponse.failure(BankResponse.Status.INVITE_EXPIRED, "Invite expired.", BankResponse.context(
                selected.bankIdLower(),
                selected.bankAccountId(),
                ownerUuid,
                null,
                inviteeUuid,
                selected.roleIdLower(),
                null,
                null,
                null,
                null,
                null,
                null
            ), Boolean.FALSE)
        );
      }

      return bankService.acceptInvite(selected.bankIdLower(), ownerUuid, inviteeUuid)
          .thenApply(ok -> ok
              ? BankResponse.success("Invite accepted.", BankResponse.context(
                  selected.bankIdLower(),
                  selected.bankAccountId(),
                  ownerUuid,
                  null,
                  inviteeUuid,
                  selected.roleIdLower(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null
              ), Boolean.TRUE)
              : BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE)
          );
    })
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

    return repo.findInvitesForInviteeFromOwner(inviteeUuid, ownerUuid).thenCompose(rows -> {
      InviteLookupRow selected = selectPreferredInvite(rows, true);
      if (selected == null) {
        return completed(BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE));
      }

      return repo.deleteInvite(selected.bankAccountId(), inviteeUuid).thenApply(deleted ->
          Boolean.TRUE.equals(deleted)
              ? BankResponse.success("Invite denied.", BankResponse.context(
                  selected.bankIdLower(),
                  selected.bankAccountId(),
                  ownerUuid,
                  null,
                  inviteeUuid,
                  selected.roleIdLower(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null
              ), Boolean.TRUE)
              : BankResponse.failure(BankResponse.Status.INVITE_NOT_FOUND, "Invite not found.", baseCtx, Boolean.FALSE)
      );
    })
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
  public CompletableFuture<BankResponse<Boolean>> changeMemberRole(String bankId, UUID ownerUuid, UUID actorUuid, UUID memberUuid, String roleId) {
    String id = normalize(bankId);
    String normalizedRole = normalize(roleId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, memberUuid, normalizedRole, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, Boolean.FALSE));
    }
    if (ownerUuid == null || actorUuid == null || memberUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner, actor or member UUID is null.", baseCtx, Boolean.FALSE));
    }

    return bankService.changeMemberRole(id, ownerUuid, actorUuid, memberUuid, normalizedRole)
        .thenApply(ok -> Boolean.TRUE.equals(ok)
            ? BankResponse.success("Member role updated.", baseCtx, Boolean.TRUE)
            : BankResponse.failure(BankResponse.Status.NO_PERMISSION, "You are not allowed to change this member.", baseCtx, Boolean.FALSE)
        )
        .exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
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

    return loadAccess(id, ownerUuid)
        .thenApply(access -> {
          BankAccountEntity acc = access.account();
          int currentLevel = normalizeLevel(acc == null ? null : acc.getLevel());
          int maxLevel = levelService.getMaxLevel(id);
          int targetLevel = currentLevel >= maxLevel ? maxLevel : currentLevel + 1;
          MantissaAmount cost = targetLevel > currentLevel ? levelService.getUpgradeCost(id, targetLevel) : MantissaAmount.zero();
          MantissaAmount maxBalance = levelService.getMaxBalance(id, currentLevel);
          MantissaAmount currentBalance = access.balance();
          BankResponse.BankContext ctx = accessContext(id, access, ownerUuid, null, null, currentLevel, currentLevel, targetLevel, cost, currentBalance, maxBalance);
          return BankResponse.success("Bank level loaded.", ctx, currentLevel);
        })
        .exceptionally(ex -> failure(ex, baseCtx, 1));
  }

  @Override
  public CompletableFuture<BankResponse<Integer>> levelUp(String bankId, UUID ownerUuid, UUID actorUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext baseCtx = BankResponse.context(id, null, ownerUuid, actorUuid, null, null, null, null, null, null, null, null);

    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", baseCtx, 1));
    }
    if (ownerUuid == null || actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner or actor UUID is null.", baseCtx, 1));
    }

    return loadAccess(id, ownerUuid)
        .thenCompose(access -> upgradeOneLevel(access, ownerUuid, actorUuid))
        .exceptionally(ex -> failure(ex, baseCtx, 1));
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
  public CompletableFuture<BankResponse<Boolean>> isAnyBankAccountLockedForPlayer(UUID playerUuid) {
    BankResponse.BankContext baseCtx = BankResponse.context(null, null, playerUuid, null, null, null, null, null, null, null, null, null);
    if (playerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Player UUID is null.", baseCtx, Boolean.FALSE));
    }

    return bankService.isAnyBankAccountLockedForPlayer(playerUuid)
        .thenApply(locked -> BankResponse.success(
            locked ? "Player accounts are locked." : "Player accounts are unlocked.",
            baseCtx,
            locked
        ))
        .exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> lockAllBankAccountsForPlayer(UUID playerUuid, UUID lockedByUuid, String reason) {
    BankResponse.BankContext baseCtx = BankResponse.context(null, null, playerUuid, lockedByUuid, null, null, null, null, null, null, null, null);
    if (playerUuid == null || lockedByUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Player or lock UUID is null.", baseCtx, Boolean.FALSE));
    }

    return isAnyBankAccountLockedForPlayer(playerUuid).thenCompose(lockState -> {
      if (!lockState.isSuccess()) {
        return completed(lockState);
      }

      if (Boolean.TRUE.equals(lockState.payload())) {
        return completed(BankResponse.failure(BankResponse.Status.ALREADY_LOCKED, "Player bank accounts are already locked.", lockState.context(), Boolean.FALSE));
      }

      return bankService.lockAllBankAccountsForPlayer(playerUuid, lockedByUuid, reason).thenApply(changed -> {
        if (Boolean.TRUE.equals(changed)) {
          return BankResponse.success("Player bank accounts locked.", baseCtx, Boolean.TRUE);
        }

        return BankResponse.failure(BankResponse.Status.INTERNAL_ERROR, "Player bank accounts could not be locked.", baseCtx, Boolean.FALSE);
      });
    }).exceptionally(ex -> failure(ex, baseCtx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> unlockAllBankAccountsForPlayer(UUID playerUuid, UUID unlockedByUuid) {
    BankResponse.BankContext baseCtx = BankResponse.context(null, null, playerUuid, unlockedByUuid, null, null, null, null, null, null, null, null);
    if (playerUuid == null || unlockedByUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Player or unlock UUID is null.", baseCtx, Boolean.FALSE));
    }

    return isAnyBankAccountLockedForPlayer(playerUuid).thenCompose(lockState -> {
      if (!lockState.isSuccess()) {
        return completed(lockState);
      }

      if (!Boolean.TRUE.equals(lockState.payload())) {
        return completed(BankResponse.failure(BankResponse.Status.ALREADY_UNLOCKED, "Player bank accounts are already unlocked.", lockState.context(), Boolean.FALSE));
      }

      return bankService.unlockAllBankAccountsForPlayer(playerUuid, unlockedByUuid).thenApply(changed -> {
        if (Boolean.TRUE.equals(changed)) {
          return BankResponse.success("Player bank accounts unlocked.", baseCtx, Boolean.TRUE);
        }

        return BankResponse.failure(BankResponse.Status.INTERNAL_ERROR, "Player bank accounts could not be unlocked.", baseCtx, Boolean.FALSE);
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

    int effectiveLimit = Math.min(limit, 100);

    return loadAccess(id, ownerUuid, true)
        .thenCompose(access -> loadTransactions(id, access, ownerUuid, viewerUuid, effectiveLimit, baseCtx))
        .exceptionally(ex -> {
      Throwable root = rootCause(ex);
      BankResponse.Status status = mapStatus(root);
      return BankResponse.failure(status, messageFor(status, root), baseCtx, List.<BankTransactionEntity>of());
    });
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> createBank(String bankId, UUID ownerUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext ctx = BankResponse.context(id, null, ownerUuid, null, null, null, null, null, null, null, null, null);
    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", ctx, Boolean.FALSE));
    }
    if (ownerUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Owner UUID is null.", ctx, Boolean.FALSE));
    }

    return bankService.createBank(id, ownerUuid)
        .thenApply(account -> {
          if (account == null || account.getId() == null) {
            return BankResponse.failure(BankResponse.Status.BANK_UNAVAILABLE, "Bank account could not be created.", ctx, Boolean.FALSE);
          }

          BankResponse.BankContext resultCtx = BankResponse.context(
              id,
              account.getId(),
              ownerUuid,
              null,
              null,
              null,
              null,
              account.getLevel(),
              null,
              null,
              null,
              null
          );
          return BankResponse.success("Bank account ready.", resultCtx, Boolean.TRUE);
        })
        .exceptionally(ex -> failure(ex, ctx, Boolean.FALSE));
  }

  @Override
  public CompletableFuture<BankResponse<Boolean>> deleteBank(String bankId, UUID actorUuid) {
    String id = normalize(bankId);
    BankResponse.BankContext ctx = BankResponse.context(id, null, null, actorUuid, null, null, null, null, null, null, null, null);
    if (id.isBlank()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Bank id is blank.", ctx, Boolean.FALSE));
    }
    if (actorUuid == null) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Actor UUID is null.", ctx, Boolean.FALSE));
    }

    return bankService.deleteBank(id, actorUuid)
        .thenApply(account -> {
          if (account == null || account.getId() == null) {
            return BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", ctx, Boolean.FALSE);
          }

          BankResponse.BankContext resultCtx = BankResponse.context(
              id,
              account.getId(),
              account.getOwnerUuid(),
              actorUuid,
              null,
              null,
              account.getLevel(),
              null,
              null,
              null,
              null,
              null
          );
          return BankResponse.success("Bank account deleted.", resultCtx, Boolean.TRUE);
        })
        .exceptionally(ex -> failure(ex, ctx, Boolean.FALSE));
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

    return loadAccess(id, ownerUuid).thenCompose(access -> {
      BankAccountEntity acc = access.account();
      int currentLevel = normalizeLevel(acc == null ? null : acc.getLevel());
      int targetLevel = up ? currentLevel + 1 : currentLevel - 1;
      return changeLevel(access, ownerUuid, actorUuid, targetLevel, currentLevel);
    }).exceptionally(ex -> failure(ex, baseCtx, 1));
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

    return loadAccess(id, ownerUuid)
        .thenCompose(access -> {
          BankAccountEntity acc = access.account();
          int currentLevel = normalizeLevel(acc == null ? null : acc.getLevel());
          return changeLevel(access, ownerUuid, actorUuid, targetLevel, currentLevel);
        })
        .exceptionally(ex -> failure(ex, baseCtx, 1));
  }

  private CompletableFuture<BankResponse<Integer>> changeLevel(BankAccess access, UUID ownerUuid, UUID actorUuid, int targetLevel, int currentLevel) {
    String id = access == null ? "" : access.bankId();
    UUID bankAccountId = access == null || access.account() == null ? null : access.account().getId();
    BankResponse.BankContext baseCtx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null, null, currentLevel, currentLevel, targetLevel, null, null, null);

    if (targetLevel < 1) {
      return completed(BankResponse.failure(BankResponse.Status.MIN_LEVEL_REACHED, "Level cannot be lower than 1.", baseCtx, currentLevel));
    }
    if (currentLevel == targetLevel) {
      return completed(BankResponse.failure(BankResponse.Status.ALREADY_AT_LEVEL, "The bank is already at this level.", baseCtx, currentLevel));
    }

    BankDefinition def = access == null ? null : access.definition();
    if (def == null) {
      return completed(BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", baseCtx, currentLevel));
    }

    BankDefinition.LevelDefinition targetDef = findLevel(def, targetLevel);
    int maxLevel = levelService.getMaxLevel(id);
    if (targetLevel > maxLevel) {
      return completed(BankResponse.failure(BankResponse.Status.MAX_LEVEL_REACHED, "The maximum level has been reached.", baseCtx, currentLevel));
    }

    Player actor = actorUuid == null ? null : Bukkit.getPlayer(actorUuid);
    if (targetLevel > currentLevel && targetDef != null && isNotBlank(targetDef.permission())) {
      if (actor == null || !actor.hasPermission(targetDef.permission())) {
        return completed(BankResponse.failure(BankResponse.Status.NO_PERMISSION, "You do not have permission for this level.", baseCtx, currentLevel));
      }
    }

    MantissaAmount cost = targetLevel > currentLevel ? normalizeAmount(levelService.getUpgradeCost(id, targetLevel)) : MantissaAmount.zero();
    MantissaAmount maxBalance = normalizeAmount(levelService.getMaxBalance(id, targetLevel));
    MantissaAmount currentBalance = access.balance();

    return repo.updateBankLevel(bankAccountId, targetLevel).thenApply(updated -> {
      if (!Boolean.TRUE.equals(updated)) {
        return BankResponse.failure(BankResponse.Status.INTERNAL_ERROR, "Failed to update bank level.", baseCtx, currentLevel);
      }

      invalidate(bankAccountId);

      BankResponse.BankContext ctx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null,
          targetDef == null ? null : targetDef.permission(), currentLevel, targetLevel, targetLevel, cost, currentBalance, maxBalance);

      String message = targetLevel > currentLevel ? "Bank level upgraded." : "Bank level downgraded.";
      return BankResponse.success(message, ctx, targetLevel);
    }).exceptionally(ex -> failure(ex, baseCtx, currentLevel));
  }

  private CompletableFuture<BankResponse<Integer>> upgradeOneLevel(BankAccess access, UUID ownerUuid, UUID actorUuid) {
    String id = access == null ? "" : access.bankId();
    BankAccountEntity acc = access == null ? null : access.account();
    UUID bankAccountId = acc == null ? null : acc.getId();
    int currentLevel = normalizeLevel(acc == null ? null : acc.getLevel());
    int targetLevel = currentLevel + 1;
    BankResponse.BankContext baseCtx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null, null, currentLevel, currentLevel, targetLevel, null, null, null);

    if (access == null || access.definition() == null || bankAccountId == null) {
      return completed(BankResponse.failure(BankResponse.Status.BANK_NOT_FOUND, "Bank not found.", baseCtx, currentLevel));
    }

    BankDefinition def = access.definition();
    int maxLevel = levelService.getMaxLevel(id);
    if (targetLevel > maxLevel) {
      return completed(BankResponse.failure(BankResponse.Status.MAX_LEVEL_REACHED, "The maximum level has been reached.", baseCtx, currentLevel));
    }

    Player actor = actorUuid == null ? null : Bukkit.getPlayer(actorUuid);
    if (actor == null || !actor.isOnline()) {
      return completed(BankResponse.failure(BankResponse.Status.INVALID_ARGUMENT, "Player must be online.", baseCtx, currentLevel));
    }

    BankDefinition.LevelDefinition targetDef = findLevel(def, targetLevel);
    if (targetDef != null && isNotBlank(targetDef.permission()) && !actor.hasPermission(targetDef.permission())) {
      return completed(BankResponse.failure(BankResponse.Status.NO_PERMISSION, "You do not have permission for this level.", baseCtx, currentLevel));
    }

    if (!Objects.equals(ownerUuid, actorUuid)) {
      BankDefinition.MemberSystem ms = def.memberSystem();
      if (ms == null || !ms.enabled() || ms.rolesByIdLower() == null) {
        return completed(BankResponse.failure(BankResponse.Status.BANK_UNAVAILABLE, "Member system is disabled.", baseCtx, currentLevel));
      }

      BankMemberEntity actorMember = findMember(access.members(), actorUuid);
      if (actorMember == null) {
        return completed(BankResponse.failure(BankResponse.Status.NOT_MEMBER, "You are not a member of this bank.", baseCtx, currentLevel));
      }

      BankDefinition.RoleDefinition actorRole = ms.rolesByIdLower().get(normalize(actorMember.getRoleIdLower()));
      if (actorRole == null || !actorRole.canUpgrade()) {
        return completed(BankResponse.failure(BankResponse.Status.NO_PERMISSION, "Your role cannot upgrade this bank.", baseCtx, currentLevel));
      }
    }

    var currency = currencies == null ? null : currencies.currency(def.currencyIdLower());
    if (currency == null) {
      return completed(BankResponse.failure(BankResponse.Status.CURRENCY_NOT_CONFIGURED, "Currency is not configured.", baseCtx, currentLevel));
    }

    MantissaAmount cost = normalizeAmount(levelService.getUpgradeCost(id, targetLevel));
    MantissaAmount currentBalance = access.balance();
    MantissaAmount maxBalance = normalizeAmount(levelService.getMaxBalance(id, targetLevel));
    MantissaAmount wallet = resolveWalletBalance(actorUuid, currency.id());

    if (wallet.compareTo(cost) < 0) {
      BankResponse.BankContext ctx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null, null, currentLevel, currentLevel, targetLevel, cost, currentBalance, maxBalance);
      return completed(BankResponse.failure(BankResponse.Status.INSUFFICIENT_FUNDS, "Insufficient funds.", ctx, currentLevel));
    }

    return economy.remove(actor, currency.id(), cost).thenCompose(removed -> {
      if (!Boolean.TRUE.equals(removed)) {
        BankResponse.BankContext ctx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null, null, currentLevel, currentLevel, targetLevel, cost, currentBalance, maxBalance);
        return completed(BankResponse.failure(BankResponse.Status.INSUFFICIENT_FUNDS, "Insufficient funds.", ctx, currentLevel));
      }

      return repo.updateBankLevel(bankAccountId, targetLevel).thenCompose(updated -> {
        if (!Boolean.TRUE.equals(updated)) {
          BankResponse.BankContext ctx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null, null, currentLevel, currentLevel, targetLevel, cost, currentBalance, maxBalance);
          return economy.add(actor, currency.id(), cost).thenApply(refunded -> BankResponse.failure(BankResponse.Status.INTERNAL_ERROR, "Failed to update bank level.", ctx, currentLevel));
        }

        invalidate(bankAccountId);
        if (levelService != null) {
          levelService.invalidate(bankAccountId);
        }

        BankResponse.BankContext ctx = BankResponse.context(id, bankAccountId, ownerUuid, actorUuid, null, null, currentLevel, targetLevel, targetLevel, cost, currentBalance, maxBalance);
        return completed(BankResponse.success("Bank level upgraded.", ctx, targetLevel));
      }).exceptionallyCompose(ex -> economy.add(actor, currency.id(), cost).thenCompose(refunded -> CompletableFuture.failedFuture(ex)));
    }).exceptionally(ex -> failure(ex, baseCtx, currentLevel));
  }

  private MantissaAmount resolveWalletBalance(UUID playerUuid, String currencyId) {
    if (playerUuid == null || currencyId == null || currencyId.isBlank() || economyCache == null) {
      return MantissaAmount.zero();
    }

    EconomyPlayer player = economyCache.getOnline(playerUuid);
    if (player == null) {
      return MantissaAmount.zero();
    }

    EconomyPlayer.BalanceEntry entry = player.entry(currencyId);
    return entry == null || entry.amount() == null ? MantissaAmount.zero() : MantissaAmount.normalize(entry.amount());
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

  private CompletableFuture<BankAccess> loadAccess(String bankId, UUID ownerUuid) {
    return loadAccess(bankId, ownerUuid, false);
  }

  private CompletableFuture<BankAccess> loadAccess(String bankId, UUID ownerUuid, boolean ignoreLockChecks) {
    String id = normalize(bankId);
    if (id.isBlank()) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    }
    if (ownerUuid == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    }

    return bankService.bank(id).thenCompose(defOpt -> {
      BankDefinition def = defOpt.orElse(null);
      if (def == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));
      }
      if (!def.enabled()) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank disabled"));
      }

      if (ignoreLockChecks) {
        if (cache == null) {
          return CompletableFuture.failedFuture(new IllegalStateException("BankAccountCacheService not available"));
        }

        return cache.loadOrCreate(id, ownerUuid).thenApply(view -> new BankAccess(id, def, view));
      }

      return requireOwnerNotLocked(ownerUuid)
          .thenCompose(v -> requireUnlockedIfNeeded(id, ownerUuid, def))
          .thenCompose(v -> {
            if (cache == null) {
              return CompletableFuture.failedFuture(new IllegalStateException("BankAccountCacheService not available"));
            }
            return cache.loadOrCreate(id, ownerUuid).thenApply(view -> new BankAccess(id, def, view));
          });
    });
  }

  private CompletableFuture<BankResponse<List<BankTransactionEntity>>> loadTransactions(
      String bankId,
      BankAccess access,
      UUID ownerUuid,
      UUID viewerUuid,
      int effectiveLimit,
      BankResponse.BankContext baseCtx
  ) {
    if (access == null || access.accountId() == null) {
      return CompletableFuture.completedFuture(BankResponse.failure(BankResponse.Status.BANK_UNAVAILABLE, "Bank account is unavailable.", baseCtx, List.<BankTransactionEntity>of()));
    }

    if (Objects.equals(ownerUuid, viewerUuid)) {
      return repo.listRecentTransactions(access.accountId(), effectiveLimit).thenApply(list -> {
        BankResponse.BankContext ctx = accessContext(bankId, access, ownerUuid, viewerUuid, null, null, null, null, null, null, null);
        return BankResponse.success("Transactions loaded.", ctx, list == null ? List.<BankTransactionEntity>of() : list);
      });
    }

    return loadAccess(bankId, ownerUuid, true).thenCompose(viewAccess -> {
      BankDefinition.MemberSystem ms = viewAccess.definition() == null ? null : viewAccess.definition().memberSystem();
      if (ms == null || !ms.enabled() || ms.rolesByIdLower() == null) {
        return completed(BankResponse.failure(BankResponse.Status.BANK_UNAVAILABLE, "Member system is disabled.", baseCtx, List.<BankTransactionEntity>of()));
      }

      BankMemberEntity member = findMember(viewAccess.members(), viewerUuid);
      if (member == null) {
        return completed(BankResponse.failure(BankResponse.Status.NOT_MEMBER, "You are not a member of this bank.", baseCtx, List.<BankTransactionEntity>of()));
      }

      BankDefinition.RoleDefinition role = ms.rolesByIdLower().get(normalize(member.getRoleIdLower()));
      if (role == null || !role.canViewLog()) {
        return completed(BankResponse.failure(BankResponse.Status.NO_PERMISSION, "You do not have permission.", baseCtx, List.<BankTransactionEntity>of()));
      }

      return repo.listRecentTransactions(viewAccess.accountId(), effectiveLimit).thenApply(list -> {
        BankResponse.BankContext ctx = accessContext(bankId, viewAccess, ownerUuid, viewerUuid, null, null, null, null, null, null, null);
        return BankResponse.success("Transactions loaded.", ctx, list == null ? List.<BankTransactionEntity>of() : list);
      });
    });
  }

  private CompletableFuture<Void> requireOwnerNotLocked(UUID ownerUuid) {
    if (ownerUuid == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    }

    return repo.isPlayerLocked(ownerUuid).thenCompose(locked -> {
      if (Boolean.TRUE.equals(locked)) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank accounts locked"));
      }
      return CompletableFuture.completedFuture(null);
    });
  }

  private CompletableFuture<Void> requireActorNotLocked(UUID actorUuid) {
    if (actorUuid == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("actorUuid is null"));
    }

    return repo.isPlayerLocked(actorUuid).thenCompose(locked -> {
      if (Boolean.TRUE.equals(locked)) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank accounts locked"));
      }
      return CompletableFuture.completedFuture(null);
    });
  }

  private CompletableFuture<Void> requireUnlockedIfNeeded(String bankIdLower, UUID ownerUuid, BankDefinition def) {
    if (def == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));
    }
    if (def.unlockedByDefault()) {
      return CompletableFuture.completedFuture(null);
    }

    return repo.isUnlocked(bankIdLower, ownerUuid).thenCompose(unlocked -> {
      if (Boolean.TRUE.equals(unlocked)) {
        return CompletableFuture.completedFuture(null);
      }
      return CompletableFuture.failedFuture(new IllegalStateException("bank locked"));
    });
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

  private static InviteLookupRow selectPreferredInvite(List<InviteLookupRow> rows, boolean preferActive) {
    InviteLookupRow selected = null;
    if (rows == null || rows.isEmpty()) {
      return null;
    }

    for (InviteLookupRow row : rows) {
      if (row == null) {
        continue;
      }
      if (selected == null) {
        selected = row;
        continue;
      }

      int cmp = compareInvites(row, selected, preferActive);
      if (cmp < 0) {
        selected = row;
      }
    }

    return selected;
  }

  private static int compareInvites(InviteLookupRow left, InviteLookupRow right, boolean preferActive) {
    if (left == right) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }

    if (preferActive) {
      int activeCmp = Boolean.compare(isExpired(left), isExpired(right));
      if (activeCmp != 0) {
        return activeCmp;
      }
    }

    int expiryCmp = compareExpiry(left.expiresAt(), right.expiresAt());
    if (expiryCmp != 0) {
      return expiryCmp;
    }

    int bankCmp = normalize(left.bankIdLower()).compareTo(normalize(right.bankIdLower()));
    if (bankCmp != 0) {
      return bankCmp;
    }

    UUID leftAccount = left.bankAccountId();
    UUID rightAccount = right.bankAccountId();
    if (leftAccount == null && rightAccount == null) {
      return 0;
    }
    if (leftAccount == null) {
      return 1;
    }
    if (rightAccount == null) {
      return -1;
    }
    return leftAccount.compareTo(rightAccount);
  }

  private static int compareExpiry(java.time.Instant left, java.time.Instant right) {
    if (left == null && right == null) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }
    return left.compareTo(right);
  }

  private static boolean isExpired(InviteLookupRow row) {
    return row != null && row.expiresAt() != null && row.expiresAt().isBefore(java.time.Instant.now());
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

  private static BankResponse.BankContext accessContext(
      String bankId,
      BankAccess access,
      UUID ownerUuid,
      UUID actorUuid,
      UUID targetUuid,
      Integer previousLevel,
      Integer currentLevel,
      Integer targetLevel,
      MantissaAmount amount,
      MantissaAmount balance,
      MantissaAmount limit
  ) {
    return context(
        bankId,
        access == null ? null : access.account(),
        ownerUuid,
        actorUuid,
        targetUuid,
        null,
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

  private record BankAccess(String bankId, BankDefinition definition, BankAccountEntity account, List<BankMemberEntity> members, MantissaAmount balance) {
    private BankAccess(String bankId, BankDefinition definition, BankAccountCacheService.View view) {
      this(
          bankId,
          definition,
          view == null ? null : view.account(),
          view == null ? null : view.members(),
          view == null ? null : view.balance()
      );
    }

    private BankAccess {
      members = members == null ? List.of() : List.copyOf(members);
      balance = balance == null ? MantissaAmount.zero() : balance;
    }

    private UUID accountId() {
      return account == null ? null : account.getId();
    }
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
    return current;
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





