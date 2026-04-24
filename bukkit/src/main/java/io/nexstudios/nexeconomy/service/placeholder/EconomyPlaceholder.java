package io.nexstudios.nexeconomy.service.placeholder;

import io.nexstudios.nexlogic.common.placeholder.PlaceholderResolveContext;

import java.time.Duration;

/**
 * Represents a single registered economy placeholder.
 *
 * <p>Each implementation covers exactly one placeholder key. The constructor of every
 * implementation is expected to call {@link EconomyPlaceholderService#register(EconomyPlaceholder)}
 * after all fields have been initialised:</p>
 *
 * <pre>{@code
 * public MyPlaceholder(EconomyPlaceholderService service, ...) {
 *     this.someField = ...;
 *     service.register(this); // always last
 * }
 * }</pre>
 */
public interface EconomyPlaceholder {

  /**
   * Returns the unique placeholder identifier (without namespace).
   * Example: {@code "vault_amount"}, {@code "vault_top_1"}.
   *
   * @return placeholder key, never {@code null}
   */
  String id();

  /**
   * Returns the desired cache TTL for this placeholder.
   * Use {@link Duration#ZERO} to disable caching.
   *
   * @return cache TTL, never {@code null}
   */
  Duration ttl();

  /**
   * Resolves the placeholder value for the given context.
   *
   * @param ctx resolve context (may carry player information)
   * @return resolved string value, never {@code null}
   */
  String resolve(PlaceholderResolveContext ctx);
}

