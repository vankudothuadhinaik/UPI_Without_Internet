# Threat Model — UPI Offline Mesh

## Status and intended use

This document describes the current repository implementation; it is not a security certification. This is an educational backend simulation. **Run only in an isolated local/test environment. Do not expose it to an untrusted network or use it for real payments.**

The REST API currently has no user authentication or authorization. Demo, mesh, bridge-ingestion, account, and transaction endpoints are not protected by an authenticated user or bridge identity. The `X-Bridge-Node-Id` header is caller-supplied metadata, not proof of identity. See [security issue #15](https://github.com/vankudothuadhinaik/UPI_Without_Internet/issues/15).

## Scope

Implemented protocol mechanisms include hybrid encryption, AES-GCM integrity, signed payment instructions, timestamp/nonce validation, and duplicate-settlement controls. The server-side mesh is a simulation; native Bluetooth LE / Wi-Fi Direct transport and Android integration are not implemented.

Out of scope for the current prototype: production identity enrollment, authenticated bridge transport, hardware-backed key storage, formal protocol verification, regulatory/payment-rail integration, and production deployment hardening.

## Assets

| Asset | Impact if compromised | Current protection / limitation |
|---|---|---|
| Payment payload | Disclosure or tampering | RSA-OAEP + AES-256-GCM; server decrypts payloads |
| Sender signing key | Forged payment instructions | Device keypair is represented in application code; hardware-backed custody is not implemented |
| Registered sender public key | Account impersonation if enrollment/binding is abused | Stored against a VPA; enrollment and account ownership are not authenticated by the REST API |
| Bridge identity | Forged ingestion provenance | Not authenticated; caller-supplied `X-Bridge-Node-Id` |
| Server private key | Decryption of captured payloads | Fresh in-memory RSA keypair generated at startup; no production KMS/HSM provisioning or rotation |
| Account balances and ledger | Unauthorized reads or mutation | Transactions, constraints and locking help consistency; they do not replace API authorization |

## Threat actors

- A passive observer who can capture QR payloads or network traffic.
- An active network attacker who can alter, replay, or submit packets.
- A malicious or compromised bridge that can submit arbitrary requests and spoof bridge-node labels.
- A caller who can reach the REST API without a user account.
- A compromised sender device or signing key.
- An operator or attacker with access to server secrets or the settlement database.

## Current controls and residual risks

### 1. Payload confidentiality

**Implemented mechanism:** Hybrid encryption uses RSA-OAEP to protect an AES session key and AES-256-GCM to encrypt/authenticate the payload.

**Limitations:** The server is the decryption endpoint. Its RSA keypair is generated in memory on application startup, not provisioned through a persistent production key-management design. Forward secrecy, key escrow/recovery, and independent cryptographic review are not established. This is not end-to-end confidentiality against the gateway.

### 2. Ciphertext integrity

**Implemented mechanism:** AES-GCM authentication detects modification of ciphertext under the corresponding key.

**Limitations:** This does not authenticate the HTTP caller, bridge node, or account owner. A caller able to reach an unauthenticated endpoint can still submit requests; application-level authorization is absent.

### 3. Payment signatures and sender identity

**Implemented mechanism:** The ingestion service verifies an RSA-PSS signature using a public key looked up for the claimed sender VPA and fingerprint.

**Critical limitation:** Signature verification proves possession relative to the public key currently registered in the database; it does not prove that the key was securely enrolled by the legitimate account owner. The REST surface has no user authentication/authorization and no independently authenticated bridge identity. Therefore the repository must not claim complete sender identity assurance, authenticated account ownership, or non-repudiation.

### 4. Replay resistance

**Implemented mechanisms:** The ingestion path validates a canonical UUID nonce, signed/expiry timestamps, maximum packet age, and a small future-clock-skew allowance. Database uniqueness and the idempotency layer prevent repeated settlement of the same payment intent under tested conditions.

**Limitations:** These controls address payment-protocol replay/duplicate cases; they do not authenticate the bridge HTTP request. Clock integrity, nonce retention, database durability, and operational configuration remain assumptions. Expiry and retention policies require deployment-level review.

### 5. Duplicate settlement and concurrency

**Implemented mechanisms:** Redis owner-token claims, durable uniqueness constraints for packet hash and payment nonce, and ordered account locking/optimistic locking reduce duplicate settlement and concurrent balance-update errors. Automated tests cover selected concurrent-delivery and failure cases.

**Limitations:** Repository tests do not prove correctness under all distributed failure modes. Redis/database outages, process crashes, backup restoration, and multi-region behavior need deployment-level testing.

### 6. Bridge spoofing and Sybil attacks

**Status: not mitigated.** The bridge ingestion endpoint accepts a caller-provided `X-Bridge-Node-Id`; it does not authenticate that node. A remote caller may spoof the label if it can reach the endpoint. The mesh simulator does not establish a trust domain for independently operated bridge nodes.

**Required before shared deployment:** Individually provisioned bridge credentials or certificates, cryptographic request authentication with freshness/replay protection, revocation/rotation, authorization scopes, and audit logging. Do not use a static shared secret or trust the node-ID header as identity.

### 7. Unauthenticated API access

**Status: not mitigated.** The current REST controller exposes demo mutation, mesh mutation, bridge ingestion, account listing, and transaction listing without user authentication or role-based authorization. Demo endpoints can create simulated transactions. This is a release blocker for any shared or internet-facing environment.

### 8. Key compromise and operational security

The server keypair is generated at startup and held in process memory. Device private-key custody is not hardware-backed. Production KMS/HSM integration, rotation/revocation, backup, and recovery workflows are not implemented. A compromised server or device invalidates assumptions made by the cryptographic protocol.

## Security property summary

| Property | Current evidence | Status |
|---|---|---|
| Payload encryption | RSA-OAEP + AES-256-GCM in code | Implemented mechanism; not independently audited |
| Ciphertext tamper detection | AES-GCM authentication | Implemented mechanism; does not authenticate API caller |
| Payment signature verification | RSA-PSS checked against registered VPA key | Implemented check; secure enrollment/account ownership not established |
| Payment nonce/expiry checks | Ingestion validation and tests | Implemented checks; operational assumptions remain |
| Duplicate settlement controls | Redis claim, database uniqueness, locking and tests | Implemented controls; not a distributed-systems proof |
| User authentication | No REST authentication | **Missing — release blocker** |
| Account/transaction authorization | No role/ownership enforcement | **Missing — release blocker** |
| Bridge-node authentication | Caller-supplied node ID only | **Missing — release blocker** |
| Secure key provisioning/rotation | Startup-generated server key; no production KMS/HSM workflow | **Missing** |
| Forward secrecy | No ephemeral key agreement demonstrated | **Missing** |
| Independent security review | Not evidenced | **Pending** |

## Required release gates

Track implementation in [issue #15](https://github.com/vankudothuadhinaik/UPI_Without_Internet/issues/15). At minimum:

1. Define separate principals and least-privilege roles for account owners, demo operators, administrators, and bridge nodes.
2. Authenticate account ownership and bind registered signing keys to authenticated principals through secure enrollment/recovery.
3. Require authentication and authorization for every state-changing endpoint and restrict account/transaction reads to authorized owners or operators.
4. Give each bridge an individually revocable credential; authenticate requests cryptographically and enforce freshness, replay prevention, scopes, and rotation.
5. Disable demo-send, mesh reset, gossip, and flush endpoints outside an explicitly isolated demo profile.
6. Provision server/device keys through a documented secret-management lifecycle; define rotation, revocation, backup, and recovery.
7. Add negative tests for missing, invalid, expired, revoked, spoofed, replayed, and underprivileged credentials.
8. Run deployment-specific integration tests and obtain an independent security review before changing the deployment warning.

## What this project is not

This is a research/educational simulation, not a production UPI implementation. It does not integrate with NPCI or real bank settlement rails, implement native Bluetooth/Wi-Fi Direct transport, establish authenticated bridge identities, provide production-grade account security, or carry formal security certification. No real funds or real payment credentials should be used.
