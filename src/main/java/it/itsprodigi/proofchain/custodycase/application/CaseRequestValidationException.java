package it.itsprodigi.proofchain.custodycase.application;

public class CaseRequestValidationException extends RuntimeException {

    public CaseRequestValidationException(String message) {
        super(message);
    }

    public CaseRequestValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
