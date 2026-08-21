package io.github.aigoodle.connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "spring-agent.connector")
public class ConnectorProperties {
    private String encryptionSecret = "change-me-connector-secret";
    private Duration channelCatalogCacheTtl = Duration.ofMinutes(10);
    private boolean channelOutboxEnabled = true;
    private Duration channelOutboxPollInterval = Duration.ofSeconds(1);
    private Duration channelOutboxLeaseDuration = Duration.ofSeconds(30);
    private Duration channelInboundLeaseDuration = Duration.ofMinutes(2);
    private int channelOutboxBatchSize = 20;
    private int channelOutboxMaxAttempts = 8;
    private int channelOutboxMaxSendsPerSecond = 10;
    private boolean channelConnectionReconcileEnabled = true;
    private Duration channelConnectionReconcileInterval = Duration.ofSeconds(10);
    private Duration channelConnectionReconcileLeaseDuration = Duration.ofSeconds(30);
    private int channelConnectionReconcileBatchSize = 20;
    private boolean channelConnectionHealthEnabled = true;
    private Duration channelConnectionHealthPollInterval = Duration.ofSeconds(30);
    private Duration channelConnectionHealthRefreshInterval = Duration.ofMinutes(2);
    private Duration channelConnectionHealthLeaseDuration = Duration.ofSeconds(30);
    private int channelConnectionHealthBatchSize = 20;
    private boolean channelSlaReminderEnabled = true;
    private Duration channelSlaReminderPollInterval = Duration.ofSeconds(30);
    private Duration channelSlaReminderLeadTime = Duration.ofMinutes(5);
    private Duration channelSlaReminderLeaseDuration = Duration.ofMinutes(1);
    private int channelSlaReminderBatchSize = 100;
    public String getEncryptionSecret() { return encryptionSecret; }
    public void setEncryptionSecret(String encryptionSecret) { this.encryptionSecret = encryptionSecret; }
    public Duration getChannelCatalogCacheTtl() { return channelCatalogCacheTtl; }
    public void setChannelCatalogCacheTtl(Duration value) { this.channelCatalogCacheTtl = value; }
    public boolean isChannelOutboxEnabled() { return channelOutboxEnabled; }
    public void setChannelOutboxEnabled(boolean value) { this.channelOutboxEnabled = value; }
    public Duration getChannelOutboxPollInterval() { return channelOutboxPollInterval; }
    public void setChannelOutboxPollInterval(Duration value) { this.channelOutboxPollInterval = value; }
    public Duration getChannelOutboxLeaseDuration() { return channelOutboxLeaseDuration; }
    public void setChannelOutboxLeaseDuration(Duration value) { this.channelOutboxLeaseDuration = value; }
    public Duration getChannelInboundLeaseDuration() { return channelInboundLeaseDuration; }
    public void setChannelInboundLeaseDuration(Duration value) { this.channelInboundLeaseDuration = value; }
    public int getChannelOutboxBatchSize() { return channelOutboxBatchSize; }
    public void setChannelOutboxBatchSize(int value) { this.channelOutboxBatchSize = value; }
    public int getChannelOutboxMaxAttempts() { return channelOutboxMaxAttempts; }
    public void setChannelOutboxMaxAttempts(int value) { this.channelOutboxMaxAttempts = value; }
    public int getChannelOutboxMaxSendsPerSecond() { return channelOutboxMaxSendsPerSecond; }
    public void setChannelOutboxMaxSendsPerSecond(int value) { this.channelOutboxMaxSendsPerSecond = value; }
    public boolean isChannelConnectionReconcileEnabled() { return channelConnectionReconcileEnabled; }
    public void setChannelConnectionReconcileEnabled(boolean value) { this.channelConnectionReconcileEnabled = value; }
    public Duration getChannelConnectionReconcileInterval() { return channelConnectionReconcileInterval; }
    public void setChannelConnectionReconcileInterval(Duration value) { this.channelConnectionReconcileInterval = value; }
    public Duration getChannelConnectionReconcileLeaseDuration() { return channelConnectionReconcileLeaseDuration; }
    public void setChannelConnectionReconcileLeaseDuration(Duration value) { this.channelConnectionReconcileLeaseDuration = value; }
    public int getChannelConnectionReconcileBatchSize() { return channelConnectionReconcileBatchSize; }
    public void setChannelConnectionReconcileBatchSize(int value) { this.channelConnectionReconcileBatchSize = value; }
    public boolean isChannelConnectionHealthEnabled() { return channelConnectionHealthEnabled; }
    public void setChannelConnectionHealthEnabled(boolean value) { this.channelConnectionHealthEnabled = value; }
    public Duration getChannelConnectionHealthPollInterval() { return channelConnectionHealthPollInterval; }
    public void setChannelConnectionHealthPollInterval(Duration value) { this.channelConnectionHealthPollInterval = value; }
    public Duration getChannelConnectionHealthRefreshInterval() { return channelConnectionHealthRefreshInterval; }
    public void setChannelConnectionHealthRefreshInterval(Duration value) { this.channelConnectionHealthRefreshInterval = value; }
    public Duration getChannelConnectionHealthLeaseDuration() { return channelConnectionHealthLeaseDuration; }
    public void setChannelConnectionHealthLeaseDuration(Duration value) { this.channelConnectionHealthLeaseDuration = value; }
    public int getChannelConnectionHealthBatchSize() { return channelConnectionHealthBatchSize; }
    public void setChannelConnectionHealthBatchSize(int value) { this.channelConnectionHealthBatchSize = value; }
    public boolean isChannelSlaReminderEnabled() { return channelSlaReminderEnabled; }
    public void setChannelSlaReminderEnabled(boolean value) { this.channelSlaReminderEnabled = value; }
    public Duration getChannelSlaReminderPollInterval() { return channelSlaReminderPollInterval; }
    public void setChannelSlaReminderPollInterval(Duration value) { this.channelSlaReminderPollInterval = value; }
    public Duration getChannelSlaReminderLeadTime() { return channelSlaReminderLeadTime; }
    public void setChannelSlaReminderLeadTime(Duration value) { this.channelSlaReminderLeadTime = value; }
    public Duration getChannelSlaReminderLeaseDuration() { return channelSlaReminderLeaseDuration; }
    public void setChannelSlaReminderLeaseDuration(Duration value) { this.channelSlaReminderLeaseDuration = value; }
    public int getChannelSlaReminderBatchSize() { return channelSlaReminderBatchSize; }
    public void setChannelSlaReminderBatchSize(int value) { this.channelSlaReminderBatchSize = value; }
}
