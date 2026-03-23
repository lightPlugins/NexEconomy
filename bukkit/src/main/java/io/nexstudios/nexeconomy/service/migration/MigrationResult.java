package io.nexstudios.nexeconomy.service.migration;

import java.math.BigDecimal;

public record MigrationResult(
    String importerId,
    String targetCurrencyIdLower,
    boolean dryRun,
    int processedPlayers,
    int writtenPlayers,
    int skippedPlayers,
    int failedPlayers,
    BigDecimal totalImportedHuman
) {}