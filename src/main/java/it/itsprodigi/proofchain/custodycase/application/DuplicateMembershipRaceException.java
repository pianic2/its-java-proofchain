package it.itsprodigi.proofchain.custodycase.application;

final class DuplicateMembershipRaceException extends RuntimeException {

    DuplicateMembershipRaceException(Throwable cause) {
        super(cause);
    }
}
