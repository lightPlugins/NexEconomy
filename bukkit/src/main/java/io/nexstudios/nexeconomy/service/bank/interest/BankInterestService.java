package io.nexstudios.nexeconomy.service.bank.interest;

import io.nexstudios.serviceregistry.di.Service;

import java.util.UUID;

/**
 * Service for managing and processing bank interest rates.
 * Interest is applied per bank account based on configuration.
 */
public interface BankInterestService extends Service {

  /**
   * Start the interest processing scheduler.
   * Must be called when the plugin is enabled.
   */
  void start();

  /**
   * Stop the interest processing scheduler.
   * Must be called when the plugin is disabled.
   */
  void stop();

  /**
   * Process interest for a specific bank.
   * Only processes for online players who own this bank.
   *
   * @param bankId The bank ID to process
   */
  void processInterestForBank(String bankId);

  /**
   * Reload interest settings for all banks from configuration files.
   */
  void reload();
}

