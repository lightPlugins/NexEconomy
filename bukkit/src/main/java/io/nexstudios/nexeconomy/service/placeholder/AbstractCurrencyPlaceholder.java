package io.nexstudios.nexeconomy.service.placeholder;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexlogic.bukkit.services.effects.context.BukkitContextKeys;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Abstract base for all per-currency economy placeholders.
 *
 * <p>Holds the {@link EconomyPlaceholderService}, the {@link CurrencyDefinition} and
 * a pre-normalised currency ID. Subclasses call {@link #register(EconomyPlaceholder)}
 * as the <em>last statement</em> of their constructor, after every field is assigned:</p>
 *
 * <pre>{@code
 * public MyPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def, ...) {
 *     super(service, def);
 *     this.someField = someField;
 *     register(this); // always last
 * }
 * }</pre>
 */
public abstract class AbstractCurrencyPlaceholder implements EconomyPlaceholder {

  private final EconomyPlaceholderService service;

  /** The currency this placeholder belongs to. */
  protected final CurrencyDefinition def;

  /** Lower-case, trimmed currency ID – safe to use as part of a placeholder key. */
  protected final String currencyId;

  protected AbstractCurrencyPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def) {
    this.service = service;
    this.def = def;
    this.currencyId = normalize(def.id());
  }

  /**
   * Registers this placeholder with the {@link EconomyPlaceholderService}.
   * Must be called at the end of every concrete constructor.
   *
   * @param placeholder {@code this}
   */
  protected final void register(EconomyPlaceholder placeholder) {
    service.register(placeholder);
  }

  // ─── Shared utilities ─────────────────────────────────────────────────────

  protected static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }

  protected static Player tryResolvePlayer(PlaceholderResolveContext ctx) {
    if (ctx == null || ctx.logicContext() == null) return null;
    return ctx.logicContext().get(BukkitContextKeys.PLAYER).orElse(null);
  }

  protected static MantissaAmount readOnlineAmount(EconomyPlayerCacheService cache, String currencyId, UUID playerId) {
    if (cache == null || playerId == null) return MantissaAmount.zero();
    var econ = cache.getOnline(playerId);
    if (econ == null) return MantissaAmount.zero();
    var entry = econ.entry(currencyId);
    MantissaAmount a = entry == null ? MantissaAmount.zero() : entry.amount();
    return a == null ? MantissaAmount.zero() : a;
  }

  protected static String applySimpleTemplate(String template, Map<String, String> values) {
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

  protected static String chooseSymbol(CurrencyDefinition def, MantissaAmount a) {
    if (def == null) return "";
    BigDecimal human = (a == null ? MantissaAmount.zero() : a).toHuman();
    return human.compareTo(BigDecimal.ONE) == 0 ? def.symbolSingular() : def.symbolPlural();
  }

  protected static String toRawHumanString(MantissaAmount a) {
    MantissaAmount n = a == null ? MantissaAmount.zero() : MantissaAmount.normalize(a);
    return n.toHuman().stripTrailingZeros().toPlainString();
  }
}

