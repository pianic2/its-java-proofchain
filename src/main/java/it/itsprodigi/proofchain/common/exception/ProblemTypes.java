package it.itsprodigi.proofchain.common.exception;

import java.net.URI;

public final class ProblemTypes {

    public static final URI RESOURCE_NOT_FOUND = URI.create("https://proofchain.dev/problems/resource-not-found");
    public static final URI VALIDATION_ERROR = URI.create("https://proofchain.dev/problems/validation-error");
    public static final URI INTERNAL_SERVER_ERROR = URI.create("https://proofchain.dev/problems/internal-server-error");
    public static final URI AUTHENTICATION_REQUIRED =
            URI.create("https://proofchain.dev/problems/authentication-required");
    public static final URI INVALID_TOKEN = URI.create("https://proofchain.dev/problems/invalid-token");
    public static final URI EXPIRED_TOKEN = URI.create("https://proofchain.dev/problems/expired-token");
    public static final URI ACCESS_DENIED = URI.create("https://proofchain.dev/problems/access-denied");
    public static final URI INVALID_CREDENTIALS = URI.create("https://proofchain.dev/problems/invalid-credentials");
    public static final URI DUPLICATE_RESOURCE = URI.create("https://proofchain.dev/problems/duplicate-resource");
    public static final URI OPERATOR_INVARIANT_CONFLICT =
            URI.create("https://proofchain.dev/problems/operator-invariant-conflict");
    public static final URI CONCURRENT_MODIFICATION =
            URI.create("https://proofchain.dev/problems/concurrent-modification");

    private ProblemTypes() {}
}
