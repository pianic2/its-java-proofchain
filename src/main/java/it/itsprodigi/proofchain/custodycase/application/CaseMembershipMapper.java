package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.custodycase.api.CaseOperatorSummaryResponse;
import it.itsprodigi.proofchain.custodycase.api.MembershipResponse;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class CaseMembershipMapper {

    public MembershipResponse toResponse(CaseMembership membership) {
        Objects.requireNonNull(membership, "membership must not be null");
        return new MembershipResponse(
                membership.getId(),
                membership.getCustodyCase().getId(),
                toOperatorSummary(membership.getOperator()),
                toOperatorSummary(membership.getAssignedBy()),
                membership.getAssignedAt());
    }

    private static CaseOperatorSummaryResponse toOperatorSummary(Operator operator) {
        return new CaseOperatorSummaryResponse(
                operator.getId(),
                operator.getUsername(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus());
    }
}
