package it.itsprodigi.proofchain.custodyevent.application;

import java.util.UUID;

/**
 * Pure outcome of {@link CustodyChainVerifier#verify}, before the server clock timestamp is attached by the
 * service layer. {@code reason}, {@code brokenAtEventId}, {@code brokenAtSequenceNumber}, {@code
 * expectedValue} and {@code actualValue} are {@code null} exactly when {@code valid} is {@code true}.
 */
public record CustodyChainVerificationResult(
        UUID evidenceId,
        boolean valid,
        long checkedEvents,
        long storedEventCount,
        long loadedEventCount,
        String storedHeadHash,
        String calculatedHeadHash,
        UUID brokenAtEventId,
        Long brokenAtSequenceNumber,
        CustodyChainVerificationReason reason,
        String expectedValue,
        String actualValue) {}
