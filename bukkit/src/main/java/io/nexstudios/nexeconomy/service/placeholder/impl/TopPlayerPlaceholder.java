package io.nexstudios.nexeconomy.service.placeholder.impl;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.EconomyPlayerCacheService;
import io.nexstudios.nexeconomy.service.economy.leaderboard.EconomyLeaderboardService;
import io.nexstudios.nexeconomy.service.placeholder.AbstractCurrencyPlaceholder;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Placeholder: {@code %nexeconomy:<currency>_top_player%}
 *
 * <p>Returns the calling player's leaderboard rank entry rendered via the
 * currency's {@code topPlaceholder} template. Returns {@code "loading"} while
 * the snapshot is not yet available, or {@code ""} if the player is unranked.</p>
 */
public final class TopPlayerPlaceholder extends AbstractCurrencyPlaceholder {

  private final EconomyPlayerCacheService cache;
  private final EconomyLeaderboardService leaderboard;

  public TopPlayerPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def, EconomyPlayerCacheService cache, EconomyLeaderboardService leaderboard) {
    super(service, def);
    this.cache = cache;
    this.leaderboard = leaderboard;
    register(this);
  }

  @Override
  public String id() {
    return currencyId + "_top_player";
  }

  @Override
  public Duration ttl() {
    return Duration.ZERO;
  }

  @Override
  public String resolve(PlaceholderResolveContext ctx) {
    Player p = tryResolvePlayer(ctx);
    if (p == null) return "";

    OptionalInt rankOpt = leaderboard.getRank(currencyId, p.getUniqueId());
    if (rankOpt.isEmpty()) return "loading";

    int rank = rankOpt.getAsInt();
    if (rank <= 0) return "";

    MantissaAmount a = readOnlineAmount(cache, currencyId, p.getUniqueId());
    String amountShown = AmountNotation.formatShort(a, def.fractionDigits());

    String template = def.topPlaceholder() == null ? "" : def.topPlaceholder();
    return applySimpleTemplate(template, Map.of(
        "number", String.valueOf(rank),
        "name", p.getName(),
        "amount", amountShown,
        "currency", def.symbolPlural()
    ));
  }
}

