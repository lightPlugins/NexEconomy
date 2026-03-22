package io.nexstudios.nexeconomy.service.definition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AmountNotation {

  private static final Pattern PATTERN = Pattern.compile("^\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]{0,2})\\s*$");

  private AmountNotation() {}

  public static MantissaAmount parseToMantissaAmount(String raw) {
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
    int exp3 = suffixToExp3(suffix);
    if (exp3 == Integer.MIN_VALUE) return null;

    return MantissaAmount.of(value, exp3);
  }

  public static String formatShort(MantissaAmount amount, int fractionDigits) {
    if (amount == null) return "0";
    if (fractionDigits < 0) fractionDigits = 0;
    if (fractionDigits > 8) fractionDigits = 8;

    // If exp3 is negative, do not attach suffixes.
    // Render the full approximate numeric value as a plain decimal string.
    if (amount.exp3() < 0) {
      return formatApproxPlain(amount, fractionDigits);
    }

    String suffix = exp3ToSuffix(amount.exp3());

    BigDecimal shown = amount.mantissa() == null ? BigDecimal.ZERO : amount.mantissa();
    shown = shown.setScale(fractionDigits, RoundingMode.DOWN).stripTrailingZeros();

    String number = shown.toPlainString();
    return suffix.isEmpty() ? number : number + suffix;
  }

  private static String formatApproxPlain(MantissaAmount amount, int fractionDigits) {
    double approx = amount.toDoubleApprox();
    if (Double.isNaN(approx) || Double.isInfinite(approx)) {
      return "0";
    }

    BigDecimal bd = BigDecimal.valueOf(approx).setScale(fractionDigits, RoundingMode.DOWN).stripTrailingZeros();
    return bd.toPlainString();
  }

  private static int suffixToExp3(String suffix) {
    if (suffix == null || suffix.isBlank()) return 0;
    return switch (suffix) {
      case "k" -> 1;
      case "m" -> 2;
      case "b" -> 3;
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