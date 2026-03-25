package io.nexstudios.nexeconomy.service.placeholder;

import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.leaderboard.EconomyLeaderboardService;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

@Dependencies({
    LoggerService.class,
    CurrencyRegistryService.class,
    EconomyPlayerCacheService.class,
    EconomyLeaderboardService.class
})
public final class EconomyPlaceholderService implements Service, AutoCloseable {

  private static final String OWNER = "nexeconomy";
  private static final String NAMESPACE = "nexeconomy";

  private final LoggerService logger;
  private final CurrencyRegistryService currencies;
  private final EconomyPlayerCacheService cache;
  private final EconomyLeaderboardService leaderboard;

  private final PlaceholderService placeholders;

  public EconomyPlaceholderService(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.currencies = accessor.getService(CurrencyRegistryService.class);
    this.cache = accessor.getService(EconomyPlayerCacheService.class);
    this.leaderboard = accessor.getService(EconomyLeaderboardService.class);

    this.placeholders = resolvePlaceholderServiceOrThrow();

    registerAll();
  }

  public void reload() {
    unregisterAll();
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
      if (p == null) return "";
      MantissaAmount a = readOnlineAmount(p.getUniqueId(), cur);
      return toRawHumanString(a);
    }, Duration.ofMillis(250));

    register(cur + "_amount", ctx -> {
      Player p = tryResolvePlayer(ctx);
      if (p == null) return "";
      MantissaAmount a = readOnlineAmount(p.getUniqueId(), cur);
      return AmountNotation.formatShort(a, def.fractionDigits());
    }, Duration.ofMillis(250));

    register(cur + "_format", ctx -> {
      Player p = tryResolvePlayer(ctx);
      if (p == null) return "";
      MantissaAmount a = readOnlineAmount(p.getUniqueId(), cur);

      String amountShown = AmountNotation.formatShort(a, def.fractionDigits());
      String symbol = chooseSymbol(def, a);

      String template = def.playerPlaceholder() == null ? "" : def.playerPlaceholder();
      return applySimpleTemplate(template, Map.of(
          "amount", amountShown,
          "symbol", symbol,
          "currency", def.symbolPlural()
      ));
    }, Duration.ofMillis(250));

    // Non-player placeholders
    register(cur + "_symbol_singular", ctx -> def.symbolSingular(), Duration.ofSeconds(2000));
    register(cur + "_symbol_plural", ctx -> def.symbolPlural(), Duration.ofSeconds(2000));
    register(cur + "_name", ctx -> def.name(), Duration.ofSeconds(10));

    // Top placeholders 1..10 (no NexLogic cache; leaderboard service handles TTL)
    for (int i = 1; i <= 10; i++) {
      final int place = i;
      register(cur + "_top_" + place, ctx -> resolveTopPlace(def, place), Duration.ZERO);
    }

    // New: %nexeconomy:<currency>_top_player% (player context)
    register(cur + "_top_player", ctx -> resolveTopPlayer(def, ctx), Duration.ZERO);
  }

  private String resolveTopPlace(CurrencyDefinition def, int place) {
    if (def == null) return "";
    if (place < 1 || place > 10) return "";

    String cur = normalize(def.id());
    if (cur.isBlank()) return "";

    Optional<EconomyLeaderboardService.SnapshotView> viewOpt = leaderboard.getTop(cur, 10);
    if (viewOpt.isEmpty()) {
      return "loading";
    }

    var view = viewOpt.get();
    if (view.top() == null || view.top().size() < place) return "";

    EconomyLeaderboardService.Row row = view.top().get(place - 1);
    if (row == null || row.uuid() == null) return "";

    UUID uuid = row.uuid();
    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
    String name = off.getName() == null ? uuid.toString() : off.getName();

    MantissaAmount amount = row.amount() == null ? MantissaAmount.zero() : row.amount();
    String amountShown = AmountNotation.formatShort(amount, def.fractionDigits());

    String template = def.topPlaceholder() == null ? "" : def.topPlaceholder();
    return applySimpleTemplate(template, Map.of(
        "number", String.valueOf(place),
        "name", name,
        "amount", amountShown,
        "currency", def.symbolPlural()
    ));
  }

  private String resolveTopPlayer(CurrencyDefinition def, PlaceholderResolveContext ctx) {
    if (def == null) return "";

    Player p = tryResolvePlayer(ctx);
    if (p == null) return "";

    String cur = normalize(def.id());
    if (cur.isBlank()) return "";

    OptionalInt rankOpt = leaderboard.getRank(cur, p.getUniqueId());
    if (rankOpt.isEmpty()) {
      return "loading";
    }

    int rank = rankOpt.getAsInt();
    if (rank <= 0) return "";

    MantissaAmount a = readOnlineAmount(p.getUniqueId(), cur);
    String amountShown = AmountNotation.formatShort(a, def.fractionDigits());

    String template = def.topPlaceholder() == null ? "" : def.topPlaceholder();
    return applySimpleTemplate(template, Map.of(
        "number", String.valueOf(rank),
        "name", p.getName(),
        "amount", amountShown,
        "currency", def.symbolPlural()
    ));
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
}