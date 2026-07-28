package it.itsprodigi.proofchain.operator.application;

import it.itsprodigi.proofchain.operator.api.CreateOperatorRequest;
import it.itsprodigi.proofchain.operator.api.OperatorDetailResponse;
import it.itsprodigi.proofchain.operator.api.OperatorPageResponse;
import it.itsprodigi.proofchain.operator.api.OperatorSortResponse;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorRoleRequest;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorStatusRequest;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorNormalizer;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorAdminService {

    private static final String VALIDATION_PASSWORD_HASH =
            "$2a$10$01234567890123456789012345678901234567890123456789012";
    private static final Set<String> DUPLICATE_CONSTRAINTS = Set.of("uk_operators_username", "uk_operators_email");

    private final OperatorRepository operators;
    private final OperatorMapper mapper;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final EntityManager entityManager;

    public OperatorAdminService(
            OperatorRepository operators,
            OperatorMapper mapper,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            Validator validator,
            EntityManager entityManager) {
        this.operators = Objects.requireNonNull(operators, "operators must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OperatorDetailResponse create(CreateOperatorRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Operator canonical = canonicalOperator(request);
        if (operators.existsByUsername(canonical.getUsername()) || operators.existsByEmail(canonical.getEmail())) {
            throw new DuplicateOperatorException();
        }

        passwordPolicy.validate(request.password());
        String passwordHash = passwordEncoder.encode(request.password());
        Operator operator = Operator.create(
                canonical.getUsername(),
                canonical.getEmail(),
                passwordHash,
                canonical.getFirstName(),
                canonical.getLastName(),
                canonical.getRole());
        try {
            return mapper.toDetail(operators.saveAndFlush(operator));
        } catch (DataIntegrityViolationException exception) {
            if (hasNamedConstraint(exception, DUPLICATE_CONSTRAINTS)) {
                throw new DuplicateOperatorException();
            }
            throw exception;
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public OperatorPageResponse list(int page, int size, List<String> publicSortCriteria) {
        validatePage(page, size);
        SortCriterion criterion = parseSort(publicSortCriteria);
        var pageable = PageRequest.of(
                page, size, Sort.by(criterion.direction(), criterion.field()).and(Sort.by(Sort.Direction.ASC, "id")));
        return mapper.toPage(
                operators.findAll(pageable),
                new OperatorSortResponse(
                        criterion.field(), criterion.direction().name().toLowerCase(Locale.ROOT)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public OperatorDetailResponse get(UUID id) {
        return mapper.toDetail(findOperator(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OperatorDetailResponse updateRole(UUID id, UpdateOperatorRoleRequest request, UUID actorId) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Operator target = findOperator(id);
        if (request.role() == target.getRole()) {
            return mapper.toDetail(target);
        }

        boolean mayDemoteActiveAdmin = target.getRole() == OperatorRole.ADMIN
                && target.getStatus() == OperatorStatus.ACTIVE
                && request.role() != OperatorRole.ADMIN;
        List<Operator> activeAdmins = mayDemoteActiveAdmin ? lockAndRefresh(target) : List.of();
        if (request.role() == target.getRole()) {
            return mapper.toDetail(target);
        }

        boolean demotesActiveAdmin = target.getRole() == OperatorRole.ADMIN
                && target.getStatus() == OperatorStatus.ACTIVE
                && request.role() != OperatorRole.ADMIN;
        if (demotesActiveAdmin) {
            boolean self = target.getId().equals(actorId);
            if (self && activeAdmins.size() < 2) {
                throw new OperatorInvariantException("Self-demotion requires another ACTIVE ADMIN.");
            }
            if (!self && activeAdmins.size() < 2) {
                throw new OperatorInvariantException("The operation would leave ProofChain without an ACTIVE ADMIN.");
            }
        }

        target.changeRole(request.role());
        flushAfterUpdate();
        return mapper.toDetail(target);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OperatorDetailResponse updateStatus(UUID id, UpdateOperatorStatusRequest request, UUID actorId) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Operator target = findOperator(id);
        if (request.status() == target.getStatus()) {
            return mapper.toDetail(target);
        }

        boolean mayDeactivateActiveAdmin = target.getRole() == OperatorRole.ADMIN
                && target.getStatus() == OperatorStatus.ACTIVE
                && request.status() != OperatorStatus.ACTIVE;
        List<Operator> activeAdmins = mayDeactivateActiveAdmin ? lockAndRefresh(target) : List.of();
        if (request.status() == target.getStatus()) {
            return mapper.toDetail(target);
        }

        boolean deactivatesSelf = target.getId().equals(actorId)
                && target.getRole() == OperatorRole.ADMIN
                && target.getStatus() == OperatorStatus.ACTIVE
                && request.status() != OperatorStatus.ACTIVE;
        if (deactivatesSelf) {
            throw new OperatorInvariantException("An ADMIN cannot suspend or disable itself.");
        }

        boolean deactivatesLastAdmin = target.getRole() == OperatorRole.ADMIN
                && target.getStatus() == OperatorStatus.ACTIVE
                && request.status() != OperatorStatus.ACTIVE;
        if (deactivatesLastAdmin && activeAdmins.size() < 2) {
            throw new OperatorInvariantException("The operation would leave ProofChain without an ACTIVE ADMIN.");
        }

        target.changeStatus(request.status());
        flushAfterUpdate();
        return mapper.toDetail(target);
    }

    private Operator canonicalOperator(CreateOperatorRequest request) {
        String username = OperatorNormalizer.normalizeUsername(request.username());
        String email = OperatorNormalizer.normalizeEmail(request.email());
        Operator candidate = Operator.create(
                username, email, VALIDATION_PASSWORD_HASH, request.firstName(), request.lastName(), request.role());
        if (!validator.validate(candidate).isEmpty()) {
            throw new IllegalArgumentException("operator request is invalid");
        }
        return candidate;
    }

    private Operator findOperator(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return operators
                .findById(id)
                .orElseThrow(() -> new it.itsprodigi.proofchain.common.exception.ResourceNotFoundException());
    }

    private List<Operator> lockAndRefresh(Operator target) {
        List<Operator> activeAdmins = operators.lockActiveAdmins();
        entityManager.refresh(target);
        return activeAdmins;
    }

    private void flushAfterUpdate() {
        try {
            operators.flush();
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new ConcurrentOperatorModificationException(exception);
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to zero");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }

    private static SortCriterion parseSort(List<String> publicSortCriteria) {
        List<String> criteria = publicSortCriteria == null ? List.of() : publicSortCriteria;
        if (criteria.isEmpty()) {
            return new SortCriterion("username", Sort.Direction.ASC);
        }
        if (criteria.size() != 1) {
            throw new IllegalArgumentException("exactly one sort criterion is supported");
        }
        String raw = criteria.getFirst();
        if (raw == null) {
            throw new IllegalArgumentException("sort must not be null");
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("sort must use field,direction format");
        }
        String field =
                switch (parts[0]) {
                    case "username", "email", "firstName", "lastName", "role", "status", "createdAt", "updatedAt" ->
                        parts[0];
                    default -> throw new IllegalArgumentException("sort field is not supported");
                };
        Sort.Direction direction =
                switch (parts[1]) {
                    case "asc" -> Sort.Direction.ASC;
                    case "desc" -> Sort.Direction.DESC;
                    default -> throw new IllegalArgumentException("sort direction is not supported");
                };
        return new SortCriterion(field, direction);
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

    private record SortCriterion(String field, Sort.Direction direction) {}
}
