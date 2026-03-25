package io.nexstudios.nexeconomy.service.bank.repo;

import java.time.Instant;
import java.util.UUID;

public record InviteLookupRow(
    UUID bankAccountId,
    String bankIdLower,
    UUID ownerUuid,
    UUID inviteeUuid,
    UUID invitedByUuid,
    String roleIdLower,
    Instant expiresAt
) {}