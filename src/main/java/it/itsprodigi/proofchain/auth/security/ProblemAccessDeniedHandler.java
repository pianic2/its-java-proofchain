package it.itsprodigi.proofchain.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

public class ProblemAccessDeniedHandler implements AccessDeniedHandler {
    private final SecurityProblemWriter writer;

    public ProblemAccessDeniedHandler(SecurityProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writer.denied(request, response);
    }
}
