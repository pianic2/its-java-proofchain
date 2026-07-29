package it.itsprodigi.proofchain.custodycase.application;

public class ConcurrentMembershipConflictException extends RuntimeException {

    public ConcurrentMembershipConflictException() {
        super("The case membership was modified concurrently. Retry using current data.");
    }
}
