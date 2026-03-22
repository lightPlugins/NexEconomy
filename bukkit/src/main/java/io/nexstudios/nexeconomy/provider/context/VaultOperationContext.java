package io.nexstudios.nexeconomy.provider.context;

import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;

import java.math.BigDecimal;

public record VaultOperationContext(
    String vaultId,
    EconomyPlayer econ,
    EconomyPlayer.BalanceEntry entry,
    BigDecimal requestedHuman,
    MantissaAmount delta
) {}