package io.nexstudios.nexeconomy.definition;

import java.math.BigDecimal;

public record CurrencyDefinition(
    String id,
    String name,
    String symbolSingular,
    String symbolPlural,
    String playerPlaceholder,
    String topPlaceholder,
    int fractionDigits,
    CurrencyType type,
    BigDecimal startBalance,
    BigDecimal maxBalance,
    boolean payable
) {}