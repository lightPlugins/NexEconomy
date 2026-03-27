package io.nexstudios.nexeconomy.service.economy;

/**
 * Stable error identifiers used across services and providers.
 * This avoids string markers like "insufficient funds" for flow control.
 */
public enum EconomyErrorCode {
  INVALID_PLAYER,
  INVALID_AMOUNT,
  CURRENCY_NOT_CONFIGURED,
  CURRENCY_NOT_FOUND,
  INSUFFICIENT_FUNDS,
  MAX_BALANCE_REACHED,
  DB_ERROR
}