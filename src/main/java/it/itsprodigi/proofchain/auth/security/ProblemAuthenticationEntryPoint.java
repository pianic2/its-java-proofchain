package it.itsprodigi.proofchain.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final SecurityProblemWriter writer;

    public ProblemAuthenticationEntryPoint(SecurityProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        writer.required(request, response);
    }
}
