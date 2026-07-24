package it.itsprodigi.proofchain.common.exception;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExceptionFixtureController {

    @PostMapping("/api/test/resource")
    void resourceNotFound() {
        throw new ResourceNotFoundException();
    }

    @PostMapping("/api/test/unexpected")
    void unexpected() {
        throw new IllegalStateException("database password and internal class details must remain private");
    }

    @PostMapping("/api/test/validation")
    void validate(@Valid @RequestBody ValidationFixtureRequest request) {}
}
