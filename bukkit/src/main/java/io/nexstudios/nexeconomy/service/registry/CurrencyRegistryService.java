package io.nexstudios.nexeconomy.service.registry;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Dependencies({
    LoggerService.class,
    PaperPluginService.class
})
public final class CurrencyRegistryService implements Service {

  private final LoggerService logger;
  private final Plugin plugin;

  private final AtomicReference<Map<String, CurrencyDefinition>> currenciesRef = new AtomicReference<>(Map.of());
  private final AtomicReference<String> vaultCurrencyIdRef = new AtomicReference<>(null);

  public CurrencyRegistryService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    reload();
  }

  public void reload() {
    File dir = new File(plugin.getDataFolder(), "currencies");
    ensureDefaultsExist(dir);

    Map<String, CurrencyDefinition> loaded = new LinkedHashMap<>();
    String vaultId = null;

    File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));
    if (files == null) files = new File[0];

    Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

    for (File f : files) {
      String id = toCurrencyId(f.getName());
      if (id.isBlank()) continue;

      YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);

      CurrencyType type = CurrencyType.parse(yml.getString("currency-type", "virtual"));

      CurrencyDefinition def = new CurrencyDefinition(
          id,
          yml.getString("name", id),
          yml.getString("symbol.singular", id),
          yml.getString("symbol.plural", id),
          yml.getString("player-placeholder", "<yellow><amount> <gray><symbol>"),
          yml.getString("top-placeholder", "<dark_gray>● <yellow><bold><number><reset><gray># <dark_gray>● <yellow><name> <gray>- <yellow><amount> <gray><currency>"),
          clampFractionDigits(yml.getInt("fraction-digits", 0)),
          type,
          readBigDecimal(yml, "start-balance", BigDecimal.ZERO),
          readMaxBalanceHuman(yml, "max-balance", type, BigDecimal.valueOf(-1)),
          yml.getBoolean("payable", true)
      );

      if (type == CurrencyType.VAULT) {
        if (vaultId != null && !Objects.equals(vaultId, id)) {
          logger.logger().warning("Multiple vault currencies detected. Overriding previous vault currency '" + vaultId + "' with '" + id + "'.");
        }
        vaultId = id;
      }

      loaded.put(id, def);
    }

    currenciesRef.set(Collections.unmodifiableMap(loaded));
    vaultCurrencyIdRef.set(vaultId);

    logger.logger().info("Loaded " + loaded.size() + " currencies. Vault currency: " + (vaultId == null ? "none" : vaultId));
  }

  public @NotNull Collection<CurrencyDefinition> currencies() {
    return currenciesRef.get().values();
  }

  public @NotNull Set<String> currencyIds() {
    return currenciesRef.get().keySet();
  }

  public CurrencyDefinition currency(String id) {
    if (id == null) return null;
    return currenciesRef.get().get(normalizeCurrencyId(id));
  }

  public String vaultCurrencyId() {
    return vaultCurrencyIdRef.get();
  }

  private void ensureDefaultsExist(File dir) {
    if (!dir.exists() && !dir.mkdirs()) {
      logger.logger().warning("Could not create currencies directory: " + dir.getAbsolutePath());
      return;
    }

    File defaultVault = new File(dir, "vault.yml");
    if (defaultVault.exists()) return;

    try (var in = plugin.getResource("currencies/vault.yml")) {
      if (in == null) return;
      Files.copy(in, defaultVault.toPath());
      logger.logger().info("Extracted default currency file: " + defaultVault.getName());
    } catch (Exception e) {
      logger.logger().warning("Failed to extract default currency file: " + e.getMessage());
    }
  }

  private static int clampFractionDigits(int digits) {
    if (digits < 0) return 0;
    return Math.min(digits, 8);
  }

  private static BigDecimal readBigDecimal(YamlConfiguration yml, String path, BigDecimal def) {
    Object raw = yml.get(path);
    if (raw == null) return def;
    try {
      if (raw instanceof Number n) return new BigDecimal(n.toString());
      return new BigDecimal(String.valueOf(raw).trim());
    } catch (Exception ignored) {
      return def;
    }
  }

  private static BigDecimal readMaxBalanceHuman(YamlConfiguration yml, String path, CurrencyType type, BigDecimal def) {
    Object raw = yml.get(path);
    if (raw == null) return def;

    // Allow numeric or suffix notation like "90b" / "90zz"
    String s = String.valueOf(raw).trim();
    if (s.isBlank()) return def;

    try {
      return new BigDecimal(s);
    } catch (Exception ignored) {
      // fall through to notation parsing
    }

    if ("-1".equals(s)) return BigDecimal.valueOf(-1);

    if (type == CurrencyType.VAULT) {
      BigDecimal human = AmountNotation.parseVaultHuman(s);
      return human == null ? def : human;
    }

    var mantissa = AmountNotation.parseVirtualMantissaAmount(s);
    return mantissa == null ? def : mantissa.toHuman();
  }

  private static String toCurrencyId(String fileName) {
    int idx = fileName.lastIndexOf('.');
    String base = idx < 0 ? fileName : fileName.substring(0, idx);
    return normalizeCurrencyId(base);
  }

  private static String normalizeCurrencyId(String id) {
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }
}