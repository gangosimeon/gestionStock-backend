package com.geststock.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Contenu d'un {@code pageToken} d'instantané : liaison, {@code asOfSeq} fixé à la première page, heure de la
 * première page, et position de reprise (section, dernier identifiant servi dans cette section ; l'UUID nul en
 * début de section).
 */
record PageToken(LinkRef link, long asOfSeq, Instant issuedAt, SnapshotSection section, UUID lastId) {
}
