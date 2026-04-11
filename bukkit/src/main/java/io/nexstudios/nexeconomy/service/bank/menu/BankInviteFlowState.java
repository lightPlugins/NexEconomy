package io.nexstudios.nexeconomy.service.bank.menu;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BankInviteFlowState {

  private static final ConcurrentMap<UUID, InviteContext> CONTEXTS = new ConcurrentHashMap<>();
  private static final ConcurrentMap<UUID, Boolean> TRANSITIONS = new ConcurrentHashMap<>();

  public static void start(UUID viewerUuid, String bankId, UUID ownerUuid, boolean ownerBank) {
    if (viewerUuid == null) {
      return;
    }

    CONTEXTS.put(viewerUuid, new InviteContext(normalize(bankId), ownerUuid, ownerBank, null, null, null, null));
  }

  public static InviteContext get(UUID viewerUuid) {
    return viewerUuid == null ? null : CONTEXTS.get(viewerUuid);
  }

  public static InviteContext selectTarget(UUID viewerUuid, UUID targetUuid, String targetName) {
    return update(viewerUuid, context -> context.withTarget(targetUuid, targetName));
  }

  public static InviteContext selectRole(UUID viewerUuid, String roleId, String roleName) {
    return update(viewerUuid, context -> context.withRole(roleId, roleName));
  }

  public static InviteContext clearRole(UUID viewerUuid) {
    return update(viewerUuid, context -> context.withRole(null, null));
  }

  public static void clear(UUID viewerUuid) {
    if (viewerUuid != null) {
      CONTEXTS.remove(viewerUuid);
      TRANSITIONS.remove(viewerUuid);
    }
  }

  public static void markTransition(UUID viewerUuid) {
    if (viewerUuid != null) {
      TRANSITIONS.put(viewerUuid, Boolean.TRUE);
    }
  }

  public static boolean consumeTransition(UUID viewerUuid) {
    if (viewerUuid == null) {
      return false;
    }

    return Boolean.TRUE.equals(TRANSITIONS.remove(viewerUuid));
  }

  private static InviteContext update(UUID viewerUuid, ContextUpdater updater) {
    if (viewerUuid == null || updater == null) {
      return null;
    }

    return CONTEXTS.computeIfPresent(viewerUuid, (key, value) -> updater.apply(value));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  @FunctionalInterface
  private interface ContextUpdater {
    InviteContext apply(InviteContext context);
  }

  public record InviteContext(String bankId, UUID ownerUuid, boolean ownerBank, UUID targetUuid, String targetName, String roleId, String roleName) {
    InviteContext withTarget(UUID newTargetUuid, String newTargetName) {
      return new InviteContext(bankId, ownerUuid, ownerBank, newTargetUuid, newTargetName, null, null);
    }

    InviteContext withRole(String newRoleId, String newRoleName) {
      return new InviteContext(bankId, ownerUuid, ownerBank, targetUuid, targetName, normalize(newRoleId), newRoleName);
    }

    private static String normalize(String value) {
      return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
  }
}

