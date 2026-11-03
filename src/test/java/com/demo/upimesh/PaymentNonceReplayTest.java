package com.demo.upimesh;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DeviceIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PaymentNonceReplayTest {

    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private DeviceIdentityService identity;
    @Autowired private DigitalSignatureService signatures;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private BridgeIngestionService bridge;

    @Test
    void sameSignedPaymentReencryptedWithDifferentCiphertextSettlesOnlyOnce() throws Exception {
        String senderVpa = createSenderAccount();
        KeyPair deviceKey = identity.registerDevice(senderVpa);
        long now = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        PaymentInstruction instruction = signedInstruction(
                senderVpa, deviceKey, nonce, now, now + 60 * 60 * 1000L);

        MeshPacket firstPacket = packet(crypto.encrypt(instruction, serverKey.getPublicKey()));
        MeshPacket secondPacket = packet(crypto.encrypt(instruction, serverKey.getPublicKey()));
        assertNotEquals(firstPacket.getCiphertext(), secondPacket.getCiphertext());

        BridgeIngestionService.IngestResult first = bridge.ingest(firstPacket, "bridge-a", 1);
        BridgeIngestionService.IngestResult second = bridge.ingest(secondPacket, "bridge-b", 2);

        assertEquals("SETTLED", first.outcome());
        assertEquals("DUPLICATE_DROPPED", second.outcome());
        assertTrue(transactions.existsByNonce(nonce));
        assertEquals(1, transactions.countByNonce(nonce));
    }

    @Test
    void expiredSignedPaymentIsRejectedBeforeSettlement() throws Exception {
        String senderVpa = createSenderAccount();
        KeyPair deviceKey = identity.registerDevice(senderVpa);
        long now = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        PaymentInstruction instruction = signedInstruction(
                senderVpa, deviceKey, nonce, now - 60 * 60 * 1000L, now - 1L);

        MeshPacket packet = packet(crypto.encrypt(instruction, serverKey.getPublicKey()));
        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-a", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("expired_packet", result.reason());
        assertFalse(transactions.existsByNonce(nonce));
    }

    @Test
    void rotatingDeviceKeyRevokesPreviouslyCachedFingerprint() throws Exception {
        String senderVpa = createSenderAccount();
        KeyPair first = identity.registerDevice(senderVpa);
        String oldFingerprint = signatures.fingerprint(first.getPublic());
        assertNotNull(identity.getPublicKey(oldFingerprint));

        KeyPair second = identity.registerDevice(senderVpa);
        String newFingerprint = signatures.fingerprint(second.getPublic());

        assertNull(identity.getPublicKey(oldFingerprint));
        assertEquals(newFingerprint, signatures.fingerprint(identity.getPublicKey(newFingerprint)));
    }

    private String createSenderAccount() {
        String vpa = "nonce-test-" + UUID.randomUUID() + "@demo";
        accounts.save(new Account(vpa, "Nonce Test", new BigDecimal("100.00")));
        return vpa;
    }

    private PaymentInstruction signedInstruction(
            String senderVpa, KeyPair deviceKey, String nonce, long signedAt, long expiresAt)
            throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                "bob@demo",
                new BigDecimal("25.00"),
                "test-pin-hash",
                nonce,
                signedAt,
                expiresAt);
        instruction.setSenderPublicKeyFingerprint(signatures.fingerprint(deviceKey.getPublic()));
        instruction.setSignature(signatures.sign(instruction.canonicalBytes(), deviceKey.getPrivate()));
        return instruction;
    }

    private MeshPacket packet(String ciphertext) {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);
        return packet;
    }
}
