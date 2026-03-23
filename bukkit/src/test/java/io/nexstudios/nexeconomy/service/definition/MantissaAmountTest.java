package io.nexstudios.nexeconomy.service.definition;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class MantissaAmountTest {

  @Test
  public void toHuman_isExactForExp3() {
    MantissaAmount a = MantissaAmount.of(new BigDecimal("1.234"), 2); // * 1000^2

    BigDecimal expected = new BigDecimal("1234000");
    BigDecimal actual = a.toHuman();

    assertEquals(0, actual.compareTo(expected), "Wert muss numerisch exakt gleich sein");
    assertEquals("1234000", actual.stripTrailingZeros().toPlainString(), "Anzeige als Plain-String");
  }

  @Test
  public void subtract_smallDecimalFromHuge_isExact() {
    // "huge": 1 * 1000^100  (extrem groß, aber BigDecimal kann das)
    MantissaAmount huge = MantissaAmount.of(new BigDecimal("1"), 100);
    MantissaAmount delta = MantissaAmount.of(new BigDecimal("0.75"), 0);

    MantissaAmount out = huge.subtract(delta);

    BigDecimal expected = huge.toHuman().subtract(new BigDecimal("0.75"));
    assertEquals(0, out.toHuman().compareTo(expected), "Subtraktion muss exakt sein (kein Runden)");
  }

  @Test
  public void add_and_subtract_roundTrip_keepsExactHumanValue() {
    MantissaAmount base = MantissaAmount.of(new BigDecimal("999.999"), 10);
    MantissaAmount delta = MantissaAmount.of(new BigDecimal("0.0000000000000001"), 0);

    MantissaAmount out = base.add(delta).subtract(delta);

    assertEquals(0, out.toHuman().compareTo(base.toHuman()));
  }

  @Test
  public void compareTo_alignsDifferentExp3Correctly() {
    MantissaAmount a = MantissaAmount.of(new BigDecimal("1"), 1);      // 1000
    MantissaAmount b = MantissaAmount.of(new BigDecimal("999.999"), 0); // 999.999

    assertTrue(a.compareTo(b) > 0);
    assertTrue(b.compareTo(a) < 0);
  }

  @Test
  public void storage_roundTrip_isStable() {
    MantissaAmount in = MantissaAmount.of(new BigDecimal("12345.6789"), 0);
    MantissaAmount.Storage st = in.toStorage();

    MantissaAmount out = MantissaAmount.parseStorage(st.mantissaText(), st.exp3());

    // wir vergleichen über Human-Wert, weil normalize() exp3/mantissa umformen darf
    assertEquals(0, out.toHuman().compareTo(in.toHuman()));
  }

  @Test
  public void normalize_zeroAndNegativeZeroBecomeZero() {
    assertSame(MantissaAmount.zero(), MantissaAmount.of(new BigDecimal("0.000"), 123));
    assertSame(MantissaAmount.zero(), MantissaAmount.parseStorage("0", 999));
    assertSame(MantissaAmount.zero(), MantissaAmount.parseStorage("-0.0000", 0));
  }
}