package io.nexstudios.nexeconomy.service.bank;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountPresenceService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexeconomy.service.bank.sync.BankRedisSyncService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.EconomyService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.*;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    BankRegistryService.class,
    BankRepositoryService.class,
    BankRedisSyncService.class,
    BankAccountCacheService.class,
    BankAccountPresenceService.class,
    CurrencyRegistryService.class,
    EconomyService.class,
    EconomyPlayerCacheService.class
})
public final class DefaultBankService implements BankService, Service {

  private final BankRegistryService banks;
  private final BankRepositoryService repo;
  private final BankRedisSyncService redisSync;
  private final BankAccountCacheService cache;
  private final BankAccountPresenceService presence;
  private final CurrencyRegistryService currencies;
  private final EconomyService economy;

  private static final String ERR_INVITEE_ALREADY_IN_ANOTHER_BANK = "invitee_already_in_another_bank";
  private static final String ERR_INVITEE_ALREADY_MEMBER_SOMEWHERE = "invitee_already_member_somewhere";
  private static final String ERR_OWNER_CANNOT_LEAVE = "owner_cannot_leave";

  public DefaultBankService(ServiceAccessor accessor) {
    this.banks = accessor.getService(BankRegistryService.class);
    this.repo = accessor.getService(BankRepositoryService.class);
    this.redisSync = accessor.getService(BankRedisSyncService.class);
    this.cache = accessor.getService(BankAccountCacheService.class);
    this.presence = accessor.getService(BankAccountPresenceService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.economy = accessor.getService(EconomyService.class);
  }

  @Override
  public CompletableFuture<Optional<BankDefinition>> bank(String bankId) {
    return CompletableFuture.completedFuture(banks.bank(normalizeId(bankId)));
  }

  @Override
  public CompletableFuture<MantissaAmount> balanceVisibleTo(String bankId, UUID ownerUuid, UUID viewerUuid) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (viewerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("viewerUuid is null"));

    // Owner can always view own bank via this path too
    if (ownerUuid.equals(viewerUuid)) {
      return balance(bankIdLower, ownerUuid);
    }

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    BankDefinition.MemberSystem ms = def.memberSystem();
    if (ms == null || !ms.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));

