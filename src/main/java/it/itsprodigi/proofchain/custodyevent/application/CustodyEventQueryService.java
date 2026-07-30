package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventDetailResponse;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventPageResponse;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.evidence.application.EvidenceRequestValidationException;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustodyEventQueryService {

    private final DigitalEvidenceRepository evidences;
    private final CustodyEventRepository events;
    private final CaseAccessService access;
    private final CustodyEventMapper mapper;

    public CustodyEventQueryService(
            DigitalEvidenceRepository evidences,
            CustodyEventRepository events,
            CaseAccessService access,
            CustodyEventMapper mapper) {
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public CustodyEventPageResponse list(
            UUID evidenceId, int page, int size, List<String> sortParameters, AuthenticatedOperator actor) {
        validatePage(page, size, sortParameters);
        visibleEvidence(evidenceId, actor);
        Page<CustodyEvent> result =
                events.findAllByEvidenceIdOrderBySequenceNumberAsc(evidenceId, PageRequest.of(page, size));
        return mapper.toPage(result);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public CustodyEventDetailResponse get(UUID evidenceId, UUID eventId, AuthenticatedOperator actor) {
        visibleEvidence(evidenceId, actor);
        Objects.requireNonNull(eventId, "eventId must not be null");
        CustodyEvent event =
                events.findByEvidenceIdAndEventId(evidenceId, eventId).orElseThrow(EventNotFoundException::new);
        return mapper.toDetail(event);
    }

    private DigitalEvidence visibleEvidence(UUID evidenceId, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        DigitalEvidence evidence =
                evidences.findByIdForVisibility(evidenceId).orElseThrow(ResourceNotFoundException::new);
        access.requireReadableCase(evidence.getCustodyCase().getId(), actor);
        return evidence;
    }

    private static void validatePage(int page, int size, List<String> sortParameters) {
        if (page < 0 || size < 1 || size > 200 || (sortParameters != null && !sortParameters.isEmpty())) {
            throw new EvidenceRequestValidationException();
        }
    }
}
