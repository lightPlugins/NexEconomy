package io.nexstudios.nexeconomy.service.definition;

import java.math.BigDecimal;

public record CurrencyDefinition(
    String id,
    String name,
    String symbolSingular,
    String symbolPlural,
    String placeholder,
    int fractionDigits,
    CurrencyType type,
    BigDecimal startBalance,
    BigDecimal maxBalance,
    boolean payable
) {}