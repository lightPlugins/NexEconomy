package io.nexstudios.nexeconomy.definition;

import java.math.BigDecimal;
import java.util.Objects;

public record MantissaAmount(BigDecimal mantissa, int exp3) {

  private static final MantissaAmount ZERO = new MantissaAmount(BigDecimal.ZERO, 0);

  public static MantissaAmount zero() {
    return ZERO;
  }

  public static MantissaAmount of(BigDecimal mantissa, int exp3) {
    if (mantissa == null) return zero();
    if (mantissa.compareTo(BigDecimal.ZERO) == 0) return zero();
    return normalize(new MantissaAmount(mantissa, exp3));
  }

  public static MantissaAmount parseStorage(String mantissaText, int exp3) {
    if (mantissaText == null || mantissaText.isBlank()) return zero();
    try {
      return of(new BigDecimal(mantissaText.trim()), exp3);
    } catch (Exception ignored) {
      return zero();
    }
  }

  public static MantissaAmount normalize(MantissaAmount a) {
    if (a == null || a.mantissa == null) return zero();
    if (a.mantissa.compareTo(BigDecimal.ZERO) == 0) return zero();

    BigDecimal m = a.mantissa.stripTrailingZeros();
    int e = a.exp3;

    // bring into a stable representation where possible:
    // try to keep |mantissa| in [1, 1000) by shifting in steps of 3 decimals (exact).
    BigDecimal abs = m.abs();
    BigDecimal thousand = new BigDecimal("1000");

    while (abs.compareTo(thousand) >= 0) {
      m = m.movePointLeft(3);
      e++;
      abs = abs.movePointLeft(3);
      // hard safety bound to keep suffix range sane
      if (e > 680) break;
    }

    while (abs.compareTo(BigDecimal.ONE) < 0) {
      m = m.movePointRight(3);
      e--;
      abs = abs.movePointRight(3);

      // if we ever hit zero, stop
      if (m.compareTo(BigDecimal.ZERO) == 0) return zero();
      if (e < -680) break;
    }

    // avoid "-0"
    if (m.compareTo(BigDecimal.ZERO) == 0) return zero();

    return new MantissaAmount(m, e);
  }

  /**
   * Realwert als "human" BigDecimal: mantissa * 1000^exp3 (exakt, da 1000 = 10^3).
   */
  public BigDecimal toHuman() {
    if (mantissa == null) return BigDecimal.ZERO;
    if (mantissa.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
    if (exp3 == 0) return mantissa;
    int shift = Math.multiplyExact(exp3, 3);
    return mantissa.movePointRight(shift);
  }

  /**
   * DB/storage representation: same as current (already normalized).
   */
  public record Storage(String mantissaText, int exp3) {}

  public Storage toStorage() {
    MantissaAmount n = normalize(this);
    BigDecimal m = n.mantissa == null ? BigDecimal.ZERO : n.mantissa;
    String text = m.stripTrailingZeros().toPlainString();
    return new Storage(text, n.exp3);
  }

  public boolean isNegative() {
    return mantissa != null && mantissa.compareTo(BigDecimal.ZERO) < 0;
  }

  public int compareTo(MantissaAmount other) {
    MantissaAmount b = other == null ? zero() : other;
    MantissaAmount a = normalize(this);
    b = normalize(b);

    if (a.mantissa == null || a.mantissa.compareTo(BigDecimal.ZERO) == 0) {
      return (b.mantissa == null || b.mantissa.compareTo(BigDecimal.ZERO) == 0) ? 0 : -b.mantissa.signum();
    }
    if (b.mantissa == null || b.mantissa.compareTo(BigDecimal.ZERO) == 0) {
      return a.mantissa.signum();
    }

    // align to the larger exp3 (keeps numbers small; exact because we shift by 3 decimals)
    int base = Math.max(a.exp3, b.exp3);

    BigDecimal am = shiftToExp3(a.mantissa, a.exp3, base);
    BigDecimal bm = shiftToExp3(b.mantissa, b.exp3, base);

    return am.compareTo(bm);
  }

  public double toDoubleApprox() {
    return toHuman().doubleValue();
  }

  public MantissaAmount add(MantissaAmount other) {
    if (other == null || other.mantissa == null || other.mantissa.compareTo(BigDecimal.ZERO) == 0) return normalize(this);

    MantissaAmount a = normalize(this);
    MantissaAmount b = normalize(other);

    int base = Math.max(a.exp3, b.exp3);

    BigDecimal am = shiftToExp3(a.mantissa, a.exp3, base);
    BigDecimal bm = shiftToExp3(b.mantissa, b.exp3, base);

    return normalize(new MantissaAmount(am.add(bm), base));
  }

  public MantissaAmount subtract(MantissaAmount other) {
    if (other == null || other.mantissa == null || other.mantissa.compareTo(BigDecimal.ZERO) == 0) return normalize(this);

    MantissaAmount a = normalize(this);
    MantissaAmount b = normalize(other);

    int base = Math.max(a.exp3, b.exp3);

    BigDecimal am = shiftToExp3(a.mantissa, a.exp3, base);
    BigDecimal bm = shiftToExp3(b.mantissa, b.exp3, base);

    return normalize(new MantissaAmount(am.subtract(bm), base));
  }

  private static BigDecimal shiftToExp3(BigDecimal mantissa, int fromExp3, int toExp3) {
    Objects.requireNonNull(mantissa, "mantissa");
    if (fromExp3 == toExp3) return mantissa;

    int diff = Math.subtractExact(fromExp3, toExp3);
    // mantissa(fromExp3) == mantissa(toExp3) * 1000^(to-from)
    // => mantissa(toExp3) = mantissa(fromExp3) * 1000^(from-to)
    int shift = Math.multiplyExact(diff, 3);
    return mantissa.movePointRight(shift);
  }
}