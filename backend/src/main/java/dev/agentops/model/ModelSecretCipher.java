package dev.agentops.model;

import dev.agentops.access.SecuritySettings;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class ModelSecretCipher {
    private static final byte[] DOMAIN = "dawnwatch:model-credential:v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD = "model_provider_config:1:deepseek:deepseek-flash".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public ModelSecretCipher(SecuritySettings settings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update((byte) 0);
            this.key = new SecretKeySpec(digest.digest(settings.pepper().getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("MODEL_CREDENTIAL_CIPHER_UNAVAILABLE", error);
        }
    }

    public EncryptedSecret encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("MODEL_CREDENTIAL_REQUIRED");
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return new EncryptedSecret(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("MODEL_CREDENTIAL_ENCRYPTION_FAILED", error);
        }
    }

    public String decrypt(EncryptedSecret encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted.nonce()));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("MODEL_CREDENTIAL_DECRYPTION_FAILED", error);
        }
    }

    public record EncryptedSecret(byte[] ciphertext, byte[] nonce) {
        public EncryptedSecret {
            if (ciphertext == null || ciphertext.length < 17 || nonce == null || nonce.length != 12) {
                throw new IllegalArgumentException("INVALID_ENCRYPTED_MODEL_CREDENTIAL");
            }
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }

        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public String toString() {
            return "EncryptedSecret[ciphertextBytes=" + ciphertext.length + ",nonceBytes=" + nonce.length + "]";
        }

        @Override public boolean equals(Object other) {
            return other instanceof EncryptedSecret that
                && Arrays.equals(ciphertext, that.ciphertext)
                && Arrays.equals(nonce, that.nonce);
        }

        @Override public int hashCode() { return 31 * Arrays.hashCode(ciphertext) + Arrays.hashCode(nonce); }
    }
}
