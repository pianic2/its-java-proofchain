package it.itsprodigi.proofchain.common.exception;

import java.net.URI;

final class ProblemTypes {

    static final URI RESOURCE_NOT_FOUND = URI.create("https://proofchain.dev/problems/resource-not-found");
    static final URI VALIDATION_ERROR = URI.create("https://proofchain.dev/problems/validation-error");
    static final URI INTERNAL_SERVER_ERROR = URI.create("https://proofchain.dev/problems/internal-server-error");

    private ProblemTypes() {}
}
