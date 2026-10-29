# Idempotency and recovery boundaries

## Current safety rule

Production configuration must fail closed when Redis-backed idempotency storage is unavailable. A process-local map cannot coordinate multiple application instances and must not be treated as durable duplicate-settlement protection. The development-only fallback exists for local demonstrations, not for deployment.

The ingestion path claims the packet hash before decrypting and validating it. Claims for invalid packets are released so a malformed packet cannot reserve a hash for the full Redis TTL. A successful settlement is recorded in the database transaction, and the unique transactions.packet_hash constraint remains the durable duplicate guard. A database duplicate remains a duplicate even if the Redis key has expired.

## Persistence schema

V1__init.sql created a legacy schema whose table and column names did not match the JPA entities used by the application. V2__align_jpa_schema.sql renames those original tables to legacy_account and legacy_transaction, creates the current accounts and transactions schema, and copies compatible account and terminal transaction records.

Legacy PENDING rows are intentionally retained in legacy_transaction for explicit reconciliation; the migration does not silently relabel them as terminal outcomes. The old schema did not store original signing time, bridge ID, or hop count, so migrated terminal records use documented placeholder values. Review the legacy tables before any real deployment.

## What the prototype does not guarantee

- A Redis key does not survive its TTL and is not the authoritative ledger.
- A cache cannot coordinate separate application instances when unavailable.
- A database uniqueness constraint protects only the exact key and transaction path covered by that constraint.
- A retry after an ambiguous external settlement requires an explicit transaction/state-machine design; a cache alone is not a recovery protocol.
- A successful unit test is not proof of exactly-once behavior under every crash point.

## Recovery design required before any production claim

1. Assign a stable idempotency key to each payment intent and persist its request hash.
2. Store a durable transaction state such as RECEIVED, VALIDATED, SETTLEMENT_PENDING, SETTLED, or FAILED.
3. Enforce uniqueness for the idempotency key in the database, not only in cache.
4. Apply debit, credit, and terminal transaction state in one database transaction where possible.
5. For external settlement, use an outbox/inbox or reconciliation workflow; never assume a network timeout means settlement failed.
6. On retry, return the recorded terminal result for the same key and reject a different request body reusing that key.
7. Test crashes before commit, after commit but before response, duplicate concurrent requests, Redis outage, and process restart.
8. Add an operator-visible reconciliation report for pending/ambiguous transactions.

## Validation commands

Run the repository's Maven test suite and the Redis-backed integration tests in the same configuration intended for deployment. A passing local-only test suite is not a substitute for validating the persistent store and transaction boundaries.

This repository is an educational simulation and is not connected to NPCI or real bank settlement rails. Do not use it to move real funds.
