package io.nexstudios.nexeconomy.service.economy.leaderboard;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexlogic.bukkit.services.entity.EconomyBalanceEntity;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Dependencies({
    LoggerService.class,
    EconomyRepository.class,
    FileReaderService.class
})
public final class EconomyLeaderboardService implements Service {

  private static final int DEFAULT_TOP_LIMIT = 10;
  private static final int HARD_CAP_ROWS_FOR_RANKING = 200_000;

  private static final int DEFAULT_TTL_SECONDS = 30;
  private static final int MIN_TTL_SECONDS = 5;
  private static final int MAX_TTL_SECONDS = 300;

  private final LoggerService logger;
  private final EconomyRepository repo;

  private final FileConfiguration settings;

  private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();

  private volatile Duration ttl = Duration.ofSeconds(DEFAULT_TTL_SECONDS);

  public EconomyLeaderboardService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.repo = accessor.getService(EconomyRepository.class);

    FileReaderService fileReader = accessor.getService(FileReaderService.class);
    this.settings = fileReader.load(Path.of("settings.yml"), "settings.yml", true);

    reload();
  }

  public void reload() {
    int seconds = settings == null ? DEFAULT_TTL_SECONDS : settings.getInt("leaderboard.ttl-seconds", DEFAULT_TTL_SECONDS);
    seconds = clamp(seconds);

    this.ttl = Duration.ofSeconds(seconds);
    this.snapshots.clear();

    logger.logger().info("Leaderboard cache settings loaded: ttlSeconds=" + seconds);
  }

  public Optional<SnapshotView> getTop(String currencyIdLower, int limit) {
    String cur = normalize(currencyIdLower);
    if (cur.isBlank()) return Optional.empty();
    int lim = limit <= 0 ? DEFAULT_TOP_LIMIT : Math.min(limit, 100);

    Snapshot snap = ensure(cur, lim, false);
    if (!snap.loaded) return Optional.empty();

    List<Row> top = snap.top == null ? List.of() : snap.top;
    if (top.size() > lim) top = top.subList(0, lim);

    return Optional.of(new SnapshotView(top, snap.rankByUuid));
  }

  public OptionalInt getRank(String currencyIdLower, UUID playerId) {
    String cur = normalize(currencyIdLower);
    if (cur.isBlank() || playerId == null) return OptionalInt.empty();

    Snapshot snap = ensure(cur, DEFAULT_TOP_LIMIT, true);
    if (!snap.loaded) return OptionalInt.empty();

    Integer r = snap.rankByUuid.get(playerId);
    return r == null ? OptionalInt.empty() : OptionalInt.of(r);
  }

  public boolean isLoading(String currencyIdLower) {
    String cur = normalize(currencyIdLower);
    if (cur.isBlank()) return false;
    Snapshot s = snapshots.get(cur);
    return s != null && !s.loaded && s.refreshing.get();
  }

  public void invalidate(String currencyIdLower) {
    String cur = normalize(currencyIdLower);
    if (cur.isBlank()) return;
    snapshots.remove(cur);
  }

  private Snapshot ensure(String cur, int topLimit, boolean needRankMap) {
    Snapshot snap = snapshots.computeIfAbsent(cur, k -> Snapshot.empty());

    Duration ttlNow = this.ttl;
    if (!snap.isExpired(ttlNow)) return snap;

    if (snap.refreshing.compareAndSet(false, true)) {
      refreshAsync(cur, Math.max(DEFAULT_TOP_LIMIT, topLimit), needRankMap);
    }

    return snap;
  }

  private void refreshAsync(String cur, int topLimit, boolean needRankMap) {
    if (needRankMap) {
      repo.loadAllBalancesForCurrency(cur, EconomyBalanceEntity.EconomyAccountType.PLAYER, HARD_CAP_ROWS_FOR_RANKING)
          .whenComplete((rows, err) -> completeRefresh(cur, topLimit, true, rows, err));
      return;
    }

    repo.topBalances(cur, topLimit, EconomyBalanceEntity.EconomyAccountType.PLAYER)
        .whenComplete((rows, err) -> completeRefresh(cur, topLimit, false, rows, err));
  }

  private void completeRefresh(
      String cur,
      int topLimit,
      boolean needRankMap,
      List<EconomyRepository.TopBalanceRow> rows,
      Throwable err
  ) {
    try {
      if (err != null) {
        logger.logger().warning("Leaderboard refresh failed for currency=" + cur + ": " + err.getMessage());
        return;
      }

      ArrayList<EconomyRepository.TopBalanceRow> list = new ArrayList<>(rows == null ? 0 : rows.size());
      if (rows != null) list.addAll(rows);

      list.sort((a, b) -> {
        MantissaAmount am = a == null ? MantissaAmount.zero() : a.amount();
        MantissaAmount bm = b == null ? MantissaAmount.zero() : b.amount();
        return bm.compareTo(am);
      });

      ArrayList<Row> top = new ArrayList<>(Math.min(topLimit, list.size()));
      HashMap<UUID, Integer> ranks = needRankMap ? new HashMap<>(Math.max(16, list.size() * 2)) : new HashMap<>(0);

      int rank = 0;
      for (EconomyRepository.TopBalanceRow r : list) {
        if (r == null || r.uuid() == null) continue;

        rank++;
        if (needRankMap) {
          ranks.putIfAbsent(r.uuid(), rank);
        }
        if (top.size() < topLimit) {
          top.add(new Row(r.uuid(), r.amount() == null ? MantissaAmount.zero() : r.amount()));
        }
        if (!needRankMap && top.size() >= topLimit) break;
      }

      snapshots.put(cur, Snapshot.ready(top, ranks));
    } finally {
      Snapshot s = snapshots.get(cur);
      if (s != null) s.refreshing.set(false);
    }
  }

  public record Row(UUID uuid, MantissaAmount amount) {}

  public record SnapshotView(List<Row> top, Map<UUID, Integer> ranks) {}

  private record Snapshot(boolean loaded, long loadedAtMs, List<Row> top, Map<UUID, Integer> rankByUuid, AtomicBoolean refreshing) {
    static Snapshot empty() {
      return new Snapshot(false, 0L, List.of(), Map.of(), new AtomicBoolean(false));
    }

    static Snapshot ready(List<Row> top, Map<UUID, Integer> rankByUuid) {
      return new Snapshot(true, System.currentTimeMillis(), top == null ? List.of() : List.copyOf(top),
          rankByUuid == null ? Map.of() : Map.copyOf(rankByUuid), new AtomicBoolean(false));
    }

    boolean isExpired(Duration ttl) {
      if (!loaded) return true;
      long age = System.currentTimeMillis() - loadedAtMs;
      return age > (ttl == null ? 0L : ttl.toMillis());
    }
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }

  private static int clamp(int value) {
    if (value < MIN_TTL_SECONDS) return MIN_TTL_SECONDS;
    return Math.min(value, MAX_TTL_SECONDS);
  }
}