package io.nexstudios.nexeconomy.service.economy;

public final class EconomyException extends RuntimeException {

  private final EconomyErrorCode code;

  public EconomyException(EconomyErrorCode code) {
    super(code == null ? null : code.name());
    this.code = code;
  }

  public EconomyException(EconomyErrorCode code, Throwable cause) {
    super(code == null ? null : code.name(), cause);
    this.code = code;
  }

  public EconomyErrorCode code() {
    return code;
  }
}