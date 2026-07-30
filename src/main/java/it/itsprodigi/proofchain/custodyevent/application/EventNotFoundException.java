package it.itsprodigi.proofchain.custodyevent.application;

public final class EventNotFoundException extends RuntimeException {

    public EventNotFoundException() {
        super("The requested custody event was not found.");
    }
}
