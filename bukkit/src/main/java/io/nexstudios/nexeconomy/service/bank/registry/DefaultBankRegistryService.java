package io.nexstudios.nexeconomy.service.bank.registry;

import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Dependencies({
    LoggerService.class,
    PaperPluginService.class,
    FileReaderService.class
})
public final class DefaultBankRegistryService implements BankRegistryService {

  private final LoggerService logger;
  private final Plugin plugin;

  private final AtomicReference<Map<String, BankDefinition>> banksRef = new AtomicReference<>(Map.of());

  public DefaultBankRegistryService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    reload();
  }

  @Override
  public void reload() {
    File dir = new File(plugin.getDataFolder(), "banks");
    ensureDefaultsExist(dir);

    Map<String, BankDefinition> loaded = new LinkedHashMap<>();

    File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
    if (files == null) files = new File[0];

    Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

    for (File f : files) {
      String id = toBankId(f.getName());
      if (id.isBlank()) continue;

      YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);

      BankDefinition def = parse(id, yml);
      loaded.put(id, def);
    }

    banksRef.set(Collections.unmodifiableMap(loaded));
    logger.logger().info("Loaded " + loaded.size() + " banks from /banks.");
  }

  @Override
  public Collection<BankDefinition> banks() {
    return banksRef.get().values();
  }

  @Override
  public Collection<String> bankIds() {
    return banksRef.get().keySet();
  }

  @Override
  public Optional<BankDefinition> bank(String bankId) {
    if (bankId == null) return Optional.empty();
    return Optional.ofNullable(banksRef.get().get(normalizeId(bankId)));
  }

  private BankDefinition parse(String idLower, YamlConfiguration yml) {
    String name = yml.getString("name", idLower);
    boolean enabled = yml.getBoolean("enabled", true);
    String currency = normalizeId(yml.getString("currency", "vault"));
    boolean unlocked = yml.getBoolean("unlocked-by-default", true);

    BankDefinition.MemberSystem memberSystem = parseMemberSystem(yml.getConfigurationSection("member-system"));
    BankDefinition.InterestSystem interestSystem = parseInterestSystem(yml.getConfigurationSection("interest-system"));

    // Parse list-based "level-system" correctly (it is a YAML list, not a section).
    List<BankDefinition.LevelDefinition> levels = parseLevelsList(yml.getMapList("level-system"));

    return new BankDefinition(
        idLower,
        name,
        enabled,
        currency,
        unlocked,
        memberSystem,
        interestSystem,
        levels
    );
  }

  private static List<BankDefinition.LevelDefinition> parseLevelsList(List<Map<?, ?>> list) {
    if (list == null || list.isEmpty()) return List.of();

    List<BankDefinition.LevelDefinition> out = new ArrayList<>();

    for (Map<?, ?> raw : list) {
      if (raw == null) continue;

      int level = parseInt(raw.get("level"), 1);
      String maxBalance = stringOrDefault(raw, "max-balance", "-1");
      String perm = stringOrDefault(raw, "need-permission", "");
      String cost = stringOrDefault(raw, "upgrade-cost", "0");

      out.add(new BankDefinition.LevelDefinition(level, maxBalance, perm, cost));
    }

    out.sort(Comparator.comparingInt(BankDefinition.LevelDefinition::level));
    return List.copyOf(out);
  }

  private static BankDefinition.MemberSystem parseMemberSystem(ConfigurationSection sec) {
    if (sec == null) {
      return new BankDefinition.MemberSystem(false, 0, Map.of());
    }

    boolean enabled = sec.getBoolean("enabled", true);
    int max = sec.getInt("max", 10);

    Map<String, BankDefinition.RoleDefinition> roles = new LinkedHashMap<>();

    List<Map<?, ?>> list = sec.getMapList("roles");
    for (Map<?, ?> raw : list) {
      if (raw == null) continue;

      String id = normalizeId(stringOrDefault(raw, "id", ""));
      if (id.isBlank()) continue;

      String name = stringOrDefault(raw, "name", id);
      int priority = parseInt(raw.get("priority"), id.equals("owner") ? 100 : 0);

      boolean canDeposit = parseBool(raw.get("canDeposit"), true);

      Map<?, ?> withdrawRaw = raw.get("withdraw") instanceof Map<?, ?> m ? m : Map.of();
      boolean canWithdraw = parseBool(withdrawRaw.get("can"), true);
      String daily = stringOrDefault(withdrawRaw, "daily-limit", "-1");
      String hourly = stringOrDefault(withdrawRaw, "hourly-limit", "-1");

      boolean canInvite = parseBool(raw.get("canInvite"), true);
      boolean canKick = parseBool(raw.get("canKick"), id.equals("owner"));
      boolean canUpgrade = parseBool(raw.get("canUpgrade"), id.equals("owner"));
      boolean canViewLog = parseBool(raw.get("canViewLog"), id.equals("owner"));

      roles.put(id, new BankDefinition.RoleDefinition(
          id,
          name,
          priority,
          canDeposit,
          new BankDefinition.WithdrawDefinition(canWithdraw, daily, hourly),
          canInvite,
          canKick,
          canUpgrade,
          canViewLog
      ));
    }

    return new BankDefinition.MemberSystem(enabled, max, Collections.unmodifiableMap(roles));
  }

  private static BankDefinition.InterestSystem parseInterestSystem(ConfigurationSection sec) {
    if (sec == null) {
      return new BankDefinition.InterestSystem(false, 0.0, "03:00:00", "Europe/Berlin");
    }

    boolean enabled = sec.getBoolean("enabled", false);
    double percentage = sec.getDouble("percentage", 0.0);
    String time = sec.getString("time", "03:00:00");
    String tz = sec.getString("timezone", "Europe/Berlin");

    return new BankDefinition.InterestSystem(enabled, percentage, time, tz);
  }

  private static List<BankDefinition.LevelDefinition> parseLevels(ConfigurationSection sec) {
    if (sec == null) return List.of();

    List<Map<?, ?>> list = sec.getMapList("");
    if (list.isEmpty()) {
      list = sec.getMapList("levels");
    }

    List<BankDefinition.LevelDefinition> out = new ArrayList<>();
    List<Map<?, ?>> fromDefaultKey = sec.getMapList("");
    if (!fromDefaultKey.isEmpty()) {
      list = fromDefaultKey;
    } else {
      list = sec.getMapList("level-system");
    }

    for (Map<?, ?> raw : list) {
      if (raw == null) continue;

      int level = parseInt(raw.get("level"), 1);
      String maxBalance = stringOrDefault(raw, "max-balance", "-1");
      String perm = stringOrDefault(raw, "need-permission", "");
      String cost = stringOrDefault(raw, "upgrade-cost", "0");

      out.add(new BankDefinition.LevelDefinition(level, maxBalance, perm, cost));
    }

    out.sort(Comparator.comparingInt(BankDefinition.LevelDefinition::level));
    return List.copyOf(out);
  }

  private void ensureDefaultsExist(File dir) {
    if (!dir.exists() && !dir.mkdirs()) {
      logger.logger().warning("Could not create banks directory: " + dir.getAbsolutePath());
      return;
    }

    File defaultBank = new File(dir, "bank.yml");
    if (defaultBank.exists()) return;

    try (var in = plugin.getResource("banks/bank.yml")) {
      if (in == null) return;
      Files.copy(in, defaultBank.toPath());
      logger.logger().info("Extracted default bank file: " + defaultBank.getName());
    } catch (Exception e) {
      logger.logger().warning("Failed to extract default bank file: " + e.getMessage());
    }
  }

  private static String stringOrDefault(Map<?, ?> map, String key, String def) {
    if (map == null || key == null) return def;
    if (!map.containsKey(key)) return def;
    Object v = map.get(key);
    if (v == null) return def;
    String s = String.valueOf(v);
    return s == null ? def : s;
  }

  private static String toBankId(String fileName) {
    int idx = fileName.lastIndexOf('.');
    String base = idx < 0 ? fileName : fileName.substring(0, idx);
    return normalizeId(base);
  }

  private static String normalizeId(String id) {
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean parseBool(Object raw, boolean def) {
    if (raw == null) return def;
    if (raw instanceof Boolean b) return b;
    String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
    if (s.isBlank()) return def;
    return "true".equals(s) || "yes".equals(s) || "1".equals(s);
  }

  private static int parseInt(Object raw, int def) {
    if (raw == null) return def;
    if (raw instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(String.valueOf(raw).trim());
    } catch (Exception ignored) {
      return def;
    }
  }
}