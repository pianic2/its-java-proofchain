package it.itsprodigi.proofchain.migration;

import it.itsprodigi.proofchain.migration.LegacyDataFixture.EvidenceSeed;

/**
 * Rebuilds a certified baseline by replaying the historical timeline: each representative row is inserted at the
 * version where its table first existed, and the remaining migrations of that baseline are then applied on top. No
 * committed dump, no hand-written DDL and no invented data are involved — only the immutable production migrations.
 */
final class BaselineReconstruction {

    private BaselineReconstruction() {}

    static void reconstruct(MigrationSchemaHarness harness, CertifiedBaseline baseline, EvidenceSeed seed) {
        reconstruct(harness, baseline.version(), seed);
    }

    static void reconstruct(MigrationSchemaHarness harness, int version, EvidenceSeed seed) {
        harness.migrateTo(1);
        LegacyDataFixture.insertOperators(harness);
        if (version >= 2) {
            harness.migrateTo(2);
            LegacyDataFixture.insertCaseAndMembership(harness);
        }
        if (version >= 3) {
            harness.migrateTo(3);
            LegacyDataFixture.insertEvidence(harness, seed);
        }
        harness.migrateTo(version);
    }
}
