package io.nexstudios.nexeconomy.service.migration;

import java.math.BigDecimal;

public record MigrationRequest(
    String importerId,
    String targetCurrencyIdLower,
    boolean dryRun,
    boolean overwriteExisting,
    int limit,
    BigDecimal minBalanceHuman
) {
  public static MigrationRequest vaultDefaults(String targetCurrencyIdLower, boolean dryRun, boolean overwriteExisting, int limit) {
    return new MigrationRequest(
        "vault",
        targetCurrencyIdLower,
        dryRun,
        overwriteExisting,
        limit,
        BigDecimal.ZERO
    );
  }
}