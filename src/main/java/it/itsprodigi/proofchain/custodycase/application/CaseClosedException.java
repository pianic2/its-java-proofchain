package it.itsprodigi.proofchain.custodycase.application;

public class CaseClosedException extends RuntimeException {

    public CaseClosedException() {
        super("The custody case is closed and cannot be modified.");
    }
}
