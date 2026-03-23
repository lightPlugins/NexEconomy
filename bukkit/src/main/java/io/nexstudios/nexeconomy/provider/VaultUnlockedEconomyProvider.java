package io.nexstudios.nexeconomy.provider;

import io.nexstudios.serviceregistry.di.Service;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.*;

public class VaultUnlockedEconomyProvider implements Economy, Service {
  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public @NotNull String getName() {
    return "";
  }

  @Override
  public boolean hasSharedAccountSupport() {
    return false;
  }

  @Override
  public boolean hasMultiCurrencySupport() {
    return false;
  }

  @Override
  public int fractionalDigits(@NotNull String pluginName) {
    return 0;
  }

  @Override
  public int fractionalDigits(@NotNull String pluginName, @NotNull String currency) {
    return Economy.super.fractionalDigits(pluginName, currency);
  }

  @Override
  public @NotNull String format(@NotNull BigDecimal amount) {
    return "";
  }

  @Override
  public @NotNull String format(@NotNull String pluginName, @NotNull BigDecimal amount) {
    return "";
  }

  @Override
  public @NotNull String format(@NotNull BigDecimal amount, @NotNull String currency) {
    return "";
  }

  @Override
  public @NotNull String format(@NotNull String pluginName, @NotNull BigDecimal amount, @NotNull String currency) {
    return "";
  }

  @Override
  public boolean hasCurrency(@NotNull String currency) {
    return false;
  }

  @Override
  public @NotNull String getDefaultCurrency(@NotNull String pluginName) {
    return "";
  }

  @Override
  public @NotNull String defaultCurrencyNamePlural(@NotNull String pluginName) {
    return "";
  }

  @Override
  public @NotNull String defaultCurrencyNameSingular(@NotNull String pluginName) {
    return "";
  }

  @Override
  public @NotNull Collection<String> currencies() {
    return List.of();
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name) {
    return false;
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name, boolean player) {
    return false;
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name, @NotNull String worldName) {
    return false;
  }

  @Override
  public boolean createAccount(@NotNull UUID accountID, @NotNull String name, @NotNull String worldName, boolean player) {
    return false;
  }

  @Override
  public @NotNull Map<UUID, String> getUUIDNameMap() {
    return Map.of();
  }

  @Override
  public Optional<String> getAccountName(@NotNull UUID accountID) {
    return Optional.empty();
  }

  @Override
  public boolean hasAccount(@NotNull UUID accountID) {
    return false;
  }

  @Override
  public boolean hasAccount(@NotNull UUID accountID, @NotNull String worldName) {
    return false;
  }

  @Override
  public boolean renameAccount(@NotNull UUID accountID, @NotNull String name) {
    return false;
  }

  @Override
  public boolean renameAccount(@NotNull String plugin, @NotNull UUID accountID, @NotNull String name) {
    return false;
  }

  @Override
  public boolean deleteAccount(@NotNull String plugin, @NotNull UUID accountID) {
    return false;
  }

  @Override
  public boolean accountSupportsCurrency(@NotNull String plugin, @NotNull UUID accountID, @NotNull String currency) {
    return false;
  }

  @Override
  public boolean accountSupportsCurrency(@NotNull String plugin, @NotNull UUID accountID, @NotNull String currency, @NotNull String world) {
    return false;
  }

  @Override
  public @NotNull BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID) {
    return null;
  }

  @Override
  public @NotNull BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world) {
    return null;
  }

  @Override
  public @NotNull BigDecimal getBalance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull String currency) {
    return null;
  }

  @Override
  public @NotNull BigDecimal balance(@NotNull String pluginName, @NotNull UUID accountID) {
    return Economy.super.balance(pluginName, accountID);
  }

  @Override
  public @NotNull BigDecimal balance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world) {
    return Economy.super.balance(pluginName, accountID, world);
  }

  @Override
  public @NotNull BigDecimal balance(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull String currency) {
    return Economy.super.balance(pluginName, accountID, world, currency);
  }

  @Override
  public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
    return false;
  }

  @Override
  public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
    return false;
  }

  @Override
  public boolean has(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
    return false;
  }

  @Override
  public EconomyResponse set(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
    return Economy.super.set(pluginName, accountID, amount);
  }

  @Override
  public EconomyResponse set(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
    return Economy.super.set(pluginName, accountID, worldName, amount);
  }

  @Override
  public EconomyResponse set(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
    return Economy.super.set(pluginName, accountID, worldName, currency, amount);
  }

  @Override
  public @NotNull EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
    return null;
  }

  @Override
  public @NotNull EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
    return null;
  }

  @Override
  public @NotNull EconomyResponse withdraw(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
    return null;
  }

  @Override
  public @NotNull EconomyResponse deposit(@NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
    return null;
  }

  @Override
  public @NotNull EconomyResponse deposit(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull BigDecimal amount) {
    return null;
  }

  @Override
  public @NotNull EconomyResponse deposit(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String worldName, @NotNull String currency, @NotNull BigDecimal amount) {
    return null;
  }

  @Override
  public boolean createSharedAccount(@NotNull String pluginName, @NotNull UUID accountID, @NotNull String name, @NotNull UUID owner) {
    return false;
  }

  @Override
  public List<String> accountsOwnedBy(@NotNull String pluginName, @NotNull UUID accountID) {
    return Economy.super.accountsOwnedBy(pluginName, accountID);
  }

  @Override
  public List<String> accountsMemberOf(@NotNull String pluginName, @NotNull UUID accountID) {
    return Economy.super.accountsMemberOf(pluginName, accountID);
  }

  @Override
  public List<String> accountsAccessTo(@NotNull String pluginName, @NotNull UUID accountID, @NotNull AccountPermission... permissions) {
    return Economy.super.accountsAccessTo(pluginName, accountID, permissions);
  }

  @Override
  public boolean isAccountOwner(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean setOwner(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean isAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean addAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean addAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission... initialPermissions) {
    return false;
  }

  @Override
  public boolean removeAccountMember(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
    return false;
  }

  @Override
  public boolean hasAccountPermission(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission permission) {
    return false;
  }

  @Override
  public boolean updateAccountPermission(@NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid, @NotNull AccountPermission permission, boolean value) {
    return false;
  }
}
