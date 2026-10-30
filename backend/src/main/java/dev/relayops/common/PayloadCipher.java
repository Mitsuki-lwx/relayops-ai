package dev.relayops.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PayloadCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PayloadCipher(@Value("${relayops.master-key}") String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32) throw new IllegalArgumentException("RELAYOPS_MASTER_KEY must contain 32 base64-encoded bytes");
        key = new SecretKeySpec(bytes, "AES");
    }

    public byte[] encrypt(String text, String context) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(12 + ciphertext.length).put(nonce).put(ciphertext).array();
        } catch (Exception ex) {
            throw new IllegalStateException("Payload encryption failed");
        }
    }

    public String decrypt(byte[] bytes, String context) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Payload decryption failed");
        }
    }
}
