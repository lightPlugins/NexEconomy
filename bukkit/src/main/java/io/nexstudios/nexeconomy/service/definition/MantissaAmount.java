package io.nexstudios.nexeconomy.service.definition;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record MantissaAmount(BigDecimal mantissa, int exp3) {

  private static final MantissaAmount ZERO = new MantissaAmount(
      BigDecimal.ZERO.setScale(2, RoundingMode.DOWN),
      0
  );

  public static MantissaAmount zero() {
    return ZERO;
  }

  public static MantissaAmount of(BigDecimal mantissa, int exp3) {
    if (mantissa == null) return zero();
    return normalize(new MantissaAmount(mantissa, exp3));
  }

  public static MantissaAmount normalize(MantissaAmount a) {
    if (a == null || a.mantissa == null) return zero();

    BigDecimal m = a.mantissa.setScale(2, RoundingMode.DOWN);
    int e = a.exp3;

    if (m.compareTo(BigDecimal.ZERO) == 0) return zero();

    BigDecimal thousand = new BigDecimal("1000.00");

    while (m.compareTo(thousand) >= 0) {
      m = m.divide(thousand, 2, RoundingMode.DOWN);
      e++;
    }

    while (m.compareTo(BigDecimal.ONE) < 0) {
      m = m.multiply(thousand).setScale(2, RoundingMode.DOWN);
      e--;
      if (m.compareTo(BigDecimal.ZERO) == 0) return zero();
    }

    return new MantissaAmount(m, e);
  }

  public boolean isNegative() {
    return mantissa != null && mantissa.compareTo(BigDecimal.ZERO) < 0;
  }

  public int compareTo(MantissaAmount other) {
    MantissaAmount o = other == null ? zero() : other;
    MantissaAmount a = this.mantissa == null ? zero() : this;

    if (a.mantissa.compareTo(BigDecimal.ZERO) == 0 && o.mantissa.compareTo(BigDecimal.ZERO) == 0) return 0;

    int targetExp = Math.max(a.exp3, o.exp3);
    BigDecimal aa = scaleToExp(a, targetExp);
    BigDecimal bb = scaleToExp(o, targetExp);
    return aa.compareTo(bb);
  }

  public double toDoubleApprox() {
    MantissaAmount a = this.mantissa == null ? zero() : this;
    if (a.mantissa.compareTo(BigDecimal.ZERO) == 0) return 0D;

    double m = a.mantissa.doubleValue();
    double factor = Math.pow(1000D, a.exp3);
    double out = m * factor;

    if (Double.isNaN(out)) return 0D;
    if (Double.isInfinite(out)) return out > 0 ? Double.MAX_VALUE : -Double.MAX_VALUE;
    return out;
  }

  public MantissaAmount add(MantissaAmount other) {
    if (other == null) return this;
    if (this.mantissa.compareTo(BigDecimal.ZERO) == 0) return other;
    if (other.mantissa.compareTo(BigDecimal.ZERO) == 0) return this;

    int targetExp = Math.max(this.exp3, other.exp3);

    BigDecimal a = scaleToExp(this, targetExp);
    BigDecimal b = scaleToExp(other, targetExp);

    return normalize(new MantissaAmount(a.add(b), targetExp));
  }

  public MantissaAmount subtract(MantissaAmount other) {
    if (other == null) return this;
    if (other.mantissa.compareTo(BigDecimal.ZERO) == 0) return this;

    int targetExp = Math.max(this.exp3, other.exp3);

    BigDecimal a = scaleToExp(this, targetExp);
    BigDecimal b = scaleToExp(other, targetExp);

    return normalize(new MantissaAmount(a.subtract(b), targetExp));
  }

  private static BigDecimal scaleToExp(MantissaAmount src, int targetExp3) {
    int diff = src.exp3 - targetExp3;
    BigDecimal m = src.mantissa;

    if (diff < -20) return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);

    BigDecimal thousand = new BigDecimal("1000.00");

    if (diff == 0) return m.setScale(2, RoundingMode.DOWN);

    if (diff > 0) {
      for (int i = 0; i < diff; i++) {
        m = m.multiply(thousand).setScale(2, RoundingMode.DOWN);
      }
      return m;
    }

    int steps = -diff;
    for (int i = 0; i < steps; i++) {
      m = m.divide(thousand, 2, RoundingMode.DOWN);
      if (m.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
    }
    return m.setScale(2, RoundingMode.DOWN);
  }
}