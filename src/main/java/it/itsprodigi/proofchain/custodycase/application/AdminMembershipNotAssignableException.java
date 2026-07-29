package it.itsprodigi.proofchain.custodycase.application;

public class AdminMembershipNotAssignableException extends RuntimeException {

    public AdminMembershipNotAssignableException() {
        super("ADMIN memberships cannot be assigned manually.");
    }
}
