# Idempotency and recovery boundaries

## Current safety rule

Production configuration must fail closed when Redis-backed idempotency storage is unavailable. A process-local map cannot coordinate multiple application instances and must not be treated as durable duplicate-settlement protection. The development-only fallback exists for local demonstrations, not for deployment.

## What the prototype does not guarantee

- A local cache does not survive process restart.
- A cache cannot coordinate separate application instances.
- A database uniqueness constraint protects only the exact key and transaction path covered by that constraint.
- A retry after a partial settlement requires an explicit transaction/state-machine design; a cache alone is not a recovery protocol.
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
