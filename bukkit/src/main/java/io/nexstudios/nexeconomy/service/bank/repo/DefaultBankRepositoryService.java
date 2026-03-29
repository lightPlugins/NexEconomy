package io.nexstudios.nexeconomy.service.bank.repo;

import io.nexstudios.databaseservice.bukkit.service.api.DatabaseAsyncService;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.*;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Dependencies({
    LoggerService.class
})
public final class DefaultBankRepositoryService implements BankRepositoryService {

  private final LoggerService logger;
  private final DatabaseAsyncService dbAsync;

  public DefaultBankRepositoryService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.dbAsync = NexEconomyPlugin.getNexLogicService()
        .findService(DatabaseAsyncService.class)
        .orElseThrow(() -> new IllegalStateException("DatabaseAsyncService not available via NexLogic"));
  }

  @Override
  public CompletableFuture<Optional<BankAccountEntity>> findAccount(String bankIdLower, UUID ownerUuid) {
    String bank = normalizeId(bankIdLower);
    if (bank.isBlank() || ownerUuid == null) return CompletableFuture.completedFuture(Optional.empty());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankAccountEntity> list = em.createQuery(
              "select a from BankAccountEntity a where a.bankIdLower = :bank and a.ownerUuid = :owner",
              BankAccountEntity.class
          )
          .setParameter("bank", bank)
          .setParameter("owner", ownerUuid)
          .setMaxResults(1)
          .getResultList();

      return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.getFirst());
    });
  }

  @Override
  public CompletableFuture<List<UUID>> findOwnerUuidsForMember(UUID memberUuid) {
    if (memberUuid == null) return CompletableFuture.completedFuture(List.of());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<UUID> owners = em.createQuery(
              """
              select distinct a.ownerUuid
              from BankMemberEntity m
              join BankAccountEntity a on a.id = m.bankAccountId
              where m.memberUuid = :member
              """,
              UUID.class
          )
          .setParameter("member", memberUuid)
          .getResultList();

      if (owners == null || owners.isEmpty()) return List.of();

      ArrayList<UUID> out = new ArrayList<>(owners.size());
      for (UUID u : owners) {
        if (u != null) out.add(u);
      }
      return List.copyOf(out);
    });
  }

  @Override
  public CompletableFuture<List<UUID>> findOwnerUuidsForMember(String bankIdLower, UUID memberUuid) {
    String bank = normalizeId(bankIdLower);
    if (bank.isBlank() || memberUuid == null) return CompletableFuture.completedFuture(List.of());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<UUID> owners = em.createQuery(
              """
              select distinct a.ownerUuid
              from BankMemberEntity m
              join BankAccountEntity a on a.id = m.bankAccountId
              where m.memberUuid = :member
                and a.bankIdLower = :bank
              """,
              UUID.class
          )
          .setParameter("member", memberUuid)
          .setParameter("bank", bank)
          .getResultList();

      if (owners == null || owners.isEmpty()) return List.of();

      ArrayList<UUID> out = new ArrayList<>(owners.size());
      for (UUID u : owners) {
        if (u != null) out.add(u);
      }
      return List.copyOf(out);
    });
  }

  @Override
  public CompletableFuture<BankAccountEntity> createAccountIfMissing(String bankIdLower, UUID ownerUuid) {
    String bank = normalizeId(bankIdLower);
    if (bank.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankIdLower is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankAccountEntity> list = em.createQuery(
              "select a from BankAccountEntity a where a.bankIdLower = :bank and a.ownerUuid = :owner",
              BankAccountEntity.class
          )
          .setParameter("bank", bank)
          .setParameter("owner", ownerUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankAccountEntity existing = list.isEmpty() ? null : list.getFirst();
      if (existing != null) return existing;

      BankAccountEntity created = BankAccountEntity.builder()
          .bankIdLower(bank)
          .ownerUuid(ownerUuid)
          .level(1)
          .balanceMantissa("0")
          .balanceExp3(0)
          .createdAt(Instant.now())
          .updatedAt(Instant.now())
          .build();

      em.persist(created);
      return created;
    });
  }

  @Override
  public CompletableFuture<MantissaAmount> loadBalance(UUID bankAccountId) {
    if (bankAccountId == null) return CompletableFuture.completedFuture(MantissaAmount.zero());

    return dbAsync.executeAsyncInTransaction(em -> {
      BankAccountEntity a = em.find(BankAccountEntity.class, bankAccountId);
      if (a == null) return MantissaAmount.zero();
      return MantissaAmount.parseStorage(a.getBalanceMantissa(), a.getBalanceExp3());
    });
  }

  @Override
  public CompletableFuture<MantissaAmount> applyBalanceDelta(UUID bankAccountId, MantissaAmount delta) {
    if (bankAccountId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    MantissaAmount d = delta == null ? MantissaAmount.zero() : MantissaAmount.normalize(delta);

    return dbAsync.executeAsyncInTransaction(em -> {
      BankAccountEntity a = em.find(BankAccountEntity.class, bankAccountId, LockModeType.PESSIMISTIC_WRITE);
      if (a == null) {
        throw new IllegalStateException("Bank account not found: " + bankAccountId);
      }

      MantissaAmount current = MantissaAmount.parseStorage(a.getBalanceMantissa(), a.getBalanceExp3());
      MantissaAmount next = current.add(d);
      MantissaAmount.Storage st = next.toStorage();

      a.setBalanceMantissa(st.mantissaText());
      a.setBalanceExp3(st.exp3());
      a.setUpdatedAt(Instant.now());
      em.merge(a);

      return MantissaAmount.parseStorage(a.getBalanceMantissa(), a.getBalanceExp3());
    });
  }

  @Override
  public CompletableFuture<MantissaAmount> addWithdrawUsage(
      UUID bankAccountId,
      UUID memberUuid,
      BankWithdrawUsageEntity.WindowType windowType,
      long windowStartEpochSeconds,
      MantissaAmount delta
  ) {
    if (bankAccountId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    if (memberUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("memberUuid is null"));
    if (windowType == null) return CompletableFuture.failedFuture(new IllegalArgumentException("windowType is null"));
    if (windowStartEpochSeconds <= 0) return CompletableFuture.failedFuture(new IllegalArgumentException("windowStartEpochSeconds is invalid"));

    MantissaAmount d = delta == null ? MantissaAmount.zero() : MantissaAmount.normalize(delta);
    MantissaAmount.Storage dst = d.toStorage();

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankWithdrawUsageEntity> list = em.createQuery(
              """
              select u from BankWithdrawUsageEntity u
              where u.bankAccountId = :acc
                and u.memberUuid = :mem
                and u.windowType = :type
                and u.windowStartEpoch = :start
              """,
              BankWithdrawUsageEntity.class
          )
          .setParameter("acc", bankAccountId)
          .setParameter("mem", memberUuid)
          .setParameter("type", windowType)
          .setParameter("start", windowStartEpochSeconds)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankWithdrawUsageEntity row = list.isEmpty() ? null : list.getFirst();

      if (row == null) {
        BankWithdrawUsageEntity created = BankWithdrawUsageEntity.builder()
            .bankAccountId(bankAccountId)
            .memberUuid(memberUuid)
            .windowType(windowType)
            .windowStartEpoch(windowStartEpochSeconds)
            .usedMantissa(dst.mantissaText())
            .usedExp3(dst.exp3())
            .updatedAt(Instant.now())
            .build();
        em.persist(created);
        return MantissaAmount.parseStorage(created.getUsedMantissa(), created.getUsedExp3());
      }

      MantissaAmount used = MantissaAmount.parseStorage(row.getUsedMantissa(), row.getUsedExp3());
      MantissaAmount next = used.add(d);
      MantissaAmount.Storage st = next.toStorage();

      row.setUsedMantissa(st.mantissaText());
      row.setUsedExp3(st.exp3());
      row.setUpdatedAt(Instant.now());
      em.merge(row);

      return MantissaAmount.parseStorage(row.getUsedMantissa(), row.getUsedExp3());
    });
  }

  @Override
  public CompletableFuture<Void> appendTransaction(
      UUID bankAccountId,
      BankTransactionEntity.Type type,
      UUID actorUuid,
      UUID targetUuid,
      MantissaAmount amount,
      String meta
  ) {
    if (bankAccountId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    if (type == null) return CompletableFuture.failedFuture(new IllegalArgumentException("type is null"));

    MantissaAmount a = amount == null ? MantissaAmount.zero() : MantissaAmount.normalize(amount);
    MantissaAmount.Storage st = a.toStorage();
    String m = meta == null ? null : meta.trim();
    if (m != null && m.length() > 512) m = m.substring(0, 512);

    String finalMeta = m;
    return dbAsync.executeAsyncInTransaction(em -> {
      BankTransactionEntity tx = BankTransactionEntity.builder()
          .bankAccountId(bankAccountId)
          .type(type)
          .actorUuid(actorUuid)
          .targetUuid(targetUuid)
          .amountMantissa(st.mantissaText())
          .amountExp3(st.exp3())
          .meta(finalMeta)
          .createdAt(Instant.now())
          .build();
      em.persist(tx);
    });
  }

  @Override
  public CompletableFuture<MantissaAmount> setBalance(UUID bankAccountId, MantissaAmount newBalance) {
    if (bankAccountId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    MantissaAmount value = newBalance == null ? MantissaAmount.zero() : MantissaAmount.normalize(newBalance);
    MantissaAmount.Storage st = value.toStorage();

    return dbAsync.executeAsyncInTransaction(em -> {
      BankAccountEntity a = em.find(BankAccountEntity.class, bankAccountId, LockModeType.PESSIMISTIC_WRITE);
      if (a == null) {
        throw new IllegalStateException("Bank account not found: " + bankAccountId);
      }

      a.setBalanceMantissa(st.mantissaText());
      a.setBalanceExp3(st.exp3());
      a.setUpdatedAt(Instant.now());
      em.merge(a);

      return MantissaAmount.parseStorage(a.getBalanceMantissa(), a.getBalanceExp3());
    }).exceptionally(ex -> {
      logger.logger().warning("setBalance failed for account=" + bankAccountId + ": " + ex.getMessage());
      throw new RuntimeException(ex);
    });
  }

  @Override
  public CompletableFuture<List<BankMemberEntity>> listMembers(UUID bankAccountId) {
    if (bankAccountId == null) return CompletableFuture.completedFuture(List.of());

    Function<EntityManager, List<BankMemberEntity>> work = em -> em.createQuery(
            "select m from BankMemberEntity m where m.bankAccountId = :id",
            BankMemberEntity.class
        )
        .setParameter("id", bankAccountId)
        .getResultList();

    return dbAsync.executeAsyncInTransaction(work);
  }

  @Override
  public CompletableFuture<Optional<BankMemberEntity>> findMember(UUID bankAccountId, UUID memberUuid) {
    if (bankAccountId == null || memberUuid == null) return CompletableFuture.completedFuture(Optional.empty());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankMemberEntity> list = em.createQuery(
              "select m from BankMemberEntity m where m.bankAccountId = :acc and m.memberUuid = :mem",
              BankMemberEntity.class
          )
          .setParameter("acc", bankAccountId)
          .setParameter("mem", memberUuid)
          .setMaxResults(1)
          .getResultList();

      return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.getFirst());
    });
  }

  @Override
  public CompletableFuture<BankMemberEntity> upsertMember(UUID bankAccountId, UUID memberUuid, UUID addedByUuid, String roleIdLower) {
    if (bankAccountId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    if (memberUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("memberUuid is null"));

    String role = normalizeId(roleIdLower);
    if (role.isBlank()) role = "member";

    String finalRole = role;
    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankMemberEntity> list = em.createQuery(
              "select m from BankMemberEntity m where m.bankAccountId = :acc and m.memberUuid = :mem",
              BankMemberEntity.class
          )
          .setParameter("acc", bankAccountId)
          .setParameter("mem", memberUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankMemberEntity row = list.isEmpty() ? null : list.getFirst();
      if (row == null) {
        BankMemberEntity created = BankMemberEntity.builder()
            .bankAccountId(bankAccountId)
            .memberUuid(memberUuid)
            .addedByUuid(addedByUuid)
            .roleIdLower(finalRole)
            .joinedAt(Instant.now())
            .build();
        em.persist(created);
        return created;
      }

      row.setRoleIdLower(finalRole);
      em.merge(row);
      return row;
    });
  }

  @Override
  public CompletableFuture<Boolean> deleteMember(UUID bankAccountId, UUID memberUuid) {
    if (bankAccountId == null || memberUuid == null) return CompletableFuture.completedFuture(false);

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankMemberEntity> list = em.createQuery(
              "select m from BankMemberEntity m where m.bankAccountId = :acc and m.memberUuid = :mem",
              BankMemberEntity.class
          )
          .setParameter("acc", bankAccountId)
          .setParameter("mem", memberUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankMemberEntity row = list.isEmpty() ? null : list.getFirst();
      if (row == null) return false;

      em.remove(row);
      return true;
    });
  }

  @Override
  public CompletableFuture<Boolean> isMemberOfAnyOtherAccount(UUID memberUuid, UUID excludeOwnedByUuid) {
    if (memberUuid == null) return CompletableFuture.completedFuture(false);
    if (excludeOwnedByUuid == null) return CompletableFuture.completedFuture(false);

    return dbAsync.executeAsyncInTransaction(em -> {
      Long count = em.createQuery(
              """
              select count(m)
              from BankMemberEntity m
              where m.memberUuid = :member
                and m.bankAccountId in (
                  select a.id from BankAccountEntity a where a.ownerUuid <> :excludeOwner
                )
              """,
              Long.class
          )
          .setParameter("member", memberUuid)
          .setParameter("excludeOwner", excludeOwnedByUuid)
          .getSingleResult();

      return count != null && count > 0;
    });
  }

  @Override
  public CompletableFuture<Optional<BankInviteEntity>> findInvite(UUID bankAccountId, UUID inviteeUuid) {
    if (bankAccountId == null || inviteeUuid == null) return CompletableFuture.completedFuture(Optional.empty());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankInviteEntity> list = em.createQuery(
              "select i from BankInviteEntity i where i.bankAccountId = :acc and i.inviteeUuid = :inv",
              BankInviteEntity.class
          )
          .setParameter("acc", bankAccountId)
          .setParameter("inv", inviteeUuid)
          .setMaxResults(1)
          .getResultList();

      return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.getFirst());
    });
  }

  @Override
  public CompletableFuture<List<InviteLookupRow>> findInvitesForInvitee(UUID inviteeUuid) {
    if (inviteeUuid == null) return CompletableFuture.completedFuture(List.of());

    Function<EntityManager, List<Object[]>> work = em -> em.createQuery(
            """
            select a.id, a.bankIdLower, a.ownerUuid,
                   i.inviteeUuid, i.invitedByUuid, i.roleIdLower, i.expiresAt
            from BankInviteEntity i
            join BankAccountEntity a on a.id = i.bankAccountId
            where i.inviteeUuid = :invitee
            """,
            Object[].class
        )
        .setParameter("invitee", inviteeUuid)
        .getResultList();

    return dbAsync.executeAsyncInTransaction(work).thenApply(DefaultBankRepositoryService::mapInviteLookupRows);
  }

  @Override
  public CompletableFuture<List<InviteLookupRow>> findInvitesForInviteeFromOwner(UUID inviteeUuid, UUID ownerUuid) {
    if (inviteeUuid == null || ownerUuid == null) return CompletableFuture.completedFuture(List.of());

    Function<EntityManager, List<Object[]>> work = em -> em.createQuery(
            """
            select a.id, a.bankIdLower, a.ownerUuid,
                   i.inviteeUuid, i.invitedByUuid, i.roleIdLower, i.expiresAt
            from BankInviteEntity i
            join BankAccountEntity a on a.id = i.bankAccountId
            where i.inviteeUuid = :invitee
              and a.ownerUuid = :owner
            """,
            Object[].class
        )
        .setParameter("invitee", inviteeUuid)
        .setParameter("owner", ownerUuid)
        .getResultList();

    return dbAsync.executeAsyncInTransaction(work).thenApply(DefaultBankRepositoryService::mapInviteLookupRows);
  }

  private static List<InviteLookupRow> mapInviteLookupRows(List<Object[]> rows) {
    if (rows == null || rows.isEmpty()) return List.of();

    List<InviteLookupRow> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      if (r == null || r.length < 7) continue;

      UUID bankAccountId = r[0] instanceof UUID u ? u : null;
      String bankIdLower = r[1] == null ? null : String.valueOf(r[1]);
      UUID ownerUuid = r[2] instanceof UUID u ? u : null;

      UUID inviteeUuid = r[3] instanceof UUID u ? u : null;
      UUID invitedByUuid = r[4] instanceof UUID u ? u : null;
      String roleIdLower = r[5] == null ? null : String.valueOf(r[5]);
      Instant expiresAt = r[6] instanceof Instant i ? i : null;

      if (bankAccountId == null) continue;

      out.add(new InviteLookupRow(
          bankAccountId,
          bankIdLower,
          ownerUuid,
          inviteeUuid,
          invitedByUuid,
          roleIdLower,
          expiresAt
      ));
    }

    return List.copyOf(out);
  }

  @Override
  public CompletableFuture<BankInviteEntity> upsertInvite(UUID bankAccountId, UUID inviteeUuid, UUID invitedByUuid, String roleIdLower) {
    if (bankAccountId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    if (inviteeUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("inviteeUuid is null"));
    if (invitedByUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("invitedByUuid is null"));

    String role = normalizeId(roleIdLower);
    if (role.isBlank()) role = "member";

    String finalRole = role;
    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankInviteEntity> list = em.createQuery(
              "select i from BankInviteEntity i where i.bankAccountId = :acc and i.inviteeUuid = :inv",
              BankInviteEntity.class
          )
          .setParameter("acc", bankAccountId)
          .setParameter("inv", inviteeUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankInviteEntity row = list.isEmpty() ? null : list.getFirst();
      if (row == null) {
        BankInviteEntity created = BankInviteEntity.builder()
            .bankAccountId(bankAccountId)
            .inviteeUuid(inviteeUuid)
            .invitedByUuid(invitedByUuid)
            .roleIdLower(finalRole)
            .createdAt(Instant.now())
            .build();
        em.persist(created);
        return created;
      }

      row.setRoleIdLower(finalRole);
      em.merge(row);
      return row;
    });
  }

  @Override
  public CompletableFuture<Boolean> deleteInvite(UUID bankAccountId, UUID inviteeUuid) {
    if (bankAccountId == null || inviteeUuid == null) return CompletableFuture.completedFuture(false);

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankInviteEntity> list = em.createQuery(
              "select i from BankInviteEntity i where i.bankAccountId = :acc and i.inviteeUuid = :inv",
              BankInviteEntity.class
          )
          .setParameter("acc", bankAccountId)
          .setParameter("inv", inviteeUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankInviteEntity row = list.isEmpty() ? null : list.getFirst();
      if (row == null) return false;

      em.remove(row);
      return true;
    });
  }

  @Override
  public CompletableFuture<List<BankAccountRef>> findBankAccountsForMember(UUID memberUuid) {
    if (memberUuid == null) return CompletableFuture.completedFuture(List.of());

    return dbAsync.executeAsyncInTransaction(em -> {
      List<Object[]> rows = em.createQuery(
              """
              select a.id, a.bankIdLower, a.ownerUuid
              from BankMemberEntity m
              join BankAccountEntity a on a.id = m.bankAccountId
              where m.memberUuid = :member
              """,
              Object[].class
          )
          .setParameter("member", memberUuid)
          .getResultList();

      if (rows == null || rows.isEmpty()) return List.of();

      ArrayList<BankAccountRef> out = new ArrayList<>(rows.size());
      for (Object[] r : rows) {
        if (r == null || r.length < 3) continue;

        UUID id = r[0] instanceof UUID u ? u : null;
        String bankIdLower = r[1] == null ? null : String.valueOf(r[1]);
        UUID ownerUuid = r[2] instanceof UUID u ? u : null;

        if (id == null) continue;
        out.add(new BankAccountRef(id, bankIdLower, ownerUuid));
      }
      return List.copyOf(out);
    });
  }

  @Override
  public CompletableFuture<Long> countOtherBankMemberships(UUID memberUuid) {
    if (memberUuid == null) return CompletableFuture.completedFuture(0L);

    return dbAsync.executeAsyncInTransaction(em -> {
      Long count = em.createQuery(
              """
              select count(distinct a.id)
              from BankMemberEntity m
              join BankAccountEntity a on a.id = m.bankAccountId
              where m.memberUuid = :member
                and a.ownerUuid <> :member
              """,
              Long.class
          )
          .setParameter("member", memberUuid)
          .getSingleResult();

      return count == null ? 0L : count;
    });
  }

  @Override
  public CompletableFuture<List<BankTransactionEntity>> listRecentTransactions(UUID bankAccountId, int limit) {
    if (bankAccountId == null) return CompletableFuture.completedFuture(List.of());
    int lim = limit <= 0 ? 10 : Math.min(limit, 100);

    Function<EntityManager, List<BankTransactionEntity>> work = em -> em.createQuery(
            """
            select t
            from BankTransactionEntity t
            where t.bankAccountId = :acc
            order by t.createdAt desc
            """,
            BankTransactionEntity.class
        )
        .setParameter("acc", bankAccountId)
        .setMaxResults(lim)
        .getResultList();

    return dbAsync.executeAsyncInTransaction(work)
        .thenApply(list -> list == null ? List.of() : List.copyOf(list));
  }

  @Override
  public CompletableFuture<Boolean> isUnlocked(String bankIdLower, UUID ownerUuid) {
    String bank = normalizeId(bankIdLower);
    if (bank.isBlank() || ownerUuid == null) return CompletableFuture.completedFuture(false);

    return dbAsync.executeAsyncInTransaction(em -> {
      Long count = em.createQuery(
              "select count(u) from BankUnlockEntity u where u.bankIdLower = :bank and u.ownerUuid = :owner",
              Long.class
          )
          .setParameter("bank", bank)
          .setParameter("owner", ownerUuid)
          .getSingleResult();

      return count != null && count > 0;
    });
  }

  @Override
  public CompletableFuture<Boolean> unlock(String bankIdLower, UUID ownerUuid, UUID unlockedByUuid) {
    String bank = normalizeId(bankIdLower);
    if (bank.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankIdLower is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (unlockedByUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("unlockedByUuid is null"));

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankUnlockEntity> list = em.createQuery(
              "select u from BankUnlockEntity u where u.bankIdLower = :bank and u.ownerUuid = :owner",
              BankUnlockEntity.class
          )
          .setParameter("bank", bank)
          .setParameter("owner", ownerUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankUnlockEntity existing = list.isEmpty() ? null : list.getFirst();
      if (existing != null) return false;

      BankUnlockEntity created = BankUnlockEntity.builder()
          .id(UUID.randomUUID())
          .bankIdLower(bank)
          .ownerUuid(ownerUuid)
          .unlockedByUuid(unlockedByUuid)
          .unlockedAt(Instant.now())
          .build();

      em.persist(created);
      return true;
    });
  }

  @Override
  public CompletableFuture<Boolean> lock(String bankIdLower, UUID ownerUuid, UUID lockedByUuid) {
    String bank = normalizeId(bankIdLower);
    if (bank.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankIdLower is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (lockedByUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("lockedByUuid is null"));

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankUnlockEntity> list = em.createQuery(
              "select u from BankUnlockEntity u where u.bankIdLower = :bank and u.ownerUuid = :owner",
              BankUnlockEntity.class
          )
          .setParameter("bank", bank)
          .setParameter("owner", ownerUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankUnlockEntity existing = list.isEmpty() ? null : list.getFirst();
      if (existing == null) return false;

      em.remove(existing);
      return true;
    });
  }

  @Override
  public CompletableFuture<Boolean> isPlayerLocked(UUID playerUuid) {
    if (playerUuid == null) return CompletableFuture.completedFuture(false);

    return dbAsync.executeAsyncInTransaction(em -> {
      Long count = em.createQuery(
              "select count(l) from BankAccountLockEntity l where l.playerUuid = :player",
              Long.class
          )
          .setParameter("player", playerUuid)
          .getSingleResult();

      return count != null && count > 0;
    });
  }

  @Override
  public CompletableFuture<Boolean> lockPlayer(UUID playerUuid, UUID lockedByUuid, String reason) {
    if (playerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid is null"));
    if (lockedByUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("lockedByUuid is null"));

    String r = reason == null ? null : reason.trim();
    if (r != null && r.isBlank()) r = null;
    if (r != null && r.length() > 256) r = r.substring(0, 256);

    String finalReason = r;

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankAccountLockEntity> list = em.createQuery(
              "select l from BankAccountLockEntity l where l.playerUuid = :player",
              BankAccountLockEntity.class
          )
          .setParameter("player", playerUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankAccountLockEntity existing = list.isEmpty() ? null : list.getFirst();
      if (existing != null) return false;

      BankAccountLockEntity created = BankAccountLockEntity.builder()
          .id(UUID.randomUUID())
          .playerUuid(playerUuid)
          .lockedByUuid(lockedByUuid)
          .lockedAt(Instant.now())
          .reason(finalReason)
          .build();

      em.persist(created);
      return true;
    });
  }

  @Override
  public CompletableFuture<Boolean> unlockPlayer(UUID playerUuid, UUID unlockedByUuid) {
    if (playerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid is null"));
    if (unlockedByUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("unlockedByUuid is null"));

    return dbAsync.executeAsyncInTransaction(em -> {
      List<BankAccountLockEntity> list = em.createQuery(
              "select l from BankAccountLockEntity l where l.playerUuid = :player",
              BankAccountLockEntity.class
          )
          .setParameter("player", playerUuid)
          .setMaxResults(1)
          .setLockMode(LockModeType.PESSIMISTIC_WRITE)
          .getResultList();

      BankAccountLockEntity existing = list.isEmpty() ? null : list.getFirst();
      if (existing == null) return false;

      em.remove(existing);
      return true;
    });
  }

  @Override
  public CompletableFuture<Optional<BankAccountEntity>> findBankAccountById(UUID bankAccountId) {
    if (bankAccountId == null) return CompletableFuture.completedFuture(Optional.empty());

    return dbAsync.executeAsyncInTransaction(em -> {
      BankAccountEntity account = em.find(BankAccountEntity.class, bankAccountId);
      return Optional.ofNullable(account);
    });
  }

  @Override
  public CompletableFuture<Boolean> updateBankLevel(UUID bankAccountId, int level) {
    if (bankAccountId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    if (level < 1) return CompletableFuture.failedFuture(new IllegalArgumentException("level must be >= 1"));

    return dbAsync.executeAsyncInTransaction(em -> {
      BankAccountEntity account = em.find(BankAccountEntity.class, bankAccountId, LockModeType.PESSIMISTIC_WRITE);
      if (account == null) {
        throw new IllegalStateException("Bank account not found: " + bankAccountId);
      }

      account.setLevel(level);
      account.setUpdatedAt(Instant.now());
      return true;
    });
  }

  private static String normalizeId(String s) {
    return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
  }
}