package io.nexstudios.economy.model;

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
    CurrencyType getCurrencyType();
    BigDecimal getStartBalance();
    BigDecimal getMaxBalance();

}
