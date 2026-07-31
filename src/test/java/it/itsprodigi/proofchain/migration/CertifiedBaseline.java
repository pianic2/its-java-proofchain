package it.itsprodigi.proofchain.migration;

/**
 * The certified schema baselines that can be reconstructed <em>factually</em> from repository history.
 *
 * <p>Every production migration file was added by exactly one commit and never modified afterwards — {@code git log
 * --diff-filter=A} and {@code git log} report the same single commit for each of {@code V1} … {@code V7}. The migration
 * directory therefore only ever grew, so the schema shipped at commit <em>C</em> is exactly the schema produced by
 * applying the versions that existed at <em>C</em>, in order, with today's unchanged files. Reconstructing a baseline
 * needs no committed dump and no guessed data: it is {@code flyway migrate} with {@code target} set to that version.
 *
 * <p>No earlier baseline exists. Sprint 0 shipped no migration at all, so the only state before {@code V1} is an empty
 * database, which the empty-database certification already covers.
 */
enum CertifiedBaseline {
    /** Operators only. */
    SPRINT_1_OPERATORS(1, "IJPC-133", "d679fa7", "Sprint 1 operator persistence model"),

    /** Custody cases and case memberships. */
    SPRINT_2_CUSTODY_CASES(2, "IJPC-146", "4383deb", "Sprint 2 custody case and membership foundation"),

    /** Digital evidence metadata and storage keys. */
    SPRINT_3_DIGITAL_EVIDENCE(3, "IJPC-151", "c86fcff", "Sprint 3 digital evidence persistence foundation"),

    /** Append-only custody events table, trigger and function. */
    SPRINT_4_CUSTODY_EVENTS(4, "IJPC-157", "2182898", "Sprint 4 append-only custody event persistence"),

    /** Denormalized chain head and event count on the evidence row. */
    SPRINT_4_CHAIN_HEAD(5, "IJPC-158", "6c87969", "Sprint 4 custody event hashing and appender"),

    /** Genesis backfill applied: every pre-existing evidence row carries its EVIDENCE_REGISTERED event. */
    SPRINT_4_CERTIFIED(6, "IJPC-159", "b0a6ecc", "Sprint 4 certified baseline (evidence custody genesis)"),

    /** Database-enforced evidence lifecycle graph; the certified Sprint 5 baseline. */
    SPRINT_5_CERTIFIED(7, "IJPC-167", "240108f", "Sprint 5 certified baseline (evidence lifecycle transitions)");

    private final int version;
    private final String jiraKey;
    private final String commit;
    private final String description;

    CertifiedBaseline(int version, String jiraKey, String commit, String description) {
        this.version = version;
        this.jiraKey = jiraKey;
        this.commit = commit;
        this.description = description;
    }

    int version() {
        return version;
    }

    String jiraKey() {
        return jiraKey;
    }

    String commit() {
        return commit;
    }

    String description() {
        return description;
    }

    boolean hasCustodyCases() {
        return version >= 2;
    }

    boolean hasDigitalEvidence() {
        return version >= 3;
    }
}
