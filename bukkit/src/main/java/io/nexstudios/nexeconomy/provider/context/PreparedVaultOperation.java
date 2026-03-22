package io.nexstudios.nexeconomy.provider.context;

import net.milkbowl.vault.economy.EconomyResponse;

public record PreparedVaultOperation(VaultOperationContext vaultOperationContext, EconomyResponse error) {
  public static PreparedVaultOperation ok(VaultOperationContext vaultOperationContext) {
    return new PreparedVaultOperation(vaultOperationContext, null);
  }

  public static PreparedVaultOperation error(EconomyResponse error) {
    return new PreparedVaultOperation(null, error);
  }
}
