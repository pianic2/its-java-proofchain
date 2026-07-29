package it.itsprodigi.proofchain.custodycase.application;

public class LastCaseManagerRemovalException extends RuntimeException {

    public LastCaseManagerRemovalException() {
        super("The operation would leave the custody case without a responsible manager.");
    }
}
