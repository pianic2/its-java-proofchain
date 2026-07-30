package it.itsprodigi.proofchain.custodyevent.application;

/**
 * Exact reason a custody-chain verification failed, in the precedence order evaluated by
 * {@link CustodyChainVerifier}. {@code null} on the response whenever verification is valid.
 */
public enum CustodyChainVerificationReason {
    EMPTY_CHAIN,
    CHAIN_LENGTH_MISMATCH,
    CASE_MISMATCH,
    EVIDENCE_MISMATCH,
    SEQUENCE_GAP,
    GENESIS_MISMATCH,
    PREVIOUS_HASH_MISMATCH,
    UNSUPPORTED_HASH_VERSION,
    UNSUPPORTED_PAYLOAD_VERSION,
    INVALID_PAYLOAD,
    EVENT_HASH_MISMATCH,
    CHAIN_HEAD_MISMATCH
}
