package it.itsprodigi.proofchain.evidence.maintenance;

/**
 * The offline report could not be produced.
 *
 * <p>The message names the mechanism that failed and never the path, the connection string or the underlying driver
 * text, because a maintenance console transcript is pasted into tickets as freely as an API response is.
 */
public final class OrphanFileReportException extends RuntimeException {

    public OrphanFileReportException(String message) {
        super(message);
    }
}
