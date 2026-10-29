package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettlementLockOrderingTest {

    private SettlementService service(AccountRepository accounts, TransactionRepository transactions) {
        SettlementService service = new SettlementService();
        ReflectionTestUtils.setField(service, "accounts", accounts);
        ReflectionTestUtils.setField(service, "transactions", transactions);
        ReflectionTestUtils.setField(service, "idempotency", mock(IdempotencyService.class));
        return service;
    }

    @Test
    void rejectsSelfTransferBeforeLockingOrWriting() {
        AccountRepository accounts = mock(AccountRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        SettlementService service = service(accounts, transactions);
        PaymentInstruction instruction = new PaymentInstruction();
        instruction.setSenderVpa("same@demo");
        instruction.setReceiverVpa("same@demo");

        assertThrows(IllegalArgumentException.class,
                () -> service.settle(instruction, "hash", "bridge", 0));
        verifyNoInteractions(accounts, transactions);
    }

    @Test
    void locksAccountsInStableLexicographicOrderRegardlessOfTransferDirection() {
        AccountRepository accounts = mock(AccountRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        Account receiver = new Account("alice@demo", "Alice", new BigDecimal("5.00"));
        Account sender = new Account("zack@demo", "Zack", new BigDecimal("20.00"));
        when(accounts.findByIdForUpdate("alice@demo")).thenReturn(Optional.of(receiver));
        when(accounts.findByIdForUpdate("zack@demo")).thenReturn(Optional.of(sender));
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentInstruction instruction = new PaymentInstruction();
        instruction.setSenderVpa("zack@demo");
        instruction.setReceiverVpa("alice@demo");
        instruction.setAmount(new BigDecimal("3.00"));
        instruction.setSignedAt(1_700_000_000_000L);

        Transaction settled = service(accounts, transactions)
                .settle(instruction, "packet-hash", "bridge", 1);

        assertNotNull(settled);
        assertEquals(Transaction.Status.SETTLED, settled.getStatus());
        assertEquals(new BigDecimal("17.00"), sender.getBalance());
        assertEquals(new BigDecimal("8.00"), receiver.getBalance());

        var order = inOrder(accounts);
        order.verify(accounts).findByIdForUpdate("alice@demo");
        order.verify(accounts).findByIdForUpdate("zack@demo");
    }
}
