package io.nexstudios.nexeconomy.service.definition;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class AmountNotationTest {

  @Test
  void parse_plainNumber_virtual() {
    MantissaAmount a = AmountNotation.parseVirtualMantissaAmount("12.34");
    assertNotNull(a);
    assertEquals(0, a.toHuman().compareTo(new BigDecimal("12.34")));
  }

  @Test
  void parse_plainNumber_vault() {
    BigDecimal human = AmountNotation.parseVaultHuman("12.34");
    assertNotNull(human);
    assertEquals(0, human.compareTo(new BigDecimal("12.34")));
  }

  @Test
  void parse_suffix_k_m_b_t_vault() {
    assertEquals(0, AmountNotation.parseVaultHuman("1k").compareTo(new BigDecimal("1000")));
    assertEquals(0, AmountNotation.parseVaultHuman("2m").compareTo(new BigDecimal("2000000")));
    assertEquals(0, AmountNotation.parseVaultHuman("3b").compareTo(new BigDecimal("3000000000")));
    assertEquals(0, AmountNotation.parseVaultHuman("4t").compareTo(new BigDecimal("4000000000000")));
  }

  @Test
  void parse_suffix_aa_and_zz_areAccepted_virtual() {
    assertNotNull(AmountNotation.parseVirtualMantissaAmount("1aa"));
    assertNotNull(AmountNotation.parseVirtualMantissaAmount("1zz"));
  }

  @Test
  void parse_suffix_aa_da_zz_areExact_virtual() {
    MantissaAmount aa = AmountNotation.parseVirtualMantissaAmount("1aa");
    assertNotNull(aa);
    assertEquals(0, aa.toHuman().compareTo(new BigDecimal("1").movePointRight(3 * 5)));

    MantissaAmount da = AmountNotation.parseVirtualMantissaAmount("1da");
    assertNotNull(da);
    int exp3Da = 5 + (('d' - 'a') * 26); // 83
    assertEquals(0, da.toHuman().compareTo(new BigDecimal("1").movePointRight(3 * exp3Da)));

    MantissaAmount zz = AmountNotation.parseVirtualMantissaAmount("1zz");
    assertNotNull(zz);
    assertEquals(0, zz.toHuman().compareTo(new BigDecimal("1").movePointRight(3 * 680)));
  }

  @Test
  void parse_virtual_rejects_k_m_b_suffixes() {
    assertNull(AmountNotation.parseVirtualMantissaAmount("1k"));
    assertNull(AmountNotation.parseVirtualMantissaAmount("1m"));
    assertNull(AmountNotation.parseVirtualMantissaAmount("1b"));
  }

  @Test
  void parse_vault_rejects_two_letter_suffixes() {
    assertNull(AmountNotation.parseVaultHuman("1aa"));
    assertNull(AmountNotation.parseVaultHuman("1zz"));
  }

  @Test
  void parse_invalid_returnsNull() {
    assertNull(AmountNotation.parseVirtualMantissaAmount(""));
    assertNull(AmountNotation.parseVirtualMantissaAmount("abc"));
    assertNull(AmountNotation.parseVirtualMantissaAmount("1???"));
    assertNull(AmountNotation.parseVirtualMantissaAmount("1kkk"));

    assertNull(AmountNotation.parseVaultHuman(""));
    assertNull(AmountNotation.parseVaultHuman("abc"));
    assertNull(AmountNotation.parseVaultHuman("1???"));
    assertNull(AmountNotation.parseVaultHuman("1kkk"));
  }

  @Test
  void formatShort_zeroAndNull() {
    assertEquals("0", AmountNotation.formatShort(null, 2));
    assertEquals("0", AmountNotation.formatShort(MantissaAmount.zero(), 2));
  }

  @Test
  void formatShort_examples() {
    assertEquals("999", AmountNotation.formatShort(MantissaAmount.of(new BigDecimal("999"), 0), 0));
    assertEquals("1k", AmountNotation.formatShort(MantissaAmount.of(new BigDecimal("1000"), 0), 0));
    assertEquals("1.5k", AmountNotation.formatShort(MantissaAmount.of(new BigDecimal("1500"), 0), 1));
  }

  @Test
  void formatShort_negative() {
    assertEquals("-1.5k", AmountNotation.formatShort(MantissaAmount.of(new BigDecimal("-1500"), 0), 1));
  }
}