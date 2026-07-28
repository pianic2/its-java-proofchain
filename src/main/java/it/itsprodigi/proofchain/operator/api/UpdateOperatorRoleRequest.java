package it.itsprodigi.proofchain.operator.api;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import jakarta.validation.constraints.NotNull;

public record UpdateOperatorRoleRequest(@NotNull OperatorRole role) {}
