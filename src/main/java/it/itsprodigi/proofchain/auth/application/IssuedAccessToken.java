package it.itsprodigi.proofchain.auth.application;

import java.time.Instant;

public record IssuedAccessToken(String value, Instant issuedAt, Instant expiresAt, long expiresInSeconds) {}