    return cache.loadOrCreate(bankIdLower, ownerUuid).thenApply(view -> {
      if (view == null || view.account() == null) {
        throw new IllegalStateException("bank not available");
      }

      if (!isMember(view.members(), viewerUuid)) {
        throw new IllegalStateException("not a member");
      }

      return view.balance() == null ? MantissaAmount.zero() : view.balance();
    });
  }

  @Override
  public CompletableFuture<List<BankMemberEntity>> membersVisibleTo(String bankId, UUID ownerUuid, UUID viewerUuid) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (viewerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("viewerUuid is null"));

    // Owner can always view own members list via this path too
    if (ownerUuid.equals(viewerUuid)) {
      return members(bankIdLower, ownerUuid);
    }

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    BankDefinition.MemberSystem ms = def.memberSystem();
    if (ms == null || !ms.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));

    return cache.loadOrCreate(bankIdLower, ownerUuid).thenApply(view -> {
      if (view == null || view.account() == null) {
        throw new IllegalStateException("bank not available");
      }

      if (!isMember(view.members(), viewerUuid)) {
        throw new IllegalStateException("not a member");
      }

      return view.members();
    });
  }

  private CompletableFuture<BankAccountEntity> getOrCreateAccountCached(String bankIdLower, UUID ownerUuid) {
    String bank = normalizeId(bankIdLower);
    if (bank.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));

    Optional<BankDefinition> defOpt = banks.bank(bank);
    if (defOpt.isEmpty() || !defOpt.get().enabled()) {
      return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));
    }

    return cache.loadOrCreate(bank, ownerUuid).thenApply(view -> {
      if (view == null || view.account() == null) {
        throw new IllegalStateException("bank not available");
      }
      return view.account();
    });
  }

  @Override
  public CompletableFuture<BankAccountEntity> getOrCreateAccount(String bankId, UUID ownerUuid) {
    return getOrCreateAccountCached(bankId, ownerUuid);
  }

  @Override
  public CompletableFuture<List<BankMemberEntity>> members(String bankId, UUID ownerUuid) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    // Fast-path: RAM cache hit (may trigger background refresh via TTL)
    BankAccountCacheService.View cached = cache == null ? null : cache.get(bankIdLower, ownerUuid);
    if (cached != null && cached.account() != null) {
      List<BankMemberEntity> mem = cached.members();
      return CompletableFuture.completedFuture(mem == null ? List.of() : mem);
    }

    if (cache == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("BankAccountCacheService not available"));
    }

    // Slow-path: load (single-flight) + cache
    return cache.loadOrCreate(bankIdLower, ownerUuid).thenApply(view -> {
      List<BankMemberEntity> mem = view == null ? null : view.members();
      return mem == null ? List.of() : mem;
    });
  }

  @Override
  public CompletableFuture<BankInviteEntity> invite(
      String bankId,
      UUID ownerUuid,
      UUID actorUuid,
      UUID inviteeUuid,
      String roleId
  ) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (actorUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("actorUuid is null"));
    if (inviteeUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("inviteeUuid is null"));

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    BankDefinition.MemberSystem ms = def.memberSystem();
    if (ms == null || !ms.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));

    String roleLower = normalizeId(roleId);
    if (roleLower.isBlank()) roleLower = "member";

    BankDefinition.RoleDefinition roleDef = ms.rolesByIdLower() == null ? null : ms.rolesByIdLower().get(roleLower);
    if (roleDef == null) return CompletableFuture.failedFuture(new IllegalArgumentException("unknown role"));

    if (inviteeUuid.equals(ownerUuid)) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("cannot invite owner"));
    }

    String finalRoleLower = roleLower;

    return cache.loadOrCreate(bankIdLower, ownerUuid).thenCompose(view -> {
      if (view == null || view.account() == null || view.account().getId() == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));
      }

      UUID bankAccountId = view.account().getId();

      BankMemberEntity actorMember = findMemberInList(view.members(), actorUuid);
      if (actorMember == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("not a member"));
      }

      BankDefinition.RoleDefinition actorRole = ms.rolesByIdLower().get(normalizeId(actorMember.getRoleIdLower()));

      if (actorRole == null || !actorRole.canInvite()) {
        return CompletableFuture.failedFuture(new IllegalStateException("no permission"));
      }

      if (findMemberInList(view.members(), inviteeUuid) != null) {
        return CompletableFuture.failedFuture(new IllegalStateException("already a member"));
      }

      int maxMembers = Math.max(0, ms.maxMembers());
      int currentMembers = view.members().size();
      if (maxMembers > 0 && currentMembers >= maxMembers) {
        return CompletableFuture.failedFuture(new IllegalStateException("member limit reached"));
      }

      return repo.isMemberOfAnyOtherAccount(inviteeUuid, inviteeUuid).thenCompose(inOther -> {
        if (Boolean.TRUE.equals(inOther)) {
          return CompletableFuture.failedFuture(new IllegalStateException(ERR_INVITEE_ALREADY_IN_ANOTHER_BANK));
        }

        return repo.upsertInvite(bankAccountId, inviteeUuid, actorUuid, finalRoleLower).thenApply(inv -> {
          cache.invalidate(bankAccountId);
          if (redisSync != null) redisSync.publishInvalidateAccount(bankAccountId);
          return inv;
        });
      });
    });
  }

  @Override
  public CompletableFuture<Boolean> acceptInvite(String bankId, UUID ownerUuid, UUID inviteeUuid) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (inviteeUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("inviteeUuid is null"));

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    BankDefinition.MemberSystem ms = def.memberSystem();
    if (ms == null || !ms.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));

    return cache.loadOrCreate(bankIdLower, ownerUuid).thenCompose(view -> {
      if (view == null || view.account() == null || view.account().getId() == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));
      }

      UUID bankAccountId = view.account().getId();

      return repo.findInvite(bankAccountId, inviteeUuid).thenCompose(invOpt -> {
        BankInviteEntity inv = invOpt.orElse(null);
        if (inv == null) return CompletableFuture.completedFuture(false);

        Instant expiresAt = inv.getExpiresAt();
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
          return repo.deleteInvite(bankAccountId, inviteeUuid).thenApply(ignored -> {
            cache.invalidate(bankAccountId);
            if (redisSync != null) redisSync.publishInvalidateAccount(bankAccountId);
            return false;
          });
        }

        int maxMembers = Math.max(0, ms.maxMembers());
        int currentMembers = view.members() == null ? 0 : view.members().size();
        if (maxMembers > 0 && currentMembers >= maxMembers) {
          return CompletableFuture.failedFuture(new IllegalStateException("member limit reached"));
        }

        String roleLower = normalizeId(inv.getRoleIdLower());
        if (roleLower.isBlank()) roleLower = "member";

        if (ms.rolesByIdLower() == null || !ms.rolesByIdLower().containsKey(roleLower)) {
          roleLower = "member";
        }

        String finalRoleLower = roleLower;

        return repo.isMemberOfAnyOtherAccount(inviteeUuid, inviteeUuid).thenCompose(inOther -> {
          if (Boolean.TRUE.equals(inOther)) {
            return CompletableFuture.failedFuture(new IllegalStateException(ERR_INVITEE_ALREADY_MEMBER_SOMEWHERE));
          }

          return repo.upsertMember(bankAccountId, inviteeUuid, inv.getInvitedByUuid(), finalRoleLower)
              .thenCompose(member -> repo.deleteInvite(bankAccountId, inviteeUuid))
              .thenApply(deleted -> {
                cache.invalidate(bankAccountId);
                if (redisSync != null) redisSync.publishInvalidateAccount(bankAccountId);

                if (presence != null) {
                  presence.onMemberAdded(bankAccountId, bankIdLower, ownerUuid, inviteeUuid);
                }
                return true;
              });
        });
      });
    });
  }

  @Override
  public CompletableFuture<MantissaAmount> balance(String bankId, UUID ownerUuid) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    // Fast-path: RAM cache hit (may trigger background refresh via TTL)
    BankAccountCacheService.View cached = cache == null ? null : cache.get(bankIdLower, ownerUuid);
    if (cached != null && cached.account() != null) {
      MantissaAmount bal = cached.balance();
      return CompletableFuture.completedFuture(bal == null ? MantissaAmount.zero() : bal);
    }

    if (cache == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("BankAccountCacheService not available"));
    }

    // Slow-path: load (single-flight) + cache
    return cache.loadOrCreate(bankIdLower, ownerUuid).thenApply(view -> {
      MantissaAmount bal = view == null ? null : view.balance();
      return bal == null ? MantissaAmount.zero() : bal;
    });
  }

  @Override
  public CompletableFuture<MantissaAmount> deposit(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (actorUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("actorUuid is null"));

    MantissaAmount requested = amount == null ? MantissaAmount.zero() : MantissaAmount.normalize(amount);
    if (requested.isNegative() || requested.compareTo(MantissaAmount.zero()) == 0) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("invalid amount"));
    }

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    CurrencyDefinition currency = currencies.currency(def.currencyIdLower());
    if (currency == null) return CompletableFuture.failedFuture(new IllegalStateException("bank currency not found"));

    Player actor = Bukkit.getPlayer(actorUuid);
    if (actor == null || !actor.isOnline()) return CompletableFuture.failedFuture(new IllegalStateException("player must be online"));

    MantissaAmount delta = normalizeForCurrency(currency, requested);

    return getOrCreateAccount(bankIdLower, ownerUuid).thenCompose(acc ->
        requireRoleCached(def, bankIdLower, ownerUuid, actorUuid).thenCompose(role -> {
          if (!role.canDeposit()) return CompletableFuture.failedFuture(new IllegalStateException("no permission"));

          return enforceLevelCap(def, currency, acc, delta).thenCompose(allowed -> {
            if (allowed.compareTo(MantissaAmount.zero()) == 0) {
              return CompletableFuture.failedFuture(new IllegalStateException("max balance reached"));
            }

            return economy.remove(actor, currency.id(), allowed).thenCompose(ok -> {
              if (!ok) return CompletableFuture.failedFuture(new IllegalStateException("insufficient funds"));

              return repo.applyBalanceDelta(acc.getId(), allowed).thenCompose(nextBalance -> {
                return repo.appendTransaction(
                    acc.getId(),
                    BankTransactionEntity.Type.DEPOSIT,
                    actorUuid,
                    ownerUuid,
                    allowed,
                    null
                ).thenApply(ignoredTx -> {
                  if (cache != null) cache.invalidate(acc.getId());
                  if (redisSync != null) redisSync.publishInvalidateAccount(acc.getId());
                  return allowed;
                });
              }).exceptionallyCompose(ex -> {
                return economy.add(actor, currency.id(), allowed).thenCompose(refundOk ->
                    CompletableFuture.failedFuture(ex)
                );
              });
            });
          });
        })
    );
  }

  @Override
  public CompletableFuture<MantissaAmount> withdraw(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (actorUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("actorUuid is null"));

    MantissaAmount requested = amount == null ? MantissaAmount.zero() : MantissaAmount.normalize(amount);
    if (requested.isNegative() || requested.compareTo(MantissaAmount.zero()) == 0) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("invalid amount"));
    }

    BankDefinition def = banks.bank(bankIdLower).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    BankDefinition.MemberSystem ms = def.memberSystem();
    if (ms == null || !ms.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));

    CurrencyDefinition currency = currencies.currency(def.currencyIdLower());
    if (currency == null) return CompletableFuture.failedFuture(new IllegalStateException("bank currency not found"));

    Player actor = Bukkit.getPlayer(actorUuid);
    if (actor == null || !actor.isOnline()) return CompletableFuture.failedFuture(new IllegalStateException("player must be online"));

    MantissaAmount delta = normalizeForCurrency(currency, requested);

    return getOrCreateAccount(bankIdLower, ownerUuid).thenCompose(acc ->
        requireRoleCached(def, bankIdLower, ownerUuid, actorUuid).thenCompose(role -> {
          BankDefinition.WithdrawDefinition wd = role.withdraw();
          boolean canWithdraw = wd != null && wd.canWithdraw();
          if (!canWithdraw) return CompletableFuture.failedFuture(new IllegalStateException("no permission"));

          return repo.loadBalance(acc.getId()).thenCompose(currentBal -> {
            MantissaAmount bankBalance = currentBal == null ? MantissaAmount.zero() : currentBal;
            if (bankBalance.compareTo(MantissaAmount.zero()) <= 0) {
              return CompletableFuture.failedFuture(new IllegalStateException("bank empty"));
            }

            MantissaAmount cappedByBalance = min(delta, bankBalance);

            ZoneId zone = resolveBankZone(def);
            long hourStart = windowStartEpoch(zone, BankWithdrawUsageEntity.WindowType.HOURLY);
            long dayStart = windowStartEpoch(zone, BankWithdrawUsageEntity.WindowType.DAILY);

            MantissaAmount dailyLimit = parseLimit(currency, wd.dailyLimitRaw());
            MantissaAmount hourlyLimit = parseLimit(currency, wd.hourlyLimitRaw());

            return computeAllowedByLimits(acc.getId(), actorUuid, cappedByBalance, dailyLimit, hourlyLimit, dayStart, hourStart)
                .thenCompose(allowed -> {
                  if (allowed.compareTo(MantissaAmount.zero()) == 0) {
                    return CompletableFuture.failedFuture(new IllegalStateException("limit reached"));
                  }

                  return repo.applyBalanceDelta(acc.getId(), negate(allowed)).thenCompose(nextBank -> {
                    return economy.add(actor, currency.id(), allowed).thenCompose(ok -> {
                      if (!ok) {
                        return repo.applyBalanceDelta(acc.getId(), allowed).thenCompose(refund ->
                            CompletableFuture.failedFuture(new IllegalStateException("wallet add failed"))
                        );
                      }

                      CompletableFuture<?> usageF = applyUsageIfNeeded(acc.getId(), actorUuid, allowed, dailyLimit, hourlyLimit, dayStart, hourStart);
                      CompletableFuture<?> txF = repo.appendTransaction(
                          acc.getId(),
                          BankTransactionEntity.Type.WITHDRAW,
                          actorUuid,
                          ownerUuid,
                          allowed,
                          null
                      );

                      return CompletableFuture.allOf(usageF, txF).thenApply(v -> {
                        if (cache != null) cache.invalidate(acc.getId());
                        if (redisSync != null) redisSync.publishInvalidateAccount(acc.getId());
                        return allowed;
                      });
                    });
                  });
                });
          });
        })
    );
  }

  @Override
  public CompletableFuture<Boolean> leave(String bankId, UUID ownerUuid, UUID memberUuid) {
    String bankIdLower = normalizeId(bankId);
    if (bankIdLower.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (memberUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("memberUuid is null"));

    if (ownerUuid.equals(memberUuid)) {
      return CompletableFuture.failedFuture(new IllegalStateException(ERR_OWNER_CANNOT_LEAVE));
    }

    return getOrCreateAccount(bankIdLower, ownerUuid).thenCompose(acc ->
        repo.deleteMember(acc.getId(), memberUuid).thenCompose(deleted ->
                repo.deleteInvite(acc.getId(), memberUuid)
                    .exceptionally(ignored -> false)
                    .thenApply(x -> deleted))
            .thenApply(deleted -> {
              if (Boolean.TRUE.equals(deleted)) {
                if (cache != null) cache.invalidate(acc.getId());
                if (redisSync != null) redisSync.publishInvalidateAccount(acc.getId());

                if (presence != null) {
                  presence.onMemberRemoved(acc.getId(), memberUuid);
                }
              }
              return Boolean.TRUE.equals(deleted);
            })
    );
  }

  private CompletableFuture<BankDefinition.RoleDefinition> requireRole(BankDefinition def, UUID bankAccountId, UUID actorUuid) {
    BankDefinition.MemberSystem ms = def == null ? null : def.memberSystem();
    if (ms == null || ms.rolesByIdLower() == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));
    }

    return repo.findMember(bankAccountId, actorUuid).thenCompose(memOpt -> {
      BankMemberEntity mem = memOpt.orElse(null);
      if (mem == null) return CompletableFuture.failedFuture(new IllegalStateException("not a member"));

      String roleId = normalizeId(mem.getRoleIdLower());
      BankDefinition.RoleDefinition role = ms.rolesByIdLower().get(roleId);
      if (role == null) return CompletableFuture.failedFuture(new IllegalStateException("role not found"));
      return CompletableFuture.completedFuture(role);
    });
  }

  private CompletableFuture<MantissaAmount> enforceLevelCap(BankDefinition def, CurrencyDefinition currency, BankAccountEntity acc, MantissaAmount requestedDelta) {
    if (def == null || acc == null) return CompletableFuture.completedFuture(requestedDelta);

    MantissaAmount max = resolveLevelMaxBalance(def, currency, acc.getLevel());
    if (max == null) return CompletableFuture.completedFuture(requestedDelta); // unlimited

    return repo.loadBalance(acc.getId()).thenApply(current -> {
      MantissaAmount cur = current == null ? MantissaAmount.zero() : current;

      MantissaAmount remaining = max.subtract(cur);
      if (remaining.compareTo(MantissaAmount.zero()) <= 0) return MantissaAmount.zero();

      return min(requestedDelta, remaining);
    });
  }

  private MantissaAmount resolveLevelMaxBalance(BankDefinition def, CurrencyDefinition currency, int level) {
    if (def == null) return null;

    // Level 1 default cap (from "default-max-balance")
    MantissaAmount defaultCap = null;
    String rawDefault = def.defaultMaxBalanceRaw();
    if (rawDefault != null) {
      MantissaAmount parsed = parseLimit(currency, rawDefault);
      BigDecimal human = parsed == null ? BigDecimal.valueOf(-1) : parsed.toHuman();
      if (human.compareTo(BigDecimal.valueOf(-1)) != 0) {
        defaultCap = parsed;
      }
    }

    // No level-system configured => default cap applies (or unlimited if default is -1)
    if (def.levels() == null || def.levels().isEmpty()) return defaultCap;

    BankDefinition.LevelDefinition best = null;
    for (BankDefinition.LevelDefinition l : def.levels()) {
      if (l == null) continue;
      if (l.level() <= level) {
        if (best == null || l.level() > best.level()) best = l;
      }
    }

    // If no matching level entry, use default cap (Level 1 behavior)
    if (best == null) return defaultCap;

    String raw = best.maxBalanceRaw();
    MantissaAmount parsed = parseLimit(currency, raw);
    if (parsed == null) return defaultCap;

    BigDecimal human = parsed.toHuman();
    if (human.compareTo(BigDecimal.valueOf(-1)) == 0) return null; // explicit unlimited at that level
    return parsed;
  }

  private CompletableFuture<MantissaAmount> computeAllowedByLimits(
      UUID bankAccountId,
      UUID memberUuid,
      MantissaAmount allowed,
      MantissaAmount dailyLimit,
      MantissaAmount hourlyLimit,
      long dayStartEpoch,
      long hourStartEpoch
  ) {

    boolean dailyUnlimited = isUnlimited(dailyLimit);
    boolean hourlyUnlimited = isUnlimited(hourlyLimit);

    CompletableFuture<MantissaAmount> f = CompletableFuture.completedFuture(allowed);

    if (!hourlyUnlimited) {
      f = f.thenCompose(curAllowed ->
          estimateRemaining(bankAccountId, memberUuid, BankWithdrawUsageEntity.WindowType.HOURLY, hourStartEpoch, hourlyLimit)
              .thenApply(rem -> min(curAllowed, rem))
      );
    }

    if (!dailyUnlimited) {
      f = f.thenCompose(curAllowed ->
          estimateRemaining(bankAccountId, memberUuid, BankWithdrawUsageEntity.WindowType.DAILY, dayStartEpoch, dailyLimit)
              .thenApply(rem -> min(curAllowed, rem))
      );
    }

    return f;
  }

  private CompletableFuture<MantissaAmount> estimateRemaining(
      UUID bankAccountId,
      UUID memberUuid,
      BankWithdrawUsageEntity.WindowType type,
      long windowStartEpoch,
      MantissaAmount limit
  ) {
    if (isUnlimited(limit)) return CompletableFuture.completedFuture(MantissaAmount.of(BigDecimal.valueOf(Long.MAX_VALUE), 0));

    return repo.addWithdrawUsage(bankAccountId, memberUuid, type, windowStartEpoch, MantissaAmount.zero())
        .thenApply(used -> {
          MantissaAmount u = used == null ? MantissaAmount.zero() : used;
          MantissaAmount remaining = limit.subtract(u);
          return remaining.compareTo(MantissaAmount.zero()) <= 0 ? MantissaAmount.zero() : remaining;
        });
  }

  private CompletableFuture<Void> applyUsageIfNeeded(
      UUID bankAccountId,
      UUID memberUuid,
      MantissaAmount delta,
      MantissaAmount dailyLimit,
      MantissaAmount hourlyLimit,
      long dayStartEpoch,
      long hourStartEpoch
  ) {
    CompletableFuture<Void> f = CompletableFuture.completedFuture(null);

    if (!isUnlimited(hourlyLimit)) {
      f = f.thenCompose(v -> repo.addWithdrawUsage(bankAccountId, memberUuid, BankWithdrawUsageEntity.WindowType.HOURLY, hourStartEpoch, delta).thenApply(x -> null));
    }
    if (!isUnlimited(dailyLimit)) {
      f = f.thenCompose(v -> repo.addWithdrawUsage(bankAccountId, memberUuid, BankWithdrawUsageEntity.WindowType.DAILY, dayStartEpoch, delta).thenApply(x -> null));
    }

    return f;
  }

  @Override
  public CompletableFuture<List<InviteLookupRow>> invites(UUID inviteeUuid) {
    if (inviteeUuid == null) return CompletableFuture.completedFuture(List.of());
    return repo.findInvitesForInvitee(inviteeUuid);
  }

  private static MantissaAmount parseLimit(CurrencyDefinition currency, String raw) {
    if (raw == null) return MantissaAmount.zero();
    String s = raw.trim();
    if (s.isBlank()) return MantissaAmount.zero();
    if ("-1".equals(s)) return MantissaAmount.of(BigDecimal.valueOf(-1), 0);

    if (currency != null && currency.type() == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(s);
      if (human == null) return MantissaAmount.zero();
      return MantissaAmount.of(human, 0);
    }

    MantissaAmount m = AmountNotation.parseVirtualMantissaAmount(s);
    return m == null ? MantissaAmount.zero() : m;
  }

  private static boolean isUnlimited(MantissaAmount limit) {
    if (limit == null) return true;
    return limit.toHuman().compareTo(BigDecimal.valueOf(-1)) == 0;
  }

  private static MantissaAmount normalizeForCurrency(CurrencyDefinition currency, MantissaAmount a) {
    if (a == null) return MantissaAmount.zero();
    MantissaAmount n = MantissaAmount.normalize(a);

    if (currency != null && currency.type() == CurrencyType.VAULT) {
      BigDecimal human = n.toHuman();
      int fd = currency.fractionDigits();
      if (fd < 0) fd = 0;
      if (fd > 8) fd = 8;
      human = human.setScale(fd, java.math.RoundingMode.DOWN);
      return MantissaAmount.of(human, 0);
    }
    return n;
  }

  private static MantissaAmount min(MantissaAmount a, MantissaAmount b) {
    MantissaAmount x = a == null ? MantissaAmount.zero() : a;
    MantissaAmount y = b == null ? MantissaAmount.zero() : b;
    return x.compareTo(y) <= 0 ? x : y;
  }

  private static MantissaAmount negate(MantissaAmount a) {
    MantissaAmount n = a == null ? MantissaAmount.zero() : MantissaAmount.normalize(a);
    return MantissaAmount.of(n.toHuman().negate(), 0);
  }

  private static ZoneId resolveBankZone(BankDefinition def) {
    try {
      String tz = def == null || def.interestSystem() == null ? null : def.interestSystem().timezone();
      if (tz == null || tz.isBlank()) return ZoneId.of("UTC");
      return ZoneId.of(tz.trim());
    } catch (Exception ignored) {
      return ZoneId.of("UTC");
    }
  }

  private static long windowStartEpoch(ZoneId zone, BankWithdrawUsageEntity.WindowType type) {
    ZonedDateTime now = ZonedDateTime.now(zone == null ? ZoneId.of("UTC") : zone);

    if (type == BankWithdrawUsageEntity.WindowType.HOURLY) {
      ZonedDateTime start = now.withMinute(0).withSecond(0).withNano(0);
      return start.toEpochSecond();
    }

    ZonedDateTime start = now.toLocalDate().atStartOfDay(zone == null ? ZoneId.of("UTC") : zone);
    return start.toEpochSecond();
  }

  @Override
  public CompletableFuture<Boolean> acceptInviteFromOwner(UUID ownerUuid, UUID inviteeUuid) {
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (inviteeUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("inviteeUuid is null"));

    return repo.findInvitesForInviteeFromOwner(inviteeUuid, ownerUuid).thenCompose(list -> {
      if (list == null || list.isEmpty()) return CompletableFuture.completedFuture(false);

      InviteLookupRow row = list.getFirst();
      if (row.expiresAt() != null && row.expiresAt().isBefore(Instant.now())) {
        return repo.deleteInvite(row.bankAccountId(), inviteeUuid).thenApply(ignored -> false);
      }

      return acceptInvite(row.bankIdLower(), ownerUuid, inviteeUuid);
    });
  }

  @Override
  public CompletableFuture<Boolean> denyInviteFromOwner(UUID ownerUuid, UUID inviteeUuid) {
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (inviteeUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("inviteeUuid is null"));

    return repo.findInvitesForInviteeFromOwner(inviteeUuid, ownerUuid).thenCompose(list -> {
      if (list == null || list.isEmpty()) return CompletableFuture.completedFuture(false);

      InviteLookupRow row = list.getFirst();
      return repo.deleteInvite(row.bankAccountId(), inviteeUuid);
    });
  }

  @Override
  public void ensureMissingUnlockedBanksForAllOnline() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (p == null || !p.isOnline()) continue;

      UUID uuid = p.getUniqueId();

      for (BankDefinition def : banks.banks()) {
        if (def == null) continue;
        if (!def.enabled()) continue;
        if (!def.unlockedByDefault()) continue;

        getOrCreateAccount(def.idLower(), uuid);
      }
    }
  }

  private CompletableFuture<BankDefinition.RoleDefinition> requireRoleCached(
      BankDefinition def,
      String bankIdLower,
      UUID ownerUuid,
      UUID actorUuid
  ) {
    BankDefinition.MemberSystem ms = def == null ? null : def.memberSystem();
    if (ms == null || ms.rolesByIdLower() == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));
    }
    if (actorUuid == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("actorUuid is null"));
    }

    // Prefer cached members list (1 DB load max; then RAM hits)
    return cache.loadOrCreate(bankIdLower, ownerUuid).thenCompose(view -> {
      if (view == null || view.account() == null || view.account().getId() == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));
      }

      BankMemberEntity mem = findMemberInList(view.members(), actorUuid);
      if (mem == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("not a member"));
      }

      String roleId = normalizeId(mem.getRoleIdLower());
      BankDefinition.RoleDefinition role = ms.rolesByIdLower().get(roleId);
      if (role == null) return CompletableFuture.failedFuture(new IllegalStateException("role not found"));

      return CompletableFuture.completedFuture(role);
    });
  }

  private static BankMemberEntity findMemberInList(List<BankMemberEntity> members, UUID uuid) {
    if (uuid == null) return null;
    if (members == null || members.isEmpty()) return null;

    for (BankMemberEntity m : members) {
      if (m == null) continue;
      if (uuid.equals(m.getMemberUuid())) return m;
    }
    return null;
  }

  private static boolean isMember(List<BankMemberEntity> members, UUID uuid) {
    if (uuid == null) return false;
    if (members == null || members.isEmpty()) return false;

    for (BankMemberEntity m : members) {
      if (m == null) continue;
      UUID mem = m.getMemberUuid();
      if (uuid.equals(mem)) return true;
    }

    return false;
  }

  private static String normalizeId(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}