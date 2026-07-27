package it.itsprodigi.proofchain.auth.security;

import it.itsprodigi.proofchain.auth.application.ExpiredJwtException;
import it.itsprodigi.proofchain.auth.application.InvalidJwtException;
import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokens;
    private final OperatorRepository operators;
    private final SecurityProblemWriter problemWriter;

    public JwtAuthenticationFilter(
            JwtTokenService tokens, OperatorRepository operators, SecurityProblemWriter problemWriter) {
        this.tokens = tokens;
        this.operators = operators;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        String[] values = request.getHeaderNames() == null
                ? new String[0]
                : java.util.Collections.list(request.getHeaders("Authorization"))
                        .toArray(String[]::new);
        if (values.length == 0) {
            chain.doFilter(request, response);
            return;
        }
        if (values.length != 1 || !values[0].matches("Bearer [^\\s,]+")) {
            problemWriter.invalid(request, response);
            return;
        }
        try {
            var claims = tokens.validate(values[0].substring(7));
            Optional<Operator> found = operators.findById(claims.operatorId());
            Operator operator =
                    found.filter(o -> o.getStatus() == OperatorStatus.ACTIVE).orElseThrow(InvalidJwtException::new);
            var principal = new AuthenticatedOperator(
                    operator.getId(),
                    operator.getUsername(),
                    operator.getEmail(),
                    operator.getFirstName(),
                    operator.getLastName(),
                    operator.getRole(),
                    operator.getStatus(),
                    operator.getCreatedAt(),
                    operator.getUpdatedAt());
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    java.util.List.of(new SimpleGrantedAuthority(
                            "ROLE_" + operator.getRole().name())));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (ExpiredJwtException ex) {
            SecurityContextHolder.clearContext();
            problemWriter.expired(request, response);
            return;
        } catch (InvalidJwtException ex) {
            SecurityContextHolder.clearContext();
            problemWriter.invalid(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
