package io.nexstudios.nexeconomy.service.migration.impl;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexeconomy.service.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.migration.MigrationImporter;
import io.nexstudios.nexeconomy.service.migration.MigrationRequest;
import io.nexstudios.nexeconomy.service.migration.MigrationResult;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Dependencies({
    EconomyRepository.class,
    PaperPluginService.class
})
public final class VaultEconomyMigrationImporter implements Service, MigrationImporter {

  private static final int READ_BATCH_SIZE = 10; // players per tick (sync)
  private static final long READ_INTERVAL_TICKS = 1L;

  private final EconomyRepository repo;
  private final Plugin plugin;

  public VaultEconomyMigrationImporter(ServiceAccessor accessor) {
    this.repo = accessor.getService(EconomyRepository.class);
    this.plugin = accessor.getService(PaperPluginService.class).plugin();
  }

  @Override
  public String id() {
    return "vault";
  }

  @Override
  public boolean isAvailable() {
    if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
    RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
    return rsp != null;
  }

  @Override
  public CompletableFuture<MigrationResult> migrate(MigrationRequest request) {
    CompletableFuture<MigrationResult> out = new CompletableFuture<>();

    Bukkit.getScheduler().runTask(plugin, () -> {
      try {
        OfflinePlayer[] playersSnapshot = Bukkit.getOfflinePlayers();

        String targetCurrency = normalizeCurrencyId(request.targetCurrencyIdLower());
        if (targetCurrency.isBlank()) {
          out.completeExceptionally(new IllegalArgumentException("targetCurrencyIdLower is blank"));
          return;
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
          out.completeExceptionally(new IllegalStateException("No Vault Economy provider registered"));
          return;
        }

        Economy provider = rsp.getProvider();

        // Safety: migrating from ourselves would just re-import NexEconomy values (and may be 0 for offline).
        if ("NexEconomy".equalsIgnoreCase(provider.getName())) {
          out.completeExceptionally(new IllegalStateException(
              "Vault provider is NexEconomy. Stop NexEconomy Vault-bridge (or remove NexEconomy temporarily) and retry migration."
          ));
          return;
        }

        startPipeline(request, targetCurrency, playersSnapshot, provider, out);
      } catch (Exception ex) {
        out.completeExceptionally(ex);
      }
    });

    return out;
  }

  private void startPipeline(
      MigrationRequest request,
      String targetCurrency,
      OfflinePlayer[] players,
      Economy economy,
      CompletableFuture<MigrationResult> out
  ) {
    int limit = request.limit() <= 0 ? Integer.MAX_VALUE : request.limit();
    BigDecimal min = request.minBalanceHuman() == null ? BigDecimal.ZERO : request.minBalanceHuman();

    AtomicInteger index = new AtomicInteger(0);

    AtomicInteger processed = new AtomicInteger(0);
    AtomicInteger written = new AtomicInteger(0);
    AtomicInteger skipped = new AtomicInteger(0);
    AtomicInteger failed = new AtomicInteger(0);

    BigDecimal[] totalImported = new BigDecimal[] { BigDecimal.ZERO };

    AtomicReference<CompletableFuture<Void>> writerChainRef =
        new AtomicReference<>(CompletableFuture.completedFuture((Void) null));

    BukkitTask readerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
      if (out.isDone()) return;

      int start = index.get();
      if (start >= players.length || processed.get() >= limit) {
        stopAndComplete(out, writerChainRef.get(), request, targetCurrency, processed, written, skipped, failed, totalImported);
        return;
      }

      int remainingPlayers = players.length - start;
      int remainingLimit = limit - processed.get();
      int take = Math.min(READ_BATCH_SIZE, Math.min(remainingPlayers, remainingLimit));

      ArrayList<PlayerBalance> batch = new ArrayList<>(take);

      for (int i = 0; i < take; i++) {
        OfflinePlayer p = players[start + i];
        if (p == null) {
          failed.incrementAndGet();
          continue;
        }

        UUID uuid = p.getUniqueId();
        if (uuid == null) {
          failed.incrementAndGet();
          continue;
        }

        processed.incrementAndGet();

        try {
          double bal = economy.getBalance(p); // sync by design
          batch.add(new PlayerBalance(uuid, BigDecimal.valueOf(bal)));
        } catch (Exception ex) {
          failed.incrementAndGet();
        }
      }

      index.addAndGet(take);

      CompletableFuture<Void> next = writerChainRef.get().thenCompose(ignored ->
          processBatchAsync(request, targetCurrency, batch, min, written, skipped, failed, totalImported)
      ).exceptionally(ex -> {
        out.completeExceptionally(ex);
        return null;
      });

      writerChainRef.set(next);
    }, 0L, READ_INTERVAL_TICKS);

    out.whenComplete((r, e) -> {
      try {
        readerTask.cancel();
      } catch (Exception ignored) {
        // no-op
      }
    });
  }

  private void stopAndComplete(
      CompletableFuture<MigrationResult> out,
      CompletableFuture<Void> writerChain,
      MigrationRequest request,
      String targetCurrency,
      AtomicInteger processed,
      AtomicInteger written,
      AtomicInteger skipped,
      AtomicInteger failed,
      BigDecimal[] totalImported
  ) {
    writerChain.whenComplete((v, err) -> {
      if (err != null) {
        out.completeExceptionally(err);
        return;
      }

      out.complete(new MigrationResult(
          id(),
          targetCurrency,
          request.dryRun(),
          processed.get(),
          written.get(),
          skipped.get(),
          failed.get(),
          totalImported[0]
      ));
    });
  }

  private CompletableFuture<Void> processBatchAsync(
      MigrationRequest request,
      String targetCurrency,
      ArrayList<PlayerBalance> batch,
      BigDecimal min,
      AtomicInteger written,
      AtomicInteger skipped,
      AtomicInteger failed,
      BigDecimal[] totalImported
  ) {
    CompletableFuture<Void> chain = CompletableFuture.completedFuture((Void) null);

    for (PlayerBalance row : batch) {
      if (row == null || row.playerId == null) continue;

      BigDecimal balHuman = row.balanceHuman == null ? BigDecimal.ZERO : row.balanceHuman;

      chain = chain.thenCompose(ignored -> {
        if (balHuman.compareTo(min) < 0) {
          skipped.incrementAndGet();
          return CompletableFuture.completedFuture((Void) null);
        }

        if (request.dryRun()) {
          totalImported[0] = totalImported[0].add(balHuman);
          skipped.incrementAndGet();
          return CompletableFuture.completedFuture((Void) null);
        }

        return repo.loadBalances(row.playerId, Set.of(targetCurrency)).thenCompose(existing -> {
          MantissaAmount current = existing.get(targetCurrency);

          if (!request.overwriteExisting() && current != null && current.compareTo(MantissaAmount.zero()) != 0) {
            skipped.incrementAndGet();
            return CompletableFuture.completedFuture((Void) null);
          }

          MantissaAmount value = MantissaAmount.of(balHuman, 0);
          return repo.upsertBulk(row.playerId, Map.of(targetCurrency, value)).thenRun(() -> {
            written.incrementAndGet();
            totalImported[0] = totalImported[0].add(balHuman);
          });
        });
      }).exceptionally(ex -> {
        failed.incrementAndGet();
        return null;
      });
    }

    return chain;
  }

  private static final class PlayerBalance {
    private final UUID playerId;
    private final BigDecimal balanceHuman;

    private PlayerBalance(UUID playerId, BigDecimal balanceHuman) {
      this.playerId = playerId;
      this.balanceHuman = balanceHuman;
    }
  }

  private static String normalizeCurrencyId(String id) {
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }
}