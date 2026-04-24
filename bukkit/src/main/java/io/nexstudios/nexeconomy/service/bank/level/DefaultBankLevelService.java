package io.nexstudios.nexeconomy.service.bank.level;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of BankLevelService.
 * Manages bank level progression stored directly in BankAccountEntity.level.
 */
@Dependencies({
    BankRegistryService.class,
    BankRepositoryService.class,
    BankAccountCacheService.class,
    CurrencyRegistryService.class
})
public final class DefaultBankLevelService implements BankLevelService, Service {

  private final BankRegistryService bankRegistry;
  private final BankRepositoryService repo;
  private final BankAccountCacheService cache;
  private final Map<UUID, Integer> levelCache = new ConcurrentHashMap<>();

  public DefaultBankLevelService(ServiceAccessor accessor) {
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.repo = accessor.getService(BankRepositoryService.class);
    this.cache = accessor.getService(BankAccountCacheService.class);
  }

  @Override
  public CompletableFuture<Integer> getLevel(UUID bankAccountId) {
    if (bankAccountId == null) {
      return CompletableFuture.completedFuture(1);
    }

    // Check cache first
    Integer cached = levelCache.get(bankAccountId);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }

    // Load from database via BankAccountEntity.level
    return repo.findBankAccountById(bankAccountId)
        .thenApply(account -> {
          int level = account.map(a -> {
            int lvl = a.getLevel();
            return lvl <= 0 ? 1 : lvl;
          }).orElse(1);
          levelCache.put(bankAccountId, level);
          return level;
        })
        .exceptionally(ex -> {
          levelCache.put(bankAccountId, 1);
          return 1;
        });
  }

  @Override
  public CompletableFuture<Boolean> upgrade(UUID bankAccountId, int targetLevel) {
    if (bankAccountId == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    }
    if (targetLevel < 1) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("targetLevel must be >= 1"));
    }

    int current = levelCache.getOrDefault(bankAccountId, 1);
    if (targetLevel <= current) {
      return CompletableFuture.completedFuture(false);
    }

    return repo.updateBankLevel(bankAccountId, targetLevel)
        .thenApply(success -> {
          if (Boolean.TRUE.equals(success)) {
            levelCache.put(bankAccountId, targetLevel);
            if (cache != null) {
              cache.updateAccountLevel(bankAccountId, targetLevel);
            }
            return true;
          }

          invalidate(bankAccountId);
          return false;
        })
        .exceptionally(ex -> {
          invalidate(bankAccountId);
          System.err.println("[NexEconomy] ERROR: updateBankLevel failed for account " + bankAccountId + ": " + ex);
          return false;
        });
  }

  @Override
  public void invalidate(UUID bankAccountId) {
    if (bankAccountId == null) {
      return;
    }
    levelCache.remove(bankAccountId);
  }

  @Override
  public CompletableFuture<Boolean> canUpgrade(UUID bankAccountId, String bankId, int targetLevel) {
    if (bankAccountId == null || bankId == null || bankId.isBlank() || targetLevel < 1) {
      return CompletableFuture.completedFuture(false);
    }

    return getLevel(bankAccountId).thenApply(currentLevel -> {
      if (targetLevel <= currentLevel) {
        return false;
      }

      int maxLevel = getMaxLevel(bankId);
      return targetLevel <= maxLevel;
    });
  }

  @Override
  public MantissaAmount getUpgradeCost(String bankId, int level) {
    Optional<BankDefinition> def = bankRegistry.bank(bankId);
    if (def.isEmpty()) {
      return MantissaAmount.zero();
    }

    BankDefinition bankDef = def.get();
    for (BankDefinition.LevelDefinition levelDef : bankDef.levels()) {
      if (levelDef.level() == level) {
        String costRaw = levelDef.upgradeCostRaw();
        // Try virtual mantissa notation first (aa, da, zz, etc.)
        MantissaAmount cost = AmountNotation.parseVirtualMantissaAmount(costRaw);
        if (cost != null) {
          return cost;
        }
        // Fallback to vault notation (25k, 1m, etc.)
        BigDecimal vaultAmount = AmountNotation.parseVaultHuman(costRaw);
        if (vaultAmount != null) {
          return MantissaAmount.of(vaultAmount, 0);
        }
        // If both parsing fails, return zero
        return MantissaAmount.zero();
      }
    }

    return MantissaAmount.zero();
  }

  @Override
  public MantissaAmount getMaxBalance(String bankId, int level) {
    Optional<BankDefinition> def = bankRegistry.bank(bankId);
    if (def.isEmpty()) {
      return MantissaAmount.zero();
    }

    BankDefinition bankDef = def.get();

    if (level == 1) {
      String raw = bankDef.defaultMaxBalanceRaw();
      // Try virtual mantissa notation first (aa, da, zz, etc.)
      MantissaAmount amount = AmountNotation.parseVirtualMantissaAmount(raw);
      if (amount != null) {
        return amount;
      }
      // Fallback to vault notation (25k, 1m, etc.)
      BigDecimal vaultAmount = AmountNotation.parseVaultHuman(raw);
      if (vaultAmount != null) {
        return MantissaAmount.of(vaultAmount, 0);
      }
      return MantissaAmount.zero();
    }

    for (BankDefinition.LevelDefinition levelDef : bankDef.levels()) {
      if (levelDef.level() == level) {
        String raw = levelDef.maxBalanceRaw();
        // Try virtual mantissa notation first (aa, da, zz, etc.)
        MantissaAmount amount = AmountNotation.parseVirtualMantissaAmount(raw);
        if (amount != null) {
          return amount;
        }
        // Fallback to vault notation (25k, 1m, etc.)
        BigDecimal vaultAmount = AmountNotation.parseVaultHuman(raw);
        if (vaultAmount != null) {
          return MantissaAmount.of(vaultAmount, 0);
        }
        return MantissaAmount.zero();
      }
    }

    return MantissaAmount.zero();
  }

  @Override
  public int getMaxLevel(String bankId) {
    Optional<BankDefinition> def = bankRegistry.bank(bankId);
    if (def.isEmpty()) {
      return 1;
    }

    BankDefinition bankDef = def.get();
    if (bankDef.levels().isEmpty()) {
      return 1;
    }

    return bankDef.levels().stream()
        .mapToInt(BankDefinition.LevelDefinition::level)
        .max()
        .orElse(1);
  }
}

