package com.demo.upimesh.service;

import com.demo.upimesh.crypto.DigitalSignatureService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages device identity: keypair generation, public-key registration,
 * and lookup by fingerprint.
 *
 * In a production system this would be a dedicated identity service with
 * certificate chains. For the prototype, we store public keys in the
 * Account table (one key per VPA for simplicity).
 */
@Service
public class DeviceIdentityService {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityService.class);

    @Autowired private AccountRepository accounts;
    @Autowired private DigitalSignatureService sigService;

    // In-memory cache of decoded public keys to avoid repeated base64 decoding
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();

    /**
     * Generate a new device keypair and register the public key against a VPA.
     * Returns the keypair (caller keeps the private key securely).
     */
    public KeyPair registerDevice(String vpa) throws Exception {
        KeyPair kp = sigService.generateKeyPair();
        String fingerprint = sigService.fingerprint(kp.getPublic());
        String pubKeyBase64 = sigService.encodePublicKey(kp.getPublic());

        Account account = accounts.findById(vpa).orElseThrow(() ->
                new IllegalArgumentException("Unknown VPA: " + vpa));

        String previousFingerprint = account.getPublicKeyFingerprint();
        account.setPublicKeyFingerprint(fingerprint);
        account.setPublicKeyBase64(pubKeyBase64);
        accounts.save(account);

        if (previousFingerprint != null && !previousFingerprint.equals(fingerprint)) {
            keyCache.remove(previousFingerprint);
        }
        keyCache.put(fingerprint, kp.getPublic());

        log.info("Registered device key for {} (fingerprint={}...)", vpa, fingerprint.substring(0, 16));
        return kp;
    }

    /**
     * Look up a public key by its fingerprint.
     * Uses in-memory cache, falls back to DB.
     */
    public PublicKey getPublicKey(String fingerprint) throws Exception {
        // Check current registration before consulting the decoded-key cache so
        // device key rotation/revocation takes effect immediately.
        Account account = accounts.findByPublicKeyFingerprint(fingerprint);
        if (account == null || account.getPublicKeyBase64() == null) {
            keyCache.remove(fingerprint);
            return null;
        }

        PublicKey cached = keyCache.get(fingerprint);
        if (cached != null) return cached;

        PublicKey pk = sigService.decodePublicKey(account.getPublicKeyBase64());
        keyCache.put(fingerprint, pk);
        return pk;
    }

    /**
     * Resolve a signing key only when it is currently registered to the claimed
     * sender VPA. A valid signature from some other registered account must not
     * authorize a payment that names this VPA as its sender.
     */
    public PublicKey getPublicKeyForVpa(String vpa, String fingerprint) throws Exception {
        if (vpa == null || vpa.isBlank() || fingerprint == null || fingerprint.isBlank()) {
            return null;
        }

        Account account = accounts.findById(vpa).orElse(null);
        if (account == null || !fingerprint.equals(account.getPublicKeyFingerprint())) {
            return null;
        }

        PublicKey cached = keyCache.get(fingerprint);
        if (cached != null) {
            return cached;
        }

        String encoded = account.getPublicKeyBase64();
        if (encoded == null || encoded.isBlank()) {
            return null;
        }

        PublicKey publicKey = sigService.decodePublicKey(encoded);
        if (!fingerprint.equals(sigService.fingerprint(publicKey))) {
            return null;
        }

        PublicKey existing = keyCache.putIfAbsent(fingerprint, publicKey);
        return existing == null ? publicKey : existing;
    }

    /**
     * Check whether a device is registered for a VPA.
     */
    public boolean isRegistered(String vpa) {
        return accounts.findById(vpa)
                .map(a -> a.getPublicKeyFingerprint() != null)
                .orElse(false);
    }
}