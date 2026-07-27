package it.itsprodigi.proofchain.auth.application;

import java.time.Duration;

public record JwtProperties(String secret, Duration accessTokenTtl) {}
