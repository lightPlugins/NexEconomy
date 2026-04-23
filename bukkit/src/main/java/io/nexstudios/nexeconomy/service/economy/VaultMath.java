package io.nexstudios.nexeconomy.service.economy;

import io.nexstudios.nexeconomy.definition.CurrencyDefinition;
import io.nexstudios.nexeconomy.definition.CurrencyType;
import io.nexstudios.nexeconomy.definition.MantissaAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared math helpers for Vault-type currency operations.
 * Eliminates duplication between {@link io.nexstudios.nexeconomy.domain.container.VaultContainer}
 * and {@link EconomyService}.
 */
public final class VaultMath {

  private static final BigDecimal VAULT_DOUBLE_SAFE_INTEGER_LIMIT = new BigDecimal("9000000000000000");

  private VaultMath() {}

  /** Rounds {@code human} to the currency's fraction-digits using FLOOR (DOWN). */
  public static BigDecimal scaleVaultHuman(CurrencyDefinition def, BigDecimal human) {
    if (human == null) return BigDecimal.ZERO;
    int fd = def == null ? 0 : Math.clamp(def.fractionDigits(), 0, 8);
    return human.setScale(fd, RoundingMode.DOWN);
  }

  /** Clamps {@code human} to ±(9e15 / 10^fractionDigits). */
  public static BigDecimal clampVaultHuman(CurrencyDefinition def, BigDecimal human) {
    if (human == null) return BigDecimal.ZERO;
    int fd = def == null ? 0 : Math.clamp(def.fractionDigits(), 0, 8);
    BigDecimal cap = VAULT_DOUBLE_SAFE_INTEGER_LIMIT.movePointLeft(fd);
    if (human.compareTo(cap) > 0) return cap;
    if (human.compareTo(cap.negate()) < 0) return cap.negate();
    return human;
  }

  /**
   * Caps {@code requested} so that {@code current + requested} does not exceed
   * {@link CurrencyDefinition#maxBalance()}. Returns {@code requested} unchanged if no max is set.
   */
  public static MantissaAmount capDeltaToMax(CurrencyDefinition def, MantissaAmount current, MantissaAmount requested) {
    if (def == null) return requested;
    BigDecimal max = def.maxBalance();
    if (max == null || max.compareTo(BigDecimal.ZERO) < 0) return requested; // unlimited

    BigDecimal cur = (current == null ? MantissaAmount.zero() : MantissaAmount.normalize(current)).toHuman();
    BigDecimal req = (requested == null ? MantissaAmount.zero() : MantissaAmount.normalize(requested)).toHuman();

    BigDecimal remaining = max.subtract(cur);
    if (remaining.compareTo(BigDecimal.ZERO) <= 0) return MantissaAmount.zero();

    BigDecimal allowed = req.min(remaining);
    return allowed.compareTo(BigDecimal.ZERO) <= 0 ? MantissaAmount.zero() : MantissaAmount.of(allowed, 0);
  }

  /**
   * Normalises {@code amount} for the given currency:
   * <ul>
   *   <li>Vault: rounds to fraction-digits and clamps to safe-integer range (exp=0).</li>
   *   <li>Virtual / null: normalises mantissa only.</li>
   * </ul>
   */
  public static MantissaAmount normalizeForCurrency(CurrencyDefinition def, MantissaAmount amount) {
    if (amount == null) return MantissaAmount.zero();
    MantissaAmount n = MantissaAmount.normalize(amount);

    if (def != null && def.type() == CurrencyType.VAULT) {
      BigDecimal human = scaleVaultHuman(def, n.toHuman());
      human = clampVaultHuman(def, human);
      return MantissaAmount.of(human, 0);
    }

    return n;
  }

  /**
   * Normalises a Vault delta: rounds + clamps, returns an amount with exp=0.
   * Equivalent to {@link #normalizeForCurrency} but asserts Vault context.
   */
  public static MantissaAmount normalizeForVault(CurrencyDefinition def, MantissaAmount amount) {
    if (amount == null) return MantissaAmount.zero();
    BigDecimal human = MantissaAmount.normalize(amount).toHuman();
    human = scaleVaultHuman(def, human);
    human = clampVaultHuman(def, human);
    return MantissaAmount.of(human, 0);
  }
}

