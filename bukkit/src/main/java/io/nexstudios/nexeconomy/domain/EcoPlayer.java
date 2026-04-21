package io.nexstudios.nexeconomy.domain;

import io.nexstudios.nexeconomy.domain.container.BankContainer;
import io.nexstudios.nexeconomy.domain.container.VaultContainer;
import io.nexstudios.nexeconomy.domain.container.VirtualContainer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The central domain facade for a single online player's economy state.
 * <p>
 * All read access is <strong>synchronous</strong> – no CompletableFutures needed.
 * The data is pre-loaded into memory when the player connects and kept in sync
 * by the backing services until the player disconnects.
 *
 * <pre>{@code
 * EcoPlayer eco = EcoPlayer.of(player);
 * if (eco == null) return; // player data not yet loaded (rare, only during join)
 *
 * MantissaAmount coins  = eco.vault().balance("coins");
 * MantissaAmount gems   = eco.virtual().balance("gems");
 * int            level  = eco.banks().level("savings");
 * MantissaAmount bankBal = eco.banks().balance("savings");
 * }</pre>
 */
public final class EcoPlayer {

  // Set once during plugin startup by EcoPlayerRegistry
  static volatile EcoPlayerRegistry REGISTRY;

  private final UUID uuid;
  private final VaultContainer vault;
  private final VirtualContainer virtual;
  private final BankContainer banks;

  public EcoPlayer(UUID uuid, VaultContainer vault, VirtualContainer virtual, BankContainer banks) {
    this.uuid = uuid;
    this.vault = vault;
    this.virtual = virtual;
    this.banks = banks;
  }

  // ─── Static factory ───────────────────────────────────────────────────────

  /**
   * Returns the {@link EcoPlayer} for an online player, or {@code null} if not yet loaded.
   * A null result is safe and expected only in the brief window between join event and async load completion.
   */
  public static @Nullable EcoPlayer of(Player player) {
    return player == null ? null : of(player.getUniqueId());
  }

  /**
   * Returns the {@link EcoPlayer} for a UUID, or {@code null} if not loaded.
   */
  public static @Nullable EcoPlayer of(UUID uuid) {
    EcoPlayerRegistry reg = REGISTRY;
    return reg == null || uuid == null ? null : reg.get(uuid);
  }

  /**
   * Returns the {@link EcoPlayer} for an online player.
   *
   * @throws IllegalStateException if the player is not loaded (e.g. player is offline or data not ready yet)
   */
  public static EcoPlayer require(Player player) {
    EcoPlayer eco = of(player);
    if (eco == null) {
      throw new IllegalStateException("EcoPlayer not cached for " + (player == null ? "null" : player.getName()));
    }
    return eco;
  }

  // ─── Internal wiring ──────────────────────────────────────────────────────

  static void bindRegistry(EcoPlayerRegistry registry) {
    REGISTRY = registry;
  }

  // ─── Containers ───────────────────────────────────────────────────────────

  /**
   * Access to Vault (real-money) currency balances. Always non-null.
   */
  public VaultContainer vault() {
    return vault;
  }

  /**
   * Access to Virtual currency balances (e.g. gems, tokens). Always non-null.
   */
  public VirtualContainer virtual() {
    return virtual;
  }

  /**
   * Access to all bank accounts owned by this player. Always non-null.
   * Individual bank lookups may return null if the bank is not yet cached.
   */
  public BankContainer banks() {
    return banks;
  }

  // ─── Identity ─────────────────────────────────────────────────────────────

  public UUID uuid() {
    return uuid;
  }

  @Override
  public String toString() {
    return "EcoPlayer{uuid=" + uuid + "}";
  }
}

