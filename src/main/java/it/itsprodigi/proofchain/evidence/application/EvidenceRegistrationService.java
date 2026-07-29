package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppendResult;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppender;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.evidence.api.CreateEvidenceRequest;
import it.itsprodigi.proofchain.evidence.api.EvidenceResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EvidenceRegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceRegistrationService.class);
    private static final Set<OperatorRole> REGISTRATION_ROLES =
            Set.of(OperatorRole.ADMIN, OperatorRole.CASE_MANAGER, OperatorRole.EVIDENCE_OFFICER);
    private static final Set<String> DUPLICATE_REFERENCE_CONSTRAINTS = Set.of("uk_digital_evidence_case_reference_tag");

    private final CustodyCaseRepository custodyCases;
    private final CaseMembershipRepository memberships;
    private final OperatorRepository operators;
    private final DigitalEvidenceRepository evidences;
    private final CaseAccessService access;
    private final EvidenceStoragePort storage;
    private final EvidenceMapper mapper;
    private final CustodyEventAppender custodyEvents;
    private final EntityManager entityManager;
    private final Clock clock;

    public EvidenceRegistrationService(
            CustodyCaseRepository custodyCases,
            CaseMembershipRepository memberships,
            OperatorRepository operators,
            DigitalEvidenceRepository evidences,
            CaseAccessService access,
            EvidenceStoragePort storage,
            EvidenceMapper mapper,
            CustodyEventAppender custodyEvents,
            EntityManager entityManager,
            Clock clock) {
        this.custodyCases = Objects.requireNonNull(custodyCases, "custodyCases must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.operators = Objects.requireNonNull(operators, "operators must not be null");
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.custodyEvents = Objects.requireNonNull(custodyEvents, "custodyEvents must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public EvidenceResponse register(
            UUID caseId, CreateEvidenceRequest request, MultipartFile file, AuthenticatedOperator actor) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        access.requireEvidenceRegistrationPermission(caseId, actor);
        EvidenceUploadNormalizer.validateMetadata(request);
        String originalFilename = EvidenceUploadNormalizer.filename(file.getOriginalFilename());
        String mediaType = EvidenceUploadNormalizer.mediaType(file.getContentType());

        CustodyCase custodyCase = custodyCases.findByIdForUpdate(caseId).orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(custodyCase);
        Operator currentActor = requireCurrentActor(caseId, actor);
        if (custodyCase.getStatus() != CaseStatus.OPEN) {
            throw new CaseClosedException();
        }
        Operator holder = requireEligibleHolder(caseId, request.initialHolderId(), currentActor);
        if (evidences.existsByCaseIdAndReferenceTag(caseId, request.referenceTag())) {
            throw new DuplicateEvidenceReferenceTagException();
        }

        UUID evidenceId = UUID.randomUUID();
        Instant registeredAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        String storageKey = EvidenceStorageKeyFactory.forEvidence(caseId, evidenceId);
        StagedEvidence staged = stage(storageKey, file);
        String contextualSha256 = EvidenceHashing.contextualSha256(caseId, evidenceId, staged.contentSha256());
        DigitalEvidence evidence;
        try {
            evidence = DigitalEvidence.create(
                    evidenceId,
                    custodyCase,
                    holder,
                    currentActor,
                    request.referenceTag(),
                    request.title(),
                    request.description(),
                    request.sourceType(),
                    request.sourceDescription(),
                    request.sourceManufacturer(),
                    request.sourceModel(),
                    request.sourceSerialNumber(),
                    request.sourceLogicalIdentifier(),
                    request.acquisitionMethod(),
                    request.acquisitionLocation(),
                    request.acquisitionToolName(),
                    request.acquisitionToolVersion(),
                    request.acquisitionNotes(),
                    request.acquiredAt(),
                    originalFilename,
                    mediaType,
                    staged.byteCount(),
                    staged.contentSha256(),
                    contextualSha256,
                    storageKey,
                    registeredAt);
        } catch (IllegalArgumentException | NullPointerException exception) {
            discardStagedWithoutMasking(staged, caseId, evidenceId);
            throw new EvidenceRequestValidationException();
        }

        CustodyEventAppendResult genesis;
        try {
            evidence = evidences.saveAndFlush(evidence);
            genesis = custodyEvents.initializeGenesis(
                    evidence, currentActor, registrationPayload(evidence), registeredAt);
        } catch (DataIntegrityViolationException exception) {
            discardStagedWithoutMasking(staged, caseId, evidenceId);
            if (hasNamedConstraint(exception, DUPLICATE_REFERENCE_CONSTRAINTS)) {
                throw new DuplicateEvidenceReferenceTagException();
            }
            throw exception;
        } catch (RuntimeException exception) {
            discardStagedWithoutMasking(staged, caseId, evidenceId);
            throw exception;
        }

        try {
            storage.finalizeStaged(staged);
        } catch (RuntimeException exception) {
            discardStagedWithoutMasking(staged, caseId, evidenceId);
            throw exception;
        }
        registerTransactionOutcome(storageKey, caseId, evidenceId, currentActor.getId(), genesis);
        return mapper.toResponse(evidence);
    }

    private Operator requireCurrentActor(UUID caseId, AuthenticatedOperator actor) {
        Operator currentActor = operators.findByIdForUpdate(actor.id()).orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(currentActor);
        boolean admin = currentActor.getRole() == OperatorRole.ADMIN;
        if (!admin && !memberships.existsByCustodyCaseIdAndOperatorId(caseId, currentActor.getId())) {
            throw new ResourceNotFoundException();
        }
        if (currentActor.getStatus() != OperatorStatus.ACTIVE || !REGISTRATION_ROLES.contains(currentActor.getRole())) {
            throw new AccessDeniedException("The current operator cannot register evidence.");
        }
        return currentActor;
    }

    private Operator requireEligibleHolder(UUID caseId, UUID holderId, Operator currentActor) {
        if (currentActor.getRole() == OperatorRole.EVIDENCE_OFFICER
                && !currentActor.getId().equals(holderId)) {
            throw new AccessDeniedException("An evidence officer can only take initial custody personally.");
        }
        Operator holder = currentActor.getId().equals(holderId)
                ? currentActor
                : operators.findByIdForUpdate(holderId).orElseThrow(EvidenceHolderNotEligibleException::new);
        if (holder.getId().equals(holderId) && holder != currentActor) {
            entityManager.refresh(holder);
        }
        if (holder.getStatus() != OperatorStatus.ACTIVE
                || !REGISTRATION_ROLES.contains(holder.getRole())
                || !memberships.existsByCustodyCaseIdAndOperatorId(caseId, holder.getId())) {
            throw new EvidenceHolderNotEligibleException();
        }
        return holder;
    }

    private StagedEvidence stage(String storageKey, MultipartFile file) {
        try {
            InputStream content = file.getInputStream();
            try (content) {
                return storage.stage(storageKey, content);
            }
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to read evidence upload");
        }
    }

    private static EvidenceRegisteredPayload registrationPayload(DigitalEvidence evidence) {
        return new EvidenceRegisteredPayload(
                false,
                evidence.getReferenceTag(),
                evidence.getTitle(),
                evidence.getDescription(),
                evidence.getStatus(),
                evidence.getSourceType(),
                evidence.getSourceDescription(),
                evidence.getSourceManufacturer(),
                evidence.getSourceModel(),
                evidence.getSourceSerialNumber(),
                evidence.getSourceLogicalIdentifier(),
                evidence.getAcquisitionMethod(),
                evidence.getAcquiredAt(),
                evidence.getAcquisitionLocation(),
                evidence.getAcquisitionToolName(),
                evidence.getAcquisitionToolVersion(),
                evidence.getAcquisitionNotes(),
                evidence.getOriginalFilename(),
                evidence.getFileExtension(),
                evidence.getMediaType(),
                evidence.getFileSize(),
                evidence.getContentSha256(),
                evidence.getContextualSha256(),
                evidence.getUploadedBy().getId(),
                evidence.getCurrentHolder().getId());
    }

    private void registerTransactionOutcome(
            String storageKey, UUID caseId, UUID evidenceId, UUID callerId, CustodyEventAppendResult genesis) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            discardFinalizedWithoutMasking(storageKey, caseId, evidenceId);
            throw new EvidenceStorageFailureException("Evidence transaction synchronization is unavailable");
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    LOGGER.info(
                            "Evidence registration result=success failureCategory=none caseId={} evidenceId={} eventId={} sequenceNumber={} callerId={}",
                            caseId,
                            evidenceId,
                            genesis.eventId(),
                            genesis.sequenceNumber(),
                            callerId);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        discardFinalizedWithoutMasking(storageKey, caseId, evidenceId);
                        LOGGER.warn(
                                "Evidence registration result=failure failureCategory=transaction-rollback caseId={} evidenceId={} eventId={} sequenceNumber={} callerId={}",
                                caseId,
                                evidenceId,
                                genesis.eventId(),
                                genesis.sequenceNumber(),
                                callerId);
                    }
                }
            });
        } catch (RuntimeException exception) {
            discardFinalizedWithoutMasking(storageKey, caseId, evidenceId);
            throw exception;
        }
    }

    private void discardStagedWithoutMasking(StagedEvidence staged, UUID caseId, UUID evidenceId) {
        try {
            storage.discardStaged(staged);
        } catch (RuntimeException exception) {
            logCleanupFailure("discard-staged", caseId, evidenceId, exception);
        }
    }

    private void discardFinalizedWithoutMasking(String storageKey, UUID caseId, UUID evidenceId) {
        try {
            storage.discardFinalized(storageKey);
        } catch (RuntimeException exception) {
            logCleanupFailure("discard-finalized", caseId, evidenceId, exception);
        }
    }

    private void logCleanupFailure(String operation, UUID caseId, UUID evidenceId, RuntimeException exception) {
        LOGGER.warn(
                "Evidence cleanup failed operation={} caseId={} evidenceId={} reason={}",
                operation,
                caseId,
                evidenceId,
                exception.getClass().getSimpleName());
    }

    private static boolean hasNamedConstraint(Throwable exception, Set<String> names) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && names.contains(violation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
