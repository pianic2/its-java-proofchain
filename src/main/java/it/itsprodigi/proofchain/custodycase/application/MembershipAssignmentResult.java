package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.custodycase.api.MembershipResponse;

public record MembershipAssignmentResult(MembershipResponse membership, boolean created) {}
