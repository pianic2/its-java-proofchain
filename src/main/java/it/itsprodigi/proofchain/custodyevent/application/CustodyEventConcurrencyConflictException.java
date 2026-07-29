package it.itsprodigi.proofchain.custodyevent.application;

public class CustodyEventConcurrencyConflictException extends RuntimeException {

    public CustodyEventConcurrencyConflictException(Throwable cause) {
        super("A concurrent custody event append prevented the operation", cause);
    }
}
