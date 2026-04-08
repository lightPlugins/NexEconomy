package io.nexstudios.nexeconomy.service.bank.interest;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.multireader.MultiFileReaderService;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

@Dependencies({
    LoggerService.class,
    PaperPluginService.class,
    FileReaderService.class,
    MultiFileReaderService.class,
    BankRepositoryService.class,
    BankRegistryService.class,
    ComponentService.class,
    BankAccountCacheService.class,
    CurrencyRegistryService.class
})
public final class DefaultBankInterestService implements BankInterestService {

  private final LoggerService logger;
  private final Plugin plugin;
  private final FileReaderService fileReader;
  private final MultiFileReaderService multiFileReader;
  private final BankRepositoryService bankRepo;
  private final ComponentService componentService;
  private final BankAccountCacheService cacheService;
  private final BankRegistryService bankRegistry;
  private final CurrencyRegistryService currencyRegistry;

  private final Map<String, BankInterestConfig> interestConfigs = new ConcurrentHashMap<>();
  private final Map<String, Long> lastProcessedTime = new ConcurrentHashMap<>();
  private BukkitTask scheduler;
  private boolean started = false;

  public DefaultBankInterestService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.fileReader = accessor.getService(FileReaderService.class);
    this.multiFileReader = accessor.getService(MultiFileReaderService.class);
    this.bankRepo = accessor.getService(BankRepositoryService.class);
    this.componentService = accessor.getService(ComponentService.class);
    this.cacheService = accessor.getService(BankAccountCacheService.class);
    this.bankRegistry = accessor.getService(BankRegistryService.class);
    this.currencyRegistry = accessor.getService(CurrencyRegistryService.class);

