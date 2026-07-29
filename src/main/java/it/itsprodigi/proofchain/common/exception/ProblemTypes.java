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
    public static final URI CASE_CLOSED = URI.create("https://proofchain.dev/problems/case-closed");
    public static final URI INVALID_CASE_STATUS_TRANSITION =
            URI.create("https://proofchain.dev/problems/invalid-case-status-transition");
    public static final URI LAST_CASE_MANAGER_REMOVAL =
            URI.create("https://proofchain.dev/problems/last-case-manager-removal");
    public static final URI OPERATOR_NOT_ACTIVE = URI.create("https://proofchain.dev/problems/operator-not-active");
    public static final URI ADMIN_MEMBERSHIP_NOT_ASSIGNABLE =
            URI.create("https://proofchain.dev/problems/admin-membership-not-assignable");
    public static final URI CONCURRENT_MEMBERSHIP_CONFLICT =
            URI.create("https://proofchain.dev/problems/concurrent-membership-conflict");
    public static final URI DUPLICATE_EVIDENCE_REFERENCE_TAG =
            URI.create("https://proofchain.dev/problems/duplicate-evidence-reference-tag");
    public static final URI HOLDER_NOT_ELIGIBLE = URI.create("https://proofchain.dev/problems/holder-not-eligible");
    public static final URI PAYLOAD_TOO_LARGE = URI.create("https://proofchain.dev/problems/payload-too-large");
    public static final URI STORAGE_FAILURE = URI.create("https://proofchain.dev/problems/storage-failure");
    public static final URI EVIDENCE_FILE_UNAVAILABLE =
            URI.create("https://proofchain.dev/problems/evidence-file-unavailable");

    private ProblemTypes() {}
}
