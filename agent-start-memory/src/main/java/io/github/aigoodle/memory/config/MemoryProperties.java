package io.github.aigoodle.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("spring-agent.memory")
public class MemoryProperties {
    private int workingCapacity = 32;
    private Duration shortTermTtl = Duration.ofDays(7);
    private double longTermThreshold = 0.8;
    private double recencyWeight = 0.35;
    private double relevanceWeight = 0.45;
    private double importanceWeight = 0.20;

    public int getWorkingCapacity() { return workingCapacity; }
    public void setWorkingCapacity(int value) { workingCapacity = value; }
    public Duration getShortTermTtl() { return shortTermTtl; }
    public void setShortTermTtl(Duration value) { shortTermTtl = value; }
    public double getLongTermThreshold() { return longTermThreshold; }
    public void setLongTermThreshold(double value) { longTermThreshold = value; }
    public double getRecencyWeight() { return recencyWeight; }
    public void setRecencyWeight(double value) { recencyWeight = value; }
    public double getRelevanceWeight() { return relevanceWeight; }
    public void setRelevanceWeight(double value) { relevanceWeight = value; }
    public double getImportanceWeight() { return importanceWeight; }
    public void setImportanceWeight(double value) { importanceWeight = value; }
}
