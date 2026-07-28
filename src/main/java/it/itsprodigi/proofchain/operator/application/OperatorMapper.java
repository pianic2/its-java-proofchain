package it.itsprodigi.proofchain.operator.application;

import it.itsprodigi.proofchain.operator.api.OperatorDetailResponse;
import it.itsprodigi.proofchain.operator.api.OperatorPageResponse;
import it.itsprodigi.proofchain.operator.api.OperatorSortResponse;
import it.itsprodigi.proofchain.operator.api.OperatorSummaryResponse;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class OperatorMapper {

    public OperatorSummaryResponse toSummary(Operator operator) {
        Objects.requireNonNull(operator, "operator must not be null");
        return new OperatorSummaryResponse(
                operator.getId(),
                operator.getUsername(),
                operator.getEmail(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus());
    }

    public OperatorDetailResponse toDetail(Operator operator) {
        Objects.requireNonNull(operator, "operator must not be null");
        return new OperatorDetailResponse(
                operator.getId(),
                operator.getUsername(),
                operator.getEmail(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus(),
                operator.getCreatedAt(),
                operator.getUpdatedAt());
    }

    public OperatorPageResponse toPage(Page<Operator> page, OperatorSortResponse sort) {
        Objects.requireNonNull(page, "page must not be null");
        Objects.requireNonNull(sort, "sort must not be null");
        return new OperatorPageResponse(
                page.getContent().stream().map(this::toSummary).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                sort);
    }
}
