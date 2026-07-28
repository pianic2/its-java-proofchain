package it.itsprodigi.proofchain.common.exception;

import it.itsprodigi.proofchain.auth.application.InvalidCredentialsException;
import it.itsprodigi.proofchain.operator.application.ConcurrentOperatorModificationException;
import it.itsprodigi.proofchain.operator.application.DuplicateOperatorException;
import it.itsprodigi.proofchain.operator.application.OperatorInvariantException;
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
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ProblemDetailFactory problemDetailFactory;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
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

    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ProblemDetail handleInvalidRequest(Exception exception, HttpServletRequest request) {
        return problemDetailFactory.create(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.VALIDATION_ERROR,
                "Validation failed",
                "One or more request fields are invalid.",
                request);
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

    private ValidationError toValidationError(FieldError error) {
        return new ValidationError(error.getField(), error.getDefaultMessage(), error.getCode());
    }
}
