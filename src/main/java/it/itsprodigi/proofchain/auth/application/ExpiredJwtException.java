package it.itsprodigi.proofchain.auth.application;

public class ExpiredJwtException extends RuntimeException {
    public ExpiredJwtException() {
        super("Expired JWT");
    }
}
