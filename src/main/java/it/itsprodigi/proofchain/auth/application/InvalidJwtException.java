package it.itsprodigi.proofchain.auth.application;

public class InvalidJwtException extends RuntimeException {
    public InvalidJwtException() {
        super("Invalid JWT");
    }
}
