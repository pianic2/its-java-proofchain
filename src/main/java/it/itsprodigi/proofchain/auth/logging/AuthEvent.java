package it.itsprodigi.proofchain.auth.logging;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.util.Objects;
import java.util.UUID;

public record AuthEvent(
        Event event, UUID operatorId, String username, OperatorRole role, Outcome outcome, String reason, String path) {

    public AuthEvent {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public enum Event {
        LOGIN_SUCCESS,
        LOGIN_FAILURE,
        INVALID_TOKEN,
        EXPIRED_TOKEN,
        ACCESS_DENIED,
        INACTIVE_OPERATOR_ACCESS,
        BOOTSTRAP_ADMIN_COMPLETED,
        BOOTSTRAP_ADMIN_SKIPPED
    }

    public enum Outcome {
        SUCCESS,
        FAILURE,
        DENIED,
        SKIPPED
    }
}
