package it.itsprodigi.proofchain.auth.api;

import java.time.Instant;

public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, long expiresInSeconds) {}
