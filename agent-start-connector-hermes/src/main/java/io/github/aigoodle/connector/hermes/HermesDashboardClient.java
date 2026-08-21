package io.github.aigoodle.connector.hermes;

import java.util.Map;

public interface HermesDashboardClient {
    Map<String, Object> platforms(String profile);
    Map<String, Object> savePlatform(String platformId, String profile, Map<String, Object> configuration);
    Map<String, Object> health();
}
