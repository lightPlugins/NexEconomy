package io.nexstudios.nexeconomy.service.bank.transaction;

import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankTransactionEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Dependencies({
    BankRegistryService.class,
    BankAccountCacheService.class,
    BankRepositoryService.class
})
public final class DefaultBankTransactionService implements BankTransactionService, Service {

  private final BankRegistryService banks;
  private final BankAccountCacheService cache;
  private final BankRepositoryService repo;

  public DefaultBankTransactionService(ServiceAccessor accessor) {
    this.banks = accessor.getService(BankRegistryService.class);
    this.cache = accessor.getService(BankAccountCacheService.class);
    this.repo = accessor.getService(BankRepositoryService.class);
  }

  @Override
  public CompletableFuture<List<BankTransactionEntity>> transactionsVisibleTo(
      String bankIdLower,
      UUID ownerUuid,
      UUID viewerUuid,
      int limit
  ) {
    String bank = normalize(bankIdLower);
    if (bank.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("bankId is blank"));
    if (ownerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("ownerUuid is null"));
    if (viewerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("viewerUuid is null"));

    BankDefinition def = banks.bank(bank).orElse(null);
    if (def == null || !def.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));

    BankDefinition.MemberSystem ms = def.memberSystem();
    if (ms == null || !ms.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("member system disabled"));

    return cache.loadOrCreate(bank, ownerUuid).thenCompose(view -> {
      if (view == null || view.account() == null || view.account().getId() == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("bank not available"));
      }

      BankMemberEntity member = findMember(view.members(), viewerUuid);
      if (member == null) return CompletableFuture.failedFuture(new IllegalStateException("not a member"));

      String roleId = normalize(member.getRoleIdLower());
      BankDefinition.RoleDefinition role = ms.rolesByIdLower() == null ? null : ms.rolesByIdLower().get(roleId);
      if (role == null) return CompletableFuture.failedFuture(new IllegalStateException("no permission"));
      if (!role.canViewLog()) return CompletableFuture.failedFuture(new IllegalStateException("no permission"));

      return repo.listRecentTransactions(view.account().getId(), limit);
    });
  }

  private static BankMemberEntity findMember(List<BankMemberEntity> members, UUID uuid) {
    if (uuid == null || members == null || members.isEmpty()) return null;
    for (BankMemberEntity m : members) {
      if (m == null) continue;
      if (uuid.equals(m.getMemberUuid())) return m;
    }
    return null;
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}