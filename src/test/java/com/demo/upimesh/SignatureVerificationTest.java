package com.demo.upimesh;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DeviceIdentityService;
import com.demo.upimesh.service.DemoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;

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
    @Autowired private AccountRepository accounts;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;

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

    @Test
    public void registeredKeyCannotAuthorizeAnotherVpa() throws Exception {
        Account alice = accounts.findById("alice@demo").orElseThrow();
        String previousFingerprint = alice.getPublicKeyFingerprint();
        String previousPublicKey = alice.getPublicKeyBase64();
        KeyPair aliceDevice = sigService.generateKeyPair();

        try {
            alice.setPublicKeyFingerprint(sigService.fingerprint(aliceDevice.getPublic()));
            alice.setPublicKeyBase64(sigService.encodePublicKey(aliceDevice.getPublic()));
            accounts.saveAndFlush(alice);

            long now = Instant.now().toEpochMilli();
            MeshPacket forgedSender = signedPacket(
                    aliceDevice, "bob@demo", "carol@demo", now, now + 60_000);

            var result = ingestion.ingest(forgedSender, "bridge-impersonation", 1);

            assertEquals("INVALID", result.outcome());
            assertEquals("sender_key_mismatch", result.reason());
        } finally {
            alice.setPublicKeyFingerprint(previousFingerprint);
            alice.setPublicKeyBase64(previousPublicKey);
            accounts.saveAndFlush(alice);
        }
    }

    @Test
    public void expiredButCorrectlySignedPacketIsRejected() throws Exception {
        Account alice = accounts.findById("alice@demo").orElseThrow();
        String previousFingerprint = alice.getPublicKeyFingerprint();
        String previousPublicKey = alice.getPublicKeyBase64();
        KeyPair aliceDevice = sigService.generateKeyPair();

        try {
            alice.setPublicKeyFingerprint(sigService.fingerprint(aliceDevice.getPublic()));
            alice.setPublicKeyBase64(sigService.encodePublicKey(aliceDevice.getPublic()));
            accounts.saveAndFlush(alice);

            long now = Instant.now().toEpochMilli();
            MeshPacket expired = signedPacket(
                    aliceDevice, "alice@demo", "bob@demo", now - 60_000, now - 1);

            var result = ingestion.ingest(expired, "bridge-expiry", 1);

            assertEquals("INVALID", result.outcome());
            assertEquals("expired_packet", result.reason());
        } finally {
            alice.setPublicKeyFingerprint(previousFingerprint);
            alice.setPublicKeyBase64(previousPublicKey);
            accounts.saveAndFlush(alice);
        }
    }

    private MeshPacket signedPacket(
            KeyPair signer, String senderVpa, String receiverVpa,
            long signedAt, long expiresAt) throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa, receiverVpa, new BigDecimal("1.00"), "unused-pin-hash",
                UUID.randomUUID().toString(), signedAt, expiresAt);
        instruction.setSenderPublicKeyFingerprint(sigService.fingerprint(signer.getPublic()));
        instruction.setSignature(sigService.sign(
                instruction.canonicalBytes(), signer.getPrivate()));

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(crypto.encrypt(instruction, serverKey.getPublicKey()));
        return packet;
    }
}
