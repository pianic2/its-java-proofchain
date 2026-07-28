package it.itsprodigi.proofchain.operator.api;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateOperatorRequest(
        @NotBlank String username,
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotNull OperatorRole role) {}
