package it.itsprodigi.proofchain.custodycase.domain;

import it.itsprodigi.proofchain.operator.domain.Operator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "custody_cases")
public class CustodyCase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotBlank
    @Size(min = 3, max = 200)
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Size(max = 2000)
    @Column(name = "description", length = 2000)
    private String description;

    @Size(max = 200)
    @Column(name = "authority_name", length = 200)
    private String authorityName;

    @Size(max = 200)
    @Column(name = "external_reference", length = 200)
    private String externalReference;

    @Size(max = 300)
    @Column(name = "location", length = 300)
    private String location;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private CasePriority priority;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CaseStatus status;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_operator_id", nullable = false, updatable = false)
    private Operator createdBy;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @PositiveOrZero
    @Column(name = "version", nullable = false)
    private long version;

    protected CustodyCase() {}

    private CustodyCase(
            String title,
            String description,
            String authorityName,
            String externalReference,
            String location,
            CasePriority priority,
            Operator createdBy) {
        id = UUID.randomUUID();
        this.title = validTitle(title);
        this.description = validOptional(description, 2000, "description");
        this.authorityName = validOptional(authorityName, 200, "authorityName");
        this.externalReference = validOptional(externalReference, 200, "externalReference");
        this.location = validOptional(location, 300, "location");
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
        status = CaseStatus.OPEN;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        Instant now = nowAtMicrosecondPrecision();
        createdAt = now;
        updatedAt = now;
        version = 0L;
    }

    public static CustodyCase create(
            String title,
            String description,
            String authorityName,
            String externalReference,
            String location,
            CasePriority priority,
            Operator createdBy) {
        return new CustodyCase(title, description, authorityName, externalReference, location, priority, createdBy);
    }

    public void updateMetadata(
            String title,
            String description,
            String authorityName,
            String externalReference,
            String location,
            CasePriority priority) {
        requireOpen();
        this.title = validTitle(title);
        this.description = validOptional(description, 2000, "description");
        this.authorityName = validOptional(authorityName, 200, "authorityName");
        this.externalReference = validOptional(externalReference, 200, "externalReference");
        this.location = validOptional(location, 300, "location");
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
        touch();
    }

    public void close() {
        requireOpen();
        Instant now = nextUpdatedAt();
        status = CaseStatus.CLOSED;
        closedAt = now;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthorityName() {
        return authorityName;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getLocation() {
        return location;
    }

    public CasePriority getPriority() {
        return priority;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public Operator getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CustodyCase custodyCase)) {
            return false;
        }
        return id != null && id.equals(custodyCase.id);
    }

    @Override
    public int hashCode() {
        return CustodyCase.class.hashCode();
    }

    @Override
    public String toString() {
        return "CustodyCase{" + "id=" + id + ", title='" + title + '\'' + ", priority=" + priority + ", status="
                + status + '}';
    }

    private static String validTitle(String title) {
        String normalized = CustodyCaseNormalizer.normalizeRequired(title, "title");
        if (normalized.isBlank() || normalized.length() < 3 || normalized.length() > 200) {
            throw new IllegalArgumentException("title must be 3 to 200 characters");
        }
        return normalized;
    }

    private static String validOptional(String value, int maximumLength, String fieldName) {
        String normalized = CustodyCaseNormalizer.normalizeOptional(value);
        if (normalized != null && normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private void requireOpen() {
        if (status != CaseStatus.OPEN) {
            throw new IllegalStateException("closed custody cases are immutable");
        }
    }

    private void touch() {
        updatedAt = nextUpdatedAt();
    }

    private Instant nextUpdatedAt() {
        Instant now = nowAtMicrosecondPrecision();
        Instant current = updatedAt.truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(current) ? now : current.plus(1, ChronoUnit.MICROS);
    }

    private static Instant nowAtMicrosecondPrecision() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
