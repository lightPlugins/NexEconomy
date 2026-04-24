package io.nexstudios.nexeconomy.service.placeholder;

import io.nexstudios.serviceregistry.di.Service;

/**
 * Service for managing economy placeholders.
 *
 * <p>Placeholder implementations register themselves by calling
 * {@link #register(EconomyPlaceholder)} from within their constructor.</p>
 */
public interface EconomyPlaceholderService extends Service, AutoCloseable {

  /**
   * Registers a single economy placeholder.
   *
   * @param placeholder the placeholder to register, must not be {@code null}
   */
  void register(EconomyPlaceholder placeholder);

  /**
   * Starts the service by registering all placeholders for every known currency.
   * Must be called explicitly after construction, before the service is used.
   */
  void start();
}
