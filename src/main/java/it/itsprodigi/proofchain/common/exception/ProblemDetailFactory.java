package it.itsprodigi.proofchain.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemDetailFactory {
    public ProblemDetail create(HttpStatus status, URI type, String title, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(instance));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    public ProblemDetail create(HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
        return create(status, type, title, detail, request.getRequestURI());
    }
}
