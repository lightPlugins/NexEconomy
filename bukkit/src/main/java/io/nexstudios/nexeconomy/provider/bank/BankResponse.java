package io.nexstudios.nexeconomy.provider.bank;

import io.nexstudios.nexeconomy.definition.MantissaAmount;

import java.util.Locale;
import java.util.UUID;

/**
 * Generic response container for bank operations.
 *
 * <p>it carries both a machine-readable status and a rich context payload.
 */
@SuppressWarnings("unused")
public record BankResponse<T>(Status status, String message, BankContext context, T payload) {

  public enum Status {
    SUCCESS,
    FAILURE,
    INVALID_ARGUMENT,
    INVALID_AMOUNT,
    BANK_NOT_FOUND,
    BANK_DISABLED,
    BANK_UNAVAILABLE,
    BANK_LOCKED,
    NOT_OWNER,
    NOT_MEMBER,
    NO_PERMISSION,
    ALREADY_MEMBER,
    ALREADY_INVITED,
    INVITE_NOT_FOUND,
    INVITE_EXPIRED,
    MEMBER_LIMIT_REACHED,
    ROLE_NOT_FOUND,
    ALREADY_AT_LEVEL,
    MAX_LEVEL_REACHED,
    MIN_LEVEL_REACHED,
    LEVEL_TOO_HIGH,
    LEVEL_TOO_LOW,
    INSUFFICIENT_FUNDS,
    CURRENCY_NOT_CONFIGURED,
    ALREADY_LOCKED,
    ALREADY_UNLOCKED,
    NOT_IMPLEMENTED,
    INTERNAL_ERROR
  }

  public record BankContext(
      String bankId,
      UUID bankAccountId,
      UUID ownerUuid,
      UUID actorUuid,
      UUID targetUuid,
      String roleId,
      Integer previousLevel,
      Integer currentLevel,
      Integer targetLevel,
      MantissaAmount amount,
      MantissaAmount balance,
      MantissaAmount limit
  ) {
    public BankContext {
      bankId = normalize(bankId);
      roleId = normalize(roleId);
    }

    private static String normalize(String value) {
      return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
  }

  public BankResponse {
    status = status == null ? Status.INTERNAL_ERROR : status;
    message = message == null ? "" : message;
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  public boolean isFailure() {
    return !isSuccess();
  }

  public boolean isNotImplemented() {
    return status == Status.NOT_IMPLEMENTED;
  }

  public String bankId() {
    return context == null ? null : context.bankId();
  }

  public UUID bankAccountId() {
    return context == null ? null : context.bankAccountId();
  }

  public UUID ownerUuid() {
    return context == null ? null : context.ownerUuid();
  }

  public UUID actorUuid() {
    return context == null ? null : context.actorUuid();
  }

  public UUID targetUuid() {
    return context == null ? null : context.targetUuid();
  }

  public String roleId() {
    return context == null ? null : context.roleId();
  }

  public Integer previousLevel() {
    return context == null ? null : context.previousLevel();
  }

  public Integer currentLevel() {
    return context == null ? null : context.currentLevel();
  }

  public Integer targetLevel() {
    return context == null ? null : context.targetLevel();
  }

  public MantissaAmount amount() {
    return context == null ? null : context.amount();
  }

  public MantissaAmount balance() {
    return context == null ? null : context.balance();
  }

  public MantissaAmount limit() {
    return context == null ? null : context.limit();
  }

  public static BankContext context(
      String bankId,
      UUID bankAccountId,
      UUID ownerUuid,
      UUID actorUuid,
      UUID targetUuid,
      String roleId,
      Integer previousLevel,
      Integer currentLevel,
      Integer targetLevel,
      MantissaAmount amount,
      MantissaAmount balance,
      MantissaAmount limit
  ) {
    return new BankContext(bankId, bankAccountId, ownerUuid, actorUuid, targetUuid, roleId, previousLevel, currentLevel, targetLevel, amount, balance, limit);
  }

  public static <T> BankResponse<T> success(String message, BankContext context, T payload) {
    return new BankResponse<>(Status.SUCCESS, message, context, payload);
  }

  public static <T> BankResponse<T> failure(Status status, String message, BankContext context, T payload) {
    Status resolved = status == null ? Status.INTERNAL_ERROR : status;
    if (resolved == Status.SUCCESS) {
      resolved = Status.FAILURE;
    }
    return new BankResponse<>(resolved, message, context, payload);
  }

  public static <T> BankResponse<T> notImplemented(String message) {
    return new BankResponse<>(Status.NOT_IMPLEMENTED, message, null, null);
  }
}


