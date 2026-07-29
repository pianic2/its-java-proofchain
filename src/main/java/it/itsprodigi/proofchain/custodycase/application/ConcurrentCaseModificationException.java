package it.itsprodigi.proofchain.custodycase.application;

public class ConcurrentCaseModificationException extends RuntimeException {

    public ConcurrentCaseModificationException(Throwable cause) {
        super("The custody case was modified concurrently.", cause);
    }
}
