package com.demo.upimesh;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DeviceIdentityService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BridgeIngestionClaimReleaseTest {

    @Mock private HybridCryptoService crypto;
    @Mock private DigitalSignatureService signatures;
    @Mock private DeviceIdentityService identity;
    @Mock private IdempotencyService idempotency;
    @Mock private SettlementService settlement;
    @InjectMocks private BridgeIngestionService ingestion;

    @Test
    void malformedCiphertextReleasesProvisionalIdempotencyClaim() throws Exception {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId("packet-1");
        packet.setCiphertext("not-valid-ciphertext");
        packet.setCreatedAt(System.currentTimeMillis());

        when(crypto.hashCiphertext(packet.getCiphertext())).thenReturn("packet-hash-long");
        when(idempotency.claim("packet-hash-long")).thenReturn(true);
        when(crypto.decrypt(packet.getCiphertext()))
                .thenThrow(new IllegalArgumentException("bad ciphertext"));

        BridgeIngestionService.IngestResult result =
                ingestion.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("decryption_failed", result.reason());
        verify(idempotency).release("packet-hash-long");
        verifyNoInteractions(settlement);
    }
}