    reload();
  }

  @Override
  public void start() {
    if (started || !plugin.isEnabled()) return;

    // Run the check on the main thread so Bukkit player access stays safe.
    this.scheduler = Bukkit.getScheduler().runTaskTimer(
        plugin,
        this::checkAndProcessInterests,
        0L,      // Start immediately
        1200L    // Check every minute (60 ticks = 1 second, 1200 ticks = 1 minute)
    );

    started = true;
    logger.logger().log(Level.INFO, "Bank interest service started (time-based scheduler)");
  }

  @Override
  public void stop() {
    if (scheduler != null) {
      scheduler.cancel();
      scheduler = null;
    }
    started = false;
    logger.logger().log(Level.INFO, "Bank interest service stopped");
  }

  @Override
  public void reload() {
    interestConfigs.clear();

    multiFileReader.loadAll(Path.of("banks")).forEach((filePath, config) -> {
      String fileName = filePath.getFileName().toString();
      if (!fileName.endsWith(".yml")) {
        return;
      }

      String bankId = normalizeBankId(fileName.substring(0, fileName.length() - 4));
      BankInterestConfig interestConfig = loadInterestConfig(config);
      interestConfigs.put(bankId, interestConfig);
    });

    logger.logger().log(Level.INFO, "Loaded interest settings for " + interestConfigs.size() + " banks");
  }

  private BankInterestConfig loadInterestConfig(FileConfiguration config) {
    if (config == null) {
      return new BankInterestConfig(false, BigDecimal.ZERO, List.of(), ZoneId.systemDefault(), BigDecimal.ZERO, "");
    }

    boolean enabled = config.getBoolean("interest.enabled", false);
    String rateStr = config.getString("interest.rate", "0");

    BigDecimal rate;
    try {
      rate = new BigDecimal(rateStr.replace("%", "").trim());
      rate = rate.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    } catch (Exception ex) {
      rate = BigDecimal.ZERO;
    }

    List<String> timesList = config.getStringList("interest.times");
    if (timesList.isEmpty()) {
      timesList = List.of("03:00:00");
    }

    List<LocalTime> times = new ArrayList<>();
    for (String timeStr : timesList) {
      try {
        times.add(LocalTime.parse(timeStr));
      } catch (Exception ex) {
        logger.logger().log(Level.WARNING, "Invalid time format: " + timeStr + ", skipping");
      }
    }

    String zoneStr = config.getString("interest.timezone", "Europe/Berlin");
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(zoneStr);
    } catch (Exception ex) {
      logger.logger().log(Level.WARNING, "Invalid timezone: " + zoneStr + ", using system default");
      zoneId = ZoneId.systemDefault();
    }

    String maxBalanceStr = config.getString("default-max-balance", "0");
    BigDecimal maxBalance;
    try {
      maxBalance = new BigDecimal(maxBalanceStr.replace("k", "").replace("m", "").trim());
      if (maxBalanceStr.toLowerCase().contains("k")) {
        maxBalance = maxBalance.multiply(new BigDecimal("1000"));
      } else if (maxBalanceStr.toLowerCase().contains("m")) {
        maxBalance = maxBalance.multiply(new BigDecimal("1000000"));
      }
    } catch (Exception ex) {
      maxBalance = BigDecimal.ZERO;
    }

    // Get the currency ID from bank config
    String currencyId = config.getString("currency", "");

    return new BankInterestConfig(enabled, rate, times, zoneId, maxBalance, currencyId);
  }

  private void checkAndProcessInterests() {
    try {
      ZonedDateTime now = ZonedDateTime.now();
      
      for (Map.Entry<String, BankInterestConfig> entry : interestConfigs.entrySet()) {
        String bankId = entry.getKey();
        BankInterestConfig config = entry.getValue();

        if (!config.enabled() || config.times().isEmpty() || !hasAnyInterestRate(bankId, config)) {
          continue;
        }

        if (shouldProcessInterest(bankId, config)) {
          logger.logger().log(Level.INFO, "Processing interest for bank: " + bankId + " at " + now);
          processInterestForBank(bankId);
        }
      }
    } catch (Exception ex) {
      logger.logger().log(Level.WARNING, "Error in bank interest processing", ex);
    }
  }

  private boolean shouldProcessInterest(String bankId, BankInterestConfig config) {
    ZonedDateTime now = ZonedDateTime.now(config.zoneId());
    LocalTime currentTime = now.toLocalTime();
    long currentTimeSeconds = currentTime.toSecondOfDay();

    for (LocalTime interestTime : config.times()) {
      long interestTimeSeconds = interestTime.toSecondOfDay();
      long secondsDiff = Math.abs(currentTimeSeconds - interestTimeSeconds);

      // Only trigger within 30 second window to avoid double triggers
      if (secondsDiff < 30) {
        // Check if we already processed this time for this bank
        String key = bankId + ":" + interestTime;
        long lastProcessed = lastProcessedTime.getOrDefault(key, -1L);

        // Only process if not processed in the last 60 seconds
        if (System.currentTimeMillis() - lastProcessed > 60000) {
          lastProcessedTime.put(key, System.currentTimeMillis());
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public void processInterestForBank(String bankId) {
    String normalizedBankId = normalizeBankId(bankId);
    BankInterestConfig config = interestConfigs.get(normalizedBankId);
    if (config == null || !config.enabled()) {
      return;
    }

    BankDefinition bankDef = bankRegistry.bank(normalizedBankId).orElse(null);
    if (bankDef == null || !bankDef.enabled()) {
      return;
    }

    // Collect all online players and process interest only for online owners.
    var onlinePlayers = new ArrayList<Player>(Bukkit.getOnlinePlayers());
    if (onlinePlayers.isEmpty()) {
      return;
    }

    // Track statistics
    var stats = new AtomicInteger(0);
    var totalInterest = new AtomicReference<>(BigDecimal.ZERO);
    processInterestBatch(normalizedBankId, bankDef, config, onlinePlayers, 0, 32, stats, totalInterest)
        .thenRun(() -> {
          int playersProcessed = stats.get();
          if (playersProcessed > 0) {
            String message = String.format(
                "[BankInterest] Bank '%s' processed interest for %d player(s). Total interest distributed: %s (rate: %s)",
                normalizedBankId,
                playersProcessed,
                totalInterest.get().toPlainString(),
                describeInterestRate(bankId, config)
            );
            logger.logger().log(Level.INFO, message);
          }
        })
        .exceptionally(ex -> {
          logger.logger().log(Level.WARNING, "Error in bank interest summary", ex);
          return null;
        });
  }

  private CompletableFuture<Void> processInterestForPlayerAsync(
      String bankId,
      UUID playerUuid,
      BankDefinition bankDef,
      BankInterestConfig config,
      AtomicInteger playersProcessed,
      AtomicReference<BigDecimal> totalInterest
  ) {
    if (playerUuid == null) {
      return CompletableFuture.completedFuture(null);
    }

    return cacheService.loadOrCreate(bankId, playerUuid).thenCompose(view -> {
      if (view == null || view.account() == null || view.account().getId() == null) {
        return CompletableFuture.completedFuture(null);
      }

      var account = view.account();
      BigDecimal effectiveRate = resolveEffectiveRate(bankDef, account.getLevel(), config);
      if (effectiveRate.compareTo(BigDecimal.ZERO) <= 0) {
        return CompletableFuture.completedFuture(null);
      }

      MantissaAmount balance = view.balance() == null ? MantissaAmount.zero() : view.balance();
      if (balance.compareTo(MantissaAmount.zero()) <= 0) {
        return CompletableFuture.completedFuture(null);
      }

      BigDecimal currentHuman = balance.toHuman();
      BigDecimal interestAmount = currentHuman.multiply(effectiveRate);

      if (interestAmount.compareTo(BigDecimal.ZERO) <= 0) {
        return CompletableFuture.completedFuture(null);
      }

      BigDecimal maxBalance = resolveEffectiveMaxBalance(bankDef, account.getLevel(), config);
      BigDecimal newBalance = currentHuman.add(interestAmount);
      BigDecimal appliedInterest = interestAmount;
      boolean isCapped = false;

      if (maxBalance.compareTo(BigDecimal.ZERO) > 0 && newBalance.compareTo(maxBalance) > 0) {
        if (currentHuman.compareTo(maxBalance) >= 0) {
          var onlinePlayer = Bukkit.getPlayer(playerUuid);
          if (onlinePlayer != null && onlinePlayer.isOnline()) {
            var component = componentService.builder(onlinePlayer, "bank.interest.max-balance-reached", "NotDefined", true)
                .resolver(TagResolver.resolver(
                    Placeholder.parsed("bank", bankId)
                ))
                .build();
            onlinePlayer.sendMessage(component);
          }
          return CompletableFuture.completedFuture(null);
        }

        appliedInterest = maxBalance.subtract(currentHuman);
        isCapped = true;
      }

      final BigDecimal finalAppliedInterest = appliedInterest;
      final boolean finalIsCapped = isCapped;
      final BigDecimal finalInterestAmount = interestAmount;

      MantissaAmount interestMantissa = MantissaAmount.of(finalAppliedInterest, 0);
      playersProcessed.incrementAndGet();
      totalInterest.updateAndGet(current -> current.add(finalAppliedInterest));

      return bankRepo.applyBalanceDelta(account.getId(), interestMantissa).thenRun(() -> {
        cacheService.invalidate(account.getId());

        var onlinePlayer = Bukkit.getPlayer(playerUuid);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
          CurrencyDefinition currencyDef = currencyRegistry.currency(config.currencyId());
          int fractionDigits = currencyDef != null ? currencyDef.fractionDigits() : 0;

          MantissaAmount appliedInterestMantissa = MantissaAmount.of(finalAppliedInterest, 0);
          BigDecimal appliedInterestHuman = appliedInterestMantissa.toHuman();
          String formattedAppliedInterest = AmountNotation.formatShort(appliedInterestMantissa, fractionDigits);
          String currencySymbol = determineCurrencySymbol(appliedInterestHuman, currencyDef);

          if (finalIsCapped) {
            MantissaAmount earnedMantissa = MantissaAmount.of(finalInterestAmount, 0);
            BigDecimal earnedHuman = earnedMantissa.toHuman();
            String formattedEarned = AmountNotation.formatShort(earnedMantissa, fractionDigits);
            String earnedCurrencySymbol = determineCurrencySymbol(earnedHuman, currencyDef);
            String cappedCurrencySymbol = determineCurrencySymbol(appliedInterestHuman, currencyDef);

            var component = componentService.builder(onlinePlayer, "bank.interest.earned-capped", "NotDefined", true)
                .resolver(TagResolver.resolver(
                    Placeholder.parsed("bank", bankId),
                    Placeholder.parsed("earned", formattedEarned),
                    Placeholder.parsed("earned-currency", earnedCurrencySymbol),
                    Placeholder.parsed("capped", formattedAppliedInterest),
                    Placeholder.parsed("capped-currency", cappedCurrencySymbol)
                ))
                .build();
            onlinePlayer.sendMessage(component);
          } else {
            var component = componentService.builder(onlinePlayer, "bank.interest.earned", "NotDefined", true)
                .resolver(TagResolver.resolver(
                    Placeholder.parsed("bank", bankId),
                    Placeholder.parsed("amount", formattedAppliedInterest),
                    Placeholder.parsed("currency", currencySymbol)
                ))
                .build();
            onlinePlayer.sendMessage(component);
          }
        }
      }).exceptionally(ex -> {
        logger.logger().log(Level.WARNING, "Failed to apply interest for bank " + bankId + " player " + playerUuid, ex);
        return null;
      });
    }).exceptionally(ex -> {
      logger.logger().log(Level.WARNING, "Error processing interest for bank " + bankId + " player " + playerUuid, ex);
      return null;
    });
  }

  private CompletableFuture<Void> processInterestBatch(
      String bankId,
      BankDefinition bankDef,
      BankInterestConfig config,
      List<Player> onlinePlayers,
      int startIndex,
      int batchSize,
      AtomicInteger playersProcessed,
      AtomicReference<BigDecimal> totalInterest
  ) {
    if (onlinePlayers == null || startIndex >= onlinePlayers.size()) {
      return CompletableFuture.completedFuture(null);
    }

    int endIndex = Math.min(onlinePlayers.size(), startIndex + Math.max(1, batchSize));
    List<CompletableFuture<Void>> futures = new ArrayList<>(endIndex - startIndex);

    for (int i = startIndex; i < endIndex; i++) {
      Player player = onlinePlayers.get(i);
      if (player == null) continue;
      futures.add(processInterestForPlayerAsync(bankId, player.getUniqueId(), bankDef, config, playersProcessed, totalInterest));
    }

    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenCompose(ignored -> processInterestBatch(bankId, bankDef, config, onlinePlayers, endIndex, batchSize, playersProcessed, totalInterest));
  }

  private BigDecimal resolveEffectiveRate(BankDefinition bankDef, Integer level, BankInterestConfig config) {
    BigDecimal baseRate = resolveBaseRate(config);
    if (bankDef == null || level == null || level <= 0) {
      return baseRate;
    }

    BankDefinition.LevelDefinition levelDef = findLevel(bankDef, level);
    if (levelDef == null) {
      return baseRate;
    }

    BigDecimal levelRate = parseRate(levelDef.interestRateRaw());
    return levelRate.compareTo(BigDecimal.ZERO) > 0 ? levelRate : baseRate;
  }

  private BigDecimal resolveEffectiveMaxBalance(BankDefinition bankDef, Integer level, BankInterestConfig config) {
    BigDecimal baseMax = config.maxBalance();
    if (bankDef == null || level == null || level <= 0) {
      return baseMax;
    }

    BankDefinition.LevelDefinition levelDef = findLevel(bankDef, level);
    if (levelDef == null) {
      return baseMax;
    }

    String raw = levelDef.maxBalanceRaw();
    if (raw == null || raw.isBlank()) {
      return baseMax;
    }

    return parseAmount(raw);
  }

  private BigDecimal resolveBaseRate(BankInterestConfig config) {
    return config == null || config.rate() == null ? BigDecimal.ZERO : config.rate();
  }

  private static BankDefinition.LevelDefinition findLevel(BankDefinition def, int level) {
    if (def == null || def.levels() == null || def.levels().isEmpty()) {
      return null;
    }

    for (BankDefinition.LevelDefinition ld : def.levels()) {
      if (ld != null && ld.level() == level) {
        return ld;
      }
    }
    return null;
  }

  private static String normalizeBankId(String bankId) {
    return bankId == null ? "" : bankId.trim().toLowerCase(Locale.ROOT);
  }

  private static BigDecimal parseRate(String raw) {
    if (raw == null || raw.isBlank()) {
      return BigDecimal.ZERO;
    }

    try {
      String cleaned = raw.trim().replace("%", "");
      BigDecimal value = new BigDecimal(cleaned);
      return value.compareTo(BigDecimal.ONE) > 0 ? value.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP) : value;
    } catch (Exception ignored) {
      return BigDecimal.ZERO;
    }
  }

  private static BigDecimal parseAmount(String raw) {
    if (raw == null || raw.isBlank()) {
      return BigDecimal.ZERO;
    }

    try {
      String cleaned = raw.trim().toLowerCase();
      BigDecimal value = new BigDecimal(cleaned.replace("k", "").replace("m", ""));
      if (cleaned.contains("k")) {
        return value.multiply(new BigDecimal("1000"));
      }
      if (cleaned.contains("m")) {
        return value.multiply(new BigDecimal("1000000"));
      }
      return value;
    } catch (Exception ignored) {
      return BigDecimal.ZERO;
    }
  }

  private static String formatRatePercent(BigDecimal rate) {
    if (rate == null) {
      return "0%";
    }

    return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
  }

  private boolean hasAnyInterestRate(String bankId, BankInterestConfig config) {
    if (resolveBaseRate(config).compareTo(BigDecimal.ZERO) > 0) {
      return true;
    }

    BankDefinition bankDef = bankRegistry.bank(bankId).orElse(null);
    if (bankDef == null || bankDef.levels() == null || bankDef.levels().isEmpty()) {
      return false;
    }

    for (BankDefinition.LevelDefinition levelDef : bankDef.levels()) {
      if (levelDef == null) continue;
      if (parseRate(levelDef.interestRateRaw()).compareTo(BigDecimal.ZERO) > 0) {
        return true;
      }
    }

    return false;
  }

  private String describeInterestRate(String bankId, BankInterestConfig config) {
    BigDecimal baseRate = resolveBaseRate(config);
    BankDefinition bankDef = bankRegistry.bank(bankId).orElse(null);

    if (bankDef == null || bankDef.levels() == null || bankDef.levels().isEmpty()) {
      return formatRatePercent(baseRate);
    }

    boolean hasLevelSpecificRate = false;
    for (BankDefinition.LevelDefinition levelDef : bankDef.levels()) {
      if (levelDef == null) continue;
      BigDecimal levelRate = parseRate(levelDef.interestRateRaw());
      if (levelRate.compareTo(BigDecimal.ZERO) > 0 && levelRate.compareTo(baseRate) != 0) {
        hasLevelSpecificRate = true;
        break;
      }
    }

    return hasLevelSpecificRate ? "level-based" : formatRatePercent(baseRate);
  }

  private String determineCurrencySymbol(BigDecimal amount, CurrencyDefinition currencyDef) {
    if (currencyDef == null) {
      return "";
    }

    // Check if amount is 1 for singular, otherwise plural
    if (amount.compareTo(BigDecimal.ONE) == 0) {
      return currencyDef.symbolSingular();
    } else {
      return currencyDef.symbolPlural();
    }
  }

  private record BankInterestConfig(
      boolean enabled,
      BigDecimal rate,
      List<LocalTime> times,
      ZoneId zoneId,
      BigDecimal maxBalance,
      String currencyId
  ) {}
}








