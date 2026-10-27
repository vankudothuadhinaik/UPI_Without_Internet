# UPI Offline Mesh — Cryptographic Payment Simulation

[![Java CI](https://github.com/vankudothuadhinaik/UPI_Without_Internet/actions/workflows/ci.yml/badge.svg)](https://github.com/vankudothuadhinaik/UPI_Without_Internet/actions/workflows/ci.yml)
[![Java 17](https://img.shields.io/badge/Java-17-blue)](https://adoptium.net/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)
![License](https://img.shields.io/badge/License-MIT-yellow)

A Java/Spring Boot prototype for exploring encrypted QR payment instructions, sender signatures, simulated store-and-forward delivery, and duplicate-ingestion handling.

> **Safety notice:** this is a research/demo prototype, not a real UPI implementation or payment service. It has no NPCI/bank settlement integration and must not process real funds or customer payments.

## Current scope

Implemented in the repository:

- Hybrid payload encryption using AES-GCM and RSA-OAEP.
- RSA-PSS sender-signature verification.
- QR-code payment demonstration.
- Server-side bridge/mesh and store-and-forward simulation.
- Transaction fingerprinting and in-process idempotency support.
- Spring Boot dashboard, API documentation, and GitHub Actions CI.

Not implemented as native device functionality:

- Android sender/receiver application.
- Bluetooth LE or Wi-Fi Direct transport.
- A production bank/NPCI settlement connection.
- Certified payment protocol, production key provisioning, or durable distributed replay protection.

## Architecture

```text
Payment instruction
  -> sign with sender key (RSA-PSS)
  -> encrypt payload (AES-GCM)
  -> protect AES key (RSA-OAEP)
  -> encode as QR / simulated offline transfer
  -> bridge store-and-forward simulation
  -> settlement gateway
       -> decrypt and authenticate
       -> verify sender and freshness
       -> claim idempotency fingerprint
       -> simulated settlement / ledger update
```

The mesh is simulated by backend components. It is not a device-to-device radio mesh.

## Technology stack

| Layer | Technology |
|---|---|
| Language/runtime | Java 17 |
| Backend | Spring Boot 3.3 |
| Cryptography | AES-GCM, RSA-OAEP, RSA-PSS, SHA-256 |
| QR | ZXing |
| UI | Thymeleaf |
| Persistence/migrations | H2 development configuration, PostgreSQL support, Flyway |
| Build and CI | Maven Wrapper, GitHub Actions |
| Optional deployment | Docker / Docker Compose |

## Quick start

### Requirements

- JDK 17 or newer.
- Maven Wrapper included in the repository.
- Docker Compose is optional.

### Run the application

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Windows:

```bat
mvnw.cmd spring-boot:run
```

When the application starts, open:

| Page | Local URL |
|---|---|
| Dashboard | http://localhost:8080 |
| QR demo | http://localhost:8080/qr-demo |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| H2 console | http://localhost:8080/h2-console |

The H2 console is for local development only; do not expose it publicly.

### Run with Docker

```bash
docker compose up --build
```

Stop the stack:

```bash
docker compose down
```

### Run tests

Linux/macOS:

```bash
./mvnw test
```

Windows:

```bat
mvnw.cmd test
```

CI runs the Java test suite on pull requests and pushes.

## API overview

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/server-key` | Retrieve the server RSA public key |
| `POST` | `/api/demo/send` | Create and inject a demo payment |
| `GET` | `/api/demo/qr` | Generate an encrypted payment QR |
| `POST` | `/api/mesh/gossip` | Simulate a mesh gossip round |
| `POST` | `/api/mesh/flush` | Flush queued bridge transactions |
| `POST` | `/api/bridge/ingest` | Submit a packet to the simulated gateway |
| `GET` | `/api/accounts` | View demo account information |
| `GET` | `/api/transactions` | View demo transaction history |

Use the application's OpenAPI/Swagger UI for request schemas and interactive exploration.

## Security and idempotency

### Cryptographic controls

- **AES-GCM** provides authenticated encryption for the payload.
- **RSA-OAEP** protects the symmetric encryption key.
- **RSA-PSS** verifies sender signatures against registered public-key material.
- A SHA-256 packet fingerprint is used for duplicate-ingestion detection.
- Freshness checks use signed timestamps and configured age/expiry limits.

These mechanisms demonstrate concepts; they are not a substitute for a reviewed protocol, secure identity lifecycle, durable nonce registry, or formal security assessment.

### Idempotency lifecycle

The active implementation change in [PR #2](https://github.com/vankudothuadhinaik/UPI_Without_Internet/pull/2) moves decryption, sender/signature validation, and freshness checks before the idempotency claim. This prevents invalid or stale packets from occupying a claim. The claim is still made immediately before settlement to retain a single winner for concurrent valid deliveries.

**Known remaining issue:** a transient failure after a successful claim can leave that claim in place until its TTL expires, delaying a legitimate retry. Safely releasing a claim requires an ownership token or compare-and-delete operation plus transaction-level regression tests. The current prototype does not claim durable, multi-instance idempotency guarantees.

## Tests and verification

The test suite covers cryptographic round trips, tampered ciphertext, duplicate and concurrent ingestion, settlement idempotency, replay/freshness checks, and sender-signature validation. Check the GitHub Actions badge for current CI status; the PR #2 validation-order change is still under review until merged.

## Project structure

```text
src/
├── main/
│   ├── java/com/demo/upimesh/
│   │   ├── config/
│   │   ├── controller/
│   │   ├── crypto/
│   │   ├── model/
│   │   └── service/
│   └── resources/
│       └── templates/
└── test/java/com/demo/upimesh/
```

The code is organized around API controllers, cryptographic components, domain models, services, and configuration.

## Limitations and roadmap

- Add ownership-token claim release and transaction-level failure/retry tests.
- Use durable database constraints or a distributed idempotency store for multi-instance deployment.
- Strengthen device identity provisioning, key rotation, and revocation.
- Add failure-injection, protocol threat-modeling, and load/concurrency tests.
- Implement native Android and offline transport only as separate, testable layers.
- Obtain independent cryptographic and security review before considering any real-world use.

## Author

**Vankudothu Adhi Naik** · [GitHub](https://github.com/vankudothuadhinaik)
