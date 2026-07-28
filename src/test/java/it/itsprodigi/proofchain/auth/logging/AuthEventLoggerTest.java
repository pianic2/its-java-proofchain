package it.itsprodigi.proofchain.auth.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class AuthEventLoggerTest {

    @Test
    void formatsFieldsInTheStableRequiredOrder() {
        Logger logger = mock(Logger.class);
        AuthEventLogger eventLogger = new AuthEventLogger(logger);
        UUID operatorId = UUID.fromString("11111111-1111-4111-8111-111111111111");

        eventLogger.log(new AuthEvent(
                AuthEvent.Event.ACCESS_DENIED,
                operatorId,
                "operator",
                OperatorRole.AUDITOR,
                AuthEvent.Outcome.DENIED,
                "ACCESS_DENIED",
                "/api/v1/operators"));

        verify(logger)
                .info(
                        "event=ACCESS_DENIED operatorId=11111111-1111-4111-8111-111111111111 username=operator role=AUDITOR "
                                + "outcome=DENIED reason=ACCESS_DENIED path=/api/v1/operators");
    }

    @Test
    void emitsEachEventAsOneLoggerCallAndUsesDashesForMissingOptionalValues() {
        Logger logger = mock(Logger.class);
        AuthEventLogger eventLogger = new AuthEventLogger(logger);

        for (AuthEvent.Event event : AuthEvent.Event.values()) {
            eventLogger.log(new AuthEvent(event, null, null, null, AuthEvent.Outcome.FAILURE, null, null));
        }

        for (AuthEvent.Event event : AuthEvent.Event.values()) {
            verify(logger)
                    .info("event=" + event.name() + " operatorId=- username=- role=- outcome=FAILURE reason=- path=-");
        }
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitizesAndTruncatesUserInfluencedValues() {
        AuthEventLogger eventLogger = new AuthEventLogger(mock(Logger.class));
        String formatted = eventLogger.format(new AuthEvent(
                AuthEvent.Event.LOGIN_FAILURE,
                null,
                "user\r\n" + "a".repeat(80),
                null,
                AuthEvent.Outcome.FAILURE,
                "INVALID_CREDENTIALS\nignored",
                "/api\r\n" + "a".repeat(600)));

        assertThat(formatted)
                .startsWith("event=LOGIN_FAILURE operatorId=- username=user" + "a".repeat(60))
                .contains(" role=- outcome=FAILURE reason=INVALID_CREDENTIALSignored path=/api" + "a".repeat(508))
                .doesNotContain("\r", "\n");
    }

    @Test
    void doesNotExposeSensitiveAuthenticationMaterialInTheApprovedFormat() {
        AuthEventLogger eventLogger = new AuthEventLogger(mock(Logger.class));
        String formatted = eventLogger.format(new AuthEvent(
                AuthEvent.Event.LOGIN_FAILURE,
                null,
                "operator",
                null,
                AuthEvent.Outcome.FAILURE,
                "INVALID_CREDENTIALS",
                AuthEventLogger.LOGIN_PATH));

        assertThat(formatted)
                .doesNotContain("password", "hash", "eyJ", "Authorization", "Bearer", "signature", "secret");
    }

    @Test
    void loggingFailureIsContained() {
        Logger logger = mock(Logger.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("file unavailable"))
                .when(logger)
                .info(org.mockito.ArgumentMatchers.anyString());

        new AuthEventLogger(logger)
                .log(new AuthEvent(
                        AuthEvent.Event.LOGIN_SUCCESS, null, null, null, AuthEvent.Outcome.SUCCESS, null, null));
    }
}
