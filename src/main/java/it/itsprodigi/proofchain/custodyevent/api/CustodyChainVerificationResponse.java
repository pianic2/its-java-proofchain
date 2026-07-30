package it.itsprodigi.proofchain.custodyevent.api;

import it.itsprodigi.proofchain.custodyevent.application.CustodyChainVerificationReason;
import java.time.Instant;
import java.util.UUID;

/**
 * Fully detached, read-only result of a full-chain verification. {@code reason}, {@code brokenAtEventId},
 * {@code brokenAtSequenceNumber}, {@code expectedValue} and {@code actualValue} are {@code null} exactly
 * when {@code valid} is {@code true}; otherwise {@code checkedEvents} is the count of complete events
 * verified before the first violation and {@code calculatedHeadHash} is the last successfully verified
 * event hash (the zero hash when none passed).
 */
public record CustodyChainVerificationResponse(
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
        String actualValue,
        Instant verifiedAt) {}
