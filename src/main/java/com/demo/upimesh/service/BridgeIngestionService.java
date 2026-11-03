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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        String claimToken = null;
        try {
            String packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- Idempotency gate ----
            claimToken = idempotency.claim(packetHash);
            if (claimToken == null) {
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

            PublicKey senderPubKey = identity.getPublicKeyForVpa(
                    instruction.getSenderVpa(), fingerprint);
            if (senderPubKey == null) {
                log.warn("Sender key is not registered to claimed VPA {}", instruction.getSenderVpa());
                return IngestResult.invalid(packetHash, "sender_key_mismatch");
            }

            boolean signatureValid = sigService.verify(
                    instruction.canonicalBytes(), instruction.getSignature(), senderPubKey);
            if (!signatureValid) {
                log.warn("Invalid signature for packet {} from sender {}",
                        packetHash.substring(0, 12) + "...", instruction.getSenderVpa());
                return IngestResult.invalid(packetHash, "signature_verification_failed");
            }

            // The signed nonce is stable across randomized re-encryption. Require
            // its canonical UUID representation before using it as a ledger key.
            String nonce = instruction.getNonce();
            try {
                if (nonce == null || !UUID.fromString(nonce).toString().equalsIgnoreCase(nonce)) {
                    return IngestResult.invalid(packetHash, "invalid_nonce");
                }
            } catch (IllegalArgumentException e) {
                return IngestResult.invalid(packetHash, "invalid_nonce");
            }
            // Reject amounts that the two-decimal ledger cannot represent exactly.
            if (!hasValidLedgerAmount(instruction.getAmount())) {
                return IngestResult.invalid(packetHash, "invalid_amount");
            }

            // ---- Freshness and expiry checks (replay protection) ----
            Long signedAt = instruction.getSignedAt();
            Long expiresAt = instruction.getExpiresAt();
            long nowMillis = Instant.now().toEpochMilli();
            if (signedAt == null || expiresAt == null || expiresAt <= signedAt) {
                return IngestResult.invalid(packetHash, "invalid_packet_timestamps");
            }
            if (expiresAt <= nowMillis) {
                return IngestResult.invalid(packetHash, "expired_packet");
            }

            long ageSeconds = (nowMillis - signedAt) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, 12) + "...", ageSeconds);
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ---- Settle ----
            Transaction tx;
            try {
                tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            } catch (DataIntegrityViolationException e) {
                // The failed settlement transaction has rolled back before this
                // query. A committed row with the same nonce/ciphertext won the race.
                if (transactions.existsByNonce(nonce)
                        || transactions.existsByPacketHash(packetHash)) {
                    keepClaim = true;
                    return IngestResult.duplicate(packetHash);
                }
                throw e;
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
                idempotency.release(claimedHash, claimToken);
            }
        }
    }

    private static boolean hasValidLedgerAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return false;
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).precision() <= 19;
        } catch (ArithmeticException e) {
            return false;
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
