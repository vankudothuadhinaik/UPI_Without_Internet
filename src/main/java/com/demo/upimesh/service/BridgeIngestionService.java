package com.demo.upimesh.service;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Instant;

/**
 * Orchestrates the full server-side pipeline for one inbound packet from a
 * bridge node:
 *
 *   1. Hash the ciphertext.
 *   2. Try to claim that hash via the idempotency cache.
 *      - If already claimed: this is a duplicate. Drop it.
 *   3. Decrypt the ciphertext with the server's private key.
 *      - If decryption fails: tampered or junk. Reject.
 *   4. Verify digital signature using the sender's registered public key.
 *      - If signature invalid: forged or corrupted. Reject.
 *   5. Check freshness — reject if signedAt is too old (replay protection).
 *   6. Hand off to SettlementService for the actual debit/credit.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private DigitalSignatureService sigService;
    @Autowired private DeviceIdentityService identity;
    @Autowired private IdempotencyService idempotency;
    @Autowired private SettlementService settlement;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        boolean claimAcquired = false;
        boolean keepClaim = false;
        String claimedHash = null;
        try {
            String packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- Idempotency gate ----
            if (!idempotency.claim(packetHash)) {
                log.info("DUPLICATE packet {} from bridge {} — dropped",
                        packetHash.substring(0, 12) + "...", bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }
            claimAcquired = true;
            claimedHash = packetHash;

            // ---- Decrypt ----
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                log.warn("Decryption failed for packet {}: {}",
                        packetHash.substring(0, 12) + "...", e.getMessage());
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            // ---- Digital signature verification ----
            String fingerprint = instruction.getSenderPublicKeyFingerprint();
            if (fingerprint == null || fingerprint.isBlank()) {
                return IngestResult.invalid(packetHash, "missing_sender_key");
            }

            PublicKey senderPubKey = identity.getPublicKey(fingerprint);
            if (senderPubKey == null) {
                log.warn("Unknown sender public key fingerprint: {}", fingerprint);
                return IngestResult.invalid(packetHash, "unknown_sender_key");
            }

            boolean signatureValid = sigService.verify(
                    instruction.canonicalBytes(), instruction.getSignature(), senderPubKey);
            if (!signatureValid) {
                log.warn("Invalid signature for packet {} from sender {}",
                        packetHash.substring(0, 12) + "...", instruction.getSenderVpa());
                return IngestResult.invalid(packetHash, "signature_verification_failed");
            }

            // ---- Freshness check (replay protection) ----
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, 12) + "...", ageSeconds);
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ---- Settle ----
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            if (tx == null) {
                // The database uniqueness constraint confirmed this packet already
                // has a durable record; retain the claim as a fast-path hint.
                keepClaim = true;
                return IngestResult.duplicate(packetHash);
            }
            keepClaim = true;
            return IngestResult.settled(packetHash, tx);

        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            return IngestResult.invalid("?", "internal_error: " + e.getMessage());
        } finally {
            // A claim is provisional until validation and settlement finish.
            // Release invalid/decryption/signature/freshness failures and transient
            // processing errors so a retry is not blocked for the full Redis TTL.
            if (claimAcquired && !keepClaim && claimedHash != null) {
                idempotency.release(claimedHash);
            }
        }
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
    }
}
