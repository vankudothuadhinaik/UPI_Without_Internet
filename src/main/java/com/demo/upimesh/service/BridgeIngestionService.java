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
 * Orchestrates the server-side pipeline for one inbound bridge packet.
 *
 * Validation deliberately precedes the idempotency claim: unauthenticated,
 * malformed, or stale packets must not reserve a packet hash and prevent a
 * later valid delivery from being processed.
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
        try {
            String packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- Decrypt before claiming idempotency ----
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
            if (ageSeconds < -300) {
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ---- Idempotency gate after packet validation ----
            // Keep the atomic claim immediately before settlement so concurrent
            // valid deliveries still have a single winner.
            if (!idempotency.claim(packetHash)) {
                log.info("DUPLICATE packet {} from bridge {} — dropped",
                        packetHash.substring(0, 12) + "...", bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }

            // ---- Settle ----
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            if (tx == null) {
                return IngestResult.duplicate(packetHash);
            }
            return IngestResult.settled(packetHash, tx);

        } catch (Exception e) {
            // A claim made immediately before settlement can still remain until
            // its TTL if settlement fails. Do not blindly delete it here:
            // release() is not ownership-token guarded and could remove another
            // worker's claim after a TTL/claim race.
            log.error("Ingestion error: {}", e.getMessage(), e);
            return IngestResult.invalid("?", "internal_error");
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
