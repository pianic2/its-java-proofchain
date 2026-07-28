package it.itsprodigi.proofchain.auth.logging;

import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuthEventLogger {
    public static final String LOGGER_NAME = "AUTH_AUDIT";
    public static final String LOGIN_PATH = "/api/v1/auth/login";

    private static final int MAX_REASON_LENGTH = 128;
    private final Logger logger;

    public AuthEventLogger() {
        this(LoggerFactory.getLogger(LOGGER_NAME));
    }

    AuthEventLogger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
    }

    public void loginSuccess(Operator operator) {
        log(operatorEvent(AuthEvent.Event.LOGIN_SUCCESS, operator, AuthEvent.Outcome.SUCCESS, null, null));
    }

    public void loginFailure(Operator operator, String reason) {
        log(operatorEvent(AuthEvent.Event.LOGIN_FAILURE, operator, AuthEvent.Outcome.FAILURE, reason, LOGIN_PATH));
    }

    public void invalidToken(String path) {
        log(new AuthEvent(
                AuthEvent.Event.INVALID_TOKEN, null, null, null, AuthEvent.Outcome.FAILURE, "INVALID_TOKEN", path));
    }

    public void expiredToken(String path) {
        log(new AuthEvent(
                AuthEvent.Event.EXPIRED_TOKEN, null, null, null, AuthEvent.Outcome.FAILURE, "EXPIRED_TOKEN", path));
    }

    public void inactiveOperatorAccess(Operator operator, String path) {
        log(operatorEvent(
                AuthEvent.Event.INACTIVE_OPERATOR_ACCESS,
                operator,
                AuthEvent.Outcome.DENIED,
                "OPERATOR_INACTIVE",
                path));
    }

    public void accessDenied(UUID operatorId, String username, OperatorRole role, String path) {
        log(new AuthEvent(
                AuthEvent.Event.ACCESS_DENIED,
                operatorId,
                username,
                role,
                AuthEvent.Outcome.DENIED,
                "ACCESS_DENIED",
                path));
    }

    public void bootstrapAdminCompleted(Operator operator) {
        log(operatorEvent(
                AuthEvent.Event.BOOTSTRAP_ADMIN_COMPLETED, operator, AuthEvent.Outcome.SUCCESS, "CREATED", null));
    }

    public void bootstrapAdminSkipped(Operator operator, String reason) {
        log(operatorEvent(AuthEvent.Event.BOOTSTRAP_ADMIN_SKIPPED, operator, AuthEvent.Outcome.SKIPPED, reason, null));
    }

    public void log(AuthEvent event) {
        try {
            logger.info(format(event));
        } catch (RuntimeException ignored) {
            // Authentication outcomes must not depend on the logging backend.
        }
    }

    String format(AuthEvent event) {
        String operatorId =
                event.operatorId() == null ? "-" : event.operatorId().toString();
        String role = event.role() == null ? "-" : event.role().name();
        String reason = LogValueSanitizer.sanitize(event.reason(), MAX_REASON_LENGTH);
        return "event=" + event.event().name()
                + " operatorId=" + operatorId
                + " username=" + LogValueSanitizer.sanitizeUsername(event.username())
                + " role=" + role
                + " outcome=" + event.outcome().name()
                + " reason=" + reason
                + " path=" + LogValueSanitizer.sanitizePath(event.path());
    }

    private AuthEvent operatorEvent(
            AuthEvent.Event event, Operator operator, AuthEvent.Outcome outcome, String reason, String path) {
        return new AuthEvent(
                event,
                operator == null ? null : operator.getId(),
                operator == null ? null : operator.getUsername(),
                operator == null ? null : operator.getRole(),
                outcome,
                reason,
                path);
    }
}
