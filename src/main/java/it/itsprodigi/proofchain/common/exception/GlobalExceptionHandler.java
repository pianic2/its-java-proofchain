package it.itsprodigi.proofchain.common.exception;

import it.itsprodigi.proofchain.auth.application.InvalidCredentialsException;
import it.itsprodigi.proofchain.auth.logging.AuthEventLogger;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.application.AdminMembershipNotAssignableException;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.application.CaseRequestValidationException;
import it.itsprodigi.proofchain.custodycase.application.ConcurrentCaseModificationException;
import it.itsprodigi.proofchain.custodycase.application.ConcurrentMembershipConflictException;
import it.itsprodigi.proofchain.custodycase.application.InvalidCaseStatusTransitionException;
import it.itsprodigi.proofchain.custodycase.application.LastCaseManagerRemovalException;
import it.itsprodigi.proofchain.custodycase.application.OperatorNotActiveException;
import it.itsprodigi.proofchain.evidence.application.DuplicateEvidenceReferenceTagException;
import it.itsprodigi.proofchain.evidence.application.EmptyEvidenceException;
import it.itsprodigi.proofchain.evidence.application.EvidenceHolderNotEligibleException;
import it.itsprodigi.proofchain.evidence.application.EvidenceRequestValidationException;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageException;
import it.itsprodigi.proofchain.evidence.application.EvidenceTooLargeException;
import it.itsprodigi.proofchain.operator.application.ConcurrentOperatorModificationException;
import it.itsprodigi.proofchain.operator.application.DuplicateOperatorException;
import it.itsprodigi.proofchain.operator.application.OperatorInvariantException;
import it.itsprodigi.proofchain.operator.application.OperatorRequestValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ProblemDetailFactory problemDetailFactory;
    private final AuthEventLogger authEventLogger;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory, AuthEventLogger authEventLogger) {
        this.problemDetailFactory = problemDetailFactory;
        this.authEventLogger = authEventLogger;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.INVALID_CREDENTIALS,
                "Invalid credentials",
                "The supplied credentials are invalid.",
                request);
    }

    @ExceptionHandler(DuplicateOperatorException.class)
    ProblemDetail handleDuplicateOperator(DuplicateOperatorException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.DUPLICATE_RESOURCE,
                "Duplicate resource",
                "An operator with the supplied username or email already exists.",
                request);
    }

    @ExceptionHandler(OperatorInvariantException.class)
    ProblemDetail handleOperatorInvariant(OperatorInvariantException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.OPERATOR_INVARIANT_CONFLICT,
                "Operator invariant conflict",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(ConcurrentOperatorModificationException.class)
    ProblemDetail handleConcurrentOperatorModification(
            ConcurrentOperatorModificationException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.CONCURRENT_MODIFICATION,
                "Concurrent modification",
                "The operator was modified by another transaction. Retry using current data.",
                request);
    }

    @ExceptionHandler(ConcurrentCaseModificationException.class)
    ProblemDetail handleConcurrentCaseModification(
            ConcurrentCaseModificationException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.CONCURRENT_MODIFICATION,
                "Concurrent modification",
                "The custody case was modified by another transaction. Retry using current data.",
                request);
    }

    @ExceptionHandler(CaseClosedException.class)
    ProblemDetail handleCaseClosed(CaseClosedException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT, ProblemTypes.CASE_CLOSED, "Custody case closed", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCaseStatusTransitionException.class)
    ProblemDetail handleInvalidCaseStatusTransition(
            InvalidCaseStatusTransitionException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.INVALID_CASE_STATUS_TRANSITION,
                "Invalid custody case status transition",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(LastCaseManagerRemovalException.class)
    ProblemDetail handleLastCaseManagerRemoval(LastCaseManagerRemovalException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.LAST_CASE_MANAGER_REMOVAL,
                "Last responsible manager removal",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(OperatorNotActiveException.class)
    ProblemDetail handleOperatorNotActive(OperatorNotActiveException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.OPERATOR_NOT_ACTIVE,
                "Operator not active",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(AdminMembershipNotAssignableException.class)
    ProblemDetail handleAdminMembershipNotAssignable(
            AdminMembershipNotAssignableException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.ADMIN_MEMBERSHIP_NOT_ASSIGNABLE,
                "ADMIN membership not assignable",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(ConcurrentMembershipConflictException.class)
    ProblemDetail handleConcurrentMembershipConflict(
            ConcurrentMembershipConflictException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.CONCURRENT_MEMBERSHIP_CONFLICT,
                "Concurrent membership conflict",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(DuplicateEvidenceReferenceTagException.class)
    ProblemDetail handleDuplicateEvidenceReferenceTag(
            DuplicateEvidenceReferenceTagException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.DUPLICATE_EVIDENCE_REFERENCE_TAG,
                "Duplicate evidence reference tag",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(EvidenceHolderNotEligibleException.class)
    ProblemDetail handleEvidenceHolderNotEligible(
            EvidenceHolderNotEligibleException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.HOLDER_NOT_ELIGIBLE,
                "Evidence holder not eligible",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(EvidenceTooLargeException.class)
    ProblemDetail handleEvidenceTooLarge(EvidenceTooLargeException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ProblemTypes.PAYLOAD_TOO_LARGE,
                "Payload too large",
                "The evidence file exceeds the configured upload limit.",
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleMultipartTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ProblemTypes.PAYLOAD_TOO_LARGE,
                "Payload too large",
                "The multipart request exceeds the configured upload limit.",
                request);
    }

    @ExceptionHandler(EvidenceStorageException.class)
    ProblemDetail handleEvidenceStorage(EvidenceStorageException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.STORAGE_FAILURE,
                "Evidence storage failure",
                "Evidence content could not be stored safely.",
                request);
    }

    @ExceptionHandler(EmptyEvidenceException.class)
    ProblemDetail handleEmptyEvidence(EmptyEvidenceException exception, HttpServletRequest request) {
        return validationProblem(request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLockingFailure(
            OptimisticLockingFailureException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.CONFLICT,
                ProblemTypes.CONCURRENT_MODIFICATION,
                "Concurrent modification",
                "The operator was modified by another transaction. Retry using current data.",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedOperator operator) {
            authEventLogger.accessDenied(operator.id(), operator.username(), operator.role(), request.getRequestURI());
        } else {
            authEventLogger.accessDenied(null, null, null, request.getRequestURI());
        }
        return problemDetailFactory.create(
                HttpStatus.FORBIDDEN,
                ProblemTypes.ACCESS_DENIED,
                "Access denied",
                "The authenticated operator is not authorized to perform this operation.",
                request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleResourceNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        LOGGER.warn("Resource not found for request path {}", request.getRequestURI());
        return problemDetailFactory.create(
                HttpStatus.NOT_FOUND,
                ProblemTypes.RESOURCE_NOT_FOUND,
                "Resource not found",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.NOT_FOUND,
                ProblemTypes.RESOURCE_NOT_FOUND,
                "Resource not found",
                "The requested resource was not found.",
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        LOGGER.warn("Request validation failed for request path {}", request.getRequestURI());

        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toValidationError)
                .sorted(Comparator.comparing(ValidationError::field)
                        .thenComparing(ValidationError::code)
                        .thenComparing(ValidationError::message))
                .toList();

        ProblemDetail problem = problemDetailFactory.create(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.VALIDATION_ERROR,
                "Validation failed",
                "One or more request fields are invalid.",
                request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(OperatorRequestValidationException.class)
    ProblemDetail handleOperatorRequestValidation(
            OperatorRequestValidationException exception, HttpServletRequest request) {
        return validationProblem(request);
    }

    @ExceptionHandler(CaseRequestValidationException.class)
    ProblemDetail handleCaseRequestValidation(CaseRequestValidationException exception, HttpServletRequest request) {
        return validationProblem(request);
    }

    @ExceptionHandler(EvidenceRequestValidationException.class)
    ProblemDetail handleEvidenceRequestValidation(
            EvidenceRequestValidationException exception, HttpServletRequest request) {
        return validationProblem(request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMediaTypeNotSupportedException.class,
        MissingServletRequestPartException.class,
        MultipartException.class
    })
    ProblemDetail handleInvalidRequestBinding(Exception exception, HttpServletRequest request) {
        return validationProblem(request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error(
                "Unexpected error [{}] for request path {}",
                exception.getClass().getName(),
                request.getRequestURI());
        LOGGER.debug("Unexpected error detail for request path {}", request.getRequestURI(), exception);
        return problemDetailFactory.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred.",
                request);
    }

    private ProblemDetail validationProblem(HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.VALIDATION_ERROR,
                "Validation failed",
                "One or more request fields are invalid.",
                request);
    }

    private ValidationError toValidationError(FieldError error) {
        return new ValidationError(error.getField(), error.getDefaultMessage(), error.getCode());
    }
}
