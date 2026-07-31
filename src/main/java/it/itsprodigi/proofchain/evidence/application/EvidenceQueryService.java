package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.evidence.api.EvidencePageResponse;
import it.itsprodigi.proofchain.evidence.api.EvidenceResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceContentMetadata;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EvidenceQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceQueryService.class);

    private final DigitalEvidenceRepository evidences;
    private final CaseAccessService access;
    private final EvidenceMapper mapper;
    private final EvidenceStoragePort storage;

    public EvidenceQueryService(
            DigitalEvidenceRepository evidences,
            CaseAccessService access,
            EvidenceMapper mapper,
            EvidenceStoragePort storage) {
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public EvidencePageResponse list(
            UUID caseId, int page, int size, List<String> sortParameters, AuthenticatedOperator actor) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        validatePage(page, size, sortParameters);
        access.requireReadableCase(caseId, actor);
        Page<DigitalEvidence> result = evidences.findPageByCaseId(caseId, PageRequest.of(page, size));
        return mapper.toPage(result);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public EvidenceResponse get(UUID evidenceId, AuthenticatedOperator actor) {
        DigitalEvidence evidence = visibleEvidence(evidenceId, actor);
        return mapper.toResponse(evidence);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public EvidenceDownloadDescriptor prepareDownload(UUID evidenceId, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        DigitalEvidenceContentMetadata metadata =
                evidences.findContentMetadataById(evidenceId).orElseThrow(ResourceNotFoundException::new);
        access.requireReadableCase(metadata.getCaseId(), actor);
        return new EvidenceDownloadDescriptor(
                metadata.getEvidenceId(),
                metadata.getOriginalFilename(),
                metadata.getMediaType(),
                metadata.getFileSize(),
                metadata.getStorageKey());
    }

    public OpenedEvidence openDownload(EvidenceDownloadDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            logOpenFailure(descriptor.evidenceId(), "transaction-active");
            throw new EvidenceStorageFailureException("Evidence content must be opened outside a database transaction");
        }
        OpenedEvidence opened;
        try {
            opened = storage.open(descriptor.storageKey());
        } catch (EvidenceFileUnavailableException exception) {
            logOpenFailure(descriptor.evidenceId(), "file-unavailable");
            throw exception;
        } catch (UnsafeEvidenceStoragePathException exception) {
            logOpenFailure(descriptor.evidenceId(), "unsafe-storage-path");
            throw new EvidenceFileUnavailableException();
        } catch (EvidenceStorageException exception) {
            logOpenFailure(descriptor.evidenceId(), "storage-failure");
            throw exception;
        }
        if (opened == null
                || !descriptor.storageKey().equals(opened.storageKey())
                || descriptor.fileSize() != opened.byteCount()) {
            if (opened != null) {
                closeWithoutMasking(opened, descriptor.evidenceId());
            }
            logOpenFailure(descriptor.evidenceId(), "descriptor-mismatch");
            throw new EvidenceFileUnavailableException();
        }
        return opened;
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
        if (page < 0) {
            throw new EvidenceRequestValidationException();
        }
        if (size < 1 || size > 100) {
            throw new EvidenceRequestValidationException();
        }
        if (sortParameters != null && !sortParameters.isEmpty()) {
            throw new EvidenceRequestValidationException();
        }
    }

    private static void closeWithoutMasking(OpenedEvidence opened, UUID evidenceId) {
        try {
            opened.close();
        } catch (RuntimeException exception) {
            logOpenFailure(evidenceId, "close-failure");
        }
    }

    private static void logOpenFailure(UUID evidenceId, String reason) {
        LOGGER.warn("Evidence download open failed evidenceId={} reason={}", evidenceId, reason);
    }
}
