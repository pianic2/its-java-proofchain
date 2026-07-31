package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventSummaryResponse;

@Schema(
        description =
                "Result of an operational custody command: the complete evidence state and the appended custody event summary.")
public record EvidenceOperationResponse(EvidenceResponse evidence, CustodyEventSummaryResponse eventSummary) {}
