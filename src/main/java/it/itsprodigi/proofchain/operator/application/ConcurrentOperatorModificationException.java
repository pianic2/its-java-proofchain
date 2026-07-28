package it.itsprodigi.proofchain.operator.application;

public class ConcurrentOperatorModificationException extends RuntimeException {

    public ConcurrentOperatorModificationException(Throwable cause) {
        super(cause);
    }
}
