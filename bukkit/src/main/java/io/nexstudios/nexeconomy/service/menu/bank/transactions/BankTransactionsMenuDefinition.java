package io.nexstudios.nexeconomy.service.menu.bank.transactions;

import io.nexstudios.menuservice.api.MenuContext;
import io.nexstudios.menuservice.api.MenuDefinition;
import io.nexstudios.menuservice.api.MenuKey;
import io.nexstudios.menuservice.api.MenuView;
import io.nexstudios.serviceregistry.di.ServiceAccessor;

/**
 * Definition for the bank-transactions menu.
 * This menu is always opened directly with bank-specific runtime parameters
 * and a preloaded transaction list; it must not be opened through the registry.
 */
public class BankTransactionsMenuDefinition implements MenuDefinition {

  public static final MenuKey KEY = MenuKey.of("bank-transactions");
  private final ServiceAccessor accessor;

  public BankTransactionsMenuDefinition(ServiceAccessor accessor) {
    this.accessor = accessor;
  }

  @Override
  public MenuKey key() {
    return KEY;
  }

  @Override
  public MenuView create(MenuContext menuContext) {
    throw new UnsupportedOperationException(
        "BankTransactionsMenuView must be opened directly with bankId, ownerUuid and viewerUuid.");
  }
}

