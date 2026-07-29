package io.github.aigoodle.model.crypto;

import io.github.aigoodle.common.crypto.AesGcmTextEncryptor;
import io.github.aigoodle.common.crypto.TextEncryptor;
import io.github.aigoodle.model.service.CredentialCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionTest {

    private final TextEncryptor encryptor = new AesGcmTextEncryptor("unit-test-secret");

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "sk-abcdef0123456789";
        String cipher = encryptor.encrypt(plain);
        assertNotEquals(plain, cipher);
        assertTrue(cipher.startsWith("agcm:"));
        assertEquals(plain, encryptor.decrypt(cipher));
    }

    @Test
    void sameInputProducesDifferentCipherButSamePlaintext() {
        String plain = "hello";
        String c1 = encryptor.encrypt(plain);
        String c2 = encryptor.encrypt(plain);
        assertNotEquals(c1, c2, "random IV should make ciphertexts differ");
        assertEquals(encryptor.decrypt(c1), encryptor.decrypt(c2));
    }

    @Test
    void decryptToleratesPlaintextLegacyValues() {
        assertEquals("not-encrypted", encryptor.decrypt("not-encrypted"));
        assertNull(encryptor.decrypt(null));
    }

    @Test
    void credentialCodecRoundTrip() {
        CredentialCodec codec = new CredentialCodec(encryptor);
        Map<String, Object> creds = Map.of("apiKey", "sk-123", "baseUrl", "https://x", "dimensions", 1536);
        String encoded = codec.encode(creds);
        Map<String, Object> decoded = codec.decode(encoded);
        assertEquals("sk-123", decoded.get("apiKey"));
        assertEquals("https://x", decoded.get("baseUrl"));
        assertEquals(1536, decoded.get("dimensions"));
    }

    @Test
    void obfuscateMasksSecrets() {
        CredentialCodec codec = new CredentialCodec(encryptor);
        Map<String, Object> masked = codec.obfuscate(Map.of("apiKey", "sk-abcdef0123"), java.util.List.of("apiKey"));
        assertTrue(masked.get("apiKey").toString().contains("******"));
        assertFalse(masked.get("apiKey").toString().equals("sk-abcdef0123"));
    }
}
