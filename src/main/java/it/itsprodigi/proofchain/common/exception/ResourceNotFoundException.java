package it.itsprodigi.proofchain.common.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException() {
        super("The requested resource was not found.");
    }
}
