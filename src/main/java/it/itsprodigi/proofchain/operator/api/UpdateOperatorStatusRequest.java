package it.itsprodigi.proofchain.operator.api;

import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOperatorStatusRequest(@NotNull OperatorStatus status) {}
