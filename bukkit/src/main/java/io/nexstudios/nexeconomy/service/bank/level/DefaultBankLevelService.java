package io.nexstudios.nexeconomy.service.bank.level;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankLevelEntity;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of BankLevelService.
 * Manages bank level progression with caching and persistence.
 */
@Dependencies({
    BankRegistryService.class,
    BankRepositoryService.class,
    CurrencyRegistryService.class
})
public final class DefaultBankLevelService implements BankLevelService, Service {

  private final BankRegistryService bankRegistry;
  private final BankRepositoryService repo;
  private final CurrencyRegistryService currencyRegistry;
  private final Map<UUID, Integer> levelCache = new HashMap<>();

  public DefaultBankLevelService(ServiceAccessor accessor) {
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.repo = accessor.getService(BankRepositoryService.class);
    this.currencyRegistry = accessor.getService(CurrencyRegistryService.class);
  }

  @Override
  public CompletableFuture<Integer> getLevel(UUID bankAccountId) {
    // Check cache first
    Integer cached = levelCache.get(bankAccountId);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }

    // Load from database - read-only, never persist/merge on read
    return repo.findBankLevel(bankAccountId)
        .thenApply(entity -> {
          int level = entity.map(BankLevelEntity::getCurrentLevel).orElse(1);
          levelCache.put(bankAccountId, level);
          return level;
        })
        .exceptionally(ex -> {
          // If any error reading level, default to 1 and cache it
          levelCache.put(bankAccountId, 1);
          return 1;
        });
  }

  @Override
  public CompletableFuture<Boolean> upgrade(UUID bankAccountId, int targetLevel) {
    return CompletableFuture.supplyAsync(() -> {
      synchronized (levelCache) {
        Integer current = levelCache.getOrDefault(bankAccountId, 1);
        
        // Validate: target level must be higher than current
        if (targetLevel <= current) {
          return false;
        }

        // Update cache immediately for consistency
        levelCache.put(bankAccountId, targetLevel);
        return true;
      }
    }).thenCompose(cacheUpdated -> {
      if (!cacheUpdated) {
        return CompletableFuture.completedFuture(false);
      }

      // Persist to database - DO NOT hide errors
      return repo.upsertBankLevel(bankAccountId, targetLevel)
          .thenApply(entity -> {
            boolean success = entity != null && entity.getCurrentLevel() == targetLevel;
            if (!success) {
              System.err.println("[NexEconomy] WARNING: upsertBankLevel returned null or wrong level for account " + bankAccountId);
            }
            return success;
          })
          .exceptionally(ex -> {
            // DB error - DO NOT treat as success
            System.err.println("[NexEconomy] ERROR: upsertBankLevel failed for account " + bankAccountId + ": " + ex.getMessage());
            ex.printStackTrace();
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
    // Validate inputs
    if (bankAccountId == null || bankId == null || bankId.isBlank() || targetLevel < 1) {
      return CompletableFuture.completedFuture(false);
    }

    return getLevel(bankAccountId).thenApply(currentLevel -> {
      // Cannot upgrade to same or lower level
      if (targetLevel <= currentLevel) {
        return false;
      }

      // Cannot upgrade beyond max level
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
        return AmountNotation.parseVirtualMantissaAmount(levelDef.upgradeCostRaw());
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
      return AmountNotation.parseVirtualMantissaAmount(bankDef.defaultMaxBalanceRaw());
    }

    for (BankDefinition.LevelDefinition levelDef : bankDef.levels()) {
      if (levelDef.level() == level) {
        return AmountNotation.parseVirtualMantissaAmount(levelDef.maxBalanceRaw());
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

  /**
   * Initializes bank level entity if it doesn't exist.
   * Called only when truly needed (on first upgrade attempt).
   * Creates entity with level=1 if missing.
   */
  public CompletableFuture<BankLevelEntity> initializeBankLevel(UUID bankAccountId) {
    if (bankAccountId == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("bankAccountId is null"));
    }

    return repo.findBankLevel(bankAccountId).thenCompose(existing -> {
      if (existing.isPresent()) {
        return CompletableFuture.completedFuture(existing.get());
      }

      // Only create if truly missing - try upsertBankLevel with error handling
      return repo.upsertBankLevel(bankAccountId, 1)
          .exceptionally(ex -> {
            // If upsert fails, return a default entity (not persisted)
            // This prevents the exception from propagating
            return BankLevelEntity.builder()
                .id(java.util.UUID.randomUUID())
                .bankAccountId(bankAccountId)
                .currentLevel(1)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
          });
    });
  }
}

