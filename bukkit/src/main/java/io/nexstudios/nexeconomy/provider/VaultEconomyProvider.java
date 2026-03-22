package io.nexstudios.nexeconomy.provider;

import io.nexstudios.nexeconomy.provider.context.PreparedVaultOperation;
import io.nexstudios.nexeconomy.provider.context.VaultOperationContext;
import io.nexstudios.nexeconomy.service.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class VaultEconomyProvider implements Economy {

  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;

  public VaultEconomyProvider(CurrencyRegistryService currencies, EconomyPlayerCacheService cache) {
    this.currencies = currencies;
    this.cache = cache;
  }

  @Override
  public boolean isEnabled() {
    return vaultDef() != null;
  }

  @Override
  public String getName() {
    return "NexEconomy";
  }

  @Override
  public int fractionalDigits() {
    CurrencyDefinition def = vaultDef();
    return def == null ? 0 : def.fractionDigits();
  }

  @Override
  public String currencyNamePlural() {
    CurrencyDefinition def = vaultDef();
    return def == null ? "Money" : def.symbolPlural();
  }

  @Override
  public String currencyNameSingular() {
    CurrencyDefinition def = vaultDef();
    return def == null ? "Money" : def.symbolSingular();
  }

  @Override
  public boolean hasAccount(OfflinePlayer player) {
    return player != null;
  }

  @Override
  public String format(double amount) {
    int fd = fractionalDigits();
    if (fd < 0) fd = 0;
    if (fd > 8) fd = 8;
    return BigDecimal.valueOf(amount)
        .setScale(fd, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }

  @Override
  public double getBalance(OfflinePlayer player) {
    if (player == null) return 0D;

    String vaultId = vaultId();
    if (vaultId == null) return 0D;

    EconomyPlayer econ = cache.getOnline(player.getUniqueId());
    if (econ == null) return 0D;

    EconomyPlayer.BalanceEntry entry = econ.entry(vaultId);
    MantissaAmount amount = entry == null ? MantissaAmount.zero() : entry.amount();
    return amount == null ? 0D : amount.toDoubleApprox();
  }

  @Override
  public boolean has(OfflinePlayer player, double amount) {
    if (player == null) return false;
    if (amount < 0) return false;

    String vaultId = vaultId();
    if (vaultId == null) return false;

    MantissaAmount current = onlineAmountOrZero(player, vaultId);
    BigDecimal neededHuman = BigDecimal.valueOf(amount).setScale(fractionalDigits(), RoundingMode.HALF_UP);
    MantissaAmount needed = MantissaAmount.of(neededHuman, 0);

    return current.compareTo(needed) >= 0;
  }

  @Override
  public boolean has(OfflinePlayer player, String worldName, double amount) {
    return has(player, amount);
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
    PreparedVaultOperation prepared = prepareOnlineTx(player, amount);
    if (prepared.error() != null) return prepared.error();

    VaultOperationContext vaultOperationContext = prepared.vaultOperationContext();

    MantissaAmount current = vaultOperationContext.entry().amount() == null ? MantissaAmount.zero() : vaultOperationContext.entry().amount();
    if (current.compareTo(vaultOperationContext.delta()) < 0) {
      return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "insufficient funds");
    }

    vaultOperationContext.entry().subtract(vaultOperationContext.delta());
    return new EconomyResponse(vaultOperationContext.requestedHuman().doubleValue(), getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
    PreparedVaultOperation prepared = prepareOnlineTx(player, amount);
    if (prepared.error() != null) return prepared.error();

    VaultOperationContext vaultOperationContext = prepared.vaultOperationContext();

    vaultOperationContext.entry().add(vaultOperationContext.delta());
    return new EconomyResponse(vaultOperationContext.requestedHuman().doubleValue(), getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
  }

  // --- Bank not supported yet (future extension point) ---
  @Override public boolean hasBankSupport() { return false; }
  @Override public EconomyResponse createBank(String name, String player) { return notSupported(); }
  @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return notSupported(); }
  @Override public EconomyResponse deleteBank(String name) { return notSupported(); }
  @Override public EconomyResponse bankBalance(String name) { return notSupported(); }
  @Override public EconomyResponse bankHas(String name, double amount) { return notSupported(); }
  @Override public EconomyResponse bankWithdraw(String name, double amount) { return notSupported(); }
  @Override public EconomyResponse bankDeposit(String name, double amount) { return notSupported(); }
  @Override public EconomyResponse isBankOwner(String name, String playerName) { return notSupported(); }
  @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notSupported(); }
  @Override public EconomyResponse isBankMember(String name, String playerName) { return notSupported(); }
  @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return notSupported(); }
  @Override public List<String> getBanks() { return List.of(); }

  // --- Unsupported legacy/string-based overloads ---
  @Override public boolean hasAccount(String playerName) { return false; }
  @Override public boolean hasAccount(String playerName, String worldName) { return false; }
  @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return hasAccount(player); }
  @Override public double getBalance(String playerName) { return 0D; }
  @Override public double getBalance(String playerName, String world) { return 0D; }
  @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }
  @Override public boolean has(String playerName, double amount) { return false; }
  @Override public boolean has(String playerName, String worldName, double amount) { return false; }
  @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return notSupported(); }
  @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return notSupported(); }
  @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }
  @Override public EconomyResponse depositPlayer(String playerName, double amount) { return notSupported(); }
  @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return notSupported(); }
  @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

  @Override public boolean createPlayerAccount(String playerName) { return false; }
  @Override public boolean createPlayerAccount(OfflinePlayer player) { return player != null; }
  @Override public boolean createPlayerAccount(String playerName, String worldName) { return false; }
  @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return createPlayerAccount(player); }
  //@Override public String getAccountName(OfflinePlayer player) { return player == null ? "" : player.getName(); }

  private static EconomyResponse notSupported() {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "not supported");
  }

  private PreparedVaultOperation prepareOnlineTx(OfflinePlayer player, double amount) {
    if (player == null) {
      return PreparedVaultOperation.error(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player is null"));
    }

    if (amount <= 0) {
      return PreparedVaultOperation.error(new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "invalid amount"));
    }

    String vaultId = vaultId();
    if (vaultId == null) {
      return PreparedVaultOperation.error(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "vault currency not configured"));
    }

    EconomyPlayer econ = cache.getOnline(player.getUniqueId());
    if (econ == null) {
      return PreparedVaultOperation.error(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player not cached (offline not supported yet)"));
    }

    int fd = fractionalDigits();
    BigDecimal requestedHuman = BigDecimal.valueOf(amount).setScale(fd, RoundingMode.HALF_UP);
    MantissaAmount delta = MantissaAmount.of(requestedHuman, 0);

    EconomyPlayer.BalanceEntry entry = econ.getOrCreate(vaultId, MantissaAmount.zero());
    return PreparedVaultOperation.ok(new VaultOperationContext(vaultId, econ, entry, requestedHuman, delta));
  }

  private MantissaAmount onlineAmountOrZero(OfflinePlayer player, String vaultId) {
    EconomyPlayer econ = cache.getOnline(player.getUniqueId());
    if (econ == null) return MantissaAmount.zero();

    EconomyPlayer.BalanceEntry entry = econ.entry(vaultId);
    return entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();
  }

  private String vaultId() {
    return currencies.vaultCurrencyId();
  }

  private CurrencyDefinition vaultDef() {
    String id = vaultId();
    return id == null ? null : currencies.currency(id);
  }

}