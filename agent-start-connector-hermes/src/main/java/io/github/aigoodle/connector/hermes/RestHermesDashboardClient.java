package io.github.aigoodle.connector.hermes;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RestHermesDashboardClient implements HermesDashboardClient {
    private final RestClient client;
    private final String configuredToken;
    private volatile String discoveredToken;
    private static final Pattern SESSION_TOKEN = Pattern.compile("__HERMES_SESSION_TOKEN__=\\\"([^\\\"]+)\\\"");
    public RestHermesDashboardClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeout()); factory.setReadTimeout(properties.getTimeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory);
        configuredToken = blank(properties.getApiToken());
        client = builder.build();
    }
    @Override public Map<String, Object> platforms(String profile) {
        return invoke(() -> client.get().uri(uri -> uri.path("/api/messaging/platforms")
                .queryParamIfPresent("profile", java.util.Optional.ofNullable(blank(profile))).build()));
    }
    @Override public Map<String, Object> savePlatform(String id, String profile, Map<String, Object> configuration) {
        return invoke(() -> client.put().uri(uri -> uri.path("/api/messaging/platforms/{id}")
                        .queryParamIfPresent("profile", java.util.Optional.ofNullable(blank(profile))).build(id))
                .contentType(MediaType.APPLICATION_JSON).body(configuration));
    }
    @Override public Map<String, Object> health() {
        Map<String, Object> value = client.get().uri("/api/health").retrieve()
                .body(new ParameterizedTypeReference<>() {}); return value == null ? Map.of() : value;
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Map<String, Object> invoke(Supplier<? extends RestClient.RequestHeadersSpec<?>> request) {
        try { return execute(request); }
        catch (HttpClientErrorException.Unauthorized unauthorized) {
            if (configuredToken != null) throw unauthorized;
            discoveredToken = null;
            return execute(request);
        }
    }
    private Map<String, Object> execute(Supplier<? extends RestClient.RequestHeadersSpec<?>> request) {
        Map<String, Object> value = request.get().header("X-Hermes-Session-Token", sessionToken()).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return value == null ? Map.of() : value;
    }
    private String sessionToken() {
        if (configuredToken != null) return configuredToken;
        String current = discoveredToken;
        if (current != null) return current;
        synchronized (this) {
            if (discoveredToken != null) return discoveredToken;
            String html = client.get().uri("/").retrieve().body(String.class);
            Matcher matcher = SESSION_TOKEN.matcher(html == null ? "" : html);
            if (!matcher.find()) throw new IllegalStateException("Hermes Dashboard session token was not advertised");
            return discoveredToken = matcher.group(1);
        }
    }
}
