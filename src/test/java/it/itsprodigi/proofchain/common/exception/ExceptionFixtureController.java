package it.itsprodigi.proofchain.common.exception;

import it.itsprodigi.proofchain.custodyevent.application.CustodyEventConcurrencyConflictException;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventPersistenceFailureException;
import it.itsprodigi.proofchain.evidence.application.InvalidEvidenceStateException;
import jakarta.validation.Valid;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExceptionFixtureController {

    @PostMapping("/api/test/evidence-state")
    void invalidEvidenceState() {
        throw new InvalidEvidenceStateException("Released evidence is terminal and cannot be modified.");
    }

    @PostMapping("/api/test/custody-event-conflict")
    void custodyEventConcurrencyConflict() {
        throw new CustodyEventConcurrencyConflictException(
                new SQLException("deadlock detected on relation digital_evidence", "40P01"));
    }

    @PostMapping("/api/test/custody-event-persistence")
    void custodyEventPersistenceFailure() {
        throw new CustodyEventPersistenceFailureException(
                new IllegalStateException("storage key cases/1/evidences/2/content.bin"));
    }

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
