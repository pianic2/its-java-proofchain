package it.itsprodigi.proofchain.operator.application;

public class OperatorRequestValidationException extends RuntimeException {

    public OperatorRequestValidationException(String message) {
        super(message);
    }

    public OperatorRequestValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
