package io.nexstudios.nexeconomy.provider;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.provider.context.PreparedVaultOperation;
import io.nexstudios.nexeconomy.provider.context.VaultOperationContext;
import io.nexstudios.nexeconomy.service.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyFlushService;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyPlayer;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import lombok.extern.slf4j.Slf4j;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

@Slf4j
@SuppressWarnings("deprecation")
@Dependencies({
    PaperPluginService.class
})
public final class VaultEconomyProvider implements Economy, Service {

  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyFlushService flush;

  public VaultEconomyProvider(CurrencyRegistryService currencies, EconomyPlayerCacheService cache, EconomyFlushService flush) {
    this.currencies = currencies;
    this.cache = cache;
    this.flush = flush;
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
    if (player == null) return false;

    // Towny fake OfflinePlayer: considered supported (we can create/load it)
    if (isTownyAccount(player)) {
      return true;
    }

    // Real players: offline not supported yet -> only if online-cached
    UUID uuid = player.getUniqueId();
    return cache.getOnline(uuid) != null;
  }

  @Override
  public boolean hasAccount(OfflinePlayer player, String worldName) {
    return hasAccount(player);
  }

  @Override
  public String format(double amount) {
    int fd = fractionalDigits();
    if (fd < 0) fd = 0;
    if (fd > 8) fd = 8;
    return BigDecimal.valueOf(amount)
        .setScale(fd, RoundingMode.HALF_UP)
        .toPlainString();
  }

  @Override
  public double getBalance(OfflinePlayer player) {
    if (player == null) return 0D;

    String vaultId = vaultId();
    if (vaultId == null) return 0D;

    EconomyPlayer econ = resolveEconForOfflinePlayer(player);
    if (econ == null) return 0D;

    EconomyPlayer.BalanceEntry entry = econ.entry(vaultId);
    MantissaAmount amount = entry == null ? MantissaAmount.zero() : entry.amount();
    return amount == null ? 0D : amount.toDoubleApprox();
  }

  @Override
  public double getBalance(OfflinePlayer player, String world) {
    return getBalance(player);
  }

  @Override
  public boolean has(OfflinePlayer player, double amount) {
    if (player == null) return false;
    if (amount < 0) return false;

    String vaultId = vaultId();
    if (vaultId == null) return false;

    EconomyPlayer econ = resolveEconForOfflinePlayer(player);
    if (econ == null) return false;

    EconomyPlayer.BalanceEntry entry = econ.entry(vaultId);
    MantissaAmount current = entry == null || entry.amount() == null ? MantissaAmount.zero() : entry.amount();

    int fd = fractionalDigits();
    BigDecimal neededHuman = BigDecimal.valueOf(amount).setScale(fd, RoundingMode.DOWN);
    MantissaAmount needed = MantissaAmount.of(neededHuman, 0);

    return current.compareTo(needed) >= 0;
  }

  @Override
  public boolean has(OfflinePlayer player, String worldName, double amount) {
    return has(player, amount);
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
    PreparedVaultOperation prepared = prepareOfflineTx(player, amount);
    if (prepared.error() != null) return prepared.error();

    VaultOperationContext ctx = prepared.vaultOperationContext();

    MantissaAmount current = ctx.entry().amount() == null ? MantissaAmount.zero() : ctx.entry().amount();
    if (current.compareTo(ctx.delta()) < 0) {
      return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "insufficient funds");
    }

    ctx.entry().subtract(ctx.delta());

    if (flush != null) {
      if (isTownyAccount(player)) flush.requestFlushTowny(ctx.econ());
      else flush.requestFlush(ctx.econ());
    }

