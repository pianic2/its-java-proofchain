package it.itsprodigi.proofchain.custodyevent.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class CustodyEventHashing {

    public static final int HASH_VERSION = 1;
    public static final String ZERO_HASH = "0".repeat(64);

    private static final byte[] DOMAIN_PREFIX = "proofchain:custody-event:v1\n".getBytes(StandardCharsets.UTF_8);

    private CustodyEventHashing() {}

    public static String eventHash(CanonicalCustodyEvent event) {
        return eventHash(event, HASH_VERSION);
    }

    public static String eventHash(CanonicalCustodyEvent event, int hashVersion) {
        Objects.requireNonNull(event, "event must not be null");
        if (hashVersion != HASH_VERSION) {
            throw new IllegalArgumentException("hashVersion must be 1");
        }
        byte[] canonicalJson = CustodyEventCanonicalizer.canonicalBytes(event);
        ByteArrayOutputStream input = new ByteArrayOutputStream(DOMAIN_PREFIX.length + canonicalJson.length);
        input.writeBytes(DOMAIN_PREFIX);
        input.writeBytes(canonicalJson);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
