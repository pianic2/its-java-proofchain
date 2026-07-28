package it.itsprodigi.proofchain.auth.security;

import it.itsprodigi.proofchain.auth.logging.AuthEventLogger;
import it.itsprodigi.proofchain.common.exception.ProblemDetailFactory;
import it.itsprodigi.proofchain.common.exception.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityProblemWriter {
    private final JsonMapper mapper;
    private final ProblemDetailFactory factory;
    private final AuthEventLogger authEventLogger;

    public SecurityProblemWriter(JsonMapper mapper, ProblemDetailFactory factory, AuthEventLogger authEventLogger) {
        this.mapper = mapper;
        this.factory = factory;
        this.authEventLogger = authEventLogger;
    }

    public void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.INVALID_TOKEN,
                "Invalid token",
                "The bearer token is invalid.");
    }

    public void expired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.EXPIRED_TOKEN,
                "Expired token",
                "The bearer token has expired.");
    }

    public void required(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.AUTHENTICATION_REQUIRED,
                "Authentication required",
                "Authentication is required to access this resource.");
    }

    public void denied(HttpServletRequest request, HttpServletResponse response) throws IOException {
        logAccessDenied(request);
        write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                ProblemTypes.ACCESS_DENIED,
                "Access denied",
                "The authenticated operator is not authorized to perform this operation.");
    }

    private void logAccessDenied(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedOperator operator) {
            authEventLogger.accessDenied(operator.id(), operator.username(), operator.role(), request.getRequestURI());
        } else {
            authEventLogger.accessDenied(null, null, null, request.getRequestURI());
        }
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            java.net.URI type,
            String title,
            String detail)
            throws IOException {
        ProblemDetail problem = factory.create(status, type, title, detail, request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
