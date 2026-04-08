package io.nexstudios.nexeconomy.service.bank.definition;

import java.util.List;
import java.util.Map;

/**
 * Immutable parsed definition loaded from banks/<id>.yml (id = filename without extension).
 */
public record BankDefinition(
    String idLower,
    String nameMiniMessage,
    boolean enabled,
    String currencyIdLower,
    boolean unlockedByDefault,
    String defaultMaxBalanceRaw,
    MemberSystem memberSystem,
    InterestSystem interestSystem,
    List<LevelDefinition> levels
) {

  public record MemberSystem(
      boolean enabled,
      int maxMembers,
      Map<String, RoleDefinition> rolesByIdLower
  ) {}

  public record RoleDefinition(
      String idLower,
      String nameMiniMessage,
      int priority,
      boolean canDeposit,
      WithdrawDefinition withdraw,
      boolean canInvite,
      boolean canKick,
      boolean canUpgrade,
      boolean canViewLog
  ) {}

  public record WithdrawDefinition(
      boolean canWithdraw,
      String dailyLimitRaw,
      String hourlyLimitRaw
  ) {}

  public record InterestSystem(
      boolean enabled,
      double percentage,
      String time,     // HH:mm:ss
      String timezone  // e.g. Europe/Berlin
  ) {}

  public record LevelDefinition(
      int level,
      String maxBalanceRaw,
      String interestRateRaw,
      String permission,
      String upgradeCostRaw
  ) {}
}