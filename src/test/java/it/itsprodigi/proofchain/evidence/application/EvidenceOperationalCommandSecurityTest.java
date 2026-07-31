package it.itsprodigi.proofchain.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Method security is enforced on the shared operational command entry point. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EvidenceOperationalCommandSecurityTest.MethodSecurityTestConfig.class)
class EvidenceOperationalCommandSecurityTest {

    @Autowired
    private EvidenceOperationalCommandService commands;

    @Autowired
    private EvidenceOperationalCommandTransaction transaction;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedCallersCannotRunOperationalCommands() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> commands.execute(
                        EvidenceOperationalCommand.CUSTODY_TRANSFER, UUID.randomUUID(), principal(), context -> {
                            throw new AssertionError("the body must not run");
                        }))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void authenticatedCallersReachTheSharedTransactionTemplate() {
        EvidenceOperationResponse expected = mock(EvidenceOperationResponse.class);
        when(expected.evidence()).thenReturn(mock(it.itsprodigi.proofchain.evidence.api.EvidenceResponse.class));
        when(expected.eventSummary())
                .thenReturn(mock(it.itsprodigi.proofchain.custodyevent.api.CustodyEventSummaryResponse.class));
        when(transaction.execute(any(), any(), any(), any())).thenReturn(expected);
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal(), null, List.of(new SimpleGrantedAuthority("ROLE_CASE_MANAGER"))));

        EvidenceOperationResponse actual = commands.execute(
                EvidenceOperationalCommand.CUSTODY_TRANSFER, UUID.randomUUID(), principal(), context -> null);

        assertThat(actual).isSameAs(expected);
    }

    private static AuthenticatedOperator principal() {
        return new AuthenticatedOperator(
                UUID.randomUUID(),
                "security-actor",
                "security-actor@example.com",
                "Security",
                "Actor",
                OperatorRole.CASE_MANAGER,
                OperatorStatus.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {

        @Bean
        EvidenceOperationalCommandTransaction transaction() {
            return mock(EvidenceOperationalCommandTransaction.class);
        }

        @Bean
        EvidenceCommandConflictTranslator conflictTranslator() {
            return new EvidenceCommandConflictTranslator();
        }

        @Bean
        EvidenceOperationalCommandService commands(
                EvidenceOperationalCommandTransaction transaction, EvidenceCommandConflictTranslator conflicts) {
            return new EvidenceOperationalCommandService(transaction, conflicts);
        }
    }
}
