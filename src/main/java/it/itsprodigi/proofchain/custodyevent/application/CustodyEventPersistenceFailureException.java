package it.itsprodigi.proofchain.custodyevent.application;

public class CustodyEventPersistenceFailureException extends RuntimeException {

    public CustodyEventPersistenceFailureException(Throwable cause) {
        super("The custody event could not be persisted", cause);
    }
}
