package io.nexstudios.nexeconomy.service.placeholder;

import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.repo.EconomyRepository;
import io.nexstudios.nexeconomy.service.registry.CurrencyRegistryService;
import io.nexstudios.nexlogic.bukkit.services.effects.context.BukkitContextKeys;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexlogic.common.services.placeholder.PlaceholderService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Dependencies({
    LoggerService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyRepository.class
})
public final class EconomyPlaceholderService implements Service, AutoCloseable {

  private static final String OWNER = "nexeconomy";
  private static final String NAMESPACE = "nexeconomy";

  private final LoggerService logger;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyRepository repo;

  private final PlaceholderService placeholders;

  private final ConcurrentHashMap<String, TopSnapshot> topCache = new ConcurrentHashMap<>();
  // TODO: Add key to settings.yml
  private final Duration topTtl = Duration.ofSeconds(10); // db refresh time is 10 seconds, maybe adjust it later, but first let us test it

  public EconomyPlaceholderService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.repo = accessor.getService(EconomyRepository.class);

    this.placeholders = resolvePlaceholderServiceOrThrow();

    registerAll();
  }

  public void reload() {
    unregisterAll();
    topCache.clear();
    registerAll();
  }

  private void registerAll() {
    for (CurrencyDefinition def : currencies.currencies()) {
      if (def == null) continue;
      registerCurrency(def);
    }
    logger.logger().info("Registered NexLogic placeholders for " + currencies.currencies().size() + " currencies.");
  }

  private void registerCurrency(CurrencyDefinition def) {
    String cur = normalize(def.id());
    if (cur.isBlank()) return;

    // Player-context placeholders
    register(cur + "_amount_raw", ctx -> {
      Player p = tryResolvePlayer(ctx);
      if (p == null) return "NoPlayerFound";
      MantissaAmount a = readOnlineAmount(p.getUniqueId(), cur);
      return toRawHumanString(a);
    }, Duration.ofMillis(250));

    register(cur + "_amount", ctx -> {
      Player p = tryResolvePlayer(ctx);
      if (p == null) return "NoPlayerFound";
      MantissaAmount a = readOnlineAmount(p.getUniqueId(), cur);
      return AmountNotation.formatShort(a, def.fractionDigits());
    }, Duration.ofMillis(250));

    register(cur + "_format", ctx -> {
      Player p = tryResolvePlayer(ctx);
      if (p == null) return "NoPlayerFound";
      MantissaAmount a = readOnlineAmount(p.getUniqueId(), cur);

      String amountShown = AmountNotation.formatShort(a, def.fractionDigits());
      String symbol = chooseSymbol(def, a);

      String template = def.playerPlaceholder() == null ? "" : def.playerPlaceholder();
      return applySimpleTemplate(template, Map.of(
          "amount", amountShown,
          "symbol", symbol,
          "currency", def.symbolPlural()
      ));
    }, Duration.ofMillis(250)); //250 ms ttl time (maybe adjust it later, but first test it)

    // Non-player placeholders
    register(cur + "_symbol_singular", ctx -> def.symbolSingular(), Duration.ofSeconds(2000));
    register(cur + "_symbol_plural", ctx -> def.symbolPlural(), Duration.ofSeconds(2000));
    register(cur + "_name", ctx -> def.name(), Duration.ofSeconds(10));

    // Top placeholders 1..10
    for (int i = 1; i <= 10; i++) {
      final int place = i;
      register(cur + "_top_" + place, ctx -> resolveTopPlaceholder(def, place), Duration.ZERO);
    }
  }

  private String resolveTopPlaceholder(CurrencyDefinition def, int place) {
    String cur = normalize(def.id());
    if (cur.isBlank()) return "";
    if (place < 1 || place > 10) return "";

    TopSnapshot snap = topCache.computeIfAbsent(cur, k -> TopSnapshot.empty(topTtl));

    if (snap.isExpired()) {
      if (snap.refreshing.compareAndSet(false, true)) {
        repo.topBalances(cur, 10).whenComplete((rows, err) -> {
          try {
            if (err != null) {
              logger.logger().warning("Failed to load top balances for currency=" + cur + ": " + err.getMessage());
              return;
            }

            String[] formatted = new String[10];

            for (int idx = 0; idx < 10; idx++) {
              if (rows == null || idx >= rows.size()) {
                formatted[idx] = "";
                continue;
              }

              EconomyRepository.TopBalanceRow row = rows.get(idx);
              UUID uuid = row == null ? null : row.uuid();
              MantissaAmount amount = row == null ? MantissaAmount.zero() : row.amount();

              OfflinePlayer off = uuid == null ? null : Bukkit.getOfflinePlayer(uuid);
              String name = off == null || off.getName() == null ? (uuid == null ? "unknown" : uuid.toString()) : off.getName();

              String amountShown = AmountNotation.formatShort(amount, def.fractionDigits());

              String template = def.topPlaceholder() == null ? "" : def.topPlaceholder();
              formatted[idx] = applySimpleTemplate(template, Map.of(
                  "number", String.valueOf(idx + 1),
                  "name", name,
                  "amount", amountShown,
                  "currency", def.symbolPlural()
              ));
            }

            topCache.put(cur, TopSnapshot.ready(formatted, topTtl));
          } finally {
            TopSnapshot s = topCache.get(cur);
            if (s != null) s.refreshing.set(false);
          }
        });
      }
    }

    // Return current cached values (even while refresh is running)
    TopSnapshot now = topCache.get(cur);
    if (now == null || now.values == null) return "";
    String out = now.values[place - 1];
    return out == null ? "" : out;
  }

  private MantissaAmount readOnlineAmount(UUID playerId, String currencyIdLower) {
    if (playerId == null) return MantissaAmount.zero();
    if (currencyIdLower == null || currencyIdLower.isBlank()) return MantissaAmount.zero();

    var econ = cache.getOnline(playerId);
    if (econ == null) return MantissaAmount.zero();

    var entry = econ.entry(currencyIdLower);
    MantissaAmount a = entry == null ? MantissaAmount.zero() : entry.amount();
    return a == null ? MantissaAmount.zero() : a;
  }

  private static String toRawHumanString(MantissaAmount a) {
    MantissaAmount n = a == null ? MantissaAmount.zero() : MantissaAmount.normalize(a);
    BigDecimal human = n.toHuman();
    return human.stripTrailingZeros().toPlainString();
  }

  private static String chooseSymbol(CurrencyDefinition def, MantissaAmount a) {
    if (def == null) return "";
    BigDecimal human = (a == null ? MantissaAmount.zero() : a).toHuman();
    boolean singular = human.compareTo(BigDecimal.ONE) == 0;
    return singular ? def.symbolSingular() : def.symbolPlural();
  }

  private void register(String id, Function<PlaceholderResolveContext, String> resolver, Duration ttl) {
    placeholders.register(
        OWNER,
        NAMESPACE,
        id,
        ctx -> {
          try {
            String out = resolver.apply(ctx);
            return out == null ? "" : out;
          } catch (Exception ex) {
            return "";
          }
        },
        ttl == null ? Duration.ZERO : ttl
    );
  }

  private void unregisterAll() {
    try {
      placeholders.unregisterOwner(OWNER);
    } catch (Exception ignored) {
      // no-op
    }
  }

  @Override
  public void close() {
    unregisterAll();
  }

  private PlaceholderService resolvePlaceholderServiceOrThrow() {
    var accessor = NexEconomyPlugin.getNexLogicService();
    if (accessor == null) {
      throw new IllegalStateException("NexLogic service accessor is not initialized.");
    }
    return accessor.getService(PlaceholderService.class);
  }

  private static String applySimpleTemplate(String template, Map<String, String> values) {
    if (template == null || template.isBlank()) return "";
    String out = template;

    for (var e : values.entrySet()) {
      String k = e.getKey();
      String v = e.getValue();
      if (k == null || k.isBlank()) continue;
      out = out.replace("<" + k + ">", v == null ? "" : v);
    }

    return out;
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }

  private static Player tryResolvePlayer(PlaceholderResolveContext ctx) {
    if (ctx == null || ctx.logicContext() == null) return null;
    return ctx.logicContext().get(BukkitContextKeys.PLAYER).orElse(null);
  }

  private record TopSnapshot(long expiresAtMs, String[] values, AtomicBoolean refreshing) {

    static TopSnapshot ready(String[] values, Duration ttl) {
      return new TopSnapshot(System.currentTimeMillis() + ttl.toMillis(), values, new AtomicBoolean(false));
    }

    static TopSnapshot empty(Duration ttl) {
      return new TopSnapshot(0L, new String[10], new AtomicBoolean(false));
    }

    boolean isExpired() {
      return System.currentTimeMillis() > expiresAtMs;
    }
  }
}