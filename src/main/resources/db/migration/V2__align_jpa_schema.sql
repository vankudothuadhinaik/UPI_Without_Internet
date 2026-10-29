-- Align the production PostgreSQL schema with the current JPA entities.
-- V1 created a legacy schema (account / transaction) that does not match
-- Account (@Table accounts) and Transaction (@Table transactions).
-- Preserve legacy rows under explicit names, then migrate compatible data.

ALTER TABLE account RENAME TO legacy_account;
ALTER TABLE "transaction" RENAME TO legacy_transaction;

CREATE TABLE accounts (
    vpa VARCHAR(255) PRIMARY KEY,
    holder_name VARCHAR(255) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    public_key_fingerprint VARCHAR(64),
    public_key_base64 VARCHAR(1024)
);

CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    packet_hash VARCHAR(64) NOT NULL UNIQUE,
    sender_vpa VARCHAR(255) NOT NULL,
    receiver_vpa VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    signed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    bridge_node_id VARCHAR(255) NOT NULL,
    hop_count INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE INDEX idx_transactions_sender_vpa ON transactions(sender_vpa);
CREATE INDEX idx_transactions_receiver_vpa ON transactions(receiver_vpa);
CREATE INDEX idx_transactions_status ON transactions(status);

INSERT INTO accounts (vpa, holder_name, balance, version)
SELECT vpa, user_id, balance, COALESCE(version, 0)
FROM legacy_account
ON CONFLICT (vpa) DO NOTHING;

-- The old schema did not record signed_at, bridge ID, or hop count.
-- Carry forward terminal rows only; legacy PENDING rows remain available in
-- legacy_transaction for explicit reconciliation instead of being mislabelled.
INSERT INTO transactions
    (id, packet_hash, sender_vpa, receiver_vpa, amount, signed_at, settled_at,
     bridge_node_id, hop_count, status)
SELECT
    old.id,
    old.packet_hash,
    sender.vpa,
    receiver.vpa,
    old.amount,
    COALESCE(old.created_at, CURRENT_TIMESTAMP),
    COALESCE(old.settled_at, old.created_at, CURRENT_TIMESTAMP),
    'legacy-migration',
    0,
    CASE WHEN UPPER(old.status) = 'SETTLED' THEN 'SETTLED' ELSE 'REJECTED' END
FROM legacy_transaction old
JOIN legacy_account sender ON sender.user_id = old.sender_id
JOIN legacy_account receiver ON receiver.user_id = old.receiver_id
WHERE UPPER(old.status) IN ('SETTLED', 'REJECTED')
ON CONFLICT (packet_hash) DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('transactions', 'id'),
    GREATEST(COALESCE((SELECT MAX(id) FROM transactions), 1), 1),
    EXISTS (SELECT 1 FROM transactions)
);
