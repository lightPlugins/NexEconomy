package io.nexstudios.nexeconomy.service.bank;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.repo.InviteLookupRow;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankAccountEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankInviteEntity;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.serviceregistry.di.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.math.BigDecimal;

public interface BankService extends Service {

  void reload();

  CompletableFuture<List<BankRepositoryService.BankAccountRef>> allBanks(UUID playerUuid);

  CompletableFuture<List<BankRepositoryService.BankAccountRef>> otherBanks(UUID memberUuid);

  CompletableFuture<Optional<BankDefinition>> bank(String bankId);

  CompletableFuture<List<InviteLookupRow>> invites(UUID inviteeUuid);

  CompletableFuture<BankAccountEntity> getOrCreateAccount(String bankId, UUID ownerUuid);

  CompletableFuture<List<BankMemberEntity>> members(String bankId, UUID ownerUuid);

  CompletableFuture<BankInviteEntity> invite(String bankId, UUID ownerUuid, UUID actorUuid, UUID inviteeUuid, String roleId);

  CompletableFuture<Boolean> changeMemberRole(String bankId, UUID ownerUuid, UUID actorUuid, UUID memberUuid, String roleId);

  CompletableFuture<Boolean> acceptInvite(String bankId, UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<MantissaAmount> balance(String bankId, UUID ownerUuid);

  CompletableFuture<MantissaAmount> deposit(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount);

  CompletableFuture<MantissaAmount> withdraw(String bankId, UUID ownerUuid, UUID actorUuid, MantissaAmount amount);


  CompletableFuture<Boolean> denyInviteFromOwner(UUID ownerUuid, UUID inviteeUuid);

  CompletableFuture<Boolean> leave(String bankId, UUID ownerUuid, UUID memberUuid);

  void ensureMissingUnlockedBanksForAllOnline();

  CompletableFuture<MantissaAmount> balanceVisibleTo(String bankId, UUID ownerUuid, UUID viewerUuid);

  CompletableFuture<List<BankMemberEntity>> membersVisibleTo(String bankId, UUID ownerUuid, UUID viewerUuid);

  CompletableFuture<Boolean> unlockBankForPlayer(String bankId, UUID ownerUuid, UUID unlockedByUuid);

  CompletableFuture<Boolean> lockBankForPlayer(String bankId, UUID ownerUuid, UUID lockedByUuid);

  CompletableFuture<BankAccountEntity> createBank(String bankId, UUID ownerUuid);

  CompletableFuture<BankAccountEntity> deleteBank(String bankId, UUID ownerUuid);

  CompletableFuture<Boolean> lockAllBankAccountsForPlayer(UUID playerUuid, UUID lockedByUuid, String reason);

  CompletableFuture<Boolean> unlockAllBankAccountsForPlayer(UUID playerUuid, UUID unlockedByUuid);

  CompletableFuture<Boolean> isAnyBankAccountLockedForPlayer(UUID playerUuid);

  default String formatBalance(MantissaAmount amount, int fractionDigits) {
    return AmountNotation.formatShort(amount, Math.max(0, fractionDigits));
  }

  default String currencySymbol(CurrencyDefinition currency, MantissaAmount amount) {
    if (currency == null) return "";

    MantissaAmount value = amount == null ? MantissaAmount.zero() : amount;
    return value.toHuman().compareTo(BigDecimal.ONE) == 0
        ? currency.symbolSingular()
        : currency.symbolPlural();
  }

  default String formatBalanceWithCurrency(MantissaAmount amount, CurrencyDefinition currency) {
    String balance = formatBalance(amount, currency == null ? 0 : currency.fractionDigits());
    String symbol = currencySymbol(currency, amount);
    return symbol.isBlank() ? balance : balance + " " + symbol;
  }

}