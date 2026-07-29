package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.custodycase.api.CaseOperatorSummaryResponse;
import it.itsprodigi.proofchain.custodycase.api.CasePageResponse;
import it.itsprodigi.proofchain.custodycase.api.CaseResponse;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class CaseMapper {

    public CaseResponse toResponse(CustodyCase custodyCase) {
        Objects.requireNonNull(custodyCase, "custodyCase must not be null");
        return new CaseResponse(
                custodyCase.getId(),
                custodyCase.getTitle(),
                custodyCase.getDescription(),
                custodyCase.getAuthorityName(),
                custodyCase.getExternalReference(),
                custodyCase.getLocation(),
                custodyCase.getPriority(),
                custodyCase.getStatus(),
                toCreatorSummary(custodyCase.getCreatedBy()),
                custodyCase.getCreatedAt(),
                custodyCase.getUpdatedAt(),
                custodyCase.getClosedAt());
    }

    public CasePageResponse toPage(Page<CustodyCase> page) {
        Objects.requireNonNull(page, "page must not be null");
        return new CasePageResponse(
                page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private static CaseOperatorSummaryResponse toCreatorSummary(Operator operator) {
        return new CaseOperatorSummaryResponse(
                operator.getId(),
                operator.getUsername(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus());
    }
}
