package io.nexstudios.nexeconomy.service.bank.registry;

import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.serviceregistry.di.Service;

import java.util.Collection;
import java.util.Optional;

public interface BankRegistryService extends Service {

  void reload();

  Collection<BankDefinition> banks();

  Collection<String> bankIds();

  Optional<BankDefinition> bank(String bankId);
}