package io.nexstudios.nexeconomy.service.definition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AmountNotation {

  private static final Pattern PATTERN = Pattern.compile("^\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]{0,2})\\s*$");

  private AmountNotation() {}

  /**
   * Virtual: erlaubt große Suffixe inkl. aa..zz.
   * Optional kannst du hier später "ab t bis zz" streng machen.
   */
  public static MantissaAmount parseVirtualMantissaAmount(String raw) {
    Parsed p = parse(raw);
    if (p == null) return null;

    int exp3 = suffixToExp3Virtual(p.suffix());
    if (exp3 == Integer.MIN_VALUE) return null;

    // "Virtuell von t-zz": wenn Suffix gesetzt ist, muss es mindestens 't' sein.
    // (kein k/m/b für virtuell; wenn du k/m/b auch virtuell willst, entferne diesen Block)
    if (!p.suffix().isEmpty() && exp3 < 4) return null;

    return MantissaAmount.of(p.value(), exp3);
  }

  /**
   * Vault: nur human BigDecimal, Suffix nur k/m/b/t (kein aa..zz).
   */
  public static BigDecimal parseVaultHuman(String raw) {
    Parsed p = parse(raw);
    if (p == null) return null;

    int exp3 = suffixToExp3Vault(p.suffix());
    if (exp3 == Integer.MIN_VALUE) return null;

    // value * 1000^exp3 ist exakt als movePointRight(3*exp3)
    return p.value().movePointRight(3 * exp3);
  }

  private record Parsed(BigDecimal value, String suffix) {}

  private static Parsed parse(String raw) {
    if (raw == null) return null;
    Matcher m = PATTERN.matcher(raw);
    if (!m.matches()) return null;

    BigDecimal value;
    try {
      value = new BigDecimal(m.group(1));
    } catch (Exception ignored) {
      return null;
    }

    String suffix = m.group(2) == null ? "" : m.group(2).trim().toLowerCase(Locale.ROOT);
    return new Parsed(value, suffix);
  }

  public static String formatShort(MantissaAmount amount, int fractionDigits) {
    if (amount == null) return "0";
    if (fractionDigits < 0) fractionDigits = 0;
    if (fractionDigits > 8) fractionDigits = 8;

    BigDecimal human = amount.toHuman();
    boolean neg = human.compareTo(BigDecimal.ZERO) < 0;
    BigDecimal abs = neg ? human.negate() : human;

    if (abs.compareTo(BigDecimal.ZERO) == 0) return "0";

    int exp3 = 0;
    BigDecimal thousand = new BigDecimal("1000");
    while (abs.compareTo(thousand) >= 0) {
      abs = abs.divide(thousand, 32, RoundingMode.DOWN);
      exp3++;
      if (exp3 > 680) break;
    }

    String suffix = exp3ToSuffix(exp3);

    BigDecimal shown = abs.setScale(fractionDigits, RoundingMode.DOWN);
    String number = shown.toPlainString();

    String out = suffix.isEmpty() ? number : number + suffix;
    return neg ? "-" + out : out;
  }

  private static int suffixToExp3Vault(String suffix) {
    if (suffix == null || suffix.isBlank()) return 0;
    return switch (suffix) {
      case "k" -> 1;
      case "m" -> 2;
      case "b" -> 3;
      case "t" -> 4;
      default -> Integer.MIN_VALUE; // keine 2-letter Suffixe bei Vault
    };
  }

  private static int suffixToExp3Virtual(String suffix) {
    if (suffix == null || suffix.isBlank()) return 0;
    return switch (suffix) {
      // case "k" -> 1;
      // case "m" -> 2;
      // case "b" -> 3;
      case "t" -> 4;
      default -> {
        if (suffix.length() == 2 && isLowerAlpha(suffix.charAt(0)) && isLowerAlpha(suffix.charAt(1))) {
          int a = suffix.charAt(0) - 'a';
          int b = suffix.charAt(1) - 'a';
          yield 5 + (a * 26) + b; // aa -> 5, ab -> 6, ..., zz -> 680
        }
        yield Integer.MIN_VALUE;
      }
    };
  }

  private static String exp3ToSuffix(int exp3) {
    if (exp3 <= 0) return "";
    return switch (exp3) {
      case 1 -> "k";
      case 2 -> "m";
      case 3 -> "b";
      case 4 -> "t";
      default -> {
        int idx = exp3 - 5;
        if (idx > 26 * 26 - 1) {
          yield "";
        }
        char first = (char) ('a' + (idx / 26));
        char second = (char) ('a' + (idx % 26));
        yield "" + first + second;
      }
    };
  }

  private static boolean isLowerAlpha(char c) {
    return c >= 'a' && c <= 'z';
  }
}