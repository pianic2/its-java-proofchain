package it.itsprodigi.proofchain.custodyevent.application;

public final class CustodyChainReadFailureException extends RuntimeException {

    private static final String SAFE_MESSAGE = "Custody chain data could not be read safely.";

    public CustodyChainReadFailureException() {
        super(SAFE_MESSAGE);
    }

    public CustodyChainReadFailureException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
