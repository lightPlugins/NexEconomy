package io.nexstudios.economy.economy.support;

import io.nexstudios.economy.currency.NexCurrency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Common scaling and clamping helpers.
 */
public final class EcoMath {

    private EcoMath() { }

    public static BigDecimal scale(NexCurrency c, BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        int scale = Math.max(0, c.getFractionDigits());
        return v.setScale(scale, RoundingMode.DOWN);
    }

    public static BigDecimal clampMax(NexCurrency c, BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        var max = c.getMaxBalance();
        if (max == null || max.signum() < 0) return v; // -1 => unlimited
        if (v.compareTo(max) > 0) return max;
        return v;
    }
}