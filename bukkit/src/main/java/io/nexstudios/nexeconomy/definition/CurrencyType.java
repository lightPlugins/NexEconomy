package io.nexstudios.nexeconomy.definition;

public enum CurrencyType {
  VIRTUAL,
  VAULT;

  public static CurrencyType parse(String raw) {
    if (raw == null) return VIRTUAL;
    String s = raw.trim().toLowerCase();
    return switch (s) {
      case "vault" -> VAULT;
      default -> VIRTUAL;
    };
  }
}