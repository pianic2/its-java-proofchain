package it.itsprodigi.proofchain.custodycase.application;

public class OperatorNotActiveException extends RuntimeException {

    public OperatorNotActiveException() {
        super("Only ACTIVE operators can receive a new case membership.");
    }
}
