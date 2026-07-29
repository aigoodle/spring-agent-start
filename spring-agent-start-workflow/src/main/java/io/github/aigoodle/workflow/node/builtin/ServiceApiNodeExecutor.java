package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls an internal service endpoint. Same wire protocol as
 * {@link HttpRequestNodeExecutor} but without auth or body configuration —
 * intended for trusted intra-cluster APIs where auth is provided out-of-band
 * (network policy, sidecar mTLS, gateway JWT).
 *
 * <p>Config produced by the designer's "服务接口" card:
 * <ul>
 *   <li>{@code method} — HTTP verb (default {@code GET}).</li>
 *   <li>{@code url} — full or relative URL, {@code {{#var#}}} templated.</li>
 *   <li>{@code headers} — {@code List<{name, value}>}, templated per entry.
 *       Empty {@code name} entries are skipped so the designer's blank row
 *       placeholder doesn't send a garbage header.</li>
 *   <li>{@code parameters} — {@code List<{name, value}>}, appended as the
 *       query string. Same skip-empty rule.</li>
 *   <li>{@code timeoutSeconds} — request timeout (default 30).</li>
 * </ul>
 *
 * <p>Outputs: {@code status}, {@code body}.
 */
public class ServiceApiNodeExecutor implements NodeExecutor {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Base URL prepended to a node URL that isn't absolute. Empty string
     * disables the feature. Trailing slashes are stripped so we can join with
     * a leading slash cleanly. Shared configuration with the HTTP node — both
     * read {@code agent-boot.http-base-url}, since 服务接口 is by definition
     * the intra-cluster half of the same story.
     */
    private final String baseUrl;

    public ServiceApiNodeExecutor() {
        this("");
    }

    public ServiceApiNodeExecutor(String baseUrl) {
        this.baseUrl = normaliseBase(baseUrl);
    }

    private static String normaliseBase(String v) {
        if (v == null) return "";
        String s = v.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public NodeType type() {
        return NodeType.SERVICE_API;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        String method = node.getString("method", "GET").toUpperCase();
        String rawUrl = VariableResolver.render(node.getString("url", ""), ctx.getPool());
        if (rawUrl == null || rawUrl.isBlank()) {
            return NodeResult.failure("Service URL is empty");
        }
        String resolvedUrl = resolveAgainstBase(rawUrl);
        if (resolvedUrl == null) {
            return NodeResult.failure("Service URL '" + rawUrl
                    + "' is not absolute and no agent-boot.http-base-url is configured");
        }
        int timeout = node.getInt("timeoutSeconds", 30);

        String query = buildQueryString(node.getMapList("parameters"), ctx);
        String url = query.isEmpty()
                ? resolvedUrl
                : resolvedUrl + (resolvedUrl.contains("?") ? "&" : "?") + query;

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout));
        } catch (IllegalArgumentException e) {
            return NodeResult.failure("Invalid service URL: " + url);
        }

        for (Map<String, Object> entry : node.getMapList("headers")) {
            String name = stringOrNull(entry.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = VariableResolver.render(stringOrNull(entry.get("value")), ctx.getPool());
            builder.header(name.trim(), value == null ? "" : value);
        }

        builder.method(method, HttpRequest.BodyPublishers.noBody());

        try {
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return NodeResult.empty()
                    .output("status", resp.statusCode())
                    .output("body", resp.body());
        } catch (Exception e) {
            return NodeResult.failure("Service call to " + url + " failed: " + e.getMessage());
        }
    }

    /**
     * Same rule as {@code HttpRequestNodeExecutor.resolveAgainstBase}: absolute
     * URLs pass through, relative URLs get prefixed with {@link #baseUrl};
     * returns {@code null} when a relative URL is used but no base is
     * configured so the caller can surface a clear config error.
     */
    private String resolveAgainstBase(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return url;
        }
        if (baseUrl.isEmpty()) {
            return null;
        }
        String tail = url.startsWith("/") ? url.substring(1) : url;
        return baseUrl + "/" + tail;
    }

    private String buildQueryString(List<Map<String, Object>> params, ExecutionContext ctx) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>(params.size());
        for (Map<String, Object> entry : params) {
            String name = stringOrNull(entry.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String rendered = VariableResolver.render(stringOrNull(entry.get("value")), ctx.getPool());
            String value = rendered == null ? "" : rendered;
            pairs.add(URLEncoder.encode(name.trim(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return String.join("&", pairs);
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
