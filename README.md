[![Java CI](https://github.com/vankudothuadhinaik/UPI_Without_Internet/actions/workflows/ci.yml/badge.svg)](https://github.com/vankudothuadhinaik/UPI_Without_Internet/actions)

# **🔐 UPI Offline Mesh — Cryptographic Offline Payments**

[![Java 17](https://img.shields.io/badge/Java-17-blue)](https://adoptium.net/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)
![License](https://img.shields.io/badge/License-MIT-yellow)

&gt; **Offline peer-to-peer payments with cryptographic settlement.**
&gt;
&gt; Two devices with zero network connectivity can exchange payment information through encrypted QR codes. When a device or bridge regains connectivity, the transaction can be forwarded to the backend settlement gateway for verification and idempotent processing.

&gt; ⚠️ **Current Status:** This repository implements the **backend settlement gateway**, **QR-code payment simulation**, and **server-side mesh/store-and-forward simulation**. Native Bluetooth LE / WiFi Direct transport and Android integration are planned future work.

---

## **🏗️ Architecture**

```text
┌────────────────────┐
│   Sender Device    │
│     Offline        │
│                    │
│ • Compose payment  │
│ • Encrypt payload  │
│ • Generate QR      │
└─────────┬──────────┘
          │
          │ QR / Transaction
          ▼
┌────────────────────┐
│   Mesh / Bridges   │
│                    │
│ • Store & forward  │
│ • Gossip delivery  │
│ • Duplicate packet │
│   handling         │
└─────────┬──────────┘
          │
          │ Connectivity restored
          ▼
┌────────────────────────────────┐
│     Spring Boot Gateway         │
│                                │
│ • Receive transaction          │
│ • Verify / decrypt payload     │
│ • Generate transaction hash    │
│ • Idempotency check            │
│ • Atomic settlement            │
│ • Update transaction ledger    │
└──────────────┬─────────────────┘
               │
               ▼
        ┌───────────────┐
        │ Account / DB  │
        │    State      │
        └───────────────┘

🚀 Live Demo

Run the application locally and open:
QR Demo: http://localhost:8080/qr-demo
The demo allows you to generate an encrypted payment QR code and follow the transaction through the simulated offline payment and settlement flow.

### **🎥 Demo Video**

[Watch the full demo on YouTube](https://youtu.be/_jcLwtK2IOU) — Flow shown: QR generation → transaction propagation → bridge ingestion → settlement

✨ Key Features

| Feature                           | Implementation                                 | Purpose                                    |
| --------------------------------- | ---------------------------------------------- | ------------------------------------------ |
| 🔐 **Hybrid Encryption**          | RSA-OAEP + AES-256-GCM                         | Protect payment payloads                   |
| 📱 **Offline QR Payments**        | ZXing QR generation                            | Exchange payment data without internet     |
| 🌐 **Store-and-Forward Mesh**     | Server-side mesh simulation                    | Model disconnected transaction propagation |
| ⚡ **Idempotent Settlement**       | SHA-256 transaction fingerprint + atomic claim | Prevent duplicate settlement               |
| 🔄 **Gossip Propagation**         | Bridge/node simulation                         | Demonstrate transaction forwarding         |
| 🛡️ **Authenticated Encryption**  | AES-GCM authentication tag                     | Detect modified ciphertext                 |
| 📊 **Transaction Dashboard**      | Spring Boot + Thymeleaf                        | Monitor transaction activity               |
| 📚 **API Documentation**          | OpenAPI / Swagger                              | Explore and test APIs                      |
| 🐳 **Docker Support**             | Docker / Docker Compose                        | Simplify local deployment                  |
| 🔄 **Database Migration Support** | Flyway                                         | Manage database schema changes             |
| 🔏 **Digital Signatures**         | RSA-PSS per-device keypairs                    | Sender authentication & non-repudiation    |
| 🤖 **CI**                         | GitHub Actions                                 | Automatically build and test changes       |   

🛠️ Tech Stack

| Layer                     | Technology                             |
| ------------------------- | -------------------------------------- |
| **Language**              | Java 17                                |
| **Backend**               | Spring Boot 3.3                        |
| **API**                   | REST APIs                              |
| **Security**              | RSA-OAEP,AES-256-GCM, RSA-PSS, SHA-256 |
| **Database**              | H2 for development, PostgreSQL support |
| **Caching / Idempotency** | ConcurrentHashMap, Redis support       |
| **QR Generation**         | ZXing                                  |
| **Frontend / Dashboard**  | Thymeleaf                              |
| **API Documentation**     | OpenAPI 3 / Swagger UI                 |
| **Database Migration**    | Flyway                                 |
| **Build Tool**            | Maven                                  |
| **Containerization**      | Docker / Docker Compose                |
| **CI**                    | GitHub Actions                         |

🚀 Quick Start

Prerequisites
Make sure you have:
• JDK 17 or higher
• Maven Wrapper included in the repository
• Docker and Docker Compose (optional)

Run Locally

Linux / macOS
./mvnw spring-boot:run

Windows
mvnw.cmd spring-boot:run

**Open in Browser**

| 🏠 Dashboard   | [http://localhost:8080](http://localhost:8080)                           |
| 📱 QR Demo     | [http://localhost:8080/qr-demo](http://localhost:8080/qr-demo)           |
| 📚 Swagger UI  | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) |
| 🗄️ H2 Console | [http://localhost:8080/h2-console](http://localhost:8080/h2-console)     |

Run with Docker

docker-compose up --build

To stop the containers: 
docker-compose down

📡 API Endpoints

| Method | Endpoint             | Description                                    |
| ------ | -------------------- | ---------------------------------------------- |
| `GET`  | `/api/server-key`    | Retrieve the server RSA public key             |
| `POST` | `/api/demo/send`     | Create and inject a demo payment               |
| `GET`  | `/api/demo/qr`       | Generate an encrypted payment QR               |
| `POST` | `/api/mesh/gossip`   | Execute a mesh gossip propagation round        |
| `POST` | `/api/mesh/flush`    | Flush queued bridge transactions               |
| `POST` | `/api/bridge/ingest` | Submit a transaction to the settlement gateway |
| `GET`  | `/api/accounts`      | Retrieve account information                   |
| `GET`  | `/api/transactions`  | Retrieve transaction history                   |

🔐 Security Model

**1. Hybrid Encryption**
The payment payload uses a hybrid encryption approach:

Payment Payload
      │
      ▼
AES-256-GCM
      │
      ▼
Encrypted Payload
      │
      +
      │
RSA-OAEP
      │
      ▼
Protected AES Key

AES-256-GCM provides:

• Confidentiality
• Authenticated encryption
• Tamper detection
RSA-OAEP is used for protecting the symmetric encryption key rather than encrypting the entire payment payload directly. This combines the efficiency of symmetric encryption with asymmetric key protection.

**2. Transaction Fingerprinting**
Transactions use a SHA-256 based fingerprint for identifying duplicate transaction submissions.

Transaction Data
       │
       ▼
    SHA-256
       │
       ▼
Transaction Fingerprint

The fingerprint is used during the idempotency/duplicate-processing flow.

**3. Idempotent Settlement**
The system is designed to prevent the same transaction from being settled multiple times when duplicate bridge deliveries occur.

Bridge A ─────┐
              │
Bridge B ─────┼────► Settlement Gateway
              │
Bridge C ─────┘
                       │
                       ▼
                 Idempotency Check
                       │
              ┌────────┴────────┐
              ▼                 ▼
          First Claim        Duplicate
              │                 │
              ▼                 ▼
           Settle             Reject

This is particularly important in a store-and-forward system where the same transaction may reach the settlement gateway through multiple paths.

**4. Transaction Freshness & Replay Defense**

Each `PaymentInstruction` contains:
- **`nonce`** — a UUID unique to the payment intent
- **`signedAt`** — epoch millis when the sender signed
- **`expiresAt`** — epoch millis when the payment expires (default 24h)

The settlement gateway rejects packets where:
- `age` exceeds `maxAgeSeconds` (configurable, default 86400)
- `age` is more than 5 minutes in the future (clock-skew tolerance)
- `nonce` has already been seen (idempotency)

This provides explicit replay-attack protection within the configured time window.

**5. Hardware Security Roadmap**
Future Android integration can use hardware-backed key storage such as:

• Android StrongBox
• Trusted Execution Environment (TEE)

This is planned future work, not part of the current backend implementation.

### **6. Digital Signatures (Sender Authentication)**

Each device generates its own 2048-bit RSA keypair. The private key never leaves the device. The public key is registered with the settlement gateway and linked to the device's VPA.

```text
Payment Instruction (canonical)
       │
       ▼
   RSA-PSS Sign
   (device private key)
       │
       ▼
   Signature
       │
       +────► Encrypted into packet
       │
       ▼
Settlement Gateway
       │
       ▼
   Decrypt packet
       │
       ▼
   Look up sender's public key by fingerprint
       │
       ▼
   Verify RSA-PSS signature
       │
   ┌───┴───┐
   ▼       ▼
  VALID   INVALID
   │         │
   ▼         ▼
 Settle    Reject

## **🔄 Payment Flow**

             CREATE PAYMENT
                    │
                    ▼
          Build Transaction
                    │
                    ▼
          Sign with Device Key
             RSA-PSS
                    │
                    ▼
          Encrypt Payload
          AES-256-GCM
                    │
                    ▼
        Protect Encryption Key
             RSA-OAEP
                    │
                    ▼
             Generate QR
                    │
                    ▼
          OFFLINE EXCHANGE
                    │
                    ▼
          Store / Forward
                    │
                    ▼
            Bridge Node
                    │
                    │ Internet Available
                    ▼
          Settlement Gateway
                    │
                    ▼
               Decrypt
                    │
                    ▼
          Verify Signature
         (sender's public key)
                    │
              ┌────┴────┐
              ▼         ▼
           VALID     INVALID
              │         │
              ▼         ▼
         Check Fresh   Reject
         (nonce/expiry)
              │
              ▼
         Idempotency
              │
       ┌──────┴──────┐
       ▼             ▼
   Duplicate      New TX
       │             │
       ▼             ▼
    Reject       Atomic Claim
                      │
                      ▼
                 Debit / Credit
                      │
                      ▼
                    SETTLED

🌐 **Mesh / Store-and-Forward Model**

The current project simulates the behavior of disconnected bridge nodes on the backend.
A simplified propagation flow is:

Node A
  │
  ▼
Node B
  │
  ├────────► Node C
  │
  ▼
Node D
  │
  │ Internet restored
  ▼
Settlement Gateway

A bridge can temporarily retain a transaction and forward it when connectivity becomes available.
**Important scope:** The current repository does not implement native Bluetooth LE or WiFi Direct communication. Instead, the backend models the transaction propagation and settlement behavior that such a transport layer could use.

🧪 **Testing**
Run the test suite with:

Linux / macOS
./mvnw test

Windows
mvnw.cmd test

Current tests cover areas including:

• Encryption/decryption round-trip
• Tampered ciphertext rejection
• Duplicate transaction handling
• Concurrent duplicate ingestion
• Idempotent settlement behavior
• Replay attack defense (same packet submitted twice)
• Digital signature verification (valid signature, forged signature, duplicate)

Concurrency Scenario
The project includes a concurrency test that simulates multiple delivery attempts for the same transaction.

             Same Transaction
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
     Thread 1    Thread 2    Thread 3
        │           │           │
        └───────────┼───────────┘
                    ▼
             Idempotency Check
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
       SETTLED             DUPLICATE

The purpose is to verify that concurrent duplicate delivery does not result in multiple settlements.

🔎 **Observability**

The application includes a web dashboard for viewing transaction activity during development and demonstrations.
The dashboard helps visualize:

• Accounts
• Transactions
• Settlement status
• Transaction flow

This is intended primarily for development, testing, and demonstration.

⚠️ **Limitations**
This project is a research/educational prototype and is not a production UPI implementation.
Current limitations include:

• No direct integration with NPCI or real bank settlement rails
• No real bank accounts or monetary transfers
• Native Bluetooth LE transport is not implemented
• Native WiFi Direct transport is not implemented
• Mesh networking is currently simulated server-side
• H2 is used for local development
• Identity and key provisioning are simplified
• Android hardware-backed key storage is not yet integrated
• The cryptographic protocol has not undergone formal security certification
• Production-grade deployment infrastructure is outside the current scope

These limitations are intentional and define the current prototype boundary.

🔮 **Future Work**
Planned improvements include:

📱 Native Mobile Layer

• Android sender/receiver application
• Bluetooth LE communication
• WiFi Direct communication
• Offline device discovery

### Idempotency store availability

The single-process development profile explicitly permits a local in-memory fallback when Redis is unavailable. The production profile disables this fallback: if Redis cannot claim a packet hash, ingestion fails closed instead of pretending a process-local map can prevent duplicate settlement across multiple application instances. This is a safety property of the simulation, not a claim of production payment readiness.

### **🔐 Security**
- ~~Digital signatures for sender authentication~~ ✅ Implemented
- Stronger device identity provisioning (certificate chains, attestation)

🌐 Distributed System

• More realistic mesh simulation
• Network failure simulation
• Multi-hop routing
• Duplicate propagation analysis
• Malicious-node / Byzantine behavior handling

🗄️ Persistence

• Production PostgreSQL deployment
• Strong database-level transaction constraints
• Durable transaction ledger
• Improved recovery mechanisms

📊 Performance

• Concurrent settlement benchmarks
• Encryption performance measurements
• Load testing
• Mesh propagation latency analysis

🔬 Research

• Formal protocol specification
• Security threat modeling
• Protocol verification
• Failure-mode analysis

🧩 **Project Structure**

src/
├── main/
│   ├── java/
│   │   └── com/demo/upimesh/
│   │       ├── config/
│   │       ├── controller/
│   │       ├── crypto/
│   │       ├── model/
│   │       ├── service/
│   │       └── ...
│   │
│   └── resources/
│       ├── templates/
│       └── application.properties
│
└── test/
    └── java/
        └── com/demo/upimesh/

The project separates API controllers, cryptographic components, domain models, services, and configuration to keep the backend modular.

📋 **Example Transaction Lifecycle**

• Sender creates payment
• Payment payload is encrypted
• Encrypted payload is encoded into QR
• Transaction can be exchanged offline
• Bridge/store-and-forward layer retains transaction
• Connectivity becomes available
• Bridge submits transaction to gateway
• Gateway decrypts and validates transaction
• Transaction fingerprint is checked
• Duplicate transactions are rejected
• Valid transaction is settled
• Transaction is recorded in the ledger


## **👤 Author**

**Vankudothu Adhi Naik**
GitHub: [vankudothuadhinaik](https://github.com/vankudothuadhinaik)













