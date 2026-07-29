package it.itsprodigi.proofchain.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceHashingAndStorageKeyTest {

    private static final UUID CASE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID EVIDENCE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174001");
    private static final String ABC_SHA_256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void createsAndValidatesTheCanonicalStorageKey() {
        String key = EvidenceStorageKeyFactory.forEvidence(CASE_ID, EVIDENCE_ID);

        assertThat(key)
                .isEqualTo(
                        "cases/123e4567-e89b-42d3-a456-426614174000/evidences/123e4567-e89b-42d3-a456-426614174001/content.bin");
        assertThat(EvidenceStorageKeyFactory.requireCanonical(key)).isSameAs(key);
        assertThatNullPointerException().isThrownBy(() -> EvidenceStorageKeyFactory.forEvidence(null, EVIDENCE_ID));
        assertThatNullPointerException().isThrownBy(() -> EvidenceStorageKeyFactory.forEvidence(CASE_ID, null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "/cases/123e4567-e89b-42d3-a456-426614174000/evidences/123e4567-e89b-42d3-a456-426614174001/content.bin",
                "cases/../content.bin",
                "cases/123E4567-E89B-42D3-A456-426614174000/evidences/123e4567-e89b-42d3-a456-426614174001/content.bin",
                "cases/123e4567-e89b-42d3-a456-426614174000//evidences/123e4567-e89b-42d3-a456-426614174001/content.bin",
                "cases\\123e4567-e89b-42d3-a456-426614174000\\evidences\\123e4567-e89b-42d3-a456-426614174001\\content.bin",
                "cases/123e4567-e89b-42d3-a456-426614174000/evidences/123e4567-e89b-42d3-a456-426614174001/original.bin",
                "cases/123e4567-e89b-42d3-a456-426614174000/evidences/123e4567-e89b-42d3-a456-426614174001/content.bin\n"
            })
    void rejectsUnsafeOrNonCanonicalStorageKeys(String key) {
        assertThatThrownBy(() -> EvidenceStorageKeyFactory.requireCanonical(key))
                .isInstanceOf(UnsafeEvidenceStoragePathException.class);
    }

    @Test
    void hashesExactContentBytesUsingLowercaseSha256Vectors() {
        assertThat(EvidenceHashing.contentSha256(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(EvidenceHashing.contentSha256("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(ABC_SHA_256);
    }

    @Test
    void hashesTheExactVersionedUtf8ContextAndRejectsInvalidInputs() {
        assertThat(EvidenceHashing.contextualSha256(CASE_ID, EVIDENCE_ID, ABC_SHA_256))
                .isEqualTo("f31b4590a4e472a2df8323ab3c2aa2a6486b940bb23550aabec56fca855d1089");

        assertThatNullPointerException()
                .isThrownBy(() -> EvidenceHashing.contextualSha256(null, EVIDENCE_ID, ABC_SHA_256));
        assertThatNullPointerException().isThrownBy(() -> EvidenceHashing.contextualSha256(CASE_ID, null, ABC_SHA_256));
        assertThatThrownBy(() -> EvidenceHashing.contextualSha256(CASE_ID, EVIDENCE_ID, ABC_SHA_256.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceHashing.contextualSha256(CASE_ID, EVIDENCE_ID, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
