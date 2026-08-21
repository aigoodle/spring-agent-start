package io.github.aigoodle.connector.connection;

/** Host-overridable KMS boundary for tenant-isolated connector secrets. */
public interface TenantTextEncryptor {
    String encrypt(String tenantId, String plaintext);
    String decrypt(String tenantId, String ciphertext);
}
