package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Where the actual ledger update happens. Wrapped in a DB transaction so either
 * BOTH the debit and credit happen, or neither does.
 *
 * Defense-in-depth idempotency:
 *   Layer 1: IdempotencyService.claim() — fast in-memory/Redis cache check
 *   Layer 2: DB unique constraint on transaction.packetHash — final guard
 *
 * If two threads pass Layer 1 simultaneously, Layer 2 rejects the second one
 * with DataIntegrityViolationException, which we catch and return DUPLICATE.
 *
 * The @Version column on Account gives us optimistic locking — if two threads
 * somehow get past both layers and both try to debit the same account, the
 * second one fails with OptimisticLockException rather than corrupting balance.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private IdempotencyService idempotency;

    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash,
                              String bridgeNodeId, int hopCount) {

        try {
            String senderVpa = instruction.getSenderVpa();
            String receiverVpa = instruction.getReceiverVpa();
            if (senderVpa == null || receiverVpa == null || senderVpa.equals(receiverVpa)) {
                throw new IllegalArgumentException("Sender and receiver must be distinct valid VPAs");
            }

            // Lock accounts in a stable global order. Without this, opposite-direction
            // transfers (A -> B and B -> A) can each lock one row and deadlock waiting
            // for the other row.
            String firstVpa = senderVpa.compareTo(receiverVpa) < 0 ? senderVpa : receiverVpa;
            String secondVpa = senderVpa.compareTo(receiverVpa) < 0 ? receiverVpa : senderVpa;
            Account first = accounts.findByIdForUpdate(firstVpa)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown account VPA: " + firstVpa));
            Account second = accounts.findByIdForUpdate(secondVpa)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown account VPA: " + secondVpa));
            Account sender = senderVpa.equals(firstVpa) ? first : second;
            Account receiver = receiverVpa.equals(firstVpa) ? first : second;

            BigDecimal amount = instruction.getAmount();
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }

            if (sender.getBalance().compareTo(amount) < 0) {
                log.warn("Insufficient balance: {} has ₹{}, tried to send ₹{}",
                        sender.getVpa(), sender.getBalance(), amount);
                return recordRejected(instruction, packetHash, bridgeNodeId, hopCount);
            }

            sender.setBalance(sender.getBalance().subtract(amount));
            receiver.setBalance(receiver.getBalance().add(amount));
            accounts.save(sender);
            accounts.save(receiver);

            Transaction tx = new Transaction();
            tx.setPacketHash(packetHash);
            tx.setSenderVpa(instruction.getSenderVpa());
            tx.setReceiverVpa(instruction.getReceiverVpa());
            tx.setAmount(amount);
            tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
            tx.setSettledAt(Instant.now());
            tx.setBridgeNodeId(bridgeNodeId);
            tx.setHopCount(hopCount);
            tx.setStatus(Transaction.Status.SETTLED);

            // Layer 2: DB unique constraint is the final guard.
            // If another thread inserted this packetHash between our cache check and now,
            // this save() throws DataIntegrityViolationException — we catch it below.
            transactions.save(tx);

            log.info("SETTLED ₹{} from {} to {} (packetHash={}, bridge={}, hops={})",
                    amount, sender.getVpa(), receiver.getVpa(),
                    packetHash.substring(0, 12) + "...", bridgeNodeId, hopCount);

            return tx;

        } catch (DataIntegrityViolationException e) {
            // Layer 2 triggered: Another thread won the race and inserted first.
            // This is EXPECTED under high concurrency, not a bug.
            log.info("DUPLICATE_DROPPED (DB constraint) — packetHash={}", packetHash.substring(0, 12));
            return null; // Caller treats null as duplicate
        }
    }

    private Transaction recordRejected(PaymentInstruction instruction, String packetHash,
                                       String bridgeNodeId, int hopCount) {
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.REJECTED);
        return transactions.save(tx);
    }
}
