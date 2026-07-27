package it.itsprodigi.proofchain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import it.itsprodigi.proofchain.auth.application.ExpiredJwtException;
import it.itsprodigi.proofchain.auth.application.InvalidJwtException;
import it.itsprodigi.proofchain.auth.application.JwtClaims;
import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class JwtAuthenticationFilterTest {
    @Mock
    JwtTokenService tokens;

    @Mock
    OperatorRepository repository;

    @Mock
    SecurityProblemWriter writer;

    JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(tokens, repository, writer);
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingHeaderIsAnonymousAndDoesNotLookup() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());
        verifyNoInteractions(repository, tokens, writer);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validTokenLooksUpOnceAndUsesDatabaseRole() throws Exception {
        Operator operator = operator(OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        UUID id = operator.getId();
        when(tokens.validate("token"))
                .thenReturn(new JwtClaims(
                        operator.getId(),
                        "user",
                        OperatorRole.AUDITOR,
                        Instant.EPOCH,
                        Instant.MAX,
                        UUID.randomUUID(),
                        "proofchain-api"));
        when(repository.findById(id)).thenReturn(Optional.of(operator));
        MockHttpServletRequest request = request("Bearer token");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        verify(repository).findById(id);
        verifyNoMoreInteractions(repository);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getCredentials())
                .isNull();
        AuthenticatedOperator principal = (AuthenticatedOperator)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.id()).isEqualTo(operator.getId());
        assertThat(principal.username()).isEqualTo(operator.getUsername());
        assertThat(principal.email()).isEqualTo(operator.getEmail());
        assertThat(principal.firstName()).isEqualTo(operator.getFirstName());
        assertThat(principal.lastName()).isEqualTo(operator.getLastName());
        assertThat(principal.role()).isEqualTo(OperatorRole.ADMIN);
        assertThat(principal.status()).isEqualTo(OperatorStatus.ACTIVE);
        assertThat(principal.createdAt()).isEqualTo(operator.getCreatedAt());
        assertThat(principal.updatedAt()).isEqualTo(operator.getUpdatedAt());
    }

    @Test
    void malformedHeadersAndDuplicateHeadersAreInvalidWithoutLookup() throws Exception {
        for (String value : new String[] {
            "bearer token", "Bearer  token", "Bearer", "Bearer token x", "Bearer to ken", "Bearer token,other"
        }) {
            reset(writer);
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                    "old", null));
            MockHttpServletRequest request = request(value);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            verify(writer).invalid(any(), any());
            verifyNoInteractions(repository, tokens);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        MockHttpServletRequest duplicate = new MockHttpServletRequest();
        duplicate.addHeader("Authorization", "Bearer a");
        duplicate.addHeader("Authorization", "Bearer b");
        filter.doFilter(duplicate, new MockHttpServletResponse(), new MockFilterChain());
        verify(writer, atLeastOnce()).invalid(any(), any());
        verifyNoInteractions(repository, tokens);
    }

    @Test
    void inactiveMissingAndDeletedOperatorsAreInvalid() throws Exception {
        UUID id = UUID.randomUUID();
        when(tokens.validate("token"))
                .thenReturn(new JwtClaims(
                        id,
                        "user",
                        OperatorRole.ADMIN,
                        Instant.EPOCH,
                        Instant.MAX,
                        UUID.randomUUID(),
                        "proofchain-api"));
        for (OperatorStatus status : new OperatorStatus[] {OperatorStatus.SUSPENDED, OperatorStatus.DISABLED}) {
            when(repository.findById(id)).thenReturn(Optional.of(operator(OperatorRole.ADMIN, status)));
            filter.doFilter(request("Bearer token"), new MockHttpServletResponse(), new MockFilterChain());
            verify(writer, atLeastOnce()).invalid(any(), any());
            SecurityContextHolder.clearContext();
        }
        when(repository.findById(id)).thenReturn(Optional.empty());
        filter.doFilter(request("Bearer token"), new MockHttpServletResponse(), new MockFilterChain());
        verify(writer, atLeast(3)).invalid(any(), any());
    }

    @Test
    void expiredAndInvalidAreMappedAndErrorDispatchIsSkipped() throws Exception {
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "old", null));
        when(tokens.validate("expired")).thenThrow(new ExpiredJwtException());
        filter.doFilter(request("Bearer expired"), new MockHttpServletResponse(), new MockFilterChain());
        verify(writer).expired(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        reset(writer);
        SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "old", null));
        when(tokens.validate("bad")).thenThrow(new InvalidJwtException());
        filter.doFilter(request("Bearer bad"), new MockHttpServletResponse(), new MockFilterChain());
        verify(writer).invalid(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        reset(repository, tokens, writer);
        MockHttpServletRequest error = new MockHttpServletRequest();
        error.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        error.setAttribute("jakarta.servlet.error.request_uri", "/protected");
        error.addHeader("Authorization", "Bearer bad");
        filter.doFilter(error, new MockHttpServletResponse(), new MockFilterChain());
        verifyNoInteractions(repository, tokens, writer);
    }

    @Test
    void downstreamJwtExceptionIsNotIntercepted(CapturedOutput output) throws Exception {
        Operator operator = operator(OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        when(tokens.validate("token"))
                .thenReturn(new JwtClaims(
                        operator.getId(),
                        "user",
                        OperatorRole.ADMIN,
                        Instant.EPOCH,
                        Instant.MAX,
                        UUID.randomUUID(),
                        "proofchain-api"));
        when(repository.findById(operator.getId())).thenReturn(Optional.of(operator));
        InvalidJwtException downstream = new InvalidJwtException();
        assertThatThrownBy(() ->
                        filter.doFilter(request("Bearer token"), new MockHttpServletResponse(), (request, response) -> {
                            throw downstream;
                        }))
                .isSameAs(downstream);
        verifyNoInteractions(writer);
        assertThat(output).doesNotContain("Bearer token").doesNotContain("token");
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }

    private Operator operator(OperatorRole role, OperatorStatus status) {
        Operator o = Operator.create(
                "user",
                "user@example.com",
                "$2a$10$7EqJtq98hPqEX7fNZaFWoO9h4kQJrE9fGJ8u4Y0sRjXx5f7qXy3yK",
                "First",
                "Last",
                role);
        if (status != OperatorStatus.ACTIVE) o.changeStatus(status);
        return o;
    }
}
