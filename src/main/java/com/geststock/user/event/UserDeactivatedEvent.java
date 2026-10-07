package com.geststock.user.event;

import java.util.UUID;

/** Publié dans la transaction qui désactive un compte : ses sessions doivent être fermées. */
public record UserDeactivatedEvent(UUID userId) {
}
