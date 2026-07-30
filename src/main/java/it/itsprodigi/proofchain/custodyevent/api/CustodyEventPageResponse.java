package it.itsprodigi.proofchain.custodyevent.api;

import java.util.List;

public record CustodyEventPageResponse(
        List<CustodyEventSummaryResponse> content, int page, int size, long totalElements, int totalPages) {}
