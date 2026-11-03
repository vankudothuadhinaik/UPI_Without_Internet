package com.demo.upimesh;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DeviceIdentityService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentIdentityValidationTest {

    @Test
    void keyRegisteredToAnotherVpaIsNotAccepted() throws Exception {
        AccountRepository accounts = mock(AccountRepository.class);
        DigitalSignatureService signatures = mock(DigitalSignatureService.class);
        Account bob = new Account("bob@demo", "Bob", new BigDecimal("100.00"));
        bob.setPublicKeyFingerprint("bob-fingerprint");
        bob.setPublicKeyBase64("bob-public-key");
        when(accounts.findById("bob@demo")).thenReturn(Optional.of(bob));

        DeviceIdentityService identity = new DeviceIdentityService();
        ReflectionTestUtils.setField(identity, "accounts", accounts);
        ReflectionTestUtils.setField(identity, "sigService", signatures);

        assertNull(identity.getPublicKeyForVpa("bob@demo", "alice-fingerprint"));
        verify(signatures, never()).decodePublicKey(anyString());
    }

    @Test
    void matchingVpaAndFingerprintResolveRegisteredPublicKey() throws Exception {
        AccountRepository accounts = mock(AccountRepository.class);
        DigitalSignatureService signatures = mock(DigitalSignatureService.class);
        Account bob = new Account("bob@demo", "Bob", new BigDecimal("100.00"));
        bob.setPublicKeyFingerprint("bob-fingerprint");
        bob.setPublicKeyBase64("bob-public-key");
        PublicKey key = mock(PublicKey.class);
        when(accounts.findById("bob@demo")).thenReturn(Optional.of(bob));
        when(signatures.decodePublicKey("bob-public-key")).thenReturn(key);
        when(signatures.fingerprint(key)).thenReturn("bob-fingerprint");

        DeviceIdentityService identity = new DeviceIdentityService();
        ReflectionTestUtils.setField(identity, "accounts", accounts);
        ReflectionTestUtils.setField(identity, "sigService", signatures);

        assertSame(key, identity.getPublicKeyForVpa("bob@demo", "bob-fingerprint"));
    }

    @Test
    void expiredPacketIsRejectedBeforeSettlement() throws Exception {
        HybridCryptoService crypto = mock(HybridCryptoService.class);
        DigitalSignatureService signatures = mock(DigitalSignatureService.class);
        DeviceIdentityService identity = mock(DeviceIdentityService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        SettlementService settlement = mock(SettlementService.class);
        PublicKey senderKey = mock(PublicKey.class);

        long now = Instant.now().toEpochMilli();
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("1.00"),
                "pin-hash", UUID.randomUUID().toString(), now - 60_000, now - 1);
        instruction.setSenderPublicKeyFingerprint("alice-fingerprint");
        instruction.setSignature("valid-signature");

        when(crypto.hashCiphertext("ciphertext")).thenReturn("packet-hash");
        when(crypto.decrypt("ciphertext")).thenReturn(instruction);
        when(idempotency.claim("packet-hash")).thenReturn(true);
        when(identity.getPublicKeyForVpa("alice@demo", "alice-fingerprint"))
                .thenReturn(senderKey);
        when(signatures.verify(any(byte[].class), eq("valid-signature"), eq(senderKey)))
                .thenReturn(true);

        BridgeIngestionService ingestion = new BridgeIngestionService();
        ReflectionTestUtils.setField(ingestion, "crypto", crypto);
        ReflectionTestUtils.setField(ingestion, "sigService", signatures);
        ReflectionTestUtils.setField(ingestion, "identity", identity);
        ReflectionTestUtils.setField(ingestion, "idempotency", idempotency);
        ReflectionTestUtils.setField(ingestion, "settlement", settlement);
        ReflectionTestUtils.setField(ingestion, "maxAgeSeconds", 86_400L);

        MeshPacket packet = new MeshPacket();
        packet.setCiphertext("ciphertext");

        BridgeIngestionService.IngestResult result =
                ingestion.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("expired_packet", result.reason());
        verify(identity).getPublicKeyForVpa("alice@demo", "alice-fingerprint");
        verifyNoInteractions(settlement);
        verify(idempotency).release("packet-hash");
    }
}
