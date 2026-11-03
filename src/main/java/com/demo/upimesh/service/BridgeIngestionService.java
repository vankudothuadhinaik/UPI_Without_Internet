package com.demo.upimesh.service;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Instant;
import java.util.UUID;

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
    @Autowired private TransactionRepository transactions;

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

            // ---- Payment-intent and freshness validation ----
            String nonce = instruction.getNonce();
            Long signedAt = instruction.getSignedAt();
            Long expiresAt = instruction.getExpiresAt();
            if (nonce == null || signedAt == null || expiresAt == null) {
                return IngestResult.invalid(packetHash, "invalid_payment_metadata");
            }
            try {
                if (!UUID.fromString(nonce).toString().equalsIgnoreCase(nonce)) {
                    return IngestResult.invalid(packetHash, "invalid_nonce");
                }
            } catch (IllegalArgumentException e) {
                return IngestResult.invalid(packetHash, "invalid_nonce");
            }
            if (maxAgeSeconds <= 0) {
                return IngestResult.invalid(packetHash, "invalid_server_policy");
            }

            long maxAgeMillis;
            long validityWindowMillis;
            long ageMillis;
            long nowMillis = Instant.now().toEpochMilli();
            try {
                maxAgeMillis = Math.multiplyExact(maxAgeSeconds, 1000L);
                validityWindowMillis = Math.subtractExact(expiresAt, signedAt);
                ageMillis = Math.subtractExact(nowMillis, signedAt);
            } catch (ArithmeticException e) {
                return IngestResult.invalid(packetHash, "invalid_payment_timestamps");
            }

            if (validityWindowMillis <= 0 || validityWindowMillis > maxAgeMillis) {
                return IngestResult.invalid(packetHash, "invalid_expiry_window");
            }
            if (ageMillis < -300_000L) { // five-minute clock-skew tolerance
                return IngestResult.invalid(packetHash, "future_dated");
            }
            if (expiresAt <= nowMillis) {
                return IngestResult.invalid(packetHash, "expired_packet");
            }
            if (ageMillis > maxAgeMillis) {
                log.warn("Packet {} too old ({}ms), rejected",
                        packetHash.substring(0, 12) + "...", ageMillis);
                return IngestResult.invalid(packetHash, "stale_packet");
            }

            // ---- Settle ----
            Transaction tx;
            try {
                tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            } catch (DataIntegrityViolationException e) {
                // The transaction has rolled back before this check. A committed row
                // with the same nonce or ciphertext means another delivery won.
                if (transactions.existsByNonce(nonce)
                        || transactions.existsByPacketHash(packetHash)) {
                    keepClaim = true;
                    return IngestResult.duplicate(packetHash);
                }
                throw e;
            }
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