    return new EconomyResponse(ctx.requestedHuman().doubleValue(), getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
    return withdrawPlayer(player, amount);
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
    PreparedVaultOperation prepared = prepareOfflineTx(player, amount);
    if (prepared.error() != null) return prepared.error();

    VaultOperationContext ctx = prepared.vaultOperationContext();

    ctx.entry().add(ctx.delta());

    if (flush != null) {
      if (isTownyAccount(player)) flush.requestFlushTowny(ctx.econ());
      else flush.requestFlush(ctx.econ());
    }

    return new EconomyResponse(ctx.requestedHuman().doubleValue(), getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
    return depositPlayer(player, amount);
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

  @Override
  public boolean createPlayerAccount(OfflinePlayer player) {
    if (player == null) {
      return false;
    }

    UUID uuid = player.getUniqueId();

    if (isTownyAccount(player)) {
      EconomyPlayer econ = cache.loadOrCreateTowny(uuid).join();
      if (flush != null && econ != null) {
        flush.requestFlushTowny(econ);
      }
      return true;
    }

    Player online = player.getPlayer();
    if (online == null || !online.isOnline()) {
      return false;
    }

    EconomyPlayer econ = cache.loadOrCreateOnline(online).join();
    if (flush != null && econ != null) {
      flush.requestFlush(econ);
    }
    return true;
  }

  @Override
  public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
    return createPlayerAccount(player);
  }

  private static EconomyResponse notSupported() {
    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "not supported");
  }

  private PreparedVaultOperation prepareOfflineTx(OfflinePlayer player, double amount) {
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

    EconomyPlayer econ = resolveEconForOfflinePlayer(player);
    if (econ == null) {
      return PreparedVaultOperation.error(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "account not available (offline player not supported yet)"));
    }

    int fd = fractionalDigits();
    BigDecimal requestedHuman = BigDecimal.valueOf(amount).setScale(fd, RoundingMode.DOWN);
    MantissaAmount delta = MantissaAmount.of(requestedHuman, 0);

    EconomyPlayer.BalanceEntry entry = econ.getOrCreate(vaultId, MantissaAmount.zero());
    return PreparedVaultOperation.ok(new VaultOperationContext(vaultId, econ, entry, requestedHuman, delta));
  }

  private EconomyPlayer resolveEconForOfflinePlayer(OfflinePlayer player) {
    if (player == null) {
      return null;
    }

    if (isTownyAccount(player)) {
      return cache.loadOrCreateTowny(player.getUniqueId()).join();
    }
    return cache.getOnline(player.getUniqueId());
  }

  private boolean isTownyAccount(OfflinePlayer p) {
    if (p == null) return false;
    UUID uuid = p.getUniqueId();

    // Heuristic: Towny provides a fake OfflinePlayer whose UUID is the account UUID.
    // If there is no online player with that UUID, treat it as a Towny account.
    return Bukkit.getPlayer(uuid) == null;
  }

  private String vaultId() {
    return currencies.vaultCurrencyId();
  }

  private CurrencyDefinition vaultDef() {
    String id = vaultId();
    return id == null ? null : currencies.currency(id);
  }

  // --- Legacy string-based overloads: explicitly unsupported (Towny does NOT use them anymore) ---

  @Override
  public boolean hasAccount(String playerName) {
    return false;
  }

  @Override
  public boolean hasAccount(String playerName, String worldName) {
    return false;
  }

  @Override
  public double getBalance(String playerName) {
    return 0D;
  }

  @Override
  public double getBalance(String playerName, String world) {
    return 0D;
  }

  @Override
  public boolean has(String playerName, double amount) {
    return false;
  }

  @Override
  public boolean has(String playerName, String worldName, double amount) {
    return false;
  }

  @Override
  public EconomyResponse withdrawPlayer(String playerName, double amount) {
    return notSupported();
  }

  @Override
  public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
    return notSupported();
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, double amount) {
    return notSupported();
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
    return notSupported();
  }

  @Override
  public boolean createPlayerAccount(String playerName) {
    return false;
  }

  @Override
  public boolean createPlayerAccount(String playerName, String worldName) {
    return false;
  }
}