package io.nexstudios.nexeconomy.service.bank.level;

import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.serviceregistry.di.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing bank level progression and upgrades.
 * Bank levels are stored in BankAccountEntity.level field.
 */
public interface BankLevelService extends Service {

  /**
   * Get the current level of a bank account.
   * Returns 1 if no level is set (default level).
   */
  CompletableFuture<Integer> getLevel(UUID bankAccountId);

  /**
   * Upgrade a bank to a new level.
   * Performs validation on permissions and balance before upgrade.
   */
  CompletableFuture<Boolean> upgrade(UUID bankAccountId, int targetLevel);

  /**
   * Check if a bank can be upgraded to the target level.
   * Validates: current level < target level <= max level.
   * Note: Permission checking is delegated to the command layer.
   */
  CompletableFuture<Boolean> canUpgrade(UUID bankAccountId, String bankId, int targetLevel);

  /**
   * Get the upgrade cost for a specific level.
   * Returns the MantissaAmount from the bank definition.
   */
  MantissaAmount getUpgradeCost(String bankId, int level);

  /**
   * Get the maximum balance for a specific level.
   * Returns the MantissaAmount from the bank definition.
   */
  MantissaAmount getMaxBalance(String bankId, int level);

  /**
   * Get the maximum level defined for a bank.
   */
  int getMaxLevel(String bankId);
}
