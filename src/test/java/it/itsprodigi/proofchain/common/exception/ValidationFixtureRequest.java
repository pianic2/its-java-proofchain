package it.itsprodigi.proofchain.common.exception;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ValidationFixtureRequest(
        @NotBlank String name, @Size(min = 5) String description, String secret) {}
