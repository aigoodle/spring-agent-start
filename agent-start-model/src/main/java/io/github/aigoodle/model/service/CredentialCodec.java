package io.github.aigoodle.model.service;

import io.github.aigoodle.common.crypto.TextEncryptor;
import io.github.aigoodle.common.util.JsonUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Serialises a credential map to an encrypted JSON blob and back. Keeps the
 * encryption concern out of the services and entities.
 */
public class CredentialCodec {

    private final TextEncryptor encryptor;

    public CredentialCodec(TextEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    public String encode(Map<String, Object> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return null;
        }
        return encryptor.encrypt(JsonUtils.toJson(credentials));
    }

    public Map<String, Object> decode(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return new HashMap<>();
        }
        String json = encryptor.decrypt(encrypted);
        Map<String, Object> map = JsonUtils.parseMap(json);
        return map == null ? new HashMap<>() : new HashMap<>(map);
    }

    /** Mask secret-named keys for safe display. */
    public Map<String, Object> obfuscate(Map<String, Object> credentials, Iterable<String> secretKeys) {
        Map<String, Object> copy = new HashMap<>(credentials);
        for (String key : secretKeys) {
            Object v = copy.get(key);
            if (v instanceof String s && !s.isEmpty()) {
                copy.put(key, encryptor.obfuscate(s));
            }
        }
        return copy;
    }
}
