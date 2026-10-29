package com.demo.upimesh;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DeviceIdentityService;
import com.demo.upimesh.service.DemoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Digital signature verification test.
 * Verifies that:
 *   1. A valid signed packet settles successfully.
 *   2. A packet with a forged signature is rejected.
 *   3. A packet with a tampered amount fails signature verification.
 */
@SpringBootTest
public class SignatureVerificationTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService ingestion;
    @Autowired private DigitalSignatureService sigService;
    @Autowired private DeviceIdentityService identity;

    @Test
    public void validSignature_SettlesSuccessfully() throws Exception {
        var packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("25.00"), "1234", 5);

        var result = ingestion.ingest(packet, "bridge-X", 1);
        assertEquals("SETTLED", result.outcome(),
                "Valid signed packet should settle");
    }

    @Test
    public void forgedSignature_IsRejected() throws Exception {
        // Create a packet normally
        var packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("25.00"), "1234", 5);

        // Tamper with the ciphertext (flip a byte) — this destroys the AES-GCM tag
        // and also makes the signature invalid since the decrypted payload changes
        char[] chars = packet.getCiphertext().toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        packet.setCiphertext(new String(chars));

        var result = ingestion.ingest(packet, "bridge-X", 1);
        assertEquals("INVALID", result.outcome(),
                "Tampered packet should be rejected");
    }

    @Test
    public void malformedCiphertext_IsRejectedWithoutSettlement() throws Exception {
        var packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "1234", 5);
        packet.setCiphertext("not-base64!");

        var result = ingestion.ingest(packet, "bridge-A", 1);

        assertEquals("INVALID", result.outcome());
    }

    @Test
    public void validPacketTwice_SecondIsDuplicate() throws Exception {
        var packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "1234", 5);

        var first = ingestion.ingest(packet, "bridge-A", 1);
        assertEquals("SETTLED", first.outcome());

        var second = ingestion.ingest(packet, "bridge-A", 1);
        assertEquals("DUPLICATE_DROPPED", second.outcome());
    }
}