package it.itsprodigi.proofchain.operator.api;

import java.util.List;

public record OperatorPageResponse(
        List<OperatorSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        OperatorSortResponse sort) {}
