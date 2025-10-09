package io.nexstudios.economy.storage.persistence.model;

import java.math.BigDecimal;

/**
 * Immutable DTO for persistence operations.
 */
public record DbAccountSnapshot(
        String currencyKey,     // lower-case
        BigDecimal balance,     // scaled by fraction digits
        long version,           // monotonic increasing
        long updatedAtMillis    // server/local timestamp in ms
) { }