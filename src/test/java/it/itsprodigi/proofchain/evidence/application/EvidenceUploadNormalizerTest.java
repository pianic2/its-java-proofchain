package it.itsprodigi.proofchain.evidence.application;

import static org.assertj.core.api.Assertions.assertThatCode;

import it.itsprodigi.proofchain.evidence.api.CreateEvidenceRequest;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceUploadNormalizerTest {

    @Test
    void acceptsOptionalValueAtItsLimitAfterStrippingOuterWhitespace() {
        CreateEvidenceRequest request = new CreateEvidenceRequest(
                null,
                " Valid title ",
                null,
                SourceType.DEVICE,
                null,
                "  " + "A".repeat(100) + "  ",
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                null,
                null,
                null,
                null,
                null,
                UUID.randomUUID());

        assertThatCode(() -> EvidenceUploadNormalizer.validateMetadata(request)).doesNotThrowAnyException();
    }
}
