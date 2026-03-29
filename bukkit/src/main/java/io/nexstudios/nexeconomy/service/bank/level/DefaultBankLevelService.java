package io.nexstudios.nexeconomy.service.bank.level;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of BankLevelService.
 * Manages bank level progression stored directly in BankAccountEntity.level.
 */
@Dependencies({
    BankRegistryService.class,
    BankRepositoryService.class,
    CurrencyRegistryService.class
})
public final class DefaultBankLevelService implements BankLevelService, Service {

  private final BankRegistryService bankRegistry;
  private final BankRepositoryService repo;
  private final Map<UUID, Integer> levelCache = new HashMap<>();

  public DefaultBankLevelService(ServiceAccessor accessor) {
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.repo = accessor.getService(BankRepositoryService.class);
  }

  @Override
  public CompletableFuture<Integer> getLevel(UUID bankAccountId) {
    // Check cache first
    Integer cached = levelCache.get(bankAccountId);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }

    // Load from database via BankAccountEntity.level
    return repo.findBankAccountById(bankAccountId)
        .thenApply(account -> {
          int level = account.map(a -> {
            Integer lvl = a.getLevel();
            return (lvl == null || lvl <= 0) ? 1 : lvl;
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

    return CompletableFuture.supplyAsync(() -> {
      synchronized (levelCache) {
        Integer current = levelCache.getOrDefault(bankAccountId, 1);
        
        // Validate: target level must be higher than current
        if (targetLevel <= current) {
          return false;
        }

        // Update cache immediately
        levelCache.put(bankAccountId, targetLevel);
        return true;
      }
    }).thenCompose(cacheUpdated -> {
      if (!cacheUpdated) {
        return CompletableFuture.completedFuture(false);
      }

      // Persist to database via BankAccountEntity
      return repo.updateBankLevel(bankAccountId, targetLevel)
          .thenApply(success -> {
            if (!success) {
              System.err.println("[NexEconomy] WARNING: updateBankLevel failed for account " + bankAccountId);
            }
            return success;
          })
          .exceptionally(ex -> {
            String msg = ex instanceof Throwable ? ex.toString() : "Unknown error";
            System.err.println("[NexEconomy] ERROR: updateBankLevel failed for account " + bankAccountId + ": " + msg);
            if (ex instanceof Throwable) {
              ((Throwable) ex).printStackTrace();
            }
            // Restore cache to previous value since DB failed
            synchronized (levelCache) {
              Integer current = levelCache.getOrDefault(bankAccountId, 1);
              if (current == targetLevel) {
                levelCache.put(bankAccountId, targetLevel - 1);
              }
            }
            return false;
          });
    });
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

