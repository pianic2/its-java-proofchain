package it.itsprodigi.proofchain.custodycase.application;

public class AffectedCaseSetChangedException extends RuntimeException {

    public AffectedCaseSetChangedException() {
        super("Custody case memberships changed while reducing operator responsibility.");
    }
}
