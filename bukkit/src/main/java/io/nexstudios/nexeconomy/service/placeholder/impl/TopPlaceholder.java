package io.nexstudios.nexeconomy.service.placeholder.impl;

import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.service.economy.leaderboard.EconomyLeaderboardService;
import io.nexstudios.nexeconomy.service.placeholder.AbstractCurrencyPlaceholder;
import io.nexstudios.nexeconomy.service.placeholder.EconomyPlaceholderService;
import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Placeholder: {@code %nexeconomy:<currency>_top_<place>%}
 *
 * <p>Returns the formatted leaderboard entry for the given rank (1-based).
 * Returns {@code "loading"} while the snapshot is not yet available.</p>
 */
public final class TopPlaceholder extends AbstractCurrencyPlaceholder {

  private final EconomyLeaderboardService leaderboard;
  private final int place;

  public TopPlaceholder(EconomyPlaceholderService service, CurrencyDefinition def, EconomyLeaderboardService leaderboard, int place) {
    super(service, def);
    this.leaderboard = leaderboard;
    this.place = place;
    register(this);
  }

  @Override
  public String id() {
    return currencyId + "_top_" + place;
  }

  @Override
  public Duration ttl() {
    return Duration.ZERO; // TTL is managed by the leaderboard service itself
  }

  @Override
  public String resolve(PlaceholderResolveContext ctx) {
    Optional<EconomyLeaderboardService.SnapshotView> viewOpt = leaderboard.getTop(currencyId, 10);
    if (viewOpt.isEmpty()) return "loading";

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
}

