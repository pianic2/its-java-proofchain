package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;

/**
 * Workflow-specific part of an operational custody command.
 *
 * <p>The body runs inside the shared transaction, after both locks are held and after shared authorization and
 * lifecycle checks. It validates the workflow invariants, mutates the locked aggregate and returns the exact typed
 * Sprint 4 payload built from the resulting aggregate state.
 */
@FunctionalInterface
public interface EvidenceCommandBody {

    CustodyEventPayload apply(EvidenceCommandContext context);
}
