package it.itsprodigi.proofchain.custodycase.application;

public class InvalidCaseStatusTransitionException extends RuntimeException {

    public InvalidCaseStatusTransitionException() {
        super("CLOSED is the only permitted target status.");
    }
}
