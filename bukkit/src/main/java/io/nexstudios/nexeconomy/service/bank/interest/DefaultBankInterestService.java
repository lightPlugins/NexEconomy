package io.nexstudios.nexeconomy.service.bank.interest;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.repo.BankRepositoryService;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
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
    BankRepositoryService.class,
    ComponentService.class,
    BankAccountCacheService.class,
    CurrencyRegistryService.class
})
public final class DefaultBankInterestService implements BankInterestService {

  private final LoggerService logger;
  private final Plugin plugin;
  private final FileReaderService fileReader;
  private final BankRepositoryService bankRepo;
  private final ComponentService componentService;
  private final BankAccountCacheService cacheService;
  private final CurrencyRegistryService currencyRegistry;

  private final Map<String, BankInterestConfig> interestConfigs = new ConcurrentHashMap<>();
  private final Map<String, Long> lastProcessedTime = new ConcurrentHashMap<>();
  private BukkitTask scheduler;
  private boolean started = false;

  public DefaultBankInterestService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
    this.fileReader = accessor.getService(FileReaderService.class);
    this.bankRepo = accessor.getService(BankRepositoryService.class);
    this.componentService = accessor.getService(ComponentService.class);
    this.cacheService = accessor.getService(BankAccountCacheService.class);
    this.currencyRegistry = accessor.getService(CurrencyRegistryService.class);

    reload();
  }

  @Override
  public void start() {
    if (started || !plugin.isEnabled()) return;

    // Run scheduler every minute to check if any interest times have been reached
    this.scheduler = Bukkit.getScheduler().runTaskTimerAsynchronously(
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

    Path banksDir = plugin.getDataFolder().toPath().resolve("banks");
    File banksDirFile = banksDir.toFile();

    if (!banksDirFile.exists()) {
      return;
    }

    File[] bankFiles = banksDirFile.listFiles((dir, name) -> name.endsWith(".yml"));
    if (bankFiles == null) {
      return;
    }

    for (File bankFile : bankFiles) {
      String fileName = bankFile.getName();
      String bankId = fileName.substring(0, fileName.length() - 4);

      FileConfiguration config = fileReader.load(
          Path.of("banks/" + fileName),
          "banks/" + fileName,
          true
      );

      if (config == null) continue;

      BankInterestConfig interestConfig = loadInterestConfig(config);
      interestConfigs.put(bankId, interestConfig);
    }

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
      rate = rate.divide(new BigDecimal("100"));
    } catch (Exception ex) {
      rate = BigDecimal.ZERO;
    }

    List<String> timesList = config.getStringList("interest.times");
    if (timesList == null || timesList.isEmpty()) {
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

    BigDecimal maxBalance = BigDecimal.ZERO;
    String maxBalanceStr = config.getString("default-max-balance", "0");
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

        if (!config.enabled() || config.rate().compareTo(BigDecimal.ZERO) <= 0 || config.times().isEmpty()) {
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
    BankInterestConfig config = interestConfigs.get(bankId);
    if (config == null || !config.enabled()) {
      return;
    }

    // Collect all online players and process interest for all
    var onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
    if (onlinePlayers.isEmpty()) {
      return;
    }

    // Track statistics
    var stats = new AtomicInteger(0);
    var totalInterest = new AtomicReference<>(BigDecimal.ZERO);
    var futures = new ArrayList<CompletableFuture<Void>>();

    // Process interest for each online player
    for (var player : onlinePlayers) {
      var future = processInterestForPlayerAsync(bankId, player.getUniqueId(), config, stats, totalInterest);
      futures.add(future);
    }

    // When all futures complete, log summary
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenRun(() -> {
          int playersProcessed = stats.get();
          if (playersProcessed > 0) {
            String message = String.format(
                "[BankInterest] Bank '%s' processed interest for %d player(s). Total interest distributed: %s (rate: %.2f%%)",
                bankId,
                playersProcessed,
                totalInterest.get().toPlainString(),
                config.rate().multiply(new BigDecimal("100"))
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
      BankInterestConfig config,
      AtomicInteger playersProcessed,
      AtomicReference<BigDecimal> totalInterest
  ) {
    if (config.rate().compareTo(BigDecimal.ZERO) <= 0) {
      return CompletableFuture.completedFuture(null);
    }

    // Find bank account for this player as owner
    return bankRepo.findAccount(bankId, playerUuid).thenCompose(accountOpt -> {
      if (accountOpt.isEmpty()) {
        return CompletableFuture.completedFuture(null);
      }

      var account = accountOpt.get();
      if (account.getId() == null) {
        return CompletableFuture.completedFuture(null);
      }

      return bankRepo.loadBalance(account.getId()).thenCompose(balance -> {
        if (balance == null || balance.compareTo(MantissaAmount.zero()) <= 0) {
          return CompletableFuture.completedFuture(null);
        }

        BigDecimal currentHuman = balance.toHuman();
        BigDecimal interestAmount = currentHuman.multiply(config.rate());

        if (interestAmount.compareTo(BigDecimal.ZERO) <= 0) {
          return CompletableFuture.completedFuture(null);
        }

        // Check if bank has reached max balance
        BigDecimal maxBalance = config.maxBalance();
        BigDecimal newBalance = currentHuman.add(interestAmount);
        BigDecimal appliedInterest = interestAmount;
        boolean isCapped = false;

        if (maxBalance.compareTo(BigDecimal.ZERO) > 0 && newBalance.compareTo(maxBalance) > 0) {
          // Interest would exceed max balance
          if (currentHuman.compareTo(maxBalance) >= 0) {
            // Already at or above max balance - no interest earned
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
          } else {
            // Cap the interest to fit within max balance
            appliedInterest = maxBalance.subtract(currentHuman);
            isCapped = true;
          }
        }

        // Make final copies for use in lambda
        final BigDecimal finalAppliedInterest = appliedInterest;
        final boolean finalIsCapped = isCapped;

        // Apply interest
        MantissaAmount interestMantissa = MantissaAmount.of(finalAppliedInterest, 0);
        
        // Update statistics
        playersProcessed.incrementAndGet();
        totalInterest.updateAndGet(current -> current.add(finalAppliedInterest));

        return bankRepo.applyBalanceDelta(account.getId(), interestMantissa).thenRun(() -> {
          // Invalidate cache to force fresh load on next query
          cacheService.invalidate(account.getId());

          // Notify player if still online using ComponentService
          var onlinePlayer = Bukkit.getPlayer(playerUuid);
          if (onlinePlayer != null && onlinePlayer.isOnline()) {
            // Get currency using the currency ID from bank config, not bankId
            CurrencyDefinition currencyDef = currencyRegistry.currency(config.currencyId());
            int fractionDigits = currencyDef != null ? currencyDef.fractionDigits() : 0;

            // Convert to MantissaAmount with exp3=0, then toHuman() to get human-readable format
            MantissaAmount appliedInterestMantissa = MantissaAmount.of(finalAppliedInterest, 0);
            BigDecimal appliedInterestHuman = appliedInterestMantissa.toHuman();
            String formattedAppliedInterest = AmountNotation.formatShort(appliedInterestMantissa, fractionDigits);
            String currencySymbol = determineCurrencySymbol(appliedInterestHuman, currencyDef);

            if (finalIsCapped) {
              MantissaAmount earnedMantissa = MantissaAmount.of(interestAmount, 0);
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
      });
    }).exceptionally(ex -> {
      logger.logger().log(Level.WARNING, "Error processing interest for bank " + bankId + " player " + playerUuid, ex);
      return null;
    });
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








