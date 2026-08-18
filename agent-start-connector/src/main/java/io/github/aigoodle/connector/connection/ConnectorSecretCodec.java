package io.github.aigoodle.connector.connection;

import io.github.aigoodle.common.crypto.TextEncryptor;
import io.github.aigoodle.common.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConnectorSecretCodec {
    private final TextEncryptor encryptor;
    public ConnectorSecretCodec(TextEncryptor encryptor) { this.encryptor = encryptor; }
    public String encode(Map<String, Object> values) {
        return values == null || values.isEmpty() ? null : encryptor.encrypt(JsonUtils.toJson(values));
    }
    public Map<String, Object> decode(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return new LinkedHashMap<>();
        Map<String, Object> value = JsonUtils.parseMap(encryptor.decrypt(encrypted));
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
}
