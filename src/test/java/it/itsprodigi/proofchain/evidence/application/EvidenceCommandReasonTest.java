package it.itsprodigi.proofchain.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceCommandReasonTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void blankOrMissingReasonsAreRejected(String reason) {
        assertThatThrownBy(() -> EvidenceCommandReason.require(reason))
                .isInstanceOf(EvidenceRequestValidationException.class);
    }

    @Test
    void reasonIsTrimmedAndBoundedToOneThousandCharacters() {
        assertThat(EvidenceCommandReason.require("  handover to the laboratory  "))
                .isEqualTo("handover to the laboratory");
        assertThat(EvidenceCommandReason.require("  " + "r".repeat(1000) + "  "))
                .hasSize(1000);
        assertThatThrownBy(() -> EvidenceCommandReason.require("r".repeat(1001)))
                .isInstanceOf(EvidenceRequestValidationException.class);
    }

    @Test
    void unicodeContentIsPreserved() {
        String reason = "  consegna al laboratorio — perizia 検証 🔎  ";

        String normalized = EvidenceCommandReason.require(reason);

        assertThat(normalized).isEqualTo("consegna al laboratorio — perizia 検証 🔎");
    }

    @Test
    void unpairedSurrogatesAreRejectedBeforeTheAggregateIsMutated() {
        assertThatThrownBy(() -> EvidenceCommandReason.require("handover \uD83D to laboratory"))
                .isInstanceOf(EvidenceRequestValidationException.class);
        assertThatThrownBy(() -> EvidenceCommandReason.require("handover to laboratory \uD83D"))
                .isInstanceOf(EvidenceRequestValidationException.class);
        assertThatThrownBy(() -> EvidenceCommandReason.require("\uDE00 handover to laboratory"))
                .isInstanceOf(EvidenceRequestValidationException.class);
        assertThat(EvidenceCommandReason.require("handover to laboratory 😀")).isEqualTo("handover to laboratory 😀");
    }

    @Test
    void applicationValidationAgreesWithTheFrozenPayloadValidation() {
        String reason = EvidenceCommandReason.require("  transfer for analysis  ");

        CustodyTransferredPayload payload = new CustodyTransferredPayload(UUID.randomUUID(), UUID.randomUUID(), reason);

        assertThat(payload.reason()).isEqualTo(reason);
    }
}
