-- Make the signed payment-intent nonce a durable idempotency key.
-- Existing legacy rows receive deterministic migration-only nonce values.
ALTER TABLE transactions ADD COLUMN nonce VARCHAR(128);

UPDATE transactions
SET nonce = 'legacy:' || packet_hash
WHERE nonce IS NULL;

ALTER TABLE transactions ALTER COLUMN nonce SET NOT NULL;
CREATE UNIQUE INDEX idx_transactions_nonce ON transactions(nonce);
