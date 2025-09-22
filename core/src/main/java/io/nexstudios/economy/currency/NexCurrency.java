package io.nexstudios.economy.currency;

import net.kyori.adventure.text.Component;

import java.math.BigDecimal;
import java.util.List;

public interface NexCurrency {

    Component getName();
    Component getPluralSymbol();
    Component getSingularSymbol();
    String getPlaceholder();
    String getMainCommand();
    List<String> getAliases();
    int getFractionDigits();
    NexCurrencyType getCurrencyType();
    BigDecimal getStartBalance();
    BigDecimal getMaxBalance();

}
