package it.itsprodigi.proofchain.custodycase.api;

import java.util.List;

public record CasePageResponse(List<CaseResponse> content, int page, int size, long totalElements, int totalPages) {}
